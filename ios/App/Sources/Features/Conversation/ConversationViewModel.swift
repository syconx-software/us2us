import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// The state of one open conversation (05): what is drafted, what is
/// presented (5.3 actions, 5.4 info, 5.5 Ghost, 5.6 viewer, the photo
/// viewer, the player, Quick Look, the “+” menu), the voice recorder and
/// the voice message that plays, and every user action. Views render it; all data goes through
/// `MessengerService`, so the live messenger replaces the fixtures without
/// touching the screens.
@Observable
@MainActor
final class ConversationViewModel {
    /// The long-pressed message and where its bubble was when the press ended.
    struct ActionsContext: Equatable {
        let messageID: MessageID
        let isTail: Bool
        let sourceFrame: CGRect
    }

    struct InfoItem: Identifiable, Equatable {
        let id: MessageID
    }

    /// What the full-screen cover shows: view-once media (5.6, its own
    /// viewer), an ordinary photo (“tap photo → media viewer”) or a video
    /// (“video → player”).
    struct ViewerItem: Identifiable, Equatable {
        enum Kind: Equatable {
            case viewOnce
            case photo
            case video
        }

        let id = UUID()
        let kind: Kind
        let messageID: MessageID?
        let sender: String
        let sentAt: Date
        /// What shows at once: the view-once media, the bubble's photo (its
        /// thumbnail or already the full photo), the video's poster.
        let media: MediaSource
        let isVideo: Bool
        var caption: String? = nil
        /// Video: the length the message announced (shown while it loads).
        var duration: TimeInterval = 0
        /// Harness: start with the screenshot warning shown (the 5.6 reference).
        var showsScreenshotWarning = false
        /// Harness: start in the screen-recording state.
        var simulatesCapture = false
        /// Harness: the photo starts zoomed in (double-tap zoom).
        var startsZoomed = false
        /// Harness: the video starts paused at this second.
        var startTime: TimeInterval? = nil
    }

    /// A file Quick Look will not show, or that could not be opened (floating sheet).
    struct FileNotice: Identifiable, Equatable {
        enum Kind: Equatable {
            case notPreviewable
            /// Not previewable, and in a disappearing message: it cannot be saved either.
            case notPreviewableDisappearing
            case openFailed
        }

        let id = UUID()
        let kind: Kind
        let messageID: MessageID
        let fileName: String
        /// The decrypted copy that “Dosyalara kaydet” would export.
        var savableURL: URL?
    }

    let chatID: ChatID
    let messenger: any MessengerService
    /// Bubble frames for the 5.3 lift (not observed).
    @ObservationIgnored let frames = BubbleFrameStore()

    var draft = ""
    /// The quote above the composer after “Yanıtla”.
    var replyTo: QuotedMessage?
    private(set) var actions: ActionsContext?
    /// Drives the 5.3 lift/dim; `actions` stays set until the dismissal finished.
    private(set) var actionsVisible = false
    /// The reaction bar shows the full emoji set (“+”).
    var showsAllReactions = false
    var info: InfoItem?
    var showsGhostSettings = false
    var viewer: ViewerItem?
    /// “Herkesten sil” waiting for its confirmation.
    var deleteForEveryoneCandidate: MessageID?
    var showsAttachMenu = false
    /// The sticker picker (the composer's sticker key): a sheet that closes
    /// once a sticker is sent, like iMessage's.
    var showsStickerPicker = false
    /// The picker's “Son kullanılanlar”, newest first, each in the language
    /// it was sent in (ids only behind it: `BrandStickerRecents`).
    private(set) var stickerRecents: [BrandStickerSelection] = []
    /// Counts stickers sent here: a read of the recents that started before
    /// one of them is older than the list and is dropped.
    @ObservationIgnored private var stickerRecentsGeneration = 0
    var fileNotice: FileNotice?
    /// A file being downloaded that opens once it is here (if the chat is still on screen).
    private(set) var pendingFileOpen: MessageID?
    /// The message whose file Quick Look or the exporter shows right now.
    private(set) var presentedFileMessage: MessageID?
    /// Opening a file (decrypting its copy) shows the ring around its tile.
    @ObservationIgnored let mediaActivity = ConversationMediaActivity()
    /// The UI language (7.1 › Dil) for what opens outside SwiftUI's
    /// environment (the protected file preview); the screen keeps it current.
    @ObservationIgnored var presentationLocale: Locale = .current {
        didSet { mediaSaver.locale = presentationLocale }
    }
    /// “Kaydet”, “Dosyalara kaydet” and the file preview's share sheet: the
    /// one-time explanation first, the sender told after (`MediaSaver`).
    @ObservationIgnored let mediaSaver: MediaSaver
    /// The one voice message that plays (bubbles read it from the environment).
    @ObservationIgnored let voicePlayer: VoicePlayback
    /// The composer's voice recorder (5.1 mic).
    @ObservationIgnored let voice: VoiceComposer
    /// The bytes of the voice message about to play, on their way.
    @ObservationIgnored private var voiceLoad: Task<Void, Never>?

