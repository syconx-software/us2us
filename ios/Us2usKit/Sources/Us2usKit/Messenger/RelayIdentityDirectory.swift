import Foundation
import LibSignalClient

/// ``IdentityDirectory`` backed by the relay, with caches: identity keys are
/// cached until a mismatch forces a refresh (§6.3 "cache it"), username
/// lookups for a limited time because usernames can move (§3.3) and the
/// directory is rate limited (§4.4).
public actor RelayIdentityDirectory: IdentityDirectory {
    private let relay: RelayClient
    private let usernameTTL: TimeInterval
    private var identities: [AccountId: IdentityKey] = [:]
    private var usernames: [NormalizedUsername: (account: AccountId?, fetchedAt: Date)] = [:]

    public init(relay: RelayClient, usernameTTL: TimeInterval = 3600) {
        self.relay = relay
        self.usernameTTL = usernameTTL
    }

    public func identityKey(of account: AccountId, refresh: Bool) async throws -> IdentityKey? {
        if !refresh, let cached = identities[account] { return cached }
        guard let response = try await relay.identity(of: account) else {
            identities[account] = nil
            return nil
        }
        guard response.accountId == account, response.identityKey.count == 33,
            let key = try? IdentityKey(bytes: response.identityKey)
        else { throw RelayError.invalidResponse }
        identities[account] = key
        return key
    }

    public func account(for username: NormalizedUsername, refresh: Bool) async throws -> AccountId? {
        if !refresh, let cached = usernames[username], Date().timeIntervalSince(cached.fetchedAt) < usernameTTL {
            return cached.account
        }
        let hash = await Task.detached(priority: .userInitiated) { username.hash() }.value
        let account = try await relay.lookup(usernameHash: hash)?.accountId
        usernames[username] = (account, Date())
        return account
    }
}
