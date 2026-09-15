package com.mockonomy.ui.theme

import androidx.compose.ui.graphics.Color
import com.mockonomy.engine.model.HttpMethod

// Exact values from the design handoff.
//
// `PrimaryOutline` is NOT in the handoff's token table; the prototype consistently uses it
// as the border of accent-outlined containers (serving strip, first-run card, response
// card). `primary` is the button border, `primaryOutline` the container border — two
// separate roles.

object DarkColorTokens {
    val Background = Color(0xFF161826)
    val Surface = Color(0xFF232532)
    val SurfaceSunken = Color(0xFF12131D)
    val SurfaceVariant = Color(0xFF1C1D2B)
    val OnSurface = Color(0xFFE9E9ED)
    val OnSurfaceVariant = Color(0xFF9397AB)
    val OnSurfaceFaint = Color(0xFF75798C)
    val Outline = Color(0xFF3F424D)
    val OutlineSubtle = Color(0x1AE9E9ED) // rgba(233,233,237,.10)
    val Primary = Color(0xFF9184D9)
    val PrimaryText = Color(0xFFB5ABFC)
    val PrimaryContainer = Color(0xFF2B2741)
    val PrimaryOutline = Color(0xFF423A6A)
    val Success = Color(0xFF5CC196)
    val Warning = Color(0xFFD9A94F)
    val Error = Color(0xFFE07A72)
}

object LightColorTokens {
    val Background = Color(0xFFECEEF8)
    val Surface = Color(0xFFF7F7FD)
    val SurfaceSunken = Color(0xFFE2E5F2)
    val SurfaceVariant = Color(0xFFF3F5FE)
    val OnSurface = Color(0xFF292B31)
    val OnSurfaceVariant = Color(0xFF595D6C)
    val OnSurfaceFaint = Color(0xFF75798C)
    val Outline = Color(0xFFCFD3E5)
    val OutlineSubtle = Color(0x17292B31) // rgba(41,43,49,.09)
    val Primary = Color(0xFF796CBF)
    val PrimaryText = Color(0xFF5D5294)
    val PrimaryContainer = Color(0xFFECE9FB)
    val PrimaryOutline = Color(0xFFB5ABFC)
    val Success = Color(0xFF2F7D5B)
    val Warning = Color(0xFF8A6320)
    val Error = Color(0xFFA33B34)
}

/** Method chip colors are theme-independent — a route's color does not change between dark and light. */
data class MethodChipColorPair(val background: Color, val foreground: Color)

private val MethodChipNeutral = MethodChipColorPair(Color(0xFF3F424D), Color(0xFFCFD3E5))

fun HttpMethod.chipColors(): MethodChipColorPair = when (this) {
    HttpMethod.GET -> MethodChipColorPair(Color(0xFF24344D), Color(0xFF9EC8F5))
    HttpMethod.POST -> MethodChipColorPair(Color(0xFF1E3A30), Color(0xFF8FD9B6))
    HttpMethod.PUT -> MethodChipColorPair(Color(0xFF40331C), Color(0xFFE8C48A))
    HttpMethod.PATCH -> MethodChipColorPair(Color(0xFF423A6A), Color(0xFFD2CEFD))
    HttpMethod.DELETE -> MethodChipColorPair(Color(0xFF4A2626), Color(0xFFF2A8A2))
    HttpMethod.HEAD, HttpMethod.OPTIONS -> MethodChipNeutral
}

/** Status code chip colors, by the first digit (the hundreds) — theme-independent. */
enum class StatusClass { Success, Info, Warning, Error, Neutral }

fun statusClassOf(statusCode: Int): StatusClass = when (statusCode / 100) {
    2 -> StatusClass.Success
    3 -> StatusClass.Info
    4 -> StatusClass.Warning
    5 -> StatusClass.Error
    // <100 or >=600: undefined in the spec — a neutral fallback.
    else -> StatusClass.Neutral
}

private val StatusChipNeutral = MethodChipColorPair(Color(0xFF3F424D), Color(0xFFCFD3E5))

fun StatusClass.chipColors(): MethodChipColorPair = when (this) {
    StatusClass.Success -> MethodChipColorPair(Color(0xFF1E3A30), Color(0xFF8FD9B6))
    StatusClass.Info -> MethodChipColorPair(Color(0xFF24344D), Color(0xFF9EC8F5))
    StatusClass.Warning -> MethodChipColorPair(Color(0xFF40331C), Color(0xFFE8C48A))
    StatusClass.Error -> MethodChipColorPair(Color(0xFF4A2626), Color(0xFFF2A8A2))
    StatusClass.Neutral -> StatusChipNeutral
}

fun Int.statusChipColors(): MethodChipColorPair = statusClassOf(this).chipColors()
