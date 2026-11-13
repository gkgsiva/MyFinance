package com.myfinance.app.ui.v14

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.myfinance.app.ui.CardPaymentsView
import com.myfinance.app.ui.FinanceViewModel
import com.myfinance.app.ui.charts.ChartCard
import com.myfinance.app.ui.theme.Amber
import com.myfinance.app.ui.theme.ExpenseRed
import com.myfinance.app.ui.theme.IncomeGreen
import com.myfinance.app.ui.theme.Indigo
import com.myfinance.app.ui.v13.Muted
import com.myfinance.app.ui.v13.SimpleScreen
import com.myfinance.core.CardMonth
import com.myfinance.core.CardMonthStatus
import com.myfinance.core.CardPayMatch
import com.myfinance.core.formatInr
import java.time.format.DateTimeFormatter

private val dayFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")
private val monthFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy")

private fun monthColor(s: CardMonthStatus): Color = when (s) {
    CardMonthStatus.PAID -> IncomeGreen
    CardMonthStatus.DUE, CardMonthStatus.NO_BILL -> Indigo
    CardMonthStatus.PART_PAID -> Amber
    CardMonthStatus.NOT_PAID -> ExpenseRed
}

private fun cardName(v: CardPaymentsView, tail: String?): String =
    if (tail == null) "Card not named" else v.cardNames[tail] ?: "Card ••$tail"

/**
 * Card bill payments made from the bank account, as their own list: month by month and card by card, each bank
 * debit matched with the payment received on the card, and the month's bill against what was paid.
 */
