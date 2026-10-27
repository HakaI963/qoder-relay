package com.qoder.relay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.DateFormat

fun fmtTok(n: Long): String = when {
    n >= 1_000_000 -> String.format("%.2fM", n / 1e6)
    n >= 1_000 -> String.format("%.1fk", n / 1e3)
    else -> n.toString()
}

fun Context.attrColor(attr: Int): Int {
    val tv = android.util.TypedValue()
    theme.resolveAttribute(attr, tv, true)
    return if (tv.resourceId != 0) androidx.core.content.ContextCompat.getColor(this, tv.resourceId) else tv.data
}
fun AppCompatActivity.app() = applicationContext as RelayApp
fun AppCompatActivity.toast(m: String) =
    android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_SHORT).show()
fun AppCompatActivity.copyText(t: String) {
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("relay", t)); toast("copied")
}

fun AppCompatActivity.autoTick(view: View, everyMs: Long, block: () -> Unit) {
    val handler = Handler(Looper.getMainLooper())
    val runnable = object : Runnable { override fun run() { block(); handler.postDelayed(this, everyMs) } }
    view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) { handler.post(runnable) }
        override fun onViewDetachedFromWindow(v: View) { handler.removeCallbacks(runnable) }
    })
}

/* ---------------- Accounts ---------------- */

class AccountsTab(private val act: AppCompatActivity, private val root: View) {
    private val app get() = act.app()
    private val recycler: RecyclerView = root.findViewById(R.id.account_list)
    private val empty: TextView = root.findViewById(R.id.accounts_empty)
    private val adapter = AccountAdapter()

    init {
        recycler.layoutManager = LinearLayoutManager(act)
        recycler.adapter = adapter
        root.findViewById<View>(R.id.fab_add).setOnClickListener { askAddMethod() }
        act.autoTick(root, 5000) { adapter.submit() }
    }

    fun submit() = adapter.submit()

    private fun askAddMethod() {
        val opts = arrayOf("Sign in with Qoder (browser)", "Paste Personal Access Token (pt-…)")
        MaterialAlertDialogBuilder(act).setTitle("Add account")
            .setItems(opts) { _, w -> if (w == 0) startDeviceLogin() else askPat() }.show()
    }

