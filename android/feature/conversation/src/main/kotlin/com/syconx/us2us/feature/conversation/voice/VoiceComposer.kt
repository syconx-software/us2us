package com.syconx.us2us.feature.conversation.voice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.Bytes
import com.syconx.us2us.domain.MediaPayload
import com.syconx.us2us.domain.SlideOffset
import com.syconx.us2us.domain.VoiceHoldGesture
import com.syconx.us2us.domain.VoiceNoteRules
import com.syconx.us2us.domain.VoiceRecording
import com.syconx.us2us.domain.VoiceWaveform
import com.syconx.us2us.domain.nav.MessageId
import com.syconx.us2us.ui.composer.ComposerVoice
import com.syconx.us2us.ui.composer.ComposerVoiceMode
import com.syconx.us2us.ui.composer.VoiceCapture
import com.syconx.us2us.ui.composer.VoiceCaptureDevice
import com.syconx.us2us.ui.composer.VoicePermission
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The composer's voice recorder (5.1 mic, proposed; WhatsApp / iMessage behaviour; iOS `VoiceComposer`):
 *
 * - press and hold the mic: recording starts after [VoiceNoteRules.holdDelay] (the pulsing dot,
 *   the time, the live waveform, “‹ İptal için kaydır”); release sends; slide left cancels; slide
 *   up locks: recording goes on hands-free with “Sil” · stop · “Gönder”; stop goes to review
 *   (listen, then delete or send);
 * - a tap, or a recording shorter than a second, shows “Kaydetmek için basılı tut” instead; at
 *   five minutes recording stops by itself and the recording waits to be sent;
 * - the microphone is asked for on the first press; refused, a sheet explains it and opens the settings;
 * - an interruption (a call, the recorder stopping by itself) keeps what was recorded for review;
 *   leaving the chat or the foreground throws the recording away ([discardAll]): it is in memory only.
 *
 * Its properties are snapshot state; the composer draws it through [ComposerVoice].
 */
