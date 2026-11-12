package com.myfinance.app.ui.charts

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myfinance.core.formatInr

/** Compact rupees for chart labels: ₹950, ₹12k, ₹1.2L, ₹1.1Cr. */
fun compactInr(minor: Long): String {
    val r = minor / 100
    val sign = if (r < 0) "−" else ""
    val a = kotlin.math.abs(r)
    return sign + when {
        a >= 1_00_00_000 -> "₹%.1fCr".format(a / 1_00_00_000.0)
        a >= 1_00_000 -> "₹%.1fL".format(a / 1_00_000.0)
        a >= 1_000 -> "₹%.0fk".format(a / 1_000.0)
        else -> "₹$a"
    }
}

data class Slice(val label: String, val value: Long, val color: Color)

/** A series for line and bar charts. */
data class ChartSeries(val label: String, val values: List<Long>, val color: Color)

/** Grows from 0 to 1 when [key] changes, so charts animate in. */
@Composable
private fun rememberGrow(key: Any?): Float {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(key) {
        anim.snapTo(0f)
        anim.animateTo(1f, tween(durationMillis = 700, easing = FastOutSlowInEasing))
    }
    return anim.value
}

/** A tinted card with a title, used for every chart. */
@Composable
fun ChartCard(
    title: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(width = 6.dp, height = 22.dp).background(accent, RoundedCornerShape(3.dp)))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    subtitle?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            content()
        }
    }
}

