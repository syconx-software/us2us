package com.syconx.us2us.feature.onboarding

import com.syconx.us2us.domain.design.DesignFixtures
import com.syconx.us2us.domain.harness.FeatureHarness
import com.syconx.us2us.domain.harness.FixtureControls
import com.syconx.us2us.domain.harness.HarnessPlan
import com.syconx.us2us.domain.harness.HarnessUiState
import com.syconx.us2us.domain.nav.LaunchRoute
import com.syconx.us2us.domain.nav.OnboardingRoute
import com.syconx.us2us.domain.nav.StartScreen
import com.syconx.us2us.domain.UsernameRules
import kotlinx.coroutines.delay

/**
 * Debug harness for 01–02 · Onboarding: opens every onboarding screen and state directly, in its
 * exact design state, for screenshots and comparisons (`U2Route onboarding:<state>`).
 *
 * | `onboarding:` | design | state |
 * |---|---|---|
 * | `1.1` · `1.2` · `1.3` | 1.1–1.3 | the welcome pages (the references) |
 * | `2.1` | 2.1 | “selin_n” available, focused (the reference) |
 * | `2.1-blurred` | 2.1 | the same, unfocused |
 * | `2.1-rules` | 2.1 | “selin_n” meets the rules (the live service: “Kurallara uygun”) |
 * | `2.1-empty` | 2.1 | nothing typed, Devam at 45 % |
 * | `2.1-checking` | 2.1 | “selin_n”, checking (spinner) |
 * | `2.1-taken` | 2.1 | “selin” taken |
 * | `2.1-short` | 2.1 | “se” — the length rule in `danger` |
 * | `2.1-invalid` | 2.1 | “selin.n” — a dot: “Geçersiz karakter” |
 * | `2.1-turkish` | 2.1 | “kağan” — the Turkish rule in `danger` |
 * | `2.1-firstLetter` | 2.1 | “1selin” — must start with a letter |
 * | `2.2` | 2.2 | set step, four digits (the reference) |
 * | `2.2-empty` · `2.2-confirm` | 2.2 | no digits · confirm step, two digits |
 * | `2.2-weak` · `2.2-mismatch` | 2.2 | red dots + sentence |
 * | `2.3` | 2.3 | acknowledged (the reference) |
 * | `2.3-unchecked` · `2.3-error` | 2.3 | not acknowledged · account creation failed |
 * | `2.3-kept` · `2.3-taken` | 2.3 | “Kilit ekranına dön” · “Başka bir ad seç” |
 * | `7.3` | 7.3 | the restore hand-off from 1.1 |
 * | `restore-2.2` · `restore-2.3` | 2.2 · 2.3 | after a restore (“1 / 2”, “2 / 2”) |
 * | `restore-2.3-gone` · `restore-2.3-damaged` | 2.3 | the restored backup is gone · damaged |
 * | `restore-2.3-lock` · `restore-2.3-toLock` | 2.3 | restoring inside the lock: the warning · refused, back to the lock |
 * | `demo` · `demo-restore` · `demo-back` | | plays the flow, the restore path, every way back |
 *
 * The harness holds the drawn state (no automatic transitions). Only `2.1` focuses the field.
 */
object OnboardingHarness : FeatureHarness {
    override val feature: LaunchRoute.Feature = LaunchRoute.Feature.Onboarding

    override val states: List<String> = listOf(
        "1.1", "1.2", "1.3", "2.1", "2.1-blurred", "2.1-rules", "2.1-empty", "2.1-checking", "2.1-taken",
        "2.1-short", "2.1-invalid", "2.1-turkish", "2.1-firstLetter", "2.2", "2.2-empty", "2.2-confirm",
        "2.2-weak", "2.2-mismatch", "2.3", "2.3-unchecked", "2.3-error", "2.3-kept", "2.3-taken", "7.3",
        "restore-2.2", "restore-2.3", "restore-2.3-gone", "restore-2.3-damaged", "restore-2.3-lock",
        "restore-2.3-toLock", "demo", "demo-restore", "demo-back",
    )

    override fun plan(target: String?, state: String?, controls: FixtureControls?): HarnessPlan? {
        if (state == null || state !in states) return null
        return HarnessPlan(start = StartScreen.Welcome, uiState = OnboardingHarnessState(state))
    }
}

/**
 * Every harness of this module. The app's debug registry reads this list, so a harness added
 * here (one per route feature) is found without changing the app.
 */
val onboardingHarnesses: List<FeatureHarness> = listOf(OnboardingHarness)

/** An onboarding harness state, applied to the flow when it is made (iOS `OnboardingHarness.apply`). */
data class OnboardingHarnessState(override val state: String) : HarnessUiState {
    /** The 2.1 reference is drawn focused (ring, glow, caret); the other states unfocused, so no keyboard covers them. */
    val focusesUsernameField: Boolean get() = state == "2.1"

