import Foundation

/// Where the account's avatar record for search stands (PROTOCOL.md §8.10,
/// "Publishing").
public enum AvatarRecordStatus: Sendable, Equatable {
    /// The relay holds what the picture, the "Show in search" setting and the
    /// username ask for: the record, or none.
    case inSync
    /// A write is still owed: it got no answer, `429` or `5xx`, or a
    /// restore's token rotation is not settled yet. It is made again at the
    /// next connection or the next change.
    case pending
    /// The relay has no avatar records (`404` or `405`): tried again after
    /// the next app start. Contacts still get the picture end to end.
    case unavailable
}

/// The user's own picture and its search setting (§8.9, §8.10).
public struct OwnProfilePicture: Sendable, Equatable {
    public var picture: ProfilePicture
    /// "Aramada göster" / "Show in search"; on by default.
    public var showInSearch: Bool
    /// The state of the search record (none is published for the initial
    /// letter or with the setting off).
    public var searchRecord: AvatarRecordStatus

    public init(picture: ProfilePicture, showInSearch: Bool, searchRecord: AvatarRecordStatus) {
        self.picture = picture
        self.showInSearch = showInSearch
        self.searchRecord = searchRecord
    }

    /// Whether a record is published for search: a preset or a photo with
    /// the setting on.
    public var isShownInSearch: Bool { showInSearch && picture != .initials }
}

/// What the relay should hold for this account (§8.10 "Publishing"): a
/// record of the current picture under the current username while the
/// setting is on and the picture is a preset or a photo this client may send
/// (§8.9), otherwise none.
enum DesiredAvatarRecord: Equatable {
    /// No record (the initial letter, the setting off, or a picture that
    /// may not be sent, e.g. a photo with metadata from an old backup).
    case absent
    case record(profileAvatar: Data, username: NormalizedUsername)

    init(_ account: LocalAccount) {
        let picture = account.profilePicture
        guard !account.avatarSearchOff, picture != .initials, picture.isSendable,
            let serialized = picture.serializedProfileAvatar
        else {
            self = .absent
            return
        }
        self = .record(profileAvatar: serialized, username: account.username)
    }
}

/// A ``Messenger``'s avatar state that lives in memory only and goes with
/// it when the vault locks (§5.4): username seeds, pictures found in search,
/// and the one write in progress.
struct AvatarSession {
    struct SearchKey: Hashable {
        let account: AccountId
        let username: String
    }

    struct SearchEntry {
        /// The version (`ETag`) the picture was opened from.
        let version: UInt64
        let picture: ProfilePicture
        var checkedAt: Date
    }

    static let maxSeeds = 16
    static let maxSearchEntries = 64
    static let maxNewestOpened = 1024
    /// A search answered within this time is not asked again (the fetch
    /// limit is 60 per hour, §4.11; §8.10 "Caching" allows it).
    static let searchFreshness: TimeInterval = 60

    /// `seed` (§8.10) per normalized username: the PBKDF2 step is the slow one.
    private(set) var seeds: [String: Data] = [:]
    private var seedOrder: [String] = []
    private(set) var search: [SearchKey: SearchEntry] = [:]
    /// The highest version opened per account and username (§8.10 "Search"
    /// step 5): a record below it is a replay and is ignored. Not lowered by
    /// a `404` or a record that does not open.
    private(set) var newestOpened: [SearchKey: UInt64] = [:]
    var searches: [SearchKey: Task<ProfilePicture, Never>] = [:]
    /// The write in progress; later callers join it (§8.10: one at a time).
    var sync: Task<AvatarRecordStatus, Never>?
    /// `404`/`405` for a write: no further attempt until the next app start.
    var relayLacksAvatars = false
    /// `429` for a store (`PUT`): no store before this time (`Retry-After`).
    var storeNotBefore: Date?
    /// `429` for a delete: no delete before this time. Deletes have a budget
    /// of their own (§4.11), so a refused store never holds one up.
    var deleteNotBefore: Date?

    /// The wait that applies to the write `desired` needs, if any.
    func notBefore(for desired: DesiredAvatarRecord) -> Date? {
        desired == .absent ? deleteNotBefore : storeNotBefore
    }

    mutating func noteRateLimited(_ desired: DesiredAvatarRecord, until date: Date) {
        if desired == .absent { deleteNotBefore = date } else { storeNotBefore = date }
    }

