package com.syconx.us2us.feature.conversation

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import com.syconx.us2us.domain.ChatSummary
import com.syconx.us2us.domain.CommunityChats
import com.syconx.us2us.domain.ConversationAttachment
import com.syconx.us2us.domain.DisappearingTimer
import com.syconx.us2us.domain.GhostPeriod
import com.syconx.us2us.domain.GhostState
import com.syconx.us2us.domain.LivePosition
import com.syconx.us2us.domain.MediaAvailability
import com.syconx.us2us.domain.MediaFileKind
import com.syconx.us2us.domain.MediaPayload
import com.syconx.us2us.domain.Message
import com.syconx.us2us.domain.MessageContent
import com.syconx.us2us.domain.MessengerService
import com.syconx.us2us.domain.NoticePermission
import com.syconx.us2us.domain.PickedMediaItem
import com.syconx.us2us.domain.PickedMediaPlan
import com.syconx.us2us.domain.QuotedMessage
import com.syconx.us2us.domain.ViewOnceState
import com.syconx.us2us.domain.VoiceRecording
import com.syconx.us2us.domain.design.DesignFixtures
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.MessageId
import com.syconx.us2us.domain.sticker.BrandStickerSelection
import com.syconx.us2us.domain.sticker.StickerLanguage
import com.syconx.us2us.domain.sticker.StickerRecents
import com.syconx.us2us.feature.conversation.voice.VoiceComposer
import com.syconx.us2us.feature.conversation.voice.VoicePlayback
import com.syconx.us2us.ui.bubbles.VoiceAudioEngine
import com.syconx.us2us.ui.composer.VoiceCaptureDevice
import com.syconx.us2us.ui.env.U2Haptics
import com.syconx.us2us.ui.l10n.U2Strings
import com.syconx.us2us.ui.media.ConversationMediaActivity
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What a conversation needs from the screen around it (haptics, the keyboard, the clipboard, the UI language). */
interface ConversationPlatform {
    val haptics: U2Haptics

    /** The UI language (7.1 › Dil), for texts built in code. */
    val strings: U2Strings

    fun dismissKeyboard()

    /** This phone only, gone after three minutes. */
    fun copy(text: String)
}

/**
 * The state of one open conversation (05; iOS `ConversationViewModel`): what is drafted, what is
 * presented (5.3 actions, 5.4 info, 5.5 Ghost, the media covers, the “+” menu, the sticker
 * picker), the voice recorder and the voice message that plays, and every user action. Screens
 * render it; all data goes through [MessengerService], so the live messenger replaces the
 * fixtures without touching the screens. Its properties are snapshot state.
 *
 * Kept cheap to create: nothing touches audio before it is used. [run] follows the messenger
 * while the screen is shown.
 */
