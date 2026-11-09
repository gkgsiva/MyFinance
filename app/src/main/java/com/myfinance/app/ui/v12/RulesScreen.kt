package com.myfinance.app.ui.v12

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import kotlinx.coroutines.launch
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import com.myfinance.app.data.CustomRuleEntity
import com.myfinance.app.ui.V12ViewModel
import com.myfinance.core.CustomRule
import com.myfinance.core.CustomRules
import com.myfinance.core.RuleField
import com.myfinance.core.TaxSection
import com.myfinance.core.TxnType

private val RuleField.label: String
    get() = when (this) {
        RuleField.ANY -> "Anywhere"; RuleField.MERCHANT -> "Merchant name"; RuleField.SMS_TEXT -> "SMS text"; RuleField.SENDER -> "Sender"
    }

private fun describe(r: CustomRule): String = buildList {
    r.setCategory?.let { add("→ $it") }
    r.renameMerchant?.let { add("rename to \"$it\"") }
    if (r.exclude) add("leave out of totals")
    if (r.ignore) add("ignore SMS")
    r.taxSection?.let { add("tax ${it.short}") }
}.joinToString(", ")

@Composable
fun RulesScreen(
    vm: V12ViewModel,
    onBack: () -> Unit,
    /** v1.6 rule tester: which past entries a rule would change. */
    test: (suspend (CustomRule, List<CustomRule>) -> com.myfinance.core.RuleTestResult)? = null,
) {
    val rules by vm.rules.collectAsState()
    var editing by remember { mutableStateOf<CustomRuleEntity?>(null) }
    var adding by remember { mutableStateOf(false) }

    V12Screen("Custom SMS rules", vm, onBack, onAdd = { adding = true }) {
        item {
            Hint12("Your rules run first on every new bank SMS, before the app's own guesses. Example: any SMS with SWIGGY goes to Food & Dining. Lower priority numbers run first.")
        }
        item { FilledTonalButton(onClick = vm::applyRulesNow, enabled = rules.isNotEmpty()) { Text("Apply rules to past SMS") } }
        items(rules, key = { it.id }) { e ->
            val r = e.toRule()
            InfoCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("\"${r.pattern}\" in ${r.field.label.lowercase()}" + (if (r.isRegex) " (pattern)" else ""), style = MaterialTheme.typography.titleSmall)
                        Text(describe(r), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = r.enabled, onCheckedChange = { vm.saveRule(r.copy(enabled = it)) })
                }
                Row {
                    TextButton(onClick = { editing = e }) { Text("Edit") }
                    TextButton(onClick = { vm.deleteRule(e.id) }) { Text("Delete") }
                }
            }
        }
    }

    if (adding || editing != null) RuleDialog(vm, editing?.toRule(), test?.let { t -> val f: suspend (CustomRule) -> com.myfinance.core.RuleTestResult = { r -> t(r, rules.map { it.toRule() }) }; f }) { adding = false; editing = null }
}

