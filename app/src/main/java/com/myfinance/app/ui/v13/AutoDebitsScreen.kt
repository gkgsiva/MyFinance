package com.myfinance.app.ui.v13

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.myfinance.app.ui.FinanceViewModel
import com.myfinance.app.ui.screens.relativeDay
import com.myfinance.app.ui.theme.ExpenseRed
import com.myfinance.app.ui.theme.IncomeGreen
import com.myfinance.app.ui.v12.AmountField
import com.myfinance.app.ui.v12.DateButton
import com.myfinance.app.ui.v12.Dropdown
import com.myfinance.app.ui.v12.asInput
import com.myfinance.core.AutoDebitItem
import com.myfinance.core.AutoDebitKind
import com.myfinance.core.AutoDebitPlan
import com.myfinance.core.AutoDebitSource
import com.myfinance.core.AutoDebits
import com.myfinance.core.DueState
import com.myfinance.core.formatInr
import com.myfinance.core.parseAmountToMinor
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val dayFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM")
private val monthFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM yyyy")

/** Screen with a back arrow and an optional + button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimpleScreen(
    title: String,
    onBack: () -> Unit,
    onAdd: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { actions() },
            )
        },
        floatingActionButton = { onAdd?.let { FloatingActionButton(onClick = it) { Icon(Icons.Filled.Add, contentDescription = "Add") } } },
    ) { inner ->
        LazyColumn(
            Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
fun Muted(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun InfoBox(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}

/** EMIs, auto debits (NACH / e-mandate / autopay), SIPs, insurance and subscriptions with the next expected date. */
@Composable
fun AutoDebitsScreen(vm: FinanceViewModel, onBack: () -> Unit) {
    val items by vm.autoDebits.collectAsState()
    val totals by vm.autoDebitTotals.collectAsState()
    val hiddenKeys by vm.hiddenAutoDebitKeys.collectAsState()
    val showHidden by vm.showHiddenAutoDebits.collectAsState()
    var editing by remember { mutableStateOf<AutoDebitPlan?>(null) }
    var selected by remember { mutableStateOf<AutoDebitItem?>(null) }
    val scope = rememberCoroutineScope()

    val mine = items.filter { it.source != AutoDebitSource.DETECTED }
    val detected = items.filter { it.source == AutoDebitSource.DETECTED }

    SimpleScreen(
        title = "Auto debits & EMIs",
        onBack = onBack,
        onAdd = { editing = newPlan() },
    ) {
        item {
            InfoBox {
                Text("Next 30 days", style = MaterialTheme.typography.titleSmall)
                Text(formatInr(totals.next30DaysMinor), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Muted("${totals.next30DaysCount} payments expected · about ${formatInr(totals.monthlyMinor)} a month in total")
            }
        }
        item { SectionTitle("Your EMIs & auto debits") }
        if (mine.isEmpty()) {
            item {
                Muted(
                    "Tap + to add a loan EMI, SIP, insurance premium or any auto debit with its date. " +
                        "Or tap one found in your SMS below and choose \"Add to my list\".",
                )
            }
        }
        items(mine, key = { it.key }) { i -> AutoDebitRow(i, i.key in hiddenKeys) { selected = i } }

        item { SectionTitle("Found in your SMS") }
        if (detected.isEmpty()) {
            item { Muted("Payments that repeat, and debits made by NACH, ECS, e-mandate or autopay, show up here.") }
        } else {
            item { Muted("Dates are worked out from past payments, so they can be off by a few days.") }
        }
        items(detected, key = { it.key }) { i -> AutoDebitRow(i, i.key in hiddenKeys) { selected = i } }
        item {
            if (hiddenKeys.isNotEmpty()) {
                TextButton(onClick = { vm.showHiddenAutoDebits.value = !showHidden }) {
                    Text(if (showHidden) "Don't show the ${hiddenKeys.size} hidden" else "Show ${hiddenKeys.size} hidden (still counted in totals)")
                }
            }
        }
    }

    selected?.let { item ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(item.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${item.kind.label} · ${item.cadenceLabel}")
                    Text("Next: ${item.nextDate.format(dayFmt)} (${relativeDay(item.nextDate)})" + if (item.estimated) ", estimated" else "")
                    Text("Amount: ${formatInr(item.amountMinor)}")
                    item.lastPaid?.let { Text("Last paid: ${it.format(dayFmt)}") }
                    item.remaining?.let { Text("$it instalments left, last one ${item.endDate?.format(monthFmt)}") }
                    item.account?.let { Text("Account: $it") }
                    if (item.occurrences > 0) Muted("Found ${item.occurrences} payments in your SMS.")
                    Muted("Hide takes it off this list but keeps it in the totals above.")
                    if (com.myfinance.core.AutoDebits.payeeKey(item) != null) {
                        Muted("Not recurring takes this payee off the list and out of the totals, e.g. a one-time payment or a family UPI payment. Labelling a payment \"Family UPI\" does the same.")
                    }
                }
            },
            confirmButton = {
                when (item.source) {
                    AutoDebitSource.MANUAL -> TextButton(onClick = {
                        selected = null
                        val id = item.planId ?: return@TextButton
                        scope.launch { editing = vm.autoPlan(id) }
                    }) { Text("Edit") }
                    else -> TextButton(onClick = {
                        selected = null
                        editing = planFrom(item)
                    }) { Text("Add to my list") }
                }
            },
            dismissButton = {
                Row {
                    if (item.source == AutoDebitSource.MANUAL) {
                        TextButton(onClick = { selected = null; item.planId?.let(vm::deleteAutoPlan) }) { Text("Delete") }
                    }
                    if (com.myfinance.core.AutoDebits.payeeKey(item) != null) {
                        TextButton(onClick = { selected = null; vm.markNotRecurring(item) }) { Text("Not recurring") }
                    }
                    if (item.key in hiddenKeys) {
                        TextButton(onClick = { selected = null; vm.unhideAutoDebit(item.key) }) { Text("Unhide") }
                    } else {
                        TextButton(onClick = { selected = null; vm.hideAutoDebit(item.key) }) { Text("Hide") }
                    }
                    TextButton(onClick = { selected = null }) { Text("Close") }
                }
            },
        )
    }

    editing?.let { plan ->
        PlanDialog(
            initial = plan,
            onDismiss = { editing = null },
            onSave = { vm.saveAutoPlan(it); editing = null },
        )
    }
}

