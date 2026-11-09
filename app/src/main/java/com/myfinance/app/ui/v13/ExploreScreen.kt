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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.TextButton
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
import com.myfinance.app.ui.theme.IncomeGreen
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
    val nicks by vm.nicknames.collectAsState()
    val labels by vm.usedLabels.collectAsState()
    val payees by vm.payeeOptions.collectAsState()
    val categories by vm.categories.collectAsState()
    val options by vm.filterOptions.collectAsState()
    val owner by vm.ownerName.collectAsState()
    val colorOf = categories.associate { it.name to Color(it.color) }
    val fallback = MaterialTheme.colorScheme.outline

    // Opened from a pinned report on the Overview: start with its settings.
    val pin = remember { vm.v14.pendingExplore.value }
    LaunchedEffect(Unit) { vm.v14.pendingExplore.value = null }
    val pinRange = remember { pin?.let { com.myfinance.app.ui.V14State.rangeOf(it) } }
    var kind by remember {
        mutableStateOf(
            pin?.let { p -> ExploreKind.entries.firstOrNull { it.name == p.kind } }
                ?: if (labels.isNotEmpty()) ExploreKind.LABEL else ExploreKind.PAYEE,
        )
    }
    val values = when (kind) {
        ExploreKind.LABEL -> labels
        ExploreKind.PAYEE -> payees
        ExploreKind.CATEGORY -> categories.map { it.name }
        ExploreKind.ACCOUNT -> options.second
        ExploreKind.PERSON -> options.first
    }
    var value by remember(kind) { mutableStateOf(pin?.takeIf { it.kind == kind.name }?.value ?: values.firstOrNull()) }
    if (value == null && values.isNotEmpty()) value = values.first()
    // null = net: what went out minus what came back (refunds, cancellations, cashback).
    var type by remember { mutableStateOf(pin?.type?.let { t -> TxnType.entries.firstOrNull { it.name == t } }) }
    var by by remember { mutableStateOf(pin?.let { p -> Granularity.entries.firstOrNull { it.name == p.granularity } } ?: Granularity.MONTH) }
    var pinning by remember { mutableStateOf(false) }
    var pinnedId by remember { mutableStateOf(pin?.id) }
    val byYear = by == Granularity.YEAR
    val earliest = all.minOfOrNull { it.timestamp }?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }
    var from by remember { mutableStateOf(pinRange?.first?.let { f -> if (pin?.range == "all") earliest ?: f else f } ?: LocalDate.now().minusMonths(11).withDayOfMonth(1)) }
    var to by remember { mutableStateOf(pinRange?.second ?: LocalDate.now()) }

    val zone = ZoneId.systemDefault()
    val both = remember(all, meta, kind, value, from, to) {
        val v = value ?: return@remember emptyList()
        val start = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        vm.explore(kind, v, null).filter { it.timestamp in start until end }.sortedByDescending { it.timestamp }
    }
    val out = both.filter { it.type == TxnType.DEBIT }
    val back = both.filter { it.type == TxnType.CREDIT }
    val matches = when (type) { TxnType.DEBIT -> out; TxnType.CREDIT -> back; null -> both }
    val outTrend = remember(out, by, from, to) { Insights.trend(out.map { it.toLite(owner) }, from, to, by, zone) }
    val backTrend = remember(back, by, from, to) { Insights.trend(back.map { it.toLite(owner) }, from, to, by, zone) }
    val trend = if (type == TxnType.CREDIT) backTrend else outTrend
    val spent = out.sumOf { it.amountMinor }
    val received = back.sumOf { it.amountMinor }
    val total = when (type) { TxnType.DEBIT -> spent; TxnType.CREDIT -> received; null -> spent - received }
    val shown = matches.filterNot { meta.hidden(it) }

    SimpleScreen(
        title = "Explore spending",
        onBack = onBack,
        actions = {
            val id = pinnedId
            if (id != null) TextButton(onClick = { vm.v14.deleteReport(id); pinnedId = null }) { Text("Unpin") }
            else if (value != null) TextButton(onClick = { pinning = true }) { Text("Pin") }
        },
    ) {
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
                FilterChip(selected = type == null, onClick = { type = null }, label = { Text("Net") })
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
            val subtitle = when (type) {
                TxnType.DEBIT -> "Spent ${formatInr(spent)} in ${out.size} payments"
                TxnType.CREDIT -> "Received ${formatInr(received)} in ${back.size} payments"
                null -> if (spent == 0L) "Received ${formatInr(received)} in ${back.size} payments"
                else "Net ${formatInr(spent - received)}: spent ${formatInr(spent)}, got back ${formatInr(received)}"
            }
            ChartCard(value ?: kind.label, subtitle = subtitle) {
                val periods = trend.size.coerceAtLeast(1)
                if (type == null && spent > 0 && received > 0) {
                    Text(
                        "${back.size} refunds, cancellations or cashback came back (${received * 100 / spent}% of what was spent).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    "Average ${formatInr(total / periods)} a ${by.label.lowercase()}" +
                        (matches.takeIf { it.isNotEmpty() }?.let { " · typical payment ${formatInr(it.map { m -> m.amountMinor }.sorted()[it.size / 2])}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (trend.isNotEmpty() && (spent > 0 || received > 0)) {
                    val step = (trend.size + 11) / 12
                    val axis = trend.mapIndexed { i, p ->
                        if ((trend.size - 1 - i) % step != 0) "" else periodText(p.label, by, short = true)
                    }
                    val series = when (type) {
                        TxnType.DEBIT -> listOf(ChartSeries("Spent", outTrend.map { it.totalMinor }, ChartPalette[0]))
                        TxnType.CREDIT -> listOf(ChartSeries("Received", backTrend.map { it.totalMinor }, IncomeGreen))
                        null -> listOfNotNull(
                            ChartSeries("Spent", outTrend.map { it.totalMinor }, ChartPalette[0]).takeIf { spent > 0 },
                            ChartSeries("Back", backTrend.map { it.totalMinor }, IncomeGreen).takeIf { received > 0 },
                        )
                    }
                    GroupedBarChart(series, axis)
                    if (series.size > 1) Muted("Bars: spent, and money back (green).")
                }
            }
        }
        if (trend.size <= 160) {
            val rows = outTrend.indices.map { i -> Triple(outTrend[i], backTrend.getOrNull(i), i) }
                .filter { (o, b, _) -> o.count > 0 || (b?.count ?: 0) > 0 }.reversed()
            items(rows, key = { "p${it.first.label}" }) { (o, b, _) ->
                val v = when (type) {
                    TxnType.DEBIT -> o.totalMinor
                    TxnType.CREDIT -> b?.totalMinor ?: 0
                    null -> o.totalMinor - (b?.totalMinor ?: 0)
                }
                if (type == TxnType.DEBIT && o.count == 0) return@items
                if (type == TxnType.CREDIT && (b?.count ?: 0) == 0) return@items
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                    Text(periodText(o.label, by, short = false), modifier = Modifier.weight(1f))
                    if (type == null && (b?.totalMinor ?: 0) > 0) {
                        Text("−${formatInr(b!!.totalMinor)} back  ", style = MaterialTheme.typography.bodySmall, color = IncomeGreen)
                    }
                    Text(formatInr(v), fontWeight = FontWeight.SemiBold)
                }
            }
        }
        item {
            Text("Payments", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            if (shown.size < matches.size) Muted("${matches.size - shown.size} hidden payments are counted above but not listed.")
        }
        items(shown.take(500), key = { it.id }) { t ->
            TransactionRow(t, colorOf[t.category] ?: fallback, meta.labels(t), false, nicks[t.uid.substringBefore('#')]) { onOpen(t.id) }
        }
    }

    if (pinning) {
        val v = value
        if (v == null) { pinning = false; return }
        val now = LocalDate.now()
        val presets = listOf(
            "3m" to now.minusMonths(2).withDayOfMonth(1),
            "1y" to now.minusMonths(11).withDayOfMonth(1),
            "3y" to now.minusYears(3).plusMonths(1).withDayOfMonth(1),
            "all" to (earliest ?: now.minusYears(1)),
        )
        val preset = presets.firstOrNull { it.second == from && to == now }?.first
        val suggested = v + when (type) { TxnType.DEBIT -> " spent"; TxnType.CREDIT -> " received"; null -> " net" } +
            " by " + by.label.lowercase()
        com.myfinance.app.ui.v14.PinDialog(suggested, onDismiss = { pinning = false }) { name ->
            vm.v14.saveReport(
                com.myfinance.app.data.SavedReportEntity(
                    id = java.util.UUID.randomUUID().toString(), title = name, kind = kind.name, value = v, type = type?.name,
                    granularity = by.name, range = preset ?: "custom",
                    fromEpochDay = if (preset == null) from.toEpochDay() else null,
                    toEpochDay = if (preset == null) to.toEpochDay() else null,
                    createdAt = System.currentTimeMillis(),
                ),
            )
            pinning = false
        }
    }
}
