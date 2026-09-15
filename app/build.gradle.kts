import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    // AGP 9 compiles Kotlin itself, but the Compose Compiler plugin is still needed separately
    alias(libs.plugins.kotlin.compose)
    // For Navigation Compose's type-safe routes (@Serializable)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    // Generates the dependency license list as raw/aboutlibraries.json at build time
    alias(libs.plugins.aboutlibraries.android)
}

android {
    namespace = "com.mockonomy"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.mockonomy"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0.0"
    }

    // Signing information is NOT in the repo; it lives in keystore.properties at the root
    // (gitignored). Without that file a release still builds, just unsigned — so the R8
    // build can be verified on a machine without the key.
    val keystoreProperties = rootProject.file("keystore.properties").takeIf { it.exists() }?.let {
        Properties().apply { it.inputStream().use(::load) }
    }

    signingConfigs {
        if (keystoreProperties != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
        // The HAR file's `creator.version` field writes the app version; this avoids
        // copying the version to a second place.
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }

    // Full license texts (Settings > Open source licenses). The licenses/ directory at the
    // repository root is the single source — no copies; the files land at the assets root.
    sourceSets {
        getByName("main") {
            assets.srcDir(rootProject.file("licenses"))
        }
    }
}

// Build-only plugin. Its runtime library (aboutlibraries-core 15.2.0) pulls in
// kotlin-stdlib 2.4, which does not match the Kotlin version AGP brings; the generated
// JSON is read with kotlinx.serialization instead (ui/screens/licenses/LibraryDefinitions.kt).
aboutLibraries {
    // The build never goes online. In this mode the JSON carries no full license text —
    // the texts live in licenses/.
    offlineMode = true

    license {
        // MIT/BSD texts must carry the library's own copyright line; a generic template
        // is not enough. A license that is not listed here fails the build: add its text
        // to licenses/ and its mapping to LibraryDefinitions.kt.
        strictMode = com.mikepenz.aboutlibraries.plugin.StrictMode.FAIL
        allowedLicenses.add("Apache-2.0")
        allowedLicensesMap = mapOf(
            "MIT" to listOf("org.slf4j:slf4j-api"),
            "BSD-3-Clause" to listOf("androidx.datastore:datastore-preferences-external-protobuf"),
        )
    }
}

dependencies {
    implementation(project(":server"))
    implementation(project(":data"))
    implementation(project(":engine"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    // ViewModel tests run on the plain JVM with EnvironmentStore/ServerLauncher fakes;
    // MockServerController has no Android dependency anyway
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    // Tests use a real DataStore (the :data module's implementation dependency does not
    // leak into :app, so it is needed here separately)
    testImplementation(libs.datastore.preferences)
}
