import Foundation
import LibSignalClient
import Testing
import Us2usKit
import Us2usStorage

@testable import Us2usRuntime

extension CommunityFixture {
    /// A prepared photo in the staging folder, as `AppRuntime` hands it over.
    func prepared(_ bytes: Data, kind: AttachmentItem.Kind = .image, contentType: String = "image/jpeg", fileName: String? = nil) throws -> PreparedMedia {
        let url = storage.makeStagingURL()
        try bytes.write(to: url)
        return PreparedMedia(
            file: url, kind: kind, contentType: contentType, fileName: fileName, size: UInt64(bytes.count), width: 40, height: 30,
            thumbnail: Data([0xFF, 0xD8, 0xFF, 0xD9]))
    }

    /// A photo another member uploaded, and the pointer every member gets.
    func uploadedByPeer(_ bytes: Data, in state: CommunityState) throws -> (BRPCommunityMessage, Data) {
        let (blob, info) = try AttachmentCipher.encrypt(bytes)
        let mediaId = relay.putMedia(blob)
        var attachment = BRPAttachment()
        attachment.kind = .image
        attachment.mediaID = mediaId
        attachment.key = info.key
        attachment.digest = info.digest
        attachment.size = info.plaintextSize
        attachment.contentType = "image/jpeg"
        attachment.thumbnail = Data([0xFF, 0xD8, 0xFF, 0xD9])
        return (BRPCommunityMessage.media(attachment, caption: "", community: state.id, revision: state.revision), mediaId)
    }

    func items(_ id: AccountId) throws -> [CommunityTextItem] { try storage.read { try $0.communities.messages(id) } }

    func recipients(_ message: AccountId, in id: AccountId) throws -> [AccountId: CommunityRecipientState] {
        let rows = try storage.read { try $0.communities.recipients(community: id, messageId: message) }
        return Dictionary(uniqueKeysWithValues: rows.map { ($0.recipient, $0.state) })
    }
}

/// Members in the order the outbox sends to them (raw account id bytes).
private func sendOrder(_ peers: [CommunityPeer]) -> [CommunityPeer] {
    peers.sorted { $0.account.bytes.lexicographicallyPrecedes($1.account.bytes) }
}

@Suite("Communities v2: media, receipts and delete for everyone against a fake relay")
struct CommunityV2EngineTests {
    static let photo = Data((0..<5000).map { UInt8($0 % 251) })

    // MARK: Media

    @Test("own media is encrypted once, uploaded once; every member gets the pointer without the delete token")
    func mediaUploadedOnce() async throws {
        let fixture = try CommunityFixture()
        let alice = CommunityPeer(), carol = CommunityPeer()
        for peer in [alice, carol] { fixture.relay.register(peer.account, identityKey: peer.member.identityKey, bundle: try peer.bundle()) }
        let state = try fixture.ownCommunity(members: [alice.member, carol.member])
        let prepared = try fixture.prepared(Self.photo)
        let id = try await fixture.engine.storeMedia(prepared, caption: "  Gün batımı ", messageId: nil, to: state.id)
        // The plaintext is now an encrypted item; the bubble can show at once.
        #expect(!FileManager.default.fileExists(atPath: prepared.file.path))
        #expect(try fixture.items(state.id).first?.status == .sending)
        #expect(await fixture.engine.deliverMedia(id, in: state.id) == nil)
        #expect(fixture.relay.uploads == 1)

        let toAlice = try fixture.opened(by: alice), toCarol = try fixture.opened(by: carol)
        #expect(toAlice.count == 1 && toCarol.count == 1)
        let pointer = toAlice[0].attachment
        #expect(try toAlice[0].validatedKind() == .media && toAlice[0].text == "Gün batımı")
        #expect(pointer.deleteToken.isEmpty && pointer.caption.isEmpty)
        #expect(pointer.mediaID == toCarol[0].attachment.mediaID && pointer.key == toCarol[0].attachment.key)
        #expect(pointer.thumbnail.count <= 10_240)
        let blob = try #require(fixture.relay.blob(pointer.mediaID))
        #expect(try AttachmentCipher.decrypt(blob, key: pointer.key, digest: pointer.digest, size: pointer.size) == Self.photo)

        let item = try #require(try fixture.items(state.id).first)
        #expect(item.status == .sent && item.kind == .media && item.attachment?.state == .uploaded && item.attachment?.hasFile == true)
        #expect(try await fixture.engine.mediaData(sender: fixture.account.accountId, messageId: id, in: state.id) == Self.photo)
        // A later pass has nothing to resume: the blob is never uploaded twice.
        _ = try await fixture.engine.sync()
        #expect(fixture.relay.uploads == 1)
    }

