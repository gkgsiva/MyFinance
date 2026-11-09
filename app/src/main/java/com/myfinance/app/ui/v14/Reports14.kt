package com.myfinance.app.ui.v14

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.myfinance.app.ui.FinanceViewModel
import com.myfinance.app.ui.charts.ChartCard
import com.myfinance.app.ui.charts.ChartLegend
import com.myfinance.app.ui.charts.ChartSeries
import com.myfinance.app.ui.charts.GroupedBarChart
import com.myfinance.app.ui.theme.Coral
import com.myfinance.app.ui.theme.ExpenseRed
import com.myfinance.app.ui.theme.IncomeGreen
import com.myfinance.app.ui.theme.Pink
import com.myfinance.app.ui.theme.Sky
import com.myfinance.app.ui.theme.Teal
import com.myfinance.app.ui.v13.Muted
import com.myfinance.core.formatInr
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val monthFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM yyyy")

private fun signed(p: Double?): String = p?.let { (if (it >= 0) "+" else "−") + "%.0f%%".format(kotlin.math.abs(it)) } ?: "–"

/** Salary breakdown: raises, bonus months, and income against spending growth over the years. */
@Composable
fun SalaryBreakdownCard(vm: FinanceViewModel) {
    val both by vm.v14.salary.collectAsState()
    var fy by remember { mutableStateOf(true) }
    val r = both?.let { if (fy) it.second else it.first } ?: return
    if (r.years.isEmpty()) return
    ChartCard("Salary breakdown", accent = Teal, subtitle = "Raises, bonuses, and income vs spending growth") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = fy, onClick = { fy = true }, label = { Text("Financial year") })
            FilterChip(selected = !fy, onClick = { fy = false }, label = { Text("Calendar year") })
        }
        if (r.currentMonthlyMinor > 0) {
            Text("Salary now: about ${formatInr(r.currentMonthlyMinor)} a month", fontWeight = FontWeight.SemiBold)
        }
        r.averageRaisePct?.let { Muted("Average raise: ${"%.1f".format(it)}% over ${r.raises.size} raises") }

        val years = r.years.takeLast(8)
        GroupedBarChart(
            listOf(
                ChartSeries("Income", years.map { it.incomeMinor }, IncomeGreen),
                ChartSeries("Spent", years.map { it.spentMinor }, Coral),
            ),
            years.map { it.label.removePrefix("FY ") },
            height = 140,
        )
        ChartLegend(listOf("Income" to IncomeGreen, "Spent" to Coral))
        Row(Modifier.fillMaxWidth()) {
            Text("Year", Modifier.weight(1.3f), style = MaterialTheme.typography.labelMedium)
            Text("Income", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End)
            Text("Spend", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End)
            Text("Saved", Modifier.weight(0.8f), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End)
        }
        years.reversed().forEach { y ->
            Row(Modifier.fillMaxWidth()) {
                Text(y.label.removePrefix("FY "), Modifier.weight(1.3f))
                Text(signed(y.incomeGrowthPct), Modifier.weight(1f), textAlign = TextAlign.End, color = if ((y.incomeGrowthPct ?: 0.0) >= 0) IncomeGreen else ExpenseRed)
                Text(signed(y.spendGrowthPct), Modifier.weight(1f), textAlign = TextAlign.End, color = if ((y.spendGrowthPct ?: 0.0) <= 0) IncomeGreen else ExpenseRed)
                Text(y.savedPct?.let { "$it%" } ?: "–", Modifier.weight(0.8f), textAlign = TextAlign.End)
            }
        }
        Muted("Growth is against the year before. Spending growing faster than income eats into savings.")

        if (r.raises.isNotEmpty()) {
            Text("Raises", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
            r.raises.reversed().take(8).forEach { x ->
                Row {
                    Text(x.month.format(monthFmt), Modifier.weight(1f))
                    Text("${formatInr(x.fromMinor)} → ${formatInr(x.toMinor)}  ", style = MaterialTheme.typography.bodySmall)
                    Text("+${"%.1f".format(x.pct)}%", color = IncomeGreen, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (r.bonuses.isNotEmpty()) {
            Text("Bonus months", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
            r.bonuses.reversed().take(8).forEach { b ->
                Row {
                    Text(b.month.format(monthFmt), Modifier.weight(1f))
                    Text("+${formatInr(b.extraMinor)}" + if (b.labelled) " (labelled Bonus)" else "", fontWeight = FontWeight.SemiBold)
                }
            }
            Muted("A bonus month is salary 40% or more above the usual, or money you labelled Bonus.")
        }
    }
}

/** Family label view: spending on each label by each person, from merged family files. */
@Composable
fun FamilyLabelsCard(vm: FinanceViewModel) {
    val meta by vm.txnMeta.collectAsState()
    val all by vm.allTransactions.collectAsState()
    val today = LocalDate.now()
    val periods = listOf(
        "This month" to today.withDayOfMonth(1),
        "3 months" to today.minusMonths(2).withDayOfMonth(1),
        "This year" to today.withDayOfYear(1),
        "12 months" to today.minusMonths(11).withDayOfMonth(1),
    )
    var period by remember { mutableStateOf(1) }
    val (people, rows) = remember(all, meta, period) { vm.v14.familyLabels(periods[period].second, today) }
    ChartCard("Labels by person", accent = Pink, subtitle = "What each of you spent per label, refunds taken off") {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            periods.forEachIndexed { i, (name, _) -> FilterChip(selected = period == i, onClick = { period = i }, label = { Text(name) }) }
        }
        if (rows.isEmpty()) {
            Muted("No labelled spending in this period. Labels added on other phones arrive when they share their file.")
            return@ChartCard
        }
        if (people.size < 2) Muted("Only your entries so far. Merge your family's files (Manage > Family) to compare.")
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            Column {
                Row {
                    Text("Label", Modifier.width(110.dp), style = MaterialTheme.typography.labelMedium)
                    people.forEach { p -> Text(p, Modifier.width(90.dp), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End, maxLines = 1) }
                    if (people.size > 1) Text("Total", Modifier.width(90.dp), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End)
                }
                rows.take(25).forEach { r ->
                    Row(Modifier.padding(top = 4.dp)) {
                        Text("#${r.label}", Modifier.width(110.dp), maxLines = 1)
                        people.forEach { p ->
                            val v = r.byPerson[p] ?: 0L
                            Text(if (v == 0L) "–" else formatInr(v), Modifier.width(90.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.bodySmall)
                        }
                        if (people.size > 1) Text(formatInr(r.totalMinor), Modifier.width(90.dp), textAlign = TextAlign.End, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/** Reports tab entry to the year in review. */
@Composable
fun YearReviewEntry(onOpen: () -> Unit) {
    ChartCard("Year in review", accent = Sky, modifier = Modifier.clickable(onClick = onOpen)) {
        Muted("Your year on one page: top sellers, record months and fun facts, ready to share on WhatsApp.")
        FilledTonalButton(onClick = onOpen) { Text("Open") }
    }
}

@Composable
internal fun KeyValue(key: String, value: String, strong: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(key, Modifier.weight(1f))
        Text(value, fontWeight = if (strong) FontWeight.Bold else FontWeight.SemiBold)
    }
}