    /// The harness asks a row for its bubble frame without a long-press.
    private(set) var frameRequest: MessageID?

    /// Kept cheap: SwiftUI may create (and drop) a view model each time the
    /// screen's value is re-created; everything heavy happens on first use
    /// (the recorder and the player touch no audio before they are used).
    init(chatID: ChatID, messenger: any MessengerService, saveNotice: (any SaveNoticeMemory)? = nil) {
        self.chatID = chatID
        self.messenger = messenger
        mediaSaver = MediaSaver(chatID: chatID, messenger: messenger, memory: saveNotice ?? StandardSaveNoticeMemory())
        let player = VoicePlayback()
        voicePlayer = player
        voice = VoiceComposer(player: player)
        voice.onSend = { [weak self] recording in self?.sendVoice(recording) }
        player.onPlaybackFailure = { [weak self] in self?.voice.report(.unplayable) }
    }

    // MARK: Reading

    var chat: ChatSummary? { messenger.chat(chatID) }

    /// The conversation, oldest first.
    var messages: [Message] {
        let live = messenger.messages(in: chatID)
        #if DEBUG
            return debugMessages(around: live)
        #else
            return live
        #endif
    }

    var ownUsername: String { messenger.ownUsername }
    var peerUsername: String { chat?.peer.username ?? chatID }

    func message(_ id: MessageID) -> Message? {
        messages.last { $0.id == id }
    }

    /// The rows of the message column, derived once per change of the
    /// conversation (tails and status sentences need the neighbours) and
    /// shared by every evaluation of the list until it changes again. The
    /// cache compares the source array first by storage, so an unchanged
    /// conversation costs nothing.
    var rows: [ConversationRow] {
        let source = messages
        if let cache = rowCache, cache.source == source { return cache.rows }
        let rows = Self.rows(for: source)
        if let old = rowCache?.rows { noteChanges(from: old, to: rows) }
        rowCache = (source, rows)
        return rows
    }

    @ObservationIgnored private var rowCache: (source: [Message], rows: [ConversationRow])?

    /// A removed message whose row is still collapsing in the column (5.3
    /// “Benden sil”), with the row it followed, so it keeps its place.
    struct LeavingRow: Equatable {
        let row: ConversationRow
        var after: MessageID?
    }

    /// Rows removed from the conversation that the column still shows while
    /// they collapse. Known as soon as `rows` reflects the removal, so the
    /// row never drops out for a frame before its collapse starts.
    @ObservationIgnored private(set) var leavingRows: [LeavingRow] = []

    /// Messages whose kind just changed (text → trace, live → ended, view
    /// once → opened), with how they were: their bubble morphs from that.
    @ObservationIgnored private(set) var kindChanges: [MessageID: Message] = [:]

    /// New messages at the end of the conversation whose rows are growing
    /// in (sent or received — not older history loading above).
    @ObservationIgnored private(set) var arrivingRows: Set<MessageID> = []

    /// One pass over the old and the new rows (both in conversation order):
    /// removed rows start leaving, rows whose kind changed start morphing,
    /// rows after the last one that was already there start arriving.
    private func noteChanges(from old: [ConversationRow], to new: [ConversationRow]) {
        let current = Set(new.lazy.map(\.id))
        var previous: MessageID?
        var j = new.startIndex
        var lastKept: Int?
        for row in old {
            if !current.contains(row.id) {
                if !leavingRows.contains(where: { $0.row.id == row.id }) {
                    leavingRows.append(LeavingRow(row: row, after: previous))
                }
            } else {
                while j < new.endIndex, new[j].id != row.id { j += 1 }
                if j < new.endIndex {
                    if new[j].message.contentKind != row.message.contentKind, kindChanges[row.id] == nil {
                        kindChanges[row.id] = row.message
                    }
                    lastKept = j
                    j += 1
                }
            }
            previous = row.id
        }
        // A message that came back (re-added) is no longer leaving.
        leavingRows.removeAll { current.contains($0.row.id) }
        let firstNew = lastKept.map { $0 + 1 } ?? (old.isEmpty ? new.startIndex : new.endIndex)
        for row in new[firstNew...] { arrivingRows.insert(row.id) }
        arrivingRows.formIntersection(current)
    }

    /// The row finished growing in.
    func finishArrival(_ id: MessageID) {
        arrivingRows.remove(id)
    }

    /// The bubble finished morphing into its new kind.
    func finishKindChange(_ id: MessageID) {
        kindChanges[id] = nil
    }

    /// The row finished collapsing: it leaves the column.
    func finishLeaving(_ id: MessageID) {
        guard let index = leavingRows.firstIndex(where: { $0.row.id == id }) else { return }
        let anchor = leavingRows[index].after
        leavingRows.remove(at: index)
        for i in leavingRows.indices where leavingRows[i].after == id { leavingRows[i].after = anchor }
    }

