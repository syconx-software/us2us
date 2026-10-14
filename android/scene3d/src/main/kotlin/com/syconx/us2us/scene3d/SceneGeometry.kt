// us2us — geometry of the welcome scene, ported 1:1 from us2us-scene.js and
// the three.js r160 geometry generators it uses (LatheGeometry,
// TorusGeometry, CylinderGeometry), including their vertex order, winding
// and normal smoothing, so shading matches the design exactly.

package com.syconx.us2us.scene3d

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Cup proportions (us2us-scene.js `H`, `RB`, `RT`, `W`). */
internal object CupShape {
    const val HEIGHT = 1.1
    const val BOTTOM_RADIUS = 0.36
    const val TOP_RADIUS = 0.52
    const val WALL = 0.025
}

/** Indexed triangle mesh: positions (and normals when lit) as float3, interleaved. */
internal class SceneMesh(val vertices: FloatArray, val indices: ShortArray) {
    /** Floats per vertex: 6 when every position is followed by its normal, else 3. */
    fun stride(lit: Boolean): Int = if (lit) 6 else 3
}

internal object SceneGeometry {
    private const val TAU = PI * 2

    // Cup

    /** `LatheGeometry` of the cup profile with 72 segments; interleaved position + normal. */
    fun cup(): SceneMesh {
        val h = CupShape.HEIGHT
        val rb = CupShape.BOTTOM_RADIUS
        val rt = CupShape.TOP_RADIUS
        val w = CupShape.WALL
        val profile = listOf(0.001 to 0.0, rb to 0.0, rt to h, (rt - w) to h, (rb - w) to w, 0.001 to w)
        return lathe(profile, segments = 72)
    }

    fun lathe(points: List<Pair<Double, Double>>, segments: Int): SceneMesh {
        // Pre-computed normals of the initial meridian, exactly as three.js does it (un-normalised
        // neighbours summed, so the bottom edge is smoothed into the wall).
        val initNormals = ArrayList<Pair<Double, Double>>(points.size)
        var prev = 0.0 to 0.0
        val last = points.size - 1
        for (j in 0..last) {
            when (j) {
                0 -> {
                    val dx = points[1].first - points[0].first
                    val dy = points[1].second - points[0].second
                    val n = dy to -dx
                    prev = n
                    initNormals += normalize2(n)
                }
                last -> initNormals += prev
                else -> {
                    val dx = points[j + 1].first - points[j].first
                    val dy = points[j + 1].second - points[j].second
                    val cur = dy to -dx
                    initNormals += normalize2((cur.first + prev.first) to (cur.second + prev.second))
                    prev = cur
                }
            }
        }

        val vertices = FloatArray((segments + 1) * points.size * 6)
        var o = 0
        for (i in 0..segments) {
            val phi = i.toDouble() * (1.0 / segments) * TAU
            val s = sin(phi)
            val c = cos(phi)
            for (j in 0..last) {
                val p = points[j]
                val n = initNormals[j]
                vertices[o++] = (p.first * s).toFloat(); vertices[o++] = p.second.toFloat(); vertices[o++] = (p.first * c).toFloat()
                vertices[o++] = (n.first * s).toFloat(); vertices[o++] = n.second.toFloat(); vertices[o++] = (n.first * c).toFloat()
            }
        }

        val stride = points.size
        val indices = ShortArray(segments * last * 6)
        var k = 0
        for (i in 0 until segments) {
            for (j in 0 until last) {
                val base = j + i * stride
                val a = base
                val b = base + stride
                val c = base + stride + 1
                val d = base + 1
                for (index in intArrayOf(a, b, d, c, d, b)) indices[k++] = index.toShort()
            }
        }
        return SceneMesh(vertices, indices)
    }

    private fun normalize2(v: Pair<Double, Double>): Pair<Double, Double> {
        val l = sqrt(v.first * v.first + v.second * v.second)
        return if (l > 0) (v.first / l) to (v.second / l) else v
    }

    /** Rim: `TorusGeometry(RT - W / 2, 0.018, 10, 96)` (positions only, unlit). */
    fun rim(): SceneMesh =
        torus(radius = CupShape.TOP_RADIUS - CupShape.WALL / 2, tube = 0.018, radialSegments = 10, tubularSegments = 96)

