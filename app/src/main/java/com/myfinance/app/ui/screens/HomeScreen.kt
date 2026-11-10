package com.myfinance.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.myfinance.app.ui.FinanceViewModel
import com.myfinance.app.ui.theme.ExpenseRed
import com.myfinance.app.ui.theme.IncomeGreen
import com.myfinance.core.Balances
import com.myfinance.core.formatInr
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val homeDate = DateTimeFormatter.ofPattern("EEEE, d MMMM")
private val monthName = DateTimeFormatter.ofPattern("MMMM")

/**
 * Home: kept simple. Today's transactions and what is due today and over the next few days (with anything
 * missed), one line for the month so far, and links to the Dashboard and other screens.
 */
@Composable
fun HomeScreen(
    vm: FinanceViewModel,
    smsGranted: Boolean,
    onImport: () -> Unit,
    open: (String) -> Unit,
) {
    val importState by vm.importState.collectAsState()
    val month by vm.month.collectAsState()
    val summary by vm.summary.collectAsState()
    val balances by vm.balances.collectAsState()
    val loaded by vm.loaded.collectAsState()
    val allTxns by vm.allTransactions.collectAsState()
    val backupDays = remember(allTxns.size) { vm.v19.backupDaysAgo() }
    val salaryNote by vm.v19.salaryNote.collectAsState()
    val cycle by vm.monthCycle.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var crash by remember { androidx.compose.runtime.mutableStateOf(com.myfinance.app.diag.CrashLog.last(ctx)) }
    var addDue by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    if (addDue) com.myfinance.app.ui.v15.QuickAddDialog(vm, com.myfinance.app.ui.v15.QuickAdd.PAYMENT_DUE) { addDue = false }

    Scaffold(floatingActionButton = { com.myfinance.app.ui.v15.QuickAddFab(vm, open) }) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Today", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(LocalDate.now().format(homeDate), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onImport, enabled = !importState.running) {
                        if (importState.running) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Filled.Refresh, contentDescription = "Read new SMS")
                    }
                }
            }

            if (!smsGranted) item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Read your bank SMS", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "MyFinance reads transaction messages from your banks to build your spending profile. Everything stays on this phone.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        FilledTonalButton(onClick = onImport) { Text("Allow and import") }
                    }
                }
            }
            importState.lastAdded?.let { added ->
                item {
                    Text(
                        if (added == 0) "No new transactions found in SMS." else "Imported $added new transactions from SMS.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            importState.error?.let { err -> item { Text(err, color = MaterialTheme.colorScheme.error) } }

            if (!loaded) item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Loading your entries…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // A new phone with nothing yet: where to start.
            if (loaded && allTxns.isEmpty()) item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Nothing here yet", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Read your bank SMS, import past bank and card statements, or add an entry with +. Everything stays on this phone.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        com.myfinance.app.ui.WrapRow(Modifier, 8.dp) {
                            FilledTonalButton(onClick = onImport) { Text("Read SMS") }
                            androidx.compose.material3.OutlinedButton(onClick = { open("import-statement") }) { Text("Import statements") }
                        }
                    }
                }
            }
            // No backup for a month: a gentle nudge.
            if (loaded && allTxns.isNotEmpty() && backupDays >= 30) item {
                Card(Modifier.fillMaxWidth().clickable { open("backup") }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("No backup in $backupDays days", style = MaterialTheme.typography.titleSmall)
                            Text("Save one so a lost phone doesn't lose your history.", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("Back up ›", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            // The app closed by itself last time: offer to send the details so it can be fixed.
            if (crash != null) item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("MyFinance closed unexpectedly last time", style = MaterialTheme.typography.titleSmall)
                        Text("Share the details (on WhatsApp, for example) so it can be fixed. Only the error is sent, no transactions.", style = MaterialTheme.typography.bodySmall)
                        com.myfinance.app.ui.WrapRow(Modifier, 8.dp) {
                            TextButton(onClick = { com.myfinance.app.diag.CrashLog.share(ctx) }) { Text("Share details") }
                            TextButton(onClick = { com.myfinance.app.diag.CrashLog.clear(ctx); crash = null }) { Text("Dismiss") }
                        }
                    }
                }
            }

            // A salary that comes in the last week counts for the next month.
            salaryNote?.let { n ->
                item {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (n.applied) {
                                Text("Salary counts for the next month", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "Your salary comes in the last week, so from the ${n.guess.fromDay}th it counts as the next month's income: " +
                                        "late October salary is November's. Change it under Manage > Month for budgets and reports.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                n.guess.latest?.let { t ->
                                    val d = java.time.Instant.ofEpochMilli(t.timestamp).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                                    val forMonth = cycle.periodOf(t.timestamp, t.type, com.myfinance.core.DefaultCategories.SALARY, java.time.ZoneId.systemDefault())
                                    Text(
                                        "Latest salary: ${com.myfinance.core.formatInr(t.amountMinor)} on ${d.dayOfMonth} ${d.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)}, counted for ${forMonth.month.name.lowercase().replaceFirstChar { it.uppercase() }}",
                                        style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
                                    )
                                }
                                com.myfinance.app.ui.WrapRow(Modifier, 8.dp) {
                                    TextButton(onClick = { vm.v19.salaryNoteDone(true) }) { Text("OK") }
                                    TextButton(onClick = { vm.v19.salaryNoteDone(false) }) { Text("Turn off") }
                                }
                            } else {
                                Text("Is this your salary?", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${n.guess.payer ?: "A steady credit"} comes around the ${n.guess.typicalDay}th each month (${n.guess.months} months). " +
                                        "Count it as salary, for the next month's income?",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                com.myfinance.app.ui.WrapRow(Modifier, 8.dp) {
                                    TextButton(onClick = { vm.v19.useSalaryDay(n.guess) }) { Text("Yes, it's my salary") }
                                    TextButton(onClick = { vm.v19.salaryNoteDone(false) }) { Text("No") }
                                }
                            }
                        }
                    }
                }
            }

            // Today's transactions, then payments due today and in the next few days, and missed ones.
            item { com.myfinance.app.ui.v15.TodayCard(vm, open) }

            // A payment you know is coming: once (school fees on the 15th) or repeating (rent every month).
            item {
                FilledTonalButton(onClick = { addDue = true }, modifier = Modifier.fillMaxWidth()) { Text("+ Future payment (once or repeating)", maxLines = 1) }
            }

            // One line for the month so far; the details are on the Dashboard.
            item {
                Card(Modifier.fillMaxWidth().clickable { open("dashboard") }) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${month.format(monthName)} so far", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text("Dashboard ›", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                        cycle.note(month)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Row {
                            Text("Spent ", style = MaterialTheme.typography.bodyMedium)
                            Text(formatInr(summary.expenseMinor), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = ExpenseRed)
                            Text("   Received ", style = MaterialTheme.typography.bodyMedium)
                            Text(formatInr(summary.incomeMinor), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = IncomeGreen)
                        }
                        if (balances.any { !it.isCardLimit }) {
                            Text(
                                "In your bank accounts: ${formatInr(Balances.totalBankMinor(balances))}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickable { open("balances") },
                            )
                        }
                    }
                }
            }

            item {
                com.myfinance.app.ui.WrapRow(Modifier, 8.dp) {
                    AssistChip(onClick = { open("dashboard") }, label = { Text("Dashboard") })
                    AssistChip(onClick = { open("balances") }, label = { Text("Balances") })
                    AssistChip(onClick = { open("import-statement") }, label = { Text("Import statement") })
                    AssistChip(onClick = { open("reports") }, label = { Text("Reports") })
                }
            }
            item { Box(Modifier.height(72.dp)) }
        }
    }
}
