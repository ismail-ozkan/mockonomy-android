package com.mockonomy.engine

import com.mockonomy.engine.model.Environment
import com.mockonomy.engine.model.EnvironmentJson
import com.mockonomy.engine.model.HeaderPair
import com.mockonomy.engine.template.TemplateContext
import kotlinx.serialization.Serializable
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import org.junit.jupiter.api.Assumptions.abort
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Runs the golden files under `spec/fixtures/` against the engine.
 *
 * Fixtures are read from files, not embedded in code: adding a fixture should mean
 * adding a test without changing code (spec/fixtures/README.md).
 *
 * Fixtures whose `meta.phase` is greater than [IMPLEMENTED_PHASE] are skipped and
 * the skips are reported — silent skipping creates an illusion of coverage.
 */
@OptIn(ExperimentalTime::class)
class SpecFixtureRunnerTest {

    private class FixedClock(private val instant: Instant) : Clock {
        override fun now(): Instant = instant
    }

    @TestFactory
    fun `spec fixtures`(): List<DynamicTest> {
        val fixtures = findFixtures()
        assertTrue(fixtures.isNotEmpty(), "no .json found under spec/fixtures")

        return fixtures.map { path ->
            val case = decode(path)
            val label = "${case.meta.name} (phase ${case.meta.phase}) — ${case.meta.description}"

            DynamicTest.dynamicTest(label) {
                if (case.meta.phase > IMPLEMENTED_PHASE) {
                    abort<Unit>(
                        "phase ${case.meta.phase} is not implemented yet " +
                            "(current: $IMPLEMENTED_PHASE)",
                    )
                }
                runCase(case)
            }
        }
    }

    @Test
    fun `skipped fixture count is reported`() {
        val cases = findFixtures().map { decode(it) }
        val skipped = cases.filter { it.meta.phase > IMPLEMENTED_PHASE }

        println(
            "spec fixtures: ${cases.size - skipped.size}/${cases.size} ran, " +
                "${skipped.size} skipped (phase > $IMPLEMENTED_PHASE)",
        )
        skipped.forEach { println("  skipped: ${it.meta.name} → phase ${it.meta.phase}") }
    }

    private fun runCase(case: FixtureCase) {
        // Each fixture is isolated: a fresh matcher and a fresh MockSession, so
        // sequential/request-number and state behavior do not leak between fixtures.
        // The steps of a multi-step fixture share the **same** session so state and
        // counters carry over from step to step (spec/fixtures/README.md).
        val matcher = RequestMatcher()
        val session = MockSession(case.environment.initialState)

        val templates = TemplateContext(
            clock = FixedClock(Instant.parse(case.now)),
            // Fixtures do not cover random helpers (spec/behavior/templating.md,
            // "Why random helpers have no golden file"). This generator only exists
            // to build the context.
            random = Random(0),
        )

        // Two formats: single-shot (request + expectedResponse) or multi-step (steps).
        // Exactly one of them must be set.
        val steps = when {
            case.steps.isNotEmpty() -> {
                require(case.request == null && case.expectedResponse == null) {
                    "${case.meta.name}: 'steps' cannot be combined with 'request'/'expectedResponse'"
                }
                case.steps
            }
            else -> listOf(
                FixtureStep(
                    request = requireNotNull(case.request) { "${case.meta.name}: missing 'request'" },
                    expectedResponse = requireNotNull(case.expectedResponse) {
                        "${case.meta.name}: missing 'expectedResponse'"
                    },
                    expectedPathParams = case.expectedPathParams,
                ),
            )
        }

        for ((index, step) in steps.withIndex()) {
            runStep(matcher, case.environment, session, templates, step, label = "${case.meta.name}[step ${index + 1}]")
        }
    }

    private fun runStep(
        matcher: RequestMatcher,
        environment: Environment,
        session: MockSession,
        templates: TemplateContext,
        step: FixtureStep,
        label: String,
    ) {
        val request = MockRequest(
            method = step.request.method,
            path = step.request.path,
            query = step.request.query,
            headers = step.request.headers,
            body = step.request.body,
        )
        val matchResult = matcher.match(environment, request, templates, session)
        val actual = matchResult?.response ?: matcher.serve(environment, request, templates, session)
        val expected = step.expectedResponse

        assertEquals(expected.statusCode, actual.statusCode, "$label statusCode")
        assertEquals(expected.body, actual.body, "$label body")
        assertEquals(expected.latencyMs, actual.latencyMs, "$label latencyMs")
        assertEquals(normalizeHeaders(expected.headers), normalizeHeaders(actual.headers), "$label headers")
        assertEquals(step.expectedPathParams, matchResult?.pathParams ?: emptyMap(), "$label pathParams")
        if (step.expectedStateAfter != null) {
            assertEquals(step.expectedStateAfter, session.state, "$label state")
        }
    }

    /** Headers are compared order-independently, with case-insensitive names. */
    private fun normalizeHeaders(headers: List<HeaderPair>): List<Pair<String, String>> =
        headers.map { it.key.lowercase() to it.value }.sortedWith(compareBy({ it.first }, { it.second }))

    private fun findFixtures(): List<Path> =
        FileSystem.SYSTEM.listRecursively(SPEC_DIR.toPath().resolve("fixtures"))
            .filter { it.name.endsWith(".json") }
            .toList()
            .sortedBy { it.toString() }

    private fun decode(path: Path): FixtureCase {
        val text = FileSystem.SYSTEM.read(path) { readUtf8() }
        return EnvironmentJson.format.decodeFromString(FixtureCase.serializer(), text)
    }

    private companion object {
        /**
         * The phase of engine behavior currently verified by fixtures. It is raised
         * once an engine capability is complete, and that phase's fixtures start
         * running.
         *
         * Phase 4 covers sequential responses with request counters and the state
         * machine. No fixture is **added** for behavior that is not implemented yet,
         * such as `RANDOM` or `COOKIE` (it would run here and rightly fail).
         */
        const val IMPLEMENTED_PHASE = 4

        val SPEC_DIR: String = requireNotNull(System.getProperty("mockonomy.spec.dir")) {
            "mockonomy.spec.dir is not set — see engine/build.gradle.kts"
        }
    }
}

@Serializable
private data class FixtureCase(
    val meta: FixtureMeta,
    val environment: Environment,
    // Single-shot format: one request → one response.
    val request: FixtureRequest? = null,
    val expectedResponse: FixtureResponse? = null,
    val expectedPathParams: Map<String, String> = emptyMap(),
    // Multi-step format: sequential requests on the same session; state and counters
    // carry over from step to step.
    val steps: List<FixtureStep> = emptyList(),
    /** The instant the template engine sees (`spec/behavior/templating.md`). */
    val now: String = "2026-03-14T15:09:26Z",
)

@Serializable
private data class FixtureStep(
    val request: FixtureRequest,
    val expectedResponse: FixtureResponse,
    val expectedPathParams: Map<String, String> = emptyMap(),
    /** The active state expected after this step is served (state machine). */
    val expectedStateAfter: String? = null,
)

@Serializable
private data class FixtureMeta(
    val name: String,
    val description: String,
    val phase: Int,
    val specRef: String = "",
)

@Serializable
private data class FixtureRequest(
    val method: String,
    val path: String,
    val query: Map<String, List<String>> = emptyMap(),
    val headers: Map<String, List<String>> = emptyMap(),
    val body: String? = null,
)

@Serializable
private data class FixtureResponse(
    val statusCode: Int,
    val headers: List<HeaderPair> = emptyList(),
    val body: String = "",
    val latencyMs: Int = 0,
)
