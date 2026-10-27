package com.qoder.relay

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.tabs.TabLayout

class MainActivity : AppCompatActivity() {

    private val themes = listOf(R.style.Theme_Relay_Graphite, R.style.Theme_Relay_Warm, R.style.Theme_Relay_Midnight)
    private var themeIdx = 0
    private val tabNames = listOf("Accounts", "Models", "Usage", "Test", "Settings")
    private val cache = arrayOfNulls<View>(5)
    private var accountsTab: AccountsTab? = null
    private var modelsTab: ModelsTab? = null
    private lateinit var container: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = getSharedPreferences("relay", Context.MODE_PRIVATE)
        themeIdx = prefs.getInt("theme", 0).coerceIn(themes.indices)
        setTheme(themes[themeIdx])
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val app = application as RelayApp
        RelayService.start(this)
        app.ensureServer()

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.act_theme) {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle("Theme")
                    .setItems(arrayOf("Graphite", "Warm neutral", "Midnight slate")) { _, w ->
                        themeIdx = w
                        getSharedPreferences("relay", Context.MODE_PRIVATE).edit().putInt("theme", w).apply()
                        recreate()
                    }.show()
                true
            } else false
        }
        toolbar.inflateMenu(R.menu.main).apply {}

        container = findViewById(R.id.tab_container)
        val tabLayout = findViewById<TabLayout>(R.id.tabs)
        tabNames.forEach { tabLayout.addTab(tabLayout.newTab().setText(it)) }
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) { show(tab.position) }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) { cache[tab.position] = null; show(tab.position) }
        })
        show(0)
    }

    private fun build(index: Int): View = when (index) {
        0 -> {
            val v = layoutInflater.inflate(R.layout.tab_accounts, container, false)
            accountsTab = AccountsTab(this, v); v
        }
        1 -> {
            val v = layoutInflater.inflate(R.layout.tab_models, container, false)
            modelsTab = ModelsTab(this, v); v
        }
        2 -> {
            val v = layoutInflater.inflate(R.layout.tab_usage, container, false)
            UsageTab(this, v); v
        }
        3 -> {
            val v = layoutInflater.inflate(R.layout.tab_test, container, false)
            TestTab(this, v); v
        }
        else -> {
            val v = layoutInflater.inflate(R.layout.tab_settings, container, false)
            SettingsTab(this, v); v
        }
    }

    private fun show(index: Int) {
        val view = cache[index] ?: build(index).also { cache[index] = it }
        (view.parent as? FrameLayout)?.removeView(view)
        container.removeAllViews()
        container.addView(view, FrameLayout.LayoutParams(-1, -1))
        if (index == 1) modelsTab?.render()
    }
}