    @Test("a received photo is a pointer: downloaded with progress, verified, never deleted by a recipient; DELIVERY goes to its sender")
    func receiveDownloadAndDeliver() async throws {
        let fixture = try CommunityFixture()
        let alice = CommunityPeer()
        fixture.relay.register(alice.account, identityKey: alice.member.identityKey, bundle: try alice.bundle())
        let state = try fixture.community(owner: alice, members: [fixture.me])
        let (message, mediaId) = try fixture.uploadedByPeer(Self.photo, in: state)
        try fixture.deliver(message, from: alice, in: state)
        _ = try await fixture.engine.sync()
        let item = try #require(try fixture.items(state.id).first)
        #expect(item.kind == .media && item.attachment?.state == .remote && item.attachment?.hasFile == false && !item.isDisplayed)

        let receipts = try fixture.opened(by: alice)
        #expect(receipts.count == 1)
        #expect(receipts.first?.receipt.type == .delivery && receipts.first?.receipt.messageIds == [message.messageID])
        // The next pass owes nothing more.
        _ = try await fixture.engine.sync()
        #expect(try fixture.opened(by: alice).isEmpty)

        let fractions = LockedState<[Double]>([])
        try await fixture.engine.download(sender: alice.account, messageId: item.messageId, in: state.id) { value in
            fractions.withLock { $0.append(value) }
        }
        #expect(try await fixture.engine.mediaData(sender: alice.account, messageId: item.messageId, in: state.id) == Self.photo)
        #expect(fractions.withLock { $0.last } == 1)
        #expect(fixture.relay.count("DELETE /v1/media") == 0 && fixture.relay.blob(mediaId) != nil)
    }

    @Test("a tampered blob fails its check; a missing one is no longer available")
    func tamperedAndMissingBlobs() async throws {
        let fixture = try CommunityFixture()
        let alice = CommunityPeer()
        fixture.relay.register(alice.account, identityKey: alice.member.identityKey, bundle: try alice.bundle())
        let state = try fixture.community(owner: alice, members: [fixture.me])
        var (tampered, _) = try fixture.uploadedByPeer(Self.photo, in: state)
        tampered.attachment.digest = Data(repeating: 1, count: 32)
        let (missing, _) = try fixture.uploadedByPeer(Self.photo, in: state)
        var gone = missing
        gone.attachment.mediaID = RandomId.make()
        try fixture.deliver(tampered, from: alice, in: state)
        try fixture.deliver(gone, from: alice, in: state)
        _ = try await fixture.engine.sync()
        for item in try fixture.items(state.id) {
            try await fixture.engine.download(sender: item.sender, messageId: item.messageId, in: state.id) { _ in }
        }
        #expect(try fixture.items(state.id).map { $0.attachment?.state } == [.failed, .unavailable])
    }

    @Test("every invalid kind combination is dropped and acknowledged; nothing is stored or sent back")
    func invalidKindsDropped() async throws {
        let fixture = try CommunityFixture()
        let alice = CommunityPeer()
        fixture.relay.register(alice.account, identityKey: alice.member.identityKey, bundle: try alice.bundle())
        let state = try fixture.community(owner: alice, members: [fixture.me])
        var empty = BRPCommunityMessage.text("x", community: state.id, revision: state.revision)
        empty.text = ""
        var whitespace = BRPCommunityMessage.text(" \n\u{3000}", community: state.id, revision: state.revision)
        whitespace.sentAt = 5
        var tokened = try fixture.uploadedByPeer(Self.photo, in: state).0
        tokened.attachment.deleteToken = Data(repeating: 5, count: 32)
        var mixed = BRPCommunityMessage.receipt(.read, for: [RandomId.make()], community: state.id, revision: state.revision)
        mixed.text = "x"
        var zero = BRPCommunityMessage.text("Merhaba", community: state.id, revision: state.revision)
        zero.sentAt = 0
        let ids = try [empty, whitespace, tokened, mixed, zero].map { try fixture.deliver($0, from: alice, in: state) }
        _ = try await fixture.engine.sync()
        #expect(Set(fixture.relay.acked) == Set(ids))
        #expect(try fixture.items(state.id).isEmpty)
        #expect(try fixture.opened(by: alice).isEmpty)
    }

