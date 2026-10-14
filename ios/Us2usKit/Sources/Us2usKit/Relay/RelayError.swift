import Foundation

/// Machine-readable relay error codes (PROTOCOL.md §2.1). Unknown codes are
/// kept as-is so newer relays do not break older clients.
public struct RelayErrorCode: RawRepresentable, Hashable, Sendable, CustomStringConvertible {
    public let rawValue: String

    public init(rawValue: String) {
        self.rawValue = rawValue
    }

    public var description: String { rawValue }

    public static let badRequest = RelayErrorCode(rawValue: "bad_request")
    public static let unauthorized = RelayErrorCode(rawValue: "unauthorized")
    public static let accessDenied = RelayErrorCode(rawValue: "access_denied")
    public static let powInvalid = RelayErrorCode(rawValue: "pow_invalid")
    public static let notFound = RelayErrorCode(rawValue: "not_found")
    public static let usernameTaken = RelayErrorCode(rawValue: "username_taken")
    /// An avatar write whose version is not above the account's highest (§4.12).
    public static let staleVersion = RelayErrorCode(rawValue: "stale_version")
    public static let payloadTooLarge = RelayErrorCode(rawValue: "payload_too_large")
    public static let rateLimited = RelayErrorCode(rawValue: "rate_limited")
    /// Communities: a request, redemption or roster would exceed 100 members (409).
    public static let communityFull = RelayErrorCode(rawValue: "community_full")
    /// Communities: one recipient's community mail is full (429).
    public static let communityInboxFull = RelayErrorCode(rawValue: "community_inbox_full")
    public static let `internal` = RelayErrorCode(rawValue: "internal")
}

public enum RelayError: Error, Equatable, Sendable {
    /// A non-2xx response. `code` is `nil` if the body was not the §2.1 shape.
    case server(status: Int, code: RelayErrorCode?, message: String?)
    /// `429`, with the `Retry-After` delay when the relay sent one.
    case rateLimited(retryAfter: TimeInterval?)
    /// `429 community_inbox_full`: only this recipient's community mail is
    /// full; others can still be reached. With the `Retry-After` delay.
    case communityInboxFull(retryAfter: TimeInterval?)
    /// The request never got a response.
    case network(URLError.Code)
    /// A 2xx response whose body did not match the spec.
    case invalidResponse
    /// An authenticated call was made before credentials were set.
    case notAuthenticated
    /// A request was not sent because it exceeds a documented relay limit.
    case payloadTooLarge
    /// WebSocket close code `4001`: a newer connection for this account replaced this one.
    case connectionReplaced
    /// The WebSocket closed with an unexpected code.
    case connectionClosed(code: Int)

    /// The §2.1 code, also for `429` and for an unauthorized WebSocket.
    public var code: RelayErrorCode? {
        switch self {
        case .server(_, let code, _): return code
        case .rateLimited: return .rateLimited
        case .communityInboxFull: return .communityInboxFull
        default: return nil
        }
    }

    public var isNotFound: Bool {
        if case .server(404, _, _) = self { return true }
        return false
    }
}

extension RelayError: LocalizedError {
    public var errorDescription: String? {
        let localizer = Localizer.current
        switch self {
        case .rateLimited(let retryAfter?), .communityInboxFull(let retryAfter?):
            return localizer.string(.relayRateLimited, localizer.duration(seconds: Int(retryAfter.rounded(.up))))
        case .rateLimited(nil), .communityInboxFull(nil):
            return localizer.string(.relayRateLimitedGeneric)
        case .network:
            return localizer.string(.relayNetwork)
        case .invalidResponse:
            return localizer.string(.relayUnexpectedResponse)
        case .notAuthenticated:
            return localizer.string(.relayUnauthorized)
        case .payloadTooLarge:
            return localizer.string(.relayPayloadTooLarge)
        case .connectionReplaced:
            return localizer.string(.relayConnectionReplaced)
        case .connectionClosed:
            return localizer.string(.relayNetwork)
        case .server(let status, let code, _):
            switch code {
            case .badRequest?: return localizer.string(.relayBadRequest)
            case .unauthorized?: return localizer.string(.relayUnauthorized)
            case .accessDenied?: return localizer.string(.relayAccessDenied)
            case .powInvalid?: return localizer.string(.relayProofOfWorkInvalid)
            case .notFound?: return localizer.string(.relayNotFound)
            case .usernameTaken?: return localizer.string(.relayUsernameTaken)
            case .payloadTooLarge?: return localizer.string(.relayPayloadTooLarge)
            case .rateLimited?: return localizer.string(.relayRateLimitedGeneric)
            default:
                return status >= 500 ? localizer.string(.relayServerError) : localizer.string(.relayUnexpectedResponse)
            }
        }
    }
}
