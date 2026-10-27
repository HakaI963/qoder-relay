package com.qoder.relay

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayInputStream
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

    companion object { const val PORT_DEFAULT = 8788 }

    private val pendingLogins = ConcurrentHashMap<String, QoderClient.LoginState>()
    private val pendingLoginStatus = ConcurrentHashMap<String, String>()

    private fun qp(session: IHTTPSession, key: String): String? = session.parameters[key]

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri.trimEnd('/')
        val method = session.method
        return try {
            when {
                uri == "" || uri == "/" -> serveDashboard()
                uri == "/favicon.ico" -> textResponse("", Status.NO_CONTENT)
                method == Method.GET && uri == "/v1/models" -> authed(session) { modelsCatalog() }
                method == Method.POST && uri == "/v1/chat/completions" -> authed(session) { chatCompletions(session) }
                uri.startsWith("/relay/") -> authed(session) { admin(session, uri, method) }
                else -> errorJson(Status.NOT_FOUND, "not_found", "unknown route $uri")
            }
        } catch (e: Exception) {
            errorJson(Status.INTERNAL_ERROR, "relay_error", e.message ?: "internal")
        }
    }

    // ---------- auth ----------
    private fun keyOk(session: IHTTPSession): Boolean {
        val hdr = session.headers["authorization"]?.removePrefix("Bearer ")?.trim()
            ?: session.headers["x-relay-key"]?.trim()
            ?: qp(session, "key")
        return hdr == app.relayKey
    }

    private fun authed(session: IHTTPSession, block: () -> Response): Response {
        if (!keyOk(session)) return errorJson(Status.UNAUTHORIZED, "invalid_api_key", "bad relay key")
        return block()
    }

    private fun serveDashboard(): Response {
        val html = app.assets.open("dashboard.html").readBytes()
        return newFixedLengthResponse(Status.OK, "text/html; charset=utf-8",
            ByteArrayInputStream(html), html.size.toLong())
    }

    private fun modelsCatalog(): Response {
        val arr = JSONArray()
        val seen = HashSet<String>()
        for (a in app.repo.accounts.filter { it.enabled }) {
            val keys = if (a.models.isNotEmpty()) a.models else a.discovered
            for (k in keys) if (seen.add(k)) arr.put(JSONObject()
                .put("id", k).put("object", "model").put("owned_by", "qoder-" + a.id.take(8)))
        }
        if (arr.length() == 0) {
            arr.put(JSONObject().put("id", "qfmodel").put("object", "model").put("owned_by", "qoder"))
            arr.put(JSONObject().put("id", "dfmodel").put("object", "model").put("owned_by", "qoder"))
        }
        return jsonResponse(JSONObject().put("object", "list").put("data", arr).toString())
    }

    // ---------- chat ----------
    private fun chatCompletions(session: IHTTPSession): Response {
        val openai = JSONObject(readBody(session))
        val requestedModel = openai.optString("model").ifEmpty { null }
        val pinned = openai.optString("relay_account").ifEmpty { qp(session, "account") ?: "" }.ifEmpty { null }
        val (cands, resolved) = engine.candidates(requestedModel, pinned)
        if (cands.isEmpty())
            return errorJson(Status.FORBIDDEN, "no_accounts",
                "no enabled Qoder accounts serve model ${requestedModel ?: "any"}")

        val stream = openai.optBoolean("stream", true)
        var lastErr = "no candidates"
        for (acc in cands) {
            if (!engine.ensureToken(acc)) {
                lastErr = "token refresh failed for ${acc.label}"
                acc.failovers++
                continue
            }
            val reqId = UUID.randomUUID().toString()
            val payload = engine.buildGatewayPayload(acc, resolved, openai, reqId)
            val t0 = System.currentTimeMillis()
            try {
                var resp = QoderClient.openChat(acc, payload)
                if (resp.code == 401) {
                    resp.close()
                    if (QoderClient.deviceRefresh(acc) != null) resp = QoderClient.openChat(acc, payload)
                }
                if (!resp.isSuccessful) {
                    val err = resp.body?.string()?.take(300) ?: "http ${resp.code}"
                    resp.close()
                    lastErr = err
                    acc.errs++; acc.status = "error"; acc.lastError = err
                    acc.cooldownUntil = System.currentTimeMillis() + 30_000
                    engine.logEvent(event(reqId, acc, resolved, resp.code, "fail", 0, 0, t0))
                    app.repo.save()
                    continue
                }
                acc.reqs++; acc.status = "active"; acc.lastError = ""; acc.cooldownUntil = 0
                return if (stream) streamResponse(resp, acc, resolved, reqId, t0)
                else collectResponse(resp, acc, resolved, reqId, t0)
            } catch (e: Exception) {
                lastErr = e.message ?: "exception"
                acc.errs++; acc.status = "error"; acc.lastError = lastErr
                acc.cooldownUntil = System.currentTimeMillis() + 30_000
                acc.failovers++
                engine.logEvent(event(reqId, acc, resolved, 0, "failover", 0, 0, t0))
                app.repo.save()
            }
        }
        return errorJson(Status.BAD_GATEWAY, "upstream_unavailable", lastErr)
    }

    private fun event(reqId: String, a: Account, model: String, code: Int, state: String,
                      pin: Long, pout: Long, t0: Long) = JSONObject()
        .put("ts", System.currentTimeMillis())
        .put("request_id", reqId)
        .put("account", a.label)
        .put("model", model)
        .put("status", code)
        .put("state", state)
        .put("in", pin).put("out", pout)
        .put("ms", System.currentTimeMillis() - t0)

    private fun streamResponse(resp: okhttp3.Response, acc: Account, model: String, reqId: String, t0: Long): Response {
        val created = System.currentTimeMillis() / 1000
        val id = "chatcmpl-" + reqId.take(16)
        val sse = object : InputStream() {
            private val reader = BufferedReader(InputStreamReader(resp.body!!.byteStream(), StandardCharsets.UTF_8))
            private val queue = ArrayDeque<Byte>()
            private var done = false
            private var inTok = 0L
            private var outTok = 0L
            private var usageSeen = false

            private fun emit(s: String) { s.toByteArray(StandardCharsets.UTF_8).forEach { queue.add(it) } }

            override fun read(): Int {
                while (queue.isEmpty()) {
                    if (done) { settle(); return -1 }
                    val line = reader.readLine()
                    if (line == null) { finishUp(); continue }
                    if (!line.startsWith("data:")) continue
                    val data = line.substring(5).trim()
                    if (data.isEmpty()) continue
                    if (data == "[DONE]") {
                        emit("data: [DONE]\n\n"); done = true; continue
                    }
                    val inner = unwrap(data) ?: continue
                    trackUsage(inner)
                    emit("data: ${normalize(inner, id, model, created)}\n\n")
                }
                return queue.removeFirst().toInt() and 0xFF
            }

            private fun finishUp() { done = true }

            private fun trackUsage(o: JSONObject) {
                val u = o.optJSONObject("usage") ?: return
                usageSeen = true
                inTok = if (u.has("prompt_tokens")) u.getLong("prompt_tokens") else u.optLong("input_tokens", inTok)
                outTok = if (u.has("completion_tokens")) u.getLong("completion_tokens") else u.optLong("output_tokens", outTok)
            }

            private fun settle() {
                resp.close()
                if (usageSeen) {
                    acc.inTok += inTok; acc.outTok += outTok
                    app.repo.save()
                    engine.logEvent(event(reqId, acc, model, 200, "ok", inTok, outTok, t0))
                }
            }

            override fun close() { if (!done) { done = true }; settle(); super.close() }
        }
        val r = newChunkedResponse(Status.OK, "text/event-stream", sse)
        r.addHeader("Cache-Control", "no-cache")
        r.addHeader("X-Accel-Buffering", "no")
        return r
    }

    private fun collectResponse(resp: okhttp3.Response, acc: Account, model: String, reqId: String, t0: Long): Response {
        val reader = BufferedReader(InputStreamReader(resp.body!!.byteStream(), StandardCharsets.UTF_8))
        val content = StringBuilder(); val reasoning = StringBuilder()
        val toolCalls = JSONArray()
        var usage: JSONObject? = null; var finish: String? = null
        while (true) {
            val line = reader.readLine() ?: break
            if (!line.startsWith("data:")) continue
            val data = line.substring(5).trim()
            if (data == "[DONE]") break
            val inner = unwrap(data) ?: continue
            inner.optJSONObject("usage")?.let { usage = it }
            val ch = inner.optJSONArray("choices")?.optJSONObject(0)
            ch?.optString("finish_reason")?.takeIf { it.isNotEmpty() }?.let { finish = it }
            val d = ch?.optJSONObject("delta") ?: continue
            d.optString("content").let { content.append(it) }
            d.optString("reasoning_content").let { if (it.isNotEmpty()) reasoning.append(it) }
            d.optJSONArray("tool_calls")?.let { t -> for (i in 0 until t.length()) toolCalls.put(t.getJSONObject(i)) }
        }
        resp.close()
        val u = usage ?: JSONObject()
        val pin = if (u.has("prompt_tokens")) u.getLong("prompt_tokens") else u.optLong("input_tokens", 0)
        val pout = if (u.has("completion_tokens")) u.getLong("completion_tokens") else u.optLong("output_tokens", 0)
        acc.inTok += pin; acc.outTok += pout; app.repo.save()
        engine.logEvent(event(reqId, acc, model, 200, "ok", pin, pout, t0))
        val msg = JSONObject().put("role", "assistant").put("content", content.toString())
        if (reasoning.isNotEmpty()) msg.put("reasoning_content", reasoning.toString())
        if (toolCalls.length() > 0) msg.put("tool_calls", toolCalls)
        val out = JSONObject()
            .put("id", "chatcmpl-" + reqId.take(16))
            .put("object", "chat.completion")
            .put("created", System.currentTimeMillis() / 1000)
            .put("model", model)
            .put("choices", JSONArray().put(JSONObject()
                .put("index", 0).put("message", msg).put("finish_reason", finish ?: "stop")))
            .put("usage", JSONObject()
                .put("prompt_tokens", pin).put("completion_tokens", pout).put("total_tokens", pin + pout))
        return jsonResponse(out.toString())
    }

    private fun unwrap(data: String): JSONObject? {
        return try {
            val o = JSONObject(data)
            if (o.has("statusCodeValue") && o.has("body")) {
                val b = o.getString("body").trim()
                if (b == "[DONE]") null else JSONObject(b)
            } else o
        } catch (_: Exception) { null }
    }

    private fun normalize(o: JSONObject, id: String, model: String, created: Long): String {
        if (o.optString("id").isEmpty()) o.put("id", id)
        if (!o.has("object")) o.put("object", "chat.completion.chunk")
        if (o.optString("model").isEmpty()) o.put("model", model)
        if (!o.has("created")) o.put("created", created)
        return o.toString()
    }

    // ---------- admin ----------
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
            uri == "/relay/usage" && method == Method.GET -> return usageJson()
            uri.startsWith("/relay/accounts/") -> {
                val id = uri.substringAfterLast('/')
                val acc = app.repo.find(id) ?: return errorJson(Status.NOT_FOUND, "no_account", id)
                when (method) {
                    Method.DELETE -> { app.repo.accounts.remove(acc); app.repo.save(); return jsonResponse(statusJson().toString()) }
                    Method.PUT, Method.POST -> { patchAccount(acc, o); return jsonResponse(publicOf(acc).toString()) }
                    else -> return errorJson(Status.METHOD_NOT_ALLOWED, "bad_method", method.name)
                }
            }
        }
        return errorJson(Status.NOT_FOUND, "admin_not_found", uri)
    }

    private fun publicOf(a: Account): JSONObject = a.publicJson().apply {
        put("discovered", JSONArray(a.discovered))
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
            return errorJson(Status.BAD_REQUEST, "pat_exchange_failed", e.message ?: "")
        }
        acc.discovered = QoderClient.listModelKeys(acc).toMutableList()
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
                    label = o.optString("label", "qoder-" + loginId),
                    region = o.optString("region", "global"),
                    kind = "device", machineId = st.machineId
                )
                acc.token = tokens.getString("token")
                acc.refreshToken = tokens.optString("refreshToken")
                acc.expiresAt = tokens.getLong("expiresAt")
                acc.status = "active"
                acc.discovered = QoderClient.listModelKeys(acc).toMutableList()
                app.repo.accounts.add(acc); app.repo.save()
                pendingLoginStatus[loginId] = "ok:" + acc.id
            } catch (e: Exception) {
                pendingLoginStatus[loginId] = "error:" + (e.message ?: "")
            }
        }.start()
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
        val keys = QoderClient.listModelKeys(acc)
        acc.discovered = keys.toMutableList()
        app.repo.save()
        return jsonResponse(JSONObject().put("models", JSONArray(keys)).toString())
    }

    private fun patchAccount(acc: Account, o: JSONObject) {
        if (o.has("label")) acc.label = o.getString("label")
        if (o.has("enabled")) acc.enabled = o.getBoolean("enabled")
        if (o.has("universal")) acc.universal = o.getBoolean("universal")
        if (o.has("modelMode")) acc.modelMode = o.getString("modelMode")
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
        return jsonResponse(JSONObject()
            .put("total_in", app.repo.totalIn())
            .put("total_out", app.repo.totalOut())
            .put("accounts", arr)
            .put("recent", ev).toString())
    }

    private fun statusJson() = JSONObject()
        .put("running", true)
        .put("port", listenPort)
        .put("accounts", app.repo.accounts.size)
        .put("total_in", app.repo.totalIn())
        .put("total_out", app.repo.totalOut())

    private fun readBody(session: IHTTPSession): String {
        val len = session.contentLength.toInt().coerceAtLeast(0)
        val bytes = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = session.inputStream.read(bytes, read, len - read)
            if (n < 0) break
            read += n
        }
        return String(bytes, 0, read, StandardCharsets.UTF_8)
    }

    private fun jsonResponse(s: String): Response =
        newFixedLengthResponse(Status.OK, "application/json; charset=utf-8",
            ByteArrayInputStream(s.toByteArray(StandardCharsets.UTF_8)),
            s.toByteArray(StandardCharsets.UTF_8).size.toLong())

    private fun textResponse(s: String, st: Status) =
        newFixedLengthResponse(st, "text/plain", s)

    private fun errorJson(st: Status, type: String, msg: String): Response {
        val body = JSONObject().put("error", JSONObject()
            .put("message", msg).put("type", type).put("code", st.requestStatus.toString())).toString()
        return newFixedLengthResponse(st, "application/json", body)
    }
}
