import SwiftUI
import UIKit

/// The contact screen's state (5.1 › the person in the header, proposed):
/// which tab shows — Doğrulama (6.1, as it was), Fotoğraflar, Belgeler — the
/// two media lists, newest first, a page at a time, and what opens from
/// them: the photo viewer and the player, swiping through the grid
/// (`MediaGalleryViewer`), and a file's preview. All data comes through
/// `MessengerService.media(in:kind:before:limit:)`; nothing is kept beyond
/// this screen.
@Observable
@MainActor
final class ContactMediaModel: MediaViewerContext {
    enum Tab: String, Hashable, CaseIterable, Sendable {
        case verification, photos, documents

        var mediaKind: ChatMediaKind? {
            switch self {
            case .verification: nil
            case .photos: .visual
            case .documents: .documents
            }
        }
    }

    /// One list as loaded so far.
    struct Page: Equatable {
        var items: [Message] = []
        var isLoading = false
        /// The last page was full: there may be older items.
        var hasMore = true
        var hasLoaded = false
    }

    /// The gallery over the grid, opened at one item.
    struct GalleryItem: Identifiable, Equatable {
        enum Pages: Equatable {
            /// Every photo and video of the grid.
            case visual
            /// Only this one (a video or an image sent as a file, from Belgeler).
            case single
        }

        let id = UUID()
        let startID: MessageID
        let pages: Pages
    }

    /// Items per request.
    static let pageSize = 30
    /// The most a refresh reads again at once (the runtime's page).
    static let refreshLimit = 500
    /// The next page loads when an item this close to the end appears.
    static let prefetchDistance = 9

    let chatID: ChatID
    let messenger: any MessengerService
    @ObservationIgnored let mediaSaver: MediaSaver

    var tab: Tab = .verification
    private(set) var visual = Page()
    private(set) var documents = Page()
    /// The viewer / player over the grid.
    var gallery: GalleryItem?
    /// A file Quick Look will not show (save it instead), or that failed.
    var fileNotice: ConversationViewModel.FileNotice?
    /// The file being decrypted for its preview (the row shows the ring).
    private(set) var openingFile: MessageID?
    /// The file whose preview or exporter is up.
    private(set) var presentedFile: MessageID?
    /// The UI language for what opens outside SwiftUI (the file preview,
    /// the floating sheets); the screen keeps it current.
    @ObservationIgnored var presentationLocale: Locale = .current {
        didSet { mediaSaver.locale = presentationLocale }
    }

    /// A reload replaces what a request that started before it would bring.
    @ObservationIgnored private var generations: [ChatMediaKind: Int] = [:]
    @ObservationIgnored private var shownNotice: ConversationViewModel.FileNotice?
    @ObservationIgnored private var exportsAfterNotice = false

    init(chatID: ChatID, messenger: any MessengerService, saveNotice: any SaveNoticeMemory) {
        self.chatID = chatID
        self.messenger = messenger
        mediaSaver = MediaSaver(chatID: chatID, messenger: messenger, memory: saveNotice)
    }

    var peerUsername: String { messenger.peer(for: chatID)?.username ?? chatID }
    var ownUsername: String { messenger.ownUsername }

    // MARK: Lists

    func page(_ kind: ChatMediaKind) -> Page {
        switch kind {
        case .visual: visual
        case .documents: documents
        }
    }

    private func setPage(_ kind: ChatMediaKind, _ change: (inout Page) -> Void) {
        switch kind {
        case .visual: change(&visual)
        case .documents: change(&documents)
        }
    }

    /// The list as it is now: an item the open conversation knows shows its
    /// current state (downloaded, saved), and one deleted meanwhile (or
    /// turned into something else) is gone.
    func items(_ kind: ChatMediaKind) -> [Message] {
        let loaded = page(kind).items
        guard !loaded.isEmpty else { return [] }
        let live = Dictionary(messenger.messages(in: chatID).map { ($0.id, $0) }, uniquingKeysWith: { _, newer in newer })
        return loaded.compactMap { item in
            let current = live[item.id] ?? item
            return Self.belongs(current, to: kind) ? current : nil
        }
    }

    /// What each tab lists: never view-once, disappearing (§8.1: a list kept
    /// outside the conversation must never show one past its deadline),
    /// deleted, pending or system lines.
    static func belongs(_ message: Message, to kind: ChatMediaKind) -> Bool {
        guard !message.isDeleted, !message.isSystem, !message.isPendingMedia, !message.isDisappearing else {
            return false
        }
        switch (message.content, kind) {
        case (.photo, .visual), (.video, .visual), (.file, .documents): return true
        default: return false
        }
    }

    /// The first page, once (the tab appears).
    func loadFirstPageIfNeeded(_ kind: ChatMediaKind) async {
        guard !page(kind).hasLoaded, !page(kind).isLoading else { return }
        await reload(kind)
    }