    // MARK: Receipts

    @Test("receipts apply only from recipients to own messages; aggregate and per-member info")
    func receiptsAggregateAndInfo() async throws {
        let fixture = try CommunityFixture()
        let alice = CommunityPeer(), carol = CommunityPeer()
        for peer in [alice, carol] { fixture.relay.register(peer.account, identityKey: peer.member.identityKey, bundle: try peer.bundle()) }
        let state = try fixture.ownCommunity(members: [alice.member, carol.member])
        _ = try await fixture.engine.send("herkese", to: state.id)
        #expect(await fixture.engine.flushNow() == nil)
        let id = try #require(try fixture.items(state.id).first?.messageId)
        func summary() throws -> CommunityReceiptSummary? { try fixture.items(state.id).first { $0.messageId == id }?.receipts }
        #expect(try summary() == CommunityReceiptSummary(accepted: 2, delivered: 0, read: 0, pending: 0))

        try fixture.deliver(.receipt(.delivery, for: [id.bytes], community: state.id, revision: state.revision), from: alice, in: state)
        // A READ for a message this phone did not send changes nothing.
        try fixture.deliver(.receipt(.read, for: [RandomId.make()], community: state.id, revision: state.revision), from: carol, in: state)
        _ = try await fixture.engine.sync()
        #expect(try summary()?.delivered == 1 && summary()?.deliveredToAll == false)

        try fixture.deliver(.receipt(.read, for: [id.bytes], community: state.id, revision: state.revision), from: carol, in: state)
        _ = try await fixture.engine.sync()
        #expect(try summary()?.deliveredToAll == true && summary()?.readByAll == false)
        try fixture.deliver(.receipt(.read, for: [id.bytes, id.bytes], community: state.id, revision: state.revision), from: alice, in: state)
        _ = try await fixture.engine.sync()
        #expect(try summary()?.readByAll == true)

        let info = try await fixture.engine.recipients(of: id, in: state.id)
        #expect(Set(info.map(\.recipient)) == [alice.account, carol.account])
        #expect(info.allSatisfy { $0.state == .accepted && $0.deliveredAt != nil && $0.readAt != nil })
        // Receipts get no receipt back.
        #expect(try fixture.opened(by: alice).count == 1)
    }

    @Test("a covered conversation keeps incoming history but sends READ only after it is visible again")
    @MainActor
    func pickerCoverageDefersReadReceipts() async throws {
        let fixture = try CommunityFixture()
        let alice = CommunityPeer()
        fixture.relay.register(alice.account, identityKey: alice.member.identityKey, bundle: try alice.bundle())
        let state = try fixture.community(owner: alice, members: [fixture.me])
        let service = CommunityService(
            backend: fixture.backend, storage: fixture.storage, pollInterval: .seconds(3600), readCoalescing: .milliseconds(20))
        defer { service.stop() }
        await service.refresh()
        try await service.open(state.id)
        service.coverConversation(state.id)
        let incoming = BRPCommunityMessage.text("seçici açıkken", community: state.id, revision: state.revision)
        try fixture.deliver(incoming, from: alice, in: state)
        await service.refresh()
        #expect(service.selected == state.id)
        #expect(service.messages.count == 1)
        await settle(60)
        #expect(try fixture.items(state.id).allSatisfy { !$0.isDisplayed })
        #expect(try fixture.opened(by: alice).allSatisfy { $0.receipt.type != .read })
        try await service.open(state.id)
        var received: [BRPCommunityMessage] = []
        try await eventually("READ after the picker dismisses") {
            received += try fixture.opened(by: alice)
            return received.contains { $0.receipt.type == .read && $0.receipt.messageIds == [incoming.messageID] }
        }
        #expect(try fixture.items(state.id).allSatisfy(\.isDisplayed))
    }

