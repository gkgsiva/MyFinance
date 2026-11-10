package com.myfinance.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.myfinance.app.ui.FinanceViewModel
import com.myfinance.app.ui.charts.ChartCard
import com.myfinance.app.ui.charts.ChartSeries
import com.myfinance.app.ui.charts.DonutChart
import com.myfinance.app.ui.charts.HorizontalBars
import com.myfinance.app.ui.charts.LineChart
import com.myfinance.app.ui.charts.Slice
import com.myfinance.app.ui.charts.compactInr
import com.myfinance.app.ui.charts.topSlices
import com.myfinance.app.ui.theme.Amber
import com.myfinance.app.ui.theme.ChartPalette
import com.myfinance.app.ui.theme.Coral
import com.myfinance.app.ui.theme.IncomeGreen
import com.myfinance.app.ui.theme.Indigo
import com.myfinance.app.ui.theme.Pink
import com.myfinance.app.ui.theme.Sky
import com.myfinance.app.ui.theme.Teal
import com.myfinance.app.ui.theme.Violet
import com.myfinance.core.BillKind
import com.myfinance.core.DashPeriod
import java.time.format.DateTimeFormatter

private val trendMonth = DateTimeFormatter.ofPattern("MMM")

/**
 * The analytics part of the Dashboard: period and account filters, KPI tiles, the monthly spend trend,
 * category mix, top merchants and spend by bank and card.
 */
@Composable
fun DashboardSection(vm: FinanceViewModel, onOpenCategory: (String) -> Unit) {
    val d by vm.dashboard.collectAsState()
    val period by vm.dashPeriod.collectAsState()
    val account by vm.dashAccount.collectAsState()
    val bills by vm.openBills.collectAsState()
    val categories by vm.categories.collectAsState()
    val colorOf = categories.associate { it.name to Color(it.color) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        com.myfinance.app.ui.WrapRow(Modifier, 8.dp) {
            DashPeriod.entries.forEach { p ->
                FilterChip(selected = period == p, onClick = { vm.dashPeriod.value = p }, label = { Text(p.label) })
            }
        }
        if (d.accounts.size > 1) {
            com.myfinance.app.ui.WrapRow(Modifier, 8.dp) {
                FilterChip(selected = account == null, onClick = { vm.dashAccount.value = null }, label = { Text("All accounts") })
                d.accounts.forEach { a ->
                    FilterChip(selected = account == a, onClick = { vm.dashAccount.value = if (account == a) null else a }, label = { Text(a, maxLines = 1) })
                }
            }
        }

        // KPI tiles, two per row.
        val cardDues = bills.filter { it.kind == BillKind.CREDIT_CARD.name }.sumOf { it.amountMinor }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Kpi("Spent", compactInr(d.spentMinor), Coral, Modifier.weight(1f), if (d.months.size > 1) "${compactInr(d.averagePerMonthMinor)} a month" else null)
            Kpi("Received", compactInr(d.receivedMinor), IncomeGreen, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Kpi(
                "Top category", d.topCategory?.key ?: "—", Violet, Modifier.weight(1f),
                d.topCategory?.let { compactInr(it.totalMinor) },
                onClick = d.topCategory?.let { c -> { onOpenCategory(c.key) } },
            )
            Kpi("Transactions", d.count.toString(), Sky, Modifier.weight(1f))
        }
        if (cardDues > 0) Kpi("Card bills due", compactInr(cardDues), Amber, Modifier.fillMaxWidth(), "Unpaid credit card bills")

        if (d.trend.any { it.second > 0 }) {
            ChartCard("Monthly spend trend", accent = Sky, subtitle = "Last 12 months" + (account?.let { " · $it" } ?: "")) {
                val step = 2
                LineChart(
                    listOf(ChartSeries("Spent", d.trend.map { it.second }, Sky)),
                    d.trend.mapIndexed { i, (m, _) -> if ((d.trend.size - 1 - i) % step == 0) m.format(trendMonth) else "" },
                    height = 150,
                )
            }
        }

        if (d.byCategory.isNotEmpty()) {
            ChartCard("Category breakdown", accent = Pink, subtitle = periodText(d.months.size)) {
                DonutChart(
                    topSlices(d.byCategory.mapIndexed { i, g -> Slice(g.key, g.totalMinor, colorOf[g.key] ?: ChartPalette[i % ChartPalette.size]) }),
                    centerTitle = "Spent",
                )
            }
        }

        if (d.topMerchants.isNotEmpty()) {
            ChartCard("Top merchants", accent = Teal, subtitle = periodText(d.months.size)) {
                HorizontalBars(
                    d.topMerchants.mapIndexed { i, m -> Slice(m.merchant, m.totalMinor, if (i < 3) Teal else Sky) },
                    valueText = { s -> compactInr(s.value) },
                )
            }
        }

        if (d.byAccount.size > 1) {
            ChartCard("Spend by bank & card", accent = Indigo, subtitle = periodText(d.months.size)) {
                HorizontalBars(
                    d.byAccount.take(8).mapIndexed { i, g -> Slice(g.key, g.totalMinor, ChartPalette[i % ChartPalette.size]) },
                    valueText = { s -> compactInr(s.value) },
                )
            }
        }
    }
}

private fun periodText(months: Int) = if (months <= 1) "This month" else "Over $months months"

@Composable
private fun Kpi(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier,
    sub: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Card(
        modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.12f)),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
            sub?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
        }
    }
}
