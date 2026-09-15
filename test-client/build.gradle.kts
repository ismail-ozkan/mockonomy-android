import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * A standalone test client that proves Mockonomy's cleartext story.
 *
 * It is deliberately set up like an ORDINARY app: no Hilt, no :engine dependency, no
 * special network settings. The goal is to answer honestly: "can an Android app built with
 * the default configuration connect to the mock server?"
 */
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.mockonomy.testclient"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.mockonomy.testclient"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
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

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.core)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)

    implementation(libs.okhttp)
}
