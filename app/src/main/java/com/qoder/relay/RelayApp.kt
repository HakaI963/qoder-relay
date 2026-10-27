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
        return s
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
