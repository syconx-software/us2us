import Foundation
import Testing

@testable import Us2usKit

/// The answers of an echo responder, a small contract for live interop tests
/// shared with the Kotlin `us2us-bot` (`Echo` in android/cli): every message
/// is answered with one text. The bot and ``LiveEchoResponderTests`` answer
/// like this, so the Kotlin `LiveEchoBotTest` and ``LiveEchoBotTests`` can
/// drive either.
enum EchoContract {
    static func text(_ body: String) -> String { "echo: \(body)" }

    /// The SHA-256 of the decrypted attachment, lowercase hex.
    static func attachment(plaintextSHA256 digest: Data) -> String { "echo-attachment: \(digest.hexString)" }

    /// Coordinates with six decimals (about 0.1 m), then the place name if there is one.
    static func location(_ location: BRPLocation) -> String {
        // `String(format:)` without a locale always uses "." as the decimal separator.
        let coordinates = String(format: "%.6f,%.6f", location.latitude, location.longitude)
        return location.name.isEmpty ? "echo-location: \(coordinates)" : "echo-location: \(coordinates) \(location.name)"
    }

    static func settings(_ settings: BRPConversationSettings) -> String {
        let ghost = settings.ghost
        return "echo-settings: ghost=\(ghost.enabled ? "on" : "off"),timeout=\(ghost.timeoutSeconds),"
            + "wipe_contact=\(ghost.wipeContact),expire=\(settings.defaultExpireTimerSeconds)"
    }

    /// The reason as named in brp.proto, e.g. `USER_REQUEST`.
    static func wipe(_ wipe: BRPConversationWipe) -> String {
        let reason: String
        switch wipe.reason {
        case .ghostTimeout: reason = "GHOST_TIMEOUT"
        case .userRequest: reason = "USER_REQUEST"
        case .unspecified: reason = "REASON_UNSPECIFIED"
        case .UNRECOGNIZED: reason = "UNRECOGNIZED"
        }
        return "echo-wipe: \(reason)"
    }
}

/// `{"accountId", "username"}`, the state file the echo responders write
/// once registered (the Kotlin bot's `--state`).
struct EchoState: Codable, Sendable {
    let accountId: String
    let username: String

    /// The bot to talk to (`BOT_STATE`; `TEST_RUNNER_BOT_STATE` with xcodebuild).
    static let botFile = path("BOT_STATE")
    /// Where ``LiveEchoResponderTests`` announces itself (`ECHO_STATE`; `TEST_RUNNER_ECHO_STATE`).
    static let responderFile = path("ECHO_STATE")

    private static func path(_ variable: String) -> URL? {
        guard let value = ProcessInfo.processInfo.environment[variable], !value.isEmpty else { return nil }
        return URL(fileURLWithPath: value)
    }

    static func read(from url: URL) throws -> EchoState {
        try JSONDecoder().decode(EchoState.self, from: Data(contentsOf: url))
    }

    /// Atomically, so a harness polling for the file never reads half of it.
    func write(to url: URL) throws {
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try JSONEncoder().encode(self).write(to: url, options: .atomic)
    }
}

/// A test timed out waiting for something a peer should have sent.
struct LiveTimeout: Error, CustomStringConvertible {
    let description: String
}

extension Inbox {
    /// The first message so far (or to come) that matches `predicate`.
    func first(
        _ what: String, timeout: TimeInterval = 60, where predicate: (IncomingMessage) -> Bool
    ) async throws -> IncomingMessage {
        let deadline = Date().addingTimeInterval(timeout)
        while true {
            if let match = all.first(where: predicate) { return match }
            guard Date() < deadline else {
                throw LiveTimeout(description: "no \(what) within \(Int(timeout)) s; received: \(all.map(\.content.kind))")
            }
            try await Task.sleep(nanoseconds: 50_000_000)
        }
    }

    func text(_ body: String, from sender: AccountId) async throws -> IncomingMessage {
        try await first("\"\(body)\"") { $0.sender == sender && $0.content.textBody == body }
    }
}

extension BRPContent {
    /// The kind of message, for failure messages.
    var kind: String {
        switch body {
        case .data(let data): return "data.\(data.payload.map { "\($0)".prefix { $0 != "(" } } ?? "none")"
        case .control(let control): return "control.\(control.action.map { "\($0)".prefix { $0 != "(" } } ?? "none")"
        case .receipt: return "receipt"
        case .typing: return "typing"
        case .presence: return "presence"
        case .community: return "community"
        case .none: return "none"
        }
    }

    var isAccept: Bool {
        guard case .control(let control) = body, case .requestResponse(let response) = control.action else { return false }
        return response.type == .accept
    }

    var accessKeyUpdate: Data? {
        guard case .control(let control) = body, case .accessKey(let update) = control.action else { return nil }
        return update.accessKey
    }

    var profile: BRPProfileUpdate? {
        guard case .control(let control) = body, case .profile(let profile) = control.action else { return nil }
        return profile
    }

    func isDeliveryReceipt(for messageId: Data) -> Bool {
        guard case .receipt(let receipt) = body else { return false }
        return receipt.type == .delivery && receipt.messageIds.contains(messageId)
    }

    var isPresenceReply: Bool {
        guard case .presence(let presence) = body else { return false }
        return presence.state == .online && presence.reply
    }
}

@Suite("Echo contract shared with the Kotlin bot")
struct EchoContractTests {
    @Test("answers are formatted exactly like android/cli's Echo")
    func formats() {
        #expect(EchoContract.text("Merhaba 👋 dünya") == "echo: Merhaba 👋 dünya")
        #expect(EchoContract.attachment(plaintextSHA256: Data([0x00, 0xFF])) == "echo-attachment: 00ff")

        var location = BRPLocation()
        location.latitude = 41.0086
        location.longitude = -28.9802
        #expect(EchoContract.location(location) == "echo-location: 41.008600,-28.980200")
        location.latitude = 0.000001
        location.longitude = 180
        location.name = "Ayasofya, İstanbul"
        #expect(EchoContract.location(location) == "echo-location: 0.000001,180.000000 Ayasofya, İstanbul")

        var settings = BRPConversationSettings()
        #expect(EchoContract.settings(settings) == "echo-settings: ghost=off,timeout=0,wipe_contact=false,expire=0")
        settings.defaultExpireTimerSeconds = 30
        settings.ghost.enabled = true
        settings.ghost.timeoutSeconds = 60
        settings.ghost.wipeContact = true
        #expect(EchoContract.settings(settings) == "echo-settings: ghost=on,timeout=60,wipe_contact=true,expire=30")

        var wipe = BRPConversationWipe()
        wipe.reason = .ghostTimeout
        #expect(EchoContract.wipe(wipe) == "echo-wipe: GHOST_TIMEOUT")
        wipe.reason = .userRequest
        #expect(EchoContract.wipe(wipe) == "echo-wipe: USER_REQUEST")
    }

    @Test("the state file round-trips in the bot's format")
    func stateFile() throws {
        let directory = try TemporaryDirectory()
        let file = directory.file("nested/state.json")
        try EchoState(accountId: "0123abcd-4567-89ef-0123-456789abcdef", username: "echo_bot").write(to: file)
        let json = try JSONSerialization.jsonObject(with: Data(contentsOf: file)) as? [String: String]
        #expect(json == ["accountId": "0123abcd-4567-89ef-0123-456789abcdef", "username": "echo_bot"])
        #expect(try EchoState.read(from: file).username == "echo_bot")
    }
}
