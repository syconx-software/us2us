package com.syconx.us2us.feature.conversation

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import com.syconx.us2us.domain.AvatarPalette
import com.syconx.us2us.domain.ChatSummary
import com.syconx.us2us.domain.CommunitiesService
import com.syconx.us2us.domain.CommunityApplication
import com.syconx.us2us.domain.CommunityCard
import com.syconx.us2us.domain.CommunityChat
import com.syconx.us2us.domain.CommunityChats
import com.syconx.us2us.domain.CommunityFormat
import com.syconx.us2us.domain.CommunityMemberCard
import com.syconx.us2us.domain.CommunityMessageInfo
import com.syconx.us2us.domain.CommunityPage
import com.syconx.us2us.domain.CommunitySender
import com.syconx.us2us.domain.CommunitySnapshot
import com.syconx.us2us.domain.DisappearingTimer
import com.syconx.us2us.domain.GhostState
import com.syconx.us2us.domain.Message
import com.syconx.us2us.domain.MessageContent
import com.syconx.us2us.domain.MessageDirection
import com.syconx.us2us.domain.MessageRecipient
import com.syconx.us2us.domain.MessageStatus
import com.syconx.us2us.domain.MessengerService
import com.syconx.us2us.domain.Peer
import com.syconx.us2us.domain.Presence
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.MessageId
import com.syconx.us2us.fixtures.FixturesMessengerService
import com.syconx.us2us.shot.ShotEnvironment
import com.syconx.us2us.shot.ShotHost
import com.syconx.us2us.ui.env.PreviewAppModel
import com.syconx.us2us.ui.env.U2PreviewRoot
import com.syconx.us2us.ui.l10n.U2Format
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A community's conversation on the 1:1 screen (Communities extension v2): sender names, the
 * long-press menu without reply and reactions, “Herkesten sil” for own messages and the owner,
 * the 5.4 recipient list, and the members and management sheet behind the header's “…”.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ShotHost.SDK])
class CommunityConversationTest {
    private val rig = ConversationRig(english = true)
    private val messenger = CommunityMessenger(rig.fixtures)

    @get:Rule
    val host = ShotHost(environment = ShotEnvironment { spec, content ->
        U2PreviewRoot(PreviewAppModel(messenger, clock = rig.fixtures.clock), dark = spec.isDark, designInsets = spec.designInsets) { content() }
    })

    /** A hang fails the test instead of blocking the run. */
    @get:Rule
    val deadline = Deadline(seconds = if (com.syconx.us2us.shot.UxMatrix.enabled) 1_800 else 120)

    private fun open() {
        host.render(lang = "en") { ConversationScreen(messenger.chatId) }
        host.advance(600)
    }

