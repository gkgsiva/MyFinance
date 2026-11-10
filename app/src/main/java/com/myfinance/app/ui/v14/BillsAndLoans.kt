package com.myfinance.app.ui.v14

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.myfinance.app.ui.FinanceViewModel
import com.myfinance.app.ui.LoanRow
import com.myfinance.app.ui.charts.ChartCard
import com.myfinance.app.ui.theme.Amber
import com.myfinance.app.ui.theme.ExpenseRed
import com.myfinance.app.ui.theme.IncomeGreen
import com.myfinance.app.ui.theme.Indigo
import com.myfinance.app.ui.theme.Teal
import com.myfinance.app.ui.v12.AmountField
import com.myfinance.app.ui.v13.Muted
import com.myfinance.app.ui.v13.SimpleScreen
import com.myfinance.core.CardBillCheck
import com.myfinance.core.CardBillStatus
import com.myfinance.core.CardBills
import com.myfinance.core.LoanPayoff
import com.myfinance.core.formatInr
import com.myfinance.core.parseAmountToMinor
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val dayFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")
private val monthFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM yyyy")

private fun statusColor(s: CardBillStatus): Color = when (s) {
    CardBillStatus.PAID, CardBillStatus.MARKED_PAID -> IncomeGreen
    CardBillStatus.NOT_DUE -> Indigo
    CardBillStatus.PAID_LATE, CardBillStatus.MIN_ONLY -> Amber
    CardBillStatus.PARTLY_PAID, CardBillStatus.UNPAID -> ExpenseRed
}

/** Card bill check: every credit card statement against what was paid, flagging gaps and late payments. */
@Composable
fun CardBillsScreen(vm: FinanceViewModel, onBack: () -> Unit, onImport: () -> Unit) {
    val checks by vm.v14.cardChecks.collectAsState()
    var card by remember { mutableStateOf<String?>(null) }
    var onlyProblems by remember { mutableStateOf(false) }
    val cards = checks.map { it.bill.title }.distinct().sorted()
    val shown = checks.filter { (card == null || it.bill.title == card) && (!onlyProblems || it.needsAttention) }

    SimpleScreen(title = "Card bill check", onBack = onBack) {
        item {
            Muted(
                "Each credit card bill (from the statement SMS or an imported card statement) compared with the payments " +
                    "credited to that card. Paid by the due date counts as on time.",
            )
        }
        if (checks.isEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("No card bills yet", style = MaterialTheme.typography.titleSmall)
                        Muted("Bills appear when your bank sends a card statement SMS, or when you import a credit card statement PDF.")
                        TextButton(onClick = onImport) { Text("Import card statements") }
                    }
                }
            }
            return@SimpleScreen
        }
        item {
            val summaries = CardBills.summaries(checks)
            ChartCard("Summary", accent = Indigo) {
                summaries.forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.card, fontWeight = FontWeight.SemiBold)
                            Muted(
                                "${s.bills} bills" +
                                    (if (s.late > 0) " · ${s.late} paid late" else "") +
                                    (if (s.short > 0) " · ${s.short} not paid in full" else " · all paid in full"),
                            )
                        }
                        if (s.totalGapMinor > 0) Text(formatInr(s.totalGapMinor) + " short", color = ExpenseRed, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        item {
            com.myfinance.app.ui.WrapRow(Modifier, 8.dp) {
                FilterChip(selected = onlyProblems, onClick = { onlyProblems = !onlyProblems }, label = { Text("Only problems") })
                FilterChip(selected = card == null, onClick = { card = null }, label = { Text("All cards") })
                cards.forEach { c -> FilterChip(selected = card == c, onClick = { card = c }, label = { Text(c) }) }
            }
        }
        if (shown.isEmpty()) item { Muted("Nothing to show. Every bill here was paid in full and on time.") }
        items(shown, key = { "${it.bill.accountTail}|${it.bill.dueDate}" }) { c -> CardBillRow(c) }
    }
}

