package com.syconx.us2us.ui.bubbles

import com.syconx.us2us.domain.MediaPayload
import kotlin.time.Duration

// The audio behind the conversation's voice player: the platform's player (or, in tests, none)
// decodes a voice message's bytes in memory into a clip the player drives.

/** One voice message decoded and ready to play. */
interface VoiceClip {
    /** The clip's length (zero when unknown). */
    val duration: Duration

    /** Where it is. */
    val position: Duration

    val isPlaying: Boolean

    /** Starts or resumes; `false` when the platform refused. */
    fun play(): Boolean

    fun pause()

    fun seek(to: Duration)

    fun setRate(rate: Float)

    /** Lets go of the decoder and the bytes. */
    fun release()

    /** Played to the end. */
    var onFinish: (() -> Unit)?

    /** The decoder failed while playing. */
    var onFailure: (() -> Unit)?
}

/** Makes [VoiceClip]s from voice messages' bytes (in memory only: nothing is written to disk). */
fun interface VoiceAudioEngine {
    /** The payload as a clip; `null` when it cannot be decoded. */
    fun prepare(payload: MediaPayload): VoiceClip?

    companion object {
        /** Plays nothing (previews and tests): every payload fails. */
        val Unavailable = VoiceAudioEngine { null }
    }
}
