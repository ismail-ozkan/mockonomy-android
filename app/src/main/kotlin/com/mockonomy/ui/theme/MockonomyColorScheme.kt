package com.mockonomy.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The color role set that is the single source of truth. It was not forced into M3's
 * [androidx.compose.material3.ColorScheme] — `success`/`warning` do not exist in M3 at all,
 * and forcing roles like `surfaceSunken`/`onSurfaceFaint`/`primaryText` onto M3's tonal
 * system derived from a seed color would be misleading.
 *
 * New code should use `MockonomyTheme.colors`, not `MaterialTheme.colorScheme`.
 */
data class MockonomyColorScheme(
    val background: Color,
    val surface: Color,
    val surfaceSunken: Color,
    val surfaceVariant: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val onSurfaceFaint: Color,
    val outline: Color,
    val outlineSubtle: Color,
    val primary: Color,
    val primaryText: Color,
    val primaryContainer: Color,
    val primaryOutline: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
)

val DarkMockonomyColorScheme = MockonomyColorScheme(
    background = DarkColorTokens.Background,
    surface = DarkColorTokens.Surface,
    surfaceSunken = DarkColorTokens.SurfaceSunken,
    surfaceVariant = DarkColorTokens.SurfaceVariant,
    onSurface = DarkColorTokens.OnSurface,
    onSurfaceVariant = DarkColorTokens.OnSurfaceVariant,
    onSurfaceFaint = DarkColorTokens.OnSurfaceFaint,
    outline = DarkColorTokens.Outline,
    outlineSubtle = DarkColorTokens.OutlineSubtle,
    primary = DarkColorTokens.Primary,
    primaryText = DarkColorTokens.PrimaryText,
    primaryContainer = DarkColorTokens.PrimaryContainer,
    primaryOutline = DarkColorTokens.PrimaryOutline,
    success = DarkColorTokens.Success,
    warning = DarkColorTokens.Warning,
    error = DarkColorTokens.Error,
)

val LightMockonomyColorScheme = MockonomyColorScheme(
    background = LightColorTokens.Background,
    surface = LightColorTokens.Surface,
    surfaceSunken = LightColorTokens.SurfaceSunken,
    surfaceVariant = LightColorTokens.SurfaceVariant,
    onSurface = LightColorTokens.OnSurface,
    onSurfaceVariant = LightColorTokens.OnSurfaceVariant,
    onSurfaceFaint = LightColorTokens.OnSurfaceFaint,
    outline = LightColorTokens.Outline,
    outlineSubtle = LightColorTokens.OutlineSubtle,
    primary = LightColorTokens.Primary,
    primaryText = LightColorTokens.PrimaryText,
    primaryContainer = LightColorTokens.PrimaryContainer,
    primaryOutline = LightColorTokens.PrimaryOutline,
    success = LightColorTokens.Success,
    warning = LightColorTokens.Warning,
    error = LightColorTokens.Error,
)

val LocalMockonomyColors = staticCompositionLocalOf<MockonomyColorScheme> {
    error("LocalMockonomyColors not provided — used outside a MockonomyTheme { } scope")
}
