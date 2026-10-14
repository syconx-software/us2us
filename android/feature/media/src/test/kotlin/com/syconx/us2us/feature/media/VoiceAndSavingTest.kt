package com.syconx.us2us.feature.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.syconx.us2us.domain.Bytes
import com.syconx.us2us.domain.DesignClock
import com.syconx.us2us.domain.DisappearingTimer
import com.syconx.us2us.domain.MediaPayload
import com.syconx.us2us.domain.MessageContent
import com.syconx.us2us.domain.SaveNoticeKind
import com.syconx.us2us.domain.SlideOffset
import com.syconx.us2us.domain.VoiceRecording
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.MessageId
import com.syconx.us2us.fixtures.FixturesMessengerService
import com.syconx.us2us.ui.env.U2Haptics
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The voice recorder's hold · lock · review · send (iOS `VoiceComposer`), the voice player's one
 * message at a time and its remembered positions, and `MediaSaver`'s rules: the one-time
 * explanation, what may leave the app, the SAVED receipt.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VoiceAndSavingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    // MARK: Fakes

    private class FakeCapture(var bytes: Bytes? = Bytes.utf8("aac")) : VoiceCapture {
        var started = false
        var discarded = false
        var recorded: Duration = Duration.ZERO
        var recording = true
        override var onInterrupted: (() -> Unit)? = null

        override fun start(): Boolean {
            started = true
            return true
        }

        override val currentTime: Duration get() = recorded
        override val isRecording: Boolean get() = recording

        override fun level(): Double = 0.5

        override fun finish(): Bytes? = bytes

        override fun discard() {
            discarded = true
        }
    }

    private class FakeDevice(var granted: Boolean = true, var answer: Boolean = true) : VoiceCaptureDevice {
        val captures = mutableListOf<FakeCapture>()
        var asked = 0
        override val permission: VoicePermission get() = if (granted) VoicePermission.Granted else VoicePermission.Undetermined

        override suspend fun requestPermission(): Boolean {
            asked++
            return answer
        }

        override fun makeCapture(): VoiceCapture = FakeCapture().also { captures += it }
    }

    private class FakeVoiceEngine(override val duration: Double = 10.0, private val loads: Boolean = true) : VoicePlayerEngine {
        override var position: Double = 0.0
        override var isPlaying: Boolean = false
        var released = false
        var speed = 1f
        override var onFinish: (() -> Unit)? = null
        override var onFailure: (() -> Unit)? = null

        override fun load(payload: MediaPayload): Boolean = loads

        override fun play(): Boolean {
            isPlaying = true
            return true
        }

        override fun pause() {
            isPlaying = false
        }

        override fun setRate(rate: Float) {
            speed = rate
        }

        override fun release() {
            released = true
            isPlaying = false
        }
    }

    private fun TestScope.player(engines: MutableList<FakeVoiceEngine>, loads: Boolean = true) =
        VoicePlayback({ FakeVoiceEngine(loads = loads).also { engines += it } }, scope = CoroutineScope(StandardTestDispatcher(testScheduler)))

    private fun TestScope.recorder(device: FakeDevice, player: VoicePlayback) = VoiceRecorderController(
        context, player, device, U2Haptics.None, {}, CoroutineScope(StandardTestDispatcher(testScheduler)),
    )

    // MARK: Recording

    @Test
    fun holdRecordsAndReleaseSends() = runTest {
        val device = FakeDevice()
        val recorder = recorder(device, player(mutableListOf()))
        val sent = mutableListOf<VoiceRecording>()
        recorder.onSend = { sent += it }
        recorder.touchChanged(SlideOffset.Zero)
        assertEquals(VoiceRecorderController.Mode.Idle, recorder.mode)
        advanceTimeBy(250)
        runCurrent()
        assertEquals(VoiceRecorderController.Mode.Holding, recorder.mode)
        device.captures.single().recorded = 3.seconds
        advanceTimeBy(300)
        runCurrent()
        assertEquals(3.seconds, recorder.elapsed)
        assertTrue(recorder.levels.isNotEmpty())
        recorder.touchEnded()
        assertEquals(VoiceRecorderController.Mode.Idle, recorder.mode)
        assertEquals(3.seconds, sent.single().duration)
        assertEquals(Bytes.utf8("aac"), sent.single().audio)
    }

    @Test
    fun aTapOnlyShowsTheHint() = runTest {
        val device = FakeDevice()
        val recorder = recorder(device, player(mutableListOf()))
        recorder.touchChanged(SlideOffset.Zero)
        recorder.touchEnded()
        assertEquals(VoiceRecorderController.Hint.Hold, recorder.hint)
        assertTrue("the microphone never opened", device.captures.isEmpty())
        advanceTimeBy(2_500)
        runCurrent()
        assertNull(recorder.hint)
    }

    @Test
    fun slidingLeftCancelsAndUpLocks() = runTest {
        val device = FakeDevice()
        val recorder = recorder(device, player(mutableListOf()))
        recorder.touchChanged(SlideOffset.Zero)
        advanceTimeBy(250)
        runCurrent()
        recorder.touchChanged(SlideOffset(-40f, 0f))
        assertEquals(-40f, recorder.micOffset.x)
        recorder.touchChanged(SlideOffset(-120f, 0f))
        assertEquals(VoiceRecorderController.Mode.Idle, recorder.mode)
        assertTrue(device.captures.single().discarded)

        recorder.touchEnded()
        recorder.touchChanged(SlideOffset.Zero)
        advanceTimeBy(250)
        runCurrent()
        recorder.touchChanged(SlideOffset(0f, -90f))
        assertEquals(VoiceRecorderController.Mode.Locked, recorder.mode)
        device.captures.last().recorded = 4.seconds
        recorder.stop()
        assertEquals(VoiceRecorderController.Mode.Review, recorder.mode)
        assertNotNull(recorder.recording)
    }

    @Test
    fun aShortRecordingGoesWithTheHint() = runTest {
        val device = FakeDevice()
        val recorder = recorder(device, player(mutableListOf()))
        val sent = mutableListOf<VoiceRecording>()
        recorder.onSend = { sent += it }
        recorder.touchChanged(SlideOffset.Zero)
        advanceTimeBy(250)
        runCurrent()
        device.captures.single().recorded = 0.5.seconds
        recorder.touchEnded()
        assertTrue(sent.isEmpty())
        assertEquals(VoiceRecorderController.Hint.Hold, recorder.hint)
    }

    @Test
    fun anInterruptionKeepsTheRecordingForReview() = runTest {
        val device = FakeDevice()
        val recorder = recorder(device, player(mutableListOf()))
        recorder.touchChanged(SlideOffset.Zero)
        advanceTimeBy(250)
        runCurrent()
        val capture = device.captures.single()
        capture.recorded = 6.seconds
        capture.onInterrupted?.invoke()
        assertEquals(VoiceRecorderController.Mode.Review, recorder.mode)
        recorder.discardAll()
        assertEquals(VoiceRecorderController.Mode.Idle, recorder.mode)
        assertNull(recorder.recording)
    }

    @Test
    fun aRefusedMicrophoneShowsTheSheet() = runTest {
        val device = FakeDevice(granted = false, answer = false)
        val recorder = recorder(device, player(mutableListOf()))
        recorder.touchChanged(SlideOffset.Zero)
        runCurrent()
        assertEquals(1, device.asked)
        assertTrue(recorder.showsPermissionNotice)
    }

    @Test
    fun theHarnessShowsEveryRecordingState() = runTest {
        val recorder = recorder(FakeDevice(), player(mutableListOf()))
        recorder.showDemo(VoiceRecorderController.Mode.Holding, 7.seconds, SlideOffset(-34f, -18f))
        assertEquals(VoiceRecorderController.Mode.Holding, recorder.mode)
        assertEquals(70, recorder.levels.size)
        assertEquals(-34f, recorder.micOffset.x)
        recorder.showDemo(VoiceRecorderController.Mode.Review, 12.seconds)
        val wav = recorder.recording!!.audio
        assertEquals("RIFF", wav.toByteArray().copyOfRange(0, 4).decodeToString())
    }

    // MARK: Playing

    @Test
    fun oneVoiceMessageAtATimeAndWhereItWasLeftIsKept() = runTest {
        val engines = mutableListOf<FakeVoiceEngine>()
        val playback = player(engines)
        val first = MessageId("d4")
        val second = MessageId("d5")
        val payload = MediaPayload(Bytes.utf8("wav"), "audio/wav", null)
        playback.begin(first)
        assertTrue(playback.isLoading)
        assertTrue(playback.play(payload, first))
        assertTrue(playback.isPlaying)
        engines[0].position = 4.0
        advanceTimeBy(60)
        runCurrent()
        assertEquals(0.4, playback.shownFraction(first, 0.0), 1e-9)
        playback.begin(second)
        assertTrue(engines[0].released)
        assertEquals("left at 40 %", 0.4, playback.shownFraction(first, 0.0), 1e-9)
        assertFalse(playback.play(payload, first))
        assertTrue(playback.play(payload, second))
        playback.cycleRate()
        assertEquals(1.5f, engines[1].speed)
        playback.reset()
        assertEquals(0.0, playback.shownFraction(first, 0.0), 1e-9)
    }

    @Test
    fun unplayableBytesReport() = runTest {
        val playback = player(mutableListOf(), loads = false)
        var failures = 0
        playback.onPlaybackFailure = { failures++ }
        playback.begin(MessageId("d4"))
        assertFalse(playback.play(MediaPayload(Bytes.utf8("x"), "audio/wav", null), MessageId("d4")))
        assertEquals(1, failures)
        assertNull(playback.activeId)
    }

    @Test
    fun aNewIncomingVoiceMessageIsMarkedPlayed() = runTest {
        val service = FixturesMessengerService(hasAccount = true, clock = DesignClock.frozen(DesignClock.referenceDate), latency = Duration.ZERO)
        val chat = ChatId("deniz")
        val id = service.addIncomingVoice(chat)
        val engines = mutableListOf<FakeVoiceEngine>()
        val playback = VoicePlayback({ FakeVoiceEngine().also { engines += it } }, scope = CoroutineScope(StandardTestDispatcher(testScheduler)))
        val message = service.messages(chat).value.first { it.id == id }
        assertFalse(message.isVoicePlayed)
        playback.toggle(message, chat, service)
        // The stand-in WAV is made on a background thread.
        repeat(200) {
            if (!playback.isPlaying) {
                Thread.sleep(10)
                advanceTimeBy(10)
                runCurrent()
            }
        }
        assertTrue(playback.isPlaying)
        // The receipt goes on the app's own scope (main looper).
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertTrue(service.messages(chat).value.first { it.id == id }.isVoicePlayed)
        playback.toggle(service.messages(chat).value.first { it.id == id }, chat, service)
        assertFalse("the same bubble pauses", playback.isPlaying)
    }

    // MARK: Saving

    private val mert = ChatId("mert_k")

    private fun saverWith(service: FixturesMessengerService, memory: SaveNoticeMemory, answer: Boolean, asked: MutableList<Pair<String, SaveNoticeKind>>) =
        MediaSaver(mert, service, memory).apply {
            askNotice = { peer, kind ->
                asked += peer to kind
                answer
            }
            addToGallery = { _, _ -> MediaSaver.GalleryResult.Saved }
            presentExporter = { _, _, _ -> true }
        }

    @Test
    fun theFirstSaveOfSomethingReceivedAsksOnce() = runTest {
        val service = FixturesMessengerService(hasAccount = true, clock = DesignClock.frozen(DesignClock.referenceDate), latency = Duration.ZERO)
        service.artworkEncoder = com.syconx.us2us.ui.media.BitmapDesignArtworkEncoder { Bytes.utf8("clip") }
        val memory = InMemorySaveNoticeMemory()
        val asked = mutableListOf<Pair<String, SaveNoticeKind>>()
        val video = service.messages(mert).value.first { it.id == MessageId("m2") }
        // “Vazgeç”: nothing saved, nothing remembered.
        assertEquals(MediaSaver.Outcome.Cancelled, saverWith(service, memory, false, asked).saveToGallery(video))
        assertFalse(memory.hasAcknowledgedSaveNotice)
        // “Kaydet”: saved, the sender told, remembered.
        assertEquals(MediaSaver.Outcome.Saved, saverWith(service, memory, true, asked).saveToGallery(video))
        assertTrue(memory.hasAcknowledgedSaveNotice)
        assertEquals(listOf("mert_k" to SaveNoticeKind.Video, "mert_k" to SaveNoticeKind.Video), asked)
        assertTrue(service.messages(mert).value.first { it.id == MessageId("m2") }.savedByMe)
        // Never again.
        assertEquals(MediaSaver.Outcome.Saved, saverWith(service, memory, false, asked).saveToGallery(video))
        assertEquals(2, asked.size)
    }

    @Test
    fun disappearingAndViewOnceMediaNeverLeave() = runTest {
        val service = FixturesMessengerService(hasAccount = true, clock = DesignClock.frozen(DesignClock.referenceDate), latency = Duration.ZERO)
        service.setDisappearing(DisappearingTimer.FiveMinutes, MessageId("m1"), mert)
        val asked = mutableListOf<Pair<String, SaveNoticeKind>>()
        val saver = saverWith(service, InMemorySaveNoticeMemory(), true, asked)
        val file = service.messages(mert).value.first { it.id == MessageId("m1") }
        assertEquals(MediaSaver.Outcome.NotAllowed, saver.exportFile(file, File("x.pdf").toURI()))
        val viewOnce = service.messages(mert).value.first { it.content is MessageContent.ViewOnce }
        assertEquals(MediaSaver.Outcome.NotAllowed, saver.saveToGallery(viewOnce))
        assertTrue(asked.isEmpty())
    }

    @Test
    fun myOwnFileIsExportedWithoutAsking() = runTest {
        val service = FixturesMessengerService(hasAccount = true, clock = DesignClock.frozen(DesignClock.referenceDate), latency = Duration.ZERO)
        val asked = mutableListOf<Pair<String, SaveNoticeKind>>()
        val saver = saverWith(service, InMemorySaveNoticeMemory(), false, asked)
        val own = service.messages(mert).value.first { it.id == MessageId("m1") }
        assertTrue(own.isOutgoing)
        assertEquals(MediaSaver.Outcome.Saved, saver.exportFile(own, File("kira-sozlesmesi.pdf").toURI()))
        assertTrue(asked.isEmpty())
        assertEquals("application/pdf", MediaSaver.mimeType(File("kira-sozlesmesi.pdf").toURI()))
    }
}
