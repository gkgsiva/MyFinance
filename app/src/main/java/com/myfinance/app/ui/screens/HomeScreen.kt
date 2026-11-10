package com.myfinance.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.myfinance.app.ui.FinanceViewModel
import com.myfinance.app.ui.theme.ExpenseRed
import com.myfinance.app.ui.theme.IncomeGreen
import com.myfinance.core.Balances
import com.myfinance.core.formatInr
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val homeDate = DateTimeFormatter.ofPattern("EEEE, d MMMM")
private val monthName = DateTimeFormatter.ofPattern("MMMM")

/**
 * Home: kept simple. Today's transactions and what is due today and over the next few days (with anything
 * missed), one line for the month so far, and links to the Dashboard and other screens.
 */
@Composable
fun HomeScreen(
    vm: FinanceViewModel,
    smsGranted: Boolean,
    onImport: () -> Unit,
    open: (String) -> Unit,
) {
    val importState by vm.importState.collectAsState()
    val month by vm.month.collectAsState()
    val summary by vm.summary.collectAsState()
    val balances by vm.balances.collectAsState()

    Scaffold(floatingActionButton = { com.myfinance.app.ui.v15.QuickAddFab(vm, open) }) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Today", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(LocalDate.now().format(homeDate), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onImport, enabled = !importState.running) {
                        if (importState.running) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Filled.Refresh, contentDescription = "Read new SMS")
                    }
                }
            }

            if (!smsGranted) item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Read your bank SMS", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "MyFinance reads transaction messages from your banks to build your spending profile. Everything stays on this phone.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        FilledTonalButton(onClick = onImport) { Text("Allow and import") }
                    }
                }
            }
            importState.lastAdded?.let { added ->
                item {
                    Text(
                        if (added == 0) "No new transactions found in SMS." else "Imported $added new transactions from SMS.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            importState.error?.let { err -> item { Text(err, color = MaterialTheme.colorScheme.error) } }

            // Today's transactions, then payments due today and in the next few days, and missed ones.
            item { com.myfinance.app.ui.v15.TodayCard(vm, open) }

            // One line for the month so far; the details are on the Dashboard.
            item {
                Card(Modifier.fillMaxWidth().clickable { open("dashboard") }) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${month.format(monthName)} so far", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text("Dashboard ›", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                        Row {
                            Text("Spent ", style = MaterialTheme.typography.bodyMedium)
                            Text(formatInr(summary.expenseMinor), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = ExpenseRed)
                            Text("   Received ", style = MaterialTheme.typography.bodyMedium)
                            Text(formatInr(summary.incomeMinor), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = IncomeGreen)
                        }
                        if (balances.any { !it.isCardLimit }) {
                            Text(
                                "In your bank accounts: ${formatInr(Balances.totalBankMinor(balances))}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { open("dashboard") }, label = { Text("Dashboard") })
                    AssistChip(onClick = { open("payments-ahead") }, label = { Text("Payments ahead") })
                    AssistChip(onClick = { open("import-statement") }, label = { Text("Import statement") })
                    AssistChip(onClick = { open("reports") }, label = { Text("Reports") })
                }
            }
            item { Box(Modifier.height(72.dp)) }
        }
    }
}
