package com.myfinance.app.ui.v16

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.myfinance.app.data.TransactionEntity
import com.myfinance.app.ui.FinanceViewModel
import com.myfinance.app.ui.theme.ExpenseRed
import com.myfinance.app.ui.v12.AmountField
import com.myfinance.app.ui.v12.Dropdown
import com.myfinance.app.ui.v12.Hint12
import com.myfinance.app.ui.v12.InfoCard
import com.myfinance.app.ui.v12.asInput
import com.myfinance.core.ForeignAmount
import com.myfinance.core.Fx
import com.myfinance.core.PaymentSplit
import com.myfinance.core.SplitPart
import com.myfinance.core.TxnType
import com.myfinance.core.formatInr
import com.myfinance.core.parseAmountToMinor
import kotlinx.coroutines.launch

/** On a saved payment: break it into parts with their own categories. */
@Composable
fun SplitPaymentCard(vm: FinanceViewModel, t: TransactionEntity) {
    if (t.type != TxnType.DEBIT) return
    val categories by vm.categories.collectAsState()
    var parts by remember { mutableStateOf<List<SplitPart>>(emptyList()) }
    var editing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(t.uid) { parts = vm.v16.partsOf(t.uid) }

    InfoCard {
        Text("Split this payment", style = MaterialTheme.typography.titleSmall)
        if (parts.isEmpty()) {
            Hint12("Break it into categories, e.g. ₹2,000 at a supermarket into Groceries ₹1,500 and Household ₹500. Totals and reports then count each part in its own category.")
        } else {
            Text("${t.category}: ${formatInr(PaymentSplit.remaining(t.amountMinor, parts))}", style = MaterialTheme.typography.bodySmall)
            parts.forEach { Text("${it.category}: ${formatInr(it.amountMinor)}" + (it.note?.let { n -> " · $n" } ?: ""), style = MaterialTheme.typography.bodySmall) }
        }
        Row {
            TextButton(onClick = { editing = true }) { Text(if (parts.isEmpty()) "Split" else "Change split") }
            if (parts.isNotEmpty()) TextButton(onClick = { scope.launch { vm.v16.saveSplit(t, emptyList()); parts = emptyList() } }) { Text("Join back") }
        }
    }

    if (editing) {
        val names = categories.map { it.name }
        val rows = remember { mutableStateListOf<Triple<String, String, String>>().apply {
            if (parts.isEmpty()) add(Triple(names.firstOrNull { it != t.category } ?: t.category, "", ""))
            else parts.forEach { add(Triple(it.category, it.amountMinor.asInput(), it.note.orEmpty())) }
        } }
        var problem by remember { mutableStateOf<String?>(null) }
        val draft = rows.map { SplitPart(it.first, parseAmountToMinor(it.second) ?: 0, it.third.trim().ifEmpty { null }) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Split ${formatInr(t.amountMinor)}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${t.category} keeps ${formatInr(PaymentSplit.remaining(t.amountMinor, draft).coerceAtLeast(0))}", style = MaterialTheme.typography.bodyMedium)
                    rows.forEachIndexed { i, r ->
                        Dropdown("Part ${i + 1} category", r.first, names, { it }, { rows[i] = r.copy(first = it) })
                        AmountField("Amount (₹)", r.second, { rows[i] = r.copy(second = it); problem = null })
                        OutlinedTextField(r.third, { rows[i] = r.copy(third = it) }, label = { Text("Note (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        if (rows.size > 1) TextButton(onClick = { rows.removeAt(i) }) { Text("Remove part ${i + 1}") }
                    }
                    TextButton(onClick = { rows.add(Triple(names.first(), "", "")) }) { Text("Add another part") }
                    problem?.let { Text(it, color = ExpenseRed, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val p = vm.v16.saveSplit(t, draft)
                        if (p == null) { parts = draft; editing = false } else problem = p
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } },
        )
    }
}

/** Under the amount when adding or editing: enter a foreign amount and get rupees, with the rate kept in the note. */
@Composable
fun ForeignAmountRow(vm: FinanceViewModel, onConverted: (inrMinor: Long, note: String) -> Unit) {
    val rates by vm.v16.fxRates.collectAsState()
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("Paid in another currency?") }
    if (!open) return
    var code by remember { mutableStateOf("USD") }
    var amt by remember { mutableStateOf("") }
    var rate by remember(code) { mutableStateOf(Fx.rate(code, rates)?.toString().orEmpty()) }
    val foreign = parseAmountToMinor(amt)?.let { ForeignAmount(code, it) }
    val r = rate.toDoubleOrNull()
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("Foreign currency") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Dropdown("Currency", code, rates.keys.toList(), { it }, { code = it })
                AmountField("Amount in $code", amt, { amt = it })
                OutlinedTextField(rate, { v -> rate = v.filter { it.isDigit() || it == '.' }.take(10) }, label = { Text("₹ for 1 $code") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (foreign != null && r != null) Text("= ${formatInr(Fx.toInrMinor(foreign, r))}", style = MaterialTheme.typography.titleMedium)
                Hint12("Use the rate on your card statement if you have it. The foreign amount and rate are kept in the note.")
            }
        },
        confirmButton = {
            TextButton(enabled = foreign != null && r != null && r > 0, onClick = {
                if (foreign != null && r != null) {
                    vm.v16.setFxRate(code, r)
                    onConverted(Fx.toInrMinor(foreign, r), Fx.note(foreign, r))
                }
                open = false
            }) { Text("Use") }
        },
        dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
    )
}
