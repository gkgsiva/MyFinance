package com.myfinance.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.myfinance.app.ui.FinanceViewModel
import com.myfinance.core.TxnType
import com.myfinance.core.formatInr
import java.time.YearMonth

@Composable
fun TransactionsScreen(vm: FinanceViewModel, onOpen: (Long) -> Unit, onAdd: () -> Unit) {
    val month by vm.month.collectAsState()
    val txns by vm.filteredTransactions.collectAsState()
    val query by vm.query.collectAsState()
    val filter by vm.categoryFilter.collectAsState()
    val categories by vm.categories.collectAsState()
    val colorOf = categories.associate { it.name to Color(it.color) }
    val fallback = MaterialTheme.colorScheme.outline

    val byDay = txns.groupBy { it.timestamp.toLocalDateTime().toLocalDate() }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) { Icon(Icons.Filled.Add, contentDescription = "Add transaction") }
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = vm::previousMonth) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous month")
                }
                Text(month.format(monthFormat), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = vm::nextMonth, enabled = month < YearMonth.now()) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next month")
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { vm.query.value = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text("Search merchant, note, account, person") },
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

            val spent = txns.filter { it.type == TxnType.DEBIT && !it.excluded }.sumOf { it.amountMinor }
            val received = txns.filter { it.type == TxnType.CREDIT && !it.excluded }.sumOf { it.amountMinor }
            Text(
                "${txns.size} transactions · spent ${formatInr(spent)} · received ${formatInr(received)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            if (txns.isEmpty()) {
                Text(
                    "Nothing here. Import SMS from the Overview tab or add an entry with +.",
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
                        TransactionRow(t, colorOf[t.category] ?: fallback) { onOpen(t.id) }
                    }
                }
            }
        }
    }
}
