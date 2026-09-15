package com.mockonomy.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.mockonomy.R

// Inter and JetBrains Mono are shipped as embedded variable fonts from google/fonts
// (see THIRD_PARTY_LICENSES.md). NOT downloadable fonts — this is an offline-first tool
// and must not depend on the internet on first launch.
val InterFontFamily = FontFamily(
    Font(
        R.font.inter,
        weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400)),
    ),
    Font(
        R.font.inter,
        weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500)),
    ),
)

val JetBrainsMonoFontFamily = FontFamily(
    Font(
        R.font.jetbrains_mono,
        weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400)),
    ),
    Font(
        R.font.jetbrains_mono,
        weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500)),
    ),
    Font(
        R.font.jetbrains_mono,
        weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700)),
    ),
)

/**
 * Two independent type scales — normal (Inter) and technical (JetBrains Mono).
 *
 * They were not forced into M3's standard [androidx.compose.material3.Typography] slots:
 * the mono scale has no counterpart in M3 at all, and partly fitting the normal side
 * would create two different access patterns.
 */
data class MockonomyTypography(
    val display: TextStyle,
    val titleLarge: TextStyle,
    val title: TextStyle,
    val body: TextStyle,
    val label: TextStyle,
    val caption: TextStyle,
    val overline: TextStyle,
    val monoDisplay: TextStyle,
    val monoPath: TextStyle,
    val monoBody: TextStyle,
    val monoChip: TextStyle,
    val monoMeta: TextStyle,
)

private fun normalStyle(sizeSp: Float, weight: FontWeight, letterSpacingEm: Float = 0f) = TextStyle(
    fontFamily = InterFontFamily,
    fontWeight = weight,
    fontSize = sizeSp.sp,
    lineHeight = (sizeSp * 1.25f).sp,
    letterSpacing = letterSpacingEm.em,
)

private fun monoStyle(sizeSp: Float, weight: FontWeight) = TextStyle(
    fontFamily = JetBrainsMonoFontFamily,
    fontWeight = weight,
    fontSize = sizeSp.sp,
    lineHeight = (sizeSp * 1.35f).sp,
)

/** The exact values from the design handoff — see its Specs section. */
val DefaultMockonomyTypography = MockonomyTypography(
    display = normalStyle(28f, FontWeight.Medium, letterSpacingEm = -0.02f),
    titleLarge = normalStyle(20f, FontWeight.Medium, letterSpacingEm = -0.02f),
    title = normalStyle(16.5f, FontWeight.Medium),
    body = normalStyle(13.5f, FontWeight.Normal),
    label = normalStyle(12f, FontWeight.Medium),
    caption = normalStyle(11f, FontWeight.Normal),
    overline = normalStyle(10f, FontWeight.Medium, letterSpacingEm = 0.14f),
    monoDisplay = monoStyle(18f, FontWeight.Medium),
    monoPath = monoStyle(13f, FontWeight.Medium),
    monoBody = monoStyle(11.5f, FontWeight.Normal),
    monoChip = monoStyle(10.5f, FontWeight.Bold),
    monoMeta = monoStyle(10f, FontWeight.Normal),
)
