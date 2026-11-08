package com.myfinance.app.security

import android.content.Context
import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.myfinance.core.PinHasher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Fingerprint / PIN lock. Only a salted hash of the PIN is stored. The app locks when opened and
 * again after it has been in the background for [RELOCK_AFTER_MS].
 */
object AppLock {
    private const val PREFS = "myfinance_lock"
    private const val RELOCK_AFTER_MS = 60_000L

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _biometric = MutableStateFlow(false)
    val biometric: StateFlow<Boolean> = _biometric.asStateFlow()

    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private var backgroundAt = 0L

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun init(context: Context) {
        val p = prefs(context)
        _enabled.value = p.getString("pin", null) != null
        _biometric.value = p.getBoolean("biometric", false)
        _locked.value = _enabled.value
    }

    fun setPin(context: Context, pin: String): Boolean {
        if (!PinHasher.isValidPin(pin)) return false
        prefs(context).edit().putString("pin", PinHasher.hash(pin)).putInt("failed", 0).apply()
        _enabled.value = true
        return true
    }

    fun disable(context: Context) {
        prefs(context).edit().clear().apply()
        _enabled.value = false
        _biometric.value = false
        _locked.value = false
    }

    fun setBiometric(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("biometric", on).apply()
        _biometric.value = on
    }

    fun canUseBiometric(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS

    sealed interface PinResult {
        data object Ok : PinResult
        data class Wrong(val waitMillis: Long) : PinResult
        data class Wait(val millis: Long) : PinResult
    }

    fun tryPin(context: Context, pin: String): PinResult {
        val p = prefs(context)
        val until = p.getLong("locked_until", 0)
        val now = System.currentTimeMillis()
        if (until > now) return PinResult.Wait(until - now)
        val stored = p.getString("pin", null) ?: return PinResult.Ok.also { _locked.value = false }
        if (PinHasher.verify(pin, stored)) {
            p.edit().putInt("failed", 0).putLong("locked_until", 0).apply()
            _locked.value = false
            return PinResult.Ok
        }
        val failed = p.getInt("failed", 0) + 1
        val wait = PinHasher.lockoutMillis(failed)
        p.edit().putInt("failed", failed).putLong("locked_until", if (wait > 0) now + wait else 0).apply()
        return PinResult.Wrong(wait)
    }

    fun unlockedByBiometric() { _locked.value = false }

    private var skipNextBackground = false

    fun onBackground() {
        if (skipNextBackground) { skipNextBackground = false; backgroundAt = 0L } else backgroundAt = SystemClock.elapsedRealtime()
    }

    fun onForeground() {
        if (_enabled.value && backgroundAt != 0L && SystemClock.elapsedRealtime() - backgroundAt > RELOCK_AFTER_MS) _locked.value = true
    }

    /** Opening a file picker or the camera sends the app to the background; don't relock for that. */
    fun expectExternalActivity() { skipNextBackground = true }

    fun promptBiometric(activity: FragmentActivity, onError: (String) -> Unit = {}) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = unlockedByBiometric()
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onError(errString.toString())
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock MyFinance")
                .setSubtitle("Use your fingerprint")
                .setNegativeButtonText("Use PIN")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                .build(),
        )
    }
}
