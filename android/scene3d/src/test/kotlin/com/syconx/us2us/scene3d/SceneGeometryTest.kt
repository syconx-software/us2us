package com.syconx.us2us.scene3d

import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The geometry, colours and maths against the values the iOS sources produce (SceneGeometry.swift, SceneMath.swift). */
class SceneGeometryTest {
    private fun assertFloats(expected: FloatArray, actual: FloatArray, tolerance: Float = 2e-7f) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            assertTrue("[$i] expected ${expected[i]}, was ${actual[i]}", abs(expected[i] - actual[i]) <= tolerance)
        }
    }

    @Test
    fun cupLatheMatchesThreeJs() {
        val cup = SceneGeometry.cup()
        assertEquals(2628, cup.vertices.size)
        assertEquals(2160, cup.indices.size)
        // Second profile point (bottom edge, smoothed into the wall) and the last one (un-normalised).
        assertFloats(floatArrayOf(0f, 0f, 0.36f, 0f, -0.4267075f, 0.9043897f), cup.vertices.copyOfRange(6, 12))
        assertFloats(floatArrayOf(0f, 0.025f, 0.001f, 0f, 0.334f, 0f), cup.vertices.copyOfRange(30, 36))
        // a, b, d, c, d, b with b one meridian (six profile points) further.
        assertEquals(6, cup.indices[1] - cup.indices[0])
    }

    @Test
    fun rimStringAndLines() {
        val rim = SceneGeometry.rim()
        assertEquals(3201, rim.vertices.size)
        assertEquals(5760, rim.indices.size)
        assertFloats(floatArrayOf(0.5255f, 0f, 0f, 0.52437484f, 0.034369346f, 0f), rim.vertices.copyOfRange(0, 6))

        val string = SceneGeometry.string()
        assertEquals(54, string.vertices.size)
        assertEquals(48, string.indices.size)
        assertFloats(floatArrayOf(-0.5f, 3.061617e-17f, 0.009f, -0.5f, 0.006363961f, 0.006363961f), string.vertices.copyOfRange(0, 6))

        val wire = SceneGeometry.cupWire()
        assertEquals(1404, wire.size)
        assertFloats(floatArrayOf(0.12361976f, 0f, 0.3396425f, 0.17856188f, 1.1f, 0.4905947f), wire.copyOfRange(6, 12))

        val lattice = SceneGeometry.lattice()
        assertEquals(1332, lattice.size)
        assertFloats(
            floatArrayOf(-0.5f, 0.3f, 0f, -0.5f, 0.15f, 0.25980762f, -0.5f, 0.3f, 0f, -0.41666666f, 0.25980762f, 0.15f),
            lattice.copyOfRange(0, 12),
        )
        assertEquals(36, SceneGeometry.hexRing(0.3).size)
        assertEquals(540, SceneGeometry.glowLine().size)
    }

    @Test
    fun dustIsTheSameFieldOnEveryLaunch() {
        val dust = SceneGeometry.dust()
        assertEquals(SceneGeometry.DUST_COUNT * 3, dust.size)
        assertFloats(
            floatArrayOf(-7.2607703f, -2.2993312f, -3.14358f, 7.882511f, 1.0247182f, -1.373919f, -3.7758622f, 0.1129151f, -1.3678697f),
            dust.copyOfRange(0, 9),
            tolerance = 1e-6f,
        )
        assertFloats(floatArrayOf(1.2333378f, -0.720753f, -4.5030494f), dust.copyOfRange(1257, 1260), tolerance = 1e-6f)
        val center = SceneGeometry.boundsCenter(dust)
        assertFloats(floatArrayOf(-0.03075552f, 0.012789011f, -1.4778488f), floatArrayOf(center.x, center.y, center.z), tolerance = 1e-6f)
    }

    @Test
    fun mulberry32() {
        val random = SceneRandom(1)
        assertEquals(0.6270739405881613, random.next(), 0.0)
        assertEquals(0.002735721180215478, random.next(), 0.0)
        assertEquals(0.5274470399599522, random.next(), 0.0)
    }

    @Test
    fun dotTexture() {
        val t = SceneGeometry.dotTexture()
        fun alpha(x: Int, y: Int) = t[(y * 64 + x) * 4 + 3].toInt() and 0xFF
        assertEquals(245, alpha(32, 32))
        assertEquals(0, alpha(0, 0))
        assertEquals(144, alpha(40, 32))
        assertEquals(83, alpha(50, 32))
        assertEquals(47, alpha(20, 10))
        // White wherever there is coverage (WebGL un-premultiplies the canvas), black elsewhere.
        assertEquals(255, t[(32 * 64 + 32) * 4].toInt() and 0xFF)
        assertEquals(0, t[0].toInt())
    }

    @Test
    fun cameraAndWorldMatrices() {
        val view = Mat4.lookAt(Vec3(0f, 0.55f, 7.8f), Vec3.Zero)
        assertFloats(
            floatArrayOf(1f, 0f, 0f, 0f, 0f, 0.99752325f, 0.070338175f, 0f, 0f, -0.070338175f, 0.99752325f, 0f, 0f, -5.9604645e-08f, -7.8193674f, 1f),
            view.m,
            tolerance = 1e-6f,
        )
        val world = Mat4.rotationX((kotlin.math.sin(4.5 * 0.21) * 0.03).toFloat()) *
            Mat4.rotationY((-0.42 + kotlin.math.sin(4.5 * 0.3) * 0.07).toFloat())
        val cup = world * Mat4.translation(-0.95f, 0f, 0f) * Mat4.rotationZ(1f * SceneMath.PI_F / 2)
        assertFloats(
            floatArrayOf(
                7.0876574e-08f, 0.9997044f, 0.024312539f, 0f,
                -0.93878865f, 0.008375581f, -0.3443918f, 0f,
                -0.34449363f, -0.022824312f, 0.9385112f, 0f,
                -0.8918492f, 0.00795673f, -0.3271722f, 1f,
            ),
            cup.m,
            tolerance = 1e-6f,
        )
        assertFloats(
            floatArrayOf(7.140777e-08f, 0.9955182f, 0.09456969f, 0f, -0.93878865f, 0.032578725f, -0.34294966f, 0f, -0.3444936f, -0.08878095f, 0.93458116f, 0f),
            (view * cup).normalMatrix.m.copyOfRange(0, 12),
            tolerance = 1e-6f,
        )
        val key = view.transformDirection(Vec3(-3f, 4f, 5f))
        assertFloats(floatArrayOf(-0.42426404f, 0.51454777f, 0.7451447f), floatArrayOf(key.x, key.y, key.z), tolerance = 1e-6f)
    }

    @Test
    fun paletteComesFromTheTokens() {
        val dark = ScenePalette.Dark
        assertArrayEquals(floatArrayOf(0x91 / 255f, 0x84 / 255f, 0xD9 / 255f), dark.edge.encoded(1.0).copyOf(3), 1e-7f)
        assertTrue(dark.additive)
        assertEquals(0.34, dark.wireOpacity, 0.0)
        assertEquals(0.1, dark.glowOpacity, 0.0)
        val light = ScenePalette.Light
        assertTrue(!light.additive)
        assertEquals(0.55, light.wireOpacity, 0.0)
        assertEquals(0.32, light.dustOpacity, 0.0)
        assertEquals(SceneState.Welcome.camera, SceneVector(0.0, 0.55, 7.8))
        assertEquals(-0.42, SceneState.Welcome.yaw, 0.0)
        assertEquals(0.95, SceneState.Welcome.halfLength, 0.0)
    }
}