    fun torus(radius: Double, tube: Double, radialSegments: Int, tubularSegments: Int): SceneMesh {
        val vertices = FloatArray((radialSegments + 1) * (tubularSegments + 1) * 3)
        var o = 0
        for (j in 0..radialSegments) {
            for (i in 0..tubularSegments) {
                val u = i.toDouble() / tubularSegments * TAU
                val v = j.toDouble() / radialSegments * TAU
                vertices[o++] = ((radius + tube * cos(v)) * cos(u)).toFloat()
                vertices[o++] = ((radius + tube * cos(v)) * sin(u)).toFloat()
                vertices[o++] = (tube * sin(v)).toFloat()
            }
        }
        val indices = ShortArray(radialSegments * tubularSegments * 6)
        var k = 0
        for (j in 1..radialSegments) {
            for (i in 1..tubularSegments) {
                val a = (tubularSegments + 1) * j + i - 1
                val b = (tubularSegments + 1) * (j - 1) + i - 1
                val c = (tubularSegments + 1) * (j - 1) + i
                val d = (tubularSegments + 1) * j + i
                for (index in intArrayOf(a, b, d, b, c, d)) indices[k++] = index.toShort()
            }
        }
        return SceneMesh(vertices, indices)
    }

    /**
     * The line: `CylinderGeometry(0.009, 0.009, 1, 8, 1, true).rotateZ(π / 2)` — an open tube of
     * length 1 along x (scaled to 2L per frame).
     */
    fun string(): SceneMesh {
        val radius = 0.009
        val height = 1.0
        val radialSegments = 8
        val heightSegments = 1
        val rz = PI / 2
        val cz = cos(rz)
        val sz = sin(rz)
        val vertices = FloatArray((heightSegments + 1) * (radialSegments + 1) * 3)
        val grid = Array(heightSegments + 1) { IntArray(radialSegments + 1) }
        var index = 0
        var o = 0
        for (y in 0..heightSegments) {
            val v = y.toDouble() / heightSegments
            for (x in 0..radialSegments) {
                val theta = x.toDouble() / radialSegments * TAU
                val px = radius * sin(theta)
                val py = -v * height + height / 2
                val pz = radius * cos(theta)
                vertices[o++] = (px * cz - py * sz).toFloat()
                vertices[o++] = (px * sz + py * cz).toFloat()
                vertices[o++] = pz.toFloat()
                grid[y][x] = index++
            }
        }
        val indices = ShortArray(radialSegments * heightSegments * 6)
        var k = 0
        for (x in 0 until radialSegments) {
            for (y in 0 until heightSegments) {
                val a = grid[y][x]
                val b = grid[y + 1][x]
                val c = grid[y + 1][x + 1]
                val d = grid[y][x + 1]
                for (i in intArrayOf(a, b, d, b, c, d)) indices[k++] = i.toShort()
            }
        }
        return SceneMesh(vertices, indices)
    }

    // Line segments (pairs of float3)

    /** Cup wireframe: 18 meridians + rings at 0, ⅓ and ⅔ of the height. */
    fun cupWire(): FloatArray {
        val h = CupShape.HEIGHT
        val rb = CupShape.BOTTOM_RADIUS
        val rt = CupShape.TOP_RADIUS
        val n = 18
        val k = 1.004
        val p = FloatList()
        for (i in 0 until n) {
            val a = i.toDouble() / n * TAU
            val s = sin(a)
            val c = cos(a)
            p.add(rb * k * s, 0.0, rb * k * c)
            p.add(rt * k * s, h, rt * k * c)
        }
        for (y in doubleArrayOf(0.0, 0.33, 0.66)) {
            val r = (rb + (rt - rb) * y) * k
            val yy = y * h
            val m = 72
            for (i in 0 until m) {
                val a0 = i.toDouble() / m * TAU
                val a1 = (i + 1).toDouble() / m * TAU
                p.add(r * sin(a0), yy, r * cos(a0))
                p.add(r * sin(a1), yy, r * cos(a1))
            }
        }
        return p.toArray()
    }

    /** Tunnel lattice around the line: 13 staggered hexagonal rings. */
    fun lattice(): FloatArray {
        val r = 0.3
        val rings = 13
        val p = FloatList()
        fun v(j: Int, i: Int) {
            val a = (i + (j % 2) * 0.5) / 6 * TAU
            p.add(j.toDouble() / (rings - 1) - 0.5, r * cos(a), r * sin(a))
        }
        for (j in 0 until rings) {
            for (i in 0 until 6) {
                v(j, i); v(j, (i + 1) % 6)
                if (j < rings - 1) {
                    v(j, i); v(j + 1, i)
                    v(j, (i + 1) % 6); v(j + 1, i)
                }
            }
        }
        return p.toArray()
    }

    /** Hexagon in the YZ plane (scan ring, relay ring). */
    fun hexRing(radius: Double): FloatArray {
        val p = FloatList()
        for (i in 0 until 6) {
            val a0 = i.toDouble() / 6 * TAU
            val a1 = (i + 1).toDouble() / 6 * TAU
            p.add(0.0, radius * cos(a0), radius * sin(a0))
            p.add(0.0, radius * cos(a1), radius * sin(a1))
        }
        return p.toArray()
    }

    // Points (float3 centres)

