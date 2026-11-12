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
import com.myfinance.app.data.accountLabel
import com.myfinance.app.ui.FinanceViewModel
import com.myfinance.app.ui.V12ViewModel
import com.myfinance.app.ui.v12.TransactionExtras
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
fun EditTransactionScreen(vm: FinanceViewModel, v12: V12ViewModel, id: Long, onDone: () -> Unit, startAsIncome: Boolean = false, onOpen: (Long) -> Unit = {}) {
    val categories by vm.categories.collectAsState()
    var original by remember { mutableStateOf<TransactionEntity?>(null) }
    var loaded by remember { mutableStateOf(id == 0L) }

    var amount by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(if (startAsIncome) TxnType.CREDIT else TxnType.DEBIT) }
    var merchant by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(if (startAsIncome) DefaultCategories.INCOME else DefaultCategories.OTHER) }
    var account by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var excluded by remember { mutableStateOf(false) }
    var timestamp by remember { mutableStateOf(System.currentTimeMillis()) }
    var learnRule by remember { mutableStateOf(true) }
    var showDate by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var amountError by remember { mutableStateOf(false) }
    var extras by remember { mutableStateOf(com.myfinance.app.ui.v13.ExtrasDraft()) }
    val meta by vm.txnMeta.collectAsState()

    LaunchedEffect(id) {
        if (id != 0L) {
            vm.load(id)?.let { t ->
                original = t
                amount = BigDecimal.valueOf(t.amountMinor, 2).stripTrailingZeros().toPlainString()
                type = t.type
                merchant = t.merchant.orEmpty()
                nickname = vm.nicknameFor(t).orEmpty()
                category = t.category
                account = t.accountTail.orEmpty()
                note = t.note.orEmpty()
                excluded = t.excluded
                timestamp = t.timestamp
                extras = extras.copy(labels = vm.labelsOf(t.uid), hidden = t.uid in meta.hiddenUids)
            }
            loaded = true
        }
    }

    Scaffold(
        topBar = {
            com.myfinance.app.ui.theme.AppTopBar(
                title = { Text(if (id == 0L) "Add transaction" else "") },
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
            var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(0) }
            val o = original
            if (o != null) {
                TxnHeader(o, nickname.ifBlank { null })
                com.myfinance.app.ui.theme.SegmentedTabs(listOf("Details", "Activity"), tab, { tab = it })
            }
            if (o != null && tab == 1) {
                TxnActivity(vm, o) { otherId -> if (otherId != o.id) onOpen(otherId) }
            } else {
            if (o != null) {
                TxnDetails(o, nickname.ifBlank { null }, categories.firstOrNull { it.name == o.category }?.let { Color(it.color) } ?: MaterialTheme.colorScheme.outline, extras.labels, extras.hidden)
                com.myfinance.app.ui.theme.SectionTitle("Change details")
            }
            com.myfinance.app.ui.theme.SegmentedTabs(
                listOf("Expense", "Income"), if (type == TxnType.DEBIT) 0 else 1,
                { type = if (it == 0) TxnType.DEBIT else TxnType.CREDIT },
            )

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
            com.myfinance.app.ui.v16.ForeignAmountRow(vm) { inr, fxNote ->
                amount = BigDecimal.valueOf(inr, 2).stripTrailingZeros().toPlainString()
                amountError = false
                note = (com.myfinance.core.Fx.parseNote(note)?.let { note.replace(Regex("""[A-Z]{3} [0-9.]+ at ₹[0-9.]+"""), "").trim() } ?: note.trim())
                    .let { if (it.isEmpty()) fxNote else "$it · $fxNote" }
            }

            OutlinedTextField(
                value = merchant,
                onValueChange = { merchant = it },
                label = { Text(if (type == TxnType.CREDIT) "From" else "Paid to / merchant") },
                supportingText = if (original?.source == TxnSource.STATEMENT) ({ Text("Name as printed on the statement") }) else null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = nickname,
                onValueChange = { nickname = it },
                label = { Text("Nickname (your own name for it)") },
                supportingText = { Text("Shown in lists and found by search. A statement never changes it.") },
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

            com.myfinance.app.ui.v13.TxnLabelsSection(
                vm = vm,
                draft = extras,
                onChange = { extras = it },
                originalMerchant = original?.merchant,
                editedMerchant = merchant,
                income = type == TxnType.CREDIT,
            )

            // v1.4: a label that fits, from payments already labelled.
            val suggestions by vm.v14.suggestions.collectAsState()
            suggestions.firstOrNull { it.txnId == id && id != 0L }?.let { sug ->
                if (extras.labels.none { it.equals(sug.label, ignoreCase = true) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Suggested: #${sug.label} (${sug.reason})", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { extras = extras.copy(labels = extras.labels + sug.label) }) { Text("Add") }
                    }
                }
            }
            original?.let { o -> com.myfinance.app.ui.v14.RefundSwitch(vm, o.uid, o.amountMinor, o.type == TxnType.DEBIT) }
            original?.let { o -> com.myfinance.app.ui.v16.SplitPaymentCard(vm, o) }

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
                    val row = base.copy(
                        amountMinor = minor,
                        type = type,
                        merchant = merchant.trim().ifEmpty { null },
                        category = category,
                        accountTail = account.ifEmpty { null },
                        note = note.trim().ifEmpty { null },
                        excluded = excluded,
                        timestamp = timestamp,
                    )
                    val before = original
                    val newName = row.merchant
                    if (nickname.trim() != (before?.let { vm.nicknameFor(it) }).orEmpty()) vm.setNickname(row.uid, nickname)
                    vm.saveWithExtras(
                        txn = row,
                        learnRule = learnRule,
                        labels = extras.labels,
                        hidden = extras.hidden,
                        labelAll = extras.labelAll,
                        // Rename from the payee's old name so all of their payments are found.
                        renameFrom = before?.takeIf { extras.renameAll && newName != null && it.merchant != null && it.merchant != newName },
                    )
                    onDone()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }

            original?.let { TransactionExtras(v12, it) } ?: Text(
                "Save first to add receipt photos, a tax section or split this payment.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

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
            text = { Text("You can undo this, or restore it later from Manage > Recently deleted.") },
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

private val detailDate = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a")

/** The payee in the serif face with the amount below, like a receipt. */
@Composable
private fun TxnHeader(t: TransactionEntity, nickname: String?) {
    val credit = t.type == TxnType.CREDIT
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            nickname ?: t.merchant ?: if (credit) "Money received" else "Payment",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        Text(
            (if (credit) "+" else "") + com.myfinance.core.formatInr(t.amountMinor),
            style = MaterialTheme.typography.titleLarge,
            color = if (credit) com.myfinance.app.ui.theme.IncomeGreen else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Label and value rows with pill chips: status and type, payee, category, account, date, source and labels. */
@Composable
private fun TxnDetails(t: TransactionEntity, nickname: String?, categoryColor: Color, labels: List<String>, hidden: Boolean) {
    val credit = t.type == TxnType.CREDIT
    val pc = com.myfinance.app.ui.theme.PillColors
    com.myfinance.app.ui.theme.DetailCard {
        com.myfinance.app.ui.theme.DetailRow("Status & type") {
            val (bg, fg) = when {
                t.excluded -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
                credit -> pc.green
                else -> pc.purple
            }
            if (hidden) com.myfinance.app.ui.theme.Pill("HIDDEN", container = MaterialTheme.colorScheme.surfaceVariant, content = MaterialTheme.colorScheme.onSurfaceVariant)
            com.myfinance.app.ui.theme.Pill(
                when { t.excluded -> "NOT COUNTED"; credit -> "RECEIVED"; else -> "PAID" },
                container = bg, content = fg,
            )
        }
        com.myfinance.app.ui.theme.DetailRow(if (credit) "From" else "Paid to") {
            com.myfinance.app.ui.theme.Pill(t.merchant ?: "Not known")
        }
        if (nickname != null && nickname != t.merchant) com.myfinance.app.ui.theme.DetailRow("Your name for it") {
            com.myfinance.app.ui.theme.Pill(nickname)
        }
        com.myfinance.app.ui.theme.DetailRow("Category") {
            com.myfinance.app.ui.theme.Pill(t.category, leading = { com.myfinance.app.ui.theme.CategoryBadge(t.category, categoryColor, 22.dp) })
        }
        t.accountLabel?.let { acct ->
            com.myfinance.app.ui.theme.DetailRow("Account") { com.myfinance.app.ui.theme.Pill(acct, container = pc.blue.first, content = pc.blue.second) }
        }
        com.myfinance.app.ui.theme.DetailRow("Date") {
            com.myfinance.app.ui.theme.Pill(t.timestamp.toLocalDateTime().format(detailDate))
        }
        t.owner?.takeIf { it.isNotBlank() }?.let { who ->
            com.myfinance.app.ui.theme.DetailRow("Person") { com.myfinance.app.ui.theme.Pill(who) }
        }
        com.myfinance.app.ui.theme.DetailRow("Source", divider = labels.isNotEmpty()) {
            com.myfinance.app.ui.theme.Pill(
                when (t.source) { TxnSource.MANUAL -> "Added by hand"; TxnSource.STATEMENT -> "Statement"; else -> "Bank SMS" },
                container = pc.amber.first, content = pc.amber.second,
            )
        }
        if (labels.isNotEmpty()) com.myfinance.app.ui.theme.DetailRow("Labels", divider = false) {
            com.myfinance.app.ui.WrapRow(Modifier, 6.dp) {
                labels.forEach { com.myfinance.app.ui.theme.Pill(it, container = pc.green.first, content = pc.green.second) }
            }
        }
    }
    t.note?.takeIf { it.isNotBlank() }?.let { n ->
        com.myfinance.app.ui.theme.DetailCard {
            Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Note", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(n, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/** Where the entry came from (the SMS or statement line) and the other payments to the same payee. */
@Composable
private fun TxnActivity(vm: FinanceViewModel, t: TransactionEntity, onOpen: (Long) -> Unit) {
    val all by vm.allTransactions.collectAsState()
    val categories by vm.categories.collectAsState()
    val nicks by vm.nicknames.collectAsState()
    val same = remember(all, t.id) {
        val key = t.merchant?.trim()?.lowercase()
        if (key.isNullOrEmpty()) emptyList()
        else all.filter { it.merchant?.trim()?.lowercase() == key && it.type == t.type }.sortedByDescending { it.timestamp }
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    com.myfinance.app.ui.theme.DetailCard {
        Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                when (t.source) { TxnSource.STATEMENT -> "From the statement"; TxnSource.MANUAL -> "Added by hand"; else -> "Original SMS" },
                style = MaterialTheme.typography.titleSmall,
            )
            Text(t.rawSms ?: "No message text kept for this entry.", style = MaterialTheme.typography.bodySmall, color = if (t.rawSms == null) muted else MaterialTheme.colorScheme.onSurface)
            listOfNotNull(
                t.bank?.let { "Bank: $it" },
                t.reference?.let { "Reference: $it" },
                t.balanceMinor?.let { "Balance after: ${com.myfinance.core.formatInr(it)}" },
                if (t.userEdited) "You have edited this entry" else null,
            ).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = muted) }
        }
    }
    if (same.size > 1) {
        val total = same.sumOf { it.amountMinor }
        com.myfinance.app.ui.theme.SectionTitle(
            (if (t.type == TxnType.CREDIT) "From " else "Paid to ") + (t.merchant ?: "") + " before",
        )
        Text(
            "${same.size} times, ${com.myfinance.core.formatInr(total)} in all, about ${com.myfinance.core.formatInr(total / same.size)} each",
            style = MaterialTheme.typography.bodySmall, color = muted,
        )
        val colorOf = categories.associate { it.name to Color(it.color) }
        same.take(12).forEach { o ->
            MoneyRow(
                name = nicks[o.uid.substringBefore('#')] ?: o.merchant ?: "Payment",
                category = o.category, color = colorOf[o.category] ?: MaterialTheme.colorScheme.outline,
                timestamp = o.timestamp, amountMinor = o.amountMinor, credit = o.type == TxnType.CREDIT,
                detail = o.timestamp.toLocalDateTime().format(dayFormat) + if (o.id == t.id) " · this one" else "",
            ) { onOpen(o.id) }
        }
    } else {
        Text("No other payments to this payee yet.", style = MaterialTheme.typography.bodySmall, color = muted)
    }
}
