import Foundation

/// The receipt rules of PROTOCOL.md §8.4.
public enum ReceiptPolicy {
    /// At most this many ids per `ReceiptMessage` (about 18 KiB, well inside one envelope).
    public static let maxMessageIds = 1000

    /// Whether `content`, received from a contact in `state`, gets a delivery
    /// receipt: text, attachment, location and sticker messages from accepted
    /// contacts, and nothing else (no receipts for reactions, deletes,
    /// live-location ticks, control messages or message requests).
    public static func wantsDeliveryReceipt(for content: BRPContent, state: ConversationRequestState) -> Bool {
        guard MessageRequestPolicy.maySendDeliveryReceipts(state: state), case .data(let data) = content.body else {
            return false
        }
        switch data.payload {
        case .text, .attachment, .location, .sticker: return true
        case .liveLocation, .reaction, .delete, .none: return false
        }
    }

    /// `ReceiptMessage`s for `messageIds`, in order, at most ``maxMessageIds`` each.
    public static func receipts(_ type: BRPReceiptMessage.TypeEnum, messageIds: [Data]) -> [BRPContent] {
        stride(from: 0, to: messageIds.count, by: maxMessageIds).map { start in
            BRPContent(receipt: type, messageIds: Array(messageIds[start..<min(start + maxMessageIds, messageIds.count)]))
        }
    }
}

/// Delivery receipts collected while messages arrive, so that each contact
/// gets as few `ReceiptMessage`s as possible (§1.1, §8.4): add every stored
/// message, then send what ``drain()`` returns, e.g. after `queue_empty` and
/// after each live message.
public struct DeliveryReceiptBatch: Sendable {
    private var pending: [AccountId: [Data]] = [:]
    private var order: [AccountId] = []

    public init() {}

    public var isEmpty: Bool { pending.isEmpty }

    /// Queues a receipt for `message` if §8.4 wants one; `state` is the
    /// conversation state after the message was handled.
    public mutating func add(_ message: IncomingMessage, state: ConversationRequestState) {
        guard ReceiptPolicy.wantsDeliveryReceipt(for: message.content, state: state),
            case .data(let data) = message.content.body
        else { return }
        if pending[message.sender] == nil { order.append(message.sender) }
        pending[message.sender, default: []].append(data.messageID)
    }

    /// The receipts to send, per contact in the order they first wrote, and
    /// empties the batch. Put back what could not be sent with ``requeue(_:for:)``.
    public mutating func drain() -> [(contact: AccountId, receipts: [BRPContent])] {
        defer {
            pending = [:]
            order = []
        }
        return order.map { contact in
            (contact, ReceiptPolicy.receipts(.delivery, messageIds: pending[contact] ?? []))
        }
    }

    /// Puts receipts that failed to send back in front of the queue.
    public mutating func requeue(_ receipts: [BRPContent], for contact: AccountId) {
        let ids = receipts.flatMap { content -> [Data] in
            if case .receipt(let receipt) = content.body { return receipt.messageIds }
            return []
        }
        guard !ids.isEmpty else { return }
        if pending[contact] == nil { order.insert(contact, at: 0) }
        pending[contact, default: []].insert(contentsOf: ids, at: 0)
    }
}
