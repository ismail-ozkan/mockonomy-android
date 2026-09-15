package com.mockonomy.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

val LocalMockonomyTypography = staticCompositionLocalOf { DefaultMockonomyTypography }

/**
 * The app's only theme container.
 *
 * There is deliberately NO `dynamicColor` parameter — dynamic color is intentionally not
 * supported (brand consistency comes first). If a flag were exposed, someone could turn it
 * on later with a single line; this signature makes that impossible.
 */
@Composable
fun MockonomyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkMockonomyColorScheme else LightMockonomyColorScheme

    // A best-effort compatibility bridge so raw M3 components (OutlinedTextField, Switch,
    // ripple/elevation behavior) look reasonable without breaking. NOT a source — new code
    // should use MockonomyTheme.colors, not MaterialTheme.colorScheme.
    val materialColorScheme = if (darkTheme) {
        darkColorScheme(
            primary = colors.primary,
            onPrimary = colors.background,
            primaryContainer = colors.primaryContainer,
            onPrimaryContainer = colors.primaryText,
            background = colors.background,
            onBackground = colors.onSurface,
            surface = colors.surface,
            onSurface = colors.onSurface,
            surfaceVariant = colors.surfaceVariant,
            onSurfaceVariant = colors.onSurfaceVariant,
            outline = colors.outline,
            error = colors.error,
            onError = colors.background,
        )
    } else {
        lightColorScheme(
            primary = colors.primary,
            onPrimary = colors.background,
            primaryContainer = colors.primaryContainer,
            onPrimaryContainer = colors.primaryText,
            background = colors.background,
            onBackground = colors.onSurface,
            surface = colors.surface,
            onSurface = colors.onSurface,
            surfaceVariant = colors.surfaceVariant,
            onSurfaceVariant = colors.onSurfaceVariant,
            outline = colors.outline,
            error = colors.error,
            onError = colors.background,
        )
    }

    CompositionLocalProvider(
        LocalMockonomyColors provides colors,
        LocalMockonomyTypography provides DefaultMockonomyTypography,
        LocalMockonomySyntaxColors provides
            if (darkTheme) DarkMockonomySyntaxColors else LightMockonomySyntaxColors,
    ) {
        MaterialTheme(colorScheme = materialColorScheme) {
            content()
        }
    }
}

/** Mimics the `MaterialTheme.colorScheme` / `.typography` access pattern. */
object MockonomyTheme {
    val colors: MockonomyColorScheme
        @Composable get() = LocalMockonomyColors.current

    val typography: MockonomyTypography
        @Composable get() = LocalMockonomyTypography.current

    /** The body editor's highlighting palette. */
    val syntax: MockonomySyntaxColors
        @Composable get() = LocalMockonomySyntaxColors.current
}
