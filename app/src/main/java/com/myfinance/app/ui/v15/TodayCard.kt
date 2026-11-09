package com.myfinance.app.ui.v15

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.myfinance.app.ui.FinanceViewModel
import com.myfinance.app.ui.theme.Amber
import com.myfinance.app.ui.theme.ExpenseRed
import com.myfinance.app.ui.theme.IncomeGreen
import com.myfinance.app.ui.v12.AmountField
import com.myfinance.app.ui.v12.DateButton
import com.myfinance.app.ui.v12.Dropdown
import com.myfinance.app.ui.v12.Hint12
import com.myfinance.core.AssetKind
import com.myfinance.core.ReminderKind
import com.myfinance.core.formatInr
import com.myfinance.core.label
import com.myfinance.core.parseAmountToMinor
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val dayName = DateTimeFormatter.ofPattern("EEE d MMM")

enum class QuickAdd { PAYMENT_DUE, INVESTMENT, PROPERTY, EPF }

/** Today's spend, the week's EMIs, auto debits and dues, and quick ways to add anything. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TodayCard(vm: FinanceViewModel, open: (String) -> Unit) {
    val today by vm.v15.today.collectAsState()
    var dialog by remember { mutableStateOf<QuickAdd?>(null) }
    val now = LocalDate.now()
    val message by vm.v15.message.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(message) {
        message?.let { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show(); vm.v15.clearMessage() }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Today", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (today.payments == 0) "No spending yet" else "${today.payments} payment${if (today.payments == 1) "" else "s"}" +
                            if (today.receivedMinor > 0) " · received ${formatInr(today.receivedMinor)}" else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(formatInr(today.spentMinor), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = ExpenseRed)
            }
            Text(
                if (today.upcoming.isEmpty()) "Nothing due in the next 7 days"
                else "Due this week: ${formatInr(today.upcomingOutMinor)}",
                style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp),
            )
            today.upcoming.take(6).forEach { e ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (e.date == now) "Today" else if (e.date == now.plusDays(1)) "Tomorrow" else e.date.format(dayName),
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.32f),
                    )
                    Text(e.title, style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.weight(0.45f))
                    Text(
                        e.amountMinor?.let { (if (e.incoming) "+" else "") + formatInr(it) } ?: "",
                        style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
                        color = if (e.incoming) IncomeGreen else if (e.late) ExpenseRed else Amber,
                    )
                }
            }
            if (today.upcoming.size > 6) Text("+${today.upcoming.size - 6} more", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { open("calendar") }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("Open the cash flow calendar") }
            Text("Add", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { open("edit/0") }, label = { Text("Spend (any date)") })
                AssistChip(onClick = { open("edit-income") }, label = { Text("Income") })
                AssistChip(onClick = { dialog = QuickAdd.PAYMENT_DUE }, label = { Text("Payment due") })
                AssistChip(onClick = { dialog = QuickAdd.INVESTMENT }, label = { Text("Past investment") })
                AssistChip(onClick = { dialog = QuickAdd.PROPERTY }, label = { Text("Property") })
                AssistChip(onClick = { dialog = QuickAdd.EPF }, label = { Text("EPF") })
                AssistChip(onClick = { open("lending") }, label = { Text("Lent / borrowed") })
                AssistChip(onClick = { open("ask") }, label = { Text("Ask") })
            }
        }
    }

    dialog?.let { QuickAddDialog(vm, it) { dialog = null } }
}

private val repeatOptions = listOf(0 to "Only once", 1 to "Every month", 3 to "Every quarter", 6 to "Every 6 months", 12 to "Every year")
private val investKinds = listOf(AssetKind.FD, AssetKind.MUTUAL_FUND, AssetKind.STOCK, AssetKind.PPF, AssetKind.NPS, AssetKind.GOLD, AssetKind.RD, AssetKind.OTHER)

/** Small forms for things that aren't a transaction: a payment due later, a past investment, a property, EPF. */
@Composable
fun QuickAddDialog(vm: FinanceViewModel, what: QuickAdd, onClose: () -> Unit) {
    val s = vm.v15
    var name by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(if (what == QuickAdd.PAYMENT_DUE) LocalDate.now().plusDays(1) else LocalDate.now()) }
    var every by remember { mutableStateOf(0) }
    var billKind by remember { mutableStateOf(ReminderKind.OTHER) }
    var kind by remember { mutableStateOf(AssetKind.FD) }
    var error by remember { mutableStateOf(false) }

    val title = when (what) {
        QuickAdd.PAYMENT_DUE -> "Payment due"
        QuickAdd.INVESTMENT -> "Past investment"
        QuickAdd.PROPERTY -> "Property"
        QuickAdd.EPF -> "EPF balance"
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (what) {
                    QuickAdd.PAYMENT_DUE -> {
                        OutlinedTextField(name, { name = it; error = false }, label = { Text("What, e.g. School fees") }, isError = error && name.isBlank(), singleLine = true, modifier = Modifier.fillMaxWidth())
                        AmountField("Amount (₹, optional)", amount, { amount = it })
                        DateButton("Due on", date, { d -> d?.let { date = it } })
                        Dropdown("Repeats", every, repeatOptions.map { it.first }, { v -> repeatOptions.first { it.first == v }.second }, { every = it })
                        Dropdown("Kind", billKind, ReminderKind.entries, { it.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase) }, { billKind = it })
                        Hint12("Shows in Today, the calendar and Bill reminders, with a reminder 2 days before.")
                    }
                    QuickAdd.INVESTMENT -> {
                        OutlinedTextField(name, { name = it; error = false }, label = { Text("Name, e.g. SBI FD or Axis Bluechip") }, isError = error && name.isBlank(), singleLine = true, modifier = Modifier.fillMaxWidth())
                        Dropdown("Kind", kind, investKinds, { it.label }, { kind = it })
                        DateButton("Invested on", date, { d -> d?.let { date = it } })
                        AmountField("Amount invested (₹)", amount, { amount = it; error = false }, error)
                        AmountField("Value today (₹, optional)", second, { second = it })
                        Hint12("Adds to Net worth with its date. Investing more in the same name later adds to it.")
                    }
                    QuickAdd.PROPERTY -> {
                        OutlinedTextField(name, { name = it; error = false }, label = { Text("Name, e.g. Flat in Chennai, plot") }, isError = error && name.isBlank(), singleLine = true, modifier = Modifier.fillMaxWidth())
                        AmountField("Bought for (₹)", amount, { amount = it; error = false }, error)
                        AmountField("Value today (₹)", second, { second = it; error = false }, error)
                        Hint12("Shows under Property in Net worth. Edit the value there any time. A home loan goes under Net worth > loans.")
                    }
                    QuickAdd.EPF -> {
                        AmountField("EPF balance today (₹)", amount, { amount = it; error = false }, error)
                        AmountField("Of which you and your employer put in (₹, optional)", second, { second = it })
                        Hint12("Find it in the EPFO / UMANG app passbook. EPFO SMS (\"contribution received… passbook balance\") update it by themselves after this.")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val a = parseAmountToMinor(amount)
                val b = parseAmountToMinor(second)
                when (what) {
                    QuickAdd.PAYMENT_DUE -> { if (name.isBlank()) { error = true; return@TextButton }; s.addPaymentDue(name, a, date, every, billKind) }
                    QuickAdd.INVESTMENT -> { if (name.isBlank() || a == null || a <= 0) { error = true; return@TextButton }; s.addPastInvestment(name, kind, date, a, b) }
                    QuickAdd.PROPERTY -> { if (name.isBlank() || (a == null && b == null)) { error = true; return@TextButton }; s.addProperty(name, a ?: b ?: 0, b ?: a ?: 0) }
                    QuickAdd.EPF -> { if (a == null || a <= 0) { error = true; return@TextButton }; s.setEpf(a, b) }
                }
                onClose()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}
