package com.myfinance.app.ui.v19

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.myfinance.app.ui.FinanceViewModel
import com.myfinance.app.ui.WrapRow
import com.myfinance.app.ui.theme.ExpenseRed
import com.myfinance.app.ui.theme.IncomeGreen
import com.myfinance.app.ui.v13.Muted
import com.myfinance.app.ui.v13.SimpleScreen
import com.myfinance.core.TxnType
import com.myfinance.core.UncategorisedGroup
import com.myfinance.core.formatInr
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val whenFmt = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a")
private val dayFmt = DateTimeFormatter.ofPattern("d MMM yyyy")
private val monFmt = DateTimeFormatter.ofPattern("MMM")

/** Shows the 1.10 messages ("Removed 120 entries…") as a toast. */
@Composable
private fun Messages(vm: FinanceViewModel) {
    val msg by vm.v19.message.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(msg) { msg?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show(); vm.v19.clearMessage() } }
}

// ---- Import history and undo ----

@Composable
fun ImportHistoryScreen(vm: FinanceViewModel, onBack: () -> Unit, open: (String) -> Unit) {
    Messages(vm)
    val batches by vm.v19.importBatches.collectAsState()
    var confirm by remember { mutableStateOf<com.myfinance.app.data.ImportBatchEntity?>(null) }
    SimpleScreen("Import history", onBack) {
        item { Muted("Every statement import, newest first. Undo takes out all the entries that import added, so you can import the files again cleanly. Entries from SMS that took a statement's details stay as they are.") }
        if (batches.isEmpty()) item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("No imports yet", fontWeight = FontWeight.SemiBold)
                    Muted("Statements you import from now on are listed here.")
                    OutlinedButton(onClick = { open("import-statement") }) { Text("Import statements") }
                }
            }
        }
        batches.forEach { b ->
            item(key = b.id) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(Instant.ofEpochMilli(b.at).atZone(ZoneId.systemDefault()).format(whenFmt), fontWeight = FontWeight.SemiBold)
                        Muted(b.files.split('\n').filter { it.isNotBlank() }.joinToString(", "))
                        if (b.fromDay > 0 && b.toDay > 0) Muted("Covers ${LocalDate.ofEpochDay(b.fromDay).format(dayFmt)} to ${LocalDate.ofEpochDay(b.toDay).format(dayFmt)}")
                        Text("${b.added} added" + if (b.updated > 0) " · ${b.updated} SMS updated" else "", style = MaterialTheme.typography.bodyMedium)
                        if (b.undone) Text("Undone", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        else if (b.added > 0) TextButton(onClick = { confirm = b }) { Text("Undo this import", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
    confirm?.let { b ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Undo this import?") },
            text = { Text("The ${b.added} entries it added are taken out of MyFinance. Your other entries are not touched.") },
            confirmButton = { TextButton(onClick = { vm.v19.undoImport(b.id); confirm = null }) { Text("Undo import", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

// ---- Missing statements ----

@Composable
fun MissingStatementsScreen(vm: FinanceViewModel, onBack: () -> Unit, open: (String) -> Unit) {
    val coverage by vm.v19.coverage.collectAsState()
    SimpleScreen("Missing statements", onBack) {
        item { Muted("For each card and account you have imported statements for: the months between its first and latest statement with no lines at all. A month with no spending on a card can be fine; a gap of several months usually means a statement was missed.") }
        if (coverage.isEmpty()) item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("No statements imported yet", fontWeight = FontWeight.SemiBold)
                    Muted("Import your bank and card statements, and this shows which months are still missing.")
                    OutlinedButton(onClick = { open("import-statement") }) { Text("Import statements") }
                }
            }
        }
        coverage.forEach { c ->
            item(key = c.account) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(c.account, fontWeight = FontWeight.SemiBold)
                        Muted("${c.first.format(DateTimeFormatter.ofPattern("MMM yyyy"))} to ${c.last.format(DateTimeFormatter.ofPattern("MMM yyyy"))} · ${c.monthsCovered} months with lines")
                        if (c.missing.isEmpty()) Text("✓ No months missing", color = IncomeGreen, style = MaterialTheme.typography.bodyMedium)
                        else {
                            Text("${c.missing.size} month${if (c.missing.size == 1) "" else "s"} missing", color = ExpenseRed, style = MaterialTheme.typography.bodyMedium)
                            c.missingByYear.forEach { (year, months) ->
                                Text("$year: " + months.joinToString(", ") { it.format(monFmt) }, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
        if (coverage.any { it.missing.isNotEmpty() }) item {
            OutlinedButton(onClick = { open("import-statement") }) { Text("Import the missing ones") }
        }
    }
}

// ---- Uncategorised cleanup ----

@Composable
fun UncategorisedScreen(vm: FinanceViewModel, onBack: () -> Unit, openTxn: (Long) -> Unit) {
    Messages(vm)
    val groups by vm.v19.uncategorised.collectAsState()
    val categories by vm.categories.collectAsState()
    var picking by remember { mutableStateOf<UncategorisedGroup?>(null) }
    SimpleScreen("Uncategorised", onBack) {
        item { Muted("Entries with no category, grouped by payee. Pick a category once for a payee: all its entries get it, and new SMS and any statements you import later (old years too) get it by themselves.") }
        item {
            WrapRow(Modifier, 8.dp) {
                OutlinedButton(onClick = { vm.v19.applyRulesToAll() }) { Text("Apply my rules to all entries") }
            }
        }
        if (groups.isEmpty()) item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("✓ Everything has a category", fontWeight = FontWeight.SemiBold, color = IncomeGreen)
                    Muted("Nothing to sort out here.")
                }
            }
        }
        item { if (groups.isNotEmpty()) Text("${groups.sumOf { it.count }} entries from ${groups.size} payees", style = MaterialTheme.typography.titleSmall) }
        groups.take(300).forEach { g ->
            item(key = g.key + g.type) {
                Card(Modifier.fillMaxWidth().clickable { picking = g }) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(g.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Muted("${g.count} entr${if (g.count == 1) "y" else "ies"} · ${if (g.type == TxnType.CREDIT) "money in" else "spent"} · tap to pick a category")
                        }
                        Text(formatInr(g.totalMinor), color = if (g.type == TxnType.CREDIT) IncomeGreen else ExpenseRed, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
    picking?.let { g ->
        AlertDialog(
            onDismissRequest = { picking = null },
            title = { Text("Category for ${g.name}") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    item { Muted("${g.count} entries, ${formatInr(g.totalMinor)}") }
                    item {
                        WrapRow(Modifier.padding(top = 8.dp), 6.dp) {
                            categories.filter { it.name != com.myfinance.core.DefaultCategories.OTHER }.forEach { c ->
                                FilterChip(selected = false, onClick = { vm.v19.categorise(g, c.name); picking = null }, label = { Text(c.name) })
                            }
                        }
                    }
                    if (g.count == 1) item { TextButton(onClick = { picking = null; openTxn(g.ids.first()) }) { Text("Open the entry instead") } }
                }
            },
            confirmButton = { TextButton(onClick = { picking = null }) { Text("Cancel") } },
        )
    }
}
