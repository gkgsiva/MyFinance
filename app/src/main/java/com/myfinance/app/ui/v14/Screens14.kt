package com.myfinance.app.ui.v14

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.myfinance.app.data.SavedReportEntity
import com.myfinance.app.ui.FinanceViewModel
import com.myfinance.app.ui.PinnedReport
import com.myfinance.app.ui.charts.ChartCard
import com.myfinance.app.ui.charts.ChartSeries
import com.myfinance.app.ui.charts.GroupedBarChart
import com.myfinance.app.ui.theme.Amber
import com.myfinance.app.ui.theme.ChartPalette
import com.myfinance.app.ui.theme.Coral
import com.myfinance.app.ui.theme.ExpenseRed
import com.myfinance.app.ui.theme.HeroGradient
import com.myfinance.app.ui.theme.IncomeGreen
import com.myfinance.app.ui.theme.Indigo
import com.myfinance.app.ui.theme.Sky
import com.myfinance.app.ui.theme.Violet
import com.myfinance.app.ui.v12.AmountField
import com.myfinance.app.ui.v13.Muted
import com.myfinance.app.ui.v13.SimpleScreen
import com.myfinance.core.RefundItem
import com.myfinance.core.RefundState
import com.myfinance.core.StatementGap
import com.myfinance.core.TxnLite
import com.myfinance.core.YearInReview
import com.myfinance.core.formatInr
import com.myfinance.core.parseAmountToMinor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val dayFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")
private val monthFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy")

private fun dateOf(ts: Long) = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate()

// ---------------------------------------------------------------- Refund tracker

private fun refundColor(s: RefundState): Color = when (s) {
    RefundState.OVERDUE -> ExpenseRed
    RefundState.PARTLY -> Amber
    RefundState.WAITING -> Indigo
    RefundState.RECEIVED -> IncomeGreen
    RefundState.CLOSED -> Color.Gray
}

/** Overview nudge for refunds that are late. Shows nothing otherwise. */
@Composable
fun RefundsPrompt(vm: FinanceViewModel, onOpen: () -> Unit) {
    val items by vm.v14.refunds.collectAsState()
    val late = items.filter { it.state == RefundState.OVERDUE }
    if (late.isEmpty()) return
    ChartCard("Refunds not back yet", accent = ExpenseRed, modifier = Modifier.clickable(onClick = onOpen)) {
        late.take(3).forEach { r -> KeyValue("${r.payment.merchant ?: "Payment"} · ${r.daysWaiting} days", formatInr(r.pendingMinor)) }
        if (late.size > 3) Muted("and ${late.size - 3} more")
    }
}

