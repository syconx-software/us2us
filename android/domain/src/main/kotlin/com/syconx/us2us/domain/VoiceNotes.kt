package com.syconx.us2us.domain

import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

// 5.1 › mic (proposed: the design draws the voice bubble and the mic, not the recording): what a
// voice message is, the rules of recording one, the mic's press-and-hold and the player's speeds.
// Plain values and pure functions: the recorder, the player and the views build on them, unit tests
// drive them directly. Nothing in this file touches the disk.

/** A voice message recorded on this phone, held in memory only: the AAC (M4A) bytes, its length and the waveform it is sent with. */
data class VoiceRecording(
    /** AAC in an M4A container (brp.proto `Attachment`, kind VOICE). */
    val audio: Bytes,
    val duration: Duration,
    /** Up to 100 amplitude samples, 0…255 (brp.proto `Attachment.waveform`). */
    val waveform: Bytes,
) {
    /** `Attachment.duration_ms`. */
    val durationMilliseconds: Long
        get() = (duration.coerceAtLeast(Duration.ZERO).inWholeMicroseconds / 1000.0).roundToInt().toLong().coerceIn(0, UInt.MAX_VALUE.toLong())

    companion object {
        const val CONTENT_TYPE = "audio/mp4"
    }
}

/** The rules of a voice message (owner: like WhatsApp / iMessage). */
object VoiceNoteRules {
    /** Shorter recordings are thrown away (the hold hint shows instead). */
    val minimumDuration: Duration = 1.seconds

    /** Recording stops by itself here; the recording then waits to be sent (held: on release; locked: with “Gönder”). */
    val maximumDuration: Duration = 5.minutes

    /** The mic must be held this long before recording starts: a tap only shows the hint and never opens the microphone. */
    val holdDelay: Duration = 200.milliseconds

    /** Sliding the held mic this far left cancels (dp). */
    const val CANCEL_DISTANCE = 110f

    /** Sliding the held mic this far up locks the recording (hands-free, dp). */
    const val LOCK_DISTANCE = 80f

    /**
     * A received voice message larger than this is not played (five minutes at 64 kbit/s are about
     * 2.4 MB): decrypting it into memory would only exhaust it.
     */
    const val MAXIMUM_PLAYABLE_BYTES: Long = 16L * 1024 * 1024

    fun isLongEnough(duration: Duration): Boolean = duration >= minimumDuration

    fun hasReachedMaximum(elapsed: Duration): Boolean = elapsed >= maximumDuration
}

/** The waveform of a voice message: input levels (0…1) while recording, up to 100 samples (0…255) on the wire, bars (0…1) in the bubble. */
object VoiceWaveform {
    /** brp.proto: “up to 100 amplitude samples”. */
    const val MAX_SAMPLES = ContentLimits.MAX_WAVEFORM_SAMPLES

    /** The quietest level drawn (dBFS); anything below is the floor. */
    const val FLOOR_DECIBELS = -50f

    /** A meter reading (dBFS, 0 = full scale) as a level 0…1, linear in decibels above the floor. */
    fun level(decibels: Float): Double {
        if (!decibels.isFinite()) return 0.0
        val clamped = minOf(0f, maxOf(FLOOR_DECIBELS, decibels))
        return ((clamped - FLOOR_DECIBELS) / -FLOOR_DECIBELS).toDouble()
    }

    /**
     * Levels (0…1) evenly downsampled to at most [maxCount] samples (the mean of each of the equal
     * stretches) as bytes 0…255. Fewer levels than [maxCount] are kept one for one.
     */
    fun samples(levels: List<Double>, maxCount: Int = MAX_SAMPLES): List<Int> {
        if (levels.isEmpty() || maxCount <= 0) return emptyList()
        return resample(levels, minOf(maxCount, levels.size)).map { level ->
            Math.round(minOf(1.0, maxOf(0.0, level)) * 255).toInt()
        }
    }

    /** [samples] as the attachment's `waveform` bytes. */
    fun data(levels: List<Double>): Bytes = Bytes.ofUnsigned(samples(levels))

    /**
     * [values] as [count] values: the mean of each of [count] equal stretches (with fewer values than
     * [count], each value repeats over its share).
     */
    fun resample(values: List<Double>, count: Int): List<Double> {
        if (values.isEmpty() || count <= 0) return emptyList()
        val total = values.size
        return (0 until count).map { index ->
            val start = index * total / count
            val end = minOf(total, maxOf(start + 1, (index + 1) * total / count))
            var sum = 0.0
            for (i in start until end) sum += values[i]
            sum / (end - start)
        }
    }

    /** The bubble's bars (heights 0…1) from the samples a message carries: never lower than a sliver; without samples an even, quiet line. */
    fun bars(data: Bytes?, count: Int): List<Double> {
        if (count <= 0) return emptyList()
        if (data == null || data.isEmpty()) return List(count) { 0.3 }
        return resample(data.toUnsignedList().map { it / 255.0 }, count).map { maxOf(0.08, it) }
    }
}

