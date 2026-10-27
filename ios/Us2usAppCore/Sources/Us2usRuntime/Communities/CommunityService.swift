import Foundation
import LibSignalClient
import Observation
import Us2usKit
import Us2usStorage

/// What went wrong with a community action or refresh, for the screens to
/// explain in their own words.
public enum CommunityIssue: Error, Equatable, Sendable {
    /// No connection, or the relay failed (5xx): try again later.
    case offline
    /// The relay has no community routes (direct chats are unaffected).
    case unsupported
    /// A signed roster or the owner's identity did not verify.
    case verification
    /// This member's identity key differs from the signed roster.
    case memberKey(AccountId)
    /// The roster changed meanwhile (409 `stale_version`): refresh and try again.
    case stale
    /// The community already has 100 members (409 `community_full`).
    case full
    /// The community is gone, or this account is no longer a member.
    case notFound
    /// The relay is limiting requests (429), e.g. a member's community inbox is full.
    case rateLimited
    case invalidName
    case invalidInvite
    /// The owner cannot leave; deleting the community is the owner's way out.
    case ownerCannotLeave
    case locked
    case failed

    public init(_ error: any Error) {
        switch error {
        case let issue as CommunityIssue: self = issue
        case CommunityError.memberKeyMismatch(let member): self = .memberKey(member)
        case CommunityError.invalidName: self = .invalidName
        case CommunityError.invalidInvite: self = .invalidInvite
        case is CommunityError: self = .verification
        case let relay as RelayError:
            switch relay {
            case .rateLimited, .communityInboxFull: self = .rateLimited
            case .server(409, .communityFull?, _): self = .full
            case .server(404, _, _): self = .notFound
            case .server(409, _, _): self = .stale
            case .server(let status, _, _) where status >= 500: self = .offline
            case .network: self = .offline
            default: self = .failed
            }
        case RuntimeError.locked: self = .locked
        default: self = .failed
        }
    }
}

/// Foreground-only communities surface. Its independent mailbox cannot
/// interrupt the one-to-one connection supervisor or create contacts.
///
/// It polls (every 10 s) only while the account has communities or a
/// communities screen is open, and never while the app is locked or in the
/// background grace period (the runtime stops it with the UI state).
@MainActor @Observable
public final class CommunityService {
    public private(set) var joined: [StoredCommunity] = []
    public private(set) var directory: [CommunitySummary] = []
    /// The open conversation's latest messages, oldest first.
    public private(set) var messages: [CommunityTextItem] = []
    /// Every community with its latest message and unread count (the chats list).
    public private(set) var chats: [CommunityChatSummary] = []
    /// Attachments downloading now: the fraction received (0…1) by item id
    /// (``CommunityTextItem/id``). Memory only.
    public internal(set) var downloadProgress: [String: Double] = [:]
    public private(set) var requests: [CommunityJoinRequest] = []
    /// Owner-only inbox across verified active communities; memory only, cleared on lock.
    public private(set) var ownerRequests: [AccountId: [CommunityJoinRequest]] = [:]
    @ObservationIgnored private var requestsRevision = 0
    /// Directory-verified usernames of personal contacts, to name members.
    public private(set) var usernames: [AccountId: String] = [:]
    public private(set) var selected: AccountId?
    @ObservationIgnored var isConversationVisible = false
    @ObservationIgnored private var historyOldest: CommunityHistoryCursor?
    @ObservationIgnored private var historyHasOlder = true
    @ObservationIgnored private var historyVersion = 0
    public private(set) var unsupported = false
    /// The last background refresh's problem (cleared by the next clean one).
    public internal(set) var issue: CommunityIssue?
    public let accountId: AccountId
    /// This account's identity key: a pin with it and no verified roster is an unconfirmed creation.
    public let identityKey: Data?
    @ObservationIgnored let engine: CommunityEngine
    @ObservationIgnored private let onIdentityChange: @Sendable (AccountId) async -> Void
    @ObservationIgnored private var poller: Task<Void, Never>?
    @ObservationIgnored var active = true
    @ObservationIgnored private var nextCursor: String?
    @ObservationIgnored private var searchQuery = ""
    @ObservationIgnored private var visibleScreens = 0
    @ObservationIgnored let pollInterval: Duration
    /// The user's read-receipt setting (the 1:1 rule: off sends no READ and shows none).
    @ObservationIgnored let readReceiptsEnabled: @MainActor () -> Bool
    /// READ receipts wait this long so they leave together, per original sender.
    @ObservationIgnored let readCoalescing: Duration
    @ObservationIgnored var readTask: Task<Void, Never>?
    @ObservationIgnored var downloads: [String: (community: AccountId, task: Task<Void, Never>)] = [:]

    /// The shortest query sent to the public directory (searches reach the relay).
    public static let minimumQueryLength = 2

    init(
        backend: MessengerBackend, storage: LocalStorage, pollInterval: Duration = .seconds(10),
        readCoalescing: Duration = .seconds(3),
        onIdentityChange: @escaping @Sendable (AccountId) async -> Void = { _ in },
        readReceipts: @escaping @MainActor () -> Bool = { true }
    ) {
        accountId = backend.accountId
        identityKey = try? backend.protocolStore.identityKeyPair(context: NullContext()).identityKey.serialize()
        engine = CommunityEngine(backend: backend, storage: storage)
        self.pollInterval = pollInterval
        self.readCoalescing = readCoalescing
        self.readReceiptsEnabled = readReceipts
        self.onIdentityChange = onIdentityChange
    }