    private fun askPat() {
        val input = EditText(act).apply { hint = "pt-…"; setSingleLine() }
        MaterialAlertDialogBuilder(act).setTitle("Paste PAT")
            .setMessage("Create one at qoder.com → Account → Integrations.")
            .setView(input)
            .setPositiveButton("Add") { _, _ -> doPat(input.text.toString().trim()) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun doPat(pat: String) {
        if (!pat.startsWith("pt-")) { act.toast("PAT must start with pt-"); return }
        act.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val t = QoderClient.patExchange(pat)
                    val a = Account(label = "pat-" + (app.repo.accounts.size + 1), kind = "pat", region = "global")
                    a.token = t.getString("token"); a.refreshToken = t.optString("refreshToken")
                    a.expiresAt = t.getLong("expiresAt"); a.status = "active"; a.needsFields = true
                    prepareAccount(a)
                    Result.success(a)
                } catch (e: Exception) { Result.failure(e) }
            }
            result.onSuccess {
                app.repo.accounts.add(it); app.repo.save(); adapter.submit(); act.toast("account added")
            }.onFailure { act.toast("failed: " + (it.message ?: "")) }
        }
    }

    private fun prepareAccount(a: Account) {
        QoderClient.ensureCosyFields(a, app.signer)
        val entries = QoderClient.listModels(a, app.signer)
        a.discovered = entries.map { it.optString("key") }.filter { it.isNotEmpty() }.toMutableList()
        entries.forEach {
            val k = it.optString("key")
            if (k.isNotEmpty()) app.engine.modelInfo[k] = (it.optString("display_name").ifEmpty { k }) to it.optBoolean("is_reasoning")
        }
    }

    private fun startDeviceLogin() {
        val regions = arrayOf("global (qoder.com)", "cn (qoder.com.cn)")
        MaterialAlertDialogBuilder(act).setTitle("Region")
            .setItems(regions) { _, w -> runDeviceLogin(if (w == 0) "global" else "cn") }.show()
    }

    private fun runDeviceLogin(region: String) {
        val (st, url) = QoderClient.loginStart(region)
        val box = TextView(act).apply {
            text = url; setTextIsSelectable(true); setPadding(44, 20, 44, 0); textSize = 12f
        }
        val dialog = MaterialAlertDialogBuilder(act)
            .setTitle("Sign in with Qoder")
            .setMessage("Approve in the browser — the relay captures the token automatically.")
            .setView(box)
            .setPositiveButton("Open", null)
            .setNegativeButton("Copy", null)
            .setNeutralButton("Cancel", null)
            .show()
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            try { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { }
        }
        dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener { act.copyText(url) }

        act.lifecycleScope.launch {
            var tries = 0; var added = false; var tokens: JSONObject? = null
            while (tries < 145 && !added) {
                delay(2000); tries++
                tokens = try { withContext(Dispatchers.IO) { QoderClient.devicePoll(st) } } catch (e: Exception) { null }
                if (tokens != null) added = true
            }
            if (added && tokens != null) {
                val acc = Account(label = "qoder-" + (app.repo.accounts.size + 1), region = region,
                    kind = "device", machineId = st.machineId)
                acc.token = tokens.getString("token"); acc.refreshToken = tokens.optString("refreshToken")
                acc.expiresAt = tokens.getLong("expiresAt"); acc.status = "active"; acc.needsFields = true
                val prepared = withContext(Dispatchers.IO) {
                    try { prepareAccount(acc); true } catch (e: Exception) {
                        acc.lastError = (e.message ?: "model list failed").take(120); false
                    }
                }
                if (!prepared) acc.lastError = acc.lastError.ifEmpty { "model list failed — tap refresh models" }
                app.repo.accounts.add(acc); app.repo.save()
                if (dialog.isShowing) dialog.dismiss()
                adapter.submit(); act.toast("signed in — account added")
            } else if (dialog.isShowing) {
                dialog.dismiss(); act.toast("login window expired — try again")
            }
        }
    }

    inner class AccountAdapter : RecyclerView.Adapter<AccountAdapter.VH>() {
        private var items: List<Account> = emptyList()
        fun submit() {
            items = app.repo.accounts.toList(); notifyDataSetChanged()
            empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val dot: View = v.findViewById(R.id.dot)
            val name: TextView = v.findViewById(R.id.tv_name)
            val meta: TextView = v.findViewById(R.id.tv_meta)
            val sw: MaterialSwitch = v.findViewById(R.id.sw_enabled)
            val chips: ChipGroup = v.findViewById(R.id.chip_group)
            val refresh: View = v.findViewById(R.id.btn_refresh_models)
            val remove: View = v.findViewById(R.id.btn_remove)
        }

        override fun onCreateViewHolder(parent: ViewGroup, t: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_account, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val a = items[pos]
            h.name.text = a.label
            val colorRes = when (a.status) {
                "active" -> R.color.ok_g
                "auth_error", "error" -> R.color.err_g
                else -> R.color.warn_g
            }
            h.dot.background?.setTint(act.getColor(colorRes))
            h.meta.text = "${a.kind} · ${a.region} · ${a.reqs} req · ${fmtTok(a.inTok)} in / ${fmtTok(a.outTok)} out · ${a.errs} err" +
                (if (a.encInfo.isEmpty()) "\nCOSY fields pending — tap refresh models" else "") +
                (if (a.lastError.isNotEmpty()) "\n" + a.lastError.take(90) else "")
            h.sw.setOnCheckedChangeListener(null)
            h.sw.isChecked = a.enabled
            h.sw.setOnCheckedChangeListener { _, on -> a.enabled = on; app.repo.save() }
            h.itemView.setOnClickListener { showDetail(a) }
            h.refresh.setOnClickListener {
                act.toast("refreshing models…")
                act.lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { prepareAccount(a); app.repo.save() }
                        submit(); act.toast("models refreshed")
                    } catch (e: Exception) { act.toast("failed: " + (e.message ?: "")) }
                }
            }
            h.remove.setOnClickListener {
                MaterialAlertDialogBuilder(act).setTitle("Remove ${a.label}?")
                    .setMessage("Token and stats for this account are deleted.")
                    .setPositiveButton("Remove") { _, _ -> app.repo.accounts.remove(a); app.repo.save(); submit() }
                    .setNegativeButton("Cancel", null).show()
            }
            fillChips(h.chips, a)
        }

        private fun showDetail(a: Account) {
            val exp = DateFormat.getDateTimeInstance().format(java.util.Date(a.expiresAt))
            val body = buildString {
                appendLine("id        ${a.id}")
                appendLine("kind      ${a.kind} (${a.region})")
                appendLine("auth      ${a.status} · token exp $exp")
                appendLine("uid       ${a.uid.ifEmpty { "—" }}")
                appendLine("token     ${a.token.take(8)}…")
                appendLine("machine   ${a.machineId}")
                appendLine("cosy      ${if (a.encInfo.isNotEmpty()) "ready" else "pending"} · key ${a.cosyKey.take(10)}…")
                appendLine("serving   ${if (a.models.isEmpty()) "all models" else a.models.joinToString()}")
                appendLine("counters  req ${a.reqs} · in ${a.inTok} · out ${a.outTok}")
                appendLine("errors    ${a.errs} · failovers ${a.failovers}")
                if (a.lastError.isNotEmpty()) appendLine("lastErr   ${a.lastError}")
            }
            MaterialAlertDialogBuilder(act).setTitle(a.label).setMessage(body)
                .setPositiveButton("Rename") { _, _ ->
                    val input = EditText(act).apply { setText(a.label) }
                    MaterialAlertDialogBuilder(act).setTitle("Label").setView(input)
                        .setPositiveButton("Save") { _, _ -> a.label = input.text.toString().ifBlank { a.label }; app.repo.save() }
                        .setNegativeButton("Cancel", null).show()
                }
                .setNeutralButton("Clear allow-list") { _, _ -> a.models.clear(); app.repo.save() }
                .setNegativeButton("Close", null)
                .show()
        }
    }

    private fun fillChips(group: ChipGroup, a: Account) {
        group.removeAllViews()
        val keys = if (a.discovered.isNotEmpty()) a.discovered else a.models
        for (k in keys) {
            val chip = Chip(group.context).apply {
                text = k; isCheckable = true
                isChecked = a.models.isEmpty() || a.models.contains(k)
                setOnCheckedChangeListener { _, on ->
                    if (on) { if (!a.models.contains(k)) a.models.add(k) } else {
                        if (a.models.isEmpty()) a.models.addAll(a.discovered.ifEmpty { keys.filter { it != k } })
                        a.models.remove(k)
                    }
                    app.repo.save()
                }
            }
            group.addView(chip)
        }
        if (keys.isEmpty()) group.addView(TextView(group.context).apply {
            text = "no models yet"; textSize = 12f; setTextColor(0xFF929292.toInt())
        })
    }
}