    /// From the newest again (the screen came back, the chat changed).
    func reload(_ kind: ChatMediaKind) async {
        let generation = (generations[kind] ?? 0) + 1
        generations[kind] = generation
        setPage(kind) { $0.isLoading = true }
        let fetched = await messenger.media(in: chatID, kind: kind, before: nil, limit: Self.pageSize)
        guard generations[kind] == generation else { return }
        setPage(kind) { page in
            page.items = Self.unique(fetched.filter { Self.belongs($0, to: kind) })
            page.hasMore = fetched.count >= Self.pageSize
            page.hasLoaded = true
            page.isLoading = false
        }
    }

    /// Grows whenever the chat's stored messages change, also while the
    /// conversation is not open (the screen reads it to refresh).
    var revision: Int { messenger.mediaRevision(in: chatID) }

    /// The chat's messages changed (a download finished, an item was saved,
    /// deleted, expired or wiped): every loaded list is read again from the
    /// newest, as far as it was loaded, so what is listed — and what is open
    /// over it — is never an old copy. No spinner: the list stays as it is
    /// until the new one is there.
    func refreshLoaded() async {
        for kind in [ChatMediaKind.visual, .documents] where page(kind).hasLoaded {
            let generation = (generations[kind] ?? 0) + 1
            generations[kind] = generation
            let limit = min(max(page(kind).items.count, Self.pageSize), Self.refreshLimit)
            let fetched = await messenger.media(in: chatID, kind: kind, before: nil, limit: limit)
            guard generations[kind] == generation else { continue }
            setPage(kind) { page in
                page.items = Self.unique(fetched.filter { Self.belongs($0, to: kind) })
                page.hasMore = fetched.count >= limit
                page.isLoading = false
            }
        }
    }

    /// The next (older) page.
    func loadMore(_ kind: ChatMediaKind) async {
        let current = page(kind)
        guard current.hasLoaded, current.hasMore, !current.isLoading, let last = current.items.last else { return }
        let generation = generations[kind] ?? 0
        setPage(kind) { $0.isLoading = true }
        let fetched = await messenger.media(in: chatID, kind: kind, before: last.id, limit: Self.pageSize)
        guard generations[kind] == generation else { return }
        setPage(kind) { page in
            page.items = Self.unique(page.items + fetched.filter { Self.belongs($0, to: kind) })
            page.hasMore = fetched.count >= Self.pageSize
            page.isLoading = false
        }
    }

    /// An item came on screen: near the end, the next page loads.
    func itemAppeared(_ id: MessageID, in kind: ChatMediaKind) {
        let items = page(kind).items
        guard let index = items.lastIndex(where: { $0.id == id }),
            index >= items.count - Self.prefetchDistance
        else { return }
        Task { await loadMore(kind) }
    }

    /// The first of each id, in order (a page that overlaps the last one).
    static func unique(_ messages: [Message]) -> [Message] {
        var seen = Set<MessageID>()
        return messages.filter { seen.insert($0.id).inserted }
    }

    /// The tab changed: its list loads the first time it shows.
    func show(_ tab: Tab) {
        self.tab = tab
        guard let kind = tab.mediaKind else { return }
        Task { await loadFirstPageIfNeeded(kind) }
    }

    // MARK: MediaViewerContext

    func message(_ id: MessageID) -> Message? {
        if let live = messenger.messages(in: chatID).last(where: { $0.id == id }) { return live }
        return visual.items.first { $0.id == id } ?? documents.items.first { $0.id == id }
    }

    /// The sender's name over the viewer (“sen” is the user's own name).
    func sender(of message: Message) -> String {
        message.isOutgoing ? ownUsername : peerUsername
    }

    // MARK: Opening

    /// A tile of the grid: the viewer or the player, swiping through the grid.
    func openVisual(_ message: Message) {
        guard Self.belongs(message, to: .visual) else { return }
        if case .video = message.content, message.mediaAvailability == .unavailable || message.mediaAvailability == .held {
            U2Haptics.error()
            return
        }
        U2Haptics.selection()
        gallery = GalleryItem(startID: message.id, pages: .visual)
    }

    func closeGallery() {
        gallery = nil
    }

    /// The pages the gallery swipes through.
    func galleryPages(_ gallery: GalleryItem) -> [Message] {
        switch gallery.pages {
        case .visual: return items(.visual)
        case .single: return message(gallery.startID).map { [$0] } ?? []
        }
    }

