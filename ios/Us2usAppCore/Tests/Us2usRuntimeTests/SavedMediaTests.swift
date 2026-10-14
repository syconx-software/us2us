import Foundation
import Testing
import Us2usKit
import Us2usStorage

@testable import Us2usRuntime

// Saved media (PROTOCOL.md §8.4 SAVED): saving a received photo, video or
// file tells its sender — whatever the read-receipt setting — and a SAVED
// marks only our own permanent photos, videos and files. The media list of
// a conversation pages through photos and videos or files, newest first.

private func media(_ kind: BRPAttachment.Kind, id: Data = RandomId.make(), expire: UInt32 = 0, viewOnce: Bool = false)
    -> BRPContent
{
    var attachment = BRPAttachment()
    attachment.kind = kind
    attachment.mediaID = RandomId.make()
    attachment.deleteToken = Data(repeating: 1, count: 32)
    attachment.key = Data(repeating: 2, count: 64)
    attachment.digest = Data(repeating: 3, count: 32)
    attachment.size = 5
    switch kind {
    case .file:
        attachment.contentType = "application/pdf"
        attachment.fileName = "belge.pdf"
    case .video: attachment.contentType = "video/mp4"
    case .voice: attachment.contentType = "audio/mp4"
    default: attachment.contentType = "image/jpeg"
    }
    return .data(.attachment(attachment), id: id, expire: expire, viewOnce: viewOnce)
}

private func prepared(_ kind: AttachmentItem.Kind, in fixture: EngineFixture) throws -> PreparedMedia {
    let file = fixture.directory.directories.stagingFile(extension: kind == .file ? "pdf" : "jpg")
    try Data("bytes".utf8).write(to: file)
    return PreparedMedia(
        file: file, kind: kind, contentType: kind == .file ? "application/pdf" : "image/jpeg",
        fileName: kind == .file ? "rapor.pdf" : nil, size: 5, width: 4, height: 3)
}

private func savedReceipts(_ fixture: EngineFixture, to peer: AccountId) -> [BRPReceiptMessage] {
    fixture.backend.sent(to: peer).compactMap { $0.receipt }.filter { $0.type == .saved }
}

@Suite("Saved media (§8.4)")
struct SavedMediaTests {
    @Test func savingAReceivedPhotoSendsSavedOnceAlsoWithReadReceiptsOff() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        await fixture.start()
        try await fixture.engine.updateUserSettings { $0.readReceipts = false }
        let id = RandomId.make()
        try await fixture.receive(media(.image, id: id), from: peer)
        #expect(try await fixture.messages(peer).first?.savedByMeAt == nil)

        try await fixture.engine.markMediaSaved(id, peer: peer)
        let item = try #require(try await fixture.messages(peer).first)
        #expect(item.savedByMeAt != nil)
        #expect(item.savedByPeerAt == nil)
        try await eventually("SAVED") { !savedReceipts(fixture, to: peer).isEmpty }
        #expect(savedReceipts(fixture, to: peer).first?.messageIds == [id])

