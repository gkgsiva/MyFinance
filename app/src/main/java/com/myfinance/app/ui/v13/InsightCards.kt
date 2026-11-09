package com.myfinance.app.ui.v13

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.myfinance.app.ui.FinanceViewModel
import com.myfinance.app.ui.charts.ChartCard
import com.myfinance.app.ui.charts.ChartSeries
import com.myfinance.app.ui.charts.GroupedBarChart
import com.myfinance.app.ui.charts.LineChart
import com.myfinance.app.ui.theme.ExpenseRed
import com.myfinance.app.ui.theme.IncomeGreen
import com.myfinance.app.ui.theme.Teal
import com.myfinance.app.ui.theme.Violet
import com.myfinance.core.formatInr
import java.time.format.DateTimeFormatter

/** "Did you know": record months, big jumps, top category, streaks. */
@Composable
fun FactsCard(vm: FinanceViewModel, max: Int = 8) {
    val facts by vm.facts.collectAsState()
    if (facts.isEmpty()) return
    ChartCard("Did you know?", accent = Violet) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            facts.take(max).forEach { f ->
                Column {
                    Text(f.title, fontWeight = FontWeight.SemiBold)
                    Muted(f.detail)
                }
            }
        }
    }
}

/** Entry to the explorer. */
@Composable
fun ExploreCard(onExplore: () -> Unit) {
    ChartCard("Explore spending", accent = Teal) {
        Muted("See what one label, payee, category, account or person cost by week, month or year, over any dates.")
        FilledTonalButton(onClick = onExplore) { Text("Explore") }
    }
}

/** Income per year with the salary change from the year before. */
@Composable
fun IncomeYearsCard(vm: FinanceViewModel) {
    val (calendar, fy) = vm.incomeYears.collectAsState().value
    val salary by vm.salaryByMonth.collectAsState()
    var financial by remember { mutableStateOf(true) }
    val years = if (financial) fy else calendar
    ChartCard("Income over the years", accent = IncomeGreen, subtitle = "Total income, salary and how it changed") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = financial, onClick = { financial = true }, label = { Text("Financial year") })
            FilterChip(selected = !financial, onClick = { financial = false }, label = { Text("Calendar year") })
        }
        if (years.isEmpty()) {
            Muted("No income yet.")
            return@ChartCard
        }
        GroupedBarChart(
            listOf(
                ChartSeries("Income", years.map { it.incomeMinor }, IncomeGreen),
                ChartSeries("Salary", years.map { it.salaryMinor }, Teal),
            ),
            years.map { if (financial) it.label.removePrefix("FY ") else it.label },
        )
        years.reversed().forEach { y ->
            Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Row {
                    Text(y.label, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(formatInr(y.incomeMinor), fontWeight = FontWeight.SemiBold)
                }
                val parts = buildList {
                    if (y.salaryMinor > 0) add("Salary ${formatInr(y.salaryMinor)} over ${y.salaryMonths} months, about ${formatInr(y.monthlySalaryMinor)} a month")
                    y.incomeChangePct?.let { add("income ${sign(it)} vs year before") }
                }
                Muted(parts.joinToString(" · "))
                y.salaryChangePct?.let { pct ->
                    Text(
                        "Monthly salary ${sign(pct)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (pct >= 0) IncomeGreen else ExpenseRed,
                    )
                }
            }
        }
        if (salary.size >= 3) {
            Text("Salary each month", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            val step = (salary.size + 11) / 12
            val fmt = DateTimeFormatter.ofPattern("MMM yy")
            LineChart(
                listOf(ChartSeries("Salary", salary.map { it.second }, Teal)),
                salary.mapIndexed { i, (m, _) -> if ((salary.size - 1 - i) % step == 0) m.format(fmt) else "" },
            )
        }
    }
}

private fun sign(pct: Double): String = (if (pct >= 0) "up " else "down ") + "%.0f%%".format(kotlin.math.abs(pct))
