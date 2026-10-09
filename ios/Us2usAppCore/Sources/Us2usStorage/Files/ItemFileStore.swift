// us2us — AGPL-3.0-only

import Foundation
import Us2usKit

/// Attachment and thumbnail files, each an item under its conversation's
/// key (PROTOCOL.md §5.3):
///
///     item = 0x01 ‖ nonce(12) ‖ AES-256-GCM(CK, nonce, aad = 0x01 ‖ itemId(16), plaintext) ‖ tag(16)
///
/// Files are named only by their random item id (`<hex>.item`), so neither
/// names nor types leak into the file system. Plaintext never touches the
/// disk outside `staging/`, which is cleared on every open and close.
final class ItemFileStore: Sendable {
    let directory: URL
    let staging: URL

    init(layout: StorageLayout) {
        self.directory = layout.filesDirectory
        self.staging = layout.stagingDirectory
    }

    func url(for itemId: Data) -> URL {
        directory.appendingPathComponent(itemId.hexString + ".item")
    }

    func exists(_ itemId: Data) -> Bool {
        FileManager.default.fileExists(atPath: url(for: itemId).path)
    }

    /// Encrypts and writes atomically (temporary file + rename).
    func write(_ plaintext: Data, itemId: Data, key: Data) throws(StorageError) {
        let item: Data
        do {
            item = try ItemCipher.seal(plaintext, itemId: itemId, conversationKey: key)
        } catch {
            throw .integrity
        }
        try FileProtection.writeAtomically(item, to: url(for: itemId), protection: .completeFileProtectionUnlessOpen)
    }

    func read(_ itemId: Data, key: Data) throws(StorageError) -> Data {
        let item: Data
        do {
            item = try Data(contentsOf: url(for: itemId), options: .uncached)
        } catch {
            throw .notFound
        }
        do {
            return try ItemCipher.open(item, itemId: itemId, conversationKey: key)
        } catch {
            throw .integrity
        }
    }

    func remove(_ itemId: Data) throws(StorageError) {
        try FileProtection.removeIfPresent(url(for: itemId))
    }

    /// Item ids of every file present (for garbage collection).
    func allItemIds() -> Set<Data> {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? []
        return Set(
            names.compactMap { name -> Data? in
                guard name.hasSuffix(".item") else { return nil }
                let id = Data(hexString: String(name.dropLast(5)))
                return id?.count == RandomId.byteCount ? id : nil
            })
    }

    /// Leftovers that are not items: temporary files of interrupted atomic writes.
    func strayFiles() -> [URL] {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? []
        return names.filter { name in
            guard name.hasSuffix(".item") else { return true }
            return Data(hexString: String(name.dropLast(5)))?.count != RandomId.byteCount
        }.map { directory.appendingPathComponent($0) }
    }

    /// A fresh path for plaintext that is about to be encrypted or was just
    /// decrypted (complete protection, excluded from backup, wiped on open/close).
    func newStagingURL() -> URL {
        staging.appendingPathComponent(UUID().uuidString)
    }

    func clearStaging() {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: staging.path)) ?? []
        for name in names {
            try? FileManager.default.removeItem(at: staging.appendingPathComponent(name))
        }
    }
}
