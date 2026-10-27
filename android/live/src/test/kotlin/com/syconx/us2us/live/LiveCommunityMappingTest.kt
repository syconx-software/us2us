package com.syconx.us2us.live

import com.google.protobuf.ByteString
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.proto.Attachment
import com.syconx.us2us.domain.AvatarPalette
import com.syconx.us2us.domain.ChatSummary
import com.syconx.us2us.domain.CommunityChats
import com.syconx.us2us.domain.DisappearingTimer
import com.syconx.us2us.domain.GhostState
import com.syconx.us2us.domain.MediaAvailability
import com.syconx.us2us.domain.MessageContent
import com.syconx.us2us.domain.MessageDirection
import com.syconx.us2us.domain.MessagePreview
import com.syconx.us2us.domain.MessagePreviewKind
import com.syconx.us2us.domain.MessageRecipient
import com.syconx.us2us.domain.MessageStatus
import com.syconx.us2us.domain.Peer
import com.syconx.us2us.domain.Presence
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.MessageId
import com.syconx.us2us.runtime.storage.CommunityDeletion
import com.syconx.us2us.runtime.storage.CommunityDeliverySummary
import com.syconx.us2us.runtime.storage.CommunityMediaState
import com.syconx.us2us.runtime.storage.CommunityRecipient
import com.syconx.us2us.runtime.storage.StoredCommunityMessage
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Communities onto the screens: the mixed chat list, the aggregate state and its mutual READ rule, the 5.4 list. */
class LiveCommunityMappingTest {
    private val own = id()
    private val deniz = id()
    private val other = id()
    private val group = id()
    private val community = LiveCommunityRecord(group, "Kitap", true, listOf(own, deniz, other), owner = own, active = true, names = mapOf(deniz to "deniz"))

    @Test fun stickersAndVoiceUseTheirExistingBubblesAndPreviewKinds() {
        val sticker = com.syconx.us2us.core.proto.Sticker.newBuilder().setPackId("us2us.cups").setStickerId(1).setEmoji("👋").build()
        val s = stored(other, 1, text = "👋").copy(sticker = sticker)
        assertEquals(MessageContent.Sticker("us2us.cups", 1, "👋", ""), LiveCommunityMapping.message(community, s, own, true).single().content)
        assertEquals(MessagePreviewKind.Sticker, LiveCommunityMapping.preview(community, s, own, true).kind)
        val a = Attachment.newBuilder().setKind(Attachment.Kind.VOICE).setDurationMs(2000).setWaveform(ByteString.copyFrom(byteArrayOf(0, 127, -1))).build()
        val voice = stored(other, 2, text = "", attachment = a, media = CommunityMediaState.REMOTE)
        val message = LiveCommunityMapping.message(community, voice, own, true).single()
        assertTrue(message.content is MessageContent.Voice)
        assertEquals(MediaAvailability.Remote, message.mediaAvailability)
        assertEquals(MessagePreviewKind.Voice, LiveCommunityMapping.preview(community, voice, own, true).kind)
    }

    private fun id() = AccountId(UUID.randomUUID())

    private fun stored(sender: AccountId, at: Long, text: String = "x", status: String = "received", delivery: CommunityDeliverySummary? = null,
        attachment: Attachment? = null, media: CommunityMediaState? = null, deleted: CommunityDeletion? = null) =
        StoredCommunityMessage(group, sender, id(), at, text, status, attachment, media, deleted, receivedAt = at, delivery = delivery)

    private fun chat(name: String, at: Long?) = ChatSummary(
        ChatId(name), Peer(name, AvatarPalette.A, false, true, Presence.Hidden),
        at?.let { MessagePreview(MessagePreviewKind.Text("t"), MessageDirection.Incoming, null, Instant.ofEpochMilli(it)) },
        0, GhostState.Off, DisappearingTimer.Off,
    )

    private fun groupRow(at: Long?): ChatSummary {
        val record = community.copy(id = id())
        return LiveCommunityMapping.summary(record, at?.let { stored(deniz, it).copy(communityId = record.id) }, 0, own, true)
    }

