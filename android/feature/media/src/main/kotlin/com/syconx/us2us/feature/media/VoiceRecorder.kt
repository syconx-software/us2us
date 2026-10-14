package com.syconx.us2us.feature.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.syconx.us2us.domain.Bytes
import com.syconx.us2us.domain.MediaPayload
import com.syconx.us2us.domain.SlideOffset
import com.syconx.us2us.domain.VoiceHoldGesture
import com.syconx.us2us.domain.VoiceNoteRules
import com.syconx.us2us.domain.VoiceRecording
import com.syconx.us2us.domain.VoiceRecordingFiles
import com.syconx.us2us.domain.VoiceWaveform
import com.syconx.us2us.domain.nav.MessageId
import com.syconx.us2us.ui.env.LocalHaptics
import com.syconx.us2us.ui.env.U2Haptics
import com.syconx.us2us.ui.env.rememberGuardedLauncher
import com.syconx.us2us.ui.env.rememberU2Keyboard
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// 5.1 › mic (proposed): recording a voice message. `VoiceRecorderController` is the composer's
// state (hold · locked · review) over a capture device; the system device records with
// MediaRecorder into `VoiceRecordingFiles` and hands the bytes over in memory the moment recording
// stops.

/** Whether the app may use the microphone. */
enum class VoicePermission { Undetermined, Granted, Denied }

/** One recording in progress. */
interface VoiceCapture {
    /** Starts recording; `false` when the microphone is not available right now. */
    fun start(): Boolean

    /** How much is recorded so far. */
    val currentTime: Duration

    /** The microphone still records: `false` once the system or an error stopped it without being asked. */
    val isRecording: Boolean

    /** The input level right now, 0…1. */
    fun level(): Double

    /** Stops and returns the recorded bytes; nothing of it is left on disk. */
    fun finish(): Bytes?

    /** Stops and throws the recording away. */
    fun discard()

    /** The system took the audio away (a call), or the recorder stopped by itself. */
    var onInterrupted: (() -> Unit)?
}

/** Where recordings come from: the microphone, or a stand-in (tests). */
interface VoiceCaptureDevice {
    val permission: VoicePermission

    /** Asks once (the system prompt); `true` when allowed. */
    suspend fun requestPermission(): Boolean

    fun makeCapture(): VoiceCapture
}

/** The phone's microphone (only while the user records a voice message). [ask] shows the system prompt. */
class SystemVoiceDevice(context: Context, private val ask: suspend () -> Boolean = { false }) : VoiceCaptureDevice {
    private val appContext = context.applicationContext

    override val permission: VoicePermission
        get() = if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            VoicePermission.Granted
        } else {
            // Android does not tell “never asked” from “refused” before asking: the prompt answers (at once when refused for good).
            VoicePermission.Undetermined
        }

    override suspend fun requestPermission(): Boolean = ask()

    override fun makeCapture(): VoiceCapture = MediaRecorderCapture(appContext)
}

/**
 * MediaRecorder: AAC-LC in M4A, mono, 24 kHz, about 32 kbit/s, into a fresh file of
 * `VoiceRecordingFiles` (app-private, no backup), which [finish] reads into memory and deletes at
 * once and [discard] deletes. Audio focus is held (exclusive, transient) while it records. A
 * recorder error counts as an interruption (what was recorded goes to review).
 */
class MediaRecorderCapture(private val context: Context) : VoiceCapture {
    override var onInterrupted: (() -> Unit)? = null

    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L
    private var stoppedAt = 0L
    private var running = false
    private val focus = SystemVoiceAudioFocus(context, recording = true)

    override val currentTime: Duration
        get() = if (startedAt == 0L) Duration.ZERO else ((if (running) SystemClock.elapsedRealtime() else stoppedAt) - startedAt).milliseconds

    override val isRecording: Boolean get() = running

    override fun start(): Boolean {
        if (recorder != null) return false
        val file = VoiceRecordingFiles.makeFile() ?: return false
        if (!focus.request { interrupted() }) {
            VoiceRecordingFiles.discard(file)
            return false
        }
        @Suppress("DEPRECATION")
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
        val started = runCatching {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioSamplingRate(24_000)
            recorder.setAudioChannels(1)
            recorder.setAudioEncodingBitRate(32_000)
            recorder.setOutputFile(file.absolutePath)
            recorder.setOnErrorListener { _, _, _ -> interrupted() }
            recorder.prepare()
            recorder.start()
        }.isSuccess
        if (!started) {
            recorder.release()
            VoiceRecordingFiles.discard(file)
            focus.abandon()
            return false
        }
        this.recorder = recorder
        this.file = file
        startedAt = SystemClock.elapsedRealtime()
        running = true
        return true
    }