@Composable
private fun RuleDialog(vm: V12ViewModel, existing: CustomRule?, test: (suspend (CustomRule) -> com.myfinance.core.RuleTestResult)?, onClose: () -> Unit) {
    var testResult by remember { mutableStateOf<com.myfinance.core.RuleTestResult?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val categories by vm.categoryNames.collectAsState()
    var pattern by remember { mutableStateOf(existing?.pattern.orEmpty()) }
    var field by remember { mutableStateOf(existing?.field ?: RuleField.ANY) }
    var regex by remember { mutableStateOf(existing?.isRegex ?: false) }
    var type by remember { mutableStateOf(existing?.onlyType) }
    var category by remember { mutableStateOf(existing?.setCategory) }
    var rename by remember { mutableStateOf(existing?.renameMerchant.orEmpty()) }
    var exclude by remember { mutableStateOf(existing?.exclude ?: false) }
    var ignore by remember { mutableStateOf(existing?.ignore ?: false) }
    var tax by remember { mutableStateOf(existing?.taxSection) }
    var priority by remember { mutableStateOf((existing?.priority ?: 0).toString()) }

    val newId = remember { java.util.UUID.randomUUID().toString() }
    val rule = CustomRule(
        id = existing?.id ?: newId,
        pattern = pattern.trim(), field = field, isRegex = regex, onlyType = type,
        setCategory = category, renameMerchant = rename.trim().ifEmpty { null }, exclude = exclude, ignore = ignore,
        taxSection = tax, priority = priority.toIntOrNull() ?: 0, enabled = existing?.enabled ?: true,
    )
    val valid = CustomRules.isValid(rule)
    val matches = remember(pattern, field, regex, type) { if (pattern.isBlank()) 0 else vm.previewCount(rule) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (existing == null) "New rule" else "Edit rule") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(pattern, { pattern = it }, label = { Text("When the text contains, e.g. SWIGGY") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Dropdown("Look in", field, RuleField.entries, { it.label }, { field = it })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = regex, onCheckedChange = { regex = it })
                    Text("Advanced pattern (regular expression)")
                }
                Dropdown("Only for", type, listOf(null, TxnType.DEBIT, TxnType.CREDIT), { when (it) { null -> "Expenses and income"; TxnType.DEBIT -> "Expenses"; TxnType.CREDIT -> "Income" } }, { type = it })
                Hint12(if (pattern.isBlank()) "Matches will be counted as you type." else "Matches $matches of your SMS transactions.")
                Text("Then", style = MaterialTheme.typography.labelLarge)
                Dropdown("Set category", category, listOf<String?>(null) + categories, { it ?: "Don't change" }, { category = it })
                OutlinedTextField(rename, { rename = it }, label = { Text("Rename merchant to (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Dropdown("Tax section", tax, listOf<TaxSection?>(null) + TaxSection.entries, { it?.short ?: "None" }, { tax = it })
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(exclude, { exclude = it }); Text("Leave out of totals") }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(ignore, { ignore = it }); Text("Ignore these SMS completely") }
                OutlinedTextField(priority, { v -> priority = v.filter { it.isDigit() }.take(3) }, label = { Text("Priority (lower runs first)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (!valid && pattern.isNotBlank()) Hint12(if (regex && runCatching { Regex(pattern) }.isFailure) "That pattern isn't valid." else "Choose at least one thing to do.")
                if (test != null && valid) {
                    androidx.compose.material3.OutlinedButton(onClick = { scope.launch { testResult = test(rule) } }) { Text("Test on past entries") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.saveRule(rule); onClose() }, enabled = valid) { Text("Save") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )

    testResult?.let { r -> RuleTestDialog(r) { testResult = null } }
}

@Composable
private fun RuleTestDialog(r: com.myfinance.core.RuleTestResult, onClose: () -> Unit) {
    val fmt = java.time.format.DateTimeFormatter.ofPattern("d MMM yy")
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("This rule would change ${r.changes.size}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Matches ${r.matched} past entries." +
                        (if (r.locked > 0) " ${r.locked} you edited or typed in are left alone." else "") +
                        (if (r.wonByOtherRule > 0) " ${r.wonByOtherRule} go to an earlier rule instead." else ""),
                    style = MaterialTheme.typography.bodySmall,
                )
                r.changes.take(100).forEach { c ->
                    val day = java.time.Instant.ofEpochMilli(c.row.timestamp).atZone(java.time.ZoneId.systemDefault()).format(fmt)
                    val what = buildList {
                        if (c.newCategory != null && c.newCategory != c.row.category) add("${c.row.category} → ${c.newCategory}")
                        if (c.newMerchant != null && c.newMerchant != c.row.merchant) add("renamed ${c.newMerchant}")
                        if (c.excluded) add("left out of totals")
                        if (c.ignored) add("ignored")
                    }.joinToString(", ")
                    Text("$day · ${c.row.merchant ?: "?"} · ${com.myfinance.core.formatInr(c.row.amountMinor)}\n$what", style = MaterialTheme.typography.bodySmall)
                }
                if (r.changes.size > 100) Text("…and ${r.changes.size - 100} more", style = MaterialTheme.typography.bodySmall)
                Hint12("Nothing changes until you save the rule and tap \"Apply rules to past SMS\".")
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("OK") } },
    )
}
