import Foundation
import Testing
@testable import us2us

@MainActor
@Suite("Conversation photos and albums")
struct ConversationPhotosTests {
    private func photo(_ id: Int, seconds: TimeInterval? = nil, caption: String? = nil) -> Message {
        Message(id: "p\(id)", direction: .outgoing, sentAt: Date(timeIntervalSince1970: 1_000 + (seconds ?? Double(id))),
            content: .photo(.designArtwork(.nightPier), caption: caption), status: .sent)
    }

    @Test func burstsKeepEveryMessageAndCaption() {
        let photos = [photo(0, caption: "Caption"), photo(1), photo(2)]
        #expect(ConversationPhotos.groups(photos) == [photos])
        #expect(ConversationPhotos.groups(photos + [photo(3, caption: "Another")]).map(\.count) == [3, 1])
    }

    @Test func albumsHaveBoundedCountAndTime() {
        #expect(ConversationPhotos.groups((0..<12).map { photo($0) }).map(\.count) == [10, 2])
        #expect(ConversationPhotos.groups([photo(0, seconds: 0), photo(1, seconds: 60), photo(2, seconds: 61)]).map(\.count) == [2, 1])
        #expect(!ConversationPhotos.canAppend([photo(0, seconds: 10)], photo(1, seconds: 9)))
    }

    @Test func senderDirectionAndInterveningMessagesSplitAlbums() {
        var first = photo(0)
        first.community = CommunityMessageMarks(senderID: "alice")
        var second = photo(1)
        second.community = CommunityMessageMarks(senderID: "bob")
        #expect(ConversationPhotos.groups([first, second]).count == 2)
        let incoming = Message(id: "in", direction: .incoming, sentAt: photo(1).sentAt,
            content: .photo(.designArtwork(.nightPier), caption: nil))
        #expect(ConversationPhotos.groups([photo(0), incoming]).count == 2)
        var text = photo(9); text.content = .text("Between")
        #expect(ConversationPhotos.groups([photo(0), text, photo(1)]).count == 3)
    }

    @Test func privacyAndFailureMarksStayOutsideAlbums() {
        var special = Array(repeating: photo(1), count: 7)
        special[0].disappearing = .thirtySeconds
        special[1].savedByPeer = true
        special[2].status = .failed
        special[3].mediaAvailability = .preparing
        special[4].mediaAvailability = .preparationFailed
        special[5].replyTo = QuotedMessage(author: "alice", kind: .text("Original"))
        special[6].reactions = [Reaction(emoji: "❤️")]
        for message in special {
            #expect(ConversationPhotos.groups([photo(0), message, photo(2)]).map(\.count) == [1, 1, 1])
        }
    }

    @Test func galleryExcludesOtherKindsAndPendingPhotos() {
        var deleted = photo(1); deleted.content = .deleted
        var text = photo(2); text.content = .text("No")
        var pending = photo(3); pending.mediaAvailability = .preparing
        var remote = photo(4); remote.mediaAvailability = .remote
        var unavailable = photo(5); unavailable.mediaAvailability = .unavailable
        #expect([photo(0), deleted, text, pending, remote, unavailable].filter(ConversationPhotos.isBrowsable).map(\.id) == ["p0", "p4", "p5"])
    }

    @Test func readStatusRequiresEveryPhotoToBeRead() {
        var read = photo(0); read.status = .read
        #expect(ConversationPhotos.status([read, photo(1)]) == .sent)
        #expect(ConversationPhotos.status([read, read]) == .read)
    }

    @Test func displayAlbumsKeepStableIdentityAndSplitForDeletionOrMorphing() {
        let rows = ConversationViewModel.rows(for: [photo(0), photo(1), photo(2)])
        let album = ConversationPhotos.albums(rows, separate: [])
        #expect(album.count == 1)
        #expect(album[0].id == "p0")
        #expect(album[0].album.map(\.id) == ["p0", "p1", "p2"])
        #expect(album[0].showsStatusSentence)
        #expect(ConversationPhotos.albums(rows, separate: ["p1"]).count == 3)
        #expect(rows.count == 3)
        let removed = ConversationPhotos.albumRemovals(rows, leaving: ["p1"])
        #expect(removed == ["p1"])
        let remaining = ConversationPhotos.albums(rows.filter { !removed.contains($0.id) }, separate: [])
        #expect(remaining.count == 1)
        #expect(remaining[0].id == "p0")
        #expect(remaining[0].album.map(\.id) == ["p0", "p2"])
        #expect(ConversationPhotos.albumRemovals([rows[0]], leaving: ["p0"]).isEmpty)
    }

    @Test func aMissingPhotoCannotLeaveAnEmptyActionsOverlay() async throws {
        let service = DesignFixturesService(hasAccount: true, clock: .frozen(at: DesignFixtures.referenceDate), latency: .zero)
        let model = ConversationViewModel(chatID: "deniz", messenger: service)
        let photo = try #require(model.message("d2"))
        model.frames.set(CGRect(x: 20, y: 200, width: 258, height: 176), for: photo.id)
        model.openActions(for: photo, isTail: true)
        #expect(model.actions != nil)
        await service.deleteForMe(photo.id, in: "deniz")
        #expect(model.actionsTargetIsGone)
        model.clearMissingActions()
        #expect(model.actions == nil)
        #expect(!model.actionsVisible)
    }
}