    /// `rows` with the leaving rows at their places.
    static func displayedRows(_ rows: [ConversationRow], leaving: [LeavingRow]) -> [ConversationRow] {
        guard !leaving.isEmpty else { return rows }
        var result = rows
        for item in leaving {
            if let after = item.after {
                let index = result.lastIndex { $0.id == after }.map { $0 + 1 } ?? result.count
                result.insert(item.row, at: index)
            } else {
                result.insert(item.row, at: 0)
            }
        }
        return result
    }

    static func rows(for messages: [Message]) -> [ConversationRow] {
        let sentences = statusSentenceIDs(in: messages)
        var rows: [ConversationRow] = []
        rows.reserveCapacity(messages.count)
        for index in messages.indices {
            let message = messages[index]
            rows.append(
                ConversationRow(
                    message: message, isTail: isTail(index, in: messages), showsStatusSentence: sentences.contains(message.id)))
        }
        return rows
    }

    /// The other side is writing — only in an accepted chat, and only when
    /// the messenger reports it (PROTOCOL §8.4: optional, ephemeral, never
    /// stored).
    var isPeerTyping: Bool {
        guard chat?.peer.isAcceptedContact == true else { return false }
        #if DEBUG
            if debugPeerTyping { return true }
        #endif
        return (messenger as? any PeerTypingReporting)?.isPeerTyping(in: chatID) ?? false
    }

    // MARK: Debug content (harness, `-U2StressMessages`)

    /// DEBUG harness only (always empty in release builds): messages added
    /// around the messenger's — older history (stress fixture, “load older”)
    /// and demo incoming messages — and a demo typing state.
    var debugPrefix: [Message] = []
    var debugSuffix: [Message] = []
    var debugPeerTyping = false

    #if DEBUG
        @ObservationIgnored private var didLoadStress = false
        @ObservationIgnored private var combined: (live: [Message], prefix: [Message], suffix: [Message], all: [Message])?

        private func debugMessages(around live: [Message]) -> [Message] {
            if !didLoadStress {
                didLoadStress = true
                let count = ConversationStressFixture.requestedCount
                if count > 0 {
                    // Not observed: this runs inside the first read.
                    _debugPrefix = ConversationStressFixture.messages(count: count, before: live.first?.sentAt ?? Date())
                }
            }
            let prefix = debugPrefix
            let suffix = debugSuffix
            guard !prefix.isEmpty || !suffix.isEmpty else { return live }
            // `==` on arrays is O(1) for unchanged storage.
            if let combined, combined.live == live, combined.prefix == prefix, combined.suffix == suffix {
                return combined.all
            }
            let all = prefix + live + suffix
            combined = (live, prefix, suffix, all)
            return all
        }
    #endif

    /// Only the newest outgoing message shows its state as a sentence (5.1).
    static func lastOutgoingID(in messages: [Message]) -> MessageID? {
        messages.last { $0.isOutgoing && !$0.isSystem && !$0.isDeleted }?.id
    }

    /// Messages that spell out their state under the bubble: the newest
    /// outgoing one while nothing has come back after it (once the other side
    /// has written, its state is evident — 5.2 shows no sentence), and every
    /// message that could not be delivered (“İletilemedi” + “Tekrar gönder”).
    static func statusSentenceIDs(in messages: [Message]) -> Set<MessageID> {
        var ids = Set(messages.filter { $0.isOutgoing && $0.status == .failed && !$0.isDeleted }.map(\.id))
        if let last = lastOutgoingID(in: messages), let index = messages.lastIndex(where: { $0.id == last }) {
            let answered = messages[(index + 1)...].contains { !$0.isSystem && !$0.isOutgoing }
            if !answered { ids.insert(last) }
        }
        return ids
    }

    /// The last bubble of a run of the same direction gets the tail (C15).
    static func isTail(_ index: Int, in messages: [Message]) -> Bool {
        let message = messages[index]
        if message.isSystem { return false }
        guard index + 1 < messages.count else { return true }
        let next = messages[index + 1]
        if next.isSystem { return true }
        return next.direction != message.direction
    }

    // MARK: Composer

