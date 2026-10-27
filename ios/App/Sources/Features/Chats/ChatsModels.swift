import SwiftUI

// View models of 04 · Sohbetler. They hold only presentation state and talk
// to the messenger exclusively through `MessengerService`, so the live
// implementation drops in without touching the screens.

// MARK: - 4.2 · Yeni sohbet

/// The exact-username lookup of 4.2: no suggestions, no prefix search. While
/// typing nothing is sent; once typing pauses for 0.4 s, the typed name is
/// normalized (UsernameRules: white space, one leading “@”, case) and, when
/// valid, one exact lookup runs. A name no account can have (a Turkish
/// letter, a dot, too long …) is answered with the rule it breaks, without
/// asking anyone, and the field keeps it as typed. Results fade in; editing
/// the name fades the previous result out at once, so a stale match is
/// never shown.
///
/// A found result's profile picture (proposed, PROTOCOL §8.10) is asked for
/// only after that exact hit: the result shows at once with the initial and
/// a travelling ring, and the picture takes its place when it arrives (the
/// initial stays on any failure). An accepted contact's is already here.
/// Editing the name drops it like the result; typing never waits for it.
@Observable
@MainActor
final class NewChatModel {
    enum Phase: Equatable {
        /// Empty or still too short: the hint only.
        case idle
        /// Waiting for typing to pause (a broken rule is said only then).
        case pending
        /// The exact lookup is running (spinner in the field).
        case searching
        /// `existingChat`: the chat with the account the lookup found —
        /// never one that only shows the same name (PROTOCOL §3.3).
        case found(Peer, existingChat: ChatID?)
        /// Nobody has the name.
        case notFound
        /// Nobody can have the name: it breaks a rule (the first one is
        /// shown), so nothing is asked.
        case invalid(UsernameRules.Violation)
        /// The user typed their own username.
        case ownName
    }

    /// The result's picture.
    enum ResultAvatar: Equatable {
        /// No result to show one for.
        case noResult
        /// Asked for (the initial and the ring meanwhile).
        case loading
        /// Here — the initial when there is none or it failed.
        case loaded(AvatarPicture)

        /// What the result's avatar draws now.
        var picture: AvatarPicture {
            if case .loaded(let picture) = self { return picture }
            return .initials
        }
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
    private(set) var resultAvatar: ResultAvatar = .noResult
    private(set) var isStarting = false

    private let messenger: any MessengerService
    private let debounce: Duration
    /// How long a found result stays unchanged before its picture is
    /// fetched: a pause in typing is not a choice (PROTOCOL §8.10 — never
    /// while the user is still typing). Return fetches at once.
    private let avatarSettle: Duration
    private var lookupTask: Task<Void, Never>?

    init(messenger: any MessengerService, debounce: Duration = .milliseconds(400), avatarSettle: Duration = .seconds(1)) {
        self.messenger = messenger
        self.debounce = debounce
        self.avatarSettle = avatarSettle
    }

    var ownUsername: String { messenger.account?.username ?? "" }

    /// Return key: look up right away (no wait for the pause).
    func submit() {
        scheduleLookup(immediately: true)
    }

    private func scheduleLookup(immediately: Bool = false) {
        lookupTask?.cancel()
        let name = UsernameRules.normalize(query)
        let violations = UsernameRules.violations(name)
        if name.isEmpty || (violations == [.length] && UsernameRules.isTooShort(name)) {
            set(.idle, name: name)
            return
        }
        if name == ownUsername {
            set(.ownName, name: name)
            return
        }
        set(.pending, name: name)
        let debounce = immediately ? Duration.zero : self.debounce
        let settle = immediately ? Duration.zero : avatarSettle
        lookupTask = Task { [weak self] in
            if debounce > .zero { try? await Task.sleep(for: debounce) }
            guard !Task.isCancelled, let self else { return }
            // Breaks a rule: no account can have it, so nothing is asked.
            if let violation = violations.first {
                self.set(.invalid(violation), name: name)
                return
            }
            self.set(.searching, name: name)
            #if DEBUG
                if self.debug.freezesSearching { return }
            #endif
            let result = await self.messenger.lookup(username: name)
            guard !Task.isCancelled, self.resultName == name else { return }
            switch result {
            case .found(let peer, let existingChat):
                self.set(.found(peer, existingChat: existingChat), name: name)
                if settle > .zero, self.resultAvatar == .loading {
                    try? await Task.sleep(for: settle)
                    guard !Task.isCancelled, self.resultName == name else { return }
                }
                await self.loadResultAvatar(name: name)
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
        let avatar = Self.resultAvatar(for: phase, own: messenger.ownAvatar.picture)
        withU2Animation(U2Motion.state) {
            self.phase = phase
            resultAvatar = avatar
        }
    }

    /// What a result's avatar starts as: an accepted contact's picture and
    /// the own one are here; anyone else's is fetched (`.loading`).
    static func resultAvatar(for phase: Phase, own: AvatarPicture) -> ResultAvatar {
        switch phase {
        case .found(let peer, _): peer.isAcceptedContact ? .loaded(peer.avatar) : .loading
        case .ownName: .loaded(own)
        case .idle, .pending, .searching, .notFound, .invalid: .noResult
        }
    }

    /// The found result's search copy, unless the name changed meanwhile.
    private func loadResultAvatar(name: String) async {
        guard resultAvatar == .loading else { return }
        let picture = await messenger.searchAvatar(username: name)
        guard !Task.isCancelled, resultName == name, case .found = phase, resultAvatar == .loading else { return }
        withU2Animation(U2Motion.state) { resultAvatar = .loaded(picture) }
    }

    /// “Mesaj yaz” / “Sohbete git” on the found result: the chat to open
    /// after the sheet is down — the chat with the account the lookup found,
    /// or a new pending-request conversation. Never matched by the name a
    /// chat shows: after an account was deleted and someone else registered
    /// its name, the old chat still shows it (PROTOCOL §3.3). The messenger
    /// looks the name up again and opens the conversation of that account.
    func startChat() async -> ChatID? {
        guard !isStarting, case .found(let peer, let existingChat) = phase else { return nil }
        isStarting = true
        defer { isStarting = false }
        if let existingChat, messenger.chat(existingChat) != nil { return existingChat }
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
