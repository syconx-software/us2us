package com.syconx.us2us.feature.lock

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.syconx.us2us.domain.Biometry
import com.syconx.us2us.domain.design.DesignFixtures
import com.syconx.us2us.domain.harness.FeatureHarness
import com.syconx.us2us.domain.harness.FixtureControls
import com.syconx.us2us.domain.harness.HarnessPlan
import com.syconx.us2us.domain.harness.HarnessUiState
import com.syconx.us2us.domain.nav.LaunchRoute
import com.syconx.us2us.domain.nav.StartScreen
import com.syconx.us2us.ui.security.PrivacyCover
import com.syconx.us2us.ui.security.PrivacySwitcherMock
import com.syconx.us2us.ui.security.rememberPrivacySkeletonSnapshot
import kotlinx.coroutines.delay

/**
 * Debug harness for 03 · Kilit: opens every lock screen and state directly, in its exact design
 * state, for screenshots and design comparisons (`U2Route lock:<state>`; the lock route always
 * starts locked).
 *
 * | `lock:` | design | state |
 * |---|---|---|
 * | `faceid` | 3.1 | Face ID scanning (rings frozen 2 s into the loop with the frozen clock) |
 * | `touchid` | 3.1 | the fingerprint variant |
 * | `code` | 3.2 | idle: empty dots, no sentence |
 * | `typing` | 3.2 | four digits entered |
 * | `wrong` | 3.2 | the reference: first wrong code — red dots + “2 yanlış daha …” |
 * | `lastchance` | 3.2 | second wrong code — “1 yanlış daha …” |
 * | `throttled` | 3.2 | third wrong code — dimmed dots, keypad at 45 %, “… 01:00 sonra tekrar dene.” |
 * | `unavailable` | 3.2 | not a wrong code: the vault could not be read right now (proposed) |
 * | `unusable` | 3.2 | not a wrong code: this phone can no longer open the vault (proposed) |
 * | `wiped` | 3.2 › | the optional wipe fired: “Veriler silindi.” with restore / start over (proposed) |
 * | `forgot` | 3.3 | the floating sheet |
 * | `erase` | 3.3 | the second confirmation |
 * | `restore` | 3.3 › 7.3 | the restore hand-off inside the lock |
 * | `cover` | 3.4 | the privacy cover, full size, over the blurred demo screen |
 * | `switcher` | 3.4 | the whole app-switcher mock (system context + the cover at 0.7015) |
 *
 * The harness never evaluates biometrics and holds the drawn state (no automatic transitions).
 */
object LockHarness : FeatureHarness {
    override val feature: LaunchRoute.Feature = LaunchRoute.Feature.Lock

    override val states: List<String> = LockHarnessState.Screen.entries.map { it.raw }

    override fun plan(target: String?, state: String?, controls: FixtureControls?): HarnessPlan? {
        val screen = state?.let(LockHarnessState.Screen::fromRaw) ?: return null
        val harness = LockHarnessState(screen)
        return HarnessPlan(
            start = StartScreen.Locked,
            uiState = harness,
            prepare = controls?.let { { harness.prepare(it) } },
        )
    }
}

/**
 * Every harness of this module. The app's debug registry reads this list, so a harness added
 * here (one per route feature) is found without changing the app.
 */
val lockHarnesses: List<FeatureHarness> = listOf(LockHarness)

/** A lock harness state: what the service needs first ([prepare]) and what the flow shows ([apply]). */
data class LockHarnessState(val screen: Screen) : HarnessUiState {
    enum class Screen(val raw: String) {
        FaceId("faceid"),
        TouchId("touchid"),
        Code("code"),
        Typing("typing"),
        Wrong("wrong"),
        LastChance("lastchance"),
        Throttled("throttled"),
        Unavailable("unavailable"),
        Unusable("unusable"),
        Wiped("wiped"),
        Forgot("forgot"),
        Erase("erase"),
        Restore("restore"),
        Cover("cover"),
        Switcher("switcher"),
        ;

