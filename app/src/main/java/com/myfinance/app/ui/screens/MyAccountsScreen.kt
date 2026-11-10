package com.myfinance.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.myfinance.app.ui.FinanceViewModel
import com.myfinance.app.ui.v13.Muted
import com.myfinance.app.ui.v13.SimpleScreen
import com.myfinance.core.MyAccount
import com.myfinance.core.MyAccounts

/** Banks offered first when adding a card or account; any other can be typed. */
private val commonBanks = listOf("ICICI Bank", "SBI", "Canara Bank", "HDFC Bank", "Axis Bank")

/**
 * Manage > My cards & accounts: the family's own cards and bank accounts by number, and whose each one is.
 * Statements and SMS for these numbers go on the right card and person by themselves.
 */
@Composable
fun MyAccountsScreen(vm: FinanceViewModel, onBack: () -> Unit) {
    val accounts by vm.myAccounts.collectAsState()
    val me by vm.ownerName.collectAsState()
    val people by vm.filterOptions.collectAsState()
    val message by vm.myAccountsMessage.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(message) {
        message?.let { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show(); vm.myAccountsMessage.value = null }
    }
    var editing by remember { mutableStateOf<MyAccount?>(null) }
    val blank = MyAccount(tail = "", bank = commonBanks.first())

    SimpleScreen("My cards & accounts", onBack, onAdd = { editing = blank }) {
        item {
            Muted(
                "Add your cards and bank accounts, and your family's. Statements you import and SMS for these numbers " +
                    "are put on the right card and person by themselves, including add-on cards listed on your statement. " +
                    "Only the last 4 digits are kept (and a card's first 6 when you type the full number).",
            )
        }
        if (accounts.isEmpty()) item { Text("Nothing added yet. Tap + to add a card or account.", style = MaterialTheme.typography.bodyMedium) }
        accounts.groupBy { it.owner ?: me }.forEach { (who, list) ->
            item { Text(who, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
            list.forEach { a ->
                item(key = a.id) {
                    Card(Modifier.fillMaxWidth().clickable { editing = a }) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(a.name?.takeIf { it.isNotBlank() } ?: (a.bank ?: "Account"), fontWeight = FontWeight.SemiBold)
                                Text(
                                    listOfNotNull(
                                        if (a.isCard) "Card" else "Account",
                                        a.bank.takeIf { !a.name.isNullOrBlank() },
                                        (a.prefix?.let { "$it…" } ?: "••") + a.tail,
                                        "PDF password saved".takeIf { !a.pdfPassword.isNullOrBlank() },
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { start ->
        MyAccountDialog(
            start = start,
            me = me,
            people = (people.first + accounts.mapNotNull { it.owner }).filter { it.isNotBlank() && it != me }.distinct(),
            onSave = { vm.saveMyAccount(it); editing = null },
            onDelete = if (start.id != 0L) ({ vm.deleteMyAccount(start.id); editing = null }) else null,
            onClose = { editing = null },
        )
    }
}

@Composable
private fun MyAccountDialog(
    start: MyAccount,
    me: String,
    people: List<String>,
    onSave: (MyAccount) -> Unit,
    onDelete: (() -> Unit)?,
    onClose: () -> Unit,
) {
    var isCard by remember { mutableStateOf(start.isCard) }
    var number by remember { mutableStateOf(if (start.tail.isEmpty()) "" else (start.prefix ?: "XXXX") + "XXXXXX" + start.tail) }
    var bank by remember { mutableStateOf(start.bank.orEmpty()) }
    var name by remember { mutableStateOf(start.name.orEmpty()) }
    var owner by remember { mutableStateOf(start.owner.orEmpty()) }
    var password by remember { mutableStateOf(start.pdfPassword.orEmpty()) }
    var error by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (start.id == 0L) "Add card or account" else "Edit") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(isCard, { isCard = true }, label = { Text("Credit card") })
                    FilterChip(!isCard, { isCard = false }, label = { Text("Bank account") })
                }
                OutlinedTextField(
                    number, { number = it; error = false },
                    label = { Text(if (isCard) "Card number (full or last 4)" else "Account number (full or last 4)") },
                    isError = error, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                )
                OutlinedTextField(bank, { bank = it }, label = { Text("Bank") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    commonBanks.take(3).forEach { b -> FilterChip(bank == b, { bank = b }, label = { Text(b.removeSuffix(" Bank")) }) }
                }
                OutlinedTextField(name, { name = it }, label = { Text("Name (optional), e.g. Amazon Pay card") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Whose", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(owner.isBlank() || owner == me, { owner = "" }, label = { Text("Me") })
                    people.take(3).forEach { p -> FilterChip(owner == p, { owner = p }, label = { Text(p) }) }
                }
                OutlinedTextField(
                    owner.takeIf { it != me }.orEmpty(), { owner = it },
                    label = { Text("Or type a name, e.g. your wife's") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    password, { password = it }, label = { Text("PDF statement password (optional)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                )
                Muted("Locked PDF statements are opened with this password by themselves. Use the same name as on that person's phone (their owner name) so family views match.")
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val parsed = MyAccounts.parseNumber(number, isCard)
                    // Editing without retyping: keep the stored digits.
                    ?: start.tail.takeIf { it.isNotEmpty() && number.filter(Char::isDigit).endsWith(it) }?.let { start.prefix to it }
                if (parsed == null) { error = true; return@TextButton }
                onSave(
                    start.copy(
                        tail = parsed.second, prefix = parsed.first ?: start.prefix.takeIf { parsed.second == start.tail },
                        bank = bank.trim().ifBlank { null }, isCard = isCard, name = name.trim().ifBlank { null },
                        owner = owner.trim().takeIf { it.isNotBlank() && it != me }, pdfPassword = password.ifBlank { null },
                    ),
                )
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                onDelete?.let { TextButton(onClick = it) { Text("Delete", color = MaterialTheme.colorScheme.error) } }
                TextButton(onClick = onClose) { Text("Cancel") }
            }
        },
    )
}
