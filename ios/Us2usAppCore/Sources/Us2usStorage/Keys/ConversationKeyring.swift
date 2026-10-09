// us2us — AGPL-3.0-only

import Foundation
import Us2usKit

/// The conversation keys `CK` of PROTOCOL.md §5.3, one per conversation, each
/// in its own key-slot file (`BRPS`, Us2usKit's ``KeySlot`` format). Opened
/// keys are cached in memory while storage is unlocked and dropped on
/// ``close()``.
///
/// Destroying a slot is the crypto-shred: once the file is gone, every item
/// encrypted under its `CK` (bodies, attachments, thumbnails, drafts, pending
/// sends) is unreadable, whether or not the rows and files still exist. A
/// destroyed slot id is also remembered in memory, so a key being loaded
/// concurrently can never be cached (or returned) after the shred.
final class ConversationKeyring: @unchecked Sendable {
    let slots: KeySlotStore
    private let directory: URL
    private let scheduleURL: URL
    private let lock = NSLock()
    private var dek: Data?
    private var cache: [Data: Data] = [:]
    private var shredded: Set<Data> = []

    init(layout: StorageLayout, dek: Data) {
        self.directory = layout.slotDirectory
        self.scheduleURL = layout.scheduleURL
        self.slots = KeySlotStore(directory: layout.slotDirectory)
        self.dek = dek
    }

    private func currentDEK() throws(StorageError) -> Data {
        guard let dek = lock.withLock({ self.dek }) else { throw .closed }
        return dek
    }

    func slotURL(_ slotId: Data) -> URL {
        directory.appendingPathComponent(slotId.hexString + ".slot")
    }

    func slotExists(_ slotId: Data) -> Bool {
        FileManager.default.fileExists(atPath: slotURL(slotId).path)
    }

    /// Creates a slot with a fresh random `CK` (written with complete file
    /// protection, atomically).
    func createSlot() throws(StorageError) -> (slotId: Data, key: Data) {
        let dek = try currentDEK()
        do {
            let created = try slots.createSlot(dek: dek)
            lock.withLock { cache[created.slotId] = created.conversationKey }
            return (created.slotId, created.conversationKey)
        } catch {
            throw .io("cannot create key slot")
        }
    }

    /// `CK` of a conversation, or ``StorageError/conversationShredded`` if its
    /// slot is gone.
    func key(for slotId: Data) throws(StorageError) -> Data {
        let cached = lock.withLock { () -> Data?? in
            if shredded.contains(slotId) { return .some(nil) }
            return cache[slotId].map { .some($0) }
        }
        switch cached {
        case .some(.some(let key)): return key
        case .some(.none): throw .conversationShredded
        case .none: break
        }
        let dek = try currentDEK()
        guard slotExists(slotId) else { throw .conversationShredded }
        let key: Data
        do {
            key = try slots.conversationKey(slotId: slotId, dek: dek)
        } catch let error as LocalStorageError {
            switch error {
            case .integrityCheckFailed, .malformed: throw .integrity
            case .io: throw slotExists(slotId) ? .io("slot unreadable") : .conversationShredded
            }
        } catch {
            throw .io("slot unreadable")
        }
        lock.lock()
        defer { lock.unlock() }
        guard !shredded.contains(slotId) else { throw .conversationShredded }
        cache[slotId] = key
        return key
    }

    /// Crypto-shreds one conversation (deletes its slot and schedule entry).
    func destroy(_ slotId: Data) throws(StorageError) {
        markShredded([slotId])
        do {
            try slots.destroySlot(slotId)
        } catch {
            // The schedule could not be rewritten; the slot itself must be gone.
            try FileProtection.removeIfPresent(slotURL(slotId))
            if slotExists(slotId) { throw .io("cannot destroy key slot") }
            repairScheduleIfUnreadable()
        }
    }

    /// Forgets cached keys of slots destroyed elsewhere (the locked-path wipe).
    func markShredded(_ slotIds: [Data]) {
        lock.withLock {
            for slotId in slotIds {
                shredded.insert(slotId)
                if let count = cache[slotId]?.count { cache[slotId]?.resetBytes(in: 0..<count) }
                cache.removeValue(forKey: slotId)
            }
        }
    }

    func slotIds() -> Set<Data> {
        Set((try? slots.slotIds()) ?? [])
    }

    func setDeadline(_ deadline: Date?, for slotId: Data) throws(StorageError) {
        do {
            try slots.setDeadline(deadline, for: slotId)
        } catch {
            // A damaged schedule must not block ghost mode forever: start a
            // fresh one (the unlocked app re-adds every deadline from the database).
            repairScheduleIfUnreadable()
            do {
                try slots.setDeadline(deadline, for: slotId)
            } catch {
                throw .io("cannot write ghost schedule")
            }
        }
    }

    func deadlines() -> [Data: Date] {
        (try? slots.deadlines()) ?? [:]
    }

    /// Destroys every slot whose ghost deadline passed; no DEK needed.
    func destroyExpiredSlots(now: Date) throws(StorageError) -> [Data] {
        let expired: [Data]
        do {
            expired = try slots.destroyExpiredSlots(now: now)
        } catch {
            throw .io("ghost schedule unreadable")
        }
        markShredded(expired)
        return expired
    }

    private func repairScheduleIfUnreadable() {
        guard (try? slots.deadlines()) == nil else { return }
        try? FileProtection.removeIfPresent(scheduleURL)
    }

    /// Drops the DEK and every cached `CK`.
    func close() {
        lock.withLock {
            // Best effort: overwrite our copies in place before dropping them.
            if let count = dek?.count { dek?.resetBytes(in: 0..<count) }
            dek = nil
            for slotId in Array(cache.keys) {
                if let count = cache[slotId]?.count { cache[slotId]?.resetBytes(in: 0..<count) }
            }
            cache.removeAll()
        }
    }
}
