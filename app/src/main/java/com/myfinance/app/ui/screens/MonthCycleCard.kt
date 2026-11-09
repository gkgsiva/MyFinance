package com.myfinance.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.myfinance.app.ui.FinanceViewModel
import com.myfinance.core.MonthCycle

/** Manage > "Month for budgets and reports": late salary for next month, or a custom start day. */
@Composable
fun MonthCycleCard(vm: FinanceViewModel) {
    val cycle by vm.monthCycle.collectAsState()

    var salaryOn by remember(cycle) { mutableStateOf(cycle.salaryFromDay != null) }
    var salaryDay by remember(cycle) { mutableStateOf((cycle.salaryFromDay ?: 25).toString()) }
    var startOn by remember(cycle) { mutableStateOf(cycle.startDay > 1) }
    var startDay by remember(cycle) { mutableStateOf((if (cycle.startDay > 1) cycle.startDay else 25).toString()) }

    val salaryDayN = salaryDay.toIntOrNull()?.takeIf { it in 2..31 }
    val startDayN = startDay.toIntOrNull()?.takeIf { it in 2..MonthCycle.MAX_START_DAY }
    val valid = (!salaryOn || salaryDayN != null) && (!startOn || startDayN != null)
    val draft = if (valid) {
        MonthCycle(
            startDay = if (startOn) startDayN!! else 1,
            salaryFromDay = if (salaryOn) salaryDayN else null,
        )
    } else null

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Month for budgets and reports", style = MaterialTheme.typography.titleMedium)
            Text(
                "If your salary comes in the last week of the month, count it for the month it pays for. " +
                    "This changes the Overview, budgets, budget alerts, the widget, Reports and the savings rate.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            ToggleRow("Count late salary for next month", salaryOn) { salaryOn = it }
            if (salaryOn) {
                DayField("From day", salaryDay, salaryDayN != null) { salaryDay = it }
                Text(
                    "A credit in the Salary category on or after day ${salaryDayN ?: "…"} counts for the next month. " +
                        "Spending stays in its own month. If your salary is not marked Salary, open it and set the " +
                        "category once, or add a rule under Rules.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            ToggleRow("Start the month on another day", startOn) { startOn = it }
            if (startOn) {
                DayField("Month starts on day", startDay, startDayN != null) { startDay = it }
                Text(
                    if (startDayN != null) {
                        "Everything from day $startDayN counts for the next month, so November runs from " +
                            "$startDayN October to ${startDayN - 1} November. Days 2 to ${MonthCycle.MAX_START_DAY}."
                    } else {
                        "Enter a day from 2 to ${MonthCycle.MAX_START_DAY}."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Button(
                onClick = { draft?.let(vm::setMonthCycle) },
                enabled = draft != null && draft != cycle,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun DayField(label: String, value: String, valid: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() }.take(2)) },
        label = { Text(label) },
        isError = !valid,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.width(180.dp),
    )
}
