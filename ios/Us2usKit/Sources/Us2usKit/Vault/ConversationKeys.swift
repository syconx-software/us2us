import Foundation

public enum LocalStorageError: Error, Equatable, Sendable {
    /// GCM authentication failed: wrong key, wrong id, or tampered data.
    case integrityCheckFailed
    case malformed
    case io(String)
}

/// Keys derived from the DEK (PROTOCOL.md §5.3).
public enum LocalKeyDerivation {
    /// `dbKey = HKDF-SHA256(DEK, salt = empty, info = "BRP/db/v1", 32)`, the SQLCipher key.
    public static func databaseKey(dek: Data) -> Data {
        HKDFSHA256.derive(inputKeyMaterial: dek, salt: Data(), info: Data("BRP/db/v1".utf8))
    }

    /// `slotKey = HKDF-SHA256(DEK, salt = empty, info = "BRP/slot/v1", 32)`.
    static func slotKey(dek: Data) -> Data {
        HKDFSHA256.derive(inputKeyMaterial: dek, salt: Data(), info: Data("BRP/slot/v1".utf8))
    }
}

/// A conversation key `CK` wrapped in a key slot (PROTOCOL.md §5.3):
///
///     slotFile = "BRPS" ‖ 0x01 ‖ nonce(12) ‖ AES-256-GCM(slotKey, nonce, aad = "BRPS" ‖ 0x01 ‖ slotId(16), CK) ‖ tag(16)
///
/// Deleting the slot file destroys the conversation instantly, without the DEK.
public enum KeySlot {
    public static let idLength = 16
    public static let keyLength = 32
    static let prefix = Data("BRPS".utf8) + [0x01]

    public static func seal(conversationKey: Data, slotId: Data, dek: Data) throws -> Data {
        try seal(
            conversationKey: conversationKey, slotId: slotId, dek: dek,
            nonce: SecureRandom.bytes(AES256GCM.nonceLength))
    }

    static func seal(conversationKey: Data, slotId: Data, dek: Data, nonce: Data) throws(LocalStorageError) -> Data {
        guard conversationKey.count == keyLength, slotId.count == idLength else { throw .malformed }
        do {
            let sealed = try AES256GCM.seal(
                conversationKey, key: LocalKeyDerivation.slotKey(dek: dek), nonce: nonce,
                associatedData: prefix + slotId)
            return prefix + nonce + sealed
        } catch {
            throw .malformed
        }
    }

    /// Returns `CK`.
    public static func open(_ slotFile: Data, slotId: Data, dek: Data) throws(LocalStorageError) -> Data {
        let slotFile = Data(slotFile)
        let nonceEnd = prefix.count + AES256GCM.nonceLength
        guard slotId.count == idLength, slotFile.count == nonceEnd + keyLength + AES256GCM.tagLength,
            slotFile.prefix(prefix.count) == prefix
        else { throw .malformed }
        do {
            return try AES256GCM.open(
                slotFile[nonceEnd...], key: LocalKeyDerivation.slotKey(dek: dek),
                nonce: slotFile[prefix.count..<nonceEnd], associatedData: prefix + slotId)
        } catch {
            throw .integrityCheckFailed
        }
    }
}

/// Message bodies, media files and thumbnails encrypted under a conversation
/// key (PROTOCOL.md §5.3):
///
///     item = 0x01 ‖ nonce(12) ‖ AES-256-GCM(CK, nonce, aad = 0x01 ‖ itemId(16), plaintext) ‖ tag(16)
///
/// Binding `itemId` (the message id, or a random id for files) prevents
/// swapping items between rows.
public enum ItemCipher {
    static let version: UInt8 = 0x01

    public static func seal(_ plaintext: Data, itemId: Data, conversationKey: Data) throws(LocalStorageError) -> Data {
        try seal(
            plaintext, itemId: itemId, conversationKey: conversationKey,
            nonce: SecureRandom.bytes(AES256GCM.nonceLength))
    }

    static func seal(
        _ plaintext: Data, itemId: Data, conversationKey: Data, nonce: Data
    ) throws(LocalStorageError) -> Data {
        guard itemId.count == RandomId.byteCount, conversationKey.count == KeySlot.keyLength else {
            throw .malformed
        }
        do {
            let sealed = try AES256GCM.seal(
                plaintext, key: conversationKey, nonce: nonce, associatedData: Data([version]) + itemId)
            return Data([version]) + nonce + sealed
        } catch {
            throw .malformed
        }
    }

    public static func open(_ item: Data, itemId: Data, conversationKey: Data) throws(LocalStorageError) -> Data {
        let item = Data(item)
        let nonceEnd = 1 + AES256GCM.nonceLength
        guard itemId.count == RandomId.byteCount, item.count >= nonceEnd + AES256GCM.tagLength,
            item[0] == version
        else { throw .malformed }
        do {
            return try AES256GCM.open(
                item[nonceEnd...], key: conversationKey, nonce: item[1..<nonceEnd],
                associatedData: Data([version]) + itemId)
        } catch {
            throw .integrityCheckFailed
        }
    }
}

