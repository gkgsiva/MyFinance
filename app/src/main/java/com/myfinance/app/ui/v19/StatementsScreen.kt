package com.myfinance.app.ui.v19

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.myfinance.app.data.TxnSource
import com.myfinance.app.ui.FinanceViewModel
import com.myfinance.app.ui.WrapRow
import com.myfinance.app.ui.theme.ExpenseRed
import com.myfinance.app.ui.theme.IncomeGreen
import com.myfinance.app.ui.v13.Muted
import com.myfinance.app.ui.v13.SimpleScreen
import com.myfinance.core.LedgerRow
import com.myfinance.core.StatementPeriod
import com.myfinance.core.StatementViews
import com.myfinance.core.formatInr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val stmtDay = DateTimeFormatter.ofPattern("d MMM yyyy")
private val lineDay = DateTimeFormatter.ofPattern("d MMM")

private enum class Range(val label: String) { THIS("This"), LAST("Last"), CUSTOM("Pick dates") }

/**
 * A card's or bank account's entries for a period, laid out like its statement: every entry (statement lines,
 * SMS, own entries; card bill payments and reversals too, marked "not counted"), oldest first, with totals and a
 * running balance for the period. Cards follow their bill cycle (bill day in My cards & accounts), accounts the month.
 */
@Composable
fun StatementsScreen(vm: FinanceViewModel, onBack: () -> Unit, openTxn: (Long) -> Unit) {
    val all by vm.allTransactions.collectAsState()
    val mine by vm.myAccounts.collectAsState()
    val zone = ZoneId.systemDefault()

    // Registered cards and accounts first, then any other number seen in entries.
    val seen = all.mapNotNull { it.accountTail?.filter(Char::isDigit)?.takeLast(4)?.takeIf { t -> t.length >= 3 } }
        .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
    val registered = mine.map { it.tail }
    val tails = (registered + seen.filter { s -> registered.none { com.myfinance.core.StatementImport.sameAccount(it, s) } }).distinct()

    var tail by rememberSaveable { mutableStateOf<String?>(null) }
    val picked = tail ?: tails.firstOrNull()
    val account = mine.firstOrNull { it.tail == picked }
    val isCard = account?.isCard ?: true
    var range by rememberSaveable { mutableStateOf(Range.THIS) }
    var customFrom by rememberSaveable { mutableStateOf<String?>(null) }
    var customTo by rememberSaveable { mutableStateOf<String?>(null) }

    val today = LocalDate.now()
    val (from, to) = when (range) {
        Range.CUSTOM -> (customFrom?.let(LocalDate::parse) ?: today.withDayOfMonth(1)) to (customTo?.let(LocalDate::parse) ?: today)
        else -> {
            val billDay = account?.billDay
            val current = if (isCard && billDay != null) StatementViews.cardCycle(billDay, today) else StatementViews.month(today)
            if (range == Range.THIS) current
            else if (isCard && billDay != null) StatementViews.cardCycle(billDay, current.first.minusDays(1))
            else StatementViews.month(current.first.minusDays(1))
        }
    }

    val period by produceState<StatementPeriod?>(null, all, picked, from, to) {
        value = picked?.let { t ->
            withContext(Dispatchers.Default) {
                val rows = all.map { e ->
                    LedgerRow(
                        e.id, e.type, e.amountMinor, e.timestamp, e.accountTail,
                        e.merchant ?: e.rawSms?.removePrefix("Statement: ")?.substringBefore("\n")?.take(60) ?: "Entry",
                        fromStatement = e.source == TxnSource.STATEMENT, excluded = e.excluded, manual = e.source == TxnSource.MANUAL,
                    )
                }
                StatementViews.build(rows, t, from, to, zone)
            }
        }
    }

    SimpleScreen("Statements", onBack) {
        item { Muted("A card's or account's entries for a period, as its statement would list them: imported statement lines and SMS together. Cards follow their bill cycle when the bill day is set in My cards & accounts.") }
        if (tails.isEmpty()) {
            item { Muted("No cards or accounts yet. Add them in My cards & accounts, or import a statement.") }
            return@SimpleScreen
        }
        item {
            WrapRow {
                tails.forEach { t ->
                    val name = mine.firstOrNull { it.tail == t }?.label ?: "••$t"
                    FilterChip(selected = picked == t, onClick = { tail = t }, label = { Text(name) })
                }
            }
        }
        item {
            val unit = if (isCard && account?.billDay != null) "bill" else "month"
            WrapRow {
                Range.entries.forEach { r ->
                    FilterChip(selected = range == r, onClick = { range = r }, label = { Text(if (r == Range.CUSTOM) r.label else "${r.label} $unit") })
                }
            }
        }
        if (range == Range.CUSTOM) item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                com.myfinance.app.ui.v12.DateButton("From", from, { d -> customFrom = d?.toString() })
                com.myfinance.app.ui.v12.DateButton("To", to, { d -> customTo = d?.toString() })
            }
        }
        val p = period
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${from.format(stmtDay)} to ${to.format(stmtDay)}", fontWeight = FontWeight.SemiBold)
                    if (p == null) Muted("Loading…") else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(if (isCard) "Spends & charges" else "Money out"); Text(formatInr(p.debitsMinor), color = ExpenseRed)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(if (isCard) "Payments & refunds" else "Money in"); Text(formatInr(p.creditsMinor), color = IncomeGreen)
                        }
                        Muted("${p.lines.size} entries · ${p.fromStatementCount} from statements · ${p.fromSmsCount} from SMS")
                    }
                }
            }
        }
        if (p != null && p.lines.isEmpty()) item { Muted("Nothing on this ${if (isCard) "card" else "account"} in these days.") }
        if (p != null) items(p.lines.reversed(), key = { "${it.row.id}|${it.row.amountMinor}|${it.row.timestamp}|${it.runningMinor}" }) { l ->
            Column(Modifier.fillMaxWidth().clickable { openTxn(l.row.id) }.padding(vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(l.row.description, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Muted(
                            listOfNotNull(
                                l.date.format(lineDay),
                                if (l.row.fromStatement) "Statement" else if (l.row.manual) "Added" else "SMS",
                                if (l.row.excluded) "not counted" else null,
                            ).joinToString(" · "),
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        if (l.debitMinor > 0) Text("-" + formatInr(l.debitMinor), color = ExpenseRed)
                        else Text("+" + formatInr(l.creditMinor), color = IncomeGreen)
                        Muted("net " + formatInr(l.runningMinor))
                    }
                }
            }
            HorizontalDivider()
        }
    }
}