/* ---------------- Models ---------------- */

class ModelsTab(private val act: AppCompatActivity, private val root: View) {
    private val app get() = act.app()
    private val host: LinearLayout = root.findViewById(R.id.models_host)

    init {
        act.autoTick(root, 20000) { render() }
    }

    fun render() {
        host.removeAllViews()
        app.repo.accounts.forEach { a ->
            val card = LayoutInflater.from(act).inflate(R.layout.item_model_account, host, false)
            card.findViewById<TextView>(R.id.tv_name).text = a.label
            card.findViewById<TextView>(R.id.tv_serving).text =
                if (a.models.isEmpty()) "serving: ALL models" else "serving: " + a.models.joinToString()
            val sw = card.findViewById<MaterialSwitch>(R.id.sw_universal)
            sw.setOnCheckedChangeListener(null)
            sw.isChecked = a.universal
            sw.setOnCheckedChangeListener { _, on -> a.universal = on; app.repo.save() }
            val chips = card.findViewById<ChipGroup>(R.id.chip_group)
            val keys = if (a.discovered.isNotEmpty()) a.discovered else a.models
            for (k in keys) {
                val chip = Chip(act).apply {
                    text = k; isCheckable = true
                    isChecked = a.models.isEmpty() || a.models.contains(k)
                    setOnCheckedChangeListener { _, on ->
                        if (on) { if (!a.models.contains(k)) a.models.add(k) } else {
                            if (a.models.isEmpty()) a.models.addAll(a.discovered.ifEmpty { keys.filter { it != k } })
                            a.models.remove(k)
                        }
                        app.repo.save()
                        card.findViewById<TextView>(R.id.tv_serving).text =
                            if (a.models.isEmpty()) "serving: ALL models" else "serving: " + a.models.joinToString()
                    }
                }
                chips.addView(chip)
            }
            if (keys.isEmpty()) chips.addView(TextView(act).apply {
                text = "no models discovered"; textSize = 12f; setTextColor(0xFF929292.toInt())
            })
            card.findViewById<View>(R.id.btn_refresh).setOnClickListener {
                act.lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            val entries = QoderClient.listModels(a, app.signer)
                            a.discovered = entries.map { it.optString("key") }.filter { it.isNotEmpty() }.toMutableList()
                            entries.forEach {
                                val k = it.optString("key")
                                if (k.isNotEmpty()) app.engine.modelInfo[k] = it.optString("display_name") to it.optBoolean("is_reasoning")
                            }
                            app.repo.save()
                        }
                        render(); act.toast("models refreshed")
                    } catch (e: Exception) { act.toast("failed: " + (e.message ?: "")) }
                }
            }
            card.findViewById<View>(R.id.btn_allow_all).setOnClickListener {
                a.models.clear(); app.repo.save(); render(); act.toast("saved")
            }
            host.addView(card)
        }
    }
}

