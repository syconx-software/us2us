// us2us — AGPL-3.0-only

import Foundation
import Testing
import Us2usKit
import Us2usStorage

@testable import Us2usRuntime

/// A download's progress reaches the conversation (the bubble's ring),
/// throttled so a 64 MiB clip does not refresh it for every chunk.
@Suite("Download progress")
struct DownloadProgressTests {
    @Test func theThrottlePassesOnEveryTwoPercentAndTheEnd() {
        let seen = LockedState<[Double]>([])
        let throttle = DownloadProgressThrottle { value in seen.withLock { $0.append(value) } }
        for step in 0...1_000 { throttle.update(Double(step) / 1_000) }
        throttle.update(1)
        throttle.update(1.5)
        let values = seen.withLock { $0 }
        #expect(values.first == 0)
        #expect(values.last == 1)
        #expect(values.filter { $0 == 1 }.count == 1, "the end once")
        #expect(values.count <= 52)
        #expect(zip(values, values.dropFirst()).allSatisfy { $1 - $0 >= 0.02 || $1 == 1 })
    }

    @Test func aDownloadingAttachmentReportsItsProgress() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        let fractions = LockedState<[Double]>([])
        let changes = fixture.engine.changes
        let listener = Task {
            for await change in changes {
                if case .downloadProgress(_, let from, let fraction) = change, from == peer {
                    fractions.withLock { $0.append(fraction) }
                }
            }
        }
        defer { listener.cancel() }
        await fixture.start()
        let mediaId = RandomId.make()
        fixture.backend.setDownload(Data("PHOTO".utf8), for: mediaId)
        var attachment = BRPAttachment()
        attachment.kind = .image
        attachment.mediaID = mediaId
        attachment.deleteToken = Data(repeating: 1, count: 32)
        attachment.key = Data(repeating: 2, count: 64)
        attachment.digest = Data(repeating: 3, count: 32)
        attachment.size = 5
        attachment.contentType = "image/jpeg"
        try await fixture.receive(.data(.attachment(attachment)), from: peer)
        try await eventually("progress reported") { fractions.withLock { $0 } == [0.5, 1] }
    }
}
