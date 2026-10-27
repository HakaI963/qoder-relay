package com.qoder.relay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.DateFormat

/* ---------------- shared helpers ---------------- */

fun Context.relayApp(): RelayApp = applicationContext as RelayApp
fun Context.dpv(v: Float): Int = (v * resources.displayMetrics.density).toInt()
fun fmtTok(n: Long): String = when {
    n >= 1_000_000 -> String.format("%.2fM", n / 1e6)
    n >= 1_000 -> String.format("%.1fk", n / 1e3)
    else -> n.toString()
}
fun AppCompatActivity.toast(m: String) =
    android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_SHORT).show()
fun AppCompatActivity.copyText(t: String) {
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("relay", t)); toast("copied")
}

private fun Context.labelUpper(s: String) = TextView(this).apply {
    text = s.uppercase(); textSize = 11f; letterSpacing = 0.06f
    setTextColor(0xFF929292.toInt()); setPadding(0, dpv(14f), 0, dpv(6f))
}
private fun Context.h1(s: String) = TextView(this).apply {
    text = s; textSize = 18f; setTypeface(null, Typeface.BOLD); setPadding(0, dpv(4f), 0, dpv(8f))
}
private fun Context.body(s: CharSequence, mono: Boolean = false) = TextView(this).apply {
    text = s; textSize = 12.5f; setTextIsSelectable(true)
    if (mono) typeface = Typeface.MONOSPACE
}
fun Context.card(vararg children: View): com.google.android.material.card.MaterialCardView {
    val c = com.google.android.material.card.MaterialCardView(this).apply {
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dpv(10f) }
        setPadding(dpv(14f), dpv(12f), dpv(14f), dpv(12f))
    }
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    children.forEach { col.addView(it) }
    c.addView(col)
    return c
}
fun Context.actionBtn(label: String, onClick: () -> Unit) =
    MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        text = label; textSize = 12.5f; setOnClickListener { onClick() }
    }
fun Context.themeColor(attr: Int): Int {
    val tv = android.util.TypedValue()
    theme.resolveAttribute(attr, tv, true)
    return if (tv.resourceId != 0) androidx.core.content.ContextCompat.getColor(this, tv.resourceId) else tv.data
}
fun Context.textAction(label: String, onClick: () -> Unit) = TextView(this).apply {
    text = label; textSize = 12.5f; isClickable = true; isFocusable = true
    setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
    setPadding(dpv(4f), dpv(10f), dpv(14f), 0)
    setOnClickListener { onClick() }
}
fun Context.spacer(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(-1, dpv(h.toFloat())) }
fun Context.pageColumn(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dpv(14f), dpv(6f), dpv(14f), dpv(20f))
}
fun Context.scrollWrap(col: View): ScrollView = ScrollView(this).apply {
    addView(col, LinearLayout.LayoutParams(-1, -2))
    setPadding(0, 0, 0, 0)
}

fun AppCompatActivity.autoTick(view: View, everyMs: Long, block: () -> Unit) {
    val handler = Handler(Looper.getMainLooper())
    val runnable = object : Runnable { override fun run() { block(); handler.postDelayed(this, everyMs) } }
    view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) { handler.post(runnable) }
        override fun onViewDetachedFromWindow(v: View) { handler.removeCallbacks(runnable) }
    })
}

/* ---------------- Accounts tab ---------------- */

class AccountsTab(private val act: AppCompatActivity) {
    private lateinit var adapter: AccountAdapter
    private val app get() = act.relayApp()

