package com.syconx.us2us.feature.settings.avatar

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.components.SheetBar
import com.syconx.us2us.ui.components.U2PrimaryButton
import com.syconx.us2us.ui.env.u2Bottom
import com.syconx.us2us.ui.env.u2Top
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.icons.PhosphorIcon
import com.syconx.us2us.ui.motion.LocalReduceMotion
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.screen.U2Screen
import com.syconx.us2us.ui.sheet.U2Toast
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.tokens.U2Radius
import com.syconx.us2us.ui.tokens.U2Spacing
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** Test tags of “Fotoğrafı yerleştir” (the iOS accessibility identifiers). */
object AvatarCropTags {
    const val SCREEN = "avatarCrop"
    const val BAR = "avatarCrop.bar"
    const val VIEWPORT = "avatarCrop.viewport"
    const val USE = "avatarCrop.use"
    const val SEARCH_NOTE = "avatarCrop.searchNote"
}

/**
 * “Fotoğrafı yerleştir” (proposed: the design has no avatar editor): a picked or taken photo
 * framed for the profile picture, in a full-screen fade cover over the full-screen picker. The
 * sheet bar (“Vazgeç”, title) on top; a square viewport (screen gutter 20, r20) where the photo
 * fills the square, moves with one finger and zooms with two (or a double tap), the circle that
 * will be shown lit and the corners veiled; a hint and, while “Aramada göster” is on, who will be
 * able to see it; “Kullan” (C1) at the bottom. TalkBack: the viewport's zoom is adjustable and it
 * has actions that move the frame left, right, up and down.
 *
 * Nothing leaves the phone from here: “Kullan” hands the framed square to the messenger, which
 * scales it to ≤ 256 px without metadata.
 */
@Composable
fun AvatarCropView(model: AvatarEditorModel, source: AvatarSourceImage, notesSearch: Boolean, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val isSaving = model.phase == AvatarEditorModel.Phase.Saving
    val colors = U2Theme.colors
    val state = remember(source) { CropState(source) }
    U2Screen(modifier.testTag(AvatarCropTags.SCREEN)) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .u2Top(62.dp)
                    .blocksTouches(isSaving)
                    .testTag(AvatarCropTags.BAR),
            ) {
                SheetBar(
                    title = stringResource(R.string.settings_avatar_crop_title),
                    cancelTitle = stringResource(R.string.common_cancel),
                    onCancel = model::cancelFraming,
                )
            }
            Spacer(Modifier.weight(1f).heightIn(min = 16.dp))
            CropViewport(
                state,
                Modifier
                    .padding(horizontal = U2Spacing.screenGutter)
                    .widthIn(max = 420.dp)
                    .fillMaxWidth(),
            )
            Column(
                Modifier.padding(start = 28.dp, top = 16.dp, end = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                U2Text(stringResource(R.string.settings_avatar_crop_hint), U2TextStyles.monoMeta, color = colors.textSecondary, textAlign = TextAlign.Center)
                if (notesSearch) {
                    val note = stringResource(R.string.settings_avatar_search_note)
                    Row(
                        Modifier
                            .testTag(AvatarCropTags.SEARCH_NOTE)
                            .clearAndSetSemantics { contentDescription = note },
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PhosphorIcon(PhosphorGlyph.Eye, size = 16.dp, tint = colors.accent300, modifier = Modifier.padding(top = 1.dp))
                        U2Text(note, U2TextStyles.footnote, color = colors.textSecondary)
                    }
                }
            }
            Spacer(Modifier.weight(1f).heightIn(min = 16.dp))
            U2PrimaryButton(
                title = stringResource(R.string.settings_avatar_crop_use),
                onClick = {
                    val crop = state.geometry.cropRect(state.zoom.value.toDouble(), state.committedOffset())
                    scope.launch { model.useFramedPhoto(crop) }
                },
                glyph = PhosphorGlyph.Check,
                enabled = !isSaving && state.side > 0f,
                isWorking = isSaving,
                modifier = Modifier
                    .padding(horizontal = U2Spacing.screenGutter)
                    .u2Bottom(U2Spacing.ctaBottom)
                    .testTag(AvatarCropTags.USE),
            )
        }
        U2Toast(message = model.toast?.let { stringResource(it) }, onDismiss = { model.toast = null })
    }
}

