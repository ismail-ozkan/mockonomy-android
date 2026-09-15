import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// ⚠️ :engine is a PURE KOTLIN/JVM module — NOT an Android library.
//
// No Android plugin or dependency may be added here. android.* classes are not on
// the classpath, so they cannot be imported even by accident; the guarantee comes
// from the compiler, not from discipline.
//
// To keep the Kotlin Multiplatform path open, kotlinx-datetime and okio are used
// instead of java.* APIs.

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    implementation(libs.okio)

    testImplementation(libs.kotlin.test)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.test {
    useJUnitPlatform()
    // Golden fixtures are read from spec/, not embedded in code (spec/README.md).
    // Without declaring it as inputs.dir, Gradle would not notice changes under spec/
    // and would silently treat the tests as "up-to-date", returning stale results.
    val specDir = rootProject.layout.projectDirectory.dir("spec")
    inputs.dir(specDir).withPropertyName("specFixtures").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("mockonomy.spec.dir", specDir.asFile.absolutePath)
}
