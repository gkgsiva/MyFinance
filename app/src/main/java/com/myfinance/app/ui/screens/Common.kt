package com.myfinance.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.myfinance.app.data.TransactionEntity
import com.myfinance.app.data.TxnSource
import com.myfinance.app.data.accountLabel
import com.myfinance.app.ui.theme.ExpenseRed
import com.myfinance.app.ui.theme.IncomeGreen
import com.myfinance.core.TxnType
import com.myfinance.core.formatInr
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

val dayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM yyyy")
val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")
val monthFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy")

fun Long.toLocalDateTime() = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDateTime()

@Composable
fun ColorDot(color: Color, size: Int = 12) {
    Box(Modifier.size(size.dp).background(color, CircleShape))
}

@Composable
fun TransactionRow(
    txn: TransactionEntity, categoryColor: Color, labels: List<String> = emptyList(), hidden: Boolean = false,
    /** The user's own name for this payment; shown first, with the merchant name in the details. */
    nickname: String? = null,
    onClick: () -> Unit,
) {
    androidx.compose.material3.Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        com.myfinance.app.ui.theme.CategoryBadge(txn.category, categoryColor)
        Column(Modifier.weight(1f)) {
            Text(
                nickname ?: txn.merchant ?: if (txn.type == TxnType.CREDIT) "Money received" else "Payment",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val details = listOfNotNull(
                txn.timestamp.toLocalDateTime().format(timeFormat),
                nickname?.let { txn.merchant },
                txn.category,
                txn.owner,
                txn.accountLabel,
                when (txn.source) { TxnSource.MANUAL -> "manual"; TxnSource.STATEMENT -> "statement"; else -> null },
                if (txn.excluded) "excluded" else null,
                if (hidden) "hidden" else null,
            ).joinToString(" · ")
            Text(
                details,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (labels.isNotEmpty()) {
                Text(
                    labels.joinToString("  ") { "#$it" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            (if (txn.type == TxnType.CREDIT) "+" else "−") + formatInr(txn.amountMinor),
            color = if (txn.type == TxnType.CREDIT) IncomeGreen else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
    }
}