    /// Whether the background refresh runs: communities exist, or a screen shows them.
    var shouldPoll: Bool { active && (visibleScreens > 0 || !joined.isEmpty) }

    func start() {
        poller?.cancel()
        let interval = pollInterval
        poller = Task { [weak self] in
            await self?.reloadLocal()
            if self?.shouldPoll == true { await self?.refresh() }
            while true {
                do { try await Task.sleep(for: interval) } catch { return }
                guard let self, self.active else { return }
                if self.shouldPoll { await self.refresh() }
            }
        }
    }

    func stop() {
        active = false; poller?.cancel(); poller = nil
        historyOldest = nil; historyHasOlder = true; historyVersion += 1
        joined = []; directory = []; messages = []; requests = []; usernames = [:]; selected = nil; issue = nil
        isConversationVisible = false
        chats = []; downloadProgress = [:]; ownerRequests = [:]; requestsRevision += 1
        readTask?.cancel(); readTask = nil
        for download in downloads.values { download.task.cancel() }
        downloads = [:]
        engine.stop()
    }

    /// A communities screen appeared (or went): polling follows it.
    public func screenAppeared() {
        visibleScreens += 1
        if visibleScreens == 1 { Task { await refresh() } }
    }

    public func screenDisappeared() { visibleScreens = max(0, visibleScreens - 1) }

    public func refresh() async {
        guard active else { return }
        engine.readReceipts.withLock { $0 = readReceiptsEnabled() }
        do {
            let report = try await engine.sync()
            guard active else { return }
            unsupported = false
            issue = report.issue
            for peer in report.identityChanges { await onIdentityChange(peer) }
            await reloadLocal()
            await reloadOwnerRequests()
            if let selected { try await reload(selected) }
        } catch {
            guard active else { return }
            let mapped = CommunityIssue(error)
            if mapped == .unsupported { unsupported = true } else if mapped != .locked { issue = mapped }
            await reloadLocal()
        }
    }

    public func dismissIssue() { issue = nil }

    // MARK: Directory

    /// Public search: only whole queries of at least ``minimumQueryLength``
    /// characters reach the relay (the screens send them on submit or after
    /// a pause in typing).
    public func search(_ query: String, more: Bool = false) async throws {
        // Queries follow the community-name rule, as on the relay.
        guard let query = CommunityName.searchQuery(query), query.count >= Self.minimumQueryLength else {
            directory = []; nextCursor = nil; searchQuery = ""
            return
        }
        let page = try await perform { try await engine.search(query, after: more ? nextCursor : nil) }
        guard active else { throw CommunityIssue.locked }
        if more, query == searchQuery { directory += page.communities } else { directory = page.communities }
        searchQuery = query; nextCursor = page.nextCursor
    }
    public var hasMoreResults: Bool { nextCursor != nil }

    // MARK: Membership

    public func create(name: String, isPublic: Bool) async throws {
        try await perform { try await engine.create(name: name, isPublic: isPublic) }
        await refresh()
    }
    public func requestJoin(_ summary: CommunitySummary) async throws {
        try await perform { try await engine.requestJoin(summary) }
        await refresh()
    }
    public func redeem(_ code: String) async throws {
        try await perform { try await engine.redeem(code) }
        await refresh()
    }
    /// Leaves as a member and forgets the community on this phone.
    public func leave(_ id: AccountId) async throws {
        try await perform { try await engine.leave(id) }
        forgotten(id)
    }
    /// Withdraws a pending request and forgets the community on this phone.
    public func withdraw(_ id: AccountId) async throws {
        try await perform { try await engine.withdraw(id) }
        forgotten(id)
    }
    /// Removes an ended, failed or rejected community from this phone only.
    public func forget(_ id: AccountId) async throws {
        try await perform { try await engine.forget(id) }
        forgotten(id)
    }

    // MARK: Conversation

    public func open(_ id: AccountId) async throws {
        if selected != id { historyOldest = nil; historyHasOlder = true; historyVersion += 1 }
        selected = id
        isConversationVisible = true
        try await perform { try await reload(id) }
    }
    /// A temporary full-screen picker keeps the feed, but newly received messages are not read yet.
    public func coverConversation(_ id: AccountId) {
        if selected == id { isConversationVisible = false }
    }
    public func closeConversation() {
        historyOldest = nil; historyHasOlder = true; historyVersion += 1
        selected = nil; isConversationVisible = false; messages = []; requests = []
    }

    public func loadOlderMessages(_ id: AccountId) async throws -> Bool {
        guard active, selected == id, historyHasOlder, let before = historyOldest else { return false }
        let version = historyVersion
        let page = try await engine.history(id, before: before)
        guard active, selected == id, version == historyVersion else { return false }
        guard let oldest = page.oldest else { historyHasOlder = false; return false }
        let window = try await engine.history(id, through: oldest)
        guard active, selected == id, version == historyVersion else { return false }
        historyVersion += 1; historyOldest = oldest; historyHasOlder = window.hasOlder
        messages = window.items
        if isConversationVisible { markDisplayed(id) }
        return true
    }

