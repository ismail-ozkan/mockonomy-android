pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "mockonomy"

// Dependency direction:
//   :app  →  :server, :data, :engine
//   :server → :engine, :data
//   :data   → :engine
//   :engine → (nothing — pure Kotlin/JVM)
include(":app")
include(":server")
include(":data")
include(":engine")

// A standalone test client — not part of the product, a permanent manual test fixture.
include(":test-client")
