// us2us — AGPL-3.0-only

import Foundation
import LibSignalClient
import SwiftProtobuf
import Us2usKit

/// Contacts and the message-request state machine of PROTOCOL.md §6.4:
///
/// | from | event | to |
/// |---|---|---|
/// | none | we send | `outgoingRequest` |
/// | none | verified message received | `incomingRequest` |
/// | `outgoingRequest` | `MessageRequestResponse{ACCEPT}` received | `accepted` |
/// | `incomingRequest` | user accepts and the relay answered 202 | `accepted` |
/// | any | Block | `blocked` |
///
/// No other event ends a request. Unblocking returns to the state before the block.
public struct ContactRepository {
    let tx: StorageTransaction

    private static let columns = """
        account, request_state, state_before_block, username, username_status, display_name,
        presence_sharing, avatar, profile_updated_at, their_access_key, our_access_key_sent,
        profile_sent_at, pending_forget, created_at
        """

    public func get(_ account: AccountId) throws -> Contact? {
        try tx.connection.queryOne("SELECT \(Self.columns) FROM contacts WHERE account = ?", [account.bytes])
            .map(contact(from:))
    }

    /// All contacts except those waiting to be forgotten, newest first.
    public func all(includingPendingForget: Bool = false) throws -> [Contact] {
        try tx.connection.query(
            """
            SELECT \(Self.columns) FROM contacts
            WHERE pending_forget = 0 OR ?
            ORDER BY created_at DESC
            """, [includingPendingForget]
        ).map(contact(from:))
    }

    public func accepted() throws -> [Contact] {
        try tx.connection.query(
            "SELECT \(Self.columns) FROM contacts WHERE request_state = ? AND pending_forget = 0",
            [ConversationRequestState.accepted.rawValue]
        ).map(contact(from:))
    }

    /// Every blocked account, with a conversation or without one (a ghost
    /// wipe or a deleted conversation leaves the block, §6.4).
    public func blocked() throws -> [Contact] {
        try tx.connection.query(
            "SELECT \(Self.columns) FROM contacts WHERE request_state = ? AND pending_forget = 0",
            [ConversationRequestState.blocked.rawValue]
        ).map(contact(from:))
    }

    public func requestState(of account: AccountId) throws -> ConversationRequestState? {
        try tx.connection.queryOne("SELECT request_state FROM contacts WHERE account = ?", [account.bytes])?
            .text("request_state").flatMap(ConversationRequestState.init(rawValue:))
    }

    // MARK: §6.4 transitions

    /// Before sending to `account`: an account without state becomes an
    /// outgoing request. Returns the state to send under (throws if blocked).
    @discardableResult
    public func prepareToSend(to account: AccountId, now: Date = Date()) throws -> ConversationRequestState {
        try tx.requireWritable()
        if let state = try requestState(of: account) {
            guard state != .blocked else { throw StorageError.invalidState("blocked") }
            return state
        }
        try insert(account, state: .outgoingRequest, now: now)
        return .outgoingRequest
    }

    /// After a message from `account` passed sender verification (§6.3):
    /// creates an incoming request for an unknown account, and ends an
    /// outgoing request on `ACCEPT`. Returns the new state; `blocked` means
    /// the message must be dropped locally.
    @discardableResult
    public func noteVerifiedMessage(
        _ content: BRPContent, from account: AccountId, now: Date = Date()
    ) throws -> ConversationRequestState {
        try tx.requireWritable()
        let current = try requestState(of: account)
        let next = MessageRequestPolicy.state(afterReceiving: content, current: current)
        if current == nil {
            try insert(account, state: next, now: now)
        } else if next != current {
            try setState(next, of: account)
        }
        return next
    }

    /// The user accepted an incoming request and the relay answered 202 for
    /// our `ACCEPT` (§6.4). Before that the state must not change.
    public func acceptIncomingRequest(from account: AccountId) throws {
        try tx.requireWritable()
        switch try requestState(of: account) {
        case .incomingRequest: try setState(.accepted, of: account)
        case .accepted: return
        default: throw StorageError.invalidState("no incoming request")
        }
    }

    /// Both sides wrote first (§6.4 crossing requests): we are in
    /// `outgoingRequest` and a verified visible message from the contact
    /// carried its `sender_username`, so it is in `outgoingRequest` with us
    /// too. Both phones then treat that as mutual acceptance; the runtime
    /// sends `ACCEPT` so the other phone ends its request as well.
    /// Returns whether the state changed.
    @discardableResult
    public func acceptCrossingRequest(from account: AccountId) throws -> Bool {
        try tx.requireWritable()
        guard try requestState(of: account) == .outgoingRequest else { return false }
        try setState(.accepted, of: account)
        return true
    }

    /// Any state → blocked. The runtime must rotate the access key (§6.4).
    public func block(_ account: AccountId, now: Date = Date()) throws {
        try tx.requireWritable()
        guard let current = try requestState(of: account) else {
            try insert(account, state: .blocked, now: now)
            return
        }
        guard current != .blocked else { return }
        try tx.connection.run(
            "UPDATE contacts SET request_state = ?, state_before_block = ? WHERE account = ?",
            [ConversationRequestState.blocked.rawValue, current.rawValue, account.bytes])
    }

