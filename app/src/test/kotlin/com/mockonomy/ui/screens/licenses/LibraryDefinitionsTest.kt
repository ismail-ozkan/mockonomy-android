package com.mockonomy.ui.screens.licenses

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LibraryDefinitionsTest {

    @Test
    fun `libraries are sorted by name, with version and license in the detail line`() {
        val entries = parseLibraryDefinitions(
            """
            {
              "libraries": [
                {"uniqueId": "io.ktor:ktor-server-core", "artifactVersion": "3.5.2", "name": "ktor-server-core", "licenses": ["Apache-2.0"], "funding": []},
                {"uniqueId": "androidx.activity:activity", "artifactVersion": "1.13.0", "name": "Activity", "licenses": ["Apache-2.0"]}
              ],
              "licenses": {"Apache-2.0": {"name": "Apache License 2.0", "url": "https://www.apache.org/licenses/LICENSE-2.0"}}
            }
            """.trimIndent(),
        )

        assertEquals(listOf("Activity", "ktor-server-core"), entries.map { it.name })
        assertEquals("1.13.0 · Apache-2.0", entries.first().detail)
        assertEquals(APACHE_2_0_ASSET, entries.first().assetPath)
    }

    @Test
    fun `an MIT library maps to its own copyright text`() {
        val entries = parseLibraryDefinitions(
            """{"libraries": [{"uniqueId": "org.slf4j:slf4j-api", "artifactVersion": "2.0.18", "name": "SLF4J API Module", "licenses": ["MIT"]}]}""",
        )

        assertEquals("slf4j-MIT.txt", entries.single().assetPath)
    }

    @Test
    fun `a license without a bundled text is not silently treated as Apache`() {
        assertFailsWith<IllegalStateException> {
            parseLibraryDefinitions(
                """{"libraries": [{"uniqueId": "com.example:lib", "name": "Lib", "licenses": ["MIT"]}]}""",
            )
        }
    }

    @Test
    fun `every mapped license file exists in the licenses directory`() {
        // Unit tests run in the module directory; the assets source is licenses/ at the repository root
        val assets = bundledNotices.map { it.assetPath } + libraryAssets.values + APACHE_2_0_ASSET
        val missing = assets.filterNot { File("../licenses", it).isFile }
        assertTrue(missing.isEmpty(), "Missing license files: $missing")
    }
}
