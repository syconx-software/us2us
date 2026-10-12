package com.syconx.us2us.feature.media

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.syconx.us2us.domain.MediaPayload
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * What plays one clip: ExoPlayer from memory in the app ([ExoVideoEngine]), a stand-in in tests.
 * Times in seconds.
 */
interface VideoEngine {
    /** The player the video surface shows; `null` for an engine without one. */
    val player: Player?

    /** Where it is now. */
    val position: Double

    /** How far it is loaded. */
    val buffered: Double

    /** Prepares [payload]; [listener] hears the rest. */
    fun prepare(payload: MediaPayload, listener: Listener)

    fun play()

    fun pause()

    fun seekTo(seconds: Double)

    fun setMuted(muted: Boolean)

    /** Stops and lets go of the player and the bytes. */
    fun release()

    interface Listener {
        /** Ready to play; [duration] is the clip's length (seconds, 0 if unknown). */
        fun onReady(duration: Double)

        fun onEnded()

        fun onFailed()

        /** The first frame is on screen (the poster can go). */
        fun onFirstFrame()

        /** The system paused it (audio focus lost, headphones unplugged). */
        fun onPausedBySystem()
    }
}

/** Makes the engine for one player screen. */
fun interface VideoEngineFactory {
    fun create(context: Context): VideoEngine
}

/** The engine the player screens use: ExoPlayer, or a stand-in (tests). */
val LocalVideoEngineFactory = staticCompositionLocalOf { VideoEngineFactory { context -> ExoVideoEngine(context) } }

/**
 * The in-app player's state (iOS `VideoPlaybackModel`): one decrypted clip played from memory,
 * play / pause, seeking, mute.
 *
 * - No casting, no picture in picture: the decrypted video stays on this phone and on this screen.
 * - Audio focus is taken (movie playback) only once something plays; losing it, or headphones
 *   unplugged, pauses.
 * - [close] drops the player, the item and the bytes at once.
 */
@Stable
class VideoPlaybackModel(
    /** The length the message announced (shown while loading). */
    duration: Duration,
    private val engine: VideoEngine,
) : VideoEngine.Listener {
    enum class Phase {
        /** Downloading, decrypting or preparing. */
        Loading,
        Ready,

        /** Played to the end: the centre button plays again. */
        Ended,
        Failed,
    }

    var phase by mutableStateOf(Phase.Loading)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var isMuted by mutableStateOf(false)
        private set

    /** Seconds. */
    var currentTime by mutableDoubleStateOf(0.0)
        private set
    var duration by mutableDoubleStateOf(duration.toDouble(DurationUnit.SECONDS))
        private set

    /** Seconds loaded ahead (from memory: soon the whole clip). */
    var buffered by mutableDoubleStateOf(0.0)
        private set

    /** The first frame is on screen (the poster can go). */
    var isShowingVideo by mutableStateOf(false)
        private set
    var isScrubbing by mutableStateOf(false)
        private set

    /** What the video surface shows. */
    val player: Player? get() = if (isStarted && !isClosed) engine.player else null

    private var isStarted = false
    private var isClosed = false
    private var resumesAfterScrub = false
    private var pendingStart: Pair<Double, Boolean>? = null

    val fraction: Double get() = if (duration > 0) min(1.0, max(0.0, currentTime / duration)) else 0.0
    val bufferedFraction: Double get() = if (duration > 0) min(1.0, max(0.0, buffered / duration)) else 0.0

    // MARK: Loading

    /** Starts playing [payload] (or fails without one), at [startTime]. */
    fun start(payload: MediaPayload?, startTime: Duration? = null, autoplay: Boolean = true) {
        if (isClosed || isStarted || phase != Phase.Loading) return
        if (payload == null || payload.data.isEmpty()) {
            phase = Phase.Failed
            return
        }
        isStarted = true
        pendingStart = (startTime?.toDouble(DurationUnit.SECONDS) ?: 0.0) to autoplay
        engine.setMuted(isMuted)
        engine.prepare(payload, this)
    }

    override fun onReady(duration: Double) {
        if (phase != Phase.Loading || isClosed) return
        if (duration.isFinite() && duration > 0) this.duration = duration
        phase = Phase.Ready
        refresh()
        val start = pendingStart ?: return
        pendingStart = null
        if (start.first > 0) {
            currentTime = min(start.first, this.duration)
            engine.seekTo(currentTime)
        }
        if (start.second) play()
    }

    override fun onEnded() {
        currentTime = duration
        phase = Phase.Ended
        isPlaying = false
    }

    override fun onFailed() {
        phase = Phase.Failed
        isPlaying = false
        release()
    }

    override fun onFirstFrame() {
        isShowingVideo = true
    }

    override fun onPausedBySystem() {
        isPlaying = false
    }

    /** A tick (30 a second while playing): where it is, how far it is loaded. */
    fun refresh() {
        if (!isStarted || isClosed) return
        buffered = max(buffered, engine.buffered)
        if (isScrubbing || phase == Phase.Ended) return
        val position = engine.position
        if (position.isFinite()) currentTime = max(0.0, min(position, duration))
    }

    // MARK: Controls

    fun togglePlay() {
        if (isPlaying) pause() else play()
    }

    fun play() {
        if (phase != Phase.Ready && phase != Phase.Ended) return
        if (phase == Phase.Ended) {
            phase = Phase.Ready
            currentTime = 0.0
            engine.seekTo(0.0)
        }
        engine.play()
        isPlaying = true
    }

    fun pause() {
        if (isStarted && !isClosed) engine.pause()
        isPlaying = false
    }

    fun toggleMute() {
        isMuted = !isMuted
        if (isStarted && !isClosed) engine.setMuted(isMuted)
    }

    /** Jumps by [seconds] (the screen reader's adjustable scrubber). */
    fun skip(seconds: Double) {
        if (phase != Phase.Ready && phase != Phase.Ended) return
        if (phase == Phase.Ended && seconds < 0) phase = Phase.Ready
        val target = max(0.0, min(duration, currentTime + seconds))
        currentTime = target
        engine.seekTo(target)
    }

    // MARK: Scrubbing

    fun beginScrub() {
        if (isScrubbing || (phase != Phase.Ready && phase != Phase.Ended)) return
        isScrubbing = true
        resumesAfterScrub = isPlaying
        engine.pause()
        isPlaying = false
        if (phase == Phase.Ended) phase = Phase.Ready
    }

    fun scrub(fraction: Double) {
        if (!isScrubbing) return
        currentTime = max(0.0, min(1.0, fraction)) * duration
        engine.seekTo(currentTime)
    }

    fun endScrub() {
        if (!isScrubbing) return
        isScrubbing = false
        if (resumesAfterScrub) play()
        resumesAfterScrub = false
    }

    // MARK: Closing

    /** Stops at once and lets go of everything decrypted. */
    fun close() {
        if (isClosed) return
        pause()
        isClosed = true
        release()
    }

    private fun release() {
        if (isStarted) engine.release()
        isPlaying = false
    }
}

