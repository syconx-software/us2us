// us2us — per-frame logic of the welcome scene: a port of `frame()` in
// us2us-scene.js (camera, sway, packets, tunnel, dust, end glows) and of
// three.js' transparent render list (every object is transparent, sorted
// back to front by its centre, ties by creation order).
//
// The result is a renderer-neutral list of draws; the GPU renderer
// (SceneRenderer) and the software one (SceneRaster) execute the same list.

package com.syconx.us2us.scene3d

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/** What a draw is: each kind has its own shader and fixed-function state. */
internal enum class SceneDrawKind {
    /** MeshStandardMaterial cup: lit, double-sided, depth write. */
    Cup,

    /** MeshBasicMaterial rim: flat colour, back faces culled, depth write. */
    Solid,

    /** The string: the cylinder's depth, then its antialiased ribbon. */
    String,

    /** LineBasicMaterial segments (instanced quads), depth test only. */
    Lines,

    /** The 180 glow points along the line. */
    GlowLine,

    /** One camera-facing glow sprite (packets, end glows). */
    Sprite,

    /** The 420 dust motes. */
    Dust,
}

/** Uniforms shared by every draw of a frame (`FrameUniforms`). */
internal class SceneFrameUniforms(
    val view: Mat4,
    val projection: Mat4,
    /** x, y: render target size (px); z: point scale (target height / 2). */
    val viewport: FloatArray,
    /** View space, towards the light. */
    val keyDirection: FloatArray,
    /** Linear rgb × intensity. */
    val keyColor: FloatArray,
    val rimDirection: FloatArray,
    val rimColor: FloatArray,
    /** View-space "up" of the hemisphere light. */
    val hemiDirection: FloatArray,
    val skyColor: FloatArray,
    val groundColor: FloatArray,
)

/** One draw (`DrawUniforms` plus what to draw and how). */
internal class SceneDraw(
    val kind: SceneDrawKind,
    val model: Mat4,
    /** rgb + opacity: linear for lit / textured shaders, sRGB-encoded for basic ones. */
    val color: FloatArray,
    /** x: size, y: roughness, z: metalness | dust wrap flag, w: dust drift. */
    val params: FloatArray = FloatArray(4),
    /** View-space normal matrix, lit meshes only. */
    val normalMatrix: Mat4 = Mat4.Identity,
    /** The segment buffer of a [SceneDrawKind.Lines] draw. */
    val lines: SceneLines? = null,
    /** Glows add light (dark theme); everything else blends normally. */
    val additive: Boolean = false,
)

/** A frame ready to render: its uniforms, the sorted draws and the canvas opacity. */
internal class SceneFrame(
    val uniforms: SceneFrameUniforms,
    val draws: List<SceneDraw>,
    /** Canvas opacity (`dim`, narrow canvases), rounded to hundredths. */
    val opacity: Double,
)

internal object SceneFrameBuilder {
    /** Field of view, near and far planes (`tokens.json › scene3D › camera`). */
    private const val FOV_Y = 34f
    private const val NEAR = 0.05f
    private const val FAR = 100f

    private class Entry(val depth: Float, val order: Int, val draw: SceneDraw)