    /** `getMaxAmplitude` (the peak since the last call) as dBFS, then the waveform's 0…1 level. */
    override fun level(): Double {
        val recorder = recorder ?: return 0.0
        val amplitude = runCatching { recorder.maxAmplitude }.getOrDefault(0)
        if (amplitude <= 0) return 0.0
        val decibels = (20 * log10(amplitude / 32_767.0)).toFloat()
        return VoiceWaveform.level(decibels)
    }

    override fun finish(): Bytes? {
        val recorder = recorder ?: return null
        val file = file ?: return null
        val stopped = runCatching { recorder.stop() }.isSuccess
        recorder.release()
        this.recorder = null
        this.file = null
        if (running) stoppedAt = SystemClock.elapsedRealtime()
        running = false
        focus.abandon()
        if (!stopped) {
            VoiceRecordingFiles.discard(file)
            return null
        }
        return VoiceRecordingFiles.consume(file)
    }

    override fun discard() {
        recorder?.let {
            runCatching { it.stop() }
            it.release()
        }
        recorder = null
        file?.let(VoiceRecordingFiles::discard)
        file = null
        if (running) stoppedAt = SystemClock.elapsedRealtime()
        running = false
        focus.abandon()
    }

    private fun interrupted() {
        if (recorder == null) return
        if (running) stoppedAt = SystemClock.elapsedRealtime()
        running = false
        onInterrupted?.invoke()
    }
}

/**
 * The composer's voice recorder (5.1 mic, proposed; WhatsApp / iMessage behaviour):
 *
 * - press and hold the mic → recording starts after `VoiceNoteRules.holdDelay` (the pulsing dot,
 *   the time, the live waveform, “‹ İptal için kaydır”); release → sent; slide left → cancelled;
 *   slide up → locked: recording goes on hands-free with “Sil” · stop · “Gönder”; stop → review
 *   (listen, then delete or send);
 * - a tap, or a recording shorter than a second, shows “Kaydetmek için basılı tut” instead; at five
 *   minutes recording stops by itself and the recording waits to be sent;
 * - the microphone is asked for on the first press; refused → a sheet explains it and opens
 *   Settings ([showsPermissionNotice]);
 * - a call (or any interruption, or the recorder stopping by itself) stops recording and keeps what
 *   was recorded for review; leaving the chat or the foreground throws the recording away
 *   ([discardAll]) — it is in memory only;
 * - the keyboard stays while the mic is held and goes once recording locks or stops for review.
 *
 * The conversation's `ComposerVoice` draws it; [player] is the conversation's one voice player
 * (the review plays through it). Everything is snapshot state.
 */
