package com.mockonomy.ui.theme

import androidx.compose.ui.unit.dp

/** A 4px-based spacing scale, from the design handoff. */
object Spacing {
    val s1 = 4.dp
    val s2 = 8.dp
    val s3 = 12.dp
    val s4 = 16.dp
    val s5 = 24.dp
    val s6 = 32.dp
    val s7 = 48.dp
}

/** Layout constants — outside the spacing scale, but from the same source. */
object MockonomyMetrics {
    val listRowHeight = 57.dp // handoff: 56-58dp
    val minTouchTarget = 44.dp
    val sheetCornerRadius = 28.dp
    val cardCornerRadius = 12.dp
    val chipCornerRadius = 6.dp
    val statusChipCornerRadius = 5.dp
    val keyValueRowCornerRadius = 10.dp
    val keyValueRowMinHeight = 46.dp
    val keyValueKeyColumnWidth = 112.dp
}