    mutating func noteOpened(_ version: UInt64, for key: SearchKey) {
        newestOpened[key] = max(newestOpened[key] ?? 0, version)
        if newestOpened.count > Self.maxNewestOpened, let evicted = newestOpened.keys.first(where: { $0 != key }) {
            newestOpened[evicted] = nil
        }
    }

    mutating func rememberSeed(_ seed: Data, for username: String) {
        if seeds[username] == nil { seedOrder.append(username) }
        seeds[username] = seed
        while seedOrder.count > Self.maxSeeds {
            let evicted = seedOrder.removeFirst()
            seeds[evicted]?.zeroize()
            seeds[evicted] = nil
        }
    }

    mutating func remember(_ entry: SearchEntry, for key: SearchKey) {
        search[key] = entry
        while search.count > Self.maxSearchEntries,
            let oldest = search.min(by: { $0.value.checkedAt < $1.value.checkedAt })?.key
        {
            search[oldest] = nil
        }
    }

    mutating func forget(_ key: SearchKey) {
        search[key] = nil
    }

    /// Drops what search found for `username` under any other account
    /// (§8.10 "Caching": the lookup now maps elsewhere, or to nobody).
    mutating func forgetSearches(of username: String, keeping account: AccountId?) {
        search = search.filter { $0.key.username != username || $0.key.account == account }
    }

    mutating func clear() {
        for key in seeds.keys { seeds[key]?.zeroize() }
        seeds = [:]
        seedOrder = []
        search = [:]
        newestOpened = [:]
    }
}

extension Messenger {
    // MARK: Own picture (§8.9, §8.10)

    /// The user's picture, the "Show in search" setting and the state of the
    /// search record.
    public var ownProfilePicture: OwnProfilePicture {
        let status: AvatarRecordStatus =
            !account.avatarRecordPending ? .inSync : avatarSession.relayLacksAvatars ? .unavailable : .pending
        return OwnProfilePicture(
            picture: account.profilePicture, showInSearch: !account.avatarSearchOff, searchRecord: status)
    }

    /// The complete `ProfileUpdate` (§8.7) with the own username and picture.
    public func ownProfileUpdate(displayName: String, presenceSharing: Bool, updatedAt: Date = Date()) -> BRPContent {
        .profileUpdate(
            displayName: displayName, username: account.username, presenceSharing: presenceSharing,
            picture: account.profilePicture, updatedAt: updatedAt)
    }

    /// Sets the user's picture and "Show in search" and saves both with the
    /// account (in the vault, and from there in the next backup). When the
    /// avatar record must change — a new record for a preset or a photo with
    /// the setting on, none otherwise (§8.10) — a write is owed from now on:
    /// make it with ``syncAvatarRecord()`` (it is also made at the next
    /// connection and by maintenance).
    ///
    /// The contacts' copy is the app's to send: a complete `ProfileUpdate`
    /// (``ownProfileUpdate(displayName:presenceSharing:updatedAt:)``) to every
    /// accepted contact when the picture changed — not for the setting alone
    /// (§8.7).
    ///
    /// - Throws: ``MessengerError/invalidContent(_:)`` for preset id 0 or a
    ///   photo this client may not send (not square, or with metadata:
    ///   ``AvatarPhoto/isSendable``), or the account saver's error (nothing
    ///   changed then).
    public func setProfilePicture(_ picture: ProfilePicture, showInSearch: Bool) throws {
        guard picture.isSendable else { throw MessengerError.invalidContent(.malformedField("profile_avatar")) }
        let before = DesiredAvatarRecord(account)
        var updated = account
        updated.profileAvatar = picture.serializedProfileAvatar
        updated.avatarSearchOff = !showInSearch
        if DesiredAvatarRecord(updated) != before { updated.avatarRecordPending = true }
        try commitAccount(updated)
    }

    /// Brings the relay's avatar record in line with the picture, the
    /// setting and the username, if a change is owed (§8.10 "Publishing"):
    /// `PUT` of a freshly sealed record, or `DELETE`, with the next version,
    /// saved before it is sent; after `409 stale_version` once more with
    /// `X-Avatar-Version + 1`. Writes are made one at a time: a caller while
    /// one runs joins it, and that one goes on until the relay matches the
    /// latest state or a write fails. Nothing is sent while a restore's
    /// token rotation is unsettled.
    @discardableResult
    public func syncAvatarRecord() async -> AvatarRecordStatus {
        if let running = avatarSession.sync { return await running.value }
        guard account.avatarRecordPending else { return .inSync }
        let task = Task { await self.writeAvatarRecord() }
        avatarSession.sync = task
        return await task.value
    }