    fun build(): View {
        val frame = FrameLayout(act)
        val recycler = RecyclerView(act).apply {
            layoutManager = LinearLayoutManager(act)
            setPadding(act.dpv(12f), act.dpv(4f), act.dpv(12f), act.dpv(96f))
            clipToPadding = false
        }
        adapter = AccountAdapter()
        recycler.adapter = adapter
        frame.addView(recycler, FrameLayout.LayoutParams(-1, -1))
        val fab = ExtendedFloatingActionButton(act).apply {
            text = "+ Add account"; extend()
            layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.BOTTOM)
                .apply { setMargins(0, 0, act.dpv(16f), act.dpv(16f)) }
            setOnClickListener { askAddMethod() }
        }
        frame.addView(fab)
        act.autoTick(frame, 5000) { adapter.submit() }
        adapter.submit()
        return frame
    }

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
            text = url; setTextIsSelectable(true); setPadding(act.dpv(22f), act.dpv(10f), act.dpv(22f), 0); textSize = 12f
        }
        val dialog = MaterialAlertDialogBuilder(act)
            .setTitle("Sign in with Qoder")
            .setMessage("Approve in the browser — the relay captures the token automatically (same flow as the CLI).")
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
                    try { prepareAccount(acc); true } catch (e: Exception) { false }
                }
                if (!prepared) acc.lastError = "model list failed — tap refresh"
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
        fun submit() { items = app.repo.accounts.toList(); notifyDataSetChanged() }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val dot: View = v.findViewById(R.id.dot)
            val name: TextView = v.findViewById(R.id.tv_name)
            val meta: TextView = v.findViewById(R.id.tv_meta)
            val sw: MaterialSwitch = v.findViewById(R.id.sw_enabled)
            val del: ImageButton = v.findViewById(R.id.btn_delete)
            val chips: ChipGroup = v.findViewById(R.id.chip_group)
            val refresh: TextView = v.findViewById(R.id.btn_refresh_models)
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
                (if (a.encInfo.isEmpty()) " · cosy: pending" else " · cosy ✓") +
                (if (a.lastError.isNotEmpty()) " · " + a.lastError.take(40) else "")
            h.sw.setOnCheckedChangeListener(null)
            h.sw.isChecked = a.enabled
            h.sw.setOnCheckedChangeListener { _, on -> a.enabled = on; app.repo.save() }
            h.itemView.setOnClickListener { showDetail(a) }
            h.del.setOnClickListener {
                MaterialAlertDialogBuilder(act).setTitle("Remove ${a.label}?")
                    .setMessage("Token and stats for this account are deleted.")
                    .setPositiveButton("Remove") { _, _ -> app.repo.accounts.remove(a); app.repo.save(); submit() }
                    .setNegativeButton("Cancel", null).show()
            }
            h.refresh.setOnClickListener {
                h.refresh.text = "refreshing…"
                act.lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { prepareAccount(a); app.repo.save() }
                        submit(); act.toast("models refreshed")
                    } catch (e: Exception) { act.toast("failed: " + (e.message ?: "")); h.refresh.text = "refresh models" }
                }
            }
            fillChips(h.chips, a)
        }

        private fun showDetail(a: Account) {
            val exp = DateFormat.getDateTimeInstance().format(java.util.Date(a.expiresAt))
            val body = buildString {
                appendLine("id        ${a.id}")
                appendLine("kind      ${a.kind} (${a.region})")
                appendLine("auth      ${a.status} · token expires $exp")
                appendLine("uid       ${a.uid.ifEmpty { "—" }}")
                appendLine("token     ${a.token.take(8)}…")
                appendLine("machine   ${a.machineId}")
                appendLine("cosy      ${if (a.encInfo.isNotEmpty()) "fields ready" else "pending"} · key ${a.cosyKey.take(10)}…")
                appendLine("models    ${if (a.models.isEmpty()) "all (universal)" else a.models.joinToString()}")
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

    fun fillChips(group: ChipGroup, a: Account) {
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
            text = "no models yet — tap refresh"; textSize = 12f; setTextColor(0xFF929292.toInt())
        })
    }
}

/* ---------------- Models tab ---------------- */

