import Foundation
import Testing

@testable import Us2usKit

enum TLSProbe {
    /// `TLS_PROBE_URL` (with xcodebuild: `TEST_RUNNER_TLS_PROBE_URL`), served by
    /// `ios/scripts/tls-probe.py`.
    static let url: URL? = ProcessInfo.processInfo.environment["TLS_PROBE_URL"].flatMap(URL.init(string:))
}

/// PROTOCOL.md §4.6 "Unlinkability", measured: the probe server reports for
/// every request which TCP connection carried it and whether the connection
/// resumed a TLS session. Uses the same session factory as ``RelayClient``.
///
/// Measured on iOS 27 (with and without a trust delegate, ephemeral and
/// default configurations alike): connections are pooled per URLSession; TLS
/// 1.2 sessions are resumed within a URLSession and never by another one; TLS
/// 1.3 sessions were not resumed at all. The probe therefore speaks TLS 1.2.
@Suite(
    "Anonymous requests share no connection or TLS session with authenticated ones (§4.6)",
    .enabled(if: TLSProbe.url != nil, "run ios/scripts/tls-probe.py and set TEST_RUNNER_TLS_PROBE_URL"),
    .serialized)
struct TLSIsolationTests {
    private struct Answer: Decodable {
        let connection: Int
        let resumed: Bool
    }

    /// Accepts the probe's throwaway certificate, for the loopback host only.
    private final class TrustProbeCertificate: NSObject, URLSessionDelegate, Sendable {
        func urlSession(
            _ session: URLSession, didReceive challenge: URLAuthenticationChallenge
        ) async -> (URLSession.AuthChallengeDisposition, URLCredential?) {
            guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
                ["localhost", "127.0.0.1"].contains(challenge.protectionSpace.host), let trust = challenge.protectionSpace.serverTrust
            else { return (.performDefaultHandling, nil) }
            return (.useCredential, URLCredential(trust: trust))
        }
    }

    private func ask(_ session: URLSession, _ label: String, close: Bool = false) async throws -> Answer {
        var components = URLComponents(url: TLSProbe.url!, resolvingAgainstBaseURL: false)!
        components.path = "/v1/probe/" + label
        if close { components.queryItems = [URLQueryItem(name: "close", value: "1")] }
        let (data, _) = try await session.data(from: components.url!)
        return try JSONDecoder().decode(Answer.self, from: data)
    }

    private func sessions() -> (authenticated: URLSession, anonymous: URLSession) {
        RelayClient.makeSessions(configuration: RelayClient.defaultConfiguration(), delegate: TrustProbeCertificate())
    }

    @Test("connections are pooled per session only")
    func connections() async throws {
        let (authenticated, anonymous) = sessions()
        defer {
            authenticated.invalidateAndCancel()
            anonymous.invalidateAndCancel()
        }
        let first = try await ask(authenticated, "a1")
        let second = try await ask(authenticated, "a2")
        try #require(first.connection == second.connection, "the probe must see keep-alive within a session")
        let anonymousAnswer = try await ask(anonymous, "n1")
        #expect(anonymousAnswer.connection != first.connection)
        #expect(!anonymousAnswer.resumed)
        let authenticatedAgain = try await ask(authenticated, "a3")
        #expect(authenticatedAgain.connection == first.connection)
    }

    @Test("TLS sessions are resumed within a session only")
    func resumption() async throws {
        let (authenticated, anonymous) = sessions()
        defer {
            authenticated.invalidateAndCancel()
            anonymous.invalidateAndCancel()
        }
        // The authenticated session holds a resumable TLS session after this.
        _ = try await ask(authenticated, "a1", close: true)
        let resumed = try await ask(authenticated, "a2", close: true)
        try #require(resumed.resumed, "URLSession must resume TLS within a session (TLS 1.2), or the probe proves nothing")

        // The anonymous session's first handshake must be a full one.
        let anonymousFirst = try await ask(anonymous, "n1", close: true)
        #expect(!anonymousFirst.resumed, "an anonymous request resumed the authenticated session's TLS session")
        // It resumes only what it established itself.
        let anonymousSecond = try await ask(anonymous, "n2", close: true)
        #expect(anonymousSecond.resumed)

        // A fresh client after both: nothing carries over between URLSessions.
        let (other, _) = sessions()
        defer { other.invalidateAndCancel() }
        #expect(!(try await ask(other, "o1", close: true)).resumed)
    }
}
