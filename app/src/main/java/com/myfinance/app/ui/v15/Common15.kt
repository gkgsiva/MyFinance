package com.myfinance.app.ui.v15

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.myfinance.app.ui.V15State
import com.myfinance.app.ui.theme.Amber
import com.myfinance.app.ui.theme.ExpenseRed
import com.myfinance.app.ui.theme.IncomeGreen
import com.myfinance.core.VaultState

/** Screen with a back arrow, an optional + button, and v1.5 messages as a snackbar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun V15Screen(
    title: String,
    state: V15State,
    onBack: () -> Unit,
    onAdd: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    val message by state.message.collectAsState()
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); state.clearMessage() }
    }
    Scaffold(
        topBar = {
            com.myfinance.app.ui.theme.AppTopBar(
                title = { Text(title, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { actions() },
            )
        },
        floatingActionButton = { onAdd?.let { com.myfinance.app.ui.theme.AppFab(onClick = it) { Icon(Icons.Filled.Add, contentDescription = "Add") } } },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        LazyColumn(
            Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

/** A tappable row inside a card: title and hint on the left, value on the right. */
@Composable
fun Row15(title: String, hint: String?, value: String, valueColor: Color = Color.Unspecified, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Text(value, fontWeight = FontWeight.SemiBold, color = valueColor)
    }
}

@Composable
fun Tile15(title: String, value: String, hint: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(value, fontWeight = FontWeight.SemiBold)
        }
    }
}

fun VaultState.color(): Color = when (this) {
    VaultState.OK -> IncomeGreen
    VaultState.SOON -> Amber
    VaultState.URGENT, VaultState.DUE_TODAY, VaultState.LAPSED -> ExpenseRed
    VaultState.ENDED -> Color.Gray
}

fun daysText(days: Long?): String = when {
    days == null -> ""
    days < -1 -> "${-days} days ago"
    days == -1L -> "yesterday"
    days == 0L -> "today"
    days == 1L -> "tomorrow"
    else -> "in $days days"
}
