package com.syconx.us2us.ui.sheet

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.offset
import com.syconx.us2us.ui.env.LocalU2SafeArea
import com.syconx.us2us.ui.env.rememberU2Keyboard
import com.syconx.us2us.ui.env.windowSafeArea
import com.syconx.us2us.ui.motion.LocalReduceMotion
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.nav.LocalIsLayerVisible
import com.syconx.us2us.ui.nav.SoftNavigation
import com.syconx.us2us.ui.theme.U2BoxShadows
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.theme.u2BoxShadow
import com.syconx.us2us.ui.tokens.U2FixedColors
import com.syconx.us2us.ui.tokens.U2Opacity
import com.syconx.us2us.ui.tokens.U2Radius
import com.syconx.us2us.ui.tokens.U2Size
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlinx.coroutines.launch

/** C18 sheet styles. */
enum class U2SheetStyle {
    /** 4.2, 5.4: starts at design y 58 (4 dp under the status bar), top radius 38. */
    FullHeight,

    /** 5.5: hugs its content, top radius 38; the content pads its own bottom (`u2Bottom(40)`). */
    Content,

    /** 3.3: floating, inset 8 on the sides and bottom, radius 42, shadow `0 -12 40 #000·35%`, over the dimmed screen. */
    Floating,
}

/** Test tags of the sheet's parts (the layout JSON's `r.1` / `r.1.0` in 4.2, 5.4 and 5.5). */
object U2SheetTags {
    const val SHEET = "u2.sheet"
    const val GRABBER = "u2.sheet.grabber"
    const val BACKDROP = "u2.sheet.backdrop"
}

/**
 * Inside a [U2Sheet]: `false` while the sheet is still rising (or going down). Content that raises
 * the keyboard waits for it (4.2). Outside sheets: `true`.
 */
val LocalSheetSettled = compositionLocalOf { true }

/**
 * Presents a C18 sheet while [isPresented]: an opaque backdrop (`mix(bg 55%, #05060c)`, or the 3.3
 * dim) fades in over 0.55 s while the sheet rises on the critically damped spring; it follows the
 * finger down and asks to be dismissed ([onDismissRequest]) past a third of its height or when
 * flung. Back, and a tap on the backdrop of the content and floating styles, ask too. When
 * [isPresented] turns `false` the sheet goes down (0.55 s, or on at the finger's speed after a
 * fling) and [onDismissed] runs once it is gone. Under reduce motion both simply cross-fade.
 *
 * Drawn by the nearest [U2OverlayHost] above everything of its layer (headers, the composer).
 */
@Composable
fun U2Sheet(
    isPresented: Boolean,
    onDismissRequest: () -> Unit,
    style: U2SheetStyle = U2SheetStyle.FullHeight,
    isDismissible: Boolean = true,
    onDismissed: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var isHosted by remember { mutableStateOf(false) }
    SideEffect { if (isPresented) isHosted = true }
    if (!isPresented && !isHosted) return
    val currentOnDismissed by rememberUpdatedState(onDismissed)
    OverlayPortal {
        SheetContainer(
            isPresented = isPresented,
            onDismissRequest = onDismissRequest,
            style = style,
            isDismissible = isDismissible,
            finish = {
                isHosted = false
                currentOnDismissed?.invoke()
            },
            content = content,
        )
    }
}

/**
 * Item-based [U2Sheet]: shown while [item] is set; it keeps showing the item it was opened with
 * while it goes down.
 */
@Composable
fun <T : Any> U2Sheet(
    item: T?,
    onDismissRequest: () -> Unit,
    style: U2SheetStyle = U2SheetStyle.FullHeight,
    isDismissible: Boolean = true,
    onDismissed: (() -> Unit)? = null,
    content: @Composable ColumnScope.(T) -> Unit,
) {
    var shown by remember { mutableStateOf(item) }
    SideEffect { if (item != null) shown = item }
    val current = item ?: shown
    U2Sheet(
        isPresented = item != null,
        onDismissRequest = onDismissRequest,
        style = style,
        isDismissible = isDismissible,
        onDismissed = {
            shown = null
            onDismissed?.invoke()
        },
    ) {
        if (current != null) content(current)
    }
}

/** The grabber: 38 × 5, r3, `text·25 %`, centred. */
@Composable
fun SheetGrabber(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(U2Size.grabberWidth, U2Size.grabberHeight)
            .background(U2Theme.colors.grabber, RoundedCornerShape(50)),
    )
}

