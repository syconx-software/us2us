package com.syconx.us2us.scene3d

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The frame structure (what is drawn, in which order) and a JSON dump of the reference frames —
 * uniforms, draws, buffers and the GLSL sources — for `scene3d/tools/gl_replay.py`, which replays
 * them through a desktop OpenGL ES 3 implementation to check the shaders without a device.
 */
class SceneFrameDumpTest {
    private fun frame(dark: Boolean, time: Double) = SceneFrameBuilder.build(
        SceneState.Welcome, ScenePalette.of(dark), time, time,
        canvasWidth = 402.0, canvasHeight = 330.0, targetWidth = 804f, targetHeight = 660f,
    )

    @Test
    fun welcomeFrameHasEveryPart() {
        val frame = frame(dark = true, time = 4.5)
        val kinds = frame.draws.groupingBy { it.kind }.eachCount()
        assertEquals(2, kinds[SceneDrawKind.Cup])
        assertEquals(2, kinds[SceneDrawKind.Solid])
        assertEquals(1, kinds[SceneDrawKind.String])
        assertEquals(1, kinds[SceneDrawKind.GlowLine])
        assertEquals(1, kinds[SceneDrawKind.Dust])
        // Two cup wireframes, the lattice and the relay ring; the scan ring when it is lit.
        val lines = frame.draws.filter { it.kind == SceneDrawKind.Lines }.map { it.lines }
        assertEquals(2, lines.count { it == SceneLines.CupWire })
        assertTrue(SceneLines.Lattice in lines && SceneLines.RelayRing in lines)
        // 8 packets × 5 sprites at most, plus the two end glows; the dark glows add light.
        val sprites = frame.draws.filter { it.kind == SceneDrawKind.Sprite }
        assertTrue(sprites.size in 30..42)
        assertTrue(sprites.all { it.additive })
        assertTrue(frame(dark = false, time = 39.95).draws.none { it.additive })
        // Dust (centre z ≈ −1.48) is the farthest object: three.js draws it first.
        assertEquals(SceneDrawKind.Dust, frame.draws.first().kind)
    }

    @Test
    fun reduceMotionStillFrameIsDeterministic() {
        val a = frame(dark = true, time = Us2usSceneDefaults.STILL_FRAME_TIME)
        val b = frame(dark = true, time = Us2usSceneDefaults.STILL_FRAME_TIME)
        assertEquals(a.draws.size, b.draws.size)
        for (i in a.draws.indices) assertTrue(a.draws[i].model.m.contentEquals(b.draws[i].model.m))
    }

    @Test
    fun narrowCanvasPullsBackAndDims() {
        val narrow = SceneFrameBuilder.build(
            SceneState.Welcome, ScenePalette.Dark, 0.0, 0.0,
            canvasWidth = 300.0, canvasHeight = 500.0, targetWidth = 600f, targetHeight = 1000f,
        )
        // narrow = (1.2 − 0.6) / 0.6 = 1 → opacity 1 − 0.45.
        assertEquals(0.55, narrow.opacity, 1e-9)
    }

    @Test
    fun dumpReferenceFrames() {
        for ((theme, time) in listOf("dark" to 4.5, "light" to 39.95)) {
            val file = File(SceneImages.outputDir, "gl-frame-$theme.json")
            file.writeText(json(frame(theme == "dark", time)))
            assertTrue(file.length() > 1000)
        }
    }

    private fun json(frame: SceneFrame): String = buildString {
        fun floats(values: FloatArray) = values.joinToString(",", "[", "]") { if (it.isFinite()) it.toString() else "0" }
        fun ints(values: IntArray) = values.joinToString(",", "[", "]")
        fun string(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
        val u = frame.uniforms
        append("{\"width\":804,\"height\":660,\"samples\":").append(SceneRenderer.SAMPLE_COUNT)
        append(",\"frame\":").append(
            floats(u.view.m + u.projection.m + u.viewport + u.keyDirection + u.keyColor + u.rimDirection + u.rimColor + u.hemiDirection + u.skyColor + u.groundColor),
        )
        append(",\"shaders\":{")
        val shaders = mapOf(
            "litVertex" to SceneShaders.LIT_VERTEX, "litFragment" to SceneShaders.LIT_FRAGMENT,
            "basicVertex" to SceneShaders.BASIC_VERTEX, "basicFragment" to SceneShaders.BASIC_FRAGMENT,
            "lineVertex" to SceneShaders.LINE_VERTEX, "lineFragment" to SceneShaders.LINE_FRAGMENT,
            "ribbonVertex" to SceneShaders.RIBBON_VERTEX, "ribbonFragment" to SceneShaders.RIBBON_FRAGMENT,
            "spriteVertex" to SceneShaders.SPRITE_VERTEX, "pointsVertex" to SceneShaders.POINTS_VERTEX,
            "spriteFragment" to SceneShaders.SPRITE_FRAGMENT,
            "copyVertex" to SceneShaders.COPY_VERTEX, "copyFragment" to SceneShaders.COPY_FRAGMENT,
        )
        append(shaders.entries.joinToString(",") { (k, v) -> string(k) + ":" + string(v) })
        append("},\"buffers\":{")
        val meshes = mapOf("cup" to SceneMeshes.cup, "rim" to SceneMeshes.rim, "string" to SceneMeshes.string)
        append(
            meshes.entries.joinToString(",") { (k, m) ->
                "\"$k\":{\"vertices\":${floats(m.vertices)},\"indices\":${ints(IntArray(m.indices.size) { m.indices[it].toInt() })}}"
            },
        )
        for (kind in SceneLines.entries) append(",\"${kind.name}\":").append(floats(SceneMeshes.lines(kind)))
        append(",\"glowLine\":").append(floats(SceneMeshes.glowLine))
        append(",\"dust\":").append(floats(SceneMeshes.dust))
        append(",\"dot\":").append(ints(IntArray(SceneMeshes.dotTexture.size) { SceneMeshes.dotTexture[it].toInt() and 0xFF }))
        append("},\"draws\":[")
        append(
            frame.draws.joinToString(",") { d ->
                "{\"kind\":\"${d.kind.name}\",\"model\":${floats(d.model.m)},\"normalMatrix\":${floats(d.normalMatrix.m)}," +
                    "\"color\":${floats(d.color)},\"params\":${floats(d.params)},\"lines\":${d.lines?.let { "\"${it.name}\"" } ?: "null"}," +
                    "\"additive\":${d.additive}}"
            },
        )
        append("]}")
    }
}
