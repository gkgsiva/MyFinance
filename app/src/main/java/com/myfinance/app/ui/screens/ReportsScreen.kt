package com.myfinance.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.geometry.CornerRadius
import com.myfinance.app.ui.charts.ChartCard
import com.myfinance.app.ui.charts.ChartLegend
import com.myfinance.app.ui.charts.ChartSeries
import com.myfinance.app.ui.charts.DonutChart
import com.myfinance.app.ui.charts.GroupedBarChart
import com.myfinance.app.ui.charts.HorizontalBars
import com.myfinance.app.ui.charts.LineChart
import com.myfinance.app.ui.charts.Slice
import com.myfinance.app.ui.charts.compactInr
import com.myfinance.app.ui.theme.Amber
import com.myfinance.app.ui.theme.ChartPalette
import com.myfinance.app.ui.theme.Coral
import com.myfinance.app.ui.theme.Indigo
import com.myfinance.app.ui.theme.Pink
import com.myfinance.app.ui.theme.Sky
import com.myfinance.app.ui.theme.Teal
import com.myfinance.app.ui.theme.Violet
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myfinance.app.ui.FinanceViewModel
import com.myfinance.app.ui.theme.ExpenseRed
import com.myfinance.app.ui.theme.IncomeGreen
import com.myfinance.core.Reports
import com.myfinance.core.Series
import com.myfinance.core.formatInr
import java.time.YearMonth
import java.time.format.DateTimeFormatter

private val shortMonth: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM")
private val monthYear: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM yyyy")