/** Refund tracker: payments waiting for money back, and recent ones that might be. */
@Composable
fun RefundsScreen(vm: FinanceViewModel, onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val items by vm.v14.refunds.collectAsState()
    val candidates by vm.v14.refundCandidates.collectAsState()
    val days by vm.v14.refundOverdueDays.collectAsState()
    var adding by remember { mutableStateOf<TxnLite?>(null) }
    var selected by remember { mutableStateOf<RefundItem?>(null) }
    val open = items.filter { it.state !in setOf(RefundState.RECEIVED, RefundState.CLOSED) }
    val done = items.filter { it.state in setOf(RefundState.RECEIVED, RefundState.CLOSED) }

    SimpleScreen(title = "Refund tracker", onBack = onBack) {
        item {
            ChartCard("Waiting for", accent = Amber) {
                Text(formatInr(open.sumOf { it.pendingMinor }), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Muted("${open.size} refunds open · flagged after $days days")
                Slider(value = days.toFloat(), onValueChange = { vm.v14.setRefundOverdueDays(it.toInt()) }, valueRange = 3f..30f, steps = 26)
                Muted(
                    "Mark a cancelled ticket or a returned order as \"Waiting for refund\" (here, or on the transaction). " +
                        "Money that comes back from the same seller is matched automatically.",
                )
            }
        }
        if (open.isEmpty()) item { Muted("Nothing pending.") }
        items(open, key = { "o${it.payment.id}" }) { r -> RefundRow(r) { selected = r } }
        if (candidates.isNotEmpty()) {
            item { Text("Recent payments that may get a refund", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
            item { Muted("Tap one you cancelled or returned.") }
            items(candidates.take(15), key = { "c${it.id}" }) { t ->
                Card(Modifier.fillMaxWidth().clickable { adding = t }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.merchant ?: t.category, maxLines = 1)
                            Muted(dateOf(t.timestamp).format(dayFmt))
                        }
                        Text(formatInr(t.amountMinor), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        if (done.isNotEmpty()) {
            item { Text("Received", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
            items(done.take(30), key = { "d${it.payment.id}" }) { r -> RefundRow(r) { selected = r } }
        }
    }

    adding?.let { t ->
        var text by remember(t) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = null },
            title = { Text("Waiting for refund?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${t.merchant ?: t.category} · ${formatInr(t.amountMinor)}")
                    AmountField("Expected back (₹), empty = all of it", text, { text = it })
                    Muted("For a cancelled train ticket, the refund is usually less than the fare.")
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.v14.watchRefund(t.id, parseAmountToMinor(text)?.takeIf { it > 0 }); adding = null }) { Text("Track it") }
            },
            dismissButton = { TextButton(onClick = { adding = null }) { Text("Cancel") } },
        )
    }
    selected?.let { r ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(r.payment.merchant ?: "Refund") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Paid ${formatInr(r.payment.amountMinor)} on ${dateOf(r.payment.timestamp).format(dayFmt)}")
                    Text("Expected back ${formatInr(r.expectedMinor)}, received ${formatInr(r.receivedMinor)}")
                    r.credits.forEach { c -> Muted("${formatInr(c.amountMinor)} from ${c.merchant ?: "?"} on ${dateOf(c.timestamp).format(dayFmt)}") }
                    Text(r.state.label + if (r.state != RefundState.RECEIVED) " · ${r.daysWaiting} days" else "", color = refundColor(r.state))
                }
            },
            confirmButton = {
                Row {
                    if (r.state != RefundState.RECEIVED && r.state != RefundState.CLOSED) {
                        TextButton(onClick = { vm.v14.closeRefund(r.payment.id); selected = null }) { Text("Got it / give up") }
                    }
                    TextButton(onClick = { selected = null; onOpen(r.payment.id) }) { Text("Open") }
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.v14.stopWatching(r.payment.id); selected = null }) { Text("Stop tracking") }
            },
        )
    }
}

@Composable
private fun RefundRow(r: RefundItem, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.payment.merchant ?: r.payment.category, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
                Text(r.state.label, color = refundColor(r.state), fontWeight = FontWeight.SemiBold)
            }
            Muted(
                "Paid ${formatInr(r.payment.amountMinor)} on ${dateOf(r.payment.timestamp).format(dayFmt)}" +
                    when (r.state) {
                        RefundState.RECEIVED -> " · ${formatInr(r.receivedMinor)} back after ${r.daysWaiting} days"
                        RefundState.CLOSED -> ""
                        else -> " · ${formatInr(r.pendingMinor)} to come · ${r.daysWaiting} days"
                    },
            )
        }
    }
}

/** On the edit screen: track this payment as waiting for a refund. */
@Composable
fun RefundSwitch(vm: FinanceViewModel, uid: String, amountMinor: Long, isDebit: Boolean) {
    if (!isDebit || uid.isBlank()) return
    val watched by vm.v14.watchedUids.collectAsState()
    val on = uid in watched
    var expected by remember(uid) { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Waiting for refund")
            Muted("Cancelled or returned? Get flagged if the money doesn't come back.")
        }
        Switch(checked = on, onCheckedChange = { v ->
            if (v) vm.v14.watchRefund(uid, parseAmountToMinor(expected)?.takeIf { it in 1..amountMinor }) else vm.v14.stopWatching(uid)
        })
    }
    if (!on) AmountField("Expected back (₹), optional", expected, { expected = it })
}

// ---------------------------------------------------------------- Year in review