    /**
     * The frame at scene time [time] (dust at [dustTime]) for a canvas of [canvasWidth] ×
     * [canvasHeight] points drawn into a [targetWidth] × [targetHeight] px target.
     */
    fun build(
        state: SceneState,
        palette: ScenePalette,
        time: Double,
        dustTime: Double,
        canvasWidth: Double,
        canvasHeight: Double,
        targetWidth: Float,
        targetHeight: Float,
    ): SceneFrame {
        val s = state
        val p = palette
        val t = time
        val sm = SceneMath::smoothstep

        // Camera (narrow canvases pull back and dim a little, as on the web).
        val narrow = SceneMath.clamp((1.2 - canvasWidth / canvasHeight) / 0.6, 0.0, 1.0)
        val kk = 1 + narrow * 0.75
        val eye = s.target + (s.camera - s.target) * kk
        val view = Mat4.lookAt(eye = eye.toVec3(), target = s.target.toVec3())
        val projection = Mat4.perspective(FOV_Y, (canvasWidth / canvasHeight).toFloat(), NEAR, FAR)

        val opacity = floor((1 - s.dim * 0.8) * (1 - narrow * 0.45) * 100 + 0.5) / 100

        // World group: slow sway around the state's yaw (Euler XYZ).
        val world = Mat4.translation((s.worldOffsetX * (1 - narrow)).toFloat(), 0f, 0f) *
            Mat4.rotationX((sin(t * 0.21) * 0.03).toFloat()) *
            Mat4.rotationY((s.yaw + sin(t * 0.3) * 0.07).toFloat())

        // (The web scene's "ghost" dissolve is driven by page scroll and not used by the app, so
        // every part is fully solid here.)
        val l = s.halfLength
        val lf = l.toFloat()
        val h = CupShape.HEIGHT.toFloat()

        fun direction(x: Float, y: Float, z: Float): FloatArray =
            view.transformDirection(Vec3(x, y, z)).let { floatArrayOf(it.x, it.y, it.z, 0f) }

        val uniforms = SceneFrameUniforms(
            view = view,
            projection = projection,
            viewport = floatArrayOf(targetWidth, targetHeight, targetHeight * 0.5f, 0f),
            keyDirection = direction(-3f, 4f, 5f),
            keyColor = p.key.linear(opacity = 1.0, intensity = 1.7),
            rimDirection = direction(3f, 1.5f, -4f),
            rimColor = p.rimLight.linear(opacity = 1.0, intensity = 2.4),
            hemiDirection = direction(0f, 1f, 0f),
            skyColor = p.sky.linear(opacity = 1.0, intensity = 1.1),
            groundColor = p.ground.linear(opacity = 1.0, intensity = 1.1),
        )

        val entries = ArrayList<Entry>(64)
        var order = 0
        fun add(draw: SceneDraw, center: Vec3) {
            val z = view.transform(center.x, center.y, center.z, 1f)[2]
            entries += Entry(-z, order, draw)
            order += 1
        }

        // Status story (only when `story` > 0).
        var rA = 0.0
        var rB = 0.0
        val qs = s.storyProgress
        val sv = s.story
        var storySprite: StorySprite? = null
        var receiptSprite: ReceiptSprite? = null
        if (sv > 0.01) {
            val tt: Double
            var dead = 0.0
            var so = 1.0
            var scl = 0.28
            var rc = -1.0
            if (qs < 0.25) {
                tt = 0.5 * sm(0.0, 0.17, qs)
                if (qs > 0.17) scl = 0.28 + 0.05 * sin(t * 4)
            } else if (qs < 0.5) {
                tt = 0.5 + 0.5 * sm(0.25, 0.4, qs)
            } else if (qs < 0.75) {
                tt = 1.0
                rc = 1 - sm(0.5, 0.66, qs)
            } else {
                tt = 0.5
                dead = sm(0.75, 0.8, qs)
                so = 1 - sm(0.82, 0.96, qs)
            }
            rB = sm(0.38, 0.42, qs) * (1 - sm(0.74, 0.76, qs))
            rA = if (rc >= 0) sm(0.6, 0.66, qs) * (1 - sm(0.74, 0.76, qs)) else 0.0
            storySprite = StorySprite(-l + 2 * l * tt, scl, p.core.mixed(p.dead, dead), sv * so)
            receiptSprite = ReceiptSprite(-l + 2 * l * max(rc, 0.0), if (rc > 0.02 && rc < 0.98) sv * 0.9 else 0.0)
        }

        // Cups (base towards the line, mouth outwards) with rim and wireframe.
        val tunnel = s.tunnel
        for ((side, rimMix) in listOf(-1.0 to rA, 1.0 to rB)) {
            val cup = world * Mat4.translation(side.toFloat() * lf, 0f, 0f) * Mat4.rotationZ((-side).toFloat() * SceneMath.PI_F / 2)
            val center = cup.transformPoint(Vec3(0f, h / 2, 0f))
            add(
                SceneDraw(
                    SceneDrawKind.Cup, cup, p.cup.linear(opacity = 1.0),
                    params = floatArrayOf(0f, 0.62f, 0.08f, 0f), normalMatrix = (view * cup).normalMatrix,
                ),
                center,
            )
            val rim = cup * Mat4.translation(0f, h, 0f) * Mat4.rotationX(SceneMath.PI_F / 2)
            add(SceneDraw(SceneDrawKind.Solid, rim, p.edge.mixed(p.core, rimMix).encoded(opacity = 1.0)), rim.transformPoint(Vec3.Zero))
            add(SceneDraw(SceneDrawKind.Lines, cup, p.edge.encoded(opacity = p.wireOpacity), lines = SceneLines.CupWire), center)
        }

        // The line and its glow.
        val origin = world.transformPoint(Vec3.Zero)
        val lineScale = world * Mat4.scale(2 * lf, 1f, 1f)
        add(SceneDraw(SceneDrawKind.String, lineScale, p.line.encoded(opacity = 1.0), params = floatArrayOf(0.009f, 0f, 0f, 0f)), origin)
        add(
            SceneDraw(
                SceneDrawKind.GlowLine, lineScale, p.glow.linear(opacity = p.glowOpacity),
                params = floatArrayOf(0.24f, 0f, 0f, 0f), additive = p.additive,
            ),
            origin,
        )

        // Tunnel lattice, scanning ring, relay ring.
        val tk = (0.55 + 0.45 * s.tunnel).toFloat()
        val latticeOpacity = tunnel * 0.42
        if (latticeOpacity > 0.004) {
            val m = world * Mat4.rotationX((t * 0.25).toFloat()) * Mat4.scale(2 * lf * 0.78f, tk, tk)
            add(SceneDraw(SceneDrawKind.Lines, m, p.edge.encoded(opacity = latticeOpacity), lines = SceneLines.Lattice), origin)
        } else {
            order += 1
        }
        val sp = SceneMath.fract(t * 0.32)
        val scanOpacity = tunnel * 0.9 * sin(sp * Math.PI)
        if (scanOpacity > 0.004) {
            val m = world * Mat4.translation(((sp - 0.5) * 2 * l * 0.78).toFloat(), 0f, 0f) *
                Mat4.rotationX((t * 0.25).toFloat()) * Mat4.scale(1f, tk, tk)
            add(SceneDraw(SceneDrawKind.Lines, m, p.line.encoded(opacity = scanOpacity), lines = SceneLines.ScanRing), m.transformPoint(Vec3.Zero))
        } else {
            order += 1
        }
        val relayOpacity = sv * 0.7 + tunnel * 0.2
        if (relayOpacity > 0.004) {
            val m = world * Mat4.rotationX((t * 0.6).toFloat())
            add(SceneDraw(SceneDrawKind.Lines, m, p.edge.encoded(opacity = relayOpacity), lines = SceneLines.RelayRing), origin)
        } else {
            order += 1
        }

        // Light packets: four each way, a head and a fading four-dot trail.
        fun sprite(x: Double, size: Double, color: SceneColor, opacity: Double) {
            val c = world.transformPoint(Vec3(x.toFloat(), 0f, 0f))
            if (opacity > 0) {
                add(
                    SceneDraw(
                        SceneDrawKind.Sprite, Mat4.translation(c), color.linear(opacity = opacity),
                        params = floatArrayOf(size.toFloat(), 0f, 0f, 0f), additive = p.additive,
                    ),
                    c,
                )
            } else {
                order += 1
            }
        }

        fun pulse(pt: Double, amp: Double, dir: Double) {
            sprite(x = -l + 2 * l * pt, size = 0.2, color = p.core, opacity = amp)
            for (j in 0 until 4) {
                val tj = SceneMath.clamp(pt - dir * (j + 1) * 0.014, 0.0, 1.0)
                sprite(x = -l + 2 * l * tj, size = 0.16 - j * 0.02, color = p.core, opacity = amp * (0.55 - j * 0.12))
            }
        }
        for (i in 0 until 4) {
            val pt = SceneMath.fract(t * 0.17 + i / 4.0)
            pulse(pt, amp = s.flow * sm(0.0, 0.08, pt) * (1 - sm(0.9, 1.0, pt)), dir = 1.0)
        }
        for (i in 0 until 4) {
            val tb = 1 - SceneMath.fract(t * 0.14 + i / 4.0 + 0.13)
            pulse(tb, amp = s.back * sm(0.0, 0.08, 1 - tb) * (1 - sm(0.9, 1.0, 1 - tb)), dir = -1.0)
        }
        storySprite?.let { sprite(x = it.x, size = it.size, color = it.color, opacity = it.opacity) } ?: run { order += 1 }
        receiptSprite?.let { sprite(x = it.x, size = 0.16, color = p.core, opacity = it.opacity) } ?: run { order += 1 }

        // Soft glows at both cup bases.
        sprite(x = -l, size = 0.55 + 0.35 * rA, color = p.core, opacity = 0.3 + 0.7 * rA)
        sprite(x = l, size = 0.55 + 0.35 * rB, color = p.core, opacity = 0.3 + 0.7 * rB)

        // Dust lives in the scene, not in the swaying world group.
        add(
            SceneDraw(
                SceneDrawKind.Dust, Mat4.Identity, p.dust.linear(opacity = p.dustOpacity),
                params = floatArrayOf(0.045f, 0f, 1f, ((dustTime * 0.05) % 10).toFloat()),
            ),
            SceneMeshes.dustCenter,
        )

        val sorted = entries.sortedWith { a, b ->
            if (a.depth != b.depth) b.depth.compareTo(a.depth) else a.order.compareTo(b.order)
        }
        return SceneFrame(uniforms, sorted.map { it.draw }, opacity)
    }

    private class StorySprite(val x: Double, val size: Double, val color: SceneColor, val opacity: Double)

    private class ReceiptSprite(val x: Double, val opacity: Double)
}
