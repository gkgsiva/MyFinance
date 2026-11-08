package com.myfinance.app.ui.v12

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
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
import com.myfinance.app.data.LiabilityEntity
import com.myfinance.app.ui.HoldingRow
import com.myfinance.app.ui.V12ViewModel
import com.myfinance.app.ui.theme.ExpenseRed
import com.myfinance.app.ui.theme.IncomeGreen
import com.myfinance.core.AssetKind
import com.myfinance.core.LiabilityKind
import com.myfinance.core.formatInr
import com.myfinance.core.label
import com.myfinance.core.parseAmountToMinor

val LiabilityKind.label: String
    get() = when (this) {
        LiabilityKind.CREDIT_CARD -> "Credit card dues"; LiabilityKind.HOME_LOAN -> "Home loan"; LiabilityKind.CAR_LOAN -> "Car / bike loan"
        LiabilityKind.PERSONAL_LOAN -> "Personal loan"; LiabilityKind.EDUCATION_LOAN -> "Education loan"; LiabilityKind.OTHER -> "Other"
    }

private val manualKinds = AssetKind.entries.filter { it != AssetKind.BANK && it != AssetKind.CASH }

@Composable
fun NetWorthScreen(vm: V12ViewModel, onBack: () -> Unit) {
    val data by vm.netWorth.collectAsState()
    var holdingDialog by remember { mutableStateOf<HoldingRow?>(null) }
    var addingHolding by remember { mutableStateOf(false) }
    var liabilityDialog by remember { mutableStateOf<LiabilityEntity?>(null) }
    var addingLiability by remember { mutableStateOf(false) }
    val s = data.summary

    V12Screen("Net worth", vm, onBack) {
        item {
            InfoCard {
                Text("Net worth", style = MaterialTheme.typography.labelLarge)
                Text(formatInr(s.netWorthMinor), style = MaterialTheme.typography.headlineMedium, color = if (s.netWorthMinor >= 0) IncomeGreen else ExpenseRed)
                Text("Assets ${formatInr(s.assetsMinor)} · Loans ${formatInr(s.liabilitiesMinor)}")
                if (s.investedMinor > 0) {
                    val sign = if (s.gainMinor >= 0) "+" else ""
                    Text("Investments: ${formatInr(s.investedMinor)} put in, $sign${formatInr(s.gainMinor)} gain", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { Section12("By type") }
        items(s.byKind, key = { "k" + it.key }) { g ->
            Row(Modifier.fillMaxWidth()) { Text(g.key, Modifier.weight(1f)); Text(formatInr(g.totalMinor)) }
        }
        item {
            Section12("Investments & assets")
            Hint12("SIPs, mutual funds, FDs and share trades are read from your SMS. Tap one to update its current value. Bank balances come from bank SMS and cash from the cash wallet.")
        }
        items(data.rows, key = { (it.manualId ?: it.overrideKey).orEmpty() }) { r ->
            Row(Modifier.fillMaxWidth().clickable { holdingDialog = r }.padding(vertical = 6.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(r.holding.name)
                    Text(r.holding.kind.label + (if (r.manualId == null) " · from SMS" else ""), style = MaterialTheme.typography.bodySmall)
                }
                Column {
                    Text(formatInr(r.holding.currentMinor), fontWeight = FontWeight.SemiBold)
                    if (r.holding.investedMinor != r.holding.currentMinor) Text("put in ${formatInr(r.holding.investedMinor)}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { FilledTonalButton(onClick = { addingHolding = true }) { Text("Add an asset") } }
        item { Section12("Loans & dues") }
        items(data.liabilities, key = { it.id }) { l ->
            Row(Modifier.fillMaxWidth().clickable { liabilityDialog = l }.padding(vertical = 6.dp)) {
                Column(Modifier.weight(1f)) { Text(l.name); Text(l.toLiability().kind.label, style = MaterialTheme.typography.bodySmall) }
                Text(formatInr(l.outstandingMinor), color = ExpenseRed)
            }
        }
        item { FilledTonalButton(onClick = { addingLiability = true }) { Text("Add a loan") } }
    }

    if (addingHolding || holdingDialog != null) {
        val r = holdingDialog
        val fromSms = r != null && r.manualId == null
        var name by remember { mutableStateOf(r?.holding?.name.orEmpty()) }
        var kind by remember { mutableStateOf(r?.holding?.kind ?: AssetKind.GOLD) }
        var invested by remember { mutableStateOf(r?.holding?.investedMinor?.asInput().orEmpty()) }
        var current by remember { mutableStateOf(r?.holding?.currentMinor?.asInput().orEmpty()) }
        val close = { addingHolding = false; holdingDialog = null }
        AlertDialog(
            onDismissRequest = close,
            title = { Text(if (r == null) "Add an asset" else r.holding.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!fromSms) {
                        OutlinedTextField(name, { name = it }, label = { Text("Name, e.g. Gold coins") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Dropdown("Type", kind, manualKinds, { it.label }, { kind = it })
                        AmountField("Amount put in (₹)", invested, { invested = it })
                    } else {
                        Hint12("Put in so far: ${formatInr(r!!.holding.investedMinor)}")
                    }
                    AmountField("Current value (₹)", current, { current = it })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val cur = parseAmountToMinor(current)
                    if (fromSms) { cur?.let { vm.setCurrentValue(r!!, it) } }
                    else {
                        val inv = parseAmountToMinor(invested) ?: cur ?: 0
                        if (name.isNotBlank()) vm.saveHolding(r?.manualId, name.trim(), kind, inv, cur ?: inv)
                    }
                    close()
                }) { Text("Save") }
            },
            dismissButton = {
                if (r != null) TextButton(onClick = { vm.deleteHolding(r); close() }) { Text("Remove") }
                else TextButton(onClick = close) { Text("Cancel") }
            },
        )
    }

    if (addingLiability || liabilityDialog != null) {
        val l = liabilityDialog
        var name by remember { mutableStateOf(l?.name.orEmpty()) }
        var kind by remember { mutableStateOf(l?.toLiability()?.kind ?: LiabilityKind.HOME_LOAN) }
        var amount by remember { mutableStateOf(l?.outstandingMinor?.asInput().orEmpty()) }
        val close = { addingLiability = false; liabilityDialog = null }
        AlertDialog(
            onDismissRequest = close,
            title = { Text(if (l == null) "Add a loan" else "Edit loan") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name, e.g. SBI home loan") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Dropdown("Type", kind, LiabilityKind.entries, { it.label }, { kind = it })
                    AmountField("Outstanding (₹)", amount, { amount = it })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val a = parseAmountToMinor(amount)
                    if (name.isNotBlank() && a != null) vm.saveLiability(l?.id, name.trim(), kind, a)
                    close()
                }) { Text("Save") }
            },
            dismissButton = {
                if (l != null) TextButton(onClick = { vm.deleteLiability(l.id); close() }) { Text("Remove") }
                else TextButton(onClick = close) { Text("Cancel") }
            },
        )
    }
}
