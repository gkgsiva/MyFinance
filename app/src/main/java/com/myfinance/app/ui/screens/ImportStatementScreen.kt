package com.myfinance.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.myfinance.app.ui.StatementFile
import com.myfinance.app.ui.StatementImportViewModel
import com.myfinance.app.ui.theme.ExpenseRed
import com.myfinance.app.ui.theme.IncomeGreen
import com.myfinance.core.TxnType
import com.myfinance.core.formatInr
import java.time.format.DateTimeFormatter

private val statementTypes = arrayOf(
    "application/pdf",
    "text/csv", "text/comma-separated-values", "text/plain", "text/tab-separated-values", "text/html",
    "application/vnd.ms-excel",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/octet-stream",
)

private val stmtDayFormat = DateTimeFormatter.ofPattern("d MMM yyyy")

/** Pick bank / credit card statements (several years at once), unlock, preview and import. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportStatementScreen(onBack: () -> Unit, onDone: () -> Unit, vm: StatementImportViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.addFiles(uris)
    }
    var editing by remember { mutableStateOf<StatementFile?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import statements") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { inner ->
        LazyColumn(
            Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Bank and credit card statements", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Add past years from the statements your bank emails or lets you download from net banking: " +
                                "PDF (password-protected is fine), Excel or CSV. Pick as many files as you like at once, " +
                                "for example every monthly card bill for the last 3 years.\n\n" +
                                "Transactions already in MyFinance from SMS, or from a statement you imported before, " +
                                "are skipped. Nothing leaves your phone, and PDF passwords are not saved.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FilledTonalButton(onClick = { picker.launch(statementTypes) }, enabled = !state.importing, modifier = Modifier.fillMaxWidth()) {
                            Text(if (state.files.isEmpty()) "Choose statement files" else "Add more files")
                        }
                    }
                }
            }

            state.result?.let { msg ->
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(msg)
                            Button(onClick = onDone) { Text("See transactions") }
                        }
                    }
                }
            }

            items(state.files, key = { it.uri.toString() }) { f ->
                FileCard(
                    f,
                    onPassword = { vm.submitPassword(f.uri, it) },
                    onRemove = { vm.remove(f.uri) },
                    onEditAccount = { editing = f },
                )
            }

            if (state.ready.isNotEmpty()) {
                item {
                    val n = state.newCount
                    Button(
                        onClick = vm::importAll,
                        enabled = n > 0 && !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.importing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text(if (n == 0) "Nothing new to import" else "Import $n new transactions")
                    }
                }
            }
        }
    }

    editing?.let { f ->
        var bank by remember(f.uri) { mutableStateOf(f.bank.orEmpty()) }
        var tail by remember(f.uri) { mutableStateOf(f.accountTail.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Which account is this?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Matching SMS are found by the account or card's last 4 digits, so set them when the " +
                            "statement didn't show them.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(bank, { bank = it }, label = { Text("Bank") }, singleLine = true)
                    OutlinedTextField(
                        tail, { v -> tail = v.filter(Char::isDigit).take(4) }, label = { Text("Last 4 digits") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { vm.setAccount(f.uri, bank, tail); editing = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FileCard(f: StatementFile, onPassword: (String) -> Unit, onRemove: () -> Unit, onEditAccount: () -> Unit) {
    var showLines by remember(f.uri) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(f.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, contentDescription = "Remove") }
            }
            Column(Modifier.padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when (f.status) {
                    StatementFile.Status.READING -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("Reading…", style = MaterialTheme.typography.bodySmall)
                    }
                    StatementFile.Status.FAILED -> Text(f.message.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    StatementFile.Status.NEEDS_PASSWORD -> PasswordBox(f.wrongPassword, onPassword)
                    StatementFile.Status.READY -> Ready(f, showLines, { showLines = !showLines }, onEditAccount)
                }
            }
        }
    }
}

@Composable
private fun PasswordBox(wrong: Boolean, onSubmit: (String) -> Unit) {
    var pw by remember { mutableStateOf("") }
    Text(
        "This PDF is password-protected. The password is in the email the statement came with; banks often use " +
            "part of your name and date of birth (e.g. SIVA0101 or 01011990) or customer ID.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (wrong) Text("That password didn't open it. Try again.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            pw, { pw = it }, label = { Text("PDF password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { onSubmit(pw) }, enabled = pw.isNotBlank()) { Text("Unlock") }
    }
}

@Composable
private fun Ready(f: StatementFile, showLines: Boolean, toggleLines: () -> Unit, onEditAccount: () -> Unit) {
    val s = f.summary
    val account = listOfNotNull(f.bank, f.accountTail?.let { "••$it" }).joinToString(" ").ifBlank { "Account not shown" }
    Text("${f.kind?.label ?: "Statement"} · $account", style = MaterialTheme.typography.bodyMedium)
    val from = s.from
    val to = s.to
    if (from != null && to != null) {
        Text("${from.format(stmtDayFormat)} to ${to.format(stmtDayFormat)}", style = MaterialTheme.typography.bodySmall)
    }
    if (f.candidates.isEmpty()) {
        Text("Checking for duplicates…", style = MaterialTheme.typography.bodySmall)
        return
    }
    Text(
        "${f.candidates.size} transactions: ${s.newCount} new, ${s.duplicateCount} already in MyFinance",
        fontWeight = FontWeight.Medium,
    )
    if (s.newCount > 0) {
        Text(
            "New: spent ${formatInr(s.spentMinor)} · received ${formatInr(s.receivedMinor)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = toggleLines) { Text(if (showLines) "Hide lines" else "Show lines") }
        TextButton(onClick = onEditAccount) { Text("Change account") }
    }
    if (showLines) {
        f.candidates.take(300).forEach { c ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(c.merchant ?: c.row.description, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    Text(
                        listOfNotNull(c.row.date.format(stmtDayFormat), c.duplicateOf?.label).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (c.duplicateOf != null) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    (if (c.row.type == TxnType.CREDIT) "+" else "−") + formatInr(c.row.amountMinor),
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        c.duplicateOf != null -> MaterialTheme.colorScheme.outline
                        c.row.type == TxnType.CREDIT -> IncomeGreen
                        else -> ExpenseRed
                    },
                )
            }
        }
        if (f.candidates.size > 300) Text("…and ${f.candidates.size - 300} more", style = MaterialTheme.typography.labelSmall)
    }
}
