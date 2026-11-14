package com.myfinance.app.ui.v19

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.myfinance.app.ui.FinanceViewModel
import com.myfinance.app.ui.WrapRow
import com.myfinance.app.ui.theme.ExpenseRed
import com.myfinance.app.ui.theme.IncomeGreen
import com.myfinance.app.ui.v13.Muted
import com.myfinance.app.ui.v13.SimpleScreen
import com.myfinance.core.HealthKind
import com.myfinance.core.MonthHealth
import com.myfinance.core.SuspectKind
import com.myfinance.core.formatInr
import com.myfinance.core.parseAmountToMinor
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val monYear = DateTimeFormatter.ofPattern("MMMM yyyy")
private val monShort = DateTimeFormatter.ofPattern("MMM yyyy")
private val AmberWarn = Color(0xFFE08E0B)

// ---- Year-end report ----

/** Settings / Reports > Year-end report (PDF): twelve months, tax items, investments and receipts in one printout. */
@Composable
fun YearPdfScreen(vm: FinanceViewModel, onBack: () -> Unit) {
    val year by vm.reportYear.collectAsState()
    val fy by vm.reportFinancialYear.collectAsState()
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri -> uri?.let(vm::saveYearPdf) }
    val label = if (fy) com.myfinance.core.Dates.financialYearLabel(year) else "$year"
    val now = LocalDate.now()
    val latest = if (fy) com.myfinance.core.TaxHelper.fyStartYear(now) else now.year
    SimpleScreen("Year-end report (PDF)", onBack) {
        item {
            WrapRow(Modifier, 8.dp) {
                FilterChip(selected = fy, onClick = { vm.setReportFinancialYear(true) }, label = { Text("Financial year (Apr–Mar)") })
                FilterChip(selected = !fy, onClick = { vm.setReportFinancialYear(false) }, label = { Text("Calendar year") })
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.setReportYear(year - 1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Earlier year") }
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                IconButton(onClick = { vm.setReportYear(year + 1) }, enabled = year < latest) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Later year")
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("What's in it", style = MaterialTheme.typography.titleSmall)
                    listOf(
                        "The year's received, spent, invested and left over, and card spends",
                        "Twelve months side by side, with whether each month's bank balances match",
                        "Income and expenditure for the year, spending by category, family members, loans and EMIs, investments",
                        "For your tax return (financial year): salary, interest, deductions you tagged, 80TTA, capital gains",
                        "Every receipt and PDF bill you attached, and the year's biggest spends",
                        "Bank months that don't add up, to check",
                    ).forEach { Text("•  $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        item {
            PdfButtons(vm, "MyFinance $label", vm::shareYearPdf) { save.launch(vm.yearPdfFileName()) }
        }
        item { Muted("Investments count as invested, not spent. Own-account transfers and card bill payments are left out.") }
    }
}

// ---- Family report and limits per person ----

/** Shared household view: everyone's month, from the family's merged exports, with a monthly limit per person. */
@Composable
fun FamilyReportScreen(vm: FinanceViewModel, onBack: () -> Unit, open: (String) -> Unit) {
    val report by vm.familyReport.collectAsState()
    val names by vm.familyNames.collectAsState()
    val limits by vm.personLimits.collectAsState()
    val month by vm.month.collectAsState()
    val current by vm.currentMonth.collectAsState()
    var editing by remember { mutableStateOf<String?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri -> uri?.let(vm::saveFamilyPdf) }
    SimpleScreen("Family report", onBack) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = vm::previousMonth) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous month") }
                Text(month.format(monYear), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                IconButton(onClick = vm::nextMonth, enabled = month < current) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next month") }
            }
        }
        item { Muted("Everyone's spending, each person apart. Family phones send theirs with Manage > Share via WhatsApp; open the file here to merge it. Cards in My cards & accounts count for their owner.") }
        val r = report
        if (r == null) item { Muted("Working it out…") }
        else {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Household", fontWeight = FontWeight.SemiBold)
                        Muted("Received ${formatInr(r.receivedMinor)} · Spent ${formatInr(r.spentMinor)} · Invested ${formatInr(r.investedMinor)}")
                    }
                }
            }
            if (r.people.size <= 1) item {
                Muted("Only this phone's entries so far. Merge the family's exports to see each person here.")
            }
            r.people.forEach { p ->
                item(key = "p-" + p.name) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(p.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                                Text(formatInr(p.spentMinor), color = ExpenseRed, fontWeight = FontWeight.SemiBold)
                            }
                            Muted("Received ${formatInr(p.receivedMinor)} · Invested ${formatInr(p.investedMinor)} · ${p.shareOf(r.spentMinor)}% of household spending · ${p.count} entries")
                            p.categories.take(3).takeIf { it.isNotEmpty() }?.let { c -> Muted("Most on " + c.joinToString(", ") { "${it.key} ${formatInr(it.totalMinor)}" }) }
                            val lim = p.limitMinor
                            if (lim != null) {
                                val pct = p.limitPercent ?: 0
                                LinearProgressIndicator(
                                    progress = { (pct / 100f).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                    color = if (pct >= 100) ExpenseRed else if (pct >= 80) AmberWarn else IncomeGreen,
                                )
                                Text(
                                    if (pct >= 100) "Over the ${formatInr(lim)} limit by ${formatInr(p.spentMinor - lim)}"
                                    else "$pct% of the ${formatInr(lim)} limit · ${formatInr(lim - p.spentMinor)} left",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (pct >= 100) ExpenseRed else if (pct >= 80) AmberWarn else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { editing = p.name }) { Text(if (lim == null) "Set a monthly limit" else "Change limit") }
                        }
                    }
                }
            }
            r.lastEntry.filter { (n, d) -> r.people.size > 1 && n != names.firstOrNull() && d.isBefore(month.atDay(1)) }.takeIf { it.isNotEmpty() }?.let { stale ->
                item { Muted("Not shared for this month yet: " + stale.keys.joinToString(", ") + ". Ask them to send their export.") }
            }
        }
        item {
            Text("Monthly limits", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            Muted("A spending limit per person each month (investments don't count). You get a notification at 80% and when it's crossed.")
        }
        item {
            WrapRow(Modifier, 8.dp) {
                names.forEach { n ->
                    OutlinedButton(onClick = { editing = n }) { Text(n + (limits[n]?.let { " · ${formatInr(it)}" } ?: "")) }
                }
            }
        }
        item {
            PdfButtons(vm, "MyFinance family ${month.format(monShort)}", vm::shareFamilyPdf) { save.launch(vm.familyPdfFileName()) }
            Muted("The family report PDF has everyone's totals, limits, categories by person and a section for each person.")
        }
    }
    editing?.let { name ->
        var text by remember(name) { mutableStateOf(limits[name]?.let { (it / 100).toString() } ?: "") }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Monthly limit for $name") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(text, { text = it }, label = { Text("Amount (₹)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Muted("Leave empty to remove the limit.")
                }
            },
            confirmButton = { TextButton(onClick = { vm.setPersonLimit(name, parseAmountToMinor(text)); editing = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}

// ---- Import health check ----

/** Months with no statement and bank months whose balances don't add up, with a jump to the entry to look at. */
@Composable
fun ImportHealthScreen(vm: FinanceViewModel, onBack: () -> Unit, openTxn: (Long) -> Unit, open: (String) -> Unit) {
    val health by vm.importHealth.collectAsState()
    var showing by remember { mutableStateOf<MonthHealth?>(null) }
    SimpleScreen("Import health check", onBack) {
        item { Muted("For each card and bank account: months with no statement, and bank months where the balance the bank gave doesn't match your entries (an entry missing or counted twice). Tap a month to see where to look.") }
        val h = health
        if (h == null) item { Muted("Checking…") }
        else if (h.isEmpty()) item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Nothing to check yet", fontWeight = FontWeight.SemiBold)
                    Muted("Import your bank and card statements, and this lists any gaps.")
                    OutlinedButton(onClick = { open("import-statement") }) { Text("Import statements") }
                }
            }
        } else {
            val problems = h.sumOf { it.problems }
            item {
                Text(
                    if (problems == 0) "✓ All months present and every bank balance matches" else "$problems month${if (problems == 1) "" else "s"} to look at",
                    color = if (problems == 0) IncomeGreen else ExpenseRed, style = MaterialTheme.typography.titleSmall,
                )
            }
            h.forEach { a ->
                item(key = a.account) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text((if (a.isCard) "Card " else "") + a.account, fontWeight = FontWeight.SemiBold)
                            Muted("${a.first.format(monShort)} to ${a.last.format(monShort)}" + if (!a.isCard) " · ${a.matchedMonths} months match" else "")
                            if (a.problems == 0) Text("✓ No gaps", color = IncomeGreen, style = MaterialTheme.typography.bodyMedium)
                            a.months.take(24).forEach { m ->
                                TextButton(onClick = { if (m.kind == HealthKind.UNMATCHED) showing = m else open("import-statement") }) {
                                    Text(
                                        m.month.format(monShort) + ": " + when (m.kind) {
                                            HealthKind.MISSING_STATEMENT -> "no statement · import it"
                                            HealthKind.UNMATCHED -> (if (m.differenceMinor != 0L) "off by ${formatInr(m.differenceMinor)}" else "entries out of step") + " · see where"
                                            HealthKind.OK -> "fine"
                                        },
                                        color = if (m.kind == HealthKind.UNMATCHED) ExpenseRed else AmberWarn,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                            if (a.months.size > 24) Muted("and ${a.months.size - 24} more")
                        }
                    }
                }
            }
            item {
                WrapRow(Modifier, 8.dp) {
                    OutlinedButton(onClick = { open("import-statement") }) { Text("Import statements") }
                    OutlinedButton(onClick = { open("missing-statements") }) { Text("Missing statements") }
                    OutlinedButton(onClick = { open("import-history") }) { Text("Import history") }
                }
            }
        }
    }
    showing?.let { m ->
        AlertDialog(
            onDismissRequest = { showing = null },
            title = { Text("${m.account}, ${m.month.format(monYear)}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (m.differenceMinor != 0L) Muted("The bank's closing balance is ${formatInr(m.differenceMinor)} " + (if (m.differenceMinor > 0) "more" else "less") + " than your entries add up to.")
                    if (m.suspects.isEmpty()) Muted("No single entry explains it; it may be several. Open the month's PDF report to go through the entries.")
                    m.suspects.forEach { sp ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(sp.message, style = MaterialTheme.typography.bodyMedium, color = if (sp.kind == SuspectKind.DOUBLED) AmberWarn else ExpenseRed)
                                WrapRow(Modifier, 4.dp) {
                                    when (sp.kind) {
                                        SuspectKind.DOUBLED -> {
                                            sp.entryId?.let { id -> TextButton(onClick = { showing = null; openTxn(id) }) { Text("Open it") } }
                                            sp.twinId?.let { id -> TextButton(onClick = { showing = null; openTxn(id) }) { Text("Open the other") } }
                                        }
                                        SuspectKind.MISSING -> {
                                            sp.afterId?.let { id -> TextButton(onClick = { showing = null; openTxn(id) }) { Text("Entry before") } }
                                            TextButton(onClick = { showing = null; openTxn(sp.beforeId) }) { Text("Entry after") }
                                            TextButton(onClick = { showing = null; open("import-statement") }) { Text("Import") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Muted("A doubled entry can be deleted (it goes to Recently deleted). A missing one usually means a statement or SMS wasn't read: import that month's statement again.")
                }
            },
            confirmButton = { TextButton(onClick = { showing = null }) { Text("Close") } },
        )
    }
}
