package com.qoder.relay

import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit

object QoderClient {
    const val WEB_GLOBAL = "https://qoder.com"
    const val WEB_CN = "https://qoder.com.cn"
    const val CLIENT_ID_GLOBAL = "e883ade2-e6e3-4d6d-adf7-f92ceff5fdcb"
    const val CLIENT_ID_CN = "e93fe488-5778-4c35-a6fc-0f54ed7b3139"
    const val UA = "qoder-cli/1.1.79"
    const val API_BASE = "https://api3.qoder.sh"

    private val jsonT = "application/json; charset=utf-8".toMediaType()

    /** Serialises COSY signing: the wasm context holds one account at a time. */
    private val signLock = Any()

    /**
     * Short-lived pooled connections. The old 4-minute pool kept sockets that were silently dead
     * after Doze / a Wi-Fi ↔ mobile switch, so the next request hung until timeout = "disconnect".
     * We also evict the pool whenever the network changes (see [onNetworkChanged]).
     */
    private val pool = ConnectionPool(5, 30, TimeUnit.SECONDS)

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(pool)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** Inference: no call timeout (long answers are fine); the caller arms a header timeout. */
    private val streamClient: OkHttpClient = client.newBuilder()
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)   // detects dead HTTP/2 connections mid-stream
        .build()

    fun onNetworkChanged() {
        runCatching { pool.evictAll() }
    }

    class LoginState(val verifier: String, val nonce: String, val challenge: String, val machineId: String)

    private fun randomBytes(n: Int): ByteArray = ByteArray(n).also { java.security.SecureRandom().nextBytes(it) }
    private fun b64u(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

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

    fun devicePoll(st: LoginState): JSONObject? {
        val url = "https://center.qoder.sh/api/v1/deviceToken/poll?nonce=${st.nonce}" +
            "&verifier=${st.verifier}&challenge_method=S256"
        val req = Request.Builder().url(url).header("User-Agent", UA).header("Accept", "application/json").build()
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
            if (!r.isSuccessful) throw RuntimeException("PAT exchange ${r.code}: ${body.take(200)}")
            return parseTokens(JSONObject(body))
        }
    }

    /** @return new tokens, or null with [Account.lastError] set. Throws on network errors (= transient). */
    private fun deviceRefresh(a: Account): JSONObject? {
        if (a.refreshToken.isEmpty()) { a.lastError = "no refresh token"; return null }
        val body = JSONObject().put("refresh_token", a.refreshToken).put("machine_id", a.machineId)
        val req = Request.Builder()
            .url("https://center.qoder.sh/api/v1/deviceToken/refresh")
            .header("User-Agent", UA)
            .post(body.toString().toRequestBody(jsonT))
            .build()
        client.newCall(req).execute().use { r ->
            val txt = r.body?.string() ?: "{}"
            if (!r.isSuccessful) { a.lastError = "refresh ${r.code}: ${txt.take(140)}"; return null }
            return parseTokens(JSONObject(txt))
        }
    }

    /**
     * Ensure the account has a usable token. Locked per account: refresh tokens rotate, and the
     * old code let two concurrent requests refresh at once — the loser invalidated the account.
     * [force] = the gateway just rejected the current token.
     * @return true if the account has a token worth trying.
     */
    fun ensureToken(a: Account, force: Boolean = false): Boolean = synchronized(a.authLock) {
        val fresh = a.token.isNotEmpty() && a.expiresAt > System.currentTimeMillis() + 5 * 60_000
        if (fresh && !force) return true
        val issuedBefore = a.token
        val t = try {
            if (a.kind == "pat" && a.pat.isNotEmpty()) patExchange(a.pat) else deviceRefresh(a)
        } catch (e: Exception) {
            // network problem: keep the old token, it may still be valid
            a.lastError = "refresh: ${e.message}"
            return a.token.isNotEmpty() && !force
        }
        if (t == null || t.optString("token").isEmpty()) {
            if (a.token == issuedBefore) a.status = "auth_error"
            return false
        }
        a.token = t.getString("token")
        a.refreshToken = t.optString("refreshToken").ifEmpty { a.refreshToken }
        a.expiresAt = t.getLong("expiresAt")
        a.needsFields = true
        a.status = "active"; a.lastError = ""
        true
    }

    private fun parseTokens(o: JSONObject): JSONObject {
        val d = o.optJSONObject("data") ?: o
        val token = d.optString("token").ifEmpty { d.optString("device_token") }.ifEmpty { d.optString("access_token") }
        val refresh = d.optString("refresh_token").ifEmpty { d.optString("refreshToken") }
        val expires = when {
            d.has("expires_at") -> parseExpiry(d.get("expires_at"))
            d.has("expiresAt") -> parseExpiry(d.get("expiresAt"))
            d.has("expires_in") -> System.currentTimeMillis() + d.getLong("expires_in") * 1000
            else -> System.currentTimeMillis() + 8 * 3600_000
        }
        return JSONObject().put("token", token).put("refreshToken", refresh).put("expiresAt", expires)
    }

    private fun parseExpiry(v: Any): Long = when (v) {
        is Number -> if (v.toLong() > 1_000_000_000_000L) v.toLong() else v.toLong() * 1000
        is String -> v.toLongOrNull()?.let { parseExpiry(it) } ?: try {
            java.time.OffsetDateTime.parse(if (v.endsWith("Z") || v.contains("+")) v else v + "Z").toInstant().toEpochMilli()
        } catch (_: Exception) { System.currentTimeMillis() + 8 * 3600_000 }
        else -> System.currentTimeMillis() + 8 * 3600_000
    }

    private fun fetchUid(token: String): String {
        val req = Request.Builder()
            .url("https://center.qoder.sh/api/v1/userinfo")
            .header("Authorization", "Bearer $token")
            .header("User-Agent", UA)
            .build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return ""
            val o = JSONObject(r.body?.string() ?: "{}")
            return o.optString("id").ifEmpty { o.optJSONObject("data")?.optString("id") ?: "" }
        }
    }

    fun ensureCosyFields(a: Account, signer: CosySigner) {
        if (!a.needsFields && a.encInfo.isNotEmpty() && a.cosyKey.isNotEmpty()) return
        if (a.uid.isEmpty()) a.uid = fetchUid(a.token)
        val fields = synchronized(signLock) { signer.genFields(a.token, a.refreshToken, a.uid, a.machineId) }
        a.encInfo = fields.optString("encrypt_user_info")
        a.cosyKey = fields.optString("key")
        a.needsFields = false
    }

    /** Fetch the model list for an account. Returns key -> display name. */
    fun listModels(a: Account, signer: CosySigner): List<JSONObject> {
        ensureToken(a)
        ensureCosyFields(a, signer)
        val signed = synchronized(signLock) { signer.signRequest(a, API_BASE, "/algo/api/v2/model/list", "GET") }
        val req = Request.Builder().url(signed.getString("url")).signed(signed).get().build()
        client.newCall(req).execute().use { r ->
            if (r.code == 401 || r.code == 403) a.needsFields = true
            if (!r.isSuccessful) throw RuntimeException("models ${r.code}: ${r.body?.string()?.take(160)}")
            val o = JSONObject(r.body?.string() ?: "{}")
            val arr = o.optJSONArray("chat") ?: o.optJSONArray("assistant") ?: JSONArray()
            return (0 until arr.length()).map { arr.getJSONObject(it) }
        }
    }

    private fun Request.Builder.signed(signed: JSONObject): Request.Builder {
        val h = signed.getJSONObject("headers")
        h.keys().forEach { k ->
            if (!k.equals("host", true) && !k.equals("content-length", true)) header(k, h.getString(k))
        }
        header("Accept", "text/event-stream")
        return this
    }

    /**
     * Fire the signed inference request. [headerTimeoutMs] bounds only the wait for response
     * headers, so a stalled account fails over quickly while long streams are unaffected.
     */
    fun openChat(a: Account, signer: CosySigner, wireBody: JSONObject, modelKey: String, headerTimeoutMs: Long): okhttp3.Response {
        ensureCosyFields(a, signer)
        val signed = synchronized(signLock) { signer.signInfer(a, API_BASE, wireBody.toString(), modelKey, "system") }
        val req = Request.Builder()
            .url(signed.getString("url"))
            .signed(signed)
            .post(signed.getString("body").toRequestBody(jsonT))
            .build()
        val call = streamClient.newCall(req)
        // NOTE: OkHttp's call.timeout() cannot be disarmed once the call started (clearTimeout()
        // only changes the value) — the old code therefore killed every stream after 45 s.
        // A separate watchdog that we cancel as soon as headers arrive bounds only the header wait.
        val watchdog = timer.schedule({ call.cancel() }, headerTimeoutMs, TimeUnit.MILLISECONDS)
        try {
            return call.execute()
        } catch (e: Exception) {
            if (watchdog.isDone) throw java.io.InterruptedIOException("timeout: no response headers in ${headerTimeoutMs / 1000}s")
            throw e
        } finally {
            watchdog.cancel(false)
        }
    }

    private val timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "header-watchdog").apply { isDaemon = true }
    }
}
