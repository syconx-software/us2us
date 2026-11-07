// us2us — AGPL-3.0-only

import Foundation
import Us2usKit

/// What one chat shows behind its messages on this phone: a built-in preset
/// (its id) or a photo the user picked (a JPEG the app prepared).
public enum ChatBackgroundChoice: Equatable, Sendable {
    case preset(String)
    case photo(Data)

    public static let maxPresetIdLength = 32
    public static let maxPhotoBytes = 2_500_000

    var isValid: Bool {
        switch self {
        case .preset(let id): !id.isEmpty && id.utf8.count <= Self.maxPresetIdLength
        case .photo(let jpeg): !jpeg.isEmpty && jpeg.count <= Self.maxPhotoBytes
        }
    }
}

/// Each chat's background (schema v12). It is a choice of this phone: never
/// sent, and not part of a backup. The row belongs to the conversation or the
/// community, so it goes with it however that ends (“Sohbeti sil”, “Kişiyi
/// sil”, a Ghost wipe, leaving a community, erasing everything).
public struct ChatBackgroundRepository {
    let tx: StorageTransaction

    public func forContact(_ account: AccountId) throws -> ChatBackgroundChoice? {
        guard let conversation = try tx.conversations.forContact(account) else { return nil }
        return try read(column: "conversation_id", owner: conversation.id.rawValue)
    }

    /// `nil` returns the chat to the app's own background.
    public func setForContact(_ choice: ChatBackgroundChoice?, _ account: AccountId, now: Date = Date()) throws {
        try tx.requireWritable()
        let conversation = try tx.conversations.ensure(for: account, now: now)
        try write(choice, column: "conversation_id", owner: conversation.id.rawValue)
    }

    public func forCommunity(_ id: AccountId) throws -> ChatBackgroundChoice? {
        try read(column: "community_id", owner: id.bytes)
    }

    /// `nil` returns the community to the app's own background.
    public func setForCommunity(_ choice: ChatBackgroundChoice?, _ id: AccountId) throws {
        try tx.requireWritable()
        guard try tx.communities.get(id) != nil else { throw StorageError.notFound }
        try write(choice, column: "community_id", owner: id.bytes)
    }

    private func read(column: String, owner: Data) throws -> ChatBackgroundChoice? {
        guard
            let row = try tx.connection.queryOne(
                "SELECT preset, photo FROM chat_backgrounds WHERE \(column) = ?", [owner])
        else { return nil }
        if let preset = row.text("preset") { return .preset(preset) }
        return row.blob("photo").map(ChatBackgroundChoice.photo)
    }

    private func write(_ choice: ChatBackgroundChoice?, column: String, owner: Data) throws {
        guard let choice else {
            try tx.connection.run("DELETE FROM chat_backgrounds WHERE \(column) = ?", [owner])
            return
        }
        guard choice.isValid else { throw StorageError.invalidState("chat background") }
        let (preset, photo): (String?, Data?) =
            switch choice {
            case .preset(let id): (id, nil)
            case .photo(let jpeg): (nil, jpeg)
            }
        try tx.connection.run(
            """
            INSERT INTO chat_backgrounds (\(column), preset, photo) VALUES (?, ?, ?)
            ON CONFLICT(\(column)) DO UPDATE SET preset = excluded.preset, photo = excluded.photo
            """, [owner, preset, photo])
    }
}