    val isDemo: Boolean get() = state.startsWith("demo")

    fun apply(flow: OnboardingFlowModel) {
        val demoCode = DesignFixtures.DEMO_SECRET_CODE
        val username = UsernameStepModel.Status.Available
        when (state) {
            "1.1" -> flow.pager.setForHarness(page = 0)
            "1.2" -> flow.pager.setForHarness(page = 1)
            "1.3" -> flow.pager.setForHarness(page = 2)
            "2.1", "2.1-blurred", "2.1-rules", "2.1-empty", "2.1-checking", "2.1-taken", "2.1-short", "2.1-invalid",
            "2.1-turkish", "2.1-firstLetter",
            -> {
                flow.pager.setForHarness(page = 2)
                flow.setForHarness(OnboardingFlowModel.Mode.Create, listOf(OnboardingRoute.Username))
                val model = flow.username
                when (state) {
                    "2.1-rules" -> model.setForHarness("selin_n", UsernameStepModel.Status.MeetsRules)
                    "2.1-empty" -> model.setForHarness("", UsernameStepModel.Status.Empty)
                    "2.1-checking" -> model.setForHarness("selin_n", UsernameStepModel.Status.Checking)
                    "2.1-taken" -> model.setForHarness("selin", UsernameStepModel.Status.Taken)
                    "2.1-short" -> model.setForHarness("se", UsernameStepModel.Status.TooShort)
                    "2.1-invalid" -> model.setForHarness("selin.n", UsernameStepModel.Status.Invalid(UsernameRules.Violation.Characters))
                    "2.1-turkish" -> model.setForHarness("kağan", UsernameStepModel.Status.Invalid(UsernameRules.Violation.TurkishCharacters))
                    "2.1-firstLetter" -> model.setForHarness("1selin", UsernameStepModel.Status.Invalid(UsernameRules.Violation.FirstLetter))
                    else -> model.setForHarness("selin_n", username)
                }
            }
            "2.2", "2.2-empty", "2.2-confirm", "2.2-weak", "2.2-mismatch" -> {
                flow.pager.setForHarness(page = 2)
                flow.username.setForHarness("selin_n", username)
                flow.setForHarness(OnboardingFlowModel.Mode.Create, listOf(OnboardingRoute.Username, OnboardingRoute.SecretCode))
                val code = flow.code
                when (state) {
                    "2.2-empty" -> code.setForHarness(SecretCodeStepModel.Step.Set, entered = 0)
                    "2.2-confirm" -> code.setForHarness(SecretCodeStepModel.Step.Confirm, entered = 2)
                    "2.2-weak" -> code.setForHarness(SecretCodeStepModel.Step.Set, entered = 6, problem = SecretCodeStepModel.Problem.Weak)
                    "2.2-mismatch" -> code.setForHarness(SecretCodeStepModel.Step.Confirm, entered = 6, problem = SecretCodeStepModel.Problem.Mismatch)
                    else -> code.setForHarness(SecretCodeStepModel.Step.Set, entered = 4)
                }
            }
            "2.3", "2.3-unchecked", "2.3-error", "2.3-kept", "2.3-taken" -> {
                flow.pager.setForHarness(page = 2)
                flow.username.setForHarness("selin_n", username)
                flow.setForHarness(
                    OnboardingFlowModel.Mode.Create,
                    listOf(OnboardingRoute.Username, OnboardingRoute.SecretCode, OnboardingRoute.NoWayBack),
                    confirmedCode = demoCode,
                )
                flow.code.setForHarness(SecretCodeStepModel.Step.Confirm, entered = 6)
                val failure = when (state) {
                    "2.3-error" -> NoWayBackStepModel.Failure.Network
                    "2.3-kept" -> NoWayBackStepModel.Failure.AccountKept
                    "2.3-taken" -> NoWayBackStepModel.Failure.UsernameTaken.also { flow.username.markTaken("selin_n") }
                    else -> null
                }
                flow.finish.setForHarness(acknowledged = state != "2.3-unchecked", failure = failure)
            }
            "demo-back" -> {
                flow.pager.setForHarness(page = 2)
                flow.username.setForHarness("selin_n", username)
                flow.setForHarness(
                    OnboardingFlowModel.Mode.Create,
                    listOf(OnboardingRoute.Username, OnboardingRoute.SecretCode, OnboardingRoute.NoWayBack),
                    confirmedCode = demoCode,
                )
                flow.code.setForHarness(SecretCodeStepModel.Step.Confirm, entered = 6)
                flow.code.isFrozenForHarness = false
                flow.finish.setForHarness(acknowledged = true)
            }
            "7.3" -> flow.setForHarness(OnboardingFlowModel.Mode.Create, listOf(OnboardingRoute.Restore))
            "restore-2.2" -> {
                flow.setForHarness(OnboardingFlowModel.Mode.Restore, listOf(OnboardingRoute.Restore, OnboardingRoute.SecretCode))
                flow.code.setForHarness(SecretCodeStepModel.Step.Set, entered = 0)
            }
            "restore-2.3", "restore-2.3-gone", "restore-2.3-damaged", "restore-2.3-lock", "restore-2.3-toLock" -> {
                flow.setForHarness(
                    OnboardingFlowModel.Mode.Restore,
                    listOf(OnboardingRoute.Restore, OnboardingRoute.SecretCode, OnboardingRoute.NoWayBack),
                    confirmedCode = demoCode,
                )
                flow.code.setForHarness(SecretCodeStepModel.Step.Confirm, entered = 6)
                // Restoring inside the lock (3.3 › 7.3): the warning that this phone's messages go.
                flow.finish.replacesThisPhone = state == "restore-2.3-lock" || state == "restore-2.3-toLock"
                when (state) {
                    "restore-2.3-gone" -> flow.finish.setForHarness(acknowledged = true, failure = NoWayBackStepModel.Failure.BackupGone)
                    "restore-2.3-damaged" -> flow.finish.setForHarness(acknowledged = true, failure = NoWayBackStepModel.Failure.BackupDamaged)
                    "restore-2.3-toLock" -> flow.finish.setForHarness(acknowledged = true, failure = NoWayBackStepModel.Failure.PreviousVaultKept)
                    else -> flow.finish.setForHarness(acknowledged = false)
                }
            }
            else -> Unit
        }
    }

