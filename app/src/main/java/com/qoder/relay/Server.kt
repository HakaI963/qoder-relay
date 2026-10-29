package com.qoder.relay

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class RelayServer(
    private val app: RelayApp,
    private val engine: RelayEngine,
    port: Int
) : NanoHTTPD(port) {

    companion object {
        const val PORT_DEFAULT = 8788
        private const val MAX_BODY = 32L * 1024 * 1024
    }

    private val pendingLogins = ConcurrentHashMap<String, QoderClient.LoginState>()
    private val pendingLoginStatus = ConcurrentHashMap<String, String>()

    private fun qp(session: IHTTPSession, key: String): String? =
        session.parameters[key]?.firstOrNull()

    private fun IHTTPSession.isLoopback(): Boolean {
        val ip = remoteIpAddress ?: return false
        return ip == "127.0.0.1" || ip == "::1" || ip.startsWith("127.") || ip.startsWith("::ffff:127.")
    }

    /**
     * Unauthenticated bootstrap for the dashboard: on-device (loopback) callers get the relay key
     * so the UI works with zero typing. LAN callers must paste the key once — it is never leaked
     * over the network by this route.
     */
    private fun boot(session: IHTTPSession): Response {
        val loop = session.isLoopback()
        return jsonResponse(JSONObject()
            .put("loopback", loop)
            .put("key", if (loop) app.relayKey else "")
            .put("port", app.port)
            .put("lan", app.lanAddress())
            .put("version", RelayApp.VERSION)
            .put("running", app.isRunning())
            .toString())
    }

    // ---------------------------------------------------------------- routing

    override fun serve(session: IHTTPSession): Response {
        val raw = session.uri
        val uri = if (raw == "/" || raw.isEmpty()) "/" else raw.trimEnd('/')
        val method = session.method
        if (method == Method.OPTIONS) return cors(emptyResponse(Status.NO_CONTENT))
        return try {
            when {
                uri == "/favicon.ico" -> textResponse("", Status.NO_CONTENT)
                uri == "/" || uri == "/ui" || uri == "/dashboard" -> dashboard()
                uri == "/health" -> jsonResponse(JSONObject()
                    .put("ok", true).put("running", app.isRunning())
                    .put("port", app.port).put("version", RelayApp.VERSION).toString())
                uri == "/cosy/sign.html" -> assetResponse("cosy/sign.html", "text/html; charset=utf-8")
                uri == "/cosy/auth_glue.js" -> assetResponse("cosy/auth_glue.js", "application/javascript")
                uri == "/cosy/auth.wasm" -> assetResponse("cosy/auth.wasm", "application/wasm")
                uri == "/relay/boot" && method == Method.GET -> boot(session)
                method == Method.GET && uri == "/v1/models" -> authed(session) { modelsCatalog() }
                method == Method.POST && uri == "/v1/chat/completions" -> authed(session) { chatCompletions(session) }
                uri.startsWith("/relay/") -> authed(session) { admin(session, uri, method) }
                else -> errorJson(Status.NOT_FOUND, "not_found", "unknown route $uri")
            }
        } catch (e: Exception) {
            errorJson(Status.INTERNAL_ERROR, "relay_error", describe(e))
        }
    }

    // ------------------------------------------------------------------- auth

    /**
     * Loopback is the device's own console: it is already inside the app's sandbox, so it is
     * trusted and never needs a key typed in. Anything arriving over the LAN still must present
     * the relay key.
     */
    private fun keyOk(session: IHTTPSession): Boolean {
        if (session.isLoopback()) return true
        val hdr = session.headers["authorization"]?.removePrefix("Bearer ")?.trim()
            ?: session.headers["x-relay-key"]?.trim()
            ?: qp(session, "key")
        return hdr == app.relayKey
    }

    private fun authed(session: IHTTPSession, block: () -> Response): Response {
        if (!keyOk(session)) return errorJson(Status.UNAUTHORIZED, "invalid_api_key", "bad relay key")
        return block()
    }

    private fun dashboard(): Response {
        val bytes = try {
            app.assets.open("dashboard.html").readBytes()
        } catch (_: Exception) {
            return errorJson(Status.NOT_FOUND, "no_dashboard", "dashboard.html missing from assets")
        }
        val r = newFixedLengthResponse(Status.OK, "text/html; charset=utf-8",
            ByteArrayInputStream(bytes), bytes.size.toLong())
        r.addHeader("Cache-Control", "no-cache")
        return r
    }

    private fun assetResponse(path: String, mime: String): Response {
        val bytes = app.assets.open(path).readBytes()
        return newFixedLengthResponse(Status.OK, mime, ByteArrayInputStream(bytes), bytes.size.toLong())
    }

    // ----------------------------------------------------------------- models

    private fun modelsCatalog(): Response {
        val arr = JSONArray()
        val seen = HashSet<String>()
        for (m in engine.modelCatalog()) {
            val id = m.optString("key")
            if (id.isEmpty() || !seen.add(id)) continue
            arr.put(JSONObject().put("id", id).put("object", "model")
                .put("display_name", m.optString("display_name", id))
                .put("owned_by", "qoder-" + (m.optJSONArray("served_by")?.let { if (it.length() > 0) it.getString(0) else "pool" } ?: "pool")))
        }
        if (arr.length() == 0) {
            arr.put(JSONObject().put("id", "qfmodel").put("object", "model").put("owned_by", "qoder"))
            arr.put(JSONObject().put("id", "dfmodel").put("object", "model").put("owned_by", "qoder"))
        }
        return jsonResponse(JSONObject().put("object", "list").put("data", arr).toString())
    }

    // ------------------------------------------------------------------- chat

    private class Opened(val resp: okhttp3.Response, val acc: Account, val reqId: String, val t0: Long)

    private fun chatCompletions(session: IHTTPSession): Response {
        val raw = readBody(session)
        if (raw.isEmpty()) return errorJson(Status.BAD_REQUEST, "empty_body", "expected a JSON body")
        val openai = try {
            JSONObject(raw)
        } catch (e: Exception) {
            return errorJson(Status.BAD_REQUEST, "invalid_json", describe(e))
        }
        val requestedModel = openai.optString("model").ifEmpty { null }
        val pinned = openai.optString("relay_account").ifEmpty { qp(session, "account") ?: "" }.ifEmpty { null }
        val (cands, resolved) = engine.candidates(requestedModel, pinned)
        if (cands.isEmpty()) {
            val why = if (app.repo.accounts.isEmpty()) "no accounts configured"
            else if (app.repo.accounts.none { it.enabled }) "all accounts are disabled"
            else if (app.repo.accounts.any { it.cooldownUntil > System.currentTimeMillis() })
                "all matching accounts are cooling down after recent errors"
            else "no enabled Qoder account serves model ${requestedModel ?: "any"}"
            return errorJson(Status.FORBIDDEN, "no_accounts", why)
        }

        if (!openai.optBoolean("stream", true)) return blockingCompletion(openai, cands, resolved)

        // Headers go out immediately; the answer is produced on a worker thread behind the pipe.
        val pipe = SsePipe()
        app.inFlight.incrementAndGet()
        val job = Thread {
            try {
                streamCompletion(openai, cands, resolved, pipe)
            } catch (e: Exception) {
                streamError(pipe, resolved, describe(e))
            } finally {
                app.inFlight.decrementAndGet()
            }
        }.apply { isDaemon = true; name = "relay-stream" }
        job.start()
        return SseResponse(pipe, linkedMapOf(
            "X-Relay-Model" to resolved,
            "X-Relay-Pool" to cands.size.toString()
        ))
    }

    /**
     * Streams the answer into [pipe], failing over accounts until one produces chunks.
     * Because the client already has 200 + SSE headers, no client-side header timeout can fire —
     * the heartbeat keeps the stream warm while the pool is being walked.
     */
    private fun streamCompletion(openai: JSONObject, cands: List<Account>, model: String, pipe: SsePipe) {
        val heartbeat = Heartbeat(pipe, engine.heartbeatMs)
        heartbeat.start()
        val overallDeadline = System.currentTimeMillis() + engine.overallMs
        try {
            var remaining = cands
            var lastErr = "no candidates"
            while (remaining.isNotEmpty() && !pipe.aborted) {
                val (opened, err) = openFirstAvailable(openai, remaining, model, overallDeadline) { msg ->
                    pipe.pushComment("relay failover: " + msg.replace("\n", " ").take(160))
                }
                if (opened == null) { lastErr = err; break }
                val delivered = streamOne(opened, model, pipe)
                if (delivered > 0 || pipe.aborted) return
                lastErr = opened.acc.lastError.ifEmpty { "upstream closed before first chunk" }
                remaining = remaining.filter { it.id != opened.acc.id }
            }
            if (!pipe.aborted) streamError(pipe, model, lastErr)
        } finally {
            heartbeat.stop()
        }
    }

    /** Walks the candidate pool opening the first account that answers. */
    private fun openFirstAvailable(
        openai: JSONObject,
        cands: List<Account>,
        model: String,
        deadline: Long,
        note: (String) -> Unit
    ): Pair<Opened?, String> {
        var lastErr = "no accounts"
        for ((i, acc) in cands.withIndex()) {
            if (System.currentTimeMillis() > deadline) {
                lastErr = "relay request budget exhausted"
                break
            }
            if (!engine.ensureToken(acc)) {
                lastErr = "token refresh failed for ${acc.label}"
                acc.failovers++; note(lastErr)
                continue
            }
            val reqId = UUID.randomUUID().toString()
            val sessionId = openai.optString("relay_session_id").ifEmpty { reqId }
            val wire = engine.buildWire(model, openai, reqId, sessionId)
            val t0 = System.currentTimeMillis()
            engine.beginActive(reqId, acc.label, model, i + 1)
            val (resp, err) = openUpstream(acc, wire, model)
            if (resp != null) {
                acc.reqs++; acc.status = "active"; acc.lastError = ""; acc.cooldownUntil = 0
                return Opened(resp, acc, reqId, t0) to ""
            }
            engine.endActive(reqId, "failover")
            engine.penalize(acc, err, httpCode(err))
            acc.failovers++
            engine.logEvent(event(reqId, acc, model, httpCode(err), "failover", 0, 0, t0, err))
            app.repo.save()
            note(err)
            lastErr = "${acc.label}: $err"
        }
        return null to lastErr
    }

    /** Open the signed inference request once, refreshing auth on 401/403 before retrying. */
    private fun openUpstream(acc: Account, wire: JSONObject, model: String): Pair<okhttp3.Response?, String> {
        return try {
            var resp = QoderClient.openChat(acc, app.signer, wire, model, engine.firstTokenMs)
            val first = resp.code
            if (first == 401 || first == 403) {
                resp.close()
                acc.needsFields = true
                val t = QoderClient.deviceRefresh(acc)
                if (t == null) {
                    acc.status = "auth_error"
                    return null to "http $first and refresh failed for ${acc.label}"
                }
                acc.token = t.getString("token")
                acc.refreshToken = t.optString("refreshToken").ifEmpty { acc.refreshToken }
                acc.expiresAt = t.getLong("expiresAt")
                acc.needsFields = true
                acc.status = "active"
                app.repo.save()
                resp = QoderClient.openChat(acc, app.signer, wire, model, engine.firstTokenMs)
            }
            if (!resp.isSuccessful) {
                val body = runCatching { resp.body?.string()?.take(280) }.getOrNull() ?: ""
                val code = resp.code
                resp.close()
                null to "http $code ${body.replace("\n", " ")}".trim()
            } else {
                resp to ""
            }
        } catch (e: Exception) {
            null to describe(e)
        }
    }

    /**
     * Copies Qoder's SSE envelope into OpenAI chunks.
     * @return number of chunks handed to the client (0 means "nothing was seen yet" → safe to
     *         fail over to another account).
     */
    private fun streamOne(o: Opened, model: String, pipe: SsePipe): Int {
        val created = System.currentTimeMillis() / 1000
        val id = "chatcmpl-" + o.reqId.take(16)
        var inTok = 0L
        var outTok = 0L
        var usageSeen = false
        var chunks = 0
        var bytes = 0L
        var ttfb = -1L
        var sawDone = false
        var failure: String? = null
        try {
            val reader = BufferedReader(InputStreamReader(o.resp.body!!.byteStream(), StandardCharsets.UTF_8))
            while (!pipe.aborted) {
                val line = reader.readLine() ?: break
                if (!line.startsWith("data:")) continue
                bytes += line.length
                val data = line.substring(5).trim()
                if (data.isEmpty()) continue
                if (data == "[DONE]") { sawDone = true; break }
                val inner = unwrap(data) ?: continue
                if (!inner.has("choices") && !inner.has("usage")) continue
                val u = inner.optJSONObject("usage")
                if (u != null) {
                    usageSeen = true
                    inTok = if (u.has("prompt_tokens")) u.getLong("prompt_tokens") else u.optLong("input_tokens", inTok)
                    outTok = if (u.has("completion_tokens")) u.getLong("completion_tokens") else u.optLong("output_tokens", outTok)
                }
                if (chunks == 0) {
                    ttfb = System.currentTimeMillis() - o.t0
                    engine.markStreaming(o.reqId, ttfb)
                }
                chunks++
                if (chunks % 6 == 0) engine.markActive(o.reqId, chunks, bytes)
                if (!pipe.pushData(normalize(inner, id, model, created))) break
            }
        } catch (e: Exception) {
            failure = describe(e)
        } finally {
            runCatching { o.resp.close() }
        }

        if (pipe.aborted) {
            engine.endActive(o.reqId, "client_gone")
            engine.logEvent(event(o.reqId, o.acc, model, 200, "client_gone", inTok, outTok, o.t0,
                "client closed after $chunks chunks").put("ttfb", ttfb))
        } else if (chunks == 0) {
            val err = failure ?: "upstream closed before first chunk"
            engine.endActive(o.reqId, "failover")
            engine.penalize(o.acc, err, 0)
            engine.logEvent(event(o.reqId, o.acc, model, 0, "failover", 0, 0, o.t0, err))
            app.repo.save()
        } else {
            engine.endActive(o.reqId, if (sawDone) "ok" else "short")
            if (usageSeen) { o.acc.inTok += inTok; o.acc.outTok += outTok }
            o.acc.status = "active"; o.acc.lastError = ""
            app.repo.save()
            engine.logEvent(event(o.reqId, o.acc, model, 200,
                if (sawDone) "ok" else "short", inTok, outTok, o.t0, failure)
                .put("ttfb", ttfb).put("chunks", chunks))
            if (!sawDone && failure != null) pipe.pushComment("relay: upstream ended early — $failure")
            pipe.pushData("[DONE]")
            pipe.finish()
        }
        return chunks
    }

    /** Non-streaming: collect everything, then answer with a normal JSON body. */
    private fun blockingCompletion(openai: JSONObject, cands: List<Account>, model: String): Response {
        val deadline = System.currentTimeMillis() + engine.overallMs
        var remaining = cands
        var lastErr = "no candidates"
        while (remaining.isNotEmpty()) {
            val (opened, err) = openFirstAvailable(openai, remaining, model, deadline) { }
            if (opened == null) { lastErr = err; break }
            val agg = collectOne(opened, model)
            if (agg.json != null) return jsonResponse(agg.json.toString())
            lastErr = agg.error
            remaining = remaining.filter { it.id != opened.acc.id }
        }
        return errorJson(Status.INTERNAL_ERROR, "upstream_unavailable", lastErr)
    }

    private class Collected(val json: JSONObject?, val error: String)

    private fun collectOne(o: Opened, model: String): Collected {
        val content = StringBuilder()
        val reasoning = StringBuilder()
        val tools = HashMap<Int, JSONObject>()
        var usage: JSONObject? = null
        var finish: String? = null
        var chunks = 0
        var ttfb = -1L
        var failure: String? = null
        try {
            val reader = BufferedReader(InputStreamReader(o.resp.body!!.byteStream(), StandardCharsets.UTF_8))
            while (true) {
                val line = reader.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.substring(5).trim()
                if (data.isEmpty()) continue
                if (data == "[DONE]") break
                val inner = unwrap(data) ?: continue
                if (!inner.has("choices") && !inner.has("usage")) continue
                if (chunks == 0) ttfb = System.currentTimeMillis() - o.t0
                chunks++
                inner.optJSONObject("usage")?.let { usage = it }
                val ch = inner.optJSONArray("choices")?.optJSONObject(0) ?: continue
                ch.optString("finish_reason").takeIf { it.isNotEmpty() && it != "null" }?.let { finish = it }
                val d = ch.optJSONObject("delta") ?: continue
                content.append(d.optString("content"))
                reasoning.append(d.optString("reasoning_content"))
                d.optJSONArray("tool_calls")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val tc = arr.optJSONObject(i) ?: continue
                        val idx = tc.optInt("index", tools.size)
                        val cur = tools.getOrPut(idx) {
                            JSONObject().put("id", "").put("type", "function")
                                .put("function", JSONObject().put("name", "").put("arguments", ""))
                        }
                        if (tc.optString("id").isNotEmpty()) cur.put("id", tc.getString("id"))
                        val fn = tc.optJSONObject("function") ?: JSONObject()
                        val target = cur.getJSONObject("function")
                        target.put("name", target.getString("name") + fn.optString("name"))
                        target.put("arguments", target.getString("arguments") + fn.optString("arguments"))
                    }
                }
            }
        } catch (e: Exception) {
            failure = describe(e)
        } finally {
            runCatching { o.resp.close() }
        }

        if (chunks == 0) {
            val err = failure ?: "upstream closed before first chunk"
            engine.penalize(o.acc, err, 0)
            engine.logEvent(event(o.reqId, o.acc, model, 0, "failover", 0, 0, o.t0, err))
            app.repo.save()
            return Collected(null, err)
        }
        val u = usage ?: JSONObject()
        val pin = if (u.has("prompt_tokens")) u.getLong("prompt_tokens") else u.optLong("input_tokens", 0)
        val pout = if (u.has("completion_tokens")) u.getLong("completion_tokens") else u.optLong("output_tokens", 0)
        o.acc.inTok += pin
        o.acc.outTok += pout
        app.repo.save()
        engine.logEvent(event(o.reqId, o.acc, model, 200, "ok", pin, pout, o.t0, failure)
            .put("ttfb", ttfb).put("chunks", chunks))

        val msg = JSONObject().put("role", "assistant").put("content", content.toString())
        if (reasoning.isNotEmpty()) msg.put("reasoning_content", reasoning.toString())
        if (tools.isNotEmpty()) {
            val arr = JSONArray()
            tools.toSortedMap().values.forEach { arr.put(it) }
            msg.put("tool_calls", arr)
        }
        return Collected(JSONObject()
            .put("id", "chatcmpl-" + o.reqId.take(16))
            .put("object", "chat.completion")
            .put("created", System.currentTimeMillis() / 1000)
            .put("model", model)
            .put("choices", JSONArray().put(JSONObject()
                .put("index", 0).put("message", msg).put("finish_reason", finish ?: "stop")))
            .put("usage", JSONObject()
                .put("prompt_tokens", pin).put("completion_tokens", pout).put("total_tokens", pin + pout)), "")
    }

    /** Last-resort in-band error: headers are already out, so the client must be told via SSE. */
    private fun streamError(pipe: SsePipe, model: String, msg: String) {
        val clean = msg.replace("\n", " ").take(300)
        pipe.pushComment("relay error: upstream_unavailable — $clean")
        pipe.pushData(JSONObject()
            .put("id", "chatcmpl-" + UUID.randomUUID().toString().take(16))
            .put("object", "chat.completion.chunk")
            .put("created", System.currentTimeMillis() / 1000)
            .put("model", model)
            .put("choices", JSONArray().put(JSONObject()
                .put("index", 0)
                .put("delta", JSONObject().put("content", "\n\n⚠ relay: upstream_unavailable — $clean"))
                .put("finish_reason", "stop")))
            .put("relay_error", JSONObject().put("type", "upstream_unavailable").put("message", clean))
            .toString())
        pipe.pushData("[DONE]")
        pipe.finish()
    }

    private fun event(reqId: String, a: Account, model: String, code: Int, state: String,
                      pin: Long, pout: Long, t0: Long, err: String? = null): JSONObject = JSONObject()
        .put("ts", System.currentTimeMillis())
        .put("request_id", reqId)
        .put("account", a.label)
        .put("model", model)
        .put("status", code)
        .put("state", state)
        .put("in", pin).put("out", pout)
        .put("ms", System.currentTimeMillis() - t0)
        .apply { if (!err.isNullOrEmpty()) put("err", err.replace("\n", " ").take(180)) }

    private fun unwrap(data: String): JSONObject? = try {
        val o = JSONObject(data)
        if (o.has("statusCodeValue") && o.has("body")) {
            val b = o.getString("body").trim()
            if (b == "[DONE]") null else JSONObject(b)
        } else o
    } catch (_: Exception) {
        null
    }

    private fun normalize(o: JSONObject, id: String, model: String, created: Long): String {
        if (o.optString("id").isEmpty()) o.put("id", id)
        if (!o.has("object")) o.put("object", "chat.completion.chunk")
        if (o.optString("model").isEmpty()) o.put("model", model)
        if (!o.has("created")) o.put("created", created)
        return o.toString()
    }

    private fun httpCode(err: String): Int =
        Regex("http (\\d{3})").find(err)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun describe(e: Exception): String {
        val m = e.message ?: e.javaClass.simpleName
        return if (m.contains("timeout", true) && e is java.io.InterruptedIOException)
            "timeout: account did not start answering in ${engine.firstTokenMs / 1000}s" else m
    }

    // ------------------------------------------------------------------ admin

    private fun admin(session: IHTTPSession, uri: String, method: Method): Response {
        val body = if (method == Method.POST || method == Method.PUT) readBody(session) else "{}"
        val o = runCatching { JSONObject(body) }.getOrElse { JSONObject() }
        when {
            uri == "/relay/status" && method == Method.GET -> return jsonResponse(statusJson().toString())
            uri == "/relay/accounts" && method == Method.GET ->
                return jsonResponse(JSONArray().also { arr -> app.repo.accounts.forEach { arr.put(publicOf(it)) } }.toString())
            uri == "/relay/accounts" && method == Method.POST -> return addPatAccount(o)
            uri == "/relay/device-start" && method == Method.POST -> return deviceStart(o)
            uri == "/relay/device-poll" && method == Method.GET -> return devicePoll(session)
            uri == "/relay/models/refresh" && method == Method.POST -> return refreshModels(o)
            uri == "/relay/models/refresh" && method == Method.GET -> return refreshAllModels()
            uri == "/relay/usage" && method == Method.GET -> return usageJson()
            uri == "/relay/catalog" && method == Method.GET ->
                return jsonResponse(JSONObject().put("models", JSONArray(engine.modelCatalog())).toString())
            uri == "/relay/config" && method == Method.GET -> return jsonResponse(configJson().toString())
            uri == "/relay/config" && method == Method.POST -> return patchConfig(o)
            uri == "/relay/selftest" && method == Method.POST -> return selftest(o)
            uri.startsWith("/relay/accounts/") -> {
                val id = uri.substringAfterLast('/')
                val acc = app.repo.find(id) ?: return errorJson(Status.NOT_FOUND, "no_account", id)
                when (method) {
                    Method.DELETE -> {
                        app.repo.accounts.remove(acc); app.repo.save()
                        return jsonResponse(statusJson().toString())
                    }
                    Method.PUT, Method.POST -> {
                        patchAccount(acc, o)
                        return jsonResponse(publicOf(acc).toString())
                    }
                    else -> return errorJson(Status.METHOD_NOT_ALLOWED, "bad_method", method.name)
                }
            }
        }
        return errorJson(Status.NOT_FOUND, "admin_not_found", uri)
    }

    private fun publicOf(a: Account): JSONObject = a.publicJson().apply {
        put("discovered", JSONArray(a.discovered))
    }

    private fun configJson(): JSONObject = JSONObject()
        .put("port", app.port)
        .put("key", app.relayKey)
        .put("lan", app.lanAddress())
        .put("running", app.isRunning())
        .put("version", RelayApp.VERSION)
        .put("uptimeMs", System.currentTimeMillis() - app.startedAt)
        .put("inFlight", app.inFlight.get())
        .put("signerState", app.signer.lastState)
        .put("signerError", app.signer.lastError)
        .put("serverError", app.serverError)
        .put("firstTokenMs", engine.firstTokenMs)
        .put("cooldownMs", engine.cooldownMs)
        .put("overallMs", engine.overallMs)
        .put("heartbeatMs", engine.heartbeatMs)
        .put("maxAttempts", engine.maxAttempts)

    private fun patchConfig(o: JSONObject): Response {
        var restart = false
        var keyRotated = false
        if (o.has("firstTokenMs")) engine.firstTokenMs = o.getLong("firstTokenMs").coerceIn(3_000, 300_000)
        if (o.has("cooldownMs")) engine.cooldownMs = o.getLong("cooldownMs").coerceIn(0, 600_000)
        if (o.has("overallMs")) engine.overallMs = o.getLong("overallMs").coerceIn(15_000, 3_600_000)
        if (o.has("heartbeatMs")) engine.heartbeatMs = o.getLong("heartbeatMs").coerceIn(1_000, 60_000)
        if (o.has("maxAttempts")) engine.maxAttempts = o.getInt("maxAttempts").coerceIn(1, 16)
        if (o.has("clearCooldowns") && o.getBoolean("clearCooldowns")) {
            app.repo.accounts.forEach { it.cooldownUntil = 0; it.status = "active"; it.lastError = "" }
            app.repo.save()
        }
        if (o.has("key_regen") && o.getBoolean("key_regen")) {
            app.regenerateKey(); keyRotated = true
        }
        val newPort = o.optInt("port", app.port)
        if (newPort != app.port && newPort in 1024..65534) {
            app.changePort(newPort)
            restart = true
        }
        if (o.has("running")) {
            val want = o.getBoolean("running")
            if (want && !app.isRunning()) { app.ensureServer(); restart = app.serverError.isEmpty() }
            if (!want && app.isRunning()) { app.stopServerAsync(); restart = true }
        }
        app.saveTuning()
        return jsonResponse(configJson()
            .put("restart", restart)
            .put("key_rotated", keyRotated)
            .put("inFlight", app.inFlight.get())
            .toString())
    }

    private fun selftest(o: JSONObject): Response {
        val acc = o.optString("account").ifEmpty { null }?.let { app.repo.find(it) }
            ?: app.repo.accounts.firstOrNull { it.enabled }
            ?: return errorJson(Status.BAD_REQUEST, "no_account", "add an account first")
        val out = JSONObject().put("account", acc.label)
        val t0 = System.currentTimeMillis()
        out.put("token", JSONObject().put("ok", engine.ensureToken(acc)).put("ms", System.currentTimeMillis() - t0))
        val t1 = System.currentTimeMillis()
        try {
            acc.needsFields = true
            QoderClient.ensureCosyFields(acc, app.signer)
            out.put("cosy", JSONObject().put("ok", acc.encInfo.isNotEmpty() && acc.cosyKey.isNotEmpty())
                .put("ms", System.currentTimeMillis() - t1).put("key_chars", acc.cosyKey.length))
        } catch (e: Exception) {
            out.put("cosy", JSONObject().put("ok", false).put("ms", System.currentTimeMillis() - t1)
                .put("error", describe(e)))
        }
        val t2 = System.currentTimeMillis()
        try {
            val entries = QoderClient.listModels(acc, app.signer)
            acc.discovered = entries.map { it.optString("key") }.filter { it.isNotEmpty() }.toMutableList()
            entries.forEach { m ->
                val k = m.optString("key")
                if (k.isNotEmpty()) engine.modelInfo[k] =
                    (m.optString("display_name").ifEmpty { k }) to m.optBoolean("is_reasoning")
            }
            app.repo.save()
            out.put("models", JSONObject().put("ok", true).put("ms", System.currentTimeMillis() - t2)
                .put("count", acc.discovered.size))
        } catch (e: Exception) {
            out.put("models", JSONObject().put("ok", false).put("ms", System.currentTimeMillis() - t2)
                .put("error", describe(e)))
        }
        return jsonResponse(out.toString())
    }

    private fun refreshAllModels(): Response {
        val res = JSONArray()
        app.repo.accounts.filter { it.enabled }.forEach { a ->
            val t = System.currentTimeMillis()
            try {
                val entries = QoderClient.listModels(a, app.signer)
                a.discovered = entries.map { it.optString("key") }.filter { it.isNotEmpty() }.toMutableList()
                entries.forEach { m ->
                    val k = m.optString("key")
                    if (k.isNotEmpty()) engine.modelInfo[k] =
                        (m.optString("display_name").ifEmpty { k }) to m.optBoolean("is_reasoning")
                }
                res.put(JSONObject().put("account", a.label).put("ok", true)
                    .put("models", JSONArray(a.discovered)).put("ms", System.currentTimeMillis() - t))
            } catch (e: Exception) {
                res.put(JSONObject().put("account", a.label).put("ok", false)
                    .put("error", describe(e)).put("ms", System.currentTimeMillis() - t))
            }
        }
        app.repo.save()
        return jsonResponse(JSONObject().put("results", res).toString())
    }

    private fun addPatAccount(o: JSONObject): Response {
        val pat = o.optString("pat")
        if (!pat.startsWith("pt-")) return errorJson(Status.BAD_REQUEST, "bad_pat", "PAT must start with pt-")
        val acc = Account(label = o.optString("label", "pat-account"), region = o.optString("region", "global"), kind = "pat")
        try {
            val t = QoderClient.patExchange(pat)
            acc.token = t.getString("token"); acc.refreshToken = t.optString("refreshToken")
            acc.expiresAt = t.getLong("expiresAt"); acc.status = "active"
        } catch (e: Exception) {
            return errorJson(Status.BAD_REQUEST, "pat_exchange_failed", describe(e))
        }
        try {
            QoderClient.ensureCosyFields(acc, app.signer)
            val entries = QoderClient.listModels(acc, app.signer)
            acc.discovered = entries.map { it.optString("key") }.filter { it.isNotEmpty() }.toMutableList()
            entries.forEach { m ->
                val k = m.optString("key")
                if (k.isNotEmpty()) engine.modelInfo[k] =
                    (m.optString("display_name").ifEmpty { k }) to m.optBoolean("is_reasoning")
            }
        } catch (e: Exception) {
            acc.lastError = "models: " + describe(e).take(120)
        }
        app.repo.accounts.add(acc); app.repo.save()
        return jsonResponse(publicOf(acc).toString())
    }

    private fun deviceStart(o: JSONObject): Response {
        val (st, url) = QoderClient.loginStart(o.optString("region", "global"))
        val loginId = UUID.randomUUID().toString().take(8)
        pendingLogins[loginId] = st
        pendingLoginStatus[loginId] = "pending"
        Thread {
            try {
                var tokens: JSONObject? = null
                val end = System.currentTimeMillis() + 290_000
                while (tokens == null && System.currentTimeMillis() < end) {
                    Thread.sleep(1500)
                    tokens = QoderClient.devicePoll(st)
                }
                if (tokens == null) { pendingLoginStatus[loginId] = "error:timeout"; return@Thread }
                val acc = Account(
                    label = o.optString("label", "qoder-" + (app.repo.accounts.size + 1)),
                    region = o.optString("region", "global"),
                    kind = "device", machineId = st.machineId
                )
                acc.token = tokens.getString("token")
                acc.refreshToken = tokens.optString("refreshToken")
                acc.expiresAt = tokens.getLong("expiresAt")
                acc.status = "active"
                acc.needsFields = true
                try {
                    QoderClient.ensureCosyFields(acc, app.signer)
                    val entries = QoderClient.listModels(acc, app.signer)
                    acc.discovered = entries.map { it.optString("key") }.filter { it.isNotEmpty() }.toMutableList()
                    entries.forEach { m ->
                        val k = m.optString("key")
                        if (k.isNotEmpty()) engine.modelInfo[k] =
                            (m.optString("display_name").ifEmpty { k }) to m.optBoolean("is_reasoning")
                    }
                } catch (e: Exception) {
                    acc.lastError = "models: " + describe(e).take(120)
                }
                app.repo.accounts.add(acc); app.repo.save()
                pendingLoginStatus[loginId] = "ok:" + acc.id
            } catch (e: Exception) {
                pendingLoginStatus[loginId] = "error:" + describe(e)
            }
        }.apply { isDaemon = true }.start()
        return jsonResponse(JSONObject().put("login_id", loginId).put("url", url).toString())
    }

    private fun devicePoll(session: IHTTPSession): Response {
        val id = qp(session, "login_id") ?: return errorJson(Status.BAD_REQUEST, "need_login_id", "")
        val st = pendingLoginStatus[id] ?: return errorJson(Status.NOT_FOUND, "no_login", id)
        if (st.startsWith("ok:")) { pendingLoginStatus.remove(id); pendingLogins.remove(id) }
        return jsonResponse(JSONObject().put("state", st).toString())
    }

    private fun refreshModels(o: JSONObject): Response {
        val acc = app.repo.find(o.optString("account")) ?: return errorJson(Status.NOT_FOUND, "no_account", o.optString("account"))
        return try {
            val t = System.currentTimeMillis()
            val entries = QoderClient.listModels(acc, app.signer)
            val keys = entries.map { it.optString("key") }.filter { it.isNotEmpty() }
            entries.forEach { m ->
                val k = m.optString("key")
                if (k.isNotEmpty()) engine.modelInfo[k] =
                    (m.optString("display_name").ifEmpty { k }) to m.optBoolean("is_reasoning")
            }
            acc.discovered = keys.toMutableList()
            acc.lastError = ""; acc.status = "active"
            app.repo.save()
            jsonResponse(JSONObject().put("models", JSONArray(keys)).put("ms", System.currentTimeMillis() - t).toString())
        } catch (e: Exception) {
            errorJson(Status.INTERNAL_ERROR, "models_failed", describe(e))
        }
    }

    private fun patchAccount(acc: Account, o: JSONObject) {
        if (o.has("label")) acc.label = o.getString("label")
        if (o.has("enabled")) acc.enabled = o.getBoolean("enabled")
        if (o.has("universal")) acc.universal = o.getBoolean("universal")
        if (o.has("modelMode")) acc.modelMode = o.getString("modelMode")
        if (o.has("clearCooldown")) { acc.cooldownUntil = 0; acc.lastError = ""; acc.status = "active" }
        if (o.has("models")) {
            acc.models.clear()
            val arr = o.getJSONArray("models")
            for (i in 0 until arr.length()) acc.models.add(arr.getString(i))
        }
        app.repo.save()
    }

    private fun usageJson(): Response {
        val arr = JSONArray()
        app.repo.accounts.forEach { arr.put(publicOf(it)) }
        val ev = JSONArray()
        synchronized(engine.recent) { engine.recent.forEach { ev.put(it) } }
        val act = JSONArray()
        engine.activeList().forEach { act.put(it) }
        return jsonResponse(JSONObject()
            .put("total_in", app.repo.totalIn())
            .put("total_out", app.repo.totalOut())
            .put("in_flight", app.inFlight.get())
            .put("accounts", arr)
            .put("active", act)
            .put("recent", ev).toString())
    }

    private fun statusJson() = JSONObject()
        .put("running", app.isRunning())
        .put("now", System.currentTimeMillis())
        .put("port", app.port)
        .put("version", RelayApp.VERSION)
        .put("uptimeMs", System.currentTimeMillis() - app.startedAt)
        .put("inFlight", app.inFlight.get())
        .put("active", engine.activeList().size)
        .put("accounts", app.repo.accounts.size)
        .put("enabled", app.repo.accounts.count { it.enabled })
        .put("cooling", app.repo.accounts.count { it.cooldownUntil > System.currentTimeMillis() })
        .put("total_in", app.repo.totalIn())
        .put("total_out", app.repo.totalOut())
        .put("models", engine.catalog().size)
        .put("lan", app.lanAddress())
        .put("signer", app.signer.lastState)

    // ------------------------------------------------------------------ utils

    private fun readBody(session: IHTTPSession): String {
        val declared = session.headers["content-length"]?.toLongOrNull()
        if (declared != null) {
            val len = declared.coerceIn(0L, MAX_BODY).toInt()
            if (len == 0) return ""
            val bytes = ByteArray(len)
            var off = 0
            while (off < len) {
                val n = session.inputStream.read(bytes, off, len - off)
                if (n < 0) break
                off += n
            }
            return String(bytes, 0, off, StandardCharsets.UTF_8)
        }
        if ("chunked".equals(session.headers["transfer-encoding"], true)) {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            val sin = session.inputStream
            while (true) {
                val sizeLine = readAsciiLine(sin) ?: break
                val size = sizeLine.trim().substringBefore(";").trim().toIntOrNull(16) ?: break
                if (size == 0) break
                var left = size
                while (left > 0) {
                    val n = sin.read(buf, 0, minOf(left, buf.size))
                    if (n < 0) break
                    out.write(buf, 0, n)
                    left -= n
                }
                readAsciiLine(sin)   // trailing CRLF
            }
            return String(out.toByteArray(), StandardCharsets.UTF_8)
        }
        return ""
    }

    private fun readAsciiLine(sin: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = sin.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString()
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > 2048) return sb.toString()
        }
    }

    private fun jsonResponse(s: String): Response {
        val bytes = s.toByteArray(StandardCharsets.UTF_8)
        return cors(newFixedLengthResponse(Status.OK, "application/json; charset=utf-8",
            ByteArrayInputStream(bytes), bytes.size.toLong()))
    }

    private fun textResponse(s: String, st: Status) =
        cors(newFixedLengthResponse(st, "text/plain", s))

    private fun emptyResponse(st: Status) = cors(newFixedLengthResponse(st, "text/plain", ""))

    private fun cors(r: Response): Response {
        r.addHeader("Access-Control-Allow-Origin", "*")
        r.addHeader("Access-Control-Allow-Headers", "authorization, content-type, x-relay-key")
        r.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
        return r
    }

    private fun errorJson(st: Status, type: String, msg: String): Response {
        val body = JSONObject().put("error", JSONObject()
            .put("message", msg).put("type", type).put("code", st.requestStatus.toString())).toString()
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        return cors(newFixedLengthResponse(st, "application/json; charset=utf-8",
            ByteArrayInputStream(bytes), bytes.size.toLong()))
    }
}
