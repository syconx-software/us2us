import Foundation
import Us2usKit

public enum NetworkPolicyError: Error, Equatable, Sendable {
    /// The relay URL is not `https`, or plain `http` to a non-loopback host.
    case insecureRelayURL
}

/// The app talks to exactly one host: the relay. No analytics, crash
/// reporting, link previews, CDNs or third-party SDKs; everything the app
/// sends goes through the one ``RelayClient`` built here.
public enum NetworkPolicy {
    /// `https` only. Plain `http` is accepted for loopback addresses, which
    /// tests and the local interop relay use; nothing else.
    public static func validatedRelayURL(_ url: URL) throws(NetworkPolicyError) -> URL {
        guard let scheme = url.scheme?.lowercased(), let host = url.host?.lowercased(), !host.isEmpty,
            url.user == nil, url.password == nil, url.query == nil, url.fragment == nil
        else { throw .insecureRelayURL }
        switch scheme {
        case "https": return url
        case "http" where loopbackHosts.contains(host): return url
        default: throw .insecureRelayURL
        }
    }

    static let loopbackHosts: Set<String> = ["127.0.0.1", "::1", "[::1]", "localhost"]

    /// The value sent as `User-Agent` and the language sent as
    /// `Accept-Language` on every request. URLSession would otherwise send
    /// the app build, the CFNetwork and Darwin versions and the user's
    /// preferred languages, which together narrow down who is connecting.
    static let userAgent = "us2us"
    static let acceptLanguage = "en"

    /// The base configuration for both of ``RelayClient``'s sessions:
    /// Us2usKit's ephemeral defaults (no cookies, cache or credential
    /// storage) plus constant, non-identifying headers, TLS 1.2+ and no
    /// multipath (which would announce the cellular interface as well).
    public static func sessionConfiguration(minimumTLS: tls_protocol_version_t = .TLSv12) -> URLSessionConfiguration {
        let configuration = RelayClient.defaultConfiguration()
        configuration.httpAdditionalHeaders = [
            "User-Agent": userAgent,
            "Accept-Language": acceptLanguage,
        ]
        configuration.tlsMinimumSupportedProtocolVersion = minimumTLS
        configuration.multipathServiceType = .none
        configuration.httpShouldUsePipelining = false
        configuration.waitsForConnectivity = false
        configuration.timeoutIntervalForResource = 15 * 60
        return configuration
    }

    /// The relay client of the app. `RelayClient` already keeps sender-hiding
    /// requests (sealed and ephemeral sends, message proof-of-work, media
    /// download and delete) on a second `URLSession` with its own connection
    /// pool and TLS session cache (PROTOCOL.md §4.6 "Unlinkability"); both
    /// sessions get the configuration above.
    public static func makeRelayClient(baseURL: URL) throws -> RelayClient {
        RelayClient(baseURL: try validatedRelayURL(baseURL), configuration: sessionConfiguration())
    }
}
