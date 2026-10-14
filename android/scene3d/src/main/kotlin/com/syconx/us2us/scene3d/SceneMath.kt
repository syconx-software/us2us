// us2us — small math helpers for the welcome scene (three.js conventions:
// right-handed, camera looking down -z, column vectors, Euler order XYZ).

package com.syconx.us2us.scene3d

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

internal object SceneMath {
    fun clamp(x: Double, a: Double, b: Double): Double = min(b, max(a, x))

    /** `sm` in us2us-scene.js (smoothstep). */
    fun smoothstep(a: Double, b: Double, x: Double): Double {
        val t = clamp((x - a) / (b - a), 0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t

    /** JavaScript `x % 1` for the non-negative values the scene uses. */
    fun fract(x: Double): Double = x % 1.0

    /** three.js `SRGBToLinear` (what `Color.setHex` stores with colour management on). */
    fun srgbToLinear(c: Double): Double =
        if (c < 0.04045) c * 0.0773993808 else (c * 0.9478672986 + 0.0521327014).pow(2.4)

    /** three.js `LinearToSRGB`. */
    fun linearToSrgb(c: Double): Double = if (c < 0.0031308) c * 12.92 else 1.055 * c.pow(0.41666) - 0.055

    const val PI_F: Float = PI.toFloat()
}

/** A point or direction (single precision, as on the GPU). */
internal data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    operator fun unaryMinus() = Vec3(-x, -y, -z)
    infix fun dot(o: Vec3): Float = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    val length: Float get() = sqrt(this dot this)
    fun normalized(): Vec3 = length.let { l -> if (l > 0f) Vec3(x / l, y / l, z / l) else this }

    companion object {
        val Zero = Vec3(0f, 0f, 0f)
    }
}

/** A point in double precision (scene states, cameras). */
data class SceneVector(val x: Double, val y: Double, val z: Double) {
    internal operator fun plus(o: SceneVector) = SceneVector(x + o.x, y + o.y, z + o.z)
    internal operator fun minus(o: SceneVector) = SceneVector(x - o.x, y - o.y, z - o.z)
    internal operator fun times(s: Double) = SceneVector(x * s, y * s, z * s)
    internal fun toVec3() = Vec3(x.toFloat(), y.toFloat(), z.toFloat())

    companion object {
        val Zero = SceneVector(0.0, 0.0, 0.0)
    }
}

/**
 * A 4 × 4 matrix in column-major order (`m[column * 4 + row]`), the layout OpenGL uniforms take
 * and simd's `float4x4` uses.
 */
internal class Mat4(val m: FloatArray) {
    init {
        require(m.size == 16)
    }

    operator fun get(column: Int, row: Int): Float = m[column * 4 + row]

    operator fun times(o: Mat4): Mat4 {
        val r = FloatArray(16)
        for (c in 0 until 4) {
            for (row in 0 until 4) {
                var s = 0f
                for (k in 0 until 4) s += m[k * 4 + row] * o.m[c * 4 + k]
                r[c * 4 + row] = s
            }
        }
        return Mat4(r)
    }

    /** `(self * (x, y, z, w))`. */
    fun transform(x: Float, y: Float, z: Float, w: Float): FloatArray = floatArrayOf(
        m[0] * x + m[4] * y + m[8] * z + m[12] * w,
        m[1] * x + m[5] * y + m[9] * z + m[13] * w,
        m[2] * x + m[6] * y + m[10] * z + m[14] * w,
        m[3] * x + m[7] * y + m[11] * z + m[15] * w,
    )

    fun transformPoint(p: Vec3): Vec3 = transform(p.x, p.y, p.z, 1f).let { Vec3(it[0], it[1], it[2]) }

    fun transformDirection(d: Vec3): Vec3 = transform(d.x, d.y, d.z, 0f).let { Vec3(it[0], it[1], it[2]).normalized() }

    /** Inverse-transpose of the upper 3 × 3, embedded in a 4 × 4 (three.js `normalMatrix`). */
    val normalMatrix: Mat4
        get() {
            val a = m[0].toDouble(); val b = m[4].toDouble(); val c = m[8].toDouble()
            val d = m[1].toDouble(); val e = m[5].toDouble(); val f = m[9].toDouble()
            val g = m[2].toDouble(); val h = m[6].toDouble(); val i = m[10].toDouble()
            // Rows a b c / d e f / g h i; the cofactor matrix divided by the determinant is the
            // inverse transpose.
            val ca = e * i - f * h; val cb = -(d * i - f * g); val cc = d * h - e * g
            val cd = -(b * i - c * h); val ce = a * i - c * g; val cf = -(a * h - b * g)
            val cg = b * f - c * e; val ch = -(a * f - c * d); val ci = a * e - b * d
            val det = a * ca + b * cb + c * cc
            val s = if (det != 0.0) 1.0 / det else 0.0
            // Inverse transpose in rows: ca cb cc / cd ce cf / cg ch ci (times s).
            return Mat4(
                floatArrayOf(
                    (ca * s).toFloat(), (cd * s).toFloat(), (cg * s).toFloat(), 0f,
                    (cb * s).toFloat(), (ce * s).toFloat(), (ch * s).toFloat(), 0f,
                    (cc * s).toFloat(), (cf * s).toFloat(), (ci * s).toFloat(), 0f,
                    0f, 0f, 0f, 1f,
                ),
            )
        }

