package com.syconx.us2us.live

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
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
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.MessageId
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom
import java.time.Instant
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A photo or video picked in “+” on its way out, as its bubble shows it (5.2, proposed “Hazırlanıyor”). */
data class OutgoingMediaItem(
    /** The id the runtime stores the message under (16 random bytes). */
    val messageData: Bytes,
    val chat: ChatId,
    /** When it was picked: the bubble's place in the conversation. The items of one pick are a millisecond apart, in the order selected. */
    val sentAt: Instant,
    val disappearing: DisappearingTimer,
    val kind: PickedMediaItem.Kind,
    /** A photo's caption (the composer's text, on the first photo of a pick). */
    val caption: String? = null,
    /** The picked copy ([SystemPickers]); `null` while the picker is still handing it over. */
    val source: File? = null,
    /** A video's first frame, or a photo's preview at the bubble's size: JPEG, in memory only. */
    val poster: Bytes? = null,
    /** A photo as it was sent (the prepared JPEG, ≤ 2048 px), read just before it was stored; memory only. */
    val sentPhoto: Bytes? = null,
    val duration: Duration = Duration.ZERO,
    val size: MediaDimensions? = null,
    val phase: Phase = Phase.Preparing,
    val failedAt: Instant? = null,
) {
    enum class Phase {
        /**
         * Being handed over by the picker, waiting for its turn, or being prepared (a photo
         * re-encoded at ≤ 2048 px, a video re-exported at ≤ 720p, no metadata either way) before it
         * is sent.
         */
        Preparing,

        /** Preparing (or storing) it failed; nothing left the phone. */
        Failed,

        /** Stored by the runtime under the bubble's id. The stand-in stays until the conversation shows the stored message, so the bubble never misses a frame. */
        Sent,
    }

    val id: MessageId get() = LiveMapping.messageId(messageData)

    /**
     * What the stored message's bubble may keep showing (sharper than the inline thumbnail): a
     * video's poster; a photo as it was sent, never its preview, which the viewer would then open
     * instead of the photo.
     */
    val keptImage: Bytes?
        get() = when (kind) {
            PickedMediaItem.Kind.Photo -> sentPhoto
            PickedMediaItem.Kind.Video -> poster
        }

    /**
     * The bubble: the picture (once it is read), the ring and “Hazırlanıyor” (sending); the warning
     * and “Tekrar gönder” once it failed. A photo shows its caption from the start.
     */
    val message: Message
        get() {
            val content = when (kind) {
                PickedMediaItem.Kind.Photo -> MessageContent.Photo(MediaSource.Memory(sentPhoto ?: poster ?: Bytes.Empty), caption)
                PickedMediaItem.Kind.Video -> MessageContent.Video(MediaSource.Memory(poster ?: Bytes.Empty), duration, 0)
            }
            return Message(
                id = id,
                direction = MessageDirection.Outgoing,
                sentAt = sentAt,
                content = content,
                status = if (phase == Phase.Failed) MessageStatus.Failed else MessageStatus.Sending,
                disappearing = disappearing,
                failedAt = failedAt,
                mediaAvailability = if (phase == Phase.Failed) MediaAvailability.PreparationFailed else MediaAvailability.Preparing,
                mediaSize = size,
            )
        }
}

/** What a picked item's bubble shows before it is prepared. */
data class OutgoingMediaPreview(
    val poster: Bytes?,
    val duration: Duration,
    val size: MediaDimensions?,
)

/**
 * The photos and videos picked in “+” that are on their way out.
 *
 * Handing an item over and preparing it take a moment (a long video a few seconds), so every bubble
 * is there at once, in the order the items were selected: the ring and “Hazırlanıyor”, the picture
 * (and a video's length) as soon as it is read from the picked copy. Then the items are prepared
 * and sent one at a time, in the order picked, so only one photo or video is ever being decoded or
 * re-exported however many were picked (the previews, at the bubble's size, are read one at a time
 * too). Each is stored under the message id its bubble already has, so the stored message takes the
 * bubble's place and nothing moves. An item still being handed over holds back the ones picked
 * after it; one that fails does not: its bubble says so, the rest carry on, and “Tekrar gönder”
 * prepares it again from the same picked copy (after whatever is still waiting); “Benden sil”
 * removes it.
 *
 * Nothing new is written: the picked copy stays in its private folder only while the item is being
 * prepared or waits for “Tekrar gönder”. It goes once the item is stored (the runtime keeps its own
 * copy, encrypted), when the bubble is removed, when a failed item is left behind in the
 * background, and on “Her şeyi sil”. Posters, previews and a sent photo are JPEG in memory.
 *
 * Main thread only; [scope] runs on it.
 */