@Composable
fun YearReviewScreen(vm: FinanceViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val all by vm.allTransactions.collectAsState()
    val meta by vm.txnMeta.collectAsState()
    var fy by remember { mutableStateOf(false) }
    val years = remember(all, fy) { vm.v14.reviewYears(fy) }
    var year by remember(fy) { mutableStateOf<Int?>(null) }
    LaunchedEffect(years) {
        val y = year
        if (y == null || !years.contains(y)) year = years.firstOrNull { it < java.time.LocalDate.now().year || fy } ?: years.firstOrNull()
    }
    val review by produceState<YearInReview?>(null, all, meta, year, fy) {
        val y = year ?: return@produceState
        value = withContext(Dispatchers.Default) { vm.v14.yearReview(y, fy) }
    }

    SimpleScreen(title = "Year in review", onBack = onBack) {
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !fy, onClick = { fy = false }, label = { Text("Calendar year") })
                FilterChip(selected = fy, onClick = { fy = true }, label = { Text("Financial year") })
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                years.forEach { y ->
                    FilterChip(selected = year == y, onClick = { year = y }, label = { Text(if (fy) com.myfinance.core.Dates.financialYearLabel(y) else "$y") })
                }
            }
        }
        val r = review
        if (r == null || r.payments == 0) {
            item { Muted(if (years.isEmpty()) "No transactions yet." else "Nothing in this year.") }
            return@SimpleScreen
        }
        item { ReviewHero(r) }
        item {
            Button(onClick = { shareText(context, vm.v14.reviewShareText(r)) }, modifier = Modifier.fillMaxWidth()) { Text("Share on WhatsApp") }
        }
        if (r.topCategories.isNotEmpty()) item {
            ChartCard("Where it went", accent = Coral) {
                val max = r.topCategories.maxOf { it.amountMinor }.coerceAtLeast(1)
                r.topCategories.forEachIndexed { i, l ->
                    Column {
                        KeyValue("${i + 1}. ${l.name}", formatInr(l.amountMinor))
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { l.amountMinor.toFloat() / max },
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                            color = ChartPalette[i % ChartPalette.size],
                        )
                    }
                }
            }
        }
        if (r.topSellers.isNotEmpty()) item {
            ChartCard("Top sellers", accent = Violet, subtitle = "Who got the most money") {
                r.topSellers.forEachIndexed { i, l -> KeyValue("${i + 1}. ${l.name} (${l.count}×)", formatInr(l.amountMinor)) }
            }
        }
        if (r.mostVisited.isNotEmpty()) item {
            ChartCard("Most visited", accent = Sky, subtitle = "Who you paid most often") {
                r.mostVisited.forEachIndexed { i, l -> KeyValue("${i + 1}. ${l.name}", "${l.count} times") }
            }
        }
        if (r.topLabels.isNotEmpty()) item {
            ChartCard("Top labels", accent = Amber) {
                r.topLabels.forEach { l -> KeyValue("#${l.name}", formatInr(l.amountMinor)) }
            }
        }
        item {
            ChartCard("Records", accent = Indigo) {
                r.biggestMonth?.let { KeyValue("Biggest spending month", "${it.first.format(monthFmt)} · ${formatInr(it.second)}") }
                r.smallestMonth?.let { KeyValue("Lightest month", "${it.first.format(monthFmt)} · ${formatInr(it.second)}") }
                r.bestSavingMonth?.let { KeyValue("Best saving month", "${it.first.format(monthFmt)} · ${formatInr(it.second)}") }
                r.biggestPayment?.let { KeyValue("Biggest payment", "${formatInr(it.amountMinor)} · ${it.merchant ?: it.category}") }
            }
        }
        if (r.facts.isNotEmpty()) item {
            ChartCard("Fun facts", accent = Violet) {
                r.facts.forEach { Text("✨ $it") }
            }
        }
    }
}

@Composable
private fun ReviewHero(r: YearInReview) {
    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(HeroGradient)
            .padding(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(r.label, style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.85f))
            Text("Saved ${formatInr(r.savedMinor)}", style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.Bold)
            r.savedPct?.let { Text("$it% of what came in", color = Color.White.copy(alpha = 0.9f)) }
            Text("In ${formatInr(r.incomeMinor)}" + (r.incomeChangePct?.let { " (${pctText(it)})" } ?: ""), color = Color.White)
            Text("Out ${formatInr(r.spentMinor)} in ${r.payments} payments" + (r.spendChangePct?.let { " (${pctText(it)})" } ?: ""), color = Color.White)
        }
    }
}

private fun pctText(p: Double) = (if (p >= 0) "up " else "down ") + "%.0f%%".format(kotlin.math.abs(p)) + " on last year"

