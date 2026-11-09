package com.myfinance.app.ui.v15

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.myfinance.app.data.GoalLinkEntity
import com.myfinance.app.ui.FinanceViewModel
import com.myfinance.app.ui.theme.ExpenseRed
import com.myfinance.app.ui.theme.IncomeGreen
import com.myfinance.app.ui.v12.AmountField
import com.myfinance.app.ui.v12.DateButton
import com.myfinance.app.ui.v12.Dropdown
import com.myfinance.app.ui.v12.Hint12
import com.myfinance.core.GoalLinkKind
import com.myfinance.core.formatInr
import com.myfinance.core.parseAmountToMinor
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val reachFormat = DateTimeFormatter.ofPattern("MMM yyyy")

/** Shown inside each goal: linked SIPs/FDs/holdings/labels, the monthly pace and when the goal is reached. */
@Composable
fun GoalLinksPanel(vm: FinanceViewModel, goalId: String) {
    val s = vm.v15
    val forecasts by s.forecasts.collectAsState()
    val links by s.goalLinks.collectAsState()
    val rates by s.goalReturnRates.collectAsState()
    val f = forecasts[goalId]
    var adding by remember { mutableStateOf(false) }
    var editRate by remember { mutableStateOf(false) }
    var unlinking by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (f != null && f.linked.isNotEmpty()) {
            f.linked.forEach { l ->
                Row15(
                    l.link.ref, l.link.kind.label + (if (l.monthlyMinor > 0) " · ${formatInr(l.monthlyMinor)}/month" else ""),
                    formatInr(l.savedMinor),
                ) { unlinking = l.link.id }
            }
            Text("With linked savings: ${formatInr(f.totalSavedMinor)} (${f.percent}%)", style = MaterialTheme.typography.bodySmall)
        }
        if (f != null) {
            val reach = f.reachBy
            val vs = f.monthsVsDeadline
            val text = when {
                f.percent >= 100 -> null
                reach == null -> if (links.any { it.goalId == goalId }) "Nothing going in each month yet" else null
                else -> "At ${formatInr(f.monthlyMinor)} a month you'll reach it by ${reach.format(reachFormat)}" +
                    when {
                        vs == null -> ""
                        vs > 0 -> ", $vs months after the deadline"
                        vs < 0 -> ", ${-vs} months early"
                        else -> ", right on time"
                    }
            }
            text?.let {
                Text(it, color = if ((vs ?: 0) > 0) ExpenseRed else IncomeGreen, style = MaterialTheme.typography.bodySmall)
            }
        }
        Row {
            TextButton(onClick = { adding = true }) { Text("Link savings") }
            TextButton(onClick = { editRate = true }) { Text("Return: ${rates[goalId]?.let { "$it%" } ?: "none"}") }
        }
        if (links.none { it.goalId == goalId }) Hint12("Link a SIP, FD, a Net worth holding or a label like \"Vacation fund\", and the goal fills up by itself.")
    }

    if (adding) {
        val (investments, holdings) = s.linkChoices.collectAsState().value
        val labels by vm.usedLabels.collectAsState()
        var kind by remember { mutableStateOf(GoalLinkKind.INVESTMENT) }
        var ref by remember { mutableStateOf("") }
        var since by remember { mutableStateOf(LocalDate.now().withDayOfMonth(1)) }
        var monthly by remember { mutableStateOf("") }
        val options = when (kind) {
            GoalLinkKind.INVESTMENT -> investments
            GoalLinkKind.HOLDING -> holdings
            GoalLinkKind.LABEL -> labels
        }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Link savings to this goal") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Dropdown("What", kind, GoalLinkKind.entries, { it.label }, { kind = it; ref = "" })
                    if (options.isNotEmpty()) Dropdown("Choose", ref.ifEmpty { options.first() }, options, { it }, { ref = it })
                    else Hint12(
                        when (kind) {
                            GoalLinkKind.INVESTMENT -> "No SIP or FD messages found yet. Add one under Net worth, or link a holding instead."
                            GoalLinkKind.HOLDING -> "Add a holding under Plan > Net worth first."
                            GoalLinkKind.LABEL -> "Label the payments that go into this goal first."
                        },
                    )
                    if (kind != GoalLinkKind.HOLDING) DateButton("Counting from", since, { d -> d?.let { since = it } })
                    AmountField("Planned each month (₹, optional)", monthly, { monthly = it })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val chosen = ref.ifEmpty { options.firstOrNull().orEmpty() }
                    if (chosen.isNotBlank()) {
                        s.addGoalLink(
                            GoalLinkEntity(goalId = goalId, kind = kind.name, ref = chosen, sinceEpochDay = since.toEpochDay(), monthlyMinor = parseAmountToMinor(monthly)),
                        )
                    }
                    adding = false
                }) { Text("Link") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }

    unlinking?.let { id ->
        AlertDialog(
            onDismissRequest = { unlinking = null },
            title = { Text("Unlink from this goal?") },
            text = { Text("The savings stay where they are; they just stop counting towards this goal.") },
            confirmButton = { TextButton(onClick = { s.deleteGoalLink(id); unlinking = null }) { Text("Unlink") } },
            dismissButton = { TextButton(onClick = { unlinking = null }) { Text("Cancel") } },
        )
    }

    if (editRate) {
        var pct by remember { mutableStateOf(rates[goalId]?.toString().orEmpty()) }
        AlertDialog(
            onDismissRequest = { editRate = false },
            title = { Text("Expected yearly return") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        pct, { v -> pct = v.filter { it.isDigit() || it == '.' }.take(5) }, label = { Text("% a year, e.g. 7 for an FD, 12 for equity funds") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    Hint12("Used only to estimate when the goal is reached. Leave 0 to count contributions alone.")
                }
            },
            confirmButton = {
                TextButton(onClick = { s.setGoalReturn(goalId, (pct.toDoubleOrNull() ?: 0.0).coerceIn(0.0, 30.0)); editRate = false }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editRate = false }) { Text("Cancel") } },
        )
    }
}
