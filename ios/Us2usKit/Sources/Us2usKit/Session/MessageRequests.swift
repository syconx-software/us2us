import Foundation

/// Where a conversation stands with respect to message requests (PROTOCOL.md §6.4).
public enum ConversationRequestState: String, Codable, Sendable, CaseIterable {
    /// We wrote first and the other side has not accepted yet.
    case outgoingRequest
    /// They wrote first and we have not accepted yet.
    case incomingRequest
    case accepted
    case blocked
}

/// How the relay authorizes an unauthenticated sealed send (§4.6).
public enum DeliveryAuthorization: Sendable, Equatable {
    /// `X-Access-Key`: the recipient's access key, given to accepted contacts.
    case accessKey(Data)
    /// `X-PoW`: a fresh `message` proof-of-work bound to the recipient (first contact).
    case proofOfWork
}

/// The access key that lets accepted contacts send without proof-of-work (§4.3, §4.6).
public enum AccessKey {
    public static let byteCount = 16

    public static func generate() -> Data { SecureRandom.bytes(byteCount) }

    /// `accessKeyHash = SHA-256(accessKey)`, the only form the relay stores.
    public static func hash(_ accessKey: Data) -> Data { accessKey.sha256Digest }
}

/// The §6.4 rules as pure functions, so both the app and ``Messenger`` apply
/// them identically.
public enum MessageRequestPolicy {
    /// How to send a non-ephemeral message in a conversation, or `nil` if
    /// nothing may be sent (blocked).
    ///
    /// - Parameter theirAccessKey: the key the contact gave us in `AccessKeyUpdate`.
    public static func delivery(
        state: ConversationRequestState, theirAccessKey: Data?
    ) -> (authorization: DeliveryAuthorization, includeSenderUsername: Bool)? {
        switch state {
        case .blocked:
            return nil
        case .outgoingRequest:
            // While a request is pending the recipient has not vouched for us.
            return (.proofOfWork, true)
        case .incomingRequest, .accepted:
            // Until the contact's access key arrives, proof-of-work it is.
            return (theirAccessKey.map(DeliveryAuthorization.accessKey) ?? .proofOfWork, false)
        }
    }

    /// Delivery receipts are always sent, except for message requests (§5.4, §8.4).
    public static func maySendDeliveryReceipts(state: ConversationRequestState) -> Bool {
        state == .accepted
    }

    /// Read receipts and typing indicators: optional, never for requests (§8.4).
    public static func maySendReadReceipts(state: ConversationRequestState, userEnabled: Bool) -> Bool {
        state == .accepted && userEnabled
    }

    public static func maySendTypingIndicators(state: ConversationRequestState, userEnabled: Bool) -> Bool {
        state == .accepted && userEnabled
    }

    /// Whether a received message is shown at all. Messages from a blocked
    /// account are dropped locally (§6.4).
    public static func shouldDeliver(state: ConversationRequestState?) -> Bool {
        state != .blocked
    }

    /// State after a message from the contact arrived. A first message from an
    /// unknown account opens an incoming request; an explicit `ACCEPT` ends an
    /// outgoing one.
    public static func state(
        afterReceiving content: BRPContent, current: ConversationRequestState?
    ) -> ConversationRequestState {
        guard let current else { return .incomingRequest }
        if current == .outgoingRequest, case .control(let control) = content.body,
            case .requestResponse(let response) = control.action, response.type == .accept
        {
            return .accepted
        }
        return current
    }

    /// The messages to send when the user accepts a request: `ACCEPT`, then our
    /// access key so the contact can stop solving proof-of-work.
    public static func acceptanceMessages(ourAccessKey: Data) -> [BRPContent] {
        [.messageRequestResponse(.accept), .accessKeyUpdate(ourAccessKey)]
    }

    /// The requester's answer to an `ACCEPT`: its own access key, so that both
    /// sides send with `X-Access-Key` from then on.
    public static func messagesAfterAcceptance(ourAccessKey: Data) -> [BRPContent] {
        [.accessKeyUpdate(ourAccessKey)]
    }
}
