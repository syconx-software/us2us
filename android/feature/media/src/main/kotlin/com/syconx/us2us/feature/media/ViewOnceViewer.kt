package com.syconx.us2us.feature.media

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import com.syconx.us2us.feature.media.R as MediaR
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.components.FloatingBanner
import com.syconx.us2us.ui.components.FloatingBannerKind
import com.syconx.us2us.ui.env.ForceDarkSystemBars
import com.syconx.us2us.ui.env.LocalHaptics
import com.syconx.us2us.ui.env.u2Bottom
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.icons.PhosphorIcon
import com.syconx.us2us.ui.l10n.L10n
import com.syconx.us2us.ui.l10n.TurkishSuffix
import com.syconx.us2us.ui.l10n.currentAppLocale
import com.syconx.us2us.ui.media.MediaView
import com.syconx.us2us.ui.motion.LocalReduceMotion
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.motion.U2Transitions
import com.syconx.us2us.ui.motion.u2TransitionBlur
import com.syconx.us2us.ui.screen.U2Screen
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.theme.onMediaGlass
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import java.util.concurrent.Executor
import java.util.function.Consumer

/** Test tags of the 5.6 viewer (the iOS accessibility identifiers). */
object ViewOnceTags {
    const val CLOSE = "viewer.close"
    const val TITLE = ViewerChromeTags.TITLE
    const val BADGE = "viewOnce.badge"
    const val WARNING = "viewOnce.warning"
    const val FOOTER = "viewOnce.footer"
    const val MEDIA = "viewOnce.media"
    const val CAPTURED = "viewOnce.captured"
}

/**
 * 5.6 · Tek görüntülemelik — the full-screen viewer for view-once media only (ordinary photos open
 * in the photo viewer, videos in the player).
 *
 * Always dark, whatever the theme. The photo fills the screen under a top shade (190,
 * `#05060c@70%` → clear) and a bottom shade (220, clear → `#05060c@80%`). Top bar: close (44,
 * glass over media) · sender + “Bugün 18:40” · the dashed “1 kez” badge. When a screenshot is
 * detected (Android 14+) the warning card drops in (fade + 8 dp) and the sender is told
 * ([onScreenshot]). While the screen is recorded (Android 15+) the photo is hidden. Footer:
 * “Kapatınca silinir” + “Bu fotoğraf yeniden açılamaz ve yedeğe girmez.” Only the close button
 * ends it (no swipe — no accidental closing). [appDark] is the app's theme: the chrome that uses
 * theme tokens (the warning card, the close button's hairline) follows it.
 */
@Composable
fun ViewOnceViewer(
    item: ViewerItem,
    appDark: Boolean,
    onScreenshot: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showsScreenshotWarning by rememberSaveable(item) { mutableStateOf(item.showsScreenshotWarning) }
    var isRecorded by remember { mutableStateOf(false) }
    val isCaptured = isRecorded || item.simulatesCapture
    val isViewOnce = item.kind == ViewerItem.Kind.ViewOnce
    val haptics = LocalHaptics.current
    val currentOnScreenshot by rememberUpdatedState(onScreenshot)

    ForceDarkSystemBars()
    ScreenCaptureProbe(
        onScreenshot = {
            if (isViewOnce) {
                haptics.error()
                showsScreenshotWarning = true
                currentOnScreenshot()
            }
        },
        onRecordingChange = { isRecorded = it },
    )
    AlwaysDark {
        val colors = U2Theme.colors
        U2Screen(modifier, background = colors.mediaViewerBg) {
            ViewOnceMedia(item, isCaptured)
            MediaViewerShades()
            Column(Modifier.fillMaxSize()) {
                MediaViewerTopBar(
                    sender = item.sender,
                    sentAt = item.sentAt,
                    appDark = appDark,
                    closeTag = ViewOnceTags.CLOSE,
                    onClose = onClose,
                ) {
                    if (isViewOnce) ViewOnceBadge()
                }
                val drop = U2Transitions.drop()
                AnimatedVisibility(visible = showsScreenshotWarning, enter = drop.enter, exit = drop.exit) {
                    AppThemed(appDark) {
                        ScreenshotWarning(
                            sender = item.sender,
                            modifier = Modifier
                                .then(u2TransitionBlur(drop))
                                .padding(start = 12.dp, top = 16.dp, end = 12.dp),
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                if (isViewOnce) ViewOnceFooter(item.isVideo)
            }
        }
    }
}

/** The photo, or — while the screen is recorded — the hint that it is hidden. */
@Composable
private fun ViewOnceMedia(item: ViewerItem, isCaptured: Boolean) {
    val colors = U2Theme.colors
    val reduceMotion = LocalReduceMotion.current
    Crossfade(
        targetState = isCaptured,
        animationSpec = U2Motion.resolve(U2Motion.state, reduceMotion).spec(),
        modifier = Modifier.fillMaxSize().background(colors.mediaViewerBg),
        label = "viewOnceCaptured",
    ) { captured ->
        if (captured) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 40.dp)
                    .testTag(ViewOnceTags.CAPTURED)
                    .semantics(mergeDescendants = true) {},
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val ink = colors.onMedia.copy(alpha = 0.72f)
                PhosphorIcon(PhosphorGlyph.EyeSlash, 28.dp, tint = ink)
                U2Text(stringResource(R.string.conversation_viewonce_captured), U2TextStyles.label15Emphasis, color = ink, textAlign = TextAlign.Center)
            }
        } else {
            val label = stringResource(if (item.isVideo) R.string.common_media_video else R.string.common_media_photo)
            MediaView(item.media, Modifier.fillMaxSize().testTag(ViewOnceTags.MEDIA).semantics { contentDescription = label })
        }
    }
}

