package com.myfinance.app.ui.v12

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.myfinance.app.data.BillReminderEntity
import com.myfinance.app.ui.V12ViewModel
import com.myfinance.app.ui.theme.ExpenseRed
import com.myfinance.core.BillReminders
import com.myfinance.core.BillState
import com.myfinance.core.ReminderKind
import com.myfinance.core.formatInr
import com.myfinance.core.parseAmountToMinor
import java.time.LocalDate

val ReminderKind.label: String
    get() = when (this) {
        ReminderKind.ELECTRICITY -> "Electricity"; ReminderKind.MOBILE -> "Mobile"; ReminderKind.INTERNET -> "Internet / DTH"
        ReminderKind.INSURANCE -> "Insurance premium"; ReminderKind.SCHOOL_FEES -> "School / college fees"; ReminderKind.RENT -> "Rent"
        ReminderKind.CREDIT_CARD -> "Credit card"; ReminderKind.LOAN_EMI -> "Loan EMI"; ReminderKind.SUBSCRIPTION -> "Subscription"
        ReminderKind.OTHER -> "Other"
        ReminderKind.WATER -> "Water"; ReminderKind.GAS -> "Gas / LPG"; ReminderKind.PROPERTY_TAX -> "Property tax"
        ReminderKind.VEHICLE -> "Vehicle insurance / renewal"; ReminderKind.MEMBERSHIP -> "Club / membership"
    }

private val repeatOptions = com.myfinance.core.reminderRepeats

@Composable
fun BillsScreen(vm: V12ViewModel, onBack: () -> Unit) {
    val bills by vm.bills.collectAsState()
    var editing by remember { mutableStateOf<BillReminderEntity?>(null) }
    var adding by remember { mutableStateOf(false) }

    V12Screen("Bill reminders", vm, onBack, onAdd = { adding = true }) {
        item {
            Hint12(
                "You get a notification a few days before each due date. When a matching payment SMS arrives " +
                    "(e.g. BESCOM for electricity), the bill is marked paid and moves to its next due date. Card bills " +
                    "and EMIs read from bank SMS are on the Dashboard's Bills screen.",
            )
        }
        items(bills, key = { it.first.id }) { (b, s) ->
            InfoCard {
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(b.name, style = MaterialTheme.typography.titleSmall)
                        Text(s.bill.kind.label + " · " + com.myfinance.core.repeatLabel(b.everyMonths), style = MaterialTheme.typography.bodySmall)
                    }
                    b.amountMinor?.let { Text(formatInr(it), fontWeight = FontWeight.SemiBold) }
                }
                val due = if (s.bill.done) "Paid (was due ${s.bill.nextDue.format(shortDate)})"
                    else "Due ${s.bill.nextDue.format(shortDate)} (" + com.myfinance.app.ui.screens.relativeDay(s.bill.nextDue) + ")"
                Text(due, color = if (!s.bill.done && (s.state == BillState.OVERDUE || s.state == BillState.DUE_TODAY)) ExpenseRed else MaterialTheme.colorScheme.onSurface)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!s.bill.done) TextButton(onClick = { vm.markBillPaid(b) }) { Text("Already paid") }
                    TextButton(onClick = { editing = b }) { Text("Edit") }
                    TextButton(onClick = { vm.deleteBill(b.id) }) { Text("Delete") }
                }
            }
        }
    }

    if (adding || editing != null) {
        val b = editing
        var kind by remember { mutableStateOf(b?.let { it.toBill().kind } ?: ReminderKind.ELECTRICITY) }
        var name by remember { mutableStateOf(b?.name.orEmpty()) }
        var amount by remember { mutableStateOf(b?.amountMinor?.asInput().orEmpty()) }
        var due by remember { mutableStateOf(b?.let { LocalDate.ofEpochDay(it.nextDueEpochDay) } ?: LocalDate.now().plusDays(7)) }
        var every by remember { mutableStateOf(b?.everyMonths ?: 1) }
        var remind by remember { mutableStateOf((b?.remindDaysBefore ?: 3).toString()) }
        var words by remember { mutableStateOf(b?.matchWords ?: BillReminders.defaultWords(ReminderKind.ELECTRICITY).joinToString(", ")) }
        var error by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { adding = false; editing = null },
            title = { Text(if (b == null) "New bill" else "Edit bill") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Dropdown("Type", kind, ReminderKind.entries, { it.label }, {
                        if (b == null) words = BillReminders.defaultWords(it).joinToString(", ")
                        if (name.isBlank() || ReminderKind.entries.any { k -> k.label == name }) name = it.label
                        kind = it
                    })
                    OutlinedTextField(name, { name = it; error = false }, label = { Text("Name, e.g. BESCOM home") }, isError = error && name.isBlank(), singleLine = true, modifier = Modifier.fillMaxWidth())
                    AmountField("Usual amount (₹, optional)", amount, { amount = it })
                    DateButton("Next due", due, { it?.let { d -> due = d } })
                    Dropdown("Repeats", every, repeatOptions.map { it.first }, { m -> repeatOptions.first { it.first == m }.second }, { every = it })
                    OutlinedTextField(remind, { v -> remind = v.filter { it.isDigit() }.take(2) }, label = { Text("Remind days before") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(words, { words = it }, label = { Text("Words in the payment SMS (comma separated)") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isBlank()) { error = true; return@TextButton }
                    val base = b ?: BillReminderEntity(name = "", kind = "", amountMinor = null, nextDueEpochDay = 0, everyMonths = 1, remindDaysBefore = 3, matchWords = "")
                    vm.saveBill(
                        base.copy(
                            name = name.trim(), kind = kind.name, amountMinor = parseAmountToMinor(amount)?.takeIf { it > 0 },
                            nextDueEpochDay = due.toEpochDay(), everyMonths = every, remindDaysBefore = remind.toIntOrNull() ?: 3,
                            matchWords = words,
                            // A new date makes a paid one-time reminder due again.
                            lastPaidAt = if (base.nextDueEpochDay != due.toEpochDay()) null else base.lastPaidAt,
                        ),
                    )
                    adding = false; editing = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { adding = false; editing = null }) { Text("Cancel") } },
        )
    }
}
