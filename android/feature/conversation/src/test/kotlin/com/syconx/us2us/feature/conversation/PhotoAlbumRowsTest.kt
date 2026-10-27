package com.syconx.us2us.feature.conversation

import com.syconx.us2us.domain.*
import com.syconx.us2us.domain.nav.MessageId
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoAlbumRowsTest {
    private fun photo(id: Int) = Message(MessageId("p$id"), MessageDirection.Incoming,
        Instant.ofEpochSecond(1_000L + id), MessageContent.Photo(MediaSource.Artwork(DesignArtwork.NightPier), null))

    @Test fun removingAChildKeepsTheAlbumIdentityAndDoesNotAnimateAStandalonePhoto() {
        val rows = ConversationRows.rows((0..2).map(::photo))
        val removed = ConversationRows.albumRemovals(rows, setOf(MessageId("p1")))
        assertEquals(setOf(MessageId("p1")), removed)
        val remaining = ConversationRows.albums(rows.filterNot { it.id in removed })
        assertEquals(1, remaining.size)
        assertEquals(MessageId("p0"), remaining.single().id)
        assertEquals(listOf("p0", "p2"), remaining.single().album.map { it.id.value })
        val single = ConversationRows.rows(listOf(photo(0)))
        assertTrue(ConversationRows.albumRemovals(single, setOf(MessageId("p0"))).isEmpty())
    }
}
