package com.myfinance.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified

/**
 * Chips and buttons that wrap onto the next line when they don't fit, instead of running off the side of
 * the screen (large font sizes and small phones). Used everywhere a row of choices would otherwise scroll sideways.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WrapRow(
    modifier: Modifier = Modifier,
    spacing: Dp = 8.dp,
    content: @Composable () -> Unit,
) {
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) { content() }
}

/** One line of text that shrinks its font to fit the width it has, down to 60%, instead of wrapping mid-word. */
@Composable
fun FitText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
) {
    var scale by remember(text) { mutableFloatStateOf(1f) }
    var ready by remember(text) { mutableFloatStateOf(0f) }
    Text(
        text,
        modifier.drawWithContent { if (ready > 0f) drawContent() },
        color = color,
        fontWeight = fontWeight,
        textAlign = textAlign,
        maxLines = 1,
        softWrap = false,
        style = style.copy(
            fontSize = if (style.fontSize.isSpecified) style.fontSize * scale else style.fontSize,
            lineHeight = if (style.lineHeight.isSpecified) style.lineHeight * scale else style.lineHeight,
        ),
        onTextLayout = { r ->
            if (r.didOverflowWidth && scale > 0.6f) scale = (scale - 0.1f).coerceAtLeast(0.6f) else ready = 1f
        },
    )
}
