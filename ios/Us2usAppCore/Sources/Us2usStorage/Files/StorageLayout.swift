// us2us — AGPL-3.0-only

import Foundation

/// Where local storage lives inside the app container (PROTOCOL.md §5.3).
///
///     <root>/                   excluded from iCloud/iTunes backup (the whole tree)
///       db/main.sqlite          SQLCipher database (+ -wal, -shm)   completeUnlessOpen
///       slots/<hex>.slot        key slots (BRPS)                    complete
///       slots/schedule.json     ghost schedule {slotId, deadline}   completeUntilFirstUserAuthentication
///       files/<hex>.item        attachment and thumbnail items      completeUnlessOpen
///       staging/                plaintext only while being encrypted or decrypted
///
/// Key slots use complete protection as the spec asks: they are only read
/// while the app is unlocked, and keys are cached in memory after that. The
/// database and item files use `completeUnlessOpen`: files already open stay
/// usable if the phone locks during the 30-second background grace period
/// (§5.4), new ones can still be written then, and nothing can be opened
/// while the phone is locked. The schedule must stay readable after first
/// unlock so a background task can crypto-shred overdue conversations
/// without the DEK.
public struct StorageLayout: Sendable, Equatable {
    public let root: URL

    public init(root: URL) {
        self.root = root
    }

    /// `Application Support/us2us-storage` of the app container. The App
    /// Group container is reserved for future extensions (ARCHITECTURE.md).
    public static func standard() throws -> StorageLayout {
        do {
            let support = try FileManager.default.url(
                for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
            return StorageLayout(root: support.appendingPathComponent("us2us-storage", isDirectory: true))
        } catch {
            throw StorageError.io("application support unavailable")
        }
    }

    public var databaseDirectory: URL { root.appendingPathComponent("db", isDirectory: true) }
    public var databaseURL: URL { databaseDirectory.appendingPathComponent("main.sqlite") }
    public var slotDirectory: URL { root.appendingPathComponent("slots", isDirectory: true) }
    public var scheduleURL: URL { slotDirectory.appendingPathComponent("schedule.json") }
    public var filesDirectory: URL { root.appendingPathComponent("files", isDirectory: true) }
    public var stagingDirectory: URL { root.appendingPathComponent("staging", isDirectory: true) }

    /// The database's companion files, which hold encrypted pages too.
    public var databaseFiles: [URL] {
        ["", "-wal", "-shm"].map { URL(fileURLWithPath: databaseURL.path + $0) }
    }

    /// Creates the directories with their protection classes and excludes the
    /// tree from backups. Idempotent; called on every open.
    func prepare() throws(StorageError) {
        try FileProtection.createDirectory(root, protection: .completeUnlessOpen)
        try FileProtection.excludeFromBackup(root)
        try FileProtection.createDirectory(databaseDirectory, protection: .completeUnlessOpen)
        try FileProtection.createDirectory(slotDirectory, protection: .complete)
        try FileProtection.createDirectory(filesDirectory, protection: .completeUnlessOpen)
        try FileProtection.createDirectory(stagingDirectory, protection: .complete)
        // Belt and braces: exclusion is inherited, but a directory recreated
        // by someone else must not slip into a backup.
        for directory in [databaseDirectory, slotDirectory, filesDirectory, stagingDirectory] {
            try FileProtection.excludeFromBackup(directory)
        }
    }
}

enum FileProtection {
    static func createDirectory(_ url: URL, protection: FileProtectionType) throws(StorageError) {
        do {
            try FileManager.default.createDirectory(
                at: url, withIntermediateDirectories: true, attributes: [.protectionKey: protection])
            // An existing directory keeps its old class unless set again;
            // files created inside inherit it.
            try FileManager.default.setAttributes([.protectionKey: protection], ofItemAtPath: url.path)
        } catch {
            throw .io("cannot prepare \(url.lastPathComponent)")
        }
    }

    static func excludeFromBackup(_ url: URL) throws(StorageError) {
        var url = url
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        do {
            try url.setResourceValues(values)
        } catch {
            throw .io("cannot exclude \(url.lastPathComponent) from backup")
        }
    }

    static func setProtection(_ protection: FileProtectionType, of url: URL) {
        guard FileManager.default.fileExists(atPath: url.path) else { return }
        try? FileManager.default.setAttributes([.protectionKey: protection], ofItemAtPath: url.path)
    }

    /// Writes `data` crash-safely: a temporary file in the same directory,
    /// then an atomic rename. A crash leaves either the old or the new file.
    static func writeAtomically(_ data: Data, to url: URL, protection: Data.WritingOptions) throws(StorageError) {
        do {
            try data.write(to: url, options: [.atomic, protection])
        } catch {
            throw .io("write \(url.lastPathComponent)")
        }
    }

    static func removeIfPresent(_ url: URL) throws(StorageError) {
        do {
            try FileManager.default.removeItem(at: url)
        } catch CocoaError.fileNoSuchFile {
            return
        } catch let error as NSError where error.domain == NSPOSIXErrorDomain && error.code == Int(ENOENT) {
            return
        } catch {
            if FileManager.default.fileExists(atPath: url.path) {
                throw .io("remove \(url.lastPathComponent)")
            }
        }
    }
}
