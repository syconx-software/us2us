package com.syconx.us2us.feature.verification

import com.syconx.us2us.domain.ChatMediaKind
import com.syconx.us2us.domain.ConversationAttachment
import com.syconx.us2us.domain.DesignArtwork
import com.syconx.us2us.domain.DisappearingTimer
import com.syconx.us2us.domain.MediaAvailability
import com.syconx.us2us.domain.MediaFileKind
import com.syconx.us2us.domain.MediaSource
import com.syconx.us2us.domain.Message
import com.syconx.us2us.domain.MessageContent
import com.syconx.us2us.domain.MessageDirection
import com.syconx.us2us.domain.design.DesignFixtures
import com.syconx.us2us.domain.design.DesignMediaArchive
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.ContactTab
import com.syconx.us2us.domain.nav.MessageId
import com.syconx.us2us.fixtures.FixturesMessengerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The contact screen's tabs and lists (iOS ContactMediaTests: tabs and paging). */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactMediaModelTest {
    private fun TestScope.model(chat: String = "deniz"): Pair<ContactMediaModel, FixturesMessengerService> {
        val service = fixtures()
        return ContactMediaModel(ChatId(chat), service, backgroundScope as CoroutineScope) to service
    }

    private fun id(value: String) = MessageId(value)

    // MARK: Tabs

    @Test
    fun opensOnVerificationAndLoadsNothingYet() = runTest {
        val (model, _) = model()
        assertEquals(ContactTab.Verification, model.tab)
        assertEquals("Doğrulama first", listOf(ContactTab.Verification, ContactTab.Photos, ContactTab.Documents), ContactTab.entries)
        assertFalse(model.page(ChatMediaKind.Visual).hasLoaded)
        assertFalse(model.page(ChatMediaKind.Documents).hasLoaded)
        assertNull(model.gallery)
    }

    @Test
    fun aTabLoadsItsListTheFirstTimeItShows() = runTest {
        val (model, _) = model()
        model.show(ContactTab.Photos)
        assertEquals(ContactTab.Photos, model.tab)
        runCurrent()
        assertTrue(model.page(ChatMediaKind.Visual).hasLoaded)
        assertFalse("the other list waits until its tab shows", model.page(ChatMediaKind.Documents).hasLoaded)
        model.show(ContactTab.Documents)
        runCurrent()
        assertTrue(model.page(ChatMediaKind.Documents).hasLoaded)
        model.show(ContactTab.Verification)
        assertEquals(ContactTab.Verification, model.tab)
        assertNull(ContactMediaModel.mediaKind(ContactTab.Verification))
    }

    @Test
    fun eachTabListsOnlyItsKind() = runTest {
        val (model, _) = model("mert_k")
        model.loadFirstPageIfNeeded(ChatMediaKind.Visual)
        model.loadFirstPageIfNeeded(ChatMediaKind.Documents)
        val visual = model.items(ChatMediaKind.Visual)
        val documents = model.items(ChatMediaKind.Documents)
        // mert_k: the 5.2 video and 12 older photos and videos; never the view-once photo (m6) or the deleted message (m5).
        assertEquals(13, visual.size)
        assertTrue(visual.any { it.id == id("m2") })
        assertFalse("never view-once", visual.any { it.id == id("m6") })
        assertFalse("never a deleted message", visual.any { it.id == id("m5") })
        assertTrue(visual.all { it.content is MessageContent.Photo || it.content is MessageContent.Video })
        // The 5.2 PDF and 9 older files.
        assertEquals(10, documents.size)
        assertEquals("the newest first", id("m1"), documents.first().id)
        assertTrue(documents.all { it.content is MessageContent.File })
    }

    @Test
    fun aChatWithoutMediaShowsItsEmptyState() = runTest {
        val (model, _) = model("kaan")
        model.loadFirstPageIfNeeded(ChatMediaKind.Visual)
        model.loadFirstPageIfNeeded(ChatMediaKind.Documents)
        assertTrue(model.page(ChatMediaKind.Visual).hasLoaded)
        assertTrue(model.items(ChatMediaKind.Visual).isEmpty())
        assertFalse(model.page(ChatMediaKind.Visual).hasMore)
        assertTrue(model.items(ChatMediaKind.Documents).isEmpty())
    }

    @Test
    fun aTileOpensTheGalleryOverTheWholeGrid() = runTest {
        val (model, _) = model()
        model.loadFirstPageIfNeeded(ChatMediaKind.Visual)
        val items = model.items(ChatMediaKind.Visual)
        val tile = items[3]
        model.openVisual(tile)
        val gallery = model.gallery!!
        assertEquals(tile.id, gallery.startId)
        assertEquals(ContactMediaModel.GalleryItem.Pages.Visual, gallery.pages)
        assertEquals("swipes through the grid, in its order", items.map { it.id }, model.galleryPages(gallery).map { it.id })
        // An older item (not in the conversation) is still found for the viewer.
        assertEquals(tile.id, model.message(tile.id)?.id)
        model.closeGallery()
        assertNull(model.gallery)
    }

    @Test
    fun aVideoFileFromBelgelerPlaysAlone() = runTest {
        val (model, _) = model("mert_k")
        model.loadFirstPageIfNeeded(ChatMediaKind.Documents)
        val movie = model.items(ChatMediaKind.Documents).first { message ->
            val content = message.content as MessageContent.File
            MediaFileKind.of(content.name) == MediaFileKind.Movie
        }
        model.openDocument(movie)
        val gallery = model.gallery!!
        assertEquals(ContactMediaModel.GalleryItem.Pages.Single, gallery.pages)
        assertEquals(listOf(movie.id), model.galleryPages(gallery).map { it.id })
        assertNull(model.filePreview)
    }

    @Test
    fun aPdfOpensItsPreview() = runTest {
        val (model, _) = model("mert_k")
        model.loadFirstPageIfNeeded(ChatMediaKind.Documents)
        val pdf = model.items(ChatMediaKind.Documents).first()
        model.openDocument(pdf)
        assertEquals(pdf.id, model.filePreview)
        assertNull(model.gallery)
        // One at a time.
        model.openDocument(model.items(ChatMediaKind.Documents)[1])
        assertEquals(pdf.id, model.filePreview)
        model.closeFilePreview()
        assertNull(model.filePreview)
    }

    @Test
    fun anItemDeletedMeanwhileLeavesTheList() = runTest {
        val (model, service) = model()
        model.loadFirstPageIfNeeded(ChatMediaKind.Visual)
        assertTrue(model.items(ChatMediaKind.Visual).any { it.id == id("d2") })
        service.deleteForEveryone(id("d2"), ChatId("deniz"))
        assertFalse("a deleted photo goes from the grid at once", model.items(ChatMediaKind.Visual).any { it.id == id("d2") })
    }

    @Test
    fun theListShowsTheCurrentStateOfAnItem() = runTest {
        val (model, service) = model()
        model.loadFirstPageIfNeeded(ChatMediaKind.Visual)
        service.setMediaAvailability(MediaAvailability.Downloading(0.5), id("d2"), ChatId("deniz"))
        assertEquals(MediaAvailability.Downloading(0.5), model.items(ChatMediaKind.Visual).first { it.id == id("d2") }.mediaAvailability)
    }

    @Test
    fun disappearingMediaIsNeverListed() = runTest {
        val (model, service) = model()
        model.loadFirstPageIfNeeded(ChatMediaKind.Visual)
        assertTrue(model.items(ChatMediaKind.Visual).any { it.id == id("d2") })
        service.setDisappearing(DisappearingTimer.FiveMinutes, id("d2"), ChatId("deniz"))
        assertFalse("a list kept outside the chat never shows it (§8.1)", model.items(ChatMediaKind.Visual).any { it.id == id("d2") })
        model.reload(ChatMediaKind.Visual)
        assertFalse(model.page(ChatMediaKind.Visual).items.any { it.id == id("d2") })
        assertFalse(service.media(ChatId("deniz"), ChatMediaKind.Visual, null, 100).any { it.id == id("d2") })
    }

    @Test
    fun aRefreshReadsTheLoadedListsAgain() = runTest {
        val (model, service) = model()
        model.loadFirstPageIfNeeded(ChatMediaKind.Visual)
        model.loadMore(ChatMediaKind.Visual)
        val loaded = model.page(ChatMediaKind.Visual).items.size
        assertTrue("two pages", loaded > ContactMediaModel.PAGE_SIZE)
        // An older item (outside the open conversation) saved meanwhile.
        val older = model.page(ChatMediaKind.Visual).items.last()
        service.markSaved(older.id, ChatId("deniz"))
        assertFalse("the loaded copy is old", model.items(ChatMediaKind.Visual).last().savedByMe)
        model.refreshLoaded()
        assertEquals("as far as it was loaded", loaded, model.page(ChatMediaKind.Visual).items.size)
        assertTrue(model.items(ChatMediaKind.Visual).last().savedByMe)
        assertFalse("a list that never showed is not read", model.page(ChatMediaKind.Documents).hasLoaded)
    }

    @Test
    fun whatOpensClosesWithTheApp() = runTest {
        val (model, _) = model()
        model.loadFirstPageIfNeeded(ChatMediaKind.Visual)
        model.openVisual(model.items(ChatMediaKind.Visual).first())
        assertNotNull(model.gallery)
        model.closeMedia()
        assertNull(model.gallery)
        assertNull(model.filePreview)
    }

    @Test
    fun theViewersFindOlderItemsThroughTheContactScreen() = runTest {
        val (model, service) = model()
        model.loadFirstPageIfNeeded(ChatMediaKind.Visual)
        val older = model.items(ChatMediaKind.Visual).last()
        assertFalse(service.messages(ChatId("deniz")).value.any { it.id == older.id })
        val viewers = ContactMediaMessenger(service, ChatId("deniz"), model.loaded)
        val merged = viewers.messages(ChatId("deniz")).value
        assertTrue(merged.any { it.id == older.id })
        assertTrue("oldest first, as the conversation", merged.zipWithNext().all { (a, b) -> a.sentAt <= b.sentAt })
        assertEquals("other chats are untouched", service.messages(ChatId("mert_k")).value, viewers.messages(ChatId("mert_k")).value)
    }

    // MARK: Paging

    @Test
    fun pagesComeNewestFirstWithoutGapsOrRepeats() = runTest {
        val (model, _) = model()
        model.loadFirstPageIfNeeded(ChatMediaKind.Visual)
        assertEquals(ContactMediaModel.PAGE_SIZE, model.page(ChatMediaKind.Visual).items.size)
        assertTrue(model.page(ChatMediaKind.Visual).hasMore)
        assertEquals("the conversation's photo is the newest", id("d2"), model.page(ChatMediaKind.Visual).items.first().id)
        model.loadMore(ChatMediaKind.Visual)
        // deniz: 40 older photos and videos and the 5.1 photo.
        val items = model.page(ChatMediaKind.Visual).items
        assertEquals(41, items.size)
        assertEquals("no repeats", items.size, items.map { it.id }.toSet().size)
        assertTrue("newest first", items.zipWithNext().all { (a, b) -> a.sentAt >= b.sentAt })
        assertFalse("a short page is the last", model.page(ChatMediaKind.Visual).hasMore)
        model.loadMore(ChatMediaKind.Visual)
        assertEquals("nothing after the last page", 41, model.page(ChatMediaKind.Visual).items.size)
    }

    @Test
    fun theNextPageLoadsNearTheEndOnly() = runTest {
        val (model, _) = model()
        model.loadFirstPageIfNeeded(ChatMediaKind.Visual)
        val first = model.page(ChatMediaKind.Visual).items
        model.itemAppeared(first[3].id, ChatMediaKind.Visual)
        runCurrent()
        assertEquals("far from the end: nothing yet", ContactMediaModel.PAGE_SIZE, model.page(ChatMediaKind.Visual).items.size)
        model.itemAppeared(first[first.size - ContactMediaModel.PREFETCH_DISTANCE].id, ChatMediaKind.Visual)
        runCurrent()
        assertEquals(41, model.page(ChatMediaKind.Visual).items.size)
    }

    @Test
    fun aReloadStartsFromTheNewestAgain() = runTest {
        val (model, service) = model()
        model.loadFirstPageIfNeeded(ChatMediaKind.Visual)
        model.loadMore(ChatMediaKind.Visual)
        service.sendAttachment(ConversationAttachment.Photo(MediaSource.Artwork(DesignArtwork.SunsetVideo), null), ChatId("deniz"))
        model.reload(ChatMediaKind.Visual)
        val items = model.page(ChatMediaKind.Visual).items
        assertEquals(ContactMediaModel.PAGE_SIZE, items.size)
        assertTrue("the photo just sent leads", items.first().isOutgoing)
        assertTrue(model.page(ChatMediaKind.Visual).hasMore)
    }

    @Test
    fun overlappingPagesAreMergedOnce() {
        val now = DesignFixtures.date(day = 8, hour = 12, minute = 0)
        val a = Message(id("a"), MessageDirection.Incoming, now, MessageContent.Photo(MediaSource.Artwork(DesignArtwork.NightPier), null))
        val b = Message(id("b"), MessageDirection.Incoming, now, MessageContent.Photo(MediaSource.Artwork(DesignArtwork.NightPier), null))
        assertEquals(listOf(id("a"), id("b")), ContactMediaModel.unique(listOf(a, b, a, b)).map { it.id })
    }

    @Test
    fun thePageFunctionHonoursBeforeAndLimit() {
        val base = DesignFixtures.date(day = 8, hour = 12, minute = 0)
        val messages = (0 until 10).map { index ->
            Message(id("p$index"), MessageDirection.Incoming, base.plusSeconds(index * 60L), MessageContent.Photo(MediaSource.Artwork(DesignArtwork.NightPier), null))
        } + listOf(
            Message(id("text"), MessageDirection.Incoming, base, MessageContent.Text("selam")),
            Message(id("once"), MessageDirection.Incoming, base, MessageContent.ViewOnce(false, com.syconx.us2us.domain.ViewOnceState.Unopened)),
            Message(id("gone"), MessageDirection.Incoming, base, MessageContent.Deleted),
        )
        assertEquals(listOf("p9", "p8", "p7", "p6"), DesignMediaArchive.page(messages, ChatMediaKind.Visual, null, 4).map { it.id.value })
        assertEquals(listOf("p5", "p4", "p3", "p2"), DesignMediaArchive.page(messages, ChatMediaKind.Visual, id("p6"), 4).map { it.id.value })
        assertEquals(listOf("p1", "p0"), DesignMediaArchive.page(messages, ChatMediaKind.Visual, id("p2"), 4).map { it.id.value })
        assertTrue("a page after something gone is empty", DesignMediaArchive.page(messages, ChatMediaKind.Visual, id("missing"), 4).isEmpty())
        assertTrue(DesignMediaArchive.page(messages, ChatMediaKind.Documents, null, 4).isEmpty())
        assertTrue(DesignMediaArchive.page(messages, ChatMediaKind.Visual, null, 0).isEmpty())
    }
}
