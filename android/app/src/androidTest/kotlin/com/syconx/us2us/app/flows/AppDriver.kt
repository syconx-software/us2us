package com.syconx.us2us.app.flows

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.syconx.us2us.app.MainActivity
import com.syconx.us2us.app.Us2usApplication
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Drives the app like the iOS UI tests drive theirs: a launch with the iOS launch arguments (the
 * design's data, the frozen clock, Turkish, dark), taps by the iOS accessibility identifiers (the
 * test tags), texts as the user reads them, the code keypad, and the home button.
 *
 * Every launch starts over: a new app model from the launch's own extras and the preferences reset,
 * as a fresh process would. As a rule (after the Compose rule) it closes the app after each test and
 * names the folder of its pictures.
 */
class AppDriver(private val compose: ComposeTestRule) : TestWatcher() {
    private var scenario: ActivityScenario<MainActivity>? = null

    /** The folder of this test's pictures. */
    private var testName: String? = null

    override fun starting(description: Description) {
        testName = "${description.testClass.simpleName}.${description.methodName}"
    }

    override fun failed(e: Throwable, description: Description) {
        if (scenario != null) snap("failed")
    }

    override fun finished(description: Description) = close()

    fun launch(vararg arguments: Pair<String, String>, language: String = "tr", theme: String = "dark") {
        close()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as Us2usApplication
        instrumentation.runOnMainSync { app.startOver() }
        val intent = Intent(context, MainActivity::class.java)
            .putExtra("U2Fixtures", "YES")
            .putExtra("U2FrozenClock", "YES")
            .putExtra("U2ResetPreferences", "YES")
            // The pictures of the walk (the window is otherwise never captured).
            .putExtra("U2AllowCapture", "YES")
            .putExtra("U2Language", language)
            .putExtra("U2Theme", theme)
        for ((name, value) in arguments) intent.putExtra(name, value)
        scenario = ActivityScenario.launch(intent)
    }

    fun close() {
        scenario?.close()
        scenario = null
    }

    /** The home button, then back to the app (`XCUIDevice.press(.home)`, `app.activate()`). */
    fun backgroundAndReturn() {
        val running = checkNotNull(scenario) { "not launched" }
        running.moveToState(Lifecycle.State.CREATED)
        running.moveToState(Lifecycle.State.RESUMED)
    }

    // MARK: Finding