/** Donut chart with a total in the middle and a legend beside it. */
@Composable
fun DonutChart(
    slices: List<Slice>,
    centerTitle: String,
    modifier: Modifier = Modifier,
    centerValue: String = compactInr(slices.sumOf { it.value }),
    maxLegend: Int = 6,
) {
    val total = slices.sumOf { it.value.coerceAtLeast(0) }.coerceAtLeast(1)
    val grow = rememberGrow(slices)
    val track = MaterialTheme.colorScheme.surfaceVariant
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(140.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(140.dp)) {
                val stroke = size.minDimension * 0.17f
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                var start = -90f
                val gap = if (slices.size > 1) 2f else 0f
                slices.forEach { s ->
                    val sweep = 360f * s.value.coerceAtLeast(0) / total * grow
                    if (sweep > gap) {
                        drawArc(
                            s.color, start + gap / 2, sweep - gap, false, Offset(inset, inset), arcSize,
                            style = Stroke(stroke, cap = StrokeCap.Butt),
                        )
                    }
                    start += sweep
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(centerTitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(centerValue, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            slices.take(maxLegend).forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(10.dp).background(s.color, CircleShape))
                    Text(
                        s.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${compactInr(s.value)} · ${s.value * 100 / total}%",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/** Keeps the [keep] biggest slices and folds the rest into "Other". */
fun topSlices(slices: List<Slice>, keep: Int = 6, otherColor: Color = Color(0xFF9CA3AF)): List<Slice> {
    val sorted = slices.filter { it.value > 0 }.sortedByDescending { it.value }
    if (sorted.size <= keep) return sorted
    val rest = sorted.drop(keep - 1).sumOf { it.value }
    return sorted.take(keep - 1) + Slice("Other", rest, otherColor)
}

/** Smooth line chart with a soft gradient fill under each line. */
@Composable
fun LineChart(
    series: List<ChartSeries>,
    labels: List<String>,
    modifier: Modifier = Modifier,
    height: Int = 160,
    fill: Boolean = true,
    showZero: Boolean = false,
) {
    val all = series.flatMap { it.values }
    val max = (all.maxOrNull() ?: 0L).coerceAtLeast(1L)
    val min = (all.minOrNull() ?: 0L).coerceAtMost(0L)
    val range = (max - min).coerceAtLeast(1L).toFloat()
    val grow = rememberGrow(series)
    val grid = MaterialTheme.colorScheme.outlineVariant
    Column(modifier) {
        Row {
            Text(compactInr(max), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Canvas(Modifier.fillMaxWidth().height(height.dp).padding(vertical = 6.dp)) {
            fun y(v: Long) = size.height - size.height * ((v - min) / range) * grow
            for (i in 0..3) {
                val gy = size.height * i / 3f
                drawLine(grid, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1f)
            }
            if (showZero && min < 0) {
                drawLine(grid, Offset(0f, y(0L)), Offset(size.width, y(0L)), strokeWidth = 3f)
            }
            series.forEach { s ->
                val n = s.values.size
                if (n == 0) return@forEach
                // Points sit in the middle of each label's slot so they line up with the axis labels.
                val slot = size.width / labels.size.coerceAtLeast(n)
                val pts = s.values.mapIndexed { i, v -> Offset((i + 0.5f) * slot, y(v)) }
                val line = Path().apply {
                    moveTo(pts[0].x, pts[0].y)
                    for (i in 1 until pts.size) {
                        val p0 = pts[i - 1]
                        val p1 = pts[i]
                        val cx = (p0.x + p1.x) / 2
                        cubicTo(cx, p0.y, cx, p1.y, p1.x, p1.y)
                    }
                }
                if (fill && n > 1) {
                    val area = Path().apply {
                        addPath(line)
                        lineTo(pts.last().x, size.height)
                        lineTo(pts.first().x, size.height)
                        close()
                    }
                    drawPath(area, Brush.verticalGradient(listOf(s.color.copy(alpha = 0.35f), s.color.copy(alpha = 0.02f))))
                }
                drawPath(line, s.color, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
                pts.forEach { p ->
                    drawCircle(Color.White, radius = 4.dp.toPx(), center = p)
                    drawCircle(s.color, radius = 2.5.dp.toPx(), center = p)
                }
            }
        }
        AxisLabels(labels)
    }
}

/** Bars with rounded tops; several series are drawn side by side per label. */
@Composable
fun GroupedBarChart(
    series: List<ChartSeries>,
    labels: List<String>,
    modifier: Modifier = Modifier,
    height: Int = 160,
    showMax: Boolean = true,
) {
    val max = (series.flatMap { it.values }.maxOrNull() ?: 0L).coerceAtLeast(1L)
    val grow = rememberGrow(series)
    val grid = MaterialTheme.colorScheme.outlineVariant
    Column(modifier) {
        if (showMax) {
            Text(compactInr(max), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Canvas(Modifier.fillMaxWidth().height(height.dp).padding(top = 6.dp)) {
            for (i in 0..3) {
                val gy = size.height * i / 3f
                drawLine(grid, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1f)
            }
            val n = labels.size.coerceAtLeast(series.maxOfOrNull { it.values.size } ?: 1).coerceAtLeast(1)
            val slot = size.width / n
            val k = series.size.coerceAtLeast(1)
            val barW = (slot * 0.72f / k).coerceAtMost(28.dp.toPx())
            val groupW = barW * k
            val r = CornerRadius(barW / 2.5f, barW / 2.5f)
            for (i in 0 until n) {
                val x0 = i * slot + (slot - groupW) / 2
                series.forEachIndexed { j, s ->
                    val v = s.values.getOrNull(i)?.coerceAtLeast(0L) ?: 0L
                    val h = size.height * v / max * grow
                    if (h > 0f) {
                        drawRoundRect(
                            brush = Brush.verticalGradient(listOf(s.color, s.color.copy(alpha = 0.6f)), startY = size.height - h, endY = size.height),
                            topLeft = Offset(x0 + j * barW + 1f, size.height - h),
                            size = Size(barW - 2f, h),
                            cornerRadius = r,
                        )
                    }
                }
            }
        }
        AxisLabels(labels)
    }
}

/** Horizontal bars, one per row, for comparing a handful of items (people, merchants). */
@Composable
fun HorizontalBars(
    items: List<Slice>,
    modifier: Modifier = Modifier,
    valueText: (Slice) -> String = { formatInr(it.value) },
) {
    val max = (items.maxOfOrNull { it.value } ?: 0L).coerceAtLeast(1L)
    val grow = rememberGrow(items)
    val track = MaterialTheme.colorScheme.surfaceVariant
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEach { s ->
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(valueText(s), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
                Canvas(Modifier.fillMaxWidth().height(12.dp).padding(top = 2.dp)) {
                    val r = CornerRadius(size.height / 2, size.height / 2)
                    drawRoundRect(track, cornerRadius = r)
                    val w = size.width * s.value.coerceAtLeast(0) / max * grow
                    if (w > 0f) {
                        drawRoundRect(
                            Brush.horizontalGradient(listOf(s.color.copy(alpha = 0.7f), s.color), endX = w),
                            size = Size(w, size.height),
                            cornerRadius = r,
                        )
                    }
                }
            }
        }
    }
}

/** A thin stacked bar showing shares of a whole. */
@Composable
fun StackedBar(slices: List<Slice>, modifier: Modifier = Modifier) {
    val total = slices.sumOf { it.value.coerceAtLeast(0) }.coerceAtLeast(1)
    val grow = rememberGrow(slices)
    Canvas(modifier.fillMaxWidth().height(14.dp)) {
        var x = 0f
        slices.forEach { s ->
            val w = size.width * s.value.coerceAtLeast(0) / total * grow
            drawRect(s.color, topLeft = Offset(x, 0f), size = Size(w, size.height))
            x += w
        }
    }
}

@Composable
fun ChartLegend(items: List<Pair<String, Color>>, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items.forEach { (label, color) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.size(10.dp).background(color, CircleShape))
                Text(label, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun AxisLabels(labels: List<String>) {
    Row(Modifier.fillMaxWidth()) {
        labels.forEach {
            Text(
                it,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