    /// Returns once the message is stored and queued for every member
    /// (the composer can clear then); delivery continues in the background.
    public func send(_ text: String, to id: AccountId) async throws {
        let unreachable = try await perform { try await engine.send(text, to: id) }
        if let member = unreachable.first { issue = .memberKey(member) }
        try? await reload(id)
        Task { [weak self] in await self?.deliverQueued(id) }
    }

    public func sendSticker(_ sticker: BRPSticker, to id: AccountId) async throws {
        let unreachable = try await perform { try await engine.sendSticker(sticker, to: id) }
        if let member = unreachable.first { issue = .memberKey(member) }
        try? await reload(id)
        Task { [weak self] in await self?.deliverQueued(id) }
    }

    func deliverQueued(_ id: AccountId) async {
        guard active else { return }
        if let problem = await engine.flushNow() { issue = problem }
        if selected == id { try? await reload(id) }
    }

    public func approve(_ request: CommunityJoinRequest, in id: AccountId) async throws {
        try await perform { try await engine.approve(request, in: id) }
        resolved(request, in: id)
        await refresh()
    }
    public func reject(_ request: CommunityJoinRequest, in id: AccountId) async throws {
        try await perform { try await engine.reject(request, in: id) }
        resolved(request, in: id)
        await refresh()
    }
    public func remove(_ member: CommunityMember, from id: AccountId) async throws {
        try await perform { try await engine.remove(member, from: id) }
        await refresh()
    }
    public func invite(_ id: AccountId) async throws -> String { try await perform { try await engine.invite(id) } }
    public func revokeInvites(_ id: AccountId) async throws { try await perform { try await engine.revokeInvites(id) } }
    /// The owner deletes the community for everyone.
    public func delete(_ id: AccountId) async throws {
        try await perform { try await engine.delete(id) }
        forgotten(id)
    }

    // MARK: Helpers

    func forgotten(_ id: AccountId) {
        if selected == id { closeConversation() }
        joined.removeAll { $0.id == id }
        ownerRequests[id] = nil; requestsRevision += 1
        Task { await reloadLocal() }
    }

    func reloadLocal() async {
        guard let groups = try? await engine.groups(), let names = try? await engine.usernames(),
              let summaries = try? await engine.summaries(), active else { return }
        joined = groups
        let owned = Set(groups.filter { $0.isActive && $0.state?.owner.accountId == accountId }.map(\.id))
        ownerRequests = ownerRequests.filter { owned.contains($0.key) }
        usernames = names
        if chats != summaries { chats = summaries }
    }

    private func resolved(_ request: CommunityJoinRequest, in id: AccountId) {
        requestsRevision += 1
        ownerRequests[id]?.removeAll { $0.accountId == request.accountId }
        if selected == id { requests.removeAll { $0.accountId == request.accountId } }
    }

    /// Fetch without opening conversations or marking their messages as read.
    private func reloadOwnerRequests() async {
        requestsRevision += 1
        let revision = requestsRevision
        for group in joined where group.isActive && group.state?.owner.accountId == accountId {
            do {
                let pending = try await engine.requests(group.id)
                guard active, requestsRevision == revision else { return }
                guard joined.contains(where: { $0.id == group.id && $0.isActive && $0.state?.owner.accountId == accountId }) else { continue }
                ownerRequests[group.id] = pending
            } catch {
                guard active, requestsRevision == revision else { return }
                // Keep the last successful count; an unavailable endpoint is not an empty inbox.
                issue = CommunityIssue(error)
            }
        }
    }

    func reload(_ id: AccountId) async throws {
        let version = historyVersion
        let history = try await engine.history(id, through: historyOldest)
        guard active, selected == id, version == historyVersion else { return }
        let items = history.items
        if historyOldest == nil { historyOldest = history.oldest }
        historyHasOlder = history.hasOlder
        if messages != items { messages = items }
        settleDownloads(of: items, in: id)
        // The open conversation shows them: they are read.
        if isConversationVisible, items.contains(where: { !$0.outgoing && !$0.isDisplayed }) { markDisplayed(id) }
        // Local history remains usable when the independent owner-only
        // requests endpoint is unavailable. Still report that API failure.
        let revision = requestsRevision
        let pending = try await engine.requests(id)
        guard active, selected == id, requestsRevision == revision else { return }
        requests = pending
        if joined.contains(where: { $0.id == id && $0.isActive && $0.state?.owner.accountId == accountId }) {
            ownerRequests[id] = pending
        }
    }

    /// Runs an action and reports its failure as a ``CommunityIssue``.
    func perform<T>(_ action: () async throws -> T) async throws(CommunityIssue) -> T {
        do {
            return try await action()
        } catch {
            let issue = CommunityIssue(error)
            if case .notFound = issue { await reloadLocal() }
            throw issue
        }
    }
}

/// The verified states of one sync or flush pass: every community is fetched
/// and verified at most once per pass.
struct CommunityPass {
    enum Lookup { case current(CommunityState), gone, unavailable }

    var states: [AccountId: CommunityState] = [:]
    var gone: Set<AccountId> = []
    var unavailable: Set<AccountId> = []
    var identityChanges: Set<AccountId> = []
    private(set) var issue: CommunityIssue?

