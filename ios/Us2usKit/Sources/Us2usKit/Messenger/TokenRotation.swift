import Foundation

/// Why restoring a backup stopped at the relay (§5.5).
public enum BackupRestoreError: Error, Equatable, Sendable {
    /// The relay refuses the backup's token: another phone restored the same
    /// backup and rotated the token first, or the account was deleted. The
    /// backup can never be restored again.
    case backupNoLongerValid
    /// The relay refused the new token (`400`). Nothing changed and the
    /// backup still works; a correct client never gets this (§4.3).
    case tokenRefused
}

/// Whether the account's token can be used after
/// ``Messenger/finishPendingTokenRotation()``.
public enum TokenRotationResult: Sendable, Equatable {
    /// No rotation was pending, or it has just been settled: the account's
    /// token is the one the relay holds, and the backup's token — the old
    /// phone's — no longer works.
    case ready
    /// `401` for the backup's token and for the new one: another phone
    /// restored the same backup and rotated first, or the account was
    /// deleted. The relay refuses this installation from now on; every later
    /// call in this process says so again.
    case backupNoLongerValid
}

/// How one rotation ended (§5.5 step 3).
enum TokenRotationOutcome: Sendable, Equatable {
    /// The relay holds this token: the new one (`204`), or the one an older
    /// relay chose itself.
    case committed(Data)
    /// `400` for the old token: nothing changed, the old token still works.
    case unchanged
    /// `401` for both tokens.
    case backupNoLongerValid
}

/// The client side of the token rotation of §4.3 and §5.5 step 3. The new
/// token `N` is chosen here and stored with the backup's token `T` before the
/// relay hears of it; the answers then decide which of the two the account
/// has. Whatever answer gets lost, one of them works.
enum TokenRotation {
    /// §4.3: the relay refuses a token with fewer different byte values.
    static let minimumDistinctBytes = 16

    /// A fresh token: 32 bytes from the system's CSPRNG. A draw with fewer
    /// than 16 different byte values (the relay's "plainly not random") is
    /// drawn again; for 32 random bytes that practically never happens.
    static func newToken() -> Data {
        var token = SecureRandom.bytes(32)
        while Set(token).count < minimumDistinctBytes {
            token = SecureRandom.bytes(32)
        }
        return token
    }

    /// Step 3.2: rotate `old` to `new`; without an answer, step 3.4 at once.
    ///
    /// - Throws: the relay error that left the outcome unknown (no answer,
    ///   `429`, `5xx`, an unexpected answer). Both tokens must then be kept
    ///   and the outcome found out later with ``resolve(old:new:relay:)``.
    static func rotate(from old: Data, to new: Data, relay: RelayClient) async throws -> TokenRotationOutcome {
        switch await ask(relay, authenticatingWith: old, newToken: new) {
        case .accepted(let token): return .committed(token)
        case .unauthorized: return try await askWithNewTokenOnceMore(relay, new)
        case .badRequest: return .unchanged
        case .unknown: return try await resolve(old: old, new: new, relay: relay)
        }
    }

    /// Step 3.4: an earlier rotation from `old` to `new` got no answer (or
    /// the app starts with a pending token). Asks with `new`, then with
    /// `old`, then with `new` once more; at most three `401`s.
    ///
    /// - Throws: as ``rotate(from:to:relay:)``, when the outcome is still unknown.
    static func resolve(old: Data, new: Data, relay: RelayClient) async throws -> TokenRotationOutcome {
        switch await ask(relay, authenticatingWith: new, newToken: new) {
        case .accepted(let token): return .committed(token)
        case .unauthorized: break
        case .badRequest(let error), .unknown(let error): throw error
        }
        switch await ask(relay, authenticatingWith: old, newToken: new) {
        case .accepted(let token): return .committed(token)
        case .unauthorized: return try await askWithNewTokenOnceMore(relay, new)
        case .badRequest: return .unchanged
        case .unknown(let error): throw error
        }
    }

    /// `401` for the old token: an earlier attempt may have completed in
    /// between, so `new` is asked once more before the backup counts as lost.
    private static func askWithNewTokenOnceMore(_ relay: RelayClient, _ new: Data) async throws
        -> TokenRotationOutcome
    {
        switch await ask(relay, authenticatingWith: new, newToken: new) {
        case .accepted(let token): return .committed(token)
        case .unauthorized: return .backupNoLongerValid
        case .badRequest(let error), .unknown(let error): throw error
        }
    }

    private enum Answer {
        case accepted(Data)
        case unauthorized
        /// `400`: nothing changed.
        case badRequest(any Error)
        /// No answer, `429`, `5xx` or anything unexpected.
        case unknown(any Error)
    }

    private static func ask(_ relay: RelayClient, authenticatingWith token: Data, newToken: Data) async -> Answer {
        do {
            return .accepted(try await relay.rotateAuthToken(to: newToken, authenticatingWith: token))
        } catch let error as RelayError {
            switch error {
            case .server(status: 401, _, _): return .unauthorized
            case .server(status: 400, _, _): return .badRequest(error)
            default: return .unknown(error)
            }
        } catch {
            return .unknown(error)
        }
    }
}

