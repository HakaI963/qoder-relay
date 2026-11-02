package com.qoder.relay

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val app get() = application as RelayApp
    private val ui = Handler(Looper.getMainLooper())
    private var keyVisible = false
    private var bindingSwitch = false

    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var statusDot: View
    private lateinit var statusTitle: TextView
    private lateinit var statusSub: TextView
    private lateinit var swRun: MaterialSwitch
    private lateinit var statReqs: TextView
    private lateinit var statIn: TextView
    private lateinit var statOut: TextView
    private lateinit var txtUrl: TextView
    private lateinit var txtKey: TextView
    private lateinit var txtLocal: TextView
    private lateinit var btnShowKey: MaterialButton
    private lateinit var listAccounts: LinearLayout
    private lateinit var listRecent: LinearLayout
    private lateinit var txtEmpty: TextView
    private lateinit var txtAccountsHeader: TextView
    private lateinit var cardBattery: MaterialCardView

    private val onChange: () -> Unit = { ui.post { render() } }
    private val ticker = object : Runnable {
        override fun run() { render(); ui.postDelayed(this, 3000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bind()
        if (app.wantRunning) RelayService.start(this)
        askNotificationPermission()
    }

    private fun bind() {
        swipe = findViewById(R.id.swipe)
        statusDot = findViewById(R.id.statusDot)
        statusTitle = findViewById(R.id.statusTitle)
        statusSub = findViewById(R.id.statusSub)
        swRun = findViewById(R.id.swRun)
        statReqs = findViewById(R.id.statReqs)
        statIn = findViewById(R.id.statIn)
        statOut = findViewById(R.id.statOut)
        txtUrl = findViewById(R.id.txtUrl)
        txtKey = findViewById(R.id.txtKey)
        txtLocal = findViewById(R.id.txtLocal)
        btnShowKey = findViewById(R.id.btnShowKey)
        listAccounts = findViewById(R.id.listAccounts)
        listRecent = findViewById(R.id.listRecent)
        txtEmpty = findViewById(R.id.txtEmpty)
        txtAccountsHeader = findViewById(R.id.txtAccountsHeader)
        cardBattery = findViewById(R.id.cardBattery)

        swipe.setColorSchemeColors(color(R.color.accent))
        swipe.setProgressBackgroundColorSchemeColor(color(R.color.surface2))
        swipe.setOnRefreshListener { checkAll() }

        swRun.setOnCheckedChangeListener { _, on ->
            if (bindingSwitch) return@setOnCheckedChangeListener
            if (on) { app.wantRunning = true; RelayService.start(this) } else RelayService.stop(this)
            ui.postDelayed({ render() }, 400)
        }
        findViewById<MaterialButton>(R.id.btnCopyUrl).setOnClickListener { copy("Base URL", baseUrl()) }
        findViewById<MaterialButton>(R.id.btnCopyKey).setOnClickListener { copy("API key", app.relayKey) }
        btnShowKey.setOnClickListener { keyVisible = !keyVisible; render() }
        findViewById<MaterialButton>(R.id.btnAdd).setOnClickListener { showAddDialog() }
        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener { showSettings() }
        findViewById<MaterialButton>(R.id.btnBattery).setOnClickListener { requestBatteryExemption() }
        findViewById<TextView>(R.id.txtVersion).text = "Qoder Relay ${RelayApp.VERSION}"
    }

    override fun onResume() {
        super.onResume()
        synchronized(app.listeners) { app.listeners.add(onChange) }
        ui.post(ticker)
    }

    override fun onPause() {
        synchronized(app.listeners) { app.listeners.remove(onChange) }
        ui.removeCallbacks(ticker)
        super.onPause()
    }

    // ------------------------------------------------------------------ render

    private fun baseUrl(): String = "http://${app.lanAddress().ifEmpty { "127.0.0.1" }}:${app.port}/v1"

    private fun render() {
        val running = app.isRunning()
        val enabled = app.repo.accounts.filter { it.enabled }
        val healthy = enabled.count { it.status != "auth_error" && it.status != "error" }

        bindingSwitch = true
        swRun.isChecked = running || (app.wantRunning && app.serverError.isEmpty())
        bindingSwitch = false

        val (dot, title, sub) = when {
            !running && app.serverError.isNotEmpty() -> Triple(R.color.err, "Error", app.serverError)
            !running -> Triple(R.color.text2, "Stopped", "Turn on to start the relay")
            enabled.isEmpty() -> Triple(R.color.warn, "No accounts", "Add a Qoder account to start serving")
            healthy == 0 -> Triple(R.color.warn, "Accounts need attention", "Tap an account to check or re-login")
            app.inFlight.get() > 0 -> Triple(R.color.accent, "Serving", "${app.inFlight.get()} active request(s) · $healthy/${enabled.size} accounts ready")
            else -> Triple(R.color.ok, "Running", "$healthy/${enabled.size} accounts ready · up ${ago(app.startedAt, suffix = false)}")
        }
        statusDot.backgroundTintList = ColorStateList.valueOf(color(dot))
        statusTitle.text = title
        statusSub.text = sub

        statReqs.text = compact(app.repo.totalReqs())
        statIn.text = compact(app.repo.totalIn())
        statOut.text = compact(app.repo.totalOut())

        txtUrl.text = baseUrl()
        txtKey.text = if (!app.requireKey) "not required" else if (keyVisible) app.relayKey
                      else app.relayKey.take(9) + "••••••••" + app.relayKey.takeLast(4)
        btnShowKey.text = if (keyVisible) "Hide" else "Show"
        btnShowKey.visibility = if (app.requireKey) View.VISIBLE else View.GONE
        txtLocal.text = if (app.lanAddress().isEmpty()) "Not on Wi-Fi — only apps on this phone can connect (http://127.0.0.1:${app.port}/v1)"
                        else "Same-phone apps can also use http://127.0.0.1:${app.port}/v1"

        cardBattery.visibility = if (isIgnoringBattery()) View.GONE else View.VISIBLE

        renderAccounts()
        renderRecent()
        swipe.isRefreshing = swipe.isRefreshing && checking > 0
    }

    private fun renderAccounts() {
        val accounts = app.repo.accounts.toList()
        txtAccountsHeader.text = if (accounts.isEmpty()) "Accounts" else "Accounts (${accounts.size})"
        txtEmpty.visibility = if (accounts.isEmpty()) View.VISIBLE else View.GONE
        listAccounts.removeAllViews()
        val inf = LayoutInflater.from(this)
        val now = System.currentTimeMillis()
        accounts.forEach { a ->
            val v = inf.inflate(R.layout.item_account, listAccounts, false)
            val dotColor = when {
                !a.enabled -> R.color.text2
                a.status == "auth_error" -> R.color.err
                a.status == "error" || a.cooldownUntil > now -> R.color.warn
                a.status == "active" -> R.color.ok
                else -> R.color.text2
            }
            v.findViewById<View>(R.id.dot).backgroundTintList = ColorStateList.valueOf(color(dotColor))
            v.findViewById<TextView>(R.id.name).text = a.label.ifEmpty { "account" }
            val state = when {
                !a.enabled -> "Disabled"
                a.status == "auth_error" -> "Login expired"
                a.cooldownUntil > now -> "Retrying soon"
                a.status == "error" -> "Error"
                a.status == "active" -> "Ready"
                else -> "Idle"
            }
            v.findViewById<TextView>(R.id.meta).text =
                "$state · ${if (a.kind == "pat") "Token" else "Browser"} · ${compact(a.reqs)} req · ${compact(a.inTok + a.outTok)} tok"
            v.findViewById<TextView>(R.id.err).apply {
                val show = a.enabled && a.lastError.isNotEmpty() && a.status != "active"
                visibility = if (show) View.VISIBLE else View.GONE
                text = a.lastError
            }
            v.findViewById<MaterialSwitch>(R.id.sw).apply {
                isChecked = a.enabled
                setOnCheckedChangeListener { _, on -> AccountOps.setEnabled(app, a, on) }
            }
            v.setOnClickListener { showAccountSheet(a) }
            listAccounts.addView(v)
        }
    }

    private fun renderRecent() {
        listRecent.removeAllViews()
        val items = app.engine.recentCopy().take(8)
        val inf = LayoutInflater.from(this)
        if (items.isEmpty()) {
            listRecent.addView(TextView(this).apply {
                text = "No requests yet"
                setTextColor(color(R.color.text2)); textSize = 13f
                setPadding(dp(16), dp(10), dp(16), dp(10))
            })
            return
        }
        items.forEach { e ->
            val v = inf.inflate(R.layout.item_recent, listRecent, false)
            val st = e.optString("state")
            val c = when (st) { "ok" -> R.color.ok; "failed" -> R.color.err; else -> R.color.warn }
            v.findViewById<View>(R.id.dot).backgroundTintList = ColorStateList.valueOf(color(c))
            v.findViewById<TextView>(R.id.line1).text = "${e.optString("model")} · ${e.optString("account")}"
            v.findViewById<TextView>(R.id.line2).text = if (e.has("err")) e.optString("err")
                else "$st · ${e.optLong("in")} in / ${e.optLong("out")} out · ${"%.1f".format(e.optLong("ms") / 1000.0)}s"
            v.findViewById<TextView>(R.id.time).text = ago(e.optLong("ts"))
            listRecent.addView(v)
        }
    }

    // ---------------------------------------------------------------- dialogs

    private fun showAddDialog() {
        val box = vbox()
        val label = field(box, "Name (optional)", "")
        val toggle = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true; isSelectionRequired = true
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
        }
        val bBrowser = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
            .apply { id = View.generateViewId(); text = "Browser login"; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        val bPat = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
            .apply { id = View.generateViewId(); text = "Access token"; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        toggle.addView(bBrowser); toggle.addView(bPat)
        box.addView(toggle)
        val patLayout = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            hint = "Personal access token (pt-…)"
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
            visibility = View.GONE
        }
        val pat = TextInputEditText(patLayout.context).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        patLayout.addView(pat); box.addView(patLayout)
        val cn = MaterialSwitch(this).apply { text = "China region (qoder.com.cn)"; setTextColor(color(R.color.text2)) }
        box.addView(cn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val hint = TextView(this).apply {
            setTextColor(color(R.color.text2)); textSize = 12f
            text = "Opens qoder.com in your browser. Sign in, approve, then come back — the account is added automatically."
        }
        box.addView(hint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        toggle.check(bBrowser.id)
        toggle.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val isPat = id == bPat.id
            patLayout.visibility = if (isPat) View.VISIBLE else View.GONE
            cn.visibility = if (isPat) View.GONE else View.VISIBLE
            hint.text = if (isPat) "Create a token at qoder.com → Account → Integrations, then paste it here."
                        else "Opens qoder.com in your browser. Sign in, approve, then come back — the account is added automatically."
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Add Qoder account")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Continue") { _, _ ->
                val name = label.text?.toString().orEmpty()
                if (toggle.checkedButtonId == bPat.id) addPat(name, pat.text?.toString().orEmpty())
                else addBrowser(name, if (cn.isChecked) "cn" else "global")
            }
            .show()
    }

    private fun addPat(name: String, token: String) {
        val p = progress("Adding account…")
        AccountOps.addPat(app, name, token) { r ->
            p.dismiss()
            r.onSuccess { toast("Added ${it.label}") }.onFailure { alert("Couldn't add account", it.message ?: "error") }
        }
    }

    private fun addBrowser(name: String, region: String) {
        var dialog: androidx.appcompat.app.AlertDialog? = null
        AccountOps.addDevice(app, name, region,
            openUrl = { url ->
                dialog = MaterialAlertDialogBuilder(this)
                    .setTitle("Waiting for sign-in…")
                    .setMessage("Finish signing in on the Qoder page in your browser. This screen updates automatically.")
                    .setCancelable(false)
                    .setNeutralButton("Open again", null)
                    .setNegativeButton("Cancel") { _, _ -> AccountOps.cancelLogin() }
                    .show()
                dialog?.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener { openUrl(url) }
                openUrl(url)
            },
            done = { r ->
                runCatching { dialog?.dismiss() }
                r.onSuccess { toast("Added ${it.label}") }
                    .onFailure { if (it.message != "Sign-in cancelled") alert("Sign-in failed", it.message ?: "error") }
            })
    }

    private fun showAccountSheet(a: Account) {
        val models = a.discovered.take(12).joinToString(", ").ifEmpty { "—" }
        val expires = if (a.expiresAt > 0) SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(a.expiresAt)) else "—"
        val msg = buildString {
            append("Type: ${if (a.kind == "pat") "Access token" else "Browser login"}${if (a.region == "cn") " (China)" else ""}\n")
            append("Token valid until: $expires\n")
            append("Requests: ${a.reqs}  ·  Errors: ${a.errs}\n")
            append("Tokens: ${compact(a.inTok)} in / ${compact(a.outTok)} out\n\n")
            append("Models: $models")
            if (a.lastError.isNotEmpty() && a.status != "active") append("\n\nLast error: ${a.lastError}")
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(a.label)
            .setMessage(msg)
            .setPositiveButton("Check now") { _, _ -> checkOne(a) }
            .setNeutralButton("Remove") { _, _ ->
                MaterialAlertDialogBuilder(this).setTitle("Remove ${a.label}?")
                    .setMessage("You can add it again later.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Remove") { _, _ -> AccountOps.remove(app, a) }.show()
            }
            .setNegativeButton("Rename") { _, _ ->
                val box = vbox(); val f = field(box, "Name", a.label)
                MaterialAlertDialogBuilder(this).setTitle("Rename").setView(box)
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Save") { _, _ -> AccountOps.rename(app, a, f.text.toString()) }.show()
            }
            .show()
    }

    private fun checkOne(a: Account) {
        val p = progress("Checking ${a.label}…")
        AccountOps.check(app, a) { r ->
            p.dismiss()
            r.onSuccess { toast("${a.label}: OK · $it models") }.onFailure { alert("${a.label} failed", it.message ?: "error") }
        }
    }

    private var checking = 0
    private fun checkAll() {
        val list = app.repo.accounts.filter { it.enabled }
        if (list.isEmpty()) { swipe.isRefreshing = false; render(); return }
        checking = list.size
        list.forEach { a ->
            AccountOps.check(app, a) {
                checking--
                if (checking <= 0) { swipe.isRefreshing = false; render() }
            }
        }
    }

    private fun showSettings() {
        val box = vbox()
        val port = field(box, "Port", app.port.toString()).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        val req = MaterialSwitch(this).apply {
            text = "Require API key from other devices"; isChecked = app.requireKey; setTextColor(color(R.color.text))
        }
        box.addView(req, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        val regen = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { text = "New API key" }
        val battery = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { text = "Battery settings" }
        box.addView(regen, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        box.addView(battery, LinearLayout.LayoutParams(-1, -2))
        box.addView(TextView(this).apply {
            setTextColor(color(R.color.text2)); textSize = 12f
            text = "On Xiaomi / Oppo / Vivo / Samsung also allow \"Autostart\" and set battery to \"No restrictions\" for Qoder Relay."
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        val d = MaterialAlertDialogBuilder(this)
            .setTitle("Settings")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                app.setRequireKey(req.isChecked)
                val p = port.text.toString().toIntOrNull()
                if (p != null && p != app.port) {
                    if (app.setPort(p)) toast("Port changed to $p") else toast("Port must be 1024–65535")
                }
                render()
            }
            .show()
        regen.setOnClickListener {
            MaterialAlertDialogBuilder(this).setTitle("Create a new API key?")
                .setMessage("Clients using the old key will stop working until updated.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Create") { _, _ -> app.regenerateKey(); keyVisible = true; render(); d.dismiss() }.show()
        }
        battery.setOnClickListener { openAppBatterySettings() }
    }

    // ---------------------------------------------------------------- battery

    private fun isIgnoringBattery(): Boolean =
        (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) { openAppBatterySettings() }
    }

    private fun openAppBatterySettings() {
        try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        catch (_: Exception) { runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            runCatching { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1) }
        }
    }

    // ------------------------------------------------------------------ utils

    private fun openUrl(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: Exception) { copy("Login link", url); toast("No browser found — link copied") }
    }

    private fun copy(label: String, text: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text))
        if (Build.VERSION.SDK_INT < 33) toast("$label copied")
    }

    private fun vbox() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(8), dp(24), 0)
    }

    private fun field(parent: LinearLayout, hint: String, value: String): EditText {
        val l = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply { this.hint = hint }
        val e = TextInputEditText(l.context).apply { setText(value); setSingleLine() }
        l.addView(e)
        parent.addView(l, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        return e
    }

    private fun progress(msg: String) = MaterialAlertDialogBuilder(this)
        .setView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            addView(com.google.android.material.progressindicator.CircularProgressIndicator(context).apply { isIndeterminate = true })
            addView(TextView(context).apply { text = msg; setTextColor(color(R.color.text)); setPadding(dp(16), 0, 0, 0) })
        })
        .setCancelable(false)
        .show()

    private fun alert(title: String, msg: String) =
        MaterialAlertDialogBuilder(this).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun color(id: Int) = ContextCompat.getColor(this, id)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun compact(n: Long): String = when {
        n >= 1_000_000_000 -> "%.1fB".format(n / 1e9)
        n >= 1_000_000 -> "%.1fM".format(n / 1e6)
        n >= 10_000 -> "%.1fK".format(n / 1e3)
        else -> n.toString()
    }

    private fun ago(ts: Long, suffix: Boolean = true): String {
        if (ts <= 0) return "—"
        val s = (System.currentTimeMillis() - ts) / 1000
        val v = when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m"
            s < 86400 -> "${s / 3600}h"
            else -> "${s / 86400}d"
        }
        return if (suffix) "$v ago" else v
    }
}
