package com.syconx.us2us.feature.media

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.components.SpinningGlyph
import com.syconx.us2us.ui.components.U2GlassIconButton
import com.syconx.us2us.ui.components.U2GlassSurface
import com.syconx.us2us.ui.env.LocalU2Clock
import com.syconx.us2us.ui.env.u2Top
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.icons.PhosphorIcon
import com.syconx.us2us.ui.l10n.U2Format
import com.syconx.us2us.ui.l10n.currentAppLocale
import com.syconx.us2us.ui.media.MediaGlyph
import com.syconx.us2us.ui.media.MediaGlyphIcon
import com.syconx.us2us.ui.motion.LocalReduceMotion
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.motion.u2PressEffect
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.theme.mediaBottomShade
import com.syconx.us2us.ui.theme.mediaTopShade
import com.syconx.us2us.ui.theme.onMediaGlass
import com.syconx.us2us.ui.theme.u2InnerStroke
import com.syconx.us2us.ui.tokens.U2Size
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// The 5.6 viewer chrome, shared by the view-once viewer (5.6), the photo viewer and the video
// player (both proposed, in 5.6's language): the two shades and the top bar. Always over media,
// always dark.

/** Top shade 190 (`#05060c@70%` → clear) and bottom shade 220 (clear → `#05060c@80%`), edge to edge, not touchable. */
@Composable
fun MediaViewerShades(modifier: Modifier = Modifier) {
    val colors = U2Theme.colors
    Column(modifier.fillMaxSize().clearAndSetSemantics {}) {
        Box(Modifier.fillMaxWidth().height(190.dp).background(colors.mediaTopShade))
        Spacer(Modifier.weight(1f))
        Box(Modifier.fillMaxWidth().height(220.dp).background(colors.mediaBottomShade))
    }
}

/**
 * The top bar (pad 56 16 0, gap 12): close (44, glass over media, `ph:x` 21) · sender
 * (`monoUsername`) over “Bugün 18:40” (`meta` at 75 %) · an optional trailing control (5.6's
 * “1 kez” badge, the player's mute). [appDark] is the app's theme: the close button's hairline
 * follows it (5.6 light reference).
 */
@Composable
fun MediaViewerTopBar(
    sender: String,
    sentAt: Instant,
    appDark: Boolean,
    closeTag: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val colors = U2Theme.colors
    val locale = currentAppLocale()
    val clock = LocalU2Clock.current
    val today = stringResource(R.string.conversation_viewonce_today, "%s")
    Row(
        modifier
            .fillMaxWidth()
            .u2Top(56.dp)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppThemed(appDark) {
            U2GlassIconButton(
                glyph = PhosphorGlyph.X,
                contentDescription = stringResource(R.string.common_a11y_close),
                onClick = onClose,
                glyphSize = 21.dp,
                surface = U2GlassSurface.Media,
                modifier = Modifier.testTag(closeTag),
            )
        }
        Column(
            Modifier
                .weight(1f)
                .testTag(ViewerChromeTags.TITLE)
                .semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            U2Text(sender, U2TextStyles.monoUsername, color = colors.onMedia, maxLines = 1, overflow = TextOverflow.Ellipsis)
            U2Text(
                MediaViewerTime.line(sentAt, clock.now, locale, today),
                U2TextStyles.meta,
                color = colors.onMedia.copy(alpha = 0.75f),
                maxLines = 1,
            )
        }
        trailing()
    }
}

/** Test tags shared by the viewers' chrome. */
object ViewerChromeTags {
    /** The sender and time column of the top bar. */
    const val TITLE = "viewer.title"
}

/** Draws [content] in the app's own theme (inside a cover that is always dark). */
@Composable
internal fun AppThemed(dark: Boolean, content: @Composable () -> Unit) {
    if (dark == U2Theme.isDark) {
        content()
    } else {
        U2Theme(dark = dark, reduceMotion = LocalReduceMotion.current, content = content)
    }
}

/** Draws [content] always dark (the viewers), keeping reduce motion. */
@Composable
internal fun AlwaysDark(content: @Composable () -> Unit) {
    AppThemed(dark = true, content = content)
}

object MediaViewerTime {
    /** “Bugün 18:40” today, otherwise “8 Eki 18:40”. [today] is the localized “Bugün %s” pattern. */
    fun line(date: Instant, now: Instant, locale: Locale, today: String, zone: ZoneId = ZoneId.systemDefault()): String {
        val time = U2Format.clockTime(date, locale, zone)
        val sameDay = date.atZone(zone).truncatedTo(ChronoUnit.DAYS) == now.atZone(zone).truncatedTo(ChronoUnit.DAYS)
        if (sameDay) return today.replace("%s", time).replace("%1\$s", time)
        return "${U2Format.dayMonth(date, locale, zone)} $time"
    }
}

