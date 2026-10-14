package com.syconx.us2us.feature.verification

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.syconx.us2us.domain.ChatMediaKind
import com.syconx.us2us.domain.MediaAvailability
import com.syconx.us2us.domain.MediaFileKind
import com.syconx.us2us.domain.Message
import com.syconx.us2us.domain.MessageContent
import com.syconx.us2us.domain.MessengerService
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.ContactTab
import com.syconx.us2us.domain.nav.MessageId
import com.syconx.us2us.ui.env.U2Haptics
import com.syconx.us2us.ui.media.ConversationMediaActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The contact screen's state (5.1 › the person in the header): which tab shows (Doğrulama, 6.1 as
 * drawn; Fotoğraflar; Belgeler), the two media lists, newest first, a page at a time, and what opens
 * from them: the photo viewer and the player swiping through the grid ([MediaGalleryViewer]) and a
 * file's preview. All data comes through [MessengerService.media]; nothing is kept beyond this
 * screen.
 */
@Stable
class ContactMediaModel(
    val chatId: ChatId,
    val messenger: MessengerService,
    private val scope: CoroutineScope,
) {
    /** One list as loaded so far. */
    @Immutable
    data class Page(
        val items: List<Message> = emptyList(),
        val isLoading: Boolean = false,
        /** The last page was full: there may be older items. */
        val hasMore: Boolean = true,
        val hasLoaded: Boolean = false,
    )

    /** The gallery over the grid, opened at one item. */
    @Immutable
    data class GalleryItem(val startId: MessageId, val pages: Pages, val serial: Long) {
        enum class Pages {
            /** Every photo and video of the grid. */
            Visual,

            /** Only this one (a video or an image sent as a file, from Belgeler). */
            Single,
        }
    }

    var tab: ContactTab by mutableStateOf(ContactTab.Verification)
        private set
    var visual: Page by mutableStateOf(Page())
        private set
    var documents: Page by mutableStateOf(Page())
        private set

    /** The viewer / player over the grid. */
    var gallery: GalleryItem? by mutableStateOf(null)
        private set

    /** The file whose preview (or notice) is up. */
    var filePreview: MessageId? by mutableStateOf(null)
        private set

    /** The file being decrypted for its preview (the row shows the ring); the preview reports it. */
    val activity = ConversationMediaActivity()

    /** Haptics of the window (the view sets them). */
    var haptics: U2Haptics = U2Haptics.None

    private val loadedFlow = MutableStateFlow<List<Message>>(emptyList())

    /** Every item of both lists as loaded (what the viewers may open besides the open conversation). */
    val loaded: StateFlow<List<Message>> = loadedFlow.asStateFlow()

    /** A reload replaces what a request that started before it would bring. */
    private val generations = HashMap<ChatMediaKind, Int>()
    private var gallerySerial = 0L

    val peerUsername: String get() = messenger.peer(chatId).value?.username ?: chatId.value
    val ownUsername: String get() = messenger.ownUsername.value

    // MARK: Lists

    fun page(kind: ChatMediaKind): Page = when (kind) {
        ChatMediaKind.Visual -> visual
        ChatMediaKind.Documents -> documents
    }

    private fun setPage(kind: ChatMediaKind, change: (Page) -> Page) {
        when (kind) {
            ChatMediaKind.Visual -> visual = change(visual)
            ChatMediaKind.Documents -> documents = change(documents)
        }
        loadedFlow.value = visual.items + documents.items
    }

    /**
     * The list as it is now: an item the open conversation knows shows its current state
     * (downloaded, saved), and one deleted meanwhile (or turned into something else) is gone.
     */
    fun items(kind: ChatMediaKind): List<Message> {
        val loaded = page(kind).items
        if (loaded.isEmpty()) return emptyList()
        val live = messenger.messages(chatId).value.associateBy { it.id }
        return loaded.mapNotNull { item ->
            val current = live[item.id] ?: item
            current.takeIf { belongs(it, kind) }
        }
    }

    /** The first page, once (the tab appears). */
    suspend fun loadFirstPageIfNeeded(kind: ChatMediaKind) {
        val current = page(kind)
        if (current.hasLoaded || current.isLoading) return
        reload(kind)
    }

    /** From the newest again (the screen came back, the chat changed). */
    suspend fun reload(kind: ChatMediaKind) {
        val generation = (generations[kind] ?: 0) + 1
        generations[kind] = generation
        setPage(kind) { it.copy(isLoading = true) }
        val fetched = messenger.media(chatId, kind, before = null, limit = PAGE_SIZE)
        if (generations[kind] != generation) return
        setPage(kind) {
            it.copy(items = unique(fetched.filter { message -> belongs(message, kind) }), hasMore = fetched.size >= PAGE_SIZE, hasLoaded = true, isLoading = false)
        }
    }

    /**
     * The chat's messages changed (a download finished, an item was saved, deleted, expired or
     * wiped): every loaded list is read again from the newest, as far as it was loaded, so what is
     * listed (and what is open over it) is never an old copy. No spinner: the list stays as it is
     * until the new one is there.
     */
    suspend fun refreshLoaded() {
        for (kind in ChatMediaKind.entries) {
            if (!page(kind).hasLoaded) continue
            val generation = (generations[kind] ?: 0) + 1
            generations[kind] = generation
            val limit = page(kind).items.size.coerceAtLeast(PAGE_SIZE).coerceAtMost(REFRESH_LIMIT)
            val fetched = messenger.media(chatId, kind, before = null, limit = limit)
            if (generations[kind] != generation) continue
            setPage(kind) {
                it.copy(items = unique(fetched.filter { message -> belongs(message, kind) }), hasMore = fetched.size >= limit, isLoading = false)
            }
        }
    }

    /** The next (older) page. */
    suspend fun loadMore(kind: ChatMediaKind) {
        val current = page(kind)
        val last = current.items.lastOrNull()
        if (!current.hasLoaded || !current.hasMore || current.isLoading || last == null) return
        val generation = generations[kind] ?: 0
        setPage(kind) { it.copy(isLoading = true) }
        val fetched = messenger.media(chatId, kind, before = last.id, limit = PAGE_SIZE)
        if (generations[kind] != generation) return
        setPage(kind) {
            it.copy(items = unique(it.items + fetched.filter { message -> belongs(message, kind) }), hasMore = fetched.size >= PAGE_SIZE, isLoading = false)
        }
    }

    /** An item came on screen: near the end, the next page loads. */
    fun itemAppeared(id: MessageId, kind: ChatMediaKind) {
        val items = page(kind).items
        val index = items.indexOfLast { it.id == id }
        if (index < 0 || index < items.size - PREFETCH_DISTANCE) return
        scope.launch { loadMore(kind) }
    }

    /** The tab changed: its list loads the first time it shows. */
    fun show(tab: ContactTab) {
        this.tab = tab
        val kind = mediaKind(tab) ?: return
        scope.launch { loadFirstPageIfNeeded(kind) }
    }

    // MARK: What the viewers show

    /** A message of this chat: the open conversation's copy first, else the one loaded here. */
    fun message(id: MessageId): Message? =
        messenger.messages(chatId).value.lastOrNull { it.id == id }
            ?: visual.items.firstOrNull { it.id == id }
            ?: documents.items.firstOrNull { it.id == id }

    /** The sender's name over the viewer (the user's own name for their own items). */
    fun sender(message: Message): String = if (message.isOutgoing) ownUsername else peerUsername

    // MARK: Opening

    /** A tile of the grid: the viewer or the player, swiping through the grid. */
    fun openVisual(message: Message) {
        if (!belongs(message, ChatMediaKind.Visual)) return
        val availability = message.mediaAvailability
        if (message.content is MessageContent.Video && (availability == MediaAvailability.Unavailable || availability == MediaAvailability.Held)) {
            haptics.error()
            return
        }
        haptics.selection()
        gallery = GalleryItem(message.id, GalleryItem.Pages.Visual, ++gallerySerial)
    }

    fun closeGallery() {
        gallery = null
    }

    /** The pages the gallery swipes through. */
    fun galleryPages(gallery: GalleryItem): List<Message> = when (gallery.pages) {
        GalleryItem.Pages.Visual -> items(ChatMediaKind.Visual)
        GalleryItem.Pages.Single -> listOfNotNull(message(gallery.startId))
    }

    /**
     * A row of Belgeler: the file's preview (its share button tells the sender), a video file in the
     * player, a picture file in the photo viewer, or the notice for what cannot be shown. Still on
     * the relay: downloaded first (the ring), then it opens.
     */
    fun openDocument(message: Message) {
        val content = message.content as? MessageContent.File ?: return
        if (!belongs(message, ChatMediaKind.Documents)) return
        when (message.mediaAvailability) {
            MediaAvailability.Ready, MediaAvailability.Remote, is MediaAvailability.Downloading -> Unit
            MediaAvailability.Held, MediaAvailability.Unavailable, MediaAvailability.Preparing, MediaAvailability.PreparationFailed -> {
                haptics.error()
                return
            }
        }
        if (activity.opening != null || filePreview != null) return
        // A video or an image sent as a file: the player or the photo viewer.
        val opensAlone = MediaFileKind.of(content.name) == MediaFileKind.Movie || MediaFileKind.opensInPhotoViewer(content.name)
        if (opensAlone && message.mediaAvailability.isReady) {
            gallery = GalleryItem(message.id, GalleryItem.Pages.Single, ++gallerySerial)
            return
        }
        haptics.selection()
        filePreview = message.id
    }

    fun closeFilePreview() {
        filePreview = null
    }

    // MARK: Lifecycle

    /** What is open shows a message that was deleted, expired or wiped. */
    val presentedMediaIsGone: Boolean
        get() {
            fun isGone(id: MessageId) = message(id)?.isDeleted ?: true
            gallery?.let { if (galleryPages(it).isEmpty()) return true }
            filePreview?.let { if (isGone(it)) return true }
            return false
        }

    /** The app left the foreground (the lock follows), or the media went: nothing decrypted waits behind it. */
    fun closeMedia() {
        gallery = null
        filePreview = null
    }

    companion object {
        /** Items per request. */
        const val PAGE_SIZE = 30

        /** The most a refresh reads again at once (the runtime's page). */
        const val REFRESH_LIMIT = 500

        /** The next page loads when an item this close to the end appears. */
        const val PREFETCH_DISTANCE = 9

        /** The list a tab shows (none for Doğrulama). */
        fun mediaKind(tab: ContactTab): ChatMediaKind? = when (tab) {
            ContactTab.Verification -> null
            ContactTab.Photos -> ChatMediaKind.Visual
            ContactTab.Documents -> ChatMediaKind.Documents
        }

        /**
         * What each tab lists: never view-once, disappearing (§8.1: a list kept outside the
         * conversation must never show one past its deadline), deleted, pending or system lines.
         */
        fun belongs(message: Message, kind: ChatMediaKind): Boolean {
            if (message.isDeleted || message.isSystem || message.isPendingMedia || message.isDisappearing) return false
            return when (message.content) {
                is MessageContent.Photo, is MessageContent.Video -> kind == ChatMediaKind.Visual
                is MessageContent.File -> kind == ChatMediaKind.Documents
                else -> false
            }
        }

        /** The first of each id, in order (a page that overlaps the last one). */
        fun unique(messages: List<Message>): List<Message> {
            val seen = HashSet<MessageId>()
            return messages.filter { seen.add(it.id) }
        }
    }
}
