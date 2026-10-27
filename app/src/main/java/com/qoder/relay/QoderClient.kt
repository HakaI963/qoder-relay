package com.qoder.relay

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.Base64

object QoderClient {
    const val WEB_GLOBAL = "https://qoder.com"
    const val WEB_CN = "https://qoder.com.cn"
    const val CLIENT_ID_GLOBAL = "e883ade2-e6e3-4d6d-adf7-f92ceff5fdcb"
    const val CLIENT_ID_CN = "e93fe488-5778-4c35-a6fc-0f54ed7b3139"
    const val UA = "qoder-cli/1.1.79"

    private val jsonT = "application/json; charset=utf-8".toMediaType()

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .callTimeout(150, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    class LoginState(val verifier: String, val nonce: String, val challenge: String, val machineId: String)

    private fun randomBytes(n: Int): ByteArray =
        ByteArray(n).also { java.security.SecureRandom().nextBytes(it) }

    private fun b64u(b: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(b)

    fun loginStart(region: String): Pair<LoginState, String> {
        val verifier = b64u(randomBytes(48))
        val challenge = b64u(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val nonce = b64u(randomBytes(16))
        val machineId = java.util.UUID.randomUUID().toString()
        val st = LoginState(verifier, nonce, challenge, machineId)
        val cid = if (region == "cn") CLIENT_ID_CN else CLIENT_ID_GLOBAL
        val web = if (region == "cn") WEB_CN else WEB_GLOBAL
        val url = "$web/device/selectAccounts?challenge=$challenge&challenge_method=S256" +
            "&nonce=$nonce&machine_id=$machineId&client_id=$cid"
        return st to url
    }

    /** Returns parsed {token, refreshToken, expiresAt} or null if still pending. */
    fun devicePoll(st: LoginState): JSONObject? {
        val url = "https://center.qoder.sh/api/v1/deviceToken/poll?nonce=${st.nonce}" +
            "&verifier=${st.verifier}&challenge_method=S256"
        val req = Request.Builder().url(url).header("User-Agent", UA)
            .header("Accept", "application/json").build()
        client.newCall(req).execute().use { r ->
            if (r.code == 404) return null
            val body = r.body?.string() ?: return null
            if (!r.isSuccessful) throw RuntimeException("poll ${r.code}: ${body.take(160)}")
            return parseTokens(JSONObject(body))
        }
    }

    fun patExchange(pat: String): JSONObject {
        val req = Request.Builder()
            .url("https://openapi.qoder.sh/api/v1/jobToken/exchange")
            .header("Authorization", "Bearer $pat")
            .header("User-Agent", UA)
            .post(JSONObject().put("personal_token", pat).toString().toRequestBody(jsonT))
            .build()
        client.newCall(req).execute().use { r ->
            val body = r.body?.string() ?: "{}"
            if (!r.isSuccessful) throw RuntimeException("patExchange ${r.code}: ${body.take(200)}")
            return parseTokens(JSONObject(body))
        }
    }

    fun deviceRefresh(a: Account): JSONObject? {
        if (a.refreshToken.isEmpty()) return null
        val body = JSONObject()
            .put("refresh_token", a.refreshToken)
            .put("machine_id", a.machineId)
        val req = Request.Builder()
            .url("https://center.qoder.sh/api/v1/deviceToken/refresh")
            .header("User-Agent", UA)
            .post(body.toString().toRequestBody(jsonT))
            .build()
        client.newCall(req).execute().use { r ->
            val txt = r.body?.string() ?: "{}"
            if (!r.isSuccessful) {
                a.status = "auth_error"; a.lastError = "refresh ${r.code}: ${txt.take(140)}"
                return null
            }
            return parseTokens(JSONObject(txt))
        }
    }

    private fun parseTokens(o: JSONObject): JSONObject {
        val token = o.optString("token").ifEmpty { o.optString("access_token") }
        val refresh = o.optString("refresh_token").ifEmpty { o.optString("refreshToken") }
        val expires = when {
            o.has("expires_at") -> parseExpiry(o.get("expires_at"))
            o.has("expiresAt") -> parseExpiry(o.get("expiresAt"))
            o.has("expires_in") -> System.currentTimeMillis() + o.getLong("expires_in") * 1000
            else -> System.currentTimeMillis() + 8 * 3600_000
        }
        return JSONObject().put("token", token).put("refreshToken", refresh).put("expiresAt", expires)
    }

    private fun parseExpiry(v: Any): Long = when (v) {
        is Number -> if (v.toLong() > 1_000_000_000_000L) v.toLong() else v.toLong() * 1000
        is String -> Regex("(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2})").find(v)?.let {
            java.time.OffsetDateTime.parse(if (v.endsWith("Z")) v else v + "Z").toInstant().toEpochMilli()
        } ?: System.currentTimeMillis() + 8 * 3600_000
        else -> System.currentTimeMillis() + 8 * 3600_000
    }

    /** Fetch model keys for an account from the inference gateway. */
    fun listModelKeys(a: Account): List<String> {
        for (path in listOf("/api/v2/model/list", "/algo/api/v2/model/list")) {
            val req = Request.Builder()
                .url("https://api3.qoder.sh$path")
                .header("Authorization", "Bearer ${a.token}")
                .header("User-Agent", UA)
                .build()
            val resp = try { client.newCall(req).execute() } catch (_: Exception) { continue }
            resp.use { r ->
                if (!r.isSuccessful) return@use
                val txt = r.body?.string() ?: return@use
                return extractKeys(JSONObject(txt))
            }
        }
        return emptyList()
    }

    private fun extractKeys(o: JSONObject): List<String> {
        val arr = o.optJSONArray("data") ?: o.optJSONArray("models") ?: JSONArray()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            if (m.has("is_enabled") && !m.getBoolean("is_enabled")) continue
            val k = m.optString("key").ifEmpty { m.optString("id") }
            if (k.isNotEmpty()) out.add(k)
        }
        return out
    }

    /** Open POST stream at the chat generation endpoint; caller closes response. */
    fun openChat(a: Account, payload: JSONObject): okhttp3.Response {
        val url = firstInferBase() + "/algo/api/v2/service/pro/sse/agent_chat_generation"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${a.token}")
            .header("Accept", "text/event-stream")
            .header("Content-Type", "application/json")
            .header("X-Request-ID", payload.getJSONObject("metadata").getJSONObject("context").getString("request_id"))
            .header("X-Session-ID", payload.getJSONObject("metadata").getJSONObject("context").getString("session_id"))
            .header("User-Agent", UA)
            .post(payload.toString().toRequestBody(jsonT))
            .build()
        return client.newCall(req).execute()
    }

    @Volatile private var inferBase: String? = null
    fun firstInferBase(): String {
        inferBase?.let { return it }
        // Discovery would go through /api/v3/service/region/endpoints; default to api1 (observed elected endpoint).
        for (h in listOf("api1.qoder.sh", "api3.qoder.sh")) {
            try {
                val req = Request.Builder().url("https://$h/algo/api/v1/ping").build()
                client.newCall(req).execute().use { r ->
                    if (r.code < 500) { inferBase = "https://$h"; return inferBase!! }
                }
            } catch (_: Exception) { }
        }
        inferBase = "https://api1.qoder.sh"
        return inferBase!!
    }

    /** Raw diagnostic probe against the generation endpoint; emits each SSE line, returns a summary. */
    fun probeChat(a: Account, model: String, prompt: String, onLine: (String) -> Unit): String {
        val reqId = java.util.UUID.randomUUID().toString()
        val sessId = java.util.UUID.randomUUID().toString()
        val payload = JSONObject()
            .put("model", model)
            .put("messages", org.json.JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            .put("stream", true)
            .put("stream_options", JSONObject().put("include_usage", true))
            .put("metadata", JSONObject().put("context", JSONObject()
                .put("request_id", reqId).put("request_set_id", reqId)
                .put("session_id", sessId).put("client_type", "cli")))
        val sb = StringBuilder()
        try {
            val resp = openChat(a, payload)
            resp.use { r ->
                sb.append("HTTP ${r.code} ${r.message}\n")
                r.headers.forEach { (k, v) -> if (k in setOf("content-type", "content-encoding", "x-request-id", "server")) sb.append("hdr $k: $v\n") }
                val body = r.body
                if (body == null) { sb.append("(no body)\n"); return sb.toString() }
                val reader = java.io.BufferedReader(java.io.InputStreamReader(body.byteStream(), Charsets.UTF_8))
                var n = 0
                while (n < 60) {
                    val line = reader.readLine() ?: break
                    onLine(line.take(400)); n++
                    if (line.contains("[DONE]")) break
                }
                if (n == 0) sb.append("(stream produced no lines)\n")
            }
        } catch (e: Exception) {
            sb.append("EXCEPTION ${e.javaClass.simpleName}: ${e.message}\n")
        }
        return sb.toString()
    }
}