    /// Keeps the most specific problem of the pass.
    mutating func note(_ problem: CommunityIssue) {
        switch (issue, problem) {
        case (nil, _), (.offline?, _), (.rateLimited?, .memberKey), (.rateLimited?, .verification), (.stale?, .memberKey), (.stale?, .verification):
            issue = problem
        default: break
        }
    }
}

struct CommunitySyncReport: Sendable {
    var issue: CommunityIssue?
    var identityChanges: Set<AccountId> = []
}

actor CommunityEngine {
    let backend: MessengerBackend
    let storage: LocalStorage
    nonisolated let activity = LockedState(true)
    private var syncing = false
    private var flushing = false
    private var flushAgain = false
    /// Recipients whose community mail is full, until the relay's `Retry-After`.
    private var fullInboxes: [AccountId: Date] = [:]
    /// Community state fetches made (one per community and pass; for tests).
    private(set) var stateFetches = 0
    /// Whether READ receipts may leave (the user's setting, kept current by the service).
    nonisolated let readReceipts = LockedState(true)
    /// Own media messages whose upload or fanout runs now.
    var deliveringMedia: Set<String> = []
    var relay: RelayClient { backend.messenger.relay }
    init(backend: MessengerBackend, storage: LocalStorage) { self.backend = backend; self.storage = storage }
    nonisolated func stop() { activity.withLock { $0 = false } }
    nonisolated var isActive: Bool { activity.withLock { $0 } }
    nonisolated func check() throws { guard isActive, storage.isOpen else { throw RuntimeError.locked } }
    func groups() throws -> [StoredCommunity] { try check(); return try storage.read { try $0.communities.all() } }
    func messages(_ id: AccountId) throws -> [CommunityTextItem] { try check(); return try storage.read { try $0.communities.messages(id) } }
    func history(_ id: AccountId, before: CommunityHistoryCursor? = nil, through: CommunityHistoryCursor? = nil) throws -> CommunityHistory {
        try check()
        return try storage.read { try $0.communities.history(id, before: before, through: through) }
    }

    /// Usernames the directory confirmed for personal contacts.
    func usernames() throws -> [AccountId: String] {
        try check()
        let contacts = try storage.read { try $0.contacts.all() }
        var names: [AccountId: String] = [:]
        for contact in contacts where contact.profile.usernameStatus == .verified {
            if let username = contact.profile.username { names[contact.accountId] = username.value }
        }
        return names
    }

    func search(_ query: String, after: String?) async throws -> CommunityDirectory {
        try check(); return try await relay.communityDirectory(query: query, after: after)
    }

    private func ownIdentity() throws -> Data {
        try backend.protocolStore.identityKeyPair(context: NullContext()).identityKey.serialize()
    }

    private func accept(_ signed: SignedCommunityState, name: String? = nil) throws -> CommunityState {
        try check()
        let state = try signed.verified()
        let own = try ownIdentity()
        guard state.member(backend.accountId)?.identityKey == own else { throw CommunityError.identityMismatch }
        if try storage.read({ try $0.communities.get(state.id) }) == nil {
            guard state.owner.accountId == backend.accountId, state.owner.identityKey == own else { throw CommunityError.identityMismatch }
        }
        try storage.write { try $0.communities.save(signed, localName: name) }
        return state
    }

    /// Fetches and verifies the current state; a community the relay no
    /// longer shows us has ended on this phone.
    func current(_ id: AccountId) async throws -> CommunityState {
        try check()
        stateFetches += 1
        let signed: SignedCommunityState
        do {
            signed = try await relay.community(id)
        } catch let failure as RelayError where failure.isNotFound {
            try? storage.write { try $0.communities.deactivate(id) }
            throw failure
        }
        guard try CommunityState(bytes: signed.state).id == id else { throw CommunityError.invalidState }
        return try accept(signed)
    }

    func lookup(_ id: AccountId, in pass: inout CommunityPass) async -> CommunityPass.Lookup {
        if let state = pass.states[id] { return .current(state) }
        if pass.gone.contains(id) { return .gone }
        if pass.unavailable.contains(id) { return .unavailable }
        do {
            let state = try await current(id)
            pass.states[id] = state
            return .current(state)
        } catch let failure as RelayError where failure.isNotFound {
            pass.gone.insert(id)
            return .gone
        } catch let failure as CommunityError {
            pass.note(CommunityIssue(failure))
            pass.gone.insert(id)
            return .gone
        } catch {
            pass.note(CommunityIssue(error))
            pass.unavailable.insert(id)
            return .unavailable
        }
    }

    // MARK: Membership

    func create(name: String, isPublic: Bool) async throws {
        try check()
        guard let name = CommunityName.normalized(name) else { throw CommunityError.invalidName }
        let owner = try await backend.messenger.communityIdentity()
        let state = try CommunityState(id: AccountId(uuid: UUID()), revision: 1, isPublic: isPublic, owner: owner, name: isPublic ? name : "", members: [owner])
        let signed = try await backend.messenger.signCommunityState(state)
        try check()
        // Persist the identity/name before network: an ambiguous create can be recovered by mine.
        try storage.write { try $0.communities.pin(state.id, ownerKey: owner.identityKey, name: name) }
        let response: SignedCommunityState
        do {
            response = try await relay.createCommunity(signed)
        } catch let failure as RelayError {
            // Refused for good: nothing exists on the relay to recover.
            if Self.isDefinite(failure) { try? storage.write { try $0.communities.forget(state.id) } }
            throw failure
        }
        guard response.state == signed.state else { throw CommunityError.invalidState }
        _ = try accept(response, name: name)
    }

    func requestJoin(_ summary: CommunitySummary) async throws {
        try check()
        guard summary.visibility == "public", CommunityName.isValid(summary.name) else { throw CommunityError.invalidState }
        let known = try storage.read { try $0.communities.get(summary.id) } != nil
        try storage.write { try $0.communities.pin(summary.id, ownerKey: summary.ownerIdentityKey, name: summary.name) }
        do {
            try await relay.requestCommunityJoin(summary.id)
        } catch let failure as RelayError {
            if !known, Self.isDefinite(failure) { try? storage.write { try $0.communities.forget(summary.id) } }
            throw failure
        }
    }

    func redeem(_ code: String) async throws {
        try check()
        let invite = try CommunityInvitation(code: code.trimmingCharacters(in: .whitespacesAndNewlines))
        let known = try storage.read { try $0.communities.get(invite.communityId) } != nil
        try storage.write { try $0.communities.pin(invite.communityId, ownerKey: invite.ownerIdentityKey, name: invite.name) }
        let summary: CommunitySummary
        do {
            summary = try await relay.redeemCommunityInvite(invite)
        } catch let failure as RelayError {
            if !known, Self.isDefinite(failure) { try? storage.write { try $0.communities.forget(invite.communityId) } }
            throw failure
        }
        try check()
        guard summary.id == invite.communityId, summary.ownerIdentityKey == invite.ownerIdentityKey else { throw CommunityError.identityMismatch }
    }

    func leave(_ id: AccountId) async throws {
        try check()
        guard let group = try storage.read({ try $0.communities.get(id) }) else { return }
        if group.isActive, group.state?.owner.accountId == backend.accountId { throw CommunityIssue.ownerCannotLeave }
        do {
            try await relay.leaveCommunity(id)
        } catch let failure as RelayError where failure.isNotFound {
            // Not a member any more (removed, or the community is gone).
        } catch RelayError.server(409, _, _) {
            throw CommunityIssue.ownerCannotLeave
        }
        try check()
        try storage.write { try $0.communities.forget(id) }
    }

    func withdraw(_ id: AccountId) async throws {
        try check()
        do {
            try await relay.withdrawCommunityRequest(id)
        } catch let failure as RelayError where failure.isNotFound {
            // The community is gone; nothing is pending.
        }
        try check()
        try storage.write { try $0.communities.forget(id) }
    }

    func forget(_ id: AccountId) async throws {
        try check()
        guard let group = try storage.read({ try $0.communities.get(id) }) else { return }
        // A current membership is left (or deleted by its owner), not just hidden.
        guard !group.isActive else { throw CommunityError.invalidState }
        try storage.write { try $0.communities.forget(id) }
    }

    func requests(_ id: AccountId) async throws -> [CommunityJoinRequest] {
        try check()
        guard let group = try storage.read({ try $0.communities.get(id) }), group.isActive,
              group.state?.owner.accountId == backend.accountId else { return [] }
        return try await relay.communityRequests(id).requests
    }

    func approve(_ request: CommunityJoinRequest, in id: AccountId) async throws {
        let state = try await current(id)
        guard state.owner.accountId == backend.accountId, state.members.count < CommunityState.maxMembers,
              state.member(request.accountId) == nil,
              try await relay.communityRequests(id).requests.contains(request) else { throw CommunityError.invalidState }
        try check()
        let next = try state.replacingMembers(state.members + [request.member])
        let signed = try await backend.messenger.signCommunityState(next)
        try check()
        _ = try accept(await relay.updateCommunity(id, state: signed))
    }

    func reject(_ request: CommunityJoinRequest, in id: AccountId) async throws { try check(); try await relay.rejectCommunityRequest(id, account: request.accountId) }

    func remove(_ member: CommunityMember, from id: AccountId) async throws {
        let state = try await current(id)
        guard state.owner.accountId == backend.accountId, member.accountId != state.owner.accountId else { throw CommunityError.invalidState }
        let next = try state.replacingMembers(state.members.filter { $0.accountId != member.accountId })
        let signed = try await backend.messenger.signCommunityState(next)
        try check()
        _ = try accept(await relay.updateCommunity(id, state: signed))
    }

    func invite(_ id: AccountId) async throws -> String {
        let state = try await current(id)
        guard state.owner.accountId == backend.accountId else { throw CommunityError.invalidState }
        let stored = try storage.read { try $0.communities.get(id)?.name ?? "" }
        let name = state.isPublic ? state.name : stored
        // Invitations carry the name under the shared rule.
        guard CommunityName.isValid(name) else { throw CommunityError.invalidName }
        let token = try await relay.createCommunityInvite(id)
        try check()
        return try CommunityInvitation(communityId: id, token: token.token, ownerIdentityKey: state.owner.identityKey, name: name).code
    }

    func revokeInvites(_ id: AccountId) async throws { try check(); try await relay.revokeCommunityInvites(id) }

    func delete(_ id: AccountId) async throws {
        try check()
        do {
            try await relay.deleteCommunity(id)
        } catch let failure as RelayError where failure.isNotFound {
            // Already gone.
        }
        try check()
        try storage.write { try $0.communities.forget(id) }
    }

    /// A relay answer that will not change on retry (4xx other than 408/429).
    static func isDefinite(_ failure: RelayError) -> Bool {
        if case .server(let status, _, _) = failure { return (400..<500).contains(status) && status != 408 }
        return false
    }

    // MARK: Sending

    /// Encrypts for every other member of the current roster and stores the
    /// message with all its ciphertexts in one transaction. A member whose
    /// key does not match the roster (or who no longer exists) is skipped
    /// and returned; everyone else still gets it.
    func send(_ text: String, to id: AccountId) async throws -> [AccountId] {
        try check()
        guard CommunityCaption.isSendableText(text) else { throw CommunityError.invalidState }
        let state = try await current(id)
        let messageId = AccountId(uuid: UUID())
        let message = BRPCommunityMessage.text(text, community: id, revision: state.revision, messageId: messageId.bytes)
        let fanout = try await seal(message, for: state)
        try check()
        // One SQLCipher transaction commits the message, its recipient set
        // and every prepared ciphertext; a crash cannot turn a partly
        // prepared fanout into a falsely successful send.
        try storage.write { tx in
            try tx.communities.store(message, sender: backend.accountId, outgoing: true)
            try commit(fanout, of: messageId, in: id, revision: state.revision, tx: tx)
        }
        return fanout.mismatched
    }

    func sendSticker(_ sticker: BRPSticker, to id: AccountId) async throws -> [AccountId] {
        try check()
        let state = try await current(id)
        let message = BRPCommunityMessage.sticker(sticker, community: id, revision: state.revision)
        try message.validatedKind()
        let messageId = AccountId(bytes: message.messageID)!
        let fanout = try await seal(message, for: state)
        try check()
        try storage.write { tx in
            try tx.communities.store(message, sender: backend.accountId, outgoing: true)
            try commit(fanout, of: messageId, in: id, revision: state.revision, tx: tx)
        }
        return fanout.mismatched
    }

    /// The sealed copies of one message for every other member of `state`.
    struct Fanout: Sendable {
        var prepared: [(recipient: AccountId, envelope: Data)] = []
        /// Members whose key does not match the roster (fail closed for them only).
        var mismatched: [AccountId] = []
        /// Members whose account no longer exists.
        var gone: [AccountId] = []
    }

    /// Encrypts `message` separately for every member but this account. A
    /// member whose key does not match the roster (or whose account is gone)
    /// is skipped; when nobody could be prepared the first failure is thrown
    /// (unless `allowingNone`: then the fanout says who was skipped).
    func seal(
        _ message: BRPCommunityMessage, for state: CommunityState, only recipients: [AccountId]? = nil, allowingNone: Bool = false
    ) async throws -> Fanout {
        var fanout = Fanout()
        var firstFailure: (any Error)?
        let others = state.members.filter { $0.accountId != backend.accountId && (recipients?.contains($0.accountId) ?? true) }
        for member in others {
            try check()
            do {
                fanout.prepared.append((member.accountId, try await backend.messenger.sealCommunity(message, to: member)))
            } catch CommunityError.memberKeyMismatch(let who) {
                fanout.mismatched.append(who)
                firstFailure = firstFailure ?? CommunityError.memberKeyMismatch(who)
            } catch let failure as RelayError where failure.isNotFound {
                fanout.gone.append(member.accountId)
                firstFailure = firstFailure ?? failure
            }
        }
        if !allowingNone, !others.isEmpty, fanout.prepared.isEmpty, let firstFailure { throw firstFailure }
        return fanout
    }

    /// Queues a prepared fanout of an own text or media message and fixes
    /// its recipient set. A member whose key does not match fails it (it can
    /// be sent again); one who is gone only makes it partial.
    func commit(_ fanout: Fanout, of messageId: AccountId, in id: AccountId, revision: UInt64, tx: StorageTransaction) throws {
        var recipients: [AccountId: CommunityRecipientState] = [:]
        for item in fanout.prepared {
            try tx.communities.enqueue(community: id, message: messageId, recipient: item.recipient, revision: revision, envelope: item.envelope)
            recipients[item.recipient] = .pending
        }
        for member in fanout.mismatched { recipients[member] = .failed }
        for member in fanout.gone { recipients[member] = .skipped }
        try tx.communities.setRecipients(recipients, community: id, messageId: messageId)
        if !fanout.mismatched.isEmpty { try tx.communities.setStatus(.failed, community: id, message: messageId) }
        else if fanout.prepared.isEmpty { try tx.communities.setStatus(.sent, community: id, message: messageId) }
        else if !fanout.gone.isEmpty { try tx.communities.setStatus(.partial, community: id, message: messageId) }
    }

    /// Delivers what is queued now (after a send); returns its problem, if any.
    func flushNow() async -> CommunityIssue? {
        var pass = CommunityPass()
        await flush(using: &pass)
        await deleteBlobs()
        return pass.issue
    }

    // MARK: Sync

    func sync() async throws -> CommunitySyncReport {
        try check(); guard !syncing else { return CommunitySyncReport() }; syncing = true; defer { syncing = false }
        var pass = CommunityPass()
        let mine: CommunityMemberships
        do {
            mine = try await relay.myCommunities()
        } catch let failure as RelayError where failure.isNotFound {
            throw CommunityIssue.unsupported
        }
        try check()
        var memberships = Set<AccountId>()
        for signed in mine.communities {
            do {
                let state = try signed.verified()
                // An arbitrary `/mine` record never enrolls this phone.
                if try storage.read({ try $0.communities.get(state.id) }) == nil, state.owner.accountId != backend.accountId { continue }
                pass.states[state.id] = try accept(signed)
                memberships.insert(state.id)
            } catch let failure as CommunityError {
                pass.note(CommunityIssue(failure))
                if let id = try? CommunityState(bytes: signed.state).id { pass.gone.insert(id) }
            }
        }
        try storage.write { try $0.communities.markCurrentMemberships(memberships) }
        // A failing outbox never keeps the inbox from being fetched.
        await flush(using: &pass)
        try await drainInbox(&pass)
        // The end of the pass: one DELIVERY per community and original
        // sender for what was stored, READs still owed, blobs to delete and
        // media whose upload or fanout is unfinished.
        await queueReceipts(.delivery, using: &pass)
        await queueReceipts(.read, using: &pass)
        await deleteBlobs()
        await resumeMedia(using: &pass)
        await flush(using: &pass)
        return CommunitySyncReport(issue: pass.issue, identityChanges: pass.identityChanges)
    }

    private func drainInbox(_ pass: inout CommunityPass) async throws {
        // A page is bounded at 100 / 1 MiB; a few pages per pass, the rest next time.
        for _ in 0..<5 {
            try check()
            let inbox = try await relay.communityInbox()
            var acknowledgements: [String] = []
            for envelope in inbox.envelopes {
                try check()
                if try storage.read({ try $0.communities.sawEnvelope(envelope.id) }) { acknowledgements.append(envelope.id); continue }
                // Unknown restored memberships have no trusted owner pin. Their
                // queued records cannot block trusted communities behind them.
                guard try storage.read({ try $0.communities.get(envelope.communityId)?.signedState }) != nil else {
                    acknowledgements.append(envelope.id); continue
                }
                switch await lookup(envelope.communityId, in: &pass) {
                case .gone:
                    acknowledgements.append(envelope.id)
                case .unavailable:
                    continue
                case .current(let state):
                    // Queued under an older roster: rejected, not processed.
                    guard state.revision == envelope.revision else { acknowledgements.append(envelope.id); continue }
                    switch try await receive(envelope, state: state) {
                    case .stored(let changedIdentity):
                        acknowledgements.append(envelope.id)
                        if let changedIdentity { pass.identityChanges.insert(changedIdentity) }
                    case .dropped:
                        acknowledgements.append(envelope.id)
                    case .keep:
                        break
                    }
                }
            }
            if !acknowledgements.isEmpty { try check(); try await relay.acknowledgeCommunity(acknowledgements) }
            // `more` can come with an empty page; a page with nothing acknowledged would only repeat.
            guard inbox.more, inbox.envelopes.isEmpty || !acknowledgements.isEmpty else { return }
        }
    }

    enum Received: Sendable {
        /// Stored; carries the sender when its identity key changed with it.
        case stored(AccountId?)
        case dropped
        case keep
    }

    /// Decrypts and stores one envelope in the same inbound turn as the
    /// one-to-one mailbox uses, so neither path can commit the other's
    /// staged ratchet state before its message is stored.
    func receive(_ envelope: CommunityEnvelope, state: CommunityState) async throws -> Received {
        let messenger = backend.messenger
        let store = backend.protocolStore
        let storage = storage
        let activity = activity
        let own = backend.accountId
        return try await messenger.inboundTurn.run {
            try await store.withStagedInboundWrites { @Sendable () async throws -> Received in
                guard activity.withLock({ $0 }) else { throw RuntimeError.locked }
                let result = await messenger.processCommunity(envelope, state: state)
                do {
                    guard activity.withLock({ $0 }) else { throw RuntimeError.locked }
                    switch result {
                    case .message(let incoming):
                        guard case .community(let content) = incoming.content.body,
                              content.communityID == state.id.bytes, content.revision == state.revision,
                              incoming.sender != own, state.member(incoming.sender) != nil,
                              let kind = try? content.validatedKind(),
                              Self.isAuthorized(content, kind: kind, from: incoming.sender, in: state)
                        else {
                            // Dropped: the ratchet is committed, nothing is stored or sent back.
                            try storage.write { try $0.communities.recordEnvelope(envelope.id) }
                            return .dropped
                        }
                        try storage.write { tx in
                            try Self.apply(content, kind: kind, from: incoming.sender, own: own, state: state, tx: tx)
                            try tx.communities.recordEnvelope(envelope.id)
                        }
                        return .stored(incoming.identityChanged ? incoming.sender : nil)
                    case .dropped:
                        try storage.write { try $0.communities.recordEnvelope(envelope.id) }
                        return .dropped
                    case .retryLater:
                        _ = try store.discardStagedInboundWrites()
                        return .keep
                    }
                } catch {
                    _ = try? store.discardStagedInboundWrites()
                    throw error
                }
            }
        }
    }

    // MARK: Outbox

    func flush(using pass: inout CommunityPass) async {
        guard !flushing else { flushAgain = true; return }
        flushing = true; defer { flushing = false }
        var resealed = await flushOnce(&pass)
        // A send queued meanwhile goes out in a fresh pass (its roster may be
        // newer); so, once, do receipts and deletes the relay refused as stale.
        var resealing = 1
        while isActive, flushAgain || (resealed && resealing > 0) {
            if !flushAgain { resealing -= 1 }
            flushAgain = false
            var next = CommunityPass()
            resealed = await flushOnce(&next)
            if let issue = next.issue { pass.note(issue) }
        }
    }

    /// Seals a queued receipt or delete again for its member under `state`'s
    /// newer revision. `nil`: it went (the member is gone or its key does not
    /// match), or it waits for the next pass (offline).
    private func reseal(_ item: CommunityOutboxItem, for state: CommunityState) async -> CommunityOutboxItem? {
        guard var event = item.event, let member = state.member(item.recipient) else {
            try? storage.write { try $0.communities.discard(item) }
            return nil
        }
        event.revision = state.revision
        do {
            let envelope = try await backend.messenger.sealCommunity(event, to: member)
            try check()
            return try storage.write { try $0.communities.reseal(item, revision: state.revision, envelope: envelope) }
        } catch let failure as RelayError where failure.isNotFound {
            try? storage.write { try $0.communities.discard(item) }
        } catch is CommunityError {
            try? storage.write { try $0.communities.discard(item) }
        } catch {}
        return nil
    }

    /// One pass over the outbox; `true` when the relay refused a receipt or
    /// delete as stale (`409`): a fresh pass seals it again.
    @discardableResult
    private func flushOnce(_ pass: inout CommunityPass) async -> Bool {
        var resealNeeded = false
        guard isActive, let items = try? storage.read({ try $0.communities.pending() }) else { return false }
        var stopped = Set<String>()
        var staleCommunities = Set<AccountId>()
        for queued in items {
            guard isActive else { return resealNeeded }
            var item = queued
            let key = item.communityId.string + "/" + item.messageId.string
            if stopped.contains(key) || staleCommunities.contains(item.communityId) { continue }
            let state: CommunityState
            switch await lookup(item.communityId, in: &pass) {
            case .gone:
                try? storage.write { try $0.communities.cancel(item) }
                stopped.insert(key); continue
            case .unavailable:
                continue
            case .current(let current):
                state = current
            }
            // Queued under a newer roster than this pass saw: next pass.
            if item.revision > state.revision { continue }
            if item.event != nil {
                // A receipt or delete carries its content: under a newer roster
                // it is sealed again for its member, if still one.
                if item.revision < state.revision || state.member(item.recipient) == nil {
                    guard let resealed = await reseal(item, for: state) else { continue }
                    item = resealed
                }
            } else {
                guard item.revision == state.revision else {
                    try? storage.write { try $0.communities.cancel(item) }
                    stopped.insert(key); continue
                }
                guard state.member(item.recipient) != nil else {
                    try? storage.write { try $0.communities.dropRecipient(item) }
                    continue
                }
            }
            if let until = fullInboxes[item.recipient], until > Date() { continue }
            do {
                try check()
                _ = try await relay.sendCommunityEnvelope(item.envelope, community: item.communityId, recipient: item.recipient, messageId: item.messageId, revision: item.revision)
                try check()
                try storage.write { try $0.communities.complete(item) }
            } catch let failure as RelayError {
                switch failure {
                case .communityInboxFull(let retryAfter):
                    // Only this recipient's mail is full: the others go on, it is retried later.
                    fullInboxes[item.recipient] = Date().addingTimeInterval(retryAfter ?? 60)
                    pass.note(.rateLimited)
                case .rateLimited:
                    // The relay limits this account: everything waits for a later pass.
                    pass.note(.rateLimited)
                    return resealNeeded
                case .server(404, _, _):
                    // The member left, was removed or deleted the account: skip them;
                    // the next pass reads the roster again.
                    try? storage.write { tx in
                        if item.event != nil { try tx.communities.discard(item) } else { try tx.communities.dropRecipient(item) }
                    }
                case .server(409, _, _):
                    // The roster moved (or the ID clashed): the community's other
                    // items wait for the next pass's roster. A message stops (it
                    // can be sent again); a receipt or delete is sealed again then.
                    if item.event != nil {
                        resealNeeded = true
                    } else {
                        try? storage.write { try $0.communities.cancel(item) }
                        stopped.insert(key)
                        pass.note(.stale)
                    }
                    staleCommunities.insert(item.communityId)
                    pass.states[item.communityId] = nil; pass.unavailable.insert(item.communityId)
                case .server(let status, _, _) where (400..<500).contains(status) && status != 408:
                    // Only this recipient cannot be reached.
                    try? storage.write { tx in
                        if item.event != nil { try tx.communities.discard(item) } else { try tx.communities.dropRecipient(item, as: .failed) }
                    }
                default:
                    // Offline or the relay is failing: everything stays queued.
                    pass.note(CommunityIssue(failure))
                    return resealNeeded
                }
            } catch {
                return resealNeeded
            }
        }
        return resealNeeded
    }
}