    /// Blocked → the state before the block. Returns `false` (and changes
    /// nothing) when the account is not blocked.
    @discardableResult
    public func unblock(_ account: AccountId) throws -> Bool {
        try tx.requireWritable()
        guard
            let row = try tx.connection.queryOne(
                "SELECT request_state, state_before_block FROM contacts WHERE account = ?", [account.bytes]),
            row.text("request_state") == ConversationRequestState.blocked.rawValue
        else { return false }
        let previous =
            row.text("state_before_block").flatMap(ConversationRequestState.init(rawValue:)) ?? .incomingRequest
        try tx.connection.run(
            "UPDATE contacts SET request_state = ?, state_before_block = NULL WHERE account = ?",
            [previous.rawValue, account.bytes])
        return true
    }

    /// Unblocking an account with nothing to go back to: no conversation,
    /// and before the block no contact either (blocked as a stranger, or
    /// kept as a bare block by “leave no trace”, `stripToBlock`) or only an
    /// incoming request, whose messages went with the conversation. It is
    /// forgotten, a stranger again: writing to it starts a new chat and a
    /// message from it is a new request, never an empty one. Returns
    /// `false` (and changes nothing) for any other account.
    @discardableResult
    public func unblockLeavingNothing(_ account: AccountId) throws -> Bool {
        try tx.requireWritable()
        guard
            let row = try tx.connection.queryOne(
                "SELECT request_state, state_before_block FROM contacts WHERE account = ?", [account.bytes]),
            row.text("request_state") == ConversationRequestState.blocked.rawValue,
            [nil, ConversationRequestState.incomingRequest.rawValue].contains(row.text("state_before_block")),
            try tx.conversations.forContact(account) == nil
        else { return false }
        try forget(account)
        return true
    }

    /// The user writes to a blocked account again (4.2) that has no state
    /// to go back to — blocked before it was a contact, or kept as a bare
    /// block by “leave no trace” (`stripToBlock`): unblocking it then makes
    /// it an outgoing request, as a new chat would be. A state kept from
    /// before the block stays as it is.
    public func unblockAsOutgoingRequest(_ account: AccountId) throws {
        try tx.requireWritable()
        try tx.connection.run(
            """
            UPDATE contacts SET state_before_block = ?
            WHERE account = ? AND request_state = ? AND state_before_block IS NULL
            """,
            [ConversationRequestState.outgoingRequest.rawValue, account.bytes,
             ConversationRequestState.blocked.rawValue])
    }

    // MARK: Access keys (§4.6, §6.4)

    /// The contact's `AccessKeyUpdate`.
    public func setTheirAccessKey(_ key: Data?, for account: AccountId) throws {
        try tx.requireWritable()
        if let key, key.count != AccessKey.byteCount { throw StorageError.invalidState("access key size") }
        try update(account, "their_access_key = ?", [key])
    }

    /// Records which of our access keys the contact holds.
    public func setOurAccessKeySent(_ key: Data?, to account: AccountId) throws {
        try tx.requireWritable()
        try update(account, "our_access_key_sent = ?", [key])
    }

    /// Accepted contacts that do not hold `current` yet (after a rotation).
    public func needingAccessKey(current: Data) throws -> [Contact] {
        try accepted().filter { $0.ourAccessKeySent != current }
    }

    // MARK: Profiles (§8.7, §3.3)

    /// Applies a complete `ProfileUpdate`; an older one (by `updated_at`) is
    /// ignored. Returns the stored profile. An `updated_at` more than a day
    /// ahead of `now` is clamped, so a far-future stamp cannot make every
    /// later update look older.
    @discardableResult
    public func applyProfileUpdate(_ update: BRPProfileUpdate, from account: AccountId, now: Date = Date()) throws
        -> ContactProfile
    {
        try tx.requireWritable()
        var update = update
        let ceiling = now.addingTimeInterval(ConversationRepository.maxClockSkew).millisecondsSince1970
        if update.updatedAt > ceiling { update.updatedAt = ceiling }
        guard let contact = try get(account) else { throw StorageError.notFound }
        guard update.updatedAt >= contact.profile.updatedAt else { return contact.profile }
        let profile = contact.profile.applying(update)
        try setProfile(profile, for: account)
        return profile
    }

    /// Stores `profile`; its picture as a serialized `ProfileAvatar` (none
    /// for the initial letter), inside the encrypted database only (§8.9).
    public func setProfile(_ profile: ContactProfile, for account: AccountId) throws {
        try tx.requireWritable()
        let avatar = profile.avatar.serializedProfileAvatar
        try update(
            account,
            """
            username = ?, username_status = ?, display_name = ?, presence_sharing = ?, avatar = ?,
            profile_updated_at = ?
            """,
            [
                profile.username?.value, profile.usernameStatus.rawValue, profile.displayName,
                profile.presenceSharing, avatar, profile.updatedAt,
            ])
    }

