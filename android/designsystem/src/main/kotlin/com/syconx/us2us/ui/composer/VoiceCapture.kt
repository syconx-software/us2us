package com.syconx.us2us.ui.composer

import com.syconx.us2us.domain.Bytes
import kotlin.time.Duration

// 5.1 › mic: where the composer's voice recordings come from. The conversation's recorder works
// on these; the platform's microphone (or, in tests and the harness, a simulated one) implements them.

/** Whether the app may use the microphone. */
enum class VoicePermission { Undetermined, Granted, Denied }

/** One recording in progress. */
interface VoiceCapture {
    /** Starts recording; `false` when the microphone is not available right now. */
    fun start(): Boolean

    /** Recorded so far. */
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

/** Where recordings come from: the microphone, or (harness, tests) a simulated one. */
interface VoiceCaptureDevice {
    val permission: VoicePermission

    /** Asks once (the system prompt); `true` when allowed. */
    suspend fun requestPermission(): Boolean

    fun makeCapture(): VoiceCapture

    companion object {
        /** No microphone (previews and tests): recording is never possible. */
        val Unavailable = object : VoiceCaptureDevice {
            override val permission = VoicePermission.Granted

            override suspend fun requestPermission() = false

            override fun makeCapture(): VoiceCapture = object : VoiceCapture {
                override fun start() = false
                override val currentTime = Duration.ZERO
                override val isRecording = false
                override fun level() = 0.0
                override fun finish(): Bytes? = null
                override fun discard() {}
                override var onInterrupted: (() -> Unit)? = null
            }
        }
    }
}
