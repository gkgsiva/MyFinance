package com.myfinance.app.ui.v16

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.myfinance.app.security.AppLock
import com.myfinance.app.ui.FinanceViewModel
import com.myfinance.app.ui.v12.AmountField
import com.myfinance.app.ui.v12.DateButton
import com.myfinance.app.ui.v12.Dropdown
import com.myfinance.app.ui.v12.Hint12
import com.myfinance.app.ui.v12.InfoCard
import com.myfinance.app.ui.v15.Row15
import com.myfinance.core.Fx
import com.myfinance.core.HeavySort
import com.myfinance.core.TxnType
import com.myfinance.core.formatInr
import com.myfinance.core.parseAmountToMinor
import java.time.LocalDate

/** Everything, with labels, payees and categories, as an Excel workbook or CSV. */
@Composable
fun ExportScreen(vm: FinanceViewModel, onBack: () -> Unit) {
    val s = vm.v16
    var excel by remember { mutableStateOf(true) }
    val today = LocalDate.now()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) s.exportAll(uri, excel)
    }
    V16Screen("Export to Excel or Sheets", s, onBack) {
        item {
            Hint12(
                "A full copy of your data for your own analysis: every entry with its category, payee, labels, account, person and note, " +
                    "plus sheets of months, categories by month, labels and payees. To use Google Sheets, save it to Google Drive in the " +
                    "file picker, then open it with Sheets.",
            )
        }
        item {
            InfoCard {
                Button(onClick = { excel = true; AppLock.expectExternalActivity(); pick.launch("MyFinance $today.xlsx") }, modifier = Modifier.fillMaxWidth()) { Text("Export as Excel (.xlsx)") }
                OutlinedButton(onClick = { excel = false; AppLock.expectExternalActivity(); pick.launch("MyFinance $today.csv") }, modifier = Modifier.fillMaxWidth()) { Text("Export as CSV") }
            }
        }
    }
}

