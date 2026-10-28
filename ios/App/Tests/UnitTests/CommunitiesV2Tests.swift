import Foundation
import SwiftUI
import Testing
import Us2usKit
import Us2usStorage

@testable import us2us

// Communities extension v2 in the app: the chats list mixes communities with
// one-to-one chats, the shared conversation screens show community messages
// (sender names, media, placeholders, aggregate states under the mutual
// read-receipt rule), the long-press menu and “Bilgi”.

private let tr = Locale(identifier: "tr_TR")
private let en = Locale(identifier: "en_TR")

/// A communities service with set data (no runtime).
@MainActor
@Observable
final class StubCommunitiesService: CommunitiesService {
    var rows: [CommunityRow] = []
    var isSupported = true
    var problem: CommunityProblem?
    var directory: [CommunityDirectoryEntry] = []
    var hasMoreResults = false
    var openRequests: [CommunityJoinRequestRow] = []
    var chatRows: [CommunityChatRow] = []
    var messages: [Message] = []
    var owner = false
    var recipientList: [CommunityRecipientInfo] = []
    var sentTexts: [String] = []
    var sendFails: CommunityProblem?
    var deletedForEveryone: [MessageID] = []
    var deletedForMe: [MessageID] = []
    var resent: [MessageID] = []
    var resendFails: CommunityProblem?

    func row(_ id: CommunityID) -> CommunityRow? { rows.first { $0.id == id } }
    func members(of id: CommunityID) -> [CommunityMemberRow] { [] }
    func screenAppeared() {}
    func screenDisappeared() {}
    func refresh() async {}
    func dismissProblem() { problem = nil }
    func search(_ query: String, more: Bool) async throws(CommunityProblem) {}
    func create(name: String, isPublic: Bool) async throws(CommunityProblem) {}
    func requestJoin(_ id: CommunityID) async throws(CommunityProblem) {}
    func redeem(_ code: String) async throws(CommunityProblem) {}
    func leave(_ id: CommunityID) async throws(CommunityProblem) {}
    func withdraw(_ id: CommunityID) async throws(CommunityProblem) {}
    func forget(_ id: CommunityID) async throws(CommunityProblem) {}
    func open(_ id: CommunityID) async throws(CommunityProblem) {}
    func close(_ id: CommunityID) {}
    func send(_ text: String, to id: CommunityID) async throws(CommunityProblem) {
        if let sendFails { throw sendFails }
        sentTexts.append(text)
    }
    func approve(_ request: CommunityID, in id: CommunityID) async throws(CommunityProblem) {}
    func reject(_ request: CommunityID, in id: CommunityID) async throws(CommunityProblem) {}
    func removeMember(_ member: String, from id: CommunityID) async throws(CommunityProblem) {}
    func invite(_ id: CommunityID) async throws(CommunityProblem) -> String { "" }
    func revokeInvites(_ id: CommunityID) async throws(CommunityProblem) {}
    func delete(_ id: CommunityID) async throws(CommunityProblem) {}
    func conversation(_ id: CommunityID) -> [Message] { messages }
    func isOwner(of id: CommunityID) -> Bool { owner }
    func send(attachment: ConversationAttachment, to id: CommunityID) async {}
    func send(pickedMedia messages: [PickedMediaMessage], to id: CommunityID) async {}
    func mediaPayload(_ message: MessageID, in id: CommunityID) async -> MediaPayload? { nil }
    func downloadMedia(_ message: MessageID, in id: CommunityID) async {}
    func deleteForEveryone(_ message: MessageID, in id: CommunityID) async throws(CommunityProblem) {
        deletedForEveryone.append(message)
    }
    func deleteForMe(_ message: MessageID, in id: CommunityID) async throws(CommunityProblem) { deletedForMe.append(message) }
    func resend(_ message: MessageID, in id: CommunityID) async throws(CommunityProblem) {
        if let resendFails { throw resendFails }
        resent.append(message)
    }
    func recipients(of message: MessageID, in id: CommunityID) async -> [CommunityRecipientInfo] { recipientList }
}

