import Foundation
import Testing

@testable import Us2usKit

@Suite("Profile updates (§3.3, §8.7)")
struct ContactProfileTests {
    let contact = AccountId(uuid: UUID())
    let alice = try! NormalizedUsername("alice_01")

    private func update(username: String, displayName: String = "Alice", presenceSharing: Bool = true)
        -> BRPProfileUpdate
    {
        var update = BRPProfileUpdate()
        update.username = username
        update.displayName = displayName
        update.presenceSharing = presenceSharing
        update.updatedAt = 1_760_000_000_000
        return update
    }

    @Test("presence sharing counts as false until the first ProfileUpdate (§8.6)")
    func defaults() {
        let profile = ContactProfile()
        #expect(!profile.presenceSharing)
        #expect(!profile.needsUsernameCheck)
    }

    @Test("every update replaces the whole profile; empty or malformed fields mean none")
    func completeReplacement() {
        var profile = ContactProfile().applying(update(username: "alice_01"))
        #expect(profile.username == alice)
        #expect(profile.displayName == "Alice")
        #expect(profile.presenceSharing)
        #expect(profile.updatedAt == 1_760_000_000_000)

        profile = profile.applying(update(username: "alice_01", displayName: "", presenceSharing: false))
        #expect(profile.displayName == nil)
        #expect(!profile.presenceSharing)
        #expect(profile.applying(update(username: "Alice_01")).username == nil)  // not normalized
        #expect(profile.applying(update(username: "a", displayName: String(repeating: "x", count: 65))).displayName == nil)
    }

    @Test("a username is a claim until the directory maps it to the sender")
    func usernameClaims() throws {
        var profile = ContactProfile().applying(update(username: "alice_01"))
        #expect(profile.usernameStatus == .unchecked)
        #expect(profile.needsUsernameCheck)

        let other = AccountId(uuid: UUID())
        #expect(profile.recordingUsernameCheck(alice, owner: other, contact: contact).usernameStatus == .mismatch)
        #expect(profile.recordingUsernameCheck(alice, owner: nil, contact: contact).usernameStatus == .mismatch)
        profile = profile.recordingUsernameCheck(alice, owner: contact, contact: contact)
        #expect(profile.usernameStatus == .verified)
        #expect(!profile.needsUsernameCheck)

        // Repeating the name keeps the result (no new lookup); a new name is a new claim.
        #expect(profile.applying(update(username: "alice_01")).usernameStatus == .verified)
        let renamed = profile.applying(update(username: "alice_new"))
        #expect(renamed.usernameStatus == .unchecked)
        // An answer for a name the contact no longer claims changes nothing.
        #expect(renamed.recordingUsernameCheck(alice, owner: contact, contact: contact) == renamed)

        let confirmed = ContactProfile().confirming(alice)
        #expect(confirmed.username == alice && confirmed.usernameStatus == .verified)
    }
}

@Suite("Delivery receipts (§8.4)")
struct DeliveryReceiptTests {
    let alice = AccountId(uuid: UUID())
    let bob = AccountId(uuid: UUID())

    private func message(_ content: BRPContent, from sender: AccountId) -> IncomingMessage {
        IncomingMessage(
            relayId: UUID().uuidString, serverTimestamp: 0, sender: sender, senderUsername: nil, content: content,
            identityChanged: false)
    }

    private func data(_ payload: BRPDataMessage.OneOf_Payload) -> BRPContent {
        BRPContent(data: BRPDataMessage(payload: payload))
    }

    @Test("only text, attachment, location and sticker from accepted contacts get one")
    func receiptedPayloads() {
        #expect(ReceiptPolicy.wantsDeliveryReceipt(for: .text("hi"), state: .accepted))
        #expect(ReceiptPolicy.wantsDeliveryReceipt(for: data(.attachment(BRPAttachment())), state: .accepted))
        #expect(ReceiptPolicy.wantsDeliveryReceipt(for: data(.location(BRPLocation())), state: .accepted))
        #expect(ReceiptPolicy.wantsDeliveryReceipt(for: data(.sticker(BRPSticker())), state: .accepted))
        #expect(!ReceiptPolicy.wantsDeliveryReceipt(for: data(.reaction(BRPReaction())), state: .accepted))
        #expect(!ReceiptPolicy.wantsDeliveryReceipt(for: data(.delete(BRPDelete())), state: .accepted))
        #expect(!ReceiptPolicy.wantsDeliveryReceipt(for: data(.liveLocation(BRPLiveLocation())), state: .accepted))
        #expect(!ReceiptPolicy.wantsDeliveryReceipt(for: .accessKeyUpdate(.id(1)), state: .accepted))
        #expect(!ReceiptPolicy.wantsDeliveryReceipt(for: PresencePolicy.online(), state: .accepted))
        for state in [ConversationRequestState.incomingRequest, .outgoingRequest, .blocked] {
            #expect(!ReceiptPolicy.wantsDeliveryReceipt(for: .text("hi"), state: state))
        }
    }

