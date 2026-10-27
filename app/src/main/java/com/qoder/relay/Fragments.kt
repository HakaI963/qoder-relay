package com.qoder.relay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Checkable
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.fragment.app.Fragment
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat

/* ---------------- shared helpers ---------------- */

fun Fragment.app() = requireContext().applicationContext as RelayApp
fun View.ctx() = context.applicationContext as RelayApp
fun Context.dpv(v: Float): Int = (v * resources.displayMetrics.density).toInt()
fun Fragment.dpv(v: Float): Int = (v * resources.displayMetrics.density).toInt()
fun Context.textCard(title: String, body: CharSequence): LinearLayout {
    val pad = dpv(16f)
    val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
    card.addView(TextView(this).apply {
        text = title.uppercase(); setTextColor(0xFF929292.toInt()); textSize = 11f; letterSpacing = 0.06f
    })
    card.addView(TextView(this).apply {
        text = body; textSize = 13f; setPadding(0, dpv(6f), 0, 0)
        typeface = android.graphics.Typeface.MONOSPACE; setTextIsSelectable(true)
    })
    return card
}
fun Fragment.textCard(title: String, body: CharSequence): LinearLayout = requireContext().textCard(title, body)
fun fmtTok(n: Long): String = when {
    n >= 1_000_000 -> String.format("%.2fM", n / 1e6)
    n >= 1_000 -> String.format("%.1fk", n / 1e3)
    else -> n.toString()
}

fun Fragment.toast(m: String) =
    android.widget.Toast.makeText(requireContext(), m, android.widget.Toast.LENGTH_SHORT).show()

fun Fragment.copyText(t: String) {
    val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("relay", t)); toast("copied")
}

fun Fragment.vpad(h: Int = 12): View = View(requireContext()).apply {
    layoutParams = LinearLayout.LayoutParams(-1, dpv(h.toFloat()))
}

/* ---------------- Accounts ---------------- */

