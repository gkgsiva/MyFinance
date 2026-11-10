package com.myfinance.app.ui.v19

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.myfinance.app.ui.FinanceViewModel
import com.myfinance.app.ui.charts.ChartCard
import com.myfinance.app.ui.charts.ChartSeries
import com.myfinance.app.ui.charts.DonutChart
import com.myfinance.app.ui.charts.HorizontalBars
import com.myfinance.app.ui.charts.LineChart
import com.myfinance.app.ui.charts.Slice
import com.myfinance.app.ui.charts.compactInr
import com.myfinance.app.ui.theme.ChartPalette
import com.myfinance.app.ui.theme.ExpenseRed
import com.myfinance.app.ui.theme.IncomeGreen
import com.myfinance.app.ui.v13.Muted
import com.myfinance.app.ui.v13.SimpleScreen
import com.myfinance.core.AccountBalance
import com.myfinance.core.Instrument
import com.myfinance.core.SavingsTrend
import com.myfinance.core.TxnType
import com.myfinance.core.formatInr
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val monthFmt = DateTimeFormatter.ofPattern("MMMM yyyy")
private val shortMonth = DateTimeFormatter.ofPattern("MMM")
private val dayFmt = DateTimeFormatter.ofPattern("d MMM")
private fun Long.day(): String = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).format(dayFmt)

/** The month the screen is about, with arrows to move between months. */
private fun LazyListScope.monthPicker(vm: FinanceViewModel) {
    item {
        val month by vm.month.collectAsState()
        val current by vm.currentMonth.collectAsState()
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.previousMonth() }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous month") }
            Text(month.format(monthFmt), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { vm.nextMonth() }, enabled = month < current) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next month") }
        }
    }
}

@Composable
private fun Line(label: String, value: String, color: Color = Color.Unspecified, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = color, fontWeight = if (bold) FontWeight.SemiBold else null)
    }
}

/** A thin bar showing [pct] (0..100) of a track. */
@Composable
private fun UseBar(pct: Int) {
    val color = when { pct >= 70 -> ExpenseRed; pct >= 30 -> Color(0xFFF59E0B); else -> IncomeGreen }
    Box(Modifier.fillMaxWidth().height(8.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))) {
        Box(Modifier.fillMaxWidth(pct.coerceIn(0, 100) / 100f).height(8.dp).background(color, RoundedCornerShape(4.dp)))
    }
}

// ---- Balances and card use ----

@Composable
fun BalancesScreen(vm: FinanceViewModel, onBack: () -> Unit, open: (String) -> Unit) {
    val o by vm.v19.balancesOverview.collectAsState()
    SimpleScreen("Balances & card use", onBack) {
        item {
            Muted("The newest balance each bank SMS gave, and how much of each card's limit is used. Add your cards' limits under My cards & accounts.")
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Line("In your bank accounts", formatInr(o.bankTotalMinor), IncomeGreen, bold = true)
                    if (o.cards.isNotEmpty()) Line("Used on your cards", formatInr(o.cardUsedMinor), ExpenseRed, bold = true)
                    o.utilisationPct?.let { p ->
                        Line("Card use", "$p% of ${formatInr(o.cardLimitMinor)}")
                        UseBar(p)
                        if (p >= 30) Muted("Keeping card use under 30% of the limit helps your credit score.")
                    }
                }
            }
        }
        item { Text("Bank accounts", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
        if (o.banks.isEmpty()) item { Muted("No balance seen yet. Bank SMS that say \"Avl bal\" fill this in.") }
        o.banks.forEach { b -> item { BalanceCard(b) } }
        item { Text("Credit cards", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
        if (o.cards.isEmpty()) item { Muted("No cards yet. Add them under My cards & accounts.") }
        o.cards.forEach { c -> item { BalanceCard(c) } }
        item {
            com.myfinance.app.ui.WrapRow(Modifier, 8.dp) {
                OutlinedButton(onClick = { open("my-accounts") }) { Text("My cards & accounts") }
                OutlinedButton(onClick = { open("networth") }) { Text("Net worth") }
            }
        }
    }
}

@Composable
private fun BalanceCard(a: AccountBalance) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(a.label, fontWeight = FontWeight.SemiBold)
                    Muted(listOfNotNull(a.owner, a.asOf?.let { "as of ${it.day()}" }).joinToString(" · ").ifEmpty { if (a.amountMinor == null) "No balance SMS yet" else "" })
                }
                if (!a.isCard) Text(a.amountMinor?.let { formatInr(it) } ?: "—", fontWeight = FontWeight.SemiBold)
            }
            if (a.isCard) {
                a.amountMinor?.let { Line("Available", formatInr(it)) }
                a.usedMinor?.let { Line(if (a.limitMinor != null && a.amountMinor != null) "Used" else "Spent this month", formatInr(it), ExpenseRed) }
                a.limitMinor?.let { Line("Limit", formatInr(it)) }
                a.utilisationPct?.let { UseBar(it); Muted("$it% of the limit used") }
            }
        }
    }
}

