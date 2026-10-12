package com.syconx.us2us.live

import com.syconx.us2us.domain.Bytes
import com.syconx.us2us.domain.DisappearingTimer
import com.syconx.us2us.domain.MediaAvailability
import com.syconx.us2us.domain.MediaDimensions
import com.syconx.us2us.domain.MediaSource
import com.syconx.us2us.domain.Message
import com.syconx.us2us.domain.MessageContent
import com.syconx.us2us.domain.MessageDirection
import com.syconx.us2us.domain.MessageStatus
import com.syconx.us2us.domain.PickedMediaItem
import com.syconx.us2us.domain.PickedMediaPlan
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.MessageId
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// 5.2 · photos and videos sent from “+”: every bubble is there at once (“Hazırlanıyor”, the poster
// and the length as soon as they are read), they are prepared one at a time and stored under the
// bubble's own id, a failure offers “Tekrar gönder”, and the picked copy goes as soon as it is no
// longer needed (the iOS OutgoingVideoTests and PickedMediaTests' live pipeline).

private class StepFailed : Exception()

/** A prepared file as the stand-in steps make it. */
private data class Prepared(val file: File)

/** Stand-ins for the runtime's steps, recording what was asked of them. */
private class Probe(open: Boolean = true) {
    var gate = CompletableDeferred<Unit>().also { if (open) it.complete(Unit) }
    var failing: Set<File> = emptySet()
    var failsSending = false
    var preparedBytes: ByteArray? = null
    val prepared = ArrayList<Triple<File, PickedMediaItem.Kind, String?>>()
    val sent = ArrayList<Pair<Bytes, ChatId>>()
    val discardedSources = ArrayList<File>()
    val discardedPrepared = ArrayList<File>()
    var inFlight = 0
    var mostInFlight = 0
    val folder: File = Files.createTempDirectory("picked-media").toFile()

    fun copy(name: String) = File(folder, name)

    fun open() = gate.complete(Unit)

    fun preview(kind: PickedMediaItem.Kind) = OutgoingMediaPreview(
        poster = Bytes.ofUnsigned(listOf(0xFF, 0xD8, if (kind == PickedMediaItem.Kind.Photo) 1 else 2)),
        duration = if (kind == PickedMediaItem.Kind.Photo) 0.seconds else 18.seconds,
        size = MediaDimensions(1_280, 720),
    )

    fun make(scope: TestScope, now: () -> Instant = { Instant.ofEpochSecond(1_791_000_000) }): OutgoingMedia<Prepared> {
        val steps = OutgoingMedia.Steps<Prepared>(
            preview = { _, kind -> preview(kind) },
            prepare = { file, kind, caption ->
                prepared += Triple(file, kind, caption)
                inFlight += 1
                mostInFlight = maxOf(mostInFlight, inFlight)
                try {
                    gate.await()
                    if (file in failing) throw StepFailed()
                    val out = File(file.path + ".prepared")
                    preparedBytes?.let { out.writeBytes(it) }
                    Prepared(out)
                } finally {
                    inFlight -= 1
                }
            },
            preparedFile = { it.file },
            send = { _, id, chat ->
                if (failsSending) throw StepFailed()
                sent += id to chat
            },
            discardSource = { discardedSources += it },
            discardPrepared = { discardedPrepared += it },
        )
        // Not the test's background scope: `advanceUntilIdle` runs only foreground work.
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        return OutgoingMedia(steps, CoroutineScope(SupervisorJob() + dispatcher), now, io = dispatcher)
    }
}

private val deniz = ChatId("deniz")

