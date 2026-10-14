package com.syconx.us2us.feature.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaDataSource
import android.media.MediaPlayer
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.syconx.us2us.domain.MediaAvailability
import com.syconx.us2us.domain.MediaPayload
import com.syconx.us2us.domain.Message
import com.syconx.us2us.domain.MessageContent
import com.syconx.us2us.domain.MessengerService
import com.syconx.us2us.domain.VoiceNoteRules
import com.syconx.us2us.domain.VoicePlaybackRate
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.MessageId
import com.syconx.us2us.ui.bubbles.VoicePlaybackState
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What plays one voice message's bytes: `MediaPlayer` from memory in the app, a stand-in in tests. Times in seconds. */
interface VoicePlayerEngine {
    /** Takes the bytes; `false` when they cannot be played. */
    fun load(payload: MediaPayload): Boolean

    val duration: Double
    var position: Double
    val isPlaying: Boolean

    fun play(): Boolean

    fun pause()

    fun setRate(rate: Float)

    /** Stops and lets go of the bytes. */
    fun release()

    /** Played to the end. */
    var onFinish: (() -> Unit)?

    /** It could not be decoded. */
    var onFailure: (() -> Unit)?
}

/** The audio focus a voice message (or a recording) holds; losing it, or headphones unplugged, pauses. */
interface VoiceAudioFocus {
    /** `false` when the focus could not be had (a call holds it). */
    fun request(onLoss: () -> Unit): Boolean

    fun abandon()

    companion object {
        val None: VoiceAudioFocus = object : VoiceAudioFocus {
            override fun request(onLoss: () -> Unit) = true

            override fun abandon() {}
        }
    }
}

/**
 * The one voice message that plays in the open conversation (5.1 voice bubble, proposed beyond the
 * design's paused state: play / pause, the progress over the waveform, drag to scrub, 1× · 1,5× ·
 * 2×). Implements the bubbles' [VoicePlaybackState]; provide it as `LocalVoicePlayback`.
 *
 * - Its bytes come decrypted into memory (`MessengerService.mediaPayload`, downloaded first if they
 *   are still on the relay) and play from memory — never from a file.
 * - One at a time: playing another message, recording or opening other media stops it; so do
 *   closing the chat, the app leaving the foreground and its message going ([stop], [reset]). Where
 *   a message was left stays for this visit only, in memory.
 * - Audio focus (transient, speech) is held only while it plays; paused or finished it is let go
 *   two seconds later, at once when playback stops; losing it (a call) or a lost route pauses.
 * - Bytes that cannot be played stop it with [onPlaybackFailure] (the conversation's hint).
 */
