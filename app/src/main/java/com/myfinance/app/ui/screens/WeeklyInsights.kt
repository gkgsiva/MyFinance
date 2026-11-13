package com.myfinance.app.ui.screens

import com.myfinance.app.data.isSpend
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.myfinance.app.ui.FinanceViewModel
import com.myfinance.app.ui.SplitView
import com.myfinance.app.ui.charts.compactInr
import com.myfinance.core.TxnType
import com.myfinance.core.formatInr
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

private val weekMonth = DateTimeFormatter.ofPattern("MMMM yyyy")
private val weekDayShort = DateTimeFormatter.ofPattern("EEE")
private val timeShort = DateTimeFormatter.ofPattern("h:mm a")
private val dayLong = DateTimeFormatter.ofPattern("EEEE, d MMM")

/**
 * This week at a glance: a Monday-to-Sunday strip (tap a day), a soft bar for each day's spend with the
 * chosen day darker, and that day's payments as a colour-coded timeline (colour = category).
 * Transfers and card bill payments that are left out of totals are left out here too.
 */
@Composable
fun WeeklyInsightsCard(vm: FinanceViewModel, onOpen: (Long) -> Unit) {
    val all by vm.allTransactions.collectAsState()
    val categories by vm.categories.collectAsState()
    val nicks by vm.nicknames.collectAsState()
    val colorOf = remember(categories) { categories.associate { it.name to Color(it.color) } }
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now()
    var weekStartEpoch by rememberSaveable { mutableStateOf(today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toEpochDay()) }
    var selectedEpoch by rememberSaveable { mutableStateOf(today.toEpochDay()) }
    val weekStart = LocalDate.ofEpochDay(weekStartEpoch)
    val days = (0L..6L).map { weekStart.plusDays(it) }
    val selected = LocalDate.ofEpochDay(selectedEpoch).takeIf { it in days } ?: days.firstOrNull { it == today } ?: days.last()

    val byDay = remember(all, weekStartEpoch) {
        val end = weekStart.plusDays(7)
        all.filter { !it.excluded }
            .map { it to Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }
            .filter { (_, d) -> !d.isBefore(weekStart) && d.isBefore(end) }
            .groupBy({ it.second }, { it.first })
    }
    val spendOf = days.associateWith { d -> byDay[d].orEmpty().filter { it.isSpend }.sumOf { it.amountMinor } }
    val weekTotal = spendOf.values.sum()
    val max = (spendOf.values.maxOrNull() ?: 0L).coerceAtLeast(1L)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val purple = MaterialTheme.colorScheme.primary

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Month and week arrows.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(weekStart.plusDays(3).format(weekMonth), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { weekStartEpoch -= 7; selectedEpoch = weekStartEpoch + 6 }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous week")
                }
                IconButton(
                    onClick = { weekStartEpoch += 7; selectedEpoch = minOf(weekStartEpoch + 6, today.toEpochDay()) },
                    enabled = weekStart.plusDays(7) <= today,
                ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next week") }
            }
            // Day strip.
            Row(Modifier.fillMaxWidth()) {
                days.forEach { d ->
                    val on = d == selected
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable(enabled = !d.isAfter(today)) { selectedEpoch = d.toEpochDay() }
                            .padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(d.format(weekDayShort).take(1), style = MaterialTheme.typography.labelMedium, color = muted)
                        Box(
                            Modifier.padding(top = 4.dp).size(34.dp).clip(CircleShape)
                                .background(if (on) purple.copy(alpha = 0.75f) else Color.Transparent),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                d.dayOfMonth.toString(), style = MaterialTheme.typography.bodyMedium,
                                color = when { on -> Color.White; d.isAfter(today) -> muted.copy(alpha = 0.5f); else -> MaterialTheme.colorScheme.onSurface },
                                fontWeight = if (d == today) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Spending this week", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(formatInr(weekTotal), style = MaterialTheme.typography.titleMedium)
            }
            // Bars.
            Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                days.forEach { d ->
                    val v = spendOf[d] ?: 0L
                    val on = d == selected
                    Column(Modifier.weight(1f).fillMaxHeight().clickable(enabled = !d.isAfter(today)) { selectedEpoch = d.toEpochDay() }, verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
                        if (v > 0) Text(compactInr(v), style = MaterialTheme.typography.labelSmall, color = muted, maxLines = 1)
                        Box(
                            Modifier.fillMaxWidth().fillMaxHeight((v.toFloat() / max * 0.8f).coerceAtLeast(0.03f))
                                .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 3.dp, bottomEnd = 3.dp))
                                .background(purple.copy(alpha = if (on) 0.8f else 0.35f)),
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                days.forEach { d ->
                    Text(d.format(weekDayShort), style = MaterialTheme.typography.labelSmall, color = muted, textAlign = TextAlign.Center, modifier = Modifier.weight(1f), maxLines = 1)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // The chosen day as a timeline.
            val list = byDay[selected].orEmpty().sortedBy { it.timestamp }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(if (selected == today) "Today" else selected.format(dayLong), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                spendOf[selected]?.takeIf { it > 0 }?.let { Text("spent ${formatInr(it)}", style = MaterialTheme.typography.labelLarge, color = muted) }
            }
            if (list.isEmpty()) Text("No payments on this day.", style = MaterialTheme.typography.bodySmall, color = muted)
            list.forEach { t ->
                val credit = t.type == TxnType.CREDIT
                val c = colorOf[t.category] ?: MaterialTheme.colorScheme.outline
                val name = nicks[t.uid.substringBefore('#')] ?: t.merchant ?: if (credit) "Money received" else "Payment"
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        Instant.ofEpochMilli(t.timestamp).atZone(zone).toLocalTime().format(timeShort),
                        style = MaterialTheme.typography.labelSmall, color = muted, modifier = Modifier.width(62.dp),
                    )
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(c.copy(alpha = 0.22f))
                            .clickable { onOpen(SplitView.originalId(t.id)) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Text(
                            name + " · " + (if (credit) "+" else "") + formatInr(t.amountMinor),
                            style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
