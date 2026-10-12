package com.syconx.us2us.ui.sheet

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * A C18 floating sheet (3.3's kind) over whatever is on screen right now: the conversation, a
 * viewer's full-screen cover, the file preview. It rises and leaves exactly like
 * `U2Sheet(style = Floating)` (it is one), follows the app's theme, and goes when the app leaves
 * the foreground (as a cancel). `onDismiss` runs once the sheet is gone, however it went.
 *
 * Presented from code (models, savers) through the presenter of the current layer
 * ([LocalFloatingSheetPresenter]).
 */
@Stable
class U2FloatingSheetPresenter internal constructor() {
    internal class Request(
        val id: Long,
        val content: @Composable ColumnScope.(close: () -> Unit) -> Unit,
        val onDismiss: () -> Unit,
    ) {
        var isPresented by mutableStateOf(true)
    }

    internal var current: Request? by mutableStateOf(null)
    private var nextId = 0L

    /** A sheet is up. */
    val isPresenting: Boolean get() = current != null

    /**
     * Presents [content] (given a `close` action) as a floating sheet. `false`, and [onDismiss] is
     * not called, when another such sheet is already up.
     */
    fun present(content: @Composable ColumnScope.(close: () -> Unit) -> Unit, onDismiss: () -> Unit): Boolean {
        if (current != null) return false
        current = Request(nextId++, content, onDismiss)
        return true
    }

    /** Closes the sheet that is up, as a cancel. */
    fun dismiss() {
        current?.isPresented = false
    }

    internal fun finished(request: Request) {
        if (current === request) current = null
        request.onDismiss()
    }
}

/** The floating-sheet presenter of the current layer; `null` outside a [U2OverlayHost]. */
val LocalFloatingSheetPresenter = staticCompositionLocalOf<U2FloatingSheetPresenter?> { null }

/** Draws the presenter's sheet; closes it when the app goes to the background. */
@Composable
internal fun FloatingSheetSlot(presenter: U2FloatingSheetPresenter) {
    val request = presenter.current ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, request) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) request.isPresented = false
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    key(request.id) {
        U2Sheet(
            isPresented = request.isPresented,
            onDismissRequest = { request.isPresented = false },
            style = U2SheetStyle.Floating,
            onDismissed = { presenter.finished(request) },
        ) {
            request.content(this) { request.isPresented = false }
        }
    }
}
