package com.syconx.us2us.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** `pressFeedback`: the scale of a pressed control. */
const val U2PressScale: Float = 0.97f

/**
 * `U2PressEffect`: scales the content to [scale] while a press from [interactionSource] is
 * down (0.16 s soft in, 0.24 s soft release). The fill brightening that goes with it is drawn
 * by each control. Nothing moves under reduce motion.
 *
 * Use it with a clickable that shares the interaction source and has no indication:
 * `Modifier.u2PressEffect(source).clickable(source, indication = null) { … }`.
 */
fun Modifier.u2PressEffect(interactionSource: InteractionSource, scale: Float = U2PressScale): Modifier =
    this then PressEffectElement(interactionSource = interactionSource, pressed = null, scale = scale)

/** [u2PressEffect] driven by a pressed flag the caller tracks (e.g. a long-press gesture). */
fun Modifier.u2PressEffect(pressed: Boolean, scale: Float = U2PressScale): Modifier =
    this then PressEffectElement(interactionSource = null, pressed = pressed, scale = scale)

private data class PressEffectElement(
    val interactionSource: InteractionSource?,
    val pressed: Boolean?,
    val scale: Float,
) : ModifierNodeElement<PressEffectNode>() {
    override fun create() = PressEffectNode(interactionSource, pressed, scale)

    override fun update(node: PressEffectNode) = node.update(interactionSource, pressed, scale)

    override fun InspectorInfo.inspectableProperties() {
        name = "u2PressEffect"
        properties["scale"] = scale
        properties["pressed"] = pressed
    }
}

private class PressEffectNode(
    private var interactionSource: InteractionSource?,
    private var pressed: Boolean?,
    private var scale: Float,
) : Modifier.Node(), LayoutModifierNode, CompositionLocalConsumerModifierNode {
    /** 0 = at rest, 1 = fully pressed. */
    private val progress = Animatable(0f)
    private var collectJob: Job? = null
    private var isDown = false

    override fun onAttach() {
        collect()
        pressed?.let { setDown(it) }
    }

    override fun onDetach() {
        collectJob = null
        isDown = false
    }

    fun update(source: InteractionSource?, pressed: Boolean?, scale: Float) {
        this.scale = scale
        if (source != interactionSource) {
            interactionSource = source
            collectJob?.cancel()
            if (isAttached) collect()
        }
        this.pressed = pressed
        if (pressed != null && isAttached) setDown(pressed)
    }

    private fun collect() {
        val source = interactionSource ?: return
        collectJob = coroutineScope.launch {
            val presses = mutableListOf<PressInteraction.Press>()
            source.interactions.collect { interaction: Interaction ->
                when (interaction) {
                    is PressInteraction.Press -> presses += interaction
                    is PressInteraction.Release -> presses -= interaction.press
                    is PressInteraction.Cancel -> presses -= interaction.press
                }
                setDown(presses.isNotEmpty())
            }
        }
    }

    private fun setDown(down: Boolean) {
        if (down == isDown) return
        isDown = down
        coroutineScope.launch {
            progress.animateTo(
                targetValue = if (down) 1f else 0f,
                animationSpec = if (down) U2Motion.press.spec() else U2Motion.release.spec(),
            )
        }
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0) {
                val s = if (currentValueOf(LocalReduceMotion)) 1f else 1f - (1f - scale) * progress.value
                scaleX = s
                scaleY = s
            }
        }
    }
}