    private fun all(matcher: SemanticsMatcher): List<SemanticsNode> =
        runCatching { compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false) }
            .getOrDefault(emptyList())

    fun exists(matcher: SemanticsMatcher): Boolean = all(matcher).isNotEmpty()

    fun exists(tag: String): Boolean = exists(hasTestTag(tag))

    fun waitFor(matcher: SemanticsMatcher, timeoutMillis: Long = TIMEOUT, what: String = matcher.description) {
        val found = runCatching { compose.waitUntil(timeoutMillis) { exists(matcher) } }.isSuccess
        assertTrue("$what not found", found)
    }

    fun waitForGone(matcher: SemanticsMatcher, timeoutMillis: Long = TIMEOUT, what: String = matcher.description) {
        val gone = runCatching { compose.waitUntil(timeoutMillis) { !exists(matcher) } }.isSuccess
        assertTrue("$what still there", gone)
    }

    fun waitForTag(tag: String, timeoutMillis: Long = TIMEOUT) = waitFor(hasTestTag(tag), timeoutMillis, tag)

    fun waitForTagGone(tag: String, timeoutMillis: Long = TIMEOUT) = waitForGone(hasTestTag(tag), timeoutMillis, tag)

    fun waitForText(text: String, substring: Boolean = false, timeoutMillis: Long = TIMEOUT) =
        waitFor(hasTextLike(text, substring), timeoutMillis, "“$text”")

    fun waitForTextGone(text: String, timeoutMillis: Long = TIMEOUT) = waitForGone(hasTextLike(text), timeoutMillis, "“$text”")

    fun node(tag: String): SemanticsNodeInteraction = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true)[0]

    /** What a tagged element says (its texts and description, merged as an accessibility service hears it). */
    fun label(tag: String): String {
        val node = compose.onAllNodes(hasTestTag(tag))[0].fetchSemanticsNode()
        return spoken(node).joinToString(" ")
    }

    /** A tagged element's value (`XCUIElement.value`): its state description. */
    fun value(tag: String): String? =
        compose.onAllNodes(hasTestTag(tag))[0].fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    fun isEnabled(tag: String): Boolean = node(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Disabled) == null

    fun isSelected(tag: String): Boolean {
        val config = compose.onAllNodes(hasTestTag(tag))[0].fetchSemanticsNode().config
        return config.getOrNull(SemanticsProperties.Selected) == true
    }

    fun waitUntil(what: String, timeoutMillis: Long = TIMEOUT, condition: () -> Boolean) {
        val met = runCatching { compose.waitUntil(timeoutMillis) { runCatching(condition).getOrDefault(false) } }.isSuccess
        assertTrue("$what did not happen", met)
    }

    // MARK: Acting

    /** Whether the tagged element lies inside the screen. */
    private fun isOnScreen(tag: String): Boolean {
        val bounds = node(tag).fetchSemanticsNode().boundsInWindow
        val screen = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics
        return bounds.width > 0f && bounds.top >= 0f && bounds.left >= 0f &&
            bounds.bottom <= screen.heightPixels && bounds.right <= screen.widthPixels
    }

    /** Waits until the tagged element stops moving (an entrance, a sheet rising), as XCUITest waits before a tap. */
    private fun waitUntilSettled(tag: String) {
        var last = node(tag).fetchSemanticsNode().boundsInWindow
        var still = 0
        val deadline = System.currentTimeMillis() + 3_000
        while (still < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(60)
            val now = runCatching { node(tag).fetchSemanticsNode().boundsInWindow }.getOrNull() ?: return
            if (now == last) still++ else still = 0
            last = now
        }
    }

    /**
     * Taps a tagged element once it has stopped moving, scrolled into view first when it is off
     * screen (XCUITest scrolls to make it hittable).
     */
    fun tap(tag: String, timeoutMillis: Long = TIMEOUT) {
        waitForTag(tag, timeoutMillis)
        if (!isOnScreen(tag)) runCatching { node(tag).performScrollTo() }
        waitUntilSettled(tag)
        node(tag).performClick()
    }

    /**
     * Taps a tagged element until [done] (a button that takes taps only once its appearance has
     * settled, like a destructive confirmation after its cross-fade); at most three taps.
     */
    fun tapUntil(tag: String, what: String, done: () -> Boolean) {
        repeat(3) {
            if (exists(tag)) tap(tag)
            val met = runCatching { compose.waitUntil(3_000) { runCatching(done).getOrDefault(false) } }.isSuccess
            if (met) return
        }
        waitUntil(what, condition = done)
    }

    /** Taps the first element with this text or description (a button found by its label). */
    fun tapText(text: String, timeoutMillis: Long = TIMEOUT) {
        val matcher = hasTextLike(text)
        waitFor(matcher, timeoutMillis, "“$text”")
        compose.onAllNodes(matcher, useUnmergedTree = true)[0].performClick()
    }

    fun longPress(tag: String, timeoutMillis: Long = TIMEOUT) {
        waitForTag(tag, timeoutMillis)
        node(tag).performTouchInput { longClick(durationMillis = 800) }
    }

    fun scrollTo(tag: String) {
        waitForTag(tag)
        node(tag).performScrollTo()
    }

    /** The text field tagged [tag] (or inside the tagged element), else the first text field. */
    private fun field(tag: String?): SemanticsMatcher =
        if (tag != null) (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))) and hasSetTextAction() else hasSetTextAction()

    /** Types into the first text field (`app.textFields.firstMatch`), or the tagged one, after what it holds. */
    fun type(text: String, tag: String? = null) {
        val matcher = field(tag)
        waitFor(matcher, what = tag ?: "a text field")
        val target = compose.onAllNodes(matcher, useUnmergedTree = true)[0]
        target.performClick()
        target.performTextInput(text)
    }

    /** Replaces what the first text field (or the tagged one) holds. */
    fun replace(text: String, tag: String? = null) {
        val matcher = field(tag)
        waitFor(matcher, what = tag ?: "a text field")
        compose.onAllNodes(matcher, useUnmergedTree = true)[0].performTextReplacement(text)
    }

    /** How many text fields are on screen (`app.secureTextFields.count`). */
    fun fieldCount(): Int = all(hasSetTextAction()).size

    /** Our activity is no longer in front: a system activity (the share sheet, a picker) is, and has risen. */
    fun waitForSystemActivity(timeoutMillis: Long = 15_000) {
        val running = checkNotNull(scenario) { "not launched" }
        waitUntil("a system activity in front", timeoutMillis) { running.state != Lifecycle.State.RESUMED }
        Thread.sleep(SYSTEM_SHEET_RISE)
    }

    /** The system back closes the system activity in front; the app is in front again. */
    fun closeSystemActivity() {
        val running = checkNotNull(scenario) { "not launched" }
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        waitUntil("the app in front again", 10_000) { running.state == Lifecycle.State.RESUMED }
    }

    /** Whether the keypad's [digit] key takes a tap. */
    fun isKeyEnabled(digit: Char): Boolean =
        all(hasContentDescription(digit.toString()) and hasClickAction()).first().config.getOrNull(SemanticsProperties.Disabled) == null

    /** How many digits the code dots say are entered (“3 / 6 hane girildi”), or `null` without dots. */
    private fun enteredDigits(): Int? = all(SemanticsMatcher("code dots") { node -> spoken(node).any { CODE_DOTS.matches(it) } })
        .flatMap(::spoken)
        .firstNotNullOfOrNull { CODE_DOTS.matchEntire(it)?.groupValues?.get(1)?.toInt() }

    /**
     * Taps the digits on the code keypad (`app.buttons["2"]`), each once its dot can show: the six
     * red dots of a wrong code take no digit until they clear (0.8 s), as on iOS.
     */
    fun typeCode(code: String) {
        waitUntil("the code dots ready") { (enteredDigits() ?: 0) < 6 }
        for ((index, digit) in code.withIndex()) {
            val key = hasContentDescription(digit.toString()) and hasClickAction()
            waitFor(key, what = "key $digit")
            waitUntil("key $digit enabled") {
                all(key).first().config.getOrNull(SemanticsProperties.Disabled) == null
            }
            compose.onAllNodes(key, useUnmergedTree = true)[0].performClick()
            // The dot shows before the next digit (the last one moves on).
            if (index < code.length - 1) {
                waitUntil("digit ${index + 1} of “$code” shown (${enteredDigits()})", timeoutMillis = 3_000) {
                    enteredDigits() == index + 1
                }
            }
        }
    }

    /**
     * A picture of the whole screen, kept in the app's external files (`flows/<test>/<name>.png`),
     * like the iOS walks' attachments.
     */
    fun snap(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val picture = instrumentation.uiAutomation.takeScreenshot() ?: return
        val folder = File(instrumentation.targetContext.getExternalFilesDir("flows"), testName ?: "flow").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { picture.compress(Bitmap.CompressFormat.PNG, 100, it) }
        picture.recycle()
    }

    /** Lets an animation finish (the iOS walks' `settle`). */
    fun settle(millis: Long = 700) {
        Thread.sleep(millis)
        compose.waitForIdle()
    }

    companion object {
        const val TIMEOUT = 6_000L

        /** How long a system sheet takes to rise. */
        private const val SYSTEM_SHEET_RISE = 1_200L

        /** The code dots' description, Turkish or English. */
        private val CODE_DOTS = Regex("""(\d+) (?:/|of) 6 .*""")

        /** The texts, descriptions and state of [node] (its label and value), with no-break spaces read as spaces. */
        fun spoken(node: SemanticsNode): List<String> {
            val config = node.config
            return (
                config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
                    config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                    listOfNotNull(config.getOrNull(SemanticsProperties.EditableText)?.text) +
                    listOfNotNull(config.getOrNull(SemanticsProperties.StateDescription))
                ).map { it.replace(' ', ' ') }
        }

        /** An element that shows (or describes itself with) [text]; no-break spaces count as spaces. */
        fun hasTextLike(text: String, substring: Boolean = false): SemanticsMatcher =
            SemanticsMatcher("text “$text”") { node ->
                spoken(node).any { if (substring) it.contains(text) else it == text }
            }
    }
}
