package com.myfinance.app.ui.v15

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myfinance.app.ui.FinanceViewModel
import com.myfinance.app.ui.theme.Amber
import com.myfinance.app.ui.theme.ExpenseRed
import com.myfinance.app.ui.theme.IncomeGreen
import com.myfinance.app.ui.v12.Hint12
import com.myfinance.app.ui.v12.InfoCard
import com.myfinance.app.ui.v12.Section12
import com.myfinance.core.CalendarDay
import com.myfinance.core.FlowEvent
import com.myfinance.core.formatInr
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val monthTitle = DateTimeFormatter.ofPattern("MMMM yyyy")
private val dayTitle = DateTimeFormatter.ofPattern("EEEE, d MMMM")

/** A month of expected salary, EMIs, SIPs and bills next to what actually happened. */
@Composable
fun CalendarScreen(vm: FinanceViewModel, onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val s = vm.v15
    val month by s.calendarMonth.collectAsState()
    val cal by s.calendar.collectAsState()
    var selectedEpoch by rememberSaveable { mutableStateOf(LocalDate.now().toEpochDay()) }
    val selected = LocalDate.ofEpochDay(selectedEpoch)

    V15Screen("Cash flow calendar", s, onBack) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { s.calendarMonth.value = month.minusMonths(1) }) { Text("‹") }
                Text(month.format(monthTitle), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { s.calendarMonth.value = month.plusMonths(1) }) { Text("›") }
            }
        }
        val c = cal
        if (c == null || c.month != month) {
            item { Hint12("Working it out…") }
            return@V15Screen
        }
        item {
            InfoCard {
                Row15("Money in", "So far ${formatInr(c.actualInMinor)}, expected ${formatInr(c.expectedInMinor)} more", formatInr(c.projectedInMinor), IncomeGreen)
                Row15("Money out", "So far ${formatInr(c.actualOutMinor)}, expected ${formatInr(c.expectedOutMinor)} more", formatInr(c.projectedOutMinor), ExpenseRed)
            }
        }
        item { MonthGrid(c.days, selected) { selectedEpoch = it.toEpochDay() } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Legend(IncomeGreen, "Money in"); Legend(ExpenseRed, "Spent"); Legend(Amber, "Expected out")
            }
        }
        val day = c.days.firstOrNull { it.date == selected }
        if (day != null) {
            item { Section12(selected.format(dayTitle)) }
            if (day.events.isEmpty()) item { Hint12("Nothing on this day.") }
            items(day.events) { e -> EventRow(e, onOpen) }
        }
        item { Hint12("Expected items come from your auto debits and EMIs, bill reminders, salary pattern, subscriptions, insurance renewals and money lent. Past days show what actually happened.") }
    }
}

@Composable
fun EventRow(e: FlowEvent, onOpen: (Long) -> Unit) {
    InfoCard {
        Row15(
            e.title,
            (if (e.expected) "Expected · " else "") + e.kind.label + if (e.late) " · late" else "",
            e.amountMinor?.let { (if (e.incoming) "+" else "") + formatInr(it) } ?: "",
            when {
                e.incoming -> IncomeGreen
                e.expected -> Amber
                else -> ExpenseRed
            },
            onClick = e.txnId?.let { id -> { onOpen(id) } },
        )
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.size(4.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun MonthGrid(days: List<CalendarDay>, selected: LocalDate, onPick: (LocalDate) -> Unit) {
    val first = days.first().date
    val lead = first.dayOfWeek.value - 1 // Monday first
    val cells: List<CalendarDay?> = List(lead) { null } + days
    val today = LocalDate.now()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            listOf("M", "T", "W", "T", "F", "S", "S").forEach {
                Text(it, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall)
            }
        }
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (i in 0 until 7) {
                    val d = week.getOrNull(i)
                    Box(Modifier.weight(1f).aspectRatio(0.8f)) {
                        if (d != null) DayCell(d, d.date == selected, d.date == today) { onPick(d.date) }
                    }
                }
            }
        }
    }
}

private fun short(minor: Long): String {
    val r = minor / 100
    return when {
        r >= 1_00_000 -> "${r / 1_00_000}.${(r % 1_00_000) / 10_000}L"
        r >= 1_000 -> "${r / 1_000}k"
        else -> r.toString()
    }
}

@Composable
private fun DayCell(d: CalendarDay, selected: Boolean, today: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .let { if (today) it.border(1.dp, MaterialTheme.colorScheme.primary, shape) else it }
            .clickable(onClick = onClick)
            .padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(d.date.dayOfMonth.toString(), fontWeight = if (today) FontWeight.Bold else FontWeight.Normal, fontSize = 12.sp)
        if (d.inMinor + d.expectedInMinor > 0) Text(short(d.inMinor + d.expectedInMinor), color = IncomeGreen, fontSize = 9.sp, maxLines = 1)
        if (d.outMinor > 0) Text(short(d.outMinor), color = ExpenseRed, fontSize = 9.sp, maxLines = 1)
        if (d.expectedOutMinor > 0) Text(short(d.expectedOutMinor), color = Amber, fontSize = 9.sp, maxLines = 1)
    }
}