class AccountsFragment : Fragment() {
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: AccountAdapter
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreateView(i: LayoutInflater, root: ViewGroup?, s: Bundle?): View {
        val frame = FrameLayout(requireContext())
        recycler = RecyclerView(requireContext()).apply {
            layoutManager = LinearLayoutManager(requireContext())
            setPadding(dpv(12f), dpv(8f), dpv(12f), dpv(90f))
            clipToPadding = false
        }
        adapter = AccountAdapter()
        recycler.adapter = adapter
        frame.addView(recycler, FrameLayout.LayoutParams(-1, -1))
        val fab = ExtendedFloatingActionButton(requireContext()).apply {
            text = "+ Add account"; extend()
            layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.BOTTOM).apply {
                setMargins(dpv(14f), 0, dpv(14f), dpv(14f))
            }
            setOnClickListener { askAddMethod() }
        }
        frame.addView(fab)
        return frame
    }

    override fun onResume() { super.onResume(); adapter.submit(); handler.postDelayed(tick, 5000) }
    override fun onPause() { handler.removeCallbacks(tick); super.onPause() }
    private val tick = object : Runnable { override fun run() { adapter.submit(); handler.postDelayed(this, 5000) } }

    fun askAddMethod() {
        val opts = arrayOf("Sign in with Qoder (browser)", "Paste Personal Access Token (pt-…)")
        MaterialAlertDialogBuilder(requireContext()).setTitle("Add account")
            .setItems(opts) { _, w -> if (w == 0) startDeviceLogin() else askPat() }.show()
    }

    private fun askPat() {
        val input = EditText(requireContext()).apply { hint = "pt-…"; setSingleLine() }
        MaterialAlertDialogBuilder(requireContext()).setTitle("Paste PAT")
            .setMessage("Create one at qoder.com → Account → Integrations.")
            .setView(input)
            .setPositiveButton("Add") { _, _ -> doPat(input.text.toString().trim()) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun doPat(pat: String) {
        val app = app()
        if (!pat.startsWith("pt-")) { toast("PAT must start with pt-"); return }
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val t = QoderClient.patExchange(pat)
                    val a = Account(label = "pat-" + (app.repo.accounts.size + 1), kind = "pat")
                    a.token = t.getString("token"); a.refreshToken = t.optString("refreshToken")
                    a.expiresAt = t.getLong("expiresAt"); a.status = "active"
                    a.discovered = QoderClient.listModelKeys(a).toMutableList()
                    Result.success(a)
                } catch (e: Exception) { Result.failure(e) }
            }
            result.onSuccess {
                app.repo.accounts.add(it); app.repo.save(); adapter.submit(); toast("account added")
            }.onFailure { toast("failed: " + (it.message ?: "")) }
        }
    }

    private fun startDeviceLogin() {
        val regions = arrayOf("global (qoder.com)", "cn (qoder.com.cn)")
        MaterialAlertDialogBuilder(requireContext()).setTitle("Region")
            .setItems(regions) { _, w -> runDeviceLogin(if (w == 0) "global" else "cn") }.show()
    }

    private fun runDeviceLogin(region: String) {
        val app = app()
        val (st, url) = QoderClient.loginStart(region)
        val box = TextView(requireContext()).apply {
            text = url; setTextIsSelectable(true); setPadding(dpv(22f), dpv(10f), dpv(22f), 0); textSize = 12f
        }
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Sign in with Qoder")
            .setMessage("Open this link and approve — the relay signs in the same way the CLI does. Waiting for approval…")
            .setView(box)
            .setPositiveButton("Open", null)
            .setNegativeButton("Copy", null)
            .setNeutralButton("Cancel", null)
            .show()
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { }
        }
        dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener { copyText(url) }

        viewLifecycleOwner.lifecycleScope.launch {
            var tries = 0; var added = false; var tokens: JSONObject? = null
            while (tries < 145 && !added && viewLifecycleOwner.lifecycle.currentState != androidx.lifecycle.Lifecycle.State.DESTROYED) {
                delay(2000); tries++
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
                adapter.submit(); toast("signed in — account added")
            } else if (dialog.isShowing && tries >= 145) {
                dialog.dismiss(); toast("login window expired — try again")
            }
        }
    }

    inner class AccountAdapter : RecyclerView.Adapter<AccountAdapter.VH>() {
        private var items: List<Account> = emptyList()
        fun submit() { items = app().repo.accounts.toList(); notifyDataSetChanged() }

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
            val app = app()
            val a = items[pos]
            h.name.text = a.label
            val color = when (a.status) {
                "active" -> R.color.ok_g
                "auth_error", "error" -> R.color.err_g
                else -> R.color.warn_g
            }
            h.dot.background?.setTint(requireContext().getColor(color))
            h.meta.text = "${a.kind} · ${a.region} · ${a.reqs} req · ${fmtTok(a.inTok)} in / ${fmtTok(a.outTok)} out · ${a.errs} err · ${a.failovers} fo" +
                (if (a.lastError.isNotEmpty()) " · " + a.lastError.take(48) else "")
            h.sw.setOnCheckedChangeListener(null)
            h.sw.isChecked = a.enabled
            h.sw.setOnCheckedChangeListener { _, on -> a.enabled = on; app.repo.save() }
            h.itemView.setOnClickListener { showDetail(a) }
            h.del.setOnClickListener {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Remove ${a.label}?").setMessage("Token and stats are deleted for this account.")
                    .setPositiveButton("Remove") { _, _ -> app.repo.accounts.remove(a); app.repo.save(); submit() }
                    .setNegativeButton("Cancel", null).show()
            }
            h.refresh.setOnClickListener {
                h.refresh.text = "refreshing…"
                viewLifecycleOwner.lifecycleScope.launch {
                    val keys = try { withContext(Dispatchers.IO) { QoderClient.listModelKeys(a) } } catch (e: Exception) { emptyList() }
                    a.discovered = keys.toMutableList(); app.repo.save(); submit()
                }
            }
            buildModelChips(h.chips, a)
        }

        private fun showDetail(a: Account) {
            val exp = DateFormat.getDateTimeInstance().format(java.util.Date(a.expiresAt))
            val body = buildString {
                appendLine("id        ${a.id}")
                appendLine("kind      ${a.kind} (${a.region})")
                appendLine("auth      ${a.status} · expires ${exp}")
                appendLine("token     ${a.token.take(6)}…${if (a.token.isNotEmpty()) "" else "(empty)"}")
                appendLine("machine   ${a.machineId}")
                appendLine("modelMode ${a.modelMode} · allow ${if (a.models.isEmpty()) "ALL" else a.models.size}")
                appendLine("universal ${a.universal}")
                appendLine("counters  req ${a.reqs} · in ${a.inTok} · out ${a.outTok}")
                appendLine("errors    ${a.errs} · failovers ${a.failovers}")
                if (a.lastError.isNotEmpty()) appendLine("lastErr   ${a.lastError}")
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(a.label).setMessage(body)
                .setPositiveButton("Rename") { _, _ ->
                    val input = EditText(requireContext()).apply { setText(a.label) }
                    MaterialAlertDialogBuilder(requireContext()).setTitle("Label").setView(input)
                        .setPositiveButton("Save") { _, _ -> a.label = input.text.toString().ifBlank { a.label }; app().repo.save() }
                        .setNegativeButton("Cancel", null).show()
                }
                .setNeutralButton("Clear model allow-list") { _, _ ->
                    a.models.clear(); app().repo.save()
                }
                .show()
        }
    }
}

