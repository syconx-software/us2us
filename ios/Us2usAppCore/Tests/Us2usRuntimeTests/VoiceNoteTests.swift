import Foundation
import Testing
import Us2usKit
import Us2usStorage

@testable import Us2usRuntime

// Voice notes: staged from memory for sending; the first play of a
// received one is marked here and acknowledged with VIEWED where a read
// receipt would go (§8.4) — and VIEWED destroys only view-once items.

private func voiceNote(mediaId: Data, id: Data) -> BRPContent {
    var attachment = BRPAttachment()
    attachment.kind = .voice
    attachment.mediaID = mediaId
    attachment.deleteToken = Data(repeating: 1, count: 32)
    attachment.key = Data(repeating: 2, count: 64)
    attachment.digest = Data(repeating: 3, count: 32)
    attachment.size = 5
    attachment.contentType = "audio/mp4"
    attachment.durationMs = 4_200
    attachment.waveform = Data([10, 200, 90])
    return .data(.attachment(attachment), id: id)
}

@Suite("Voice notes")
struct VoiceNoteTests {
    @Test func aRecordingIsStagedFromMemoryWithItsWaveform() throws {
        let directory = try TemporaryDirectory()
        let directories = directory.directories
        try directories.prepare()
        let audio = Data("aac".utf8)
        let prepared = try MediaPreparation.prepareVoice(
            audio, directories: directories, durationMs: 4_200, waveform: Data(repeating: 7, count: 140))
        #expect(prepared.kind == .voice)
        #expect(prepared.contentType == "audio/mp4")
        #expect(prepared.size == 3)
        #expect(prepared.durationMs == 4_200)
        #expect(prepared.waveform?.count == ContentLimits.maxWaveformSamples, "at most 100 samples")
        #expect(prepared.file.deletingLastPathComponent().path == directories.staging.path)
        #expect(try Data(contentsOf: prepared.file) == audio)
        let attachment = prepared.pendingAttachment
        #expect(attachment.kind == .voice)
        #expect(attachment.durationMs == 4_200)
        #expect(attachment.waveform.count == ContentLimits.maxWaveformSamples)
    }

    @Test func theFirstPlaySendsViewedAndDestroysNothing() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        await fixture.start()
        let mediaId = RandomId.make()
        let id = RandomId.make()
        fixture.backend.setDownload(Data("voice".utf8), for: mediaId)
        try await fixture.receive(voiceNote(mediaId: mediaId, id: id), from: peer)
        try await eventually {
            guard case .attachment(let shown)? = try await fixture.messages(peer).first?.body else { return false }
            return shown.availability == .local
        }
        #expect(try await fixture.messages(peer).first?.viewedAt == nil)
        try await fixture.engine.markVoiceNotePlayed(id, peer: peer)
        #expect(try await fixture.messages(peer).first?.viewedAt != nil)
        try await eventually("VIEWED") { fixture.backend.sent(to: peer).contains { $0.receipt?.type == .viewed } }
        try await fixture.engine.markVoiceNotePlayed(id, peer: peer)
        await settle()
        let viewed = fixture.backend.sent(to: peer).filter { $0.receipt?.type == .viewed }
        #expect(viewed.count == 1, "only the first play")
        #expect(try await fixture.engine.mediaData(id, peer: peer) == Data("voice".utf8), "still here and playable")
        await fixture.engine.stop()
    }

    @Test func withReadReceiptsOffThePlayStaysOnThisPhone() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        await fixture.start()
        try await fixture.engine.updateUserSettings { $0.readReceipts = false }
        let id = RandomId.make()
        try await fixture.receive(voiceNote(mediaId: RandomId.make(), id: id), from: peer)
        try await fixture.engine.markVoiceNotePlayed(id, peer: peer)
        #expect(try await fixture.messages(peer).first?.viewedAt != nil)
        let sent = await happens(within: 0.3) {
            fixture.backend.sent(to: peer).contains { $0.receipt?.type == .viewed }
        }
        #expect(!sent)
        await fixture.engine.stop()
    }

    @Test func onlyVoiceNotesCountAsPlayed() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        await fixture.start()
        let text = RandomId.make()
        try await fixture.receive(.textMessage("selam", id: text), from: peer)
        try await fixture.engine.markVoiceNotePlayed(text, peer: peer)
        let photo = RandomId.make()
        try await fixture.receive(.attachmentMessage(id: photo), from: peer)
        try await fixture.engine.markVoiceNotePlayed(photo, peer: peer)
        let items = try await fixture.messages(peer)
        #expect(items.count == 2)
        #expect(items.allSatisfy { $0.viewedAt == nil })
        let sent = await happens(within: 0.3) {
            fixture.backend.sent(to: peer).contains { $0.receipt?.type == .viewed }
        }
        #expect(!sent)
        await fixture.engine.stop()
    }

    @Test func theContactsViewedMarksOurVoiceNotePlayedAndKeepsIt() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        await fixture.start()
        let prepared = try MediaPreparation.prepareVoice(
            Data("aac".utf8), directories: fixture.directory.directories, durationMs: 2_000, waveform: Data([1, 2]))
        let id = try await fixture.engine.send(.media(prepared), to: peer)
        #expect(try await fixture.messages(peer).first?.viewedAt == nil)
        try await fixture.receive(BRPContent(receipt: .viewed, messageIds: [id]), from: peer)
        let item = try #require(try await fixture.messages(peer).first)
        #expect(item.viewedAt != nil)
        guard case .attachment(let voice) = item.body else {
            Issue.record("the voice note is still there")
            return
        }
        #expect(voice.kind == .voice)
        #expect(voice.durationMs == 2_000)
        await fixture.engine.stop()
    }
}
