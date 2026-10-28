package com.syconx.us2us.feature.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.AvatarPalette
import com.syconx.us2us.domain.AvatarPicture
import com.syconx.us2us.domain.Presence
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.components.Avatar
import com.syconx.us2us.ui.components.AvatarSize
import com.syconx.us2us.ui.components.DottedRule
import com.syconx.us2us.ui.components.LocalU2LabelStyle
import com.syconx.us2us.ui.components.U2GlassIconButton
import com.syconx.us2us.ui.components.U2LabelStyle
import com.syconx.us2us.ui.env.LocalU2Clock
import com.syconx.us2us.ui.env.currentSafeArea
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.icons.PhosphorIcon
import com.syconx.us2us.ui.icons.PhosphorWeight
import com.syconx.us2us.ui.l10n.U2Format
import com.syconx.us2us.ui.l10n.currentAppLocale
import com.syconx.us2us.ui.theme.U2BackdropSource
import com.syconx.us2us.ui.theme.U2BackdropStyle
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.theme.u2Backdrop
import com.syconx.us2us.ui.tokens.U2Size
import com.syconx.us2us.ui.tokens.U2Spacing
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import java.time.Duration
import kotlin.math.max

/**
 * C8 chat header (5.1, 4.4): back (C4 40, `ph:caret-left` 20) · avatar 38 · name (`monoUsername`)
 * with the verified seal (`ph:seal-check` fill 15 `accent`) over the status line (`meta`
 * `text·68%`) · [trailing]; padding 54 14 10 12, gap 10, on the header backdrop (`bg·80%` +
 * blur), with the dotted rule at design y 104. A tap on the person opens [onTapPeer].
 */
@Composable
fun ChatHeader(
    username: String,
    palette: AvatarPalette,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    isVerified: Boolean = false,
    showsOnlineDot: Boolean = false,
    avatar: AvatarPicture = AvatarPicture.Initials,
    onTapPeer: (() -> Unit)? = null,
    peerHint: String? = null,
    backdrop: U2BackdropSource? = null,
    /** Drawn in the avatar's place (a community's mark); `null` draws the person's avatar. */
    avatarContent: (@Composable () -> Unit)? = null,
    status: @Composable () -> Unit,
    trailing: @Composable RowScope.() -> Unit,
) {
    val safeArea = currentSafeArea
    val colors = U2Theme.colors
    Box(modifier.fillMaxWidth().testTag("chatHeader").u2Backdrop(U2BackdropStyle.Header, source = backdrop)) {
        Row(
            Modifier.padding(start = 12.dp, top = safeArea.top(U2Spacing.headerTopChat), end = 14.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            U2GlassIconButton(
                PhosphorGlyph.CaretLeft,
                contentDescription = stringResource(R.string.common_a11y_back),
                onClick = onBack,
                modifier = Modifier.testTag("chatHeader.back"),
                diameter = U2Size.iconButtonChat,
                glyphSize = 20.dp,
            )
            val source = remember { MutableInteractionSource() }
            Row(
                Modifier
                    .weight(1f)
                    .testTag("chatHeader.peer")
                    .clickable(source, indication = null, enabled = onTapPeer != null, role = Role.Button) { onTapPeer?.invoke() }
                    .semantics(mergeDescendants = true) {
                        if (onTapPeer != null && peerHint != null) {
                            onClick(label = peerHint) {
                                onTapPeer()
                                true
                            }
                        }
                    },
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (avatarContent != null) {
                    Box(Modifier.testTag("chatHeader.avatar")) { avatarContent() }
                } else {
                    Avatar(username, palette, Modifier.testTag("chatHeader.avatar"), size = AvatarSize.Header, showsOnlineDot = showsOnlineDot, picture = avatar)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                        U2Text(
                            username,
                            U2TextStyles.monoUsername,
                            Modifier.weight(1f, fill = false).testTag("chatHeader.name").semantics { heading() },
                            color = colors.text,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (isVerified) {
                            PhosphorIcon(
                                PhosphorGlyph.SealCheck,
                                15.dp,
                                weight = PhosphorWeight.Fill,
                                tint = colors.accent,
                                contentDescription = stringResource(R.string.common_a11y_verified),
                            )
                        }
                    }
                    Box(Modifier.testTag("chatHeader.status")) {
                        CompositionLocalProvider(LocalU2LabelStyle provides U2LabelStyle(U2TextStyles.meta, colors.textSecondary)) { status() }
                    }
                }
            }
            trailing()
        }
        // The rule sits at design y 104: 4.63 dp above the header's bottom.
        DottedRule(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .offset(y = -(108.63 - 104 - 2).dp),
            color = colors.dotRuleHeader,
        )
    }
}

/** The header's status line in its style (`meta`, `text·68%`), one line. */
@Composable
internal fun HeaderStatusText(text: String, modifier: Modifier = Modifier) {
    val style = LocalU2LabelStyle.current
    U2Text(text, style.style, modifier, color = style.color, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
}

/** The header status line: “Aktif” with the 7 dp dot, “Az önce aktifti”, “3 dk önce aktifti”, “Bugün 16:40 aktifti”. */
@Composable
fun PresenceLine(presence: Presence, modifier: Modifier = Modifier) {
    when (presence) {
        Presence.Hidden -> Unit
        Presence.Online -> Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(U2Theme.colors.online, CircleShape))
            HeaderStatusText(stringResource(R.string.common_presence_online))
        }
        Presence.JustNow -> HeaderStatusText(stringResource(R.string.common_presence_justnow), modifier)
        is Presence.LastSeen -> {
            val now = LocalU2Clock.current.now
            val minutes = Duration.between(presence.at, now).toMinutes().toInt()
            if (minutes < 60) {
                HeaderStatusText(stringResource(R.string.common_presence_minutesago, max(1, minutes)), modifier)
            } else {
                HeaderStatusText(stringResource(R.string.common_presence_todayat, U2Format.clockTime(presence.at, currentAppLocale())), modifier)
            }
        }
    }
}
