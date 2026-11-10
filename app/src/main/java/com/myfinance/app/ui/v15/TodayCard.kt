package com.myfinance.app.ui.v15

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextOverflow
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
private val timeOfDay = DateTimeFormatter.ofPattern("h:mm a")

enum class QuickAdd { PAYMENT_DUE, INVESTMENT, PROPERTY, EPF }

/**
 * The top of Overview, compact: today's transactions listed, then what is due today and over the next
 * few days (and anything missed), then one-line alerts. Adding things lives in the + button menu.
 */
@Composable
fun TodayCard(vm: FinanceViewModel, open: (String) -> Unit) {
    val today by vm.v15.today.collectAsState()
    val all by vm.allTransactions.collectAsState()
    val nicks by vm.nicknames.collectAsState()
    val now = LocalDate.now()
    val message by vm.v15.message.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(message) {
        message?.let { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show(); vm.v15.clearMessage() }
    }
    val zone = java.time.ZoneId.systemDefault()
    val todays = remember(all, now) {
        all.filter { !it.excluded && com.myfinance.core.Dates.localDate(it.timestamp, zone) == now }.sortedByDescending { it.timestamp }
    }
    var showAllToday by remember { mutableStateOf(false) }
    val small = MaterialTheme.typography.bodySmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // Today: total, then each transaction.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Today", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("  " + now.format(dayName), style = small, color = muted, modifier = Modifier.weight(1f))
                if (today.receivedMinor > 0) Text("+" + formatInr(today.receivedMinor) + "  ", style = small, color = IncomeGreen, fontWeight = FontWeight.SemiBold)
                Text(formatInr(today.spentMinor), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = ExpenseRed)
            }
            if (todays.isEmpty()) Text("No transactions yet today", style = small, color = muted)
            val shown = if (showAllToday) todays else todays.take(5)
            shown.forEach { t ->
                val name = nicks[t.uid.substringBefore('#')] ?: t.merchant ?: if (t.type == com.myfinance.core.TxnType.CREDIT) "Money received" else "Payment"
                Row(
                    Modifier.fillMaxWidth().clickable { open("edit/${com.myfinance.app.ui.SplitView.originalId(t.id)}") }.padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        java.time.Instant.ofEpochMilli(t.timestamp).atZone(zone).toLocalTime().format(timeOfDay),
                        style = small, color = muted, modifier = Modifier.width(56.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(t.category, style = MaterialTheme.typography.labelSmall, color = muted, maxLines = 1)
                    }
                    val credit = t.type == com.myfinance.core.TxnType.CREDIT
                    Text(
                        (if (credit) "+" else "") + formatInr(t.amountMinor),
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                        color = if (credit) IncomeGreen else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            if (todays.size > 5) {
                Text(
                    if (showAllToday) "Show less" else "+${todays.size - 5} more today",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { showAllToday = !showAllToday }.padding(vertical = 4.dp),
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 6.dp))

            // Commitments: missed ones first, then today and the next few days.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Due next 7 days", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (today.upcomingOutMinor > 0) Text(formatInr(today.upcomingOutMinor), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Amber)
            }
            today.late.forEach { e ->
                DueLine("Missed?", e.title.substringBefore(" (was due"), e.amountMinor?.let { formatInr(it) }.orEmpty(), ExpenseRed,
                    onPaid = e.ref?.takeIf { vm.v15.canMarkPaid(it) }?.let { r -> { vm.v15.markReminderPaid(r) } })
            }
            val ahead = today.upcoming.filter { !it.late }
            if (ahead.isEmpty() && today.late.isEmpty()) Text("Nothing due", style = small, color = muted)
            ahead.take(5).forEach { e ->
                DueLine(
                    if (e.date == now) "Today" else if (e.date == now.plusDays(1)) "Tomorrow" else e.date.format(dayName),
                    e.title,
                    e.amountMinor?.let { (if (e.incoming) "+" else "") + formatInr(it) }.orEmpty(),
                    if (e.incoming) IncomeGreen else if (e.date == now) ExpenseRed else Amber,
                    onPaid = e.ref?.takeIf { !e.incoming && vm.v15.canMarkPaid(it) }?.let { r -> { vm.v15.markReminderPaid(r) } },
                )
            }
            if (ahead.size > 5) Text("+${ahead.size - 5} more", style = small, color = muted)

            // One-line alerts.
            val pace by vm.v15.pace.collectAsState()
            pace?.takeIf { it.state != com.myfinance.core.PaceState.ON_TRACK }?.let { p ->
                Text(
                    "${p.state.label}: ${p.spentPercent}% of budget used by day ${p.day} of ${p.daysInPeriod}",
                    style = small, color = if (p.state == com.myfinance.core.PaceState.OVER) ExpenseRed else Amber,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            val inbox by vm.v16.inbox.collectAsState()
            com.myfinance.app.ui.WrapRow(Modifier.padding(top = 2.dp), 0.dp) {
                LinkText("Payments ahead") { open("payments-ahead") }
                LinkText("Calendar") { open("calendar") }
                if (inbox.isNotEmpty()) LinkText("${inbox.size} to review", Amber) { open("review") }
            }
        }
    }
}

@Composable
private fun DueLine(
    whenText: String, title: String, amount: String, color: androidx.compose.ui.graphics.Color,
    /** Set for payment reminders and card bills: shows an "Already paid?" choice on tap. */
    onPaid: (() -> Unit)? = null,
) {
    var ask by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().then(if (onPaid != null) Modifier.clickable { ask = true } else Modifier).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(whenText, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.width(84.dp), maxLines = 1)
        Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(amount, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = color)
        if (onPaid != null) {
            Text(
                "Paid", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { ask = true }.padding(start = 10.dp, top = 4.dp, bottom = 4.dp),
            )
        }
    }
    if (ask && onPaid != null) {
        AlertDialog(
            onDismissRequest = { ask = false },
            title = { Text("Already paid?") },
            text = { Text("$title\n\nA repeating payment moves to its next date; a one-time one is done and stops showing as due or missed.") },
            confirmButton = { TextButton(onClick = { onPaid(); ask = false }) { Text("Yes, already paid") } },
            dismissButton = { TextButton(onClick = { ask = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun LinkText(text: String, color: androidx.compose.ui.graphics.Color? = null, onClick: () -> Unit) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, color = color ?: MaterialTheme.colorScheme.primary,
        maxLines = 1, softWrap = false,
        modifier = Modifier.clickable(onClick = onClick).padding(end = 16.dp, top = 6.dp, bottom = 6.dp),
    )
}

/** The Overview + button: a short menu of everything that can be added, plus Ask and Heavy spends. */
@Composable
fun QuickAddFab(vm: FinanceViewModel, open: (String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<QuickAdd?>(null) }
    Box {
        FloatingActionButton(onClick = { menu = true }) { Icon(Icons.Filled.Add, contentDescription = "Add") }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            fun pick(action: () -> Unit) { menu = false; action() }
            DropdownMenuItem(text = { Text("Spend (any date)") }, onClick = { pick { open("edit/0") } })
            DropdownMenuItem(text = { Text("Income") }, onClick = { pick { open("edit-income") } })
            DropdownMenuItem(text = { Text("Future payment (once or repeating)") }, onClick = { pick { dialog = QuickAdd.PAYMENT_DUE } })
            DropdownMenuItem(text = { Text("Lent / borrowed") }, onClick = { pick { open("lending") } })
            DropdownMenuItem(text = { Text("Past investment") }, onClick = { pick { dialog = QuickAdd.INVESTMENT } })
            DropdownMenuItem(text = { Text("Property") }, onClick = { pick { dialog = QuickAdd.PROPERTY } })
            DropdownMenuItem(text = { Text("EPF balance") }, onClick = { pick { dialog = QuickAdd.EPF } })
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Ask a question") }, onClick = { pick { open("ask") } })
            DropdownMenuItem(text = { Text("Heavy spends") }, onClick = { pick { open("heavy") } })
        }
    }
    dialog?.let { QuickAddDialog(vm, it) { dialog = null } }
}

private val repeatOptions = com.myfinance.core.reminderRepeats
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
    var alreadyPaid by remember { mutableStateOf(false) }

    val title = when (what) {
        QuickAdd.PAYMENT_DUE -> "Future payment"
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Checkbox(checked = alreadyPaid, onCheckedChange = { alreadyPaid = it })
                            Text(if (every == 0) "Already paid" else "This one is already paid (remind from the next)")
                        }
                        Hint12(if (every == 0) "Once: shows on Home and in Payments ahead, the calendar and the forecast, with a reminder 2 days before." else "Repeats: each time it is paid (or you tap Already paid) it moves to the next date. Shows on Home and in Payments ahead, the calendar and the forecast, with a reminder 2 days before. Stop it from Bill reminders.")
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
                    QuickAdd.PAYMENT_DUE -> { if (name.isBlank()) { error = true; return@TextButton }; s.addPaymentDue(name, a, date, every, billKind, alreadyPaid) }
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
