import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The JVM screenshot host shared by every module's tests: Robolectric native graphics at the
// iOS reference size, the design insets, the frozen clock and the layout-JSON geometry checks.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.syconx.us2us.shot"
    // Platform 37.0 is installed as android-37.0, which AGP 9.0 only finds through the minor level.
    compileSdk {
        version = release(37) { minorApiLevel = 0 }
    }
    defaultConfig {
        minSdk = 28
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures { compose = true }
    testOptions {
        // Robolectric 4.17 runs up to API 36.
        targetSdk = 36
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("robolectric.graphicsMode", "NATIVE")
                it.maxHeapSize = "2g"
                // Robolectric reaches into FileDescriptor internals, which JDK 23 does not export.
                it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED", "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
                // Where the shots go and where the iOS references and layout JSON are read from.
                it.systemProperty("us2us.androidDir", rootProject.layout.projectDirectory.asFile.absolutePath)
            }
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
}

composeCompiler {
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("compose-stability.conf"))
}

dependencies {
    api(project(":designsystem"))
    api(project(":fixtures"))
    api(libs.robolectric)
    api(libs.roborazzi)
    api(libs.roborazzi.compose)
    api(libs.roborazzi.junit.rule)
    api(libs.compose.ui.test.junit4)
    api(libs.junit4)
    api(libs.kotlinx.coroutines.test)
    // ComponentActivity in the merged test manifest, for createComposeRule().
    api(libs.compose.ui.test.manifest)
}