@Stable
class VoiceComposer(
    /** Plays the recording in review (the conversation's one player). */
    val player: VoicePlayback,
    private val scope: CoroutineScope,
    private val device: VoiceCaptureDevice,
    private val feedback: VoiceFeedback = VoiceFeedback.None,
) : ComposerVoice {
    /** What the composer asks of the screen around it: haptics and the keyboard. */
    interface VoiceFeedback {
        /** A light tap: recording starts, locks, stops at the maximum, is cancelled. */
        fun voice() {}

        fun error() {}

        /** The keyboard goes (recording locked or stopped for review). */
        fun dismissKeyboard() {}

        companion object {
            val None = object : VoiceFeedback {}
        }
    }

    enum class Hint {
        /** “Kaydetmek için basılı tut”. */
        Hold,

        /** “Şu anda kayıt yapılamıyor”. */
        Unavailable,

        /** “Bu sesli mesaj oynatılamıyor” (the conversation's player failed). */
        Unplayable,

        /** “Sesli mesaj gönderilemedi”. */
        SendFailed,
    }

    override var mode: ComposerVoiceMode by mutableStateOf(ComposerVoiceMode.Idle)
        private set
    var gesture: VoiceHoldGesture by mutableStateOf(VoiceHoldGesture())
        private set

    /** The microphone records right now (false once stopped at the maximum). */
    var isCapturing: Boolean by mutableStateOf(false)
        private set

    /** Recorded so far. */
    var elapsed: Duration by mutableStateOf(Duration.ZERO)
        private set

    /** Input levels (0…1), ten a second: the live waveform. */
    var levels: List<Double> by mutableStateOf(emptyList())
        private set

    /** A finished recording waiting to be sent (review, or held past the maximum). */
    var recording: VoiceRecording? by mutableStateOf(null)
        private set
    var hint: Hint? by mutableStateOf(null)
        private set

    /** The microphone is refused: the sheet that leads to the settings. */
    var showsPermissionNotice: Boolean by mutableStateOf(false)

    /** Hands a finished recording to the conversation. */
    var onSend: ((VoiceRecording) -> Unit)? = null

    private var capture: VoiceCapture? = null
    private var holdJob: Job? = null
    private var meterJob: Job? = null
    private var hintJob: Job? = null
    private var isAskingPermission = false
    private var touchActive = false
    private var touchNumber = 0

    /** Recording, or a recording waiting in review. */
    val isBusy: Boolean get() = mode != ComposerVoiceMode.Idle

    /** Recording right now (held or locked): nothing else may play. */
    val isRecording: Boolean get() = mode == ComposerVoiceMode.Holding || mode == ComposerVoiceMode.Locked

    override val showsHint: Boolean get() = hint != null

    /** Where the held mic is drawn: it follows the finger, left and up. */
    override val micOffset: DpOffset
        get() {
            if (mode != ComposerVoiceMode.Holding) return DpOffset.Zero
            val translation = gesture.translation
            return DpOffset(max(-VoiceNoteRules.CANCEL_DISTANCE, translation.x).dp, max(-VoiceNoteRules.LOCK_DISTANCE, translation.y).dp)
        }

    // The mic's touch

    /** The finger on the mic moved, by [translation] dp from where it went down (the first call is the touch going down). */
    fun touchChanged(translation: SlideOffset) {
        if (!touchActive) {
            touchActive = true
            touchNumber++
            touchBegan()
        }
        if (mode != ComposerVoiceMode.Holding) return
        val step = gesture.slide(translation)
        gesture = step.gesture
        when (step.event) {
            VoiceHoldGesture.Event.Cancel -> cancel()
            VoiceHoldGesture.Event.Lock -> lock()
            else -> Unit
        }
    }

    /** The finger lifted. */
    fun touchEnded() {
        if (!touchActive) return
        endTouch()
        val recorded = recording?.duration ?: capture?.currentTime ?: Duration.ZERO
        val step = gesture.release(recorded)
        gesture = step.gesture
        when (step.event) {
            VoiceHoldGesture.Event.Send -> send()
            VoiceHoldGesture.Event.Hint -> {
                if (mode == ComposerVoiceMode.Holding) discardRecording()
                showHint(Hint.Hold)
            }
            else -> Unit
        }
    }

    /**
     * The touch is over: lifted ([touchEnded] came first and this does nothing) or taken by the
     * system without a release; then what was recorded is kept for review.
     */
    fun touchFinished() {
        if (!touchActive) return
        val number = touchNumber
        scope.launch {
            if (!touchActive || touchNumber != number) return@launch
            endTouch()
            when (gesture.phase) {
                VoiceHoldGesture.Phase.Holding -> {
                    gesture = gesture.reset()
                    keepForReview()
                }
                VoiceHoldGesture.Phase.Pressed -> gesture = gesture.reset()
                else -> Unit
            }
        }
    }

    /** A screen reader's double tap on the mic: recording starts hands-free (stop, delete and send are their own buttons). */
    fun startHandsFree() {
        if (mode != ComposerVoiceMode.Idle) return
        when (device.permission) {
            VoicePermission.Granted -> {
                gesture = gesture.press() ?: return
                val step = gesture.holdDelayPassed()
                gesture = step.gesture
                if (step.event != VoiceHoldGesture.Event.StartRecording) return
                if (!startRecording()) {
                    gesture = gesture.reset()
                    return
                }
                gesture = gesture.slide(SlideOffset(0f, -VoiceNoteRules.LOCK_DISTANCE)).gesture
                lock()
            }
            VoicePermission.Undetermined -> requestPermission()
            VoicePermission.Denied -> showsPermissionNotice = true
        }
    }

    private fun touchBegan() {
        if (mode != ComposerVoiceMode.Idle) return
        hideHint()
        when (device.permission) {
            VoicePermission.Granted -> {
                // Nothing records: a press left over from a lost touch starts afresh.
                if (gesture.phase != VoiceHoldGesture.Phase.Idle) gesture = gesture.reset()
                gesture = gesture.press() ?: return
                holdJob?.cancel()
                holdJob = scope.launch {
                    delay(VoiceNoteRules.holdDelay)
                    holdJob = null
                    val step = gesture.holdDelayPassed()
                    gesture = step.gesture
                    if (step.event == VoiceHoldGesture.Event.StartRecording && !startRecording()) gesture = gesture.reset()
                }
            }
            VoicePermission.Undetermined -> requestPermission()
            VoicePermission.Denied -> showsPermissionNotice = true
        }
    }

    /** The current touch counts no more (lifted, taken away, or its view went when recording locked or stopped). */
    private fun endTouch() {
        touchActive = false
        holdJob?.cancel()
        holdJob = null
    }

    // Recording

    /** Opens the microphone; `false` (and the hint) if it cannot. */
    private fun startRecording(): Boolean {
        // One sound at a time: a voice message (or the review) stops first.
        player.stop()
        player.forget(PREVIEW_ID)
        // Felt before the microphone opens, so the tap is not recorded.
        feedback.voice()
        val capture = device.makeCapture()
        if (!capture.start()) {
            feedback.error()
            showHint(Hint.Unavailable)
            return false
        }
        capture.onInterrupted = { interrupted() }
        this.capture = capture
        recording = null
        levels = emptyList()
        elapsed = Duration.ZERO
        isCapturing = true
        mode = ComposerVoiceMode.Holding
        startMetering()
        return true
    }

    private fun lock() {
        feedback.voice()
        // The finger is free: its touch (whose view goes now) is over, and the keyboard can go.
        endTouch()
        feedback.dismissKeyboard()
        if (isCapturing) {
            mode = ComposerVoiceMode.Locked
            return
        }
        // Stopped at the maximum while held: straight to review.
        gesture = gesture.reset()
        if (recording != null) mode = ComposerVoiceMode.Review else returnToIdle()
    }

    /** “Kaydı durdur” (locked): the recording waits in review. */
    fun stop() {
        if (mode != ComposerVoiceMode.Locked) return
        gesture = gesture.reset()
        keepForReview()
    }

    /** Slid away, or “Sil”: the recording is gone. */
    override fun cancel() {
        discardRecording()
        // After the microphone closed: felt, never recorded.
        feedback.voice()
    }

    /** Released, or “Gönder”: the recording goes to the conversation. */
    override fun send() {
        if (mode == ComposerVoiceMode.Locked) gesture = gesture.sendLocked(capture?.currentTime ?: Duration.ZERO).gesture
        player.forget(PREVIEW_ID)
        val finished = recording ?: finishCapture()
        returnToIdle()
        if (finished == null) {
            showHint(Hint.Unavailable)
            return
        }
        if (!VoiceNoteRules.isLongEnough(finished.duration)) {
            showHint(Hint.Hold)
            return
        }
        onSend?.invoke(finished)
    }

    /** The review's play / pause. */
    fun togglePreview() {
        val recording = recording
        if (mode != ComposerVoiceMode.Review || recording == null) return
        if (player.activeId == PREVIEW_ID) {
            player.togglePause()
            return
        }
        player.begin(PREVIEW_ID)
        player.play(MediaPayload(recording.audio, VoiceRecording.CONTENT_TYPE, null), PREVIEW_ID)
    }

    /** Leaving the chat or the foreground: whatever is recorded (or waits in review) is thrown away, at once. */
    fun discardAll() {
        endTouch()
        hintJob?.cancel()
        hintJob = null
        hint = null
        showsPermissionNotice = false
        player.forget(PREVIEW_ID)
        capture?.discard()
        capture = null
        stopMetering()
        gesture = gesture.reset()
        isCapturing = false
        recording = null
        levels = emptyList()
        elapsed = Duration.ZERO
        mode = ComposerVoiceMode.Idle
    }

    /** A call took the microphone: what was recorded is kept for review. */
    private fun interrupted() {
        if (!isCapturing) return
        gesture = gesture.reset()
        keepForReview()
    }

    /** Stops recording; at least a second goes to review, anything shorter is thrown away (with the hold hint). */
    private fun keepForReview() {
        endTouch()
        val finished = recording ?: finishCapture()
        if (finished == null) {
            returnToIdle()
            showHint(Hint.Unavailable)
            return
        }
        if (!VoiceNoteRules.isLongEnough(finished.duration)) {
            returnToIdle()
            showHint(Hint.Hold)
            return
        }
        recording = finished
        feedback.dismissKeyboard()
        mode = ComposerVoiceMode.Review
    }

    /** Five minutes: recording stops; held, it is sent on release; locked, it waits in review. */
    private fun reachedMaximum() {
        if (mode == ComposerVoiceMode.Locked) {
            gesture = gesture.reset()
            keepForReview()
        } else {
            recording = finishCapture()
        }
        feedback.voice()
    }

    /** Stops the microphone and takes the recording into memory (its file is deleted by the capture). */
    private fun finishCapture(): VoiceRecording? {
        stopMetering()
        val capture = capture ?: return null
        val duration = minOf(capture.currentTime, VoiceNoteRules.maximumDuration)
        this.capture = null
        isCapturing = false
        val audio = capture.finish() ?: return null
        return VoiceRecording(audio, duration, VoiceWaveform.data(levels))
    }

    private fun discardRecording() {
        player.forget(PREVIEW_ID)
        capture?.discard()
        capture = null
        stopMetering()
        returnToIdle()
    }

    private fun returnToIdle() {
        gesture = gesture.reset()
        isCapturing = false
        recording = null
        mode = ComposerVoiceMode.Idle
        levels = emptyList()
        elapsed = Duration.ZERO
    }

    // Metering

    private fun startMetering() {
        meterJob?.cancel()
        meterJob = scope.launch {
            while (isActive) {
                delay(100.milliseconds)
                meter()
            }
        }
    }

    private fun stopMetering() {
        meterJob?.cancel()
        meterJob = null
    }

    private fun meter() {
        val capture = capture ?: return
        if (!isCapturing) return
        elapsed = minOf(capture.currentTime, VoiceNoteRules.maximumDuration)
        // Stopped underneath (a full disk, an encoder error): the clock would freeze, so it ends here, kept for review.
        if (!capture.isRecording) {
            interrupted()
            return
        }
        levels = levels + capture.level()
        if (VoiceNoteRules.hasReachedMaximum(capture.currentTime)) reachedMaximum()
    }

    // Permission and hints

    private fun requestPermission() {
        if (isAskingPermission) return
        isAskingPermission = true
        scope.launch {
            val granted = device.requestPermission()
            isAskingPermission = false
            if (granted) showHint(Hint.Hold) else showsPermissionNotice = true
        }
    }

    /** The conversation's voice message could not be played or sent: said above the mic (never over a recording). */
    fun report(hint: Hint) {
        if (isRecording) return
        showHint(hint)
    }

    private fun showHint(hint: Hint) {
        hintJob?.cancel()
        this.hint = hint
        hintJob = scope.launch {
            delay(2.2.seconds)
            this@VoiceComposer.hint = null
        }
    }

    private fun hideHint() {
        hintJob?.cancel()
        hintJob = null
        hint = null
    }

    // Harness

    /**
     * Shows a recording state without a microphone (the screenshots of the proposed recording UI).
     * Review keeps a synthesized recording, so listening works.
     */
    fun showDemo(mode: ComposerVoiceMode, elapsed: Duration, slide: SlideOffset = SlideOffset.Zero) {
        discardAll()
        val count = (elapsed.toDouble(DurationUnit.SECONDS) * 10).toInt()
        val levels = (0 until count).map { index ->
            0.25 + 0.55 * abs(sin(index * 0.45)) * (0.6 + 0.4 * abs(cos(index * 0.13)))
        }
        this.levels = levels
        this.elapsed = elapsed
        when (mode) {
            ComposerVoiceMode.Idle -> return
            ComposerVoiceMode.Holding -> {
                gesture = VoiceHoldGesture(VoiceHoldGesture.Phase.Holding, slide)
                isCapturing = true
            }
            ComposerVoiceMode.Locked -> {
                gesture = VoiceHoldGesture(VoiceHoldGesture.Phase.Locked)
                isCapturing = true
            }
            ComposerVoiceMode.Review -> {
                val waveform = VoiceWaveform.data(levels)
                recording = VoiceRecording(DemoVoice.wav(elapsed, levels), elapsed, waveform)
            }
        }
        this.mode = mode
    }

    /** The hint as a tap shows it. */
    fun showDemoHint() = showHint(Hint.Hold)

    // ComposerVoice

    @Composable
    override fun RecordingStrip(modifier: Modifier) = VoiceRecordingStrip(this, modifier)

    @Composable
    override fun HoldArea(modifier: Modifier) = VoiceHoldArea(this, modifier)

    @Composable
    override fun LockHint(modifier: Modifier) = VoiceLockHint(this, modifier)

    @Composable
    override fun HintBubble(modifier: Modifier) {
        val shown = hint ?: lastHint
        if (hint != null) lastHint = hint
        shown?.let { VoiceHintBubble(it, modifier) }
    }

    /** The hint fading out keeps its words. */
    private var lastHint: Hint? = null

    companion object {
        /** The recording's id in [player] while it is listened to in review. */
        val PREVIEW_ID = MessageId("voice.preview")
    }
}