fun modelsTab(act: AppCompatActivity): View {
    val col = act.pageColumn()
    col.addView(act.h1("Model selection"))
    col.addView(act.body("Universal = empty allow-list (account serves everything). Checked models restrict an account. Pin one account per request with \"relay_account\" in the JSON body.", mono = false))
    val app = act.relayApp()
    app.repo.accounts.forEach { a ->
        val chips = ChipGroup(act)
        val universal = MaterialSwitch(act).apply {
            isChecked = a.universal
            setOnCheckedChangeListener { _, on -> a.universal = on; app.repo.save() }
        }
        val head = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(TextView(act).apply { text = a.label; textSize = 15f; setTypeface(null, Typeface.BOLD) },
                LinearLayout.LayoutParams(0, -2, 1f))
            addView(universal)
        }
        val actions = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(act.textAction("refresh models") {
                act.lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            val entries = QoderClient.listModels(a, app.signer)
                            a.discovered = entries.map { it.optString("key") }.filter { it.isNotEmpty() }.toMutableList()
                            entries.forEach { val k = it.optString("key"); if (k.isNotEmpty()) app.engine.modelInfo[k] = it.optString("display_name") to it.optBoolean("is_reasoning") }
                            app.repo.save()
                        }
                        act.toast("models refreshed — reopen Models tab")
                    } catch (e: Exception) { act.toast("failed: " + (e.message ?: "")) }
                }
            })
            addView(act.textAction("allow all (clear)") { a.models.clear(); app.repo.save(); act.toast("saved") })
        }
        col.addView(act.card(
            head,
            TextView(act).apply { text = "universal pool"; textSize = 11f; setTextColor(0xFF929292.toInt()) },
            chips, actions,
            TextView(act).apply { text = if (a.models.isEmpty()) "serving: ALL models" else "serving: " + a.models.joinToString(); textSize = 11.5f; setPadding(0, act.dpv(6f), 0, 0); setTextColor(0xFF929292.toInt()) }
        ))
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
                }
            }
            chips.addView(chip)
        }
        if (keys.isEmpty()) chips.addView(TextView(act).apply {
            text = "no models discovered yet"; textSize = 12f; setTextColor(0xFF929292.toInt())
        })
    }
    return act.scrollWrap(col)
}

/* ---------------- Usage tab ---------------- */

fun usageTab(act: AppCompatActivity): View {
    val app = act.relayApp()
    val col = act.pageColumn()
    col.addView(act.h1("Token usage"))
    fun statCard(title: String, value: String) = act.card(
        TextView(act).apply { text = title.uppercase(); textSize = 11f; letterSpacing = 0.06f; setTextColor(0xFF929292.toInt()) },
        TextView(act).apply { text = value; textSize = 22f; setTypeface(null, Typeface.BOLD); setPadding(0, act.dpv(4f), 0, 0) }
    )
    val stats = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
    val c1 = statCard("Tokens in", fmtTok(app.repo.totalIn()))
    val c2 = statCard("Tokens out", fmtTok(app.repo.totalOut()))
    c1.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = act.dpv(8f) }
    c2.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
    stats.addView(c1); stats.addView(c2)
    col.addView(stats)
    col.addView(act.card(
        TextView(act).apply { text = "REQUESTS"; textSize = 11f; setTextColor(0xFF929292.toInt()) },
        TextView(act).apply {
            text = "${app.repo.accounts.sumOf { it.reqs }} total · ${app.repo.accounts.sumOf { it.errs }} errors · ${app.repo.accounts.sumOf { it.failovers }} failovers"
            textSize = 13f; setPadding(0, act.dpv(4f), 0, 0)
        }
    ))
    col.addView(act.labelUpper("per account"))
    val table = act.card(
        TextView(act).apply {
            text = if (app.repo.accounts.isEmpty()) "—" else app.repo.accounts.joinToString("\n") {
                "${it.label}:  in ${fmtTok(it.inTok)}  out ${fmtTok(it.outTok)}  req ${it.reqs}  err ${it.errs}"
            }
            typeface = Typeface.MONOSPACE; textSize = 12f
        })
    col.addView(table)
    col.addView(act.labelUpper("recent requests"))
    val logTv = TextView(act).apply { typeface = Typeface.MONOSPACE; textSize = 11f; setTextIsSelectable(true) }
    col.addView(act.card(logTv))
    fun render() {
        val sb = StringBuilder()
        synchronized(app.engine.recent) {
            app.engine.recent.take(40).forEach { e ->
                sb.appendLine("${DateFormat.getTimeInstance(DateFormat.SHORT).format(java.util.Date(e.optLong("ts")))} ${e.optString("state")} ${e.optString("model")} ${e.optLong("in")}→${e.optLong("out")} ${e.optLong("ms")}ms [${e.optString("account")}] ${e.optString("status")}")
            }
        }
        logTv.text = if (sb.isEmpty()) "—" else sb.toString().trimEnd()
    }
    render()
    val wrap = act.scrollWrap(col)
    act.autoTick(wrap, 3000) { render() }
    return wrap
}

