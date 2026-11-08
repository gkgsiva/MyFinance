package com.myfinance.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.myfinance.app.data.BillEntity
import com.myfinance.app.ui.FinanceViewModel
import com.myfinance.app.ui.GroupBy
import com.myfinance.app.ui.theme.ExpenseRed
import com.myfinance.app.ui.theme.IncomeGreen
import com.myfinance.core.BalanceReading
import com.myfinance.core.Balances
import com.myfinance.core.RecurringPayment
import com.myfinance.core.formatInr
import java.time.LocalDate
import java.time.YearMonth

@Composable
fun OverviewScreen(
    vm: FinanceViewModel,
    smsGranted: Boolean,
    onImport: () -> Unit,
    onOpenGroup: (category: String?) -> Unit,
    onAdd: () -> Unit,
    onOpenUpcoming: () -> Unit,
) {
    val month by vm.month.collectAsState()
    val summary by vm.summary.collectAsState()
    val groupBy by vm.groupBy.collectAsState()
    val categories by vm.categories.collectAsState()
    val importState by vm.importState.collectAsState()
    val txns by vm.monthTransactions.collectAsState()
    val bills by vm.openBills.collectAsState()
    val recurring by vm.recurring.collectAsState()
    val balances by vm.balances.collectAsState()

    val colorOf = categories.associate { it.name to Color(it.color) }
    val budgetOf = categories.associate { it.name to it.monthlyBudgetMinor }
    val groups = when (groupBy) {
        GroupBy.CATEGORY -> summary.byCategory
        GroupBy.MERCHANT -> summary.byMerchant
        GroupBy.ACCOUNT -> summary.byAccount
        GroupBy.PERSON -> summary.byPerson
    }
    val maxGroup = groups.maxOfOrNull { it.totalMinor }?.coerceAtLeast(1) ?: 1

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) { Icon(Icons.Filled.Add, contentDescription = "Add transaction") }
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = vm::previousMonth) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous month")
                    }
                    Text(
                        month.format(monthFormat),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = vm::nextMonth, enabled = month < YearMonth.now()) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next month")
                    }
                    IconButton(onClick = onImport, enabled = !importState.running) {
                        if (importState.running) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Refresh, contentDescription = "Import SMS")
                        }
                    }
                }
            }

            if (!smsGranted) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Read your bank SMS", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "MyFinance reads transaction messages from your banks to build your spending " +
                                    "profile. Everything stays on this phone.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            FilledTonalButton(onClick = onImport) { Text("Allow and import") }
                        }
                    }
                }
            }

            importState.lastAdded?.let { added ->
                item {
                    Text(
                        if (added == 0) "No new transactions found in SMS." else "Imported $added new transactions from SMS.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            importState.error?.let { err ->
                item { Text(err, color = MaterialTheme.colorScheme.error) }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard("Income", formatInr(summary.incomeMinor), IncomeGreen, Modifier.weight(1f))
                    StatCard("Spent", formatInr(summary.expenseMinor), ExpenseRed, Modifier.weight(1f))
                }
            }
            item {
                val net = summary.netMinor
                StatCard(
                    "Net this month",
                    formatInr(net) + (summary.savingsRatePercent?.let { "  ·  saved $it%" } ?: ""),
                    if (net >= 0) IncomeGreen else ExpenseRed,
                    Modifier.fillMaxWidth(),
                )
            }

            item {
                ComingUpCard(bills, recurring, balances, onOpenUpcoming)
            }

            item {
                Spacer(Modifier.height(4.dp))
                Text("Where it went", style = MaterialTheme.typography.titleMedium)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    GroupBy.entries.forEach { g ->
                        FilterChip(selected = groupBy == g, onClick = { vm.groupBy.value = g }, label = { Text(g.label) })
                    }
                }
            }

            if (groups.isEmpty()) {
                item {
                    Text(
                        if (txns.isEmpty()) "No transactions this month yet." else "No spending this month.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(groups, key = { "${groupBy.name}:${it.key}" }) { g ->
                val color = if (groupBy == GroupBy.CATEGORY) colorOf[g.key] ?: MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.primary
                val budget = if (groupBy == GroupBy.CATEGORY) budgetOf[g.key] else null
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = groupBy == GroupBy.CATEGORY) { onOpenGroup(g.key) }
                        .padding(vertical = 4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ColorDot(color)
                        Text(g.key, modifier = Modifier.weight(1f), maxLines = 1)
                        Text("${g.count}×", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        Text(formatInr(g.totalMinor), fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(4.dp))
                    val progress = if (budget != null && budget > 0) g.totalMinor.toFloat() / budget
                    else g.totalMinor.toFloat() / maxGroup
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = if (budget != null && g.totalMinor > budget) ExpenseRed else color,
                    )
                    if (budget != null && budget > 0) {
                        Text(
                            if (g.totalMinor > budget) "Over budget by ${formatInr(g.totalMinor - budget)}"
                            else "${formatInr(budget - g.totalMinor)} left of ${formatInr(budget)} budget",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item { Box(Modifier.height(72.dp)) }
        }
    }
}

@Composable
private fun ComingUpCard(
    bills: List<BillEntity>,
    recurring: List<RecurringPayment>,
    balances: List<BalanceReading>,
    onOpen: () -> Unit,
) {
    val today = LocalDate.now()
    val soon = today.plusDays(30)
    // Next few items: bills first, then recurring payments, by date.
    val lines = (
        bills.map { b -> val bill = b.toBill(); Triple(bill.dueDate, bill.title, bill.amountMinor) } +
            recurring.filter { !it.nextDate.isAfter(soon) }.map { Triple(it.nextDate, it.merchant, it.amountMinor) }
        ).sortedBy { it.first }.take(4)

    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Coming up", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("See all", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            }
            if (lines.isEmpty()) {
                Text(
                    "No bills or regular payments due in the next 30 days.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            lines.forEach { (date, title, amount) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, modifier = Modifier.weight(1f), maxLines = 1)
                    Text(
                        relativeDay(date, today) + "  ",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (date.isBefore(today)) ExpenseRed else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(formatInr(amount), fontWeight = FontWeight.SemiBold)
                }
            }
            if (balances.any { !it.isCardLimit }) {
                Text(
                    "In your bank accounts: ${formatInr(Balances.totalBankMinor(balances))}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, color = color, fontWeight = FontWeight.Bold)
        }
    }
}
