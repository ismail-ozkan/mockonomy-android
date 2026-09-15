package com.mockonomy.ui.screens.licenses

import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One row on the licenses screen. [assetPath] is the full text, embedded into the assets
 * from the `licenses/` directory at the repository root — that file is the notice the APK
 * actually carries.
 */
data class LicenseEntry(
    val name: String,
    val detail: String,
    val assetPath: String,
)

const val APACHE_2_0_ASSET = "apache-2.0.txt"

/** Fonts and icons the app embeds — they are not Gradle dependencies, so the JSON misses them. */
val bundledNotices = listOf(
    LicenseEntry("Inter", "OFL-1.1", "inter-OFL.txt"),
    LicenseEntry("JetBrains Mono", "OFL-1.1", "jetbrains-mono-OFL.txt"),
    LicenseEntry("Phosphor Icons", "2.1.1 · MIT", "phosphor-MIT.txt"),
)

/**
 * MIT and BSD texts have to carry the library's own copyright line, so they are mapped per
 * library rather than per license id. Keep this in sync with `allowedLicensesMap` in
 * `app/build.gradle.kts`.
 */
internal val libraryAssets = mapOf(
    "org.slf4j:slf4j-api" to "slf4j-MIT.txt",
    "androidx.datastore:datastore-preferences-external-protobuf" to "protobuf-BSD-3-Clause.txt",
)

@Serializable
private data class Definitions(val libraries: List<Library>)

@Serializable
private data class Library(
    val uniqueId: String,
    val artifactVersion: String? = null,
    val name: String? = null,
    val licenses: List<String> = emptyList(),
)

private val json = Json { ignoreUnknownKeys = true }

/** Turns the `aboutlibraries.json` the Gradle plugin generates into rows sorted by name. */
fun parseLibraryDefinitions(text: String): List<LicenseEntry> =
    json.decodeFromString<Definitions>(text).libraries
        .map { library ->
            LicenseEntry(
                name = library.name ?: library.uniqueId,
                detail = listOfNotNull(library.artifactVersion, *library.licenses.toTypedArray()).joinToString(" · "),
                assetPath = libraryAssets[library.uniqueId]
                    ?: if (library.licenses == listOf("Apache-2.0")) {
                        APACHE_2_0_ASSET
                    } else {
                        // strictMode in the build already prevents this
                        error("No bundled license text for ${library.uniqueId}: ${library.licenses}")
                    },
            )
        }
        .sortedBy { it.name.lowercase(Locale.ROOT) }
