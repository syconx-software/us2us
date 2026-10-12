// us2us — the welcome screen's 3D scene ("İki uç. Bir hat."): two cups and
// the line between them, with light packets flowing both ways.
//
// An OpenGL ES 3 port of the iOS Metal scene (itself a port of the design's
// three.js scene, us2us-scene.js) with the same geometry, materials, lights,
// blending and motion. The background is transparent; place it over the
// screen's own background.
//
//   WelcomeScene(dark, frozenSeconds)                   // design 1.1
//   Us2usScene(dark, state = SceneState.Welcome)
//   Us2usScene(dark, isActive = false)                  // covered: no GPU work
//
// Behaviour
//   • Sways slowly; packets travel along the line in both directions.
//   • Reduce motion: a single still frame, no animation.
//   • Renders only while on screen, active and the app is resumed (paused
//     otherwise; never draws while the app is in the background), at most
//     60 fps (30 fps in battery saver or when the device is hot), at 2 px per
//     point with 4× MSAA.
//   • Fades in softly once its first frame is ready.

package com.syconx.us2us.scene3d

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.env.LocalU2Clock
import com.syconx.us2us.ui.motion.LocalReduceMotion
import com.syconx.us2us.ui.nav.LocalIsLayerVisible
import com.syconx.us2us.ui.nav.LocalLayerMotion
import com.syconx.us2us.ui.tokens.U2Scene3DTokens

/**
 * The 1.1 welcome scene: a transparent 330 dp tall canvas across the width (place it at design
 * y 84 over the `bg` + dot grid).
 *
 * - [frozenSeconds] pins it to one frame; the reference PNGs were captured at
 *   [WelcomeSceneReference.seconds] (dark t = 4.5 s, light t = 39.95 s). `null` lets it run, except
 *   under the frozen design clock (`LocalU2Clock` frozen), where it is pinned to the reference
 *   moment so screenshots compare 1:1.
 * - It pauses (no GPU work) while its layer is covered (`LocalIsLayerVisible`), and holds its last
 *   frame while the stack or pager it is on is moving (`LocalLayerMotion`), so the transition has
 *   every frame to itself; [isActive] `false` pauses it too.
 */
@Composable
fun WelcomeScene(dark: Boolean, frozenSeconds: Float?, modifier: Modifier = Modifier, isActive: Boolean = true) {
    val clock = LocalU2Clock.current
    val isVisible = LocalIsLayerVisible.current
    val motion = LocalLayerMotion.current
    val pinned = frozenSeconds?.let(WelcomeSceneReference::exact)
        ?: if (clock.isFrozen) WelcomeSceneReference.seconds(dark).toDouble() else null
    Us2usScene(
        dark = dark,
        modifier = modifier.fillMaxWidth().height(U2Scene3DTokens.canvas.height),
        state = SceneState.Welcome,
        pinnedSeconds = pinned,
        isActive = isActive && isVisible && (motion?.isSettled ?: true),
    )
}

/** The scene times of `ios/design/reference/1.1-karsilama.png` (dark) and `reference/light/1.1-karsilama.png`. */
object WelcomeSceneReference {
    private const val DARK = 4.5
    private const val LIGHT = 39.95

    /**
     * The canvas was captured while the web scene was running; these are the times whose frames
     * match the references best (sway, packets, lattice).
     */
    fun seconds(dark: Boolean): Float = (if (dark) DARK else LIGHT).toFloat()

    /** [seconds] back in the design's decimals (39.95f → 39.95). */
    internal fun exact(seconds: Float): Double = seconds.toString().toDouble()
}

object Us2usSceneDefaults {
    /** Time of the still frame shown when reduce motion is on: the packets are spread evenly along the line. */
    const val STILL_FRAME_TIME = 2.0
}

/**
 * The scene in [state] for the [dark] or light palette. [pinnedSeconds] pins it to a fixed time
 * (screenshots, previews, design comparisons); [isActive] `false` pauses it while it is in the
 * hierarchy but cannot be seen — the last frame stays in place and nothing is rendered.
 */
@Composable
fun Us2usScene(
    dark: Boolean,
    modifier: Modifier = Modifier,
    state: SceneState = SceneState.Welcome,
    pinnedSeconds: Double? = null,
    isActive: Boolean = true,
) {
    val reduceMotion = LocalReduceMotion.current
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val content = SceneContent(
        palette = ScenePalette.of(dark),
        state = state,
        frozenTime = pinnedSeconds ?: if (reduceMotion) Us2usSceneDefaults.STILL_FRAME_TIME else null,
    )
    val isForeground = lifecycle.isAtLeast(Lifecycle.State.STARTED)
    val isRunning = isActive && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val label = stringResource(R.string.scene3d_accessibilitylabel)

    // Invisible until the first frame is ready, then a soft 0.6 s fade; later opacity changes
    // (dim, narrow canvases) ease over 0.4 s.
    val alpha = remember { Animatable(0f) }
    var firstFrameShown by remember { mutableStateOf(false) }
    var canvasOpacity by remember { mutableFloatStateOf(1f) }
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(firstFrameShown, canvasOpacity) {
        if (!firstFrameShown) return@LaunchedEffect
        if (!revealed) {
            revealed = true
            alpha.animateTo(canvasOpacity, tween(durationMillis = 600, easing = EaseOut))
        } else {
            alpha.animateTo(canvasOpacity, tween(durationMillis = 400, easing = EaseInOut))
        }
    }

    Box(
        modifier.clearAndSetSemantics {
            contentDescription = label
            role = Role.Image
        },
    ) {
        if (SceneStills.isGpuAvailable && !LocalInspectionMode.current) {
            var view by remember { mutableStateOf<SceneView?>(null) }
            AndroidView(
                factory = { context ->
                    SceneView(context).apply {
                        this.alpha = 0f
                        onFirstFrame = { firstFrameShown = true }
                        onOpacityChange = { canvasOpacity = it.toFloat() }
                        view = this
                    }
                },
                modifier = Modifier.matchParentSize(),
                update = { it.configure(content, isRunning = isRunning, isForeground = isForeground) },
                onRelease = { it.stop() },
            )
            LaunchedEffect(view) {
                val target = view ?: return@LaunchedEffect
                snapshotFlow { alpha.value }.collect { target.alpha = it }
            }
        } else {
            SceneStill(
                content = content,
                alpha = { alpha.value },
                onFirstFrame = { opacity ->
                    canvasOpacity = opacity.toFloat()
                    firstFrameShown = true
                },
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

/** UIKit's `.curveEaseOut` and `.curveEaseInOut`. */
private val EaseOut = CubicBezierEasing(0f, 0f, 0.58f, 1f)
private val EaseInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

@Preview(name = "Welcome · dark", widthDp = 402, heightDp = 330, showBackground = true, backgroundColor = 0xFF161826)
@Composable
private fun WelcomeSceneDarkPreview() {
    Us2usScene(dark = true, modifier = Modifier.fillMaxSize(), pinnedSeconds = WelcomeSceneReference.exact(WelcomeSceneReference.seconds(dark = true)))
}

@Preview(name = "Welcome · light", widthDp = 402, heightDp = 330, showBackground = true, backgroundColor = 0xFFF3F5FE)
@Composable
private fun WelcomeSceneLightPreview() {
    Us2usScene(dark = false, modifier = Modifier.fillMaxSize(), pinnedSeconds = WelcomeSceneReference.exact(WelcomeSceneReference.seconds(dark = false)))
}
