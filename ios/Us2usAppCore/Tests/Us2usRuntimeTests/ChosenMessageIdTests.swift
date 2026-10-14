import Foundation
import Testing
import Us2usKit
import Us2usStorage

@testable import Us2usRuntime

/// A picked video's bubble is on screen while the video is still being
/// prepared: the app gives it an id up front, and the stored message keeps
/// that id, so the bubble stays where it is.
@Suite("Chosen message ids")
struct ChosenMessageIdTests {
    @Test func theMessageIsStoredUnderTheChosenId() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        await fixture.start()
        let chosen = RandomId.make()
        let file = fixture.directory.directories.stagingFile(extension: "mp4")
        try Data("mp4 bytes".utf8).write(to: file)
        let media = PreparedMedia(
            file: file, kind: .video, contentType: "video/mp4", size: 9, width: 4, height: 3, durationMs: 18_000)
        let id = try await fixture.engine.send(.media(media), to: peer, messageId: chosen)
        #expect(id == chosen)
        let ids = try await fixture.messages(peer).map(\.messageId)
        #expect(ids.filter { $0 == chosen }.count == 1)
        #expect(!FileManager.default.fileExists(atPath: file.path), "moved into encrypted storage as usual")
        await fixture.engine.stop()
    }

    @Test func aTakenOrMalformedIdIsRefused() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        await fixture.start()
        let taken = try await fixture.engine.send(.text("bir"), to: peer)
        await #expect(throws: EngineError.invalidMessage) {
            try await fixture.engine.send(.text("iki"), to: peer, messageId: taken)
        }
        await #expect(throws: EngineError.invalidMessage) {
            try await fixture.engine.send(.text("üç"), to: peer, messageId: Data([1, 2, 3]))
        }
        let messages = try await fixture.messages(peer)
        #expect(messages.filter { $0.messageId == taken }.count == 1)
        #expect(messages.filter { $0.direction == .outgoing }.count == 1, "nothing else was stored")
        await fixture.engine.stop()
    }
}