/* ---------------- Usage ---------------- */

class UsageTab(private val act: AppCompatActivity, private val root: View) {
    private val app get() = act.app()
    private val inTv: TextView = root.findViewById(R.id.tv_total_in)
    private val outTv: TextView = root.findViewById(R.id.tv_total_out)
    private val countsTv: TextView = root.findViewById(R.id.tv_counts)
    private val perHost: LinearLayout = root.findViewById(R.id.per_account_host)
    private val recentTv: TextView = root.findViewById(R.id.tv_recent)

    init { act.autoTick(root, 3000) { render() } }

    fun render() {
        inTv.text = fmtTok(app.repo.totalIn())
        outTv.text = fmtTok(app.repo.totalOut())
        countsTv.text = "${app.repo.accounts.sumOf { it.reqs }} total · ${app.repo.accounts.sumOf { it.errs }} errors · ${app.repo.accounts.sumOf { it.failovers }} failovers"
        perHost.removeAllViews()
        app.repo.accounts.forEach { a ->
            val row = TextView(act).apply {
                text = "${a.label}\nin ${fmtTok(a.inTok)}   out ${fmtTok(a.outTok)}   req ${a.reqs}   err ${a.errs}"
                typeface = Typeface.MONOSPACE; textSize = 12f
                setTextColor(act.attrColor(com.google.android.material.R.attr.colorOnSurface))
                setPadding(0, 8, 0, 8)
            }
            perHost.addView(row)
        }
        if (app.repo.accounts.isEmpty()) perHost.addView(TextView(act).apply {
            text = "—"; textSize = 12f; setTextColor(0xFF929292.toInt())
        })
        val sb = StringBuilder()
        synchronized(app.engine.recent) {
            app.engine.recent.take(40).forEach { e ->
                sb.appendLine("${DateFormat.getTimeInstance(DateFormat.SHORT).format(java.util.Date(e.optLong("ts")))} ${e.optString("state")} ${e.optString("model")} ${e.optLong("in")}→${e.optLong("out")} ${e.optLong("ms")}ms [${e.optString("account")}] ${e.optString("status")}")
            }
        }
        recentTv.text = if (sb.isEmpty()) "—" else sb.toString().trimEnd()
    }
}

/* ---------------- Test ---------------- */

