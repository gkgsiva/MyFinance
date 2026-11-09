package com.myfinance.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import com.myfinance.app.ui.FinanceViewModel
import com.myfinance.core.TxnType
import com.myfinance.core.formatInr
import java.time.LocalDate
import java.time.YearMonth

@Composable
fun TransactionsScreen(vm: FinanceViewModel, onOpen: (Long) -> Unit, onAdd: () -> Unit) {
    val month by vm.month.collectAsState()
    val currentMonth by vm.currentMonth.collectAsState()
    val txns by vm.filteredTransactions.collectAsState()
    val query by vm.query.collectAsState()
    val filter by vm.categoryFilter.collectAsState()
    val advanced by vm.advancedFilter.collectAsState()
    val categories by vm.categories.collectAsState()
    val options by vm.filterOptions.collectAsState()
    val sync by vm.syncState.collectAsState()
    val meta by vm.txnMeta.collectAsState()
    val usedLabels by vm.usedLabels.collectAsState()
    val labelFilter by vm.labelFilter.collectAsState()
    val showHidden by vm.showHidden.collectAsState()
    val colorOf = categories.associate { it.name to Color(it.color) }
    val fallback = MaterialTheme.colorScheme.outline

    var showFilters by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { vm.exportStatement(it, excel = false) }
    }
    val xlsxLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    ) { uri -> uri?.let { vm.exportStatement(it, excel = true) } }
    val fileStem = "myfinance-statement-${LocalDate.now()}"

    val byDay = txns.groupBy { it.timestamp.toLocalDateTime().toLocalDate() }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) { Icon(Icons.Filled.Add, contentDescription = "Add transaction") }
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (advanced.hasDateRange) {
                    Text(
                        rangeLabel(advanced.fromMillis, advanced.toMillis),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                    )
                } else {
                    IconButton(onClick = vm::previousMonth) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous month")
                    }
                    Text(month.format(monthFormat), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = vm::nextMonth, enabled = month < currentMonth) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next month")
                    }
                }
                TextButton(onClick = { showFilters = true }) { Text(if (advanced.hasAdvanced) "Filters •" else "Filters") }
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Export") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Export to Excel") }, onClick = { menuOpen = false; xlsxLauncher.launch("$fileStem.xlsx") })
                        DropdownMenuItem(text = { Text("Export to CSV") }, onClick = { menuOpen = false; csvLauncher.launch("$fileStem.csv") })
                        DropdownMenuItem(
                            text = { Text(if (showHidden) "Don't show hidden transactions" else "Show hidden transactions") },
                            onClick = { menuOpen = false; vm.showHidden.value = !showHidden },
                        )
                        if (advanced.hasAdvanced || query.isNotEmpty() || filter != null) {
                            DropdownMenuItem(text = { Text("Clear all filters") }, onClick = { menuOpen = false; vm.clearFilters() })
                        }
                    }
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { vm.query.value = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text("Search merchant, note, amount, person") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Filled.Clear, contentDescription = "Clear") }
                    }
                },
                singleLine = true,
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = filter == null, onClick = { vm.categoryFilter.value = null }, label = { Text("All") })
                categories.forEach { c ->
                    FilterChip(
                        selected = filter == c.name,
                        onClick = { vm.categoryFilter.value = if (filter == c.name) null else c.name },
                        label = { Text(c.name) },
                        leadingIcon = { ColorDot(Color(c.color), 10) },
                    )
                }
            }
            if (usedLabels.isNotEmpty()) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    usedLabels.forEach { l ->
                        FilterChip(
                            selected = labelFilter == l,
                            onClick = { vm.labelFilter.value = if (labelFilter == l) null else l },
                            label = { Text("#$l") },
                        )
                    }
                }
            }

            val spent = txns.filter { it.type == TxnType.DEBIT && !it.excluded }.sumOf { it.amountMinor }
            val received = txns.filter { it.type == TxnType.CREDIT && !it.excluded }.sumOf { it.amountMinor }
            Text(
                "${txns.size} transactions · spent ${formatInr(spent)} · received ${formatInr(received)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            sync.message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
            }

            if (txns.isEmpty()) {
                Text(
                    "Nothing here. Import SMS from the Overview tab, add an entry with +, or loosen the filters.",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            LazyColumn(Modifier.fillMaxSize()) {
                byDay.forEach { (day, list) ->
                    item(key = "h$day") {
                        Text(
                            day.format(dayFormat),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                        )
                        HorizontalDivider()
                    }
                    items(list, key = { it.id }) { t ->
                        TransactionRow(t, colorOf[t.category] ?: fallback, meta.labels(t), meta.hidden(t)) { onOpen(t.id) }
                    }
                }
            }
        }
    }

    if (showFilters) {
        FilterDialog(
            initial = advanced,
            people = options.first,
            accounts = options.second,
            onDismiss = { showFilters = false },
            onApply = { vm.advancedFilter.value = it; showFilters = false },
        )
    }
}
