package com.myfinance.app.ui.v16

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.myfinance.app.ui.FinanceViewModel
import com.myfinance.app.ui.theme.Amber
import com.myfinance.app.ui.v12.Hint12
import com.myfinance.app.ui.v12.InfoCard
import com.myfinance.app.ui.v15.Row15
import com.myfinance.core.formatInr
import java.time.YearMonth

/** Review a finished month and lock it, so later imports don't quietly change it. */
@Composable
fun MonthCloseScreen(vm: FinanceViewModel, onBack: () -> Unit, open: (String) -> Unit) {
    val s = vm.v16
    val data by s.monthClose.collectAsState()
    val inbox by s.inbox.collectAsState()
    var reopen by remember { mutableStateOf<YearMonth?>(null) }
    V16Screen("Monthly close", s, onBack) {
        item {
            Hint12(
                "At the end of a month, check it and close it. Statement imports then leave a closed month alone, and an SMS that " +
                    "arrives late for it is added but shown in the Review inbox, so nothing changes without you seeing it.",
            )
        }
        if (inbox.isNotEmpty()) item {
            InfoCard {
                Text("${inbox.size} things to review first", color = Amber)
                TextButton(onClick = { open("review") }) { Text("Open the Review inbox") }
            }
        }
        if (data.open.isEmpty()) item { InfoCard { Text("Every finished month is closed.") } }
        items(data.open, key = { "o" + it.month }) { m ->
            InfoCard {
                Text(m.month.label16(), style = MaterialTheme.typography.titleSmall)
                Row15("Spent", "${m.count} entries", formatInr(m.spentMinor))
                Row15("Received", null, formatInr(m.receivedMinor))
                m.topCategories.forEach { (c, v) -> Row15(c, null, formatInr(v)) }
                if (m.uncategorized > 0) Text("${m.uncategorized} entries have no category", color = Amber, style = MaterialTheme.typography.bodySmall)
                Row { Button(onClick = { s.closeMonth(m.month) }) { Text("Close ${m.month.month.name.lowercase().replaceFirstChar(Char::uppercase)}") } }
            }
        }
        if (data.closed.isNotEmpty()) {
            item { Text("Closed", style = MaterialTheme.typography.titleSmall) }
            items(data.closed, key = { "c$it" }) { m ->
                InfoCard { Row15(m.label16(), "Locked", "Reopen") { reopen = m } }
            }
        }
    }
    reopen?.let { m ->
        AlertDialog(
            onDismissRequest = { reopen = null },
            title = { Text("Reopen ${m.label16()}?") },
            text = { Text("Statement imports can then add to and update this month again.") },
            confirmButton = { TextButton(onClick = { s.reopenMonth(m); reopen = null }) { Text("Reopen") } },
            dismissButton = { TextButton(onClick = { reopen = null }) { Text("Cancel") } },
        )
    }
}
