import Foundation
import SwiftProtobuf
import Testing

@testable import Us2usKit

@Suite("Content padding (§5.1)")
struct ContentPaddingTests {
    @Test(arguments: [(0, 160), (1, 160), (158, 160), (159, 160), (160, 320), (161, 320), (319, 320), (320, 480)])
    func paddedLength(_ length: Int, _ expected: Int) throws {
        let plaintext = Data.pattern(length)
        let padded = ContentPadding.pad(plaintext)
        #expect(padded.count == expected)
        #expect(padded[length] == 0x80)
        #expect(padded[(length + 1)...].allSatisfy { $0 == 0 })
        #expect(try ContentPadding.unpad(padded) == plaintext)
    }

    @Test("plaintext ending in 0x00 or 0x80 survives")
    func trailingBytes() throws {
        for tail: UInt8 in [0x00, 0x80] {
            let plaintext = Data([1, 2, tail])
            #expect(try ContentPadding.unpad(ContentPadding.pad(plaintext)) == plaintext)
        }
    }

    @Test(
        "anything but zeros after a single 0x80 is a failure",
        arguments: [
            Data(), Data(count: 160), Data([0x01, 0x00]), Data([0x80, 0x01]), Data([0x7F]),
        ])
    func malformed(_ padded: Data) {
        #expect(throws: ContentPaddingError.malformedPadding) { try ContentPadding.unpad(padded) }
    }
}

@Suite("Content rules")
struct ContentRulesTests {
    @Test func textBuilderFillsDataMessage() {
        let content = BRPContent.text("Merhaba", expireTimerSeconds: 60)
        guard case .data(let data) = content.body else {
            Issue.record("not data")
            return
        }
        #expect(data.messageID.count == 16)
        #expect(data.expireTimerSeconds == 60)
        #expect(data.sentAt > 1_700_000_000_000)
        #expect(content.textBody == "Merhaba")
    }

    @Test func sendingLimits() throws {
        try BRPContent.text(String(repeating: "a", count: ContentLimits.maxTextBytes)).validateForSending()
        // "ş" is two UTF-8 bytes: the limit is in bytes, not characters.
        #expect(throws: ContentValidationError.tooLarge("text.body")) {
            try BRPContent.text(String(repeating: "ş", count: 4097)).validateForSending()
        }
        var quote = BRPQuote()
        quote.targetMessageID = .id(1)
        quote.previewText = String(repeating: "x", count: 201)
        #expect(throws: ContentValidationError.tooLarge("quote.preview_text")) {
            try BRPContent.text("hi", quote: quote).validateForSending()
        }
    }

    @Test func structuralChecks() {
        var reaction = BRPReaction()
        reaction.targetMessageID = Data(count: 15)
        reaction.emoji = "👍"
        let content = BRPContent(data: BRPDataMessage(payload: .reaction(reaction)))
        #expect(throws: ContentValidationError.malformedField("reaction.target_message_id")) {
            try content.validateStructure()
        }

        var ghost = BRPGhostMode()
        ghost.enabled = true
        ghost.timeoutSeconds = 30
        var settings = BRPConversationSettings()
        settings.ghost = ghost
        #expect(throws: ContentValidationError.malformedField("settings.ghost.timeout_seconds")) {
            try BRPContent(control: .settings(settings)).validateStructure()
        }

        #expect(throws: ContentValidationError.malformedField("access_key.access_key")) {
            try BRPContent.accessKeyUpdate(Data(count: 8)).validateStructure()
        }
    }

    @Test("unknown future payloads pass structural validation")
    func forwardCompatible() throws {
        var known = BRPDataMessage()
        known.messageID = .id(1)
        // Field 99, wire type 2, as a newer client might send: tag 0x9A 0x06, length 1, one byte.
        let bytes: Data = try known.serializedBytes() + Data([0x9A, 0x06, 0x01, 0x00])
        let content = BRPContent(data: try BRPDataMessage(serializedBytes: bytes))
        #expect(content.data.payload == nil)
        try content.validateStructure()
    }

    @Test("settings: last writer wins, ties go to the smaller account id")
    func settingsResolution() throws {
        let a = try #require(AccountId(bytes: .id(1)))
        let b = try #require(AccountId(bytes: .id(2)))
        var older = BRPConversationSettings()
        older.updatedAt = 1
        older.defaultExpireTimerSeconds = 10
        var newer = BRPConversationSettings()
        newer.updatedAt = 2
        newer.defaultExpireTimerSeconds = 20
        #expect(BRPConversationSettings.resolve((older, a), (newer, b)) == newer)
        #expect(BRPConversationSettings.resolve((newer, b), (older, a)) == newer)

        var tie = newer
        tie.defaultExpireTimerSeconds = 30
        #expect(BRPConversationSettings.resolve((tie, b), (newer, a)) == newer)
        #expect(BRPConversationSettings.resolve((newer, a), (tie, b)) == newer)
    }
}
