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

/**
 * OpenAI-compatible HTTP server. Only three routes:
 *   GET  /health
 *   GET  /v1/models
 *   POST /v1/chat/completions   (stream + non-stream)
 * Everything else (accounts, settings) lives in the native app UI.
 */
class RelayServer(private val app: RelayApp, private val engine: RelayEngine, port: Int) : NanoHTTPD(port) {

    companion object {
        const val PORT_DEFAULT = 8788
        private const val MAX_BODY = 32L * 1024 * 1024
        /** Idle keep-alive sockets are closed after this; long requests are unaffected (reads only). */
        const val SOCKET_TIMEOUT_MS = 120_000
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri.trimEnd('/').ifEmpty { "/" }
        val method = session.method
        if (method == Method.OPTIONS) return cors(newFixedLengthResponse(Status.NO_CONTENT, "text/plain", ""))
        return try {
            when {
                uri == "/" || uri == "/health" -> json(JSONObject()
                    .put("ok", true).put("service", "qoder-relay").put("version", RelayApp.VERSION)
                    .put("accounts", app.repo.accounts.count { it.enabled }).toString())
                method == Method.GET && (uri == "/v1/models" || uri == "/models") -> authed(session) { models() }
                method == Method.POST && (uri == "/v1/chat/completions" || uri == "/chat/completions") ->
                    authed(session) { chat(session) }
                else -> error(Status.NOT_FOUND, "not_found", "unknown route $uri")
            }
        } catch (e: Throwable) {
            error(Status.INTERNAL_ERROR, "relay_error", describe(e))
        }
    }

    // ------------------------------------------------------------------- auth

    private fun authed(session: IHTTPSession, block: () -> Response): Response {
        if (!app.requireKey || isLoopback(session)) return block()
        val auth = session.headers["authorization"]?.trim()
        val key = auth?.removePrefix("Bearer ")?.removePrefix("bearer ")?.trim()
            ?: session.headers["x-api-key"]?.trim()
            ?: session.parameters["key"]?.firstOrNull()
        return if (key == app.relayKey) block()
        else error(Status.UNAUTHORIZED, "invalid_api_key", "Invalid relay API key")
    }

    private fun isLoopback(s: IHTTPSession): Boolean {
        val ip = s.remoteIpAddress ?: return false
        return ip.startsWith("127.") || ip == "::1" || ip.startsWith("::ffff:127.") || ip == "0:0:0:0:0:0:0:1"
    }

    // ----------------------------------------------------------------- models

    private fun models(): Response {
        val arr = JSONArray()
        engine.catalog().forEach { id ->
            arr.put(JSONObject().put("id", id).put("object", "model").put("created", 0)
                .put("owned_by", "qoder").put("display_name", engine.modelInfo[id]?.first ?: id))
        }
        return json(JSONObject().put("object", "list").put("data", arr).toString())
    }

    // ------------------------------------------------------------------- chat

    private class Opened(val resp: okhttp3.Response, val acc: Account, val reqId: String, val t0: Long)

    private fun chat(session: IHTTPSession): Response {
        val raw = readBody(session)
        if (raw.isBlank()) return error(Status.BAD_REQUEST, "empty_body", "expected a JSON body")
        val openai = try { JSONObject(raw) } catch (e: Exception) {
            return error(Status.BAD_REQUEST, "invalid_json", describe(e))
        }
        val model = engine.resolveModel(openai.optString("model"))
        val pinned = openai.optString("relay_account").ifEmpty { null }
        val cands = engine.candidates(pinned)
        if (cands.isEmpty()) {
            val why = when {
                app.repo.accounts.isEmpty() -> "No Qoder accounts added. Open the app and add an account."
                app.repo.accounts.none { it.enabled } -> "All accounts are disabled in the app."
                else -> "No account has a valid token. Re-login in the app."
            }
            return error(Status.SERVICE_UNAVAILABLE, "no_accounts", why)
        }
        app.markActivity()

        if (!openai.optBoolean("stream", false)) return blocking(openai, cands, model)

        // headers go out at once; the answer is produced by a worker behind the pipe
        val pipe = SsePipe()
        app.inFlight.incrementAndGet()
        Thread {
            try { stream(openai, cands, model, pipe) }
            catch (e: Throwable) { streamError(pipe, model, describe(e)) }
            finally { app.inFlight.decrementAndGet(); app.markActivity() }
        }.apply { isDaemon = true; name = "relay-stream" }.start()
        return SseResponse(pipe, mapOf("X-Relay-Model" to model))
    }

