import Foundation
import Observation
import Us2usKit
import Us2usRuntime
import Us2usStorage

/// The live `CommunitiesService`: Us2usRuntime's `CommunityService` mapped
/// onto the screens' types. Every property is computed from the runtime's
/// observable state, so SwiftUI tracks the runtime directly; nothing is kept
/// here beyond the unlock (the runtime replaces its service on every unlock).
@Observable
@MainActor
final class RealCommunitiesService: CommunitiesService {
    @ObservationIgnored let service: CommunityService
    /// How picked media is prepared (the runtime's: no metadata, ≤ 2048 px, ≤ 720p).
    @ObservationIgnored let preparation: CommunityMediaPreparation
    /// Photos and videos picked in “+”, shown at once while they are prepared.
    @ObservationIgnored let outgoingMedia: OutgoingMedia
    /// Full photos of the open community, decrypted into memory only.
    var media: [MessageID: Data] = [:]
    @ObservationIgnored var mediaLoading: Set<MessageID> = []
    @ObservationIgnored var conversationCache: ConversationCache?

    init(service: CommunityService, preparation: CommunityMediaPreparation = .unavailable) {
        self.service = service
        self.preparation = preparation
        outgoingMedia = OutgoingMedia(steps: Self.mediaSteps(service: service, preparation: preparation))
        outgoingMedia.onSent = { [weak self] item in
            if let image = item.keptImage { self?.media[item.id] = image }
        }
    }

    // MARK: State

    var rows: [CommunityRow] { service.joined.map(row) }

    var isSupported: Bool { !service.unsupported }

    var problem: CommunityProblem? { service.issue.map(problem) }

    var directory: [CommunityDirectoryEntry] {
        let known = Set(service.joined.map(\.id))
        return service.directory.map {
            CommunityDirectoryEntry(id: $0.id.string, name: $0.name, memberCount: $0.memberCount, isKnown: known.contains($0.id))
        }
    }

    var hasMoreResults: Bool { service.hasMoreResults }

    var openRequests: [CommunityJoinRequestRow] {
        mapRequests(service.requests)
    }

    var requestInbox: [CommunityRequestInboxRow] {
        rows.filter { $0.phase == .owner }.compactMap { row in
            let count = requests(in: row.id).count
            return count > 0 ? CommunityRequestInboxRow(id: row.id, name: row.name, count: count) : nil
        }.sorted { $0.id < $1.id }
    }

    func requests(in id: CommunityID) -> [CommunityJoinRequestRow] {
        guard let account = AccountId(string: id) else { return [] }
        return mapRequests(service.ownerRequests[account] ?? (service.selected == account ? service.requests : []))
    }

    private func mapRequests(_ pending: [CommunityJoinRequest]) -> [CommunityJoinRequestRow] {
        let names = service.usernames
        return pending.map { request in
            CommunityJoinRequestRow(
                id: request.accountId.string, username: names[request.accountId], shortID: Self.shortID(request.accountId),
                keyFingerprint: CommunityIdentifiers.keyFingerprint(request.identityKey),
                requestedAt: Date(timeIntervalSince1970: TimeInterval(request.createdAt) / 1000))
        }
    }

    func row(_ id: CommunityID) -> CommunityRow? {
        service.joined.first { $0.id.string == id }.map(row)
    }

    func members(of id: CommunityID) -> [CommunityMemberRow] {
        guard let state = service.joined.first(where: { $0.id.string == id })?.state else { return [] }
        let names = service.usernames
        return state.members.map { member in
            CommunityMemberRow(
                id: member.accountId.string, username: names[member.accountId], shortID: Self.shortID(member.accountId),
                isOwner: member.accountId == state.owner.accountId, isSelf: member.accountId == service.accountId)
        }
        // The owner first, then this account, then the others by name.
        .sorted { lhs, rhs in
            if lhs.isOwner != rhs.isOwner { return lhs.isOwner }
            if lhs.isSelf != rhs.isSelf { return lhs.isSelf }
            return (lhs.username ?? "~" + lhs.shortID) < (rhs.username ?? "~" + rhs.shortID)
        }
    }

    // MARK: Actions

    func screenAppeared() { service.screenAppeared() }
    func screenDisappeared() { service.screenDisappeared() }
    func refresh() async { await service.refresh() }
    func dismissProblem() { service.dismissIssue() }

    func search(_ query: String, more: Bool) async throws(CommunityProblem) {
        try await run { try await service.search(query, more: more) }
    }

    func create(name: String, isPublic: Bool) async throws(CommunityProblem) {
        try await run { try await service.create(name: name, isPublic: isPublic) }
    }