    /// A row of Belgeler: Quick Look in the app's screen (its share button
    /// tells the sender), a video file in the player, or the notice for what
    /// Quick Look cannot show. Still on the relay: downloaded first (the
    /// ring), then it opens.
    func openDocument(_ message: Message) {
        guard case .file(let name, _, _, _) = message.content, Self.belongs(message, to: .documents) else { return }
        switch message.mediaAvailability {
        case .ready, .remote, .downloading: break
        case .held, .unavailable, .preparing, .preparationFailed:
            U2Haptics.error()
            return
        }
        guard openingFile == nil, presentedFile == nil else { return }
        // A video or an image sent as a file: the player or the photo viewer
        // (never Quick Look for an image: its long press hands it on untold).
        let opensAlone = MediaFileKind(fileName: name) == .movie || MediaFileKind.opensInPhotoViewer(fileName: name)
        if opensAlone, message.mediaAvailability.isReady {
            gallery = GalleryItem(startID: message.id, pages: .single)
            return
        }
        U2Haptics.selection()
        openingFile = message.id
        let id = message.id
        let messenger = messenger
        let chatID = chatID
        Task { [weak self] in
            let url = await messenger.previewFile(id, in: chatID)
            guard let self else {
                if let url { messenger.discardPreviewFile(url) }
                return
            }
            if self.openingFile == id { self.openingFile = nil }
            guard let url else {
                U2Haptics.error()
                self.showNotice(.init(kind: .openFailed, messageID: id, fileName: name))
                return
            }
            guard UIApplication.shared.applicationState != .background, let current = self.message(id) else {
                messenger.discardPreviewFile(url)
                return
            }
            self.present(url, of: current, name: name)
        }
    }

    private func present(_ url: URL, of message: Message, name: String) {
        let id = message.id
        let isDisappearing = message.isDisappearing
        if FilePreviewPresenter.canPreview(url) {
            presentedFile = id
            var onShare: (@MainActor () -> Void)?
            if !isDisappearing { onShare = { [weak self] in self?.share(id, from: url) } }
            let shown = FilePreviewPresenter.present(url, title: name, onShare: onShare, locale: presentationLocale) {
                [weak self] in
                self?.messenger.discardPreviewFile(url)
                if self?.presentedFile == id { self?.presentedFile = nil }
            }
            if !shown {
                presentedFile = nil
                messenger.discardPreviewFile(url)
            }
        } else if isDisappearing {
            messenger.discardPreviewFile(url)
            showNotice(.init(kind: .notPreviewableDisappearing, messageID: id, fileName: name))
        } else {
            showNotice(.init(kind: .notPreviewable, messageID: id, fileName: name, savableURL: url))
        }
    }

    /// The preview's share button (after the one-time explanation).
    private func share(_ id: MessageID, from url: URL) {
        guard let message = message(id) else { return }
        let saver = mediaSaver
        Task { _ = await saver.shareFile(message, from: url) }
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

    /// The notice's sheet finished closing: export (telling the sender), or its copy goes.
    func fileNoticeDidDismiss() {
        guard let notice = shownNotice else { return }
        shownNotice = nil
        let exports = exportsAfterNotice
        exportsAfterNotice = false
        guard let url = notice.savableURL else { return }
        guard exports, let message = message(notice.messageID), UIApplication.shared.applicationState != .background
        else {
            messenger.discardPreviewFile(url)
            return
        }
        presentedFile = message.id
        let saver = mediaSaver
        let messenger = messenger
        Task { [weak self] in
            if await saver.exportFile(message, from: url) == .saved { U2Haptics.success() }
            messenger.discardPreviewFile(url)
            if self?.presentedFile == message.id { self?.presentedFile = nil }
        }
    }

    private func showNotice(_ notice: ConversationViewModel.FileNotice) {
        if let previous = shownNotice?.savableURL, previous != notice.savableURL { messenger.discardPreviewFile(previous) }
        shownNotice = notice
        exportsAfterNotice = false
        fileNotice = notice
    }

    // MARK: Lifecycle

    /// What is open shows a message that was deleted, expired or wiped.
    var presentedMediaIsGone: Bool {
        func isGone(_ id: MessageID) -> Bool { message(id).map(\.isDeleted) ?? true }
        // The gallery itself steps over (or closes on) a page that went.
        if let gallery, galleryPages(gallery).isEmpty { return true }
        if let id = presentedFile, isGone(id) { return true }
        if let notice = fileNotice, isGone(notice.messageID) { return true }
        return false
    }

    /// The app left the foreground (the lock follows), or the media went:
    /// nothing decrypted waits behind it.
    func closeMedia() {
        gallery = nil
        FilePreviewPresenter.dismiss(animated: false)
        FileExporter.dismiss(animated: false)
        presentedFile = nil
        openingFile = nil
        if let url = shownNotice?.savableURL { messenger.discardPreviewFile(url) }
        shownNotice = nil
        exportsAfterNotice = false
        fileNotice = nil
    }
}
