package com.syconx.us2us.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.DesignClock
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.MessageId
import com.syconx.us2us.domain.nav.OnboardingEntry
import com.syconx.us2us.domain.nav.Phase
import com.syconx.us2us.domain.nav.QrScanMode
import com.syconx.us2us.domain.nav.RestoreMode
import com.syconx.us2us.fixtures.FixturesMessengerService
import com.syconx.us2us.shot.Frame
import com.syconx.us2us.shot.LocalShotSpec
import com.syconx.us2us.shot.ShotSpec
import com.syconx.us2us.shot.measuredFrame
import com.syconx.us2us.ui.env.FeatureScreens
import com.syconx.us2us.ui.env.PreviewAppModel
import com.syconx.us2us.ui.env.U2PreviewRoot
import com.syconx.us2us.ui.theme.U2Theme
import kotlin.time.Duration

/** The stand-in for 7.3 (settings' screen) in onboarding tests. */
const val RESTORE_SCREEN_TAG = "test.restore"

/** The fixtures app onboarding runs in: no account yet, the design's data on the frozen design clock, no latency. */
class OnboardingFixture(english: Boolean = false, hasAccount: Boolean = false) {
    val service = FixturesMessengerService(
        hasAccount = hasAccount,
        clock = DesignClock.frozen(DesignClock.referenceDate),
        english = english,
        latency = Duration.ZERO,
    )
    val model = PreviewAppModel(messenger = service, clock = service.clock, phase = Phase.Onboarding)

    /** The flow, once the screen has made it. */
    var flow: OnboardingFlowModel? = null
}

/** 7.3 as a plain stand-in: the background and a tag. */
object RestoreStandIn : FeatureScreens {
    @Composable
    override fun RestoreBackup(mode: RestoreMode, onClose: () -> Unit, onFinished: () -> Unit, modifier: Modifier) {
        Box(modifier.fillMaxSize().background(U2Theme.colors.bg).testTag(RESTORE_SCREEN_TAG))
    }

    @Composable
    override fun QrScan(mode: QrScanMode, onDismiss: () -> Unit, modifier: Modifier) = Unit

    @Composable
    override fun Invite(state: String?, onClose: () -> Unit, modifier: Modifier) = Unit

    @Composable
    override fun MediaViewer(chat: ChatId, message: MessageId, onDismiss: () -> Unit, modifier: Modifier) = Unit

    @Composable
    override fun VideoPlayer(chat: ChatId, message: MessageId, onDismiss: () -> Unit, modifier: Modifier) = Unit

    @Composable
    override fun FilePreview(chat: ChatId, message: MessageId, onDismiss: () -> Unit, modifier: Modifier) = Unit
}

/**
 * Onboarding as the app shows it, in the theme and language of the shot: [state]'s harness state
 * applied first (`2.1-taken`, `restore-2.3-lock` …), or the flow from [entry].
 */
@Composable
fun OnboardingApp(
    state: String? = null,
    entry: OnboardingEntry = OnboardingEntry.Welcome,
    reduceMotion: Boolean = false,
    onFixture: (OnboardingFixture) -> Unit = {},
) {
    val spec: ShotSpec = checkNotNull(LocalShotSpec.current) { "render through ShotHost" }
    val fixture = remember { OnboardingFixture(english = spec.lang == "en").also(onFixture) }
    val plan = remember { state?.let { OnboardingHarness.plan(null, it, fixture.service) } }
    if (state != null) check(plan != null) { "no harness state $state" }
    U2PreviewRoot(
        appModel = fixture.model,
        dark = spec.isDark,
        designInsets = spec.designInsets,
        reduceMotion = reduceMotion,
        harnessState = plan?.uiState,
        screens = RestoreStandIn,
    ) {
        OnboardingFlow(entry = entry, onFinished = fixture.model::didFinishOnboarding)
    }
}

/** The node tagged [tag] is at [x], [y] with size [w] × [h] (dp), within [tolerance]. */
fun SemanticsNodeInteractionsProvider.assertAt(tag: String, x: Float, y: Float, w: Float, h: Float, tolerance: Dp = 1.dp) {
    val expected = Frame(x, y, w, h)
    val actual = measuredFrame(tag)
    val distance = expected.distanceTo(actual)
    if (distance > tolerance.value + 0.001f) throw AssertionError("$tag: expected [$expected], got [$actual]")
}
