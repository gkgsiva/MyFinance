package com.myfinance.app.ui.v15

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.myfinance.app.security.AppLock
import com.myfinance.app.ui.FinanceViewModel
import com.myfinance.app.ui.theme.Amber
import com.myfinance.app.ui.theme.ExpenseRed
import com.myfinance.app.ui.theme.IncomeGreen
import com.myfinance.app.ui.v12.AmountField
import com.myfinance.app.ui.v12.DateButton
import com.myfinance.app.ui.v12.Hint12
import com.myfinance.app.ui.v12.InfoCard
import com.myfinance.app.ui.v12.Section12
import com.myfinance.app.ui.v12.asInput
import com.myfinance.app.ui.v12.shortDate
import com.myfinance.core.LendDirection
import com.myfinance.core.LendState
import com.myfinance.core.LendStatus
import com.myfinance.core.formatInr
import com.myfinance.core.parseAmountToMinor
import java.time.LocalDate

/** Money lent to, or borrowed from, friends and relatives. */
@Composable
fun LendingScreen(vm: FinanceViewModel, onBack: () -> Unit) {
    val s = vm.v15
    val data by s.lending.collectAsState()
    val context = LocalContext.current
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<LendStatus?>(null) }
    var repaying by remember { mutableStateOf<LendStatus?>(null) }

    V15Screen("Lent & borrowed", s, onBack, onAdd = { adding = true }) {
        val sum = data.summary
        item {
            InfoCard {
                Row15("Others owe you", null, formatInr(sum.owedToYouMinor), IncomeGreen)
                Row15("You owe", null, formatInr(sum.youOweMinor), ExpenseRed)
                if (sum.overdueCount > 0) Text("${sum.overdueCount} overdue", color = ExpenseRed, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (sum.people.isNotEmpty()) {
            item { Section12("By person") }
            items(sum.people, key = { "p:" + it.person }) { p ->
                InfoCard {
                    Row15(
                        p.person, "${p.open} open" + if (p.overdue > 0) ", ${p.overdue} overdue" else "",
                        if (p.netMinor >= 0) "owes you ${formatInr(p.netMinor)}" else "you owe ${formatInr(-p.netMinor)}",
                        if (p.netMinor >= 0) IncomeGreen else ExpenseRed,
                    )
                }
            }
        }
        if (data.statuses.isEmpty()) item { Hint12("Note money you lend or borrow: who, how much, when and when it's due back. Add part repayments as they come in. You'll get a reminder on the due date, and can send a polite WhatsApp reminder.") }
        else item { Section12("Entries") }
        items(data.statuses, key = { it.entry.id }) { st ->
            val e = st.entry
            val lent = e.direction == LendDirection.LENT
            InfoCard {
                Row15(
                    (if (lent) "Lent to " else "Borrowed from ") + e.person,
                    "${e.date.format(shortDate)}" + (e.note?.let { " · $it" } ?: ""),
                    formatInr(st.outstandingMinor), if (lent) IncomeGreen else ExpenseRed,
                ) { editing = st }
                val color = when (st.state) { LendState.OVERDUE -> ExpenseRed; LendState.DUE_SOON -> Amber; else -> MaterialTheme.colorScheme.onSurfaceVariant }
                Text(
                    buildString {
                        append(st.state.label)
                        e.dueDate?.let { append(" · due ${it.format(shortDate)} (${daysText(st.daysLeft)})") }
                        if (st.repaidMinor > 0) append(" · ${formatInr(st.repaidMinor)} of ${formatInr(e.amountMinor)} back")
                    },
                    color = color, style = MaterialTheme.typography.bodySmall,
                )
                com.myfinance.app.ui.WrapRow(Modifier, 4.dp) {
                    if (st.state != LendState.SETTLED) {
                        TextButton(onClick = { repaying = st }) { Text(if (lent) "Got some back" else "Paid some back") }
                        TextButton(onClick = { s.settle(st) }) { Text("Settled") }
                        if (lent) TextButton(onClick = {
                            val text = s.reminderText(st)
                            val wa = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/?text=" + Uri.encode(text)))
                            AppLock.expectExternalActivity()
                            runCatching { context.startActivity(wa) }.onFailure {
                                runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Send reminder")) }
                            }
                        }) { Text("Remind") }
                    } else TextButton(onClick = { s.reopen(st) }) { Text("Reopen") }
                }
            }
        }
    }

    if (adding || editing != null) {
        val e = editing?.entry
        var person by remember { mutableStateOf(e?.person.orEmpty()) }
        var lent by remember { mutableStateOf(e?.direction != LendDirection.BORROWED) }
        var amount by remember { mutableStateOf(e?.amountMinor?.asInput().orEmpty()) }
        var date by remember { mutableStateOf(e?.date ?: LocalDate.now()) }
        var due by remember { mutableStateOf(e?.dueDate) }
        var note by remember { mutableStateOf(e?.note.orEmpty()) }
        var error by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { adding = false; editing = null },
            title = { Text(if (e == null) "Add" else "Edit") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.myfinance.app.ui.WrapRow(Modifier, 8.dp) {
                        FilterChip(lent, { lent = true }, label = { Text("I lent") })
                        FilterChip(!lent, { lent = false }, label = { Text("I borrowed") })
                    }
                    OutlinedTextField(person, { person = it; error = false }, label = { Text("Person") }, isError = error && person.isBlank(), singleLine = true, modifier = Modifier.fillMaxWidth())
                    AmountField("Amount (₹)", amount, { amount = it; error = false }, error)
                    DateButton("On", date, { d -> d?.let { date = it } })
                    DateButton("Due back", due, { due = it }, allowClear = true)
                    OutlinedTextField(note, { note = it }, label = { Text("For what (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val m = parseAmountToMinor(amount)
                    if (person.isBlank() || m == null || m <= 0) { error = true; return@TextButton }
                    s.newLend(person, lent, m, date, due, note, id = e?.id)
                    adding = false; editing = null
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    if (e != null) TextButton(onClick = { s.deleteLend(e.id); editing = null }) { Text("Delete") }
                    TextButton(onClick = { adding = false; editing = null }) { Text("Cancel") }
                }
            },
        )
    }

    repaying?.let { st ->
        var amount by remember(st.entry.id) { mutableStateOf(st.outstandingMinor.asInput()) }
        var date by remember(st.entry.id) { mutableStateOf(LocalDate.now()) }
        AlertDialog(
            onDismissRequest = { repaying = null },
            title = { Text("Repayment") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AmountField("Amount (₹)", amount, { amount = it })
                    DateButton("On", date, { d -> d?.let { date = it } })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    parseAmountToMinor(amount)?.takeIf { it > 0 }?.let { s.addRepayment(st.entry.id, it, date) }
                    repaying = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { repaying = null }) { Text("Cancel") } },
        )
    }
}