class TestTab(private val act: AppCompatActivity, private val root: View) {
    private val app get() = act.app()
    private val accountSpin: Spinner = root.findViewById(R.id.sp_account)
    private val modelSpin: Spinner = root.findViewById(R.id.sp_model)
    private val prompt: EditText = root.findViewById(R.id.et_prompt)
    private val streamBox: android.widget.CheckBox = root.findViewById(R.id.cb_stream)
    private val statusTv: TextView = root.findViewById(R.id.tv_status)
    private val out: TextView = root.findViewById(R.id.tv_output)

    init {
        fillSpinners()
        root.findViewById<View>(R.id.btn_probe).setOnClickListener { probe() }
        root.findViewById<View>(R.id.btn_call).setOnClickListener { callRelay() }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { fillSpinners() }
            override fun onViewDetachedFromWindow(v: View) {}
        })
    }

    private fun fillSpinners() {
        val accs = app.repo.accounts.filter { it.enabled }
        accountSpin.adapter = ArrayAdapter(act, android.R.layout.simple_spinner_dropdown_item,
            (if (accs.isNotEmpty()) listOf("auto (pool)") else listOf("auto (no accounts!)")) + accs.map { it.label })
        val models = LinkedHashSet<String>()
        accs.forEach { m -> (if (m.models.isNotEmpty()) m.models else m.discovered).forEach { models.add(it) } }
        app.engine.modelInfo.keys.forEach { models.add(it) }
        modelSpin.adapter = ArrayAdapter(act, android.R.layout.simple_spinner_dropdown_item,
            models.ifEmpty { listOf("qfmodel", "dfmodel") }.toList())
    }

    private fun chosenAccount(): Account? {
        val accs = app.repo.accounts.filter { it.enabled }
        val idx = accountSpin.selectedItemPosition - 1
        return if (idx < 0) accs.firstOrNull() else accs.getOrNull(idx)
    }

    private fun probe() {
        val acc = chosenAccount() ?: run { act.toast("add an account first"); return }
        val model = modelSpin.selectedItem?.toString().orEmpty()
        out.text = "signing + sending…"; statusTv.text = "account ${acc.label} → $model"
        act.lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                try {
                    val reqId = java.util.UUID.randomUUID().toString()
                    val openai = JSONObject()
                        .put("model", model)
                        .put("messages", org.json.JSONArray().put(JSONObject().put("role", "user").put("content", prompt.text.toString())))
                        .put("stream", true)
                    val wire = app.engine.buildWire(model, openai, reqId, java.util.UUID.randomUUID().toString())
                    val resp = QoderClient.openChat(acc, app.signer, wire, model)
                    val sb = StringBuilder("HTTP ${resp.code}\n")
                    resp.use { r ->
                        val rd = java.io.BufferedReader(java.io.InputStreamReader(r.body!!.byteStream()))
                        var n = 0
                        while (n < 80) {
                            val line = rd.readLine() ?: break
                            sb.appendLine(line.take(360)); n++
                            if (line.contains("[DONE]")) break
                        }
                    }
                    sb.toString()
                } catch (e: Exception) { "EXCEPTION ${e.javaClass.simpleName}: ${e.message}" }
            }
            out.text = res; statusTv.text = "probe finished"
        }
    }

    private fun callRelay() {
        val model = modelSpin.selectedItem?.toString().orEmpty()
        val stream = streamBox.isChecked
        out.text = "calling http://127.0.0.1:${app.port}/v1/chat/completions …"
        statusTv.text = "relay loopback"
        act.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val conn = java.net.URL("http://127.0.0.1:${app.port}/v1/chat/completions")
                        .openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "POST"; conn.connectTimeout = 20000; conn.readTimeout = 200000
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.setRequestProperty("Authorization", "Bearer " + app.relayKey)
                    conn.doOutput = true
                    val body = JSONObject().put("model", model).put("stream", stream)
                        .put("messages", org.json.JSONArray().put(JSONObject().put("role", "user").put("content", prompt.text.toString())))
                    conn.outputStream.use { it.write(body.toString().toByteArray()) }
                    val code = conn.responseCode
                    val rd = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()
                    val sb = StringBuilder("HTTP $code\n")
                    rd?.let { r ->
                        var n = 0
                        while (n < 200) { val line = r.readLine() ?: break; sb.appendLine(line.take(500)); n++; if (line.contains("[DONE]")) break }
                        r.close()
                    }
                    sb.toString()
                } catch (e: Exception) { "EXCEPTION ${e.javaClass.simpleName}: ${e.message}" }
            }
            out.text = result; statusTv.text = "relay call finished"
        }
    }
}

