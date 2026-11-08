package com.myfinance.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.myfinance.core.PaymentMode
import com.myfinance.core.TxnFilter
import com.myfinance.core.TxnType
import com.myfinance.core.parseAmountToMinor
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val rangeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

fun rangeLabel(from: Long?, to: Long?): String {
    val zone = ZoneId.systemDefault()
    fun f(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().format(rangeFormat)
    return when {
        from != null && to != null -> "${f(from)} – ${f(to)}"
        from != null -> "From ${f(from)}"
        to != null -> "Until ${f(to)}"
        else -> "Any time"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterDialog(
    initial: TxnFilter,
    people: List<String>,
    accounts: List<String>,
    onDismiss: () -> Unit,
    onApply: (TxnFilter) -> Unit,
) {
    var from by remember { mutableStateOf(initial.fromMillis) }
    var to by remember { mutableStateOf(initial.toMillis) }
    var owner by remember { mutableStateOf(initial.owner) }
    var account by remember { mutableStateOf(initial.account) }
    var type by remember { mutableStateOf(initial.type) }
    var mode by remember { mutableStateOf(initial.mode) }
    var min by remember { mutableStateOf(initial.minAmountMinor?.let { BigDecimal.valueOf(it, 2).stripTrailingZeros().toPlainString() } ?: "") }
    var max by remember { mutableStateOf(initial.maxAmountMinor?.let { BigDecimal.valueOf(it, 2).stripTrailingZeros().toPlainString() } ?: "") }
    var pickDates by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filters") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Label("Dates")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickDates = true }) { Text(if (from == null && to == null) "This month (pick range)" else rangeLabel(from, to)) }
                    if (from != null || to != null) TextButton(onClick = { from = null; to = null }) { Text("Clear") }
                }

                if (people.size > 1) {
                    Label("Person")
                    ChipRow(listOf<String?>(null) + people, owner, { it ?: "Anyone" }) { owner = it }
                }
                if (accounts.isNotEmpty()) {
                    Label("Account")
                    ChipRow(listOf<String?>(null) + accounts, account, { it ?: "Any" }) { account = it }
                }
                Label("Type")
                ChipRow(listOf(null, TxnType.DEBIT, TxnType.CREDIT), type, {
                    when (it) { null -> "Both"; TxnType.DEBIT -> "Expenses"; TxnType.CREDIT -> "Income" }
                }) { type = it }
                Label("Paid by")
                ChipRow(listOf<PaymentMode?>(null) + PaymentMode.entries, mode, { it?.label ?: "Any" }) { mode = it }

                Label("Amount (₹)")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = min, onValueChange = { min = it }, label = { Text("Min") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = max, onValueChange = { max = it }, label = { Text("Max") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onApply(
                    initial.copy(
                        fromMillis = from, toMillis = to, owner = owner, account = account, type = type, mode = mode,
                        minAmountMinor = parseAmountToMinor(min), maxAmountMinor = parseAmountToMinor(max),
                    ),
                )
            }) { Text("Apply") }
        },
        dismissButton = {
            TextButton(onClick = { onApply(TxnFilter(text = initial.text, category = initial.category)) }) { Text("Reset") }
        },
    )

    if (pickDates) {
        val state = rememberDateRangePickerState(
            initialSelectedStartDateMillis = from?.let(::toUtcDay),
            initialSelectedEndDateMillis = to?.let(::toUtcDay),
        )
        DatePickerDialog(
            onDismissRequest = { pickDates = false },
            confirmButton = {
                TextButton(onClick = {
                    val zone = ZoneId.systemDefault()
                    state.selectedStartDateMillis?.let { s ->
                        from = utcDay(s).atStartOfDay(zone).toInstant().toEpochMilli()
                        val end = utcDay(state.selectedEndDateMillis ?: s)
                        to = end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
                    }
                    pickDates = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickDates = false }) { Text("Cancel") } },
        ) {
            DateRangePicker(state = state, modifier = Modifier.fillMaxWidth().height(480.dp))
        }
    }
}

/** The pickers work in UTC midnights; the app in local time. */
private fun utcDay(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()

private fun toUtcDay(localMs: Long): Long =
    Instant.ofEpochMilli(localMs).atZone(ZoneId.systemDefault()).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o ->
            FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) })
        }
    }
}
