package com.syconx.us2us.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import com.syconx.us2us.app.debug.DebugScreens
import com.syconx.us2us.domain.nav.Overlay
import com.syconx.us2us.domain.nav.Phase
import com.syconx.us2us.feature.chats.ChatsScreen
import com.syconx.us2us.feature.lock.LockFlow
import com.syconx.us2us.feature.onboarding.OnboardingFlow
import com.syconx.us2us.ui.env.LocalOwnAvatar
import com.syconx.us2us.ui.env.LocalSystemBars
import com.syconx.us2us.ui.env.ProvideU2Environment
import com.syconx.us2us.ui.env.ScreenAwakeScope
import com.syconx.us2us.ui.env.U2Haptics
import com.syconx.us2us.ui.env.U2SystemBars
import com.syconx.us2us.ui.env.rememberViewHaptics
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.motion.U2Transitions
import com.syconx.us2us.ui.motion.rememberSystemReduceMotion
import com.syconx.us2us.ui.motion.u2TransitionBlur
import com.syconx.us2us.ui.nav.LocalIsLayerVisible
import com.syconx.us2us.ui.nav.SoftNavigationStack
import com.syconx.us2us.ui.security.PrivacyCover
import com.syconx.us2us.ui.sheet.U2OverlayHost
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.theme.isDark
import kotlinx.coroutines.launch

/** Test tags of the root's layers. */
object RootTags {
    const val MAIN_STACK = "root.main"
    const val ONBOARDING = "root.onboarding"
    const val SECURITY = "root.security"
    const val PRIVACY_COVER = "root.security.cover"
    const val LOCK = "root.security.lock"
}

/**
 * The whole app in one composable: the theme from the saved preference, the environment every
 * screen reads, the root phases with their overlays, and the security layer above everything.
 * [onSystemBarsDark] is told whether the system bars sit on dark (light icons) or light content.
 */
@Composable
fun AppRoot(
    model: AppModel,
    services: ServiceLocator? = null,
    haptics: U2Haptics = rememberViewHaptics(),
    onSystemBarsDark: (Boolean) -> Unit = {},
) {
    // 7.1 › Tema cross-fades the whole window (iOS switches inside `withU2Animation(U2Motion.screen)`):
    // the old theme's picture is taken while it is still on screen, then fades out over the new one.
    val view = LocalView.current
    val requested = model.preferences.theme
    var shownTheme by remember { mutableStateOf(requested) }
    var oldTheme by remember { mutableStateOf<ImageBitmap?>(null) }
    val oldThemeAlpha = remember { Animatable(0f) }
    val reduceMotion = rememberSystemReduceMotion()
    LaunchedEffect(requested) {
        if (requested == shownTheme) return@LaunchedEffect
        oldTheme = ThemeSnapshot.capture(view)
        oldThemeAlpha.snapTo(1f)
        shownTheme = requested
        oldThemeAlpha.animateTo(0f, U2Motion.resolve(U2Motion.screen, reduceMotion).spec())
        oldTheme = null
    }
    U2Theme(preference = shownTheme, reduceMotion = reduceMotion) {
        ScreenAwakeScope(
            preference = model.preferences.keepScreenOn,
            contentVisible = model.phase == Phase.Unlocked && model.overlay == Overlay.None,
        ) {
            val systemBars = services?.systemBars ?: remember { U2SystemBars() }
            ProvideU2Environment(
                appModel = model,
                designInsets = model.configuration.designInsets,
                haptics = haptics,
                harnessState = model.harnessState,
                guard = model.guard,
                systemBars = systemBars,
                screens = AppFeatureScreens,
            ) {
                val dark = U2Theme.isDark || LocalSystemBars.current.forcesDark
                LaunchedEffect(dark) { onSystemBarsDark(dark) }
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(U2Theme.colors.bg),
                ) {
                    // The harness's preparation runs before its state's first frame: only the background until then.
                    if (model.isReady) {
                        DebugScreens.Provide(model.configuration) {
                            U2OverlayHost {
                                RootView(model)
                                DebugScreens.Gallery(model.configuration)
                            }
                            SecurityLayer(model)
                        }
                    }
                    oldTheme?.let { picture ->
                        Image(
                            picture,
                            contentDescription = null,
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { alpha = oldThemeAlpha.value }
                                .clearAndSetSemantics {},
                        )
                    }
                }
            }
        }
    }
}

/**
 * The main window's content for each root phase. Root swaps cross-fade (0.5 s, the design curve).
 * Leaving onboarding, 4.1 is drawn at once *under* 2.3, which dissolves over it (one continuous
 * cross-fade); onboarding stays composed until it has faded out completely.
 */
