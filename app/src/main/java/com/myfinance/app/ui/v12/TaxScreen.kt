package com.myfinance.app.ui.v12

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.myfinance.app.ui.TaxRow
import com.myfinance.app.ui.V12ViewModel
import com.myfinance.app.ui.screens.toLocalDateTime
import com.myfinance.core.TaxHelper
import com.myfinance.core.TaxSection
import com.myfinance.core.formatInr
import java.time.LocalDate

val TaxSection.short: String get() = label.substringBefore(':')

@Composable
fun TaxScreen(vm: V12ViewModel, onBack: () -> Unit, onOpenTxn: (Long) -> Unit) {
    val data by vm.tax.collectAsState()
    val thisFy = TaxHelper.fyStartYear(LocalDate.now())

    V12Screen("Tax helper", vm, onBack) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { vm.taxYear.value = data.fyStart - 1 }) { Text("‹") }
                Text(TaxHelper.fyLabel(data.fyStart), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.taxYear.value = data.fyStart + 1 }, enabled = data.fyStart < thisFy) { Text("›") }
            }
        }
        item {
            InfoCard {
                Text("Deductions found", style = MaterialTheme.typography.labelLarge)
                Text(formatInr(TaxHelper.totalDeduction(data.totals)), style = MaterialTheme.typography.headlineMedium)
                Hint12("Old tax regime limits, counted per person. Payments made by other family members are included when merged, so check whose name each claim belongs to. This is a helper, not tax advice.")
            }
        }
        items(data.totals, key = { "t" + it.section.name }) { t ->
            InfoCard {
                Text(t.section.label, style = MaterialTheme.typography.titleSmall)
                val limit = t.section.limitMinor
                if (limit != null) {
                    LinearProgressIndicator(progress = { (t.claimedMinor.toFloat() / limit).coerceAtMost(1f) }, modifier = Modifier.fillMaxWidth())
                    Text("${formatInr(t.claimedMinor)} of ${formatInr(limit)}" + if ((t.remainingMinor ?: 0) > 0) " · ${formatInr(t.remainingMinor!!)} left to invest" else " · limit reached")
                } else Text(formatInr(t.claimedMinor))
            }
        }
        if (data.suggestions.isNotEmpty()) {
            item { Section12("Might be tax-saving") }
            items(data.suggestions, key = { "s" + it.txn.uid }) { r -> TaxRowItem(r, onOpenTxn, accept = { vm.setTax(r.txn.uid, r.section) }, reject = { vm.setTax(r.txn.uid, null, notTaxSaving = true) }) }
        }
        item { Section12("Tagged payments") }
        if (data.tagged.isEmpty()) item { Hint12("Tag insurance premiums, PPF, ELSS, NPS, tuition fees, donations and medical insurance from a transaction's edit screen, or accept the suggestions above.") }
        items(data.tagged, key = { "g" + it.txn.uid }) { r -> TaxRowItem(r, onOpenTxn, accept = null, reject = { vm.setTax(r.txn.uid, null) }) }
    }
}

@Composable
private fun TaxRowItem(r: TaxRow, onOpen: (Long) -> Unit, accept: (() -> Unit)?, reject: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            TextButton(onClick = { onOpen(r.txn.id) }) { Text(r.txn.merchant ?: r.txn.category) }
            Text("${r.section.short} · ${r.txn.timestamp.toLocalDateTime().toLocalDate().format(shortDate)}", style = MaterialTheme.typography.bodySmall)
        }
        Text(formatInr(r.txn.amountMinor), fontWeight = FontWeight.SemiBold)
        if (accept != null) TextButton(onClick = accept) { Text("Add") }
        TextButton(onClick = reject) { Text(if (accept != null) "No" else "Remove") }
    }
}
