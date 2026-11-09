package com.myfinance.app.ui.v15

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.myfinance.app.ui.FinanceViewModel
import com.myfinance.app.ui.theme.ExpenseRed
import com.myfinance.app.ui.theme.IncomeGreen
import com.myfinance.app.ui.v12.Hint12
import com.myfinance.app.ui.v12.InfoCard
import com.myfinance.app.ui.v12.Section12
import com.myfinance.app.ui.v12.shortDate
import com.myfinance.core.Ask
import com.myfinance.core.Dates
import com.myfinance.core.TxnType
import com.myfinance.core.formatInr
import java.time.ZoneId

/** "How much on Swiggy last year?" answered on the phone. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AskScreen(vm: FinanceViewModel, onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val s = vm.v15
    val answer by s.answer.collectAsState()
    var question by rememberSaveable { mutableStateOf("") }

    V15Screen("Ask", s, onBack) {
        item {
            OutlinedTextField(
                value = question,
                onValueChange = { question = it },
                label = { Text("Ask about your money") },
                placeholder = { Text("How much on Swiggy last year?") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { s.ask(question) }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item { Button(onClick = { s.ask(question) }, modifier = Modifier.fillMaxWidth()) { Text("Ask") } }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Ask.examples.forEach { ex -> SuggestionChip(onClick = { question = ex; s.ask(ex) }, label = { Text(ex) }) }
            }
        }
        val a = answer
        if (a == null) {
            item { Hint12("Ask about a payee, category or label over any time: today, last week, this month, March, 2025, this financial year, the last 3 months. Answers are worked out on your phone; nothing is sent anywhere.") }
        } else {
            item {
                InfoCard {
                    Text(a.headline, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    a.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    Hint12("Read as: ${a.understood}")
                }
            }
            if (a.top.isNotEmpty()) {
                item { Section12("Breakdown") }
                items(a.top) { t -> InfoCard { Row15(t.name, "${t.count} payments", formatInr(t.totalMinor)) } }
            }
            if (a.matches.isNotEmpty()) {
                item { Section12("Transactions (${a.matches.size})") }
                items(a.matches.take(200), key = { it.id }) { t ->
                    val d = Dates.localDate(t.timestamp, ZoneId.systemDefault())
                    val credit = t.type == TxnType.CREDIT
                    InfoCard {
                        Row15(
                            t.merchant ?: t.category, "${d.format(shortDate)} · ${t.category}",
                            (if (credit) "+" else "") + formatInr(t.amountMinor), if (credit) IncomeGreen else ExpenseRed,
                        ) { onOpen(t.id) }
                    }
                }
            }
        }
    }
}
