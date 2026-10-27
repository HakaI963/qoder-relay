package com.qoder.relay

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {

    private val themes = listOf(R.style.Theme_Relay_Graphite, R.style.Theme_Relay_Warm, R.style.Theme_Relay_Midnight)
    private var themeIdx = 0

    private val frags = HashMap<Int, Fragment>()

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = getSharedPreferences("relay", Context.MODE_PRIVATE)
        themeIdx = prefs.getInt("theme", 0).coerceIn(themes.indices)
        setTheme(themes[themeIdx])
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val app = application as RelayApp
        RelayService.start(this)
        app.ensureServer()

        val nav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        nav.setOnItemSelectedListener { item -> showTab(item.itemId); true }
        if (savedInstanceState == null) showTab(R.id.tab_accounts)
    }

    private fun showTab(id: Int) {
        val fm = supportFragmentManager
        val tag = "tab_$id"
        val tx = fm.beginTransaction()
        fm.fragments.forEach { if (it.isAdded) tx.hide(it) }
        var f = fm.findFragmentByTag(tag)
        if (f == null) {
            f = newFragment(id)
            frags[id] = f
            tx.add(R.id.fragment_container, f, tag)
        } else {
            tx.show(f)
        }
        tx.commitNowAllowingStateLoss()
    }

    private fun newFragment(id: Int): Fragment = when (id) {
        R.id.tab_models -> ModelsFragment()
        R.id.tab_usage -> UsageFragment()
        R.id.tab_test -> TestFragment()
        R.id.tab_settings -> SettingsFragment()
        else -> AccountsFragment()
    }

    fun cycleTheme() {
        themeIdx = (themeIdx + 1) % themes.size
        getSharedPreferences("relay", Context.MODE_PRIVATE).edit().putInt("theme", themeIdx).apply()
        recreate()
    }
}