extension Messenger {
    /// Settles a token rotation whose answer never arrived (§5.5 step 3.4):
    /// after a restore that lost its connection, or at the next start. Asks
    /// the relay which of the two stored tokens the account has and keeps
    /// that one, in one save. Until this succeeds nothing else uses either
    /// token: authenticated calls fail with ``RelayError/notAuthenticated``
    /// without being sent. The runtime calls it before it connects; key
    /// maintenance and account deletion call it themselves. Concurrent
    /// callers share one attempt.
    ///
    /// - Returns: ``TokenRotationResult/ready`` also when nothing was pending
    ///   (and none was refused in this process).
    /// - Throws: the relay error when the outcome is still unknown (no answer,
    ///   `429`, `5xx`); both tokens stay stored, ask again later.
    ///   ``BackupRestoreError/tokenRefused`` when the relay refused the new
    ///   token (`400`, §5.5 step 3.5): nothing changed, so the backup's token
    ///   — which the old phone still holds — is the account's; a fresh new
    ///   token is pending in its place and the next call rotates to it.
    @discardableResult
    public func finishPendingTokenRotation() async throws -> TokenRotationResult {
        switch try await settleTokenRotation(firstAttempt: false) {
        case nil: return tokenRotationRefused ? .backupNoLongerValid : .ready
        case .committed?: return .ready
        case .unchanged?: throw BackupRestoreError.tokenRefused
        case .backupNoLongerValid?: return .backupNoLongerValid
        }
    }

    /// Runs the rotation to ``LocalAccount/pendingAuthToken`` (step 3.2 for
    /// the first attempt of a restore, otherwise step 3.4) and saves its
    /// outcome; `nil` when nothing is pending.
    func settleTokenRotation(firstAttempt: Bool) async throws -> TokenRotationOutcome? {
        guard let pending = account.pendingAuthToken else { return nil }
        let attempt: Task<TokenRotationOutcome, any Error>
        if let running = tokenRotation {
            attempt = running
        } else {
            let relay = self.relay
            let old = account.authToken
            attempt = Task {
                if firstAttempt {
                    return try await TokenRotation.rotate(from: old, to: pending, relay: relay)
                }
                return try await TokenRotation.resolve(old: old, new: pending, relay: relay)
            }
            tokenRotation = attempt
        }
        let outcome: TokenRotationOutcome
        do {
            outcome = try await attempt.value
        } catch {
            if tokenRotation == attempt { tokenRotation = nil }
            throw error
        }
        if tokenRotation == attempt { tokenRotation = nil }
        // A caller that shared the attempt may have saved it already.
        guard account.pendingAuthToken == pending else { return outcome }
        var settled = account
        settled.pendingAuthToken = nil
        switch outcome {
        case .committed(let token):
            // Step 3.3: in one save the relay's token becomes the account's
            // and the other one is forgotten.
            settled.authToken = token
        case .unchanged where !firstAttempt:
            // 400 while settling (step 3.5): nothing changed, so the old
            // token — the old phone's too — is still the account's. The
            // outcome is known, so the pending token may be replaced: a
            // fresh one, settled like an unanswered rotation next time.
            settled.pendingAuthToken = TokenRotation.newToken()
        case .unchanged:
            // A restore's first attempt: the old token stays and works; the
            // restore fails (``BackupRestoreError/tokenRefused``).
            break
        case .backupNoLongerValid:
            // Nothing works (401 for both): the old token stays and every
            // later call is refused like any revoked token.
            break
        }
        try commitAccount(settled)
        if outcome == .backupNoLongerValid { tokenRotationRefused = true }
        return outcome
    }

    /// The read-only relay check before a restore replaces data on this
    /// phone (a forgotten code): verifies the whole backup (§5.5 step 1),
    /// then makes one authenticated `GET /v1/keys/status` with the backup's
    /// token on `relay`, whose credentials are cleared again afterwards.
    /// Nothing changes except what any authenticated request does: the relay
    /// notes the account's last-seen day (§4.10); a refused token counts once
    /// as a failed token check (§4.11).
    ///
    /// - Throws: ``MessengerError/invalidBackup``;
    ///   ``BackupRestoreError/backupNoLongerValid`` when the relay refuses the
    ///   token (`401`, or `404`); other relay errors (no answer, `429`, `5xx`)
    ///   mean that nothing could be checked.
    public static func checkBackup(_ backup: BRPAccountBackup, relay: RelayClient) async throws {
        let validated = try validate(backup)
        relay.setCredentials(RelayCredentials(accountId: validated.accountId, authToken: backup.authToken))
        defer { relay.setCredentials(nil) }
        do {
            _ = try await relay.keyStatus()
        } catch let error as RelayError {
            switch error {
            case .server(status: 401, _, _), .server(status: 404, _, _): throw BackupRestoreError.backupNoLongerValid
            default: throw error
            }
        }
    }
}
