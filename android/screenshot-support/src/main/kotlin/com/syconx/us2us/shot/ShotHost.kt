package com.syconx.us2us.shot

import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureScreenRoboImage
import java.io.File
import java.util.Locale
import java.util.TimeZone
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.robolectric.RuntimeEnvironment

/**
 * The JVM screenshot host: renders a composable as the whole screen of the iOS references and
 * captures it.
 *
 * - 402 × 874 dp at density 3 (exactly 1206 × 2622 px), portrait, font scale 1.
 * - Night mode and locale by [ShotTheme] and language, so `isSystemInDarkTheme()` and
 *   `stringResource` resolve as in the app.
 * - Design insets: `WindowInsets.statusBars` is 62 dp and `navigationBars` 34 dp, as on the iPhone.
 * - Frozen clock: the Compose clock is paused and moved only by [ShotOptions.settleMillis]; the
 *   design's moment ([DesignMoment]) and the fixed time zone are set for the whole test; the
 *   module's [ShotEnvironment] gets [ShotSpec.frozenClock] / [ShotSpec.nowMillis] for its own clock.
 *
 * ```
 * @RunWith(RobolectricTestRunner::class)
 * @Config(sdk = [ShotHost.SDK])
 * class LockShotTest {
 *     @get:Rule val host = ShotHost(environment = LockShots)
 *
 *     @Test fun wrongCode() {
 *         host.shoot("3.2-kod-yanlis", ShotTheme.Dark, "tr") { LockFlow(…) }
 *         host.assertFrames("r.0.1", "r.1.0", "r.3.0")
 *     }
 * }
 * ```
 *
 * Shots go to `android/build/shots/android/<theme>-<lang>/<name>.png` ([ShotPaths]);
 * `scripts/compare_shots.py` diffs them against the iOS references.
 */
