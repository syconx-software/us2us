import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.syconx.us2us"
    // Platform 37.0 is installed as android-37.0, which AGP 9.0 only finds through the minor level.
    compileSdk {
        version = release(37) { minorApiLevel = 0 }
    }
    // Strip native debug symbols in release artifacts with a pinned toolchain.
    ndkVersion = "30.0.16248370"
    defaultConfig {
        applicationId = "com.syconx.us2us"
        minSdk = 28
        targetSdk = 36
        versionCode = 16
        versionName = "1.1.5"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        // libsignal and :core are Java 21 bytecode.
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        // libsignal-android declares that it needs core library desugaring.
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // Play upload key: kept outside the repo (scripts/create-upload-key.sh); the password comes only
    // from the environment of scripts/bundle-release.sh, which asks for it.
    val uploadKey = file(System.getenv("US2US_UPLOAD_KEYSTORE") ?: (System.getProperty("user.home") + "/.us2us/us2us-upload.jks"))
    val uploadPassword = System.getenv("US2US_UPLOAD_KEY_PASSWORD")
    signingConfigs {
        create("upload") {
            storeFile = uploadKey
            storePassword = uploadPassword
            keyAlias = System.getenv("US2US_UPLOAD_KEY_ALIAS") ?: "us2us-upload"
            keyPassword = System.getenv("US2US_UPLOAD_PRIVATE_KEY_PASSWORD") ?: uploadPassword
        }
    }
    buildTypes {
        release {
            if (uploadKey.exists() && uploadPassword != null) signingConfig = signingConfigs.getByName("upload")
            // R8: shrinks and optimizes the release build; libraries bring their own keep rules,
            // the app's are in proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            // Without an NDK the libsignal .so files stay unstripped (about 120 MB per ABI):
            // debug builds carry only the ABI of this Mac's emulator and of current phones.
            ndk { abiFilters += setOf("arm64-v8a") }
        }
    }
    packaging {
        // libsignal-client carries desktop binaries at its jar root (about 640 MB uncompressed):
        // libsignal_jni[_testing]_{aarch64,amd64}.dylib, signal_jni[_testing]_amd64.dll and
        // libsignal_jni[_testing]_amd64.so. The Android .so files come from libsignal-android's
        // jni/ folders, which these patterns do not touch.
        // libsignal_jni_testing.so carries test-only entry points; the app never calls them, and
        // the loader would otherwise prefer it over the production library.
        jniLibs { excludes += setOf("**/*.dylib", "**/*.dll", "**/libsignal_jni_testing.so") }
        resources { excludes += setOf("**/*.dylib", "**/*.dll", "libsignal_jni*.so") }
    }
    // The app switches its language itself (7.1 › Dil, per-app locales): a Play bundle keeps every
    // language in the base APK, and only the two the app is written in are packaged.
    bundle {
        language { enableSplit = false }
    }
    androidResources {
        localeFilters += listOf("en", "tr")
    }
    testOptions {
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

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.packaging.jniLibs.excludes.add("**/libsignal_jni_testing.so")
        variant.androidResources.ignoreAssetsPatterns.add("libsignal-testing.md")
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
}

composeCompiler {
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("compose-stability.conf"))
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(project(":designsystem"))
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:lock"))
    implementation(project(":feature:chats"))
    implementation(project(":feature:conversation"))
    implementation(project(":feature:media"))
    implementation(project(":feature:verification"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:report"))
    implementation(project(":scene3d"))
    implementation(project(":live"))
    // Replaces the desktop natives of :core's libsignal-client with the Android ones.
    implementation(libs.libsignal.android)
    implementation(libs.appcompat)
    implementation(libs.fragment.ktx)
    implementation(libs.core.ktx)
    implementation(libs.core.splashscreen)
    implementation(libs.lifecycle.process)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(project(":fixtures"))
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(project(":screenshot-support"))
    testImplementation(project(":fixtures"))
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.compose.ui.test.manifest)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.uiautomator)
    androidTestImplementation(project(":runtime"))
    androidTestImplementation(project(":core"))
    // The Compose test library asks for Espresso 3.5, which cannot inject input on API 35 and later.
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}
