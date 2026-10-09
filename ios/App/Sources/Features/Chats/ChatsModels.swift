import SwiftUI

// View models of 04 · Sohbetler. They hold only presentation state and talk
// to the messenger exclusively through `MessengerService`, so the live
// implementation drops in without touching the screens.

// MARK: - 4.2 · Yeni sohbet

/// The exact-username lookup of 4.2: no suggestions, no prefix search. While
/// typing nothing is sent; once the name is valid (UsernameRules) and typing
/// pauses for 0.4 s, one exact lookup runs. Results fade in; editing the name
/// fades the previous result out at once, so a stale match is never shown.
@Observable
@MainActor
final class NewChatModel {
    enum Phase: Equatable {
        /// Empty or not (yet) a valid username: the hint only.
        case idle
        /// Valid, waiting for typing to pause.
        case pending
        /// The exact lookup is running (spinner in the field).
        case searching
        case found(Peer, isExistingChat: Bool)
        case notFound
        /// The user typed their own username.
        case ownName
    }

    #if DEBUG
        /// Screenshot harness controls.
        struct DebugOptions: Equatable {
            /// Keep the lookup running forever (the “Aranıyor…” state).
            var freezesSearching = false
            /// Draw the focused field without raising the keyboard.
            var forcesFocusChrome = false
            /// Open on the “Kodumu göster” page.
            var startsOnMyCode = false
        }
        var debug = DebugOptions()
    #endif

    var query = "" {
        didSet {
            guard query != oldValue else { return }
            scheduleLookup()
        }
    }

    private(set) var phase: Phase = .idle
    /// The username the current result is about.
    private(set) var resultName = ""
    private(set) var isStarting = false

    private let messenger: any MessengerService
    private let debounce: Duration
    private var lookupTask: Task<Void, Never>?

    init(messenger: any MessengerService, debounce: Duration = .milliseconds(400)) {
        self.messenger = messenger
        self.debounce = debounce
    }

    var ownUsername: String { messenger.account?.username ?? "" }

    /// Return key: look up right away (no wait for the pause).
    func submit() {
        scheduleLookup(immediately: true)
    }

    private func scheduleLookup(immediately: Bool = false) {
        lookupTask?.cancel()
        let name = UsernameRules.sanitize(query)
        guard UsernameRules.isValid(name) else {
            set(.idle, name: name)
            return
        }
        if name == ownUsername {
            set(.ownName, name: name)
            return
        }
        set(.pending, name: name)
        let debounce = immediately ? Duration.zero : self.debounce
        lookupTask = Task { [weak self] in
            if debounce > .zero { try? await Task.sleep(for: debounce) }
            guard !Task.isCancelled, let self else { return }
            self.set(.searching, name: name)
            #if DEBUG
                if self.debug.freezesSearching { return }
            #endif
            let result = await self.messenger.lookup(username: name)
            guard !Task.isCancelled, self.resultName == name else { return }
            switch result {
            case .found(let peer, let isExistingChat):
                self.set(.found(peer, isExistingChat: isExistingChat), name: name)
            case .notFound:
                self.set(.notFound, name: name)
            case .idle, .searching:
                self.set(.idle, name: name)
            }
        }
    }

    private func set(_ phase: Phase, name: String) {
        guard phase != self.phase || name != resultName else { return }
        resultName = name
        withU2Animation(U2Motion.state) { self.phase = phase }
    }

    /// “Mesaj yaz” / “Sohbete git”: the chat to open after the sheet is down —
    /// the existing one, or a new pending-request conversation.
    func startChat(with peer: Peer) async -> ChatID? {
        guard !isStarting else { return nil }
        isStarting = true
        defer { isStarting = false }
        if let existing = messenger.chats.first(where: { $0.peer.username == peer.username }) {
            return existing.id
        }
        return await messenger.startChat(username: peer.username)
    }

    func cancel() {
        lookupTask?.cancel()
    }
}

// MARK: - 4.3 / 4.4 · Request triage

/// The three decisions on a stranger's request. Removals are optimistic: the
/// card collapses at once (0.36 s) while the messenger catches up, so a slow
/// relay never makes the list jump. Nothing here tells the sender anything —
/// “Sil” is silent, “Engelle” is not announced, no read receipt is sent before
/// acceptance (that is the messenger's contract).
@Observable
@MainActor
final class RequestTriageModel {
    /// Requests already decided on this screen (hidden before the messenger confirms).
    private(set) var decided: Set<RequestID> = []

    func requests(in messenger: any MessengerService) -> [MessageRequest] {
        messenger.requests.filter { !decided.contains($0.id) }
    }

    func block(_ request: MessageRequest, in messenger: any MessengerService) async {
        hide(request.id)
        await messenger.block(request)
    }

    func delete(_ request: MessageRequest, in messenger: any MessengerService) async {
        hide(request.id)
        await messenger.delete(request)
    }

    func deleteAll(in messenger: any MessengerService) async {
        let all = requests(in: messenger)
        withU2Animation(U2Motion.state) { decided.formUnion(all.map(\.id)) }
        for request in all { await messenger.delete(request) }
    }

    /// Accepts: the chat appears in 4.1 and the caller opens 5.1.
    func accept(_ request: MessageRequest, in messenger: any MessengerService) async -> ChatID? {
        let chat = await messenger.accept(request)
        if chat != nil { hide(request.id) }
        return chat
    }

    private func hide(_ id: RequestID) {
        withU2Animation(U2Motion.state) { _ = decided.insert(id) }
    }
}
