import CryptoKit
import Foundation
import Us2usKit
import Us2usStorage

/// What the runtime keeps besides Us2usStorage's database: the user's
/// settings and bookkeeping that carries no message content. One file,
/// encrypted (AES-256-GCM, Us2usKit `ItemCipher`) under a key derived from
/// the DEK, so it is exactly as protected as the database.
struct RuntimeStateData: Codable, Equatable {
    var settings = UserSettings()
    /// A block needs a new access key on the relay (§6.4); retried until done.
    var pendingAccessKeyRotation = false
    /// Conversation id (hex) → notice key-slot id (hex), see ``NoticeStore``.
    var noticeSlots: [String: String] = [:]
    /// When an exported account backup was last saved (7.1 “Yedek · Son: …”);
    /// only the time, nothing about the file or where it went. `nil` before
    /// the first one, also after a restore (its token rotation, §5.5, makes
    /// the restored file unusable). Optional, so state files written by
    /// earlier builds, which lack it, still load with all their settings.
    var lastBackupAt: Date?
    /// The built-in stickers last sent (the picker's recents), newest first:
    /// short opaque ids the app chose (“101.tr”), at most
    /// ``MessagingEngine/maxRecentStickers``. Which stickers only — never
    /// to whom or when — and here, encrypted, rather than in UserDefaults.
    var recentStickers: [String] = []

    private enum CodingKeys: String, CodingKey {
        case settings, pendingAccessKeyRotation, noticeSlots, lastBackupAt, recentStickers
    }
}

// Tolerant decoding: a field that is missing or unreadable falls back to its
// default, so a state file written by another build (one that lacks a field
// added later, or wrote one differently) still loads with everything else it
// holds, instead of failing as a whole — `RuntimeStateFile.load()` would then
// silently reset every setting, the display name and the notice slots. New
// fields need nothing more than a default here.
extension RuntimeStateData {
    init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let defaults = RuntimeStateData()
        settings = (try? container.decodeIfPresent(UserSettings.self, forKey: .settings)) ?? defaults.settings
        pendingAccessKeyRotation =
            (try? container.decodeIfPresent(Bool.self, forKey: .pendingAccessKeyRotation))
            ?? defaults.pendingAccessKeyRotation
        noticeSlots =
            (try? container.decodeIfPresent([String: String].self, forKey: .noticeSlots)) ?? defaults.noticeSlots
        lastBackupAt = (try? container.decodeIfPresent(Date.self, forKey: .lastBackupAt)) ?? defaults.lastBackupAt
        recentStickers =
            (try? container.decodeIfPresent([String].self, forKey: .recentStickers)) ?? defaults.recentStickers
    }
}

final class RuntimeStateFile: @unchecked Sendable {
    static let fileName = "runtime-state.item"
    /// Fixed item id binding the ciphertext to its purpose.
    static let itemId = Data("us2us-runtime-01".utf8)

    let url: URL
    private var key: Data
    private var closed = false
    /// ``close()`` may come from another thread (the system ending the
    /// background time); no load or save ever runs with a half-zeroed key.
    private let lock = NSLock()

    init(directory: URL, dek: SecretKeyMaterial) throws {
        url = directory.appendingPathComponent(Self.fileName)
        key = try dek.withData { Self.deriveKey(dek: $0, purpose: "us2us/runtime-state/v1") }
    }

    deinit {
        SecretKeyMaterial.zeroize(&key)
    }

    /// HKDF-SHA256(DEK, salt = empty, info = purpose), 32 bytes.
    static func deriveKey(dek: Data, purpose: String) -> Data {
        let derived = HKDF<SHA256>.deriveKey(
            inputKeyMaterial: SymmetricKey(data: dek), salt: Data(), info: Data(purpose.utf8), outputByteCount: 32)
        return derived.withUnsafeBytes { Data($0) }
    }

    func load() -> RuntimeStateData {
        lock.lock()
        defer { lock.unlock() }
        guard !closed, let sealed = try? Data(contentsOf: url),
            let plaintext = try? ItemCipher.open(sealed, itemId: Self.itemId, conversationKey: key),
            let state = try? JSONDecoder().decode(RuntimeStateData.self, from: plaintext)
        else { return RuntimeStateData() }
        return state
    }

    func save(_ state: RuntimeStateData) throws {
        lock.lock()
        defer { lock.unlock() }
        // After close the key is zeros: never write under it.
        guard !closed else { throw SecretKeyMaterialError.wiped }
        var plaintext = try JSONEncoder().encode(state)
        defer { SecretKeyMaterial.zeroize(&plaintext) }
        let sealed = try ItemCipher.seal(plaintext, itemId: Self.itemId, conversationKey: key)
        try ProtectedFiles.write(sealed, to: url)
    }

    func close() {
        lock.lock()
        defer { lock.unlock() }
        closed = true
        SecretKeyMaterial.zeroize(&key)
    }
}