        // Saving again sends nothing more and keeps the first time.
        fixture.clock.advance(by: 60)
        try await fixture.engine.markMediaSaved(id, peer: peer)
        await settle()
        #expect(savedReceipts(fixture, to: peer).count == 1)
        #expect(try await fixture.messages(peer).first?.savedByMeAt == item.savedByMeAt)
        await fixture.engine.stop()
    }

    @Test func videosAndFilesCanBeSavedToo() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        await fixture.start()
        let video = RandomId.make()
        let file = RandomId.make()
        try await fixture.receive(media(.video, id: video), from: peer)
        try await fixture.receive(media(.file, id: file), from: peer)
        try await fixture.engine.markMediaSaved(video, peer: peer)
        try await fixture.engine.markMediaSaved(file, peer: peer)
        try await eventually("SAVED") {
            Set(savedReceipts(fixture, to: peer).flatMap(\.messageIds)) == [video, file]
        }
        await fixture.engine.stop()
    }

    @Test func viewOnceDisappearingDeletedAndOtherItemsCannotBeSaved() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        await fixture.start()
        let viewOnce = RandomId.make()
        let disappearing = RandomId.make()
        let voice = RandomId.make()
        let text = RandomId.make()
        let deleted = RandomId.make()
        try await fixture.receive(media(.image, id: viewOnce, viewOnce: true), from: peer)
        try await fixture.receive(media(.video, id: disappearing, expire: 3600), from: peer)
        try await fixture.receive(media(.voice, id: voice), from: peer)
        try await fixture.receive(.textMessage("selam", id: text), from: peer)
        try await fixture.receive(media(.image, id: deleted), from: peer)
        var delete = BRPDelete()
        delete.targetMessageID = deleted
        try await fixture.receive(.data(.delete(delete)), from: peer)
        for id in [viewOnce, disappearing, voice, text, deleted, RandomId.make()] {
            await #expect(throws: EngineError.invalidMessage) { try await fixture.engine.markMediaSaved(id, peer: peer) }
        }
        #expect(try await fixture.messages(peer).allSatisfy { $0.savedByMeAt == nil })
        let sent = await happens(within: 0.3) { !savedReceipts(fixture, to: peer).isEmpty }
        #expect(!sent)
        await fixture.engine.stop()
    }

    @Test func savingOurOwnPhotoTellsNobody() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        await fixture.start()
        let id = try await fixture.engine.send(.media(try prepared(.image, in: fixture)), to: peer)
        try await fixture.engine.markMediaSaved(id, peer: peer)
        #expect(try await fixture.messages(peer).first { $0.messageId == id }?.savedByMeAt != nil)
        let sent = await happens(within: 0.3) { !savedReceipts(fixture, to: peer).isEmpty }
        #expect(!sent)
        await fixture.engine.stop()
    }

    @Test func aRequesterIsNeverSentSaved() async throws {
        let fixture = try EngineFixture()
        await fixture.start()
        let stranger = AccountId(uuid: UUID())
        let id = RandomId.make()
        try await fixture.receive(media(.image, id: id), from: stranger)
        #expect(try fixture.contact(stranger)?.requestState == .incomingRequest)
        try await fixture.engine.markMediaSaved(id, peer: stranger)
        let sent = await happens(within: 0.3) { !savedReceipts(fixture, to: stranger).isEmpty }
        #expect(!sent)
        await fixture.engine.stop()
    }

    @Test func theContactsSavedMarksOurPhotoOnce() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        await fixture.start()
        let photo = try await fixture.engine.send(.media(try prepared(.image, in: fixture)), to: peer)
        let file = try await fixture.engine.send(.media(try prepared(.file, in: fixture)), to: peer)
        #expect(try await fixture.messages(peer).allSatisfy { $0.savedByPeerAt == nil })
        try await fixture.receive(BRPContent(receipt: .saved, messageIds: [photo, file]), from: peer)
        let items = try await fixture.messages(peer)
        let first = try #require(items.first { $0.messageId == photo }?.savedByPeerAt)
        #expect(items.first { $0.messageId == file }?.savedByPeerAt != nil)
        #expect(items.allSatisfy { $0.savedByMeAt == nil })

        fixture.clock.advance(by: 300)
        try await fixture.receive(BRPContent(receipt: .saved, messageIds: [photo]), from: peer)
        #expect(try await fixture.messages(peer).first { $0.messageId == photo }?.savedByPeerAt == first)
        await fixture.engine.stop()
    }

    @Test func savedIsIgnoredForWrongKindsAndPeers() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        let other = try fixture.acceptedContact()
        await fixture.start()
        let text = try await fixture.engine.send(.text("merhaba"), to: peer)
        let viewOnce = try await fixture.engine.send(.media(try prepared(.image, in: fixture)), to: peer, viewOnce: true)
        let theirs = RandomId.make()
        try await fixture.receive(media(.image, id: theirs), from: peer)
        let toOther = try await fixture.engine.send(.media(try prepared(.image, in: fixture)), to: other)
        try await fixture.engine.updateConversationSettings(peer, ghost: nil, defaultExpireTimerSeconds: 3600)
        let disappearing = try await fixture.engine.send(.media(try prepared(.image, in: fixture)), to: peer)
        let voice = try await fixture.engine.send(
            .media(
                try MediaPreparation.prepareVoice(
                    Data("aac".utf8), directories: fixture.directory.directories, durationMs: 1_000,
                    waveform: Data([1]))), to: peer)

        // From the wrong contact, for the wrong kinds, for their own message, for an unknown id.
        try await fixture.receive(BRPContent(receipt: .saved, messageIds: [toOther]), from: peer)
        try await fixture.receive(
            BRPContent(receipt: .saved, messageIds: [text, viewOnce, theirs, disappearing, voice, RandomId.make()]),
            from: peer)
        #expect(try await fixture.messages(peer).allSatisfy { $0.savedByPeerAt == nil })
        #expect(try await fixture.messages(other).allSatisfy { $0.savedByPeerAt == nil })
        await fixture.engine.stop()
    }

    @Test func savedFromAnUnacceptedContactIsIgnored() async throws {
        let fixture = try EngineFixture()
        let peer = AccountId(uuid: UUID())
        let now = fixture.clock.now()
        try fixture.storage.write { tx in
            try tx.contacts.create(peer, state: .outgoingRequest, now: now)
            _ = try tx.conversations.ensure(for: peer, now: now)
        }
        await fixture.start()
        let photo = try await fixture.engine.send(.media(try prepared(.image, in: fixture)), to: peer)
        try await fixture.receive(BRPContent(receipt: .saved, messageIds: [photo]), from: peer)
        #expect(try fixture.contact(peer)?.requestState == .outgoingRequest)
        #expect(try await fixture.messages(peer).first { $0.messageId == photo }?.savedByPeerAt == nil)
        await fixture.engine.stop()
    }

    @Test func aReceiptTypeFromANewerClientChangesNothing() async throws {
        // What builds that predate SAVED do with it: an unknown type is ignored.
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        await fixture.start()
        let photo = try await fixture.engine.send(.media(try prepared(.image, in: fixture)), to: peer)
        let before = try #require(try await fixture.messages(peer).first { $0.messageId == photo })
        try await fixture.receive(
            BRPContent(receipt: .UNRECOGNIZED(9), messageIds: [photo]),
            from: peer)
        let after = try #require(try await fixture.messages(peer).first { $0.messageId == photo })
        #expect(after.savedByPeerAt == nil)
        #expect(after.readAt == before.readAt && after.viewedAt == before.viewedAt)
        await fixture.engine.stop()
    }
}

