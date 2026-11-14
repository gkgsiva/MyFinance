package com.myfinance.app.ui.v16

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
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
import com.myfinance.app.ui.FinanceViewModel
import com.myfinance.app.ui.theme.ExpenseRed
import com.myfinance.app.ui.v12.Hint12
import com.myfinance.app.ui.v12.InfoCard
import com.myfinance.app.ui.v15.Row15
import com.myfinance.core.AutoDebitItem
import com.myfinance.core.DuplicateGroup
import com.myfinance.core.Duplicates
import com.myfinance.core.ReviewKind
import com.myfinance.core.TransferMatch
import com.myfinance.core.formatInr

/** New entries the app isn't sure about: confirm or fix each with a tap. */
@Composable
fun ReviewInboxScreen(vm: FinanceViewModel, onBack: () -> Unit, openTxn: (Long) -> Unit, open: (String) -> Unit) {
    val s = vm.v16
    val items by s.inbox.collectAsState()
    var preview by remember { mutableStateOf<List<DuplicateGroup>?>(null) }
    V16Screen("Review inbox", s, onBack) {
        item { Hint12("Things the app isn't sure about. Fix or confirm each one; anything you answer here doesn't come back.") }
        if (s.sameIdRemoved > 0) item {
            Hint12("${s.sameIdRemoved} entries had the same transaction ID as another entry (one payment stored twice) and were removed for you. They are in Recently deleted if you need them back.")
        }
        if (items.isEmpty()) item { InfoCard { Text("All clear. Nothing to review.") } }
        val groups = items.groupBy { it.kind }
        ReviewKind.entries.forEach { kind ->
            val list = groups[kind].orEmpty()
            if (list.isEmpty()) return@forEach
            item { Text("${kind.label} (${list.size})", style = MaterialTheme.typography.titleSmall) }
            if (kind == ReviewKind.DUPLICATE) item {
                val dups = list.mapNotNull { it.ref as? DuplicateGroup }
                InfoCard {
                    Text("${Duplicates.extraCount(dups)} extra copies in ${dups.size} payments", style = MaterialTheme.typography.bodyMedium)
                    Hint12("One entry of each payment is kept (the statement line, or the one you edited). The rest go to Recently deleted, so you can bring any back. Payments with different transaction IDs are never shown here: they are real.")
                    Button(onClick = { preview = dups }) { Text("Review and remove all") }
                }
            }
            items(list, key = { it.key }) { r ->
                InfoCard {
                    Row15(r.title, r.detail + " · " + r.timestamp.day16(), r.amountMinor?.let { formatInr(it) } ?: "", if (kind == ReviewKind.FAMILY_LIMIT) ExpenseRed else androidx.compose.ui.graphics.Color.Unspecified) {
                        r.txnId?.let(openTxn)
                    }
                    Row {
                        when (kind) {
                            ReviewKind.UNKNOWN_PAYEE -> {
                                r.txnId?.let { id -> TextButton(onClick = { openTxn(id) }) { Text("Set category") } }
                                TextButton(onClick = { s.answer(r.key) }) { Text("Leave as is") }
                            }
                            ReviewKind.RECURRING -> {
                                val item = r.ref as AutoDebitItem
                                TextButton(onClick = { s.answer(r.key) }) { Text("Yes, recurring") }
                                TextButton(onClick = { vm.markNotRecurring(item); s.answer(r.key, "no") }) { Text("Not recurring") }
                                TextButton(onClick = { open("autodebits") }) { Text("Open list") }
                            }
                            ReviewKind.TRANSFER -> {
                                val m = r.ref as TransferMatch
                                TextButton(onClick = { s.dontCount(listOf(m)) }) { Text("Don't count") }
                                TextButton(onClick = { s.notTransfer(m) }) { Text("It's real spending") }
                            }
                            ReviewKind.DUPLICATE -> {
                                val g = r.ref as DuplicateGroup
                                TextButton(onClick = { s.removeDuplicate(g) }) { Text(if (g.rows.size == 2) "Remove one" else "Keep one") }
                                TextButton(onClick = { s.notDuplicate(g) }) { Text(if (g.rows.size == 2) "Both are real" else "All are real") }
                            }
                            ReviewKind.LATE -> {
                                TextButton(onClick = { s.lateSeen(r); s.answer(r.key) }) { Text("Fine, keep it") }
                                r.txnId?.let { id -> TextButton(onClick = { openTxn(id) }) { Text("Open") } }
                            }
                            ReviewKind.FAMILY_LIMIT -> {
                                TextButton(onClick = { open("family-upi") }) { Text("See family UPI") }
                                TextButton(onClick = { s.answer(r.key) }) { Text("OK for this month") }
                            }
                        }
                    }
                }
            }
        }
    }
    preview?.let { groups ->
        val extra = Duplicates.extraCount(groups)
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text("Remove $extra extra copies?") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text("One entry of each payment below stays. The others move to Recently deleted, where you can restore them.", style = MaterialTheme.typography.bodySmall)
                    groups.take(PREVIEW_LIMIT).forEach { g ->
                        Text("${g.title} · ${g.timestamp.day16()} · ${formatInr(g.amountMinor)} × ${g.rows.size}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (groups.size > PREVIEW_LIMIT) Text("and ${groups.size - PREVIEW_LIMIT} more", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { s.removeDuplicates(groups); preview = null }) { Text("Remove $extra") } },
            dismissButton = { TextButton(onClick = { preview = null }) { Text("Cancel") } },
        )
    }
}

private const val PREVIEW_LIMIT = 200
