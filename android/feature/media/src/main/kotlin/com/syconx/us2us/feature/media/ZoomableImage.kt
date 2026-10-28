package com.syconx.us2us.feature.media

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import com.syconx.us2us.ui.motion.LocalReduceMotion
import com.syconx.us2us.ui.motion.U2Motion
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The viewer's zoom arithmetic (pure, so it is tested on its own). Sizes and offsets in px. */
object ZoomMath {
    /** [image] scaled to fit inside [bounds], keeping its proportions. */
    fun fittedSize(image: Size, bounds: Size): Size {
        if (image.width <= 0f || image.height <= 0f || bounds.width <= 0f || bounds.height <= 0f) return Size.Zero
        val scale = min(bounds.width / image.width, bounds.height / image.height)
        return Size(image.width * scale, image.height * scale)
    }

    /** The deepest zoom: at least 4×, more for a detailed photo (twice its own pixels, at most 10×). */
    fun maximumZoom(pixelWidth: Float, fittedWidthPx: Float): Float {
        if (fittedWidthPx <= 0f) return 4f
        val native = pixelWidth / fittedWidthPx
        return max(4f, min(10f, native * 2f))
    }

    /** The double-tap zoom. */
    const val DOUBLE_TAP_SCALE = 2.5f

    /**
     * The offset (of the photo's centre from the screen's centre) that keeps the content point under
     * [focus] (relative to the screen's centre) in place while the scale goes from [scale] to [newScale].
     */
    fun zoomedOffset(offset: Offset, focus: Offset, scale: Float, newScale: Float): Offset {
        if (scale <= 0f) return offset
        val factor = newScale / scale
        return focus - (focus - offset) * factor
    }

    /** The offset that brings the content point under [point] (relative to the screen's centre) to the middle at [newScale]. */
    fun centeredOffset(offset: Offset, point: Offset, scale: Float, newScale: Float): Offset {
        if (scale <= 0f) return Offset.Zero
        val content = (point - offset) / scale
        return -content * newScale
    }

    /** How far the photo may move each way at [scale]: none while it is smaller than the screen. */
    fun maxOffset(fitted: Size, bounds: Size, scale: Float): Offset = Offset(
        max(0f, (fitted.width * scale - bounds.width) / 2f),
        max(0f, (fitted.height * scale - bounds.height) / 2f),
    )

    /** [offset] kept inside the photo's travel at [scale]. */
    fun clampOffset(offset: Offset, fitted: Size, bounds: Size, scale: Float): Offset {
        val limit = maxOffset(fitted, bounds, scale)
        return Offset(offset.x.coerceIn(-limit.x, limit.x), offset.y.coerceIn(-limit.y, limit.y))
    }
}

/** The zoom, pan and swipe-away of one photo. Values in px. */
@Stable
internal class ZoomState {
    var bounds by mutableStateOf(Size.Zero)
    var imageSize by mutableStateOf(Size.Zero)
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)

    /** The swipe away: where the finger moved the photo, and the extra shrink of the throw. */
    var dismissTranslation by mutableStateOf(Offset.Zero)
    var dismissScale by mutableFloatStateOf(1f)
    var isDismissing by mutableStateOf(false)
    var maxScale by mutableFloatStateOf(4f)

    private var animation: Job? = null

    val fitted: Size get() = ZoomMath.fittedSize(imageSize, bounds)
    val isZoomed: Boolean get() = scale > 1.01f

    fun stop() {
        animation?.cancel()
        animation = null
    }

    fun run(scope: CoroutineScope, block: suspend CoroutineScope.() -> Unit) {
        stop()
        animation = scope.launch(block = block)
    }

    /** One step of a pinch or pan: zoom by [zoom] about [focus] (relative to the centre), then move by [pan]. */
    fun transform(focus: Offset, zoom: Float, pan: Offset) {
        val newScale = (scale * zoom).coerceIn(0.8f, maxScale * 1.15f)
        val zoomed = ZoomMath.zoomedOffset(offset, focus, scale, newScale)
        scale = newScale
        offset = rubberBand(zoomed + pan)
    }

    /** Past the travel, the photo follows the finger at a third. */
    private fun rubberBand(target: Offset): Offset {
        val limit = ZoomMath.maxOffset(fitted, bounds, max(scale, 1f))
        fun band(value: Float, limit: Float): Float = when {
            value > limit -> limit + (value - limit) / 3f
            value < -limit -> -limit + (value + limit) / 3f
            else -> value
        }
        return Offset(band(target.x, limit.x), band(target.y, limit.y))
    }
}

