package com.myfinance.app.ui.v13

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.myfinance.app.data.DeletedTxnEntity
import com.myfinance.app.ui.FinanceViewModel
import com.myfinance.app.ui.screens.toLocalDateTime
import com.myfinance.app.ui.theme.ExpenseRed
import com.myfinance.app.ui.theme.IncomeGreen
import com.myfinance.core.formatInr
import java.time.format.DateTimeFormatter

private val whenFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a")

/** The Recently deleted bin: restore a transaction deleted by mistake, or remove it for good. */
@Composable
fun TrashScreen(vm: FinanceViewModel, onBack: () -> Unit) {
    val trash by vm.recentlyDeleted.collectAsState()
    var confirmEmpty by remember { mutableStateOf(false) }

    SimpleScreen(
        title = "Recently deleted",
        onBack = onBack,
        actions = { if (trash.isNotEmpty()) TextButton(onClick = { confirmEmpty = true }) { Text("Empty") } },
    ) {
        item {
            Muted(
                "Deleted transactions stay here for 60 days. Tap Restore to put one back exactly as it was. " +
                    "Rescanning your messages won't add deleted ones back, even after they are removed from here.",
            )
        }
        if (trash.isEmpty()) item { Text("Nothing deleted.", modifier = Modifier.padding(top = 16.dp)) }
        items(trash, key = { it.uid }) { t -> TrashRow(t, onRestore = { vm.restore(t.uid) }, onForget = { vm.deleteForever(t.uid) }) }
    }

    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text("Empty Recently deleted?") },
            text = { Text("These ${trash.size} transactions can't be restored after this.") },
            confirmButton = { TextButton(onClick = { vm.emptyTrash(); confirmEmpty = false }) { Text("Empty") } },
            dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TrashRow(t: DeletedTxnEntity, onRestore: () -> Unit, onForget: () -> Unit) {
    val credit = t.type == "CREDIT"
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t.merchant ?: t.category, fontWeight = FontWeight.SemiBold)
                    Muted("${t.category} · ${t.timestamp.toLocalDateTime().format(whenFmt)}")
                    Muted("Deleted ${t.deletedAt.toLocalDateTime().format(whenFmt)}")
                }
                Text(
                    (if (credit) "+" else "-") + formatInr(t.amountMinor),
                    fontWeight = FontWeight.Bold,
                    color = if (credit) IncomeGreen else ExpenseRed,
                )
            }
            Row {
                TextButton(onClick = onRestore) { Text("Restore") }
                TextButton(onClick = onForget) { Text("Delete for good") }
            }
        }
    }
}

private val periods = listOf(1 to "Last month", 3 to "Last 3 months", 6 to "Last 6 months", 12 to "Last 12 months", 0 to "Everything in the inbox")

/**
 * Reads the SMS inbox again. Messages already imported are left as they are (with your edits); ones you
 * deleted are skipped unless you ask for them back.
 */
@Composable
fun RescanScreen(vm: FinanceViewModel, smsGranted: Boolean, onAskPermission: () -> Unit, onBack: () -> Unit) {
    val state by vm.importState.collectAsState()
    var months by remember { mutableIntStateOf(12) }
    var bringBack by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }

    SimpleScreen(title = "Rescan messages", onBack = onBack) {
        item {
            Muted(
                "Reads your bank SMS again to pick up anything that was missed, for example messages that " +
                    "arrived while SMS access was off. Nothing is added twice, and transactions you edited keep your changes.",
            )
        }
        item { Text("How far back", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
        items(periods, key = { it.first }) { (m, label) ->
            Row(
                Modifier.fillMaxWidth().selectable(selected = months == m, onClick = { months = m }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = months == m, onClick = { months = m })
                Text(label)
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().selectable(selected = bringBack, onClick = { bringBack = !bringBack }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = bringBack, onCheckedChange = { bringBack = it })
                Column {
                    Text("Also bring back SMS transactions I deleted")
                    Muted("Off: anything you deleted stays deleted.")
                }
            }
        }
        item {
            Button(
                onClick = {
                    if (!smsGranted) onAskPermission() else {
                        started = true
                        vm.importInbox(months, bringBack)
                    }
                },
                enabled = !state.running,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) { Text(if (smsGranted) "Rescan now" else "Allow SMS access") }
        }
        if (state.running) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.padding(4.dp))
                    Text("Reading messages…")
                }
            }
        }
        if (started && !state.running) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val r = state.scan
                        when {
                            state.error != null -> Text(state.error.orEmpty(), color = ExpenseRed)
                            r != null -> {
                                Text("Done", style = MaterialTheme.typography.titleSmall)
                                Text("Checked ${r.messages} bank messages.")
                                Text("Added ${r.added} new transactions.")
                                Text("${r.alreadyHere} were already here and were left as they are.")
                                if (r.skippedDeleted > 0) Text("Skipped ${r.skippedDeleted} you had deleted.")
                                if (r.restored > 0) Text("Brought back ${r.restored} deleted transactions.")
                            }
                        }
                    }
                }
            }
        }
    }
}