    /// Starts ``syncAvatarRecord()`` without waiting, when a write is owed.
    func scheduleAvatarSync() {
        guard account.avatarRecordPending, avatarSession.sync == nil, !avatarSession.relayLacksAvatars else { return }
        if let notBefore = avatarSession.notBefore(for: DesiredAvatarRecord(account)), Date() < notBefore { return }
        Task { await self.syncAvatarRecord() }
    }

    /// After a username change (`204`): the relay deleted the record with it
    /// (§4.3), so a published picture is sealed again under the new name.
    func republishAvatarAfterUsernameChange() {
        guard DesiredAvatarRecord(account) != .absent else { return }
        try? saveAvatarState { $0.avatarRecordPending = true }
        scheduleAvatarSync()
    }

    private func writeAvatarRecord() async -> AvatarRecordStatus {
        defer { avatarSession.sync = nil }
        var repeatedAfterConflict = false
        while account.avatarRecordPending {
            if avatarSession.relayLacksAvatars { return .unavailable }
            let desired = DesiredAvatarRecord(account)
            // Only a wait for this kind of write applies (§4.11).
            if let notBefore = avatarSession.notBefore(for: desired), Date() < notBefore { return .pending }
            guard account.pendingAuthToken == nil, relay.credentials != nil else { return .pending }
            guard account.avatarVersion < AvatarRecord.versions.upperBound else { return .pending }
            let version = account.avatarVersion + 1
            var record: Data?
            if case .record(let profileAvatar, let username) = desired {
                // Never sealed unless it may be sent (§8.9), whoever stored it.
                guard ProfilePicture(serialized: profileAvatar).isSendable else { return .pending }
                let key = await avatarRecordKey(username: username, account: account.accountId)
                // The picture or the name may have changed meanwhile.
                guard DesiredAvatarRecord(account) == desired else { continue }
                guard let sealed = try? AvatarRecord.seal(profileAvatar, version: version, key: key) else {
                    return .pending
                }
                record = sealed
            }
            // Saved before it is sent: a write whose answer is lost is never
            // followed by another with the same version.
            do {
                try saveAvatarState { $0.avatarVersion = version }
            } catch {
                return .pending
            }
            let outcome: AvatarWriteOutcome
            do {
                if let record {
                    outcome = try await relay.putAvatar(record)
                } else {
                    outcome = try await relay.deleteAvatar(version: version)
                }
            } catch {
                return noteAvatarWriteFailure(error, for: desired)
            }
            switch outcome {
            case .accepted:
                if desired == .absent { avatarSession.deleteNotBefore = nil } else { avatarSession.storeNotBefore = nil }
                // Changed while the write was under way: write again.
                guard DesiredAvatarRecord(account) == desired else { continue }
                do {
                    try saveAvatarState { $0.avatarRecordPending = false }
                } catch {
                    return .pending
                }
                return .inSync
            case .stale(let highest):
                // A correct client needs at most one repeat (§8.10).
                guard !repeatedAfterConflict, let highest, highest < AvatarRecord.versions.upperBound else {
                    return .pending
                }
                repeatedAfterConflict = true
                do {
                    try saveAvatarState { $0.avatarVersion = max($0.avatarVersion, highest) }
                } catch {
                    return .pending
                }
            }
        }
        return .inSync
    }

    private func noteAvatarWriteFailure(_ error: any Error, for desired: DesiredAvatarRecord) -> AvatarRecordStatus {
        switch error as? RelayError {
        case .server(status: 404, _, _)?, .server(status: 405, _, _)?:
            avatarSession.relayLacksAvatars = true
            return .unavailable
        case .rateLimited(let retryAfter)?:
            // At most a day, as on Android; a store's wait never delays a delete.
            let wait = min(max(retryAfter ?? 60, 1), 86_400)
            avatarSession.noteRateLimited(desired, until: Date().addingTimeInterval(wait))
            return .pending
        default:
            return .pending
        }
    }

    /// Changes the account and saves it before it is used.
    func saveAvatarState(_ change: (inout LocalAccount) -> Void) throws {
        var updated = account
        change(&updated)
        try commitAccount(updated)
    }

    /// The record key of `account` under `username`; the PBKDF2 seed is
    /// derived off the actor once per username and kept in memory only.
    func avatarRecordKey(username: NormalizedUsername, account: AccountId) async -> AvatarRecordKey {
        if let seed = avatarSession.seeds[username.value] { return AvatarRecordKey(seed: seed, accountId: account) }
        let seed = await Task.detached(priority: .userInitiated) { AvatarRecord.seed(for: username) }.value
        avatarSession.rememberSeed(seed, for: username.value)
        return AvatarRecordKey(seed: seed, accountId: account)
    }

