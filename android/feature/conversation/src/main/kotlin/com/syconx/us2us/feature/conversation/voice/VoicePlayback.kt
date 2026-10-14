package com.syconx.us2us.feature.conversation.voice

import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.media.PlaybackParams
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.syconx.us2us.domain.MediaPayload
import com.syconx.us2us.domain.VoicePlaybackRate
import com.syconx.us2us.domain.nav.MessageId
import com.syconx.us2us.ui.bubbles.VoiceAudioEngine
import com.syconx.us2us.ui.bubbles.VoiceClip
import com.syconx.us2us.ui.bubbles.VoicePlaybackState
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The one voice message that plays in the open conversation (iOS `VoicePlayback`): its bytes in
 * memory, play / pause, where it is, the speed (1× → 1,5× → 2×), a finger dragging the position,
 * and where each message was left during this visit. Starting another message stops the one
 * before. Bubbles read it through `LocalVoicePlayback`; its properties are snapshot state.
 */
@Stable
class VoicePlayback(
    private val engine: VoiceAudioEngine,
    private val scope: CoroutineScope,
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

    /** The active message's position, 0…1. */
    var progress: Double by mutableDoubleStateOf(0.0)
        private set

    override var duration: Duration by mutableStateOf(Duration.ZERO)
        private set

    /** Where each message was left, during this visit. */
    private val positions = mutableStateMapOf<MessageId, Double>()

    /** Playback could not start or broke off (the composer says so above the mic). */
    var onPlaybackFailure: (() -> Unit)? = null

    /** Haptic for a failure. */
    var onError: (() -> Unit)? = null

    private var clip: VoiceClip? = null
    private var ticker: Job? = null
    private var startFraction = 0.0
    private var resumesAfterScrub = false

    override val currentTime: Duration get() = duration * progress

    fun isActive(id: MessageId): Boolean = activeId == id

    override fun shownFraction(id: MessageId, fallback: Double): Double =
        if (activeId == id) progress else positions[id] ?: fallback

    /** [id] is about to play: whatever played stops; the bubble shows the ring while its bytes come. */
    fun begin(id: MessageId, startFraction: Double = 0.0) {
        halt()
        activeId = id
        isLoading = true
        val start = positions[id] ?: startFraction
        this.startFraction = if (start < 0.999) max(0.0, start) else 0.0
        progress = this.startFraction
        duration = Duration.ZERO
    }

    /** The bytes of [id] arrived: it plays (from where it was left). `false` when it could not. */
    fun play(payload: MediaPayload?, id: MessageId): Boolean {
        if (activeId != id || !isLoading) return false
        isLoading = false
        val clip = payload?.takeIf { !it.data.isEmpty() }?.let(engine::prepare)
        if (clip == null) {
            fail()
            return false
        }
        clip.onFinish = { didFinish() }
        clip.onFailure = { fail() }
        clip.setRate(rate.value)
        if (startFraction > 0 && clip.duration.isPositive()) clip.seek(clip.duration * startFraction)
        if (!clip.play()) {
            clip.release()
            fail()
            return false
        }
        this.clip = clip
        duration = clip.duration
        progress = fraction(clip)
        isPlaying = true
        startTicking()
        return true
    }

    fun togglePause() {
        val clip = clip ?: return
        if (clip.isPlaying) pause() else resume()
    }

    fun pause() {
        val clip = clip ?: return
        clip.pause()
        isPlaying = false
        progress = fraction(clip)
        stopTicking()
    }

    private fun resume() {
        val clip = clip ?: return
        if (fraction(clip) >= 0.999) clip.seek(Duration.ZERO)
        if (!clip.play()) return
        isPlaying = true
        startTicking()
    }

    override fun cycleRate() {
        rate = rate.next
        clip?.setRate(rate.value)
    }

    override fun beginScrub(id: MessageId) {
        val clip = clip
        if (activeId != id || clip == null || isScrubbing) return
        isScrubbing = true
        resumesAfterScrub = clip.isPlaying
        clip.pause()
        stopTicking()
    }

    override fun scrub(id: MessageId, fraction: Double) {
        val clip = clip
        if (activeId != id || clip == null || !clip.duration.isPositive()) return
        val clamped = min(1.0, max(0.0, fraction))
        progress = clamped
        clip.seek(clip.duration * clamped)
    }

    override fun endScrub(id: MessageId) {
        if (activeId != id || !isScrubbing) return
        isScrubbing = false
        val resumes = resumesAfterScrub
        resumesAfterScrub = false
        when {
            progress >= 0.999 -> didFinish()
            resumes -> resume()
            else -> pause()
        }
    }

    override fun skip(id: MessageId, by: Duration) {
        val clip = clip
        if (activeId != id || clip == null || !clip.duration.isPositive()) return
        val target = (clip.position + by).coerceIn(Duration.ZERO, clip.duration)
        clip.seek(target)
        progress = fraction(clip)
    }

    /** Stops whatever plays; where it was is kept for this visit. */
    fun stop() = halt()

    /** Stops [id] if it plays and forgets where it was (the review of a recording that went). */
    fun forget(id: MessageId) {
        if (activeId == id) stop()
        positions.remove(id)
    }

    /** The conversation closes or the app leaves the foreground: everything goes. */
    fun reset() {
        stop()
        positions.clear()
    }

    private fun halt() {
        val id = activeId
        val clip = clip
        if (id != null && clip != null) {
            val left = fraction(clip)
            if (left > 0 && left < 0.999) positions[id] = left else positions.remove(id)
        }
        releaseClip()
        activeId = null
        isPlaying = false
        isLoading = false
        isScrubbing = false
        resumesAfterScrub = false
        progress = 0.0
        duration = Duration.ZERO
    }

    private fun didFinish() {
        activeId?.let { positions.remove(it) }
        releaseClip()
        activeId = null
        isPlaying = false
        progress = 0.0
        duration = Duration.ZERO
    }

    private fun fail() {
        releaseClip()
        activeId = null
        isPlaying = false
        isLoading = false
        isScrubbing = false
        progress = 0.0
        duration = Duration.ZERO
        onError?.invoke()
        onPlaybackFailure?.invoke()
    }

    private fun releaseClip() {
        stopTicking()
        clip?.let {
            it.onFinish = null
            it.onFailure = null
            it.release()
        }
        clip = null
    }

    private fun startTicking() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(50.milliseconds)
                val clip = clip ?: return@launch
                if (!isScrubbing) progress = fraction(clip)
            }
        }
    }

    private fun stopTicking() {
        ticker?.cancel()
        ticker = null
    }

    private fun fraction(clip: VoiceClip): Double {
        val total = clip.duration.toDouble(DurationUnit.MILLISECONDS)
        return if (total > 0) min(1.0, max(0.0, clip.position.toDouble(DurationUnit.MILLISECONDS) / total)) else 0.0
    }
}

