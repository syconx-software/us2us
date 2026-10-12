package com.syconx.us2us.ui.nav

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.syconx.us2us.ui.motion.LocalReduceMotion
import com.syconx.us2us.ui.motion.U2Motion
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.hypot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The numbers of the soft stack (iOS `SoftNavigationStack`). */
object SoftNavigation {
    /** An edge swipe starts within this distance of the leading edge. */
    val EdgeWidth = 28.dp

    /** The finger moves this far before a drag counts (SwiftUI `DragGesture(minimumDistance: 12)`). */
    val MinimumDistance = 12.dp

    /** The pushed screen's drift while it fades (`screenPush`). */
    val PushDrift = 16.dp

    /** How far the top screen follows the finger at full progress. */
    val SwipeTravel = 64.dp

    /** Released past this progress, the swipe pops. */
    const val COMMIT_PROGRESS = 0.35f

    /** Released with this projected progress, the swipe pops too (a fling). */
    const val COMMIT_PROJECTED = 0.6f

    /**
     * A staged screen is drawn at 0.1 %: invisible (under half a level of 255 on any pixel) but
     * rendered, so its first frame is made before its push starts.
     */
    const val STAGED_ALPHA = 0.001f

    /** UIKit's normal deceleration rate (per millisecond), which SwiftUI projects a release with. */
    private const val DECELERATION_RATE = 0.998f

    /** Where a drag released at [translation] with [velocity] (per second) would come to rest. */
    fun projectedEnd(translation: Float, velocity: Float): Float =
        translation + velocity / 1000f * DECELERATION_RATE / (1f - DECELERATION_RATE)

    /** Whether a swipe released at [progress] with [projected] progress pops. */
    fun commits(progress: Float, projected: Float): Boolean = progress > COMMIT_PROGRESS || projected > COMMIT_PROJECTED
}

/**
 * A navigation stack with the design's *soft* push: screens cross-fade with a 16 dp horizontal
 * drift on the design curve (0.5 s) instead of the system slide, and the edge swipe back follows the
 * finger and settles on the critically damped spring. Under reduce motion pushes become 0.3 s
 * cross-fades. System back and Android's predictive back gesture pop with the same progress.
 *
 * Every screen on the path stays composed (hidden below the top, each with its own saveable
 * state), so state and scroll positions survive, also while the lock covers the app. Covered
 * screens are transparent, take no input and are left out of the semantics tree. The design draws
 * its own headers; there is no navigation bar.
 */
@Composable
fun <R : Any> SoftNavigationStack(
    router: U2Router<R>,
    modifier: Modifier = Modifier,
    allowsSwipeBack: (R) -> Boolean = { true },
    root: @Composable () -> Unit,
    destination: @Composable (R) -> Unit,
) {
    val reduceMotion = LocalReduceMotion.current
    val isStackVisible = LocalIsLayerVisible.current
    val enclosingMotion = LocalLayerMotion.current
    val holder = rememberSaveableStateHolder()
    val scope = rememberCoroutineScope()
    val state = remember(router) { StackState(router) }
    SideEffect { router.motion.parent = enclosingMotion }

    // Entries removed with an animation keep playing their exit above the stack.
    val entries = router.entries
    state.version // re-read when an exit finishes
    state.sync(entries, router.animatesChange)
    SideEffect { state.dropRemovedStates(holder) }

    // Stage → reveal: the staged screen is composed (transparent) this frame; its push starts on the next.
    val stagedIds = entries.filter { it.isStaged }.map { it.id }
    if (stagedIds.isNotEmpty()) {
        LaunchedEffect(stagedIds) {
            withFrameNanos { }
            router.revealStaged()
        }
    }
    SideEffect { router.motion.updateMoving(state.running > 0 || state.isSwiping || stagedIds.isNotEmpty()) }

    val top = router.top
    val canSwipe = top != null && allowsSwipeBack(top) && isStackVisible
    val currentCanSwipe by rememberUpdatedState(canSwipe)

    var widthPx by remember { mutableFloatStateOf(1f) }
    Box(modifier.fillMaxSize().onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }) {
        PredictiveBackHandler(enabled = canSwipe) { events ->
            var started = false
            try {
                events.collect { event ->
                    if (!started) {
                        started = true
                        state.beginSwipe()
                    }
                    state.swipe.snapTo(event.progress.coerceIn(0f, 1f))
                }
                if (started) {
                    state.settle(scope, target = 1f, velocity = 0f, reduceMotion = reduceMotion)
                } else {
                    router.pop()
                }
            } catch (cancelled: CancellationException) {
                if (started) state.settle(scope, target = 0f, velocity = 0f, reduceMotion = reduceMotion)
                throw cancelled
            }
        }

        val count = entries.size - (if (entries.lastOrNull()?.isStaged == true) 1 else 0)
        Box(
            Modifier
                .fillMaxSize()
                .edgeSwipe(state, scope, widthPx, reduceMotion) { currentCanSwipe },
        ) {
            Layer(LayerKey.ROOT, role(0, count), 0, state, router, isStackVisible, reduceMotion, holder, onExited = {}) { root() }
            // One loop for the entries and the leaving ones, so a popped screen keeps its state (keyed by id) while it fades out.
            val layers = entries.mapIndexed { index, entry -> entry to role(index + 1, count) } +
                state.exiting.map { it to LayerRole.Exiting }
            layers.forEachIndexed { index, (entry, layerRole) ->
                key(entry.id) {
                    Layer(
                        LayerKey.of(entry.id), layerRole, index + 1, state, router, isStackVisible, reduceMotion, holder,
                        onExited = { state.finishExit(entry.id) },
                    ) {
                        destination(entry.route)
                    }
                }
            }
        }
    }
}

