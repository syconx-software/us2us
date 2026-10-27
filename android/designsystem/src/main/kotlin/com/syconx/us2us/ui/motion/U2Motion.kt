package com.syconx.us2us.ui.motion

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable
import com.syconx.us2us.ui.tokens.U2MotionTokens
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A timing: a duration on an easing curve, with an optional delay. The Compose spec of any
 * type comes from [spec]: `animateFloatAsState(x, U2Motion.state.spec())`.
 */
@Immutable
data class U2Timing(val seconds: Float, val easing: Easing, val delaySeconds: Float = 0f) {
    val millis: Int get() = (seconds * 1000f).roundToInt()

    /** The tween of this timing for any animated type. */
    fun <T> spec(): TweenSpec<T> = tween(
        durationMillis = millis,
        delayMillis = (delaySeconds * 1000f).roundToInt(),
        easing = easing,
    )

    /** The same timing, starting [seconds] later. */
    fun delayed(seconds: Float): U2Timing = copy(delaySeconds = seconds)
}

/**
 * Motion tokens (tokens.json › motion).
 *
 * Every animation is *very soft* — no bounce, no overshoot, responsive gentle
 * ease-in-out, nothing snaps. All curves here are the design's own
 * `cubic-bezier(0.45, 0, 0.55, 1)`, its ease-out, or a critically damped spring (damping
 * ratio 1, never below).
 *
 * Reduce motion (`LocalReduceMotion`, the system's “Remove animations”): ambient motion stops,
 * movement becomes a short cross-fade, shakes are removed; countdowns keep ticking because
 * they are information. Use [resolve], `rememberU2Spec`, the `U2Transitions` and
 * `u2PressEffect`: they all apply that rule.
 */
object U2Motion {
    /** Durations in seconds. */
    object Duration {
        /** Press feedback in. */
        const val press: Float = U2MotionTokens.Duration.press
        /** Dots, icons, chips: small state changes. */
        const val micro: Float = U2MotionTokens.Duration.micro
        /** Text/colour changes, messages appearing. */
        const val state: Float = U2MotionTokens.Duration.state
        /** Screen changes (push, page, root swap). */
        const val screen: Float = U2MotionTokens.Duration.screen
        /** Sheet backdrops. */
        const val sheet: Float = U2MotionTokens.Duration.sheet
        /** Short cross-fade replacing movement under reduce motion. */
        const val reducedCrossFade: Float = U2MotionTokens.Duration.reducedCrossFade
        /** Ambient colour changes (Ghost calm → urgent). */
        const val ambient: Float = U2MotionTokens.Duration.ambient
    }

    /** The design's curve, `cubic-bezier(0.45, 0, 0.55, 1)`: the default for every transition. */
    val SoftEasing: Easing = U2MotionTokens.curveSoft.let { CubicBezierEasing(it[0], it[1], it[2], it[3]) }

    /** `ease-out` = `cubic-bezier(0, 0, 0.58, 1)`: entrances and ring pulses. */
    val OutEasing: Easing = U2MotionTokens.curveOut.let { CubicBezierEasing(it[0], it[1], it[2], it[3]) }

    /** SwiftUI's `.easeInOut` = `cubic-bezier(0.42, 0, 0.58, 1)`: the reduce-motion cross-fade. */
    val EaseInOutEasing: Easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

    /** [seconds] on the soft curve. */
    fun soft(seconds: Float): U2Timing = U2Timing(seconds, SoftEasing)

    /** [seconds] on the ease-out curve. */
    fun out(seconds: Float): U2Timing = U2Timing(seconds, OutEasing)

    // Named uses.
    val press: U2Timing = soft(Duration.press)
    val release: U2Timing = soft(Duration.micro)
    val micro: U2Timing = soft(Duration.micro)
    val state: U2Timing = soft(Duration.state)
    val screen: U2Timing = out(Duration.screen)
    val sheet: U2Timing = out(Duration.sheet)
    val ambient: U2Timing = soft(Duration.ambient)
    val appear: U2Timing = out(Duration.state)

