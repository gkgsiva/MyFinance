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
import com.myfinance.app.ui.theme.IncomeGreen
import com.myfinance.app.ui.v12.Hint12
import com.myfinance.app.ui.v12.InfoCard
import com.myfinance.app.ui.v15.Row15
import com.myfinance.core.formatInr

/** Missing months, accounts with no recent SMS, and likely duplicates. */
@Composable
fun HealthScreen(vm: FinanceViewModel, onBack: () -> Unit, openTxn: (Long) -> Unit, open: (String) -> Unit) {
    val s = vm.v16
    val r by s.health.collectAsState()
    V16Screen("Data health check", s, onBack) {
        val h = r
        if (h == null) { item { Text("Checking…") }; return@V16Screen }
        item {
            InfoCard {
                Text(if (h.problems == 0) "Looks healthy" else "${h.problems} things to look at", style = MaterialTheme.typography.titleMedium, color = if (h.problems == 0) IncomeGreen else androidx.compose.ui.graphics.Color.Unspecified)
                if (h.uncategorized > 0) Text("${h.uncategorized} entries have no category.", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (h.missingMonths.isNotEmpty()) {
            item { Text("Months with no entries", style = MaterialTheme.typography.titleSmall) }
            item {
                InfoCard {
                    Text(h.missingMonths.joinToString(", ") { it.label16() })
                    Hint12("SMS for these months may have been deleted. Import a bank statement for them to fill the gap.")
                    TextButton(onClick = { open("import-statement") }) { Text("Import a statement") }
                }
            }
        }
        if (h.quietAccounts.isNotEmpty()) {
            item { Text("Accounts with no recent SMS", style = MaterialTheme.typography.titleSmall) }
            items(h.quietAccounts, key = { "q" + it.account }) { q ->
                InfoCard {
                    Row15(q.account, "Last entry ${q.lastSeen.format(dayFmt16)}, usually about ${q.usualPerMonth} a month", "${q.daysQuiet} days")
                    Hint12("Closed the account, or the bank stopped sending SMS? If you still use it, import its statement.")
                }
            }
        }
        if (h.duplicates.isNotEmpty()) {
            item { Text("Possible duplicates", style = MaterialTheme.typography.titleSmall) }
            items(h.duplicates, key = { it.key }) { p ->
                InfoCard {
                    listOf(p.a, p.b).forEach { x ->
                        Row15(x.merchant ?: "Entry", listOfNotNull(x.account, x.timestamp.day16(), if (x.manual) "typed in" else if (x.statement) "statement" else "SMS").joinToString(" · "), formatInr(x.amountMinor)) { openTxn(x.id) }
                    }
                    Row {
                        TextButton(onClick = { s.removeDuplicate(p) }) { Text("Remove one") }
                        TextButton(onClick = { s.notDuplicate(p) }) { Text("Both are real") }
                    }
                }
            }
        }
    }
}