    public func recordUsernameCheck(_ username: NormalizedUsername, owner: AccountId?, for account: AccountId) throws {
        guard let contact = try get(account) else { throw StorageError.notFound }
        try setProfile(contact.profile.recordingUsernameCheck(username, owner: owner, contact: account), for: account)
    }

    /// A username verified by our own lookup or a verified `sender_username`.
    public func confirmUsername(_ username: NormalizedUsername, for account: AccountId) throws {
        guard let contact = try get(account) else { throw StorageError.notFound }
        try setProfile(contact.profile.confirming(username), for: account)
    }

    public func setProfileSent(at date: Date?, to account: AccountId) throws {
        try tx.requireWritable()
        try update(account, "profile_sent_at = ?", [date?.storageMilliseconds])
    }

    // MARK: Creation and removal

    /// Creates a contact with an explicit state (e.g. from a backup).
    public func create(_ account: AccountId, state: ConversationRequestState, now: Date = Date()) throws {
        try tx.requireWritable()
        guard try requestState(of: account) == nil else { throw StorageError.invalidState("contact exists") }
        try insert(account, state: state, now: now)
    }

    /// Contacts flagged by a ghost wipe with `wipe_contact` (§8.2).
    public func pendingForget() throws -> [AccountId] {
        try tx.connection.query("SELECT account FROM contacts WHERE pending_forget = 1")
            .compactMap { $0.blob("account").flatMap(AccountId.init(bytes:)) }
    }

    /// Deletes the contact, its conversation (crypto-shredded first), pending
    /// sends to it, and its session and identity.
    public func forget(_ account: AccountId) throws {
        try tx.requireWritable()
        if let conversation = try tx.conversations.forContact(account) {
            try WipeService.wipe(conversation, reason: .requested, in: tx)
        }
        try tx.connection.run("DELETE FROM outbox WHERE recipient = ?", [account.bytes])
        try tx.connection.run("DELETE FROM contacts WHERE account = ?", [account.bytes])
        try tx.storage.protocolStore?.deleteProtocolState(of: account, tx.connection)
    }

    /// Removes everything about a blocked contact except the block: profile,
    /// access keys, pending sends, session and identity.
    func stripToBlock(_ account: AccountId) throws {
        try update(
            account,
            """
            username = NULL, username_status = 'unchecked', display_name = NULL, presence_sharing = 0,
            avatar = NULL, profile_updated_at = 0, their_access_key = NULL, our_access_key_sent = NULL,
            profile_sent_at = NULL, state_before_block = NULL
            """, [])
        try tx.connection.run("DELETE FROM outbox WHERE recipient = ?", [account.bytes])
        try tx.storage.protocolStore?.deleteProtocolState(of: account, tx.connection)
    }

    func markPendingForget(_ account: AccountId) throws {
        try update(account, "pending_forget = 1", [])
    }

    // MARK: Rows

    private func insert(_ account: AccountId, state: ConversationRequestState, now: Date) throws {
        try tx.connection.run(
            "INSERT INTO contacts (account, request_state, created_at) VALUES (?, ?, ?)",
            [account.bytes, state.rawValue, now.storageMilliseconds])
    }

    private func setState(_ state: ConversationRequestState, of account: AccountId) throws {
        try update(account, "request_state = ?", [state.rawValue])
    }

    private func update(_ account: AccountId, _ assignments: String, _ values: [any SQLBindable]) throws {
        try tx.connection.run("UPDATE contacts SET \(assignments) WHERE account = ?", values + [account.bytes])
        guard tx.connection.changes > 0 else { throw StorageError.notFound }
    }

    private func contact(from row: Row) throws -> Contact {
        guard let bytes = row.blob("account"), let account = AccountId(bytes: bytes),
            let state = row.text("request_state").flatMap(ConversationRequestState.init(rawValue:))
        else { throw StorageError.integrity }
        let username = row.text("username").flatMap { try? NormalizedUsername($0) }
        // Checked again on the way out: anything malformed is the initial letter.
        let avatar = ProfilePicture(serialized: row.blob("avatar"))
        let profile = ContactProfile(
            username: username,
            usernameStatus: row.text("username_status").flatMap(ContactProfile.UsernameStatus.init(rawValue:))
                ?? .unchecked,
            displayName: row.text("display_name"),
            presenceSharing: row.bool("presence_sharing"),
            avatar: avatar,
            updatedAt: row.uint64("profile_updated_at") ?? 0)
        let identity = try tx.storage.protocolStore?.identityTrust(for: account) ?? .unknown
        return Contact(
            accountId: account, requestState: state, profile: profile, theirAccessKey: row.blob("their_access_key"),
            ourAccessKeySent: row.blob("our_access_key_sent"), profileSentAt: row.date("profile_sent_at"),
            createdAt: row.date("created_at") ?? Date(timeIntervalSince1970: 0),
            pendingForget: row.bool("pending_forget"), identity: identity)
    }
}
