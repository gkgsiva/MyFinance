package com.myfinance.app.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.myfinance.app.MyFinanceApp
import com.myfinance.app.notify.Notifier
import com.myfinance.core.AutoBackupSchedule
import com.myfinance.core.BackupFrequency
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class AutoBackupState(
    val enabled: Boolean = false,
    val uri: String? = null,
    val frequency: BackupFrequency = BackupFrequency.WEEKLY,
    val lastSuccess: Long? = null,
    val lastError: String? = null,
    val lastTried: Long? = null,
)

/**
 * Scheduled encrypted backups. The user picks a file once (on the phone or in Google Drive through the
 * system file picker) and a password; the app overwrites that file with a fresh encrypted backup on
 * schedule. The password is kept encrypted with a key in the Android keystore, which never leaves the phone.
 */
object AutoBackup {
    private const val PREFS = "myfinance"
    private const val K_ON = "v15_ab_on"
    private const val K_URI = "v15_ab_uri"
    private const val K_FREQ = "v15_ab_freq"
    private const val K_PASS = "v15_ab_pass"
    private const val K_LAST = "v15_ab_last"
    private const val K_ERR = "v15_ab_err"
    private const val K_TRIED = "v15_ab_tried"
    private const val KEY_ALIAS = "myfinance_auto_backup"
    private const val WORK = "auto-backup"

    private val _state = MutableStateFlow(AutoBackupState())
    val state: StateFlow<AutoBackupState> = _state.asStateFlow()

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun init(context: Context) { _state.value = read(context) }

    private fun read(context: Context): AutoBackupState {
        val p = prefs(context)
        return AutoBackupState(
            enabled = p.getBoolean(K_ON, false),
            uri = p.getString(K_URI, null),
            frequency = BackupFrequency.entries.firstOrNull { it.name == p.getString(K_FREQ, null) } ?: BackupFrequency.WEEKLY,
            lastSuccess = p.getLong(K_LAST, 0).takeIf { it > 0 },
            lastError = p.getString(K_ERR, null),
            lastTried = p.getLong(K_TRIED, 0).takeIf { it > 0 },
        )
    }

    /** Turns auto-backup on for [uri] (from ACTION_CREATE_DOCUMENT) with [password]. */
    fun enable(context: Context, uri: Uri, password: CharArray, frequency: BackupFrequency) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        prefs(context).edit()
            .putBoolean(K_ON, true)
            .putString(K_URI, uri.toString())
            .putString(K_FREQ, frequency.name)
            .putString(K_PASS, seal(String(password)))
            .remove(K_ERR)
            .apply()
        _state.value = read(context)
        schedule(context)
    }

    fun setFrequency(context: Context, frequency: BackupFrequency) {
        prefs(context).edit().putString(K_FREQ, frequency.name).apply()
        _state.value = read(context)
    }

    fun disable(context: Context) {
        val uri = prefs(context).getString(K_URI, null)
        prefs(context).edit().putBoolean(K_ON, false).remove(K_PASS).remove(K_URI).remove(K_ERR).apply()
        uri?.let { u ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(u), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
        _state.value = read(context)
    }

    /** Daily check; the backup itself runs only when [AutoBackupSchedule.isDue]. */
    fun schedule(context: Context) {
        if (!prefs(context).getBoolean(K_ON, false)) return
        val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Runs a backup now when due (or always with [force]). Returns a message for the screen. */
    suspend fun runIfDue(context: Context, force: Boolean = false): String? {
        val s = read(context)
        if (!s.enabled || s.uri == null) return null
        if (!force && !AutoBackupSchedule.isDue(s.lastSuccess, s.frequency, System.currentTimeMillis())) return null
        val p = prefs(context)
        val now = System.currentTimeMillis()
        return try {
            val pass = p.getString(K_PASS, null)?.let(::open) ?: error("The saved password is missing. Turn auto-backup on again.")
            val repo = (context.applicationContext as MyFinanceApp).repository
            val n = repo.v12.backupTo(Uri.parse(s.uri), pass.toCharArray())
            p.edit().putLong(K_LAST, now).putLong(K_TRIED, now).remove(K_ERR).apply()
            _state.value = read(context)
            "Backup saved ($n files)."
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            p.edit().putLong(K_TRIED, now).putString(K_ERR, msg).apply()
            _state.value = read(context)
            if (AutoBackupSchedule.isStale(s.lastSuccess, s.frequency, now) || s.lastSuccess == null) {
                Notifier.reminder(context, "auto-backup".hashCode(), "Auto-backup failed", "Open MyFinance > Backup to fix it: $msg")
            }
            "Backup failed: $msg"
        }
    }

    // ---- Password kept with an Android keystore key ----

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun seal(text: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(text.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun open(sealed: String): String? = runCatching {
        val bytes = Base64.decode(sealed, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
        String(c.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
    }.getOrNull()
}

class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        AutoBackup.runIfDue(applicationContext)
        return Result.success()
    }
}
