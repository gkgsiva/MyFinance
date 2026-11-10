package com.myfinance.app.ui.v16

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.myfinance.app.ui.FinanceViewModel
import com.myfinance.app.ui.theme.Amber
import com.myfinance.app.ui.theme.ExpenseRed
import com.myfinance.app.ui.theme.IncomeGreen
import com.myfinance.app.ui.v12.DateButton
import com.myfinance.app.ui.v12.Hint12
import com.myfinance.app.ui.v12.InfoCard
import com.myfinance.app.ui.v15.QuickAdd
import com.myfinance.app.ui.v15.QuickAddDialog
import com.myfinance.app.ui.v15.Row15
import com.myfinance.app.ui.v15.V15Screen
import com.myfinance.core.FlowEvent
import com.myfinance.core.formatInr
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

private enum class AheadView(val label: String) { DAY("Day"), WEEK("Week ahead"), MONTH("This month"), NEXT("Next month"), D30("30 days"), YEAR("Year ahead") }

private val dayHead = DateTimeFormatter.ofPattern("EEE d MMM")

/** Payment reminders, EMIs, auto debits, card bills and renewals by day, week, month or year, with what was missed. */
@Composable
fun PaymentsAheadScreen(vm: FinanceViewModel, onBack: () -> Unit, openTxn: (Long) -> Unit, open: (String) -> Unit) {
    val s = vm.v15
    val today = LocalDate.now()
    var view by remember { mutableStateOf(AheadView.WEEK) }
    var day by remember { mutableStateOf(today) }
    var adding by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<FlowEvent?>(null) }
    val days by s.ahead.collectAsState()
    val missed by s.missed.collectAsState()
    val annual by s.annualDues.collectAsState()

    val range = when (view) {
        AheadView.DAY -> day to day
        AheadView.WEEK -> today to today.plusDays(6)
        AheadView.MONTH -> today.withDayOfMonth(1) to YearMonth.from(today).atEndOfMonth()
        AheadView.NEXT -> YearMonth.from(today).plusMonths(1).let { it.atDay(1) to it.atEndOfMonth() }
        AheadView.D30 -> today to today.plusDays(29)
        AheadView.YEAR -> today to today
    }
    LaunchedEffect(range) { s.aheadRange.value = range }

    V15Screen("Payments ahead", s, onBack, onAdd = { adding = true }) {
        item {
            com.myfinance.app.ui.WrapRow(Modifier, 6.dp) {
                AheadView.entries.forEach { v -> FilterChip(selected = view == v, onClick = { view = v }, label = { Text(v.label) }) }
            }
        }
        if (missed.isNotEmpty() && view != AheadView.YEAR) {
            item { Text("Missed? No payment seen yet", style = MaterialTheme.typography.titleSmall, color = ExpenseRed) }
            items(missed, key = { "m" + it.title + it.ref + it.date }) { e ->
                InfoCard { Row15(e.title, e.kind.label, e.amountMinor?.let { formatInr(it) } ?: "", ExpenseRed) { selected = e } }
            }
        }
        if (view == AheadView.YEAR) {
            item { Hint12("Yearly payments in the next 12 months: insurance premiums, property tax, vehicle renewal, memberships. You get a heads-up a month before each.") }
            if (annual.isEmpty()) item { InfoCard { Text("No yearly dues yet. Tap + and choose \"Every year\".") } }
            val setAside = annual.sumOf { it.monthlySetAside(today) ?: 0 }
            if (setAside > 0) item { InfoCard { Row15("Set aside each month", "So every yearly due is covered when it comes", formatInr(setAside)) } }
            items(annual, key = { "a" + it.title + it.date }) { a ->
                InfoCard {
                    Row15(a.title, "${a.source} · ${a.date.format(dayFmt16)}" + (a.monthlySetAside(today)?.let { " · ${formatInr(it)}/month to set aside" } ?: ""), a.amountMinor?.let { formatInr(it) } ?: "")
                }
            }
            return@V15Screen
        }
        if (view == AheadView.DAY) {
            item {
                Row {
                    TextButton(onClick = { day = day.minusDays(1) }) { Text("‹") }
                    DateButton("Day", day, { d -> d?.let { day = it } })
                    TextButton(onClick = { day = day.plusDays(1) }) { Text("›") }
                }
            }
        }
        val expected = days.flatMap { d -> d.events.filter { it.expected && !it.late } }
        val out = expected.filter { !it.incoming }.sumOf { it.amountMinor ?: 0 }
        val inn = expected.filter { it.incoming }.sumOf { it.amountMinor ?: 0 }
        item {
            InfoCard {
                Row15("Due", "${expected.count { !it.incoming }} payments", formatInr(out), Amber)
                if (inn > 0) Row15("Coming in", null, formatInr(inn), IncomeGreen)
                TextButton(onClick = { open("forecast") }) { Text("30-day balance forecast") }
            }
        }
        val shown = days.filter { d -> d.events.any { it.expected && !it.late } || (view == AheadView.DAY && d.events.isNotEmpty()) }
        if (shown.isEmpty()) item { InfoCard { Text("Nothing due. Tap + to add a payment reminder.") } }
        shown.forEach { d ->
            item(key = "d" + d.date) {
                Text(
                    when (d.date) { today -> "Today"; today.plusDays(1) -> "Tomorrow"; else -> d.date.format(dayHead) },
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            item(key = "e" + d.date) {
                InfoCard {
                    d.events.filter { !it.late && (it.expected || view == AheadView.DAY) }.forEach { e ->
                        Row15(
                            e.title, if (e.expected) e.kind.label else "Paid",
                            e.amountMinor?.let { (if (e.incoming) "+" else "") + formatInr(it) } ?: "",
                            if (e.incoming) IncomeGreen else if (e.expected) Color.Unspecified else Color.Gray,
                        ) { if (e.txnId != null) openTxn(e.txnId!!) else selected = e }
                    }
                }
            }
        }
    }

    if (adding) QuickAddDialog(vm, QuickAdd.PAYMENT_DUE) { adding = false }

    selected?.let { e ->
        val ref = e.ref
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(e.title) },
            text = {
                Text(
                    listOfNotNull(e.kind.label, e.amountMinor?.let { formatInr(it) }, e.date.format(dayFmt16)).joinToString(" · ") +
                        when {
                            ref?.startsWith("bill:") == true -> "\n\nAlready paid moves a repeating reminder to its next date; a one-time one is done."
                            ref?.startsWith("auto:") == true -> "\n\nThis comes from your auto debits & EMIs; it clears when the debit SMS arrives."
                            ref?.startsWith("card:") == true -> "\n\nThis card bill clears when the payment to the card is seen, or tap Already paid."
                            else -> ""
                        },
                )
            },
            confirmButton = {
                when {
                    ref?.startsWith("bill:") == true || ref?.startsWith("card:") == true ->
                        TextButton(onClick = { s.markReminderPaid(ref); selected = null }) { Text("Already paid") }
                    ref?.startsWith("auto:") == true -> TextButton(onClick = { selected = null; open("autodebits") }) { Text("Open auto debits") }
                    else -> TextButton(onClick = { selected = null }) { Text("OK") }
                }
            },
            dismissButton = {
                Row {
                    if (ref?.startsWith("card:") == true) TextButton(onClick = { selected = null; open("card-bills") }) { Text("Card bills") }
                    if (ref?.startsWith("bill:") == true) {
                        TextButton(onClick = { selected = null; open("bills") }) { Text("Edit") }
                        TextButton(onClick = { s.deleteReminder(ref); selected = null }) { Text("Delete") }
                    }
                    TextButton(onClick = { selected = null }) { Text("Close") }
                }
            },
        )
    }
}