    /** 180 glow points along the unit line (scaled to 2L per frame). */
    fun glowLine(): FloatArray {
        val n = 180
        val p = FloatList()
        for (i in 0 until n) p.add(i.toDouble() / (n - 1) - 0.5, 0.0, 0.0)
        return p.toArray()
    }

    const val DUST_SEED: Int = 0x75327573
    const val DUST_COUNT = 420

    /** 420 dust motes in a 16 × 10 × 9 box in front of and behind the cups. */
    fun dust(seed: Int = DUST_SEED): FloatArray {
        val random = SceneRandom(seed)
        val p = FloatArray(DUST_COUNT * 3)
        var o = 0
        repeat(DUST_COUNT) {
            val x = (random.next() - 0.5) * 16
            val y = (random.next() - 0.5) * 10
            val z = -6 + random.next() * 9
            p[o++] = x.toFloat(); p[o++] = y.toFloat(); p[o++] = z.toFloat()
        }
        return p
    }

    /**
     * Centre of the axis-aligned bounds of float3 points (three.js `computeBoundingSphere` centre,
     * used for transparent sorting).
     */
    fun boundsCenter(p: FloatArray): Vec3 {
        var lx = Float.MAX_VALUE; var ly = Float.MAX_VALUE; var lz = Float.MAX_VALUE
        var hx = -Float.MAX_VALUE; var hy = -Float.MAX_VALUE; var hz = -Float.MAX_VALUE
        for (i in p.indices step 3) {
            lx = min(lx, p[i]); ly = min(ly, p[i + 1]); lz = min(lz, p[i + 2])
            hx = max(hx, p[i]); hy = max(hy, p[i + 1]); hz = max(hz, p[i + 2])
        }
        return Vec3((lx + hx) / 2, (ly + hy) / 2, (lz + hz) / 2)
    }

    // Dot texture

    const val DOT_TEXTURE_SIZE = 64

    /**
     * The scene's 64 × 64 radial "dot" (canvas radial gradient: alpha 1 at the centre, 0.6 at
     * 22 %, 0 at the edge), straight (non-premultiplied) RGBA8 — white wherever alpha > 0, as WebGL
     * un-premultiplies a canvas.
     */
    fun dotTexture(): ByteArray {
        val n = DOT_TEXTURE_SIZE
        val c = n / 2.0
        val px = ByteArray(n * n * 4)
        for (y in 0 until n) {
            for (x in 0 until n) {
                val dx = x + 0.5 - c
                val dy = y + 0.5 - c
                val r = min(sqrt(dx * dx + dy * dy) / c, 1.0)
                val a = if (r < 0.22) 1 - 0.4 * (r / 0.22) else 0.6 * (1 - (r - 0.22) / 0.78)
                val a8 = (max(0.0, min(1.0, a)) * 255).roundToInt()
                val o = (y * n + x) * 4
                val rgb = if (a8 > 0) 255 else 0
                px[o] = rgb.toByte()
                px[o + 1] = rgb.toByte()
                px[o + 2] = rgb.toByte()
                px[o + 3] = a8.toByte()
            }
        }
        return px
    }

    /** A growable float array (the geometry is built once). */
    private class FloatList {
        private var data = FloatArray(256)
        private var size = 0

        fun add(x: Double, y: Double, z: Double) {
            if (size + 3 > data.size) data = data.copyOf(data.size * 2)
            data[size++] = x.toFloat(); data[size++] = y.toFloat(); data[size++] = z.toFloat()
        }

        fun toArray(): FloatArray = data.copyOf(size)
    }
}

/** Every static buffer of the scene, built once per process and shared by the renderers. */
internal object SceneMeshes {
    val cup: SceneMesh by lazy { SceneGeometry.cup() }
    val rim: SceneMesh by lazy { SceneGeometry.rim() }
    val string: SceneMesh by lazy { SceneGeometry.string() }
    val cupWire: FloatArray by lazy { SceneGeometry.cupWire() }
    val lattice: FloatArray by lazy { SceneGeometry.lattice() }
    val scanRing: FloatArray by lazy { SceneGeometry.hexRing(0.3) }
    val relayRing: FloatArray by lazy { SceneGeometry.hexRing(0.13) }
    val glowLine: FloatArray by lazy { SceneGeometry.glowLine() }
    val dust: FloatArray by lazy { SceneGeometry.dust() }
    val dustCenter: Vec3 by lazy { SceneGeometry.boundsCenter(dust) }
    val dotTexture: ByteArray by lazy { SceneGeometry.dotTexture() }

    /** The segment list of [lines]. */
    fun lines(lines: SceneLines): FloatArray = when (lines) {
        SceneLines.CupWire -> cupWire
        SceneLines.Lattice -> lattice
        SceneLines.ScanRing -> scanRing
        SceneLines.RelayRing -> relayRing
    }
}

/** The line-segment buffers. */
internal enum class SceneLines { CupWire, Lattice, ScanRing, RelayRing }
