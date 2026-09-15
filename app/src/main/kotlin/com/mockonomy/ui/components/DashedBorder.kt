package com.mockonomy.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Compose has no built-in dashed border — it is produced with `drawBehind` +
 * `dashPathEffect`. The design uses it in three places: the empty-state glyph frame, the
 * empty body box and "coming soon" chips.
 */
internal fun Modifier.dashedBorder(
    color: Color,
    cornerRadius: Dp,
    strokeWidth: Dp = 1.dp,
    dashLength: Dp = 4.dp,
    gapLength: Dp = 4.dp,
): Modifier = drawBehind {
    val stroke = Stroke(
        width = strokeWidth.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashLength.toPx(), gapLength.toPx()), 0f),
    )
    drawRoundRect(
        color = color,
        style = stroke,
        cornerRadius = CornerRadius(cornerRadius.toPx()),
    )
}