    private fun tagged(tag: String) = host.compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun exists(text: String) = host.compose.onAllNodes(hasText(text, substring = true, ignoreCase = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun longPress(id: String) {
        host.compose.onNodeWithTag("message.$id", useUnmergedTree = true).performTouchInput { longClick() }
        host.advance(1_000)
    }

    private fun menu(item: String) {
        host.compose.onNodeWithTag("menu.$item", useUnmergedTree = true).performClick()
        host.advance(1_000)
    }

    @Test fun communityComposerOffersStickersAndVoiceWithoutGhostTimer() {
        open()
        assertTrue("mic replaces the send-only community composer", tagged("composer.action"))
        assertTrue(host.compose.onAllNodes(hasContentDescription("Sticker"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        host.compose.onNodeWithContentDescription("Sticker", useUnmergedTree = true).performClick()
        host.advance(600)
        assertTrue(tagged("sticker.picker"))
        host.compose.onNodeWithTag("sticker.picker.cell.1.-", useUnmergedTree = true).performClick()
        host.advance(600)
        assertEquals(1, messenger.sentStickers.size)
        assertEquals(messenger.chatId, messenger.sentStickers.single().second)
        assertFalse(tagged("sticker.picker"))
    }

    @Test fun incomingBubblesSayWhoWroteThemOncePerRun() {
        open()
        assertTrue("a verified contact by username", tagged("message.in1.sender"))
        assertTrue(exists("@deniz"))
        assertFalse("the second message of the same run has no name", tagged("message.in2.sender"))
        assertTrue("an unknown member is a short id", tagged("message.in3.sender"))
        assertTrue(exists("Member · 9f8e7d6c"))
        assertFalse("never a whole account id", exists("9f8e7d6c-"))
        assertFalse("own messages carry no name", tagged("message.own1.sender"))
        assertTrue("the header says how many members", exists("3 members"))
    }

    @Test fun ownMessageMenuHasInfoAndDeleteForEveryoneButNoReplyOrReactions() {
        open()
        longPress("own1")
        assertTrue(tagged("actions.overlay"))
        assertFalse("no reactions in a community", tagged("actions.reactions"))
        assertFalse("no reply in a community", tagged("menu.reply"))
        for (item in listOf("info", "copy", "deleteEveryone", "deleteMe")) assertTrue(item, tagged("menu.$item"))
        menu("deleteEveryone")
        assertTrue("it asks first, and says what cannot be recalled", exists("cannot be recalled"))
        host.compose.onNodeWithTag("conversation.deleteEveryone.confirm", useUnmergedTree = true).performClick()
        host.advance(1_000)
        assertEquals(listOf(MessageId("own1")), messenger.deletedForEveryone)
    }

    @Test fun talkBacksActionOpensTheMenuWithoutATouch() {
        open()
        // TalkBack's custom action arrives with no press, so no frame was recorded for the bubble.
        val node = host.compose.onNodeWithTag("message.own1", useUnmergedTree = true).fetchSemanticsNode()
        val action = node.config[androidx.compose.ui.semantics.SemanticsActions.CustomActions].first()
        host.compose.runOnIdle { action.action() }
        host.advance(1_500)
        assertTrue("the menu opens all the same", tagged("actions.overlay"))
    }

    @Test fun anIncomingMessageOffersOnlyDeleteForMeToAMember() {
        open()
        longPress("in1")
        assertFalse(tagged("menu.info"))
        assertFalse(tagged("menu.deleteEveryone"))
        assertTrue(tagged("menu.copy"))
        menu("deleteMe")
        assertEquals(listOf(MessageId("in1")), messenger.deletedForMe)
    }

    @Test fun theOwnerMayDeleteAnyMessageForEveryone() {
        messenger.isOwner = true
        open()
        longPress("in3")
        assertTrue("moderation", tagged("menu.deleteEveryone"))
    }

    @Test fun placeholdersSayWhoDeletedTheMessage() {
        open()
        assertTrue(exists("This message was deleted"))
        assertTrue(exists("Removed by the owner"))
    }

    @Test fun infoListsEveryRecipientWithTimesOrPending() {
        open()
        longPress("own1")
        menu("info")
        assertTrue(tagged("info.sheet"))
        assertTrue(tagged("info.recipients"))
        val at = Instant.parse("2026-10-14T14:00:00Z")
        fun time(seconds: Long) = U2Format.clockTime(at.plusSeconds(seconds), java.util.Locale.ENGLISH)
        assertTrue(exists("Read ${time(120)}"))
        assertTrue("read implies delivered: both times", exists("Delivered ${time(60)}"))
        assertTrue(exists("Pending"))
        assertTrue(exists("Left the community"))
        assertTrue(exists("Not sent"))
        assertTrue("never a whole account id", exists("Member · 0a1b2c3d"))
        assertFalse(exists("0a1b2c3d-"))
        assertEquals(listOf(MessageId("own1")), messenger.recipientsAsked)
    }

    // MARK: Members and management (the header's “…”)

    private fun manage() {
        host.compose.onNodeWithTag(CommunityManageTags.MANAGE, useUnmergedTree = true).performClick()
        host.advance(1_000)
    }

    private fun tap(text: String) = clickVisible(host.compose.onAllNodes(hasText(text) and hasClickAction()).onLast())
    private fun tapTag(tag: String) = clickVisible(host.compose.onNodeWithTag(tag))

    /**
     * Clicks [node] once it is on screen: in the sheet it is scrolled into view first by the scroll
     * action itself (`performScrollTo` waits for an animated scroll that the paused clock never
     * moves, and a swipe down would drag the sheet).
     */
    private fun clickVisible(node: SemanticsNodeInteraction) {
        val height = host.compose.onAllNodes(isRoot()).onFirst().fetchSemanticsNode().boundsInRoot.height
        val sheet = host.compose.onAllNodesWithTag(CommunityManageTags.SHEET).fetchSemanticsNodes().firstOrNull()?.boundsInRoot
        // A node scrolled wholly out of the sheet has empty bounds: bring the sheet's end into view first.
        if (sheet != null && node.fetchSemanticsNode().boundsInRoot.isEmpty) {
            host.compose.onNode(hasScrollAction() and hasAnyAncestor(hasTestTag(CommunityManageTags.SHEET)))
                .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, sheet.height) }
            host.advance(1_000)
        }
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val delta = when {
            sheet == null -> 0f
            bounds.top < sheet.top + 160f -> bounds.top - (sheet.top + 240f)
            bounds.bottom > height - 8f -> bounds.bottom - (height - 120f)
            else -> 0f
        }
        if (delta != 0f) {
            host.compose.onNode(hasScrollAction() and hasAnyAncestor(hasTestTag(CommunityManageTags.SHEET)))
                .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, delta) }
            host.advance(1_000)
        }
        node.performClick()
        host.advance(1_000)
    }

    private fun confirm() {
        host.compose.onNodeWithTag("communities.confirm").performClick()
        host.advance(1_000)
    }

    @Test fun keysAreGroupedHexAndAccountsShortIds() {
        messenger.isOwner = true
        open()
        manage()
        assertTrue(tagged(CommunityManageTags.SHEET))
        assertTrue(exists("Identity key to add"))
        assertTrue(exists(CommunityFormat.fingerprint(KEY)))
        assertFalse("the raw key is not shown", exists(messenger.service.identity))
        assertTrue(exists("Member · d5bb5a72"))
        assertTrue("a verified contact is shown by username", exists("@deniz"))
        assertTrue(exists("Not in your contacts"))
        assertTrue(exists("Members · 3/100"))
    }

    @Test fun ownerApprovesAndRemovesOnlyAfterConfirming() {
        messenger.isOwner = true
        open()
        manage()
        tap("Approve")
        assertEquals(APPLICANT, messenger.service.approved)
        tapTag(CommunityManageTags.remove(DENIZ))
        assertNull("removing needs a confirmation", messenger.service.removed)
        confirm()
        assertEquals(DENIZ, messenger.service.removed)
        assertTrue("the sheet stays open after removing", tagged(CommunityManageTags.SHEET))
    }

    @Test fun theOwnerRejectsAllRequestsOnlyAfterConfirming() {
        messenger.isOwner = true
        open()
        manage()
        assertFalse("one request needs no bulk action", tagged(CommunityManageTags.REJECT_ALL))
        messenger.service.applicationList += CommunityApplication("second", messenger.service.identity, 2, CommunityFormat.fingerprint(KEY))
        host.advance(10_100)
        tapTag(CommunityManageTags.REJECT_ALL)
        assertNull("rejecting all needs a confirmation", messenger.service.rejectedAll)
        confirm()
        assertEquals("owned", messenger.service.rejectedAll)
        assertFalse(tagged(CommunityManageTags.REJECT_ALL))
        assertTrue("the sheet stays open", tagged(CommunityManageTags.SHEET))
    }

    @Test fun anInvitationIsHiddenOnceUsedOrWhenTheSheetCloses() {
        messenger.isOwner = true
        open()
        manage()
        tap("Create one-use invitation")
        assertTrue(tagged(CommunityManageTags.INVITE))
        // Someone redeemed it: their request arrives with the next poll.
        messenger.service.applicationList += CommunityApplication("redeemer", messenger.service.identity, 2, CommunityFormat.fingerprint(KEY))
        host.advance(10_100)
        assertFalse("a redeemed invitation leaves the screen", tagged(CommunityManageTags.INVITE))
        tap("Create one-use invitation")
        assertTrue(tagged(CommunityManageTags.INVITE))
        tapTag("sheet.close")
        manage()
        assertFalse("closing the sheet drops the invitation", tagged(CommunityManageTags.INVITE))
    }

    @Test fun aMemberLeavesAfterConfirming() {
        open()
        manage()
        tap("Leave community")
        assertNull(messenger.service.left)
        confirm()
        assertEquals("owned", messenger.service.left)
        assertFalse(tagged(CommunityManageTags.SHEET))
    }

    @Test fun anEndedMembershipIsReadOnlyAndForgottenAfterConfirming() {
        messenger.isActive = false
        open()
        assertTrue("no composer for an ended membership", tagged("conversation.communityInactive"))
        assertFalse(tagged("composer"))
        manage()
        tap("Remove")
        assertNull(messenger.service.forgotten)
        confirm()
        assertEquals("owned", messenger.service.forgotten)
    }

    /** The conversation, its menu and its info, for review next to the iOS captures. */
    @Test fun communityConversationShots() {
        open()
        host.capture("communities_conversation")
        longPress("own1")
        host.capture("communities_actions")
        menu("info")
        host.capture("communities_info")
    }

    /** The review matrix (`UX_PHASE`): the conversation, its menu, info, the members sheet and the confirmations. */
    @Test fun uxScenes() {
        org.junit.Assume.assumeTrue(com.syconx.us2us.shot.UxMatrix.enabled)
        messenger.isOwner = true
        for (variant in com.syconx.us2us.shot.UxMatrix.variants) {
            fun show() {
                host.render(variant.theme, variant.lang, variant.options()) { ConversationScreen(messenger.chatId) }
                host.advance(600)
            }
            show()
            host.captureUx("community_conversation", variant)
            longPress("own1")
            host.captureUx("community_actions", variant)
            menu("deleteEveryone")
            host.captureUx("community_delete_everyone", variant)
            show()
            longPress("own1")
            menu("info")
            host.captureUx("community_info", variant)
            show()
            manage()
            host.captureUx("community_manage", variant)
            tapTag(CommunityManageTags.remove(DENIZ))
            host.captureUx("community_remove_confirm", variant)
        }
    }

    /** Interrupts the test thread once [seconds] have passed, so a test that never settles fails. */
    class Deadline(private val seconds: Long) : org.junit.rules.TestRule {
        override fun apply(base: org.junit.runners.model.Statement, description: org.junit.runner.Description) = object : org.junit.runners.model.Statement() {
            override fun evaluate() {
                val thread = Thread.currentThread()
                val timer = java.util.Timer(true)
                timer.schedule(object : java.util.TimerTask() { override fun run() = thread.interrupt() }, seconds * 1_000)
                try { base.evaluate() } finally { timer.cancel(); Thread.interrupted() }
            }
        }
    }

    /** The fixtures service with one community chat: its messages, recipients and management. */
    class CommunityMessenger(private val base: FixturesMessengerService) : MessengerService by base {
        val chatId: ChatId = CommunityChats.chatId("owned")
        var isOwner = false
        var isActive = true
        val service = ManageFixture()
        val sentStickers = mutableListOf<Pair<com.syconx.us2us.domain.sticker.BrandStickerSelection, ChatId>>()
        override suspend fun sendSticker(sticker: com.syconx.us2us.domain.sticker.BrandStickerSelection, replyTo: com.syconx.us2us.domain.QuotedMessage?, chat: ChatId) { sentStickers += sticker to chat }
        val deletedForEveryone = mutableListOf<MessageId>()
        val deletedForMe = mutableListOf<MessageId>()
        val recipientsAsked = mutableListOf<MessageId>()
        override val communities: CommunitiesService get() = service.also { it.owner = isOwner; it.active = isActive }

        private val at = Instant.parse("2026-10-14T14:00:00Z")
        private val deniz = CommunitySender(DENIZ, "deniz")
        private val unknown = CommunitySender("9f8e7d6c-5b4a-4321-8fed-cba987654321", null)
        private val self = CommunitySender("me", null)

        private val summary by lazy {
            MutableStateFlow<ChatSummary?>(
                ChatSummary(
                    id = chatId,
                    peer = Peer("Owned", AvatarPalette.C, isVerified = false, isAcceptedContact = false, presence = Presence.Hidden),
                    lastMessage = null, unreadCount = 0, ghost = GhostState.Off, disappearing = DisappearingTimer.Off,
                    community = CommunityChat("owned", "Owned", isPublic = false, memberCount = 3, isOwner = isOwner, isActive = isActive),
                ),
            )
        }

        private fun message(id: String, sender: CommunitySender, content: MessageContent, minute: Long, outgoing: Boolean = false, owner: Boolean = false) = Message(
            id = MessageId(id),
            direction = if (outgoing) MessageDirection.Outgoing else MessageDirection.Incoming,
            sentAt = at.plusSeconds(minute * 60),
            content = content,
            status = if (outgoing) MessageStatus.Delivered else null,
            community = CommunityMessageInfo(sender, removedByOwner = owner, canDeleteForEveryone = content != MessageContent.Deleted && isActive && (outgoing || isOwner)),
        )

        private val list by lazy {
            MutableStateFlow(
                listOf(
                    message("in1", deniz, MessageContent.Text("Merhaba topluluk"), 1),
                    message("in2", deniz, MessageContent.Text("Bu akşam buluşuyor muyuz?"), 2),
                    message("in3", unknown, MessageContent.Text("Ben de geliyorum"), 3),
                    message("gone1", deniz, MessageContent.Deleted, 4),
                    message("gone2", unknown, MessageContent.Deleted, 5, owner = true),
                    message("own1", self, MessageContent.Text("Saat yedide"), 6, outgoing = true),
                ),
            )
        }

        override fun chat(id: ChatId): StateFlow<ChatSummary?> = if (id == chatId) summary else base.chat(id)
        override fun messages(chat: ChatId): StateFlow<List<Message>> = if (chat == chatId) list else base.messages(chat)
        override suspend fun conversationAppeared(chat: ChatId) {}
        override fun conversationDisappeared(chat: ChatId) {}
        override suspend fun deleteForEveryone(message: MessageId, chat: ChatId) { deletedForEveryone += message }
        override suspend fun deleteForMe(message: MessageId, chat: ChatId) { deletedForMe += message }
        override suspend fun messageRecipients(message: MessageId, chat: ChatId): List<MessageRecipient> {
            recipientsAsked += message
            return listOf(
                MessageRecipient(deniz, MessageRecipient.State.Sent, deliveredAt = at.plusSeconds(60), readAt = at.plusSeconds(120)),
                MessageRecipient(CommunitySender("0a1b2c3d-4e5f-4061-8293-a4b5c6d7e8f9", null), MessageRecipient.State.Pending, null, null),
                MessageRecipient(CommunitySender("11111111-2222-4333-8444-555555555555", null), MessageRecipient.State.Failed, null, null),
                MessageRecipient(CommunitySender("21111111-2222-4333-8444-555555555555", null), MessageRecipient.State.Sent, at.plusSeconds(30), null, hasLeft = true),
            )
        }
    }

    class ManageFixture : CommunitiesService {
        override val unlocked = MutableStateFlow(true)
        var owner = false
        var active = true
        val identity: String = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(KEY)
        val applicationList = mutableListOf(CommunityApplication(APPLICANT, identity, 1, CommunityFormat.fingerprint(KEY)))
        var approved: String? = null
        var removed: String? = null
        var left: String? = null
        var forgotten: String? = null
        private val card get() = CommunityCard(
            "owned", "Owned", false, 3, if (owner) "me" else "other", "key", 1, active,
            listOf(
                CommunityMemberCard("me", isOwner = owner, isSelf = true),
                CommunityMemberCard(DENIZ, "deniz"),
                CommunityMemberCard("0a1b2c3d-4e5f-4061-8293-a4b5c6d7e8f9", isOwner = !owner),
            ),
            owner,
        )
        override suspend fun refresh() = CommunitySnapshot(listOf(card), true)
        override suspend fun search(name: String, after: String?) = CommunityPage(emptyList(), null)
        override suspend fun create(name: String, public: Boolean) = "new"
        override suspend fun request(group: CommunityCard) = Unit
        override suspend fun redeem(code: String) = Unit
        override suspend fun applications(id: String) = applicationList.toList()
        override suspend fun approve(id: String, application: CommunityApplication) {
            approved = application.account
            applicationList.removeAll { it.account == application.account }
        }
        override suspend fun reject(id: String, account: String) = Unit
        var rejectedAll: String? = null
        override suspend fun rejectAll(id: String) {
            rejectedAll = id
            applicationList.clear()
        }
        override suspend fun remove(id: String, account: String) { removed = account }
        override suspend fun invite(id: String) = "us2us-community:v1:invite"
        override suspend fun revokeInvites(id: String) = Unit
        override suspend fun delete(id: String) = Unit
        override suspend fun leave(id: String) { left = id }
        override suspend fun withdraw(id: String) = Unit
        override suspend fun forget(id: String) { forgotten = id }
    }

    private companion object {
        const val APPLICANT = "d5bb5a72-0301-484d-9aae-e17b80094f55"
        const val DENIZ = "4c3b2a19-8f7e-4d6c-9b5a-0e1f2a3b4c5d"
        val KEY: ByteArray = "05a77a12c630a9629a2b3971b478932d93a3f1d9aa4ae127966a9b1bc39d34467e"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
