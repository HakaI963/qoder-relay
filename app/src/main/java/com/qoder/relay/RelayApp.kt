package com.qoder.relay

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class RelayApp : Application() {

    lateinit var repo: AccountRepo
        private set
    lateinit var engine: RelayEngine
        private set
    val signer: CosySigner by lazy { CosySigner(this) }

    private val prefs by lazy { getSharedPreferences("relay", Context.MODE_PRIVATE) }

    var relayKey: String = ""
        private set
    var port: Int = RelayServer.PORT_DEFAULT
        private set
    /** When off, LAN clients may call without the API key (handy for trusted home networks). */
    var requireKey: Boolean = true
        private set
    /** User intent: should the relay be running? Survives restarts, used by boot receiver + watchdog. */
    var wantRunning: Boolean
        get() = prefs.getBoolean("wantRunning", true)
        set(v) { prefs.edit().putBoolean("wantRunning", v).apply() }

    @Volatile var server: RelayServer? = null
        private set
    @Volatile var serverError: String = ""
        private set
    @Volatile var startedAt: Long = 0
        private set
    @Volatile var lastActivity: Long = 0
        private set
    val inFlight = AtomicInteger(0)

    /** UI listeners (main thread not guaranteed). */
    val listeners = mutableSetOf<() -> Unit>()
    fun notifyChanged() = synchronized(listeners) { listeners.toList() }.forEach { runCatching { it() } }

    override fun onCreate() {
        super.onCreate()
        relayKey = prefs.getString("key", null) ?: newKey().also { prefs.edit().putString("key", it).apply() }
        port = prefs.getInt("port", RelayServer.PORT_DEFAULT)
        requireKey = prefs.getBoolean("requireKey", true)
        repo = AccountRepo(this).apply { load() }
        engine = RelayEngine(repo)
        // statuses from a previous process are stale — give every account a fresh chance
        repo.accounts.forEach { if (it.status == "error") it.status = "unknown"; it.cooldownUntil = 0 }
        signer.prewarm()
        watchNetwork()
        Thread {
            runCatching {
                if (signer.ready(30_000)) repo.accounts.firstOrNull { it.enabled }?.let {
                    QoderClient.ensureToken(it); QoderClient.ensureCosyFields(it, signer); repo.save()
                }
            }
        }.apply { isDaemon = true; name = "cosy-warm" }.start()
    }

    @Synchronized
    fun ensureServer(): Boolean {
        val s = server
        if (s != null && s.isAlive) return true
        runCatching { s?.stop() }
        server = null
        return try {
            val n = RelayServer(this, engine, port)
            n.start(RelayServer.SOCKET_TIMEOUT_MS, false)
            server = n
            startedAt = System.currentTimeMillis()
            serverError = ""
            true
        } catch (e: Exception) {
            serverError = if (e is java.net.BindException) "Port $port is already in use" else (e.message ?: "start failed")
            false
        }.also { notifyChanged() }
    }

    @Synchronized
    fun stopServer() {
        runCatching { server?.stop() }
        server = null
        notifyChanged()
    }

    fun isRunning(): Boolean = server?.isAlive == true

    fun setPort(p: Int): Boolean {
        if (p !in 1024..65535) return false
        if (p == port) return true
        port = p
        prefs.edit().putInt("port", p).apply()
        if (server != null) { stopServer(); ensureServer() }
        return true
    }

    fun setRequireKey(v: Boolean) {
        requireKey = v
        prefs.edit().putBoolean("requireKey", v).apply()
    }

    fun regenerateKey() {
        relayKey = newKey()
        prefs.edit().putString("key", relayKey).apply()
    }

    fun markActivity() { lastActivity = System.currentTimeMillis() }

    private fun newKey() = "sk-relay-" + UUID.randomUUID().toString().replace("-", "")

    /** Drop pooled upstream sockets whenever the network changes — stale sockets were a main cause of hangs. */
    private fun watchNetwork() {
        runCatching {
            val cm = getSystemService(ConnectivityManager::class.java)
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { QoderClient.onNetworkChanged(); notifyChanged() }
                override fun onLost(network: Network) { QoderClient.onNetworkChanged(); notifyChanged() }
            })
        }
    }

    fun lanAddress(): String = try {
        val ifaces = Collections.list(NetworkInterface.getNetworkInterfaces()).filter { it.isUp && !it.isLoopback }
        val pick = ifaces.sortedBy { i ->
            when {
                i.name.startsWith("wlan") -> 0
                i.name.startsWith("ap") || i.name.startsWith("swlan") -> 1
                i.name.startsWith("eth") || i.name.startsWith("rndis") -> 2
                else -> 3
            }
        }
        pick.firstNotNullOfOrNull { i ->
            Collections.list(i.inetAddresses).firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
        } ?: ""
    } catch (_: Exception) { "" }

    companion object {
        const val VERSION = "1.0.0"
    }
}
