package com.myfinance.app.ui.v15

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.myfinance.app.data.CapitalGainEntity
import com.myfinance.app.security.AppLock
import com.myfinance.app.ui.FinanceViewModel
import com.myfinance.app.ui.theme.ExpenseRed
import com.myfinance.app.ui.theme.IncomeGreen
import com.myfinance.app.ui.v12.AmountField
import com.myfinance.app.ui.v12.DateButton
import com.myfinance.app.ui.v12.Dropdown
import com.myfinance.app.ui.v12.Hint12
import com.myfinance.app.ui.v12.InfoCard
import com.myfinance.app.ui.v12.Section12
import com.myfinance.app.ui.v12.asInput
import com.myfinance.app.ui.v12.shortDate
import com.myfinance.core.Dates
import com.myfinance.core.GainAsset
import com.myfinance.core.TaxHelper
import com.myfinance.core.TaxPack
import com.myfinance.core.formatInr
import com.myfinance.core.parseAmountToMinor
import java.time.LocalDate
import java.time.ZoneId

/** One financial year's numbers for ITR filing: income, interest, gains and deductions. */
@Composable
fun TaxPackScreen(vm: FinanceViewModel, onBack: () -> Unit, onOpenTaxHelper: () -> Unit) {
    val s = vm.v15
    val fy by s.taxYear.collectAsState()
    val pack by s.taxPack.collectAsState()
    val context = LocalContext.current
    var editing by remember { mutableStateOf<CapitalGainEntity?>(null) }
    var adding by remember { mutableStateOf(false) }

    val save = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    ) { uri -> uri?.let { s.exportTaxPack(it) } }

    V15Screen("Tax-time pack", s, onBack) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { s.taxYear.value = fy - 1 }) { Text("‹ Earlier") }
                Text(TaxHelper.fyLabel(fy), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).align(androidx.compose.ui.Alignment.CenterVertically))
                OutlinedButton(onClick = { s.taxYear.value = fy + 1 }, enabled = fy < TaxHelper.fyStartYear(LocalDate.now())) { Text("Later ›") }
            }
        }
        item { Hint12("What the app knows for 1 Apr $fy to 31 Mar ${fy + 1}, gathered for filing your return. It doesn't work out the tax: check it against Form 16, Form 26AS and AIS.") }
        val p = pack
        if (p == null) {
            item { Hint12("Working it out…") }
        } else {
            item {
                InfoCard {
                    Text("Income", style = MaterialTheme.typography.titleSmall)
                    Row15("Salary received", "${p.salaryCredits} credits, after TDS", formatInr(p.salaryMinor))
                    Row15("Savings interest", "Other sources", formatInr(p.savingsInterestMinor))
                    Row15("FD / RD interest", "Other sources", formatInr(p.depositInterestMinor))
                    if (p.otherInterestMinor > 0) Row15("Other interest", "Bonds, NSC, SCSS", formatInr(p.otherInterestMinor))
                    Row15("Other money received", "Refunds, gifts, transfers; most aren't income", formatInr(p.otherIncomeMinor))
                }
            }
            item {
                InfoCard {
                    Text("Deductions (old regime)", style = MaterialTheme.typography.titleSmall)
                    if (p.deductions.isEmpty()) Hint12("No tax-saving payments tagged for this year yet.")
                    p.deductions.forEach { d ->
                        Row15(d.section.label.substringBefore(':'), d.remainingMinor?.let { "Room left ${formatInr(it)}" }, formatInr(d.countedMinor))
                    }
                    Row15("80TTA savings interest", "Up to ₹10,000", formatInr(p.tta80Minor))
                    Row15("Total counted", null, formatInr(p.deductionTotalMinor + p.tta80Minor), IncomeGreen)
                    TextButton(onClick = onOpenTaxHelper) { Text("Tag tax-saving payments") }
                }
            }
            item {
                InfoCard {
                    Text("Capital gains", style = MaterialTheme.typography.titleSmall)
                    val g = p.gainTotals
                    Row15("Equity, short term", "Held 12 months or less", formatInr(g.equityShortMinor))
                    Row15("Equity, long term", "First ${formatInr(TaxPack.EQUITY_LTCG_EXEMPT_MINOR)} a year is exempt", formatInr(g.equityLongMinor))
                    Row15("Other, short term", "Taxed at your slab rate", formatInr(g.otherShortMinor))
                    Row15("Other, long term", "Gold, property after 24 months", formatInr(g.otherLongMinor))
                    TextButton(onClick = { adding = true }) { Text("Add a sale") }
                }
            }
            if (p.gains.isNotEmpty()) {
                item { Section12("Sales in this year") }
                items(p.gains, key = { it.entry.id }) { l ->
                    InfoCard {
                        Row15(
                            l.entry.name,
                            "${l.entry.asset.label} · ${if (l.longTerm) "long" else "short"} term · held ${l.heldMonths} months · sold ${l.entry.sellDate.format(shortDate)}",
                            formatInr(l.gainMinor), if (l.gainMinor >= 0) IncomeGreen else ExpenseRed,
                        ) { editing = s.gains.value.firstOrNull { it.id == l.entry.id } }
                    }
                }
            }
            if (p.interest.isNotEmpty()) {
                item { Section12("Interest credits") }
                items(p.interest, key = { it.txn.id }) { i ->
                    InfoCard {
                        Row15(
                            i.kind.label, "${Dates.localDate(i.txn.timestamp, ZoneId.systemDefault()).format(shortDate)} · ${i.txn.account ?: i.txn.merchant ?: ""}",
                            formatInr(i.txn.amountMinor),
                        )
                    }
                }
            }
            item {
                com.myfinance.app.ui.WrapRow(Modifier, 8.dp) {
                    Button(onClick = {
                        AppLock.expectExternalActivity()
                        save.launch("MyFinance tax pack ${TaxHelper.fyLabel(fy).removePrefix("FY ")}.xlsx")
                    }) { Text("Save as Excel") }
                    OutlinedButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, TaxPack.summaryText(p))
                        AppLock.expectExternalActivity()
                        runCatching { context.startActivity(Intent.createChooser(send, "Share summary")) }
                    }) { Text("Share summary") }
                }
            }
        }
    }

    if (adding || editing != null) {
        val e = editing
        var name by remember { mutableStateOf(e?.name.orEmpty()) }
        var asset by remember { mutableStateOf(e?.toEntry()?.asset ?: GainAsset.EQUITY) }
        var buyDate by remember { mutableStateOf(e?.let { LocalDate.ofEpochDay(it.buyEpochDay) } ?: LocalDate.now().minusYears(1)) }
        var sellDate by remember { mutableStateOf(e?.let { LocalDate.ofEpochDay(it.sellEpochDay) } ?: LocalDate.now()) }
        var cost by remember { mutableStateOf(e?.buyMinor?.asInput().orEmpty()) }
        var sale by remember { mutableStateOf(e?.sellMinor?.asInput().orEmpty()) }
        var error by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { adding = false; editing = null },
            title = { Text(if (e == null) "Add a sale" else "Edit sale") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("What you sold, e.g. Nifty 50 fund") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Dropdown("Kind", asset, GainAsset.entries, { it.label }, { asset = it })
                    DateButton("Bought on", buyDate, { d -> d?.let { buyDate = it } })
                    AmountField("Cost (₹)", cost, { cost = it; error = false }, error)
                    DateButton("Sold on", sellDate, { d -> d?.let { sellDate = it } })
                    AmountField("Sale value (₹)", sale, { sale = it; error = false }, error)
                    Hint12("For shares and funds, your broker's or CAMS capital gains statement has these numbers.")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val c = parseAmountToMinor(cost)
                    val v = parseAmountToMinor(sale)
                    if (c == null || v == null || name.isBlank() || sellDate.isBefore(buyDate)) { error = true; return@TextButton }
                    s.saveGain(
                        CapitalGainEntity(
                            id = e?.id ?: java.util.UUID.randomUUID().toString(), name = name.trim(), asset = asset.name,
                            buyEpochDay = buyDate.toEpochDay(), buyMinor = c, sellEpochDay = sellDate.toEpochDay(), sellMinor = v,
                        ),
                    )
                    adding = false; editing = null
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    if (e != null) TextButton(onClick = { s.deleteGain(e.id); editing = null }) { Text("Delete") }
                    TextButton(onClick = { adding = false; editing = null }) { Text("Cancel") }
                }
            },
        )
    }
}
