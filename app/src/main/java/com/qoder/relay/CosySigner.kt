package com.qoder.relay

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs the qoder_auth wasm module (fully inlined HTML asset, no network) inside a hidden WebView and
 * produces COSY-signed URL/headers/body.
 *
 * Self-healing: when the app is in the background Android may kill the WebView renderer. Without
 * [WebViewClient.onRenderProcessGone] that kills the whole app process (= relay "disconnects").
 * We now swallow that, drop the dead WebView and transparently rebuild it on the next request.
 */
class CosySigner(private val app: RelayApp) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var web: WebView? = null
    @Volatile var lastState: String = "init"
        private set
    @Volatile var lastError: String = ""
        private set

    fun prewarm() = onMain { runCatching { ensureWeb() } }

    fun ready(timeoutMs: Long = 25_000): Boolean = waitReady(timeoutMs)

    /** Drop the WebView; the next call rebuilds it. */
    fun recycle() = onMain {
        runCatching { web?.destroy() }
        web = null
        lastState = "init"
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWeb() {
        if (web != null) return
        val w = WebView(app)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = false
        w.webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                lastError = "renderer gone (crash=${detail?.didCrash()})"
                if (web === view) web = null
                runCatching { view?.destroy() }
                lastState = "init"
                return true   // keep the app (and the relay) alive
            }
        }
        val html = app.assets.open("cosy_page.html").bufferedReader().use { it.readText() }
        w.loadDataWithBaseURL("https://cosy.local/", html, "text/html", "UTF-8", null)
        // keep JS timers running while the app has no visible activity
        w.resumeTimers()
        web = w
        lastState = "loading"
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun evalSync(js: String, timeoutMs: Long = 20_000): String? {
        val result = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)
        onMain {
            try {
                ensureWeb()
                val w = web
                if (w == null) latch.countDown()
                else w.evaluateJavascript(js) { v -> result.set(unquoteJs(v)); latch.countDown() }
            } catch (e: Exception) {
                lastError = "eval: ${e.message}"
                latch.countDown()
            }
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return null
        return result.get()
    }

    private fun unquoteJs(v: String?): String? {
        if (v == null || v == "null") return null
        return try { org.json.JSONTokener(v).nextValue().toString() } catch (_: Exception) { v }
    }

    private fun waitReady(timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        var misses = 0
        var recycled = false
        while (System.currentTimeMillis() < end) {
            val st = evalSync("window.__state", 4000)
            if (st == "ready") { lastState = st; return true }
            if (st == null) misses++ else lastState = st
            // wasm failed to start, or the WebView stopped answering: rebuild once and retry
            if (!recycled && (st?.startsWith("error") == true || misses >= 3)) {
                lastError = st ?: "webview unresponsive"
                recycle(); recycled = true; misses = 0
            }
            try { Thread.sleep(150) } catch (_: InterruptedException) { return false }
        }
        lastError = "signer not ready (state=$lastState)"
        return false
    }

    private fun call(js: String): JSONObject {
        if (!waitReady(25_000)) throw IllegalStateException(lastError)
        val res = evalSync(js) ?: run { recycle(); throw IllegalStateException("signer timed out") }
        val o = JSONObject(res)
        if (!o.optBoolean("ok")) throw IllegalStateException(o.optString("err"))
        return o
    }

    fun genFields(accessToken: String, refreshToken: String, uid: String, machineId: String): JSONObject {
        val arg = JSONObject()
            .put("accessToken", accessToken).put("refreshToken", refreshToken)
            .put("uid", uid).put("machineId", machineId).toString()
        return JSONObject(call("window.__genFields(" + JSONObject.quote(arg) + ")").getString("out"))
    }

    /** Reset the wasm context to [a] and sign in ONE evaluation, so a renderer rebuild can never split them. */
    private fun resetJs(a: Account) =
        "window.__reset(" + listOf(a.machineId, a.uid, a.encInfo, a.cosyKey).joinToString(",") { JSONObject.quote(it) } + ");"

    fun signInfer(a: Account, endpoint: String, bodyJson: String, modelKey: String, modelSource: String): JSONObject =
        call(resetJs(a) + "window.__signInfer(" + listOf(endpoint, bodyJson, modelKey, modelSource).joinToString(",") { JSONObject.quote(it) } + ")")

    fun signRequest(a: Account, endpoint: String, path: String, method: String): JSONObject =
        call(resetJs(a) + "window.__signRequest(" + listOf(endpoint, path, method).joinToString(",") { JSONObject.quote(it) } + ")")
}
