package com.qoder.relay

import android.app.Application
import android.content.Context
import fi.iki.elonen.NanoHTTPD
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class RelayApp : Application() {

    lateinit var repo: AccountRepo
        private set
    lateinit var engine: RelayEngine
        private set
    val signer: CosySigner by lazy { CosySigner(this) }
    var relayKey: String = ""
        private set
    var port: Int = RelayServer.PORT_DEFAULT
    var server: RelayServer? = null

    /** epoch ms of process start — surfaced in the dashboard. */
    var startedAt: Long = System.currentTimeMillis()
        private set

    /** requests currently being served (streaming included). */
    val inFlight = AtomicInteger(0)

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("relay", Context.MODE_PRIVATE)
        relayKey = prefs.getString("key", null) ?: ("sk-relay-" + UUID.randomUUID().toString().replace("-", "")).also {
            prefs.edit().putString("key", it).apply()
        }
        port = prefs.getInt("port", RelayServer.PORT_DEFAULT)
        repo = AccountRepo(this).apply { load() }
        engine = RelayEngine(repo)
        loadTuning(prefs)
        signer.prewarm()
        // warm COSY fields for the first enabled account so request #1 is not a cold start
        Thread {
            try {
                if (signer.ready(30_000)) {
                    engine.usableFor(null).firstOrNull()?.let { QoderClient.ensureCosyFields(it, signer) }
                }
            } catch (_: Exception) { }
        }.apply { isDaemon = true; name = "cosy-warm"; start() }
    }

    fun startServer(): RelayServer {
        server?.stop()
        val s = RelayServer(this, engine, port)
        s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        server = s
        startedAt = System.currentTimeMillis()
        serverError = ""
        return s
    }

    var serverError: String = ""
    fun isRunning(): Boolean = server != null

    /**
     * Port changes / restarts are deferred: they must never be executed from inside a request
     * thread of the very server being restarted, or the dashboard's own POST would be cut off
     * before it can reply.
     */
    fun changePort(newPort: Int) {
        if (newPort in 1024..65534 && newPort != port) {
            port = newPort
            getSharedPreferences("relay", Context.MODE_PRIVATE).edit().putInt("port", newPort).apply()
            restartSoon()
        }
    }

    fun restartSoon(delayMs: Long = 400) {
        Thread {
            Thread.sleep(delayMs)
            try { server?.stop() } catch (_: Exception) { }
            server = null
            try { signer.resetTransport() } catch (_: Exception) { }
            ensureServer()
        }.apply { isDaemon = true; name = "relay-restart"; start() }
    }

    fun stopServer() {
        try { server?.stop() } catch (_: Exception) { }
        server = null
    }

    fun stopServerAsync() {
        Thread {
            Thread.sleep(400)
            stopServer()
        }.apply { isDaemon = true; name = "relay-stop"; start() }
    }

    fun regenerateKey() {
        relayKey = "sk-relay-" + UUID.randomUUID().toString().replace("-", "")
        getSharedPreferences("relay", Context.MODE_PRIVATE).edit().putString("key", relayKey).apply()
    }

    fun ensureServer() {
        if (server != null) return
        try { startServer() } catch (e: Exception) {
            serverError = e.message ?: "start failed"
        }
    }

    fun loadTuning(prefs: android.content.SharedPreferences) {
        engine.firstTokenMs = prefs.getLong("firstTokenMs", engine.firstTokenMs)
        engine.cooldownMs = prefs.getLong("cooldownMs", engine.cooldownMs)
        engine.overallMs = prefs.getLong("overallMs", engine.overallMs)
        engine.heartbeatMs = prefs.getLong("heartbeatMs", engine.heartbeatMs)
        engine.maxAttempts = prefs.getInt("maxAttempts", engine.maxAttempts)
    }

    fun saveTuning() {
        getSharedPreferences("relay", Context.MODE_PRIVATE).edit()
            .putLong("firstTokenMs", engine.firstTokenMs)
            .putLong("cooldownMs", engine.cooldownMs)
            .putLong("overallMs", engine.overallMs)
            .putLong("heartbeatMs", engine.heartbeatMs)
            .putInt("maxAttempts", engine.maxAttempts)
            .apply()
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

    companion object {
        const val VERSION = "0.2.5"
    }
}
