package com.myfinance.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import com.myfinance.app.ui.charts.ChartCard
import com.myfinance.app.ui.charts.ChartLegend
import com.myfinance.app.ui.charts.ChartSeries
import com.myfinance.app.ui.charts.DonutChart
import com.myfinance.app.ui.charts.GroupedBarChart
import com.myfinance.app.ui.charts.Slice
import com.myfinance.app.ui.charts.topSlices
import com.myfinance.app.ui.theme.ChartPalette
import com.myfinance.app.ui.theme.Coral
import com.myfinance.app.ui.theme.HeroGradient
import com.myfinance.app.ui.theme.IncomeGradient
import com.myfinance.app.ui.theme.Pink
import com.myfinance.app.ui.theme.SpendGradient
import com.myfinance.app.ui.theme.Teal
import java.time.format.DateTimeFormatter
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.graphics.StrokeCap
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
    open: (String) -> Unit = {},
) {
    val month by vm.month.collectAsState()
    val currentMonth by vm.currentMonth.collectAsState()
    val cycle by vm.monthCycle.collectAsState()
    val summary by vm.summary.collectAsState()
    val groupBy by vm.groupBy.collectAsState()
    val categories by vm.categories.collectAsState()
    val importState by vm.importState.collectAsState()
    val txns by vm.monthTransactions.collectAsState()
    val bills by vm.openBills.collectAsState()
    val recurring by vm.recurring.collectAsState()
    val balances by vm.balances.collectAsState()
    val trend by vm.reports.collectAsState()
    val labelGroups by vm.labelGroups.collectAsState()
    val labelBudgets by vm.v14.labelBudgets.collectAsState()
    val labelNet by vm.v14.labelMonthNet.collectAsState()
    val pinned by vm.v14.pinned.collectAsState()
    val refunds by vm.v14.refunds.collectAsState()
    val suggestions by vm.v14.suggestions.collectAsState()

    val colorOf = categories.associate { it.name to Color(it.color) }
    val budgetOf = categories.associate { it.name to it.monthlyBudgetMinor }
    val groups = when (groupBy) {
        GroupBy.CATEGORY -> summary.byCategory
        GroupBy.LABEL -> labelGroups
        GroupBy.MERCHANT -> summary.byMerchant
        GroupBy.ACCOUNT -> summary.byAccount
        GroupBy.PERSON -> summary.byPerson
    }
    val maxGroup = groups.maxOfOrNull { it.totalMinor }?.coerceAtLeast(1) ?: 1

    Scaffold(
        floatingActionButton = {
            // The current month adds through a menu (spend, income, payment due, ...); past months add a spend.
            if (month == currentMonth) com.myfinance.app.ui.v15.QuickAddFab(vm, open)
            else FloatingActionButton(onClick = onAdd) { Icon(Icons.Filled.Add, contentDescription = "Add transaction") }
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = vm::previousMonth) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous month")
                    }
                    Column(Modifier.weight(1f)) {
                        Text(month.format(monthFormat), style = MaterialTheme.typography.titleLarge)
                        // With a custom month (e.g. 25th to 24th) show which dates it covers.
                        cycle.rangeLabel(month)?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    IconButton(onClick = vm::nextMonth, enabled = month < currentMonth) {
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

            if (month == currentMonth) item { com.myfinance.app.ui.v15.TodayCard(vm, open) }

            item { HeroCard(summary.incomeMinor, summary.expenseMinor, summary.netMinor, summary.savingsRatePercent) }

            if (groups.isNotEmpty()) {
                item {
                    val slices = topSlices(
                        groups.mapIndexed { i, g ->
                            val c = if (groupBy == GroupBy.CATEGORY) colorOf[g.key] ?: ChartPalette[i % ChartPalette.size]
                            else ChartPalette[i % ChartPalette.size]
                            Slice(g.key, g.totalMinor, c)
                        },
                    )
                    ChartCard("Spending mix", accent = Pink, subtitle = "By ${groupBy.label.lowercase()} this month") {
                        DonutChart(slices, centerTitle = "Spent")
                    }
                }
            }

            if (trend.monthly.size > 1 && trend.monthly.any { it.incomeMinor > 0 || it.expenseMinor > 0 }) {
                item {
                    val last6 = trend.monthly.takeLast(6)
                    val labels = last6.map { it.month.format(shortMonthFormat) }
                    ChartCard("Income vs spend", accent = Teal, subtitle = "Last ${last6.size} months") {
                        GroupedBarChart(
                            listOf(
                                ChartSeries("Income", last6.map { it.incomeMinor }, IncomeGreen),
                                ChartSeries("Spent", last6.map { it.expenseMinor }, Coral),
                            ),
                            labels,
                            height = 130,
                        )
                        ChartLegend(listOf("Income" to IncomeGreen, "Spent" to Coral))
                    }
                }
            }

            // This month's Today card already lists what is due; keep only the bank balance line here.
            val showDue = month != currentMonth
            if (showDue || balances.any { !it.isCardLimit }) item {
                ComingUpCard(bills, recurring, balances, onOpenUpcoming, showDue)
            }

            item { com.myfinance.app.ui.v13.FactsCard(vm, max = 3) }

            if (pinned.isNotEmpty()) item {
                com.myfinance.app.ui.v14.PinnedReportsCard(vm) { r ->
                    vm.v14.pendingExplore.value = r
                    open("explore")
                }
            }
            if (refunds.any { it.state == com.myfinance.core.RefundState.OVERDUE }) item {
                com.myfinance.app.ui.v14.RefundsPrompt(vm) { open("refunds") }
            }
            if (suggestions.any { it.strength >= 2 }) item {
                com.myfinance.app.ui.v14.SuggestionsPrompt(vm) { open("label-suggestions") }
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

            itemsIndexed(groups, key = { _, g -> "${groupBy.name}:${g.key}" }) { i, g ->
                val color = if (groupBy == GroupBy.CATEGORY) colorOf[g.key] ?: ChartPalette[i % ChartPalette.size]
                else ChartPalette[i % ChartPalette.size]
                val budget = when (groupBy) {
                    GroupBy.CATEGORY -> budgetOf[g.key]
                    GroupBy.LABEL -> labelBudgets[g.key]
                    else -> null
                }
                // Label budgets count refunds against the label, like the alerts do.
                val used = if (groupBy == GroupBy.LABEL && budget != null) labelNet[g.key] ?: g.totalMinor else g.totalMinor
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = groupBy == GroupBy.CATEGORY || (groupBy == GroupBy.LABEL && g.key != com.myfinance.core.Labels.NONE)) {
                            if (groupBy == GroupBy.LABEL) {
                                vm.labelFilter.value = g.key
                                onOpenGroup(null)
                            } else {
                                vm.labelFilter.value = null
                                onOpenGroup(g.key)
                            }
                        }
                        .padding(vertical = 4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ColorDot(color)
                        Text(g.key, modifier = Modifier.weight(1f), maxLines = 1)
                        Text("${g.count}×", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        Text(formatInr(g.totalMinor), fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(4.dp))
                    val progress = if (budget != null && budget > 0) used.toFloat() / budget
                    else g.totalMinor.toFloat() / maxGroup
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                        color = if (budget != null && used > budget) ExpenseRed else color,
                        trackColor = color.copy(alpha = 0.18f),
                        strokeCap = StrokeCap.Round,
                        gapSize = 0.dp,
                        drawStopIndicator = {},
                    )
                    if (budget != null && budget > 0) {
                        Text(
                            if (used > budget) "Over budget by ${formatInr(used - budget)}"
                            else "${formatInr(budget - used)} left of ${formatInr(budget)} budget",
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
    showDue: Boolean = true,
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
            if (showDue) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Coming up", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("See all", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            }
            if (showDue && lines.isEmpty()) {
                Text(
                    "No bills or regular payments due in the next 30 days.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (showDue) lines.forEach { (date, title, amount) ->
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

private val shortMonthFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM")

/** Colourful month header: net in big type, income and spend in two tinted chips. */
@Composable
private fun HeroCard(incomeMinor: Long, expenseMinor: Long, netMinor: Long, savingsRate: Int?) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(HeroGradient)
            .padding(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column {
                Text("Net this month", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.85f))
                Text(
                    (if (netMinor < 0) "−" else "") + formatInr(kotlin.math.abs(netMinor)),
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                )
                savingsRate?.let {
                    Text(
                        if (it >= 0) "You saved $it% of your income" else "You spent ${-it}% more than you earned",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.85f),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HeroChip("Income", formatInr(incomeMinor), IncomeGradient, Modifier.weight(1f))
                HeroChip("Spent", formatInr(expenseMinor), SpendGradient, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun HeroChip(label: String, value: String, brush: Brush, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(brush)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.9f))
        Text(value, style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}
