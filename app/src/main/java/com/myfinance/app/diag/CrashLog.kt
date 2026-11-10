package com.myfinance.app.diag

import android.content.Context
import android.content.Intent
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime

/**
 * Keeps the details of the last crash (and of errors the app recovered from) in a small file, so they can
 * be shared from the app instead of being lost. Nothing leaves the phone unless the user shares it.
 */
object CrashLog {
    private const val FILE = "last_crash.txt"
    private const val SOFT = "recent_errors.txt"
    private lateinit var dir: File
    private var version: String = "?"

    fun init(context: Context) {
        dir = context.filesDir
        version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { File(dir, FILE).writeText(describe("Crash on ${thread.name}", e)) }
            previous?.uncaughtException(thread, e)
        }
    }

    /** An error the app caught and carried on from: kept (last few) to share with a crash report. */
    fun recovered(where: String, e: Throwable) {
        if (!::dir.isInitialized) return
        runCatching {
            val f = File(dir, SOFT)
            val old = if (f.exists()) f.readText().takeLast(6000) else ""
            f.writeText(old + describe("Recovered in $where", e) + "\n")
        }
    }

    private fun describe(title: String, e: Throwable): String {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        return "MyFinance $version · ${LocalDateTime.now()}\n$title\n${sw.toString().take(8000)}"
    }

    /** The last crash's details, or null when the app hasn't crashed since they were cleared. */
    fun last(context: Context): String? = File(context.filesDir, FILE).takeIf { it.exists() }?.readText()?.ifBlank { null }

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE).delete() }
    }

    /** Opens the share sheet (WhatsApp, email…) with the crash details and recent recovered errors. */
    fun share(context: Context) {
        val soft = File(context.filesDir, SOFT).takeIf { it.exists() }?.readText().orEmpty()
        val text = (last(context) ?: "No crash saved.") + if (soft.isNotBlank()) "\n\n---- Recent errors ----\n" + soft.takeLast(6000) else ""
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "MyFinance crash details")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(send, "Share crash details").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
