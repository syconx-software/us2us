import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// RealMessengerService: the domain's MessengerService on the live runtime.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.syconx.us2us.live"
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
        // libsignal-android declares that it needs core library desugaring.
        isCoreLibraryDesugaringEnabled = true
    }
    // The instrumented tests' APK: Android libsignal only, never the desktop binaries of
    // libsignal-client (see app/build.gradle.kts).
    packaging {
        jniLibs { excludes += setOf("**/*.dylib", "**/*.dll") }
        resources { excludes += setOf("**/*.dylib", "**/*.dll", "libsignal_jni*.so") }
    }
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
            }
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    api(project(":domain"))
    implementation(project(":runtime"))
    implementation(libs.activity.compose)
    // The runtime's biometric prompt is shown over a FragmentActivity.
    implementation(libs.fragment.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.robolectric)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.libsignal.android)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
