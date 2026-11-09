package com.myfinance.app.ui.v12

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.myfinance.app.ui.V12ViewModel
import com.myfinance.app.ui.theme.ExpenseRed
import com.myfinance.app.ui.theme.IncomeGreen
import com.myfinance.core.GoalProgress
import com.myfinance.core.GoalStatus
import com.myfinance.core.formatInr
import com.myfinance.core.parseAmountToMinor
import java.time.LocalDate

@Composable
fun GoalsScreen(vm: V12ViewModel, onBack: () -> Unit, extra: @Composable (goalId: String) -> Unit = {}) {
    val goals by vm.goals.collectAsState()
    var editing by remember { mutableStateOf<GoalProgress?>(null) }
    var adding by remember { mutableStateOf(false) }
    var addMoney by remember { mutableStateOf<GoalProgress?>(null) }

    V12Screen("Goals", vm, onBack, onAdd = { adding = true }) {
        if (goals.isEmpty()) item { Hint12("Set a target like \"Vacation ₹1,00,000 by June\". Add money to it as you save, and see how much to put aside each month.") }
        items(goals, key = { it.goal.id }) { p ->
            InfoCard {
                Row(Modifier.fillMaxWidth()) {
                    Text(p.goal.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text("${p.percent}%", fontWeight = FontWeight.SemiBold)
                }
                LinearProgressIndicator(progress = { p.percent / 100f }, modifier = Modifier.fillMaxWidth())
                Text("${formatInr(p.goal.savedMinor)} of ${formatInr(p.goal.targetMinor)}" + (p.goal.deadline?.let { " by ${it.format(shortDate)}" } ?: ""))
                val (text, color) = when (p.status) {
                    GoalStatus.DONE -> "Reached. Well done!" to IncomeGreen
                    GoalStatus.ON_TRACK -> "On track. Save ${formatInr(p.perMonthNeededMinor ?: 0)} a month" to IncomeGreen
                    GoalStatus.BEHIND -> "Behind. Save ${formatInr(p.perMonthNeededMinor ?: 0)} a month to catch up" to ExpenseRed
                    GoalStatus.OVERDUE -> "Deadline passed, ${formatInr(p.remainingMinor)} to go" to ExpenseRed
                    GoalStatus.NO_DEADLINE -> "${formatInr(p.remainingMinor)} to go" to MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(text, color = color, style = MaterialTheme.typography.bodySmall)
                extra(p.goal.id)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { addMoney = p }) { Text("Add money") }
                    TextButton(onClick = { editing = p }) { Text("Edit") }
                    TextButton(onClick = { vm.deleteGoal(p.goal.id) }) { Text("Delete") }
                }
            }
        }
    }

    if (adding || editing != null) {
        val g = editing?.goal
        var name by remember { mutableStateOf(g?.name.orEmpty()) }
        var target by remember { mutableStateOf(g?.targetMinor?.asInput().orEmpty()) }
        var saved by remember { mutableStateOf(g?.savedMinor?.asInput() ?: "") }
        var deadline by remember { mutableStateOf<LocalDate?>(g?.deadline) }
        var error by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { adding = false; editing = null },
            title = { Text(if (g == null) "New goal" else "Edit goal") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Goal, e.g. Vacation") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    AmountField("Target (₹)", target, { target = it; error = false }, error)
                    AmountField("Saved so far (₹)", saved, { saved = it })
                    DateButton("By", deadline, { deadline = it }, allowClear = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val t = parseAmountToMinor(target)
                    if (name.isBlank() || t == null || t <= 0) { error = true; return@TextButton }
                    vm.saveGoal(g?.id, name.trim(), t, deadline, parseAmountToMinor(saved) ?: 0)
                    adding = false; editing = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { adding = false; editing = null }) { Text("Cancel") } },
        )
    }

    addMoney?.let { p ->
        var amount by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addMoney = null },
            title = { Text("Add to ${p.goal.name}") },
            text = { AmountField("Amount (₹)", amount, { amount = it }) },
            confirmButton = {
                TextButton(onClick = { parseAmountToMinor(amount)?.let { vm.addToGoal(p.goal.id, it) }; addMoney = null }) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { parseAmountToMinor(amount)?.let { vm.addToGoal(p.goal.id, -it) }; addMoney = null }) { Text("Take out") }
            },
        )
    }
}
