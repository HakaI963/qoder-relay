package com.qoder.relay

import android.app.Application
import android.content.Context
import fi.iki.elonen.NanoHTTPD
import java.util.UUID

class RelayApp : Application() {
    lateinit var repo: AccountRepo
        private set
    lateinit var engine: RelayEngine
        private set
    var relayKey: String = ""
        private set
    var port: Int = RelayServer.PORT_DEFAULT
    var server: RelayServer? = null

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("relay", Context.MODE_PRIVATE)
        relayKey = prefs.getString("key", null) ?: ("sk-relay-" + UUID.randomUUID().toString().replace("-", "")).also {
            prefs.edit().putString("key", it).apply()
        }
        port = prefs.getInt("port", RelayServer.PORT_DEFAULT)
        repo = AccountRepo(this).apply { load() }
        engine = RelayEngine(repo)
    }

    fun startServer(): RelayServer {
        server?.stop()
        val s = RelayServer(this, engine, port)
        s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        server = s
        serverError = ""
        return s
    }

    var serverError: String = ""
    fun isRunning(): Boolean = server != null

    fun changePort(newPort: Int) {
        if (newPort in 1024..65534 && newPort != port) {
            port = newPort
            getSharedPreferences("relay", Context.MODE_PRIVATE).edit().putInt("port", newPort).apply()
            server?.stop(); server = null; ensureServer()
        }
    }

    fun stopServer() {
        try { server?.stop() } catch (_: Exception) { }
        server = null
    }

    fun regenerateKey() {
        relayKey = "sk-relay-" + UUID.randomUUID().toString().replace("-", "")
        getSharedPreferences("relay", Context.MODE_PRIVATE).edit().putString("key", relayKey).apply()
    }

    fun ensureServer() {
        if (server != null) return
        try { startServer() } catch (e: Exception) {
            serverError = e.message ?: "start failed"
            // one retry on the next request
        }
    }

    fun lanAddress(): String = try {
        val ifaces = java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
        val good = ifaces.firstOrNull { it.isUp && !it.isLoopback &&
            (it.name.startsWith("wlan") || it.name.startsWith("ap") || it.name.startsWith("eth") || it.name.startsWith("rndis")) }
            ?: ifaces.firstOrNull { it.isUp && !it.isLoopback }
        good?.let { g ->
            java.util.Collections.list(g.inetAddresses)
                .firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }?.hostAddress
        } ?: ""
    } catch (_: Exception) { "" }
}