@OptIn(ExperimentalCoroutinesApi::class)
class OutgoingMediaTest {
    @Test
    fun theBubbleIsThereAtOnceAndTheStoredMessageTakesItsPlace() = runTest {
        val probe = Probe(open = false)
        val videos = probe.make(this)
        val handedOver = ArrayList<OutgoingMediaItem>()
        videos.onSent = { handedOver += it }
        val source = probe.copy("klip.mov")

        val id = videos.send(source, PickedMediaItem.Kind.Video, chat = deniz, disappearing = DisappearingTimer.FiveMinutes)

        // At once: an outgoing video, sending, being prepared.
        val bubble = videos.item(id)!!.message
        assertTrue(bubble.isOutgoing)
        assertEquals(MessageStatus.Sending, bubble.status)
        assertEquals(MediaAvailability.Preparing, bubble.mediaAvailability)
        assertTrue(bubble.isPendingMedia)
        assertEquals(DisappearingTimer.FiveMinutes, bubble.disappearing)
        assertEquals("an id the runtime can store the message under", 16, LiveMapping.messageData(id)?.size)
        // The poster and the length as soon as they are read.
        advanceUntilIdle()
        val read = videos.item(id)!!.message
        val video = read.content as MessageContent.Video
        assertEquals(MediaSource.Memory(probe.preview(PickedMediaItem.Kind.Video).poster!!), video.source)
        assertEquals(18.seconds, video.duration)
        assertEquals("the size is known once it is prepared", 0L, video.byteCount)
        assertEquals(MediaDimensions(1_280, 720), read.mediaSize)
        assertEquals(listOf(source), probe.prepared.map { it.first })
        assertTrue(probe.sent.isEmpty())

        probe.open()
        advanceUntilIdle()
        assertEquals(OutgoingMediaItem.Phase.Sent, videos.item(id)?.phase)
        assertEquals(listOf(LiveMapping.messageData(id)), probe.sent.map { it.first })
        assertEquals(listOf(deniz), probe.sent.map { it.second })
        assertEquals("the picked copy goes once the video is stored", listOf(source), probe.discardedSources)
        assertEquals(listOf(id), handedOver.map { it.id })

        // Until the stored message shows, the stand-in keeps the bubble; then the stored one is the bubble (same id, never two).
        assertEquals(listOf(id), OutgoingMedia.merge(videos.items(deniz), emptyList()).map { it.id })
        val stored = bubble.copy(mediaAvailability = MediaAvailability.Ready)
        val shown = OutgoingMedia.merge(videos.items(deniz), listOf(stored))
        assertEquals(listOf(id), shown.map { it.id })
        assertEquals(MediaAvailability.Ready, shown.first().mediaAvailability)
        videos.settle(listOf(stored))
        advanceUntilIdle()
        assertTrue(videos.items.value.isEmpty())
    }

    @Test
    fun aFailureOffersSendAgainFromTheSameCopy() = runTest {
        val probe = Probe()
        val source = probe.copy("klip.mov")
        probe.failing = setOf(source)
        val videos = probe.make(this)
        val id = videos.send(source, PickedMediaItem.Kind.Video, chat = deniz, disappearing = DisappearingTimer.Off)
        advanceUntilIdle()

        val failed = videos.item(id)!!.message
        assertEquals(OutgoingMediaItem.Phase.Failed, videos.item(id)?.phase)
        assertEquals(MessageStatus.Failed, failed.status)
        assertEquals(MediaAvailability.PreparationFailed, failed.mediaAvailability)
        assertTrue(failed.failedAt != null)
        assertTrue("the copy stays for “Tekrar gönder”", probe.discardedSources.isEmpty())
        assertTrue(probe.sent.isEmpty())

        probe.failing = emptySet()
        assertTrue(videos.resend(id))
        assertEquals(OutgoingMediaItem.Phase.Preparing, videos.item(id)?.phase)
        advanceUntilIdle()
        assertEquals(OutgoingMediaItem.Phase.Sent, videos.item(id)?.phase)
        assertEquals(listOf(source, source), probe.prepared.map { it.first })
        assertEquals(1, probe.sent.size)
        assertEquals(listOf(source), probe.discardedSources)
    }

    @Test
    fun aStoringFailureDropsThePreparedFile() = runTest {
        val probe = Probe()
        probe.failsSending = true
        val videos = probe.make(this)
        val source = probe.copy("klip.mov")
        val id = videos.send(source, PickedMediaItem.Kind.Video, chat = deniz, disappearing = DisappearingTimer.Off)
        advanceUntilIdle()
        assertEquals(OutgoingMediaItem.Phase.Failed, videos.item(id)?.phase)
        assertEquals(listOf(File(source.path + ".prepared")), probe.discardedPrepared)
        assertTrue(probe.discardedSources.isEmpty())
    }

