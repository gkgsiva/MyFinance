package com.myfinance.app.ui.v13

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.myfinance.app.ui.FinanceViewModel
import com.myfinance.app.ui.V12ViewModel
import com.myfinance.app.ui.screens.MonthCycleCard
import com.myfinance.app.ui.screens.toLocalDateTime
import com.myfinance.app.ui.theme.ThemeMode
import com.myfinance.app.ui.theme.ThemePref
import com.myfinance.core.formatInr
import java.time.format.DateTimeFormatter

private val dateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

/** One place for every setting, grouped by what it is for. [open] navigates to a route. */
@Composable
fun SettingsScreen(vm: FinanceViewModel, v12: V12ViewModel, onBack: () -> Unit, open: (String) -> Unit) {
    val context = LocalContext.current
    val trash by vm.recentlyDeleted.collectAsState()
    val hiddenTxns by vm.hiddenTransactions.collectAsState()
    val hiddenAuto by vm.hiddenAutoDebitItems.collectAsState()
    val labels by vm.usedLabels.collectAsState()
    val payees by vm.payeeNames.collectAsState()
    val rules by v12.rules.collectAsState()
    val lock by v12.lockEnabled.collectAsState()
    val owner by vm.ownerName.collectAsState()
    val theme by ThemePref.mode.collectAsState()
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    val notRecurring by vm.notRecurringKeys.collectAsState()
    val hiddenCount = hiddenTxns.size + hiddenAuto.size + notRecurring.size

    SimpleScreen(title = "Settings", onBack = onBack) {
        item { Group("Hidden & deleted") }
        item { SettingTile("Hidden items", if (hiddenCount == 0) "None" else "$hiddenCount", "Transactions and EMIs you hid. Unhide them here.") { open("hidden") } }
        item { SettingTile("Recently deleted", if (trash.isEmpty()) "Empty" else "${trash.size}", "Restore transactions deleted by mistake") { open("trash") } }

        item { Group("Statements & accounts") }
        item { SettingTile("Import statements", "", "Bank statements and credit card bills: PDF, Excel or CSV") { open("import-statement") } }
        item { SettingTile("Accounts & cards", "", "Every account and card, together or one at a time") { open("accounts") } }
        item { SettingTile("My cards & accounts", "", "Your and your family's card and account numbers, so statements and SMS go to the right person") { open("my-accounts") } }
        item {
            val day by vm.v14.statementReminderDay.collectAsState()
            SettingTile("Statement reminders", if (day == 0) "Off" else "Day $day", "A monthly nudge to import the latest bank and card statements") { open("statement-reminders") }
        }
        item { SettingTile("Card bill check", "", "Card statements against what you paid") { open("card-bills") } }

        item { Group("Messages") }
        item { SettingTile("Rescan messages", "", "Read the SMS inbox again without making duplicates") { open("rescan") } }
        item { SettingTile("Custom SMS rules", "${rules.size}", "e.g. any SMS with SWIGGY goes to Food") { open("rules") } }
        item {
            SettingTile("SMS permission", "", "Open app settings to allow SMS (⋮ > Allow restricted settings on Android 13+)") {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }

        item { Group("Organise") }
        item { SettingTile("Labels & label budgets", if (labels.isEmpty()) "None yet" else "${labels.size}", "Rename or delete labels, and set a monthly limit per label") { open("labels") } }
        item { SettingTile("Label suggestions", "", "Payments that look like ones you already labelled") { open("label-suggestions") } }
        item { SettingTile("Payee names", if (payees.isEmpty()) "None yet" else "${payees.size}", "Names like \"Vegetable seller Ravi\" used for all payments") { open("payees") } }
        item { SettingTile("Categories & budgets", "", "Add categories and monthly budgets") { open("categories") } }
        item { SettingTile("Auto debits & EMIs", "", "Your EMIs, SIPs and autopay with next dates") { open("autodebits") } }

        item { Group("Month") }
        item { SettingTile("Month for budgets and reports", "", "Count late salary in next month, or start the month on another day") { open("month-settings") } }

        item { Group("Family") }
        item { SettingTile("This phone belongs to", owner, "Name, export, merge and WhatsApp sharing are on the Manage tab") { open("categories") } }

        item { Group("Security & backup") }
        item { SettingTile("App lock", if (lock) "On" else "Off", "PIN or fingerprint to open the app") { open("security") } }
        item { SettingTile("Backup & restore", "", "Encrypted backup of everything, to move to a new phone") { open("backup") } }

        item { Group("Look & notifications") }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Theme", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeMode.entries.forEach { m ->
                            FilterChip(selected = theme == m, onClick = { ThemePref.set(m) }, label = { Text(m.label) })
                        }
                    }
                }
            }
        }
        item {
            SettingTile("Notifications", "", "Bill, EMI and budget alerts: turn them on or off") {
                val intent = if (Build.VERSION.SDK_INT >= 26) {
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                } else {
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                }
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }

        item { Group("About") }
        item { SettingTile("MyFinance", "v$version", "Your data stays on this phone") {} }
    }
}

