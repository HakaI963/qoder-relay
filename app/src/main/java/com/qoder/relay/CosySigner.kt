package com.qoder.relay

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Runs the original qoder_auth wasm module (served from our local NanoHTTPD) inside a hidden
 * WebView. Produces the COSY-signed URL/headers/encrypted body that the gateway requires.
 */
class CosySigner(private val app: RelayApp) {
    private val main = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private var loaded = false

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWeb() {
        if (web != null) return
        val w = WebView(app)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = false
        w.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        w.addJavascriptInterface(object {
            @JavascriptInterface fun version(): String = "1"
        }, "RelayHost")
        w.loadUrl("http://127.0.0.1:" + app.port + "/cosy/sign.html")
        web = w
        loaded = false
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun evalSync(js: String, timeoutMs: Long = 15000): String? {
        var result: String? = null
        val latch = CountDownLatch(1)
        onMain {
            ensureWeb()
            web?.evaluateJavascript(js) { value ->
                result = unquoteJs(value)
                latch.countDown()
            } ?: latch.countDown()
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return result
    }

    private fun unquoteJs(v: String?): String? {
        if (v == null || v == "null") return null
        return try {
            val t = org.json.JSONTokener(v)
            t.nextValue().toString()
        } catch (_: Exception) { v }
    }

    private fun waitReady(timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val st = evalSync("window.__state", 3000) ?: ""
            if (st == "ready") return true
            if (st.startsWith("error")) throw IllegalStateException("cosy wasm: $st")
            try { Thread.sleep(200) } catch (_: InterruptedException) { return false }
        }
        return false
    }

    /** Regenerate the per-account auth fields. Returns {encrypt_user_info, key}. */
    fun genFields(accessToken: String, refreshToken: String, uid: String, machineId: String): JSONObject {
        if (!waitReady(15000)) throw IllegalStateException("cosy wasm not ready")
        val arg = JSONObject()
            .put("accessToken", accessToken).put("refreshToken", refreshToken)
            .put("uid", uid).put("machineId", machineId).toString()
        val res = evalSync("window.__genFields(" + JSONObject.quote(arg) + ")", 20000)
            ?: throw IllegalStateException("genFields returned null")
        val o = JSONObject(res)
        if (!o.optBoolean("ok")) throw IllegalStateException(o.optString("err"))
        return JSONObject(o.getString("out"))
    }

    fun reset(machineId: String, uid: String, enc: String, key: String) {
        evalSync("window.__reset(" + listOf(machineId, uid, enc, key).joinToString(",") { JSONObject.quote(it) } + ")")
    }

    /** Signed inference request. */
    fun signInfer(endpoint: String, bodyJson: String, modelKey: String, modelSource: String): JSONObject {
        if (!waitReady(15000)) throw IllegalStateException("cosy wasm not ready")
        val args = listOf(endpoint, bodyJson, modelKey, modelSource).joinToString(",") { JSONObject.quote(it) }
        val res = evalSync("window.__signInfer($args)", 20000)
            ?: throw IllegalStateException("signInfer returned null")
        val o = JSONObject(res)
        if (!o.optBoolean("ok")) throw IllegalStateException(o.optString("err"))
        return o
    }

    /** Signed plain API request, e.g. GET /algo/api/v2/model/list. */
    fun signRequest(endpoint: String, path: String, method: String): JSONObject {
        if (!waitReady(15000)) throw IllegalStateException("cosy wasm not ready")
        val args = listOf(endpoint, path, method).joinToString(",") { JSONObject.quote(it) }
        val res = evalSync("window.__signRequest($args)", 20000)
            ?: throw IllegalStateException("signRequest returned null")
        val o = JSONObject(res)
        if (!o.optBoolean("ok")) throw IllegalStateException(o.optString("err"))
        return o
    }
}