/** The viewport's zoom and offset (committed, animatable) and the gesture in progress. */
private class CropState(val source: AvatarSourceImage) {
    val image = source.bitmap.asImageBitmap()
    var side by mutableFloatStateOf(0f)
    val zoom = Animatable(1f)
    val offsetX = Animatable(0f)
    val offsetY = Animatable(0f)

    /** The pinch and drag of the gesture in progress (1 / zero when none). */
    var pinch by mutableFloatStateOf(1f)
    var drag by mutableStateOf(Offset.Zero)

    val geometry: AvatarCropGeometry
        get() = AvatarCropGeometry(source.pixelWidth.toDouble(), source.pixelHeight.toDouble(), side.coerceAtLeast(1f).toDouble())

    fun committedOffset() = AvatarCropGeometry.Vector(offsetX.value.toDouble(), offsetY.value.toDouble())

    /** What is on screen now: the committed state with the gesture applied, clamped as it goes. */
    fun live(): Pair<Double, AvatarCropGeometry.Vector> {
        val g = geometry
        val liveZoom = g.clampedZoom(zoom.value.toDouble() * pinch)
        val liveOffset = g.clampedOffset(AvatarCropGeometry.Vector(offsetX.value.toDouble() + drag.x, offsetY.value.toDouble() + drag.y), liveZoom)
        return liveZoom to liveOffset
    }
}