/** A stand-in recording for the harness: a soft tone shaped by the levels, as 16-bit mono WAV. */
internal object DemoVoice {
    private const val RATE = 8_000

    fun wav(duration: Duration, levels: List<Double>): Bytes {
        val samples = (duration.toDouble(DurationUnit.SECONDS) * RATE).toInt().coerceAtLeast(1)
        val data = ByteArray(44 + samples * 2)
        fun int(at: Int, value: Int) {
            for (i in 0 until 4) data[at + i] = (value shr (8 * i)).toByte()
        }
        fun short(at: Int, value: Int) {
            data[at] = value.toByte()
            data[at + 1] = (value shr 8).toByte()
        }
        "RIFF".forEachIndexed { i, c -> data[i] = c.code.toByte() }
        int(4, 36 + samples * 2)
        "WAVEfmt ".forEachIndexed { i, c -> data[8 + i] = c.code.toByte() }
        int(16, 16)
        short(20, 1)
        short(22, 1)
        int(24, RATE)
        int(28, RATE * 2)
        short(32, 2)
        short(34, 16)
        "data".forEachIndexed { i, c -> data[36 + i] = c.code.toByte() }
        int(40, samples * 2)
        for (n in 0 until samples) {
            val level = levels.getOrNull(n * levels.size / samples) ?: 0.3
            val value = (sin(2 * Math.PI * 220 * n / RATE) * 6_000 * min(1.0, level)).toInt()
            short(44 + n * 2, value)
        }
        return Bytes.wrap(data)
    }
}