/// A local system line of a conversation.
struct NoticeRecord: Codable, Equatable, Sendable {
    let id: Data
    let at: Date
    let notice: SystemNotice
}

/// Local system lines ("screenshot taken", "safety number changed", ghost
/// settings changed), which Us2usStorage has no table for.
///
/// Privacy: each conversation's lines are one item encrypted under their
/// own conversation key, held in a Us2usKit key slot whose deadline mirrors
/// the conversation's ghost deadline. Deleting the slot (when the
/// conversation is wiped, by the background task while locked, or at the
/// next unlock for anything left over) makes the lines unreadable at the
/// same moment as the conversation itself (§5.3).
final class NoticeStore: @unchecked Sendable {
    let directory: URL
    private let slots: KeySlotStore
    private var dek: Data
    private var closed = false
    /// See ``RuntimeStateFile``: ``close()`` may come from another thread.
    private let lock = NSRecursiveLock()

    init(directory: URL, dek: SecretKeyMaterial) throws {
        self.directory = directory
        self.slots = KeySlotStore(directory: directory.appendingPathComponent("slots", isDirectory: true))
        self.dek = try dek.withData { Data($0) }
        try FileManager.default.createDirectory(
            at: directory, withIntermediateDirectories: true, attributes: [.protectionKey: FileProtectionType.complete])
    }

    deinit {
        SecretKeyMaterial.zeroize(&dek)
    }

    func close() {
        lock.lock()
        defer { lock.unlock() }
        closed = true
        SecretKeyMaterial.zeroize(&dek)
    }

    static func slotDirectory(in directory: URL) -> URL {
        directory.appendingPathComponent("slots", isDirectory: true)
    }

    /// Crypto-shreds notices of ghost conversations whose deadline passed,
    /// without the DEK (background task / launch).
    @discardableResult
    static func runBackgroundWipes(directory: URL, now: Date) -> Int {
        let slots = KeySlotStore(directory: slotDirectory(in: directory))
        return ((try? slots.destroyExpiredSlots(now: now)) ?? []).count
    }

    private func fileURL(slotId: Data) -> URL {
        directory.appendingPathComponent(slotId.hexString + ".notices")
    }

    func notices(slotId: Data) -> [NoticeRecord] {
        lock.lock()
        defer { lock.unlock() }
        guard !closed, let sealed = try? Data(contentsOf: fileURL(slotId: slotId)),
            let key = try? slots.conversationKey(slotId: slotId, dek: dek),
            let plaintext = try? ItemCipher.open(sealed, itemId: slotId, conversationKey: key),
            let records = try? JSONDecoder().decode([NoticeRecord].self, from: plaintext)
        else { return [] }
        return records
    }

    /// Appends a line; creates the conversation's slot on first use.
    /// Returns the slot id (store it in the runtime state).
    func append(_ record: NoticeRecord, slotId existing: Data?, deadline: Date?) throws -> Data {
        lock.lock()
        defer { lock.unlock() }
        // After close the key is zeros: never create slots or items under it.
        guard !closed else { throw SecretKeyMaterialError.wiped }
        let slotId: Data
        if let existing, (try? slots.conversationKey(slotId: existing, dek: dek)) != nil {
            slotId = existing
        } else {
            slotId = try slots.createSlot(dek: dek).slotId
        }
        var records = notices(slotId: slotId)
        records.append(record)
        // Lines are few; keep the newest 500 per conversation.
        if records.count > 500 { records.removeFirst(records.count - 500) }
        let key = try slots.conversationKey(slotId: slotId, dek: dek)
        let sealed = try ItemCipher.seal(try JSONEncoder().encode(records), itemId: slotId, conversationKey: key)
        try ProtectedFiles.write(sealed, to: fileURL(slotId: slotId))
        try slots.setDeadline(deadline, for: slotId)
        return slotId
    }

    func setDeadline(_ deadline: Date?, slotId: Data) {
        try? slots.setDeadline(deadline, for: slotId)
    }

    /// Crypto-shreds one conversation's lines (slot first, then the file).
    func destroy(slotId: Data) {
        try? slots.destroySlot(slotId)
        try? FileManager.default.removeItem(at: fileURL(slotId: slotId))
    }

    /// Removes everything not in `keep` (slots and files of wiped
    /// conversations, orphans after a crash).
    func collectGarbage(keep: Set<Data>) {
        for slotId in (try? slots.slotIds()) ?? [] where !keep.contains(slotId) {
            destroy(slotId: slotId)
        }
        let names = (try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? []
        for name in names where name.hasSuffix(".notices") {
            guard let slotId = Data(hexString: String(name.dropLast(".notices".count))), !keep.contains(slotId)
            else { continue }
            try? FileManager.default.removeItem(at: directory.appendingPathComponent(name))
        }
    }
}
