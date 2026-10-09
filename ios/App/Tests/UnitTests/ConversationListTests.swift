import Foundation
import Testing
import UIKit

@testable import us2us

/// 5.1's message column: the derived rows (cached per change), the rows
/// that keep collapsing after their message is removed, the kind changes a
/// bubble morphs from, the stress fixture and the bubbles' time format.
@MainActor
@Suite("05 · Message column")
struct ConversationListTests {
    private func makeModel() -> (ConversationViewModel, DesignFixturesService) {
        let service = DesignFixturesService(hasAccount: true, clock: .frozen(at: DesignFixtures.referenceDate), latency: .zero)
        return (ConversationViewModel(chatID: "deniz", messenger: service), service)
    }

    @Test func rowsCarryTailsAndTheSentence() {
        let (model, _) = makeModel()
        let rows = model.rows
        #expect(rows.map(\.id) == ["d0", "d1", "d2", "d3", "d4", "d5", "d6"])
        #expect(rows.filter(\.isTail).map(\.id) == ["d2", "d3", "d4", "d6"])
        #expect(rows.filter(\.showsStatusSentence).map(\.id) == ["d6"])
    }

    @Test func rowsAreCachedUntilTheConversationChanges() async {
        let (model, service) = makeModel()
        let first = model.rows
        let again = model.rows
        // Same storage: nothing was derived again.
        #expect(first.withUnsafeBufferPointer { $0.baseAddress } == again.withUnsafeBufferPointer { $0.baseAddress })
        await service.send(text: "Görüşürüz", replyTo: nil, in: "deniz")
        let after = model.rows
        #expect(after.count == first.count + 1)
        #expect(after.last?.showsStatusSentence == true)
        #expect(after.first { $0.id == "d6" }?.showsStatusSentence == false, "the sentence moves to the newest message")
    }

    @Test func aRemovedRowKeepsItsPlaceWhileItCollapses() async {
        let (model, service) = makeModel()
        _ = model.rows
        await service.deleteForMe("d4", in: "deniz")
        let rows = model.rows
        #expect(!rows.contains { $0.id == "d4" })
        #expect(model.leavingRows.map(\.row.id) == ["d4"])
        #expect(model.leavingRows.first?.after == "d3")
        let shown = ConversationViewModel.displayedRows(rows, leaving: model.leavingRows)
        #expect(shown.map(\.id) == ["d0", "d1", "d2", "d3", "d4", "d5", "d6"])
        model.finishLeaving("d4")
        #expect(model.leavingRows.isEmpty)
        #expect(ConversationViewModel.displayedRows(model.rows, leaving: model.leavingRows).map(\.id) == ["d0", "d1", "d2", "d3", "d5", "d6"])
    }

    @Test func consecutiveRemovalsStayInOrder() {
        let rows = ConversationViewModel.rows(for: DesignFixtures.denizMessages(english: false))
        let kept = rows.filter { $0.id != "d2" && $0.id != "d3" }
        let leaving = [
            ConversationViewModel.LeavingRow(row: rows[2], after: "d1"),
            ConversationViewModel.LeavingRow(row: rows[3], after: "d2"),
        ]
        #expect(ConversationViewModel.displayedRows(kept, leaving: leaving).map(\.id) == rows.map(\.id))
    }

    @Test func deleteForEveryoneMorphsFromTheOldBubble() async {
        let (model, service) = makeModel()
        _ = model.rows
        await service.deleteForEveryone("d5", in: "deniz")
        _ = model.rows
        #expect(model.kindChanges["d5"]?.contentKind == "text")
        #expect(model.leavingRows.isEmpty, "the trace stays in place: nothing leaves")
        model.finishKindChange("d5")
        #expect(model.kindChanges.isEmpty)
    }

    @Test func stressFixtureIsDeterministic() {
        let end = DesignFixtures.referenceDate
        let a = ConversationStressFixture.messages(count: 5000, before: end)
        let b = ConversationStressFixture.messages(count: 5000, before: end)
        #expect(a.count == 5000)
        #expect(a == b)
        #expect(Set(a.map(\.id)).count == 5000)
        #expect(a.last.map { $0.sentAt < end } == true)
        #expect(zip(a, a.dropFirst()).allSatisfy { $0.sentAt < $1.sentAt })
        // Variety: several kinds and both directions.
        #expect(Set(a.map(\.contentKind)).isSuperset(of: ["text", "photo", "voice", "file", "deleted", "system"]))
        #expect(Set(a.map(\.direction)).count == 2)
    }

    @Test func bubbleTimesMatchTheSharedFormat() {
        for identifier in ["tr_TR", "en_US", "en_GB"] {
            let locale = Locale(identifier: identifier)
            for minutes in [0, 9 * 60 + 41, 18 * 60 + 10, 23 * 60 + 59] {
                let date = DesignFixtures.date(day: 8, minutes / 60, minutes % 60)
                #expect(BubbleTimeFormat.string(date, locale: locale) == U2Format.clockTime(date, locale: locale))
            }
        }
    }

    @Test func bubbleImagesDecodeOffTheMainThreadAtTheirShownSize() async throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("bubble-\(UUID().uuidString).png")
        let source = UIGraphicsImageRenderer(size: CGSize(width: 600, height: 400), format: {
            let format = UIGraphicsImageRendererFormat()
            format.scale = 1
            return format
        }()).image { context in
            UIColor.systemTeal.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 600, height: 400))
        }
        try #require(source.pngData()).write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }
        #expect(BubbleImageCache.shared.image(for: url, maxPixel: 300) == nil)
        let image = try #require(await BubbleImageCache.shared.load(url, maxPixel: 300))
        #expect(max(image.size.width * image.scale, image.size.height * image.scale) == 300)
        #expect(BubbleImageCache.shared.image(for: url, maxPixel: 300) === image, "kept for the next time the row appears")
        let missing = FileManager.default.temporaryDirectory.appendingPathComponent("missing-\(UUID().uuidString).png")
        #expect(await BubbleImageCache.shared.load(missing, maxPixel: 300) == nil)
    }

    @Test func typingNeedsAnAcceptedChat() {
        let (model, _) = makeModel()
        #expect(!model.isPeerTyping)
        model.debugPeerTyping = true
        #expect(model.isPeerTyping)
    }
}
