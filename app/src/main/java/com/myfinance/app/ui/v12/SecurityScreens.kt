package com.myfinance.app.ui.v12

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.myfinance.app.security.AppLock
import com.myfinance.app.ui.V12ViewModel
import com.myfinance.core.BackupCrypto
import com.myfinance.core.PinHasher
import java.time.LocalDate
import kotlin.system.exitProcess

@Composable
fun SecurityScreen(vm: V12ViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val enabled by vm.lockEnabled.collectAsState()
    val bio by vm.biometricEnabled.collectAsState()
    var settingPin by remember { mutableStateOf(false) }
    val canBio = remember { AppLock.canUseBiometric(context) }

    V12Screen("App lock", vm, onBack) {
        item {
            InfoCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Lock the app", style = MaterialTheme.typography.titleSmall)
                        Hint12("Asks for your PIN or fingerprint when you open MyFinance, and after a minute away. The app is also hidden in the recent apps screen.")
                    }
                    Switch(checked = enabled, onCheckedChange = { on -> if (on) settingPin = true else vm.disableLock() })
                }
                if (enabled) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Use fingerprint")
                            if (!canBio) Hint12("No fingerprint is set up on this phone.")
                        }
                        Switch(checked = bio && canBio, enabled = canBio, onCheckedChange = vm::setBiometric)
                    }
                    TextButton(onClick = { settingPin = true }) { Text("Change PIN") }
                }
                Hint12("If you forget the PIN, the only way back in is to clear the app's data (Settings > Apps > MyFinance > Storage) and restore from a backup.")
            }
        }
    }

    if (settingPin) {
        var pin by remember { mutableStateOf("") }
        var again by remember { mutableStateOf("") }
        val ok = PinHasher.isValidPin(pin) && pin == again
        AlertDialog(
            onDismissRequest = { settingPin = false },
            title = { Text("Choose a PIN") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PinField("PIN (4 to 8 digits)", pin) { pin = it }
                    PinField("Enter it again", again) { again = it }
                    if (again.isNotEmpty() && pin != again) Hint12("The PINs don't match.")
                }
            },
            confirmButton = {
                TextButton(enabled = ok, onClick = {
                    vm.enableLock(pin)
                    if (canBio) vm.setBiometric(true)
                    settingPin = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { settingPin = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun PinField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() }.take(8)) },
        label = { Text(label) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun BackupScreen(vm: V12ViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val busy by vm.busy.collectAsState()
    val ready by vm.restoreReady.collectAsState()
    var backupPassword by remember { mutableStateOf<String?>(null) }
    var askBackupPassword by remember { mutableStateOf(false) }
    var restoreUri by remember { mutableStateOf<android.net.Uri?>(null) }

    val createLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val pw = backupPassword
        if (uri != null && pw != null) vm.backup(uri, pw)
        backupPassword = null
    }
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> restoreUri = uri }

    V12Screen("Backup & restore", vm, onBack) {
        item {
            InfoCard {
                Text("Encrypted backup", style = MaterialTheme.typography.titleSmall)
                Hint12(
                    "Saves all your transactions, categories, rules, goals, bills, splits, tax tags and receipt photos " +
                        "into one file locked with a password you choose (AES-256). Save it to Google Drive, your Downloads " +
                        "folder or a pen drive. Without the password nobody, not even you, can open it.",
                )
                vm.lastBackup?.let { Hint12("Last backup: " + java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate().format(shortDate)) }
                Button(onClick = { askBackupPassword = true }, enabled = !busy) { Text("Back up now") }
            }
        }
        item {
            InfoCard {
                Text("Restore", style = MaterialTheme.typography.titleSmall)
                Hint12("On a new phone: install MyFinance, then restore your backup file here. This replaces the data on this phone with the backup.")
                if (ready) {
                    Text("Ready. Restart the app to finish restoring.")
                    Button(onClick = {
                        (context as? Activity)?.finishAffinity()
                        exitProcess(0)
                    }) { Text("Close the app now") }
                } else {
                    FilledTonalButton(onClick = {
                        AppLock.expectExternalActivity()
                        openLauncher.launch(arrayOf("*/*"))
                    }, enabled = !busy) { Text("Choose backup file") }
                }
            }
        }
    }

    if (askBackupPassword) {
        var pw by remember { mutableStateOf("") }
        var again by remember { mutableStateOf("") }
        val ok = pw.length >= BackupCrypto.MIN_PASSWORD && pw == again
        AlertDialog(
            onDismissRequest = { askBackupPassword = false },
            title = { Text("Backup password") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PasswordField("Password (at least ${BackupCrypto.MIN_PASSWORD} characters)", pw) { pw = it }
                    PasswordField("Enter it again", again) { again = it }
                    Hint12("Write it down somewhere safe. It can't be recovered.")
                }
            },
            confirmButton = {
                TextButton(enabled = ok, onClick = {
                    backupPassword = pw
                    askBackupPassword = false
                    AppLock.expectExternalActivity()
                    createLauncher.launch("myfinance-backup-${LocalDate.now()}.mfbackup")
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { askBackupPassword = false }) { Text("Cancel") } },
        )
    }

    restoreUri?.let { uri ->
        var pw by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { restoreUri = null },
            title = { Text("Restore backup?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Everything on this phone will be replaced by the backup.")
                    PasswordField("Backup password", pw) { pw = it }
                }
            },
            confirmButton = { TextButton(enabled = pw.isNotEmpty(), onClick = { vm.restore(uri, pw); restoreUri = null }) { Text("Restore") } },
            dismissButton = { TextButton(onClick = { restoreUri = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PasswordField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
}