/** Opens WhatsApp with [text]; falls back to the share sheet. */
fun shareText(context: android.content.Context, text: String) {
    fun intent(pkg: String?) = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        pkg?.let(::setPackage)
    }
    for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
        try {
            context.startActivity(intent(pkg))
            return
        } catch (_: ActivityNotFoundException) {
        }
    }
    context.startActivity(Intent.createChooser(intent(null), "Share year in review"))
}

// ---------------------------------------------------------------- Statement reminders

@Composable
fun StatementRemindersScreen(vm: FinanceViewModel, onBack: () -> Unit, onImport: () -> Unit) {
    val day by vm.v14.statementReminderDay.collectAsState()
    val all by vm.allTransactions.collectAsState()
    val gaps by produceState<List<StatementGap>>(emptyList(), all) { value = vm.v14.statementGaps() }
    SimpleScreen(title = "Statement reminders", onBack = onBack) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Monthly reminder", style = MaterialTheme.typography.titleSmall)
                            Muted(if (day == 0) "Off" else "On the $day${suffix(day)} of each month, if a statement is missing")
                        }
                        Switch(checked = day > 0, onCheckedChange = { vm.v14.setStatementReminderDay(if (it) 5 else 0) })
                    }
                    if (day > 0) {
                        Slider(value = day.toFloat(), onValueChange = { vm.v14.setStatementReminderDay(it.toInt()) }, valueRange = 1f..28f, steps = 26)
                        Muted("Most banks email last month's statement in the first week.")
                    }
                }
            }
        }
        item { Text("Statements to import", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
        if (gaps.isEmpty()) {
            item { Muted("Up to date. Accounts and cards you imported statements for are covered up to last month.") }
        }
        items(gaps, key = { it.account }) { g ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(g.account, fontWeight = FontWeight.SemiBold)
                        Muted("Imported up to ${g.lastCovered.format(dayFmt)}. Next: ${g.nextMonth.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${g.nextMonth.year}")
                    }
                }
            }
        }
        item { FilledTonalButton(onClick = onImport) { Text("Import statements") } }
    }
}

private fun suffix(d: Int) = when {
    d in 11..13 -> "th"
    d % 10 == 1 -> "st"
    d % 10 == 2 -> "nd"
    d % 10 == 3 -> "rd"
    else -> "th"
}

// ---------------------------------------------------------------- Pinned reports

/** Pinned Explore views on the Overview. Shows nothing when none are pinned. */
@Composable
fun PinnedReportsCard(vm: FinanceViewModel, onOpen: (SavedReportEntity) -> Unit) {
    val pinned by vm.v14.pinned.collectAsState()
    if (pinned.isEmpty()) return
    ChartCard("Pinned reports", accent = Sky) {
        pinned.forEachIndexed { i, p -> PinnedRow(p, ChartPalette[i % ChartPalette.size]) { onOpen(p.report) } }
    }
}

@Composable
private fun PinnedRow(p: PinnedReport, color: Color, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.report.title, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Muted(
                    p.rangeText + when (p.report.type) {
                        null -> if (p.receivedMinor > 0 && p.spentMinor > 0) " · net of ${formatInr(p.receivedMinor)} back" else ""
                        else -> ""
                    },
                )
            }
            Text(formatInr(p.totalMinor), fontWeight = FontWeight.Bold)
        }
        if (p.recent.size > 1 && p.recent.any { it > 0 }) {
            GroupedBarChart(listOf(ChartSeries(p.report.title, p.recent, color)), p.recentLabels.map { shortPeriod(it) }, height = 60)
        }
    }
}

private fun shortPeriod(label: String): String = when {
    label.length == 7 -> runCatching { java.time.YearMonth.parse(label).month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) }.getOrDefault(label)
    label.length == 10 -> runCatching { java.time.LocalDate.parse(label).format(DateTimeFormatter.ofPattern("d MMM")) }.getOrDefault(label)
    else -> label
}

/** "Pin to Overview" from Explore, with a name for the pinned report. */
@Composable
fun PinDialog(suggested: String, onDismiss: () -> Unit, onPin: (String) -> Unit) {
    var name by remember { mutableStateOf(suggested) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pin to Dashboard") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedTextField(name, { name = it.take(40) }, label = { Text("Name") }, singleLine = true)
                Muted("Shows on the Dashboard with the same dates (a moving window like \"last 12 months\" keeps moving).")
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onPin(name.trim()) }) { Text("Pin") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
