package com.myfinance.app.ui.v16

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.unit.dp
import com.myfinance.app.ui.FinanceViewModel
import com.myfinance.app.ui.theme.Amber
import com.myfinance.app.ui.theme.ExpenseRed
import com.myfinance.app.ui.theme.IncomeGreen
import com.myfinance.app.ui.v12.AmountField
import com.myfinance.app.ui.v12.Hint12
import com.myfinance.app.ui.v12.InfoCard
import com.myfinance.app.ui.v12.asInput
import com.myfinance.app.ui.v15.Row15
import com.myfinance.core.FamilyPayment
import com.myfinance.core.formatInr
import com.myfinance.core.parseAmountToMinor

/** What family members spent through the user's family UPI / UPI Circle, per person and month, with limits. */
@Composable
fun FamilyUpiScreen(vm: FinanceViewModel, onBack: () -> Unit, openTxn: (Long) -> Unit) {
    val s = vm.v16
    val data by s.family.collectAsState()
    val limits by s.familyLimits.collectAsState()
    var limitFor by remember { mutableStateOf<String?>(null) }
    var personFor by remember { mutableStateOf<FamilyPayment?>(null) }
    var addPerson by remember { mutableStateOf(false) }

    V16Screen("Family UPI", s, onBack) {
        item {
            Hint12(
                "Payments your wife, daughter or others made through your family UPI (UPI Circle). The app spots them from the bank " +
                    "SMS (\"UPI Circle\", \"secondary user\", \"family\") or from a label such as \"Family UPI - Priya\". Tap a payment to set who made it.",
            )
        }
        item {
            Row {
                TextButton(onClick = { s.familyMonth.value = data.month.minusMonths(1) }) { Text("‹ Previous") }
                Text(data.month.label16(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(top = 10.dp))
                TextButton(onClick = { s.familyMonth.value = data.month.plusMonths(1) }, enabled = data.month < java.time.YearMonth.now()) { Text("Next ›") }
            }
        }
        if (data.people.isEmpty()) item { InfoCard { Text("No family UPI payments this month.") } }
        items(data.people, key = { "p" + it.person }) { p ->
            InfoCard {
                Row15(p.person, "${p.count} payments" + (p.top.firstOrNull()?.let { " · most at ${it.first}" } ?: ""), formatInr(p.spentMinor), if (p.over) ExpenseRed else androidx.compose.ui.graphics.Color.Unspecified)
                p.limitMinor?.let { lim ->
                    val frac = (p.spentMinor.toFloat() / lim.coerceAtLeast(1)).coerceIn(0f, 1f)
                    LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth(), color = if (p.over) ExpenseRed else if ((p.percent ?: 0) >= 80) Amber else IncomeGreen)
                    Text(
                        if (p.over) "Over the ${formatInr(lim)} limit by ${formatInr(p.spentMinor - lim)}" else "${p.percent}% of the ${formatInr(lim)} monthly limit",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = { limitFor = p.person }) { Text(if (p.limitMinor == null) "Set a monthly limit" else "Change limit") }
            }
        }
        item { TextButton(onClick = { addPerson = true }) { Text("Add a person and limit") } }
        if (data.history.any { it.second.isNotEmpty() }) {
            item { Text("Last 6 months", style = MaterialTheme.typography.titleSmall) }
            item {
                InfoCard {
                    data.history.reversed().forEach { (m, byPerson) ->
                        Row15(m.label16(), byPerson.entries.joinToString(", ") { "${it.key} ${formatInr(it.value)}" }.ifEmpty { "None" }, formatInr(byPerson.values.sum()))
                    }
                }
            }
        }
        if (data.payments.isNotEmpty()) {
            item { Text("Payments this month", style = MaterialTheme.typography.titleSmall) }
            items(data.payments, key = { "f" + it.uid }) { pay ->
                InfoCard {
                    Row15(pay.merchant ?: "Payment", "${pay.person} · ${pay.timestamp.day16()}", formatInr(pay.amountMinor)) { personFor = pay }
                }
            }
        }
    }

    limitFor?.let { person ->
        var amt by remember { mutableStateOf(limits[person]?.asInput().orEmpty()) }
        AlertDialog(
            onDismissRequest = { limitFor = null },
            title = { Text("Monthly limit for $person") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AmountField("Limit a month (₹)", amt, { amt = it })
                    Hint12("You get a note in the Review inbox when the month's spending goes over it. Leave empty to remove the limit.")
                }
            },
            confirmButton = { TextButton(onClick = { s.setLimit(person, parseAmountToMinor(amt)); limitFor = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { limitFor = null }) { Text("Cancel") } },
        )
    }

    if (addPerson) {
        var name by remember { mutableStateOf("") }
        var amt by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addPerson = false },
            title = { Text("Add a person") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name, e.g. Priya") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    AmountField("Limit a month (₹)", amt, { amt = it })
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank() && parseAmountToMinor(amt) != null, onClick = { s.setLimit(name, parseAmountToMinor(amt)); addPerson = false }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { addPerson = false }) { Text("Cancel") } },
        )
    }

    personFor?.let { pay ->
        val people = (data.people.map { it.person } + limits.keys).distinct()
        var name by remember { mutableStateOf(pay.person.takeIf { it != com.myfinance.core.FamilyUpi.DEFAULT_PERSON }.orEmpty()) }
        AlertDialog(
            onDismissRequest = { personFor = null },
            title = { Text("Who made this payment?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${pay.merchant ?: "Payment"} · ${formatInr(pay.amountMinor)} · ${pay.timestamp.day16()}", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row { people.filter { it != com.myfinance.core.FamilyUpi.DEFAULT_PERSON }.take(4).forEach { p -> TextButton(onClick = { name = p }) { Text(p) } } }
                }
            },
            confirmButton = { TextButton(onClick = { s.setPerson(pay.uid, name); personFor = null }) { Text("Save") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { personFor = null; openTxn(pay.id) }) { Text("Open") }
                    TextButton(onClick = { personFor = null }) { Text("Cancel") }
                }
            },
        )
    }
}