@Stable
class VoiceRecorderController internal constructor(
    context: Context,
    /** Plays the recording in review (the conversation's one player). */
    val player: VoicePlayback,
    private val device: VoiceCaptureDevice,
    private val haptics: U2Haptics,
    private val dismissKeyboard: () -> Unit,
    private val scope: CoroutineScope,
) {
    /** A recorder with its own player and the microphone; the prompt is never shown (prefer [rememberVoiceRecorderController]). */
    constructor(context: Context) : this(
        context,
        VoicePlayback({ MediaPlayerVoiceEngine() }, SystemVoiceAudioFocus(context)),
        SystemVoiceDevice(context),
        U2Haptics.None,
        {},
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    )

    internal val appContext: Context = context.applicationContext

    enum class Mode {
        Idle,

        /** Recording while the finger holds the mic. */
        Holding,

        /** Recording hands-free. */
        Locked,

        /** Stopped: the recording waits to be listened to, deleted or sent. */
        Review,
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

    var mode by mutableStateOf(Mode.Idle)
        private set
    var gesture by mutableStateOf(VoiceHoldGesture())
        private set

    /** The microphone records right now (false once stopped at the maximum). */
    var isCapturing by mutableStateOf(false)
        private set

    /** How much is recorded. */
    var elapsed by mutableStateOf(Duration.ZERO)
        private set

    /** Input levels (0…1), ten a second: the live waveform. */
    var levels by mutableStateOf<List<Double>>(emptyList())
        private set

    /** A finished recording waiting to be sent (review, or held past the maximum). */
    var recording by mutableStateOf<VoiceRecording?>(null)
        private set
    var hint by mutableStateOf<Hint?>(null)
        private set

    /** The microphone is refused: the sheet that leads to Settings. */
    var showsPermissionNotice by mutableStateOf(false)

    /** Hands a finished recording to the conversation. */
    var onSend: ((VoiceRecording) -> Unit)? = null

    private var capture: VoiceCapture? = null
    private var holdJob: Job? = null
    private var meterJob: Job? = null
    private var hintJob: Job? = null
    private var isAskingPermission = false

    /** A finger is on the mic (between its first move and its release). */
    private var touchActive = false
    private var touchNumber = 0

    /** Recording, or a recording waiting in review. */
    val isBusy: Boolean get() = mode != Mode.Idle

    /** Recording right now (held or locked): nothing else may play. */
    val isRecording: Boolean get() = mode == Mode.Holding || mode == Mode.Locked

    /** Where the held mic is drawn: it follows the finger, left and up (dp). */
    val micOffset: SlideOffset
        get() {
            if (mode != Mode.Holding) return SlideOffset.Zero
            return SlideOffset(
                max(-VoiceNoteRules.CANCEL_DISTANCE, gesture.translation.x),
                max(-VoiceNoteRules.LOCK_DISTANCE, gesture.translation.y),
            )
        }

    // MARK: The mic's touch

    /** The finger on the mic moved (the first call is the touch going down); [translation] in dp from where it went down. */
    fun touchChanged(translation: SlideOffset) {
        if (!touchActive) {
            touchActive = true
            touchNumber++
            touchBegan()
        }
        if (mode != Mode.Holding) return
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
                if (mode == Mode.Holding) discardRecording()
                showHint(Hint.Hold)
            }
            else -> Unit
        }
    }

    /** The touch is over without a release (taken by the system): what was recorded is kept for review. */
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

    /** The screen reader's double tap on the mic: recording starts hands-free (stop, delete and send are their own buttons). */
    fun startHandsFree() {
        if (mode != Mode.Idle) return
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
        if (mode != Mode.Idle) return
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

    private fun endTouch() {
        touchActive = false
        holdJob?.cancel()
        holdJob = null
    }

    // MARK: Recording

    /** Opens the microphone; `false` (and the hint) if it cannot. */
    private fun startRecording(): Boolean {
        // One sound at a time: a voice message (or the review) stops first.
        player.stop()
        player.forget(PREVIEW_ID)
        // Felt before the microphone opens, so the tap is not recorded.
        haptics.voice()
        val capture = device.makeCapture()
        if (!capture.start()) {
            haptics.error()
            showHint(Hint.Unavailable)
            return false
        }
        capture.onInterrupted = { interrupted() }
        this.capture = capture
        recording = null
        levels = emptyList()
        elapsed = Duration.ZERO
        isCapturing = true
        mode = Mode.Holding
        startMetering()
        return true
    }

    private fun lock() {
        haptics.voice()
        // The finger is free, and the keyboard (kept while the mic was held) can go.
        endTouch()
        dismissKeyboard()
        if (isCapturing) {
            mode = Mode.Locked
            return
        }
        // Stopped at the maximum while held: straight to review.
        gesture = gesture.reset()
        if (recording != null) mode = Mode.Review else returnToIdle()
    }

    /** “Kaydı durdur” (locked): the recording waits in review. */
    fun stop() {
        if (mode != Mode.Locked) return
        gesture = gesture.reset()
        keepForReview()
    }

    /** Slid away, or “Sil”: the recording is gone. */
    fun cancel() {
        discardRecording()
        // After the microphone closed: felt, never recorded.
        haptics.voice()
    }

    /** Released, or “Gönder”: the recording goes to the conversation. */
    fun send() {
        if (mode == Mode.Locked) gesture = gesture.sendLocked(capture?.currentTime ?: Duration.ZERO).gesture
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
        val recording = recording ?: return
        if (mode != Mode.Review) return
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
        mode = Mode.Idle
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
        dismissKeyboard()
        mode = Mode.Review
    }

    /** Five minutes: recording stops; held, it is sent on release; locked, it waits in review. */
    private fun reachedMaximum() {
        if (mode == Mode.Locked) {
            gesture = gesture.reset()
            keepForReview()
        } else {
            recording = finishCapture()
        }
        haptics.voice()
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
        mode = Mode.Idle
        levels = emptyList()
        elapsed = Duration.ZERO
    }

    // MARK: Metering

    private fun startMetering() {
        meterJob?.cancel()
        meterJob = scope.launch {
            while (true) {
                delay(100)
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
        // Stopped underneath: the clock would freeze, so it ends here, kept for review.
        if (!capture.isRecording) {
            interrupted()
            return
        }
        levels = levels + capture.level()
        if (VoiceNoteRules.hasReachedMaximum(capture.currentTime)) reachedMaximum()
    }

    // MARK: Permission and hints

    private fun requestPermission() {
        if (isAskingPermission) return
        isAskingPermission = true
        scope.launch {
            val granted = device.requestPermission()
            isAskingPermission = false
            if (granted) showHint(Hint.Hold) else showsPermissionNotice = true
        }
    }

    /** The conversation's voice message could not be played or sent: said above the mic like the recorder's own hints (never over a recording). */
    fun report(hint: Hint) {
        if (isRecording) return
        showHint(hint)
    }

    private fun showHint(hint: Hint) {
        hintJob?.cancel()
        this.hint = hint
        hintJob = scope.launch {
            delay(2.2.seconds)
            this@VoiceRecorderController.hint = null
        }
    }

    private fun hideHint() {
        hintJob?.cancel()
        hintJob = null
        hint = null
    }

    /** Lets go of the coroutines (the conversation closed). */
    fun close() {
        discardAll()
        scope.cancel()
    }

    // MARK: Harness

    /** Debug harness: shows a recording state without a microphone; `Review` keeps a synthesized recording, so listening works. */
    fun showDemo(mode: Mode, elapsed: Duration, slide: SlideOffset = SlideOffset.Zero) {
        discardAll()
        val count = (elapsed.toDouble(DurationUnit.SECONDS) * 10).toInt()
        val levels = (0 until count).map { index ->
            0.25 + 0.55 * abs(sin(index * 0.45)) * (0.6 + 0.4 * abs(cos(index * 0.13)))
        }
        this.levels = levels
        this.elapsed = elapsed
        when (mode) {
            Mode.Idle -> return
            Mode.Holding -> {
                gesture = VoiceHoldGesture(VoiceHoldGesture.Phase.Holding, slide)
                isCapturing = true
            }
            Mode.Locked -> {
                gesture = VoiceHoldGesture(VoiceHoldGesture.Phase.Locked)
                isCapturing = true
            }
            Mode.Review -> {
                val waveform = VoiceWaveform.data(levels)
                recording = VoiceRecording(DemoVoice.wav(elapsed, waveform), elapsed, waveform)
            }
        }
        this.mode = mode
    }

    /** Debug harness: the hint as a tap shows it. */
    fun showDemoHint() {
        showHint(Hint.Hold)
    }

    companion object {
        /** The recording's id in [player] while it is listened to in review. */
        val PREVIEW_ID = MessageId("voice.preview")
    }
}

/**
 * The composer's recorder for the open conversation: the microphone (asked through the system
 * prompt under the app's guard), the app's haptics and keyboard, and [player] — the conversation's
 * one voice player. Everything recorded is thrown away when it leaves the screen.
 */
@Composable
fun rememberVoiceRecorderController(player: VoicePlayback): VoiceRecorderController {
    val context = LocalContext.current
    val haptics = LocalHaptics.current
    val keyboard = rememberU2Keyboard()
    val answer = remember { arrayOfNulls<CompletableDeferred<Boolean>>(1) }
    val launcher = rememberGuardedLauncher(ActivityResultContracts.RequestPermission()) { granted -> answer[0]?.complete(granted) }
    val controller = remember(context, player) {
        val device = SystemVoiceDevice(context) {
            val deferred = CompletableDeferred<Boolean>()
            answer[0] = deferred
            runCatching { launcher.launch(Manifest.permission.RECORD_AUDIO) }.onFailure { deferred.complete(false) }
            deferred.await()
        }
        VoiceRecorderController(context, player, device, haptics, { keyboard.dismiss() }, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
    }
    DisposableEffect(controller) { onDispose { controller.close() } }
    return controller
}

/** The conversation's one voice player: `MediaPlayer` from memory with transient audio focus; stopped when it leaves the screen. */
@Composable
fun rememberVoicePlayback(): VoicePlayback {
    val context = LocalContext.current
    val haptics = LocalHaptics.current
    val playback = remember(context) {
        VoicePlayback({ MediaPlayerVoiceEngine() }, SystemVoiceAudioFocus(context), onError = { haptics.error() })
    }
    DisposableEffect(playback) { onDispose { playback.reset() } }
    return playback
}

/** A stand-in voice sound for the harness's review: a soft hum (165 Hz with two overtones) that follows the waveform, 8 kHz 16-bit mono WAV. */
internal object DemoVoice {
    fun wav(duration: Duration, waveform: Bytes): Bytes {
        val rate = 8_000
        val count = max(1, (max(0.5, duration.toDouble(DurationUnit.SECONDS)) * rate).toInt())
        val levels = waveform.toUnsignedList().map { it / 255.0 }.ifEmpty { listOf(0.5) }
        val buffer = ByteBuffer.allocate(44 + count * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + count * 2).put("WAVEfmt ".toByteArray())
        buffer.putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        buffer.put("data".toByteArray()).putInt(count * 2)
        for (index in 0 until count) {
            val t = index.toDouble() / rate
            val level = levels[min(levels.size - 1, index * levels.size / count)]
            val swell = 0.55 + 0.45 * sin(2 * PI * 3.2 * t)
            val hum = sin(2 * PI * 165 * t) + 0.35 * sin(2 * PI * 330 * t) + 0.15 * sin(2 * PI * 495 * t)
            val value = max(-1.0, min(1.0, hum / 1.5 * level * swell * 0.6))
            buffer.putShort((value * Short.MAX_VALUE).toInt().toShort())
        }
        return Bytes.wrap(buffer.array())
    }
}
