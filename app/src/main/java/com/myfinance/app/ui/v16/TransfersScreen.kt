package com.myfinance.app.ui.v16

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.myfinance.app.ui.FinanceViewModel
import com.myfinance.app.ui.v12.Hint12
import com.myfinance.app.ui.v12.InfoCard
import com.myfinance.app.ui.v15.Row15
import com.myfinance.core.TxnType
import com.myfinance.core.formatInr

/** Money moved between own accounts, and card bill payments, that would otherwise count as spending twice. */
@Composable
fun TransfersScreen(vm: FinanceViewModel, onBack: () -> Unit, openTxn: (Long) -> Unit) {
    val s = vm.v16
    val list by s.transfers.collectAsState()
    V16Screen("Transfers & card bills", s, onBack) {
        item {
            Hint12(
                "Moving money from one of your accounts to another isn't spending, and paying a card bill isn't either: the card's " +
                    "spends are already counted. These look like that. \"Don't count\" leaves both sides out of totals; they stay in your lists.",
            )
        }
        if (list.isEmpty()) item { InfoCard { Text("Nothing found. Transfers you already left out of totals don't show here.") } }
        else item {
            Row {
                Button(onClick = { s.dontCount(list) }) { Text("Don't count all ${list.size}") }
            }
        }
        items(list, key = { it.key }) { m ->
            InfoCard {
                Text(m.kind.label + " · " + formatInr(m.amountMinor), style = MaterialTheme.typography.titleSmall)
                m.rows.forEach { r ->
                    Row15(
                        (if (r.type == TxnType.DEBIT) "Out of " else "Into ") + (r.account ?: "unknown account"),
                        listOfNotNull(r.merchant, r.timestamp.day16(), r.owner).joinToString(" · "),
                        (if (r.type == TxnType.DEBIT) "-" else "+") + formatInr(r.amountMinor),
                    ) { openTxn(r.id) }
                }
                Row {
                    TextButton(onClick = { s.dontCount(listOf(m)) }) { Text("Don't count") }
                    TextButton(onClick = { s.notTransfer(m) }) { Text("It's real spending") }
                }
            }
        }
    }
}