    @Test
    fun removingItWhilePreparingSendsNothing() = runTest {
        val probe = Probe(open = false)
        val videos = probe.make(this)
        val source = probe.copy("klip.mov")
        val first = videos.send(source, PickedMediaItem.Kind.Video, chat = deniz, disappearing = DisappearingTimer.Off)
        val next = videos.send(probe.copy("ikinci.mov"), PickedMediaItem.Kind.Video, chat = deniz, disappearing = DisappearingTimer.Off)
        advanceUntilIdle()
        assertEquals(listOf(source), probe.prepared.map { it.first })

        assertTrue(videos.remove(first))
        assertNull(videos.item(first))
        assertEquals(listOf(source), probe.discardedSources)
        probe.open()
        advanceUntilIdle()
        assertTrue("removed while it was prepared: never sent", probe.sent.none { LiveMapping.messageId(it.first) == first })
        assertEquals("the next one takes its turn", OutgoingMediaItem.Phase.Sent, videos.item(next)?.phase)
    }

    @Test
    fun theBubbleWaitsForThePickersCopy() = runTest {
        val probe = Probe()
        val videos = probe.make(this)

        val id = videos.begin(PickedMediaItem.Kind.Video, chat = deniz, disappearing = DisappearingTimer.Off)
        val waiting = videos.item(id)!!
        assertNull(waiting.source)
        assertEquals(MediaAvailability.Preparing, waiting.message.mediaAvailability)
        advanceUntilIdle()
        assertTrue(probe.prepared.isEmpty())

        val source = probe.copy("klip.mov")
        videos.provide(source, id)
        advanceUntilIdle()
        assertEquals(OutgoingMediaItem.Phase.Sent, videos.item(id)?.phase)
        assertEquals(listOf(source), probe.prepared.map { it.first })

        // The picker could not hand one over: its bubble goes.
        val unreadable = videos.begin(PickedMediaItem.Kind.Video, chat = deniz, disappearing = DisappearingTimer.Off)
        videos.provide(null, unreadable)
        assertNull(videos.item(unreadable))

        // Removed before the copy came: the copy is not kept.
        val removed = videos.begin(PickedMediaItem.Kind.Video, chat = deniz, disappearing = DisappearingTimer.Off)
        assertTrue(videos.remove(removed))
        val late = probe.copy("geç.mov")
        videos.provide(late, removed)
        advanceUntilIdle()
        assertTrue(late in probe.discardedSources)
        assertFalse(probe.prepared.any { it.first == late })
    }

    @Test
    fun aFailedItemIsGivenUpInTheBackground() = runTest {
        val probe = Probe()
        val videos = probe.make(this)
        val failedSource = probe.copy("1.mov")
        val awaySource = probe.copy("2.mov")
        val backSource = probe.copy("3.mov")
        probe.failing = setOf(failedSource, awaySource, backSource)
        val failed = videos.send(failedSource, PickedMediaItem.Kind.Video, chat = deniz, disappearing = DisappearingTimer.Off)
        advanceUntilIdle()
        assertEquals(OutgoingMediaItem.Phase.Failed, videos.item(failed)?.phase)

        videos.didEnterBackground()
        assertNull(videos.item(failed))
        assertEquals(listOf(failedSource), probe.discardedSources)

        // Failing while the app is away: given up at once.
        val away = videos.send(awaySource, PickedMediaItem.Kind.Video, chat = deniz, disappearing = DisappearingTimer.Off)
        advanceUntilIdle()
        assertNull(videos.item(away))
        assertTrue(awaySource in probe.discardedSources)

        videos.didBecomeActive()
        val back = videos.send(backSource, PickedMediaItem.Kind.Video, chat = deniz, disappearing = DisappearingTimer.Off)
        advanceUntilIdle()
        assertEquals("in the foreground it waits for “Tekrar gönder”", OutgoingMediaItem.Phase.Failed, videos.item(back)?.phase)
    }