/* ---------------- Test tab ---------------- */

fun testTab(act: AppCompatActivity): View {
    val app = act.relayApp()
    val col = act.pageColumn()
    col.addView(act.h1("Test"))
    val accountSpin = Spinner(act)
    val modelSpin = Spinner(act)
    val prompt = EditText(act).apply { setText("Reply with exactly: RELAY-APP-OK"); minLines = 1 }
    val streamBox = com.google.android.material.checkbox.MaterialCheckBox(act).apply { text = "stream"; isChecked = true }
    val statusTv = TextView(act).apply { textSize = 12f; setTextColor(0xFF929292.toInt()) }
    val out = TextView(act).apply {
        typeface = Typeface.MONOSPACE; textSize = 11f; setTextIsSelectable(true)
        setPadding(act.dpv(10f), act.dpv(10f), act.dpv(10f), act.dpv(10f))
    }
    fun fillSpinners() {
        val accs = app.repo.accounts.filter { it.enabled }
        accountSpin.adapter = ArrayAdapter(act, android.R.layout.simple_spinner_dropdown_item,
            (if (accs.isNotEmpty()) listOf("auto (pool)") else listOf("auto (no accounts!)")) + accs.map { it.label })
        val models = LinkedHashSet<String>()
        accs.forEach { m -> (if (m.models.isNotEmpty()) m.models else m.discovered).forEach { models.add(it) } }
        app.engine.modelInfo.keys.forEach { models.add(it) }
        modelSpin.adapter = ArrayAdapter(act, android.R.layout.simple_spinner_dropdown_item,
            models.ifEmpty { listOf("qfmodel", "dfmodel") }.toList())
    }
    fillSpinners()
    col.addView(act.labelUpper("account")); col.addView(accountSpin)
    col.addView(act.labelUpper("model")); col.addView(modelSpin)
    col.addView(act.labelUpper("prompt")); col.addView(prompt)
    col.addView(streamBox)
    val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, act.dpv(8f), 0, 0) }
    row.addView(act.actionBtn("Probe upstream") {
        val accs = app.repo.accounts.filter { it.enabled }
        val idx = accountSpin.selectedItemPosition - 1
        val acc = if (idx < 0) accs.firstOrNull() else accs.getOrNull(idx)
        if (acc == null) { act.toast("add an account first"); return@actionBtn }
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
    })
    row.addView(act.actionBtn("Call relay") {
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
                    val rd = ((if (code in 200..299) conn.inputStream else conn.errorStream))?.bufferedReader()
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
    })
    col.addView(row)
    col.addView(statusTv)
    val outScroll = ScrollView(act).apply {
        addView(out, LinearLayout.LayoutParams(-1, -2))
        layoutParams = LinearLayout.LayoutParams(-1, act.dpv(320f))
    }
    col.addView(outScroll)
    return act.scrollWrap(col)
}

/* ---------------- Settings tab ---------------- */