@Composable
private fun CardBillRow(c: CardBillCheck) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(c.bill.title, fontWeight = FontWeight.SemiBold)
                    Muted("Due ${c.bill.dueDate.format(dayFmt)}")
                }
                Text(c.status.label, color = statusColor(c.status), fontWeight = FontWeight.SemiBold)
            }
            Row {
                Text("Bill ${formatInr(c.bill.amountMinor)}", modifier = Modifier.weight(1f))
                Text("Paid ${formatInr(c.paidMinor)}")
            }
            c.bill.minDueMinor?.let { Muted("Minimum due ${formatInr(it)}") }
            if (c.paidLateMinor > 0) Muted("${formatInr(c.paidLateMinor)} paid after the due date" + (c.lastPayment?.let { ", last on ${it.format(dayFmt)}" } ?: ""))
            if (c.gapMinor > 0 && c.status != CardBillStatus.NOT_DUE) {
                Text(
                    "${formatInr(c.gapMinor)} not paid" + (if (c.daysLate > 0) ", ${c.daysLate} days past due. Interest and late fees may apply." else "."),
                    color = ExpenseRed,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (c.status == CardBillStatus.NOT_DUE) {
                val days = ChronoUnit.DAYS.between(LocalDate.now(), c.bill.dueDate)
                Muted("Due in $days days.")
            }
            if (c.status == CardBillStatus.MARKED_PAID) Muted("You marked this paid; no payment to the card was found in SMS or statements.")
        }
    }
}

/** EMI end dates and loan payoff: when each EMI finishes, interest paid and left, and what a prepayment saves. */
@Composable
fun LoansScreen(vm: FinanceViewModel, onBack: () -> Unit, onOpenAutoDebits: () -> Unit) {
    val loans by vm.v14.loans.collectAsState()
    val ends by vm.v14.emiEnds.collectAsState()
    var open by remember { mutableStateOf<LoanRow?>(null) }

    SimpleScreen(title = "EMIs & loan payoff", onBack = onBack) {
        if (ends.isNotEmpty()) {
            item {
                ChartCard("When your EMIs end", accent = Teal) {
                    ends.forEach { e ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(e.name, fontWeight = FontWeight.SemiBold)
                                Muted("${e.instalmentsLeft} left · ${formatInr(e.totalLeftMinor)} still to pay")
                            }
                            Text(e.endDate.format(monthFmt), fontWeight = FontWeight.SemiBold)
                        }
                    }
                    val freed = LoanPayoff.freedByMonth(ends)
                    if (freed.isNotEmpty()) {
                        var running = 0L
                        Text("Money freed up each month", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
                        freed.forEach { (m, v) ->
                            running += v
                            Muted("From ${m.format(monthFmt)}: ${formatInr(v)} a month more (${formatInr(running)} in total)")
                        }
                    }
                }
            }
        }
        item {
            Muted(
                "Tap a loan and enter its interest rate to see the interest paid so far, what is left, and how much a part-prepayment saves. " +
                    "Add an end date to an EMI in Auto debits & EMIs to see when it finishes.",
            )
        }
        if (loans.isEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("No EMIs yet", style = MaterialTheme.typography.titleSmall)
                        Muted("Add your loan EMIs with their last instalment date, or confirm ones found in your SMS.")
                        TextButton(onClick = onOpenAutoDebits) { Text("Open Auto debits & EMIs") }
                    }
                }
            }
        }
        items(loans, key = { it.item.key }) { l ->
            Card(Modifier.fillMaxWidth().clickable { open = l }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row {
                        Text(l.item.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(formatInr(l.item.amountMinor) + " / " + l.item.cadenceLabel.lowercase())
                    }
                    Muted(
                        when {
                            l.item.endDate != null && l.item.remaining != null -> "Ends ${l.item.endDate?.format(monthFmt)} · ${l.item.remaining} instalments left" +
                                (l.ratePct?.let { " · ${"%.2f".format(it)}% interest" } ?: "")
                            l.item.planId == null -> "Found in SMS. Add it to your list with an end date to plan the payoff."
                            else -> "No end date yet. Edit it in Auto debits & EMIs."
                        },
                    )
                }
            }
        }
        item { TextButton(onClick = onOpenAutoDebits) { Text("Add or edit EMIs") } }
    }

    open?.let { l -> LoanDialog(vm, l, onOpenAutoDebits) { open = null } }
}

