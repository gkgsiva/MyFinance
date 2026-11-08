package com.myfinance.app.ui.v12

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.myfinance.app.data.SplitEntity
import com.myfinance.app.ui.V12ViewModel
import com.myfinance.app.ui.screens.toLocalDateTime
import com.myfinance.app.ui.theme.ExpenseRed
import com.myfinance.app.ui.theme.IncomeGreen
import com.myfinance.core.Splits
import com.myfinance.core.Transfer
import com.myfinance.core.formatInr
import com.myfinance.core.parseAmountToMinor

@Composable
fun SplitsScreen(vm: V12ViewModel, onBack: () -> Unit) {
    val data by vm.splits.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<SplitEntity?>(null) }
    var settling by remember { mutableStateOf<Transfer?>(null) }

    V12Screen("Split & settle", vm, onBack, onAdd = { adding = true }) {
        item { Hint12("Mark a payment as shared, from here or from any transaction (\"Split this payment\"). The app works out who owes whom with the fewest payments.") }
        item { Section12("Who owes whom") }
        if (data.plan.isEmpty()) item { Hint12("Everyone is settled up.") }
        items(data.plan, key = { "p${it.from}>${it.to}" }) { t ->
            InfoCard {
                Text("${t.from} pays ${t.to} ${formatInr(t.amountMinor)}", style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { settling = t }) { Text("Record payment") }
            }
        }
        if (data.balances.isNotEmpty()) {
            item { Section12("Balances") }
            items(data.balances.entries.toList(), key = { "b" + it.key }) { (p, v) ->
                Row(Modifier.fillMaxWidth()) {
                    Text(p, Modifier.weight(1f))
                    Text(if (v > 0) "gets back ${formatInr(v)}" else "owes ${formatInr(-v)}", color = if (v > 0) IncomeGreen else ExpenseRed)
                }
            }
        }
        item { Section12("Shared payments") }
        items(data.splits, key = { it.id }) { s ->
            InfoCard {
                Row(Modifier.fillMaxWidth()) {
                    Text(s.description, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text(formatInr(s.totalMinor))
                }
                Text("Paid by ${s.paidBy} · " + s.toSplit().shares.entries.joinToString { "${it.key} ${formatInr(it.value)}" }, style = MaterialTheme.typography.bodySmall)
                Text(s.timestamp.toLocalDateTime().toLocalDate().format(shortDate), style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick = { editing = s }) { Text("Edit") }
                    TextButton(onClick = { vm.deleteSplit(s.id) }) { Text("Delete") }
                }
            }
        }
        if (data.settlements.isNotEmpty()) {
            item { Section12("Settlements") }
            items(data.settlements, key = { it.id }) { st ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${st.fromPerson} paid ${st.toPerson} ${formatInr(st.amountMinor)} on ${st.timestamp.toLocalDateTime().toLocalDate().format(shortDate)}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { vm.deleteSettlement(st.id) }) { Text("Undo") }
                }
            }
        }
    }

    if (adding || editing != null) {
        SplitDialog(vm, existing = editing, txnUid = editing?.txnUid, defaultTotal = null, defaultDescription = null) { adding = false; editing = null }
    }

    settling?.let { t ->
        var amount by remember { mutableStateOf(t.amountMinor.asInput()) }
        AlertDialog(
            onDismissRequest = { settling = null },
            title = { Text("${t.from} paid ${t.to}") },
            text = { AmountField("Amount (₹)", amount, { amount = it }) },
            confirmButton = {
                TextButton(onClick = { parseAmountToMinor(amount)?.takeIf { it > 0 }?.let { vm.settle(t.from, t.to, it) }; settling = null }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { settling = null }) { Text("Cancel") } },
        )
    }
}

/** Add or edit a shared payment, split equally between the chosen people. */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SplitDialog(
    vm: V12ViewModel,
    existing: SplitEntity?,
    txnUid: String?,
    defaultTotal: Long?,
    defaultDescription: String?,
    onClose: () -> Unit,
) {
    val known by vm.people.collectAsState()
    val owner by vm.ownerName.collectAsState()
    val old = existing?.toSplit()
    var description by remember { mutableStateOf(existing?.description ?: defaultDescription.orEmpty()) }
    var total by remember { mutableStateOf((existing?.totalMinor ?: defaultTotal)?.asInput().orEmpty()) }
    var paidBy by remember { mutableStateOf(existing?.paidBy ?: owner) }
    val people = remember { mutableStateListOf<String>().apply { addAll(old?.shares?.keys ?: listOf(owner)) } }
    val options = remember(known) { mutableStateListOf<String>().apply { addAll((known + people).distinct()) } }
    var newName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (existing == null) "Split a payment" else "Edit split") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(description, { description = it }, label = { Text("What for, e.g. Dinner") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                AmountField("Total (₹)", total, { total = it; error = false }, error)
                Text("Split equally between", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    options.forEach { p ->
                        FilterChip(selected = p in people, onClick = { if (p in people) people.remove(p) else people.add(p) }, label = { Text(p) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newName, { newName = it }, label = { Text("Add a person") }, singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        val n = newName.trim()
                        if (n.isNotEmpty()) { if (n !in options) options.add(n); if (n !in people) people.add(n) }
                        newName = ""
                    }) { Text("Add") }
                }
                Dropdown("Paid by", paidBy, options.toList(), { it }, { paidBy = it })
                val t = parseAmountToMinor(total)
                if (t != null && people.isNotEmpty()) Hint12("Each pays ${formatInr(t / people.size)}")
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val t = parseAmountToMinor(total)
                if (t == null || t <= 0 || people.isEmpty()) { error = true; return@TextButton }
                val shares = Splits.equalShares(t, people.toList())
                vm.saveSplit(
                    SplitEntity(
                        id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                        txnUid = txnUid,
                        description = description.trim().ifEmpty { "Shared payment" },
                        paidBy = paidBy,
                        totalMinor = t,
                        shares = SplitEntity.encodeShares(shares),
                        timestamp = existing?.timestamp ?: System.currentTimeMillis(),
                    ),
                )
                onClose()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}
