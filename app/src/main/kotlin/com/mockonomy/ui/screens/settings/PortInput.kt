package com.mockonomy.ui.screens.settings

import com.mockonomy.engine.model.Environment

/** How many digits the Port field accepts; [Environment.MAX_PORT] is five digits long. */
const val PORT_INPUT_MAX_DIGITS: Int = 5

/**
 * Validates the raw text of the Port field.
 *
 * Returns the message to show under the field, or `null` when the text is a port a
 * server can bind. Empty text is an error: a port is required, and there is no sensible
 * default to fall back to silently.
 *
 * Port `0` ("let the operating system choose") is valid for the server itself but is
 * rejected here — it is a test affordance, not something to type into the field.
 *
 * Pure on purpose: the Settings tab and the Start button in the app bar must agree on a
 * single answer.
 */
fun portInputError(text: String): String? {
    val port = text.toIntOrNull()
    return if (port != null && port in Environment.MIN_PORT..Environment.MAX_PORT) {
        null
    } else {
        "Must be between ${Environment.MIN_PORT} and ${Environment.MAX_PORT}"
    }
}
