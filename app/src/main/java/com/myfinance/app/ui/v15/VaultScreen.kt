package com.myfinance.app.ui.v15

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.myfinance.app.ui.FinanceViewModel
import com.myfinance.app.ui.PolicyRow
import com.myfinance.app.ui.V12ViewModel
import com.myfinance.app.ui.WarrantyRow
import com.myfinance.app.ui.v12.AmountField
import com.myfinance.app.ui.v12.DateButton
import com.myfinance.app.ui.v12.Dropdown
import com.myfinance.app.ui.v12.Hint12
import com.myfinance.app.ui.v12.InfoCard
import com.myfinance.app.ui.v12.PhotosCard
import com.myfinance.app.ui.v12.asInput
import com.myfinance.app.ui.v12.shortDate
import com.myfinance.core.Policy
import com.myfinance.core.PolicyKind
import com.myfinance.core.Vault
import com.myfinance.core.Warranty
import com.myfinance.core.formatInr
import com.myfinance.core.parseAmountToMinor
import java.time.LocalDate

private val premiumOptions = listOf(12 to "Yearly", 6 to "Half-yearly", 3 to "Quarterly", 1 to "Monthly", 0 to "Single premium")

/** Insurance policies and product warranties with renewal dates and photos. */
@Composable
fun VaultScreen(vm: FinanceViewModel, v12: V12ViewModel, onBack: () -> Unit) {
    val s = vm.v15
    val policies by s.policies.collectAsState()
    val warranties by s.warranties.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var editPolicy by remember { mutableStateOf<Policy?>(null) }
    var newPolicy by remember { mutableStateOf(false) }
    var editWarranty by remember { mutableStateOf<Warranty?>(null) }
    var newWarranty by remember { mutableStateOf(false) }
    var openPolicy by remember { mutableStateOf<PolicyRow?>(null) }
    var openWarranty by remember { mutableStateOf<WarrantyRow?>(null) }

    V15Screen("Insurance & warranties", s, onBack, onAdd = { if (tab == 0) newPolicy = true else newWarranty = true }) {
        item {
            TabRow(selectedTabIndex = tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text("Insurance (${policies.size})") })
                Tab(tab == 1, { tab = 1 }, text = { Text("Warranties (${warranties.size})") })
            }
        }
        if (tab == 0) {
            if (policies.isEmpty()) item { Hint12("Add your health, term, car and home policies: insurer, policy number, cover and renewal date, with a photo of the policy. You'll get a reminder 30, 7 and 1 day before each renewal.") }
            else item {
                InfoCard {
                    Row15("Premiums a year", "${policies.size} policies", formatInr(Vault.yearlyPremium(policies.map { it.policy })))
                    Row15("Total cover", null, formatInr(policies.sumOf { it.policy.coverMinor ?: 0 }))
                }
            }
            items(policies, key = { it.policy.id }) { r ->
                val p = r.policy
                InfoCard {
                    Row15(
                        p.name, "${p.kind.label} · ${p.insurer}" + (p.coverMinor?.let { " · cover ${formatInr(it)}" } ?: ""),
                        p.premiumMinor?.let(::formatInr) ?: "",
                    ) { openPolicy = r }
                    p.renewalDate?.let { d ->
                        Text("${r.status.state.label}: renews ${d.format(shortDate)} (${daysText(r.status.daysLeft)})", color = r.status.state.color(), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        } else {
            if (warranties.isEmpty()) item { Hint12("Add the fridge, phone, AC or laptop with the shop, purchase date and warranty period, and a photo of the bill. You'll be reminded before the warranty ends.") }
            items(warranties, key = { it.warranty.id }) { r ->
                val w = r.warranty
                InfoCard {
                    Row15(w.item, listOfNotNull(w.shop, "bought ${w.purchaseDate.format(shortDate)}").joinToString(" · "), w.priceMinor?.let(::formatInr) ?: "") { openWarranty = r }
                    Text(
                        if ((r.status.daysLeft ?: 0) < 0) "Warranty ended ${w.endDate.format(shortDate)}"
                        else "Warranty till ${w.endDate.format(shortDate)} (${daysText(r.status.daysLeft)})",
                        color = r.status.state.color(), style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }

    openPolicy?.let { r ->
        val p = r.policy
        AlertDialog(
            onDismissRequest = { openPolicy = null },
            title = { Text(p.name) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${p.kind.label} · ${p.insurer}")
                    p.policyNumber?.let { Text("Policy no. $it", fontWeight = FontWeight.SemiBold) }
                    p.coverMinor?.let { Text("Cover ${formatInr(it)}") }
                    p.premiumMinor?.let { Text("Premium ${formatInr(it)} · ${premiumOptions.firstOrNull { o -> o.first == p.everyMonths }?.second.orEmpty()}") }
                    p.renewalDate?.let { Text("Renews ${it.format(shortDate)}") }
                    p.members?.let { Text("Covers: $it") }
                    p.note?.let { Text(it) }
                    PhotosCard(v12, "vault:${p.id}", "Policy documents", "Add a photo of the policy schedule or card, so it's at hand when you need to claim.")
                }
            },
            confirmButton = {
                Row {
                    if (p.renewalDate != null) TextButton(onClick = { s.renewPolicy(p); openPolicy = null }) { Text("Renewed") }
                    TextButton(onClick = { editPolicy = p; openPolicy = null }) { Text("Edit") }
                }
            },
            dismissButton = { TextButton(onClick = { openPolicy = null }) { Text("Close") } },
        )
    }

    openWarranty?.let { r ->
        val w = r.warranty
        AlertDialog(
            onDismissRequest = { openWarranty = null },
            title = { Text(w.item) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    w.shop?.let { Text("From $it") }
                    Text("Bought ${w.purchaseDate.format(shortDate)}" + (w.priceMinor?.let { " for ${formatInr(it)}" } ?: ""))
                    Text("Warranty ${w.months} months, till ${w.endDate.format(shortDate)}")
                    w.note?.let { Text(it) }
                    PhotosCard(v12, "vault:${w.id}", "Bill and warranty card", "Shops ask for the bill for any repair under warranty.")
                }
            },
            confirmButton = { TextButton(onClick = { editWarranty = w; openWarranty = null }) { Text("Edit") } },
            dismissButton = { TextButton(onClick = { openWarranty = null }) { Text("Close") } },
        )
    }

    if (newPolicy || editPolicy != null) PolicyDialog(editPolicy, onSave = { s.savePolicy(it) }, onDelete = { s.deletePolicy(it) }) { newPolicy = false; editPolicy = null }
    if (newWarranty || editWarranty != null) WarrantyDialog(editWarranty, onSave = { s.saveWarranty(it) }, onDelete = { s.deleteWarranty(it) }) { newWarranty = false; editWarranty = null }
}

@Composable
private fun PolicyDialog(p: Policy?, onSave: (Policy) -> Unit, onDelete: (String) -> Unit, onClose: () -> Unit) {
    var name by remember { mutableStateOf(p?.name.orEmpty()) }
    var kind by remember { mutableStateOf(p?.kind ?: PolicyKind.HEALTH) }
    var insurer by remember { mutableStateOf(p?.insurer.orEmpty()) }
    var number by remember { mutableStateOf(p?.policyNumber.orEmpty()) }
    var cover by remember { mutableStateOf(p?.coverMinor?.asInput().orEmpty()) }
    var premium by remember { mutableStateOf(p?.premiumMinor?.asInput().orEmpty()) }
    var renewal by remember { mutableStateOf(p?.renewalDate) }
    var every by remember { mutableStateOf(p?.everyMonths ?: 12) }
    var members by remember { mutableStateOf(p?.members.orEmpty()) }
    var note by remember { mutableStateOf(p?.note.orEmpty()) }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (p == null) "New policy" else "Edit policy") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it; error = false }, label = { Text("Name, e.g. Family health cover") }, isError = error, singleLine = true, modifier = Modifier.fillMaxWidth())
                Dropdown("Kind", kind, PolicyKind.entries, { it.label }, { kind = it })
                OutlinedTextField(insurer, { insurer = it }, label = { Text("Insurer") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(number, { number = it }, label = { Text("Policy number") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                AmountField("Cover / sum insured (₹)", cover, { cover = it })
                AmountField("Premium (₹)", premium, { premium = it })
                Dropdown("Premium paid", every, premiumOptions.map { it.first }, { v -> premiumOptions.first { it.first == v }.second }, { every = it })
                DateButton("Next renewal", renewal, { renewal = it }, allowClear = true)
                OutlinedTextField(members, { members = it }, label = { Text("People covered") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(note, { note = it }, label = { Text("Note (agent, TPA, claim number)") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isBlank()) { error = true; return@TextButton }
                onSave(
                    Policy(
                        id = p?.id ?: java.util.UUID.randomUUID().toString(), name = name.trim(), kind = kind,
                        insurer = insurer.trim(), policyNumber = number.trim().ifEmpty { null }, coverMinor = parseAmountToMinor(cover),
                        premiumMinor = parseAmountToMinor(premium), renewalDate = renewal, everyMonths = every,
                        members = members.trim().ifEmpty { null }, note = note.trim().ifEmpty { null },
                    ),
                )
                onClose()
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (p != null) TextButton(onClick = { onDelete(p.id); onClose() }) { Text("Delete") }
                TextButton(onClick = onClose) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun WarrantyDialog(w: Warranty?, onSave: (Warranty) -> Unit, onDelete: (String) -> Unit, onClose: () -> Unit) {
    var item by remember { mutableStateOf(w?.item.orEmpty()) }
    var shop by remember { mutableStateOf(w?.shop.orEmpty()) }
    var bought by remember { mutableStateOf(w?.purchaseDate ?: LocalDate.now()) }
    var price by remember { mutableStateOf(w?.priceMinor?.asInput().orEmpty()) }
    var months by remember { mutableStateOf((w?.months ?: 12).toString()) }
    var note by remember { mutableStateOf(w?.note.orEmpty()) }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (w == null) "New warranty" else "Edit warranty") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(item, { item = it; error = false }, label = { Text("Item, e.g. Samsung fridge") }, isError = error, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(shop, { shop = it }, label = { Text("Shop") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                DateButton("Bought on", bought, { d -> d?.let { bought = it } })
                AmountField("Price (₹)", price, { price = it })
                OutlinedTextField(months, { v -> months = v.filter(Char::isDigit).take(3) }, label = { Text("Warranty (months)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(note, { note = it }, label = { Text("Note (serial no., service number)") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val m = months.toIntOrNull()
                if (item.isBlank() || m == null || m <= 0) { error = true; return@TextButton }
                onSave(
                    Warranty(
                        id = w?.id ?: java.util.UUID.randomUUID().toString(), item = item.trim(), shop = shop.trim().ifEmpty { null },
                        purchaseDate = bought, priceMinor = parseAmountToMinor(price), months = m, note = note.trim().ifEmpty { null },
                    ),
                )
                onClose()
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (w != null) TextButton(onClick = { onDelete(w.id); onClose() }) { Text("Delete") }
                TextButton(onClick = onClose) { Text("Cancel") }
            }
        },
    )
}
