import Foundation
import Testing

@testable import Us2usKit

/// docs/COMMUNITIES.md, Extension v2: the four kinds of a `CommunityMessage`
/// and their structural validation, against spec/test-vectors/communities-content.json.
@Suite("Communities v2: content kinds and shared vectors")
struct CommunityContentTests {
    struct File: Decodable {
        let communityId: String
        let revision: UInt64
        let vectors: [Vector]
    }

    struct Vector: Decodable {
        let `case`: String
        let reason: String
        let valid: Bool
        let communityMessage: String
        let content: String
    }

    static let vectorURL = RepositoryPaths.testVectors.appendingPathComponent("communities-content.json")
    static let community = AccountId(string: "11111111-2222-4333-8444-555555555555")!
    static let m2 = Data(hex: "00000000000040008000000000000002")

    static var image: BRPAttachment {
        var attachment = BRPAttachment()
        attachment.kind = .image
        attachment.mediaID = .id(0x23)
        attachment.key = Data(repeating: 0x06, count: 64)
        attachment.digest = Data(repeating: 0x07, count: 32)
        attachment.size = 123_456
        attachment.contentType = "image/jpeg"
        attachment.width = 1920
        attachment.height = 1080
        attachment.thumbnail = Data(hex: "ffd8ffd9")
        return attachment
    }

    /// The message README.md names for `name`.
    static func build(_ name: String) -> BRPCommunityMessage? {
        var message = BRPCommunityMessage()
        message.communityID = community.bytes
        message.revision = 2
        func receipt(_ type: BRPCommunityReceipt.TypeEnum, _ ids: [Data]) -> BRPCommunityReceipt {
            var receipt = BRPCommunityReceipt()
            receipt.type = type
            receipt.messageIds = ids
            return receipt
        }
        switch name {
        case "text":
            message.messageID = .id(0x21); message.sentAt = 1_760_000_000_000; message.text = "Merhaba topluluk 👋"
        case "media_caption":
            message.messageID = .id(0x22); message.sentAt = 1_760_000_000_001; message.text = "Gün batımı"
            message.attachment = image
        case "media_file_no_caption":
            message.messageID = .id(0x24); message.sentAt = 1_760_000_000_002
            var file = BRPAttachment()
            file.kind = .file; file.mediaID = .id(0x25); file.key = Data(repeating: 0x08, count: 64)
            file.digest = Data(repeating: 0x09, count: 32); file.size = 5000
            file.contentType = "application/pdf"; file.fileName = "plan.pdf"
            message.attachment = file
        case "receipt_read":
            message.messageID = .id(0x26); message.sentAt = 1_760_000_000_003
            message.receipt = receipt(.read, [.id(0x21), .id(0x22)])
        case "receipt_delivery":
            message.messageID = .id(0x27); message.sentAt = 1_760_000_000_004
            message.receipt = receipt(.delivery, [.id(0x24)])
        case "delete":
            message.messageID = .id(0x28); message.sentAt = 1_760_000_000_005
            message.deleteTargetMessageID = .id(0x22); message.deleteTargetSenderID = m2
        case "invalid_empty":
            message.messageID = .id(0x29); message.sentAt = 1_760_000_000_006
        case "invalid_whitespace_text":
            message.messageID = .id(0x2a); message.sentAt = 1_760_000_000_007; message.text = " \n\u{3000}"
        case "invalid_receipt_with_text":
            message.messageID = .id(0x2b); message.sentAt = 1_760_000_000_008; message.text = "x"
            message.receipt = receipt(.read, [.id(0x21)])
        case "invalid_receipt_no_ids":
            message.messageID = .id(0x2c); message.sentAt = 1_760_000_000_009
            message.receipt = receipt(.delivery, [])
        case "invalid_receipt_short_id":
            message.messageID = .id(0x2d); message.sentAt = 1_760_000_000_010
            message.receipt = receipt(.read, [.id(0x21), Data(repeating: 0x22, count: 15)])
        case "invalid_receipt_unknown_type":
            message.messageID = .id(0x2e); message.sentAt = 1_760_000_000_011
            message.receipt = receipt(.UNRECOGNIZED(3), [.id(0x21)])
        case "invalid_media_delete_token":
            message.messageID = .id(0x2f); message.sentAt = 1_760_000_000_012
            var attachment = image
            attachment.deleteToken = Data(repeating: 0x05, count: 32)
            message.attachment = attachment
        case "invalid_media_attachment_caption":
            message.messageID = .id(0x30); message.sentAt = 1_760_000_000_013
            var attachment = image
            attachment.caption = "Foto"
            message.attachment = attachment
        case "invalid_media_voice":
            message.messageID = .id(0x31); message.sentAt = 1_760_000_000_014
            var attachment = image
            attachment.kind = .voice; attachment.contentType = "audio/mp4"; attachment.thumbnail = Data()
            message.attachment = attachment
        case "invalid_media_and_receipt":
            message.messageID = .id(0x32); message.sentAt = 1_760_000_000_015
            message.attachment = image
            message.receipt = receipt(.delivery, [.id(0x21)])
        case "invalid_delete_without_sender":
            message.messageID = .id(0x33); message.sentAt = 1_760_000_000_016
            message.deleteTargetMessageID = .id(0x22)
        case "invalid_delete_short_target":
            message.messageID = .id(0x34); message.sentAt = 1_760_000_000_017
            message.deleteTargetMessageID = Data(repeating: 0x22, count: 15); message.deleteTargetSenderID = m2
        case "invalid_delete_with_text":
            message.messageID = .id(0x35); message.sentAt = 1_760_000_000_018; message.text = "silindi"
            message.deleteTargetMessageID = .id(0x22); message.deleteTargetSenderID = m2
        case "invalid_zero_sent_at":
            message.messageID = .id(0x36); message.sentAt = 0; message.text = "Merhaba"
        default:
            return nil
        }
        return message
    }

