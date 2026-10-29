package com.qoder.relay

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Thin shell around the dashboard: the UI itself is `assets/dashboard.html`, served by the relay at
 * `/ui`, so the exact same console works inside the app and in any browser on the LAN. The only
 * thing native here is what a web page cannot do: hand out the relay key, open the browser login,
 * copy to the system clipboard and control the foreground service.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var root: FrameLayout
    private lateinit var spinner: ProgressBar
    private var failing = false

    private val app get() = applicationContext as RelayApp

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Relay_Midnight)
        super.onCreate(savedInstanceState)
        RelayService.start(this)
        app.ensureServer()
        askNotificationPermission()

        root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#0B0D10"))
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            setBackgroundColor(Color.parseColor("#0B0D10"))
            isVerticalScrollBarEnabled = false
            addJavascriptInterface(Bridge(), "Android")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    spinner.visibility = View.GONE
                    if (failing) showOfflineScreen()
                }

                override fun onReceivedError(
                    view: WebView?, req: WebResourceRequest?, err: WebResourceError?
                ) {
                    if (req?.isForMainFrame == true) {
                        failing = true
                        spinner.visibility = View.GONE
                        showOfflineScreen()
                    }
                }
            }
        }
        spinner = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.VISIBLE
        }
        root.addView(web, FrameLayout.LayoutParams(-1, -1))
        root.addView(spinner, FrameLayout.LayoutParams(-2, -2, android.view.Gravity.CENTER))
        setContentView(root)
        load()
    }

    private fun load() {
        runOnUiThread {
            failing = false
            spinner.visibility = View.VISIBLE
            web.loadUrl("http://127.0.0.1:${app.port}/ui")
        }
    }

    /** The server is reachable only if it is actually running — say so loudly instead of a blank page. */
    private fun showOfflineScreen() {
        val html = """
            <html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>
              body{background:#0B0D10;color:#E6E8EB;font:15px/1.5 system-ui;display:flex;
                   align-items:center;justify-content:center;height:100vh;margin:0}
              .card{max-width:420px;padding:28px;text-align:center}
              h1{font-size:19px;margin:0 0 6px} p{color:#9AA3AD;margin:0 0 18px}
              button{background:#E6E8EB;color:#0B0D10;border:0;border-radius:10px;
                     padding:11px 18px;font-size:14px;font-weight:600;margin:0 6px}
              code{color:#8AB4F8}
            </style></head><body><div class="card">
            <h1>Relay is not answering on <code>127.0.0.1:${app.port}</code></h1>
            <p>${esc(app.serverError.ifEmpty { "the HTTP server is stopped or the port is busy" })}</p>
            <button onclick="Android.retry()">Retry</button>
            <button onclick="Android.changePort()">Change port</button>
            </div></body></html>
        """.trimIndent()
        web.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    override fun onResume() {
        super.onResume()
        web.evaluateJavascript("window.__onResume && window.__onResume()", null)
    }

    @Suppress("DEPRECATION")
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && web.canGoBack()) { web.goBack(); return true }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    private fun askNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            try { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1) }
            catch (_: Exception) { }
        }
    }

    inner class Bridge {
        @JavascriptInterface fun relayKey(): String = app.relayKey
        @JavascriptInterface fun version(): String = RelayApp.VERSION
        @JavascriptInterface fun port(): Int = app.port
        @JavascriptInterface fun isRunning(): Boolean = app.isRunning()
        @JavascriptInterface fun lan(): String = app.lanAddress()

        /** Keeps the native status bar / window background in sync with the page theme. */
        @JavascriptInterface
        fun setChrome(bg: String, dark: Boolean) {
            runOnUiThread {
                try {
                    val c = Color.parseColor(bg)
                    window.statusBarColor = c
                    root.setBackgroundColor(c)
                    @Suppress("DEPRECATION")
                    val flag = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    web.systemUiVisibility = if (dark) web.systemUiVisibility and flag.inv()
                    else web.systemUiVisibility or flag
                } catch (_: Exception) { }
            }
        }

        @JavascriptInterface
        fun openUrl(url: String) {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            catch (_: Exception) { toastOnUi("cannot open: $url") }
        }

        @JavascriptInterface
        fun copy(text: String) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("qoder-relay", text))
            toastOnUi("copied")
        }

        @JavascriptInterface fun toast(text: String) = toastOnUi(text)

        @JavascriptInterface
        fun startServer() {
            app.ensureServer()
            toastOnUi(if (app.isRunning()) "server running on :${app.port}" else "failed: ${app.serverError}")
            runOnUiThread { load() }
        }

        @JavascriptInterface
        fun stopServer() {
            app.stopServer()
            toastOnUi("server stopped")
        }

        @JavascriptInterface
        fun setPort(p: Int) {
            app.changePort(p)
            toastOnUi("port → $p, restarting…")
            web.postDelayed({ load() }, 1200)
        }

        @JavascriptInterface
        fun retry() {
            app.ensureServer()
            load()
        }

        @JavascriptInterface
        fun changePort() {
            runOnUiThread {
                val e = EditText(this@MainActivity).apply {
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER
                    setText(app.port.toString())
                }
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Listen port").setView(e)
                    .setPositiveButton("Apply") { _, _ ->
                        val p = e.text.toString().toIntOrNull() ?: return@setPositiveButton
                        setPort(p)
                    }
                    .setNegativeButton("Cancel", null).show()
            }
        }

        private fun toastOnUi(text: String) = runOnUiThread {
            Toast.makeText(this@MainActivity, text, Toast.LENGTH_SHORT).show()
        }
    }
}
