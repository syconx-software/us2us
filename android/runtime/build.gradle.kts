import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The live core: vault and Keystore, SQLCipher storage, network supervision, media preparation
// and the messaging engine on :core.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.syconx.us2us.runtime"
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
    api(project(":core"))
    // SQLCipher's AAR alone: its database classes run on androidx.sqlite, declared next to it.
    implementation(variantOf(libs.sqlcipher.android) { artifactType("aar") })
    implementation(libs.androidx.sqlite)
    implementation(libs.androidx.biometric)
    implementation(libs.fragment.ktx)
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.process)
    implementation(libs.media3.transformer)
    implementation(libs.androidx.exifinterface)
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