@Composable
fun ReportsScreen(vm: FinanceViewModel, onExplore: () -> Unit = {}, open: (String) -> Unit = {}) {
    val data by vm.reports.collectAsState()
    val month by vm.month.collectAsState()
    val currentMonth by vm.currentMonth.collectAsState()
    val count by vm.reportMonths.collectAsState()
    val categories by vm.categories.collectAsState()
    val sync by vm.syncState.collectAsState()
    val colorOf = categories.associate { it.name to Color(it.color) }
    val primary = MaterialTheme.colorScheme.primary
    var merchantsByCount by remember { mutableStateOf(false) }

    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        uri?.let(vm::exportPdf)
    }

    // Long ranges (several years of imported statements) label every few months so the axis stays readable.
    val step = (data.months.size + 11) / 12
    val labels = data.months.mapIndexed { i, m -> if ((data.months.size - 1 - i) % step == 0) m.format(shortMonth) else "" }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = vm::previousMonth) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous month")
                }
                Text("Up to ${month.format(monthYear)}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = vm::nextMonth, enabled = month < currentMonth) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next month")
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(6 to "6 months", 12 to "1 year", 36 to "3 years", 60 to "5 years").forEach { (n, label) ->
                    FilterChip(selected = count == n, onClick = { vm.reportMonths.value = n }, label = { Text(label) })
                }
            }
        }

        item { com.myfinance.app.ui.v13.FactsCard(vm) }
        item { com.myfinance.app.ui.v13.ExploreCard(onExplore) }
        item { com.myfinance.app.ui.v14.YearReviewEntry { open("year-review") } }

        // 9. Shareable PDF
        item {
            ReportCard("Monthly statement (PDF)", accent = Violet) {
                Text(
                    "A statement of ${month.format(monthYear)} for the whole family: totals, categories, people, top merchants and every transaction.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilledTonalButton(
                    onClick = { pdfLauncher.launch("myfinance-${month}.pdf") },
                    enabled = !sync.running,
                ) { Text("Save PDF") }
                sync.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }

        // 1. Month-on-month trend
        item {
            ReportCard("Month by month", accent = Teal) {
                val last = data.monthly.lastOrNull()
                val prev = data.monthly.getOrNull(data.monthly.size - 2)
                if (last != null) {
                    val change = prev?.let { Reports.changePercent(last.expenseMinor, it.expenseMinor) }
                    Text(
                        "Spent ${formatInr(last.expenseMinor)} in ${last.month.format(monthYear)}" +
                            (change?.let { if (it >= 0) ", up $it% on last month" else ", down ${-it}% on last month" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                GroupedBarChart(
                    listOf(
                        ChartSeries("Income", data.monthly.map { it.incomeMinor }, IncomeGreen),
                        ChartSeries("Spent", data.monthly.map { it.expenseMinor }, Coral),
                    ),
                    labels,
                )
                ChartLegend(listOf("Income" to IncomeGreen, "Spent" to Coral))
            }
        }

        // 5. Savings rate
        item {
            ReportCard("Savings", accent = IncomeGreen) {
                LineChart(
                    listOf(ChartSeries("Saved", data.monthly.map { it.netMinor }, Indigo)),
                    labels,
                    height = 130,
                    showZero = true,
                )
                Text(
                    "Money left over each month (income minus spending), and the share of income you kept:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                data.monthly.reversed().forEach { m ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(m.month.format(monthYear), modifier = Modifier.width(84.dp), style = MaterialTheme.typography.bodySmall)
                        val rate = m.savingsRatePercent
                        LinearProgressIndicator(
                            progress = { ((rate ?: 0).coerceIn(0, 100)) / 100f },
                            modifier = Modifier.weight(1f),
                            color = if ((rate ?: 0) >= 0) IncomeGreen else ExpenseRed,
                        )
                        Text(
                            rate?.let { "$it%" } ?: "–",
                            modifier = Modifier.width(48.dp),
                            textAlign = TextAlign.End,
                            color = if ((rate ?: 0) < 0) ExpenseRed else MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            formatInr(m.netMinor),
                            modifier = Modifier.width(96.dp),
                            textAlign = TextAlign.End,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        // 2. Category trends
        item {
            ReportCard("Category trends", accent = Pink) {
                if (data.categoryTrends.isEmpty()) Empty()
                val top = data.categoryTrends.sortedByDescending { it.total }.take(5)
                if (top.isNotEmpty()) {
                    LineChart(
                        top.mapIndexed { i, s -> ChartSeries(s.key, s.values, colorOf[s.key] ?: ChartPalette[i % ChartPalette.size]) },
                        labels,
                        fill = false,
                    )
                    FlowLegend(top.mapIndexed { i, s -> s.key to (colorOf[s.key] ?: ChartPalette[i % ChartPalette.size]) })
                }
                data.categoryTrends.take(8).forEach { s -> SeriesRow(s, colorOf[s.key] ?: primary, labels) }
            }
        }

        // 4. Per person
        item {
            ReportCard("Spending by person", accent = Violet) {
                if (data.perPerson.size <= 1) {
                    Text(
                        "Merge or sync the family's data (Manage tab) to compare people side by side.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val palette = ChartPalette
                if (data.perPerson.size > 1) {
                    DonutChart(
                        data.perPerson.mapIndexed { i, s -> Slice(s.key, s.total, palette[i % palette.size]) },
                        centerTitle = "Family",
                    )
                }
                if (data.perPerson.isNotEmpty()) {
                    HorizontalBars(data.perPerson.mapIndexed { i, s -> Slice(s.key, s.total, palette[i % palette.size]) })
                }
                if (data.perPerson.size > 1) {
                    Text("Month by month", style = MaterialTheme.typography.labelLarge)
                    LineChart(
                        data.perPerson.mapIndexed { i, s -> ChartSeries(s.key, s.values, palette[i % palette.size]) },
                        labels,
                        fill = false,
                        height = 130,
                    )
                }
            }
        }

        // 3. Top merchants
        item {
            ReportCard("Top merchants", accent = Amber) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !merchantsByCount, onClick = { merchantsByCount = false }, label = { Text("By amount") })
                    FilterChip(selected = merchantsByCount, onClick = { merchantsByCount = true }, label = { Text("By number of payments") })
                }
                val list = if (merchantsByCount) data.topByCount else data.topByAmount
                if (list.isEmpty()) Empty()
                if (list.isNotEmpty()) {
                    HorizontalBars(
                        list.take(5).mapIndexed { i, m ->
                            Slice(m.merchant, if (merchantsByCount) m.count.toLong() else m.totalMinor, ChartPalette[i % ChartPalette.size])
                        },
                        valueText = { if (merchantsByCount) "${it.value}×" else formatInr(it.value) },
                    )
                }
                list.drop(5).forEachIndexed { idx, m ->
                    val i = idx + 5
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}.", modifier = Modifier.width(28.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(m.merchant, modifier = Modifier.weight(1f), maxLines = 1)
                        Text("${m.count}×  ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(formatInr(m.totalMinor), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // Labels
        item {
            ReportCard("By label", accent = Teal) {
                val spentLabels = data.byLabel.filter { it.spentMinor > 0 }
                val incomeLabels = data.byLabel.filter { it.spentMinor == 0L && it.receivedMinor > 0 }
                if (data.byLabel.isEmpty()) {
                    Text(
                        "Add labels like EMI, SIP, Grocery, IRCTC or Salary to transactions (tap one to edit) to see them here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (spentLabels.isNotEmpty()) {
                    Text("Spending, net of refunds", style = MaterialTheme.typography.titleSmall)
                    HorizontalBars(
                        spentLabels.take(8).mapIndexed { i, g -> Slice(g.label, g.netMinor.coerceAtLeast(0), ChartPalette[i % ChartPalette.size]) },
                        valueText = { formatInr(it.value) },
                    )
                    spentLabels.filter { it.receivedMinor > 0 }.forEach { g ->
                        Text(
                            "${g.label}: spent ${formatInr(g.spentMinor)}, got back ${formatInr(g.receivedMinor)} (${g.credits} refunds), net ${formatInr(g.netMinor)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    spentLabels.drop(8).forEach { g ->
                        Row {
                            Text(g.label, modifier = Modifier.weight(1f), maxLines = 1)
                            Text(formatInr(g.netMinor), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                if (incomeLabels.isNotEmpty()) {
                    Text("Income", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                    incomeLabels.forEach { g ->
                        Row {
                            Text(g.label, modifier = Modifier.weight(1f), maxLines = 1)
                            Text(formatInr(g.receivedMinor), fontWeight = FontWeight.SemiBold, color = IncomeGreen)
                        }
                    }
                }
                if (data.incomeLabelsByFy.isNotEmpty()) {
                    Text("Income by label, per financial year", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                    data.incomeLabelsByFy.forEach { (fy, rows) ->
                        Text(fy, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                        rows.filter { it.second > 0 }.forEach { (label, amt) ->
                            Row {
                                Text(label, modifier = Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.bodySmall)
                                Text(formatInr(amt), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }

        // 6. Payment modes
        item {
            ReportCard("How you pay", accent = Sky) {
                val modeColors = listOf(Sky, Pink, Amber, Teal, Violet, Coral)
                if (data.modes.isEmpty()) Empty() else {
                    DonutChart(
                        data.modes.mapIndexed { i, m -> Slice(m.mode.label, m.totalMinor, modeColors[i % modeColors.size]) },
                        centerTitle = "Paid",
                    )
                }
            }
        }

        // 7. Financial year
        item {
            ReportCard("Financial year (April to March)", accent = Indigo) {
                if (data.financialYears.isEmpty()) Empty()
                if (data.financialYears.size >= 2) {
                    val years = data.financialYears.reversed()
                    GroupedBarChart(
                        listOf(
                            ChartSeries("Income", years.map { it.incomeMinor }, IncomeGreen),
                            ChartSeries("Spent", years.map { it.expenseMinor }, Coral),
                        ),
                        years.map { "FY" + (it.startYear % 100).toString().padStart(2, '0') },
                    )
                    ChartLegend(listOf("Income" to IncomeGreen, "Spent" to Coral))
                }
                data.financialYears.forEach { fy ->
                    Text(fy.label, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                    Text(
                        "Income ${formatInr(fy.incomeMinor)} · Spent ${formatInr(fy.expenseMinor)} · Net ${formatInr(fy.netMinor)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    fy.byCategory.take(5).forEach { g ->
                        Row {
                            Text(g.key, modifier = Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.bodySmall)
                            Text(formatInr(g.totalMinor), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        item { com.myfinance.app.ui.v13.IncomeYearsCard(vm) }
        item { com.myfinance.app.ui.v14.SalaryBreakdownCard(vm) }
        item { com.myfinance.app.ui.v14.FamilyLabelsCard(vm) }

        // 8. Unusual spends
        item {
            ReportCard("Unusual spends", accent = Coral) {
                if (data.unusual.isEmpty()) {
                    Text(
                        "Nothing unusual: no payment was more than 3× your typical amount for its category.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                data.unusual.forEach { u ->
                    Column {
                        Row {
                            Text(u.txn.merchant ?: u.txn.category, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                            Text(formatInr(u.txn.amountMinor), color = ExpenseRed, fontWeight = FontWeight.SemiBold)
                        }
                        Text(
                            u.txn.timestamp.toLocalDateTime().format(dayFormat) + " · " +
                                "%.1f× your usual %s for %s".format(u.times, formatInr(u.typicalMinor), u.txn.category),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportCard(title: String, accent: Color = MaterialTheme.colorScheme.primary, content: @Composable () -> Unit) {
    ChartCard(title, accent = accent) { content() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowLegend(items: List<Pair<String, Color>>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { (label, color) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ColorDot(color, 10)
                Text(label, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun Empty() {
    Text("No data yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** One category / person: name, total, change and a small bar per month. */
@Composable
private fun SeriesRow(s: Series, color: Color, labels: List<String>) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ColorDot(color)
            Text(s.key, modifier = Modifier.weight(1f), maxLines = 1)
            s.trendPercent?.let { t ->
                Text(
                    if (t >= 0) "▲ $t%" else "▼ ${-t}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (t > 10) ExpenseRed else if (t < -10) IncomeGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(formatInr(s.total), fontWeight = FontWeight.SemiBold)
        }
        BarChart(values = s.values, labels = labels, color = color, height = 48, showValues = false)
    }
}

/** Simple bar chart; with [second] it draws two bars per label side by side. */
@Composable
private fun BarChart(
    values: List<Long>,
    labels: List<String>,
    color: Color,
    second: List<Long>? = null,
    secondColor: Color = Color.Gray,
    height: Int = 140,
    showValues: Boolean = true,
) {
    val max: Long = (values + second.orEmpty()).maxOrNull()?.coerceAtLeast(1L) ?: 1L
    Column {
        if (showValues) {
            Text("max ${compactInr(max)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Canvas(Modifier.fillMaxWidth().height(height.dp)) {
            val n = values.size.coerceAtLeast(1)
            val slot = size.width / n
            val bars = if (second != null) 2 else 1
            val barW = slot * 0.7f / bars
            values.forEachIndexed { i, v ->
                val h = size.height * v / max
                val x = i * slot + slot * 0.15f
                drawRoundRect(color, topLeft = Offset(x, size.height - h), size = Size(barW, h), cornerRadius = CornerRadius(barW / 3, barW / 3))
                second?.getOrNull(i)?.let { v2 ->
                    val h2 = size.height * v2 / max
                    drawRoundRect(secondColor, topLeft = Offset(x + barW, size.height - h2), size = Size(barW, h2), cornerRadius = CornerRadius(barW / 3, barW / 3))
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            labels.forEach {
                Text(
                    it,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}
