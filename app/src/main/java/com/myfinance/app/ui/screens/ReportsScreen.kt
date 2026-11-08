package com.myfinance.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
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

/** Compact rupees for chart labels: ₹950, ₹12k, ₹1.2L. */
private fun compactInr(minor: Long): String {
    val r = minor / 100
    return when {
        r >= 1_00_00_000 -> "₹%.1fCr".format(r / 1_00_00_000.0)
        r >= 1_00_000 -> "₹%.1fL".format(r / 1_00_000.0)
        r >= 1_000 -> "₹%.0fk".format(r / 1_000.0)
        else -> "₹$r"
    }
}

@Composable
fun ReportsScreen(vm: FinanceViewModel) {
    val data by vm.reports.collectAsState()
    val month by vm.month.collectAsState()
    val count by vm.reportMonths.collectAsState()
    val categories by vm.categories.collectAsState()
    val sync by vm.syncState.collectAsState()
    val colorOf = categories.associate { it.name to Color(it.color) }
    val primary = MaterialTheme.colorScheme.primary
    var merchantsByCount by remember { mutableStateOf(false) }

    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        uri?.let(vm::exportPdf)
    }

    val labels = data.months.map { it.format(shortMonth) }

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
                IconButton(onClick = vm::nextMonth, enabled = month < YearMonth.now()) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next month")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(6, 12).forEach { n ->
                    FilterChip(selected = count == n, onClick = { vm.reportMonths.value = n }, label = { Text("$n months") })
                }
            }
        }

        // 9. Shareable PDF
        item {
            ReportCard("Monthly statement (PDF)") {
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
            ReportCard("Month by month") {
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
                BarChart(
                    values = data.monthly.map { it.expenseMinor },
                    second = data.monthly.map { it.incomeMinor },
                    labels = labels,
                    color = ExpenseRed,
                    secondColor = IncomeGreen,
                )
                Legend(listOf("Spent" to ExpenseRed, "Income" to IncomeGreen))
            }
        }

        // 5. Savings rate
        item {
            ReportCard("Savings rate") {
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
            ReportCard("Category trends") {
                if (data.categoryTrends.isEmpty()) Empty()
                data.categoryTrends.take(8).forEach { s -> SeriesRow(s, colorOf[s.key] ?: primary, labels) }
            }
        }

        // 4. Per person
        item {
            ReportCard("Spending by person") {
                if (data.perPerson.size <= 1) {
                    Text(
                        "Merge or sync the family's data (Manage tab) to compare people side by side.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val palette = listOf(primary, Color(0xFFE76F51), Color(0xFF457B9D), Color(0xFFE9C46A), Color(0xFF6D597A))
                data.perPerson.forEachIndexed { i, s -> SeriesRow(s, palette[i % palette.size], labels) }
            }
        }

        // 3. Top merchants
        item {
            ReportCard("Top merchants") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !merchantsByCount, onClick = { merchantsByCount = false }, label = { Text("By amount") })
                    FilterChip(selected = merchantsByCount, onClick = { merchantsByCount = true }, label = { Text("By number of payments") })
                }
                val list = if (merchantsByCount) data.topByCount else data.topByAmount
                if (list.isEmpty()) Empty()
                list.forEachIndexed { i, m ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}.", modifier = Modifier.width(28.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(m.merchant, modifier = Modifier.weight(1f), maxLines = 1)
                        Text("${m.count}×  ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(formatInr(m.totalMinor), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // 6. Payment modes
        item {
            ReportCard("How you pay") {
                val total = data.modes.sumOf { it.totalMinor }.coerceAtLeast(1)
                val modeColors = listOf(primary, Color(0xFFE76F51), Color(0xFFE9C46A), Color(0xFF457B9D), Color(0xFF8D99AE))
                if (data.modes.isEmpty()) Empty()
                Canvas(Modifier.fillMaxWidth().height(18.dp)) {
                    var x = 0f
                    data.modes.forEachIndexed { i, m ->
                        val w = size.width * m.totalMinor / total
                        drawRect(modeColors[i % modeColors.size], topLeft = Offset(x, 0f), size = Size(w, size.height))
                        x += w
                    }
                }
                data.modes.forEachIndexed { i, m ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ColorDot(modeColors[i % modeColors.size])
                        Text(m.mode.label, modifier = Modifier.weight(1f))
                        Text("${m.totalMinor * 100 / total}%", style = MaterialTheme.typography.bodySmall)
                        Text(formatInr(m.totalMinor), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // 7. Financial year
        item {
            ReportCard("Financial year (April to March)") {
                if (data.financialYears.isEmpty()) Empty()
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

        // 8. Unusual spends
        item {
            ReportCard("Unusual spends") {
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
private fun ReportCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Empty() {
    Text("No data yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Legend(items: List<Pair<String, Color>>) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        items.forEach { (label, color) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ColorDot(color, 10)
                Text(label, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
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
                drawRect(color, topLeft = Offset(x, size.height - h), size = Size(barW, h))
                second?.getOrNull(i)?.let { v2 ->
                    val h2 = size.height * v2 / max
                    drawRect(secondColor, topLeft = Offset(x + barW, size.height - h2), size = Size(barW, h2))
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