    @Test
    fun eraseStopsEverythingAndTheCopiesGo() = runTest {
        val probe = Probe(open = false)
        val videos = probe.make(this)
        val first = probe.copy("1.mov")
        val second = probe.copy("2.mov")
        videos.send(first, PickedMediaItem.Kind.Video, chat = deniz, disappearing = DisappearingTimer.Off)
        videos.send(second, PickedMediaItem.Kind.Video, chat = ChatId("mert_k"), disappearing = DisappearingTimer.Off)
        advanceUntilIdle()
        assertEquals("one at a time: the second waits for its turn", listOf(first), probe.prepared.map { it.first })
        videos.removeAll()
        assertTrue(videos.items.value.isEmpty())
        assertEquals(setOf(first, second), probe.discardedSources.toSet())
        probe.open()
        advanceUntilIdle()
        assertEquals("the second never started", listOf(first), probe.prepared.map { it.first })
        assertTrue(probe.sent.isEmpty())
    }

    @Test
    fun messagesOfTheRuntimeAreLeftToTheRuntime() = runTest {
        val probe = Probe()
        val videos = probe.make(this)
        val stranger = MessageId("00112233445566778899aabbccddeeff")
        assertFalse(videos.resend(stranger))
        assertFalse(videos.remove(stranger))
        val id = videos.send(probe.copy("klip.mov"), PickedMediaItem.Kind.Video, chat = deniz, disappearing = DisappearingTimer.Off)
        advanceUntilIdle()
        assertFalse("stored: “Tekrar gönder” is the runtime's", videos.resend(id))
        assertFalse("stored: the runtime deletes it", videos.remove(id))
        assertNull(videos.item(id))
    }

    @Test
    fun eachBubbleSitsWherePickedAndOnlyOnce() {
        val start = Instant.ofEpochSecond(1_791_000_000)
        val early = Message(MessageId("a"), MessageDirection.Incoming, start, MessageContent.Text("bir"))
        val late = Message(MessageId("b"), MessageDirection.Incoming, start.plusSeconds(120), MessageContent.Text("iki"))
        val picked = OutgoingMediaItem(Bytes.of(ByteArray(16) { 7 }), deniz, start.plusSeconds(60), DisappearingTimer.Off, PickedMediaItem.Kind.Video)
        assertEquals(listOf(MessageId("a"), picked.id, MessageId("b")), OutgoingMedia.merge(listOf(picked), listOf(early, late)).map { it.id })
        assertEquals(listOf(picked.id), OutgoingMedia.merge(listOf(picked), emptyList()).map { it.id })
        val stored = Message(picked.id, MessageDirection.Outgoing, start.plusSeconds(90), MessageContent.Text("x"))
        assertEquals(listOf(MessageId("a"), picked.id), OutgoingMedia.merge(listOf(picked), listOf(early, stored)).map { it.id })
        assertEquals(listOf(early), OutgoingMedia.merge(emptyList(), listOf(early)))
    }

    // MARK: Several at once (the pick)

    private fun begin(media: OutgoingMedia<Prepared>, kinds: List<PickedMediaItem.Kind>, caption: String?): List<MessageId> {
        val items = kinds.map { PickedMediaItem(it, PickedMediaItem.Source.Copy(CompletableDeferred(null))) }
        return PickedMediaPlan.messages(items, caption).map { media.begin(it.item.kind, it.caption, deniz, DisappearingTimer.Off) }
    }

    private val photo = PickedMediaItem.Kind.Photo
    private val video = PickedMediaItem.Kind.Video

