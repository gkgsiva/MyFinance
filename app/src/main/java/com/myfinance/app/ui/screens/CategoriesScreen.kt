package com.myfinance.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.myfinance.app.data.CategoryEntity
import com.myfinance.app.ui.FinanceViewModel
import com.myfinance.core.formatInr
import com.myfinance.core.parseAmountToMinor
import java.math.BigDecimal
import java.time.LocalDate

/** Family sharing plus category & budget management. */
@Composable
fun CategoriesScreen(vm: FinanceViewModel) {
    val categories by vm.categories.collectAsState()
    val summary by vm.summary.collectAsState()
    val owner by vm.ownerName.collectAsState()
    val sync by vm.syncState.collectAsState()
    val spentBy = summary.byCategory.associate { it.key to it.totalMinor }

    var editing by remember { mutableStateOf<CategoryEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<CategoryEntity?>(null) }
    var nameDraft by remember(owner) { mutableStateOf(owner) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let(vm::exportTo)
    }
    val mergeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::mergeFrom)
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Family", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Export from each phone, then merge the files on one phone to see the whole family's " +
                            "spending. Transactions that appear on more than one phone, like joint-account " +
                            "payments, are only counted once.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = nameDraft,
                            onValueChange = { nameDraft = it },
                            label = { Text("This phone belongs to") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { vm.setOwnerName(nameDraft) }, enabled = nameDraft.trim() != owner) {
                            Text("Save")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = { exportLauncher.launch("myfinance-${owner.lowercase().replace(' ', '-')}-${LocalDate.now()}.csv") },
                            enabled = !sync.running,
                        ) { Text("Export") }
                        FilledTonalButton(
                            onClick = { mergeLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/octet-stream")) },
                            enabled = !sync.running,
                        ) { Text("Merge a file") }
                    }
                    sync.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Text("Categories & budgets", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, contentDescription = "Add category") }
            }
            Text(
                "Tap a category to set a monthly budget.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        items(categories, key = { it.name }) { c ->
            Row(
                Modifier.fillMaxWidth().clickable { editing = c }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ColorDot(Color(c.color), 16)
                Column(Modifier.weight(1f)) {
                    Text(c.name)
                    val spent = spentBy[c.name] ?: 0
                    Text(
                        "Spent ${formatInr(spent)} this month" + (c.monthlyBudgetMinor?.let { " of ${formatInr(it)}" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!c.builtIn) {
                    IconButton(onClick = { deleting = c }) { Icon(Icons.Filled.Delete, contentDescription = "Delete ${c.name}") }
                }
            }
        }
    }

    if (adding) {
        CategoryDialog(
            title = "New category",
            initialName = "",
            initialBudget = null,
            nameEditable = true,
            onDismiss = { adding = false },
            onSave = { name, budget -> vm.addCategory(name, budget); adding = false },
        )
    }
    editing?.let { c ->
        CategoryDialog(
            title = c.name,
            initialName = c.name,
            initialBudget = c.monthlyBudgetMinor,
            nameEditable = false,
            onDismiss = { editing = null },
            onSave = { _, budget -> vm.updateCategory(c.copy(monthlyBudgetMinor = budget)); editing = null },
        )
    }
    deleting?.let { c ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${c.name}?") },
            text = { Text("Its transactions move to Uncategorized.") },
            confirmButton = { TextButton(onClick = { vm.deleteCategory(c.name); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CategoryDialog(
    title: String,
    initialName: String,
    initialBudget: Long?,
    nameEditable: Boolean,
    onDismiss: () -> Unit,
    onSave: (name: String, budgetMinor: Long?) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var budget by remember {
        mutableStateOf(initialBudget?.let { BigDecimal.valueOf(it, 2).stripTrailingZeros().toPlainString() } ?: "")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (nameEditable) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                }
                OutlinedTextField(
                    value = budget,
                    onValueChange = { budget = it },
                    label = { Text("Monthly budget (₹, optional)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(name, parseAmountToMinor(budget)?.takeIf { it > 0 }) }, enabled = name.isNotBlank()) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
