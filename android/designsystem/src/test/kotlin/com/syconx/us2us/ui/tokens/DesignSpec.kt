package com.syconx.us2us.ui.tokens

import java.io.File
import org.json.JSONObject

/** The design specification (ios/design) and the Android tree, located from the test's working directory. */
object DesignSpec {
    /** android/ (the Gradle root). */
    val androidDir: File by lazy {
        System.getProperty("us2us.androidDir")?.let(::File)?.takeIf { it.isDirectory }
            ?: generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .first { File(it, "settings.gradle.kts").isFile && File(it, "designsystem").isDirectory }
    }

    /** ios/design. */
    val directory: File get() = File(androidDir.parentFile, "ios/design")

    /** A JSON file of ios/design (needs Robolectric's org.json). */
    fun json(path: String): JSONObject = JSONObject(File(directory, path).readText())

    /** Where this module's shots go: android/build/shots/android/designsystem. */
    fun shotFile(name: String): File = File(androidDir, "build/shots/android/designsystem/$name").also { it.parentFile?.mkdirs() }
}
