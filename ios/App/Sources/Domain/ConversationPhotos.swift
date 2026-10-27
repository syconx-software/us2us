import Foundation

/// Presentation only. Encryption, persistence, deletion and actions stay per message.
enum ConversationPhotos {
    static let albumLimit = 10

    static func isBrowsable(_ message: Message) -> Bool {
        guard case .photo = message.content else { return false }
        return !message.isPendingMedia
    }

    private static func canGroup(_ message: Message) -> Bool {
        isBrowsable(message) && !message.isDisappearing && message.replyTo == nil && message.reactions.isEmpty
            && !message.savedByPeer && message.status != .failed
    }

    static func canAppend(_ album: [Message], _ next: Message) -> Bool {
        guard let first = album.first, album.count < albumLimit, canGroup(first), canGroup(next),
            first.direction == next.direction, first.community?.senderID == next.community?.senderID,
            case .photo(_, let caption) = next.content,
            caption?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty != false
        else { return false }
        let elapsed = next.sentAt.timeIntervalSince(first.sentAt)
        return elapsed >= 0 && elapsed <= 60
    }

    static func groups(_ messages: [Message]) -> [[Message]] {
        var result: [[Message]] = []
        for message in messages {
            if let last = result.last, canAppend(last, message) { result[result.count - 1].append(message) }
            else { result.append([message]) }
        }
        return result
    }

    /// Do not claim every photo is read when only one reached that state.
    static func status(_ photos: [Message]) -> MessageStatus? {
        let order: [MessageStatus] = [.sending, .sent, .delivered, .read, .failed]
        return photos.compactMap(\.status).min { order.firstIndex(of: $0)! < order.firstIndex(of: $1)! }
    }

    static func albums(_ rows: [ConversationRow], separate: Set<MessageID>) -> [ConversationRow] {
        var result: [ConversationRow] = []
        for row in rows {
            if let previous = result.last {
                let photos = previous.album.isEmpty ? [previous.message] : previous.album
                if !separate.contains(row.id), !photos.contains(where: { separate.contains($0.id) }), canAppend(photos, row.message) {
                    result[result.count - 1] = ConversationRow(message: previous.message, isTail: row.isTail,
                        showsStatusSentence: previous.showsStatusSentence || row.showsStatusSentence,
                        showsSender: previous.showsSender, album: photos + [row.message])
                    continue
                }
            }
            result.append(row)
        }
        return result
    }

    static func albumRemovals(_ rows: [ConversationRow], leaving: Set<MessageID>) -> Set<MessageID> {
        guard !leaving.isEmpty else { return [] }
        return Set(albums(rows, separate: []).flatMap(\.album).map(\.id)).intersection(leaving)
    }
}
