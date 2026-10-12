// us2us — the welcome scene drawn on the CPU: the same frame (SceneFrame) and
// the same shading as the GLES renderer, executed by a small rasterizer with
// 4× multisampling, depth testing and the GL blend equations on an 8-bit
// target. It renders still frames where OpenGL is not available (JVM tests,
// layout previews); the app on a device always draws with the GPU.

package com.syconx.us2us.scene3d

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A rendered frame: [rgba] holds premultiplied red, green, blue, alpha per pixel, top row first. */
internal class ScenePixels(val width: Int, val height: Int, val rgba: FloatArray, val opacity: Double)

internal class SceneRaster(private val width: Int, private val height: Int) {
    private val color = FloatArray(width * height * SAMPLES * 4)
    private val depth = FloatArray(width * height * SAMPLES)

    private enum class Blend { Normal, Additive, None }

    private class Pipe(val blend: Blend, val depthWrite: Boolean, val cullBack: Boolean)

    /** A vertex after the vertex stage: clip position and varyings. */
    private class Vertex(val clip: DoubleArray, val varyings: DoubleArray)

    /** A fragment stage; [shade] writes rgba (straight alpha) into `out`. */
    private abstract class Shader(val noPerspective: Boolean, val derivatives: Boolean) {
        abstract fun shade(v: DoubleArray, dx: DoubleArray?, dy: DoubleArray?, front: Boolean, out: DoubleArray)
    }

    private lateinit var view: DoubleArray
    private lateinit var projection: DoubleArray
    private lateinit var viewport: DoubleArray
    private lateinit var uniforms: SceneFrameUniforms

    fun render(frame: SceneFrame): ScenePixels {
        color.fill(0f)
        depth.fill(1f)
        uniforms = frame.uniforms
        view = frame.uniforms.view.d()
        projection = frame.uniforms.projection.d()
        viewport = DoubleArray(4) { frame.uniforms.viewport[it].toDouble() }
        for (draw in frame.draws) encode(draw)
        return ScenePixels(width, height, resolve(), frame.opacity)
    }

    // Draws (SceneRenderer.encode)