    var canSend: Bool { !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    func sendDraft() {
        guard canSend else { return }
        let text = draft
        let quote = replyTo
        ConversationHaptics.send()
        // The field shrinks back to one line as the bubble leaves it.
        withU2Animation(U2Motion.micro) { draft = "" }
        withU2Animation(U2Motion.state) { replyTo = nil }
        Task { await messenger.send(text: text, replyTo: quote, in: chatID) }
    }

    func cancelReply() {
        withU2Animation(U2Motion.state) { replyTo = nil }
    }

    func toggleAttachMenu() {
        withU2Animation(showsAttachMenu ? U2Motion.soft(0.24) : U2Motion.spring) { showsAttachMenu.toggle() }
    }

    func dismissAttachMenu() {
        guard showsAttachMenu else { return }
        withU2Animation(U2Motion.soft(0.24)) { showsAttachMenu = false }
    }

    func send(_ attachment: ConversationAttachment) {
        dismissAttachMenu()
        ConversationHaptics.send()
        Task { await messenger.send(attachment: attachment, in: chatID) }
    }

    /// The fixtures' attachments: the design's own content (a real picker,
    /// file importer and location come with the live backend). “Fotoğraf ve
    /// video” sends three of the design's pictures as one pick.
    func sendDemoAttachment(_ kind: AttachmentKind) {
        if !messenger.sendsDesignContent {
            sendPicked(kind)
            return
        }
        switch kind {
        case .photo:
            dismissAttachMenu()
            send(picked: DesignFixtures.pickedPhotos)
        case .file:
            send(.file(name: "kira-sozlesmesi.pdf", byteCount: 2_100_000, typeLabel: "PDF", pageCount: 4))
        case .location:
            send(.location(name: "Moda İskelesi", locality: "Kadıköy, İstanbul", latitude: 40.9846, longitude: 29.0249))
        case .liveLocation:
            send(.liveLocation(duration: 3_600))
        }
    }

    enum AttachmentKind: CaseIterable {
        case photo, file, location, liveLocation
    }

    /// The live service: the system pickers (up to ten photos and videos at
    /// a time, files — no photo-library permission: the picker runs out of
    /// process) and the current location (when-in-use, asked the first time).
    private func sendPicked(_ kind: AttachmentKind) {
        dismissAttachMenu()
        let chatID = chatID
        let messenger = messenger
        switch kind {
        case .photo:
            Task {
                let items = await SystemPickers.pickMedia()
                send(picked: items)
            }
        case .file:
            Task {
                guard let url = await SystemPickers.pickDocument() else { return }
                ConversationHaptics.send()
                await messenger.send(attachment: .document(url), in: chatID)
            }
        case .location:
            ConversationHaptics.send()
            Task { await messenger.sendCurrentLocation(in: chatID) }
        case .liveLocation:
            send(.liveLocation(duration: 3_600))
        }
    }

    // MARK: Stickers (PROTOCOL §8.8)

    /// The composer's sticker key: the keyboard and “+” make way, the
    /// picker opens with the recents (read when the conversation opened,
    /// and again now).
    func openStickerPicker() {
        dismissAttachMenu()
        U2Keyboard.dismiss()
        showsStickerPicker = true
        loadStickerRecents()
    }

    /// Reads the recents from the service (the vault's encrypted state in
    /// the live app). Called when the conversation opens too, so the sheet
    /// rises with “Son kullanılanlar” already in place. A sticker sent while
    /// the read is under way wins: the older list is not put back.
    func loadStickerRecents() {
        let messenger = messenger
        let locale = presentationLocale
        let generation = stickerRecentsGeneration
        Task { [weak self] in
            let ids = await messenger.recentStickers()
            guard let self, self.stickerRecentsGeneration == generation else { return }
            self.stickerRecents = BrandStickerRecents.selections(from: ids, ui: BrandStickerLanguage(locale: locale))
        }
    }

    func closeStickerPicker() {
        showsStickerPicker = false
    }

    /// A tap (or “Gönder” under the preview) in the picker: the sticker
    /// leaves with the send tap (as a reply, when one is set, like a voice
    /// message) and the picker closes. It goes first in the recents.
    func send(sticker: BrandStickerSelection) {
        let quote = replyTo
        ConversationHaptics.send()
        showsStickerPicker = false
        if quote != nil { withU2Animation(U2Motion.state) { replyTo = nil } }
        stickerRecentsGeneration += 1
        stickerRecents = BrandStickerRecents.selections(
            from: BrandStickerRecents.adding(sticker, to: stickerRecents.map(\.id)),
            ui: BrandStickerLanguage(locale: presentationLocale))
        let messenger = messenger
        let chatID = chatID
        Task { await messenger.send(sticker: sticker, replyTo: quote, in: chatID) }
    }

    // MARK: Voice messages

    /// A finished recording leaves (as a reply, when one is set): its bubble
    /// shows at once, “Gönderiliyor” like any message. If it could not even
    /// be stored (the app locked meanwhile, a refused contact), the hint says
    /// so — the recording is gone, nothing waits to be sent.
    func sendVoice(_ recording: VoiceRecording) {
        let quote = replyTo
        ConversationHaptics.send()
        withU2Animation(U2Motion.state) { replyTo = nil }
        let messenger = messenger
        let chatID = chatID
        Task { [weak self] in
            let sent = await messenger.send(voice: recording, replyTo: quote, in: chatID)
            if !sent { self?.voice.report(.sendFailed) }
        }
    }

    /// A voice bubble's play / pause: the bytes are fetched (downloaded
    /// first if they are still on the relay) and decrypted into memory, and
    /// play; whatever played before stops. The first play of a received
    /// message marks it played.
    func toggleVoice(_ id: MessageID) {
        if voicePlayer.activeID == id {
            if !voicePlayer.isLoading { voicePlayer.togglePause() }
            return
        }
        guard !voice.isRecording, let message = message(id), case .voice(_, _, let played) = message.content else {
            return
        }
        switch message.mediaAvailability {
        case .ready, .remote, .downloading:
            break
        case .held, .unavailable, .preparing, .preparationFailed:
            U2Haptics.error()
            return
        }
        voiceLoad?.cancel()
        voicePlayer.begin(id, startFraction: played)
        let marksPlayed = !message.isOutgoing && !message.isVoicePlayed
        let messenger = messenger
        let chatID = chatID
        voiceLoad = Task { [weak self] in
            let payload = await messenger.mediaPayload(id, in: chatID)
            guard let self, !Task.isCancelled else { return }
            guard self.voicePlayer.play(payload, for: id) else { return }
            if marksPlayed { await messenger.markVoicePlayed(id, in: chatID) }
        }
    }

    /// The voice message that plays was deleted, expired or wiped
    /// underneath (or taken back: “Herkesten sil” leaves only the trace).
    var playingVoiceIsGone: Bool {
        guard let id = voicePlayer.activeID, id != VoiceComposer.previewID else { return false }
        guard let message = message(id), case .voice = message.content else { return true }
        return false
    }

    /// A voice message records or plays: the screen stays on. Auto-lock would
    /// send the app to the background, which throws a hands-free recording
    /// away and stops playback.
    var keepsScreenAwake: Bool {
        voice.isRecording || voicePlayer.isPlaying
    }

    /// The chat closes or the app leaves the foreground: a recording is
    /// thrown away, playback stops and lets go of its bytes — nothing waits
    /// behind the lock.
    func stopVoice() {
        voiceLoad?.cancel()
        voiceLoad = nil
        voice.discardAll()
        voicePlayer.reset()
    }

    /// Only the voice message stops (other media opens, its message went).
    func stopVoicePlayback() {
        voiceLoad?.cancel()
        voiceLoad = nil
        voicePlayer.stop()
    }

    func resend(_ id: MessageID) {
        // Nothing reaches someone blocked (the bar offers “Engeli kaldır”).
        guard !isBlocked else { return }
        ConversationHaptics.send()
        Task { await messenger.resend(id, in: chatID) }
    }

    // MARK: 5.3 · Message actions

    func canOpenActions(_ message: Message) -> Bool {
        !message.isSystem
    }

    func openActions(for message: Message, isTail: Bool) {
        guard canOpenActions(message), actions == nil, let frame = frames.frame(for: message.id) else { return }
        dismissAttachMenu()
        // The keyboard goes down; the menu needs the whole screen.
        U2Keyboard.dismiss()
        U2Haptics.longPress()
        showsAllReactions = false
        actions = ActionsContext(messageID: message.id, isTail: isTail, sourceFrame: frame)
    }

    /// Called by the overlay once it is on screen, so the bubble starts
    /// exactly where it was and glides from there.
    func liftActions() {
        guard actions != nil, !actionsVisible else { return }
        withU2Animation(U2Motion.spring) { actionsVisible = true }
    }

    /// Opens the menu without a long-press (harness), the bubble taken from its current frame.
    func openActionsWhenReady(for id: MessageID) {
        // Rows report their bubble's frame only while pressed (no geometry
        // work per row while scrolling) — or when asked, like here.
        frames.remove(id)
        frameRequest = id
        Task { @MainActor in
            defer { if frameRequest == id { frameRequest = nil } }
            for _ in 0..<160 {
                if let message = message(id), frames.frame(for: id) != nil {
                    let all = messages
                    let index = all.lastIndex { $0.id == id } ?? 0
                    openActions(for: message, isTail: Self.isTail(index, in: all))
                    return
                }
                try? await Task.sleep(for: .milliseconds(50))
            }
        }
    }

    func dismissActions(then next: (@MainActor () -> Void)? = nil) {
        guard actions != nil else {
            next?()
            return
        }
        withU2Animation(U2Motion.soft(0.24)) {
            actionsVisible = false
        } completion: { [weak self] in
            guard let self, !self.actionsVisible else { return }
            self.actions = nil
            self.showsAllReactions = false
            next?()
        }
    }

    /// The reaction the user has on a message, if any.
    func ownReaction(on message: Message) -> String? {
        message.reactions.first { $0.includesMe }?.emoji
    }

    func react(_ emoji: String, to id: MessageID) {
        guard let message = message(id) else { return }
        let current = ownReaction(on: message)
        U2Haptics.selection()
        dismissActions { [weak self] in
            guard let self else { return }
            Task { await self.messenger.setReaction(current == emoji ? nil : emoji, on: id, in: self.chatID) }
        }
    }

    func showAllReactions() {
        withU2Animation(U2Motion.state) { showsAllReactions = true }
    }

    /// “Yanıtla”: the quote slides up into the composer (0.3 s). The
    /// quote with the author/kind texts in the UI language.
    func reply(to id: MessageID, locale: Locale) {
        guard let message = message(id),
            let quote = message.quote(ownUsername: ownUsername, peerUsername: peerUsername, locale: locale)
        else { return }
        dismissActions { [weak self] in
            withU2Animation(U2Motion.soft(0.3)) { self?.replyTo = quote }
        }
    }

    func showInfo(for id: MessageID) {
        dismissActions { [weak self] in self?.info = InfoItem(id: id) }
    }

    func copy(_ id: MessageID) {
        guard let message = message(id), !message.isDisappearing, let text = message.copyableText else { return }
        dismissActions {
            // This device only (no Universal Clipboard), and gone after 3 minutes.
            UIPasteboard.general.setItems(
                [[UTType.utf8PlainText.identifier: text]],
                options: [.localOnly: true, .expirationDate: Date().addingTimeInterval(180)])
            U2Haptics.success()
        }
    }

    func askDeleteForEveryone(_ id: MessageID) {
        dismissActions { [weak self] in self?.deleteForEveryoneCandidate = id }
    }

    func confirmDeleteForEveryone() {
        guard let id = deleteForEveryoneCandidate else { return }
        deleteForEveryoneCandidate = nil
        if replyTo != nil, message(id) == nil { replyTo = nil }
        Task { await messenger.deleteForEveryone(id, in: chatID) }
    }

    func deleteForMe(_ id: MessageID) {
        dismissActions { [weak self] in
            guard let self else { return }
            Task { await self.messenger.deleteForMe(id, in: self.chatID) }
        }
    }

    // MARK: 5.2 · Live location

    func stopLiveLocation(_ id: MessageID) {
        U2Haptics.selection()
        Task { await messenger.stopLiveLocation(id, in: chatID) }
    }

    // MARK: 5.2 · Location → maps (proposed)

    /// The live-location map, opened on a share.
    struct LiveMapItem: Identifiable, Equatable {
        let id: MessageID
    }

    /// The floating “Haritada aç” chooser of a tapped place.
    var locationChoice: MapChoice?
    /// The full-screen live-location map.
    var liveMap: LiveMapItem?
    /// The app picked in the chooser: it opens once the sheet is down.
    @ObservationIgnored private var pendingMapLaunch: MapLaunch?

    /// A tap on a place bubble: Apple Maps or Google Maps (the chooser says
    /// the location is handed to the chosen app).
    func openLocation(_ message: Message) {
        guard case .location(let name, _, let latitude, let longitude) = message.content,
            LivePosition(latitude: latitude, longitude: longitude).isValidCoordinate
        else { return }
        dismissAttachMenu()
        U2Keyboard.dismiss()
        U2Haptics.selection()
        pendingMapLaunch = nil
        locationChoice = MapChoice(
            place: MapPlace(name: name, latitude: latitude, longitude: longitude),
            hasGoogleMapsApp: MapLauncher.hasGoogleMapsApp, isDisappearing: message.isDisappearing)
    }

    func chooseMapApp(_ app: MapApp) {
        guard let choice = locationChoice else { return }
        U2Haptics.selection()
        pendingMapLaunch = MapLaunch(app: app, place: choice.place)
        locationChoice = nil
    }

    func closeLocationChoice() {
        pendingMapLaunch = nil
        locationChoice = nil
    }

    /// The chooser finished closing: the chosen app opens now — never over
    /// the sheet, and not once the app has left the foreground.
    func locationChoiceDidDismiss() {
        guard let launch = pendingMapLaunch else { return }
        pendingMapLaunch = nil
        guard UIApplication.shared.applicationState == .active else { return }
        MapLauncher.open(launch.place, in: launch.app)
    }

    /// A tap on a running live-location bubble: the in-app map.
    func openLiveMap(_ message: Message) {
        guard case .liveLocation(_, let isActive) = message.content, isActive else { return }
        dismissAttachMenu()
        U2Keyboard.dismiss()
        U2Haptics.selection()
        liveMap = LiveMapItem(id: message.id)
    }

    func closeLiveMap() {
        liveMap = nil
    }

    /// The app left the foreground: the map and the chooser close (the lock
    /// follows on return; no position waits behind it).
    func closeLocationScreens() {
        closeLocationChoice()
        liveMap = nil
    }

    // MARK: 5.6 · Viewers

    func openViewOnce(_ message: Message) {
        guard case .viewOnce(let isVideo, let state) = message.content, state == .unopened, !message.isOutgoing else { return }
        guard !voice.isRecording else { return }
        stopVoicePlayback()
        Task {
            guard let media = await messenger.viewOnceMedia(message.id, in: chatID) else { return }
            viewer = ViewerItem(
                kind: .viewOnce, messageID: message.id, sender: peerUsername, sentAt: message.sentAt, media: media,
                isVideo: isVideo)
        }
    }

    /// A tap on a photo (viewer), a video (player) or a file (Quick Look;
    /// a video file plays). View-once media never comes here (`openViewOnce`).
    func openMedia(_ message: Message) {
        // One sound at a time; nothing opens over a recording.
        guard !voice.isRecording else { return }
        if case .voice = message.content { return }
        stopVoicePlayback()
        let sender = message.isOutgoing ? ownUsername : peerUsername
        switch message.content {
        case .photo(let source, let caption):
            // Still being prepared here (or that failed): it opens once it is sent.
            if message.mediaAvailability == .preparing { return }
            guard message.mediaAvailability != .preparationFailed else {
                U2Haptics.error()
                return
            }
            viewer = ViewerItem(
                kind: .photo, messageID: message.id, sender: sender, sentAt: message.sentAt, media: source, isVideo: false,
                caption: caption)
        case .video(let poster, let duration, _):
            // Still being prepared here (or that failed): nothing to play yet.
            if message.mediaAvailability == .preparing { return }
            guard message.mediaAvailability != .unavailable, message.mediaAvailability != .held,
                message.mediaAvailability != .preparationFailed
            else {
                U2Haptics.error()
                return
            }
            viewer = ViewerItem(
                kind: .video, messageID: message.id, sender: sender, sentAt: message.sentAt, media: poster, isVideo: true,
                duration: duration)
        case .file(let name, _, _, _):
            if MediaFileKind(fileName: name) == .movie, message.mediaAvailability.isReady {
                viewer = ViewerItem(
                    kind: .video, messageID: message.id, sender: sender, sentAt: message.sentAt, media: .memory(Data()),
                    isVideo: true)
            } else if MediaFileKind.opensInPhotoViewer(fileName: name), message.mediaAvailability.isReady {
                // An image sent as a file: the photo viewer, not Quick Look.
                viewer = ViewerItem(
                    kind: .photo, messageID: message.id, sender: sender, sentAt: message.sentAt, media: .memory(Data()),
                    isVideo: false)
            } else {
                openFile(message)
            }
        default:
            return
        }
    }

    func viewerDidDetectScreenshot(_ item: ViewerItem) {
        guard item.kind == .viewOnce, let id = item.messageID else { return }
        Task { await messenger.reportScreenshot(of: id, in: chatID) }
    }

    func closeViewer() {
        guard let item = viewer else { return }
        viewer = nil
        if item.kind == .viewOnce, let id = item.messageID {
            // Closing deletes it on both sides; the bubble becomes “Açıldı”.
            Task { await messenger.markViewOnceOpened(id, in: chatID) }
        }
    }

    // MARK: 5.2 · Files

    /// Here: Quick Look (or “Dosyalara kaydet” for what it cannot show).
    /// On the relay: downloads with the ring around the tile, then opens.
    func openFile(_ message: Message) {
        guard case .file(let name, _, _, _) = message.content else { return }
        switch message.mediaAvailability {
        case .ready:
            presentFile(message.id, name: name, exporting: false)
        case .remote:
            U2Haptics.selection()
            pendingFileOpen = message.id
            Task { await messenger.downloadMedia(message.id, in: chatID) }
        case .downloading:
            pendingFileOpen = message.id
        case .held, .unavailable, .preparing, .preparationFailed:
            U2Haptics.error()
        }
    }

    /// 5.3 › “Dosyalara kaydet” (never for a disappearing message: a saved
    /// copy would outlive it).
    func saveToFiles(_ id: MessageID) {
        dismissActions { [weak self] in
            guard let self, let message = self.message(id), !message.isDisappearing,
                case .file(let name, _, _, _) = message.content
            else { return }
            self.presentFile(id, name: name, exporting: true)
        }
    }

    /// The file the user tapped while it downloaded is here now.
    var pendingFileIsReady: Bool {
        guard let id = pendingFileOpen else { return false }
        return message(id)?.mediaAvailability == .ready
    }

    func openPendingFile() {
        guard let id = pendingFileOpen, let message = message(id), message.mediaAvailability == .ready else { return }
        pendingFileOpen = nil
        openMedia(message)
    }

    private func presentFile(_ id: MessageID, name: String, exporting: Bool) {
        guard !mediaActivity.isOpening(id), presentedFileMessage == nil else { return }
        mediaActivity.begin(id)
        let messenger = messenger
        let chatID = chatID
        Task { [weak self] in
            let url = await messenger.previewFile(id, in: chatID)
            guard let self else {
                if let url { messenger.discardPreviewFile(url) }
                return
            }
            self.mediaActivity.end(id)
            guard let url else {
                U2Haptics.error()
                self.showNotice(FileNotice(kind: .openFailed, messageID: id, fileName: name))
                return
            }
            guard UIApplication.shared.applicationState != .background, let message = self.message(id) else {
                messenger.discardPreviewFile(url)
                return
            }
            // A disappearing message's file is shown, never handed on: Quick
            // Look without its share button, and nothing to save (5.3 turns
            // copying off for the same reason).
            let isDisappearing = message.isDisappearing
            if exporting {
                guard !isDisappearing else {
                    messenger.discardPreviewFile(url)
                    return
                }
                self.export(url, of: id)
            } else if FilePreviewPresenter.canPreview(url) {
                self.presentedFileMessage = id
                // Shared only through the app's own button (the sender is told).
                var share: (@MainActor () -> Void)?
                if !isDisappearing { share = { [weak self] in self?.shareFile(id, from: url) } }
                let shown = FilePreviewPresenter.present(
                    url, title: name, onShare: share, locale: self.presentationLocale
                ) { [weak self] in
                    messenger.discardPreviewFile(url)
                    if self?.presentedFileMessage == id { self?.presentedFileMessage = nil }
                }
                if !shown {
                    self.presentedFileMessage = nil
                    messenger.discardPreviewFile(url)
                }
            } else if isDisappearing {
                messenger.discardPreviewFile(url)
                self.showNotice(FileNotice(kind: .notPreviewableDisappearing, messageID: id, fileName: name))
            } else {
                self.showNotice(FileNotice(kind: .notPreviewable, messageID: id, fileName: name, savableURL: url))
            }
        }
    }

    /// The Files exporter, after the one-time explanation; a save tells the
    /// sender (`MediaSaver`). The copy goes either way.
    private func export(_ url: URL, of id: MessageID) {
        guard let message = message(id) else {
            messenger.discardPreviewFile(url)
            return
        }
        presentedFileMessage = id
        let messenger = messenger
        let saver = mediaSaver
        Task { [weak self] in
            let outcome = await saver.exportFile(message, from: url)
            messenger.discardPreviewFile(url)
            if outcome == .saved { U2Haptics.success() }
            if self?.presentedFileMessage == id { self?.presentedFileMessage = nil }
        }
    }

    /// The notice's “Dosyalara kaydet”: the exporter opens once the sheet is down.
    func saveNoticeFile() {
        guard let notice = fileNotice, notice.savableURL != nil else { return }
        exportsAfterNotice = true
        fileNotice = nil
    }

    func closeFileNotice() {
        fileNotice = nil
    }

    /// The notice's sheet finished closing: export, or its copy goes.
    func fileNoticeDidDismiss() {
        guard let notice = shownNotice else { return }
        shownNotice = nil
        let exports = exportsAfterNotice
        exportsAfterNotice = false
        guard let url = notice.savableURL else { return }
        if exports, message(notice.messageID) != nil, UIApplication.shared.applicationState != .background {
            export(url, of: notice.messageID)
        } else {
            messenger.discardPreviewFile(url)
        }
    }

    private func showNotice(_ notice: FileNotice) {
        if let previous = shownNotice?.savableURL, previous != notice.savableURL { messenger.discardPreviewFile(previous) }
        shownNotice = notice
        exportsAfterNotice = false
        fileNotice = notice
    }

    @ObservationIgnored private var shownNotice: FileNotice?
    @ObservationIgnored private var exportsAfterNotice = false

    // MARK: Media lifecycle

    /// The message on screen in the viewer, the player or Quick Look was
    /// deleted, expired or wiped underneath — or the sender took it back
    /// (“Herkesten sil” leaves only the trace).
    var presentedMediaIsGone: Bool {
        func isGone(_ id: MessageID) -> Bool { message(id)?.isDeleted ?? true }
        if let viewer, viewer.kind != .viewOnce, let id = viewer.messageID, isGone(id) { return true }
        if let id = presentedFileMessage, isGone(id) { return true }
        if let notice = fileNotice, isGone(notice.messageID) { return true }
        return false
    }

    /// Ordinary media closes when its message goes or the app leaves the
    /// foreground (the lock follows on return): nothing decrypted waits
    /// behind it. View-once keeps its own rules (5.6 closes by its button).
    func closeMedia() {
        if let viewer, viewer.kind != .viewOnce { self.viewer = nil }
        FilePreviewPresenter.dismiss(animated: false)
        FileExporter.dismiss(animated: false)
        presentedFileMessage = nil
        if let url = shownNotice?.savableURL { messenger.discardPreviewFile(url) }
        shownNotice = nil
        exportsAfterNotice = false
        fileNotice = nil
        pendingFileOpen = nil
    }

    // MARK: 5.5 · Ghost

    func openGhostSettings() {
        dismissAttachMenu()
        showsGhostSettings = true
    }

    /// “Kaydet”: the sheet closes first; the change (and its system line,
    /// fading in) lands once the chat is visible again.
    func saveGhost(enabled: Bool, period: GhostPeriod, disappearing: DisappearingTimer) {
        guard let chat else { return }
        var ghost = chat.ghost
        ghost.isEnabled = enabled
        ghost.period = period
        pendingGhostChange = (ghost, disappearing)
        if showsGhostSettings {
            showsGhostSettings = false
        } else {
            commitGhostChange()
        }
    }

    /// The Ghost sheet finished closing.
    func commitGhostChange() {
        guard let change = pendingGhostChange else { return }
        pendingGhostChange = nil
        Task { await messenger.updateGhost(change.ghost, disappearing: change.disappearing, in: chatID) }
    }

    @ObservationIgnored private var pendingGhostChange: (ghost: GhostState, disappearing: DisappearingTimer)?
}

/// One row of the 5.1 column: the message and how the column draws it.
struct ConversationRow: Identifiable, Equatable {
    let message: Message
    /// The last bubble of a run (C15 tail).
    let isTail: Bool
    /// The state spelled out under the bubble (5.1 item 8; failed messages).
    let showsStatusSentence: Bool

    var id: MessageID { message.id }
}
