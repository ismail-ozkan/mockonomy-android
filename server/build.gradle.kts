import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    // The admin API returns structured JSON; needed for @Serializable DTOs.
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.mockonomy.server"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
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
}

dependencies {
    api(project(":engine"))

    // api: MockServerController's constructor takes a RequestLogSink, so :data types are
    // visible in :server's public API.
    api(project(":data"))

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    // Proxy forwarding client
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Integration tests send real HTTP requests to a real server
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.okhttp)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.network.tls.certificates)
    testImplementation(libs.ktor.server.netty)
    // The admin API controller test builds its settings store with a temporary DataStore
    testImplementation(libs.datastore.preferences)
}