/* ---------------- Settings ---------------- */

class SettingsTab(private val act: AppCompatActivity, private val root: View) {
    private val app get() = act.app()

    init {
        val lan = app.lanAddress()
        root.findViewById<TextView>(R.id.tv_endpoint).text =
            "http://" + (lan.ifEmpty { "<wifi-ip>" }) + ":" + app.port + "/v1\nhttp://127.0.0.1:" + app.port + "/v1"
        root.findViewById<TextView>(R.id.tv_server_state).text =
            if (app.isRunning()) "server: running" else "server: STOPPED " + app.serverError
        root.findViewById<TextView>(R.id.tv_key).text = app.relayKey
        val portEdit = root.findViewById<EditText>(R.id.et_port).apply { setText(app.port.toString()) }
        root.findViewById<View>(R.id.btn_copy_endpoint).setOnClickListener {
            act.copyText("http://" + (lan.ifEmpty { "<wifi-ip>" }) + ":" + app.port + "/v1\nAuthorization: Bearer " + app.relayKey)
        }
        root.findViewById<View>(R.id.btn_regen_key).setOnClickListener {
            MaterialAlertDialogBuilder(act).setTitle("Regenerate key?")
                .setMessage("Clients must be reconfigured.")
                .setPositiveButton("Regenerate") { _, _ ->
                    app.regenerateKey()
                    root.findViewById<TextView>(R.id.tv_key).text = app.relayKey
                    act.toast("key regenerated")
                }
                .setNegativeButton("Cancel", null).show()
        }
        root.findViewById<View>(R.id.btn_apply_port).setOnClickListener {
            val p = portEdit.text.toString().toIntOrNull() ?: 0
            app.changePort(p)
            root.findViewById<TextView>(R.id.tv_endpoint).text =
                "http://" + (app.lanAddress().ifEmpty { "<wifi-ip>" }) + ":" + app.port + "/v1\nhttp://127.0.0.1:" + app.port + "/v1"
            act.toast("port " + app.port)
        }
        val btnService = root.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_service)
        btnService.text = if (app.isRunning()) "stop server" else "start server"
        btnService.setOnClickListener {
            if (app.isRunning()) app.stopServer() else app.ensureServer()
            btnService.text = if (app.isRunning()) "stop server" else "start server"
            act.toast(if (app.isRunning()) "running" else "stopped")
        }
        root.findViewById<TextView>(R.id.tv_auth).text = if (app.repo.accounts.isEmpty()) "no accounts"
            else app.repo.accounts.joinToString("\n") {
                "${it.label}: ${it.status} · token ${it.token.take(8)}… · cosy ${if (it.encInfo.isNotEmpty()) "ready" else "pending"}"
            }
        val signerTv = root.findViewById<TextView>(R.id.tv_signer_state)
        fun showSignerState() {
            signerTv.text = "state: " + app.signer.lastState + (if (app.signer.lastError.isNotEmpty()) " · " + app.signer.lastError.take(120) else "")
        }
        showSignerState()
        root.findViewById<View>(R.id.btn_signer_test).setOnClickListener {
            signerTv.text = "state: " + app.signer.lastState + " · testing…"
            act.lifecycleScope.launch {
                val r = withContext(Dispatchers.IO) {
                    try {
                        val f = app.signer.genFields("selftest", "selftest", "", java.util.UUID.randomUUID().toString())
                        "ok — key " + f.optString("key").length + " chars"
                    } catch (e: Exception) { "failed: ${e.message}" }
                }
                act.toast(r)
                showSignerState()
            }
        }
    }
}