    private fun stream(openai: JSONObject, cands: List<Account>, model: String, pipe: SsePipe) {
        val hb = Heartbeat(pipe, engine.heartbeatMs).also { it.start() }
        val deadline = System.currentTimeMillis() + engine.overallMs
        try {
            var remaining = cands
            var lastErr = "no candidates"
            while (remaining.isNotEmpty() && !pipe.aborted) {
                val (opened, err) = openFirst(openai, remaining, model, deadline)
                if (opened == null) { lastErr = err; break }
                if (streamOne(opened, model, pipe) > 0 || pipe.aborted) return
                lastErr = opened.acc.lastError.ifEmpty { "upstream closed before first chunk" }
                remaining = remaining.filter { it.id != opened.acc.id }
            }
            if (!pipe.aborted) streamError(pipe, model, lastErr)
        } finally {
            hb.stop()
        }
    }

    /** Walk the candidates until one account answers with 2xx headers. */
    private fun openFirst(openai: JSONObject, cands: List<Account>, model: String, deadline: Long): Pair<Opened?, String> {
        var lastErr = "no accounts"
        for (acc in cands) {
            if (System.currentTimeMillis() > deadline) { lastErr = "request budget exhausted"; break }
            if (!QoderClient.ensureToken(acc)) {
                lastErr = "${acc.label}: token refresh failed (${acc.lastError})"
                app.repo.save()
                continue
            }
            val reqId = UUID.randomUUID().toString()
            val sessionId = openai.optString("relay_session_id").ifEmpty { reqId }
            val wire = engine.buildWire(model, openai, reqId, sessionId)
            val t0 = System.currentTimeMillis()
            val (resp, err) = openUpstream(acc, wire, model)
            if (resp != null) {
                acc.reqs++; acc.status = "active"; acc.lastError = ""; acc.cooldownUntil = 0
                return Opened(resp, acc, reqId, t0) to ""
            }
            engine.penalize(acc, err, httpCode(err))
            engine.logEvent(event(acc, model, "failed", 0, 0, t0, err))
            app.repo.save()
            lastErr = "${acc.label}: $err"
        }
        return null to lastErr
    }

    /** One signed request; on 401/403 refresh token + COSY fields and retry once. */
    private fun openUpstream(acc: Account, wire: JSONObject, model: String): Pair<okhttp3.Response?, String> = try {
        var resp = QoderClient.openChat(acc, app.signer, wire, model, engine.firstTokenMs)
        if (resp.code == 401 || resp.code == 403) {
            resp.close()
            acc.needsFields = true
            if (!QoderClient.ensureToken(acc, force = true)) {
                // token itself may be fine and only the COSY fields were stale — retry once with fresh fields
                acc.needsFields = true
            }
            app.repo.save()
            resp = QoderClient.openChat(acc, app.signer, wire, model, engine.firstTokenMs)
        }
        if (resp.isSuccessful) resp to ""
        else {
            val code = resp.code
            val body = runCatching { resp.body?.string()?.take(240) }.getOrNull().orEmpty()
            resp.close()
            null to "http $code ${body.replace("\n", " ")}".trim()
        }
    } catch (e: Throwable) {
        null to describe(e)
    }

    /** Copy Qoder SSE into OpenAI chunks. @return chunks delivered (0 → safe to fail over). */
    private fun streamOne(o: Opened, model: String, pipe: SsePipe): Int {
        val created = System.currentTimeMillis() / 1000
        val id = "chatcmpl-" + o.reqId.take(16)
        var inTok = 0L; var outTok = 0L
        var chunks = 0
        var sawDone = false
        var failure: String? = null
        try {
            val reader = BufferedReader(InputStreamReader(o.resp.body!!.byteStream(), StandardCharsets.UTF_8))
            while (!pipe.aborted) {
                val line = reader.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.substring(5).trim()
                if (data.isEmpty()) continue
                if (data == "[DONE]") { sawDone = true; break }
                val inner = unwrap(data) ?: continue
                if (!inner.has("choices") && !inner.has("usage")) continue
                inner.optJSONObject("usage")?.let { u ->
                    inTok = u.optLong("prompt_tokens", u.optLong("input_tokens", inTok))
                    outTok = u.optLong("completion_tokens", u.optLong("output_tokens", outTok))
                }
                chunks++
                if (!pipe.pushData(normalize(inner, id, model, created))) break
            }
        } catch (e: Throwable) {
            failure = describe(e)
        } finally {
            runCatching { o.resp.close() }
        }

        when {
            pipe.aborted -> {
                o.acc.inTok += inTok; o.acc.outTok += outTok
                engine.logEvent(event(o.acc, model, "client closed", inTok, outTok, o.t0, null))
            }
            chunks == 0 -> {
                val err = failure ?: "upstream closed before first chunk"
                engine.penalize(o.acc, err, 0)
                engine.logEvent(event(o.acc, model, "failed", 0, 0, o.t0, err))
            }
            else -> {
                o.acc.inTok += inTok; o.acc.outTok += outTok
                o.acc.status = "active"; o.acc.lastError = ""
                engine.logEvent(event(o.acc, model, if (sawDone) "ok" else "partial", inTok, outTok, o.t0, failure))
                pipe.pushData("[DONE]")
                pipe.finish()
            }
        }
        app.repo.save()
        return chunks
    }

