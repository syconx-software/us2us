import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// The state of one open conversation (05): what is drafted, what is
/// presented (5.3 actions, 5.4 info, 5.5 Ghost, 5.6 viewer, the “+” menu) and
/// every user action. Views render it; all data goes through
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

    /// What the full-screen viewer shows: view-once media (5.6) or an ordinary
    /// photo / video poster (“tap photo → media viewer”).
    struct ViewerItem: Identifiable, Equatable {
        enum Kind: Equatable {
            case viewOnce
            case media
        }

        let id = UUID()
        let kind: Kind
        let messageID: MessageID?
        let sender: String
        let sentAt: Date
        let media: MediaSource
        let isVideo: Bool
        /// Harness: start with the screenshot warning shown (the 5.6 reference).
        var showsScreenshotWarning = false
        /// Harness: start in the screen-recording state.
        var simulatesCapture = false
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

    /// The harness asks a row for its bubble frame without a long-press.
    private(set) var frameRequest: MessageID?

    /// Kept cheap: SwiftUI may create (and drop) a view model each time the
    /// screen's value is re-created; everything heavy happens on first use.
    init(chatID: ChatID, messenger: any MessengerService) {
        self.chatID = chatID
        self.messenger = messenger
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
    /// file importer and location come with the live backend).
    func sendDemoAttachment(_ kind: AttachmentKind) {
        if !messenger.sendsDesignContent {
            sendPicked(kind)
            return
        }
        switch kind {
        case .photo:
            send(.photo(.designArtwork(.nightPier), caption: nil))
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

    /// The live service: the system pickers (no photo-library permission —
    /// the picker runs out of process) and the current location (when-in-use,
    /// asked the first time).
    private func sendPicked(_ kind: AttachmentKind) {
        dismissAttachMenu()
        let chatID = chatID
        let messenger = messenger
        switch kind {
        case .photo:
            Task {
                guard let url = await SystemPickers.pickPhoto() else { return }
                ConversationHaptics.send()
                await messenger.send(attachment: .photo(.file(url), caption: nil), in: chatID)
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

    func sendSticker() {
        send(.textSticker("tamam!"))
    }

    func resend(_ id: MessageID) {
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

    // MARK: 5.6 · Viewers

    func openViewOnce(_ message: Message) {
        guard case .viewOnce(let isVideo, let state) = message.content, state == .unopened, !message.isOutgoing else { return }
        Task {
            guard let media = await messenger.viewOnceMedia(message.id, in: chatID) else { return }
            viewer = ViewerItem(
                kind: .viewOnce, messageID: message.id, sender: peerUsername, sentAt: message.sentAt, media: media,
                isVideo: isVideo)
        }
    }

    func openMedia(_ message: Message) {
        let media: MediaSource
        let isVideo: Bool
        switch message.content {
        case .photo(let source, _):
            media = source
            isVideo = false
        case .video(let poster, _, _):
            media = poster
            isVideo = true
        default:
            return
        }
        viewer = ViewerItem(
            kind: .media, messageID: message.id, sender: message.isOutgoing ? ownUsername : peerUsername,
            sentAt: message.sentAt, media: media, isVideo: isVideo)
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
