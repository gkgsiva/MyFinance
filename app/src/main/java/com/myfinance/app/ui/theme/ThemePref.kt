package com.myfinance.app.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode(val label: String) { SYSTEM("Same as phone"), LIGHT("Light"), DARK("Dark") }

/** The app's light / dark choice, kept in the app's preferences. */
object ThemePref {
    private const val KEY = "theme_mode"
    private val _mode = MutableStateFlow(ThemeMode.SYSTEM)
    val mode: StateFlow<ThemeMode> = _mode.asStateFlow()
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        val saved = context.getSharedPreferences("myfinance", Context.MODE_PRIVATE).getString(KEY, null)
        _mode.value = ThemeMode.entries.firstOrNull { it.name == saved } ?: ThemeMode.SYSTEM
    }

    fun set(mode: ThemeMode) {
        _mode.value = mode
        appContext?.getSharedPreferences("myfinance", Context.MODE_PRIVATE)?.edit()?.putString(KEY, mode.name)?.apply()
    }
}
