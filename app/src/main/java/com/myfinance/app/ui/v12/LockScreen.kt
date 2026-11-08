package com.myfinance.app.ui.v12

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.myfinance.app.security.AppLock

/** Shown over everything while the app is locked. */
@Composable
fun LockScreen(useBiometric: Boolean) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    var pin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val bio = useBiometric && AppLock.canUseBiometric(context) && activity != null

    LaunchedEffect(Unit) { if (bio) AppLock.promptBiometric(activity!!) }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text("MyFinance is locked", style = MaterialTheme.typography.titleLarge)
            PinField("PIN", pin) { pin = it; message = null }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = {
                message = when (val r = AppLock.tryPin(context, pin)) {
                    AppLock.PinResult.Ok -> null
                    is AppLock.PinResult.Wrong -> if (r.waitMillis > 0) "Wrong PIN. Try again in ${r.waitMillis / 1000} seconds." else "Wrong PIN."
                    is AppLock.PinResult.Wait -> "Too many tries. Wait ${r.millis / 1000 + 1} seconds."
                }
                pin = ""
            }, enabled = pin.length >= 4) { Text("Unlock") }
            if (bio) OutlinedButton(onClick = { AppLock.promptBiometric(activity!!) }) { Text("Use fingerprint") }
        }
    }
}
