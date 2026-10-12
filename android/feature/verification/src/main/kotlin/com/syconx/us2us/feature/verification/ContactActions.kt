package com.syconx.us2us.feature.verification

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.syconx.us2us.domain.MessengerService
import com.syconx.us2us.domain.Peer
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.feature.report.ReportMailer
import com.syconx.us2us.feature.report.ReportModel
import com.syconx.us2us.feature.report.ReportPresenter
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.components.U2TextButton
import com.syconx.us2us.ui.components.U2TextButtonTone
import com.syconx.us2us.ui.env.U2Haptics
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.icons.PhosphorIcon
import com.syconx.us2us.ui.l10n.U2Strings
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.theme.u2InnerStroke
import com.syconx.us2us.ui.tokens.U2Radius
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text

/**
 * The contact screen's “…” menu (proposed, App Review 1.2): the safety number to share (on
 * Doğrulama), “Bildir” (the report sheet) and “Engelle” after the request block's own confirmation
 * (“{kişi} engellensin mi?” · Engelle · Bildir · Vazgeç); “Engeli kaldır” once blocked, in the same
 * place. The blocked state itself comes from the messenger ([Peer.isBlocked]), so 4.1 and 5.1
 * follow on their own.
 */
@Stable
internal class ContactActions(val chatId: ChatId, val messenger: MessengerService) {
    var showsMenu by mutableStateOf(false)

    /** “{kişi} engellensin mi?” */
    var confirmsBlock by mutableStateOf(false)

    /** Where reports are sent from; `null` is the screen's own mailer (the harness swaps it). */
    var mailer: ReportMailer? = null

    /** Set by the screen: the window's haptics, the UI language's texts and its announcer. */
    var haptics: U2Haptics = U2Haptics.None
    var strings: U2Strings? = null
    var announce: (String) -> Unit = {}

    /** The name of a block the user confirmed, until it shows ([blockDidShow]). */
    private var blockAwaitingFeedback: String? = null

    fun peerName(peer: Peer?): String = peer?.username ?: chatId.value

    fun requestBlock() {
        confirmsBlock = true
    }

    /** After the confirmation: blocked (the menu, the note, 5.1 and 4.1 follow). */
    suspend fun block(name: String) {
        blockAwaitingFeedback = name
        if (messenger.block(chatId)) blockDidShow() else blockAwaitingFeedback = null
    }

    /**
     * The block is on screen ([Peer.isBlocked] turned true): the haptic and the announcement, once.
     * The block is stored at once, but the call also waits for the new access key it asks the relay
     * for (up to its timeout on a poor connection); the feedback does not.
     */
    fun blockDidShow() {
        val name = blockAwaitingFeedback ?: return
        blockAwaitingFeedback = null
        haptics.success()
        strings?.let { announce(it.get(R.string.contact_a11y_blocked, name)) }
    }

    /**
     * “Engeli kaldır”: no confirmation; it can be undone at once. The name is taken first: a blocked
     * request leaves the chats (it is a request again), and with it the peer.
     */
    suspend fun unblock(name: String) {
        if (!messenger.unblock(chatId)) return
        strings?.let { announce(it.get(R.string.contact_a11y_unblocked, name)) }
    }

    /**
     * “Bildir” (the menu, or the block confirmation): the sheet, with “Bu kişiyi de engelle” unless
     * the person is blocked already. From the confirmation it rises once the confirmation has gone.
     */
    fun openReport(reports: ReportPresenter, peer: Peer?, afterConfirmation: Boolean = false): ReportModel {
        confirmsBlock = false
        val messenger = messenger
        val chat = chatId
        return reports.open(
            subject = messenger.reportSubject(chat),
            personName = peerName(peer),
            offersBlock = peer?.isBlocked != true,
            afterConfirmation = afterConfirmation,
            mailer = mailer,
        ) { messenger.block(chat) }
    }
}

/**
 * The contact screen's note while the person is blocked (Doğrulama, above the line; proposed): the
 * 5.4 note card's look (inner 1 dp `danger·40%`, r20, pad 16 16 6 16, gap 14, `ph:prohibit` 22
 * `danger`, `noteTitle` + `captionTight`) with “Engeli kaldır” (`rowTitle` `accent300`) under the text.
 */
@Composable
internal fun ContactBlockedNote(name: String, onUnblock: () -> Unit, modifier: Modifier = Modifier) {
    val colors = U2Theme.colors
    Row(
        modifier
            .fillMaxWidth()
            .u2InnerStroke(colors.dangerStrokeSoft, 1.dp, U2Radius.cardL)
            .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        PhosphorIcon(PhosphorGlyph.Prohibit, size = 22.dp, tint = colors.danger)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                U2Text(stringResource(R.string.contact_blocked_title, name), U2TextStyles.noteTitle, color = colors.text)
                U2Text(stringResource(R.string.contact_blocked_message), U2TextStyles.captionTight, color = colors.textSecondary)
            }
            val hint = stringResource(R.string.contact_a11y_unblock_hint)
            U2TextButton(
                title = stringResource(R.string.common_block_unblock),
                onClick = onUnblock,
                tone = U2TextButtonTone.Accent,
                height = 36.dp,
                fillsWidth = false,
                modifier = Modifier
                    .testTag(VerificationTags.UNBLOCK)
                    // iOS `.accessibilityHint`: the outer action's label wins over the button's own.
                    .semantics {
                        onClick(label = hint) {
                            onUnblock()
                            true
                        }
                    },
            )
        }
    }
}
