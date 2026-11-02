package com.qoder.relay

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * Account actions used by the native UI. Work runs on a background executor; results are posted
 * back on the main thread. Login polling is owned by the app (not the activity), so it keeps going
 * while the user is in the browser.
 */
object AccountOps {
    private val io = Executors.newCachedThreadPool { r -> Thread(r, "account-ops").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    @Volatile var loginInProgress = false
        private set
    @Volatile private var loginCancelled = false

    private fun <T> run(work: () -> T, done: (Result<T>) -> Unit) {
        io.execute {
            val r = runCatching(work)
            main.post { done(r) }
        }
    }

    /** Fetch models (also validates token + signing) and store them on the account. */
    private fun discover(app: RelayApp, a: Account) {
        val entries = QoderClient.listModels(a, app.signer)
        app.engine.rememberModels(a, entries)
        a.status = "active"; a.lastError = ""; a.cooldownUntil = 0
    }

    fun addPat(app: RelayApp, label: String, pat: String, done: (Result<Account>) -> Unit) = run({
        val token = pat.trim()
        require(token.startsWith("pt-")) { "Token must start with pt-" }
        val a = Account(label = label.ifBlank { "pat-" + (app.repo.accounts.size + 1) }, kind = "pat", pat = token)
        val t = QoderClient.patExchange(token)
        a.token = t.getString("token"); a.refreshToken = t.optString("refreshToken"); a.expiresAt = t.getLong("expiresAt")
        a.status = "active"
        runCatching { discover(app, a) }.onFailure { a.lastError = "models: ${it.message}".take(160) }
        app.repo.accounts.add(a); app.repo.save()
        a
    }, { done(it); app.notifyChanged() })

    /**
     * Browser sign-in. [openUrl] is called on the main thread with the login URL; [done] fires once
     * the account is added, the user cancels, or after ~5 minutes.
     */
    fun addDevice(app: RelayApp, label: String, region: String, openUrl: (String) -> Unit, done: (Result<Account>) -> Unit) {
        val (st, url) = QoderClient.loginStart(region)
        loginInProgress = true; loginCancelled = false
        openUrl(url)
        run({
            try {
                var tokens: org.json.JSONObject? = null
                val end = System.currentTimeMillis() + 300_000
                while (tokens == null && System.currentTimeMillis() < end && !loginCancelled) {
                    Thread.sleep(2000)
                    tokens = try { QoderClient.devicePoll(st) } catch (e: java.io.IOException) { null }
                }
                if (loginCancelled) throw IllegalStateException("Sign-in cancelled")
                tokens ?: throw IllegalStateException("Sign-in timed out")
                val a = Account(label = label.ifBlank { "qoder-" + (app.repo.accounts.size + 1) },
                    region = region, kind = "device", machineId = st.machineId)
                a.token = tokens.getString("token")
                a.refreshToken = tokens.optString("refreshToken")
                a.expiresAt = tokens.getLong("expiresAt")
                a.status = "active"
                runCatching { discover(app, a) }.onFailure { a.lastError = "models: ${it.message}".take(160) }
                app.repo.accounts.add(a); app.repo.save()
                a
            } finally {
                loginInProgress = false
            }
        }, { done(it); app.notifyChanged() })
    }

    fun cancelLogin() { loginCancelled = true }

    /** "Check" button: refresh token if needed, regenerate signing fields, reload models. */
    fun check(app: RelayApp, a: Account, done: (Result<Int>) -> Unit) = run({
        try {
            a.needsFields = true
            if (!QoderClient.ensureToken(a)) throw IllegalStateException(a.lastError.ifEmpty { "token refresh failed" })
            discover(app, a)
            a.discovered.size
        } catch (e: Exception) {
            a.status = if (a.status == "auth_error") "auth_error" else "error"
            a.lastError = e.message.orEmpty().take(200)
            throw e
        } finally {
            app.repo.save()
        }
    }, { done(it); app.notifyChanged() })

    fun setEnabled(app: RelayApp, a: Account, on: Boolean) {
        a.enabled = on
        if (on) { a.cooldownUntil = 0; if (a.status == "error") a.status = "unknown" }
        app.repo.save(); app.notifyChanged()
    }

    fun rename(app: RelayApp, a: Account, label: String) {
        if (label.isNotBlank()) { a.label = label.trim(); app.repo.save(); app.notifyChanged() }
    }

    fun remove(app: RelayApp, a: Account) {
        app.repo.accounts.remove(a); app.repo.save(); app.notifyChanged()
    }
}
