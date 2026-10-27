package com.qoder.relay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var app: RelayApp
    private val handler = Handler(Looper.getMainLooper())
    private val themes = listOf(R.style.Theme_Relay_Graphite, R.style.Theme_Relay_Warm, R.style.Theme_Relay_Midnight)
    private val themeNames = listOf("Graphite", "Warm neutral", "Midnight slate")
    private var themeIdx = 0

    private lateinit var accountAdapter: AccountAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = getSharedPreferences("relay", Context.MODE_PRIVATE)
        themeIdx = prefs.getInt("theme", 0).coerceIn(themes.indices)
        setTheme(themes[themeIdx])
        super.onCreate(savedInstanceState)
        app = application as RelayApp
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById(R.id.toolbar))

        findViewById<View>(R.id.btn_copy_url).setOnClickListener {
            val text = urlBase() + "/v1\nAuthorization: Bearer " + app.relayKey
            copy(text)
        }
        findViewById<View>(R.id.btn_add).setOnClickListener { askAddMethod() }

        val rec = findViewById<RecyclerView>(R.id.account_list)
        accountAdapter = AccountAdapter()
        rec.layoutManager = LinearLayoutManager(this)
        rec.adapter = accountAdapter
        rec.isNestedScrollingEnabled = false

        RelayService.start(this)
        app.ensureServer()
        render()
        handler.postDelayed(tick, 4000)
    }

    override fun onDestroy() { handler.removeCallbacks(tick); super.onDestroy() }
    private val tick = object : Runnable { override fun run() { render(); handler.postDelayed(this, 4000) } }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu); return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.act_add -> { askAddMethod(); true }
        R.id.act_theme -> { showThemeDialog(); true }
        R.id.act_refresh -> { app.ensureServer(); render(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun showThemeDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Theme")
            .setItems(themeNames.toTypedArray()) { _, which ->
                themeIdx = which
                getSharedPreferences("relay", Context.MODE_PRIVATE).edit().putInt("theme", which).apply()
                recreate()
            }.show()
    }

    // ---------------- rendering ----------------
    private fun urlBase(): String {
        val lan = app.lanAddress()
        return if (lan.isNotEmpty()) "http://" + lan + ":" + app.port else "http://127.0.0.1:" + app.port
    }

    private fun fmt(n: Long) = when {
        n >= 1_000_000 -> String.format("%.2fM", n / 1e6)
        n >= 1_000 -> String.format("%.1fk", n / 1e3)
        else -> n.toString()
    }

    private fun render() {
        findViewById<TextView>(R.id.tv_url).text = urlBase() + "/v1"
        findViewById<TextView>(R.id.tv_key).text = "key: " + app.relayKey
        val state = when {
            app.serverError.isNotEmpty() -> "server: ERROR " + app.serverError
            app.isRunning() -> "server: running on :" + app.port + "  (same Wi-Fi LAN reachable)"
            else -> "server: stopped — tap Refresh"
        }
        findViewById<TextView>(R.id.tv_server_state).text = state

        val accounts = app.repo.accounts
        findViewById<View>(R.id.tv_no_accounts).visibility =
            if (accounts.isEmpty()) View.VISIBLE else View.GONE
        accountAdapter.submit(accounts)

        val reqs = accounts.sumOf { it.reqs }
        findViewById<TextView>(R.id.tv_usage_totals).text =
            "in ${fmt(app.repo.totalIn())}   out ${fmt(app.repo.totalOut())}   reqs $reqs"
        findViewById<TextView>(R.id.tv_usage_accounts).text = accounts.joinToString("\n") {
            "${it.label}: ${fmt(it.inTok)} in / ${fmt(it.outTok)} out · ${it.reqs} req · ${it.errs} err · ${it.failovers} failover"
        }.ifEmpty { "—" }

        val log = StringBuilder()
        synchronized(app.engine.recent) {
            app.engine.recent.take(40).forEach { e ->
                log.append("${e.optString("state")} ${e.optString("model")} ${e.optLong("in")}→${e.optLong("out")} ${e.optLong("ms")}ms [${e.optString("account")}]\n")
            }
        }
        findViewById<TextView>(R.id.tv_log).text = if (log.isEmpty()) "—" else log.toString().take(4000)
    }

    // ---------------- add account ----------------
    private fun askAddMethod() {
        val opts = arrayOf(getString(R.string.login_browser), getString(R.string.login_pat))
        MaterialAlertDialogBuilder(this).setTitle(R.string.add_account)
            .setItems(opts) { _, w -> if (w == 0) startDeviceLogin() else askPat() }.show()
    }

    private fun askPat() {
        val input = EditText(this).apply { hint = "pt-…"; inputType = android.text.InputType.TYPE_CLASS_TEXT }
        MaterialAlertDialogBuilder(this).setTitle(R.string.login_pat)
            .setView(input)
            .setPositiveButton("Add") { _, _ -> doPat(input.text.toString().trim()) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun doPat(pat: String) {
        if (!pat.startsWith("pt-")) { toast("PAT must start with pt-"); return }
        lifecycleScope.launch {
            val acc = try {
                withContext(Dispatchers.IO) {
                    val t = QoderClient.patExchange(pat)
                    val a = Account(label = "pat-" + (app.repo.accounts.size + 1), kind = "pat")
                    a.token = t.getString("token"); a.refreshToken = t.optString("refreshToken")
                    a.expiresAt = t.getLong("expiresAt"); a.status = "active"
                    a.discovered = QoderClient.listModelKeys(a).toMutableList()
                    a
                }
            } catch (e: Exception) { toast("exchange failed: " + (e.message ?: "")); return@launch }
            app.repo.accounts.add(acc); app.repo.save(); render(); toast("account added")
        }
    }

    private fun startDeviceLogin() {
        val regions = arrayOf("global (qoder.com)", "cn (qoder.com.cn)")
        MaterialAlertDialogBuilder(this).setTitle("Region")
            .setItems(regions) { _, w -> runDeviceLogin(if (w == 0) "global" else "cn") }.show()
    }

    private fun runDeviceLogin(region: String) {
        val (st, url) = QoderClient.loginStart(region)
        val box = TextView(this).apply {
            text = url; setTextIsSelectable(true); setPadding(48, 24, 48, 8)
            setTextIsSelectable(true)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Sign in with Qoder")
            .setMessage("Open this link and approve — the relay signs in exactly like the CLI:")
            .setView(box)
            .setPositiveButton(getString(R.string.btn_open), null)
            .setNegativeButton(getString(R.string.btn_copy), null)
            .setNeutralButton("Cancel", null)
            .show()
        // wire buttons without dismissing on open/copy
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { }
        }
        dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener { copy(url) }
        dialog.setOnDismissListener { /* polling continues regardless */ }

        lifecycleScope.launch {
            var tries = 0
            var added = false
            var tokens: org.json.JSONObject? = null
            while (tries < 145 && !added) {
                kotlinx.coroutines.delay(2000); tries++
                tokens = try { withContext(Dispatchers.IO) { QoderClient.devicePoll(st) } } catch (e: Exception) { null }
                if (tokens != null) added = true
            }
            if (added && tokens != null) {
                val acc = Account(label = "qoder-" + (app.repo.accounts.size + 1), region = region,
                    kind = "device", machineId = st.machineId)
                acc.token = tokens.getString("token"); acc.refreshToken = tokens.optString("refreshToken")
                acc.expiresAt = tokens.getLong("expiresAt"); acc.status = "active"
                acc.discovered = try { withContext(Dispatchers.IO) { QoderClient.listModelKeys(acc) }.toMutableList() }
                               catch (e: Exception) { mutableListOf() }
                app.repo.accounts.add(acc); app.repo.save()
                if (dialog.isShowing) dialog.dismiss()
                render(); toast("signed in — account added")
            } else if (dialog.isShowing) {
                dialog.dismiss(); toast("login window expired — try again")
            }
        }
    }

    private fun copy(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("relay", text)); toast("copied")
    }
    private fun toast(m: String) =
        android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_SHORT).show()

    // ---------------- accounts list ----------------
    inner class AccountAdapter : RecyclerView.Adapter<AccountAdapter.VH>() {
        private var items: List<Account> = emptyList()
        fun submit(a: List<Account>) { items = a.toList(); notifyDataSetChanged() }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val dot: View = v.findViewById(R.id.dot)
            val name: TextView = v.findViewById(R.id.tv_name)
            val meta: TextView = v.findViewById(R.id.tv_meta)
            val sw: MaterialSwitch = v.findViewById(R.id.sw_enabled)
            val del: ImageButton = v.findViewById(R.id.btn_delete)
            val chips: com.google.android.material.chip.ChipGroup = v.findViewById(R.id.chip_group)
            val refresh: TextView = v.findViewById(R.id.btn_refresh_models)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_account, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val a = items[position]
            holder.name.text = a.label
            val color = when (a.status) {
                "active" -> R.color.ok_g
                "auth_error", "error" -> R.color.err_g
                else -> R.color.warn_g
            }
            holder.dot.background = ContextCompat.getDrawable(holder.itemView.context, R.drawable.dot)
            holder.dot.background?.setTint(ContextCompat.getColor(holder.itemView.context, color))
            holder.meta.text = "${a.kind} · ${a.region} · ${a.reqs} req · ${fmt(a.inTok)} in / ${fmt(a.outTok)} out · ${a.errs} err" +
                (if (a.lastError.isNotEmpty()) " · " + a.lastError.take(60) else "")
            holder.sw.setOnCheckedChangeListener(null)
            holder.sw.isChecked = a.enabled
            holder.sw.setOnCheckedChangeListener { _, on -> a.enabled = on; app.repo.save() }
            holder.del.setOnClickListener {
                MaterialAlertDialogBuilder(holder.itemView.context)
                    .setTitle("Remove ${a.label}?").setMessage("Token and stats for this account are deleted.")
                    .setPositiveButton("Remove") { _, _ -> app.repo.accounts.remove(a); app.repo.save(); render() }
                    .setNegativeButton("Cancel", null).show()
            }
            holder.refresh.setOnClickListener {
                holder.refresh.text = "refreshing…"
                lifecycleScope.launch {
                    val keys = try { withContext(Dispatchers.IO) { QoderClient.listModelKeys(a) } } catch (e: Exception) { emptyList() }
                    a.discovered = keys.toMutableList(); app.repo.save()
                    render()
                }
            }
            holder.chipGroupWrap(a)
        }

        private fun Account.chipGroupWrap(vh: AccountAdapter.VH) {
            val group = vh.chips
            group.removeAllViews()
            val keys = if (discovered.isNotEmpty()) discovered else models
            for (k in keys) {
                val chip = Chip(group.context).apply {
                    text = k; isCheckable = true
                    isChecked = models.contains(k)
                    setOnCheckedChangeListener { _, on ->
                        if (on) { if (!models.contains(k)) models.add(k) } else models.remove(k)
                        app.repo.save()
                    }
                }
                group.addView(chip)
            }
        }
    }
}