@Stable
class ConversationViewModel(
    val chatId: ChatId,
    val messenger: MessengerService,
    private val scope: CoroutineScope,
    private val platform: ConversationPlatform,
    voiceDevice: VoiceCaptureDevice = VoiceCaptureDevice.Unavailable,
    voiceEngine: VoiceAudioEngine = VoiceAudioEngine.Unavailable,
) {
    /** The long-pressed message and where its bubble was (in window coordinates) when the press ended. */
    @Immutable
    data class ActionsContext(val messageId: MessageId, val isTail: Boolean, val sourceFrame: Rect)

    /** What a full-screen cover shows. */
    @Immutable
    sealed interface Cover {
        val messageId: MessageId

        /** “tap photo → media viewer” (also an image sent as a file). */
        data class Photo(override val messageId: MessageId) : Cover

        /** “video → player” (also a video sent as a file). */
        data class Video(override val messageId: MessageId) : Cover

        /** A file's preview. */
        data class File(override val messageId: MessageId) : Cover

        /** 5.6: view-once media (its own viewer). */
        data class ViewOnce(override val messageId: MessageId) : Cover

        /** 5.2 › harita: the live-location map, opened on a share. */
        data class LiveMap(override val messageId: MessageId) : Cover
    }

    /** The message whose details 5.4 shows. */
    @Immutable
    data class InfoItem(val id: MessageId)

    /** The floating “Haritada aç” chooser of a tapped place. */
    @Immutable
    data class LocationChoice(val id: MessageId)

    // Reading

    var chat: ChatSummary? by mutableStateOf(messenger.chat(chatId).value)
        private set

    /** The conversation, oldest first (the messenger's, then the harness's demo messages). */
    var messages: List<Message> by mutableStateOf(messenger.messages(chatId).value)
        private set

    val ownUsername: String get() = messenger.ownUsername.value

    /** The UI language, for texts built in code. */
    val strings: U2Strings get() = platform.strings
    val peerUsername: String get() = chat?.peer?.username ?: chatId.value

    fun message(id: MessageId): Message? = messages.lastOrNull { it.id == id }

    /** The rows of the message column, derived once per change of the conversation. */
    var rows: List<ConversationRow> by mutableStateOf(ConversationRows.rows(messages))
        private set

    /** Rows removed from the conversation that the column still shows while they collapse. */
    var leavingRows: List<LeavingRow> by mutableStateOf(emptyList())
        private set

    /** Messages whose kind just changed (text → trace, live → ended, view once → opened), with how they were. */
    var kindChanges: Map<MessageId, Message> by mutableStateOf(emptyMap())
        private set

    /** New messages at the end of the conversation whose rows are growing in. */
    var arrivingRows: Set<MessageId> by mutableStateOf(emptySet())
        private set

    /** [rows] with the leaving rows at their places. */
    val displayedRows: List<ConversationRow> get() = ConversationRows.displayed(rows, leavingRows)

    /** The column, bottom-anchored: the newest row is item 0. */
    val listState = LazyListState()

    /** Harness: messages added after the messenger's, and a demo typing state. */
    var debugSuffix: List<Message> by mutableStateOf(emptyList())
    var debugPeerTyping: Boolean by mutableStateOf(false)

    /** The other side is writing: only in an accepted chat, and only when reported (PROTOCOL §8.4). */
    val isPeerTyping: Boolean get() = chat?.peer?.isAcceptedContact == true && debugPeerTyping

    /** The first-contact prompt: a safety number exists but was never verified, and no change waits for a decision. */
    val showsVerifyPrompt: Boolean get() = chat?.peer?.offersVerification ?: false

    /** The banner: the peer's safety number changed and sending is held until the user compares it. */
    val showsSafetyNumberChanged: Boolean get() = chat?.peer?.safetyNumberChanged ?: false

    /** The person is blocked (PROTOCOL §6.4): nothing can be sent, reacted or changed here (`ConversationBlocked.kt`). */
    val isBlocked: Boolean get() = chat?.peer?.isBlocked ?: false

    /**
     * A community's conversation (docs/COMMUNITIES.md, Extension v2): the same bubbles, composer,
     * pickers, viewers and actions, without what communities do not carry (reactions, replies,
     * places, Ghost).
     */
    val isCommunity: Boolean = CommunityChats.isCommunity(chatId)

    /** A community's members and management (the header's “…”). */
    var showsCommunityManage: Boolean by mutableStateOf(false)

    /** A community that ended (left elsewhere, removed, deleted): read only. */
    val isReadOnlyCommunity: Boolean get() = isCommunity && chat?.community?.isActive == false

    // Composer

    var isLoadingHistory by mutableStateOf(false)
        private set
    private var historyExhausted = false
    private var historyGeneration = 0

    suspend fun loadOlderMessages() {
        if (isLoadingHistory || historyExhausted || rows.isEmpty()) return
        isLoadingHistory = true
        val generation = historyGeneration
        try {
            val loaded = messenger.loadOlderMessages(chatId)
            if (generation == historyGeneration) historyExhausted = !loaded
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            // Keep current history; a later visit to the top retries the local read.
        } finally {
            isLoadingHistory = false
        }
    }

    var draft: String by mutableStateOf("")

    /** The quote above the composer after “Yanıtla”. */
    var replyTo: QuotedMessage? by mutableStateOf(null)
    var showsAttachMenu: Boolean by mutableStateOf(false)
        private set

    /** The sticker picker (the composer's sticker key): closes once a sticker is sent. */
    var showsStickerPicker: Boolean by mutableStateOf(false)

    /** The picker's “Son kullanılanlar”, newest first, each in the language it was sent in. */
    var stickerRecents: List<BrandStickerSelection> by mutableStateOf(emptyList())
        private set
    private var stickerRecentsGeneration = 0

    // Presented

    var actions: ActionsContext? by mutableStateOf(null)
        private set

    /** Drives the 5.3 lift and dim; [actions] stays set until the dismissal finished. */
    var actionsVisible: Boolean by mutableStateOf(false)
        private set

    /** The reaction bar shows the full emoji set (“+”). */
    var showsAllReactions: Boolean by mutableStateOf(false)
        private set
    var info: InfoItem? by mutableStateOf(null)
    var showsGhostSettings: Boolean by mutableStateOf(false)
    var cover: Cover? by mutableStateOf(null)
    var locationChoice: LocationChoice? by mutableStateOf(null)

    /** “Herkesten sil” waiting for its confirmation. */
    var deleteForEveryoneCandidate: MessageId? by mutableStateOf(null)

    /** Android: the live-location share sheet, when the share would stop as the app leaves (its notification cannot be seen). */
    var showsLiveShareSheet: Boolean by mutableStateOf(false)
        private set

    /** “Dosyalara kaydet”: the file waiting for the system's save dialog. */
    var pendingExport: MessageId? by mutableStateOf(null)

    /** A file being downloaded that opens once it is here (if the chat is still on screen). */
    var pendingFileOpen: MessageId? by mutableStateOf(null)
        private set

    /** Opening a file shows the ring around its tile. */
    val mediaActivity = ConversationMediaActivity()

    /** Bubble frames for the 5.3 lift (not observed). */
    val frames = BubbleFrameStore()

    /** The harness asks a row for its bubble frame without a long press. */
    var frameRequest: MessageId? by mutableStateOf(null)
        private set

    private var dismissJob: Job? = null

    // Voice

    /** The one voice message that plays (bubbles read it through `LocalVoicePlayback`). */
    val voicePlayer = VoicePlayback(voiceEngine, scope).apply {
        onError = { platform.haptics.error() }
    }

    /** The composer's voice recorder (5.1 mic). */
    val voice = VoiceComposer(
        voicePlayer,
        scope,
        voiceDevice,
        object : VoiceComposer.VoiceFeedback {
            override fun voice() = platform.haptics.voice()

            override fun error() = platform.haptics.error()

            override fun dismissKeyboard() = platform.dismissKeyboard()
        },
    ).also { composer ->
        composer.onSend = { recording -> sendVoice(recording) }
        voicePlayer.onPlaybackFailure = { composer.report(VoiceComposer.Hint.Unplayable) }
    }

    private var voiceLoad: Job? = null

    /** Follows the chat and its messages while the screen is shown. */
    suspend fun run() = coroutineScope {
        launch { messenger.chat(chatId).collect { chat = it } }
        launch { messenger.messages(chatId).collect { update(it, debugSuffix) } }
    }

    private fun update(live: List<Message>, suffix: List<Message>) {
        val all = if (suffix.isEmpty()) live else live + suffix
        if (all == messages && rows.size == all.size) return
        val nearBottom = isNearBottom()
        val newest = rows.lastOrNull()?.id
        val next = ConversationRows.rows(all)
        val changes = ConversationRows.changes(rows, next)
        val current = next.mapTo(HashSet()) { it.id }
        if (rows.isEmpty() || next.isEmpty() || rows.none { it.id in current }) {
            historyExhausted = false; historyGeneration++
        }
        val leaving = (leavingRows + changes.leaving.filter { item -> leavingRows.none { it.row.id == item.row.id } })
            .filter { it.row.id !in current }
        val kinds = kindChanges.toMutableMap()
        changes.kindChanges.forEach { (id, before) -> if (id !in kinds) kinds[id] = before }
        messages = all
        // A remote deletion/expiry may remove the long-pressed photo while its menu is open.
        // Drop the stale overlay too; otherwise only its empty dim/blur layer remains.
        actions?.let { context ->
            val target = all.lastOrNull { it.id == context.messageId }
            if (target == null) {
                dismissJob?.cancel()
                actionsVisible = false
                actions = null
                showsAllReactions = false
            }
        }
        rows = next
        leavingRows = leaving
        kindChanges = kinds
        arrivingRows = (arrivingRows + changes.arriving).intersect(current)
        val last = next.lastOrNull()
        if (last != null && last.id != newest) {
            // Near the bottom an arrival needs no scrolling: the new row grows in under the anchor.
            // What the user sends (or changes) also comes into view from further up; incoming
            // messages never yank someone reading further up.
            if (nearBottom) {
                listState.requestScrollToItem(0)
            } else if (newest != null && (last.message.isOutgoing || last.message.isSystem)) {
                scope.launch { listState.animateScrollToItem(0) }
            }
        }
    }

    /** The column is at (or within 80 dp of) its newest row. */
    private fun isNearBottom(): Boolean =
        listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 80 * density

    /** Pixels per dp of the screen (for [isNearBottom]); set by the screen. */
    var density: Float = 3f

    /** The harness added messages (demo arrivals). */
    fun appendDebug(message: Message) {
        debugSuffix = debugSuffix + message
        update(messenger.messages(chatId).value, debugSuffix)
    }

    /** The row finished growing in. */
    fun finishArrival(id: MessageId) {
        arrivingRows = arrivingRows - id
    }

    /** The bubble finished morphing into its new kind. */
    fun finishKindChange(id: MessageId) {
        kindChanges = kindChanges - id
    }

    /** The row finished collapsing: it leaves the column. */
    fun finishLeaving(id: MessageId) {
        val index = leavingRows.indexOfFirst { it.row.id == id }
        if (index < 0) return
        val anchor = leavingRows[index].after
        leavingRows = leavingRows.filterIndexed { i, _ -> i != index }.map { if (it.after == id) it.copy(after = anchor) else it }
    }

    // Composer

    val canSend: Boolean get() = draft.isNotBlank() && !isReadOnlyCommunity

    /** What the composer may hold: a community text is at most 8192 UTF-8 bytes. */
    fun editDraft(text: String) {
        if (isCommunity && text.encodeToByteArray().size > COMMUNITY_TEXT_BYTES) return
        draft = text
    }

    fun openCommunityManage() {
        if (!isCommunity) return
        dismissAttachMenu()
        platform.dismissKeyboard()
        showsCommunityManage = true
    }

    fun sendDraft() {
        if (!canSend) return
        val text = draft
        val quote = replyTo
        platform.haptics.send()
        draft = ""
        replyTo = null
        scope.launch { messenger.sendText(text, quote, chatId) }
    }

    fun cancelReply() {
        replyTo = null
    }

    fun toggleAttachMenu() {
        showsAttachMenu = !showsAttachMenu
    }

    fun dismissAttachMenu() {
        showsAttachMenu = false
    }

    fun send(attachment: ConversationAttachment) {
        dismissAttachMenu()
        platform.haptics.send()
        scope.launch { messenger.sendAttachment(attachment, chatId) }
    }

    /** The “+” menu's items. */
    enum class AttachmentKind { Photo, File, Location, LiveLocation }

    /**
     * The fixtures' attachments: the design's own content (the system pickers and the location
     * come with the live service, through [onPick]). “Fotoğraf ve video” sends three of the
     * design's pictures as one pick.
     */
    fun sendDemoAttachment(kind: AttachmentKind, onPick: (AttachmentKind) -> Unit = {}) {
        if (!messenger.sendsDesignContent) {
            dismissAttachMenu()
            when (kind) {
                AttachmentKind.Location -> {
                    platform.haptics.send()
                    scope.launch { messenger.sendCurrentLocation(chatId) }
                }
                AttachmentKind.LiveLocation -> shareLiveLocation()
                else -> onPick(kind)
            }
            return
        }
        when (kind) {
            AttachmentKind.Photo -> {
                dismissAttachMenu()
                sendPicked(DesignFixtures.pickedPhotos)
            }
            AttachmentKind.File -> send(ConversationAttachment.File("kira-sozlesmesi.pdf", 2_100_000, "PDF", 4))
            AttachmentKind.Location -> send(ConversationAttachment.Location("Moda İskelesi", "Kadıköy, İstanbul", 40.9846, 29.0249))
            AttachmentKind.LiveLocation -> shareLiveLocation()
        }
    }

    /**
     * “+” › Canlı konum, for an hour (as iOS). On Android a share goes on in the
     * background only while its notification can be seen; when it cannot, the share sheet says
     * first that sharing stops when the user leaves the app, and offers to allow notifications.
     */
    private fun shareLiveLocation() {
        if (messenger.liveSharingContinuesInBackground()) {
            send(ConversationAttachment.LiveLocation(1.hours))
            return
        }
        dismissAttachMenu()
        showsLiveShareSheet = true
    }

    /** The share sheet's “Yine de paylaş”: the share runs while the app is in front. */
    fun shareLiveLocationInFront() {
        showsLiveShareSheet = false
        send(ConversationAttachment.LiveLocation(1.hours))
    }

    /**
     * The share sheet's “Bildirimlere izin ver”: the system's question, then the share, whatever the
     * answer (never forced). When the system no longer asks, [openSettings] shows the app's
     * notification settings instead and nothing is shared: back from them, “Canlı konum” shares.
     */
    fun allowNoticeAndShareLiveLocation(openSettings: () -> Unit) {
        showsLiveShareSheet = false
        scope.launch {
            if (messenger.allowLiveSharingNotice() == NoticePermission.InSettings) {
                openSettings()
            } else {
                send(ConversationAttachment.LiveLocation(1.hours))
            }
        }
    }

    fun closeLiveShareSheet() {
        showsLiveShareSheet = false
    }

    /**
     * A pick from the photo picker (up to ten photos and videos): every item leaves as a message
     * of its own, in the order it was selected. Text in the composer becomes the first photo's
     * caption and the field clears, unless the pick has no photo or the text is too long for a caption.
     */
    fun sendPicked(items: List<PickedMediaItem>) {
        val planned = PickedMediaPlan.messages(items, draft)
        if (planned.isEmpty()) return
        platform.haptics.send()
        if (planned.any { it.caption != null }) draft = ""
        scope.launch { messenger.sendPickedMedia(planned, chatId) }
    }

    // Stickers (PROTOCOL §8.8)

    /** The composer's sticker key: the keyboard and “+” make way, the picker opens with the recents. */
    fun openStickerPicker() {
        dismissAttachMenu()
        platform.dismissKeyboard()
        showsStickerPicker = true
        loadStickerRecents()
    }

    /** Reads the recents from the service; a sticker sent while the read is under way wins. */
    fun loadStickerRecents() {
        val generation = stickerRecentsGeneration
        scope.launch {
            val ids = messenger.recentStickers()
            if (stickerRecentsGeneration != generation) return@launch
            stickerRecents = StickerRecents.selections(ids, StickerLanguage.forLocale(platform.strings.locale))
        }
    }

    fun closeStickerPicker() {
        showsStickerPicker = false
    }

    /** A sticker from the picker leaves (as a reply, when one is set) and the picker closes. */
    fun send(sticker: BrandStickerSelection) {
        val quote = replyTo
        platform.haptics.send()
        showsStickerPicker = false
        replyTo = null
        stickerRecentsGeneration++
        stickerRecents = StickerRecents.selections(
            StickerRecents.adding(sticker, stickerRecents.map { it.id }),
            StickerLanguage.forLocale(platform.strings.locale),
        )
        scope.launch { messenger.sendSticker(sticker, quote, chatId) }
    }

    // Voice messages

    /** A finished recording leaves (as a reply, when one is set); if it could not even be stored, the hint says so. */
    fun sendVoice(recording: VoiceRecording) {
        val quote = replyTo
        platform.haptics.send()
        replyTo = null
        scope.launch {
            val sent = messenger.sendVoice(recording, quote, chatId)
            if (!sent) voice.report(VoiceComposer.Hint.SendFailed)
        }
    }

    /**
     * A voice bubble's play / pause: the bytes are fetched (downloaded first if they are still on
     * the relay) and decrypted into memory, and play; whatever played before stops. The first play
     * of a received message marks it played.
     */
    fun toggleVoice(id: MessageId) {
        if (voicePlayer.activeId == id) {
            if (!voicePlayer.isLoading) voicePlayer.togglePause()
            return
        }
        val message = message(id)
        val content = message?.content as? MessageContent.Voice
        if (voice.isRecording || message == null || content == null) return
        when (message.mediaAvailability) {
            MediaAvailability.Ready, MediaAvailability.Remote, is MediaAvailability.Downloading -> Unit
            else -> {
                platform.haptics.error()
                return
            }
        }
        voiceLoad?.cancel()
        voicePlayer.begin(id, content.playedFraction)
        val marksPlayed = !message.isOutgoing && !message.isVoicePlayed
        voiceLoad = scope.launch {
            val payload = messenger.mediaPayload(id, chatId)
            if (!voicePlayer.play(payload, id)) return@launch
            if (marksPlayed) messenger.markVoicePlayed(id, chatId)
        }
    }

    /** The voice message that plays was deleted, expired or wiped underneath (or taken back). */
    val playingVoiceIsGone: Boolean
        get() {
            val id = voicePlayer.activeId ?: return false
            if (id == VoiceComposer.PREVIEW_ID) return false
            return message(id)?.content !is MessageContent.Voice
        }

    /** A voice message records or plays: the screen stays on. */
    val keepsScreenAwake: Boolean get() = voice.isRecording || voicePlayer.isPlaying

    /** The chat closes or the app leaves the foreground: a recording is thrown away, playback stops. */
    fun stopVoice() {
        voiceLoad?.cancel()
        voiceLoad = null
        voice.discardAll()
        voicePlayer.reset()
    }

    /** Only the voice message stops (other media opens, its message went). */
    fun stopVoicePlayback() {
        voiceLoad?.cancel()
        voiceLoad = null
        voicePlayer.stop()
    }

    fun resend(id: MessageId) {
        // Nothing reaches someone blocked (the bar offers “Engeli kaldır”).
        if (isBlocked) return
        platform.haptics.send()
        scope.launch { messenger.resend(id, chatId) }
    }

    // Blocked

    /**
     * “Engeli kaldır” in the bar. A blocked request becomes a request again and leaves 4.1 (the
     * screen then pops, as for any chat that goes).
     */
    fun unblock() {
        platform.haptics.selection()
        scope.launch { messenger.unblock(chatId) }
    }

    /**
     * The composer went (the person was blocked while the chat was open): the keyboard, the “+”
     * menu, the sticker picker, a reply quote and a recording in progress go with it. A voice message
     * that plays goes on.
     */
    fun leaveComposer() {
        platform.dismissKeyboard()
        dismissAttachMenu()
        showsStickerPicker = false
        replyTo = null
        voice.discardAll()
    }

    // 5.3 · Message actions

    fun canOpenActions(message: Message): Boolean = !message.isSystem

    fun openActions(message: Message, isTail: Boolean) {
        if (!canOpenActions(message) || actions != null) return
        val frame = frames[message.id] ?: return
        dismissAttachMenu()
        // The keyboard goes down; the menu needs the whole screen.
        platform.dismissKeyboard()
        platform.haptics.longPress()
        showsAllReactions = false
        actions = ActionsContext(message.id, isTail, frame)
    }

    /** Called by the overlay once it is on screen, so the bubble starts exactly where it was and glides from there. */
    fun liftActions() {
        if (actions == null || actionsVisible) return
        dismissJob?.cancel()
        actionsVisible = true
    }

    /** Opens the menu without a long press (harness), the bubble taken from its current frame. */
    fun openActionsWhenReady(id: MessageId) {
        frames.remove(id)
        frameRequest = id
        scope.launch {
            try {
                repeat(160) {
                    val message = message(id)
                    if (message != null && frames[id] != null) {
                        val index = messages.indexOfLast { it.id == id }.coerceAtLeast(0)
                        openActions(message, ConversationRows.isTail(index, messages))
                        return@launch
                    }
                    delay(50.milliseconds)
                }
            } finally {
                if (frameRequest == id) frameRequest = null
            }
        }
    }

    /** The menu fades away (0.24 s); [next] runs once it has gone. */
    fun dismissActions(next: (() -> Unit)? = null) {
        if (actions == null) {
            next?.invoke()
            return
        }
        actionsVisible = false
        dismissJob?.cancel()
        dismissJob = scope.launch {
            delay(ACTIONS_DISMISS_MILLIS.milliseconds)
            if (actionsVisible) return@launch
            actions = null
            showsAllReactions = false
            next?.invoke()
        }
    }

    /** The reaction the user has on a message, if any. */
    fun ownReaction(message: Message): String? = message.reactions.firstOrNull { it.includesMe }?.emoji

    fun react(emoji: String, id: MessageId) {
        val message = message(id) ?: return
        val current = ownReaction(message)
        platform.haptics.selection()
        dismissActions {
            scope.launch { messenger.setReaction(if (current == emoji) null else emoji, id, chatId) }
        }
    }

    fun showAllReactions() {
        showsAllReactions = true
    }

    /** “Yanıtla”: the quote slides up into the composer, with the author and kind texts in the UI language. */
    fun reply(id: MessageId) {
        if (isCommunity) return
        val quote = message(id)?.quote(ownUsername, peerUsername, platform.strings) ?: return
        dismissActions { replyTo = quote }
    }

    fun showInfo(id: MessageId) {
        dismissActions { info = InfoItem(id) }
    }

    fun copy(id: MessageId) {
        val message = message(id) ?: return
        if (message.isDisappearing) return
        val text = message.copyableText ?: return
        dismissActions {
            platform.copy(text)
            platform.haptics.success()
        }
    }

    fun askDeleteForEveryone(id: MessageId) {
        dismissActions { deleteForEveryoneCandidate = id }
    }

    fun confirmDeleteForEveryone() {
        val id = deleteForEveryoneCandidate ?: return
        deleteForEveryoneCandidate = null
        if (replyTo != null && message(id) == null) replyTo = null
        scope.launch { messenger.deleteForEveryone(id, chatId) }
    }

    fun deleteForMe(id: MessageId) {
        dismissActions { scope.launch { messenger.deleteForMe(id, chatId) } }
    }

    // 5.2 · Live location and places

    fun stopLiveLocation(id: MessageId) {
        platform.haptics.selection()
        scope.launch { messenger.stopLiveLocation(id, chatId) }
    }

    /** A tap on a place bubble: the “Haritada aç” chooser. */
    fun openLocation(message: Message) {
        val content = message.content as? MessageContent.Location ?: return
        if (!LivePosition(content.latitude, content.longitude).isValidCoordinate) return
        dismissAttachMenu()
        platform.dismissKeyboard()
        platform.haptics.selection()
        locationChoice = LocationChoice(message.id)
    }

    fun closeLocationChoice() {
        locationChoice = null
    }

    /** A tap on a running live-location bubble: the in-app map. */
    fun openLiveMap(message: Message) {
        val content = message.content as? MessageContent.LiveLocation ?: return
        if (!content.isActive) return
        dismissAttachMenu()
        platform.dismissKeyboard()
        platform.haptics.selection()
        cover = Cover.LiveMap(message.id)
    }

    /** The app left the foreground: the map and the chooser close (the lock follows on return). */
    fun closeLocationScreens() {
        closeLocationChoice()
        if (cover is Cover.LiveMap) cover = null
    }

    // 5.6 and the media covers

    fun openViewOnce(message: Message) {
        val content = message.content as? MessageContent.ViewOnce ?: return
        if (content.state != ViewOnceState.Unopened || message.isOutgoing || voice.isRecording) return
        stopVoicePlayback()
        cover = Cover.ViewOnce(message.id)
    }

    /** A tap on a photo (viewer), a video (player) or a file (preview). View-once media never comes here. */
    fun openMedia(message: Message) {
        // One sound at a time; nothing opens over a recording.
        if (voice.isRecording || message.content is MessageContent.Voice) return
        stopVoicePlayback()
        val availability = message.mediaAvailability
        when (val content = message.content) {
            is MessageContent.Photo -> {
                // Still being prepared here (or that failed): it opens once it is sent.
                if (availability == MediaAvailability.Preparing) return
                if (availability == MediaAvailability.PreparationFailed) {
                    platform.haptics.error()
                    return
                }
                cover = Cover.Photo(message.id)
            }
            is MessageContent.Video -> {
                if (availability == MediaAvailability.Preparing) return
                if (availability == MediaAvailability.Unavailable || availability == MediaAvailability.Held || availability == MediaAvailability.PreparationFailed) {
                    platform.haptics.error()
                    return
                }
                cover = Cover.Video(message.id)
            }
            is MessageContent.File -> {
                val kind = MediaFileKind.of(content.name)
                when {
                    kind == MediaFileKind.Movie && availability.isReady -> cover = Cover.Video(message.id)
                    MediaFileKind.opensInPhotoViewer(content.name) && availability.isReady -> cover = Cover.Photo(message.id)
                    else -> openFile(message)
                }
            }
            else -> Unit
        }
    }

    fun closeCover() {
        val item = cover ?: return
        cover = null
        if (item is Cover.ViewOnce) {
            // Closing deletes it on both sides; the bubble becomes “Açıldı”.
            scope.launch { messenger.markViewOnceOpened(item.messageId, chatId) }
        }
    }

    /** Here: the preview. On the relay: downloads with the ring around the tile, then opens. */
    fun openFile(message: Message) {
        if (message.content !is MessageContent.File) return
        when (message.mediaAvailability) {
            MediaAvailability.Ready -> cover = Cover.File(message.id)
            MediaAvailability.Remote -> {
                platform.haptics.selection()
                pendingFileOpen = message.id
                scope.launch { messenger.downloadMedia(message.id, chatId) }
            }
            is MediaAvailability.Downloading -> pendingFileOpen = message.id
            else -> platform.haptics.error()
        }
    }

    /** The file the user tapped while it downloaded is here now. */
    val pendingFileIsReady: Boolean
        get() = pendingFileOpen?.let { message(it)?.mediaAvailability == MediaAvailability.Ready } ?: false

    fun openPendingFile() {
        val id = pendingFileOpen ?: return
        val message = message(id) ?: return
        if (message.mediaAvailability != MediaAvailability.Ready) return
        pendingFileOpen = null
        openMedia(message)
    }

    /** 5.3 › “Dosyalara kaydet” (never for a disappearing message: a saved copy would outlive it). */
    fun saveToFiles(id: MessageId) {
        dismissActions {
            val message = message(id) ?: return@dismissActions
            if (message.isDisappearing || message.content !is MessageContent.File) return@dismissActions
            pendingExport = id
        }
    }

    /** The system's save dialog answered: the file's bytes go to [write]; a save tells the sender. */
    fun export(id: MessageId, write: (MediaPayload) -> Boolean) {
        pendingExport = null
        val message = message(id) ?: return
        mediaActivity.begin(id)
        scope.launch {
            val payload = messenger.mediaPayload(id, chatId)
            mediaActivity.end(id)
            if (payload == null || !write(payload)) {
                platform.haptics.error()
                return@launch
            }
            platform.haptics.success()
            if (!message.isOutgoing) messenger.markSaved(id, chatId)
        }
    }

    /** The media on screen in a cover was deleted, expired or wiped underneath, or the sender took it back. */
    val presentedMediaIsGone: Boolean
        get() {
            val item = cover ?: return false
            if (item is Cover.ViewOnce || item is Cover.LiveMap) return false
            return message(item.messageId)?.isDeleted ?: true
        }

    /** Ordinary media closes when its message goes or the app leaves the foreground. View-once keeps its own rules. */
    fun closeMedia() {
        if (cover != null && cover !is Cover.ViewOnce && cover !is Cover.LiveMap) cover = null
        pendingFileOpen = null
        pendingExport = null
    }

    // 5.5 · Ghost

    fun openGhostSettings() {
        dismissAttachMenu()
        showsGhostSettings = true
    }

    private var pendingGhostChange: Pair<GhostState, DisappearingTimer>? = null

    /** “Kaydet”: the sheet closes first; the change (and its system line) lands once the chat is visible again. */
    fun saveGhost(enabled: Boolean, period: GhostPeriod, disappearing: DisappearingTimer) {
        val chat = chat ?: return
        pendingGhostChange = chat.ghost.copy(isEnabled = enabled, period = period) to disappearing
        if (showsGhostSettings) showsGhostSettings = false else commitGhostChange()
    }

    /** The Ghost sheet finished closing. */
    fun commitGhostChange() {
        val (ghost, disappearing) = pendingGhostChange ?: return
        pendingGhostChange = null
        scope.launch { messenger.updateGhost(ghost, disappearing, chatId) }
    }

    companion object {
        /** The 5.3 dismissal (0.24 s). */
        const val ACTIONS_DISMISS_MILLIS = 240L

        /** A community text's limit (COMMUNITIES.md: 1..8192 UTF-8 bytes). */
        const val COMMUNITY_TEXT_BYTES = 8192
    }
}
