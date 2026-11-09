package com.myfinance.app.ui.v15

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.myfinance.app.ui.FinanceViewModel
import com.myfinance.app.ui.theme.Amber
import com.myfinance.app.ui.theme.IncomeGreen
import com.myfinance.app.ui.v12.Hint12
import com.myfinance.app.ui.v12.InfoCard
import com.myfinance.app.ui.v12.shortDate
import com.myfinance.core.formatInr

/** Subscriptions with price hikes, duplicates, double charges and the ones you don't use. */
@Composable
fun SubscriptionsScreen(vm: FinanceViewModel, onBack: () -> Unit) {
    val s = vm.v15
    val audit by s.subscriptions.collectAsState()

    V15Screen("Subscription audit", s, onBack) {
        val a = audit
        if (a == null) {
            item { Hint12("Looking through your payments…") }
            return@V15Screen
        }
        item {
            InfoCard {
                Row15("Subscriptions", "${a.items.size} found, ${a.flagged} to look at", formatInr(a.monthlyTotalMinor) + " a month")
                Row15("In a year", null, formatInr(a.yearlyTotalMinor))
                if (a.yearlySavingMinor > 0) Row15("Cancel the ones you don't use", "Saves a year", formatInr(a.yearlySavingMinor), IncomeGreen)
            }
        }
        if (a.items.isEmpty()) item { Hint12("No subscriptions found yet. Netflix, Prime, Hotstar, Spotify, YouTube, Google One, iCloud, gym memberships and other repeating charges show up here once paid.") }
        items(a.items, key = { it.key }) { sub ->
            InfoCard {
                Row15(
                    sub.name,
                    "${sub.kind.label} · ${sub.cadence.label} · last ${sub.lastDate.format(shortDate)} · next ${sub.nextDate.format(shortDate)}",
                    formatInr(sub.amountMinor),
                )
                Text("${formatInr(sub.yearlyMinor)} a year", style = MaterialTheme.typography.bodySmall)
                sub.details.forEach { Text("• $it", color = Amber, style = MaterialTheme.typography.bodySmall) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Still using it", modifier = Modifier.weight(1f))
                    Switch(checked = !sub.notUsed, onCheckedChange = { using -> s.markSubscription(sub.key, notUsed = !using) })
                }
                TextButton(onClick = { s.ignoreSubscription(sub.key) }) { Text("Not a subscription") }
            }
        }
        item { Hint12("\"Not used\" is your call: the app can't see whether you open an app. Mark the ones you don't use to see what cancelling saves.") }
    }
}