/** How far the finger slid since the press (dp); both values ≤ 0 while holding (left and up). */
data class SlideOffset(val x: Float, val y: Float) {
    companion object {
        val Zero = SlideOffset(0f, 0f)
    }
}

/**
 * The mic's press-and-hold (5.1, proposed): press → (after the hold delay) recording while held →
 * release sends; slide left far enough to cancel, up far enough to lock (recording goes on
 * hands-free, with delete · stop · send). A release before recording started, or of a recording
 * shorter than the minimum, shows the hint instead.
 */
data class VoiceHoldGesture(
    val phase: Phase = Phase.Idle,
    /** How far the finger slid since the press, leftwards and upwards only (both ≤ 0), while holding. */
    val translation: SlideOffset = SlideOffset.Zero,
) {
    enum class Phase {
        /** Nothing pressed. */
        Idle,

        /** The finger is down, recording has not started yet. */
        Pressed,

        /** Recording while the finger stays down. */
        Holding,

        /** Slid up past the lock: the finger is free. */
        Locked,
    }

    enum class Event {
        /** The hold delay passed: start recording. */
        StartRecording,

        /** Slid up far enough: recording goes on hands-free. */
        Lock,

        /** Released (or “Gönder” when locked): send what was recorded. */
        Send,

        /** Slid left far enough (or “Sil” when locked): throw it away. */
        Cancel,

        /** Released too early: nothing to send, show “Kaydetmek için basılı tut”. */
        Hint,
    }

    /** A gesture step: the new gesture and what to do, if anything. */
    data class Step(val gesture: VoiceHoldGesture, val event: Event?)

    /** 0…1 towards cancelling (the “İptal için kaydır” label fades with it). */
    val cancelProgress: Double
        get() = if (phase != Phase.Holding) 0.0 else progress(-translation.x, VoiceNoteRules.CANCEL_DISTANCE)

    /** 0…1 towards locking (the lock above the mic follows it). */
    val lockProgress: Double
        get() = if (phase != Phase.Holding) 0.0 else progress(-translation.y, VoiceNoteRules.LOCK_DISTANCE)

    private fun progress(distance: Float, limit: Float): Double = if (distance <= 0f) 0.0 else minOf(1.0, (distance / limit).toDouble())

    /** The finger went down; `null` if something is already going on. */
    fun press(): VoiceHoldGesture? = if (phase != Phase.Idle) null else VoiceHoldGesture(Phase.Pressed, SlideOffset.Zero)

    /** The finger stayed down for the hold delay. */
    fun holdDelayPassed(): Step =
        if (phase != Phase.Pressed) Step(this, null) else Step(copy(phase = Phase.Holding), Event.StartRecording)

    /** The finger moved; [moved] from where it went down. */
    fun slide(moved: SlideOffset): Step {
        if (phase != Phase.Holding) return Step(this, null)
        val clamped = SlideOffset(minOf(0f, moved.x), minOf(0f, moved.y))
        if (-clamped.x >= VoiceNoteRules.CANCEL_DISTANCE) return Step(VoiceHoldGesture(), Event.Cancel)
        if (-clamped.y >= VoiceNoteRules.LOCK_DISTANCE) return Step(VoiceHoldGesture(Phase.Locked, SlideOffset.Zero), Event.Lock)
        return Step(copy(translation = clamped), null)
    }

    /** The finger lifted, after [recorded] of recording. */
    fun release(recorded: Duration): Step = when (phase) {
        Phase.Pressed -> Step(VoiceHoldGesture(), Event.Hint)
        Phase.Holding -> Step(VoiceHoldGesture(), if (VoiceNoteRules.isLongEnough(recorded)) Event.Send else Event.Hint)
        Phase.Idle, Phase.Locked -> Step(this, null)
    }

    /** “Gönder” while locked. */
    fun sendLocked(recorded: Duration): Step =
        if (phase != Phase.Locked) {
            Step(this, null)
        } else {
            Step(copy(phase = Phase.Idle), if (VoiceNoteRules.isLongEnough(recorded)) Event.Send else Event.Hint)
        }

    /** “Sil” while locked. */
    fun deleteLocked(): Step = if (phase != Phase.Locked) Step(this, null) else Step(copy(phase = Phase.Idle), Event.Cancel)

    /** Back to the start, whatever happened (a cancelled touch, an interruption, the app leaving the foreground). */
    fun reset(): VoiceHoldGesture = VoiceHoldGesture()
}

/** The voice player's speeds, cycled by the chip under the play button. */
enum class VoicePlaybackRate(val value: Float) {
    Normal(1f),
    OneAndHalf(1.5f),
    Double(2f),
    ;

    val next: VoicePlaybackRate
        get() = when (this) {
            Normal -> OneAndHalf
            OneAndHalf -> Double
            Double -> Normal
        }

    /** “1×”, “1,5×” (Turkish) / “1.5×” (English), “2×”. */
    fun label(locale: Locale): String = when (this) {
        Normal -> "1×"
        Double -> "2×"
        OneAndHalf -> {
            val separator = if (locale.language == "en") '.' else DecimalFormatSymbols.getInstance(locale).decimalSeparator
            "1${separator}5×"
        }
    }
}
