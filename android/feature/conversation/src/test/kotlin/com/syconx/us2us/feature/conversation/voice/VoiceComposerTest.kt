package com.syconx.us2us.feature.conversation.voice

import com.syconx.us2us.domain.Bytes
import com.syconx.us2us.domain.SlideOffset
import com.syconx.us2us.domain.VoiceNoteRules
import com.syconx.us2us.domain.VoiceRecording
import com.syconx.us2us.domain.VoiceWaveform
import com.syconx.us2us.ui.bubbles.VoiceAudioEngine
import com.syconx.us2us.ui.composer.ComposerVoiceMode
import com.syconx.us2us.ui.composer.VoiceCapture
import com.syconx.us2us.ui.composer.VoiceCaptureDevice
import com.syconx.us2us.ui.composer.VoicePermission
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The composer's voice recorder on a fake microphone (iOS `VoiceComposerTests`). */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceComposerTest {
    private class FakeDevice(override var permission: VoicePermission = VoicePermission.Granted) : VoiceCaptureDevice {
        var grants = true
        var failsToStart = false
        var permissionRequests = 0
        val captures = mutableListOf<FakeCapture>()

        override suspend fun requestPermission(): Boolean {
            permissionRequests++
            permission = if (grants) VoicePermission.Granted else VoicePermission.Denied
            return grants
        }

        override fun makeCapture(): VoiceCapture = FakeCapture(failsToStart).also { captures += it }
    }

    private class FakeCapture(private val failsToStart: Boolean) : VoiceCapture {
        override var onInterrupted: (() -> Unit)? = null
        override var currentTime: Duration = Duration.ZERO

        /** The recorder stopped underneath (a full disk, an encoder error). */
        var stopsBySystem = false

        /** What `finish()` reads back (`null`: nothing readable was recorded). */
        var audio: Bytes? = Bytes.utf8("aac")
        var started = false
        var finished = false
        var discarded = false

        override val isRecording: Boolean get() = started && !finished && !discarded && !stopsBySystem

        override fun start(): Boolean {
            if (failsToStart) return false
            started = true
            return true
        }

        override fun level(): Double = 0.5

        override fun finish(): Bytes? {
            finished = true
            return audio
        }

        override fun discard() {
            discarded = true
        }
    }

    private class Rig(scope: TestScope, val device: FakeDevice = FakeDevice()) {
        val sent = mutableListOf<VoiceRecording>()
        val player = VoicePlayback(VoiceAudioEngine.Unavailable, scope.backgroundScope)
        val composer = VoiceComposer(player, scope.backgroundScope, device).also { it.onSend = { recording -> sent += recording } }
    }

    /** Presses the mic and waits out the hold delay. */
    private fun TestScope.hold(rig: Rig): FakeCapture {
        rig.composer.touchChanged(SlideOffset.Zero)
        advanceTimeBy(VoiceNoteRules.holdDelay + 1.milliseconds)
        runCurrent()
        assertEquals(ComposerVoiceMode.Holding, rig.composer.mode)
        return rig.device.captures.first()
    }

    @Test
    fun aTapShowsTheHintAndNeverOpensTheMicrophone() = runTest {
        val rig = Rig(this)
        rig.composer.touchChanged(SlideOffset.Zero)
        rig.composer.touchEnded()
        assertEquals(VoiceComposer.Hint.Hold, rig.composer.hint)
        advanceTimeBy(350.milliseconds)
        assertTrue("released before the hold delay", rig.device.captures.isEmpty())
        assertEquals(ComposerVoiceMode.Idle, rig.composer.mode)
        assertTrue(rig.sent.isEmpty())
    }

    @Test
    fun holdingRecordsAndReleasingSends() = runTest {
        val rig = Rig(this)
        val capture = hold(rig)
        assertTrue(capture.started)
        assertTrue(rig.composer.isRecording)
        capture.currentTime = 3.2.seconds
        advanceTimeBy(250.milliseconds)
        assertTrue(rig.composer.levels.isNotEmpty())
        rig.composer.touchEnded()
        assertEquals(ComposerVoiceMode.Idle, rig.composer.mode)
        assertTrue(capture.finished)
        val recording = rig.sent.single()
        assertEquals(3.2.seconds, recording.duration)
        assertEquals(Bytes.utf8("aac"), recording.audio)
        assertFalse(recording.waveform.isEmpty())
        assertTrue(recording.waveform.size <= VoiceWaveform.MAX_SAMPLES)
    }

    @Test
    fun underASecondIsThrownAwayWithTheHint() = runTest {
        val rig = Rig(this)
        val capture = hold(rig)
        capture.currentTime = 0.5.seconds
        rig.composer.touchEnded()
        assertTrue(rig.sent.isEmpty())
        assertTrue(capture.discarded)
        assertEquals(VoiceComposer.Hint.Hold, rig.composer.hint)
        assertEquals(ComposerVoiceMode.Idle, rig.composer.mode)
    }

    @Test
    fun slidingLeftCancels() = runTest {
        val rig = Rig(this)
        val capture = hold(rig)
        capture.currentTime = 4.seconds
        rig.composer.touchChanged(SlideOffset(-60f, 0f))
        assertEquals(ComposerVoiceMode.Holding, rig.composer.mode)
        assertEquals(-60f, rig.composer.micOffset.x.value)
        rig.composer.touchChanged(SlideOffset(-VoiceNoteRules.CANCEL_DISTANCE - 5, 0f))
        assertEquals(ComposerVoiceMode.Idle, rig.composer.mode)
        assertTrue(capture.discarded)
        rig.composer.touchEnded()
        assertTrue("the release after cancelling sends nothing", rig.sent.isEmpty())
    }

    @Test
    fun slidingUpLocksThenStopReviewsAndSendSends() = runTest {
        val rig = Rig(this)
        val capture = hold(rig)
        capture.currentTime = 6.seconds
        rig.composer.touchChanged(SlideOffset(0f, -VoiceNoteRules.LOCK_DISTANCE))
        assertEquals(ComposerVoiceMode.Locked, rig.composer.mode)
        rig.composer.touchEnded()
        assertEquals("the finger is free", ComposerVoiceMode.Locked, rig.composer.mode)
        assertTrue(rig.sent.isEmpty())
        rig.composer.stop()
        assertEquals(ComposerVoiceMode.Review, rig.composer.mode)
        assertTrue(capture.finished)
        assertEquals(6.seconds, rig.composer.recording?.duration)
        rig.composer.send()
        assertEquals(ComposerVoiceMode.Idle, rig.composer.mode)
        assertEquals(6.seconds, rig.sent.single().duration)
    }

    @Test
    fun lockedItCanBeDeleted() = runTest {
        val rig = Rig(this)
        val capture = hold(rig)
        rig.composer.touchChanged(SlideOffset(0f, -200f))
        assertEquals(ComposerVoiceMode.Locked, rig.composer.mode)
        rig.composer.cancel()
        assertEquals(ComposerVoiceMode.Idle, rig.composer.mode)
        assertTrue(capture.discarded)
        assertTrue(rig.sent.isEmpty())
    }

    @Test
    fun atFiveMinutesRecordingStopsAndTheReleaseSendsIt() = runTest {
        val rig = Rig(this)
        val capture = hold(rig)
        capture.currentTime = VoiceNoteRules.maximumDuration
        advanceTimeBy(150.milliseconds)
        assertFalse(rig.composer.isCapturing)
        assertTrue(capture.finished)
        assertEquals("still held: it waits for the release", ComposerVoiceMode.Holding, rig.composer.mode)
        rig.composer.touchEnded()
        assertEquals(VoiceNoteRules.maximumDuration, rig.sent.single().duration)
    }

    @Test
    fun lockedAtFiveMinutesItWaitsInReview() = runTest {
        val rig = Rig(this)
        val capture = hold(rig)
        rig.composer.touchChanged(SlideOffset(0f, -200f))
        rig.composer.touchEnded()
        capture.currentTime = VoiceNoteRules.maximumDuration + 2.seconds
        advanceTimeBy(150.milliseconds)
        assertEquals(ComposerVoiceMode.Review, rig.composer.mode)
        assertEquals(VoiceNoteRules.maximumDuration, rig.composer.recording?.duration)
        assertTrue(rig.sent.isEmpty())
    }

    @Test
    fun aCallKeepsWhatWasRecordedForReview() = runTest {
        val rig = Rig(this)
        val capture = hold(rig)
        capture.currentTime = 2.5.seconds
        capture.onInterrupted?.invoke()
        assertEquals(ComposerVoiceMode.Review, rig.composer.mode)
        rig.composer.touchEnded()
        assertTrue("the release after the interruption sends nothing by itself", rig.sent.isEmpty())
        assertEquals(ComposerVoiceMode.Review, rig.composer.mode)
    }

    @Test
    fun aRecorderThatStopsByItselfKeepsWhatWasRecordedForReview() = runTest {
        val rig = Rig(this)
        val capture = hold(rig)
        capture.currentTime = 3.seconds
        capture.stopsBySystem = true
        advanceTimeBy(150.milliseconds)
        assertEquals(ComposerVoiceMode.Review, rig.composer.mode)
        assertTrue(capture.finished)
        assertEquals(3.seconds, rig.composer.recording?.duration)
        rig.composer.touchEnded()
        assertTrue(rig.sent.isEmpty())
    }

    @Test
    fun anInterruptionWithNothingReadableSaysRecordingFailed() = runTest {
        val rig = Rig(this)
        val capture = hold(rig)
        capture.currentTime = 2.seconds
        capture.audio = null
        capture.onInterrupted?.invoke()
        assertEquals(ComposerVoiceMode.Idle, rig.composer.mode)
        assertEquals(VoiceComposer.Hint.Unavailable, rig.composer.hint)
        rig.composer.touchEnded()
        assertTrue(rig.sent.isEmpty())
    }

    @Test
    fun aPlaybackOrSendingProblemIsSaidAboveTheMicButNotOverARecording() = runTest {
        val rig = Rig(this)
        rig.composer.report(VoiceComposer.Hint.SendFailed)
        assertEquals(VoiceComposer.Hint.SendFailed, rig.composer.hint)
        hold(rig)
        rig.composer.report(VoiceComposer.Hint.Unplayable)
        assertNull("the press hid the hint; nothing shows over the recording", rig.composer.hint)
        rig.composer.discardAll()
    }

    @Test
    fun theHintGoesAfterTwoSeconds() = runTest {
        val rig = Rig(this)
        rig.composer.showDemoHint()
        assertTrue(rig.composer.showsHint)
        advanceTimeBy(2.3.seconds)
        assertFalse(rig.composer.showsHint)
    }

    @Test
    fun aTouchTheSystemTakesKeepsTheRecordingForReview() = runTest {
        val rig = Rig(this)
        val capture = hold(rig)
        capture.currentTime = 2.seconds
        rig.composer.touchFinished()
        runCurrent()
        assertEquals(ComposerVoiceMode.Review, rig.composer.mode)
        assertTrue(rig.sent.isEmpty())
    }

    @Test
    fun leavingThrowsTheRecordingAway() = runTest {
        val rig = Rig(this)
        val capture = hold(rig)
        rig.composer.discardAll()
        assertEquals(ComposerVoiceMode.Idle, rig.composer.mode)
        assertTrue(capture.discarded)
        assertTrue(rig.composer.levels.isEmpty())
        rig.composer.touchEnded()
        assertTrue(rig.sent.isEmpty())
    }

    @Test
    fun aRefusedMicrophoneLeadsToSettings() = runTest {
        val rig = Rig(this, FakeDevice(VoicePermission.Denied))
        rig.composer.touchChanged(SlideOffset.Zero)
        rig.composer.touchEnded()
        assertTrue(rig.composer.showsPermissionNotice)
        assertTrue(rig.device.captures.isEmpty())
    }

    @Test
    fun theFirstPressAsksForTheMicrophone() = runTest {
        val rig = Rig(this, FakeDevice(VoicePermission.Undetermined))
        rig.composer.touchChanged(SlideOffset.Zero)
        rig.composer.touchEnded()
        runCurrent()
        assertEquals(1, rig.device.permissionRequests)
        assertEquals(VoiceComposer.Hint.Hold, rig.composer.hint)
        assertTrue("asking records nothing; the next press does", rig.device.captures.isEmpty())
        val refusing = Rig(this, FakeDevice(VoicePermission.Undetermined).apply { grants = false })
        refusing.composer.touchChanged(SlideOffset.Zero)
        refusing.composer.touchEnded()
        runCurrent()
        assertTrue(refusing.composer.showsPermissionNotice)
    }

    @Test
    fun aMicrophoneThatWillNotStartSaysSo() = runTest {
        val rig = Rig(this, FakeDevice().apply { failsToStart = true })
        rig.composer.touchChanged(SlideOffset.Zero)
        advanceTimeBy(VoiceNoteRules.holdDelay + 1.milliseconds)
        runCurrent()
        assertEquals(VoiceComposer.Hint.Unavailable, rig.composer.hint)
        assertEquals(ComposerVoiceMode.Idle, rig.composer.mode)
        rig.composer.touchEnded()
        assertEquals(ComposerVoiceMode.Idle, rig.composer.mode)
    }

    @Test
    fun screenReadersRecordHandsFree() = runTest {
        val rig = Rig(this)
        rig.composer.startHandsFree()
        assertEquals(ComposerVoiceMode.Locked, rig.composer.mode)
        rig.device.captures.single().currentTime = 2.seconds
        rig.composer.send()
        assertEquals(2.seconds, rig.sent.single().duration)
    }

    @Test
    fun theReviewDemoKeepsAPlayableRecording() = runTest {
        val rig = Rig(this)
        rig.composer.showDemo(ComposerVoiceMode.Review, 4.seconds)
        val recording = assertNotNull(rig.composer.recording)
        assertEquals(4.seconds, recording.duration)
        // A WAV stand-in: “RIFF” … “WAVE”.
        val bytes = recording.audio.toByteArray()
        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals("WAVE", String(bytes, 8, 4))
        assertEquals(ComposerVoiceMode.Review, rig.composer.mode)
        // The preview plays through the conversation's player: without an engine it fails and says so.
        rig.composer.togglePreview()
        assertNull(rig.player.activeId)
        rig.composer.cancel()
        assertEquals(ComposerVoiceMode.Idle, rig.composer.mode)
        assertNull(rig.composer.recording)
    }

    private fun <T> assertNotNull(value: T?): T {
        assertNotNull(null, value)
        return value!!
    }
}