    /**
     * Plays the flow through the view models with human timing: page changes (one by a simulated
     * swipe), typing with the availability check (a taken name first), a weak code, a mismatch, the
     * right code twice, the acknowledgement and “Kurulumu tamamla”.
     */
    suspend fun runDemo(flow: OnboardingFlowModel) {
        suspend fun wait(seconds: Double) = delay((seconds * 1000).toLong())
        suspend fun type(code: String) {
            for (digit in code) {
                flow.code.type(digit.digitToInt())
                wait(0.22)
            }
        }
        wait(2.0)
        if (state == "demo-back") {
            flow.router.pop() // 2.3 → 2.2: the code starts over
            wait(1.4)
            type("246813") // → confirm
            wait(1.2)
            flow.codeBack() // confirm → set
            wait(1.2)
            flow.codeBack() // 2.2 → 2.1
            wait(1.2)
            flow.router.pop() // 2.1 → 1.3
            wait(1.4)
            // 1.3 → 1.2 by a swipe to the right, then 1.2 → 1.1 by a quick flick.
            for (step in 1..12) {
                flow.pager.dragProgress = -0.4f * step / 12
                wait(0.025)
            }
            flow.pager.endDrag(predictedProgress = -0.6f)
            wait(1.4)
            for (step in 1..4) {
                flow.pager.dragProgress = -0.1f * step / 4
                wait(0.025)
            }
            flow.pager.endDrag(predictedProgress = -0.9f)
            wait(1.4)
            // A short drag that does not commit settles back.
            for (step in 1..8) {
                flow.pager.dragProgress = -0.15f * step / 8
                wait(0.025)
            }
            flow.pager.endDrag(predictedProgress = -0.2f)
            return
        }
        if (state == "demo-restore") {
            flow.startRestore()
            wait(1.6)
            flow.restoreFinished()
        } else {
            flow.pager.advance() // 1.1 → 1.2 by the button
            wait(2.0)
            // 1.2 → 1.3 by a swipe: the finger drags 45 % across, then lifts.
            for (step in 1..12) {
                flow.pager.dragProgress = 0.45f * step / 12
                wait(0.025)
            }
            flow.pager.endDrag(predictedProgress = 0.7f)
            wait(2.0)
            flow.startSetup()
            wait(1.4)
            for (character in "selin") {
                flow.username.text += character
                wait(0.16)
            }
            wait(1.6) // “Alınmış”
            for (character in "_n") {
                flow.username.text += character
                wait(0.16)
            }
            wait(1.4) // “Kullanılabilir”
            flow.usernameContinue()
            wait(1.2)
        }
        type("111111") // weak
        wait(1.6)
        type("246813") // → confirm
        wait(0.8)
        type("246814") // mismatch → set
        wait(1.8)
        type("246813")
        wait(0.8)
        type("246813") // → 2.3
        wait(1.6)
        flow.finish.acknowledged = true
        wait(1.0)
        flow.finish.submit()
    }
}