    private fun blocking(openai: JSONObject, cands: List<Account>, model: String): Response {
        app.inFlight.incrementAndGet()
        try {
            val deadline = System.currentTimeMillis() + engine.overallMs
            var remaining = cands
            var lastErr = "no candidates"
            while (remaining.isNotEmpty()) {
                val (opened, err) = openFirst(openai, remaining, model, deadline)
                if (opened == null) { lastErr = err; break }
                collect(opened, model)?.let { return json(it.toString()) }
                lastErr = opened.acc.lastError
                remaining = remaining.filter { it.id != opened.acc.id }
            }
            return error(Status.INTERNAL_ERROR, "upstream_unavailable", lastErr)
        } finally {
            app.inFlight.decrementAndGet()
        }
    }

    private fun collect(o: Opened, model: String): JSONObject? {
        val content = StringBuilder()
        val reasoning = StringBuilder()
        val tools = sortedMapOf<Int, JSONObject>()
        var usage: JSONObject? = null
        var finish: String? = null
        var chunks = 0
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
                chunks++
                inner.optJSONObject("usage")?.let { usage = it }
                val ch = inner.optJSONArray("choices")?.optJSONObject(0) ?: continue
                ch.optString("finish_reason").takeIf { it.isNotEmpty() && it != "null" }?.let { finish = it }
                val d = ch.optJSONObject("delta") ?: continue
                if (!d.isNull("content")) content.append(d.optString("content"))
                if (!d.isNull("reasoning_content")) reasoning.append(d.optString("reasoning_content"))
                d.optJSONArray("tool_calls")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val tc = arr.optJSONObject(i) ?: continue
                        val cur = tools.getOrPut(tc.optInt("index", tools.size)) {
                            JSONObject().put("id", "").put("type", "function")
                                .put("function", JSONObject().put("name", "").put("arguments", ""))
                        }
                        if (tc.optString("id").isNotEmpty()) cur.put("id", tc.getString("id"))
                        val fn = tc.optJSONObject("function") ?: JSONObject()
                        val t = cur.getJSONObject("function")
                        t.put("name", t.getString("name") + fn.optString("name"))
                        t.put("arguments", t.getString("arguments") + fn.optString("arguments"))
                    }
                }
            }
        } catch (e: Throwable) {
            failure = describe(e)
        } finally {
            runCatching { o.resp.close() }
        }
        if (chunks == 0) {
            val err = failure ?: "upstream closed before first chunk"
            engine.penalize(o.acc, err, 0)
            engine.logEvent(event(o.acc, model, "failed", 0, 0, o.t0, err))
            app.repo.save()
            return null
        }
        val u = usage ?: JSONObject()
        val pin = u.optLong("prompt_tokens", u.optLong("input_tokens", 0))
        val pout = u.optLong("completion_tokens", u.optLong("output_tokens", 0))
        o.acc.inTok += pin; o.acc.outTok += pout
        engine.logEvent(event(o.acc, model, "ok", pin, pout, o.t0, failure))
        app.repo.save()

        val msg = JSONObject().put("role", "assistant").put("content", content.toString())
        if (reasoning.isNotEmpty()) msg.put("reasoning_content", reasoning.toString())
        if (tools.isNotEmpty()) msg.put("tool_calls", JSONArray(tools.values.toList()))
        return JSONObject()
            .put("id", "chatcmpl-" + o.reqId.take(16))
            .put("object", "chat.completion")
            .put("created", System.currentTimeMillis() / 1000)
            .put("model", model)
            .put("choices", JSONArray().put(JSONObject().put("index", 0).put("message", msg)
                .put("finish_reason", finish ?: if (tools.isNotEmpty()) "tool_calls" else "stop")))
            .put("usage", JSONObject().put("prompt_tokens", pin).put("completion_tokens", pout).put("total_tokens", pin + pout))
    }

    /** Headers are already sent, so errors must go in-band. */
    private fun streamError(pipe: SsePipe, model: String, msg: String) {
        val clean = msg.replace("\n", " ").take(300)
        pipe.pushData(JSONObject()
            .put("id", "chatcmpl-" + UUID.randomUUID().toString().take(16))
            .put("object", "chat.completion.chunk")
            .put("created", System.currentTimeMillis() / 1000)
            .put("model", model)
            .put("choices", JSONArray().put(JSONObject().put("index", 0)
                .put("delta", JSONObject().put("content", "\n\n[relay error] $clean"))
                .put("finish_reason", "stop")))
            .put("error", JSONObject().put("type", "upstream_unavailable").put("message", clean))
            .toString())
        pipe.pushData("[DONE]")
        pipe.finish()
    }

    private fun event(a: Account, model: String, state: String, pin: Long, pout: Long, t0: Long, err: String?) =
        JSONObject()
            .put("ts", System.currentTimeMillis()).put("account", a.label).put("model", model)
            .put("state", state).put("in", pin).put("out", pout).put("ms", System.currentTimeMillis() - t0)
            .apply { if (!err.isNullOrEmpty()) put("err", err.replace("\n", " ").take(160)) }

    private fun unwrap(data: String): JSONObject? = try {
        val o = JSONObject(data)
        if (o.has("statusCodeValue") && o.has("body")) {
            val b = o.getString("body").trim()
            if (b == "[DONE]") null else JSONObject(b)
        } else o
    } catch (_: Exception) { null }

    private fun normalize(o: JSONObject, id: String, model: String, created: Long): String {
        if (o.optString("id").isEmpty()) o.put("id", id)
        if (!o.has("object")) o.put("object", "chat.completion.chunk")
        if (o.optString("model").isEmpty()) o.put("model", model)
        if (!o.has("created")) o.put("created", created)
        return o.toString()
    }

    private fun httpCode(err: String) = Regex("http (\\d{3})").find(err)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun describe(e: Throwable): String = e.message ?: e.javaClass.simpleName

    // ------------------------------------------------------------------ utils

    private fun readBody(session: IHTTPSession): String {
        val declared = session.headers["content-length"]?.toLongOrNull()
        val sin = session.inputStream
        if (declared != null) {
            val len = declared.coerceIn(0L, MAX_BODY).toInt()
            val bytes = ByteArray(len)
            var off = 0
            while (off < len) { val n = sin.read(bytes, off, len - off); if (n < 0) break; off += n }
            return String(bytes, 0, off, StandardCharsets.UTF_8)
        }
        if ("chunked".equals(session.headers["transfer-encoding"], true)) {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val size = readLine(sin)?.substringBefore(";")?.trim()?.toIntOrNull(16) ?: break
                if (size == 0) break
                var left = size
                while (left > 0) { val n = sin.read(buf, 0, minOf(left, buf.size)); if (n < 0) break; out.write(buf, 0, n); left -= n }
                readLine(sin)
                if (out.size() > MAX_BODY) break
            }
            return String(out.toByteArray(), StandardCharsets.UTF_8)
        }
        return ""
    }

    private fun readLine(sin: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = sin.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString()
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > 2048) return sb.toString()
        }
    }

    private fun json(s: String): Response {
        val bytes = s.toByteArray(StandardCharsets.UTF_8)
        return cors(newFixedLengthResponse(Status.OK, "application/json; charset=utf-8", ByteArrayInputStream(bytes), bytes.size.toLong()))
    }

    private fun cors(r: Response): Response {
        r.addHeader("Access-Control-Allow-Origin", "*")
        r.addHeader("Access-Control-Allow-Headers", "authorization, content-type, x-api-key")
        r.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        // No server-side keep-alive: when a client reuses an idle socket exactly as the server
        // times it out, the client sees "connection reset" — one of the random disconnects.
        r.closeConnection(true)
        return r
    }

    private fun error(st: Status, type: String, msg: String): Response {
        val body = JSONObject().put("error", JSONObject().put("message", msg).put("type", type)
            .put("code", st.requestStatus.toString())).toString().toByteArray(StandardCharsets.UTF_8)
        return cors(newFixedLengthResponse(st, "application/json; charset=utf-8", ByteArrayInputStream(body), body.size.toLong()))
    }
}
