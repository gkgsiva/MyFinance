package com.myfinance.app

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow

/** Screens opened straight from a home screen shortcut or the Today widget. */
object Shortcuts {
    const val ACTION = "com.myfinance.app.OPEN"
    const val EXTRA_ROUTE = "open_route"

    /** Only these screens can be opened from outside the app. */
    private val allowed = setOf("edit/0", "edit-income", "ask", "calendar", "lending", "vault", "subscriptions", "tax-pack")

    val pending = MutableStateFlow<String?>(null)

    fun accept(intent: Intent?) {
        if (intent?.action != ACTION) return
        val route = intent.getStringExtra(EXTRA_ROUTE) ?: return
        if (route in allowed) pending.value = route
    }
}