private object LayerKey {
    const val ROOT = "root"

    fun of(id: Long): String = "entry-$id"
}

/** Where a layer is on the stack. */
private enum class LayerRole(val target: Float) {
    Top(0f),
    BelowTop(-1f),
    Covered(-1f),
    Staged(1f),
    Exiting(1f),
}

private fun role(level: Int, count: Int): LayerRole = when {
    level == count -> LayerRole.Top
    level == count - 1 -> LayerRole.BelowTop
    level > count -> LayerRole.Staged
    else -> LayerRole.Covered
}

@Composable
private fun <R : Any> Layer(
    key: String,
    role: LayerRole,
    z: Int,
    state: StackState<R>,
    router: U2Router<R>,
    isStackVisible: Boolean,
    reduceMotion: Boolean,
    holder: SaveableStateHolder,
    onExited: () -> Unit,
    content: @Composable () -> Unit,
) {
    // The layer's place as one number: 0 on top, −1 covered (16 dp to the left), +1 staged or leaving (16 dp to the right).
    val position = remember { Animatable(role.target) }
    val target = role.target
    val currentOnExited by rememberUpdatedState(onExited)
    LaunchedEffect(target) {
        if (position.value != target) {
            // A covered screen that leaves is already invisible: it goes at once.
            val fromCovered = role == LayerRole.Exiting && position.value < 0f
            if (!fromCovered && (router.animatesChange || role == LayerRole.Exiting)) {
                state.running++
                try {
                    position.animateTo(target, U2Motion.resolve(U2Motion.screen, reduceMotion).spec())
                } finally {
                    state.running--
                }
            } else {
                position.snapTo(target)
            }
        }
        if (role == LayerRole.Exiting) currentOnExited()
    }

    val isTop = role == LayerRole.Top
    val swiping = state.isSwiping
    val isVisible = isStackVisible && (isTop || role == LayerRole.Staged || (role == LayerRole.BelowTop && swiping))
    val pushDrift = with(LocalDensity.current) { SoftNavigation.PushDrift.toPx() }
    val swipeTravel = with(LocalDensity.current) { SoftNavigation.SwipeTravel.toPx() }

    Box(
        Modifier
            .zIndex(z.toFloat())
            .fillMaxSize()
            .graphicsLayer {
                val progress = if (swiping) state.swipe.value else 0f
                val p = position.value
                val (alpha, offset) = when {
                    swiping && isTop -> (1f - progress) to progress * swipeTravel
                    swiping && role == LayerRole.BelowTop -> progress to -pushDrift * (1f - progress)
                    role == LayerRole.Staged -> SoftNavigation.STAGED_ALPHA to pushDrift
                    else -> (1f - abs(p)).coerceIn(0f, 1f) to p * pushDrift
                }
                this.alpha = alpha
                translationX = if (reduceMotion) 0f else offset
            }
            .then(if (isTop && !swiping) Modifier else Modifier.blockPointerInput())
            .then(if (isTop) Modifier else Modifier.clearAndSetSemantics { }.focusProperties { canFocus = false }.focusGroup()),
    ) {
        CompositionLocalProvider(LocalIsLayerVisible provides isVisible, LocalLayerMotion provides router.motion) {
            holder.SaveableStateProvider(key, content)
        }
    }
}

/** Takes every pointer event before the layer's content sees it: a covered or moving screen is untouchable. */
private fun Modifier.blockPointerInput(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
}