    @Test("receipts are batched per contact, at most 1000 ids each")
    func batching() throws {
        var batch = DeliveryReceiptBatch()
        let texts = (0..<1001).map { _ in BRPContent.text("m") }
        for text in texts { batch.add(message(text, from: alice), state: .accepted) }
        batch.add(message(.text("b"), from: bob), state: .accepted)
        batch.add(message(.text("request"), from: bob), state: .incomingRequest)
        batch.add(message(data(.reaction(BRPReaction())), from: bob), state: .accepted)

        let drained = batch.drain()
        #expect(batch.isEmpty)
        #expect(drained.map(\.contact) == [alice, bob])
        let counts = drained[0].receipts.map { content -> Int in
            guard case .receipt(let receipt) = content.body, receipt.type == .delivery else { return -1 }
            return receipt.messageIds.count
        }
        #expect(counts == [1000, 1])
        guard case .receipt(let first) = drained[0].receipts[0].body, case .data(let data) = texts[0].body else {
            Issue.record("not a receipt")
            return
        }
        #expect(first.messageIds.first == data.messageID)
        #expect(drained[1].receipts.count == 1)

        // What failed to send comes back first.
        batch.add(message(.text("later"), from: alice), state: .accepted)
        batch.requeue(drained[1].receipts, for: bob)
        #expect(batch.drain().map(\.contact) == [bob, alice])
    }

    @Test("a receipt with more than 1000 ids is refused before sending")
    func sendLimit() throws {
        let ids = (0..<1001).map { _ in RandomId.make() }
        #expect(throws: ContentValidationError.tooLarge("receipt.message_ids")) {
            try BRPContent(receipt: .read, messageIds: ids).validateForSending()
        }
        try BRPContent(receipt: .read, messageIds: Array(ids.prefix(1000))).validateForSending()
        #expect(ReceiptPolicy.receipts(.read, messageIds: ids).count == 2)
        #expect(ReceiptPolicy.receipts(.read, messageIds: []).isEmpty)
    }

    @Test("SAVED covers permanent photos, videos and files only")
    func savable() {
        func attachment(_ kind: BRPAttachment.Kind, viewOnce: Bool = false, timer: UInt32 = 0) -> BRPDataMessage {
            var attachment = BRPAttachment()
            attachment.kind = kind
            var data = BRPDataMessage(payload: .attachment(attachment))
            data.viewOnce = viewOnce
            data.expireTimerSeconds = timer
            return data
        }
        for kind in [BRPAttachment.Kind.image, .video, .file] {
            #expect(ReceiptPolicy.isSavable(attachment(kind)))
            // View-once and disappearing media cannot be saved, so never SAVED.
            #expect(!ReceiptPolicy.isSavable(attachment(kind, viewOnce: true)))
            #expect(!ReceiptPolicy.isSavable(attachment(kind, timer: 3600)))
        }
        for kind in [BRPAttachment.Kind.voice, .stickerImage, .unspecified, .UNRECOGNIZED(9)] {
            #expect(!ReceiptPolicy.isSavable(attachment(kind)))
        }
        #expect(!ReceiptPolicy.isSavable(BRPDataMessage(payload: .text(BRPText()))))
        #expect(!ReceiptPolicy.isSavable(BRPDataMessage(payload: .sticker(BRPSticker()))))
        #expect(!ReceiptPolicy.isSavable(BRPDataMessage(payload: .location(BRPLocation()))))
    }

    @Test("SAVED goes to accepted contacts only, whatever the read-receipt setting")
    func savedReceiptRecipients() throws {
        #expect(MessageRequestPolicy.maySendSavedReceipts(state: .accepted))
        for state in [ConversationRequestState.incomingRequest, .outgoingRequest, .blocked] {
            #expect(!MessageRequestPolicy.maySendSavedReceipts(state: state))
        }
        let content = BRPContent(receipt: .saved, messageIds: [RandomId.make()])
        try content.validateForSending()
        // Type 4 on the wire: builds that predate it read an unknown type and ignore the receipt.
        let bytes: Data = try content.serializedBytes()
        let decoded = try BRPContent(serializedBytes: bytes)
        guard case .receipt(let receipt) = decoded.body else {
            Issue.record("not a receipt")
            return
        }
        #expect(receipt.type == .saved)
        #expect(receipt.type.rawValue == 4)
    }
}
