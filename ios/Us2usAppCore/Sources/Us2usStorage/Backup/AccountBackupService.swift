// us2us — AGPL-3.0-only

import Foundation
import LibSignalClient
import Us2usKit

/// Account backup export and import (PROTOCOL.md §5.5) on top of Us2usKit's
/// ``AccountBackupFile``, protected by the **backup password** — separate
/// from the six-digit secret code, at least 10 characters, checked with
/// ``BackupPassword``. Backups hold the identity and contacts, never messages.
///
/// Which contacts are exported: accepted and blocked ones. Pending message
/// requests are not relationships yet (§6.4) and are left out; on restore
/// every non-blocked contact comes back as accepted, with the access key it
/// gave us, its profile claim (unchecked), its `presence_sharing` and the
/// profile picture it last sent (§8.9; contacts do not send it again after a
/// restore until something changes). The own picture and "Show in search"
/// travel in the account part (`Messenger.makeBackup`).
public enum AccountBackupService {
    public enum Failure: Error, Equatable, Sendable {
        case invalidPassword(BackupPasswordError)
        case noAccount
    }

    /// The app's part of the backup: contacts as `BackupContact`s.
    /// `Messenger.makeBackup` fills in their identity fields from the store.
    public static func backupContacts(_ tx: StorageTransaction) throws -> [BRPBackupContact] {
        try tx.contacts.all().compactMap { contact -> BRPBackupContact? in
            guard contact.requestState == .accepted || contact.requestState == .blocked else { return nil }
            var entry = BRPBackupContact()
            entry.accountID = contact.accountId.bytes
            entry.username = contact.profile.username?.value ?? ""
            entry.displayName = contact.profile.displayName ?? ""
            entry.blocked = contact.isBlocked
            entry.theirAccessKey = contact.theirAccessKey ?? Data()
            entry.presenceSharing = contact.profile.presenceSharing
            if let avatar = contact.profile.avatar.profileAvatar { entry.avatar = avatar }
            return entry
        }
    }

    /// Builds and encrypts the backup. The password is validated (twice-typed
    /// `confirmation`) before the slow key derivation runs, off the caller's
    /// executor. Returns the file bytes for the share sheet / Files.
    public static func export(
        messenger: Messenger, storage: LocalStorage, displayName: String, password: String, confirmation: String,
        now: Date = Date()
    ) async throws -> Data {
        do {
            try BackupPassword.validate(password, confirmation: confirmation)
        } catch {
            throw Failure.invalidPassword(error)
        }
        let contacts = try storage.read { try backupContacts($0) }
        let backup = try await messenger.makeBackup(displayName: displayName, contacts: contacts, now: now)
        return try await Task.detached(priority: .userInitiated) {
            try AccountBackupFile.export(backup, password: password)
        }.value
    }

    /// Writes an exported backup to a staging file (complete protection, not
    /// backed up) to hand to `UIActivityViewController` / `fileExporter`.
    /// The name carries no account information.
    public static func writeToStaging(_ file: Data, storage: LocalStorage, now: Date = Date()) throws -> URL {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyyMMdd-HHmm"
        let directory = storage.makeStagingURL()
        try FileProtection.createDirectory(directory, protection: .complete)
        let url = directory.appendingPathComponent("us2us-backup-\(formatter.string(from: now)).brpb")
        try FileProtection.writeAtomically(file, to: url, protection: .completeFileProtection)
        return url
    }

    /// Decrypts a backup file and verifies the whole backup (≈1 s: PBKDF2
    /// with 10^6 iterations), so a backup that opens is one a restore
    /// accepts. Nothing is changed anywhere. Throws ``AccountBackupError``.
    public static func decrypt(_ file: Data, password: String) async throws -> BRPAccountBackup {
        try await Task.detached(priority: .userInitiated) {
            try AccountBackupFile.import(file, password: password)
        }.value
    }

    /// Restores an account on a new phone (§5.5): `Messenger.restore`
    /// validates the whole backup before anything is created, stores the
    /// identity in `storage` (which must be fresh: no identity yet) and the
    /// contacts, rotates the token (saved through the storage before the key
    /// upload) and uploads new keys. Afterwards the runtime announces our
    /// profile to every accepted contact (`Messenger.announceProfile`,
    /// pending setup `.announceProfile`).
    ///
    /// Crash safety: the contacts (blocks included) are written *before* the
    /// irreversible token rotation, which needs none of them. A process that
    /// dies at any point leaves either no account (the backup's token still
    /// works: restore again), or the account with every contact and both the
    /// backup's token and the new one, of which the next start keeps the one
    /// the relay holds (§5.5 step 3.4), or the rotated account.
    ///
    /// Throws `BackupRestoreError` when the relay refuses the backup's token
    /// and the new one, or the new one alone.
    public static func restore(
        _ backup: BRPAccountBackup, storage: LocalStorage, relay: RelayClient,
        directory: (any IdentityDirectory)? = nil, configuration: Messenger.Configuration = Messenger.Configuration(),
        now: Date = Date()
    ) async throws -> Messenger {
        let messenger = try await Messenger.restore(
            from: backup, relay: relay,
            makeStore: { identity, registrationId in
                // Called after the backup was fully validated, before any relay call.
                let store = try storage.createIdentity(identity, registrationId: registrationId)
                try storage.write { tx in try restoreContacts(backup, tx, now: now) }
                return store
            },
            directory: directory, configuration: configuration, saveAccount: storage.accountSaver, now: now)
        // The storage must still be open and hold the rotated account; a
        // closed one (the vault locked meanwhile) is not a finished restore.
        guard try storage.read({ try $0.account.load() }) != nil else { throw Failure.noAccount }
        return messenger
    }

    /// Creates the contacts of a validated backup (skipping ones that exist).
    /// Every contact that is not blocked also gets its (empty) conversation:
    /// the chat list is built from conversations, so without one a restored
    /// contact would exist but appear nowhere (messages are never in a
    /// backup, PROTOCOL §5.5).
    public static func restoreContacts(_ backup: BRPAccountBackup, _ tx: StorageTransaction, now: Date = Date())
        throws
    {
        for entry in backup.contacts {
            guard let account = AccountId(bytes: entry.accountID), try tx.contacts.get(account) == nil else {
                continue
            }
            try tx.contacts.create(account, state: entry.blocked ? .blocked : .accepted, now: now)
            let username = entry.username.isEmpty ? nil : try? NormalizedUsername(entry.username)
            try tx.contacts.setProfile(
                ContactProfile(
                    username: username, usernameStatus: .unchecked,
                    displayName: entry.displayName.isEmpty ? nil : entry.displayName,
                    presenceSharing: entry.presenceSharing,
                    avatar: ProfilePicture(entry.hasAvatar ? entry.avatar : nil)),
                for: account)
            if entry.theirAccessKey.count == AccessKey.byteCount {
                try tx.contacts.setTheirAccessKey(entry.theirAccessKey, for: account)
            }
            if !entry.blocked {
                // The restored account keeps its access key, which these contacts hold.
                try tx.contacts.setOurAccessKeySent(backup.accessKey, to: account)
                _ = try tx.conversations.ensure(for: account, now: now)
            }
        }
    }
}
