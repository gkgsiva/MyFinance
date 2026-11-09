package com.myfinance.app.ui.v12

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.myfinance.app.ui.V12ViewModel
import com.myfinance.core.BillState
import com.myfinance.core.GoalStatus
import com.myfinance.core.formatInr

/** The "Plan" tab: entry points to goals, net worth, bills, splits, cash, tax, and the privacy settings. */
@Composable
fun PlanScreen(vm: V12ViewModel, open: (String) -> Unit) {
    val goals by vm.goals.collectAsState()
    val net by vm.netWorth.collectAsState()
    val bills by vm.bills.collectAsState()
    val splits by vm.splits.collectAsState()
    val cash by vm.cash.collectAsState()
    val tax by vm.tax.collectAsState()
    val owner by vm.ownerName.collectAsState()
    val lock by vm.lockEnabled.collectAsState()
    val rules by vm.rules.collectAsState()

    val dueSoon = bills.count { it.second.state in setOf(BillState.DUE_SOON, BillState.DUE_TODAY, BillState.OVERDUE) }
    val myBalance = splits.balances[owner] ?: 0L

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Text("Plan", style = MaterialTheme.typography.headlineSmall) }
        item {
            Tile("Net worth", formatInr(net.summary.netWorthMinor), "Bank, investments, cash minus loans") { open("networth") }
        }
        item {
            val behind = goals.count { it.status == GoalStatus.BEHIND || it.status == GoalStatus.OVERDUE }
            Tile(
                "Goals",
                if (goals.isEmpty()) "Add a goal" else "${goals.size} goals",
                if (behind > 0) "$behind behind schedule" else "Savings targets like \"Vacation ₹1L by June\"",
            ) { open("goals") }
        }
        item {
            Tile("Auto debits & EMIs", "Next dates", "Loan EMIs, SIPs, insurance, NACH and autopay debits") { open("autodebits") }
        }
        item { Tile("EMIs & loan payoff", "End dates", "When each EMI ends, interest paid, and what prepaying saves") { open("loans") } }
        item { Tile("Card bill check", "Bills vs paid", "Each card statement against what you paid; flags gaps and late payments") { open("card-bills") } }
        item { Tile("Refund tracker", "Waiting", "IRCTC, Amazon and other refunds still to come back") { open("refunds") } }
        item { Tile("Year in review", "Share", "Your year on one page, ready for WhatsApp") { open("year-review") } }
        item {
            Tile("Bill reminders", if (bills.isEmpty()) "Add a bill" else "${bills.size} bills", if (dueSoon > 0) "$dueSoon due soon" else "Electricity, mobile, insurance, school fees") { open("bills") }
        }
        item {
            val text = when {
                myBalance > 0 -> "You are owed ${formatInr(myBalance)}"
                myBalance < 0 -> "You owe ${formatInr(-myBalance)}"
                else -> "All settled"
            }
            Tile("Split & settle", text, "Shared payments with family and friends") { open("splits") }
        }
        item { Tile("Cash wallet", formatInr(cash.summary.inHandMinor) + " in hand", "ATM withdrawals and what the cash went on") { open("cash") } }
        item {
            Tile(
                "Tax helper",
                com.myfinance.core.TaxHelper.fyLabel(tax.fyStart) + ": " + formatInr(com.myfinance.core.TaxHelper.totalDeduction(tax.totals)),
                if (tax.suggestions.isNotEmpty()) "${tax.suggestions.size} payments may be tax-saving" else "80C, 80D, NPS and more",
            ) { open("tax") }
        }
        item { Text("Settings", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp)) }
        item { Tile("All settings", "", "Hidden items, deleted, labels, payee names, rescan, theme and more") { open("settings") } }
        item { Tile("Custom SMS rules", "${rules.size} rules", "e.g. any SMS with SWIGGY goes to Food") { open("rules") } }
        item { Tile("App lock", if (lock) "On" else "Off", "Fingerprint or PIN to open the app") { open("security") } }
        item { Tile("Backup & restore", "Encrypted", "Move to a new phone without losing data") { open("backup") } }
    }
}

@Composable
private fun Tile(title: String, value: String, hint: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(value, fontWeight = FontWeight.SemiBold)
        }
    }
}