        companion object {
            fun fromRaw(raw: String): Screen? = entries.firstOrNull { it.raw == raw }
        }
    }

    override val state: String get() = screen.raw

    /** 3.4 draws over the lock. */
    val replacesLock: Boolean get() = screen == Screen.Cover || screen == Screen.Switcher

    /** The design draws the Face ID variant (keypad key included). */
    val biometry: Biometry get() = if (screen == Screen.TouchId) Biometry.Fingerprint else Biometry.Face

    /** Wrong codes entered before the one the flow types (`wrong` 0, `lastchance` 1, `throttled` 2). */
    private val earlierWrongCodes: Int
        get() = when (screen) {
            Screen.LastChance -> 1
            Screen.Throttled -> 2
            else -> 0
        }

    /** Before the first frame: the phone's biometrics and the wrong codes so far. */
    fun prepare(controls: FixtureControls) {
        controls.biometry = biometry
        if (screen == Screen.Wrong || screen == Screen.LastChance || screen == Screen.Throttled) {
            controls.setWrongCodes(earlierWrongCodes)
        }
    }

    fun apply(flow: LockFlowModel) {
        val code = flow.code
        code.isFrozenForHarness = true
        when (screen) {
            Screen.FaceId, Screen.TouchId -> flow.setForHarness(LockFlowModel.Stage.Biometric)
            Screen.Code, Screen.Cover, Screen.Switcher -> flow.setForHarness(LockFlowModel.Stage.Code)
            Screen.Typing -> {
                flow.setForHarness(LockFlowModel.Stage.Code)
                code.setEnteredForHarness("2468")
            }
            Screen.Wrong, Screen.LastChance, Screen.Throttled -> {
                flow.setForHarness(LockFlowModel.Stage.Code)
                code.holdsErrorState = screen != Screen.Throttled
                for (digit in WRONG_CODE) code.type(digit.digitToInt())
            }
            Screen.Unavailable, Screen.Unusable -> {
                flow.setForHarness(LockFlowModel.Stage.Code)
                code.setMessageForHarness(if (screen == Screen.Unusable) LockCodeModel.Message.Unusable else LockCodeModel.Message.Unavailable)
            }
            Screen.Wiped -> flow.setForHarness(LockFlowModel.Stage.Wiped)
            Screen.Forgot -> flow.setForHarness(LockFlowModel.Stage.Code, LockFlowModel.ForgotStep.Options)
            Screen.Erase -> flow.setForHarness(LockFlowModel.Stage.Code, LockFlowModel.ForgotStep.ConfirmErase)
            Screen.Restore -> flow.setForHarness(LockFlowModel.Stage.Restore(null))
        }
    }

    companion object {
        /** The fixtures' wrong code (any six digits but the demo code). */
        const val WRONG_CODE = "111112"
    }
}

/**
 * `U2LockDemo`: `forgot` — on 3.2, “Kodu unuttum” opens the 3.3 sheet after 1.5 s and “Tekrar dene”
 * closes it 2.5 s later (recording the sheet's rise and dismissal); `unlock` — types the demo code.
 */
internal suspend fun runLockDemo(demo: String?, flow: LockFlowModel) {
    when (demo) {
        "forgot" -> {
            delay(1_500)
            flow.openForgot()
            delay(2_500)
            flow.closeForgot()
        }
        "unlock" -> {
            delay(2_500)
            for (digit in DesignFixtures.DEMO_SECRET_CODE) {
                flow.code.type(digit.digitToInt())
                delay(180)
            }
        }
        else -> Unit
    }
}

/** 3.4 in the harness: the cover over the blurred demo screen, alone or inside the app-switcher mock. */
@Composable
internal fun LockHarnessScreen(state: LockHarnessState, modifier: Modifier = Modifier) {
    val snapshot = rememberPrivacySkeletonSnapshot()
    if (state.screen == LockHarnessState.Screen.Switcher) {
        PrivacySwitcherMock(snapshot, modifier)
    } else {
        PrivacyCover(snapshot, modifier)
    }
}
