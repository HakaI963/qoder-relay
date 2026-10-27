package com.qoder.relay

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as RelayApp
        RelayService.start(this)
        val web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.addJavascriptInterface(object {
            @JavascriptInterface fun key(): String = app.relayKey
            @JavascriptInterface fun port(): Int = app.port
            @JavascriptInterface fun lan(): String = app.lanAddress()
            @JavascriptInterface fun openUrl(url: String) {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { }
            }
        }, "Relay")
        web.loadUrl("http://127.0.0.1:${app.port}/")
    }
}
