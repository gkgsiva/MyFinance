package com.myfinance.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.myfinance.app.data.TransactionEntity
import com.myfinance.app.data.toLite
import com.myfinance.app.ui.FinanceViewModel
import com.myfinance.app.ui.theme.ExpenseRed
import com.myfinance.app.ui.theme.IncomeGreen
import com.myfinance.core.AccountPeriods
import com.myfinance.core.PeriodGroup
import com.myfinance.core.Period
import com.myfinance.core.formatInr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val ALL = "All accounts"

/**
 * Every bank account and credit card, statement imports and SMS alike: totals per month, quarter
 * or year, consolidated across all accounts or for one, with the individual transactions a tap away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(vm: FinanceViewModel, onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val all by vm.allTransactions.collectAsState()
    val me by vm.ownerName.collectAsState()
    val categories by vm.categories.collectAsState()
    val colorOf = categories.associate { it.name to Color(it.color) }
    val fallback = MaterialTheme.colorScheme.outline

    var account by rememberSaveable { mutableStateOf(ALL) }
    var period by rememberSaveable { mutableStateOf(Period.MONTH) }
    var itemsView by rememberSaveable { mutableStateOf(false) }

    data class Data(val accounts: List<String>, val groups: List<PeriodGroup>, val byId: Map<Long, TransactionEntity>)

    val data by produceState(Data(emptyList(), emptyList(), emptyMap()), all, me, account, period) {
        value = withContext(Dispatchers.Default) {
            val counted = all.filterNot { it.excluded }
            val lite = counted.map { it.toLite(me) }
            val chosen = if (account == ALL) lite else lite.filter { (it.account ?: AccountPeriods.NO_ACCOUNT) == account }
            Data(AccountPeriods.accounts(lite), AccountPeriods.group(chosen, period), counted.associateBy { it.id })
        }
    }
    var expanded by remember(account, period) { mutableStateOf(setOf<String>()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Accounts & cards") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { inner ->
        LazyColumn(
            Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (listOf(ALL) + data.accounts).forEach { a ->
                            FilterChip(selected = account == a, onClick = { account = a }, label = { Text(a) })
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Period.entries.forEach { p ->
                            FilterChip(selected = period == p, onClick = { period = p }, label = { Text(p.label) })
                        }
                    }
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        SegmentedButton(selected = !itemsView, onClick = { itemsView = false }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Grouped") }
                        SegmentedButton(selected = itemsView, onClick = { itemsView = true }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("All items") }
                    }
                    if (data.groups.isNotEmpty()) {
                        val spent = data.groups.sumOf { it.spentMinor }
                        val received = data.groups.sumOf { it.receivedMinor }
                        val from = data.groups.last().start
                        Text(
                            "Since ${from.format(monthFormat)}: spent ${formatInr(spent)} · received ${formatInr(received)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }
            }

            if (data.groups.isEmpty()) {
                item { Text("No transactions yet.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }

            if (itemsView) {
                data.groups.forEach { g ->
                    item(key = "h${g.start}") {
                        PeriodHeader(g)
                        HorizontalDivider()
                    }
                    val ids = g.accounts.flatMap { it.ids }
                    val rows = ids.mapNotNull { data.byId[it] }.sortedByDescending { it.timestamp }
                    items(rows, key = { "t${g.start}-${it.id}" }) { t -> TransactionRow(t, colorOf[t.category] ?: fallback) { onOpen(t.id) } }
                }
            } else {
                data.groups.forEach { g ->
                    item(key = "g${g.start}") {
                        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                            Column(Modifier.padding(vertical = 8.dp)) {
                                PeriodHeader(g)
                                g.accounts.forEach { a ->
                                    val key = "${g.start}|${a.account}"
                                    val open = key in expanded
                                    HorizontalDivider()
                                    Row(
                                        Modifier.fillMaxWidth().clickable { expanded = if (open) expanded - key else expanded + key }
                                            .padding(horizontal = 16.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(a.account, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                            Text(
                                                "${a.count} transactions · ${if (open) "tap to hide" else "tap to see them"}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Column(horizontalAlignment = Alignment.End) {
                                            if (a.spentMinor > 0) Text("−" + formatInr(a.spentMinor), color = ExpenseRed, style = MaterialTheme.typography.bodySmall)
                                            if (a.receivedMinor > 0) Text("+" + formatInr(a.receivedMinor), color = IncomeGreen, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                    if (open) {
                                        a.ids.mapNotNull { data.byId[it] }.forEach { t ->
                                            TransactionRow(t, colorOf[t.category] ?: fallback) { onOpen(t.id) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PeriodHeader(g: PeriodGroup) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(g.label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text(
            "Spent ${formatInr(g.spentMinor)} · received ${formatInr(g.receivedMinor)} · ${g.count} transactions",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