@Composable
private fun LoanDialog(vm: FinanceViewModel, l: LoanRow, onOpenAutoDebits: () -> Unit, onDismiss: () -> Unit) {
    val item = l.item
    var rateText by remember(l) { mutableStateOf(l.ratePct?.let { "%.2f".format(it).trimEnd('0').trimEnd('.') }.orEmpty()) }
    var prepayText by remember { mutableStateOf("") }
    val rate = rateText.replace(',', '.').toDoubleOrNull()?.takeIf { it in 0.0..60.0 }
    val left = item.remaining
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("EMI ${formatInr(item.amountMinor)} · ${item.cadenceLabel}")
                if (left == null || item.planId == null) {
                    Muted("This loan needs its last instalment date. Add it (or confirm it from SMS) in Auto debits & EMIs.")
                    return@Column
                }
                Text("Ends ${item.endDate?.format(monthFmt)} · $left instalments left · ${formatInr(item.amountMinor * left)} to go")
                OutlinedTextField(
                    value = rateText,
                    onValueChange = { v -> rateText = v.filter { it.isDigit() || it == '.' || it == ',' }.take(6) },
                    label = { Text("Interest rate (% a year)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                if (rate == null) {
                    Muted("Find the rate on your loan statement or the lender's app, e.g. 8.5 for a home loan.")
                    return@Column
                }
                val months = item.everyMonths.coerceAtLeast(1)
                val yearly = rate * months // one "instalment period" may be several months
                val s = LoanPayoff.status(item.amountMinor, yearly, left, item.occurrences, item.endDate)
                Text("Still owed (principal): ${formatInr(s.outstandingMinor)}", fontWeight = FontWeight.SemiBold)
                Muted("Interest still to pay: ${formatInr(s.interestLeftMinor)}")
                if (s.instalmentsPaid > 0) Muted("Paid so far: ${s.instalmentsPaid} EMIs, about ${formatInr(s.interestPaidMinor)} of it interest")
                AmountField("Prepay now (₹)", prepayText, { prepayText = it })
                val prepay = parseAmountToMinor(prepayText)?.takeIf { it > 0 }
                if (prepay != null) {
                    val p = LoanPayoff.prepay(s, prepay.coerceAtMost(s.outstandingMinor), months)
                    Text("Keep the EMI: finish ${p.monthsSaved} months sooner" + (p.newEndDate?.let { " (${it.format(monthFmt)})" } ?: ""), color = IncomeGreen)
                    Muted("Saves about ${formatInr(p.interestSavedMinor)} in interest.")
                    Text("Or keep the end date: EMI drops to ${formatInr(p.reducedEmiMinor)}", color = Teal)
                    Muted("Saves about ${formatInr(p.interestSavedReducedEmiMinor)} in interest.")
                    Muted("Estimates. Banks may charge a prepayment fee on some loans.")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val id = item.planId
                if (id != null && rate != null && rate != l.ratePct) vm.v14.setLoanRate(id, rate)
                onDismiss()
            }) { Text(if (rate != null && rate != l.ratePct && item.planId != null) "Save rate" else "Close") }
        },
        dismissButton = {
            if (item.planId == null || item.remaining == null) TextButton(onClick = { onDismiss(); onOpenAutoDebits() }) { Text("Auto debits & EMIs") }
        },
    )
}