// ---- This month against last month ----

@Composable
fun CompareScreen(vm: FinanceViewModel, onBack: () -> Unit, onOpenCategory: (String) -> Unit) {
    val c by vm.v19.compare.collectAsState()
    SimpleScreen("This month vs last", onBack) {
        monthPicker(vm)
        val m = c ?: return@SimpleScreen
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Line("Spent", formatInr(m.spentMinor), ExpenseRed, bold = true)
                    if (m.dayReached != null) {
                        Line("Last month by day ${m.dayReached}", formatInr(m.lastSameDayMinor))
                        Line("All of last month", formatInr(m.lastSpentMinor))
                    } else {
                        Line("Last month", formatInr(m.lastSpentMinor))
                    }
                    val base = if (m.dayReached != null) m.lastSameDayMinor else m.lastSpentMinor
                    val diff = m.spentMinor - base
                    if (base > 0) Text(
                        if (diff > 0) "${formatInr(diff)} more (${diff * 100 / base}%)" else "${formatInr(-diff)} less (${-diff * 100 / base}%)",
                        color = if (diff > 0) ExpenseRed else IncomeGreen, fontWeight = FontWeight.SemiBold,
                    )
                    Line("Received", formatInr(m.receivedMinor), IncomeGreen)
                    Line("Received last month", formatInr(m.lastReceivedMinor))
                }
            }
        }
        item { Text("By category", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
        if (m.categories.isEmpty()) item { Muted("No spending in either month.") }
        m.categories.forEach { ch ->
            item(key = ch.category) {
                Card(Modifier.fillMaxWidth().clickable { onOpenCategory(ch.category) }) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(ch.category, fontWeight = FontWeight.SemiBold)
                            Muted("${formatInr(ch.thisMinor)} now · ${formatInr(ch.lastMinor)} last month")
                        }
                        Text(
                            (if (ch.changeMinor > 0) "+" else if (ch.changeMinor < 0) "−" else "") + formatInr(kotlin.math.abs(ch.changeMinor)),
                            color = if (ch.changeMinor > 0) ExpenseRed else IncomeGreen, fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

// ---- Fixed and flexible, and how you paid ----

@Composable
fun SpendTypesScreen(vm: FinanceViewModel, onBack: () -> Unit) {
    val ff by vm.v19.fixedFlexible.collectAsState()
    val modes by vm.v19.modes.collectAsState()
    val period by vm.dashPeriod.collectAsState()
    SimpleScreen("Fixed vs flexible", onBack) {
        monthPicker(vm)
        ff?.let { f ->
            item {
                ChartCard("Fixed and flexible spending", subtitle = "Fixed: rent, EMIs, bills, fees, investments and payments that repeat") {
                    Line("Fixed", formatInr(f.fixedMinor), bold = true)
                    Line("Flexible (you can cut these)", formatInr(f.flexibleMinor), bold = true)
                    UseBar(f.fixedPct)
                    Muted("${f.fixedPct}% of this month's spending is fixed.")
                }
            }
            if (f.flexible.isNotEmpty()) item {
                ChartCard("Flexible, by category") {
                    HorizontalBars(f.flexible.take(8).mapIndexed { i, g -> Slice(g.key, g.totalMinor, ChartPalette[i % ChartPalette.size]) }, valueText = { compactInr(it.value) })
                }
            }
            if (f.fixed.isNotEmpty()) item {
                ChartCard("Fixed, by category") {
                    HorizontalBars(f.fixed.take(8).mapIndexed { i, g -> Slice(g.key, g.totalMinor, ChartPalette[(i + 3) % ChartPalette.size]) }, valueText = { compactInr(it.value) })
                }
            }
        }
        if (modes.isNotEmpty()) item {
            ChartCard("Cash, UPI and card", subtitle = period.label) {
                DonutChart(modes.mapIndexed { i, m -> Slice(m.mode.label, m.totalMinor, ChartPalette[i % ChartPalette.size]) }, centerTitle = "Spent")
            }
        }
    }
}

// ---- Savings rate ----

@Composable
fun SavingsScreen(vm: FinanceViewModel, onBack: () -> Unit) {
    val points by vm.v19.savings.collectAsState()
    SimpleScreen("Savings rate", onBack) {
        item { Muted("The share of the money you received that you didn't spend, month by month. Transfers between your accounts and card bill payments are not counted.") }
        SavingsTrend.averagePct(points)?.let { avg ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("$avg%", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = if (avg >= 0) IncomeGreen else ExpenseRed)
                        Muted("Average over the months with income")
                    }
                }
            }
        }
        if (points.any { it.ratePct != null }) item {
            ChartCard("Savings rate trend", subtitle = "Last 12 months") {
                LineChart(
                    listOf(ChartSeries("Saved %", points.map { (it.ratePct ?: 0).coerceAtLeast(0).toLong() }, IncomeGreen)),
                    points.mapIndexed { i, p -> if ((points.size - 1 - i) % 2 == 0) p.month.format(shortMonth) else "" },
                    height = 150, fill = true, showZero = true,
                )
            }
        }
        points.reversed().forEach { p ->
            item(key = p.month.toString()) {
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.month.format(monthFmt), fontWeight = FontWeight.SemiBold)
                            Muted("Received ${formatInr(p.receivedMinor)} · Spent ${formatInr(p.spentMinor)}")
                        }
                        Text(p.ratePct?.let { "$it%" } ?: "—", fontWeight = FontWeight.SemiBold, color = if ((p.ratePct ?: 0) >= 0) IncomeGreen else ExpenseRed)
                    }
                }
            }
        }
    }
}

