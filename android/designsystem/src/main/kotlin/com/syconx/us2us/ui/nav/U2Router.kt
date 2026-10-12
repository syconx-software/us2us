package com.syconx.us2us.ui.nav

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The navigation path of a [SoftNavigationStack] (iOS `U2Router`).
 *
 * The path is snapshot state: screens read [path], [top] and [isAtRoot] and recompose when they
 * change. Every change says whether it animates ([animatesChange]); the stack plays it with the
 * design's soft push (cross-fade + 16 dp drift, 0.5 s) or swaps in one frame.
 *
 * Call it on the main thread.
 */
@Stable
class U2Router<R : Any>(path: List<R> = emptyList()) {
    /** One screen on the path. [id] is unique for the router's lifetime. */
    @Immutable
    data class Entry<R : Any>(
        val id: Long,
        val route: R,
        /**
         * Pushed, but not shown yet: the screen is being built in place, transparent, for one frame
         * before its push animation starts.
         */
        val isStaged: Boolean = false,
        /** Replaced ([replaceTop]), still on screen until its successor, staged above it, starts moving in. Not on the path. */
        val isLeaving: Boolean = false,
    )

    private var nextId = 0L
    private val list = mutableStateListOf<Entry<R>>()

    /** Every entry, staged and leaving ones included, bottom first. */
    val entries: List<Entry<R>> get() = list

    /**
     * Whether the last change animates: pushes, pops and an animated [replaceTop] do; [setPath], a
     * replace without animation and the end of an edge swipe swap in one frame.
     */
    var animatesChange: Boolean by mutableStateOf(false)
        private set

    /**
     * Whether this stack is moving (a push or pop animating, the edge swipe under the finger),
     * published to its screens as [LocalLayerMotion].
     */
    val motion = U2LayerMotion()

    /** The routes above the root, bottom first (a staged route is on the path at once). */
    val path: List<R> get() = list.filter { !it.isLeaving }.map { it.route }

    /** The top route, `null` at the root. */
    val top: R? get() = list.lastOrNull { !it.isLeaving }?.route

    val isAtRoot: Boolean get() = list.none { !it.isLeaving }

    init {
        list.addAll(path.map { newEntry(it) })
    }

    /**
     * Pushes with the soft `screenPush` motion. Like UIKit, the incoming screen is built before it
     * moves: it is added (practically) transparent and untouchable, its first, heaviest frame is made
     * while the current screen stays as it is, and the cross-fade starts on the next frame. It is on
     * the path at once.
     */
    fun push(route: R) = change(animated = false) {
        list.add(newEntry(route).copy(isStaged = true))
    }

    fun pop() {
        if (list.isEmpty()) return
        change(animated = true) { list.removeAt(list.lastIndex) }
    }

    fun popToRoot() {
        if (list.isEmpty()) return
        change(animated = true) { list.clear() }
    }

    /** Pops back to the topmost [route], with every screen above it, in one soft pop. Nothing happens when [route] is not below the top. */
    fun pop(to: R) {
        val index = list.indexOfLast { it.route == to && !it.isLeaving }
        if (index < 0 || index >= list.lastIndex) return
        change(animated = true) { list.removeRange(index + 1, list.size) }
    }

    /**
     * Replaces the top screen. `animated = false` swaps in one frame, for a screen that has already
     * morphed into the one replacing it (4.4 “Kabul et” → 5.1).
     */
    fun replaceTop(route: R, animated: Boolean = true) {
        if (animated) {
            // The replaced screen leaves the path now, and the screen once its successor has been
            // built and starts moving in.
            change(animated = false) {
                if (list.isNotEmpty()) list[list.lastIndex] = list.last().copy(isLeaving = true)
                list.add(newEntry(route).copy(isStaged = true))
            }
        } else {
            change(animated = false) {
                if (list.isNotEmpty()) list.removeAt(list.lastIndex)
                list.add(newEntry(route))
            }
        }
    }

    /** Sets the whole path without animation (launch routes, the first unlock, leaving onboarding). */
    fun setPath(path: List<R>) = change(animated = false) {
        list.clear()
        list.addAll(path.map { newEntry(it) })
    }

    /**
     * Starts the push of every staged entry: called by the stack one frame after it composed them.
     * Leaving entries go at the same moment (their removal plays as a pop).
     */
    fun revealStaged() {
        if (list.none { it.isStaged || it.isLeaving }) return
        change(animated = true) {
            list.removeAll { it.isLeaving }
            for (index in list.indices) {
                if (list[index].isStaged) list[index] = list[index].copy(isStaged = false)
            }
        }
    }

    /** Removes the top entry without animation: the end of an edge swipe or a predictive back. */
    fun finishInteractivePop() {
        if (list.isEmpty()) return
        change(animated = false) { list.removeAt(list.lastIndex) }
    }

    private fun newEntry(route: R): Entry<R> = Entry(id = nextId++, route = route)

    private inline fun change(animated: Boolean, block: () -> Unit) {
        animatesChange = animated
        block()
    }
}

/**
 * Whether a layer is moving, as a chain: a screen is settled when its own stack (or pager) is not
 * moving and neither is any enclosing one. Continuous GPU work (the welcome scene) holds its last
 * frame until [isSettled], so a transition has every frame to itself.
 */
@Stable
class U2LayerMotion(parent: U2LayerMotion? = null) {
    /** The enclosing stack's or pager's motion. */
    var parent: U2LayerMotion? by mutableStateOf(parent)

    var isMoving: Boolean by mutableStateOf(false)
        private set

    /** Nothing this layer is on is moving. */
    val isSettled: Boolean get() = !isMoving && (parent?.isSettled ?: true)

    fun updateMoving(moving: Boolean) {
        if (moving != isMoving) isMoving = moving
    }
}
