package com.myfinance.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myfinance.core.DefaultCategories
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalTime

/*
 * Shared building blocks for the app's look: a centred serif title bar with round icon buttons, a round purple +
 * button, a pill-shaped bottom bar, pastel category circles, label/value detail rows with pill chips, a grey
 * segmented switch and a rounded search bar. Every screen uses these so the app looks the same everywhere.
 */

/** Space between cards and around the page. */
object Spacing {
    val page = 16.dp
    val gap = 12.dp
    val cardPadding = 16.dp
}

/**
 * The title bar used on every screen: title centred in the serif face, the back arrow and actions inside small
 * white circles. Same parameters as Material's TopAppBar so screens can switch with a rename.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    CenterAlignedTopAppBar(
        modifier = modifier,
        title = { ProvideTextStyle(MaterialTheme.typography.headlineSmall.copy(fontSize = 21.sp, lineHeight = 26.sp)) { title() } },
        navigationIcon = { Box(Modifier.padding(start = 8.dp)) { RoundIconHolder { navigationIcon() } } },
        actions = { Row(Modifier.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) { RoundIconHolder { Row(content = actions) } } },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.background,
        ),
    )
}

/** A white pill behind icon buttons (back arrow, refresh, menu), as in the title bar. */
@Composable
fun RoundIconHolder(content: @Composable () -> Unit) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

/** The round purple + button. */
@Composable
fun AppFab(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        shape = CircleShape,
        containerColor = Color.Transparent,
        contentColor = Color.White,
        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp, pressedElevation = 6.dp),
    ) {
        Box(
            Modifier.size(56.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFF9D86FA), Color(0xFF7A5AF8)))),
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}

data class NavTab(val route: String, val label: String, val icon: ImageVector)

/** The bottom bar: a white rounded bar floating over the page, with the open tab on a grey pill. */
@Composable
fun PillNavBar(tabs: List<NavTab>, current: String?, onSelect: (NavTab) -> Unit) {
    Box(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .navigationBarsPadding(),
    ) {
        Surface(
            shape = RoundedCornerShape(30.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            shadowElevation = 2.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                tabs.forEach { tab ->
                    val selected = tab.route == current
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(24.dp))
                            .background(if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                            .clickable { onSelect(tab) }
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val tint = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                        Icon(tab.icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                        com.myfinance.app.ui.FitText(
                            tab.label,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = tint,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}

/** A small emoji for each built-in category; others get their first letter. */
fun categoryEmoji(category: String?): String? = when (category) {
    DefaultCategories.FOOD -> "🍽️"
    DefaultCategories.GROCERIES -> "🛒"
    DefaultCategories.SHOPPING -> "🛍️"
    DefaultCategories.TRANSPORT -> "🚌"
    DefaultCategories.FUEL -> "⛽"
    DefaultCategories.BILLS -> "💡"
    DefaultCategories.ENTERTAINMENT -> "🎬"
    DefaultCategories.HEALTH -> "💊"
    DefaultCategories.TRAVEL -> "✈️"
    DefaultCategories.RENT -> "🏠"
    DefaultCategories.EDUCATION -> "📚"
    DefaultCategories.INVESTMENTS -> "📈"
    DefaultCategories.EMI -> "🏦"
    DefaultCategories.CASH -> "💵"
    DefaultCategories.TRANSFERS -> "🔁"
    DefaultCategories.SALARY -> "💼"
    DefaultCategories.INCOME -> "💰"
    DefaultCategories.OTHER -> "🧾"
    else -> null
}

/** A pastel circle with the category's emoji (or first letter), tinted with the category's colour. */
@Composable
fun CategoryBadge(category: String?, color: Color, size: Dp = 44.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(color.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        val emoji = categoryEmoji(category)
        if (emoji != null) Text(emoji, fontSize = (size.value * 0.42f).sp)
        else Text(
            (category ?: "?").take(1).uppercase(),
            color = color, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.4f).sp,
        )
    }
}

/** A rounded label, like the PAID / category / tag chips on the transaction screen. */
@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceVariant,
    content: Color = MaterialTheme.colorScheme.onSurface,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(container)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        leading?.invoke()
        Text(text, color = content, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Pastel pill colours for status chips. */
object PillColors {
    val green = Color(0xFFD9F2DF) to Color(0xFF1F6B35)
    val red = Color(0xFFFBE0DF) to Color(0xFF8A2226)
    val blue = Color(0xFFDCE8FB) to Color(0xFF1D4F91)
    val purple = Color(0xFFE9E4FE) to Color(0xFF4B33B5)
    val amber = Color(0xFFFCEFD6) to Color(0xFF7A4B07)
}

/** A white rounded card holding label/value rows separated by thin lines. */
@Composable
fun DetailCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), content = content)
    }
}

/** A label on the left and its value (usually pills) on the right. */
@Composable
fun DetailRow(label: String, divider: Boolean = true, value: @Composable RowScope.() -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 12.dp))
            Row(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
                content = value,
            )
        }
        if (divider) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** A grey track with the chosen option on a white pill (Details / Activity, Expense / Income). */
@Composable
fun SegmentedTabs(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (on) MaterialTheme.colorScheme.surfaceContainerLowest else Color.Transparent)
                    .clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, style = MaterialTheme.typography.labelLarge,
                    color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center, maxLines = 1,
                )
            }
        }
    }
}

/** A rounded grey search bar that opens search when tapped. */
@Composable
fun SearchPill(placeholder: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** A section heading above a group of cards ("Recent activity"). */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** Initials in a soft circle, for the person using this phone. */
@Composable
fun InitialsAvatar(name: String, size: Dp = 40.dp) {
    val initials = name.split(' ', '.').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }.ifEmpty { "Me" }
    Box(
        Modifier.size(size).clip(CircleShape).background(PillColors.amber.first),
        contentAlignment = Alignment.Center,
    ) { Text(initials, style = MaterialTheme.typography.labelLarge, color = PillColors.amber.second) }
}

fun greeting(now: LocalTime = LocalTime.now()): String = when (now.hour) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    else -> "Good evening"
}

/** One-off requests between screens, e.g. Home's search bar asking Transactions to open its search box. */
object UiSignals {
    val openSearch = MutableStateFlow(false)
}

/** A tab's big serif title, centred, with an optional line under it. */
@Composable
fun ScreenTitle(text: String, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) }
    }
}

/** A white rounded row that opens a screen: pastel circle with an emoji, title and hint, and a value on the right. */
@Composable
fun NavTile(emoji: String, tint: Color, title: String, hint: String, value: String?, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(tint.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                Text(emoji, fontSize = 18.sp)
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            value?.let { Text(it, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1) }
        }
    }
}
