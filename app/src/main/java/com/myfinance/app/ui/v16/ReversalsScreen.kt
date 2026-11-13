package com.myfinance.app.ui.v16

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.myfinance.app.data.TransactionEntity
import com.myfinance.app.ui.FinanceViewModel
import com.myfinance.app.ui.v12.Hint12
import com.myfinance.app.ui.v12.InfoCard
import com.myfinance.app.ui.v15.Row15
import com.myfinance.core.TxnType
import com.myfinance.core.formatInr

private fun TransactionEntity.whereWhen(): String =
    listOfNotNull(merchant, (timestamp).day16() + " " + timeOf(timestamp), accountTail?.let { "a/c ••" + it.filter(Char::isDigit).takeLast(4) })
        .joinToString(" · ")

private fun timeOf(ts: Long): String =
    java.time.Instant.ofEpochMilli(ts).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("h:mm a"))

/**
 * Payments whose money came back: failed and reversed UPI payments, refunds, autopay holds that were released.
 * Neither side counts as spending or income; "Not a reversal" makes both count again.
 */
@Composable
fun ReversalsScreen(vm: FinanceViewModel, onBack: () -> Unit, openTxn: (Long) -> Unit) {
    val s = vm.v16
    val list by s.reversals.collectAsState()
    V16Screen("Reversed & refunded", s, onBack) {
        item {
            Hint12(
                "A payment that failed and came back, a refund of the same amount, or an autopay hold that was released. " +
                    "The payment and the money back cancel out, so neither is counted as spending or as money received. " +
                    "If one isn't really a reversal, tap \"Not a reversal\" and both count again.",
            )
        }
        item { Row { OutlinedButton(onClick = { s.findReversals() }) { Text("Look again") } } }
        if (list.isEmpty()) item { InfoCard { Text("Nothing here yet. Payments that come back are paired as messages and statements come in.") } }
        items(list, key = { it.key }) { r ->
            InfoCard {
                Text(r.kind.label + " · " + formatInr(r.amountMinor), style = MaterialTheme.typography.titleSmall)
                for (t in listOf(r.debit, r.credit)) {
                    Row15(
                        if (t.type == TxnType.DEBIT) "Paid" else "Came back",
                        t.whereWhen(),
                        (if (t.type == TxnType.DEBIT) "-" else "+") + formatInr(t.amountMinor),
                    ) { openTxn(t.id) }
                }
                Row { TextButton(onClick = { s.undoReversal(r.key) }) { Text("Not a reversal") } }
            }
        }
    }
}