    companion object {
        val Identity = Mat4(floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f))

        fun translation(x: Float, y: Float, z: Float) =
            Mat4(floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, x, y, z, 1f))

        fun translation(p: Vec3) = translation(p.x, p.y, p.z)

        fun scale(x: Float, y: Float, z: Float) =
            Mat4(floatArrayOf(x, 0f, 0f, 0f, 0f, y, 0f, 0f, 0f, 0f, z, 0f, 0f, 0f, 0f, 1f))

        fun rotationX(a: Float): Mat4 {
            val c = cos(a); val s = sin(a)
            return Mat4(floatArrayOf(1f, 0f, 0f, 0f, 0f, c, s, 0f, 0f, -s, c, 0f, 0f, 0f, 0f, 1f))
        }

        fun rotationY(a: Float): Mat4 {
            val c = cos(a); val s = sin(a)
            return Mat4(floatArrayOf(c, 0f, -s, 0f, 0f, 1f, 0f, 0f, s, 0f, c, 0f, 0f, 0f, 0f, 1f))
        }

        fun rotationZ(a: Float): Mat4 {
            val c = cos(a); val s = sin(a)
            return Mat4(floatArrayOf(c, s, 0f, 0f, -s, c, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f))
        }

        /** View matrix of a camera at [eye] looking at [target] (three.js `Camera.lookAt`). */
        fun lookAt(eye: Vec3, target: Vec3, up: Vec3 = Vec3(0f, 1f, 0f)): Mat4 {
            val z = (eye - target).normalized()
            val x = (up cross z).normalized()
            val y = z cross x
            return Mat4(
                floatArrayOf(
                    x.x, y.x, z.x, 0f,
                    x.y, y.y, z.y, 0f,
                    x.z, y.z, z.z, 0f,
                    -(x dot eye), -(y dot eye), -(z dot eye), 1f,
                ),
            )
        }

        /**
         * Right-handed perspective projection with OpenGL's −1…1 clip depth (three.js
         * `makePerspective`); only the depth mapping differs from the Metal original.
         */
        fun perspective(fovyDegrees: Float, aspect: Float, near: Float, far: Float): Mat4 {
            val ys = 1f / tan(fovyDegrees * SceneMath.PI_F / 360f)
            val xs = ys / aspect
            val zs = (far + near) / (near - far)
            val zw = 2f * far * near / (near - far)
            return Mat4(floatArrayOf(xs, 0f, 0f, 0f, 0f, ys, 0f, 0f, 0f, 0f, zs, -1f, 0f, 0f, zw, 0f))
        }
    }
}

/** An sRGB colour given as 0xRRGGBB, as in the scene's palette. */
internal class SceneColor private constructor(val r: Double, val g: Double, val b: Double) {
    constructor(hex: Int) : this(((hex shr 16) and 0xFF) / 255.0, ((hex shr 8) and 0xFF) / 255.0, (hex and 0xFF) / 255.0)

    /** Linear-light components (three.js working colour space). */
    val linear: DoubleArray
        get() = doubleArrayOf(SceneMath.srgbToLinear(r), SceneMath.srgbToLinear(g), SceneMath.srgbToLinear(b))

    /** `Color.lerp` on the stored (linear) values, returned as sRGB again. */
    fun mixed(other: SceneColor, t: Double): SceneColor {
        if (t <= 0) return this
        val a = linear
        val o = other.linear
        return SceneColor(
            SceneMath.linearToSrgb(a[0] + (o[0] - a[0]) * t),
            SceneMath.linearToSrgb(a[1] + (o[1] - a[1]) * t),
            SceneMath.linearToSrgb(a[2] + (o[2] - a[2]) * t),
        )
    }

    /** sRGB-encoded rgb + alpha for the flat-colour shaders. */
    fun encoded(opacity: Double): FloatArray = floatArrayOf(r.toFloat(), g.toFloat(), b.toFloat(), opacity.toFloat())

    /** Linear rgb (optionally × intensity) + alpha for the lit and textured shaders. */
    fun linear(opacity: Double, intensity: Double = 1.0): FloatArray {
        val l = linear
        return floatArrayOf((l[0] * intensity).toFloat(), (l[1] * intensity).toFloat(), (l[2] * intensity).toFloat(), opacity.toFloat())
    }

    override fun equals(other: Any?): Boolean = other is SceneColor && other.r == r && other.g == g && other.b == b

    override fun hashCode(): Int = (r * 255).toInt() shl 16 or ((g * 255).toInt() shl 8) or (b * 255).toInt()
}

/**
 * mulberry32 — a tiny deterministic generator, so the dust field (and the reduce-motion still
 * frame) is identical on every launch.
 */
internal class SceneRandom(seed: Int) {
    private var state: Int = seed

    fun next(): Double {
        state += 0x6D2B79F5
        var t = (state xor (state ushr 15)) * (1 or state)
        t = (t + ((t xor (t ushr 7)) * (61 or t))) xor t
        return (t xor (t ushr 14)).toUInt().toDouble() / 4_294_967_296.0
    }
}
