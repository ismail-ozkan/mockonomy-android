package com.mockonomy.ui.screens.response

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.AnnotatedString.Range
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.mockonomy.ui.theme.MockonomySyntaxColors
import kotlinx.coroutines.delay

/**
 * The editor's highlighting state.
 *
 * Rescanning the whole text on every keystroke causes visible freezes with large bodies; so
 * the scan is delayed by [DEBOUNCE_MS].
 *
 * The delay creates a hazard: tokens belong to the *old* text, so their ranges may fall
 * outside the new text. The fix is keeping tokens together with the text they were computed
 * for; as soon as the text changes, highlighting turns itself off until the scan catches
 * up. A color in the wrong place is worse than a brief lack of color.
 */
class BodyHighlightState(
    val tokens: List<Token>,
    private val tokenizedText: String,
    /** The body exceeds the threshold: highlighting is off and the user is told. */
    val disabledForSize: Boolean,
) {
    fun matches(text: String): Boolean = text == tokenizedText

    val unknownTemplates: List<Token> get() = tokens.filter { it.type == TokenType.UNKNOWN_TEMPLATE }
}

@Composable
fun rememberBodyHighlight(body: String): State<BodyHighlightState> {
    val state = remember { mutableStateOf(BodyHighlightState(emptyList(), "", disabledForSize = false)) }

    LaunchedEffect(body) {
        if (body.length > HIGHLIGHT_LIMIT) {
            state.value = BodyHighlightState(emptyList(), body, disabledForSize = true)
            return@LaunchedEffect
        }
        delay(DEBOUNCE_MS)
        state.value = BodyHighlightState(JsonHighlighter.tokenize(body), body, disabledForSize = false)
    }

    return state
}

/**
 * Only colors the text without changing it, so cursor offset mapping is one to one. A
 * transformation that changes the text (e.g. one that adds indentation) would shift the
 * cursor and break editing.
 */
fun syntaxTransformation(
    highlight: BodyHighlightState,
    colors: MockonomySyntaxColors,
): VisualTransformation = VisualTransformation { text ->
    val usable = highlight.matches(text.text) && !highlight.disabledForSize
    val spans = if (!usable) {
        emptyList()
    } else {
        highlight.tokens.mapNotNull { token ->
            val end = token.end.coerceAtMost(text.length)
            if (token.start >= end) {
                null
            } else {
                Range(SpanStyle(color = colors.colorOf(token.type)), token.start, end)
            }
        }
    }
    TransformedText(AnnotatedString(text.text, spans), OffsetMapping.Identity)
}

private fun MockonomySyntaxColors.colorOf(type: TokenType) = when (type) {
    TokenType.KEY -> key
    TokenType.STRING -> string
    TokenType.NUMBER -> number
    TokenType.LITERAL -> literal
    TokenType.PUNCTUATION -> punctuation
    TokenType.TEMPLATE -> template
    TokenType.UNKNOWN_TEMPLATE -> unknownTemplate
}

/** 100 KB — highlighting turns off above this size. */
const val HIGHLIGHT_LIMIT: Int = 100 * 1024

private const val DEBOUNCE_MS = 120L
