package com.mockonomy.engine.model

import kotlinx.serialization.json.jsonObject
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves that the model does not drift from the `spec/` contract.
 *
 * Fixtures are read from files, not embedded in code — adding a fixture should mean
 * adding a test without changing code (spec/README.md).
 *
 * This test only decodes the `environment` block of each fixture. The full fixtures
 * (request → expected response) are run by `SpecFixtureRunnerTest`.
 */
class SpecFixtureParsingTest {

    private val specDir = requireNotNull(System.getProperty("mockonomy.spec.dir")) {
        "mockonomy.spec.dir is not set — see the test configuration in engine/build.gradle.kts"
    }

    @Test
    fun `environment blocks in all fixture files decode into the model`() {
        val fixturesDir = specDir.toPath().resolve("fixtures")
        val fixtures = FileSystem.SYSTEM.listRecursively(fixturesDir)
            .filter { it.name.endsWith(".json") }
            .toList()
            .sortedBy { it.toString() }

        assertTrue(fixtures.isNotEmpty(), "no .json found under spec/fixtures: $fixturesDir")

        for (path in fixtures) {
            val text = FileSystem.SYSTEM.read(path) { readUtf8() }
            val root = EnvironmentJson.format.parseToJsonElement(text).jsonObject

            val environmentElement = requireNotNull(root["environment"]) {
                "${path.name}: fixture has no 'environment' field"
            }
            val decoded = EnvironmentJson.format
                .decodeFromJsonElement(Environment.serializer(), environmentElement)
            // Older fixtures (v1) are migrated just like real device files; after
            // migration all of them must be on the current schema.
            val environment = SchemaMigration.migrate(decoded)

            assertEquals(
                Environment.CURRENT_SCHEMA_VERSION,
                environment.schemaVersion,
                "${path.name}: unexpected schemaVersion after migration",
            )
            assertTrue(environment.id.isNotBlank(), "${path.name}: environment.id is blank")
        }
    }

    @Test
    fun `fixture meta blocks carry the required fields`() {
        val fixturesDir = specDir.toPath().resolve("fixtures")
        val fixtures = FileSystem.SYSTEM.listRecursively(fixturesDir)
            .filter { it.name.endsWith(".json") }
            .toList()

        for (path in fixtures) {
            val text = FileSystem.SYSTEM.read(path) { readUtf8() }
            val root = EnvironmentJson.format.parseToJsonElement(text).jsonObject
            val meta = requireNotNull(root["meta"]) { "${path.name}: missing 'meta' field" }.jsonObject

            val name = requireNotNull(meta["name"]) { "${path.name}: missing meta.name" }
            val expectedName = path.name.removeSuffix(".json")
            assertEquals(
                "\"$expectedName\"",
                name.toString(),
                "${path.name}: meta.name does not match the file name",
            )

            requireNotNull(meta["description"]) { "${path.name}: missing meta.description" }
            requireNotNull(meta["phase"]) { "${path.name}: missing meta.phase" }
        }
    }
}
