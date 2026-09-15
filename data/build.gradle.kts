import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.mockonomy.data"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // AGP 9: Kotlin settings go inside the android block
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
}

// The Room schema is kept under version control: if a migration is ever needed,
// the previous schema must be available.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Model types come from :engine and must be visible to consumers too
    api(project(":engine"))

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okio)
    implementation(libs.datastore.preferences)

    // Request log storage
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // To inject EnvironmentStore into :server and :app — see di/DataModule.kt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // File logic is tested without an emulator using FakeFileSystem
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.okio.fakefilesystem)
    testImplementation(libs.kotlinx.coroutines.test)
}
