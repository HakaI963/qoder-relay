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
    private val tabs = listOf("Accounts", "Models", "Usage", "Test", "Settings")
    private val cache = arrayOfNulls<View>(5)
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
        toolbar.menu.add(0, 1, 0, "Theme").setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER)
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == 1) {
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

        container = findViewById(R.id.tab_container)
        val tabLayout = findViewById<TabLayout>(R.id.tabs)
        tabs.forEach { tabLayout.addTab(tabLayout.newTab().setText(it)) }
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) { show(tab.position) }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        show(0)
    }

    private fun buildView(index: Int): View = when (index) {
        0 -> AccountsTab(this).build()
        1 -> modelsTab(this)
        2 -> usageTab(this)
        3 -> testTab(this)
        else -> settingsTab(this)
    }

    private fun show(index: Int) {
        val view = cache[index] ?: buildView(index).also { cache[index] = it }
        (view.parent as? FrameLayout)?.removeView(view)
        container.removeAllViews()
        container.addView(view, FrameLayout.LayoutParams(-1, -1))
    }
}
