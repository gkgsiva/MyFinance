package com.myfinance.app.ui.v15

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.myfinance.app.security.AppLock
import com.myfinance.app.ui.FinanceViewModel
import com.myfinance.app.ui.theme.ExpenseRed
import com.myfinance.app.ui.theme.IncomeGreen
import com.myfinance.app.ui.v12.Dropdown
import com.myfinance.app.ui.v12.Hint12
import com.myfinance.app.ui.v12.InfoCard
import com.myfinance.core.AutoBackupSchedule
import com.myfinance.core.BackupFrequency
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val whenFormat = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a")

/** Scheduled encrypted backup to a file on the phone or in Google Drive. */
@Composable
fun AutoBackupCard(vm: FinanceViewModel) {
    val s = vm.v15
    val st by s.autoBackup.collectAsState()
    var asking by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var pendingFreq by remember { mutableStateOf(BackupFrequency.WEEKLY) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null && password.isNotEmpty()) s.enableAutoBackup(uri, password, pendingFreq)
        password = ""
    }

    InfoCard {
        Text("Auto-backup", style = MaterialTheme.typography.titleSmall)
        Hint12(
            "Makes the same encrypted backup on a schedule and saves it over one file you choose. Pick Google Drive " +
                "in the file picker to keep it off the phone. The password is kept locked in this phone's keystore so " +
                "the backup can run by itself; you still need it to restore.",
        )
        if (st.enabled) {
            val last = st.lastSuccess
            Text(
                last?.let { "Last backup: " + Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(whenFormat) } ?: "No backup made yet",
                color = if (last != null && !AutoBackupSchedule.isStale(last, st.frequency, System.currentTimeMillis())) IncomeGreen else ExpenseRed,
                style = MaterialTheme.typography.bodySmall,
            )
            st.lastError?.let { Text("Last try failed: $it", color = ExpenseRed, style = MaterialTheme.typography.bodySmall) }
            Dropdown("How often", st.frequency, BackupFrequency.entries, { it.label }, { s.setAutoBackupFrequency(it) })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { s.backupNow() }) { Text("Back up now") }
                OutlinedButton(onClick = { asking = true }) { Text("Change file") }
                TextButton(onClick = { s.disableAutoBackup() }) { Text("Turn off") }
            }
        } else {
            Button(onClick = { asking = true }) { Text("Turn on auto-backup") }
        }
    }

    if (asking) {
        var pw by remember { mutableStateOf("") }
        var pw2 by remember { mutableStateOf("") }
        var freq by remember { mutableStateOf(st.frequency) }
        val ok = pw.length >= 6 && pw == pw2
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("Auto-backup") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        pw, { pw = it }, label = { Text("Backup password (6+ characters)") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        pw2, { pw2 = it }, label = { Text("Password again") }, singleLine = true, isError = pw2.isNotEmpty() && pw != pw2,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Dropdown("How often", freq, BackupFrequency.entries, { it.label }, { freq = it })
                    Hint12("Next, choose where to save the file. Write the password down somewhere safe: without it the backup can't be opened.")
                }
            },
            confirmButton = {
                TextButton(enabled = ok, onClick = {
                    password = pw
                    pendingFreq = freq
                    asking = false
                    AppLock.expectExternalActivity()
                    pick.launch("MyFinance auto-backup.mfbackup")
                }) { Text("Choose file") }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text("Cancel") } },
        )
    }
}