@Suite("Communities v2 · chats list, conversation mapping, menu and info")
@MainActor
struct CommunitiesV2Tests {
    static let community = "6f1c2a8e-41d2-4d55-9a0e-2f7c1b3d9e10"
    static let t0 = Date(timeIntervalSince1970: 1_790_000_000)

    private func chat(_ id: String, at date: Date?) -> ChatSummary {
        ChatSummary(
            id: id, peer: Peer(username: id, palette: .a, isVerified: false, isAcceptedContact: true, presence: .hidden),
            lastMessage: date.map { MessagePreview(kind: .text("x"), direction: .incoming, status: nil, sentAt: $0) },
            unreadCount: 0, ghost: .off, disappearing: .off)
    }

    private func communityRow(_ id: String, at date: Date?, unread: Int = 0) -> CommunityChatRow {
        CommunityChatRow(
            id: id, name: "Topluluk \(id)", isPublic: true,
            lastMessage: date.map {
                MessagePreview(
                    kind: .photo, direction: .incoming, status: nil, sentAt: $0,
                    sender: CommunitySenderName(username: "deniz", shortID: "1a2b3c4d"))
            },
            lastActivity: date, unreadCount: unread)
    }

    // MARK: Chats list

    @Test("communities and direct chats share latest-message order; empty chats follow all messages")
    func chatListOrdering() {
        let chats = [chat("ayse", at: Self.t0.addingTimeInterval(500)), chat("bora", at: Self.t0.addingTimeInterval(100)),
                     chat("yeni", at: nil), chat("cem", at: Self.t0)]
        let communities = [communityRow("c1", at: Self.t0.addingTimeInterval(300), unread: 2),
                           communityRow("c2", at: Self.t0.addingTimeInterval(900)), communityRow("c3", at: nil),
                           communityRow("c4", at: Self.t0.addingTimeInterval(50))]
        let merged = ChatListOrder.merged(chats: chats, communities: communities)
        #expect(merged.map(\.id) == [
            "community.c2", "ayse", "community.c1", "bora", "community.c4", "cem", "yeni", "community.c3",
        ])
        let row = merged[2]
        #expect(row.community?.id == "c1" && row.unreadCount == 2 && row.peer.username == "Topluluk c1")
        #expect(row.lastMessage?.sender?.username == "deniz")
        #expect(ChatListOrder.merged(chats: chats, communities: []).map(\.id) == ["ayse", "bora", "cem", "yeni"])
    }

    @Test("unsorted sources and empty new chats cannot displace the most recent conversation")
    func unsortedChatListOrdering() {
        let direct = [chat("empty", at: nil), chat("old", at: Self.t0), chat("recent", at: Self.t0.addingTimeInterval(500))]
        let groups = [communityRow("middle", at: Self.t0.addingTimeInterval(300)), communityRow("newest", at: Self.t0.addingTimeInterval(900)), communityRow("empty", at: nil)]
        #expect(ChatListOrder.merged(chats: direct, communities: groups).map(\.id) ==
                ["community.newest", "recent", "community.middle", "old", "empty", "community.empty"])
        #expect(ChatListOrder.merged(chats: direct, communities: []).map(\.id) == ["recent", "old", "empty"])
        #expect(ChatListOrder.merged(chats: [], communities: groups).map(\.id) == ["community.newest", "community.middle", "community.empty"])
    }

    @Test("local message activity wins over a sender clock; unread counts do not change order; ties are stable")
    func chatListLocalActivity() {
        var old = chat("old", at: Self.t0.addingTimeInterval(9000))
        old.lastMessageActivity = Self.t0
        old.unreadCount = 99
        var newest = chat("new", at: Self.t0.addingTimeInterval(-300))
        newest.lastMessageActivity = Self.t0.addingTimeInterval(500)
        newest.lastMessage = MessagePreview(kind: .voice, direction: .outgoing, status: .sent,
                                           sentAt: Self.t0.addingTimeInterval(-300))
        let group = communityRow("middle", at: Self.t0.addingTimeInterval(300))
        #expect(ChatListOrder.merged(chats: [old, newest], communities: [group]).map(\.id) == ["new", "community.middle", "old"])
        let tie = chat("tie", at: Self.t0.addingTimeInterval(500))
        #expect(ChatListOrder.merged(chats: [newest, tie], communities: []).map(\.id) == ["new", "tie"])
        // An incoming community photo becomes newest after its next message.
        #expect(ChatListOrder.merged(chats: [old, newest], communities: [communityRow("middle", at: Self.t0.addingTimeInterval(600))]).first?.id == "community.middle")
    }

    @Test("a community row renders its name, the sender of the last message and the unread count", arguments: ["tr", "en"])
    func communityRowRenders(_ language: String) {
        let summary = communityRow("c1", at: Self.t0, unread: 3).chatSummary()
        let size = layoutSize(ChatListRow(chat: summary), width: 390, locale: language)
        #expect(size.height >= 76)
    }

    // MARK: Mapping

    private func item(
        outgoing: Bool, status: CommunityDeliveryStatus = .sent, kind: CommunityMessageKind = .text, text: String = "Merhaba",
        attachment: CommunityAttachmentItem? = nil, deletion: CommunityDeletion? = nil, receipts: CommunityReceiptSummary? = nil,
        sender: AccountId
    ) -> CommunityTextItem {
        CommunityTextItem(
            communityId: AccountId(string: Self.community)!, sender: sender, messageId: AccountId(uuid: UUID()), text: text,
            sentAt: 1_790_000_000_000, receivedAt: 1_790_000_000_000, outgoing: outgoing, status: outgoing ? status : .received,
            kind: kind, attachment: attachment, deletion: deletion, receipts: receipts)
    }

    private func photo(state: CommunityAttachmentState, hasFile: Bool) -> CommunityAttachmentItem {
        var descriptor = BRPAttachment()
        descriptor.kind = .image
        descriptor.size = 1234
        descriptor.width = 400
        descriptor.height = 300
        descriptor.thumbnail = Data([0xFF, 0xD8])
        descriptor.contentType = "image/jpeg"
        return CommunityAttachmentItem(itemId: RandomId.make(), descriptor: descriptor, state: state, hasFile: hasFile)
    }

    @Test("own messages show sent → delivered to all → read by all; Read only while read receipts are on")
    func aggregateStatusFollowsTheMutualRule() {
        let own = AccountId(uuid: UUID())
        func status(_ receipts: CommunityReceiptSummary?, readReceipts: Bool, base: CommunityDeliveryStatus = .sent) -> us2us.MessageStatus? {
            RealCommunitiesService.message(
                item(outgoing: true, status: base, receipts: receipts, sender: own), own: own, names: [:], media: [:],
                downloads: [:], readReceipts: readReceipts
            ).status
        }
        #expect(status(nil, readReceipts: true, base: .sending) == .sending)
        #expect(status(CommunityReceiptSummary(accepted: 3, delivered: 0, read: 0, pending: 0), readReceipts: true) == .sent)
        #expect(status(CommunityReceiptSummary(accepted: 3, delivered: 2, read: 2, pending: 0), readReceipts: true) == .sent)
        #expect(status(CommunityReceiptSummary(accepted: 3, delivered: 3, read: 2, pending: 0), readReceipts: true) == .delivered)
        #expect(status(CommunityReceiptSummary(accepted: 3, delivered: 3, read: 3, pending: 0), readReceipts: true) == .read)
        // Read receipts off: never “Okundu”.
        #expect(status(CommunityReceiptSummary(accepted: 3, delivered: 3, read: 3, pending: 0), readReceipts: false) == .delivered)
        // A recipient still pending keeps it at “Gönderildi”.
        #expect(status(CommunityReceiptSummary(accepted: 2, delivered: 2, read: 2, pending: 1), readReceipts: true) == .sent)
        #expect(status(nil, readReceipts: true, base: .failed) == .failed)
        // Some members could not be reached: the others' receipts still add up.
        #expect(status(CommunityReceiptSummary(accepted: 2, delivered: 2, read: 0, pending: 0), readReceipts: true, base: .partial) == .delivered)
    }

    @Test("received media, placeholders and sender names map onto the one-to-one bubbles")
    func messageMapping() {
        let own = AccountId(uuid: UUID())
        let deniz = AccountId(uuid: UUID())
        let names = [deniz: "deniz"]
        func map(_ item: CommunityTextItem, downloads: [String: Double] = [:]) -> Message {
            RealCommunitiesService.message(item, own: own, names: names, media: [:], downloads: downloads, readReceipts: true)
        }
        let remote = item(outgoing: false, kind: .media, text: "Gün batımı", attachment: photo(state: .remote, hasFile: false), sender: deniz)
        let mapped = map(remote)
        #expect(mapped.direction == .incoming && mapped.status == nil)
        #expect(mapped.mediaAvailability == .remote && mapped.mediaSize == MediaDimensions(width: 400, height: 300))
        if case .photo(let source, let caption) = mapped.content {
            #expect(source == .memory(Data([0xFF, 0xD8])) && caption == "Gün batımı")
        } else {
            Issue.record("not a photo")
        }
        #expect(mapped.community?.sender == CommunitySenderName(username: "deniz", shortID: CommunityIdentifiers.shortID(deniz.string)))
        #expect(map(remote, downloads: [remote.id: 0.4]).mediaAvailability == .downloading(0.4))
        #expect(map(item(outgoing: false, kind: .media, attachment: photo(state: .unavailable, hasFile: false), sender: deniz)).mediaAvailability == .unavailable)
        #expect(map(item(outgoing: false, kind: .media, attachment: photo(state: .downloaded, hasFile: true), sender: deniz)).mediaAvailability == .ready)

        let removed = map(item(outgoing: false, text: "", deletion: .byOwner, sender: deniz))
        #expect(removed.isDeleted && removed.community?.removedByOwner == true)
        let deleted = map(item(outgoing: false, text: "", deletion: .bySender, sender: deniz))
        #expect(deleted.isDeleted && deleted.community?.removedByOwner == false)

        let stranger = AccountId(uuid: UUID())
        #expect(map(item(outgoing: false, sender: stranger)).community?.sender?.username == nil)
        let mine = map(item(outgoing: true, sender: own))
        #expect(mine.community?.sender == nil && mine.isOutgoing)
        // An own photo still uploading is “Gönderiliyor” and opens from here.
        let uploading = map(item(outgoing: true, status: .sending, kind: .media, attachment: photo(state: .uploading, hasFile: true), sender: own))
        #expect(uploading.status == .sending && uploading.mediaAvailability == .ready)
    }

    private func media(_ kind: BRPAttachment.Kind, fileName: String = "") -> CommunityAttachmentItem {
        var descriptor = BRPAttachment()
        descriptor.kind = kind
        descriptor.size = 4096
        descriptor.durationMs = kind == .video ? 18_000 : 0
        descriptor.fileName = fileName
        descriptor.contentType = kind == .video ? "video/mp4" : "application/pdf"
        return CommunityAttachmentItem(itemId: RandomId.make(), descriptor: descriptor, state: .remote, hasFile: false)
    }

    @Test("a video's or file's caption is a text row under it, received and sent; a photo keeps its own")
    func videoAndFileCaptions() {
        let own = AccountId(uuid: UUID())
        let deniz = AccountId(uuid: UUID())
        func map(_ item: CommunityTextItem) -> Message {
            RealCommunitiesService.message(item, own: own, names: [:], media: [:], downloads: [:], readReceipts: true)
        }
        let video = media(.video)
        let file = media(.file, fileName: "plan.pdf")
        for (outgoing, sender) in [(false, deniz), (true, own)] {
            let mappedVideo = map(item(outgoing: outgoing, kind: .media, text: "Sahil", attachment: video, sender: sender))
            #expect(mappedVideo.community?.caption == "Sahil" && mappedVideo.copyableText == "Sahil")
            if case .video = mappedVideo.content {} else { Issue.record("not a video") }
            let mappedFile = map(item(outgoing: outgoing, kind: .media, text: "Toplantı notları", attachment: file, sender: sender))
            #expect(mappedFile.community?.caption == "Toplantı notları" && mappedFile.copyableText == "Toplantı notları")
            if case .file(let name, _, _, _) = mappedFile.content { #expect(name == "plan.pdf") } else { Issue.record("not a file") }
        }
        // Without a caption: no row.
        #expect(map(item(outgoing: false, kind: .media, text: "", attachment: video, sender: deniz)).community?.caption == nil)
        #expect(map(item(outgoing: false, kind: .media, text: "", attachment: file, sender: deniz)).copyableText == nil)
        // A photo carries its caption in its own bubble.
        let mappedPhoto = map(item(outgoing: false, kind: .media, text: "Gün batımı", attachment: photo(state: .remote, hasFile: false), sender: deniz))
        #expect(mappedPhoto.community?.caption == nil && mappedPhoto.copyableText == "Gün batımı")
        // A deleted message keeps nothing.
        #expect(map(item(outgoing: false, kind: .media, text: "", deletion: .bySender, sender: deniz)).community?.caption == nil)
    }

    @Test("the caption row adds height under a video and a file bubble", arguments: ["tr", "en"])
    func captionRowsRender(_ language: String) {
        func bubble(_ content: MessageContent, caption: String?, outgoing: Bool) -> Message {
            var message = Message(
                id: "m", direction: outgoing ? .outgoing : .incoming, sentAt: Self.t0, content: content, status: outgoing ? .sent : nil)
            message.community = CommunityMessageMarks(senderID: "a", caption: caption)
            return message
        }
        for outgoing in [false, true] {
            let video = MessageContent.video(.memory(Data()), duration: 18, byteCount: 4096)
            let file = MessageContent.file(name: "plan.pdf", byteCount: 4096, typeLabel: "PDF", pageCount: nil)
            let plainVideo = layoutSize(MessageBubbleView(message: bubble(video, caption: nil, outgoing: outgoing)), width: 390, locale: language)
            let captionedVideo = layoutSize(MessageBubbleView(message: bubble(video, caption: "Sahil", outgoing: outgoing)), width: 390, locale: language)
            #expect(captionedVideo.height > plainVideo.height)
            let plainFile = layoutSize(MessageBubbleView(message: bubble(file, caption: nil, outgoing: outgoing)), width: 390, locale: language)
            let captionedFile = layoutSize(MessageBubbleView(message: bubble(file, caption: "Notlar", outgoing: outgoing)), width: 390, locale: language)
            #expect(captionedFile.height > plainFile.height)
        }
    }

    @Test("chats list previews: kind, sender and placeholders")
    func previews() {
        let own = AccountId(uuid: UUID())
        let deniz = AccountId(uuid: UUID())
        let media = item(outgoing: false, kind: .media, attachment: photo(state: .remote, hasFile: false), sender: deniz)
        let preview = RealCommunitiesService.preview(media, own: own, names: [deniz: "deniz"])
        #expect(preview.kind == .photo && preview.sender?.username == "deniz" && preview.direction == .incoming)
        let removed = RealCommunitiesService.preview(item(outgoing: false, deletion: .byOwner, sender: deniz), own: own, names: [:])
        #expect(removed.kind == .communityDeleted(byOwner: true) && removed.sender?.username == nil)
        let mine = RealCommunitiesService.preview(item(outgoing: true, text: "Selam", sender: own), own: own, names: [:])
        #expect(mine.kind == .text("Selam") && mine.sender == nil && mine.status == .sent)
    }

    @Test("message ids: own by message id, received by sender and message id")
    func messageKeys() {
        let own = AccountId(uuid: UUID())
        let other = AccountId(uuid: UUID())
        let mine = item(outgoing: true, sender: own)
        let theirs = item(outgoing: false, sender: other)
        let mineID = CommunityMessageKey.id(mine, own: own)
        let theirsID = CommunityMessageKey.id(theirs, own: own)
        #expect(mineID == LiveMapping.messageID(mine.messageId.bytes))
        #expect(CommunityMessageKey.parse(mineID, own: own)?.sender == own)
        #expect(CommunityMessageKey.parse(theirsID, own: own)?.sender == other)
        #expect(CommunityMessageKey.parse(theirsID, own: own)?.message == theirs.messageId)
        #expect(CommunityMessageKey.parse("zz:yy", own: own) == nil)
    }

    // MARK: Conversation screen

    private func conversation(owner: Bool, messages: [Message]) -> (ConversationViewModel, StubCommunitiesService) {
        let stub = StubCommunitiesService()
        stub.owner = owner
        stub.messages = messages
        stub.rows = [CommunityRow(id: Self.community, name: "Mahalle", isPublic: true, memberCount: 3, phase: owner ? .owner : .member)]
        let base = DesignFixturesService(hasAccount: true, clock: .frozen(at: DesignFixtures.referenceDate), latency: .zero)
        let messenger = CommunityChatMessenger(base: base, communityID: Self.community, service: stub)
        return (ConversationViewModel(chatID: Self.community, messenger: messenger), stub)
    }

    private func message(_ id: String, from sender: String?, text: String = "x") -> Message {
        var message = Message(
            id: id, direction: sender == nil ? .outgoing : .incoming, sentAt: Self.t0, content: .text(text),
            status: sender == nil ? .sent : nil)
        message.community = CommunityMessageMarks(
            senderID: sender ?? "me", sender: sender.map { CommunitySenderName(username: $0, shortID: "1a2b3c4d") })
        return message
    }

    @Test("incoming runs name their sender once and end where the sender changes")
    func senderNamesAndTails() {
        let messages = [message("1", from: "deniz"), message("2", from: "deniz"), message("3", from: "ece"), message("4", from: nil),
                        message("5", from: "ece")]
        let rows = ConversationViewModel.rows(for: messages)
        #expect(rows.map(\.showsSender) == [true, false, true, false, true])
        #expect(rows.map(\.isTail) == [false, true, true, true, true])
        // One-to-one chats are unchanged: no names.
        var plain = messages
        for index in plain.indices { plain[index].community = nil }
        #expect(ConversationViewModel.rows(for: plain).allSatisfy { !$0.showsSender })
    }

    @Test("“Herkesten sil”: own messages, and for the owner everyone's; never on a placeholder")
    func deleteForEveryoneRights() {
        var deleted = message("3", from: "deniz")
        deleted.content = .deleted
        let messages = [message("1", from: nil), message("2", from: "deniz"), deleted]
        let (member, _) = conversation(owner: false, messages: messages)
        #expect(member.community != nil)
        #expect(member.canDeleteForEveryone(messages[0]))
        #expect(!member.canDeleteForEveryone(messages[1]))
        #expect(!member.canDeleteForEveryone(messages[2]))
        let (owner, _) = conversation(owner: true, messages: messages)
        #expect(owner.canDeleteForEveryone(messages[1]))
        #expect(!owner.canDeleteForEveryone(messages[2]))
    }

    @Test("the long-press menu: Bilgi for own messages, no reactions or replies; the owner may remove others' messages")
    func communityMenu() {
        let messages = [message("1", from: nil), message("2", from: "deniz")]
        func ids(owner: Bool, _ message: Message) -> [String] {
            let (viewModel, _) = conversation(owner: owner, messages: messages)
            let overlay = MessageActionsOverlay(
                viewModel: viewModel, context: .init(messageID: message.id, isTail: true, sourceFrame: .zero), message: message)
            return overlay.communityMenuItems.map(\.id)
        }
        #expect(ids(owner: false, messages[0]) == ["info", "copy", "deleteEveryone", "deleteMe"])
        #expect(ids(owner: false, messages[1]) == ["copy", "deleteMe"])
        #expect(ids(owner: true, messages[1]) == ["copy", "deleteEveryone", "deleteMe"])
    }

    @Test("a text that could not be queued comes back to the composer")
    func failedTextReturnsToComposer() async throws {
        let (viewModel, stub) = conversation(owner: false, messages: [])
        stub.sendFails = .offline
        viewModel.draft = "Merhaba"
        viewModel.sendDraft()
        for _ in 0..<100 where viewModel.draft.isEmpty { try await Task.sleep(for: .milliseconds(10)) }
        #expect(viewModel.draft == "Merhaba")
        #expect((viewModel.community?.problem) == .offline)
        stub.sendFails = nil
        viewModel.sendDraft()
        for _ in 0..<100 where stub.sentTexts.isEmpty { try await Task.sleep(for: .milliseconds(10)) }
        #expect(stub.sentTexts == ["Merhaba"] && viewModel.draft.isEmpty)
    }

    @Test("“Tekrar gönder” is offered under a failed community message while a member, and reaches the community")
    func resendFailedMessage() async throws {
        var failed = message("f", from: nil)
        failed.status = .failed
        let (viewModel, stub) = conversation(owner: false, messages: [failed])
        #expect(viewModel.offersResend(failed))
        viewModel.resend("f")
        for _ in 0..<100 where stub.resent.isEmpty { try await Task.sleep(for: .milliseconds(10)) }
        #expect(stub.resent == ["f"])
        // A failure is explained on the screen.
        stub.resendFails = .stale
        await viewModel.messenger.resend("f", in: Self.community)
        #expect((viewModel.community?.problem) == .stale)
        // Removed from the community: the history stays, nothing is sent again.
        stub.rows = [CommunityRow(id: Self.community, name: "Mahalle", isPublic: true, memberCount: 3, phase: .ended)]
        #expect(!viewModel.offersResend(failed))
        // One-to-one chats are unchanged.
        var plain = failed
        plain.community = nil
        #expect(viewModel.offersResend(plain))
    }

    @Test("community media is saved without telling anyone (no SAVED in v2)")
    func savingIsNotReported() {
        var photo = message("p", from: "deniz")
        photo.content = .photo(.memory(Data()), caption: nil)
        #expect(!MediaExportPolicy.tellsSender(photo))
        photo.community = nil
        #expect(MediaExportPolicy.tellsSender(photo))
    }

    // MARK: Info

    @Test("Bilgi lists every recipient by name with delivered and read times, pending, failed and left")
    func infoRows() {
        let deliveredAt = Self.t0
        let readAt = Self.t0.addingTimeInterval(120)
        let read = CommunityRecipientInfo(
            id: "a", username: "deniz", shortID: "1a2b3c4d", state: .accepted, deliveredAt: deliveredAt, readAt: readAt, hasLeft: false)
        let pending = CommunityRecipientInfo(id: "b", username: nil, shortID: "9e10aa01", state: .pending, deliveredAt: nil, readAt: nil, hasLeft: false)
        let left = CommunityRecipientInfo(id: "c", username: nil, shortID: "41d24d55", state: .skipped, deliveredAt: nil, readAt: nil, hasLeft: true)
        let time = U2Format.clockTime(deliveredAt, locale: tr)
        let readTime = U2Format.clockTime(readAt, locale: tr)
        #expect(CommunityRecipientRow.detail(for: read, showsRead: true, locale: tr) == "İletildi \(time) · Okundu \(readTime)")
        // Read receipts off: no “Okundu”.
        #expect(CommunityRecipientRow.detail(for: read, showsRead: false, locale: tr) == "İletildi \(time)")
        #expect(CommunityRecipientRow.detail(for: pending, showsRead: true, locale: tr) == "Bekliyor")
        #expect(CommunityRecipientRow.detail(for: pending, showsRead: true, locale: en) == "Pending")
        #expect(CommunityRecipientRow.detail(for: left, showsRead: true, locale: en) == "Left · Not sent · no longer a member")
    }

    @Test("Bilgi lists who read it first, then delivered, waiting and failed; names keep their order within each")
    func infoOrder() {
        func info(_ id: String, _ state: CommunityRecipientInfo.State, delivered: Bool = false, read: Bool = false) -> CommunityRecipientInfo {
            CommunityRecipientInfo(
                id: id, username: id, shortID: id, state: state, deliveredAt: delivered ? Self.t0 : nil,
                readAt: read ? Self.t0 : nil, hasLeft: false)
        }
        let roster = [
            info("ali", .failed), info("bora", .accepted, delivered: true), info("cem", .accepted),
            info("deniz", .accepted, delivered: true, read: true), info("ece", .pending), info("fatih", .accepted, delivered: true, read: true),
        ]
        #expect(CommunityRecipientRow.ordered(roster, showsRead: true).map(\.id) == ["deniz", "fatih", "bora", "cem", "ece", "ali"])
        // Read receipts off: read counts as delivered only.
        #expect(CommunityRecipientRow.ordered(roster, showsRead: false).map(\.id) == ["bora", "deniz", "fatih", "cem", "ece", "ali"])
    }

    @Test("recipient infos: names, local times, members who left, sorted by name")
    func recipientInfos() {
        let a = AccountId(uuid: UUID()), b = AccountId(uuid: UUID())
        let rows = [
            CommunityRecipientItem(recipient: b, state: .accepted, deliveredAt: 1_790_000_000_000, readAt: nil),
            CommunityRecipientItem(recipient: a, state: .pending, deliveredAt: nil, readAt: nil),
        ]
        let infos = RealCommunitiesService.recipientInfos(rows, names: [b: "ayse"], members: [a])
        #expect(infos.map(\.id) == [b.string, a.string])
        #expect(infos[0].username == "ayse" && infos[0].hasLeft && infos[0].deliveredAt == Date(timeIntervalSince1970: 1_790_000_000))
        #expect(infos[1].state == .pending && !infos[1].hasLeft && infos[1].username == nil)
    }

    @Test("the adapter answers the conversation from the community and forwards everything else")
    func adapter() async {
        let (viewModel, stub) = conversation(owner: false, messages: [message("1", from: "deniz")])
        #expect(viewModel.chat?.id == Self.community && viewModel.chat?.community?.id == Self.community)
        #expect(viewModel.peerUsername == "Mahalle")
        #expect(viewModel.messages.map(\.id) == ["1"])
        await viewModel.messenger.deleteForEveryone("1", in: Self.community)
        await viewModel.messenger.deleteForMe("1", in: Self.community)
        #expect(stub.deletedForEveryone == ["1"] && stub.deletedForMe == ["1"])
        #expect(await viewModel.messenger.media(in: Self.community, kind: .visual, before: nil, limit: 10).isEmpty)
        #expect(viewModel.messenger.messages(in: "another").isEmpty)
    }

    // MARK: Texts

    nonisolated static let newKeys = [
        "status.sent.sentence", "status.delivered.sentence", "status.failed.sentence", "message.deleted", "message.removedByOwner",
        "confirm.deleteEveryone.message", "newChat.subtitle", "info.recipients.none", "info.note.title", "info.note.body",
        "info.recipient.pending", "info.recipient.waiting", "info.recipient.skipped", "info.recipient.failed", "info.recipient.left",
        "search.searching",
    ]

    @Test(arguments: newKeys)
    func newTextsAreInBothLanguages(_ key: String) {
        let turkish = StringTable.communities.string(String.LocalizationValue(key), locale: tr)
        let english = StringTable.communities.string(String.LocalizationValue(key), locale: en)
        #expect(turkish != key && english != key)
        #expect(turkish != english)
    }

    @Test func placeholdersSayWhoDeleted() {
        #expect(StringTable.communities.string("message.deleted", locale: tr) == "Bu mesaj silindi")
        #expect(StringTable.communities.string("message.deleted", locale: en) == "This message was deleted")
        #expect(StringTable.communities.string("message.removedByOwner", locale: tr) == "Yönetici tarafından kaldırıldı")
        #expect(StringTable.communities.string("message.removedByOwner", locale: en) == "Removed by the owner")
    }
}