class ShotHost(
    private val environment: ShotEnvironment = ShotEnvironment.None,
    private val activity: Class<out ComponentActivity> = ComponentActivity::class.java,
    /** Where `<theme>-<lang>/<name>.png` go; the host's own tests write elsewhere so they never pass for app shots. */
    private val outputDir: File = File(ShotPaths.shotsDir, "android"),
) : TestRule {
    /** Finders, interactions and the paused [ComposeTestRule.mainClock] for the screen on show. */
    val compose: ComposeTestRule = createEmptyComposeRule()

    /** The screen on show, `null` before [render]. */
    var spec: ShotSpec? = null
        private set

    /** The design layout [assertFrame] checks against: set by [shoot] for a drawn screen, or by [useLayout]. */
    var layout: LayoutSpec? = null
        private set

    private var scenario: ActivityScenario<out ComponentActivity>? = null
    private var insetsLayout: DesignInsetsLayout? = null
    private var testName: String = ""

    override fun apply(base: Statement, description: Description): Statement {
        testName = "${description.className}#${description.methodName}"
        val hosted = object : Statement() {
            override fun evaluate() {
                val zone = TimeZone.getDefault()
                val locale = Locale.getDefault()
                TimeZone.setDefault(TimeZone.getTimeZone(DesignMoment.zone))
                try {
                    base.evaluate()
                } finally {
                    closeScreen()
                    TimeZone.setDefault(zone)
                    Locale.setDefault(locale)
                }
            }
        }
        return compose.apply(hosted, description)
    }

    /**
     * Shows [content] as the screen (replacing any screen shown before) in [theme] and [lang], and
     * lets it settle: the first frame, the design insets, then [ShotOptions.settleMillis] on the paused clock.
     */
    fun render(
        theme: ShotTheme = ShotTheme.Dark,
        lang: String = "tr",
        options: ShotOptions = ShotOptions(),
        content: @Composable () -> Unit,
    ): ShotSpec {
        require(lang == "tr" || lang == "en") { "lang must be tr or en, not '$lang'" }
        closeScreen()
        val spec = ShotSpec(theme, lang, options)
        this.spec = spec
        RuntimeEnvironment.setQualifiers(spec.qualifiers)
        Locale.setDefault(spec.locale)
        compose.mainClock.autoAdvance = false

        val launched = ActivityScenario.launch(activity)
        scenario = launched
        launched.onActivity { activity ->
            val frame = DesignInsetsLayout(activity, options.designInsets)
            val view = ComposeView(activity)
            view.setContent {
                CompositionLocalProvider(LocalShotSpec provides spec) {
                    environment.Provide(spec, content)
                }
            }
            frame.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            activity.setContentView(frame)
            insetsLayout = frame
        }
        compose.waitForIdle()
        launched.onActivity { insetsLayout?.applyDesignInsets() }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        if (options.settleMillis > 0) {
            compose.mainClock.advanceTimeBy(options.settleMillis)
            compose.waitForIdle()
        }
        return spec
    }

    /**
     * Renders [content] and writes `android/build/shots/android/<theme>-<lang>/<name>.png`.
     *
     * [design] names the drawn screen this shot is compared with (`3.2-kod-yanlis`); it defaults to
     * [name] when that is a layout file, and it also selects the layout for [assertFrame]. Other shots
     * are named by their launch route (`lock:throttled` → `lock_throttled`), as the iOS captures are.
     */
    fun shoot(
        name: String,
        theme: ShotTheme = ShotTheme.Dark,
        lang: String = "tr",
        design: String? = null,
        options: ShotOptions = ShotOptions(),
        content: @Composable () -> Unit,
    ): File {
        render(theme, lang, options, content)
        val reference = design ?: name.takeIf(LayoutSpec::exists)
        layout = reference?.let(LayoutSpec::load)
        return capture(name, design = reference)
    }

    /** Captures the screen as it is now (after interactions) under [name]. */
    @OptIn(ExperimentalRoborazziApi::class)
    fun capture(name: String, design: String? = layout?.name): File {
        val spec = checkNotNull(spec) { "render a screen before capturing it" }
        val file = File(outputDir, "${ShotPaths.variant(spec.theme, spec.lang)}/${ShotPaths.fileName(name)}.png")
        file.parentFile?.mkdirs()
        compose.waitForIdle()
        captureScreenRoboImage(file, RoborazziOptions(taskType = RoborazziTaskType.Record))
        writeSidecar(file, name, spec, design)
        return file
    }

    /** Moves the paused clock (an animation mid-way, a countdown tick) and waits for the frame. */
    fun advance(millis: Long) {
        compose.mainClock.advanceTimeBy(millis)
        compose.waitForIdle()
    }

    /** Selects the layout [assertFrame] checks against (a file name such as `4.2-yeni-sohbet`, or a screen number). */
    fun useLayout(name: String): LayoutSpec = LayoutSpec.load(name).also { layout = it }

    /** The node tagged [tag] has the frame of [element] (default: the same id) in the current [layout], within [tolerance]. */
    fun assertFrame(tag: String, tolerance: Dp = 1.dp, element: String = tag) {
        compose.assertFrame(currentLayout(), tag, tolerance, element)
    }

    /** Every tag has the frame of the element with the same id; all mismatches are reported together. */
    fun assertFrames(vararg tags: String, tolerance: Dp = 1.dp) {
        compose.assertFrames(currentLayout(), *tags, tolerance = tolerance)
    }

    private fun currentLayout(): LayoutSpec =
        checkNotNull(layout) { "no layout: shoot a drawn screen (design = …) or call useLayout(…)" }

    private fun closeScreen() {
        insetsLayout = null
        scenario?.close()
        scenario = null
    }

    private fun writeSidecar(png: File, name: String, spec: ShotSpec, design: String?) {
        fun quote(value: String?) = value?.let { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" } ?: "null"
        val module = File(System.getProperty("user.dir") ?: ".").absoluteFile.relativeToOrNull(ShotPaths.androidDir)?.path
        val json = buildString {
            append("{\n")
            append("  \"name\": ").append(quote(name)).append(",\n")
            append("  \"theme\": ").append(quote(spec.theme.raw)).append(",\n")
            append("  \"lang\": ").append(quote(spec.lang)).append(",\n")
            append("  \"design\": ").append(quote(design)).append(",\n")
            append("  \"designInsets\": ").append(spec.designInsets).append(",\n")
            append("  \"frozenClock\": ").append(spec.frozenClock).append(",\n")
            append("  \"module\": ").append(quote(module?.ifEmpty { "." })).append(",\n")
            append("  \"test\": ").append(quote(testName)).append("\n")
            append("}\n")
        }
        File(png.parentFile, png.nameWithoutExtension + ".json").writeText(json)
    }

    companion object {
        /** The Robolectric SDK for shots (`@Config(sdk = [ShotHost.SDK])`): Robolectric 4.17 runs up to 36. */
        const val SDK = 36

        /** The iOS reference size, for `@Config(qualifiers = …)` in tests that do not go through [render]. */
        const val QUALIFIERS = "w402dp-h874dp-port-xxhdpi"
    }
}
