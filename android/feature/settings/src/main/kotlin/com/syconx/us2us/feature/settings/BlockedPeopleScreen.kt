package com.syconx.us2us.feature.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syconx.us2us.domain.BlockedPerson
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.components.Avatar
import com.syconx.us2us.ui.components.AvatarSize
import com.syconx.us2us.ui.components.CenteredTitleHeader
import com.syconx.us2us.ui.components.GroupedCard
import com.syconx.us2us.ui.components.GroupedSeparator
import com.syconx.us2us.ui.components.U2GlassIconButton
import com.syconx.us2us.ui.components.U2TextButton
import com.syconx.us2us.ui.components.U2TextButtonTone
import com.syconx.us2us.ui.env.LocalHaptics
import com.syconx.us2us.ui.env.u2Bottom
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.l10n.rememberU2Strings
import com.syconx.us2us.ui.motion.LocalReduceMotion
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.motion.U2Transitions
import com.syconx.us2us.ui.nav.LocalRouter
import com.syconx.us2us.ui.screen.U2Screen
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.tokens.U2Size
import com.syconx.us2us.ui.tokens.U2Spacing
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import kotlinx.coroutines.launch

/** Test tags of 7.1 › Engellenenler (the iOS identifiers). */
object BlockedTags {
    const val SCREEN = "blocked.screen"
    const val BACK = "blocked.back"
    const val LIST = "blocked.list"
    const val EMPTY = "blocked.empty"
    const val FOOTNOTE = "blocked.footnote"

    fun row(id: ChatId): String = "blocked.row.${id.value}"

    fun unblock(id: ChatId): String = "blocked.unblock.${id.value}"
}

/**
 * 7.1 › Gizlilik › Engellenenler (proposed, in 7.1's language): everyone the user blocked, also those
 * whose chat is gone (Ghost erased it, or it was deleted), who could otherwise only be found again
 * through 4.2. One C13 card, a row per person, and under it what a block means. “Engeli kaldır”
 * needs no confirmation, as on the contact screen: the row collapses at once and the person is back
 * as before the block. With nobody blocked, one line in 4.3's empty-state style.
 */
@Composable
internal fun BlockedPeopleScreen(modifier: Modifier = Modifier) {
    val router = LocalRouter.current
    val haptics = LocalHaptics.current
    val strings = rememberU2Strings()
    val flow = rememberSettingsFlow()
    val messenger = flow.messenger
    val reduceMotion = LocalReduceMotion.current

    val blocked by messenger.blockedPeople.collectAsStateWithLifecycle()
    // Unblocked here and not yet gone from the service's list (the live runtime reloads it a moment
    // later): the row leaves at once.
    var leaving by remember { mutableStateOf(emptySet<ChatId>()) }
    val people = blocked.filter { it.id !in leaving }
    // Every row shown on this page, kept while it collapses after it has left the list.
    val rendered = remember { mutableStateListOf<BlockedPerson>() }
    var announcement by remember { mutableStateOf<String?>(null) }

    val blockedIds = blocked.map { it.id }
    LaunchedEffect(blockedIds) { leaving = leaving.intersect(blockedIds.toSet()) }
    // New people take their place in the list's order; departed ones stay until the page goes.
    val known = rendered.map { it.id }.toSet()
    val additions = blocked.filter { it.id !in known }
    if (additions.isNotEmpty()) {
        val merged = BlockedPerson.listed(rendered + additions)
        rendered.clear()
        rendered.addAll(merged)
    }

    // No confirmation: it can be undone at once (Engelle on the contact screen). The row goes back
    // if the unblock could not be stored.
    fun unblock(person: BlockedPerson) {
        haptics.selection()
        leaving = leaving + person.id
        val said = strings.get(R.string.contact_a11y_unblocked, person.name)
        flow.scope.launch {
            if (messenger.unblock(person)) announcement = said else leaving = leaving - person.id
        }
    }

    U2Screen(modifier.testTag(BlockedTags.SCREEN)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            CenteredTitleHeader(
                title = stringResource(R.string.settings_blocked),
                leading = {
                    U2GlassIconButton(
                        glyph = PhosphorGlyph.CaretLeft,
                        contentDescription = stringResource(R.string.common_a11y_back),
                        onClick = router::pop,
                        modifier = Modifier.testTag(BlockedTags.BACK),
                    )
                },
            )
            val listShown = people.isNotEmpty()
            val fade = U2Motion.resolve(U2Motion.state, reduceMotion)
            AnimatedContent(
                targetState = listShown,
                transitionSpec = { fadeIn(fade.spec()) togetherWith fadeOut(fade.spec()) },
                contentAlignment = Alignment.TopCenter,
                label = "blocked",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 18.dp, end = 16.dp)
                    .u2Bottom(U2Spacing.ctaBottom),
            ) { shown ->
                if (shown) {
                    BlockedList(rendered, visible = people.map { it.id }.toSet(), onUnblock = ::unblock)
                } else {
                    U2Text(
                        stringResource(R.string.settings_blocked_empty),
                        U2TextStyles.callout,
                        color = U2Theme.colors.textSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 120.dp)
                            .testTag(BlockedTags.EMPTY),
                    )
                }
            }
        }
        SettingsStatusBarFade()
        // TalkBack hears “… artık engelli değil.” once the unblock is stored (iOS posts an announcement).
        Box(
            Modifier
                .size(1.dp)
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    announcement?.let { contentDescription = it } ?: hideFromAccessibility()
                },
        )
    }
}