/**
 * ExoPlayer playing the clip's bytes from memory (`ByteArrayDataSource`: never a file), with movie
 * audio focus handled by the player and “audio becoming noisy” pausing it.
 */
@OptIn(UnstableApi::class)
class ExoVideoEngine(context: Context) : VideoEngine {
    private val exo: ExoPlayer = ExoPlayer.Builder(context.applicationContext)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        .build()
    private var listener: VideoEngine.Listener? = null
    private var reportedReady = false
    private var released = false

    override val player: Player? get() = if (released) null else exo
    override val position: Double get() = if (released) 0.0 else exo.currentPosition / 1000.0
    override val buffered: Double get() = if (released) 0.0 else exo.bufferedPosition / 1000.0

    private val events = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> if (!reportedReady) {
                    reportedReady = true
                    val duration = exo.duration
                    listener?.onReady(if (duration == C.TIME_UNSET) 0.0 else duration / 1000.0)
                }
                Player.STATE_ENDED -> {
                    exo.playWhenReady = false
                    listener?.onEnded()
                }
                else -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            listener?.onFailed()
        }

        override fun onRenderedFirstFrame() {
            listener?.onFirstFrame()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            val bySystem = reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS ||
                reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY
            if (!playWhenReady && bySystem) listener?.onPausedBySystem()
        }
    }

    override fun prepare(payload: MediaPayload, listener: VideoEngine.Listener) {
        this.listener = listener
        val bytes = payload.data.toByteArray()
        val factory = DataSource.Factory { ByteArrayDataSource(bytes) }
        val source = ProgressiveMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(Uri.parse("memory://clip")))
        exo.addListener(events)
        exo.setMediaSource(source)
        exo.playWhenReady = false
        exo.prepare()
    }

    override fun play() {
        if (!released) exo.play()
    }

    override fun pause() {
        if (!released) exo.pause()
    }

    override fun seekTo(seconds: Double) {
        if (!released) exo.seekTo((seconds * 1000).toLong())
    }

    override fun setMuted(muted: Boolean) {
        if (!released) exo.volume = if (muted) 0f else 1f
    }

    override fun release() {
        if (released) return
        released = true
        listener = null
        exo.removeListener(events)
        exo.stop()
        exo.clearMediaItems()
        exo.release()
    }
}
