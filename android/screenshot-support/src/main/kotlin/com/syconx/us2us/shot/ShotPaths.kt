package com.syconx.us2us.shot

import java.io.File

/**
 * Where shots are written and where the iOS design is read from.
 *
 * - JVM shots: `android/build/shots/android/<theme>-<lang>/<name>.png`, each with a `<name>.json`
 *   sidecar (theme, language, design reference, test) for `scripts/compare_shots.py`.
 * - Device shots (`scripts/run-route.sh`): `android/build/shots/device/<theme>-<lang>/<name>.png`.
 * - iOS references: `ios/design/reference/{,light/,urgent/}` for the drawn screens and
 *   `android/build/ios-reference/<theme>-<lang>/<name>.png` (captured from the simulator) for the rest.
 */
object ShotPaths {
    /** The `android/` directory: the `us2us.androidDir` system property, else found above the working directory. */
    val androidDir: File by lazy {
        System.getProperty("us2us.androidDir")?.let(::File)?.takeIf { it.isDirectory }
            ?: generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "settings.gradle.kts").isFile && File(it, "gradlew").isFile }
            ?: error("cannot find the android/ directory above ${System.getProperty("user.dir")}")
    }

    /** The repository root (it holds `ios/` and `android/`). */
    val repoDir: File get() = androidDir.absoluteFile.parentFile ?: error("$androidDir has no parent")

    /** `ios/design`: reference PNGs, layout JSON and tokens. */
    val designDir: File get() = File(repoDir, "ios/design")

    /** `ios/design/layout`. */
    val layoutDir: File get() = File(designDir, "layout")

    /** `android/build/shots`. */
    val shotsDir: File get() = File(androidDir, "build/shots")

    /** `android/build/shots/android/<theme>-<lang>`. */
    fun androidShotDir(theme: ShotTheme, lang: String): File = File(shotsDir, "android/${variant(theme, lang)}")

    /** The PNG for a JVM shot. */
    fun androidShot(theme: ShotTheme, lang: String, name: String): File =
        File(androidShotDir(theme, lang), "${fileName(name)}.png")

    /** `dark-tr`, `light-en` … — the folder name shared with the iOS captures. */
    fun variant(theme: ShotTheme, lang: String): String = "${theme.raw}-$lang"

    /**
     * The file name for a shot: a design screen (`3.2-kod-yanlis`) stays as it is; a launch route
     * (`conversation:deniz/5.3`, `lock:wrong`) becomes `conversation_deniz_5.3`, `lock_wrong`,
     * which is how the iOS captures are named.
     */
    fun fileName(name: String): String {
        val cleaned = name.trim().replace(':', '_').replace('/', '_').replace(Regex("[^A-Za-z0-9._+-]"), "_")
        require(cleaned.isNotEmpty() && cleaned != "." && cleaned != "..") { "not a shot name: '$name'" }
        return cleaned
    }

    /** The shot name for a launch route (`feature[:target][/state]`), as the iOS captures name it. */
    fun routeName(route: String): String = fileName(route)
}