@Composable
private fun SheetContainer(
    isPresented: Boolean,
    onDismissRequest: () -> Unit,
    style: U2SheetStyle,
    isDismissible: Boolean,
    finish: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val reduceMotion = LocalReduceMotion.current
    val density = LocalDensity.current
    val keyboard = rememberU2Keyboard()
    val scope = rememberCoroutineScope()
    val currentRequest by rememberUpdatedState(onDismissRequest)
    val currentFinish by rememberUpdatedState(finish)
    val currentPresented by rememberUpdatedState(isPresented)

    // 0 = parked just off screen, 1 = in place.
    val travel = remember { Animatable(0f, visibilityThreshold = 0.0005f) }
    val backdrop = remember { Animatable(0f, visibilityThreshold = 0.001f) }
    val drag = remember { Animatable(0f) } // px, + downwards
    var sheetHeight by remember { mutableStateOf<Int?>(null) }
    var containerHeight by remember { mutableStateOf<Int?>(null) }
    var flingMargin by remember { mutableFloatStateOf(0f) }
    var releaseVelocity by remember { mutableStateOf<Float?>(null) }
    var isSettled by remember { mutableStateOf(false) }
    var hasRisen by remember { mutableStateOf(false) }

    val floatingInset = with(density) { (if (style == U2SheetStyle.Floating) 8.dp else 0.dp).toPx() }
    // How far the sheet moves to be just off screen: its height plus the floating inset (+1), so the
    // whole curve plays on screen. Before the first measurement: the container's height.
    fun hiddenOffset(): Float {
        val height = sheetHeight ?: return (containerHeight ?: 3000).toFloat()
        return height + floatingInset + with(density) { 1.dp.toPx() } + flingMargin
    }

    // The rise starts one frame after the sheet appears: its first frame (building the content,
    // measuring the sheet) is spent parked off screen behind a clear backdrop, and the spring then
    // plays from rest with every frame.
    LaunchedEffect(Unit) {
        withFrameNanos { }
        while (sheetHeight == null) withFrameNanos { }
        if (!currentPresented) return@LaunchedEffect
        hasRisen = true
        launch { backdrop.animateTo(1f, U2Motion.resolve(U2Motion.spring<Float>(), reduceMotion)) }
        travel.animateTo(1f, U2Motion.resolve(U2Motion.spring(visibilityThreshold = 0.0005f), reduceMotion))
        isSettled = true
    }

    // “Dismiss reverses”: down on the design curve over the sheet duration (0.55 s), from rest, while
    // the backdrop fades over 0.36 s; let go mid-fling, on at the finger's speed instead.
    LaunchedEffect(isPresented) {
        if (isPresented) return@LaunchedEffect
        isSettled = false
        keyboard.dismiss()
        val velocity = releaseVelocity
        releaseVelocity = null
        launch { backdrop.animateTo(0f, U2Motion.resolve(U2Motion.state, reduceMotion).spec()) }
        if (velocity != null && !reduceMotion && hasRisen) {
            flingMargin = with(density) { 48.dp.toPx() }
            val total = hiddenOffset()
            val remaining = total - drag.value.coerceAtLeast(0f)
            // In travel units: 1 = the whole way; moving towards 0.
            val relative = U2Motion.relativeVelocity(velocity, remaining)
            val spanTravel = if (total > 0f) remaining / total else 1f
            travel.animateTo(0f, U2Motion.spring(visibilityThreshold = 0.0005f), initialVelocity = -relative * spanTravel)
        } else {
            withFrameNanos { }
            travel.animateTo(0f, U2Motion.resolve(U2Motion.soft(U2Motion.Duration.sheet), reduceMotion).spec())
        }
        drag.snapTo(0f)
        flingMargin = 0f
        currentFinish()
    }

    // Back belongs to the sheet while it is up, unless its layer is covered (the lock over the app).
    BackHandler(enabled = isPresented && LocalIsLayerVisible.current) { if (isDismissible) currentRequest() }

    val safeArea = windowSafeArea()
    CompositionLocalProvider(LocalU2SafeArea provides safeArea) {
        Box(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.ime)
                .onSizeChanged { containerHeight = it.height },
        ) {
            val backdropColor = when (style) {
                U2SheetStyle.FullHeight, U2SheetStyle.Content -> U2Theme.colors.sheetBackdrop
                // 3.3: the content at 30 % (= 70 % bg over it) under #06070e·55 %.
                U2SheetStyle.Floating -> U2FixedColors.lockOverlay.compositeOver(U2Theme.colors.bg.copy(alpha = 1f - U2Opacity.lockBehindSheet))
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag(U2SheetTags.BACKDROP)
                    .graphicsLayer {
                        val height = (sheetHeight ?: 600).coerceAtLeast(1)
                        alpha = backdrop.value * (1f - drag.value / height * 0.6f).coerceIn(0f, 1f)
                    }
                    .background(backdropColor)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        if (isDismissible && style != U2SheetStyle.FullHeight && isPresented) currentRequest()
                    },
            )
            val shape = sheetShape(style)
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .then(placement(style))
                    .offset {
                        val y = if (reduceMotion) 0f else {
                            val shownY = drag.value.coerceAtLeast(0f)
                            hiddenOffset() + (shownY - hiddenOffset()) * travel.value
                        }
                        IntOffset(0, y.toInt())
                    }
                    .graphicsLayer { alpha = if (reduceMotion) travel.value else 1f }
                    .onSizeChanged { sheetHeight = it.height }
                    .then(if (style == U2SheetStyle.Floating) Modifier.u2BoxShadow(shape, U2BoxShadows.sheetFloating) else Modifier)
                    .clip(shape)
                    .background(U2Theme.colors.surface)
                    .testTag(U2SheetTags.SHEET)
                    .semantics { isTraversalGroup = true }
                    .sheetDrag(
                        enabled = isDismissible && isPresented,
                        onDrag = { dy ->
                            // Follow the finger downwards, rubber-band upwards.
                            val y = if (dy > 0f) dy else -sqrt(-dy / density.density) * 2f * density.density
                            scope.launch { drag.snapTo(y) }
                            if (dy > 12f * density.density) keyboard.dismiss()
                        },
                        onRelease = { dy, velocity ->
                            val height = (sheetHeight ?: 600).toFloat()
                            val projected = SoftNavigation.projectedEnd(dy, velocity)
                            if (dy > height / 3f || projected > height * 0.6f) {
                                releaseVelocity = velocity
                                currentRequest()
                                // Refused (the presenter keeps the sheet): back into place.
                                scope.launch {
                                    withFrameNanos { }
                                    withFrameNanos { }
                                    if (currentPresented) {
                                        releaseVelocity = null
                                        drag.animateTo(0f, U2Motion.resolve(U2Motion.spring<Float>(), reduceMotion))
                                    }
                                }
                            } else {
                                val offset = drag.value.coerceAtLeast(0f)
                                scope.launch {
                                    drag.animateTo(
                                        0f,
                                        U2Motion.resolve(U2Motion.spring<Float>(), reduceMotion),
                                        initialVelocity = if (reduceMotion) 0f else U2Motion.settleVelocity(velocity, -offset),
                                    )
                                }
                            }
                        },
                    ),
            ) {
                SheetGrabber(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = if (style == U2SheetStyle.Floating) 10.dp else 8.dp)
                        .testTag(U2SheetTags.GRABBER),
                )
                CompositionLocalProvider(LocalSheetSettled provides isSettled) {
                    if (style == U2SheetStyle.FullHeight) {
                        Column(Modifier.weight(1f, fill = false).fillMaxWidth(), content = content)
                    } else {
                        content()
                    }
                }
            }
        }
    }
}