/* ---------------- chip builder shared by Accounts + Models ---------------- */

fun Fragment.buildModelChips(group: ChipGroup, a: Account) {
    val app = app()
    group.removeAllViews()
    val keys = if (a.discovered.isNotEmpty()) a.discovered else a.models
    for (k in keys) {
        val chip = Chip(group.context).apply {
            text = k; isCheckable = true
            isChecked = a.models.isEmpty() || a.models.contains(k)   // empty allow-list = all
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
        text = "no models discovered — tap refresh"; textSize = 12f; setTextColor(0xFF929292.toInt())
    })
}

/* ---------------- Models ---------------- */

class ModelsFragment : Fragment() {
    private lateinit var host: LinearLayout
    override fun onCreateView(i: LayoutInflater, root: ViewGroup?, s: Bundle?): View {
        host = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        return ScrollView(requireContext()).apply {
            addView(host, LinearLayout.LayoutParams(-1, -2))
            setPadding(dpv(12f), dpv(8f), dpv(12f), dpv(12f))
        }
    }

    override fun onResume() { super.onResume(); rebuild() }

    private fun rebuild() {
        host.removeAllViews()
        val app = app()
        val rc = requireContext()
        host.addView(MaterialCard(rc).apply {
            content = com.google.android.material.card.MaterialCardView(rc).apply {
                setPadding(dpv(14f), dpv(12f), dpv(14f), dpv(12f))
                addView(context.textCard("routing rules",
                    "Universal = an account with an empty allow-list serves every model to any caller.\n" +
                    "Per-account = check only the models this account should serve.\n" +
                    "Pin one account in a request with \"relay_account\" in the JSON body."))
            }
        }.build())

        for (a in app.repo.accounts) {
            val card = com.google.android.material.card.MaterialCardView(requireContext()).apply {
                setPadding(dpv(14f), dpv(12f), dpv(14f), dpv(12f))
            }
            val col = LinearLayout(rc).apply { orientation = LinearLayout.VERTICAL }
            val titleRow = LinearLayout(rc).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            titleRow.addView(TextView(rc).apply { text = a.label; textSize = 15f; setTypeface(null, android.graphics.Typeface.BOLD) })
            val universal = MaterialSwitch(rc).apply {
                isChecked = a.universal
                layoutParams = LinearLayout.LayoutParams(-2, -2, 1f).apply { marginStart = dpv(10f) }
                setOnCheckedChangeListener { _, on -> a.universal = on; app.repo.save() }
            }
            titleRow.addView(universal)
            col.addView(titleRow)
            col.addView(TextView(rc).apply {
                text = "universal pool"; textSize = 11f; setTextColor(0xFF929292.toInt())
            })
            val chips = ChipGroup(rc)
            col.addView(chips)
            val row = LinearLayout(rc).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(TextView(rc).apply {
                text = "all models (clear)"; textSize = 12f; setTextColor(0xFFEDEEF0.toInt())
                setPadding(0, dpv(6f), dpv(16f), 0); isClickable = true
                setOnClickListener { a.models.clear(); app.repo.save(); rebuild() }
            })
            row.addView(TextView(rc).apply {
                text = "refresh models"; textSize = 12f; setTextColor(0xFFEDEEF0.toInt())
                isClickable = true
                setOnClickListener {
                    viewLifecycleOwner.lifecycleScope.launch {
                        try { a.discovered = withContext(Dispatchers.IO) { QoderClient.listModelKeys(a) }.toMutableList() } catch (e: Exception) { }
                        app.repo.save(); rebuild()
                    }
                }
            })
            col.addView(row)
            card.addView(col)
            buildModelChipsOn(chips, a)
            host.addView(vpad(6)); host.addView(card)
        }
    }