    @Test fun communitiesAndDirectChatsShareLatestMessageOrder() {
        val a = chat("a", 500)
        val b = chat("b", 300)
        val c = chat("c", null)
        val newest = groupRow(900)
        val middle = groupRow(400)
        val oldest = groupRow(100)
        val silent = groupRow(null)
        val mixed = LiveCommunityMapping.mixed(listOf(a, b, c), listOf(oldest, silent, middle, newest))
        assertEquals(listOf(newest.id, a.id, middle.id, b.id, oldest.id, c.id, silent.id), mixed.map { it.id })
        assertEquals("no communities: the chats as they were", listOf(a, b), LiveCommunityMapping.mixed(listOf(a, b), emptyList()))
    }

    @Test fun unsortedSourcesAndEmptyChatsAreOrderedGloballyEvenWithoutCommunities() {
        val empty = chat("empty", null)
        val old = chat("old", 100)
        val recent = chat("recent", 500)
        val middle = groupRow(300)
        val newest = groupRow(900)
        val silent = groupRow(null)
        val direct = listOf(empty, old, recent)
        val groups = listOf(middle, silent, newest)
        assertEquals(listOf(newest.id, recent.id, middle.id, old.id, empty.id, silent.id), LiveCommunityMapping.mixed(direct, groups).map { it.id })
        assertEquals(listOf(recent.id, old.id, empty.id), LiveCommunityMapping.mixed(direct, emptyList()).map { it.id })
        assertEquals(listOf(newest.id, middle.id, silent.id), LiveCommunityMapping.mixed(emptyList(), groups).map { it.id })
    }

    @Test fun localMessageActivityWinsOverSenderClockAndUnreadCountsWhileTiesStayStable() {
        val old = chat("old", 9000).copy(lastMessageActivity = Instant.ofEpochMilli(100), unreadCount = 99)
        val recent = chat("recent", 10).let { it.copy(lastMessageActivity = Instant.ofEpochMilli(500), lastMessage = it.lastMessage!!.copy(kind = MessagePreviewKind.Voice, direction = MessageDirection.Outgoing)) }
        val middle = groupRow(300)
        assertEquals(listOf(recent.id, middle.id, old.id), LiveCommunityMapping.mixed(listOf(old, recent), listOf(middle)).map { it.id })
        val tie = chat("tie", 500)
        assertEquals(listOf(recent.id, tie.id), LiveCommunityMapping.mixed(listOf(recent, tie), emptyList()).map { it.id })
        val next = groupRow(600)
        assertEquals(next.id, LiveCommunityMapping.mixed(listOf(old, recent), listOf(next)).first().id)
    }

    @Test fun aRowShowsNameSenderUnreadAndKindPreviews() {
        val photo = Attachment.newBuilder().setKind(Attachment.Kind.IMAGE).setMediaId(ByteString.copyFrom(ByteArray(16))).build()
        val row = LiveCommunityMapping.summary(community, stored(deniz, 1000, text = "Gün batımı", attachment = photo, media = CommunityMediaState.REMOTE), 3, own, true)
        assertEquals(CommunityChats.chatId(group.toString()), row.id)
        assertEquals("Kitap", row.community!!.name)
        assertTrue(row.community!!.isOwner)
        assertEquals(3, row.unreadCount)
        val preview = row.lastMessage!!
        assertEquals(MessagePreviewKind.Photo, preview.kind)
        assertEquals("Gün batımı", preview.caption)
        assertEquals("deniz", preview.sender!!.username)
        val removed = LiveCommunityMapping.preview(community, stored(other, 1, text = "", deleted = CommunityDeletion.OWNER), own, true)
        assertEquals(MessagePreviewKind.Deleted, removed.kind)
        assertTrue(removed.removedByOwner)
        assertNull("own messages carry their state, not a sender", LiveCommunityMapping.preview(community, stored(own, 1, status = "sent"), own, true).sender)
    }