    /** The cross-fade that replaces movement when reduce motion is on. */
    val reducedCrossFade: U2Timing = U2Timing(Duration.reducedCrossFade, EaseInOutEasing)

    /**
     * Stiffness of a critically damped spring with SwiftUI's `response` (the period of the
     * undamped oscillation): k = (2π / response)² for unit mass.
     */
    fun stiffness(response: Float): Float {
        val omega = 2.0 * PI / response
        return (omega * omega).toFloat()
    }

    /** `springSoft`: damping ratio 1. Sheets following the finger, toggles, the reaction bar. */
    val SpringStiffness: Float = stiffness(U2MotionTokens.springResponse)

    /** The quicker spring for small interactive elements (toggle knob). */
    val SpringQuickStiffness: Float = stiffness(U2MotionTokens.springQuickResponse)

    /** The critically damped interactive spring: zero bounce. */
    fun <T> spring(visibilityThreshold: T? = null): SpringSpec<T> =
        spring(dampingRatio = 1f, stiffness = SpringStiffness, visibilityThreshold = visibilityThreshold)

    /** The same spring, a touch quicker (toggle knob). */
    fun <T> springQuick(visibilityThreshold: T? = null): SpringSpec<T> =
        spring(dampingRatio = 1f, stiffness = SpringQuickStiffness, visibilityThreshold = visibilityThreshold)

    /**
     * The software keyboard's own curve, a critically damped spring without overshoot
     * (SwiftUI `interpolatingSpring(mass: 3, stiffness: 1000, damping: 500)`): for content
     * that follows the keyboard (5.1 composer).
     */
    fun <T> keyboard(visibilityThreshold: T? = null): SpringSpec<T> {
        val mass = 3f
        val stiffness = 1000f
        val damping = 500f
        return spring(
            dampingRatio = damping / (2f * sqrt(stiffness * mass)),
            stiffness = stiffness / mass,
            visibilityThreshold = visibilityThreshold,
        )
    }

    /** [spec], or the short cross-fade when reduce motion is on. */
    fun <T> resolve(spec: AnimationSpec<T>, reduceMotion: Boolean): AnimationSpec<T> =
        if (reduceMotion) reducedCrossFade.spec() else spec

    /** [spec] for a finite animation (transitions), or the cross-fade when reduce motion is on. */
    fun <T> resolveFinite(spec: FiniteAnimationSpec<T>, reduceMotion: Boolean): FiniteAnimationSpec<T> =
        if (reduceMotion) reducedCrossFade.spec() else spec

    /** [timing], or the short cross-fade when reduce motion is on. */
    fun resolve(timing: U2Timing, reduceMotion: Boolean): U2Timing = if (reduceMotion) reducedCrossFade else timing

    // Gesture hand-offs: when a finger lets go of something it was dragging (a sheet, the edge
    // swipe back, a welcome page), the settle carries on at the finger's own speed instead of
    // starting from rest — no stall at release, no overshoot ([spring] with the release velocity).

    /**
     * The finger's speed ([pointsPerSecond], positive towards the target) relative to the
     * [remaining] distance (1 = the whole remaining distance in one second). Clamped to ±12, so
     * a flick over the last points never becomes a jump. A finger still moving away from the
     * target gives a negative value: the content eases on a little in that direction and turns
     * round — critically damped, it never passes the target.
     */
    fun relativeVelocity(pointsPerSecond: Float, remaining: Float): Float {
        if (abs(remaining) <= 0.5f) return 0f
        return (pointsPerSecond / remaining).coerceIn(-12f, 12f)
    }

    /**
     * The release velocity to pass to a Compose spring (`Animatable.animateTo(…, initialVelocity)`):
     * the finger's [velocity] clamped like [relativeVelocity]. Both arguments are in the animated
     * value's units, [remaining] = target − current.
     */
    fun settleVelocity(velocity: Float, remaining: Float): Float =
        relativeVelocity(velocity, remaining) * remaining
}
