package com.qoder.relay

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
    val signLock = Any()

    /** Auth/token/model endpoints: short, bounded, safe to retry on another account. */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(45, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Inference client. There is deliberately **no callTimeout**: a long answer must not be killed
     * mid-stream. Instead the caller arms a per-call *header* timeout (see [openChat]) so a stalled
     * account fails over in seconds, while [readTimeout] only guards the gap between SSE chunks.
     */
    val streamClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(150, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .connectionPool(okhttp3.ConnectionPool(8, 4, TimeUnit.MINUTES))
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
            if (!r.isSuccessful) throw RuntimeException("patExchange ${r.code}: ${body.take(200)}")
            return parseTokens(JSONObject(body))
        }
    }

    fun deviceRefresh(a: Account): JSONObject? {
        if (a.refreshToken.isEmpty()) return null
        val body = JSONObject().put("refresh_token", a.refreshToken).put("machine_id", a.machineId)
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
        val token = o.optString("token").ifEmpty { o.optString("device_token") }
            .ifEmpty { o.optString("access_token") }
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
        is String -> try {
            java.time.OffsetDateTime.parse(if (v.endsWith("Z")) v else v + "Z")
                .toInstant().toEpochMilli()
        } catch (_: Exception) { System.currentTimeMillis() + 30L * 86400_000 }
        else -> System.currentTimeMillis() + 30L * 86400_000
    }

    /** Plain-bearer userinfo (works without COSY): returns the account uid. */
    fun fetchUid(token: String): String {
        val req = Request.Builder()
            .url("https://center.qoder.sh/api/v1/userinfo")
            .header("Authorization", "Bearer $token")
            .header("User-Agent", UA)
            .build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return ""
            return JSONObject(r.body?.string() ?: "{}").optString("id")
        }
    }

    /** Make sure the account has fresh COSY auth fields for its current token. */
    fun ensureCosyFields(a: Account, signer: CosySigner) {
        if (!a.needsFields && a.encInfo.isNotEmpty() && a.cosyKey.isNotEmpty()) return
        if (a.uid.isEmpty()) a.uid = fetchUid(a.token)
        val fields = synchronized(signLock) { signer.genFields(a.token, a.refreshToken, a.uid, a.machineId) }
        a.encInfo = fields.optString("encrypt_user_info")
        a.cosyKey = fields.optString("key")
        a.needsFields = false
    }

    /** Model catalog entry: key -> (displayName, isReasoning). */
    fun listModels(a: Account, signer: CosySigner): List<JSONObject> {
        ensureCosyFields(a, signer)
        val signed = synchronized(signLock) {
            signer.reset(a.machineId, a.uid, a.encInfo, a.cosyKey)
            signer.signRequest(API_BASE, "/algo/api/v2/model/list", "GET")
        }
        val req = Request.Builder()
            .url(signed.getString("url"))
            .apply { applySignedHeaders(signed) }
            .get().build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw RuntimeException("models ${r.code}: ${r.body?.string()?.take(160)}")
            val o = JSONObject(r.body?.string() ?: "{}")
            val arr = o.optJSONArray("chat") ?: o.optJSONArray("assistant") ?: JSONArray()
            return (0 until arr.length()).map { arr.getJSONObject(it) }
        }
    }

    private fun Request.Builder.applySignedHeaders(signed: JSONObject): Request.Builder {
        val h = signed.getJSONObject("headers")
        h.keys().forEach { k ->
            if (!k.equals("host", true) && !k.equals("content-length", true)) header(k, h.getString(k))
        }
        header("Accept", "text/event-stream")
        return this
    }

    /**
     * Prepare and fire the COSY-signed inference request.
     *
     * [headerTimeoutMs] bounds only the wait for upstream response headers (OkHttp's per-call
     * timeout, cleared the moment headers land) — that is what lets the relay fail over from a
     * stalled account in seconds instead of sitting on OkHttp's 360 s call timeout, which is the
     * root cause of clients reporting "Timed out waiting for response headers".
     *
     * @throws java.io.InterruptedIOException when the account is too slow to start answering.
     */
    fun openChat(
        a: Account,
        signer: CosySigner,
        wireBody: JSONObject,
        modelKey: String,
        headerTimeoutMs: Long = 45_000
    ): okhttp3.Response {
        ensureCosyFields(a, signer)
        val payload = wireBody.toString()
        val signed = synchronized(signLock) {
            signer.reset(a.machineId, a.uid, a.encInfo, a.cosyKey)
            signer.signInfer(API_BASE, payload, modelKey, "system")
        }
        val req = Request.Builder()
            .url(signed.getString("url"))
            .apply { applySignedHeaders(signed) }
            .post(signed.getString("body").toRequestBody(jsonT))
            .build()
        val call = streamClient.newCall(req)
        if (headerTimeoutMs > 0) call.timeout().timeout(headerTimeoutMs, TimeUnit.MILLISECONDS)
        val resp = try {
            call.execute()
        } catch (e: Exception) {
            runCatching { call.cancel() }
            throw e
        }
        if (headerTimeoutMs > 0) call.timeout().clearTimeout()
        return resp
    }
}
