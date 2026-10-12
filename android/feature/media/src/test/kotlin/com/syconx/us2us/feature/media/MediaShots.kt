package com.syconx.us2us.feature.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import com.syconx.us2us.domain.DesignClock
import com.syconx.us2us.domain.MessengerService
import com.syconx.us2us.domain.harness.HarnessStateId
import com.syconx.us2us.fixtures.FixturesMessengerService
import androidx.compose.ui.test.onAllNodesWithTag
import com.syconx.us2us.shot.ShotEnvironment
import com.syconx.us2us.shot.ShotHost
import com.syconx.us2us.shot.ShotSpec
import com.syconx.us2us.ui.env.LocalU2Clock
import com.syconx.us2us.ui.env.MemoryPreferences
import com.syconx.us2us.ui.env.PreviewAppModel
import com.syconx.us2us.ui.env.U2PreviewRoot
import com.syconx.us2us.ui.media.BitmapDesignArtworkEncoder
import java.time.Instant
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The media module's shot environment: the theme, a fixtures service on the design's frozen
 * moment (with the bitmap artwork encoder, so design photos have bytes), the app model, the
 * harness state the conversation harness would hand over ([state]), and an overlay host.
 */
internal class MediaShotEnvironment : ShotEnvironment {
    /** The harness state id of the next render (`5.6`, `5.1-viewer-zoomed` …), `null` for none. */
    var state: String? = null

    /** Set up the service before the screen's first frame (fixture knobs). */
    var prepare: (FixturesMessengerService) -> Unit = {}

    /** Wraps the service the screen sees (a stand-in for one call); the fixtures by default. */
    var wrap: (FixturesMessengerService) -> MessengerService = { it }

    /** The service of the screen on show. */
    lateinit var service: FixturesMessengerService
        private set

    lateinit var appModel: PreviewAppModel
        private set

    @Composable
    override fun Provide(spec: ShotSpec, content: @Composable () -> Unit) {
        val clock = remember { DesignClock.frozen(Instant.ofEpochMilli(spec.nowMillis)) }
        val messenger = remember {
            FixturesMessengerService(
                hasAccount = true,
                clock = clock,
                english = spec.lang == "en",
                latency = Duration.ZERO,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            ).also {
                it.artworkEncoder = BitmapDesignArtworkEncoder()
                prepare(it)
                service = it
            }
        }
        val model = remember { PreviewAppModel(wrap(messenger), clock = clock, preferences = MemoryPreferences()).also { appModel = it } }
        val harness = remember(state) { state?.let(::HarnessStateId) }
        U2PreviewRoot(model, dark = spec.isDark, designInsets = spec.designInsets, harnessState = harness) {
            CompositionLocalProvider(LocalU2Clock provides clock) { content() }
        }
    }
}

/**
 * Moves the paused clock frame by frame (and real time a little, for work on background threads:
 * decoding, rendering the artwork) until [condition] holds or [timeoutMillis] of real time passed.
 */
internal fun ShotHost.settle(timeoutMillis: Long = 5_000, condition: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < end) {
        advance(16)
        if (condition()) {
            advance(400)
            return
        }
        Thread.sleep(5)
    }
    throw AssertionError("the screen did not settle within $timeoutMillis ms")
}

/** Whether a node tagged [tag] exists (in the unmerged tree). */
internal fun ShotHost.has(tag: String): Boolean =
    compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