@Composable
private fun Group(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun SettingTile(title: String, value: String, hint: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Muted(hint)
            }
            if (value.isNotEmpty()) Text(value, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** Everything hidden, with Unhide. Hidden things are still counted in totals. */
@Composable
fun HiddenItemsScreen(vm: FinanceViewModel, onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val txns by vm.hiddenTransactions.collectAsState()
    val auto by vm.hiddenAutoDebitItems.collectAsState()
    val notRecurring by vm.notRecurringKeys.collectAsState()

    SimpleScreen(
        title = "Hidden items",
        onBack = onBack,
        actions = {
            if (txns.isNotEmpty() || auto.isNotEmpty() || notRecurring.isNotEmpty()) {
                TextButton(onClick = { vm.unhideAllTxns(); vm.unhideAutoDebits() }) { Text("Unhide all") }
            }
        },
    ) {
        item { Muted("Hidden items aren't shown in lists, but they are still counted in every total.") }
        item { Text("Transactions", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
        if (txns.isEmpty()) item { Muted("No hidden transactions. To hide one, tap it and turn on \"Hide from lists\".") }
        items(txns, key = { "t${it.uid}" }) { t ->
            HiddenRow(
                title = t.merchant ?: t.category,
                detail = "${t.category} · ${t.timestamp.toLocalDateTime().format(dateFmt)} · ${formatInr(t.amountMinor)}",
                onOpen = { onOpen(t.id) },
                onUnhide = { vm.unhideTxn(t.uid) },
            )
        }
        item { Text("Auto debits & EMIs", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
        if (auto.isEmpty()) item { Muted("No hidden auto debits or EMIs.") }
        items(auto, key = { "a${it.key}" }) { a ->
            HiddenRow(
                title = a.name,
                detail = "${a.kind.label} · ${formatInr(a.amountMinor)} · next ${a.nextDate.format(dateFmt)}",
                onOpen = null,
                onUnhide = { vm.unhideAutoDebit(a.key) },
            )
        }
        if (notRecurring.isNotEmpty()) {
            item { Text("Not recurring", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
            item { Muted("Payees you marked as not a recurring payment. They are left out of auto debits and their totals.") }
            items(notRecurring, key = { "n$it" }) { k ->
                HiddenRow(
                    title = k.removePrefix(com.myfinance.core.AutoDebits.NOT_RECURRING_PREFIX).replaceFirstChar(Char::uppercase),
                    detail = "Not a recurring payment",
                    onOpen = null,
                    onUnhide = { vm.unhideAutoDebit(k) },
                )
            }
        }
    }
}

@Composable
private fun HiddenRow(title: String, detail: String, onOpen: (() -> Unit)?, onUnhide: () -> Unit) {
    Card(Modifier.fillMaxWidth().let { if (onOpen != null) it.clickable(onClick = onOpen) else it }) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Muted(detail)
            }
            TextButton(onClick = onUnhide) { Text("Unhide") }
        }
    }
}

/** Saved payee names: rename one everywhere, or stop using it for new SMS. */
@Composable
fun PayeeNamesScreen(vm: FinanceViewModel, onBack: () -> Unit) {
    val payees by vm.payeeNames.collectAsState()
    var editing by remember { mutableStateOf<String?>(null) }

    SimpleScreen(title = "Payee names", onBack = onBack) {
        item {
            Muted(
                "To name a payee, tap one of their transactions, change \"Paid to\" (e.g. Vegetable seller Ravi) " +
                    "and tick \"Call every payment…\". Their past and new payments then use that name.",
            )
        }
        if (payees.isEmpty()) item { Text("No payee names yet.", modifier = Modifier.padding(top = 16.dp)) }
        items(payees, key = { it.first }) { (name, raw) ->
            Card(Modifier.fillMaxWidth().clickable { editing = name }) {
                Column(Modifier.padding(12.dp)) {
                    Text(name, fontWeight = FontWeight.SemiBold)
                    Muted("Used for: " + raw.joinToString(", "))
                }
            }
        }
    }

    editing?.let { old ->
        var name by remember(old) { mutableStateOf(old) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Payee name") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it.take(40) }, label = { Text("Name") }, singleLine = true)
                    Muted("\"Stop\" keeps the name on past payments but doesn't use it for new SMS.")
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { vm.renamePayeeName(old, name); editing = null }) { Text("Rename all") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { vm.forgetPayeeName(old); editing = null }) { Text("Stop") }
                    TextButton(onClick = { editing = null }) { Text("Cancel") }
                }
            },
        )
    }
}

@Composable
fun LabelsScreen(vm: FinanceViewModel, onBack: () -> Unit) {
    SimpleScreen(title = "Labels", onBack = onBack) {
        item { LabelsCard(vm) }
        item { com.myfinance.app.ui.v14.LabelBudgetsCard(vm) }
    }
}

@Composable
fun MonthSettingsScreen(vm: FinanceViewModel, onBack: () -> Unit) {
    SimpleScreen(title = "Month for budgets and reports", onBack = onBack) {
        item { MonthCycleCard(vm) }
    }
}
