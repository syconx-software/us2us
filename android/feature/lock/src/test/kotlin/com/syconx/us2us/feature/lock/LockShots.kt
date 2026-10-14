package com.syconx.us2us.feature.lock

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.DesignClock
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.LaunchConfig
import com.syconx.us2us.domain.nav.MessageId
import com.syconx.us2us.domain.nav.Overlay
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
import com.syconx.us2us.ui.security.PrivacyCover
import com.syconx.us2us.ui.theme.U2Theme
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** The stand-in for 7.3 (settings' screen) in lock tests. */
const val RESTORE_SCREEN_TAG = "test.restore"

/** A wall clock and a boot clock the test moves (the lock's waits run on them, not on the design clock). */
class TestClocks(start: Instant = Instant.parse("2026-10-08T15:52:00Z")) {
    var now: Instant = start
    private val bootStart = start

    val wallClock: Clock = object : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    fun sinceBoot(): Duration = java.time.Duration.between(bootStart, now).toNanos().nanoseconds

    fun advance(seconds: Long) {
        now = now.plusSeconds(seconds)
    }
}

/** The locked fixtures app: an account, the design's data on the frozen design clock, no latency. */
class LockFixture(english: Boolean = false, val clocks: TestClocks? = null, configuration: LaunchConfig = LaunchConfig.Release) {
    val service = if (clocks != null) {
        FixturesMessengerService(
            hasAccount = true,
            clock = DesignClock.frozen(DesignClock.referenceDate),
            english = english,
            latency = Duration.ZERO,
            bootClock = clocks::sinceBoot,
            wallClock = clocks.wallClock,
        )
    } else {
        FixturesMessengerService(hasAccount = true, clock = DesignClock.frozen(DesignClock.referenceDate), english = english, latency = Duration.ZERO)
    }
    val model = PreviewAppModel(messenger = service, clock = service.clock, configuration = configuration, phase = Phase.Locked).also {
        it.overlay = Overlay.Lock
    }
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
 * The lock as the app's security layer shows it (the cover's base, then the lock flow), in the
 * theme and language of the shot: [state]'s harness state prepared and applied first.
 */
@Composable
fun LockApp(
    state: String? = null,
    reduceMotion: Boolean = false,
    clocks: TestClocks? = null,
    configuration: LaunchConfig = LaunchConfig.Release,
    onFixture: (LockFixture) -> Unit = {},
) {
    val spec: ShotSpec = checkNotNull(LocalShotSpec.current) { "render through ShotHost" }
    val fixture = remember { LockFixture(english = spec.lang == "en", clocks = clocks, configuration = configuration).also(onFixture) }
    val plan = remember {
        state?.let { LockHarness.plan(null, it, fixture.service) ?: error("no harness state $it") }
            ?.also { plan -> runBlocking { plan.prepare?.invoke() } }
    }
    val scope = rememberCoroutineScope()
    U2PreviewRoot(
        appModel = fixture.model,
        dark = spec.isDark,
        designInsets = spec.designInsets,
        reduceMotion = reduceMotion,
        harnessState = plan?.uiState,
        screens = RestoreStandIn,
    ) {
        Box(Modifier.fillMaxSize()) {
            PrivacyCover(snapshot = null, showsMark = false)
            LockFlow(
                onUnlocked = fixture.model::didUnlock,
                onRestore = fixture.model::continueAfterRestore,
                onErased = { scope.launch { fixture.model.eraseEverything() } },
            )
        }
    }
}

/** The node tagged [tag] is at [x], [y] with size [w] × [h] (dp), within [tolerance]. */
fun SemanticsNodeInteractionsProvider.assertAt(tag: String, x: Float, y: Float, w: Float, h: Float, tolerance: Dp = 1.dp) {
    val expected = Frame(x, y, w, h)
    val actual = measuredFrame(tag)
    if (expected.distanceTo(actual) > tolerance.value + 0.001f) throw AssertionError("$tag: expected [$expected], got [$actual]")
}