private fun newPlan() = AutoDebitPlan(
    id = "",
    name = "",
    kind = AutoDebitKind.EMI,
    amountMinor = 0,
    firstDue = LocalDate.now(),
)

private fun planFrom(i: AutoDebitItem) = AutoDebitPlan(
    id = "",
    name = i.name,
    kind = i.kind,
    amountMinor = i.amountMinor,
    firstDue = i.nextDate,
    everyMonths = i.everyMonths,
    matchWords = com.myfinance.core.Categorizer.merchantKey(i.name).split(' ').filter { it.length >= 3 }.take(2),
    account = i.account,
)

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun AutoDebitRow(i: AutoDebitItem, hidden: Boolean, onClick: () -> Unit) {
    val dateColor = when (i.state) {
        DueState.OVERDUE -> ExpenseRed
        DueState.DUE_TODAY, DueState.DUE_SOON -> MaterialTheme.colorScheme.primary
        DueState.PAID -> IncomeGreen
        DueState.UPCOMING -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(i.name, fontWeight = FontWeight.SemiBold)
                Text(
                    "Next ${i.nextDate.format(dayFmt)} (${relativeDay(i.nextDate)})" +
                        (if (i.state == DueState.OVERDUE) " · not seen yet" else "") +
                        (if (i.estimated) " · estimated" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = dateColor,
                )
                val extra = buildList {
                    add(i.kind.label)
                    add(i.cadenceLabel)
                    i.remaining?.let { add("$it left") }
                    i.account?.let { add(it) }
                    if (hidden) add("hidden")
                }.joinToString(" · ")
                Muted(extra)
            }
            Text(formatInr(i.amountMinor), fontWeight = FontWeight.Bold)
        }
    }
}

private val frequencies = listOf(1, 3, 6, 12)

@Composable
private fun PlanDialog(initial: AutoDebitPlan, onDismiss: () -> Unit, onSave: (AutoDebitPlan) -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var kind by remember { mutableStateOf(initial.kind) }
    var amount by remember { mutableStateOf(if (initial.amountMinor > 0) initial.amountMinor.asInput() else "") }
    var first by remember { mutableStateOf(initial.firstDue) }
    var every by remember { mutableStateOf(initial.everyMonths) }
    var end by remember { mutableStateOf(initial.endDate) }
    var count by remember { mutableStateOf("") }
    var words by remember { mutableStateOf(initial.matchWords.joinToString(", ")) }
    val minor = parseAmountToMinor(amount)
    val endFromCount = count.toIntOrNull()?.takeIf { it > 0 }?.let { n -> first.plusMonths((n - 1).toLong() * every) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id.isEmpty()) "Add EMI / auto debit" else "Edit") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name, e.g. HDFC home loan") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Dropdown("Type", kind, AutoDebitKind.entries, { it.label }, { kind = it })
                AmountField("Amount (₹)", amount, { amount = it }, error = amount.isNotEmpty() && minor == null)
                DateButton("Next due date", first, { d -> d?.let { first = it } })
                Dropdown("Repeats", every, frequencies, { AutoDebits.cadenceLabel(it) }, { every = it })
                DateButton("Last instalment", endFromCount ?: end, { end = it; count = "" }, allowClear = true)
                OutlinedTextField(
                    count, { v -> count = v.filter(Char::isDigit) },
                    label = { Text("…or instalments left (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    words, { words = it },
                    label = { Text("Words in the debit SMS (optional)") },
                    supportingText = { Text("e.g. bajaj, nach. Used to tick it off when the debit arrives.") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && minor != null && minor > 0,
                onClick = {
                    onSave(
                        initial.copy(
                            id = initial.id.ifEmpty { java.util.UUID.randomUUID().toString() },
                            name = name.trim(), kind = kind, amountMinor = minor ?: 0, firstDue = first,
                            everyMonths = every, endDate = endFromCount ?: end,
                            matchWords = words.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