class OutgoingMedia<P>(
    private val steps: Steps<P>,
    private val scope: CoroutineScope,
    private val now: () -> Instant = Instant::now,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** How an item goes out; the live service runs them on the runtime, the tests on stand-ins. */
    class Steps<P>(
        /** The poster (a video's first frame, a photo's preview), length and size, read from the picked copy. */
        val preview: suspend (File, PickedMediaItem.Kind) -> OutgoingMediaPreview?,
        /** Prepares the picked copy into the runtime's staging folder: a photo re-encoded with its caption, a video re-exported. */
        val prepare: suspend (File, PickedMediaItem.Kind, String?) -> P,
        /** The prepared file. */
        val preparedFile: (P) -> File,
        /** Stores and queues the prepared item under the id, in the chat. */
        val send: suspend (P, Bytes, ChatId) -> Unit,
        /** The picked copy is no longer needed. */
        val discardSource: (File) -> Unit,
        /** A prepared file that is not going to be sent. */
        val discardPrepared: (File) -> Unit,
    )

    private val itemsFlow = MutableStateFlow<List<OutgoingMediaItem>>(emptyList())

    /** The items on their way out, in the order picked. */
    val items: StateFlow<List<OutgoingMediaItem>> = itemsFlow.asStateFlow()

    /** An item was stored: its picture can stay for the stored bubble. */
    var onSent: ((OutgoingMediaItem) -> Unit)? = null

    /**
     * When the items stored so far were picked, while items picked with them are still on their way:
     * a later item of the same pick never shows above one stored before it. Memory only.
     */
    var landed: Map<MessageId, Instant> = emptyMap()
        private set

    /** The item being prepared and stored now (one at a time). */
    var active: MessageId? = null
        private set

    private val tasks = HashMap<MessageId, Job>()

    /** Items waiting for their turn, in the order they were picked. */
    private val queue = ArrayList<MessageId>()

    /** The latest preview being read (each waits for the one before). */
    private var previews: Job? = null
    private var lastPick: Instant? = null
    private var isInBackground = false

    // MARK: Reading

    fun item(id: MessageId): OutgoingMediaItem? = itemsFlow.value.firstOrNull { it.id == id }

    fun items(chat: ChatId): List<OutgoingMediaItem> = itemsFlow.value.filter { it.chat == chat }

    private fun update(id: MessageId, change: (OutgoingMediaItem) -> OutgoingMediaItem) {
        itemsFlow.value = itemsFlow.value.map { if (it.id == id) change(it) else it }
    }

    // MARK: Sending

    /**
     * Puts the bubble up for an item the picker is still handing over ([provide] brings the copy).
     * It takes its turn after everything picked before it; a photo carries [caption].
     */
    fun begin(kind: PickedMediaItem.Kind, caption: String? = null, chat: ChatId, disappearing: DisappearingTimer): MessageId {
        // The items of one pick keep the order they were selected in, also when the clock reads the same for all of them.
        var pickedAt = now()
        lastPick?.let { last -> if (!pickedAt.isAfter(last)) pickedAt = last.plusMillis(1) }
        lastPick = pickedAt
        val item = OutgoingMediaItem(
            messageData = RandomId.make(),
            chat = chat,
            sentAt = pickedAt,
            disappearing = disappearing,
            kind = kind,
            caption = if (kind == PickedMediaItem.Kind.Photo) caption else null,
        )
        itemsFlow.value = itemsFlow.value + item
        queue += item.id
        return item.id
    }

    /**
     * The picked copy of [id]'s item: its preview is read, and it is prepared once its turn comes.
     * `null`: the picker could not hand it over; there is nothing to send again, the bubble goes (and
     * the items after it carry on).
     */
    fun provide(source: File?, id: MessageId) {
        val item = item(id)
        if (item == null || item.source != null || item.phase != OutgoingMediaItem.Phase.Preparing) {
            // Removed in the meantime: the copy is not needed.
            source?.let(steps.discardSource)
            return
        }
        if (source == null) {
            itemsFlow.value = itemsFlow.value.filterNot { it.id == id }
            queue.remove(id)
            pump()
            return
        }
        update(id) { it.copy(source = source) }
        readPreview(id, item.kind, source)
        pump()
    }

    /** Puts the bubble up and queues [source] (a picked copy). */
    fun send(source: File, kind: PickedMediaItem.Kind, caption: String? = null, chat: ChatId, disappearing: DisappearingTimer): MessageId {
        val id = begin(kind, caption, chat, disappearing)
        provide(source, id)
        return id
    }

    /** “Tekrar gönder” on an item that failed: it waits for its turn again. `false`: not an item of this list (a message of the runtime). */
    fun resend(id: MessageId): Boolean {
        val item = item(id) ?: return false
        if (item.phase == OutgoingMediaItem.Phase.Sent) return false
        if (item.phase != OutgoingMediaItem.Phase.Failed) return true
        update(id) { it.copy(phase = OutgoingMediaItem.Phase.Preparing, failedAt = null) }
        queue += id
        pump()
        return true
    }

    /**
     * “Benden sil”: the bubble goes, preparing stops, nothing is sent, the picked copy goes.
     * `false`: not an item of this list (or already stored: then the runtime deletes it).
     */
    fun remove(id: MessageId): Boolean {
        val item = item(id) ?: return false
        itemsFlow.value = itemsFlow.value.filterNot { it.id == id }
        queue.remove(id)
        tasks.remove(id)?.cancel()
        if (active == id) active = null
        if (item.phase == OutgoingMediaItem.Phase.Sent) return false
        item.source?.let(steps.discardSource)
        pump()
        return true
    }

    // MARK: Lifecycle

    /** The conversation shows [messages]: stand-ins of items stored among them are no longer needed. */
    fun settle(shown: List<Message>) {
        if (itemsFlow.value.none { it.phase == OutgoingMediaItem.Phase.Sent }) return
        val ids = shown.mapTo(HashSet()) { it.id }
        val done = itemsFlow.value.filter { it.phase == OutgoingMediaItem.Phase.Sent && it.id in ids }.mapTo(HashSet()) { it.id }
        if (done.isEmpty()) return
        // Not in this pass: the caller is reading the conversation.
        scope.launch {
            itemsFlow.value = itemsFlow.value.filterNot { it.phase == OutgoingMediaItem.Phase.Sent && it.id in done }
            forgetLandedWhenIdle()
        }
    }

    /** The conversation closed: stored items need no stand-ins any more (one being prepared carries on, a failed one waits for its chat). */
    fun conversationClosed(chat: ChatId) {
        itemsFlow.value = itemsFlow.value.filterNot { it.chat == chat && it.phase == OutgoingMediaItem.Phase.Sent }
        forgetLandedWhenIdle()
    }

    /**
     * The app left the foreground: failed items are given up and their copies go; the ones being
     * prepared or waiting carry on (if one fails while the app is away, it is given up too).
     */
    fun didEnterBackground() {
        isInBackground = true
        for (item in itemsFlow.value) {
            if (item.phase == OutgoingMediaItem.Phase.Failed) item.source?.let(steps.discardSource)
        }
        itemsFlow.value = itemsFlow.value.filter { it.phase == OutgoingMediaItem.Phase.Preparing }
        forgetLandedWhenIdle()
    }

    fun didBecomeActive() {
        isInBackground = false
    }

    /** “Her şeyi sil”: everything stops and every picked copy goes. */
    fun removeAll() {
        for (task in tasks.values) task.cancel()
        tasks.clear()
        previews?.cancel()
        previews = null
        queue.clear()
        active = null
        for (item in itemsFlow.value) {
            if (item.phase != OutgoingMediaItem.Phase.Sent) item.source?.let(steps.discardSource)
        }
        itemsFlow.value = emptyList()
        landed = emptyMap()
    }

    // MARK: Steps

    /** The previews are read one at a time, in the order the copies came. */
    private fun readPreview(id: MessageId, kind: PickedMediaItem.Kind, source: File) {
        val previous = previews
        val read = steps.preview
        previews = scope.launch {
            previous?.join()
            // Gone meanwhile: nothing to read.
            if (item(id) == null) return@launch
            val preview = try {
                read(source, kind)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: return@launch
            apply(preview, id)
        }
    }

    private fun apply(preview: OutgoingMediaPreview, id: MessageId) {
        // Already stored: a photo shows itself as sent by now.
        val item = item(id) ?: return
        if (item.phase == OutgoingMediaItem.Phase.Sent) return
        update(id) { it.copy(poster = preview.poster, duration = preview.duration, size = preview.size) }
    }

    /**
     * The next item takes its turn: one at a time, in the order picked. An item still being handed
     * over holds back the ones picked after it; an item removed (or no longer waiting) is skipped.
     */
    private fun pump() {
        while (active == null && queue.isNotEmpty()) {
            val id = queue.first()
            val item = item(id)
            if (item == null || item.phase != OutgoingMediaItem.Phase.Preparing) {
                queue.removeAt(0)
                continue
            }
            val source = item.source ?: return
            queue.removeAt(0)
            start(item, source)
        }
    }

    private sealed interface Outcome {
        /** Stored; for a photo, the photo as it was sent. */
        data class Sent(val photo: Bytes?) : Outcome

        data object Failed : Outcome
    }

    private fun start(item: OutgoingMediaItem, source: File) {
        val id = item.id
        active = id
        tasks[id] = scope.launch {
            var outcome: Outcome = Outcome.Failed
            try {
                val prepared = steps.prepare(source, item.kind, item.caption)
                val file = steps.preparedFile(prepared)
                // The bubble keeps the photo as it is sent: read before storing moves the file into encrypted storage.
                val photo = if (item.kind == PickedMediaItem.Kind.Photo) withContext(io) { runCatching { Bytes.wrap(file.readBytes()) }.getOrNull() } else null
                // Removed while it was being prepared: nothing is sent.
                if (!isActive || item(id)?.phase != OutgoingMediaItem.Phase.Preparing) {
                    steps.discardPrepared(file)
                    finish(id, Outcome.Failed)
                    return@launch
                }
                try {
                    steps.send(prepared, item.messageData, item.chat)
                    outcome = Outcome.Sent(photo)
                } catch (e: CancellationException) {
                    steps.discardPrepared(file)
                    throw e
                } catch (_: Exception) {
                    steps.discardPrepared(file)
                }
            } catch (e: CancellationException) {
                // Removed (“Benden sil”) or everything stopped: the next item takes its turn.
                tasks.remove(id)
                if (active == id) {
                    active = null
                    pump()
                }
                throw e
            } catch (_: Exception) {
                outcome = Outcome.Failed
            }
            finish(id, outcome)
        }
    }

    private fun finish(id: MessageId, outcome: Outcome) {
        tasks.remove(id)
        if (active == id) active = null
        val item = item(id)
        if (item != null && item.phase == OutgoingMediaItem.Phase.Preparing) {
            when {
                outcome is Outcome.Sent -> {
                    item.source?.let(steps.discardSource)
                    val sent = item.copy(sentPhoto = outcome.photo, phase = OutgoingMediaItem.Phase.Sent)
                    update(id) { sent }
                    landed = landed + (id to item.sentAt)
                    onSent?.invoke(sent)
                }
                isInBackground -> {
                    // Nobody is looking: the picked copy does not wait in the background.
                    item.source?.let(steps.discardSource)
                    itemsFlow.value = itemsFlow.value.filterNot { it.id == id }
                }
                else -> update(id) { it.copy(phase = OutgoingMediaItem.Phase.Failed, failedAt = now()) }
            }
        }
        pump()
    }

    /** Nothing is waiting any more: when the stored ones were picked no longer matters. */
    private fun forgetLandedWhenIdle() {
        if (itemsFlow.value.none { it.phase != OutgoingMediaItem.Phase.Sent }) landed = emptyMap()
    }

    companion object {
        /**
         * [messages] (a stored conversation, oldest first) with the bubbles of [pending], each where
         * it was picked, but never above a message that was picked before it and stored meanwhile
         * ([landed]: when those were picked); one already stored is not added again.
         */
        fun merge(pending: List<OutgoingMediaItem>, messages: List<Message>, landed: Map<MessageId, Instant> = emptyMap()): List<Message> {
            if (pending.isEmpty()) return messages
            val stored = messages.mapTo(HashSet()) { it.id }
            val result = messages.toMutableList()
            for (item in pending.sortedBy { it.sentAt }) {
                if (item.id in stored) continue
                var index = result.indexOfLast { !it.sentAt.isAfter(item.sentAt) } + 1
                if (landed.isNotEmpty()) {
                    val earlier = result.indexOfLast { message -> landed[message.id]?.isBefore(item.sentAt) ?: false }
                    if (earlier >= 0) index = max(index, earlier + 1)
                }
                result.add(index, item.message)
            }
            return result
        }
    }
}

/** 16 random bytes: the id a message is stored under. */
internal object RandomId {
    private val random = SecureRandom()

    fun make(): Bytes = Bytes.wrap(ByteArray(16).also(random::nextBytes))
}

/**
 * Reads what a picked video's bubble shows before it is prepared (its length, size and a poster
 * frame) from the picked copy. The frame is encoded to a JPEG in memory; nothing is written.
 */
object VideoPreviewReader {
    /** The poster's longest edge: the 236 × 124 bubble at 3×, with room. */
    const val POSTER_EDGE = 720

    /** `null`: not a readable video. */
    fun read(file: File): OutgoingMediaPreview? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            if (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") return null
            val millis = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val turned = rotation % 180 != 0
            val size = if (width > 0 && height > 0) {
                if (turned) MediaDimensions(height, width) else MediaDimensions(width, height)
            } else {
                null
            }
            val frame = retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, POSTER_EDGE, POSTER_EDGE)
            val poster = frame?.let { jpeg(it).also { _ -> it.recycle() } }
            OutgoingMediaPreview(poster = poster, duration = max(0L, millis).milliseconds, size = size)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** Pixels only: no metadata is written. */
    fun jpeg(bitmap: Bitmap, quality: Int = 80): Bytes? {
        val out = ByteArrayOutputStream()
        return if (bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) Bytes.wrap(out.toByteArray()) else null
    }
}

/**
 * Reads what a picked photo's bubble shows before it is prepared (its size and a preview) from the
 * picked copy. Only the preview's pixels are decoded (at the bubble's size), never the full photo;
 * the preview is a JPEG in memory and nothing is written.
 */
object PhotoPreviewReader {
    /** The preview's longest edge: the 258 × 176 bubble at 3×, with room. */
    const val PREVIEW_EDGE = 800

    /** `null`: not a readable image. */
    fun read(file: File): OutgoingMediaPreview? = try {
        var full: MediaDimensions? = null
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val width = info.size.width
            val height = info.size.height
            full = MediaDimensions(width, height)
            val scale = PREVIEW_EDGE.toDouble() / max(width, height).coerceAtLeast(1)
            if (scale < 1) decoder.setTargetSize(max(1, (width * scale).roundToInt()), max(1, (height * scale).roundToInt()))
        }
        // The decoder applies the photo's orientation: the size as it is seen follows the pixels.
        val size = full?.let { source ->
            val sourceLandscape = source.width > source.height
            val shownLandscape = bitmap.width > bitmap.height
            if (source.width != source.height && sourceLandscape != shownLandscape) MediaDimensions(source.height, source.width) else source
        } ?: MediaDimensions(bitmap.width, bitmap.height)
        val poster = VideoPreviewReader.jpeg(bitmap)
        bitmap.recycle()
        OutgoingMediaPreview(poster = poster, duration = Duration.ZERO, size = size)
    } catch (_: Exception) {
        null
    }
}