    func requestJoin(_ id: CommunityID) async throws(CommunityProblem) {
        guard let summary = service.directory.first(where: { $0.id.string == id }) else { throw .notFound }
        try await run { try await service.requestJoin(summary) }
    }

    func redeem(_ code: String) async throws(CommunityProblem) {
        try await run { try await service.redeem(code) }
    }

    func leave(_ id: CommunityID) async throws(CommunityProblem) {
        let account = try account(id)
        try await run { try await service.leave(account) }
    }

    func withdraw(_ id: CommunityID) async throws(CommunityProblem) {
        let account = try account(id)
        try await run { try await service.withdraw(account) }
    }

    func forget(_ id: CommunityID) async throws(CommunityProblem) {
        let account = try account(id)
        try await run { try await service.forget(account) }
    }

    func loadOlderMessages(in id: CommunityID) async throws -> Bool {
        try await service.loadOlderMessages(try account(id))
    }

    func open(_ id: CommunityID) async throws(CommunityProblem) {
        let account = try account(id)
        try await run { try await service.open(account) }
    }

    func close(_ id: CommunityID) {
        outgoingMedia.conversationClosed(id)
        if service.selected?.string == id {
            service.closeConversation()
            media = [:]
            mediaLoading = []
            conversationCache = nil
        }
    }

    func cover(_ id: CommunityID) {
        guard let account = AccountId(string: id) else { return }
        service.coverConversation(account)
    }

    func send(_ text: String, to id: CommunityID) async throws(CommunityProblem) {
        let account = try account(id)
        try await run { try await service.send(text, to: account) }
    }

    func approve(_ request: CommunityID, in id: CommunityID) async throws(CommunityProblem) {
        let account = try account(id)
        guard let pending = (service.ownerRequests[account] ?? (service.selected == account ? service.requests : [])).first(where: { $0.accountId.string == request }) else { throw .stale }
        try await run { try await service.approve(pending, in: account) }
    }

    func reject(_ request: CommunityID, in id: CommunityID) async throws(CommunityProblem) {
        let account = try account(id)
        guard let pending = (service.ownerRequests[account] ?? (service.selected == account ? service.requests : [])).first(where: { $0.accountId.string == request }) else { throw .stale }
        try await run { try await service.reject(pending, in: account) }
    }

    func removeMember(_ member: String, from id: CommunityID) async throws(CommunityProblem) {
        let account = try account(id)
        guard let state = service.joined.first(where: { $0.id == account })?.state,
              let target = state.members.first(where: { $0.accountId.string == member })
        else { throw .stale }
        try await run { try await service.remove(target, from: account) }
    }

    func invite(_ id: CommunityID) async throws(CommunityProblem) -> String {
        let account = try account(id)
        return try await run { try await service.invite(account) }
    }

    func revokeInvites(_ id: CommunityID) async throws(CommunityProblem) {
        let account = try account(id)
        try await run { try await service.revokeInvites(account) }
    }

    func delete(_ id: CommunityID) async throws(CommunityProblem) {
        let account = try account(id)
        try await run { try await service.delete(account) }
    }

    // MARK: Mapping

    private func account(_ id: CommunityID) throws(CommunityProblem) -> AccountId {
        guard let account = AccountId(string: id) else { throw .notFound }
        return account
    }

    func run<T>(_ action: () async throws -> T) async throws(CommunityProblem) -> T {
        do {
            return try await action()
        } catch {
            throw problem(CommunityIssue(error))
        }
    }

    private func row(_ group: StoredCommunity) -> CommunityRow {
        let phase: CommunityPhase
        if let state = group.state {
            if !group.isActive {
                phase = .ended
            } else {
                phase = state.owner.accountId == service.accountId ? .owner : .member
            }
        } else {
            // Never verified: our own creation the relay did not confirm, or a request.
            phase = group.ownerKey == service.identityKey ? .unfinished : .pending
        }
        return CommunityRow(
            id: group.id.string, name: group.name, isPublic: group.state?.isPublic ?? false,
            memberCount: group.state?.members.count, phase: phase)
    }

    private func problem(_ issue: CommunityIssue) -> CommunityProblem {
        switch issue {
        case .offline, .locked: .offline
        case .unsupported: .unsupported
        case .verification: .verification
        case .memberKey(let member): .memberKey(name: service.usernames[member], shortID: Self.shortID(member))
        case .stale: .stale
        case .full: .full
        case .notFound: .notFound
        case .rateLimited: .rateLimited
        case .invalidName: .invalidName
        case .invalidInvite: .invalidInvite
        case .ownerCannotLeave: .ownerCannotLeave
        case .failed: .failed
        }
    }

    static func shortID(_ account: AccountId) -> String { CommunityIdentifiers.shortID(account.string) }
}