    private fun buildModelChipsOn(group: ChipGroup, a: Account) {
        val app = app()
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
            text = "no models discovered — tap refresh"; textSize = 12f; setTextColor(0xFF929292.toInt())
        })
    }

    private class MaterialCard(val context: Context) {
        var content: View? = null
        fun build(): View {
            val card = com.google.android.material.card.MaterialCardView(context).apply {
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = context.dpv(8f) }
                content?.let { addView(it) }
            }
            return card
        }
    }
}

/* ---------------- Usage ---------------- */

class UsageFragment : Fragment() {
    private lateinit var host: LinearLayout
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreateView(i: LayoutInflater, root: ViewGroup?, s: Bundle?): View {
        host = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        return ScrollView(requireContext()).apply {
            addView(host, LinearLayout.LayoutParams(-1, -2))
            setPadding(dpv(12f), dpv(8f), dpv(12f), dpv(12f))
        }
    }

    override fun onResume() { super.onResume(); render(); handler.postDelayed(tick, 3000) }
    override fun onPause() { handler.removeCallbacks(tick); super.onPause() }
    private val tick = object : Runnable { override fun run() { render(); handler.postDelayed(this, 3000) } }

    private fun stat(title: String, value: String): com.google.android.material.card.MaterialCardView {
        val c = com.google.android.material.card.MaterialCardView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dpv(8f) }
            setPadding(dpv(14f), dpv(12f), dpv(14f), dpv(12f))
        }
        c.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply { text = title.uppercase(); textSize = 11f; setTextColor(0xFF929292.toInt()); letterSpacing = 0.06f })
            addView(TextView(context).apply { text = value; textSize = 22f; setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, dpv(4f), 0, 0) })
        })
        return c
    }

    private fun render() {
        host.removeAllViews()
        val app = app()
        val rc = requireContext()
        val a = app.repo.accounts
        val row1 = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(stat("Tokens in", fmtTok(app.repo.totalIn())))
        row1.addView(stat("Tokens out", fmtTok(app.repo.totalOut())))
        host.addView(row1); host.addView(vpad(8))
        val row2 = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(stat("Requests", a.sumOf { it.reqs }.toString()))
        row2.addView(stat("Errors / FO", "${a.sumOf { it.errs }} / ${a.sumOf { it.failovers }}"))
        host.addView(row2); host.addView(vpad(10))

        host.addView(TextView(rc).apply { text = "PER ACCOUNT"; textSize = 11f; setTextColor(0xFF929292.toInt()); letterSpacing = 0.06f })
        val table = com.google.android.material.card.MaterialCardView(context).apply {
            setPadding(dpv(12f), dpv(8f), dpv(12f), dpv(8f))
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        val col = LinearLayout(rc).apply { orientation = LinearLayout.VERTICAL }
        if (a.isEmpty()) col.addView(TextView(rc).apply { text = "no accounts"; setTextColor(0xFF929292.toInt()) })
        a.forEach { acc ->
            col.addView(TextView(rc).apply {
                text = "${acc.label}\nin ${fmtTok(acc.inTok)}   out ${fmtTok(acc.outTok)}   req ${acc.reqs}   err ${acc.errs}   fo ${acc.failovers}"
                textSize = 12.5f; setPadding(0, dpv(6f), 0, dpv(6f))
            })
        }
        table.addView(col); host.addView(table); host.addView(vpad(10))

        host.addView(TextView(rc).apply { text = "RECENT REQUESTS"; textSize = 11f; setTextColor(0xFF929292.toInt()); letterSpacing = 0.06f })
        val logCard = com.google.android.material.card.MaterialCardView(context).apply {
            setPadding(dpv(12f), dpv(8f), dpv(12f), dpv(8f))
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        val sb = StringBuilder()
        synchronized(app.engine.recent) {
            app.engine.recent.take(30).forEach { e ->
                sb.appendLine("${DateFormat.getTimeInstance(DateFormat.SHORT).format(java.util.Date(e.optLong("ts")))}  ${e.optString("state")}  ${e.optString("model")}  ${fmtTok(e.optLong("in"))}→${fmtTok(e.optLong("out"))}  ${e.optLong("ms")}ms  ${e.optString("account")}  ${(e.optString("status"))}")
            }
        }
        logCard.addView(TextView(rc).apply {
            text = if (sb.isEmpty()) "—" else sb.toString().trimEnd()
            textSize = 11f; typeface = android.graphics.Typeface.MONOSPACE; setTextIsSelectable(true)
        })
        host.addView(logCard)
    }
}

/* ---------------- Test ---------------- */

class TestFragment : Fragment() {
    private lateinit var accountSpin: Spinner
    private lateinit var modelSpin: Spinner
    private lateinit var prompt: EditText
    private lateinit var streamBox: com.google.android.material.checkbox.MaterialCheckBox
    private lateinit var out: TextView
    private lateinit var statusTv: TextView

    override fun onCreateView(i: LayoutInflater, root: ViewGroup?, s: Bundle?): View {
        val ctx = requireContext()
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dpv(14f), dpv(10f), dpv(14f), dpv(10f)) }
        accountSpin = Spinner(ctx)
        modelSpin = Spinner(ctx)
        prompt = EditText(ctx).apply { setText("Say OK"); minLines = 1 }
        streamBox = com.google.android.material.checkbox.MaterialCheckBox(ctx).apply { text = "stream"; isChecked = true }
        val btns = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        btns.addView(MaterialButton(ctx).apply { text = "Probe upstream"; setOnClickListener { probeDirect() } })
        btns.addView(MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Call relay"; layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dpv(8f) }
            setOnClickListener { callRelay() }
        })
        statusTv = TextView(ctx).apply { textSize = 12f; setTextColor(0xFF929292.toInt()) }
        out = TextView(ctx).apply {
            textSize = 11f; typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dpv(10f), dpv(10f), dpv(10f), dpv(10f)); setTextIsSelectable(true)
        }
        col.addView(TextView(ctx).apply { text = "ACCOUNT"; textSize = 11f; setTextColor(0xFF929292.toInt()) })
        col.addView(accountSpin)
        col.addView(TextView(ctx).apply { text = "MODEL"; textSize = 11f; setTextColor(0xFF929292.toInt()); setPadding(0, dpv(8f), 0, 0) })
        col.addView(modelSpin)
        col.addView(TextView(ctx).apply { text = "PROMPT"; textSize = 11f; setTextColor(0xFF929292.toInt()); setPadding(0, dpv(8f), 0, 0) })
        col.addView(prompt)
        col.addView(streamBox)
        col.addView(btns, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpv(8f) })
        col.addView(statusTv)
        val scroll = ScrollView(ctx).apply {
            addView(out, LinearLayout.LayoutParams(-1, -2))
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dpv(8f) }
        }
        col.addView(scroll)
        val wrap = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(col, LinearLayout.LayoutParams(-1, -1))
        return wrap
    }

    override fun onResume() {
        super.onResume()
        val app = app()
        val accs = app.repo.accounts.filter { it.enabled }
        accountSpin.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item,
            (if (accs.isNotEmpty()) listOf("auto (pool)") else listOf("auto (no accounts!)")) + accs.map { it.label })
        val models = LinkedHashSet<String>()
        accs.forEach { m -> (if (m.models.isNotEmpty()) m.models else m.discovered).forEach { models.add(it) } }
        modelSpin.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item,
            models.ifEmpty { listOf("qfmodel", "dfmodel") }.toList())
    }

    private fun chosenAccount(): Account? {
        val app = app()
        val accs = app.repo.accounts.filter { it.enabled }
        val i = accountSpin.selectedItemPosition - 1
        return if (i < 0) accs.firstOrNull() else accs.getOrNull(i)
    }

    private fun probeDirect() {
        val a = chosenAccount() ?: run { toast("add an account first"); return }
        val model = modelSpin.selectedItem?.toString().orEmpty()
        out.text = "probing…"; statusTv.text = "account ${a.label} → api"
        viewLifecycleOwner.lifecycleScope.launch {
            val acc = a
            val res = withContext(Dispatchers.IO) {
                QoderClient.probeChat(acc, model, prompt.text.toString()) { line ->
                    handlerPost { out.append(line + "\n") }
                }
            }
            statusTv.text = "done in " + a.reqs + " reqs so far · see raw below"
            out.text = res + "\n---- stream ----\n" + out.text
        }
    }

    private fun callRelay() {
        val app = app()
        val model = modelSpin.selectedItem?.toString().orEmpty()
        val stream = streamBox.isChecked
        out.text = "calling relay via HTTP loopback…"; statusTv.text = "POST http://127.0.0.1:${app.port}/v1/chat/completions"
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val conn = java.net.URL("http://127.0.0.1:" + app.port + "/v1/chat/completions")
                        .openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 20000; conn.readTimeout = 150000
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.setRequestProperty("Authorization", "Bearer " + app.relayKey)
                    conn.doOutput = true
                    val body = JSONObject().put("model", model).put("stream", stream)
                        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt.text.toString())))
                    conn.outputStream.use { it.write(body.toString().toByteArray()) }
                    val code = conn.responseCode
                    val streamIn = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()
                    val sb = StringBuilder("HTTP $code\n")
                    if (streamIn != null) {
                        var n = 0
                        while (n < 200) {
                            val line = streamIn.readLine() ?: break
                            sb.appendLine(line.take(500)); n++
                            if (line.contains("[DONE]")) break
                        }
                        streamIn.close()
                    }
                    sb.toString()
                } catch (e: Exception) { "EXCEPTION ${e.javaClass.simpleName}: ${e.message}" }
            }
            out.text = result
            statusTv.text = "relay call finished"
        }
    }

    private fun handlerPost(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else Handler(Looper.getMainLooper()).post(block)
    }
}