fun settingsTab(act: AppCompatActivity): View {
    val app = act.relayApp()
    val col = act.pageColumn()
    col.addView(act.h1("Settings"))
    val lan = app.lanAddress()
    col.addView(act.card(
        TextView(act).apply { text = "ENDPOINT"; textSize = 11f; letterSpacing = 0.06f; setTextColor(0xFF929292.toInt()) },
        act.body("http://" + (lan.ifEmpty { "<wifi-ip>" }) + ":" + app.port + "/v1\nhttp://127.0.0.1:" + app.port + "/v1", mono = true),
        act.body(if (app.isRunning()) "server: running" else "server: STOPPED " + app.serverError, mono = true),
        act.actionBtn("copy url + key") { act.copyText("http://" + (lan.ifEmpty { "<wifi-ip>" }) + ":" + app.port + "/v1\nAuthorization: Bearer " + app.relayKey) }
    ))
    col.addView(act.card(
        TextView(act).apply { text = "RELAY API KEY"; textSize = 11f; setTextColor(0xFF929292.toInt()) },
        act.body(app.relayKey, mono = true),
        act.actionBtn("regenerate") {
            MaterialAlertDialogBuilder(act).setTitle("Regenerate key?")
                .setMessage("Clients must be reconfigured.")
                .setPositiveButton("Regenerate") { _, _ -> app.regenerateKey(); act.toast("new key: " + app.relayKey) }
                .setNegativeButton("Cancel", null).show()
        }
    ))
    val portEdit = EditText(act).apply { setText(app.port.toString()); inputType = android.text.InputType.TYPE_CLASS_NUMBER }
    val portRow = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(portEdit, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = act.dpv(8f) })
        addView(act.actionBtn("apply") {
            val p = portEdit.text.toString().toIntOrNull() ?: 0
            app.changePort(p); act.toast("port " + app.port)
        })
    }
    col.addView(act.card(TextView(act).apply { text = "SERVER PORT"; textSize = 11f; setTextColor(0xFF929292.toInt()) }, portRow))
    col.addView(act.card(
        TextView(act).apply { text = "FOREGROUND SERVICE"; textSize = 11f; setTextColor(0xFF929292.toInt()) },
        act.body("keeps the relay alive when the screen is off"),
        act.actionBtn(if (app.isRunning()) "stop server" else "start server") {
            if (app.isRunning()) app.stopServer() else app.ensureServer(); act.toast(if (app.isRunning()) "running" else "stopped")
        }
    ))
    col.addView(act.card(
        TextView(act).apply { text = "ACCOUNT AUTH"; textSize = 11f; setTextColor(0xFF929292.toInt()) },
        act.body(if (app.repo.accounts.isEmpty()) "no accounts" else app.repo.accounts.joinToString("\n") {
            "${it.label}: ${it.status} · ${it.kind} · token ${it.token.take(8)}… · cosy ${if (it.encInfo.isNotEmpty()) "ready" else "pending"}"
        }, mono = true)
    ))
    col.addView(act.card(
        TextView(act).apply { text = "COSY SIGNER"; textSize = 11f; setTextColor(0xFF929292.toInt()) },
        act.body("vendor wasm signer (original module) running in an embedded JS engine — same signatures as the CLI.", mono = false),
        act.actionBtn("self-test") {
            act.lifecycleScope.launch {
                val r = withContext(Dispatchers.IO) {
                    try {
                        val f = app.signer.genFields("self", "self", "", java.util.UUID.randomUUID().toString())
                        if (f.length() > 0) "ok — fields produced (${f.optString("key").length} chars key)" else "empty output"
                    } catch (e: Exception) { "failed: ${e.message}" }
                }
                act.toast(r)
            }
        }
    ))
    col.addView(act.card(TextView(act).apply {
        text = "qoder-relay v0.3 · protocol verified against the live gateway · APK built in CI"
        textSize = 11.5f; setTextColor(0xFF929292.toInt())
    }))
    return act.scrollWrap(col)
}