    // MARK: Search (§8.10)

    /// Derives the record key of a username ahead of its search result,
    /// e.g. while the directory lookup runs (§8.10 step 1).
    public func prepareAvatarSearch(username: NormalizedUsername) async {
        if avatarSession.seeds[username.value] != nil { return }
        let seed = await Task.detached(priority: .userInitiated) { AvatarRecord.seed(for: username) }.value
        avatarSession.rememberSeed(seed, for: username.value)
    }

    /// The picture of a search result (§8.10 "Search", step 4): `account`
    /// is what the directory returned for `username` (step 2). Fetches the
    /// avatar record — with `If-None-Match` when a copy for this account and
    /// username is cached — opens it with the key of `username` and
    /// `account`, and checks the picture (§8.9). Every failure — no record,
    /// no answer, `429`, `5xx`, a record that does not open — is the initial
    /// letter; nothing is thrown or shown as an error. A record older than
    /// one opened before for this account and username is a replay (§8.10
    /// step 5): it is ignored, and the cached copy, if any, is kept.
    ///
    /// Call it only for an exact directory hit the user searched for, never
    /// while typing nor for lookups the app makes by itself, and not for
    /// accepted contacts, whose `ProfileUpdate` carries the picture (§8.9):
    /// every fetch tells the relay who looked at whom. Copies live in memory
    /// only and go with this messenger; a search answered within the last
    /// minute is not asked again.
    public func searchAvatar(username: NormalizedUsername, account target: AccountId, now: Date = Date()) async
        -> ProfilePicture
    {
        if target == account.accountId { return account.profilePicture }
        avatarSession.forgetSearches(of: username.value, keeping: target)
        let key = AvatarSession.SearchKey(account: target, username: username.value)
        if let running = avatarSession.searches[key] { return await running.value }
        let cached = avatarSession.search[key]
        if let cached, now >= cached.checkedAt, now.timeIntervalSince(cached.checkedAt) < AvatarSession.searchFreshness {
            return cached.picture
        }
        let task = Task { await self.fetchSearchedAvatar(key, username: username, cached: cached, now: now) }
        avatarSession.searches[key] = task
        let picture = await task.value
        avatarSession.searches[key] = nil
        return picture
    }

    private func fetchSearchedAvatar(
        _ key: AvatarSession.SearchKey, username: NormalizedUsername, cached: AvatarSession.SearchEntry?, now: Date
    ) async -> ProfilePicture {
        let result: AvatarFetchResult
        do {
            result = try await relay.fetchAvatar(of: key.account, ifNoneMatch: cached?.version)
        } catch {
            return .initials  // the next search asks again
        }
        switch result {
        case .notModified:
            guard var cached else { return .initials }
            cached.checkedAt = now
            avatarSession.remember(cached, for: key)
            return cached.picture
        case .notFound:
            avatarSession.forget(key)
            return .initials
        case .record(let record):
            // Length and header first, before anything is derived.
            guard let version = AvatarRecord.version(of: record) else {
                avatarSession.forget(key)
                return .initials
            }
            // Older than a record opened before: a replay (step 5). No key is
            // derived for it, and the cached copy stays.
            if let newest = avatarSession.newestOpened[key], version < newest {
                return avatarSession.search[key]?.picture ?? .initials
            }
            let recordKey = await avatarRecordKey(username: username, account: key.account)
            guard let opened = try? AvatarRecord.openPicture(record, key: recordKey) else {
                avatarSession.forget(key)
                return .initials
            }
            // Authenticated: its version counts even if the picture fails §8.9.
            avatarSession.noteOpened(opened.version, for: key)
            avatarSession.remember(
                AvatarSession.SearchEntry(version: opened.version, picture: opened.picture, checkedAt: now), for: key)
            return opened.picture
        }
    }

    /// The directory maps `username` to `account` now (or to nobody):
    /// copies found in search under any other account go (§8.10 "Caching").
    func noteDirectoryAnswer(for username: NormalizedUsername, account: AccountId?) {
        avatarSession.forgetSearches(of: username.value, keeping: account)
    }

    /// Drops every seed and search copy now (they go with the messenger
    /// anyway when the vault locks).
    public func forgetAvatarCaches() {
        avatarSession.clear()
    }
}