/**
 * Voice messages through the platform's [MediaPlayer], fed from memory (a [MediaDataSource] over
 * the decrypted bytes): AAC in M4A from the recorder, WAV from the design fixtures.
 */
class MediaPlayerVoiceEngine : VoiceAudioEngine {
    override fun prepare(payload: MediaPayload): VoiceClip? {
        val player = MediaPlayer()
        return runCatching {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            player.setDataSource(MemoryDataSource(payload.data.toByteArray()))
            player.prepare()
            MediaPlayerClip(player)
        }.getOrElse {
            player.release()
            null
        }
    }
}

private class MediaPlayerClip(private val player: MediaPlayer) : VoiceClip {
    private var released = false
    override var onFinish: (() -> Unit)? = null
    override var onFailure: (() -> Unit)? = null

    init {
        player.setOnCompletionListener { onFinish?.invoke() }
        player.setOnErrorListener { _, _, _ ->
            onFailure?.invoke()
            true
        }
    }

    override val duration: Duration get() = if (released) Duration.ZERO else max(0, player.duration).milliseconds
    override val position: Duration get() = if (released) Duration.ZERO else max(0, player.currentPosition).milliseconds
    override val isPlaying: Boolean get() = !released && player.isPlaying

    override fun play(): Boolean = runCatching { player.start() }.isSuccess

    override fun pause() {
        runCatching { if (player.isPlaying) player.pause() }
    }

    override fun seek(to: Duration) {
        runCatching { player.seekTo(to.inWholeMilliseconds, MediaPlayer.SEEK_CLOSEST) }
    }

    override fun setRate(rate: Float) {
        runCatching {
            val playing = player.isPlaying
            player.playbackParams = PlaybackParams().setSpeed(rate)
            // Setting a speed starts a paused player: keep it paused.
            if (!playing) player.pause()
        }
    }

    override fun release() {
        if (released) return
        released = true
        runCatching { player.stop() }
        player.release()
    }
}

/** The bytes of one voice message, read by the player straight from memory. */
private class MemoryDataSource(private val data: ByteArray) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= data.size) return -1
        val count = min(size.toLong(), data.size - position).toInt()
        System.arraycopy(data, position.toInt(), buffer, offset, count)
        return count
    }

    override fun getSize(): Long = data.size.toLong()

    override fun close() {}
}