/**
 * A photo fitted to the screen at 1× that zooms like a scroll view: pinch (up to 4× or the photo's
 * own detail), double-tap to zoom in at that point (2.5×) and out again, pan while zoomed (with the
 * finger's speed carried on), and the swipe away at 1× (the photo follows the finger; the settle
 * carries the finger's speed).
 */
@Composable
internal fun ZoomableImage(
    image: ImageBitmap?,
    contentDescription: String,
    zoomHint: String,
    onSingleTap: () -> Unit,
    onZoomChange: (Boolean) -> Unit,
    onDismissProgress: (Float, Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    startsZoomed: Boolean = false,
    locale: Locale = Locale.getDefault(),
    onNavigate: ((Boolean) -> Unit)? = null,
) {
    val state = remember { ZoomState() }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val reduceMotion = LocalReduceMotion.current
    val currentOnSingleTap by rememberUpdatedState(onSingleTap)
    val currentOnZoomChange by rememberUpdatedState(onZoomChange)
    val currentOnDismissProgress by rememberUpdatedState(onDismissProgress)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val currentOnNavigate by rememberUpdatedState(onNavigate)
    // The gesture handlers below live as long as the photo's pointer input (keyed on the state, not
    // the image) and start at the first touch: they read the photo through this, not the parameter,
    // so a touch made before the photo was decoded does not leave double-tap, pinch and swipe-away
    // off for good once it has arrived.
    val currentImage by rememberUpdatedState(image)
    var appliedStartZoom by remember { mutableStateOf(false) }

    // The image and its proportions: the full photo replaces the thumbnail in place.
    LaunchedEffect(image) {
        val size = image?.let { Size(it.width.toFloat(), it.height.toFloat()) } ?: Size.Zero
        val sameAspect = state.imageSize.height > 0f && size.height > 0f &&
            abs(state.imageSize.width / state.imageSize.height - size.width / size.height) < 0.02f
        if (!sameAspect) {
            state.stop()
            state.scale = 1f
            state.offset = Offset.Zero
        }
        state.imageSize = size
    }
    LaunchedEffect(state.fitted, image) {
        val fitted = state.fitted
        if (image == null || fitted.width <= 0f) return@LaunchedEffect
        state.maxScale = ZoomMath.maximumZoom(image.width.toFloat(), fitted.width)
        if (startsZoomed && !appliedStartZoom) {
            appliedStartZoom = true
            state.scale = min(state.maxScale, ZoomMath.DOUBLE_TAP_SCALE)
            state.offset = Offset.Zero
        }
    }
    val zoomed = state.isZoomed
    LaunchedEffect(zoomed) { currentOnZoomChange(zoomed) }

    fun animateTo(scale: Float, offset: Offset, millis: Int = 300) {
        state.run(scope) {
            val fromScale = state.scale
            val fromOffset = state.offset
            animate(0f, 1f, animationSpec = U2Motion.resolve(tween(millis, easing = U2Motion.SoftEasing), reduceMotion)) { t, _ ->
                state.scale = fromScale + (scale - fromScale) * t
                state.offset = fromOffset + (offset - fromOffset) * t
            }
        }
    }

    /** After a pinch or pan: back inside 1…max and the travel, the pan carrying the finger's speed. */
    fun settle(velocity: Offset, focus: Offset) {
        val targetScale = state.scale.coerceIn(1f, state.maxScale)
        if (targetScale != state.scale) {
            val offset = ZoomMath.clampOffset(
                ZoomMath.zoomedOffset(state.offset, focus, state.scale, targetScale),
                state.fitted, state.bounds, targetScale,
            )
            animateTo(targetScale, offset)
            return
        }
        val limit = ZoomMath.maxOffset(state.fitted, state.bounds, state.scale)
        state.run(scope) {
            val clamped = ZoomMath.clampOffset(state.offset, state.fitted, state.bounds, state.scale)
            if (clamped != state.offset) {
                val from = state.offset
                animate(0f, 1f, animationSpec = U2Motion.spring()) { t, _ -> state.offset = from + (clamped - from) * t }
                return@run
            }
            // Deceleration, stopped at the edges.
            val decay = exponentialDecay<Float>(frictionMultiplier = 1.6f)
            launch {
                AnimationState(state.offset.x, velocity.x).animateDecay(decay) {
                    val x = value.coerceIn(-limit.x, limit.x)
                    state.offset = Offset(x, state.offset.y)
                    if (x != value) cancelAnimation()
                }
            }
            launch {
                AnimationState(state.offset.y, velocity.y).animateDecay(decay) {
                    val y = value.coerceIn(-limit.y, limit.y)
                    state.offset = Offset(state.offset.x, y)
                    if (y != value) cancelAnimation()
                }
            }
        }
    }

    fun finishDismiss(translation: Offset, velocity: Offset) {
        state.isDismissing = true
        currentOnDismiss()
        if (reduceMotion) return
        val direction = if ((if (velocity.y == 0f) translation.y else velocity.y) >= 0f) 1f else -1f
        val travel = max(abs(translation.y) + 160f * density.density, min(state.bounds.height * 0.6f, abs(velocity.y) * 0.25f)) * direction
        val target = Offset(translation.x + velocity.x * 0.08f, travel)
        val fromScale = state.dismissScale
        state.run(scope) {
            animate(0f, 1f, animationSpec = U2Motion.out(U2Motion.Duration.state).spec()) { t, _ ->
                state.dismissTranslation = translation + (target - translation) * t
                state.dismissScale = fromScale + (0.86f - fromScale) * t
            }
        }
    }

    fun settleBack(translation: Offset, velocity: Offset) {
        currentOnDismissProgress(0f, true)
        state.run(scope) {
            val distance = hypot(translation.x, translation.y)
            val speed = if (distance > 0.5f) (translation.x * -velocity.x + translation.y * -velocity.y) / distance else 0f
            val relative = U2Motion.relativeVelocity(speed, distance)
            animate(0f, 1f, initialVelocity = relative, animationSpec = if (reduceMotion) tween(300) else U2Motion.spring()) { t, _ ->
                state.dismissTranslation = translation * (1f - t)
            }
        }
    }

    val percent = remember(locale) { NumberFormat.getPercentInstance(locale).apply { maximumFractionDigits = 0 } }
    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { size: IntSize -> state.bounds = Size(size.width.toFloat(), size.height.toFloat()) }
            .testTag(ZoomableImageTags.PHOTO)
            .semantics {
                this.contentDescription = "$contentDescription. $zoomHint"
                stateDescription = percent.format(state.scale.toDouble())
                progressBarRangeInfo = ProgressBarRangeInfo(state.scale, 1f..state.maxScale)
                setProgress { target ->
                    val scale = when {
                        target > state.scale -> min(state.maxScale, max(ZoomMath.DOUBLE_TAP_SCALE, state.scale * 1.6f))
                        else -> (state.scale / 1.6f).let { if (it < 1.2f) 1f else it }
                    }
                    state.stop()
                    state.scale = scale
                    state.offset = ZoomMath.clampOffset(state.offset, state.fitted, state.bounds, scale)
                    true
                }
                onClick {
                    currentOnSingleTap()
                    true
                }
            }
            .pointerInput(state) {
                detectTapGestures(
                    onDoubleTap = { position ->
                        if (state.isDismissing || currentImage == null) return@detectTapGestures
                        val focus = position - Offset(size.width / 2f, size.height / 2f)
                        if (state.isZoomed) {
                            animateTo(1f, Offset.Zero)
                        } else {
                            val target = min(state.maxScale, ZoomMath.DOUBLE_TAP_SCALE)
                            val offset = ZoomMath.clampOffset(
                                ZoomMath.centeredOffset(state.offset, focus, state.scale, target),
                                state.fitted, state.bounds, target,
                            )
                            animateTo(target, offset)
                        }
                    },
                    onTap = { currentOnSingleTap() },
                )
            }
            .pointerInput(state) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (state.isDismissing) return@awaitEachGesture
                    state.stop()
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    val center = Offset(size.width / 2f, size.height / 2f)
                    var mode = GestureMode.Undecided
                    var moved = Offset.Zero
                    var focus = Offset.Zero
                    var dismiss = state.dismissTranslation
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed == 0) break
                        event.changes.firstOrNull { it.id == down.id }?.let { tracker.addPosition(it.uptimeMillis, it.position) }
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        moved += pan
                        val centroid = event.calculateCentroid(useCurrent = true)
                        if (centroid.isSpecified()) focus = centroid - center
                        if (mode == GestureMode.Undecided) {
                            mode = when {
                                pressed > 1 && currentImage != null -> GestureMode.Transform
                                moved.getDistance() <= viewConfiguration.touchSlop -> GestureMode.Undecided
                                state.isZoomed -> GestureMode.Transform
                                pressed == 1 && currentOnNavigate != null && abs(moved.x) > abs(moved.y) * 1.2f -> GestureMode.Navigate
                                currentImage != null && abs(moved.y) > abs(moved.x) * 1.2f -> GestureMode.Dismiss
                                else -> GestureMode.Ignored
                            }
                        }
                        when (mode) {
                            GestureMode.Navigate -> {
                                if (pressed > 1) mode = GestureMode.Ignored
                                else {
                                    event.changes.forEach { it.consume() }
                                }
                            }
                            GestureMode.Transform -> {
                                state.transform(focus, zoom, pan)
                                event.changes.forEach { it.consume() }
                            }
                            GestureMode.Dismiss -> {
                                dismiss += pan
                                state.dismissTranslation = dismiss
                                val progress = MediaDismissGesture.progress(dismiss.y / density.density, state.bounds.height / density.density)
                                state.dismissScale = MediaDismissGesture.scale(progress)
                                currentOnDismissProgress(progress, false)
                                event.changes.forEach { it.consume() }
                            }
                            else -> Unit
                        }
                    }
                    val velocity = tracker.calculateVelocity().let { Offset(it.x, it.y) }
                    when (mode) {
                        GestureMode.Navigate -> {
                            if (abs(moved.x) / density.density >= 48f) currentOnNavigate?.invoke(moved.x < 0f)
                        }
                        GestureMode.Transform -> settle(velocity, focus)
                        GestureMode.Dismiss -> {
                            val dpTranslation = dismiss.y / density.density
                            val dpVelocity = velocity.y / density.density
                            if (MediaDismissGesture.shouldDismiss(dpTranslation, dpVelocity)) {
                                finishDismiss(dismiss, velocity)
                            } else {
                                settleBack(dismiss, velocity)
                            }
                        }
                        else -> Unit
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val fitted = state.fitted
        if (image != null && fitted.width > 0f) {
            val fittedDp = with(density) { DpSize(fitted.width.toDp(), fitted.height.toDp()) }
            Box(
                Modifier
                    .requiredSize(fittedDp)
                    .graphicsLayer {
                        val scale = state.scale * state.dismissScale
                        scaleX = scale
                        scaleY = scale
                        translationX = state.offset.x + state.dismissTranslation.x
                        translationY = state.offset.y + state.dismissTranslation.y
                    },
            ) {
                // The full photo replaces the thumbnail in place (same proportions): a cross-fade.
                Crossfade(image, animationSpec = U2Motion.resolve(U2Motion.micro, reduceMotion).spec(), label = "photo") { shown ->
                    Image(
                        bitmap = shown,
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize().testTag(ZoomableImageTags.IMAGE),
                    )
                }
            }
        }
    }
}

private fun Offset.isSpecified(): Boolean = x.isFinite() && y.isFinite()

private enum class GestureMode { Undecided, Transform, Dismiss, Navigate, Ignored }

/** Test tags of the zooming photo. */
object ZoomableImageTags {
    const val PHOTO = "mediaViewer.photo"

    /** The picture itself, once it is decoded. */
    const val IMAGE = "mediaViewer.image"
}