    @Test fun ownMessagesAggregateWithTheOneToOneStatesAndReadOnlyWhileReadReceiptsAreOn() {
        fun status(status: String, delivery: CommunityDeliverySummary?, showsRead: Boolean = true) =
            LiveCommunityMapping.status(stored(own, 1, status = status, delivery = delivery), showsRead)
        assertEquals(MessageStatus.Sending, status("preparing", null))
        assertEquals(MessageStatus.Sending, status("partial", CommunityDeliverySummary(2, 1, 0, 0, 0)))
        assertEquals(MessageStatus.Failed, status("stale", CommunityDeliverySummary(2, 1, 0, 0, 1)))
        assertEquals(MessageStatus.Sent, status("sent", CommunityDeliverySummary(2, 2, 1, 1, 0)))
        assertEquals(MessageStatus.Delivered, status("sent", CommunityDeliverySummary(2, 2, 2, 1, 0)))
        assertEquals(MessageStatus.Read, status("sent", CommunityDeliverySummary(2, 2, 2, 2, 0)))
        assertEquals("read receipts off: never Read", MessageStatus.Delivered, status("sent", CommunityDeliverySummary(2, 2, 2, 2, 0), showsRead = false))
        assertEquals("an older message without a recipient set", MessageStatus.Sent, status("sent", null))
    }

    @Test fun messagesUseTheOneToOneBubblesWithSenderAndPlaceholders() {
        val file = Attachment.newBuilder().setKind(Attachment.Kind.FILE).setFileName("plan.pdf").setSize(5000).build()
        val list = LiveCommunityMapping.messages(
            community,
            listOf(
                stored(deniz, 1, text = "merhaba"),
                stored(other, 2, text = "notlar", attachment = file, media = CommunityMediaState.REMOTE),
                stored(other, 3, text = "", deleted = CommunityDeletion.SENDER),
                stored(own, 4, status = "sent"),
            ),
            own, showsRead = true,
        )
        assertEquals(5, list.size)
        assertEquals("deniz", list[0].community!!.sender.username)
        assertTrue("the owner may delete anyone's message", list[0].community!!.canDeleteForEveryone)
        assertEquals(MessageContent.File("plan.pdf", 5000, "PDF", null), list[1].content)
        assertEquals(MediaAvailability.Remote, list[1].mediaAvailability)
        assertEquals("a file's caption follows as a text", MessageContent.Text("notlar"), list[2].content)
        assertEquals(LiveCommunityMapping.key(own, list[1].id), LiveCommunityMapping.key(own, list[2].id))
        assertEquals(MessageContent.Deleted, list[3].content)
        assertFalse(list[3].community!!.removedByOwner)
        assertFalse("a placeholder is not deleted again", list[3].community!!.canDeleteForEveryone)
        assertEquals(MessageDirection.Outgoing, list[4].direction)
        val member = community.copy(owner = other)
        assertFalse("a member deletes only its own", LiveCommunityMapping.message(member, stored(deniz, 1), own, true).single().community!!.canDeleteForEveryone)
        assertTrue(LiveCommunityMapping.message(member, stored(own, 1, status = "sent"), own, true).single().community!!.canDeleteForEveryone)
    }

    @Test fun bubbleIdsRoundTripToTheirKey() {
        val messageId = id()
        assertEquals(own to messageId, LiveCommunityMapping.key(own, LiveCommunityMapping.messageId(own, own, messageId)))
        assertEquals(deniz to messageId, LiveCommunityMapping.key(own, LiveCommunityMapping.messageId(own, deniz, messageId)))
        assertNull(LiveCommunityMapping.key(own, MessageId("deniz")))
        assertNull(LiveCommunityMapping.community(ChatId("deniz")))
    }

    @Test fun infoListsEveryRecipientHidingReadWhileReadReceiptsAreOff() {
        val left = id()
        val recipients = listOf(
            CommunityRecipient(deniz, CommunityRecipient.ACCEPTED, 10, 20),
            CommunityRecipient(other, CommunityRecipient.PENDING, null, null),
            CommunityRecipient(left, CommunityRecipient.SKIPPED, 5, null),
        )
        val shown = LiveCommunityMapping.recipients(community, recipients, showsRead = true)
        assertEquals(listOf(deniz.toString(), other.toString(), left.toString()), shown.map { it.member.account })
        assertEquals(Instant.ofEpochMilli(20), shown[0].readAt)
        assertEquals(MessageRecipient.State.Pending, shown[1].state)
        assertTrue(shown[2].hasLeft)
        val hidden = LiveCommunityMapping.recipients(community, recipients, showsRead = false)
        assertNull("READ is not shown while read receipts are off", hidden[0].readAt)
        assertEquals(Instant.ofEpochMilli(10), hidden[0].deliveredAt)
    }
}
