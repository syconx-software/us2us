package com.syconx.us2us.domain

import com.syconx.us2us.domain.nav.MessageId
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class ConversationPhotosTest {
    private fun photo(id: Int, seconds: Long = id.toLong(), caption: String? = null) = Message(
        MessageId("p$id"), MessageDirection.Outgoing, Instant.ofEpochSecond(1_000 + seconds),
        MessageContent.Photo(MediaSource.Artwork(DesignArtwork.NightPier), caption), status = MessageStatus.Sent)

    @Test fun `a photo burst keeps every ID in one group and only its first caption`() {
        val messages = listOf(photo(0, caption = "Caption"), photo(1), photo(2))
        assertEquals(listOf(messages), ConversationPhotos.groups(messages))
        assertEquals(listOf(3, 1), ConversationPhotos.groups(messages + photo(3, caption = "Another")).map { it.size })
    }

    @Test fun `albums stop at ten and sixty seconds without chaining unbounded bursts`() {
        assertEquals(listOf(10, 2), ConversationPhotos.groups((0..11).map { photo(it) }).map { it.size })
        assertEquals(listOf(2, 1), ConversationPhotos.groups(listOf(photo(0, 0), photo(1, 60), photo(2, 61))).map { it.size })
        assertFalse(ConversationPhotos.canAppend(listOf(photo(0, 10)), photo(1, 9)))
    }

    @Test fun `different senders directions and intervening text split albums`() {
        val first = photo(0).copy(community = CommunityMessageInfo(CommunitySender("alice", "alice")))
        val second = photo(1).copy(community = CommunityMessageInfo(CommunitySender("bob", "bob")))
        assertEquals(2, ConversationPhotos.groups(listOf(first, second)).size)
        assertEquals(2, ConversationPhotos.groups(listOf(photo(0), photo(1).copy(direction = MessageDirection.Incoming))).size)
        val text = photo(9).copy(content = MessageContent.Text("Between"))
        assertEquals(3, ConversationPhotos.groups(listOf(photo(0), text, photo(1))).size)
    }

    @Test fun `privacy and failure marks are never concealed by grouping`() {
        val special = listOf(
            photo(1).copy(disappearing = DisappearingTimer.ThirtySeconds),
            photo(1).copy(savedByPeer = true),
            photo(1).copy(status = MessageStatus.Failed),
            photo(1).copy(mediaAvailability = MediaAvailability.Preparing),
            photo(1).copy(mediaAvailability = MediaAvailability.PreparationFailed),
        )
        for (message in special) assertEquals(listOf(1, 1, 1), ConversationPhotos.groups(listOf(photo(0), message, photo(2))).map { it.size })
    }

    @Test fun `gallery contains ordinary photos only and skips media that cannot yet open`() {
        val candidates = listOf(photo(0), photo(1).copy(content = MessageContent.Deleted),
            photo(2).copy(content = MessageContent.Text("No")), photo(3).copy(mediaAvailability = MediaAvailability.Preparing),
            photo(4).copy(mediaAvailability = MediaAvailability.Remote), photo(5).copy(mediaAvailability = MediaAvailability.Unavailable))
        assertEquals(listOf("p0", "p4", "p5"), candidates.filter(ConversationPhotos::isBrowsable).map { it.id.value })
    }

    @Test fun `read status requires every album member to be read`() {
        assertEquals(MessageStatus.Sent, ConversationPhotos.status(listOf(photo(0).copy(status = MessageStatus.Read), photo(1))))
        assertEquals(MessageStatus.Read, ConversationPhotos.status(listOf(photo(0).copy(status = MessageStatus.Read), photo(1).copy(status = MessageStatus.Read))))
    }
}