    @Test("READ follows the read-receipt setting: off sends none, then or later; on, one per message and sender")
    @MainActor
    func readReceiptSetting() async throws {
        let fixture = try CommunityFixture()
        let alice = CommunityPeer()
        fixture.relay.register(alice.account, identityKey: alice.member.identityKey, bundle: try alice.bundle())
        let state = try fixture.community(owner: alice, members: [fixture.me])
        let enabled = LockedState(false)
        let service = CommunityService(
            backend: fixture.backend, storage: fixture.storage, pollInterval: .seconds(3600), readCoalescing: .milliseconds(20),
            readReceipts: { enabled.withLock { $0 } })
        defer { service.stop() }
        let first = BRPCommunityMessage.text("bir", community: state.id, revision: state.revision)
        try fixture.deliver(first, from: alice, in: state)
        await service.refresh()
        try await service.open(state.id)
        try await eventually("displayed") { try fixture.items(state.id).allSatisfy(\.isDisplayed) }
        await settle(40)
        await service.refresh()
        #expect(try fixture.opened(by: alice).map(\.receipt.type) == [.delivery])

        // Turned on: nothing is sent for what was read while it was off.
        enabled.withLock { $0 = true }
        let second = BRPCommunityMessage.text("iki", community: state.id, revision: state.revision)
        try fixture.deliver(second, from: alice, in: state)
        await service.refresh()
        var received: [BRPCommunityMessage] = []
        try await eventually("READ") {
            received += try fixture.opened(by: alice)
            return received.contains { $0.receipt.type == .read }
        }
        let reads = received.filter { $0.receipt.type == .read }
        #expect(reads.count == 1 && reads.first?.receipt.messageIds == [second.messageID])
        #expect(received.contains { $0.receipt.type == .delivery && $0.receipt.messageIds == [second.messageID] })
    }

    // MARK: Deletes

