package com.mockonomy.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The body editor's highlighting palette.
 *
 * A separate set: these are *meaning* colors, not roles. Borrowing status colors like
 * `success`/`warning` for JSON would suggest a wrong meaning, such as reading a number as
 * "success".
 *
 * Template expressions get the most eye-catching color in the palette — the point is that
 * users instantly see that the `{{ ... }}` they typed is recognized.
 */
data class MockonomySyntaxColors(
    val key: Color,
    val string: Color,
    val number: Color,
    val literal: Color,
    val punctuation: Color,
    val template: Color,
    /** An expression the engine does not recognize: it will be served as is. */
    val unknownTemplate: Color,
)

val DarkMockonomySyntaxColors = MockonomySyntaxColors(
    key = Color(0xFF9EC8F5),
    string = Color(0xFF8FD9B6),
    number = Color(0xFFE8C48A),
    literal = Color(0xFFD2CEFD),
    punctuation = Color(0xFF9397AB),
    template = Color(0xFFB5ABFC),
    unknownTemplate = DarkColorTokens.Warning,
)

val LightMockonomySyntaxColors = MockonomySyntaxColors(
    key = Color(0xFF1F5C99),
    string = Color(0xFF2F7D5B),
    number = Color(0xFF8A5A12),
    literal = Color(0xFF5D5294),
    punctuation = Color(0xFF595D6C),
    template = Color(0xFF5D5294),
    unknownTemplate = LightColorTokens.Warning,
)

val LocalMockonomySyntaxColors = staticCompositionLocalOf<MockonomySyntaxColors> {
    error("LocalMockonomySyntaxColors not provided — used outside a MockonomyTheme { } scope")
}
