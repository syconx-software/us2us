package com.syconx.us2us.live

import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.proto.Attachment
import com.syconx.us2us.domain.Bytes
import com.syconx.us2us.domain.ChatSummary
import com.syconx.us2us.domain.MediaPayload
import com.syconx.us2us.domain.Message
import com.syconx.us2us.domain.MessageRecipient
import com.syconx.us2us.domain.mapState
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.MessageId
import com.syconx.us2us.runtime.community.CommunityEngine
import com.syconx.us2us.runtime.facade.AppRuntime
import com.syconx.us2us.runtime.facade.LockState
import com.syconx.us2us.runtime.media.PreparedMedia
import com.syconx.us2us.runtime.storage.CommunityActivity
import com.syconx.us2us.runtime.storage.CommunityMediaState
import com.syconx.us2us.runtime.storage.StoredCommunity
import com.syconx.us2us.runtime.storage.StoredCommunityMessage
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Communities in the chat list and in the conversation screen (Communities extension v2): the
 * rows next to the one-to-one chats, the open community's messages, its media (photos decrypted
 * into memory only, as in 1:1 chats), downloads and the actions on its messages.
 *
 * The relay is asked only while the app is unlocked and in front: every [POLL] (every
 * [OPEN_POLL] while a community is open). Nothing decrypted outlives a lock: every list and cache
 * here is dropped with it, and on leaving the foreground the images go.
 *
 * Main thread only; [scope] runs on it.
 */
