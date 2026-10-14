import Foundation
import Us2usStorage

/// Where the app keeps its files, all under one directory that is excluded
/// from iCloud/iTunes backups and uses Data Protection "complete" (readable
/// only while the device is unlocked) as the default for new files.
///
/// Layout (names carry no user data):
///
///     <root>/vault/     vault file, attempt counter, quick-unlock record (§5.2)
///     <root>/storage/   Us2usStorage: SQLCipher database, key slots, ghost
///                       schedule, encrypted media (§5.3); its own protection
///                       classes (the schedule stays readable after first
///                       unlock so a background task can crypto-shred)
///     <root>/runtime/   the runtime's encrypted state and notice items
///     <root>/staging/   prepared media before it is encrypted, exported
///                       backups; emptied on every lock and at every launch
public struct AppDirectories: Sendable, Equatable {
    public let root: URL

    public init(root: URL) {
        self.root = root
    }

    /// `Library/Application Support/us2us` of the app sandbox.
    public static func standard() throws -> AppDirectories {
        let support = try FileManager.default.url(
            for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        return AppDirectories(root: support.appendingPathComponent("us2us", isDirectory: true))
    }

    public var vault: URL { root.appendingPathComponent("vault", isDirectory: true) }
    public var runtime: URL { root.appendingPathComponent("runtime", isDirectory: true) }
    public var staging: URL { root.appendingPathComponent("staging", isDirectory: true) }
    public var storage: StorageLayout {
        StorageLayout(root: root.appendingPathComponent("storage", isDirectory: true))
    }

    /// Creates the directories with complete protection, excluded from backups.
    public func prepare() throws {
        let fileManager = FileManager.default
        for directory in [root, vault, runtime, staging] {
            try fileManager.createDirectory(
                at: directory, withIntermediateDirectories: true,
                attributes: [.protectionKey: FileProtectionType.complete])
            try ProtectedFiles.protect(directory)
        }
        try ProtectedFiles.excludeFromBackup(root)
    }

    /// Deletes everything in the staging directory (plaintext never outlives a lock).
    public func purgeStaging() {
        let fileManager = FileManager.default
        guard let names = try? fileManager.contentsOfDirectory(atPath: staging.path) else { return }
        for name in names {
            try? fileManager.removeItem(at: staging.appendingPathComponent(name))
        }
    }

    /// A fresh staging file URL (random name, nothing derived from content).
    public func stagingFile(extension pathExtension: String = "bin") -> URL {
        staging.appendingPathComponent(UUID().uuidString).appendingPathExtension(pathExtension)
    }

    /// Removes every file of the app. The keys are destroyed separately
    /// (vault + device secret + biometric key) *before* this runs, so the
    /// ciphertext left on flash during deletion is already unreadable.
    public func eraseAll() throws {
        let fileManager = FileManager.default
        guard fileManager.fileExists(atPath: root.path) else { return }
        try fileManager.removeItem(at: root)
    }
}

/// File attribute helpers for §5.3 and the "complete protection,
/// no backups" rule.
public enum ProtectedFiles {
    /// Sets Data Protection "complete" on an existing file or directory.
    public static func protect(_ url: URL) throws {
        try FileManager.default.setAttributes([.protectionKey: FileProtectionType.complete], ofItemAtPath: url.path)
    }

    /// Excludes `url` (and, for a directory, everything inside) from device backups.
    public static func excludeFromBackup(_ url: URL) throws {
        var url = url
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try url.setResourceValues(values)
    }

    /// Writes `data` atomically with complete protection.
    public static func write(_ data: Data, to url: URL) throws {
        try FileManager.default.createDirectory(
            at: url.deletingLastPathComponent(), withIntermediateDirectories: true,
            attributes: [.protectionKey: FileProtectionType.complete])
        try data.write(to: url, options: [.atomic, .completeFileProtection])
    }

    /// The protection class of an existing file, for tests and diagnostics.
    public static func protection(of url: URL) -> FileProtectionType? {
        (try? FileManager.default.attributesOfItem(atPath: url.path)[.protectionKey]) as? FileProtectionType
    }

    public static func isExcludedFromBackup(_ url: URL) -> Bool {
        (try? url.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup) ?? false
    }
}