@Composable
fun CardPaymentsScreen(vm: FinanceViewModel, onBack: () -> Unit, onImport: () -> Unit, onCardBills: () -> Unit) {
    val view by vm.v14.cardPayments.collectAsState()
    var card by remember { mutableStateOf<String?>(null) }
    var allCards by remember { mutableStateOf(true) }
    var onlyProblems by remember { mutableStateOf(false) }
    var year by remember { mutableStateOf<Int?>(null) }
    var tab by remember { mutableStateOf(0) }

    val months = view.months
    val years = months.map { it.month.year }.distinct().sortedDescending()
    val pickedYear = year ?: years.firstOrNull()
    val tails = months.map { it.cardTail }.distinct().sortedBy { it ?: "~" }
    val shown = months.filter {
        (pickedYear == null || it.month.year == pickedYear) && (allCards || it.cardTail == card) && (!onlyProblems || it.needsAttention)
    }

    SimpleScreen(title = "Card bill payments", onBack = onBack) {
        item {
            Muted(
                "Credit card bills you paid from your bank account, matched every month: the debit from the bank with the " +
                    "payment received on the card. These are not counted as spending or income; the card's purchases are.",
            )
        }
        if (!view.ready) {
            item { Muted("Working it out…") }
            return@SimpleScreen
        }
        if (months.isEmpty() && view.cycles.isEmpty() && view.bankMonths.isEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("No card bill payments yet", style = MaterialTheme.typography.titleSmall)
                        Muted(
                            "Payments appear from your bank SMS and statements. Add your cards in My cards & accounts, and import " +
                                "bank and card statements so both sides can be matched.",
                        )
                        TextButton(onClick = onImport) { Text("Import statements") }
                    }
                }
            }
            return@SimpleScreen
        }

        item {
            com.myfinance.app.ui.WrapRow(Modifier, 8.dp) {
                listOf("Payments", "Card totals", "Bank entries").forEachIndexed { i, t ->
                    FilterChip(selected = tab == i, onClick = { tab = i }, label = { Text(t) })
                }
            }
        }
        if (tab == 1) { cardTotals(view); return@SimpleScreen }
        if (tab == 2) { bankEntries(view); return@SimpleScreen }
        if (months.isEmpty()) { item { Muted("No card bill payments found yet.") }; return@SimpleScreen }
        item {
            val inYear = months.filter { it.month.year == pickedYear }
            val paid = inYear.sumOf { it.paidMinor }
            val payments = inYear.flatMap { it.payments }
            val matched = payments.count { it.match == CardPayMatch.MATCHED }
            val short = inYear.filter { it.status == CardMonthStatus.PART_PAID || it.status == CardMonthStatus.NOT_PAID }
            ChartCard("$pickedYear", accent = Indigo) {
                Row {
                    Text("Paid to cards", modifier = Modifier.weight(1f))
                    Text(formatInr(paid), fontWeight = FontWeight.SemiBold)
                }
                Muted("$matched of ${payments.size} payments matched on both sides")
                if (short.isNotEmpty()) {
                    Text(
                        "${short.size} bills not paid in full (${formatInr(short.sumOf { it.shortMinor })} short)",
                        color = ExpenseRed, style = MaterialTheme.typography.bodySmall,
                    )
                }
                // Per card for the year.
                inYear.groupBy { it.cardTail }.toSortedMap(compareBy { it ?: "~" }).forEach { (t, l) ->
                    Row {
                        Text(cardName(view, t), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Text(formatInr(l.sumOf { it.paidMinor }), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item {
            com.myfinance.app.ui.WrapRow(Modifier, 8.dp) {
                years.forEach { y -> FilterChip(selected = pickedYear == y, onClick = { year = y }, label = { Text("$y") }) }
            }
        }
        item {
            com.myfinance.app.ui.WrapRow(Modifier, 8.dp) {
                FilterChip(selected = onlyProblems, onClick = { onlyProblems = !onlyProblems }, label = { Text("Needs a look") })
                FilterChip(selected = allCards, onClick = { allCards = true }, label = { Text("All cards") })
                tails.forEach { t ->
                    FilterChip(selected = !allCards && card == t, onClick = { allCards = false; card = t }, label = { Text(cardName(view, t)) })
                }
            }
        }
        if (shown.isEmpty()) item { Muted(if (onlyProblems) "Nothing needs a look. Every payment here is matched and every bill paid." else "No payments for this choice.") }

        val byMonth = shown.groupBy { it.month }
        byMonth.forEach { (m, list) ->
            item(key = "h$m") {
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(m.format(monthFmt), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(formatInr(list.sumOf { it.paidMinor }), fontWeight = FontWeight.SemiBold)
                }
            }
            items(list, key = { "${it.month}|${it.cardTail}" }) { cm -> CardMonthRow(view, cm) }
        }
        item { TextButton(onClick = onCardBills) { Text("Card bill check: each bill and its due date") } }
    }
}

@Composable
private fun CardMonthRow(view: CardPaymentsView, cm: CardMonth) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(cardName(view, cm.cardTail), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(cm.status.label, color = monthColor(cm.status), fontWeight = FontWeight.SemiBold)
            }
            Row {
                Text(cm.billMinor?.let { "Bill ${formatInr(it)}" } ?: "Bill not found", modifier = Modifier.weight(1f))
                Text("Paid ${formatInr(cm.paidMinor)}")
            }
            cm.billDue?.let { Muted("Due ${it.format(dayFmt)}") }
            if (cm.shortMinor > 0 && cm.status != CardMonthStatus.DUE) {
                Text("${formatInr(cm.shortMinor)} not paid", color = ExpenseRed, style = MaterialTheme.typography.bodySmall)
            }
            if (cm.status == CardMonthStatus.NOT_PAID) Muted("No payment found. If you paid from another account, import that statement.")
            if (cm.payments.isNotEmpty()) HorizontalDivider(Modifier.padding(vertical = 4.dp))
            cm.payments.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${p.date.format(dayFmt)} · ${formatInr(p.amountMinor)}")
                        val from = p.bankDebit?.let { view.bankNames[it.id] ?: "bank" }
                        Muted(
                            when (p.match) {
                                CardPayMatch.MATCHED -> "From $from, received on the card ${p.cardCredit?.let { java.time.Instant.ofEpochMilli(it.timestamp).atZone(java.time.ZoneId.systemDefault()).toLocalDate().format(dayFmt) }}"
                                CardPayMatch.BANK_ONLY -> "From $from. Not seen on the card yet; import the card statement for this month."
                                CardPayMatch.CARD_ONLY -> "Received on the card. No bank debit found; import the bank statement, or it was paid from elsewhere."
                            },
                        )
                    }
                    Text(
                        p.match.label,
                        color = if (p.match == CardPayMatch.MATCHED) IncomeGreen else Amber,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

private val shortFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

/** Each card's statement cycle (14th to 14th, or the card's own bill day): its entries against the statement total. */
private fun LazyListScope.cardTotals(view: CardPaymentsView) {
    item {
        Muted(
            "For each card statement (the 15th to the 14th, or the bill day set in My cards & accounts): the previous bill, minus " +
                "payments, plus spends, minus refunds should equal the statement's total due. A difference means an entry is missing " +
                "or extra here.",
        )
    }
    if (view.cycles.isEmpty()) { item { Muted("No card entries yet.") }; return }
    items(view.cycles, key = { "c${it.cardTail}|${it.to}" }) { c ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(cardName(view, c.cardTail), fontWeight = FontWeight.SemiBold)
                        Muted("${c.from.format(dayFmt)} to ${c.to.format(shortFmt)} · ${c.entries} entries")
                    }
                    val m = c.matches
                    Text(
                        when (m) { true -> "Matches"; false -> "Doesn't match"; null -> "No statement" },
                        color = when (m) { true -> IncomeGreen; false -> ExpenseRed; null -> Indigo },
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                c.previousMinor?.let { Line("Previous bill", formatInr(it)) }
                if (c.paymentsMinor > 0) Line("Payments", "− " + formatInr(c.paymentsMinor))
                Line("Spends", formatInr(c.spendsMinor))
                if (c.refundsMinor > 0) Line("Refunds", "− " + formatInr(c.refundsMinor))
                Line("Works out to", formatInr(c.computedMinor), bold = true)
                Line("Statement total", c.statementMinor?.let { formatInr(it) } ?: "Not found")
                c.differenceMinor?.takeIf { c.matches == false }?.let { d ->
                    Text(
                        if (d < 0) "${formatInr(-d)} less here than on the statement: an entry may be missing. Import this statement."
                        else "${formatInr(d)} more here than on the statement: an entry may be counted twice.",
                        color = ExpenseRed, style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (c.statementMinor == null) Muted("Import this card statement, or wait for the bill SMS, to check the total.")
                if (c.previousMinor == null && c.statementMinor != null) Muted("Previous bill not found, so only spends and refunds are compared.")
            }
        }
    }
}

/** Each bank account per month: the running balance on the entries shows whether any entry is missing. */
private fun LazyListScope.bankEntries(view: CardPaymentsView) {
    item {
        Muted(
            "Checks that every entry from your bank account is here: each day's closing balance should follow from the day " +
                "before plus money in minus money out. A gap means an entry is missing; import that month's bank statement.",
        )
    }
    if (view.bankMonths.isEmpty()) { item { Muted("No bank entries with a balance yet. Bank SMS and statements usually carry one.") }; return }
    items(view.bankMonths, key = { "b${it.accountTail}|${it.month}" }) { b ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${b.month.format(monthFmt)} · a/c ••${b.accountTail}", fontWeight = FontWeight.SemiBold)
                        Muted("${b.entries} entries")
                    }
                    Text(
                        when { !b.checkable -> "Can't check"; b.gaps.isEmpty() -> "All entries here"; else -> "${b.gaps.size} gaps" },
                        color = when { !b.checkable -> Indigo; b.gaps.isEmpty() -> IncomeGreen; else -> ExpenseRed },
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                b.gaps.forEach { g ->
                    val m = g.missingMinor
                    Muted(
                        (g.previousDate?.let { "Between ${it.format(dayFmt)} and ${g.date.format(dayFmt)}: " } ?: "${g.date.format(dayFmt)}: ") +
                            (if (m < 0) "${formatInr(-m)} went out" else "${formatInr(m)} came in") + " that isn't here.",
                    )
                }
            }
        }
    }
}

@Composable
private fun Line(label: String, value: String, bold: Boolean = false) {
    Row {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = if (bold) FontWeight.SemiBold else null)
    }
}
