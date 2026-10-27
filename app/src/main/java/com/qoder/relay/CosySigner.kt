package com.qoder.relay

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Runs the original qoder_auth wasm module (fully inlined HTML asset, no network) inside a hidden
 * WebView. Produces the COSY-signed URL/headers/encrypted body the gateway requires.
 */
class CosySigner(private val app: RelayApp) {
    private val main = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    @Volatile var lastState: String = "init"
        private set
    @Volatile var lastError: String = ""
        private set

    fun resetTransport() {
        onMain {
            try { web?.destroy() } catch (_: Exception) { }
            web = null
            lastState = "init"
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWeb() {
        if (web != null) return
        val w = WebView(app)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = false
        w.setWebChromeClient(object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                Log.d("CosySigner", "${m.messageLevel()}: ${m.message()} @${m.sourceId()}:${m.lineNumber()}")
                return true
            }
        })
        val html = app.assets.open("cosy_page.html").bufferedReader().use { it.readText() }
        w.loadDataWithBaseURL("https://cosy.local/", html, "text/html", "UTF-8", null)
        web = w
        lastState = "loading"
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun evalSync(js: String, timeoutMs: Long = 20000): String? {
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
        return try { org.json.JSONTokener(v).nextValue().toString() } catch (_: Exception) { v }
    }

    private fun waitReady(timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        var last = ""
        while (System.currentTimeMillis() < end) {
            val st = evalSync("window.__state", 4000) ?: "unreachable"
            last = st
            lastState = st
            if (st == "ready") return true
            if (st.startsWith("error")) {
                lastError = st
                throw IllegalStateException(st.removePrefix("error: ").trim().ifEmpty { st })
            }
            try { Thread.sleep(150) } catch (_: InterruptedException) { return false }
        }
        lastError = "state=$last after ${timeoutMs}ms"
        return false
    }

    fun genFields(accessToken: String, refreshToken: String, uid: String, machineId: String): JSONObject {
        if (!waitReady(25000)) throw IllegalStateException("cosy wasm not ready (${lastState})")
        val arg = JSONObject()
            .put("accessToken", accessToken).put("refreshToken", refreshToken)
            .put("uid", uid).put("machineId", machineId).toString()
        val res = evalSync("window.__genFields(" + JSONObject.quote(arg) + ")")
            ?: throw IllegalStateException("genFields returned null")
        val o = JSONObject(res)
        if (!o.optBoolean("ok")) throw IllegalStateException(o.optString("err"))
        return JSONObject(o.getString("out"))
    }

    fun reset(machineId: String, uid: String, enc: String, key: String) {
        evalSync("window.__reset(" + listOf(machineId, uid, enc, key).joinToString(",") { JSONObject.quote(it) } + ")")
    }

    fun signInfer(endpoint: String, bodyJson: String, modelKey: String, modelSource: String): JSONObject {
        if (!waitReady(25000)) throw IllegalStateException("cosy wasm not ready (${lastState})")
        val args = listOf(endpoint, bodyJson, modelKey, modelSource).joinToString(",") { JSONObject.quote(it) }
        val res = evalSync("window.__signInfer($args)")
            ?: throw IllegalStateException("signInfer returned null")
        val o = JSONObject(res)
        if (!o.optBoolean("ok")) throw IllegalStateException(o.optString("err"))
        return o
    }

    fun signRequest(endpoint: String, path: String, method: String): JSONObject {
        if (!waitReady(25000)) throw IllegalStateException("cosy wasm not ready (${lastState})")
        val args = listOf(endpoint, path, method).joinToString(",") { JSONObject.quote(it) }
        val res = evalSync("window.__signRequest($args)")
            ?: throw IllegalStateException("signRequest returned null")
        val o = JSONObject(res)
        if (!o.optBoolean("ok")) throw IllegalStateException(o.optString("err"))
        return o
    }
}