internal class LiveCommunities(
    private val runtime: AppRuntime,
    private val scope: CoroutineScope,
    private val outgoing: OutgoingMedia<PreparedMedia>,
    /** The user's read-receipt setting: READ states are shown only while it is on (they are mutual). */
    private val showsRead: () -> Boolean,
) {
    private class Snapshot(
        val own: AccountId,
        val records: List<LiveCommunityRecord>,
        val activity: Map<AccountId, CommunityActivity>,
        val open: AccountId?,
        val history: com.syconx.us2us.runtime.storage.CommunityHistory?,
    )

    private val summariesState = MutableStateFlow<List<ChatSummary>>(emptyList())

    /** The communities' chat-list rows (joined and ended; pending requests have no conversation yet). */
    val summaries: StateFlow<List<ChatSummary>> = summariesState.asStateFlow()

    private class Open(val chat: ChatId?, val messages: List<Message>)

    private val openState = MutableStateFlow(Open(null, emptyList()))
    private val messageFlows = HashMap<ChatId, StateFlow<List<Message>>>()

    private var own: AccountId? = null
    private var records: Map<AccountId, LiveCommunityRecord> = emptyMap()
    private var openChat: ChatId? = null
    private var openStored: List<StoredCommunityMessage> = emptyList()
    private var historyOldest: com.syconx.us2us.runtime.storage.CommunityHistoryCursor? = null
    private var historyHasOlder = true
    private var historyVersion = 0
    private var foreground = true

    /** Full images of the open community, decrypted into memory (cleared on close, background and lock). */
    private val media = MutableStateFlow<Map<MessageId, Bytes>>(emptyMap())
    private val mediaLoading = HashSet<MessageId>()

    /** Downloads the user asked for (or photos fetched on their own), so the bubble shows its ring at once. */
    private val requested = MutableStateFlow<Set<MessageId>>(emptySet())
    private val syncing = Mutex()
    private var reloadJob: Job? = null
    private var receiptsJob: Job? = null

    fun start() {
        scope.launch {
            merge(runtime.communityRevision, runtime.lockState).conflate().collect {
                if (runtime.lockState.value != LockState.UNLOCKED) clear() else scheduleReload()
            }
        }
        scope.launch {
            merge(media, requested, runtime.communityDownloads, outgoing.items).conflate().collect { recomputeOpen() }
        }
        scope.launch {
            while (isActive) {
                runtime.lockState.first { it == LockState.UNLOCKED }
                if (foreground) syncNow()
                delay(if (openChat != null) OPEN_POLL else POLL)
            }
        }
    }

    fun isCommunity(chat: ChatId): Boolean = LiveCommunityMapping.community(chat) != null

    fun messages(chat: ChatId): StateFlow<List<Message>> =
        messageFlows.getOrPut(chat) { openState.mapState { if (it.chat == chat) it.messages else emptyList() } }

    fun appeared(chat: ChatId) {
        if (openChat != chat) {
            historyOldest = null; historyHasOlder = true; historyVersion++
            openChat = chat
            openStored = emptyList()
            media.value = emptyMap()
            mediaLoading.clear()
            requested.value = emptySet()
            recomputeOpen()
        }
        scope.launch { syncNow() }
        scheduleReload()
    }

    fun disappeared(chat: ChatId) {
        if (openChat != chat) return
        historyOldest = null; historyHasOlder = true; historyVersion++
        openChat = null
        openStored = emptyList()
        media.value = emptyMap()
        mediaLoading.clear()
        requested.value = emptySet()
        openState.value = Open(null, emptyList())
    }

    fun foregroundChanged(active: Boolean) {
        foreground = active
        if (active) {
            scope.launch { syncNow() }
        } else {
            media.value = emptyMap()
            mediaLoading.clear()
        }
    }

    /** A photo sent from here, as it was sent: its bubble keeps it (memory only). */
    fun keep(item: OutgoingMediaItem) {
        val image = item.keptImage ?: return
        if (openChat != item.chat) return
        media.value = media.value + (item.id to image)
    }

    suspend fun loadOlder(chat: ChatId): Boolean {
        val id = LiveCommunityMapping.community(chat) ?: return false
        val before = historyOldest ?: return false
        if (openChat != chat || !historyHasOlder || runtime.lockState.value != LockState.UNLOCKED) return false
        val version = historyVersion
        val page = runtime.withCommunities { it.history(id, before = before) }
        if (openChat != chat || version != historyVersion || runtime.lockState.value != LockState.UNLOCKED) return false
        val oldest = page.oldest
        if (oldest == null) { historyHasOlder = false; return false }
        val window = runtime.withCommunities { it.history(id, through = oldest) }
        if (openChat != chat || version != historyVersion || runtime.lockState.value != LockState.UNLOCKED) return false
        historyVersion++; historyOldest = oldest; historyHasOlder = window.hasOlder
        openStored = window.items
        recomputeOpen()
        markDisplayed(id, window.items)
        return true
    }

    // MARK: Actions

    suspend fun sendText(text: String, chat: ChatId) {
        val id = LiveCommunityMapping.community(chat) ?: return
        quietly { runtime.withCommunities { it.send(id, text) } }
    }

    /** Prepared media (a picked photo or video under the id its bubble has, or a document): stored, uploaded once and sent. */
    suspend fun sendMedia(prepared: PreparedMedia, chat: ChatId, messageId: AccountId? = null) {
        val id = LiveCommunityMapping.community(chat) ?: throw java.io.IOException("unknown community")
        runtime.withCommunities { engine ->
            if (messageId != null) engine.sendMedia(id, prepared, messageId = messageId) else engine.sendMedia(id, prepared)
        }
    }

    suspend fun resend(message: MessageId, chat: ChatId) = act(message, chat) { engine, id, sender, messageId ->
        if (sender == engine.accountId) engine.resend(id, messageId)
    }

    suspend fun deleteForEveryone(message: MessageId, chat: ChatId) = act(message, chat) { engine, id, sender, messageId ->
        engine.deleteForEveryone(id, sender, messageId)
    }

    suspend fun deleteForMe(message: MessageId, chat: ChatId) = act(message, chat) { engine, id, sender, messageId ->
        engine.deleteForMe(id, sender, messageId)
    }

    suspend fun recipients(message: MessageId, chat: ChatId): List<MessageRecipient> {
        val id = LiveCommunityMapping.community(chat) ?: return emptyList()
        val own = own ?: return emptyList()
        val (sender, messageId) = LiveCommunityMapping.key(own, message) ?: return emptyList()
        if (sender != own) return emptyList()
        val list = valueOr(emptyList()) { runtime.withCommunities { it.recipients(id, messageId) } }
        val record = records[id] ?: return emptyList()
        return LiveCommunityMapping.recipients(record, list, showsRead())
    }

    fun downloadMedia(message: MessageId, chat: ChatId) {
        val id = LiveCommunityMapping.community(chat) ?: return
        val own = own ?: return
        val (sender, messageId) = LiveCommunityMapping.key(own, message) ?: return
        val stored = openStored.firstOrNull { it.sender == sender && it.messageId == messageId } ?: return
        if (stored.media != CommunityMediaState.REMOTE || sender == own) return
        download(id, sender, messageId, LiveCommunityMapping.messageId(own, sender, messageId))
    }

    /** The decrypted bytes of a photo, video or file, in memory only; downloaded first when still on the relay. */
    suspend fun mediaPayload(message: MessageId, chat: ChatId): MediaPayload? {
        val id = LiveCommunityMapping.community(chat) ?: return null
        val own = own ?: return null
        val (sender, messageId) = LiveCommunityMapping.key(own, message) ?: return null
        val bubble = LiveCommunityMapping.messageId(own, sender, messageId)
        val stored = openStored.firstOrNull { it.sender == sender && it.messageId == messageId } ?: return null
        val attachment = stored.attachment ?: return null
        val name = attachment.fileName.takeIf { attachment.kind == Attachment.Kind.FILE && it.isNotEmpty() }
        if (attachment.kind == Attachment.Kind.IMAGE) media.value[bubble]?.let { return MediaPayload(it, attachment.contentType, name) }
        if (!waitUntilStored(id, sender, messageId, bubble)) return null
        val data = valueOr(null) { runtime.withCommunities { it.mediaData(id, sender, messageId) } } ?: return null
        val bytes = Bytes.wrap(data)
        if (attachment.kind == Attachment.Kind.IMAGE && openChat == chat) media.value = media.value + (bubble to bytes)
        return MediaPayload(bytes, attachment.contentType, name)
    }

    // MARK: Internals

    private suspend fun act(message: MessageId, chat: ChatId, action: suspend (CommunityEngine, AccountId, AccountId, AccountId) -> Unit) {
        val id = LiveCommunityMapping.community(chat) ?: return
        val own = own ?: return
        val (sender, messageId) = LiveCommunityMapping.key(own, message) ?: return
        quietly { runtime.withCommunities { action(it, id, sender, messageId) } }
    }

    private suspend fun syncNow() {
        if (runtime.lockState.value != LockState.UNLOCKED || syncing.isLocked) return
        syncing.withLock { quietly { runtime.withCommunities { it.sync() } } }
        scheduleReload()
    }

    private var reloadPending = false

    private fun scheduleReload() {
        reloadPending = true
        if (reloadJob?.isActive == true) return
        reloadJob = scope.launch {
            // Changes come in bursts (a sync pass): one read for each burst, and one more for what changed during it.
            while (reloadPending) {
                reloadPending = false
                delay(RELOAD_COALESCING)
                reload()
            }
        }
    }

    private suspend fun reload() {
        if (runtime.lockState.value != LockState.UNLOCKED) return
        val openId = openChat?.let(LiveCommunityMapping::community)
        val version = historyVersion
        val through = historyOldest
        val snapshot = valueOr(null) {
            runtime.withCommunities { engine ->
                val records = engine.communities().filter { !it.pending }.map { record(engine, it) }
                Snapshot(engine.accountId, records, engine.activity().associateBy { it.communityId }, openId, openId?.let { engine.history(it, through = through) })
            }
        } ?: return
        if (runtime.lockState.value != LockState.UNLOCKED) return
        own = snapshot.own
        records = snapshot.records.associateBy { it.id }
        val showsRead = showsRead()
        summariesState.value = snapshot.records.map { record ->
            val activity = snapshot.activity[record.id]
            LiveCommunityMapping.summary(record, activity?.last, activity?.unread ?: 0, snapshot.own, showsRead)
        }
        if (snapshot.open != null && snapshot.open == openChat?.let(LiveCommunityMapping::community) && version == historyVersion) {
            val history = snapshot.history ?: return
            if (historyOldest == null) historyOldest = history.oldest
            historyHasOlder = history.hasOlder
            openStored = history.items
            recomputeOpen()
            markDisplayed(snapshot.open, history.items)
        }
    }

    private fun record(engine: CommunityEngine, group: StoredCommunity): LiveCommunityRecord {
        val state = try {
            group.state
        } catch (_: IllegalArgumentException) {
            null
        }
        return LiveCommunityRecord(
            id = group.id,
            name = group.name,
            isPublic = state?.visibility == "public",
            members = state?.members?.map { it.accountId }.orEmpty(),
            owner = state?.ownerAccountId,
            active = group.active,
            names = state?.let(engine::memberNames).orEmpty(),
        )
    }

    private fun recomputeOpen() {
        val chat = openChat
        val id = chat?.let(LiveCommunityMapping::community)
        val own = own
        val record = id?.let { records[it] }
        if (chat == null || id == null || own == null || record == null) {
            openState.value = Open(chat, emptyList())
            return
        }
        val downloads = HashMap<MessageId, Double?>()
        for (bubble in requested.value) downloads[bubble] = null
        for ((key, fraction) in runtime.communityDownloads.value) {
            if (key.community == id) downloads[LiveCommunityMapping.messageId(own, key.sender, key.messageId)] = fraction
        }
        val stored = LiveCommunityMapping.messages(record, openStored, own, showsRead(), media.value, downloads)
        // Photos and videos picked here and still being prepared show where they were picked.
        openState.value = Open(chat, OutgoingMedia.merge(outgoing.items(chat), stored, outgoing.landed))
        outgoing.settle(stored)
        loadMedia(id, own)
        settleRequested()
    }

    /** Full images of the open community into memory; a photo still on the relay is fetched on its own, as in 1:1 chats. */
    private fun loadMedia(id: AccountId, own: AccountId) {
        for (stored in openStored) {
            val attachment = stored.attachment ?: continue
            if (attachment.kind != Attachment.Kind.IMAGE || stored.isDeleted) continue
            val bubble = LiveCommunityMapping.messageId(own, stored.sender, stored.messageId)
            if (media.value[bubble] != null || bubble in mediaLoading) continue
            when (stored.media) {
                CommunityMediaState.LOCAL -> {
                    mediaLoading += bubble
                    scope.launch {
                        val data = valueOr(null) { runtime.withCommunities { it.mediaData(id, stored.sender, stored.messageId) } }
                        if (openChat?.let(LiveCommunityMapping::community) != id) return@launch
                        if (data == null) {
                            // Not loaded: the next change of the conversation tries again.
                            mediaLoading -= bubble
                            return@launch
                        }
                        media.value = media.value + (bubble to Bytes.wrap(data))
                    }
                }
                CommunityMediaState.REMOTE -> if (foreground && stored.sender != own) {
                    mediaLoading += bubble
                    download(id, stored.sender, stored.messageId, bubble, done = { mediaLoading -= bubble })
                }
                else -> Unit
            }
        }
    }

    private fun download(id: AccountId, sender: AccountId, messageId: AccountId, bubble: MessageId, done: () -> Unit = {}) {
        if (bubble !in requested.value) requested.value = requested.value + bubble
        scope.launch {
            quietly { runtime.withCommunities { it.download(id, sender, messageId) } }
            requested.value = requested.value - bubble
            done()
            scheduleReload()
        }
    }

    private fun settleRequested() {
        if (requested.value.isEmpty()) return
        val own = own ?: return
        val done = requested.value.filter { bubble ->
            val key = LiveCommunityMapping.key(own, bubble)
            val stored = key?.let { (sender, messageId) -> openStored.firstOrNull { it.sender == sender && it.messageId == messageId } }
            stored == null || stored.media != CommunityMediaState.REMOTE
        }
        if (done.isEmpty()) return
        scope.launch { requested.value = requested.value - done.toSet() }
    }

    /**
     * The open conversation is on screen: its incoming messages count as displayed (as in 1:1
     * chats). READ is queued for them while read receipts are on and sent a few seconds later,
     * coalesced per original sender.
     */
    private fun markDisplayed(id: AccountId, messages: List<StoredCommunityMessage>) {
        if (!foreground) return
        val own = own ?: return
        val unseen = messages.filter { it.sender != own && it.displayedAt == null }.map { it.sender to it.messageId }
        if (unseen.isEmpty()) return
        scope.launch {
            val marked = valueOr(false) { runtime.withCommunities { it.markDisplayed(id, unseen) } }
            if (!marked) return@launch
            if (receiptsJob?.isActive == true) return@launch
            receiptsJob = scope.launch {
                delay(READ_COALESCING)
                quietly { runtime.withCommunities { it.flushReceipts() } }
            }
        }
    }

    /** Waits until the file is here, downloading it first when it is still on the relay. `false` when it never can be. */
    private suspend fun waitUntilStored(id: AccountId, sender: AccountId, messageId: AccountId, bubble: MessageId): Boolean {
        var requestedHere = false
        val deadline = Instant.now().plusSeconds(WAIT_FOR_DOWNLOAD.inWholeSeconds)
        while (currentCoroutineContext().isActive && Instant.now().isBefore(deadline)) {
            val stored = valueOr(null) { runtime.withCommunities { it.message(com.syconx.us2us.runtime.community.CommunityMessageKey(id, sender, messageId)) } } ?: return false
            when (stored.media) {
                CommunityMediaState.LOCAL -> return true
                CommunityMediaState.REMOTE -> if (!requestedHere) {
                    requestedHere = true
                    download(id, sender, messageId, bubble)
                }
                else -> return false
            }
            delay(250.milliseconds)
        }
        return false
    }

    private fun clear() {
        historyOldest = null; historyHasOlder = true; historyVersion++
        reloadJob?.cancel()
        reloadPending = false
        receiptsJob?.cancel()
        summariesState.value = emptyList()
        openStored = emptyList()
        own = null
        records = emptyMap()
        media.value = emptyMap()
        mediaLoading.clear()
        requested.value = emptySet()
        openState.value = Open(openChat, emptyList())
    }

    /** Drops what is decrypted in memory (the app left the foreground). */
    fun clearCaches() {
        media.value = emptyMap()
        mediaLoading.clear()
    }

    private suspend inline fun quietly(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private suspend inline fun <T> valueOr(fallback: T, block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        fallback
    }

    private companion object {
        val POLL = 15.seconds
        val OPEN_POLL = 4.seconds
        val RELOAD_COALESCING = 120.milliseconds
        val READ_COALESCING = 3.seconds
        val WAIT_FOR_DOWNLOAD = 60.seconds
    }
}
