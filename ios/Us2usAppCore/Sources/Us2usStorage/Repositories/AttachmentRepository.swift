// us2us — AGPL-3.0-only

import Foundation
import SwiftProtobuf
import Us2usKit

/// Attachments: local encrypted files and upload/download state (§5.3, §7).
///
/// The relay pointer and keys (`Attachment`: media id, delete token, key,
/// digest, content type, file name, caption…) are an item under `CK`
/// (`itemId` = the attachment id). The decrypted media itself is an item
/// file named by the same id; a locally generated thumbnail is an item file
/// of its own. Plaintext exists on disk only in the staging directory, while
/// `Messenger` encrypts or decrypts it.
public struct AttachmentRepository {
    let tx: StorageTransaction

    private static let columns = """
        id, conversation_id, message_id, descriptor, state, has_file, thumbnail_id, byte_count, transferred,
        attempts, updated_at
        """

    /// Creates the row for an attachment of `messageId`.
    @discardableResult
    public func create(
        for messageId: Data, in conversationId: ConversationId, descriptor: BRPAttachment?, state: AttachmentState,
        now: Date = Date()
    ) throws -> StoredAttachment {
        try tx.requireWritable()
        let id = RandomId.make()
        let key = try tx.conversationKey(conversationId)
        let sealed = try descriptor.map { try tx.seal(try $0.serializedBytes(), itemId: id, key: key) }
        try tx.connection.run(
            """
            INSERT INTO attachments (id, conversation_id, message_id, descriptor, state, byte_count, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
            [id, conversationId.rawValue, messageId, sealed, state.rawValue, descriptor.map { $0.size },
             now.storageMilliseconds])
        guard let created = try get(id) else { throw StorageError.notFound }
        return created
    }

    public func get(_ id: Data) throws -> StoredAttachment? {
        guard let row = try tx.connection.queryOne("SELECT \(Self.columns) FROM attachments WHERE id = ?", [id])
        else { return nil }
        return try attachment(from: row)
    }

    public func forMessage(_ messageId: Data, in conversationId: ConversationId) throws -> [StoredAttachment] {
        try tx.connection.query(
            "SELECT \(Self.columns) FROM attachments WHERE conversation_id = ? AND message_id = ?",
            [conversationId.rawValue, messageId]
        ).map(attachment(from:))
    }

    /// Attachments in `states` (e.g. pending downloads to resume on open).
    public func inStates(_ states: [AttachmentState], limit: Int = 100) throws -> [StoredAttachment] {
        guard !states.isEmpty else { return [] }
        let placeholders = Array(repeating: "?", count: states.count).joined(separator: ", ")
        return try tx.connection.query(
            "SELECT \(Self.columns) FROM attachments WHERE state IN (\(placeholders)) ORDER BY updated_at LIMIT ?",
            states.map { $0.rawValue as any SQLBindable } + [limit]
        ).map(attachment(from:))
    }

    public func setState(
        _ state: AttachmentState, of id: Data, transferredBytes: UInt64? = nil, countsAsAttempt: Bool = false,
        now: Date = Date()
    ) throws {
        try tx.requireWritable()
        try tx.connection.run(
            """
            UPDATE attachments SET state = ?, transferred = coalesce(?, transferred),
                attempts = attempts + ?, updated_at = ?
            WHERE id = ?
            """, [state.rawValue, transferredBytes, countsAsAttempt ? 1 : 0, now.storageMilliseconds, id])
        guard tx.connection.changes > 0 else { throw StorageError.notFound }
    }

    /// The `Attachment` after an upload (`Messenger.uploadAttachment`); with a
    /// relay pointer the state becomes `uploaded`.
    public func setDescriptor(_ descriptor: BRPAttachment, of id: Data, now: Date = Date()) throws {
        try tx.requireWritable()
        guard let attachment = try get(id) else { throw StorageError.notFound }
        let key = try tx.conversationKey(attachment.conversationId)
        let sealed = try tx.seal(try descriptor.serializedBytes(), itemId: id, key: key)
        // A pointer means the blob is on the relay; without one it is still to upload.
        let state: AttachmentState =
            descriptor.mediaID.count == RandomId.byteCount ? .uploaded : attachment.state
        try tx.connection.run(
            "UPDATE attachments SET descriptor = ?, byte_count = ?, state = ?, updated_at = ? WHERE id = ?",
            [sealed, descriptor.size, state.rawValue, now.storageMilliseconds, id])
    }

    // MARK: Files

    /// Encrypts `plaintext` as the attachment's file and marks it present
    /// (state `downloaded` unless it is an upload in progress).
    public func storeFile(_ plaintext: Data, for id: Data, now: Date = Date()) throws {
        try tx.requireWritable()
        guard let attachment = try get(id) else { throw StorageError.notFound }
        let key = try tx.conversationKey(attachment.conversationId)
        try tx.storage.files.write(plaintext, itemId: id, key: key)
        let state: AttachmentState =
            [.pendingUpload, .uploading, .uploaded].contains(attachment.state) ? attachment.state : .downloaded
        try tx.connection.run(
            "UPDATE attachments SET has_file = 1, state = ?, byte_count = coalesce(byte_count, ?), updated_at = ? WHERE id = ?",
            [state.rawValue, UInt64(plaintext.count), now.storageMilliseconds, id])
    }

    /// Encrypts the plaintext file at `url` (e.g. a staging file written by
    /// `Messenger.downloadAttachment`) and deletes the plaintext.
    public func importFile(at url: URL, for id: Data, now: Date = Date()) throws {
        let plaintext: Data
        do {
            plaintext = try Data(contentsOf: url, options: .uncached)
        } catch {
            throw StorageError.io("staging file unreadable")
        }
        defer { try? FileManager.default.removeItem(at: url) }
        try storeFile(plaintext, for: id, now: now)
    }

    /// Decrypts the attachment's file.
    public func readFile(_ id: Data) throws -> Data {
        guard let attachment = try get(id), attachment.hasFile else { throw StorageError.notFound }
        return try tx.storage.files.read(id, key: try tx.conversationKey(attachment.conversationId))
    }

    /// Decrypts the file into a staging URL for APIs that need a path (upload,
    /// AVPlayer). Delete it when done; it is wiped on the next open/close anyway.
    public func exportToStaging(_ id: Data) throws -> URL {
        let plaintext = try readFile(id)
        let url = tx.storage.files.newStagingURL()
        try FileProtection.writeAtomically(plaintext, to: url, protection: .completeFileProtection)
        return url
    }

    public func storeThumbnail(_ plaintext: Data, for id: Data) throws {
        try tx.requireWritable()
        guard let attachment = try get(id) else { throw StorageError.notFound }
        let thumbnailId = attachment.thumbnailId ?? RandomId.make()
        try tx.storage.files.write(
            plaintext, itemId: thumbnailId, key: try tx.conversationKey(attachment.conversationId))
        try tx.connection.run("UPDATE attachments SET thumbnail_id = ? WHERE id = ?", [thumbnailId, id])
    }

    public func readThumbnail(_ id: Data) throws -> Data? {
        guard let attachment = try get(id), let thumbnailId = attachment.thumbnailId else { return nil }
        return try tx.storage.files.read(thumbnailId, key: try tx.conversationKey(attachment.conversationId))
    }

    /// Deletes rows and (after commit) files of the given messages' attachments.
    func deleteAll(for messageIds: [Data], in conversationId: ConversationId) throws {
        var itemIds: [Data] = []
        for messageId in messageIds {
            let rows = try tx.connection.query(
                "SELECT id, thumbnail_id FROM attachments WHERE conversation_id = ? AND message_id = ?",
                [conversationId.rawValue, messageId])
            for row in rows {
                if let id = row.blob("id") { itemIds.append(id) }
                if let thumbnail = row.blob("thumbnail_id") { itemIds.append(thumbnail) }
            }
            try tx.connection.run(
                "DELETE FROM attachments WHERE conversation_id = ? AND message_id = ?",
                [conversationId.rawValue, messageId])
        }
        removeFilesAfterCommit(itemIds)
    }

    func removeFilesAfterCommit(_ itemIds: [Data]) {
        guard !itemIds.isEmpty else { return }
        let files = tx.storage.files
        tx.afterCommit {
            for id in itemIds { try? files.remove(id) }
        }
    }

    // MARK: Rows

    private func attachment(from row: Row) throws -> StoredAttachment {
        guard let id = row.blob("id"), let conversationBytes = row.blob("conversation_id"),
            let conversationId = ConversationId(rawValue: conversationBytes), let messageId = row.blob("message_id"),
            let state = row.int("state").flatMap(AttachmentState.init(rawValue:))
        else { throw StorageError.integrity }
        var descriptor: BRPAttachment?
        if let sealed = row.blob("descriptor") {
            let plaintext = try tx.open(sealed, itemId: id, key: try tx.conversationKey(conversationId))
            do {
                descriptor = try BRPAttachment(serializedBytes: plaintext)
            } catch {
                throw StorageError.integrity
            }
        }
        return StoredAttachment(
            id: id, conversationId: conversationId, messageId: messageId, descriptor: descriptor, state: state,
            hasFile: row.bool("has_file"), thumbnailId: row.blob("thumbnail_id"), byteCount: row.uint64("byte_count"),
            transferredBytes: row.uint64("transferred") ?? 0, attempts: row.int("attempts") ?? 0,
            updatedAt: row.date("updated_at") ?? Date(timeIntervalSince1970: 0))
    }
}
