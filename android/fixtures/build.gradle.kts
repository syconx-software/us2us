// The design's data behind MessengerService, with the real rules from :core.
// Debug builds and the UI tests only; never part of a release build.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":domain"))
    api(libs.kotlinx.coroutines.core)
    implementation(project(":core"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
