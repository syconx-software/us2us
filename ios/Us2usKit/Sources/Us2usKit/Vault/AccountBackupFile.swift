import Foundation
import SwiftProtobuf

public enum AccountBackupError: Error, Equatable, Sendable {
    /// The backup password does not open the file (a GCM failure).
    case wrongPassword
    case invalidFile
}

/// The account backup file the user exports and keeps wherever they like; it
/// never touches the relay (PROTOCOL.md §5.5). It is encrypted with the
/// separate backup password, never with the secret code:
///
///     backupKey = PBKDF2-HMAC-SHA256(UTF-8(NFKC(password)), salt(16), iterations = 1000000, 32)
///     file      = header ‖ nonce(12) ‖ AES-256-GCM(backupKey, nonce, aad = header, AccountBackup) ‖ tag(16)
///     header    = "BRPB" ‖ 0x01 ‖ salt(16) ‖ uint32_be(iterations)
///
/// It holds the identity, not messages, so restoring keeps the safety numbers.
public enum AccountBackupFile {
    public static let defaultIterations = 1_000_000
    static let header = FileHeader(magic: "BRPB")
    public static let currentVersion: UInt32 = 1

    /// Encrypts `backup` under a newly chosen backup password, which must pass
    /// ``BackupPassword/validate(_:)`` (the UI asks for it twice with
    /// ``BackupPassword/validate(_:confirmation:)`` first). Takes about a
    /// second on a phone; call it off the main thread.
    ///
    /// - Throws: ``BackupPasswordError`` for a password the rules refuse.
    public static func export(_ backup: BRPAccountBackup, password: String) throws -> Data {
        try BackupPassword.validate(password)
        return try export(
            backup, password: password, salt: SecureRandom.bytes(FileHeader.saltLength),
            iterations: defaultIterations, nonce: SecureRandom.bytes(AES256GCM.nonceLength))
    }

    static func export(
        _ backup: BRPAccountBackup, password: String, salt: Data, iterations: Int, nonce: Data
    ) throws -> Data {
        try encrypt(
            try backup.serializedBytes(), password: password, salt: salt, iterations: iterations, nonce: nonce)
    }

    /// Encrypts an already serialized `AccountBackup`.
    static func encrypt(_ plaintext: Data, password: String, salt: Data, iterations: Int, nonce: Data) throws -> Data
    {
        let header = Self.header.encode(salt: salt, iterations: iterations)
        let key = PBKDF2.sha256(
            password: BackupPassword.keyMaterial(password), salt: salt, iterations: iterations)
        return header + nonce + (try AES256GCM.seal(plaintext, key: key, nonce: nonce, associatedData: header))
    }

    /// Decrypts a backup file with its password and verifies the **whole**
    /// backup — version, every field's size and format (§5.5 step 1), the
    /// same check ``Messenger/restore(from:relay:makeStore:directory:configuration:saveAccount:now:)``
    /// repeats — so a file that opens here is one a restore accepts. The
    /// password rules are not checked here: a backup always opens with the
    /// password it was made with.
    ///
    /// - Throws: ``AccountBackupError/wrongPassword``, or
    ///   ``AccountBackupError/invalidFile`` for a damaged or malformed backup.
    public static func `import`(_ file: Data, password: String) throws(AccountBackupError) -> BRPAccountBackup {
        let plaintext = try decrypt(file, password: password)
        guard let backup = try? BRPAccountBackup(serializedBytes: plaintext), (try? Messenger.validate(backup)) != nil
        else { throw .invalidFile }
        return backup
    }

    /// Returns the serialized `AccountBackup`.
    static func decrypt(_ file: Data, password: String) throws(AccountBackupError) -> Data {
        guard let parsed = header.decode(file) else { throw .invalidFile }
        let key = PBKDF2.sha256(
            password: BackupPassword.keyMaterial(password), salt: parsed.salt, iterations: parsed.iterations)
        do {
            return try AES256GCM.open(parsed.ciphertext, key: key, nonce: parsed.nonce, associatedData: parsed.header)
        } catch {
            throw .wrongPassword
        }
    }
}

extension AccountBackupError: LocalizedError {
    public var errorDescription: String? {
        switch self {
        case .wrongPassword: return Localizer.current.string(.backupWrongPassword)
        case .invalidFile: return Localizer.current.string(.backupInvalidFile)
        }
    }
}