/** Bank balance over the next 30 days after salary, EMIs, reminders and everyday spending. */
@Composable
fun ForecastScreen(vm: FinanceViewModel, onBack: () -> Unit) {
    val s = vm.v15
    val f by s.forecast.collectAsState()
    V15Screen("30-day balance forecast", s, onBack) {
        val fc = f
        if (fc == null) { item { Text("Working it out…") }; return@V15Screen }
        item {
            Hint12(
                "Starts from the latest balances in your bank SMS (${formatInr(fc.startMinor)}), adds expected salary and money coming in, " +
                    "takes off EMIs, auto debits, reminders, card bills and renewals, and about ${formatInr(fc.dailySpendMinor)} a day of everyday spending (your last 90 days).",
            )
        }
        val low = fc.low
        item {
            InfoCard {
                Row15("In 30 days", null, formatInr(fc.endMinor), if (fc.endMinor < 0) ExpenseRed else IncomeGreen)
                low?.let { Row15("Lowest", it.date.format(dayFmt16), formatInr(it.balanceMinor), if (it.balanceMinor < 0) ExpenseRed else Amber) }
                if (low != null && low.balanceMinor < 0) Text("Your balance may go below zero around ${low.date.format(dayFmt16)}. Move money in or delay a payment.", color = ExpenseRed, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (fc.startMinor == 0L) item { Hint12("No balance found in your bank SMS yet, so this starts from ₹0.") }
        items(fc.days.filter { it.items.isNotEmpty() }, key = { "f" + it.date }) { d ->
            InfoCard {
                Row15(d.date.format(dayHead), d.items.joinToString(", "), formatInr(d.balanceMinor), if (d.balanceMinor < 0) ExpenseRed else Color.Unspecified)
            }
        }
    }
}