/** The dashed “1 kez” badge: Mono 11, 32 high, pad 0 12 (+ the 1 dp border), `#0c0d16@45%`, a 1 dp dashed `onMedia·50%` ring (3 / 2.5). */
@Composable
private fun ViewOnceBadge() {
    val colors = U2Theme.colors
    val ring = colors.onMedia.copy(alpha = 0.5f)
    Box(
        Modifier
            .testTag(ViewOnceTags.BADGE)
            .height(32.dp)
            .background(colors.onMediaGlass(0.45f), CircleShape)
            .drawWithContent {
                drawContent()
                val width = 1.dp.toPx()
                val inset = width / 2f
                val radius = (size.height - width) / 2f
                drawRoundRect(
                    color = ring,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - width, size.height - width),
                    cornerRadius = CornerRadius(radius, radius),
                    style = Stroke(width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.5.dp.toPx()))),
                )
            }
            .padding(horizontal = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        U2Text(stringResource(R.string.conversation_viewonce_badge), U2TextStyles.monoMeta, color = colors.onMedia, maxLines = 1)
    }
}

/** The screenshot warning (C10 floating banner, in the app's theme): camera 22 `danger` · title over the body naming the sender. */
@Composable
private fun ScreenshotWarning(sender: String, modifier: Modifier = Modifier) {
    val colors = U2Theme.colors
    val locale = currentAppLocale()
    val name = if (L10n.isTurkish(locale)) TurkishSuffix.dative(sender) else sender
    FloatingBanner(FloatingBannerKind.Warning, modifier.testTag(ViewOnceTags.WARNING).semantics(mergeDescendants = true) {}) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            PhosphorIcon(PhosphorGlyph.Camera, 22.dp, tint = colors.danger)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                U2Text(stringResource(R.string.conversation_viewonce_screenshot_title), U2TextStyles.noteTitle, color = colors.text)
                U2Text(stringResource(MediaR.string.media_android_viewonce_screenshot_body, name), U2TextStyles.captionTighter, color = colors.textSecondary)
            }
        }
    }
}

/** “Kapatınca silinir” over the footer line (gap 8), pad 0 24 50, centred. */
@Composable
private fun ViewOnceFooter(isVideo: Boolean) {
    val colors = U2Theme.colors
    Column(
        Modifier
            .testTag(ViewOnceTags.FOOTER)
            .semantics(mergeDescendants = true) {}
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .u2Bottom(50.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PhosphorIcon(PhosphorGlyph.EyeSlash, 18.dp, tint = colors.onMedia)
            U2Text(stringResource(R.string.conversation_viewonce_closedeletes), U2TextStyles.label15Emphasis, color = colors.onMedia)
        }
        U2Text(
            stringResource(if (isVideo) R.string.conversation_viewonce_footer_video else R.string.conversation_viewonce_footer_photo),
            U2TextStyles.caption,
            color = colors.onMedia.copy(alpha = 0.72f),
            textAlign = TextAlign.Center,
        )
    }
}

// MARK: - Screen capture

/**
 * Hears screenshots (Android 14+, `Activity.registerScreenCaptureCallback`) and screen recording
 * of this app's window (Android 15+, `WindowManager.addScreenRecordingCallback`) while composed.
 * Older versions report nothing; the window is `FLAG_SECURE` anyway, so captures come out black.
 */
@Composable
internal fun ScreenCaptureProbe(onScreenshot: () -> Unit, onRecordingChange: (Boolean) -> Unit) {
    val activity = LocalContext.current.findActivity() ?: return
    val currentOnScreenshot by rememberUpdatedState(onScreenshot)
    val currentOnRecording by rememberUpdatedState(onRecordingChange)
    DisposableEffect(activity) {
        val executor = Executor { command -> activity.runOnUiThread(command) }
        val screenshot = if (Build.VERSION.SDK_INT >= 34) ScreenshotRegistration.register(activity, executor) { currentOnScreenshot() } else null
        val recording = if (Build.VERSION.SDK_INT >= 35) RecordingRegistration.register(activity, executor) { currentOnRecording(it) } else null
        onDispose {
            screenshot?.invoke()
            recording?.invoke()
        }
    }
}

@RequiresApi(34)
private object ScreenshotRegistration {
    /** Registers, returning the unregistration; `null` when the platform refused (no permission). */
    fun register(activity: Activity, executor: Executor, onScreenshot: () -> Unit): (() -> Unit)? {
        val callback = Activity.ScreenCaptureCallback { onScreenshot() }
        return runCatching {
            activity.registerScreenCaptureCallback(executor, callback)
            val unregister: () -> Unit = { runCatching { activity.unregisterScreenCaptureCallback(callback) } }
            unregister
        }.getOrNull()
    }
}

@RequiresApi(35)
private object RecordingRegistration {
    fun register(activity: Activity, executor: Executor, onChange: (Boolean) -> Unit): (() -> Unit)? {
        val manager = activity.getSystemService(WindowManager::class.java) ?: return null
        val consumer = Consumer<Int> { state -> onChange(state == WindowManager.SCREEN_RECORDING_STATE_VISIBLE) }
        return runCatching {
            val initial = manager.addScreenRecordingCallback(executor, consumer)
            onChange(initial == WindowManager.SCREEN_RECORDING_STATE_VISIBLE)
            val unregister: () -> Unit = { runCatching { manager.removeScreenRecordingCallback(consumer) } }
            unregister
        }.getOrNull()
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