    @Test
    fun everyBubbleIsThereAtOnceAndTheyAreSentOneAtATimeInOrder() = runTest {
        val probe = Probe(open = false)
        val media = probe.make(this)
        val ids = begin(media, listOf(photo, video, photo, photo), "Moda'dan")
        val copies = listOf("1.heic", "2.mov", "3.heic", "4.jpg").map(probe::copy)

        // At once, in the order selected: outgoing, sending, being prepared.
        val shown = OutgoingMedia.merge(media.items(deniz), emptyList())
        assertEquals(ids, shown.map { it.id })
        assertTrue(shown.all { it.isOutgoing && it.status == MessageStatus.Sending && it.mediaAvailability == MediaAvailability.Preparing })
        assertEquals(listOf("Moda'dan", null, null, null), shown.map { (it.content as? MessageContent.Photo)?.caption })
        assertEquals("each its own place, even with a clock that stands still", 4, shown.map { it.sentAt }.toSet().size)

        // The copies come back in any order: nothing starts before the first one's.
        for (index in listOf(3, 2, 1)) media.provide(copies[index], ids[index])
        advanceUntilIdle()
        assertTrue("the first item picked goes first", probe.prepared.isEmpty())
        media.provide(copies[0], ids[0])
        advanceUntilIdle()
        assertEquals("one at a time", listOf(copies[0]), probe.prepared.map { it.first })

        probe.open()
        advanceUntilIdle()
        assertEquals("stored in the order selected", ids, probe.sent.map { LiveMapping.messageId(it.first) })
        assertEquals(copies, probe.prepared.map { it.first })
        assertEquals(listOf(photo, video, photo, photo), probe.prepared.map { it.second })
        assertEquals("the caption on the first photo only", listOf("Moda'dan", null, null, null), probe.prepared.map { it.third })
        assertEquals("never two prepared at the same time", 1, probe.mostInFlight)
        assertEquals("each copy goes once its item is stored", copies.toSet(), probe.discardedSources.toSet())
        assertTrue(ids.all { media.item(it)?.phase == OutgoingMediaItem.Phase.Sent })
    }

    @Test
    fun aFailingItemDoesNotStopTheRest() = runTest {
        val probe = Probe()
        val media = probe.make(this)
        val ids = begin(media, listOf(photo, photo, photo, photo), null)
        val copies = listOf("1.jpg", "2.jpg", "3.jpg", "4.jpg").map(probe::copy)
        probe.failing = setOf(copies[1])
        ids.zip(copies).forEach { (id, copy) -> media.provide(copy, id) }
        advanceUntilIdle()

        assertEquals(listOf(ids[0], ids[2], ids[3]), probe.sent.map { LiveMapping.messageId(it.first) })
        val failed = media.item(ids[1])!!.message
        assertEquals(MessageStatus.Failed, failed.status)
        assertEquals(MediaAvailability.PreparationFailed, failed.mediaAvailability)
        assertFalse("its copy stays for “Tekrar gönder”", copies[1] in probe.discardedSources)

        probe.failing = emptySet()
        assertTrue(media.resend(ids[1]))
        advanceUntilIdle()
        assertEquals(listOf(ids[0], ids[2], ids[3], ids[1]), probe.sent.map { LiveMapping.messageId(it.first) })
        assertEquals(listOf(copies[0], copies[1], copies[2], copies[3], copies[1]), probe.prepared.map { it.first })
        assertEquals(1, probe.mostInFlight)
    }

    @Test
    fun anItemThePickerCouldNotHandOverDoesNotHoldUpTheRest() = runTest {
        val probe = Probe()
        val media = probe.make(this)
        val ids = begin(media, listOf(photo, photo, photo), null)
        media.provide(probe.copy("3.jpg"), ids[2])
        media.provide(null, ids[1])
        assertNull("nothing to send again: its bubble goes", media.item(ids[1]))
        media.provide(probe.copy("1.jpg"), ids[0])
        advanceUntilIdle()
        assertEquals(listOf(ids[0], ids[2]), probe.sent.map { LiveMapping.messageId(it.first) })
    }

