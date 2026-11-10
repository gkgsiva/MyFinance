package com.myfinance.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.myfinance.app.data.BillEntity
import com.myfinance.app.ui.FinanceViewModel
import com.myfinance.app.ui.theme.ExpenseRed
import com.myfinance.core.BalanceReading
import com.myfinance.core.Balances
import com.myfinance.core.RecurringPayment
import com.myfinance.core.formatInr
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val shortDay: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM")

/** "today", "tomorrow", "in 5 days", "3 days ago". */
fun relativeDay(date: LocalDate, today: LocalDate = LocalDate.now()): String {
    val days = ChronoUnit.DAYS.between(today, date)
    return when {
        days == 0L -> "today"
        days == 1L -> "tomorrow"
        days > 1 -> "in $days days"
        days == -1L -> "yesterday"
        else -> "${-days} days ago"
    }
}

/** Card bills and EMIs, recurring payments and account balances. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpcomingScreen(vm: FinanceViewModel, onBack: () -> Unit, onOpenAutoDebits: () -> Unit = {}) {
    val bills by vm.openBills.collectAsState()
    val recurring by vm.recurring.collectAsState()
    val balances by vm.balances.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bills, payments & balances") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { inner ->
        LazyColumn(
            Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { SectionTitle("Credit card bills & EMIs") }
            if (bills.isEmpty()) {
                item { Hint("No unpaid bills. Card statements and EMI reminders from your bank SMS show up here.") }
            }
            items(bills, key = { "b${it.id}" }) { b -> BillCard(b, onPaid = { vm.markBillPaid(b.id) }, onRemove = { vm.deleteBill(b.id) }) }

            item { SectionTitle("Recurring payments") }
            item {
                TextButton(onClick = onOpenAutoDebits) { Text("See all auto debits & EMIs with next dates") }
            }
            if (recurring.isEmpty()) {
                item { Hint("Subscriptions, rent, SIPs and other payments that repeat every week, month, quarter or year appear here once they have been paid a few times.") }
            }
            items(recurring, key = { "r${it.key}" }) { r -> RecurringRow(r) }

            item { SectionTitle("Account balances") }
            if (balances.isEmpty()) {
                item { Hint("Balances appear when your bank SMS mention one, e.g. \"Avl Bal Rs 12,000\".") }
            } else {
                item {
                    Text(
                        "Total in bank accounts: ${formatInr(Balances.totalBankMinor(balances))}",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            items(balances, key = { "a${it.accountKey}" }) { BalanceRow(it) }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun BillCard(b: BillEntity, onPaid: () -> Unit, onRemove: () -> Unit) {
    val bill = b.toBill()
    val overdue = bill.dueDate.isBefore(LocalDate.now())
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(bill.title, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Due ${bill.dueDate.format(shortDay)} (${relativeDay(bill.dueDate)})",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (overdue) ExpenseRed else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    bill.minDueMinor?.let {
                        Text("Minimum ${formatInr(it)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(formatInr(bill.amountMinor), fontWeight = FontWeight.Bold)
            }
            Row {
                TextButton(onClick = onPaid) { Text("Already paid") }
                TextButton(onClick = onRemove) { Text("Remove") }
            }
        }
    }
}

@Composable
private fun RecurringRow(r: RecurringPayment) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(r.merchant, fontWeight = FontWeight.Medium)
            Text(
                "${r.cadence.label} · ${r.category} · next ${r.nextDate.format(shortDay)} (${relativeDay(r.nextDate)})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(formatInr(r.amountMinor), fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun BalanceRow(b: BalanceReading) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(b.label + if (b.isCardLimit) " (card limit left)" else "")
            Text(
                "as of " + b.timestamp.toLocalDateTime().format(DateTimeFormatter.ofPattern("d MMM, h:mm a")),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(formatInr(b.amountMinor), fontWeight = FontWeight.SemiBold)
    }
}
