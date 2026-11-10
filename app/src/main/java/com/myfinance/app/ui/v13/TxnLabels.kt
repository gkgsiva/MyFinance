package com.myfinance.app.ui.v13

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import com.myfinance.app.ui.FinanceViewModel

/** What the edit screen collects for labels, hiding and the payee name. */
data class ExtrasDraft(
    val labels: List<String> = emptyList(),
    val hidden: Boolean = false,
    /** Add these labels to every transaction from this payee (and new ones). */
    val labelAll: Boolean = true,
    /** Use the edited name for every transaction from this payee (and new ones). */
    val renameAll: Boolean = true,
)

/** Labels, "hide from lists" and "use this name for all" on the edit screen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TxnLabelsSection(
    vm: FinanceViewModel,
    draft: ExtrasDraft,
    onChange: (ExtrasDraft) -> Unit,
    originalMerchant: String?,
    editedMerchant: String,
    income: Boolean = false,
) {
    val choices by (if (income) vm.incomeLabelChoices else vm.labelChoices).collectAsState()
    var newLabel by remember { mutableStateOf("") }
    val all = (draft.labels + choices).distinctBy { it.lowercase() }

    val renamed = originalMerchant != null && editedMerchant.isNotBlank() && editedMerchant.trim() != originalMerchant
    if (renamed) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = draft.renameAll, onCheckedChange = { onChange(draft.copy(renameAll = it)) })
            Text(
                "Call every payment to \"$originalMerchant\" \"${editedMerchant.trim()}\", now and in future",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    Text("Labels", style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        all.forEach { l ->
            val on = draft.labels.any { it.equals(l, ignoreCase = true) }
            FilterChip(
                selected = on,
                onClick = {
                    onChange(draft.copy(labels = if (on) draft.labels.filterNot { it.equals(l, ignoreCase = true) } else draft.labels + l))
                },
                label = { Text(l) },
            )
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = newLabel,
            onValueChange = { newLabel = it.take(30) },
            label = { Text("New label, e.g. Vegetables") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            enabled = newLabel.isNotBlank(),
            onClick = {
                val l = newLabel.trim()
                if (draft.labels.none { it.equals(l, ignoreCase = true) }) onChange(draft.copy(labels = draft.labels + l))
                newLabel = ""
            },
        ) { Text("Add") }
    }
    val payee = editedMerchant.trim().ifEmpty { originalMerchant.orEmpty() }
    if (payee.isNotBlank() && draft.labels.isNotEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = draft.labelAll, onCheckedChange = { onChange(draft.copy(labelAll = it)) })
            Text(
                "Use these labels for every payment to and from \"$payee\" (refunds too)",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Hide from lists")
            Muted("Not shown in the transaction list or report lists, but still counted in all totals.")
        }
        Switch(checked = draft.hidden, onCheckedChange = { onChange(draft.copy(hidden = it)) })
    }
}

/** Manage tab: rename or delete labels. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LabelsCard(vm: FinanceViewModel) {
    val used by vm.usedLabels.collectAsState()
    var editing by remember { mutableStateOf<String?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Labels", style = MaterialTheme.typography.titleMedium)
            Muted(
                if (used.isEmpty()) "Tap any transaction to add labels like EMI, SIP, Grocery or Vegetables. " +
                    "Group by label on the Dashboard, filter by label on Transactions, and see totals in Reports."
                else "Tap a label to rename or delete it.",
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                used.forEach { l -> FilterChip(selected = false, onClick = { editing = l }, label = { Text("#$l") }) }
            }
        }
    }
    editing?.let { label ->
        var name by remember(label) { mutableStateOf(label) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Label") },
            text = { OutlinedTextField(name, { name = it.take(30) }, label = { Text("Name") }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { vm.renameLabel(label, name); editing = null }) { Text("Rename") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { vm.deleteLabel(label); editing = null }) { Text("Delete") }
                    TextButton(onClick = { editing = null }) { Text("Cancel") }
                }
            },
        )
    }
}
