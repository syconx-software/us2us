import Foundation
import Testing
import Us2usRuntime

@testable import us2us

// Saved media in the live mapping (PROTOCOL §8.4): the contact's SAVED and
// our own saves reach `Message` for photos, videos and files that can be
// saved at all, whatever the read-receipt setting.

@Suite("Saved media · live mapping")
struct SavedReceiptMappingTests {
    private let now = Date(timeIntervalSince1970: 1_800_000_000)

    private func record(
        _ kind: LiveAttachment.Kind, id: UInt8, direction: LiveMessageRecord.Direction, timer: UInt32 = 0,
        viewOnce: Bool = false, savedByPeerAt: Date? = nil, savedByMeAt: Date? = nil
    ) -> LiveMessageRecord {
        let attachment = LiveAttachment(
            kind: kind, contentType: kind == .file ? "application/pdf" : "image/jpeg",
            fileName: kind == .file ? "rapor.pdf" : nil, size: 1_000, durationMs: kind == .voice ? 2_000 : 0,
            thumbnail: nil, caption: nil, waveform: nil, availability: .local)
        var record = LiveMessageRecord(
            id: Data(repeating: id, count: 16), direction: direction, body: .attachment(attachment), sentAt: now,
            quoteTarget: nil, quoteText: nil, status: direction == .outgoing ? .delivered : nil)
        record.expireTimerSeconds = timer
        record.viewOnce = viewOnce
        record.savedByPeerAt = savedByPeerAt
        record.savedByMeAt = savedByMeAt
        return record
    }

    private func map(_ records: [LiveMessageRecord], readReceipts: Bool = true) -> [Message] {
        LiveMapping.messages(
            records, ownUsername: "selin", peerUsername: "deniz", now: now, media: [:],
            showsPeerPlayback: readReceipts)
    }

    @Test func theContactsSaveShowsOnOurPhotosVideosAndFilesAlsoWithReadReceiptsOff() {
        let saved = now.addingTimeInterval(60)
        let kinds: [LiveAttachment.Kind] = [.image, .video, .file]
        let records = kinds.enumerated().map { index, kind in
            record(kind, id: UInt8(index + 1), direction: .outgoing, savedByPeerAt: saved)
        }
        for messages in [map(records), map(records, readReceipts: false)] {
            #expect(messages.map(\.savedByPeer) == [true, true, true])
            #expect(messages.map(\.savedByPeerAt) == [saved, saved, saved], "5.4 › Kaydedildi shows when")
            #expect(messages.allSatisfy { !$0.savedByMe })
        }
        let unsaved = map([record(.image, id: 9, direction: .outgoing)]).first
        #expect(unsaved?.savedByPeer == false)
        #expect(unsaved?.savedByPeerAt == nil)
    }

    @Test func ourOwnSaveShowsOnReceivedAndSentItems() {
        let saved = now.addingTimeInterval(5)
        let messages = map([
            record(.image, id: 1, direction: .incoming, savedByMeAt: saved),
            record(.file, id: 2, direction: .outgoing, savedByMeAt: saved),
            record(.video, id: 3, direction: .incoming),
        ])
        #expect(messages.map(\.savedByMe) == [true, true, false])
        #expect(messages.allSatisfy { !$0.savedByPeer })
    }

    @Test func nothingElseShowsAsSaved() {
        let saved = now.addingTimeInterval(5)
        let messages = map([
            // A received item never shows the contact's save.
            record(.image, id: 1, direction: .incoming, savedByPeerAt: saved),
            record(.voice, id: 2, direction: .outgoing, savedByPeerAt: saved, savedByMeAt: saved),
            record(.image, id: 3, direction: .outgoing, viewOnce: true, savedByPeerAt: saved, savedByMeAt: saved),
            record(.video, id: 4, direction: .outgoing, timer: 3600, savedByPeerAt: saved, savedByMeAt: saved),
        ])
        #expect(messages.count == 4)
        #expect(messages.allSatisfy { !$0.savedByPeer && !$0.savedByMe })
    }

    @Test func aNewSaveIsAChangeOfTheRecord() {
        let before = record(.image, id: 1, direction: .outgoing)
        var after = before
        after.savedByPeerAt = now
        #expect(before != after)
        var mine = before
        mine.savedByMeAt = now
        #expect(before != mine)
    }

    @Test func theMediaTabsAskForTheRightKind() {
        #expect(Us2usRuntime.MediaKind(ChatMediaKind.visual) == .visual)
        #expect(Us2usRuntime.MediaKind(ChatMediaKind.documents) == .documents)
    }
}