    @Test("delete for everyone: by the sender or the owner; anyone else is refused")
    func deleteAuthorization() async throws {
        let fixture = try CommunityFixture()
        let alice = CommunityPeer(), carol = CommunityPeer()
        for peer in [alice, carol] { fixture.relay.register(peer.account, identityKey: peer.member.identityKey, bundle: try peer.bundle()) }
        let state = try fixture.community(owner: alice, members: [fixture.me, carol.member])
        let fromAlice = BRPCommunityMessage.text("sahip", community: state.id, revision: state.revision)
        let fromCarol = BRPCommunityMessage.text("üye", community: state.id, revision: state.revision)
        try fixture.deliver(fromAlice, from: alice, in: state)
        try fixture.deliver(fromCarol, from: carol, in: state)
        _ = try await fixture.engine.sync()
        // Carol is neither the sender of alice's message nor the owner.
        let refused = try fixture.deliver(
            .delete(target: fromAlice.messageID, sender: alice.account, community: state.id, revision: state.revision), from: carol, in: state)
        // The owner removes carol's message.
        try fixture.deliver(
            .delete(target: fromCarol.messageID, sender: carol.account, community: state.id, revision: state.revision), from: alice, in: state)
        _ = try await fixture.engine.sync()
        #expect(fixture.relay.acked.contains(refused))
        var items = try fixture.items(state.id)
        #expect(items.first { $0.sender == alice.account }?.text == "sahip")
        #expect(items.first { $0.sender == carol.account }?.deletion == .byOwner)

        try fixture.deliver(
            .delete(target: fromAlice.messageID, sender: alice.account, community: state.id, revision: state.revision), from: alice, in: state)
        _ = try await fixture.engine.sync()
        items = try fixture.items(state.id)
        #expect(items.first { $0.sender == alice.account }?.deletion == .bySender)
        #expect(items.allSatisfy { $0.text.isEmpty })

        // A member who is not the owner cannot delete someone else's message.
        let another = BRPCommunityMessage.text("yine üye", community: state.id, revision: state.revision)
        try fixture.deliver(another, from: carol, in: state)
        _ = try await fixture.engine.sync()
        await #expect(throws: (any Error).self) {
            try await fixture.engine.deleteForEveryone(sender: carol.account, messageId: AccountId(bytes: another.messageID)!, in: state.id)
        }
        #expect(try fixture.items(state.id).first { $0.messageId.bytes == another.messageID }?.deletion == nil)
    }

    @Test("a delete before its target is remembered; the target is stored as the placeholder and still gets DELIVERY")
    func earlyDelete() async throws {
        let fixture = try CommunityFixture()
        let alice = CommunityPeer()
        fixture.relay.register(alice.account, identityKey: alice.member.identityKey, bundle: try alice.bundle())
        let state = try fixture.community(owner: alice, members: [fixture.me])
        let (late, _) = try fixture.uploadedByPeer(Self.photo, in: state)
        try fixture.deliver(.delete(target: late.messageID, sender: alice.account, community: state.id, revision: state.revision), from: alice, in: state)
        _ = try await fixture.engine.sync()
        #expect(try fixture.items(state.id).isEmpty)
        try fixture.deliver(late, from: alice, in: state)
        _ = try await fixture.engine.sync()
        let item = try #require(try fixture.items(state.id).first)
        #expect(item.deletion == .bySender && item.attachment == nil)
        #expect(try fixture.opened(by: alice).map(\.receipt.messageIds) == [[late.messageID]])
        #expect(fixture.relay.count("GET /v1/media") == 0)
    }

    @Test("the sender deletes its blob with its token: on its own delete and on the owner's; recipients never do")
    func blobDeletedOnlyBySender() async throws {
        // This phone owns the community and deletes its own photo.
        let fixture = try CommunityFixture()
        let alice = CommunityPeer()
        fixture.relay.register(alice.account, identityKey: alice.member.identityKey, bundle: try alice.bundle())
        let owned = try fixture.ownCommunity(members: [alice.member])
        let id = try await fixture.engine.storeMedia(try fixture.prepared(Self.photo), caption: "", messageId: nil, to: owned.id)
        #expect(await fixture.engine.deliverMedia(id, in: owned.id) == nil)
        let mediaId = try #require(try fixture.opened(by: alice).first?.attachment.mediaID)
        try await fixture.engine.deleteForEveryone(sender: fixture.account.accountId, messageId: id, in: owned.id)
        #expect(await fixture.engine.flushNow() == nil)
        #expect(fixture.relay.deletedMedia == [mediaId])
        let delete = try #require(try fixture.opened(by: alice).first)
        #expect(try delete.validatedKind() == .delete)
        #expect(delete.deleteTargetMessageID == id.bytes && delete.deleteTargetSenderID == fixture.account.accountId.bytes)
        #expect(try fixture.items(owned.id).first?.deletion == .bySender)

        // In carol's community the owner removes this phone's photo: this phone deletes the blob.
        let carol = CommunityPeer()
        fixture.relay.register(carol.account, identityKey: carol.member.identityKey, bundle: try carol.bundle())
        let theirs = try fixture.community(owner: carol, members: [fixture.me])
        _ = try await fixture.engine.sync()
        let mine = try await fixture.engine.storeMedia(try fixture.prepared(Self.photo), caption: "", messageId: nil, to: theirs.id)
        #expect(await fixture.engine.deliverMedia(mine, in: theirs.id) == nil)
        let blob = try #require(try fixture.opened(by: carol).first?.attachment.mediaID)
        try fixture.deliver(.delete(target: mine.bytes, sender: fixture.account.accountId, community: theirs.id, revision: theirs.revision), from: carol, in: theirs)
        _ = try await fixture.engine.sync()
        #expect(fixture.relay.deletedMedia == [mediaId, blob])
        #expect(try fixture.items(theirs.id).first?.deletion == .byOwner)
    }

    @Test("delete for me is local: nothing is sent, the blob stays, a retransmission is a duplicate")
    func deleteForMe() async throws {
        let fixture = try CommunityFixture()
        let alice = CommunityPeer()
        fixture.relay.register(alice.account, identityKey: alice.member.identityKey, bundle: try alice.bundle())
        let state = try fixture.community(owner: alice, members: [fixture.me])
        let (message, mediaId) = try fixture.uploadedByPeer(Self.photo, in: state)
        try fixture.deliver(message, from: alice, in: state)
        _ = try await fixture.engine.sync()
        _ = try fixture.opened(by: alice)
        let item = try #require(try fixture.items(state.id).first)
        try await fixture.engine.deleteForMe(sender: item.sender, messageId: item.messageId, in: state.id)
        try fixture.deliver(message, from: alice, in: state)
        _ = try await fixture.engine.sync()
        #expect(try fixture.items(state.id).isEmpty)
        #expect(try fixture.opened(by: alice).isEmpty)
        #expect(fixture.relay.blob(mediaId) != nil)
    }

    // MARK: Roster changes and “Tekrar gönder”

    @Test("a receipt the relay refuses as stale (409) stays queued and is sealed again under the new roster")
    func receiptResealedAfterConflict() async throws {
        let fixture = try CommunityFixture()
        let alice = CommunityPeer(), carol = CommunityPeer()
        for peer in [alice, carol] { fixture.relay.register(peer.account, identityKey: peer.member.identityKey, bundle: try peer.bundle()) }
        let state = try fixture.community(owner: alice, members: [fixture.me, carol.member])
        let text = BRPCommunityMessage.text("selam", community: state.id, revision: state.revision)
        try fixture.deliver(text, from: alice, in: state)
        fixture.relay.setSendStatus(409, for: alice.account)
        _ = try await fixture.engine.sync()
        // Not dropped: still queued for alice, with its content.
        let queued = try fixture.pending()
        #expect(queued.map(\.recipient) == [alice.account] && queued.first?.revision == 1)
        #expect(queued.first?.event?.receipt.type == .delivery)

        // The owner publishes revision 2 (carol removed); the relay takes it now.
        try fixture.community(owner: alice, members: [fixture.me], revision: 2, id: state.id, pin: false)
        fixture.relay.setSendStatus(nil, for: alice.account)
        #expect(await fixture.engine.flushNow() == nil)
        #expect(try fixture.pending().isEmpty)
        #expect(fixture.relay.sent.filter { $0.recipient == alice.account }.map(\.revision) == [2])
        let receipts = try fixture.opened(by: alice)
        #expect(receipts.count == 1 && receipts.first?.revision == 2)
        #expect(receipts.first?.receipt.type == .delivery && receipts.first?.receipt.messageIds == [text.messageID])
    }

    @Test("a delete queued under an older roster is sealed again for the members still there; a removed member's copy goes")
    func deleteResealedForCurrentMembers() async throws {
        let fixture = try CommunityFixture()
        let alice = CommunityPeer(), carol = CommunityPeer()
        for peer in [alice, carol] { fixture.relay.register(peer.account, identityKey: peer.member.identityKey, bundle: try peer.bundle()) }
        let state = try fixture.community(owner: alice, members: [fixture.me, carol.member])
        _ = try await fixture.engine.sync()
        _ = try await fixture.engine.send("yanlış", to: state.id)
        #expect(await fixture.engine.flushNow() == nil)
        let id = try #require(try fixture.items(state.id).first?.messageId)
        _ = try fixture.opened(by: alice)
        _ = try fixture.opened(by: carol)

        // Offline: the delete waits under revision 1.
        for peer in [alice, carol] { fixture.relay.setSendStatus(503, for: peer.account) }
        try await fixture.engine.deleteForEveryone(sender: fixture.account.accountId, messageId: id, in: state.id)
        _ = await fixture.engine.flushNow()
        #expect(try fixture.pending().count == 2)

        // Meanwhile the owner removed carol (revision 2) and the relay answers again.
        try fixture.community(owner: alice, members: [fixture.me], revision: 2, id: state.id, pin: false)
        for peer in [alice, carol] { fixture.relay.setSendStatus(nil, for: peer.account) }
        #expect(await fixture.engine.flushNow() == nil)
        #expect(try fixture.pending().isEmpty)
        let delete = try #require(try fixture.opened(by: alice).first)
        #expect(try delete.validatedKind() == .delete && delete.revision == 2)
        #expect(delete.deleteTargetMessageID == id.bytes && delete.deleteTargetSenderID == fixture.account.accountId.bytes)
        #expect(try fixture.opened(by: carol).isEmpty)
    }

    @Test("“Tekrar gönder” after a 409 goes under the new roster only to the members that did not get it")
    func resendAfterConflict() async throws {
        let fixture = try CommunityFixture()
        let alice = CommunityPeer(), carol = CommunityPeer(), dave = CommunityPeer(), erin = CommunityPeer()
        let everyone = [alice, carol, dave, erin]
        for peer in everyone { fixture.relay.register(peer.account, identityKey: peer.member.identityKey, bundle: try peer.bundle()) }
        let state = try fixture.community(owner: alice, members: [fixture.me, carol.member, dave.member, erin.member])
        _ = try await fixture.engine.sync()
        let order = sendOrder(everyone)
        // The relay refuses the third copy as stale: the first two went, the message stops.
        fixture.relay.setSendStatus(409, for: order[2].account)
        _ = try await fixture.engine.send("tekrar", to: state.id)
        #expect(await fixture.engine.flushNow() == .stale)
        let item = try #require(try fixture.items(state.id).first)
        #expect(item.status == .failed)
        #expect(try fixture.recipients(item.messageId, in: state.id) == [
            order[0].account: .accepted, order[1].account: .accepted, order[2].account: .failed, order[3].account: .failed,
        ])
        for peer in everyone { _ = try fixture.opened(by: peer) }

        // Revision 2: one of the two who did not get it has been removed (never the owner).
        let removed = order[3].account == alice.account ? order[2] : order[3]
        let target = removed.account == order[3].account ? order[2] : order[3]
        try fixture.community(
            owner: alice, members: [fixture.me] + everyone.filter { $0.account != alice.account && $0.account != removed.account }.map(\.member),
            revision: 2, id: state.id, pin: false)
        fixture.relay.setSendStatus(nil, for: order[2].account)
        #expect(try await fixture.engine.resend(item.messageId, in: state.id) == .queued(unreachable: []))
        #expect(await fixture.engine.flushNow() == nil)

        let again = try fixture.opened(by: target)
        #expect(again.count == 1 && again.first?.text == "tekrar" && again.first?.revision == 2)
        #expect(again.first?.messageID == item.messageId.bytes)
        for peer in everyone where peer.account != target.account { #expect(try fixture.opened(by: peer).isEmpty) }
        #expect(try fixture.recipients(item.messageId, in: state.id) == [
            order[0].account: .accepted, order[1].account: .accepted, target.account: .accepted, removed.account: .skipped,
        ])
        // Everyone still a member has it; the removed member is skipped.
        #expect(try fixture.items(state.id).first?.status == .partial)
        // Sent again: a second tap does nothing.
        #expect(try await fixture.engine.resend(item.messageId, in: state.id) == .nothing)
    }

    @Test("a member the relay refuses fails the message; “Tekrar gönder” goes to that member alone")
    func resendToRefusedMember() async throws {
        let fixture = try CommunityFixture()
        let alice = CommunityPeer(), carol = CommunityPeer()
        for peer in [alice, carol] { fixture.relay.register(peer.account, identityKey: peer.member.identityKey, bundle: try peer.bundle()) }
        let state = try fixture.ownCommunity(members: [alice.member, carol.member])
        fixture.relay.setSendStatus(403, for: carol.account)
        _ = try await fixture.engine.send("herkese", to: state.id)
        #expect(await fixture.engine.flushNow() == nil)
        let item = try #require(try fixture.items(state.id).first)
        #expect(item.status == .failed)
        #expect(try fixture.opened(by: alice).count == 1 && fixture.opened(by: carol).isEmpty)

        fixture.relay.setSendStatus(nil, for: carol.account)
        #expect(try await fixture.engine.resend(item.messageId, in: state.id) == .queued(unreachable: []))
        #expect(await fixture.engine.flushNow() == nil)
        #expect(try fixture.opened(by: alice).isEmpty)
        #expect(try fixture.opened(by: carol).map(\.text) == ["herkese"])
        #expect(try fixture.status(state.id) == [.sent])
    }

    @Test("“Tekrar gönder” on a media message reuses its uploaded blob")
    func mediaResendReusesTheBlob() async throws {
        let fixture = try CommunityFixture()
        let alice = CommunityPeer()
        fixture.relay.register(alice.account, identityKey: alice.member.identityKey, bundle: try alice.bundle())
        let state = try fixture.community(owner: alice, members: [fixture.me])
        _ = try await fixture.engine.sync()
        fixture.relay.setSendStatus(409, for: alice.account)
        let id = try await fixture.engine.storeMedia(try fixture.prepared(Self.photo), caption: "Gün batımı", messageId: nil, to: state.id)
        #expect(await fixture.engine.deliverMedia(id, in: state.id) == .stale)
        #expect(fixture.relay.uploads == 1)
        let failed = try #require(try fixture.items(state.id).first)
        #expect(failed.status == .failed && failed.attachment?.state == .uploaded)
        let mediaId = try #require(failed.attachment?.descriptor.mediaID)

        fixture.relay.setSendStatus(nil, for: alice.account)
        #expect(try await fixture.engine.resend(id, in: state.id) == .queued(unreachable: []))
        #expect(await fixture.engine.flushNow() == nil)
        #expect(fixture.relay.uploads == 1)
        let received = try #require(try fixture.opened(by: alice).first)
        #expect(received.attachment.mediaID == mediaId && received.text == "Gün batımı" && received.messageID == id.bytes)
        #expect(try fixture.status(state.id) == [.sent])
    }
}


