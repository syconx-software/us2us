// us2us — scene states and palettes, mirroring `S` and `PAL` in us2us-scene.js.

package com.syconx.us2us.scene3d

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.syconx.us2us.ui.tokens.U2Scene3DTokens
import com.syconx.us2us.ui.tokens.U2ScenePalette

/**
 * Where the camera sits and what the line shows. Field names follow the keys of the design's
 * scene states (`S` in us2us-scene.js).
 */
data class SceneState(
    /** Camera position (`cx`, `cy`, `cz`). */
    val camera: SceneVector,
    /** Point the camera looks at (`tx`, `ty`, `tz`). */
    val target: SceneVector,
    /** Horizontal offset of the cups on wide canvases (`wx`). */
    val worldOffsetX: Double,
    /** Base yaw of the cups, radians (`ry`); the scene sways ±0.07 around it. */
    val yaw: Double,
    /** Half the distance between the two cup bases (`L`). */
    val halfLength: Double,
    /** Strength of the light packets travelling left → right (`flow`). */
    val flow: Double,
    /** Strength of the light packets travelling right → left (`back`). */
    val back: Double,
    /** Visibility of the hexagonal tunnel around the line (`tunnel`). */
    val tunnel: Double,
    /**
     * Size of the sealed-envelope octahedra on the packets (`seal`). The design draws them fully
     * transparent, so it has no visible effect.
     */
    val seal: Double,
    /** Visibility of the single "message" packet of the status story (`story`). */
    val story: Double,
    /** Progress through the status story, 0…1 (the web page's scroll progress). */
    val storyProgress: Double = 0.0,
    /** Dims the whole scene (`dim`, 1 → 20 % opacity). */
    val dim: Double,
) {
    companion object {
        /** The welcome screen (design 1.1): `tokens.json › scene3D › fixedState`. */
        val Welcome: SceneState = U2Scene3DTokens.FixedState.let { s ->
            SceneState(
                camera = SceneVector(s.cx.exact(), s.cy.exact(), s.cz.exact()),
                target = SceneVector(s.tx.exact(), s.ty.exact(), s.tz.exact()),
                worldOffsetX = s.wx.exact(), yaw = s.ry.exact(), halfLength = s.L.exact(),
                flow = s.flow.exact(), back = s.back.exact(), tunnel = s.tunnel.exact(),
                seal = s.seal.exact(), story = s.story.exact(), dim = s.dim.exact(),
            )
        }

        // The website's scroll states, for completeness.
        val Hero = SceneState(
            camera = SceneVector(0.0, 0.55, 7.2), target = SceneVector.Zero, worldOffsetX = 1.45, yaw = -0.5, halfLength = 0.95,
            flow = 1.0, back = 0.0, tunnel = 0.0, seal = 0.0, story = 0.0, dim = 0.0,
        )
        val Line = SceneState(
            camera = SceneVector(0.0, 0.32, 3.7), target = SceneVector.Zero, worldOffsetX = 0.95, yaw = -0.24, halfLength = 1.2,
            flow = 1.0, back = 0.0, tunnel = 1.0, seal = 1.0, story = 0.0, dim = 0.0,
        )
        val Status = SceneState(
            camera = SceneVector(0.0, 0.42, 4.9), target = SceneVector.Zero, worldOffsetX = 1.3, yaw = -0.12, halfLength = 1.2,
            flow = 0.0, back = 0.0, tunnel = 0.5, seal = 0.0, story = 1.0, dim = 0.0,
        )
        val Ghost = SceneState(
            camera = SceneVector(0.0, 0.75, 6.0), target = SceneVector.Zero, worldOffsetX = 1.4, yaw = -0.42, halfLength = 1.0,
            flow = 0.3, back = 0.0, tunnel = 0.0, seal = 0.0, story = 0.0, dim = 0.0,
        )
        val Features = SceneState(
            camera = SceneVector(0.0, 1.9, 9.5), target = SceneVector.Zero, worldOffsetX = 0.0, yaw = 0.2, halfLength = 1.0,
            flow = 0.7, back = 0.5, tunnel = 0.3, seal = 0.0, story = 0.0, dim = 0.72,
        )
        val Verify = SceneState(
            camera = SceneVector(-2.3, 0.62, 1.5), target = SceneVector(0.9, -0.05, -0.6), worldOffsetX = 0.0, yaw = 0.0, halfLength = 1.2,
            flow = 1.0, back = 0.0, tunnel = 1.0, seal = 1.0, story = 0.0, dim = 0.25,
        )
        val Cta = SceneState(
            camera = SceneVector(0.0, 0.35, 5.6), target = SceneVector.Zero, worldOffsetX = 1.3, yaw = -0.6, halfLength = 0.62,
            flow = 1.0, back = 1.0, tunnel = 0.35, seal = 0.0, story = 0.0, dim = 0.0,
        )
    }
}

/** Colours and per-theme strengths (`PAL` in us2us-scene.js; `tokens.json › scene3D › palette`). */
internal class ScenePalette(
    val cup: SceneColor,
    val edge: SceneColor,
    val line: SceneColor,
    val core: SceneColor,
    val glow: SceneColor,
    val dust: SceneColor,
    val dead: SceneColor,
    val sky: SceneColor,
    val ground: SceneColor,
    val key: SceneColor,
    val rimLight: SceneColor,
    /** Glows add light (dark theme) or are painted normally (light theme). */
    val additive: Boolean,
    val wireOpacity: Double,
    val dustOpacity: Double,
    val glowOpacity: Double,
    val isDark: Boolean,
) {
    companion object {
        val Dark = of(U2Scene3DTokens.dark, dead = 0x75798C, isDark = true)
        val Light = of(U2Scene3DTokens.light, dead = 0x9397AB, isDark = false)

        fun of(dark: Boolean): ScenePalette = if (dark) Dark else Light

        /**
         * The token palette plus `dead` (the status story's failed packet), which tokens.json does
         * not list.
         */
        private fun of(p: U2ScenePalette, dead: Int, isDark: Boolean): ScenePalette {
            fun hex(c: Color) = c.toArgb() and 0xFFFFFF
            return ScenePalette(
                cup = SceneColor(hex(p.cup)), edge = SceneColor(hex(p.edge)), line = SceneColor(hex(p.line)),
                core = SceneColor(hex(p.core)), glow = SceneColor(hex(p.glow)), dust = SceneColor(hex(p.dust)),
                dead = SceneColor(dead), sky = SceneColor(hex(p.sky)), ground = SceneColor(hex(p.ground)),
                key = SceneColor(hex(p.key)), rimLight = SceneColor(hex(p.rim)),
                additive = p.additiveBlending,
                wireOpacity = p.wireOp.exact(), dustOpacity = p.dustOp.exact(), glowOpacity = p.glowOp.exact(),
                isDark = isDark,
            )
        }
    }
}

/** The design's decimal for a token stored in single precision (0.55f → 0.55). */
private fun Float.exact(): Double = toString().toDouble()