@Suite("Media of a conversation")
struct MediaListTests {
    @Test func photosAndVideosOrFilesNewestFirstInPages() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        await fixture.start()
        let photo = RandomId.make()
        let file = RandomId.make()
        let video = RandomId.make()
        let viewOnce = RandomId.make()
        let disappearing = RandomId.make()
        let deleted = RandomId.make()
        try await fixture.receive(media(.image, id: photo), from: peer)
        try await fixture.receive(media(.file, id: file), from: peer)
        try await fixture.receive(.textMessage("arada"), from: peer)
        try await fixture.receive(media(.voice), from: peer)
        try await fixture.receive(media(.video, id: video), from: peer)
        try await fixture.receive(media(.image, id: viewOnce, viewOnce: true), from: peer)
        try await fixture.receive(media(.image, id: disappearing, expire: 60), from: peer)
        try await fixture.receive(media(.image, id: deleted), from: peer)
        var delete = BRPDelete()
        delete.targetMessageID = deleted
        try await fixture.receive(.data(.delete(delete)), from: peer)
        let ours = try await fixture.engine.send(.media(try prepared(.image, in: fixture)), to: peer)

        let visual = try await fixture.engine.mediaMessages(with: peer, kind: .visual, before: nil, limit: 10)
        // Never view-once, disappearing, deleted, voice or text items.
        #expect(visual.map(\.messageId) == [ours, video, photo])
        let documents = try await fixture.engine.mediaMessages(with: peer, kind: .documents, before: nil, limit: 10)
        #expect(documents.map(\.messageId) == [file])
        guard case .attachment(let attachment)? = documents.first?.body else {
            Issue.record("a file item")
            return
        }
        #expect(attachment.kind == .file)
        #expect(attachment.fileName == "belge.pdf")

        // Pages: pass the last item's id.
        let first = try await fixture.engine.mediaMessages(with: peer, kind: .visual, before: nil, limit: 2)
        #expect(first.map(\.messageId) == [ours, video])
        let second = try await fixture.engine.mediaMessages(with: peer, kind: .visual, before: video, limit: 2)
        #expect(second.map(\.messageId) == [photo])
        #expect(try await fixture.engine.mediaMessages(with: peer, kind: .visual, before: photo, limit: 2).isEmpty)

        // Displayed (its timer runs), the disappearing photo is still not listed (§8.1).
        try await fixture.engine.markDisplayed(peer)
        let shown = try await fixture.engine.mediaMessages(with: peer, kind: .visual, before: nil, limit: 10)
        #expect(shown.map(\.messageId) == [ours, video, photo])
        fixture.clock.advance(by: 120)
        let later = try await fixture.engine.mediaMessages(with: peer, kind: .visual, before: nil, limit: 10)
        #expect(later.map(\.messageId) == [ours, video, photo])

        // Saved items say so in the list too.
        try await fixture.engine.markMediaSaved(video, peer: peer)
        let saved = try await fixture.engine.mediaMessages(with: peer, kind: .visual, before: nil, limit: 10)
        #expect(saved.first { $0.messageId == video }?.savedByMeAt != nil)

        // An item gone meanwhile ends the paging; the list loads again from the newest.
        #expect(try await fixture.engine.messageItem(video, peer: peer)?.savedByMeAt != nil)
        try await fixture.engine.deleteForMe(video, peer: peer)
        #expect(try await fixture.engine.messageItem(video, peer: peer) == nil)
        #expect(try await fixture.engine.mediaMessages(with: peer, kind: .visual, before: video, limit: 2).isEmpty)
        #expect(
            try await fixture.engine.mediaMessages(with: AccountId(uuid: UUID()), kind: .visual, before: nil, limit: 5)
                .isEmpty)
        #expect(try await fixture.engine.mediaMessages(with: peer, kind: .visual, before: nil, limit: 0).isEmpty)
        await fixture.engine.stop()
    }
}