@Composable
private fun CropViewport(state: CropState, modifier: Modifier = Modifier) {
    val colors = U2Theme.colors
    val scope = rememberCoroutineScope()
    val reduceMotion = LocalReduceMotion.current
    val density = LocalDensity.current
    val shape = RoundedCornerShape(U2Radius.cardL)
    val spec = U2Motion.resolve(U2Motion.state, reduceMotion).spec<Float>()

    /** Moves the committed state to ([zoom], [offset]), animated, from what is on screen. */
    fun settle(zoom: Double, offset: AvatarCropGeometry.Vector) {
        val (shownZoom, shownOffset) = state.live()
        scope.launch {
            state.zoom.snapTo(shownZoom.toFloat())
            state.offsetX.snapTo(shownOffset.x.toFloat())
            state.offsetY.snapTo(shownOffset.y.toFloat())
            state.pinch = 1f
            state.drag = Offset.Zero
            launch { state.zoom.animateTo(zoom.toFloat(), spec) }
            launch { state.offsetX.animateTo(offset.x.toFloat(), spec) }
            state.offsetY.animateTo(offset.y.toFloat(), spec)
        }
    }

    fun setZoom(target: Double) {
        val g = state.geometry
        val zoom = g.clampedZoom(target)
        settle(zoom, g.clampedOffset(state.committedOffset(), zoom))
    }

    /** The accessibility actions' move: the frame goes [x], [y] steps over the photo (the photo the other way), a quarter of the free travel each. */
    fun moveFrame(x: Int, y: Int) {
        val g = state.geometry
        val zoom = state.zoom.value.toDouble()
        val size = g.displayedSize(zoom)
        val stepX = maxOf(0.0, size.x - g.side) / 4
        val stepY = maxOf(0.0, size.y - g.side) / 4
        val committed = state.committedOffset()
        settle(zoom, g.clampedOffset(AvatarCropGeometry.Vector(committed.x - x * stepX, committed.y - y * stepY), zoom))
    }

    val area = stringResource(R.string.settings_avatar_crop_area)
    val zoomValue = stringResource(R.string.settings_avatar_crop_zoom, (state.zoom.value * 100).roundToInt())
    val moveLeft = stringResource(R.string.settings_avatar_crop_move_left)
    val moveRight = stringResource(R.string.settings_avatar_crop_move_right)
    val moveUp = stringResource(R.string.settings_avatar_crop_move_up)
    val moveDown = stringResource(R.string.settings_avatar_crop_move_down)
    val maxZoom = state.geometry.maxZoom.toFloat()
    val touchSlop = with(density) { 2.dp.toPx() }

    Box(
        modifier
            .testTag(AvatarCropTags.VIEWPORT)
            .aspectRatio(1f)
            .onSizeChanged { size: IntSize -> state.side = size.width.toFloat() }
            .clip(shape)
            .background(colors.surface)
            .pointerInput(state) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var moved = false
                    var travelled = Offset.Zero
                    var pinched = false
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val zoomChange = event.calculateZoom()
                        val pan = event.calculatePan()
                        travelled += pan
                        if (!moved && (abs(travelled.x) > touchSlop || abs(travelled.y) > touchSlop || zoomChange != 1f)) moved = true
                        if (moved) {
                            if (zoomChange != 1f) pinched = true
                            state.pinch *= zoomChange
                            state.drag += pan
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    if (moved) {
                        val g = state.geometry
                        val zoom = if (pinched) g.clampedZoom(state.zoom.value.toDouble() * state.pinch) else state.zoom.value.toDouble()
                        val offset = g.clampedOffset(
                            AvatarCropGeometry.Vector(state.offsetX.value.toDouble() + state.drag.x, state.offsetY.value.toDouble() + state.drag.y),
                            zoom,
                        )
                        settle(zoom, offset)
                    }
                }
            }
            .pointerInput(state) {
                // Double tap: in to 2× (or as far as it goes), and back out.
                detectTapGestures(onDoubleTap = {
                    val zoom = state.zoom.value
                    setZoom(if (zoom > 1.01f) 1.0 else minOf(2.0, state.geometry.maxZoom))
                })
            }
            .clearAndSetSemantics {
                contentDescription = area
                stateDescription = zoomValue
                progressBarRangeInfo = ProgressBarRangeInfo(state.zoom.value, 1f..maxOf(1f, maxZoom))
                setProgress { target ->
                    setZoom(target.toDouble())
                    true
                }
                customActions = listOf(
                    CustomAccessibilityAction(moveLeft) { moveFrame(-1, 0); true },
                    CustomAccessibilityAction(moveRight) { moveFrame(1, 0); true },
                    CustomAccessibilityAction(moveUp) { moveFrame(0, -1); true },
                    CustomAccessibilityAction(moveDown) { moveFrame(0, 1); true },
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            if (state.side <= 0f) return@Canvas
            val (zoom, offset) = state.live()
            val displayed = state.geometry.displayedSize(zoom)
            val left = (size.width - displayed.x.toFloat()) / 2f + offset.x.toFloat()
            val top = (size.height - displayed.y.toFloat()) / 2f + offset.y.toFloat()
            drawImage(
                state.image,
                dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                dstSize = IntSize(displayed.x.roundToInt(), displayed.y.roundToInt()),
                filterQuality = FilterQuality.High,
            )
            // The corners outside the circle under a `bg` veil, and the circle's edge.
            val inset = 1.dp.toPx()
            val circle = Rect(inset, inset, size.width - inset, size.height - inset)
            val veil = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(0f, 0f, size.width, size.height))
                addOval(circle)
            }
            drawPath(veil, colors.bg.copy(alpha = 0.62f))
            val ring = 1.5.dp.toPx()
            drawCircle(
                colors.accent300.copy(alpha = 0.9f),
                radius = circle.width / 2f - ring / 2f,
                center = circle.center,
                style = Stroke(ring),
            )
        }
    }
}

/** While [blocks], the content takes no touches (iOS `.disabled`). */
private fun Modifier.blocksTouches(blocks: Boolean): Modifier = if (!blocks) this else pointerInput(Unit) {
    awaitEachGesture {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            event.changes.forEach { it.consume() }
            if (event.changes.none { it.pressed }) break
        }
    }
}
