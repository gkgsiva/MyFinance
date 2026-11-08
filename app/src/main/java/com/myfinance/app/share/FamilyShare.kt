package com.myfinance.app.share

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Sending the family export through WhatsApp, and picking it up again on the main phone. */
object FamilyShare {
    private val whatsApps = listOf("com.whatsapp", "com.whatsapp.w4b")

    /** Opens WhatsApp (or WhatsApp Business) with the file attached; falls back to the share sheet. */
    fun sendViaWhatsApp(context: Context, file: Uri) {
        fun intent(pkg: String?) = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, file)
            putExtra(Intent.EXTRA_TEXT, "MyFinance family export. Open this file with MyFinance on the main phone to merge it.")
            clipData = ClipData.newRawUri("MyFinance export", file)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            pkg?.let(::setPackage)
        }
        for (pkg in whatsApps) {
            try {
                context.startActivity(intent(pkg))
                return
            } catch (_: ActivityNotFoundException) {
                // Not installed; try the next one.
            }
        }
        val chooser = Intent.createChooser(intent(null), "Share MyFinance export").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(chooser)
    }

    private val _incoming = MutableStateFlow<Uri?>(null)

    /** A file opened from WhatsApp that is waiting for the user to confirm the merge. */
    val incoming: StateFlow<Uri?> = _incoming.asStateFlow()

    /** Picks the file out of an "Open with" (VIEW) or "Share to" (SEND) intent. */
    fun accept(intent: Intent?) {
        intent ?: return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND ->
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
        if (uri != null) _incoming.value = uri
    }

    fun clear() { _incoming.value = null }
}
