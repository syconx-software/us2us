package com.syconx.us2us.ui.sheet

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics

/**
 * Draws sheets, fade covers and the floating sheet above the screens of one layer, in the order
 * they were presented: the app's content has one, and the security layer (the lock) has its own
 * above it. Nothing is a separate window, so the backdrop colours, FLAG_SECURE and screenshots
 * behave as on iOS.
 *
 * While anything is presented, the content below is hidden from accessibility services (the iOS
 * sheet traps VoiceOver focus); it stays in the semantics tree for tests.
 */
@Stable
class U2OverlayHost internal constructor() {
    internal val overlays = mutableStateListOf<OverlayEntry>()
    private var nextId = 0L

    /** The floating sheet of this layer (3.3's kind), presented from anywhere. */
    val floatingSheets = U2FloatingSheetPresenter()

    /** Something is presented. */
    val isPresenting: Boolean get() = overlays.isNotEmpty()

    internal fun newEntry(): OverlayEntry = OverlayEntry(nextId++)

    internal fun add(entry: OverlayEntry) {
        if (entry !in overlays) overlays += entry
    }

    internal fun remove(entry: OverlayEntry) {
        overlays.remove(entry)
    }
}

/** One presented overlay: its content and the composition locals of the place it was presented from. */
@Stable
internal class OverlayEntry(val id: Long) {
    var content: @Composable () -> Unit by mutableStateOf({})
    var locals: CompositionLocalContext? by mutableStateOf(null)
}

/** The overlay host of the current layer; `null` outside one (overlays then draw in place). */
val LocalOverlayHost = staticCompositionLocalOf<U2OverlayHost?> { null }

/**
 * A layer of the app with its own overlays: [content] below, every sheet and cover presented from
 * inside it above, in presentation order.
 */
@Composable
fun U2OverlayHost(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val host = remember { U2OverlayHost() }
    CompositionLocalProvider(LocalOverlayHost provides host, LocalFloatingSheetPresenter provides host.floatingSheets) {
        Box(modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().then(if (host.isPresenting) Modifier.semantics { hideFromAccessibility() } else Modifier)) {
                content()
            }
            FloatingSheetSlot(host.floatingSheets)
            for (entry in host.overlays) {
                key(entry.id) {
                    val locals = entry.locals
                    if (locals != null) CompositionLocalProvider(locals) { entry.content() } else entry.content()
                }
            }
        }
    }
}

/**
 * Shows [content] in the nearest [U2OverlayHost], above the layer's screens, with the composition
 * locals of this place (theme, environment, the feature's own). Without a host it draws in place.
 *
 * As with the iOS sheets, what the overlay shows should reach [content] live (from state the
 * content reads), not only as values captured here: the host recomposes the content when that
 * state changes, and picks up a new [content] lambda one frame later at most.
 */
@Composable
internal fun OverlayPortal(content: @Composable () -> Unit) {
    val host = LocalOverlayHost.current
    if (host == null) {
        content()
        return
    }
    val entry = remember(host) { host.newEntry() }
    entry.content = content
    entry.locals = currentCompositionLocalContext
    DisposableEffect(host, entry) {
        host.add(entry)
        onDispose { host.remove(entry) }
    }
}
