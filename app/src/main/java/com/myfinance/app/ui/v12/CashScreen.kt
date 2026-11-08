package com.myfinance.app.ui.v12

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import com.myfinance.app.ui.V12ViewModel
import com.myfinance.app.ui.screens.toLocalDateTime
import com.myfinance.app.ui.theme.ExpenseRed
import com.myfinance.app.ui.theme.IncomeGreen
import com.myfinance.core.CashWallet
import com.myfinance.core.DefaultCategories
import com.myfinance.core.formatInr
import com.myfinance.core.parseAmountToMinor

@Composable
fun CashScreen(vm: V12ViewModel, onBack: () -> Unit, onOpenTxn: (Long) -> Unit) {
    val data by vm.cash.collectAsState()
    val categories by vm.categoryNames.collectAsState()
    var adding by remember { mutableStateOf(false) }
    val s = data.summary

    V12Screen("Cash wallet", vm, onBack, onAdd = { adding = true }) {
        item {
            InfoCard {
                Text("Cash in hand", style = MaterialTheme.typography.labelLarge)
                Text(formatInr(s.inHandMinor), style = MaterialTheme.typography.headlineMedium)
                Text("Withdrawn ${formatInr(s.withdrawnMinor)} · Spent ${formatInr(s.spentMinor)}")
                if (s.overspentMinor > 0) Hint12("You have logged ${formatInr(s.overspentMinor)} more cash spending than you withdrew. Cash from other places isn't tracked.")
                Hint12("ATM withdrawals from bank SMS add cash here. Tap + each time you pay in cash so you know where it went.")
            }
        }
        if (s.byCategory.isNotEmpty()) {
            item { Section12("Cash spent on") }
            items(s.byCategory, key = { "c" + it.key }) { g -> Row(Modifier.fillMaxWidth()) { Text(g.key, Modifier.weight(1f)); Text(formatInr(g.totalMinor)) } }
        }
        item { Section12("Recent") }
        items(data.recent, key = { it.id }) { t ->
            val withdrawal = CashWallet.isAtmWithdrawal(t.category, t.rawSms)
            Row(Modifier.fillMaxWidth().clickable { onOpenTxn(t.id) }.padding(vertical = 6.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(if (withdrawal) "ATM withdrawal" else (t.merchant ?: t.category))
                    Text(t.timestamp.toLocalDateTime().toLocalDate().format(shortDate) + (if (withdrawal) "" else " · ${t.category}"), style = MaterialTheme.typography.bodySmall)
                }
                Text((if (withdrawal) "+" else "−") + formatInr(t.amountMinor), color = if (withdrawal) IncomeGreen else ExpenseRed)
            }
        }
    }

    if (adding) {
        var amount by remember { mutableStateOf("") }
        var category by remember { mutableStateOf(DefaultCategories.GROCERIES) }
        var what by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Spent cash") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AmountField("Amount (₹)", amount, { amount = it })
                    OutlinedTextField(what, { what = it }, label = { Text("On what, e.g. vegetables") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Dropdown("Category", category, categories.filter { it != DefaultCategories.CASH }, { it }, { category = it })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    parseAmountToMinor(amount)?.takeIf { it > 0 }?.let { vm.addCashSpend(it, category, what.trim().ifEmpty { null }) }
                    adding = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }
}
