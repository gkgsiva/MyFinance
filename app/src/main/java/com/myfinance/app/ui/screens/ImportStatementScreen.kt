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
import com.myfinance.core.StatementKind
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
fun ImportStatementScreen(onBack: () -> Unit, onDone: () -> Unit, open: (String) -> Unit = {}, vm: StatementImportViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.addFiles(uris)
    }
    var editing by remember { mutableStateOf<StatementFile?>(null) }

    Scaffold(
        topBar = {
            com.myfinance.app.ui.theme.AppTopBar(
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
                                "Nothing is counted twice. A payment already added from SMS stays one entry and takes the " +
                                "statement's details, since the statement is the official record (your category, labels " +
                                "and notes are kept). Statements imported before are skipped. Nothing leaves your phone, " +
                                "and PDF passwords are not saved.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FilledTonalButton(onClick = { picker.launch(statementTypes) }, enabled = !state.importing, modifier = Modifier.fillMaxWidth()) {
                            Text(if (state.files.isEmpty()) "Choose statement files" else "Add more files")
                        }
                        com.myfinance.app.ui.WrapRow(Modifier, 0.dp) {
                            TextButton(onClick = { open("import-history") }) { Text("Import history & undo") }
                            TextButton(onClick = { open("missing-statements") }) { Text("Missing statements") }
                        }
                    }
                }
            }

            state.result?.let { msg ->
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(msg)
                            // Past years: what was added per financial year.
                            if (state.years.isNotEmpty()) {
                                Text("Added per year (April to March)", style = MaterialTheme.typography.titleSmall)
                                state.years.forEach { y ->
                                    Row(Modifier.fillMaxWidth()) {
                                        Text(y.label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                        Text("−${formatInr(y.spentMinor)}  +${formatInr(y.receivedMinor)}", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                Text(
                                    "Bank balances still come from each account's latest SMS, so old statements don't change today's balance.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            com.myfinance.app.ui.WrapRow(Modifier, 8.dp) {
                                Button(onClick = onDone) { Text("See transactions") }
                                TextButton(onClick = { open("year-review") }) { Text("Year in review") }
                                TextButton(onClick = { open("import-history") }) { Text("Undo") }
                            }
                        }
                    }
                }
            }

            items(state.files, key = { it.uri.toString() }) { f ->
                FileCard(
                    f,
                    myAccounts = state.myAccounts,
                    onPassword = { vm.submitPassword(f.uri, it) },
                    onRemove = { vm.remove(f.uri) },
                    onEditAccount = { editing = f },
                )
            }

            // Reading or importing: how far it has got, and a way to stop.
            if (state.busy) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (state.importing && state.importTotal > 0) {
                                androidx.compose.material3.LinearProgressIndicator(progress = { state.importDone / state.importTotal.toFloat() }, modifier = Modifier.fillMaxWidth())
                                Text("Importing ${state.importDone} of ${state.importTotal} lines…", style = MaterialTheme.typography.bodyMedium)
                            } else {
                                val reading = state.files.count { it.status == StatementFile.Status.READING }
                                val done = state.files.size - reading
                                androidx.compose.material3.LinearProgressIndicator(progress = { done / state.files.size.coerceAtLeast(1).toFloat() }, modifier = Modifier.fillMaxWidth())
                                Text("Reading files: $done of ${state.files.size} done", style = MaterialTheme.typography.bodyMedium)
                            }
                            androidx.compose.material3.OutlinedButton(onClick = vm::cancel) { Text("Stop") }
                        }
                    }
                }
            }

            // One preview over all the files before importing.
            if (state.ready.isNotEmpty() && !state.importing) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Before you import", style = MaterialTheme.typography.titleSmall)
                            val from = state.from
                            val to = state.to
                            if (from != null && to != null) Text("Covers ${from.format(stmtDayFormat)} to ${to.format(stmtDayFormat)}", style = MaterialTheme.typography.bodySmall)
                            Text("${state.lineCount} lines in ${state.ready.size} file${if (state.ready.size == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall)
                            Text("${state.newCount} new · ${state.alreadyCount} already here" + (if (state.updateCount > 0) " · ${state.updateCount} SMS will take the statement's details" else ""), style = MaterialTheme.typography.bodySmall)
                            Text("You can undo the whole import later from Import history.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            if (state.ready.isNotEmpty()) {
                item {
                    val n = state.newCount
                    val u = state.updateCount
                    Button(
                        onClick = vm::importAll,
                        enabled = (n > 0 || u > 0) && !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.importing) Text("Importing…")
                        else Text(
                            when {
                                n == 0 && u == 0 -> "Nothing new to import"
                                u == 0 -> "Import $n new transactions"
                                else -> "Import $n new, update $u from SMS"
                            },
                        )
                    }
                }
            }
        }
    }

    editing?.let { f ->
        var bank by remember(f.uri) { mutableStateOf(f.bank.orEmpty()) }
        var tail by remember(f.uri) { mutableStateOf(f.accountTail.orEmpty()) }
        val several = f.accounts.size > 1
        val renames = remember(f.uri) { androidx.compose.runtime.mutableStateMapOf<String, String>().apply { f.accounts.forEach { (p, _) -> put(p, f.renames[p] ?: p) } } }
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
                    if (several) {
                        Text("This statement lists ${f.accounts.size} ${if (f.kind == StatementKind.CARD) "cards" else "accounts"}. Correct any last 4 digits here:", style = MaterialTheme.typography.bodySmall)
                        f.accounts.forEach { (printed, n) ->
                            OutlinedTextField(
                                renames[printed].orEmpty(), { v -> renames[printed] = v.filter(Char::isDigit).take(4) },
                                label = { Text("••$printed · $n lines") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                        }
                    } else OutlinedTextField(
                        tail, { v -> tail = v.filter(Char::isDigit).take(4) }, label = { Text("Last 4 digits") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { vm.setAccount(f.uri, bank, tail, if (several) renames.toMap() else emptyMap()); editing = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FileCard(f: StatementFile, myAccounts: List<com.myfinance.core.MyAccount>, onPassword: (String) -> Unit, onRemove: () -> Unit, onEditAccount: () -> Unit) {
    var showLines by remember(f.uri) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(f.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, contentDescription = "Remove") }
            }
            Column(Modifier.padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when (f.status) {
                    StatementFile.Status.READING -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (f.pages > 0) {
                            androidx.compose.material3.LinearProgressIndicator(progress = { f.pagesRead / f.pages.toFloat() }, modifier = Modifier.fillMaxWidth())
                            Text(
                                if (f.pagesRead >= f.pages) "Read ${f.pages} pages, finding transactions…" else "Reading page ${f.pagesRead} of ${f.pages}…",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } else {
                            androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text("Opening…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    StatementFile.Status.FAILED -> Text(f.message.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    StatementFile.Status.NEEDS_PASSWORD -> PasswordBox(f.wrongPassword, onPassword)
                    StatementFile.Status.READY -> Ready(f, myAccounts, showLines, { showLines = !showLines }, onEditAccount)
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
private fun Ready(f: StatementFile, myAccounts: List<com.myfinance.core.MyAccount>, showLines: Boolean, toggleLines: () -> Unit, onEditAccount: () -> Unit) {
    val s = f.summary
    val several = f.accounts.size > 1
    val account = if (several) listOfNotNull(f.bank, "${f.accounts.size} ${if (f.kind == StatementKind.CARD) "cards" else "accounts"}").joinToString(" · ")
    else listOfNotNull(f.bank, f.accountTail?.let { "••$it" }).joinToString(" ").ifBlank { "Account not shown" }
    Text("${f.kind?.label ?: "Statement"} · $account", style = MaterialTheme.typography.bodyMedium)
    // A statement covering several cards (add-on, wife's card) or accounts (savings and current): each line goes to its own.
    if (several) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            f.accounts.forEach { (printed, n) ->
                val tail = f.renames[printed] ?: printed
                val reg = com.myfinance.core.MyAccounts.match(myAccounts, tail, f.bank)
                val who = reg?.let { r -> r.label + (r.owner?.takeIf { it.isNotBlank() }?.let { " ($it's)" } ?: "") } ?: "••$tail"
                Text(
                    "$who · $n line${if (n == 1) "" else "s"}" + if (tail != printed) " (printed ••$printed)" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (reg != null) com.myfinance.app.ui.theme.IncomeGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (!several) f.matched?.let { m ->
        Text(
            "✓ Your registered " + m.label + (m.owner?.let { " ($it's)" } ?: ""),
            style = MaterialTheme.typography.bodySmall, color = com.myfinance.app.ui.theme.IncomeGreen,
        )
    }
    val from = s.from
    val to = s.to
    if (from != null && to != null) {
        Text("${from.format(stmtDayFormat)} to ${to.format(stmtDayFormat)}", style = MaterialTheme.typography.bodySmall)
    }
    // Do the lines read add up to the statement's own totals?
    f.statement?.check?.let { c ->
        Text(
            if (c.ok) "✓ Every line read: the totals match the statement"
            else "Totals differ from the statement by ${formatInr(kotlin.math.abs(c.differenceMinor))}. A line may not have been read; check with Show lines.",
            style = MaterialTheme.typography.bodySmall,
            color = if (c.ok) com.myfinance.app.ui.theme.IncomeGreen else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (f.candidates.isEmpty()) {
        Text("Checking for duplicates…", style = MaterialTheme.typography.bodySmall)
        return
    }
    Text(
        "${f.candidates.size} transactions: ${s.newCount} new" +
            (if (s.updateCount > 0) ", ${s.updateCount} already from SMS (they will use the statement's details)" else "") +
            (if (s.duplicateCount > 0) ", ${s.duplicateCount} imported before" else ""),
        fontWeight = FontWeight.Medium,
    )
    if (s.newCount > 0) {
        Text(
            "New: spent ${formatInr(s.spentMinor)} · received ${formatInr(s.receivedMinor)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (s.notCountedCount > 0) Text(
            "Not counted: ${s.notCountedCount} card bill payments and transfers between your accounts (${formatInr(s.notCountedMinor)})",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    com.myfinance.app.ui.WrapRow(Modifier, 4.dp) {
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
                        color = if (c.duplicateOf != null && !c.updatesExisting) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    (if (c.row.type == TxnType.CREDIT) "+" else "−") + formatInr(c.row.amountMinor),
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        c.duplicateOf != null && !c.updatesExisting -> MaterialTheme.colorScheme.outline
                        c.row.type == TxnType.CREDIT -> IncomeGreen
                        else -> ExpenseRed
                    },
                )
            }
        }
        if (f.candidates.size > 300) Text("…and ${f.candidates.size - 300} more", style = MaterialTheme.typography.labelSmall)
    }
}