/** Places the sheet: full height from design y 58; content and floating at the bottom. */
@Composable
private fun placement(style: U2SheetStyle): Modifier = when (style) {
    U2SheetStyle.FullHeight -> Modifier
        .padding(top = LocalU2SafeArea.current.top(58.dp).coerceAtLeast(0.dp))
        .fillMaxHeight()
        .fillMaxWidth()
    U2SheetStyle.Content -> Modifier.fillMaxWidth()
    U2SheetStyle.Floating -> Modifier
        .padding(horizontal = 8.dp)
        .padding(bottom = 8.dp)
        .fillMaxWidth()
}

/** Top-rounded (and, floating, bottom-rounded) sheet outline. */
private fun sheetShape(style: U2SheetStyle): Shape {
    val top: Dp = if (style == U2SheetStyle.Floating) U2Radius.sheetFloating else U2Radius.sheet
    val bottom: Dp = if (style == U2SheetStyle.Floating) U2Radius.sheetFloating else 0.dp
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

/**
 * The sheet's own drag: a vertical drag of at least 8 dp that the content did not take (a scrolling
 * list keeps its drags, as on iOS). Reports the translation as it moves and the velocity on release.
 */
private fun Modifier.sheetDrag(
    enabled: Boolean,
    onDrag: (dy: Float) -> Unit,
    onRelease: (dy: Float, velocity: Float) -> Unit,
): Modifier = if (!enabled) this else pointerInput(Unit) {
    val minimum = 8.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val tracker = VelocityTracker()
        tracker.addPointerInputChange(down)
        var dragging = false
        while (true) {
            val change = awaitPointerEvent(PointerEventPass.Main).changes.firstOrNull { it.id == down.id } ?: break
            tracker.addPointerInputChange(change)
            val dx = change.position.x - down.position.x
            val dy = change.position.y - down.position.y
            if (!dragging) {
                if (change.isConsumed) break
                if (change.pressed && hypot(dx, dy) >= minimum && abs(dy) >= abs(dx)) dragging = true
            }
            if (dragging) {
                change.consume()
                onDrag(dy)
            }
            if (!change.pressed) {
                if (dragging) onRelease(dy, tracker.calculateVelocity().y)
                break
            }
        }
    }
}
