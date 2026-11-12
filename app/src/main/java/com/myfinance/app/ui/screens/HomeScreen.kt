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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.myfinance.app.ui.FinanceViewModel
import com.myfinance.app.ui.theme.ExpenseRed
import com.myfinance.app.ui.theme.IncomeGreen
import com.myfinance.core.Balances
import com.myfinance.core.MonthLedger
import com.myfinance.core.formatInr
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val homeDate = DateTimeFormatter.ofPattern("EEEE, d MMMM")
private val monthName = DateTimeFormatter.ofPattern("MMMM")
private val ledgerDay = DateTimeFormatter.ofPattern("EEE, d MMM")
private val ledgerTime = DateTimeFormatter.ofPattern("h:mm a")
private val amountCol = 84.dp
private val balanceCol = 88.dp

private fun signedInr(minor: Long) = (if (minor < 0) "−" else "") + formatInr(kotlin.math.abs(minor))

@Composable
private fun LedgerTotal(label: String, value: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        com.myfinance.app.ui.FitText(value, style = MaterialTheme.typography.titleSmall, color = color, fontWeight = FontWeight.SemiBold)
    }
}

/** One day: its date, what went out and came in that day, and the balance at the end of the day. */
@Composable
private fun LedgerDayHeader(day: MonthLedger.Day, period: java.time.YearMonth, cycle: com.myfinance.core.MonthCycle) {
    val today = LocalDate.now()
    val label = when (day.date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> day.date.format(ledgerDay)
    }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                val parts = listOfNotNull(
                    day.outMinor.takeIf { it > 0 }?.let { "out ${formatInr(it)}" },
                    day.inMinor.takeIf { it > 0 }?.let { "in ${formatInr(it)}" },
                    if (day.date < cycle.start(period)) "counts for ${period.format(monthName)}" else null,
                )
                if (parts.isNotEmpty()) Text(parts.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            com.myfinance.app.ui.FitText(
                signedInr(day.closingMinor), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.End, modifier = Modifier.width(balanceCol),
            )
        }
    }
}

@Composable
private fun LedgerRow(row: MonthLedger.Row, name: String, onClick: () -> Unit) {
    val t = row.txn
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val time = java.time.Instant.ofEpochMilli(t.timestamp).atZone(java.time.ZoneId.systemDefault()).toLocalTime().format(ledgerTime)
            Text(
                listOfNotNull(time, t.category, t.account).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        com.myfinance.app.ui.FitText(
            (if (row.isCredit) "+" else "−") + formatInr(t.amountMinor),
            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
            color = if (row.isCredit) IncomeGreen else ExpenseRed,
            textAlign = TextAlign.End, modifier = Modifier.width(amountCol),
        )
        com.myfinance.app.ui.FitText(
            signedInr(row.balanceMinor), style = MaterialTheme.typography.bodySmall, color = muted,
            textAlign = TextAlign.End, modifier = Modifier.width(balanceCol),
        )
    }
}

/**
 * Home: a ledger of the current budget month (the salary month setting decides which month that is),
 * with what is due over the next few days compact on top, and links to the Dashboard and other screens.
 */
@Composable
fun HomeScreen(
    vm: FinanceViewModel,
    smsGranted: Boolean,
    onImport: () -> Unit,
    open: (String) -> Unit,
) {
    val importState by vm.importState.collectAsState()
    val balances by vm.balances.collectAsState()
    val loaded by vm.loaded.collectAsState()
    val allTxns by vm.allTransactions.collectAsState()
    val backupDays = remember(allTxns.size) { vm.v19.backupDaysAgo() }
    val salaryNote by vm.v19.salaryNote.collectAsState()
    val cycle by vm.monthCycle.collectAsState()
    val ledger by vm.homeLedger.collectAsState()
    val nicks by vm.nicknames.collectAsState()
    val byId = remember(allTxns) { allTxns.associateBy { it.id } }
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

            // Compact on top: today's total and what is due over the next few days, and anything missed.
            item { com.myfinance.app.ui.v15.TodayCard(vm, open, showTransactions = false) }

            item {
                com.myfinance.app.ui.WrapRow(Modifier, 8.dp) {
                    AssistChip(onClick = { addDue = true }, label = { Text("+ Future payment") })
                    AssistChip(onClick = { open("dashboard") }, label = { Text("Dashboard") })
                    AssistChip(onClick = { open("balances") }, label = { Text("Balances") })
                    AssistChip(onClick = { open("import-statement") }, label = { Text("Import statement") })
                    AssistChip(onClick = { open("reports") }, label = { Text("Reports") })
                }
            }

            // The month as a ledger: totals, then every day newest first with a running balance.
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${ledger.period.format(monthName)} ledger", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text("Dashboard ›", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { open("dashboard") })
                        }
                        cycle.note(ledger.period)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                            LedgerTotal("Money in", formatInr(ledger.inMinor), IncomeGreen, Modifier.weight(1f))
                            LedgerTotal("Money out", formatInr(ledger.outMinor), ExpenseRed, Modifier.weight(1f))
                            LedgerTotal("Balance", signedInr(ledger.netMinor), if (ledger.netMinor < 0) ExpenseRed else MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
                        }
                        if (balances.any { !it.isCardLimit }) {
                            Text(
                                "In your bank accounts: ${formatInr(Balances.totalBankMinor(balances))} ›",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickable { open("balances") }.padding(top = 2.dp),
                            )
                        }
                    }
                }
            }

            if (loaded && ledger.rowCount == 0) item {
                Text(
                    "No transactions yet this month." + if (ledger.hiddenCount > 0) " ${ledger.hiddenCount} hidden ones still count in the totals." else "",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            if (ledger.rowCount > 0) item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    val st = MaterialTheme.typography.labelSmall
                    val c = MaterialTheme.colorScheme.onSurfaceVariant
                    Text("DETAILS", style = st, color = c, modifier = Modifier.weight(1f))
                    Text("AMOUNT", style = st, color = c, textAlign = TextAlign.End, modifier = Modifier.width(amountCol))
                    Text("BALANCE", style = st, color = c, textAlign = TextAlign.End, modifier = Modifier.width(balanceCol))
                }
            }

            ledger.days.forEach { day ->
                item(key = "day-${day.date}") { LedgerDayHeader(day, ledger.period, cycle) }
                items(day.rows) { row ->
                    val entity = byId[row.txn.id]
                    val name = entity?.let { nicks[it.uid.substringBefore('#')] } ?: row.txn.merchant
                        ?: if (row.isCredit) "Money received" else "Payment"
                    LedgerRow(row, name) {
                        if (row.txn.id != 0L) open("edit/${com.myfinance.app.ui.SplitView.originalId(row.txn.id)}")
                    }
                }
            }

            if (ledger.rowCount > 0) item {
                Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (ledger.hiddenCount > 0) {
                        Text(
                            "${ledger.hiddenCount} hidden " + (if (ledger.hiddenCount == 1) "entry isn't" else "entries aren't") + " listed but still count in the balance.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "Own-account transfers and card bill payments are left out. Balance is money in minus money out this month.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Search and filter in Transactions ›", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { open("transactions") }.padding(vertical = 4.dp),
                    )
                }
            }
            item { Box(Modifier.height(72.dp)) }
        }
    }
}