    private fun encode(d: SceneDraw) {
        val model = d.model.d()
        val mv = mul(view, model)
        val mvp = mul(projection, mv)
        when (d.kind) {
            SceneDrawKind.Cup -> {
                val normalMatrix = d.normalMatrix.d()
                val mesh = SceneMeshes.cup
                val vertices = Array(mesh.vertices.size / 6) { i ->
                    val o = i * 6
                    val p = transform(mv, mesh.vertices[o], mesh.vertices[o + 1], mesh.vertices[o + 2], 1.0)
                    val n = transform(normalMatrix, mesh.vertices[o + 3], mesh.vertices[o + 4], mesh.vertices[o + 5], 0.0)
                    val l = sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]).takeIf { it > 0 } ?: 1.0
                    Vertex(transform(projection, p[0], p[1], p[2], p[3]), doubleArrayOf(p[0], p[1], p[2], n[0] / l, n[1] / l, n[2] / l))
                }
                triangles(vertices, mesh.indices, Pipe(Blend.Normal, depthWrite = true, cullBack = false), LitShader(d))
            }
            SceneDrawKind.Solid -> {
                val mesh = SceneMeshes.rim
                triangles(positions(mvp, mesh.vertices), mesh.indices, Pipe(Blend.Normal, depthWrite = true, cullBack = true), FlatShader(d.color))
            }
            SceneDrawKind.String -> {
                val mesh = SceneMeshes.string
                triangles(positions(mvp, mesh.vertices), mesh.indices, Pipe(Blend.None, depthWrite = true, cullBack = true), FlatShader(d.color))
                strip(ribbon(mv, d.params[0].toDouble()), Pipe(Blend.Normal, depthWrite = false, cullBack = false), CoverageShader(d.color, ribbon = true))
            }
            SceneDrawKind.Lines -> {
                val p = SceneMeshes.lines(requireNotNull(d.lines))
                val pipe = Pipe(Blend.Normal, depthWrite = false, cullBack = false)
                val shader = CoverageShader(d.color, ribbon = false)
                for (i in 0 until p.size / 6) {
                    val o = i * 6
                    val c0 = transform(mvp, p[o], p[o + 1], p[o + 2], 1.0)
                    val c1 = transform(mvp, p[o + 3], p[o + 4], p[o + 5], 1.0)
                    strip(segment(c0, c1), pipe, shader)
                }
            }
            SceneDrawKind.GlowLine -> points(SceneMeshes.glowLine, model, d, if (d.additive) Blend.Additive else Blend.Normal)
            SceneDrawKind.Dust -> points(SceneMeshes.dust, model, d, Blend.Normal)
            SceneDrawKind.Sprite -> {
                val center = transform(view, model[12], model[13], model[14], 1.0)
                val size = d.params[0].toDouble()
                val quad = Array(4) { vid ->
                    val cx = QUAD[vid * 2]
                    val cy = QUAD[vid * 2 + 1]
                    val clip = transform(projection, center[0] + cx * size, center[1] + cy * size, center[2], 1.0)
                    Vertex(clip, doubleArrayOf(cx + 0.5, cy + 0.5))
                }
                strip(quad, Pipe(if (d.additive) Blend.Additive else Blend.Normal, depthWrite = false, cullBack = false), SpriteShader(d.color))
            }
        }
    }

    private fun positions(mvp: DoubleArray, p: FloatArray): Array<Vertex> =
        Array(p.size / 3) { i -> Vertex(transform(mvp, p[i * 3], p[i * 3 + 1], p[i * 3 + 2], 1.0), EMPTY) }

    /** `us2us_line_vertex`: a 2 px quad around the segment; varying: signed offset in px. */
    private fun segment(c0: DoubleArray, c1: DoubleArray): Array<Vertex> {
        val n = screenNormal(c0, c1)
        return Array(4) { vid ->
            val side = if (vid and 1 == 1) 1.0 else -1.0
            val c = (if (vid >= 2) c1 else c0).copyOf()
            c[0] += n[0] * side / (0.5 * viewport[0]) * c[3]
            c[1] += n[1] * side / (0.5 * viewport[1]) * c[3]
            Vertex(c, doubleArrayOf(side, 0.0))
        }
    }

    /** `us2us_ribbon_vertex`; varyings: offset and half width in px. */
    private fun ribbon(mv: DoubleArray, radius: Double): Array<Vertex> {
        val v0 = transform(mv, -0.5f, 0f, 0f, 1.0)
        val v1 = transform(mv, 0.5f, 0f, 0f, 1.0)
        val c0 = transform(projection, v0[0], v0[1], v0[2], v0[3])
        val c1 = transform(projection, v1[0], v1[1], v1[2], v1[3])
        val n = screenNormal(c0, c1)
        val focal = projection[5] * 0.5 * viewport[1]
        return Array(4) { vid ->
            val end1 = vid >= 2
            val side = if (vid and 1 == 1) 1.0 else -1.0
            val v = if (end1) v1 else v0
            val c = (if (end1) c1 else c0).copyOf()
            val halfWidth = radius * focal / c[3]
            val extent = halfWidth + 1.0
            c[0] += n[0] * side * extent / (0.5 * viewport[0]) * c[3]
            c[1] += n[1] * side * extent / (0.5 * viewport[1]) * c[3]
            val front = transform(projection, v[0], v[1], v[2] + 2.0 * radius, 1.0)
            c[2] = front[2] / front[3] * c[3]
            Vertex(c, doubleArrayOf(side * extent, halfWidth))
        }
    }

    /** `us2us_points_vertex`: one screen-aligned quad per centre. */
    private fun points(centers: FloatArray, model: DoubleArray, d: SceneDraw, blend: Blend) {
        val pipe = Pipe(blend, depthWrite = false, cullBack = false)
        val shader = SpriteShader(d.color)
        val mv = mul(view, model)
        val wrap = d.params[2] > 0.5f
        val drift = d.params[3].toDouble()
        val size = d.params[0].toDouble()
        for (i in 0 until centers.size / 3) {
            var y = centers[i * 3 + 1].toDouble()
            if (wrap) y = (y + drift + 5.0).mod(10.0) - 5.0
            val p = transform(mv, centers[i * 3], y.toFloat(), centers[i * 3 + 2], 1.0)
            val clip = transform(projection, p[0], p[1], p[2], p[3])
            val sizePx = max(size * viewport[2] / -p[2], 1.0)
            val quad = Array(4) { vid ->
                val cx = QUAD[vid * 2]
                val cy = QUAD[vid * 2 + 1]
                val c = clip.copyOf()
                c[0] += cx * (sizePx * 2.0) / viewport[0] * c[3]
                c[1] += cy * (sizePx * 2.0) / viewport[1] * c[3]
                Vertex(c, doubleArrayOf(cx + 0.5, cy + 0.5))
            }
            strip(quad, pipe, shader)
        }
    }

    private fun screenNormal(c0: DoubleArray, c1: DoubleArray): DoubleArray {
        val ax = c0[0] / c0[3] * 0.5 * viewport[0]
        val ay = c0[1] / c0[3] * 0.5 * viewport[1]
        val bx = c1[0] / c1[3] * 0.5 * viewport[0]
        val by = c1[1] / c1[3] * 0.5 * viewport[1]
        var dx = bx - ax
        var dy = by - ay
        val len = sqrt(dx * dx + dy * dy)
        if (len > 1e-5) {
            dx /= len
            dy /= len
        } else {
            dx = 1.0
            dy = 0.0
        }
        return doubleArrayOf(-dy, dx)
    }

    // Fragment stages

    /** MeshStandardMaterial (`us2us_lit_fragment`). */
    private inner class LitShader(d: SceneDraw) : Shader(noPerspective = false, derivatives = true) {
        private val albedo = DoubleArray(3) { d.color[it].toDouble() }
        private val alpha = d.color[3].toDouble()
        private val roughnessParam = d.params[1].toDouble()
        private val metalness = d.params[2].toDouble()
        private val n = DoubleArray(3)
        private val nx = DoubleArray(3)
        private val ny = DoubleArray(3)
        private val diffuse = DoubleArray(3)
        private val specular = DoubleArray(3)

        private fun normal(v: DoubleArray, sign: Double, out: DoubleArray) {
            val l = sqrt(v[3] * v[3] + v[4] * v[4] + v[5] * v[5]).takeIf { it > 0 } ?: 1.0
            out[0] = v[3] / l * sign
            out[1] = v[4] / l * sign
            out[2] = v[5] / l * sign
        }

        override fun shade(v: DoubleArray, dx: DoubleArray?, dy: DoubleArray?, front: Boolean, out: DoubleArray) {
            val sign = if (front) 1.0 else -1.0
            normal(v, sign, n)
            var geometryRoughness = 0.0
            if (dx != null && dy != null) {
                normal(dx, sign, nx)
                normal(dy, sign, ny)
                for (i in 0 until 3) geometryRoughness = max(geometryRoughness, max(abs(nx[i] - n[i]), abs(ny[i] - n[i])))
            }
            val vl = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).takeIf { it > 0 } ?: 1.0
            val viewDir = doubleArrayOf(-v[0] / vl, -v[1] / vl, -v[2] / vl)
            val diffuseColor = DoubleArray(3) { albedo[it] * (1 - metalness) }
            val roughness = min(max(roughnessParam, 0.0525) + geometryRoughness, 1.0)
            val specularColor = DoubleArray(3) { 0.04 + (albedo[it] - 0.04) * metalness }
            diffuse.fill(0.0)
            specular.fill(0.0)
            directLight(uniforms.keyDirection, uniforms.keyColor, viewDir, diffuseColor, specularColor, roughness)
            directLight(uniforms.rimDirection, uniforms.rimColor, viewDir, diffuseColor, specularColor, roughness)
            val hemi = uniforms.hemiDirection
            val hemiWeight = 0.5 * (n[0] * hemi[0] + n[1] * hemi[1] + n[2] * hemi[2]) + 0.5
            for (i in 0 until 3) {
                val ground = uniforms.groundColor[i].toDouble()
                val irradiance = ground + (uniforms.skyColor[i] - ground) * hemiWeight
                diffuse[i] += irradiance * RECIPROCAL_PI * diffuseColor[i]
                out[i] = linearToSrgb(diffuse[i] + specular[i])
            }
            out[3] = alpha
        }

        private fun directLight(
            direction: FloatArray,
            lightColor: FloatArray,
            viewDir: DoubleArray,
            diffuseColor: DoubleArray,
            specularColor: DoubleArray,
            roughness: Double,
        ) {
            val lx = direction[0].toDouble()
            val ly = direction[1].toDouble()
            val lz = direction[2].toDouble()
            val dotNL = (n[0] * lx + n[1] * ly + n[2] * lz).coerceIn(0.0, 1.0)
            val a = roughness * roughness
            var hx = lx + viewDir[0]
            var hy = ly + viewDir[1]
            var hz = lz + viewDir[2]
            val hl = sqrt(hx * hx + hy * hy + hz * hz).takeIf { it > 0 } ?: 1.0
            hx /= hl; hy /= hl; hz /= hl
            val dotNV = (n[0] * viewDir[0] + n[1] * viewDir[1] + n[2] * viewDir[2]).coerceIn(0.0, 1.0)
            val dotNH = (n[0] * hx + n[1] * hy + n[2] * hz).coerceIn(0.0, 1.0)
            val dotVH = (viewDir[0] * hx + viewDir[1] * hy + viewDir[2] * hz).coerceIn(0.0, 1.0)
            val fresnel = 2.0.pow((-5.55473 * dotVH - 6.98316) * dotVH)
            val a2 = a * a
            val gv = dotNL * sqrt(a2 + (1 - a2) * dotNV * dotNV)
            val gl = dotNV * sqrt(a2 + (1 - a2) * dotNL * dotNL)
            val vis = 0.5 / max(gv + gl, 1e-6)
            val denom = dotNH * dotNH * (a2 - 1) + 1
            val dGgx = RECIPROCAL_PI * a2 / (denom * denom)
            for (i in 0 until 3) {
                val irradiance = dotNL * lightColor[i]
                val f = specularColor[i] * (1 - fresnel) + fresnel
                specular[i] += irradiance * f * (vis * dGgx)
                diffuse[i] += irradiance * RECIPROCAL_PI * diffuseColor[i]
            }
        }
    }

    /** MeshBasicMaterial (`us2us_basic_fragment`). */
    private class FlatShader(color: FloatArray) : Shader(noPerspective = false, derivatives = false) {
        private val rgba = DoubleArray(4) { color[it].toDouble() }

        override fun shade(v: DoubleArray, dx: DoubleArray?, dy: DoubleArray?, front: Boolean, out: DoubleArray) {
            rgba.copyInto(out)
        }
    }

    /** Lines and the string's ribbon: analytic coverage of a screen-space offset. */
    private class CoverageShader(color: FloatArray, private val ribbon: Boolean) : Shader(noPerspective = true, derivatives = false) {
        private val rgba = DoubleArray(4) { color[it].toDouble() }

        override fun shade(v: DoubleArray, dx: DoubleArray?, dy: DoubleArray?, front: Boolean, out: DoubleArray) {
            val coverage = if (ribbon) v[1] + 0.5 - abs(v[0]) else 1.0 - abs(v[0])
            out[0] = rgba[0]
            out[1] = rgba[1]
            out[2] = rgba[2]
            out[3] = rgba[3] * coverage.coerceIn(0.0, 1.0)
        }
    }

    /** Sprites and points (`us2us_sprite_fragment`): the dot texture, trilinear. */
    private class SpriteShader(color: FloatArray) : Shader(noPerspective = false, derivatives = true) {
        private val rgba = DoubleArray(4) { color[it].toDouble() }
        private val texel = DoubleArray(4)

        override fun shade(v: DoubleArray, dx: DoubleArray?, dy: DoubleArray?, front: Boolean, out: DoubleArray) {
            val size = SceneGeometry.DOT_TEXTURE_SIZE.toDouble()
            var rho = 0.0
            if (dx != null && dy != null) {
                val ax = (dx[0] - v[0]) * size
                val ay = (dx[1] - v[1]) * size
                val bx = (dy[0] - v[0]) * size
                val by = (dy[1] - v[1]) * size
                rho = max(sqrt(ax * ax + ay * ay), sqrt(bx * bx + by * by))
            }
            val lod = if (rho > 0) ln(rho) / ln(2.0) else 0.0
            DotTexture.sample(v[0], v[1], lod, texel)
            for (i in 0 until 3) out[i] = linearToSrgb(rgba[i] * texel[i])
            out[3] = rgba[3] * texel[3]
        }
    }

    /** The dot texture with its mip chain, decoded to linear light (an sRGB texture samples linear). */
    private object DotTexture {
        private val levels: List<FloatArray>
        private val sizes: List<Int>

        init {
            val n = SceneGeometry.DOT_TEXTURE_SIZE
            val base = SceneMeshes.dotTexture
            val l0 = FloatArray(n * n * 4) { i ->
                val byte = (base[i].toInt() and 0xFF) / 255.0
                if (i % 4 == 3) byte.toFloat() else SceneMath.srgbToLinear(byte).toFloat()
            }
            val all = arrayListOf(l0)
            val dims = arrayListOf(n)
            var size = n
            var level = l0
            while (size > 1) {
                val half = size / 2
                val next = FloatArray(half * half * 4)
                for (y in 0 until half) {
                    for (x in 0 until half) {
                        for (c in 0 until 4) {
                            var s = 0.0
                            for (dy in 0..1) for (dx in 0..1) s += level[((y * 2 + dy) * size + x * 2 + dx) * 4 + c]
                            // Stored as sRGB8 / A8 again, as the GPU keeps each level.
                            val avg = s / 4
                            next[(y * half + x) * 4 + c] = if (c == 3) {
                                ((avg * 255).roundToInt() / 255.0).toFloat()
                            } else {
                                SceneMath.srgbToLinear((linearToSrgb(avg) * 255).roundToInt() / 255.0).toFloat()
                            }
                        }
                    }
                }
                all += next
                dims += half
                level = next
                size = half
            }
            levels = all
            sizes = dims
        }

        private val a = DoubleArray(4)
        private val b = DoubleArray(4)

        fun sample(u: Double, v: Double, lod: Double, out: DoubleArray) {
            val maxLevel = (levels.size - 1).toDouble()
            if (lod <= 0) {
                bilinear(0, u, v, out)
                return
            }
            val l = lod.coerceAtMost(maxLevel)
            val l0 = floor(l).toInt()
            val l1 = min(l0 + 1, levels.size - 1)
            val f = l - l0
            bilinear(l0, u, v, a)
            bilinear(l1, u, v, b)
            for (i in 0 until 4) out[i] = a[i] + (b[i] - a[i]) * f
        }

        private fun bilinear(level: Int, u: Double, v: Double, out: DoubleArray) {
            val size = sizes[level]
            val data = levels[level]
            val x = u * size - 0.5
            val y = v * size - 0.5
            val x0 = floor(x).toInt()
            val y0 = floor(y).toInt()
            val fx = x - x0
            val fy = y - y0
            fun at(px: Int, py: Int, c: Int): Double {
                val cx = px.coerceIn(0, size - 1)
                val cy = py.coerceIn(0, size - 1)
                return data[(cy * size + cx) * 4 + c].toDouble()
            }
            for (c in 0 until 4) {
                val top = at(x0, y0, c) * (1 - fx) + at(x0 + 1, y0, c) * fx
                val bottom = at(x0, y0 + 1, c) * (1 - fx) + at(x0 + 1, y0 + 1, c) * fx
                out[c] = top * (1 - fy) + bottom * fy
            }
        }
    }

    // Rasterizer

    /** A triangle strip of four vertices: (0, 1, 2) and (2, 1, 3), as the GPU splits it. */
    private fun strip(v: Array<Vertex>, pipe: Pipe, shader: Shader) {
        triangle(v[0], v[1], v[2], pipe, shader)
        triangle(v[2], v[1], v[3], pipe, shader)
    }

    private fun triangles(vertices: Array<Vertex>, indices: ShortArray, pipe: Pipe, shader: Shader) {
        for (i in indices.indices step 3) {
            triangle(vertices[indices[i].toInt()], vertices[indices[i + 1].toInt()], vertices[indices[i + 2].toInt()], pipe, shader)
        }
    }

    private val shaded = DoubleArray(4)

    private fun triangle(a: Vertex, b: Vertex, c: Vertex, pipe: Pipe, shader: Shader) {
        if (a.clip[3] <= 0 || b.clip[3] <= 0 || c.clip[3] <= 0) return
        // Window coordinates (y up, as in GL) and NDC depth.
        val ax = (a.clip[0] / a.clip[3] + 1) * 0.5 * width
        val ay = (a.clip[1] / a.clip[3] + 1) * 0.5 * height
        val bx = (b.clip[0] / b.clip[3] + 1) * 0.5 * width
        val by = (b.clip[1] / b.clip[3] + 1) * 0.5 * height
        val cx = (c.clip[0] / c.clip[3] + 1) * 0.5 * width
        val cy = (c.clip[1] / c.clip[3] + 1) * 0.5 * height
        val az = a.clip[2] / a.clip[3]
        val bz = b.clip[2] / b.clip[3]
        val cz = c.clip[2] / c.clip[3]
        val area = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
        if (area == 0.0 || area.isNaN()) return
        val front = area > 0
        if (pipe.cullBack && !front) return
        val sign = if (front) 1.0 else -1.0
        val absArea = abs(area)

        // Top-left fill rule on the counter-clockwise edges, so shared edges are drawn once.
        val tl0 = topLeft(bx, by, cx, cy, front)
        val tl1 = topLeft(cx, cy, ax, ay, front)
        val tl2 = topLeft(ax, ay, bx, by, front)

        val x0 = max(0, floor(min(ax, min(bx, cx))).toInt())
        val x1 = min(width - 1, ceil(max(ax, max(bx, cx))).toInt())
        val y0 = max(0, floor(min(ay, min(by, cy))).toInt())
        val y1 = min(height - 1, ceil(max(ay, max(by, cy))).toInt())
        if (x0 > x1 || y0 > y1) return

        val count = a.varyings.size
        val center = DoubleArray(count)
        val right = if (shader.derivatives) DoubleArray(count) else null
        val up = if (shader.derivatives) DoubleArray(count) else null
        val iwa = 1 / a.clip[3]
        val iwb = 1 / b.clip[3]
        val iwc = 1 / c.clip[3]

        fun interpolate(px: Double, py: Double, out: DoubleArray) {
            val la = sign * edge(bx, by, cx, cy, px, py) / absArea
            val lb = sign * edge(cx, cy, ax, ay, px, py) / absArea
            val lc = sign * edge(ax, ay, bx, by, px, py) / absArea
            if (shader.noPerspective) {
                for (i in 0 until count) out[i] = la * a.varyings[i] + lb * b.varyings[i] + lc * c.varyings[i]
            } else {
                val den = la * iwa + lb * iwb + lc * iwc
                for (i in 0 until count) {
                    out[i] = (la * a.varyings[i] * iwa + lb * b.varyings[i] * iwb + lc * c.varyings[i] * iwc) / den
                }
            }
        }

        val passed = BooleanArray(SAMPLES)
        val sampleDepth = DoubleArray(SAMPLES)
        for (py in y0..y1) {
            for (px in x0..x1) {
                var any = false
                for (s in 0 until SAMPLES) {
                    val sx = px + 0.5 + SAMPLE_X[s]
                    val sy = py + 0.5 + SAMPLE_Y[s]
                    val w0 = sign * edge(bx, by, cx, cy, sx, sy)
                    val w1 = sign * edge(cx, cy, ax, ay, sx, sy)
                    val w2 = sign * edge(ax, ay, bx, by, sx, sy)
                    val inside = (w0 > 0 || (w0 == 0.0 && tl0)) && (w1 > 0 || (w1 == 0.0 && tl1)) && (w2 > 0 || (w2 == 0.0 && tl2))
                    passed[s] = false
                    if (!inside) continue
                    val z = (w0 * az + w1 * bz + w2 * cz) / absArea
                    val d = z * 0.5 + 0.5
                    val index = (py * width + px) * SAMPLES + s
                    if (d <= depth[index]) {
                        passed[s] = true
                        sampleDepth[s] = d
                        any = true
                    }
                }
                if (!any) continue
                val colorWrite = pipe.blend != Blend.None
                if (colorWrite) {
                    interpolate(px + 0.5, py + 0.5, center)
                    if (right != null && up != null) {
                        interpolate(px + 1.5, py + 0.5, right)
                        interpolate(px + 0.5, py + 1.5, up)
                    }
                    shader.shade(center, right, up, front, shaded)
                }
                for (s in 0 until SAMPLES) {
                    if (!passed[s]) continue
                    val index = (py * width + px) * SAMPLES + s
                    if (pipe.depthWrite) depth[index] = sampleDepth[s].toFloat()
                    if (colorWrite) blend(index * 4, pipe.blend)
                }
            }
        }
    }

    /** Blends [shaded] into a sample with the GL blend equation, clamped and stored as 8-bit unorm. */
    private fun blend(o: Int, mode: Blend) {
        val sa = shaded[3].coerceIn(0.0, 1.0)
        for (i in 0 until 3) {
            val s = shaded[i].coerceIn(0.0, 1.0)
            val d = color[o + i].toDouble()
            val r = if (mode == Blend.Additive) s * sa + d else s * sa + d * (1 - sa)
            color[o + i] = unorm8(r)
        }
        val da = color[o + 3].toDouble()
        val ra = if (mode == Blend.Additive) sa * sa + da else sa + da * (1 - sa)
        color[o + 3] = unorm8(ra)
    }

    /** Multisample resolve (average of the samples), flipped to top row first. */
    private fun resolve(): FloatArray {
        val out = FloatArray(width * height * 4)
        for (y in 0 until height) {
            val row = height - 1 - y
            for (x in 0 until width) {
                for (c in 0 until 4) {
                    var s = 0.0
                    for (k in 0 until SAMPLES) s += color[((y * width + x) * SAMPLES + k) * 4 + c]
                    out[(row * width + x) * 4 + c] = unorm8(s / SAMPLES)
                }
            }
        }
        return out
    }

    private companion object {
        const val SAMPLES = 4

        /** The standard 4× sample pattern (offsets from the pixel centre). */
        val SAMPLE_X = doubleArrayOf(-0.125, 0.375, -0.375, 0.125)
        val SAMPLE_Y = doubleArrayOf(0.375, 0.125, -0.125, -0.375)

        val QUAD = doubleArrayOf(-0.5, -0.5, 0.5, -0.5, -0.5, 0.5, 0.5, 0.5)
        val EMPTY = DoubleArray(0)
        const val RECIPROCAL_PI = 0.3183098861837907

        fun unorm8(v: Double): Float = ((v.coerceIn(0.0, 1.0) * 255).roundToInt() / 255.0).toFloat()

        /** The shader's sRGB transfer (`linearToSrgb`). */
        fun linearToSrgb(c: Double): Double = if (c <= 0.0031308) c * 12.92 else max(c, 0.0).pow(0.41666) * 1.055 - 0.055

        /**
         * Edge function of p against a→b, evaluated from the lower endpoint, so the two triangles
         * sharing an edge get exactly opposite values.
         */
        fun edge(ax: Double, ay: Double, bx: Double, by: Double, px: Double, py: Double): Double =
            if (ax < bx || (ax == bx && ay < by)) {
                (bx - ax) * (py - ay) - (by - ay) * (px - ax)
            } else {
                -((ax - bx) * (py - by) - (ay - by) * (px - bx))
            }

        /** A left or top edge of a counter-clockwise (y-up) triangle; [front] false reverses it. */
        fun topLeft(ax: Double, ay: Double, bx: Double, by: Double, front: Boolean): Boolean {
            val dx = if (front) bx - ax else ax - bx
            val dy = if (front) by - ay else ay - by
            return dy < 0 || (dy == 0.0 && dx < 0)
        }

        fun Mat4.d(): DoubleArray = DoubleArray(16) { m[it].toDouble() }

        fun mul(a: DoubleArray, b: DoubleArray): DoubleArray {
            val r = DoubleArray(16)
            for (c in 0 until 4) {
                for (row in 0 until 4) {
                    var s = 0.0
                    for (k in 0 until 4) s += a[k * 4 + row] * b[c * 4 + k]
                    r[c * 4 + row] = s
                }
            }
            return r
        }

        fun transform(m: DoubleArray, x: Double, y: Double, z: Double, w: Double): DoubleArray = doubleArrayOf(
            m[0] * x + m[4] * y + m[8] * z + m[12] * w,
            m[1] * x + m[5] * y + m[9] * z + m[13] * w,
            m[2] * x + m[6] * y + m[10] * z + m[14] * w,
            m[3] * x + m[7] * y + m[11] * z + m[15] * w,
        )

        fun transform(m: DoubleArray, x: Float, y: Float, z: Float, w: Double): DoubleArray =
            transform(m, x.toDouble(), y.toDouble(), z.toDouble(), w)
    }
}