/// The directory of key-slot files plus the ghost-mode schedule (PROTOCOL.md
/// §5.3, §8.2). Destroying slots needs no DEK, so overdue wipes run even while
/// the app is locked (from a `BGTaskScheduler` task or at the next launch).
public final class KeySlotStore: Sendable {
    private struct ScheduleEntry: Codable {
        /// Hex slot id.
        var slotId: String
        /// Milliseconds since the Unix epoch.
        var deadline: UInt64
    }

    private let directory: URL
    private let lock = Locked(())

    public init(directory: URL) {
        self.directory = directory
    }

    private var scheduleURL: URL { directory.appendingPathComponent("schedule.json") }

    private func url(for slotId: Data) -> URL {
        directory.appendingPathComponent(slotId.hexString + ".slot")
    }

    /// Creates a slot holding a fresh random conversation key.
    public func createSlot(dek: Data) throws -> (slotId: Data, conversationKey: Data) {
        let slotId = SecureRandom.bytes(KeySlot.idLength)
        let conversationKey = SecureRandom.bytes(KeySlot.keyLength)
        let file = try KeySlot.seal(conversationKey: conversationKey, slotId: slotId, dek: dek)
        try lock.withLock { _ in
            try write(file, to: url(for: slotId), options: .completeFileProtection)
        }
        return (slotId, conversationKey)
    }

    public func conversationKey(slotId: Data, dek: Data) throws -> Data {
        let file: Data
        do {
            file = try Data(contentsOf: url(for: slotId))
        } catch {
            throw LocalStorageError.io("slot missing")
        }
        return try KeySlot.open(file, slotId: slotId, dek: dek)
    }

    /// Crypto-shreds one conversation and drops its schedule entry.
    public func destroySlot(_ slotId: Data) throws {
        try lock.withLock { _ in
            try removeIfPresent(url(for: slotId))
            var schedule = try loadSchedule()
            schedule.removeAll { $0.slotId == slotId.hexString }
            try saveSchedule(schedule)
        }
    }

    public func slotIds() throws -> [Data] {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? []
        return names.compactMap { name in
            guard name.hasSuffix(".slot") else { return nil }
            return Data(hexString: String(name.dropLast(5)))
        }
    }

    /// Sets (or clears) the ghost-mode deadline of a conversation. The
    /// schedule holds only `{slotId, deadline}`, no names or content.
    public func setDeadline(_ deadline: Date?, for slotId: Data) throws {
        try lock.withLock { _ in
            var schedule = try loadSchedule()
            schedule.removeAll { $0.slotId == slotId.hexString }
            if let deadline {
                schedule.append(ScheduleEntry(slotId: slotId.hexString, deadline: deadline.millisecondsSince1970))
            }
            try saveSchedule(schedule)
        }
    }

    public func deadlines() throws -> [Data: Date] {
        try lock.withLock { _ in
            var result: [Data: Date] = [:]
            for entry in try loadSchedule() {
                guard let slotId = Data(hexString: entry.slotId) else { continue }
                result[slotId] = Date(timeIntervalSince1970: TimeInterval(entry.deadline) / 1000)
            }
            return result
        }
    }

    /// Destroys every slot whose deadline has passed and returns their ids;
    /// the leftover ciphertext is garbage-collected at the next unlock.
    @discardableResult
    public func destroyExpiredSlots(now: Date = Date()) throws -> [Data] {
        try lock.withLock { _ in
            let schedule = try loadSchedule()
            let nowMillis = now.millisecondsSince1970
            let expired = schedule.filter { $0.deadline <= nowMillis }
            for entry in expired {
                if let slotId = Data(hexString: entry.slotId) { try removeIfPresent(url(for: slotId)) }
            }
            try saveSchedule(schedule.filter { $0.deadline > nowMillis })
            return expired.compactMap { Data(hexString: $0.slotId) }
        }
    }

    private func loadSchedule() throws -> [ScheduleEntry] {
        guard FileManager.default.fileExists(atPath: scheduleURL.path) else { return [] }
        do {
            return try JSONDecoder().decode([ScheduleEntry].self, from: Data(contentsOf: scheduleURL))
        } catch {
            throw LocalStorageError.io("schedule unreadable")
        }
    }

    private func saveSchedule(_ schedule: [ScheduleEntry]) throws {
        let data = try JSONEncoder().encode(schedule.sorted { $0.deadline < $1.deadline })
        // Readable after first unlock, so a background task can run wipes.
        try write(data, to: scheduleURL, options: .completeFileProtectionUntilFirstUserAuthentication)
    }

    private func write(_ data: Data, to url: URL, options: Data.WritingOptions) throws {
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            try data.write(to: url, options: [.atomic, options])
        } catch {
            throw LocalStorageError.io(error.localizedDescription)
        }
    }

    private func removeIfPresent(_ url: URL) throws {
        guard FileManager.default.fileExists(atPath: url.path) else { return }
        do {
            try FileManager.default.removeItem(at: url)
        } catch {
            throw LocalStorageError.io(error.localizedDescription)
        }
    }
}

extension LocalStorageError: LocalizedError {
    public var errorDescription: String? {
        Localizer.current.string(.storageIntegrity)
    }
}
