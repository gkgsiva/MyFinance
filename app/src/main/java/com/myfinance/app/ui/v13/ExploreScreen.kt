package com.myfinance.app.ui.v13

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.myfinance.app.data.toLite
import com.myfinance.app.ui.ExploreKind
import com.myfinance.app.ui.FinanceViewModel
import com.myfinance.app.ui.charts.ChartCard
import com.myfinance.app.ui.charts.ChartSeries
import com.myfinance.app.ui.charts.GroupedBarChart
import com.myfinance.app.ui.screens.TransactionRow
import com.myfinance.app.ui.theme.ChartPalette
import com.myfinance.app.ui.v12.DateButton
import com.myfinance.app.ui.v12.Dropdown
import com.myfinance.core.Granularity
import com.myfinance.core.Insights
import com.myfinance.core.TxnType
import com.myfinance.core.formatInr
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val monthLabel: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM yy")
private val weekLabel: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")
private val weekLong: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

private fun periodText(label: String, by: Granularity, short: Boolean): String = when (by) {
    Granularity.YEAR -> label
    Granularity.MONTH -> YearMonth.parse(label).format(if (short) monthLabel else DateTimeFormatter.ofPattern("MMMM yyyy"))
    Granularity.WEEK -> LocalDate.parse(label).let { if (short) it.format(weekLabel) else "Week of " + it.format(weekLong) }
}

/** What one label, payee, category, account or person cost (or brought in) over any dates. */
@Composable
fun ExploreScreen(vm: FinanceViewModel, onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val all by vm.allTransactions.collectAsState()
    val meta by vm.txnMeta.collectAsState()
    val labels by vm.usedLabels.collectAsState()
    val payees by vm.payeeOptions.collectAsState()
    val categories by vm.categories.collectAsState()
    val options by vm.filterOptions.collectAsState()
    val owner by vm.ownerName.collectAsState()
    val colorOf = categories.associate { it.name to Color(it.color) }
    val fallback = MaterialTheme.colorScheme.outline

    var kind by remember { mutableStateOf(if (labels.isNotEmpty()) ExploreKind.LABEL else ExploreKind.PAYEE) }
    val values = when (kind) {
        ExploreKind.LABEL -> labels
        ExploreKind.PAYEE -> payees
        ExploreKind.CATEGORY -> categories.map { it.name }
        ExploreKind.ACCOUNT -> options.second
        ExploreKind.PERSON -> options.first
    }
    var value by remember(kind) { mutableStateOf(values.firstOrNull()) }
    if (value == null && values.isNotEmpty()) value = values.first()
    var type by remember { mutableStateOf(TxnType.DEBIT) }
    var by by remember { mutableStateOf(Granularity.MONTH) }
    val byYear = by == Granularity.YEAR
    val earliest = all.minOfOrNull { it.timestamp }?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }
    var from by remember { mutableStateOf(LocalDate.now().minusMonths(11).withDayOfMonth(1)) }
    var to by remember { mutableStateOf(LocalDate.now()) }

    val zone = ZoneId.systemDefault()
    val matches = remember(all, meta, kind, value, type, from, to) {
        val v = value ?: return@remember emptyList()
        val start = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        vm.explore(kind, v, type).filter { it.timestamp in start until end }.sortedByDescending { it.timestamp }
    }
    val trend = remember(matches, by, from, to) {
        Insights.trend(matches.map { it.toLite(owner) }, from, to, by, zone)
    }
    val total = matches.sumOf { it.amountMinor }
    val shown = matches.filterNot { meta.hidden(it) }

    SimpleScreen(title = "Explore spending", onBack = onBack) {
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExploreKind.entries.forEach { k -> FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k.label) }) }
            }
        }
        item {
            if (values.isEmpty()) {
                Muted(if (kind == ExploreKind.LABEL) "No labels yet. Tap a transaction to add one." else "Nothing to choose yet.")
            } else {
                Dropdown(kind.label, value ?: values.first(), values, { it }, { value = it })
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = type == TxnType.DEBIT, onClick = { type = TxnType.DEBIT }, label = { Text("Spent") })
                FilterChip(selected = type == TxnType.CREDIT, onClick = { type = TxnType.CREDIT }, label = { Text("Received") })
                Granularity.entries.forEach { g -> FilterChip(selected = by == g, onClick = { by = g }, label = { Text("By ${g.label.lowercase()}") }) }
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val now = LocalDate.now()
                listOf(
                    "3 months" to now.minusMonths(2).withDayOfMonth(1),
                    "1 year" to now.minusMonths(11).withDayOfMonth(1),
                    "3 years" to now.minusYears(3).plusMonths(1).withDayOfMonth(1),
                    "All" to (earliest ?: now.minusYears(1)),
                ).forEach { (label, start) ->
                    FilterChip(selected = from == start && to == now, onClick = { from = start; to = now }, label = { Text(label) })
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) { DateButton("From", from, { d -> d?.let { from = it } }) }
                Column(Modifier.weight(1f)) { DateButton("To", to, { d -> d?.let { to = it } }) }
            }
        }
        item {
            ChartCard(value ?: kind.label, subtitle = "${if (type == TxnType.DEBIT) "Spent" else "Received"} ${formatInr(total)} in ${matches.size} payments") {
                val periods = trend.size.coerceAtLeast(1)
                Text(
                    "Average ${formatInr(total / periods)} a ${by.label.lowercase()}" +
                        (matches.takeIf { it.isNotEmpty() }?.let { " · typical payment ${formatInr(it.map { m -> m.amountMinor }.sorted()[it.size / 2])}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (trend.isNotEmpty() && total > 0) {
                    val step = (trend.size + 11) / 12
                    val axis = trend.mapIndexed { i, p ->
                        if ((trend.size - 1 - i) % step != 0) "" else periodText(p.label, by, short = true)
                    }
                    GroupedBarChart(listOf(ChartSeries(value ?: "", trend.map { it.totalMinor }, ChartPalette[0])), axis)
                }
            }
        }
        if (trend.size <= 160) {
            items(trend.filter { it.count > 0 }.reversed(), key = { "p${it.label}" }) { p ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                    Text(periodText(p.label, by, short = false), modifier = Modifier.weight(1f))
                    Text("${p.count}×  ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatInr(p.totalMinor), fontWeight = FontWeight.SemiBold)
                }
            }
        }
        item {
            Text("Payments", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            if (shown.size < matches.size) Muted("${matches.size - shown.size} hidden payments are counted above but not listed.")
        }
        items(shown.take(500), key = { it.id }) { t ->
            TransactionRow(t, colorOf[t.category] ?: fallback, meta.labels(t), false) { onOpen(t.id) }
        }
    }
}