@Stable
class VoicePlayback(
    private val makeEngine: () -> VoicePlayerEngine,
    private val focus: VoiceAudioFocus = VoiceAudioFocus.None,
    private val onError: () -> Unit = {},
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : VoicePlaybackState {
    override var activeId: MessageId? by mutableStateOf(null)
        private set
    override var isPlaying: Boolean by mutableStateOf(false)
        private set
    override var isLoading: Boolean by mutableStateOf(false)
        private set
    override var isScrubbing: Boolean by mutableStateOf(false)
        private set
    override var rate: VoicePlaybackRate by mutableStateOf(VoicePlaybackRate.Normal)
        private set

    /** Where the active message is, 0…1. */
    var progress: Double by mutableDoubleStateOf(0.0)
        private set
    private var durationSeconds by mutableDoubleStateOf(0.0)

    /** Where messages played earlier in this visit were left, 0…1. */
    private val positions = mutableStateMapOf<MessageId, Double>()

    /** The active message could not be played. */
    var onPlaybackFailure: (() -> Unit)? = null

    private var engine: VoicePlayerEngine? = null
    private var ticker: Job? = null
    private var holdsFocus = false
    private var idleRelease: Job? = null
    private var startFraction = 0.0
    private var resumesAfterScrub = false
    private var load: Job? = null

    override val duration: Duration get() = durationSeconds.seconds
    override val currentTime: Duration get() = (durationSeconds * progress).seconds

    fun isActive(id: MessageId): Boolean = activeId == id

    override fun shownFraction(id: MessageId, fallback: Double): Double = if (activeId == id) progress else positions[id] ?: fallback

    // MARK: Playing

    /** [id] becomes the active message while its bytes are on their way ([play] follows). It starts where it was left in this visit, else at [fallback]. */
    fun begin(id: MessageId, fallback: Double = 0.0) {
        halt(keepingFocus = true)
        activeId = id
        isLoading = true
        val start = positions[id] ?: fallback
        startFraction = if (start < 0.999) max(0.0, start) else 0.0
        progress = startFraction
        durationSeconds = 0.0
    }

    /** Plays the bytes of the active message [id]. `false` — and nothing plays — when another message became active meanwhile, or the bytes cannot be played. */
    fun play(payload: MediaPayload?, id: MessageId): Boolean {
        if (activeId != id || !isLoading) return false
        isLoading = false
        if (payload == null || payload.data.isEmpty() || payload.data.size > VoiceNoteRules.MAXIMUM_PLAYABLE_BYTES) {
            fail()
            return false
        }
        val engine = makeEngine()
        if (!engine.load(payload)) {
            engine.release()
            fail()
            return false
        }
        engine.onFinish = { didFinish() }
        engine.onFailure = { fail() }
        engine.setRate(rate.value)
        if (startFraction > 0 && engine.duration > 0) engine.position = startFraction * engine.duration
        if (!takeFocus() || !engine.play()) {
            engine.release()
            fail()
            return false
        }
        this.engine = engine
        durationSeconds = engine.duration
        progress = fraction(engine)
        isPlaying = true
        startTicking()
        return true
    }

    /**
     * A tap on the voice bubble of [message] (iOS `toggleVoice`): the active one pauses or plays on;
     * another one loads (downloaded first if needed) and plays, marked played when it is incoming
     * and new. Held or gone media only buzzes.
     */
    fun toggle(message: Message, chat: ChatId, messenger: MessengerService, isRecording: Boolean = false) {
        val id = message.id
        if (activeId == id) {
            if (!isLoading) togglePause()
            return
        }
        val content = message.content as? MessageContent.Voice ?: return
        if (isRecording) return
        when (message.mediaAvailability) {
            MediaAvailability.Ready, MediaAvailability.Remote, is MediaAvailability.Downloading -> Unit
            else -> {
                onError()
                return
            }
        }
        load?.cancel()
        begin(id, content.playedFraction)
        val marksPlayed = !message.isOutgoing && !message.isVoicePlayed
        load = scope.launch {
            val payload = messenger.mediaPayload(id, chat)
            if (!play(payload, id)) return@launch
            if (marksPlayed) MediaWork.scope.launch { messenger.markVoicePlayed(id, chat) }
        }
    }

    /** The active message: pauses it while it plays, plays it on while paused. */
    fun togglePause() {
        val engine = engine ?: return
        if (engine.isPlaying) pause() else resume()
    }

    fun pause() {
        val engine = engine ?: return
        engine.pause()
        isPlaying = false
        progress = fraction(engine)
        stopTicking()
        releaseFocusSoon()
    }

    private fun resume() {
        val engine = engine ?: return
        if (!takeFocus()) return
        if (fraction(engine) >= 0.999) engine.position = 0.0
        if (!engine.play()) return
        isPlaying = true
        startTicking()
    }

    /** 1× → 1,5× → 2× → 1× (kept for the next messages of this visit). */
    override fun cycleRate() {
        rate = rate.next
        engine?.setRate(rate.value)
    }

    // MARK: Seeking

    override fun beginScrub(id: MessageId) {
        val engine = engine ?: return
        if (activeId != id || isScrubbing) return
        isScrubbing = true
        resumesAfterScrub = engine.isPlaying
        engine.pause()
        stopTicking()
    }

    override fun scrub(id: MessageId, fraction: Double) {
        val engine = engine ?: return
        if (activeId != id || engine.duration <= 0) return
        val clamped = min(1.0, max(0.0, fraction))
        progress = clamped
        engine.position = clamped * engine.duration
    }

    override fun endScrub(id: MessageId) {
        if (activeId != id || !isScrubbing) return
        isScrubbing = false
        val resumes = resumesAfterScrub
        resumesAfterScrub = false
        when {
            // Let go at the very end: the message is finished, not started over.
            progress >= 0.999 -> didFinish()
            resumes -> resume()
            else -> pause()
        }
    }

    override fun skip(id: MessageId, by: Duration) {
        val engine = engine ?: return
        if (activeId != id || engine.duration <= 0) return
        engine.position = min(engine.duration, max(0.0, engine.position + by.toDouble(DurationUnit.SECONDS)))
        progress = fraction(engine)
    }

    // MARK: Stopping

    /** Stops whatever plays or loads and lets go of its bytes and of the audio focus; where the message was left is kept for this visit. */
    fun stop() {
        load?.cancel()
        load = null
        halt(keepingFocus = false)
    }

    /** [id] stops if it is the active one, and where it was left is forgotten (the composer's review, once its recording is sent or deleted). */
    fun forget(id: MessageId) {
        if (activeId == id) stop()
        positions.remove(id)
    }

    /** [stop], and the remembered positions go too (the chat closed). */
    fun reset() {
        stop()
        positions.clear()
    }

    private fun halt(keepingFocus: Boolean) {
        val id = activeId
        val engine = engine
        if (id != null && engine != null) {
            val left = fraction(engine)
            if (left > 0 && left < 0.999) positions[id] = left else positions.remove(id)
        }
        releaseEngine(keepingFocus)
        activeId = null
        isPlaying = false
        isLoading = false
        isScrubbing = false
        resumesAfterScrub = false
        progress = 0.0
        durationSeconds = 0.0
    }

    private fun didFinish() {
        activeId?.let { positions.remove(it) }
        releaseEngine(keepingFocus = true)
        activeId = null
        isPlaying = false
        progress = 0.0
        durationSeconds = 0.0
    }

    private fun fail() {
        releaseEngine(keepingFocus = false)
        activeId = null
        isPlaying = false
        isLoading = false
        isScrubbing = false
        progress = 0.0
        durationSeconds = 0.0
        onError()
        onPlaybackFailure?.invoke()
    }

    private fun releaseEngine(keepingFocus: Boolean) {
        stopTicking()
        engine?.let {
            it.onFinish = null
            it.onFailure = null
            it.release()
        }
        engine = null
        if (keepingFocus) releaseFocusSoon() else releaseFocus()
    }

    // MARK: Progress

    private fun startTicking() {
        ticker?.cancel()
        ticker = scope.launch {
            while (true) {
                delay(50)
                val engine = engine ?: return@launch
                if (!isScrubbing) progress = fraction(engine)
            }
        }
    }

    private fun stopTicking() {
        ticker?.cancel()
        ticker = null
    }

    private fun fraction(engine: VoicePlayerEngine): Double =
        if (engine.duration > 0) min(1.0, max(0.0, engine.position / engine.duration)) else 0.0

    // MARK: Audio focus

    private fun takeFocus(): Boolean {
        idleRelease?.cancel()
        idleRelease = null
        if (holdsFocus) return true
        if (!focus.request { pause() }) return false
        holdsFocus = true
        return true
    }

    private fun releaseFocus() {
        idleRelease?.cancel()
        idleRelease = null
        if (!holdsFocus) return
        holdsFocus = false
        focus.abandon()
    }

    /** Paused, finished, or between two messages: the focus goes in two seconds unless something plays again before. */
    private fun releaseFocusSoon() {
        if (!holdsFocus || idleRelease != null) return
        idleRelease = scope.launch {
            delay(2_000)
            idleRelease = null
            releaseFocus()
        }
    }
}

/** `MediaPlayer` playing a voice message's bytes from memory (`MediaDataSource`: never a file). */
class MediaPlayerVoiceEngine : VoicePlayerEngine {
    private val player = MediaPlayer()
    private var prepared = false
    private var released = false

    override var onFinish: (() -> Unit)? = null
    override var onFailure: (() -> Unit)? = null

    override fun load(payload: MediaPayload): Boolean = runCatching {
        val bytes = payload.data.toByteArray()
        player.setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
        )
        player.setDataSource(MemoryDataSource(bytes))
        player.setOnCompletionListener { onFinish?.invoke() }
        player.setOnErrorListener { _, _, _ ->
            onFailure?.invoke()
            true
        }
        player.prepare()
        prepared = true
        true
    }.getOrDefault(false)

    override val duration: Double get() = if (prepared && !released) player.duration / 1000.0 else 0.0

    override var position: Double
        get() = if (prepared && !released) player.currentPosition / 1000.0 else 0.0
        set(value) {
            if (prepared && !released) runCatching { player.seekTo((value * 1000).toLong(), MediaPlayer.SEEK_CLOSEST) }
        }

    override val isPlaying: Boolean get() = prepared && !released && runCatching { player.isPlaying }.getOrDefault(false)

    override fun play(): Boolean = runCatching {
        player.start()
        true
    }.getOrDefault(false)

    override fun pause() {
        if (prepared && !released) runCatching { player.pause() }
    }

    override fun setRate(rate: Float) {
        if (!prepared || released) return
        val wasPlaying = isPlaying
        runCatching {
            player.playbackParams = player.playbackParams.setSpeed(rate)
            // Setting the speed starts a paused player: keep it paused.
            if (!wasPlaying) player.pause()
        }
    }

    override fun release() {
        if (released) return
        released = true
        runCatching { player.stop() }
        player.release()
    }

    private class MemoryDataSource(private val bytes: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= bytes.size) return -1
            val count = min(size.toLong(), bytes.size - position).toInt()
            System.arraycopy(bytes, position.toInt(), buffer, offset, count)
            return count
        }

        override fun getSize(): Long = bytes.size.toLong()

        override fun close() {}
    }
}

/**
 * Transient audio focus for spoken audio (a voice message playing) or for a recording; losing it
 * or the headphones going away (“becoming noisy”) calls back.
 */
class SystemVoiceAudioFocus(context: Context, private val recording: Boolean = false) : VoiceAudioFocus {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(AudioManager::class.java)
    private var request: AudioFocusRequest? = null
    private var noisy: BroadcastReceiver? = null

    override fun request(onLoss: () -> Unit): Boolean {
        val manager = manager ?: return true
        val attributes = AudioAttributes.Builder()
            .setUsage(if (recording) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val focus = AudioFocusRequest.Builder(if (recording) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) onLoss()
            }
            .build()
        if (manager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
        request = focus
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) onLoss()
            }
        }
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        noisy = receiver
        return true
    }

    override fun abandon() {
        request?.let { manager?.abandonAudioFocusRequest(it) }
        request = null
        noisy?.let { runCatching { appContext.unregisterReceiver(it) } }
        noisy = null
    }
}
