import Foundation
import Us2usKit
import Us2usRuntime

/// The app's only network endpoint: the relay (PROTOCOL §4). Production
/// builds always use `https://relay.us2us.app` (Us2usKit's
/// `RelayClient.productionURL`); DEBUG builds accept `-U2RelayURL <url>` for
/// a local relay, through the same `NetworkPolicy` rule (https, or plain http
/// to a loopback address only).
enum RelayEndpoint {
    static let production: URL = RelayClient.productionURL

    /// The DEBUG override, if it passes the network policy.
    static func debugOverride(_ value: String?) -> URL? {
        guard let value, let url = URL(string: value) else { return nil }
        return try? NetworkPolicy.validatedRelayURL(url)
    }
}