extension CommunityV2EngineTests {
    @Test("v3 sticker persists, fans out encrypted, retries as a sticker and deletes cleanly")
    func stickerRoundTripAndRetry() async throws {
        let f = try CommunityFixture()
        let peer = CommunityPeer()
        f.relay.register(peer.account, identityKey: peer.member.identityKey, bundle: try peer.bundle())
        let state = try f.ownCommunity(members: [peer.member])
        var sticker = BRPSticker()
        sticker.packID = "us2us.cups"; sticker.stickerID = 1; sticker.emoji = "👋"
        f.relay.setSendStatus(503, for: peer.account)
        _ = try await f.engine.sendSticker(sticker, to: state.id)
        _ = await f.engine.flushNow()
        let first = try #require(try f.items(state.id).first)
        #expect(first.sticker == sticker && first.text == "👋" && first.kind == .sticker)
        #expect(try f.opened(by: peer).isEmpty)
        f.relay.setSendStatus(nil, for: peer.account)
        try f.storage.write { try $0.communities.setStatus(.failed, community: state.id, message: first.messageId) }
        _ = try await f.engine.resend(first.messageId, in: state.id)
        _ = await f.engine.flushNow()
        #expect(try f.opened(by: peer).first?.sticker == sticker)
        #expect(try f.items(state.id).first?.sticker == sticker)
        try f.storage.write {
            try $0.communities.applyDelete(community: state.id, targetSender: f.account.accountId,
                targetId: first.messageId, by: .bySender, own: f.account.accountId)
        }
        #expect(try f.items(state.id).first?.sticker == nil)
        #expect(try f.items(state.id).first?.deletion == .bySender)
    }

