package com.myfinance.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.myfinance.app.data.TransactionEntity
import com.myfinance.app.data.TxnSource
import com.myfinance.app.ui.FinanceViewModel
import com.myfinance.core.DefaultCategories
import com.myfinance.core.TxnType
import com.myfinance.core.parseAmountToMinor
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTransactionScreen(vm: FinanceViewModel, id: Long, onDone: () -> Unit) {
    val categories by vm.categories.collectAsState()
    var original by remember { mutableStateOf<TransactionEntity?>(null) }
    var loaded by remember { mutableStateOf(id == 0L) }

    var amount by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(TxnType.DEBIT) }
    var merchant by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(DefaultCategories.OTHER) }
    var account by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var excluded by remember { mutableStateOf(false) }
    var timestamp by remember { mutableStateOf(System.currentTimeMillis()) }
    var learnRule by remember { mutableStateOf(true) }
    var showDate by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var amountError by remember { mutableStateOf(false) }

    LaunchedEffect(id) {
        if (id != 0L) {
            vm.load(id)?.let { t ->
                original = t
                amount = BigDecimal.valueOf(t.amountMinor, 2).stripTrailingZeros().toPlainString()
                type = t.type
                merchant = t.merchant.orEmpty()
                category = t.category
                account = t.accountTail.orEmpty()
                note = t.note.orEmpty()
                excluded = t.excluded
                timestamp = t.timestamp
            }
            loaded = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (id == 0L) "Add transaction" else "Edit transaction") },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (original != null) {
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
                    }
                },
            )
        },
    ) { inner ->
        if (!loaded) return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(TxnType.DEBIT to "Expense", TxnType.CREDIT to "Income").forEachIndexed { i, (t, label) ->
                    SegmentedButton(
                        selected = type == t,
                        onClick = { type = t },
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                    ) { Text(label) }
                }
            }

            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it; amountError = false },
                label = { Text("Amount (₹)") },
                isError = amountError,
                supportingText = if (amountError) ({ Text("Enter a valid amount") }) else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = merchant,
                onValueChange = { merchant = it },
                label = { Text(if (type == TxnType.CREDIT) "From" else "Paid to / merchant") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            CategoryPicker(
                selected = category,
                options = categories.map { it.name to Color(it.color) },
                onSelect = { category = it },
            )

            if (original != null && original?.category != category && merchant.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = learnRule, onCheckedChange = { learnRule = it })
                    Text("Always put \"$merchant\" in $category", style = MaterialTheme.typography.bodyMedium)
                }
            }

            val date = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
            OutlinedButton(onClick = { showDate = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Date: ${date.format(dayFormat)}")
            }

            OutlinedTextField(
                value = account,
                onValueChange = { v -> account = v.filter { it.isLetterOrDigit() }.take(6) },
                label = { Text("Account / card last digits (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Note") },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Leave out of totals")
                    Text(
                        "For transfers between your own accounts, card bill payments, etc.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = excluded, onCheckedChange = { excluded = it })
            }

            Button(
                onClick = {
                    val minor = parseAmountToMinor(amount)
                    if (minor == null || minor == 0L) {
                        amountError = true
                        return@Button
                    }
                    val base = original ?: TransactionEntity(
                        amountMinor = minor, type = type, merchant = null, category = category,
                        accountTail = null, bank = null, timestamp = timestamp, source = TxnSource.MANUAL,
                    )
                    vm.save(
                        base.copy(
                            amountMinor = minor,
                            type = type,
                            merchant = merchant.trim().ifEmpty { null },
                            category = category,
                            accountTail = account.ifEmpty { null },
                            note = note.trim().ifEmpty { null },
                            excluded = excluded,
                            timestamp = timestamp,
                        ),
                        learnRule,
                    )
                    onDone()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }

            original?.rawSms?.let { sms ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Original SMS", style = MaterialTheme.typography.labelMedium)
                        Text(sms, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    if (showDate) {
        val initial = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { utcMillis ->
                        val picked = Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate()
                        timestamp = withDate(timestamp, picked)
                    }
                    showDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete transaction?") },
            text = { Text("This removes it from your records on this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    original?.let { vm.delete(it) }
                    confirmDelete = false
                    onDone()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/** Keeps the time of day, swaps in a new date. */
private fun withDate(timestamp: Long, date: LocalDate): Long {
    val zone = ZoneId.systemDefault()
    val time = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalTime()
    return date.atTime(time).atZone(zone).toInstant().toEpochMilli()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryPicker(selected: String, options: List<Pair<String, Color>>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text("Category") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (name, color) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    leadingIcon = { ColorDot(color) },
                    onClick = { onSelect(name); expanded = false },
                )
            }
        }
    }
}
