package com.myfinance.app.ui.v16

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.myfinance.app.ui.V16State
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal val dayFmt16: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")
internal val monthFmt16: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy")

internal fun Long.day16(): String = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).format(dayFmt16)
internal fun YearMonth.label16(): String = format(monthFmt16)

/** Screen with a back arrow and v1.6 messages as a snackbar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun V16Screen(
    title: String,
    state: V16State,
    onBack: () -> Unit,
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
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { actions() },
            )
        },
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
