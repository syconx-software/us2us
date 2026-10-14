package com.syconx.us2us.ui.motion

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Motion is very soft: the design curve, ease-out and critically damped springs only. */
class MotionTest {
    @Test
    fun durationsAreTheTokens() {
        assertEquals(0.16f, U2Motion.Duration.press, 0f)
        assertEquals(0.24f, U2Motion.Duration.micro, 0f)
        assertEquals(0.36f, U2Motion.Duration.state, 0f)
        assertEquals(0.5f, U2Motion.Duration.screen, 0f)
        assertEquals(0.55f, U2Motion.Duration.sheet, 0f)
        assertEquals(0.8f, U2Motion.Duration.ambient, 0f)
        assertEquals(360, U2Motion.state.millis)
        assertEquals(500, U2Motion.screen.millis)
        assertEquals(240, U2Motion.release.millis)
        assertEquals(160, U2Motion.press.millis)
    }

    /** cubic-bezier(0.45, 0, 0.55, 1) is symmetric: half-way at t = 0.5, slow at both ends. */
    @Test
    fun softCurveIsTheDesignsBezier() {
        val soft = U2Motion.SoftEasing
        assertEquals(0f, soft.transform(0f), 0.0001f)
        assertEquals(0.5f, soft.transform(0.5f), 0.001f)
        assertEquals(1f, soft.transform(1f), 0.0001f)
        assertEquals(1f - soft.transform(0.25f), soft.transform(0.75f), 0.001f)
        assertTrue(soft.transform(0.1f) < 0.05f)
        // ease-out: fast start, never overshoots.
        val out = U2Motion.OutEasing
        assertTrue(out.transform(0.25f) > 0.25f)
        for (i in 0..100) assertTrue(out.transform(i / 100f) <= 1.0001f)
    }

    /** SwiftUI `.spring(response: 0.55, dampingFraction: 1)` → stiffness (2π / 0.55)², damping ratio 1. */
    @Test
    fun springsAreCriticallyDamped() {
        assertEquals(130.5f, U2Motion.SpringStiffness, 0.1f)
        assertEquals(223.8f, U2Motion.SpringQuickStiffness, 0.1f)
        val spring = U2Motion.spring<Float>()
        assertEquals(1f, spring.dampingRatio, 0f)
        assertEquals(U2Motion.SpringStiffness, spring.stiffness, 0f)
        assertEquals(1f, U2Motion.springQuick<Float>().dampingRatio, 0f)
        // The keyboard curve is over-damped: no overshoot either.
        assertTrue(U2Motion.keyboard<Float>().dampingRatio >= 1f)
    }

    @Test
    fun reduceMotionBecomesAShortCrossFade() {
        val spec = U2Motion.spring<Float>()
        assertSame(spec, U2Motion.resolve(spec, reduceMotion = false))
        val reduced = U2Motion.resolve(spec, reduceMotion = true)
        assertTrue(reduced is TweenSpec<*>)
        assertEquals(300, (reduced as TweenSpec<*>).durationMillis)
        assertEquals(U2Motion.reducedCrossFade, U2Motion.resolve(U2Motion.screen, reduceMotion = true))
        assertEquals(U2Motion.screen, U2Motion.resolve(U2Motion.screen, reduceMotion = false))
        assertTrue(U2Motion.resolve(U2Motion.spring<Float>(), reduceMotion = false) is SpringSpec<*>)
    }

    @Test
    fun releaseVelocityIsRelativeAndClamped() {
        assertEquals(2f, U2Motion.relativeVelocity(200f, 100f), 0.0001f)
        assertEquals(12f, U2Motion.relativeVelocity(5000f, 10f), 0.0001f)
        assertEquals(-12f, U2Motion.relativeVelocity(-5000f, 10f), 0.0001f)
        assertEquals(0f, U2Motion.relativeVelocity(300f, 0.2f), 0.0001f)
        // Absolute, for Animatable.animateTo(initialVelocity = …).
        assertEquals(120f, U2Motion.settleVelocity(5000f, 10f), 0.0001f)
        assertEquals(-200f, U2Motion.settleVelocity(-200f, -100f), 0.0001f)
    }

    @Test
    fun timingsBuildTweens() {
        val spec = U2Motion.state.delayed(0.25f).spec<Float>()
        assertEquals(360, spec.durationMillis)
        assertEquals(250, spec.delay)
        assertSame(U2Motion.SoftEasing, spec.easing)
        assertSame(U2Motion.OutEasing, U2Motion.appear.spec<Float>().easing)
    }
}