/** The glass button over media: 44, `#0c0d16@40%` (55 % pressed), hairline `strokeGlass`, `onMedia` ink, the 0.97 press. */
@Composable
internal fun MediaGlassButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = U2Theme.colors
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        modifier
            .size(U2Size.iconButton)
            .u2PressEffect(source)
            .background(colors.onMediaGlass(if (pressed) 0.55f else 0.4f), CircleShape)
            .u2InnerStroke(colors.strokeGlass, 0.5.dp, CircleShape)
            .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** A 44 dp glass button over media (the player's mute) with one of the drawn media glyphs. */
@Composable
fun MediaGlassIconButton(
    glyph: MediaGlyph,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glyphSize: Dp = 21.dp,
) {
    MediaGlassButton(onClick, contentDescription, modifier) {
        MediaGlyphIcon(glyph, glyphSize, tint = U2Theme.colors.onMedia)
    }
}

/** “Kaydet” over media: idle, working (spinner), saved (check). */
enum class MediaSavePhase { Idle, Working, Saved }

/**
 * “Kaydet” over media (proposed): the 44 glass button with the download glyph (to the gallery);
 * a spinner while the bytes are read and written, a check once saved (“Kaydedildi”). Accessibility
 * says that the sender will see it ([tellsSender]).
 */
@Composable
fun MediaSaveButton(phase: MediaSavePhase, tellsSender: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(if (phase == MediaSavePhase.Saved) R.string.conversation_viewer_saved else R.string.conversation_viewer_save)
    val hint = if (tellsSender) stringResource(R.string.conversation_viewer_save_hint) else ""
    val ink = U2Theme.colors.onMedia
    MediaGlassButton(
        onClick = onClick,
        contentDescription = if (hint.isEmpty()) label else "$label. $hint",
        enabled = phase != MediaSavePhase.Working,
        modifier = modifier.testTag("viewer.save"),
    ) {
        val reduceMotion = LocalReduceMotion.current
        AnimatedContent(
            targetState = phase,
            transitionSpec = {
                val fade = U2Motion.resolve(U2Motion.micro, reduceMotion)
                fadeIn(fade.spec()) togetherWith fadeOut(fade.spec())
            },
            contentAlignment = Alignment.Center,
            label = "saveButton",
        ) { shown ->
            when (shown) {
                MediaSavePhase.Idle -> MediaGlyphIcon(MediaGlyph.Download, 21.dp, tint = ink)
                MediaSavePhase.Working -> SpinningGlyph(size = 20.dp, tint = ink)
                MediaSavePhase.Saved -> PhosphorIcon(PhosphorGlyph.Check, 21.dp, tint = ink)
            }
        }
    }
}

/**
 * The photo viewer's and the player's swipe away (proposed; 5.6 itself never closes by swipe): the
 * media follows the finger, shrinks to 0.9 and the backdrop thins to 15 % with the distance; let go
 * past 110 dp, or flicked faster than 800 dp/s the same way, and it closes — otherwise it settles
 * back on the critically damped spring.
 */
object MediaDismissGesture {
    const val DISTANCE = 110f
    const val VELOCITY = 800f

    /** 0 at rest … 1 at a third of the screen. */
    fun progress(translation: Float, height: Float): Float {
        if (height <= 0f) return 0f
        return min(1f, abs(translation) / (height / 3f))
    }

    fun scale(progress: Float): Float = 1f - 0.1f * progress.coerceIn(0f, 1f)

    fun backdropOpacity(progress: Float): Float = 1f - 0.85f * progress.coerceIn(0f, 1f)

    /** The chrome fades three times as fast as the backdrop. */
    fun chromeOpacity(progress: Float): Float = max(0f, 1f - progress * 3f)

    /**
     * Released with [translation] and [velocity] (dp, dp per second, same axis): close, or settle
     * back. A fast flick back towards the rest position always settles back.
     */
    fun shouldDismiss(translation: Float, velocity: Float): Boolean {
        val flickedBack = translation != 0f && (velocity > 0f) != (translation > 0f) && abs(velocity) > VELOCITY
        if (flickedBack) return false
        return abs(translation) > DISTANCE || abs(velocity) > VELOCITY
    }
}