    static let expectedKinds: [String: CommunityContentKind] = [
        "text": .text, "media_caption": .media, "media_file_no_caption": .media,
        "receipt_read": .receipt, "receipt_delivery": .receipt, "delete": .delete,
    ]

    @Test("every vector builds to identical bytes, parses equal and validates as listed")
    func sharedVectors() throws {
        let file = try JSONDecoder().decode(File.self, from: Data(contentsOf: Self.vectorURL))
        #expect(file.vectors.count == 20)
        #expect(Data(hex: file.communityId) == Self.community.bytes && file.revision == 2)
        for vector in file.vectors {
            let built = try #require(Self.build(vector.case), "unknown case \(vector.case)")
            let messageBytes = Data(hex: vector.communityMessage)
            let contentBytes = Data(hex: vector.content)
            #expect(try built.serializedBytes() == messageBytes, "\(vector.case): CommunityMessage bytes")
            var wrapper = BRPContent()
            wrapper.body = .community(built)
            #expect(try wrapper.serializedBytes() == contentBytes, "\(vector.case): Content bytes")

            let parsed = try BRPCommunityMessage(serializedBytes: messageBytes)
            let parsedContent = try BRPContent(serializedBytes: contentBytes)
            #expect(parsed == built, "\(vector.case): parsed message")
            #expect(parsedContent.community == parsed, "\(vector.case): parsed content")

            let kind = try? parsed.validatedKind()
            #expect((kind != nil) == vector.valid, "\(vector.case): \(vector.reason)")
            #expect(((try? parsedContent.validateStructure()) != nil) == vector.valid, "\(vector.case): Content validation")
            if vector.valid { #expect(kind == Self.expectedKinds[vector.case], "\(vector.case): kind") }
        }
    }

    @Test("v3 shared bytes validate voice limits, stickers, fallback and mixed-kind rejection")
    func v3Vectors() throws {
        let data = try Data(contentsOf: RepositoryPaths.testVectors.appendingPathComponent("communities-v3.json"))
        let json = try #require(JSONSerialization.jsonObject(with: data) as? [String: Any])
        let vectors = try #require(json["vectors"] as? [[String: Any]])
        #expect(vectors.count == 27)
        for vector in vectors {
            let name = try #require(vector["case"] as? String)
            let message = try BRPCommunityMessage(serializedBytes: Data(hex: try #require(vector["communityMessage"] as? String)))
            let content = try BRPContent(serializedBytes: Data(hex: try #require(vector["content"] as? String)))
            #expect(content.community == message)
            let expected = vector["kind"] as? String
            #expect((try? message.validatedKind()) == expected.map { $0 == "sticker" ? .sticker : .media }, "\(name)")
            #expect(((try? content.validateStructure()) != nil) == (expected != nil), "\(name)")
            if expected == "sticker" { #expect(message.text == message.sticker.emoji) }
            if name == "voice" { #expect(message.attachment.communityPointer.waveform == Data([0, 128, 255])) }
        }
    }

    @Test("builders produce the kinds they name; the pointer never carries the delete token")
    func builders() throws {
        var uploaded = Self.image
        uploaded.deleteToken = Data(repeating: 1, count: 32)
        uploaded.caption = "old"
        uploaded.waveform = Data([1, 2])
        let media = BRPCommunityMessage.media(uploaded, caption: "  \n", community: Self.community, revision: 3)
        #expect(try media.validatedKind() == .media)
        #expect(media.attachment.deleteToken.isEmpty && media.attachment.caption.isEmpty && media.text.isEmpty)
        #expect(media.messageID.count == 16 && media.sentAt > 0)

        let captioned = BRPCommunityMessage.media(Self.image, caption: " Deniz ", community: Self.community, revision: 3)
        #expect(captioned.text == "Deniz")

        let text = BRPCommunityMessage.text("Selam", community: Self.community, revision: 3)
        #expect(try text.validatedKind() == .text)

        let receipt = BRPCommunityMessage.receipt(.read, for: [.id(1), .id(1), .id(2)], community: Self.community, revision: 3)
        #expect(try receipt.validatedKind() == .receipt)
        #expect(receipt.distinctReceiptIds == [.id(1), .id(2)])

        let sender = AccountId(bytes: Self.m2)!
        let delete = BRPCommunityMessage.delete(target: .id(9), sender: sender, community: Self.community, revision: 3)
        #expect(try delete.validatedKind() == .delete)
        #expect(delete.messageID != Data.id(9))
    }

    @Test("receipt id limits: 1000 distinct ids pass, 1001 do not; repeats count once")
    func receiptLimits() throws {
        let thousand = (0..<1000).map { index -> Data in
            var id = Data(repeating: 0, count: 16)
            id[0] = UInt8(index >> 8); id[1] = UInt8(index & 0xFF)
            return id
        }
        let ok = BRPCommunityMessage.receipt(.delivery, for: thousand + [thousand[0]], community: Self.community, revision: 1)
        #expect(try ok.validatedKind() == .receipt)
        var extra = Data(repeating: 0xFF, count: 16)
        extra[15] = 1
        let tooMany = BRPCommunityMessage.receipt(.delivery, for: thousand + [extra], community: Self.community, revision: 1)
        #expect(throws: ContentValidationError.self) { try tooMany.validatedKind() }
    }

    @Test("media limits: thumbnail 10 KiB, names 255 bytes, sticker and unknown kinds refused")
    func mediaLimits() throws {
        func message(_ change: (inout BRPAttachment) -> Void) -> BRPCommunityMessage {
            var attachment = Self.image
            change(&attachment)
            return BRPCommunityMessage.media(attachment, caption: "", community: Self.community, revision: 1)
        }
        #expect(throws: Never.self) { try message { $0.thumbnail = Data(count: 10_240) }.validatedKind() }
        #expect(throws: ContentValidationError.self) { try message { $0.thumbnail = Data(count: 10_241) }.validatedKind() }
        #expect(throws: ContentValidationError.self) { try message { $0.fileName = String(repeating: "a", count: 256) }.validatedKind() }
        #expect(throws: ContentValidationError.self) { try message { $0.contentType = String(repeating: "a", count: 256) }.validatedKind() }
        #expect(throws: ContentValidationError.self) { try message { $0.kind = .stickerImage }.validatedKind() }
        #expect(throws: ContentValidationError.self) { try message { $0.kind = .UNRECOGNIZED(9) }.validatedKind() }
        #expect(throws: ContentValidationError.self) { try message { $0.mediaID = Data(count: 15) }.validatedKind() }
        // Caption 8192 bytes passes; 8193 does not.
        var long = message { _ in }
        long.text = String(repeating: "a", count: 8192)
        #expect(throws: Never.self) { try long.validatedKind() }
        long.text += "a"
        #expect(throws: ContentValidationError.self) { try long.validatedKind() }
    }

    @Test("the 1:1 attachment rules are unchanged")
    func directAttachmentRulesUnchanged() throws {
        var data = BRPDataMessage(payload: .attachment(Self.image), messageId: .id(1))
        // A 1:1 attachment still needs its 32-byte delete token.
        #expect(throws: ContentValidationError.self) { try BRPContent(data: data).validateStructure() }
        var withToken = Self.image
        withToken.deleteToken = Data(repeating: 5, count: 32)
        data.payload = .attachment(withToken)
        try BRPContent(data: data).validateStructure()
    }
}