/**
 * The edge swipe back: a mostly horizontal drag that started at the leading edge. The top screen
 * follows the finger; on release it settles at the finger's own speed (critically damped: no
 * stall, no overshoot) towards the previous screen or back into place.
 */
private fun <R : Any> Modifier.edgeSwipe(
    state: StackState<R>,
    scope: CoroutineScope,
    widthPx: Float,
    reduceMotion: Boolean,
    canSwipe: () -> Boolean,
): Modifier = pointerInput(widthPx, reduceMotion) {
    val edge = SoftNavigation.EdgeWidth.toPx()
    val minimum = SoftNavigation.MinimumDistance.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (!canSwipe() || down.position.x >= edge || state.isSwiping) return@awaitEachGesture
        val tracker = VelocityTracker()
        tracker.addPointerInputChange(down)
        var started = false
        var settled = false
        while (true) {
            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
            tracker.addPointerInputChange(change)
            val dx = change.position.x - down.position.x
            val dy = change.position.y - down.position.y
            if (!started && change.pressed && hypot(dx, dy) >= minimum && abs(dx) > abs(dy)) {
                started = true
                state.beginSwipe()
            }
            if (started) {
                change.consume()
                val progress = (dx / widthPx).coerceIn(0f, 1f)
                scope.launch { state.swipe.snapTo(progress) }
            }
            if (!change.pressed) {
                if (started) {
                    val velocity = tracker.calculateVelocity().x
                    val projected = SoftNavigation.projectedEnd(dx, velocity) / widthPx
                    val progress = (dx / widthPx).coerceIn(0f, 1f)
                    val target = if (SoftNavigation.commits(progress, projected)) 1f else 0f
                    state.settle(scope, target, velocity / widthPx, reduceMotion)
                    settled = true
                }
                break
            }
        }
        // The pointer was cancelled under the swipe: back into place.
        if (started && !settled) state.settle(scope, 0f, 0f, reduceMotion)
    }
}

/** The stack's own state: exiting layers, the swipe, and how many layer animations are running. */
@Stable
private class StackState<R : Any>(private val router: U2Router<R>) {
    /** Entries removed with an animation, still fading out. */
    val exiting = mutableListOf<U2Router.Entry<R>>()

    /** Bumped when an exit finishes, so the stack recomposes without it. */
    var version by mutableIntStateOf(0)

    /** Layer animations running (push, pop). */
    var running by mutableIntStateOf(0)

    /** 0 … 1: how far the top screen has been swiped (or predictive-back dragged) away. */
    val swipe = Animatable(0f, visibilityThreshold = 0.0005f)

    /** A swipe is under the finger or settling. */
    var isSwiping by mutableStateOf(false)
        private set

    var isSettling = false
        private set

    private var previous: List<U2Router.Entry<R>> = emptyList()
    private val removedKeys = mutableListOf<String>()

    /** Compares [entries] with the last composed ones: removed entries exit (animated) or go at once. */
    fun sync(entries: List<U2Router.Entry<R>>, animated: Boolean) {
        val current = entries.toList()
        if (current == previous) return
        val ids = entries.mapTo(HashSet()) { it.id }
        for (old in previous) {
            if (old.id in ids || exiting.any { it.id == old.id }) continue
            if (animated) exiting += old else removedKeys += LayerKey.of(old.id)
        }
        previous = current
    }

    fun dropRemovedStates(holder: SaveableStateHolder) {
        removedKeys.forEach(holder::removeState)
        removedKeys.clear()
    }

    fun finishExit(id: Long) {
        if (exiting.removeAll { it.id == id }) {
            removedKeys += LayerKey.of(id)
            version++
        }
    }

    fun beginSwipe() {
        isSwiping = true
    }

    fun endSwipe() {
        isSwiping = false
        isSettling = false
    }

    /** Settles the swipe at [target] (1 pops) from the finger's [velocity] (progress per second). */
    fun settle(scope: CoroutineScope, target: Float, velocity: Float, reduceMotion: Boolean) {
        isSettling = true
        scope.launch {
            val remaining = target - swipe.value
            val spec = U2Motion.resolve(U2Motion.spring(visibilityThreshold = 0.0005f), reduceMotion)
            try {
                swipe.animateTo(target, spec, initialVelocity = if (reduceMotion) 0f else U2Motion.settleVelocity(velocity, remaining))
            } finally {
                withContext(NonCancellable) {
                    if (target == 1f) {
                        router.finishInteractivePop()
                        swipe.snapTo(0f)
                    }
                    endSwipe()
                }
            }
        }
    }
}
