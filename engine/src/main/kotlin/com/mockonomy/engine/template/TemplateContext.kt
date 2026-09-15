package com.mockonomy.engine.template

import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * The template engine's only contact with the changing world.
 *
 * `{{uuid}}`, `{{now}}` and `{{random.int}}` are untestable by nature — *unless you
 * design for it from the start*. The engine does not produce time or randomness
 * itself, so tests can pass a fixed instant and a seeded generator; this lets the
 * output be pinned against a golden file.
 *
 * Contract: `spec/behavior/templating.md`, "Determinism".
 */
@OptIn(ExperimentalTime::class)
class TemplateContext(
    val clock: Clock,
    val random: Random,
) {
    companion object {
        /**
         * Production configuration. The experimental [Clock] opt-in is consumed here
         * so callers (`:server`, `RequestMatcher`) do not have to opt in themselves.
         */
        fun system(): TemplateContext = TemplateContext(Clock.System, Random.Default)
    }
}