@Composable
private fun BlockedList(people: List<BlockedPerson>, visible: Set<ChatId>, onUnblock: (BlockedPerson) -> Unit) {
    val reduceMotion = LocalReduceMotion.current
    val first = people.firstOrNull { it.id in visible }?.id
    Column(verticalArrangement = Arrangement.spacedBy(U2Spacing.sectionLabelGap)) {
        GroupedCard(Modifier.testTag(BlockedTags.LIST)) {
            people.forEach { person ->
                val fade = U2Transitions.fade(U2Motion.state)
                AnimatedVisibility(
                    visible = person.id in visible,
                    enter = fade.enter,
                    exit = if (reduceMotion) fade.exit else U2Transitions.collapse(),
                ) {
                    Column {
                        if (person.id != first) GroupedSeparator(inset = BlockedRowMetrics.SEPARATOR_INSET)
                        BlockedPersonRow(person) { onUnblock(person) }
                    }
                }
            }
        }
        U2Text(
            stringResource(R.string.settings_blocked_footnote),
            U2TextStyles.footnote,
            color = U2Theme.colors.textSecondary,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .testTag(BlockedTags.FOOTNOTE),
        )
    }
}

/**
 * A row: C13's geometry with the avatar in the tile's place: avatar 44 (the initial: no picture for
 * someone blocked) · the name in `monoUsername`, with “Kullanıcı adı bilinmiyor” in
 * `captionCompact` under it when the phone keeps none · “Engeli kaldır” (`rowTitle` `accent300`).
 * 62 high (pad 9 14), gap 13. At large font scales “Engeli kaldır” goes under the name.
 */
@Composable
private fun BlockedPersonRow(person: BlockedPerson, onUnblock: () -> Unit) {
    val large = LocalDensity.current.fontScale >= ACCESSIBILITY_FONT_SCALE
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 62.dp)
            .padding(horizontal = 14.dp, vertical = 9.dp)
            .testTag(BlockedTags.row(person.id)),
        horizontalArrangement = Arrangement.spacedBy(U2Spacing.rowGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(
            username = person.name,
            palette = person.palette,
            size = AvatarSize.Request,
            picture = person.avatar,
            modifier = Modifier.clearAndSetSemantics {},
        )
        if (large) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                BlockedName(person, lines = 2)
                UnblockButton(person, onUnblock)
            }
        } else {
            BlockedName(person, lines = 1, modifier = Modifier.weight(1f))
            UnblockButton(person, onUnblock)
        }
    }
}

@Composable
private fun BlockedName(person: BlockedPerson, lines: Int, modifier: Modifier = Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        U2Text(person.name, U2TextStyles.monoUsername, color = U2Theme.colors.text, maxLines = lines, overflow = TextOverflow.Ellipsis)
        if (!person.isUsernameKnown) {
            U2Text(
                stringResource(R.string.settings_blocked_unknownusername),
                U2TextStyles.captionCompact,
                color = U2Theme.colors.textSecondary,
                maxLines = lines,
            )
        }
    }
}

@Composable
private fun UnblockButton(person: BlockedPerson, onUnblock: () -> Unit) {
    val hint = stringResource(R.string.contact_a11y_unblock_hint)
    U2TextButton(
        title = stringResource(R.string.common_block_unblock),
        onClick = onUnblock,
        tone = U2TextButtonTone.Accent,
        height = U2Size.iconButton,
        fillsWidth = false,
        modifier = Modifier
            .testTag(BlockedTags.unblock(person.id))
            // iOS `.accessibilityHint`: the outer action's label wins over the button's own.
            .semantics {
                onClick(label = hint) {
                    onUnblock()
                    true
                }
            },
    )
}

private object BlockedRowMetrics {
    /** The dotted separator starts under the name (14 + 44 + 13). */
    val SEPARATOR_INSET = 71.dp
}

/** iOS `isAccessibilitySize` starts at the first accessibility text size (about 1.6 × here). */
private const val ACCESSIBILITY_FONT_SCALE = 1.6f