/** Rates for other currencies, and spends made in them. */
@Composable
fun CurrencyScreen(vm: FinanceViewModel, onBack: () -> Unit, openTxn: (Long) -> Unit) {
    val s = vm.v16
    val rates by s.fxRates.collectAsState()
    val rows by s.foreign.collectAsState()
    var editing by remember { mutableStateOf<String?>(null) }
    V16Screen("Foreign currency", s, onBack) {
        item {
            Hint12(
                "Card SMS in USD, EUR and other currencies are turned into rupees with these rates, and the foreign amount is kept in the " +
                    "note. Typing an entry? Tap \"Paid in another currency?\" under the amount. When the card statement comes, its rupee amount replaces the estimate.",
            )
        }
        val totals = Fx.totals(rows.map { it.foreign to it.txn.amountMinor })
        if (totals.isNotEmpty()) {
            item { Text("Spent abroad or in other currencies", style = MaterialTheme.typography.titleSmall) }
            item { InfoCard { totals.forEach { t -> Row15(t.code + " " + java.math.BigDecimal.valueOf(t.foreignMinor, 2).toPlainString(), "${t.count} entries", formatInr(t.inrMinor)) } } }
            items(rows.take(100), key = { "fx" + it.txn.id }) { r ->
                InfoCard { Row15(r.txn.merchant ?: r.txn.category, r.foreign.amount.text + " · " + r.txn.timestamp.day16(), formatInr(r.txn.amountMinor)) { openTxn(r.txn.id) } }
            }
        }
        item { Text("Rates (₹ for 1 unit)", style = MaterialTheme.typography.titleSmall) }
        item {
            InfoCard {
                rates.forEach { (code, rate) -> Row15(code, null, "₹" + rate) { editing = code } }
            }
        }
    }
    editing?.let { code ->
        var v by remember { mutableStateOf(rates[code]?.toString().orEmpty()) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("₹ for 1 $code") },
            text = { OutlinedTextField(v, { x -> v = x.filter { it.isDigit() || it == '.' }.take(10) }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { v.toDoubleOrNull()?.takeIf { it > 0 }?.let { s.setFxRate(code, it) }; editing = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}

private enum class Period(val label: String) { MONTH("This month"), THREE("3 months"), SIX("6 months"), YEAR("This year"), FY("This FY"), CUSTOM("Pick dates") }

/** Payments at or above a threshold you set, in a period you choose. */
@Composable
fun HeavySpendsScreen(vm: FinanceViewModel, onBack: () -> Unit, openTxn: (Long) -> Unit) {
    val s = vm.v16
    val today = LocalDate.now()
    var threshold by remember { mutableStateOf("10000") }
    var period by remember { mutableStateOf(Period.THREE) }
    var from by remember { mutableStateOf(today.minusMonths(3)) }
    var to by remember { mutableStateOf(today) }
    var sort by remember { mutableStateOf(HeavySort.AMOUNT) }
    var income by remember { mutableStateOf(false) }
    var account by remember { mutableStateOf<String?>(null) }
    var label by remember { mutableStateOf<String?>(null) }
    val options by vm.filterOptions.collectAsState()
    val labels by vm.usedLabels.collectAsState()
    val all by vm.allTransactions.collectAsState()

    val range = when (period) {
        Period.MONTH -> today.withDayOfMonth(1) to today
        Period.THREE -> today.minusMonths(3) to today
        Period.SIX -> today.minusMonths(6) to today
        Period.YEAR -> today.withDayOfYear(1) to today
        Period.FY -> (if (today.monthValue >= 4) LocalDate.of(today.year, 4, 1) else LocalDate.of(today.year - 1, 4, 1)) to today
        Period.CUSTOM -> from to to
    }
    val min = parseAmountToMinor(threshold) ?: 0
    val result = remember(all, min, range, sort, income, account, label) {
        s.heavy(min, range.first, range.second, sort, if (income) TxnType.CREDIT else TxnType.DEBIT, account, label)
    }

    V16Screen("Heavy spends", s, onBack) {
        item {
            InfoCard {
                AmountField("At or above (₹)", threshold, { threshold = it })
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf("5000", "10000", "25000", "50000").forEach { v ->
                        FilterChip(selected = threshold == v, onClick = { threshold = v }, label = { Text("₹" + v.toLong().let { if (it >= 1000) "${it / 1000}k" else "$it" }) })
                    }
                }
                Dropdown("Period", period, Period.entries, { it.label }, { period = it })
                if (period == Period.CUSTOM) {
                    DateButton("From", from, { d -> d?.let { from = it } })
                    DateButton("To", to, { d -> d?.let { to = it } })
                }
                Dropdown("Sort", sort, HeavySort.entries, { it.label }, { sort = it })
                Dropdown("Show", income, listOf(false, true), { if (it) "Big money received" else "Big spends" }, { income = it })
                if (options.second.isNotEmpty()) Dropdown("Account", account, listOf<String?>(null) + options.second, { it ?: "All accounts" }, { account = it })
                if (labels.isNotEmpty()) Dropdown("Label", label, listOf<String?>(null) + labels, { it ?: "Any label" }, { label = it })
            }
        }
        item {
            InfoCard {
                Text("${result.rows.size} ${if (income) "credits" else "payments"} · ${formatInr(result.totalMinor)}", style = MaterialTheme.typography.titleMedium)
                result.shareOfSpendPercent?.let { Text("$it% of everything ${if (income) "received" else "spent"} in this period", style = MaterialTheme.typography.bodySmall) }
            }
        }
        items(result.rows.take(300), key = { "h" + it.id }) { t ->
            InfoCard { Row15(t.merchant ?: t.category, listOfNotNull(t.category, t.account, t.timestamp.day16()).joinToString(" · "), formatInr(t.amountMinor)) { openTxn(t.id) } }
        }
    }
}