/* ---------------- Settings ---------------- */

class SettingsFragment : Fragment() {
    private lateinit var host: LinearLayout

    override fun onCreateView(i: LayoutInflater, root: ViewGroup?, s: Bundle?): View {
        host = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        return ScrollView(requireContext()).apply {
            addView(host, LinearLayout.LayoutParams(-1, -2))
            setPadding(dpv(12f), dpv(8f), dpv(12f), dpv(16f))
        }
    }

    override fun onResume() { super.onResume(); rebuild() }

    private fun card(view: View) {
        val c = com.google.android.material.card.MaterialCardView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dpv(10f) }
        }
        c.addView(view); host.addView(c)
    }

    private fun rebuild() {
        host.removeAllViews()
        val app = app()
        val lan = app.lanAddress()

        card(requireContext().textCard("endpoint",
            "http://" + (lan.ifEmpty { "<wifi-ip>" }) + ":" + app.port + "/v1\n" +
            "http://127.0.0.1:" + app.port + "/v1\n" +
            (if (app.isRunning()) "server: running" else "server: STOPPED " + app.serverError)))
        val copyRow = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dpv(14f), 0, dpv(14f), dpv(10f)) }
        copyRow.addView(MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "copy url + key"; setOnClickListener {
                copyText("http://" + (lan.ifEmpty { "<wifi-ip>" }) + ":" + app.port + "/v1\nAuthorization: Bearer " + app.relayKey)
            }
        })
        host.addView(copyRow); host.addView(vpad(4))

        val keyCard = requireContext().textCard("relay api key (clients must send as bearer)", app.relayKey)
        val keyRow = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
        keyRow.addView(MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "regenerate"; setOnClickListener {
                MaterialAlertDialogBuilder(requireContext()).setTitle("Regenerate key?")
                    .setMessage("Clients must be reconfigured. The key cannot be recovered afterwards.")
                    .setPositiveButton("Regenerate") { _, _ -> app.regenerateKey(); rebuild() }
                    .setNegativeButton("Cancel", null).show()
            }
        })
        keyCard.addView(keyRow)
        card(keyCard)

        val portEdit = EditText(requireContext()).apply {
            setText(app.port.toString()); inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val portRow = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        portRow.addView(portEdit, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dpv(8f) })
        portRow.addView(MaterialButton(requireContext()).apply {
            text = "apply"; setOnClickListener {
                val p = portEdit.text.toString().toIntOrNull() ?: 0
                app.changePort(p); rebuild(); toast("port " + app.port)
            }
        })
        val portCard = requireContext().textCard("server port", "")
        portCard.addView(portRow)
        card(portCard)

        val svc = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
        svc.addView(MaterialButton(requireContext()).apply {
            text = if (app.isRunning()) "stop server" else "start server"
            setOnClickListener { if (app.isRunning()) app.stopServer() else app.ensureServer(); rebuild() }
        })
        val svcCard = requireContext().textCard("service", "foreground keeps the relay alive when the screen is off")
        svcCard.addView(svc)
        card(svcCard)

        val auth = buildString {
            if (app.repo.accounts.isEmpty()) append("no accounts")
            app.repo.accounts.forEach { a ->
                appendLine("${a.label}: ${a.status} · ${a.kind} · ${a.region} · exp ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(java.util.Date(a.expiresAt))}")
            }
        }.trimEnd()
        card(requireContext().textCard("account auth", auth))

        val gwRow = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
        val gwTv = TextView(requireContext()).apply { textSize = 12f; setTextColor(0xFF929292.toInt()); gravity = Gravity.CENTER_VERTICAL }
        gwRow.addView(MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "ping gateway"; setOnClickListener {
                gwTv.text = "…"
                viewLifecycleOwner.lifecycleScope.launch {
                    val r = withContext(Dispatchers.IO) {
                        try {
                            val c = QoderClient.client.newCall(okhttp3.Request.Builder()
                                .url(QoderClient.firstInferBase() + "/algo/api/v1/ping").build()).execute()
                            c.use { "base ${QoderClient.firstInferBase()} → HTTP ${it.code}" }
                        } catch (e: Exception) { "gateway unreachable: ${e.message}" }
                    }
                    gwTv.text = r
                }
            }
        })
        gwRow.addView(gwTv, LinearLayout.LayoutParams(-1, -2).apply { marginStart = dpv(8f) })
        val gwCard = requireContext().textCard("upstream gateway", "")
        gwCard.addView(gwRow)
        card(gwCard)

        card(requireContext().textCard("about", "qoder-relay v0.2 · native UI · APK built by GitHub Actions"))
    }
}