    @Test("100-member voice fanout uploads once, retains waveform, and encrypts a separate envelope for 99 peers")
    func voiceAtMemberLimit() async throws {
        let f = try CommunityFixture()
        let peers = (0..<99).map { _ in CommunityPeer() }
        for peer in peers { f.relay.register(peer.account, identityKey: peer.member.identityKey, bundle: try peer.bundle()) }
        let state = try f.ownCommunity(members: peers.map(\.member))
        let audio = Data((0..<3000).map { UInt8($0 % 251) })
        let file = f.storage.makeStagingURL()
        try audio.write(to: file)
        let prepared = PreparedMedia(file: file, kind: .voice, contentType: "audio/mp4", size: UInt64(audio.count),
                                     durationMs: 2000, waveform: Data([0, 128, 255]))
        let id = try await f.engine.storeMedia(prepared, caption: "", messageId: nil, to: state.id)
        #expect(await f.engine.deliverMedia(id, in: state.id) == nil)
        #expect(f.relay.uploads == 1)
        var pointers: [BRPAttachment] = []
        for peer in peers {
            let message = try #require(try f.opened(by: peer).first)
            #expect(try message.validatedKind() == .media)
            #expect(message.attachment.kind == .voice && message.attachment.waveform == Data([0, 128, 255]))
            #expect(message.attachment.deleteToken.isEmpty)
            pointers.append(message.attachment)
        }
        #expect(pointers.count == 99 && Set(pointers.map(\.mediaID)).count == 1 && Set(pointers.map(\.key)).count == 1)
        let pointer = try #require(pointers.first)
        let blob = try #require(f.relay.blob(pointer.mediaID))
        #expect(try AttachmentCipher.decrypt(blob, key: pointer.key, digest: pointer.digest, size: pointer.size) == audio)
        #expect(try f.items(state.id).first?.attachment?.descriptor.waveform == Data([0, 128, 255]))
    }

    @Test("v3 stickers from removed senders and stale revisions are rejected before persistence")
    func stickerMembershipChecks() async throws {
        let f = try CommunityFixture()
        let owner = CommunityPeer(), outsider = CommunityPeer()
        for peer in [owner, outsider] { f.relay.register(peer.account, identityKey: peer.member.identityKey) }
        let state = try f.community(owner: owner, members: [f.me], revision: 2)
        var sticker = BRPSticker(); sticker.packID = "us2us.cups"; sticker.stickerID = 1; sticker.emoji = "👋"
        let removed = BRPCommunityMessage.sticker(sticker, community: state.id, revision: 2)
        let stale = BRPCommunityMessage.sticker(sticker, community: state.id, revision: 1)
        let a = try f.deliver(removed, from: outsider, in: state)
        let b = try f.deliver(stale, from: owner, in: state)
        _ = try await f.engine.sync()
        #expect(try f.items(state.id).isEmpty)
        #expect(Set(f.relay.acked) == [a, b])
    }
}