@Composable
fun RootView(model: AppModel) {
    val phase = model.phase
    val animates = model.phaseAnimates
    Box(Modifier.fillMaxSize()) {
        // Nothing is built while locked before the first unlock (the vault is closed).
        val mainFade = U2Transitions.fade(U2Motion.screen)
        AnimatedVisibility(
            visible = phase == Phase.Unlocked,
            enter = if (animates && !model.isLeavingOnboarding) mainFade.enter else EnterTransition.None,
            exit = if (animates) mainFade.exit else ExitTransition.None,
        ) {
            DisposableEffect(Unit) { onDispose { model.mainStackRemoved() } }
            // The fade's slight blur only when the swap animates (otherwise it would hold the stack for its duration).
            val blur = if (animates) u2TransitionBlur(mainFade) else Modifier
            Box(Modifier.fillMaxSize().then(blur).testTag(RootTags.MAIN_STACK)) {
                // Under the lock or the privacy cover nothing of the stack can be seen: its continuous work pauses.
                val ownAvatar by model.messenger.ownAvatar.collectAsState()
                CompositionLocalProvider(
                    LocalIsLayerVisible provides (model.overlay == Overlay.None),
                    LocalOwnAvatar provides ownAvatar.picture,
                ) {
                    MainStack(model)
                }
            }
            if (model.unlockDissolvePending) {
                LaunchedEffect(Unit) {
                    // The content's first (heaviest) frame, then the next: then the lock dissolves.
                    withFrameNanos { }
                    withFrameNanos { }
                    model.unlockContentShown()
                }
            }
        }

        val onboardingFade = U2Transitions.fade(U2Motion.screen)
        val inOnboarding = phase == Phase.Onboarding
        AnimatedVisibility(
            visible = inOnboarding || model.isLeavingOnboarding,
            enter = if (animates) onboardingFade.enter else EnterTransition.None,
            exit = ExitTransition.None,
            modifier = Modifier.zIndex(1f),
        ) {
            val alpha = remember { Animatable(1f) }
            LaunchedEffect(inOnboarding) {
                val target = if (inOnboarding) 1f else 0f
                if (alpha.value != target) {
                    if (animates) alpha.animateTo(target, U2Motion.screen.spec()) else alpha.snapTo(target)
                }
                if (!inOnboarding) model.onboardingFadedOut()
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .then(if (animates && inOnboarding) u2TransitionBlur(onboardingFade) else Modifier)
                    .graphicsLayer { this.alpha = alpha.value }
                    .testTag(RootTags.ONBOARDING)
                    .then(
                        if (inOnboarding) {
                            Modifier
                        } else {
                            Modifier
                                .semantics { hideFromAccessibility() }
                                .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } }
                        },
                    ),
            ) {
                OnboardingFlow(entry = model.onboardingEntry, onFinished = model::didFinishOnboarding)
            }
        }
    }
}

/** 04–07: the main stack rooted at 4.1. */
@Composable
fun MainStack(model: AppModel) {
    SoftNavigationStack(
        router = model.router,
        allowsSwipeBack = { it.allowsSwipeBack },
        root = { ChatsScreen() },
        destination = { route -> RouteHost(route, onBack = model.router::pop) },
    )
}

/**
 * The security layer above everything (sheets included): nothing, the 3.4 privacy cover, or the
 * lock flow over the cover's base. Appearing is instant (it must be in place before the system's
 * picture); leaving dissolves softly (0.36 s). Coming back from the background, the cover gives way
 * to the lock with a 0.36 s cross-fade over an opaque base, so nothing of the app shows through.
 */
@Composable
fun SecurityLayer(model: AppModel) {
    val overlay = model.overlay
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val covers = overlay != Overlay.None
    LaunchedEffect(covers) {
        // No keyboard (or focused field) stays above the cover or the lock, nor comes back with the app.
        if (covers) {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
    }
    // The overlay on screen: it stays while the layer fades out.
    var shown by remember { mutableStateOf(overlay) }
    SideEffect { if (overlay != Overlay.None) shown = overlay }
    val displayed = if (overlay != Overlay.None) overlay else shown
    if (displayed == Overlay.None) return

    val alpha = remember { Animatable(1f) }
    LaunchedEffect(overlay) {
        if (overlay == Overlay.None) {
            if (model.overlayAnimates) alpha.animateTo(0f, U2Motion.state.spec()) else alpha.snapTo(0f)
            shown = Overlay.None
            model.overlayFadeFinished()
        } else {
            alpha.snapTo(1f)
        }
    }
    val scope = rememberCoroutineScope()
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha.value }
            // Nothing below takes a touch while the cover or the lock is up.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
            .semantics { isTraversalGroup = true }
            .testTag(RootTags.SECURITY),
    ) {
        Box(Modifier.fillMaxSize().background(U2Theme.colors.bg))
        AnimatedContent(
            targetState = displayed,
            transitionSpec = {
                if (model.overlayAnimates) {
                    fadeIn(U2Motion.state.spec()) togetherWith fadeOut(U2Motion.state.spec())
                } else {
                    EnterTransition.None togetherWith ExitTransition.None
                }
            },
            label = "security",
        ) { state ->
            when (state) {
                Overlay.None -> Unit
                Overlay.PrivacyCover -> PrivacyCover(model.privacySnapshot, Modifier.testTag(RootTags.PRIVACY_COVER))
                Overlay.Lock -> U2OverlayHost(Modifier.testTag(RootTags.LOCK)) {
                    PrivacyCover(snapshot = null, showsMark = false)
                    LockFlow(
                        onUnlocked = model::didUnlock,
                        onRestore = model::continueAfterRestore,
                        onErased = { scope.launch { model.eraseEverything() } },
                    )
                }
            }
        }
    }
}

/** The window as it is drawn right now, at full size, for the theme cross-fade; `null` when it cannot be drawn. */
private object ThemeSnapshot {
    fun capture(view: View): ImageBitmap? {
        val root = view.rootView ?: return null
        if (root.width <= 0 || root.height <= 0) return null
        return try {
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            bitmap.asImageBitmap()
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }
}
