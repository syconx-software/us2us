import Foundation
import Us2usKit
import Us2usStorage

/// What the app's screens need beyond the actions: an exact-match directory
/// lookup that creates nothing (4.2 search, adding a contact by QR) and the
/// own account's id and identity key (the contact code's fingerprint).
/// Additive; each needs an unlocked vault.
extension AppRuntime {
    /// A directory hit (§4.4): the account and the identity key the relay serves.
    public struct DirectoryEntry: Sendable, Equatable {
        public let account: AccountId
        public let identityKey: Data
    }

    /// Exact-match lookup of `username` (§4.4); `nil` when nobody has it.
    /// Unlike ``startConversation(username:)`` it creates no conversation.
    public func lookupAccount(username rawUsername: String) async throws -> DirectoryEntry? {
        let username: NormalizedUsername
        do {
            username = try NormalizedUsername(rawUsername)
        } catch {
            throw RuntimeError.invalidUsername(error)
        }
        guard lockState == .unlocked, let session, let backend = session.backend as? MessengerBackend else {
            throw RuntimeError.locked
        }
        guard let found = try await backend.messenger.findAccount(username: username) else { return nil }
        return DirectoryEntry(account: found.accountId, identityKey: found.identityKey)
    }

    /// The own account id while unlocked (to tell own reactions from the contact's).
    public var ownAccountId: AccountId? {
        guard lockState == .unlocked else { return nil }
        return session?.backend.accountId
    }

    /// The own identity key as the relay serves it to others (what a scanned
    /// contact code is compared with on the other phone).
    public func ownIdentityKey() async throws -> Data? {
        guard lockState == .unlocked, let session, let backend = session.backend as? MessengerBackend else {
            throw RuntimeError.locked
        }
        return try await backend.messenger.relay.identity(of: backend.accountId)?.identityKey
    }

    /// Checks the current code while unlocked (7.1 code change, first step).
    /// It is a counted attempt with the lock's throttle (§5.2), so this screen
    /// is never a way around the delays; the derived key is wiped at once.
    /// If the optional "wipe after 10" fires, everything is erased.
    public func verifyCode(_ code: String) async throws(UnlockError) {
        guard lockState == .unlocked else { throw .busy }
        do {
            let key = try await vault.unlock(code: code)
            key.wipe()
            await refreshLockScreen()
        } catch {
            if case .vault(.wiped) = error { await eraseEverything() }
            await refreshLockScreen()
            throw error
        }
    }
}
