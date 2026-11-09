package com.myfinance.app.ui.v16

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.myfinance.app.ui.FinanceViewModel
import com.myfinance.app.ui.theme.ExpenseRed
import com.myfinance.app.ui.v12.Hint12
import com.myfinance.app.ui.v12.InfoCard
import com.myfinance.app.ui.v15.Row15
import com.myfinance.core.AutoDebitItem
import com.myfinance.core.DuplicatePair
import com.myfinance.core.ReviewKind
import com.myfinance.core.TransferMatch
import com.myfinance.core.formatInr

/** New entries the app isn't sure about: confirm or fix each with a tap. */
@Composable
fun ReviewInboxScreen(vm: FinanceViewModel, onBack: () -> Unit, openTxn: (Long) -> Unit, open: (String) -> Unit) {
    val s = vm.v16
    val items by s.inbox.collectAsState()
    V16Screen("Review inbox", s, onBack) {
        item { Hint12("Things the app isn't sure about. Fix or confirm each one; anything you answer here doesn't come back.") }
        if (items.isEmpty()) item { InfoCard { Text("All clear. Nothing to review.") } }
        val groups = items.groupBy { it.kind }
        ReviewKind.entries.forEach { kind ->
            val list = groups[kind].orEmpty()
            if (list.isEmpty()) return@forEach
            item { Text("${kind.label} (${list.size})", style = MaterialTheme.typography.titleSmall) }
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
                                val p = r.ref as DuplicatePair
                                TextButton(onClick = { s.removeDuplicate(p) }) { Text("Remove one") }
                                TextButton(onClick = { s.notDuplicate(p) }) { Text("Both are real") }
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
}