// ---- Family summary and weekly digest ----

@Composable
fun FamilySummaryScreen(vm: FinanceViewModel, onBack: () -> Unit) {
    val text by vm.v19.familySummary.collectAsState()
    val weekly by vm.v19.weeklyDigest.collectAsState()
    val context = LocalContext.current
    fun share(whatsApp: Boolean) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        val viaWhatsApp = Intent(send).setPackage("com.whatsapp")
        runCatching {
            if (whatsApp && viaWhatsApp.resolveActivity(context.packageManager) != null) context.startActivity(viaWhatsApp)
            else context.startActivity(Intent.createChooser(send, "Share summary"))
        }
    }
    SimpleScreen("Family summary", onBack) {
        monthPicker(vm)
        item { Muted("A short summary of the month to send to the family group: totals, each person's spending and the top categories. Amounts only, no account numbers.") }
        item {
            Card(Modifier.fillMaxWidth()) { Text(text.replace("*", ""), Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium) }
        }
        item {
            com.myfinance.app.ui.WrapRow(Modifier, 8.dp) {
                Button(onClick = { share(true) }) { Text("Share on WhatsApp") }
                TextButton(onClick = { share(false) }) { Text("Other apps") }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Weekly digest", fontWeight = FontWeight.SemiBold)
                        Muted("Every Monday: what you spent last week against the week before, where most of it went, and what is due this week.")
                    }
                    Switch(checked = weekly, onCheckedChange = { vm.v19.setWeeklyDigest(it) })
                }
            }
        }
    }
}

// ---- Meal card and FASTag ----

@Composable
fun InstrumentScreen(vm: FinanceViewModel, which: Instrument, onBack: () -> Unit, openTxn: (Long) -> Unit) {
    val all by vm.v19.instruments.collectAsState()
    val v = all[which]
    SimpleScreen(which.label, onBack) {
        if (v == null) {
            item { Muted("No ${which.label} messages yet. Spends and recharges show here once their SMS arrive.") }
            return@SimpleScreen
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    v.balanceMinor?.let { Line("Balance (newest SMS)", formatInr(it), IncomeGreen, bold = true) }
                    Line("Spent in ${YearMonth.now().format(monthFmt)}", formatInr(v.spentThisMonthMinor), ExpenseRed)
                    if (v.loadedThisMonthMinor > 0) Line("Loaded / recharged", formatInr(v.loadedThisMonthMinor), IncomeGreen)
                }
            }
        }
        if (v.byPlace.isNotEmpty()) item {
            ChartCard(if (which == Instrument.FASTAG) "Tolls and parking this month" else "Where you used it this month") {
                HorizontalBars(v.byPlace.take(8).mapIndexed { i, g -> Slice(g.key, g.totalMinor, ChartPalette[i % ChartPalette.size]) }, valueText = { compactInr(it.value) })
            }
        }
        item { Text("All entries", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
        v.entries.take(200).forEach { e ->
            item(key = e.txn.id) {
                Card(Modifier.fillMaxWidth().clickable { openTxn(e.txn.id) }) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(e.txn.merchant ?: which.label, fontWeight = FontWeight.SemiBold)
                            Muted(listOfNotNull(e.txn.timestamp.day(), e.balanceMinor?.let { "bal ${formatInr(it)}" }).joinToString(" · "))
                        }
                        val credit = e.txn.type == TxnType.CREDIT
                        Text((if (credit) "+" else "") + formatInr(e.txn.amountMinor), color = if (credit) IncomeGreen else ExpenseRed, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