    @Test
    fun removingAWaitingItemSkipsIt() = runTest {
        val probe = Probe(open = false)
        val media = probe.make(this)
        val ids = begin(media, listOf(photo, photo, photo), null)
        val copies = listOf("1.jpg", "2.jpg", "3.jpg").map(probe::copy)
        ids.zip(copies).forEach { (id, copy) -> media.provide(copy, id) }
        advanceUntilIdle()

        assertTrue(media.remove(ids[1]))
        assertEquals(listOf(copies[1]), probe.discardedSources)
        probe.open()
        advanceUntilIdle()
        assertEquals(listOf(ids[0], ids[2]), probe.sent.map { LiveMapping.messageId(it.first) })
        assertFalse(probe.prepared.any { it.first == copies[1] })
    }

    @Test
    fun aLaterItemNeverShowsAboveOneStoredBeforeIt() = runTest {
        val probe = Probe()
        val start = Instant.ofEpochSecond(1_791_000_000)
        val media = probe.make(this) { start }
        val ids = begin(media, listOf(photo, photo), null)
        probe.failing = setOf(probe.copy("2.jpg"))
        media.provide(probe.copy("1.jpg"), ids[0])
        media.provide(probe.copy("2.jpg"), ids[1])
        advanceUntilIdle()
        assertEquals(OutgoingMediaItem.Phase.Failed, media.item(ids[1])?.phase)
        assertEquals(start, media.landed[ids[0]])

        // The first is stored now, later than the second was picked (a stored message sits where it was stored).
        val earlier = Message(MessageId("a"), MessageDirection.Incoming, start.minusSeconds(60), MessageContent.Text("bir"))
        val stored = Message(ids[0], MessageDirection.Outgoing, start.plusSeconds(5), MessageContent.Text("x"), status = MessageStatus.Sending)
        val pending = media.items(deniz)
        assertEquals(listOf(MessageId("a"), ids[0], ids[1]), OutgoingMedia.merge(pending, listOf(earlier, stored), media.landed).map { it.id })
        assertEquals(
            "without knowing when the first was picked, the second would jump above it",
            listOf(MessageId("a"), ids[1], ids[0]),
            OutgoingMedia.merge(pending, listOf(earlier, stored)).map { it.id },
        )

        // Nothing waits any more: forgotten.
        assertTrue(media.remove(ids[1]))
        media.settle(listOf(earlier, stored))
        advanceUntilIdle()
        assertTrue(media.items.value.isEmpty())
        assertTrue(media.landed.isEmpty())
    }

    @Test
    fun aSentPhotoKeepsThePhotoAsItWasSent() = runTest {
        val probe = Probe()
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 7, 7, 7)
        probe.preparedBytes = bytes
        val media = probe.make(this)
        val handedOver = ArrayList<OutgoingMediaItem>()
        media.onSent = { handedOver += it }
        val id = media.send(probe.copy("1.heic"), photo, caption = "Moda", chat = deniz, disappearing = DisappearingTimer.Off)
        advanceUntilIdle()

        val item = media.item(id)!!
        assertEquals(Bytes.of(bytes), item.sentPhoto)
        assertEquals("the stored bubble and the viewer get the photo as sent, not its preview", Bytes.of(bytes), item.keptImage)
        val content = item.message.content as MessageContent.Photo
        assertEquals(MediaSource.Memory(Bytes.of(bytes)), content.source)
        assertEquals("Moda", content.caption)
        assertEquals(listOf(Bytes.of(bytes)), handedOver.map { it.keptImage })

        // A video keeps its poster; a video carries no caption.
        val clip = media.send(probe.copy("2.mov"), video, caption = "yok", chat = deniz, disappearing = DisappearingTimer.Off)
        advanceUntilIdle()
        assertNull(media.item(clip)?.caption)
        assertEquals(media.item(clip)?.poster, media.item(clip)?.keptImage)
    }

    @Test
    fun theConversationClosingDropsStoredStandIns() = runTest {
        val probe = Probe()
        val media = probe.make(this)
        val stored = media.send(probe.copy("1.jpg"), photo, chat = deniz, disappearing = DisappearingTimer.Off)
        advanceUntilIdle()
        assertEquals(OutgoingMediaItem.Phase.Sent, media.item(stored)?.phase)
        media.conversationClosed(deniz)
        assertNull(media.item(stored))
        assertTrue(media.landed.isEmpty())
    }
}
