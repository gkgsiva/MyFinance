package com.myfinance.app.ui.v14

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.myfinance.app.ui.FinanceViewModel
import com.myfinance.app.ui.charts.ChartCard
import com.myfinance.app.ui.theme.Amber
import com.myfinance.app.ui.theme.Violet
import com.myfinance.app.ui.v12.AmountField
import com.myfinance.app.ui.v12.asInput
import com.myfinance.app.ui.v13.Muted
import com.myfinance.app.ui.v13.SimpleScreen
import com.myfinance.core.LabelSuggestion
import com.myfinance.core.formatInr
import com.myfinance.core.parseAmountToMinor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dayFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

/** Overview nudge: "12 payments look like Vegetables". Shows nothing when there are no suggestions. */
@Composable
fun SuggestionsPrompt(vm: FinanceViewModel, onOpen: () -> Unit) {
    val list by vm.v14.suggestions.collectAsState()
    val strong = list.filter { it.strength >= 2 }
    if (strong.isEmpty()) return
    val top = strong.groupBy { it.label }.maxByOrNull { it.value.size }!!
    ChartCard("Label suggestions", accent = Violet, modifier = Modifier.clickable(onClick = onOpen)) {
        Text(
            if (strong.size == top.value.size) "${top.value.size} payments look like ${top.key}."
            else "${top.value.size} payments look like ${top.key}, and ${strong.size - top.value.size} more fit your other labels.",
        )
        FilledTonalButton(onClick = onOpen) { Text("Review") }
    }
}

/** Every suggested label, grouped by label, to accept or reject one by one or all at once. */
@Composable
fun SuggestionsScreen(vm: FinanceViewModel, onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val list by vm.v14.suggestions.collectAsState()
    val all by vm.allTransactions.collectAsState()
    val byId = remember(all) { all.associateBy { it.id } }
    var showMaybe by remember { mutableStateOf(false) }
    val shown = list.filter { showMaybe || it.strength >= 2 }
    val maybe = list.count { it.strength < 2 }
    val groups = shown.groupBy { it.label }.toList().sortedByDescending { it.second.size }

    SimpleScreen(title = "Label suggestions", onBack = onBack) {
        item {
            Muted(
                "Based on the payments you already labelled: the same seller, a shared word in the name " +
                    "(like \"vegetables\"), or the label's own name. Tick the ones that fit and add them.",
            )
        }
        if (groups.isEmpty()) {
            item {
                Muted(
                    if (list.isEmpty()) "No suggestions right now. Label a few payments (at least 2 per label) and similar ones will show up here."
                    else "Nothing certain. Turn on \"Show maybes\" to see payments that only share a category and size.",
                )
            }
        }
        if (maybe > 0) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = showMaybe, onCheckedChange = { showMaybe = it })
                    Text("Show maybes ($maybe): same category and similar amount only")
                }
            }
        }
        for ((label, items) in groups) {
            item(key = "h$label") {
                SuggestionGroup(label, items, byId.mapValues { (_, t) -> Triple(t.merchant ?: t.category, t.amountMinor, t.timestamp) }, vm, onOpen)
            }
        }
        if (list.isNotEmpty()) {
            item {
                TextButton(onClick = { vm.v14.resetDismissed() }) { Text("Bring back suggestions I said no to") }
            }
        }
    }
}

@Composable
private fun SuggestionGroup(
    label: String,
    items: List<LabelSuggestion>,
    info: Map<Long, Triple<String, Long, Long>>,
    vm: FinanceViewModel,
    onOpen: (Long) -> Unit,
) {
    var picked by remember(items) { mutableStateOf(items.map { it.txnId }.toSet()) }
    var expanded by remember { mutableStateOf(items.size <= 6) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("#$label", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Muted("${items.size} payments")
            }
            val visible = if (expanded) items else items.take(6)
            visible.forEach { s ->
                val (name, amount, ts) = info[s.txnId] ?: return@forEach
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = s.txnId in picked, onCheckedChange = { on -> picked = if (on) picked + s.txnId else picked - s.txnId })
                    Column(Modifier.weight(1f).clickable { onOpen(s.txnId) }) {
                        Text(name, maxLines = 1)
                        Muted("${Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate().format(dayFmt)} · ${s.reason}")
                    }
                    Text(formatInr(amount), fontWeight = FontWeight.SemiBold)
                }
            }
            if (!expanded) TextButton(onClick = { expanded = true }) { Text("Show all ${items.size}") }
            com.myfinance.app.ui.WrapRow(Modifier, 8.dp) {
                FilledTonalButton(enabled = picked.isNotEmpty(), onClick = {
                    vm.v14.acceptSuggestions(items.filter { it.txnId in picked })
                    // The ones left unticked were wrong: don't suggest them again.
                    vm.v14.dismissSuggestions(items.filter { it.txnId !in picked && it in visible })
                }) { Text("Label ${picked.size} as $label") }
                TextButton(onClick = { vm.v14.dismissSuggestions(items) }) { Text("None of these") }
            }
        }
    }
}

/** Monthly limits per label, e.g. Vegetables ₹4,000 or Amazon ₹5,000, with progress for the current month. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun LabelBudgetsCard(vm: FinanceViewModel) {
    val budgets by vm.v14.labelBudgets.collectAsState()
    val used by vm.usedLabels.collectAsState()
    val spent by vm.v14.labelMonthNet.collectAsState()
    var editing by remember { mutableStateOf<String?>(null) }
    ChartCard("Label budgets", accent = Amber, subtitle = "A monthly limit for a label, with an alert at 80% and 100%") {
        if (used.isEmpty()) {
            Muted("Add labels to your payments first, then set a monthly limit here.")
            return@ChartCard
        }
        budgets.forEach { (label, limit) ->
            val s = spent[label] ?: 0L
            Row(Modifier.fillMaxWidth().clickable { editing = label }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("#$label")
                    Muted(if (s > limit) "Over by ${formatInr(s - limit)} this month" else "${formatInr(limit - s)} left this month")
                }
                Text("${formatInr(s)} / ${formatInr(limit)}", fontWeight = FontWeight.SemiBold)
            }
        }
        val without = used.filter { it !in budgets }
        if (without.isNotEmpty()) {
            Muted("Tap a label to set its budget:")
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                without.forEach { l -> androidx.compose.material3.AssistChip(onClick = { editing = l }, label = { Text("#$l") }) }
            }
        }
    }
    editing?.let { label ->
        var text by remember(label) { mutableStateOf(budgets[label]?.asInput().orEmpty()) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Budget for #$label") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AmountField("Monthly limit (₹)", text, { text = it })
                    Muted("Refunds on this label come off its spending. Leave empty to remove the budget.")
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.v14.setLabelBudget(label, parseAmountToMinor(text)); editing = null }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}
