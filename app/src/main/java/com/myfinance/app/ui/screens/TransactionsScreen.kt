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
import androidx.compose.ui.focus.focusRequester
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
    val nicks by vm.nicknames.collectAsState()
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
            com.myfinance.app.ui.theme.AppFab(onClick = onAdd) { Icon(Icons.Filled.Add, contentDescription = "Add transaction") }
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            // Serif title with the export menu, then the month (or date range) on a white pill.
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Transactions", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.align(Alignment.Center))
                Box(Modifier.align(Alignment.CenterEnd)) {
                    com.myfinance.app.ui.theme.RoundIconHolder {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Export") }
                    }
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
            androidx.compose.material3.Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (advanced.hasDateRange) {
                        Text(
                            rangeLabel(advanced.fromMillis, advanced.toMillis),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f).padding(start = 12.dp, top = 12.dp, bottom = 12.dp),
                        )
                    } else {
                        IconButton(onClick = vm::previousMonth) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous month")
                        }
                        Text(
                            month.format(monthFormat), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        IconButton(onClick = vm::nextMonth, enabled = month < currentMonth) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next month")
                        }
                    }
                    TextButton(onClick = { showFilters = true }) { Text(if (advanced.hasAdvanced) "Filters •" else "Filters") }
                }
            }
            val cycle by vm.monthCycle.collectAsState()
            if (!advanced.hasDateRange) cycle.note(month)?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            // A rounded search bar; Home's search bar opens it ready to type.
            var searchOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
            val focus = remember { androidx.compose.ui.focus.FocusRequester() }
            val askSearch by com.myfinance.app.ui.theme.UiSignals.openSearch.collectAsState()
            androidx.compose.runtime.LaunchedEffect(askSearch) {
                if (askSearch) {
                    com.myfinance.app.ui.theme.UiSignals.openSearch.value = false
                    searchOpen = true
                    kotlinx.coroutines.delay(150)
                    runCatching { focus.requestFocus() }
                }
            }
            androidx.compose.material3.TextField(
                value = query,
                onValueChange = { vm.query.value = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).focusRequester(focus),
                placeholder = { Text("Search transactions or payees…", maxLines = 1) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = if (query.isNotEmpty()) ({
                    IconButton(onClick = { vm.query.value = ""; searchOpen = false }) { Icon(Icons.Filled.Clear, contentDescription = "Clear search") }
                }) else null,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                colors = androidx.compose.material3.TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            com.myfinance.app.ui.WrapRow(Modifier.padding(horizontal = 16.dp, vertical = 2.dp), 8.dp) {
                var catMenu by remember { mutableStateOf(false) }
                Box {
                    FilterChip(
                        selected = filter != null,
                        onClick = { catMenu = true },
                        label = { Text(filter ?: "All categories", maxLines = 1) },
                        leadingIcon = if (filter != null) ({ ColorDot(colorOf[filter.orEmpty()] ?: fallback, 10) }) else null,
                    )
                    DropdownMenu(expanded = catMenu, onDismissRequest = { catMenu = false }) {
                        DropdownMenuItem(text = { Text("All categories") }, onClick = { catMenu = false; vm.categoryFilter.value = null })
                        categories.forEach { c ->
                            DropdownMenuItem(
                                text = { Text(c.name) },
                                leadingIcon = { ColorDot(Color(c.color), 10) },
                                onClick = { catMenu = false; vm.categoryFilter.value = c.name },
                            )
                        }
                    }
                }
                if (usedLabels.isNotEmpty()) {
                    var labelMenu by remember { mutableStateOf(false) }
                    Box {
                        FilterChip(
                            selected = labelFilter != null,
                            onClick = { labelMenu = true },
                            label = { Text(labelFilter?.let { "#$it" } ?: "All labels", maxLines = 1) },
                        )
                        DropdownMenu(expanded = labelMenu, onDismissRequest = { labelMenu = false }) {
                            DropdownMenuItem(text = { Text("All labels") }, onClick = { labelMenu = false; vm.labelFilter.value = null })
                            usedLabels.forEach { l ->
                                DropdownMenuItem(text = { Text("#$l") }, onClick = { labelMenu = false; vm.labelFilter.value = l })
                            }
                        }
                    }
                }
            }

            val spent = txns.filter { it.type == TxnType.DEBIT && !it.excluded }.sumOf { it.amountMinor }
            val received = txns.filter { it.type == TxnType.CREDIT && !it.excluded }.sumOf { it.amountMinor }
            androidx.compose.material3.Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    SummaryFigure("Spent", formatInr(spent), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
                    SummaryFigure("Received", formatInr(received), com.myfinance.app.ui.theme.IncomeGreen, Modifier.weight(1f))
                    SummaryFigure("Entries", txns.size.toString(), MaterialTheme.colorScheme.onSurface, Modifier.weight(0.7f))
                }
            }
            sync.message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
            }

            val loaded by vm.loaded.collectAsState()
            if (!loaded) {
                androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                Text("Loading your entries…", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (txns.isEmpty()) {
                Text(
                    if (filter != null || labelFilter != null) "No entries match these filters this month. Tap All, or pick another month."
                    else "No entries this month yet. They appear as bank SMS arrive; you can also import statements or add one with +.",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp)) {
                byDay.forEach { (day, list) ->
                    item(key = "h$day") {
                        val dayOut = list.filter { it.type == TxnType.DEBIT && !it.excluded }.sumOf { it.amountMinor }
                        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                when (day) { LocalDate.now() -> "Today"; LocalDate.now().minusDays(1) -> "Yesterday"; else -> day.format(dayFormat) },
                                style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f),
                            )
                            if (dayOut > 0) Text(formatInr(dayOut), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    items(list, key = { it.id }) { t ->
                        TransactionRow(t, colorOf[t.category] ?: fallback, meta.labels(t), meta.hidden(t), nicks[t.uid.substringBefore('#')]) { onOpen(t.id) }
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

@Composable
private fun SummaryFigure(label: String, value: String, color: Color, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        com.myfinance.app.ui.FitText(value, style = MaterialTheme.typography.titleMedium, color = color)
    }
}
