#if DEBUG
    import Foundation

    /// DEBUG: communities for the design fixtures (`-U2Communities YES`), so
    /// the communities screens can be opened, checked and screenshotted
    /// without a relay — the list, search, create, the roster and requests,
    /// a conversation with photos, a file, receipts (“kimin okuduğu”) and the
    /// deletes. In memory, nothing is sent anywhere. Off by default: the
    /// design draws no communities and the other UI tests expect none.
    @Observable
    @MainActor
    final class DesignFixtureCommunities: CommunitiesService {
        static let bookClub = "6f1c2a8e-41d2-4d55-9a0e-2f7c1b3d9e10"
        static let run = "8a3b4c5d-1e2f-4a5b-8c6d-7e8f9a0b1c2d"
        static let photographers = "2c4e6a8b-0d1f-4e3a-9b5c-7d9e1f3a5b7c"
        static let oldTeam = "9e8d7c6b-5a4f-4e3d-8c2b-1a0f9e8d7c6b"

        /// The launch argument that turns them on.
        static var isRequested: Bool { UserDefaults.standard.bool(forKey: "U2Communities") }

        private(set) var rows: [CommunityRow]
        var isSupported = true
        private(set) var problem: CommunityProblem?
        private(set) var directory: [CommunityDirectoryEntry] = []
        private(set) var hasMoreResults = false
        private(set) var openRequests: [CommunityJoinRequestRow]
        private var messagesByCommunity: [CommunityID: [Message]]
        private let english: Bool
        private let now: Date
        init(english: Bool, now: Date) {
            self.english = english
            self.now = now
            func t(_ tr: String, _ en: String) -> String { english ? en : tr }
            rows = [
                CommunityRow(id: Self.bookClub, name: t("Kitap kulübü", "Book club"), isPublic: false, memberCount: 6, phase: .owner),
                CommunityRow(id: Self.run, name: t("Pazar koşusu · Moda", "Sunday run · Moda"), isPublic: true, memberCount: 42, phase: .member),
                CommunityRow(id: Self.photographers, name: t("Kadıköy fotoğrafçıları", "Kadıköy photographers"), isPublic: true, memberCount: 87, phase: .pending),
                CommunityRow(id: Self.oldTeam, name: t("Eski ekip", "Old team"), isPublic: false, memberCount: nil, phase: .ended),
            ]
            openRequests = [
                CommunityJoinRequestRow(
                    id: "5b6c7d8e-9f0a-4b1c-8d2e-3f4a5b6c7d8e", username: nil, shortID: "5b6c7d8e",
                    keyFingerprint: "05ab cdef 1234 5678 9abc def0 1357 9bdf", requestedAt: now.addingTimeInterval(-3_600)),
                CommunityJoinRequestRow(
                    id: "6c7d8e9f-0a1b-4c2d-9e3f-4a5b6c7d8e9f", username: nil, shortID: "6c7d8e9f",
                    keyFingerprint: "05cd ef01 2345 6789 abcd ef02 468a ce13", requestedAt: now.addingTimeInterval(-1_800)),
            ]
            messagesByCommunity = [Self.bookClub: Self.bookClubMessages(english: english, now: now)]
        }

        private static func sender(_ username: String?, _ id: String) -> CommunityMessageMarks {
            CommunityMessageMarks(senderID: id, sender: CommunitySenderName(username: username, shortID: String(id.prefix(8))))
        }

        private static func bookClubMessages(english: Bool, now: Date) -> [Message] {
            func t(_ tr: String, _ en: String) -> String { english ? en : tr }
            let own = CommunityMessageMarks(senderID: "self")
            let deniz = sender("deniz", "1a2b3c4d-0000")
            let mert = sender("mert_k", "2b3c4d5e-0000")
            let stranger = sender(nil, "7f8e9d0c-0000")
            func at(_ minutes: Double) -> Date { now.addingTimeInterval(-minutes * 60) }
            return [
                Message(
                    id: "c1", direction: .incoming, sentAt: at(90),
                    content: .text(t("Bu ayın kitabı: Tutunamayanlar. Kim başladı?", "This month's book: The Disconnected. Who has started?")),
                    community: deniz),
                Message(
                    id: "c2", direction: .incoming, sentAt: at(88), content: .text(t("Ben yarıladım 📚", "Halfway through 📚")),
                    community: mert),
                Message(
                    id: "c3", direction: .incoming, sentAt: at(80),
                    content: .photo(.designArtwork(.nightPier), caption: t("Buluşma yeri burası", "This is where we meet")),
                    community: deniz),
                Message(
                    id: "c4", direction: .outgoing, sentAt: at(60), content: .text(t("Cumartesi 15:00 uygun mu?", "Does Saturday 3 pm work?")),
                    status: .read, deliveredAt: at(59), readAt: at(55), community: own),
                Message(id: "c5", direction: .incoming, sentAt: at(50), content: .deleted, community: stranger),
                Message(
                    id: "c6", direction: .outgoing, sentAt: at(20),
                    content: .file(name: "okuma-listesi.pdf", byteCount: 820_000, typeLabel: "PDF", pageCount: 2),
                    status: .delivered, deliveredAt: at(19), community: own),
                Message(
                    id: "c7", direction: .incoming, sentAt: at(5), content: .text(t("Uygun, ben varım!", "Works for me!")),
                    community: mert),
                Message(
                    id: "c8", direction: .outgoing, sentAt: at(1), content: .text(t("Harika, görüşürüz", "Great, see you")),
                    status: .sent, community: own),
            ]
        }

        var chatRows: [CommunityChatRow] {
            rows.filter { $0.phase != .pending }.map { row in
                let last = messagesByCommunity[row.id]?.last
                return CommunityChatRow(
                    id: row.id, name: row.name, isPublic: row.isPublic,
                    lastMessage: last.map {
                        MessagePreview(
                            kind: .text(Self.previewText($0)), direction: $0.direction, status: $0.status, sentAt: $0.sentAt,
                            sender: $0.community?.sender)
                    },
                    lastActivity: last?.sentAt, unreadCount: row.id == Self.bookClub ? 2 : 0)
            }
        }

        private static func previewText(_ message: Message) -> String {
            if case .text(let text) = message.content { return text }
            return "…"
        }

        var requestInbox: [CommunityRequestInboxRow] {
            guard let club = rows.first(where: { $0.id == Self.bookClub }), !openRequests.isEmpty else { return [] }
            return [CommunityRequestInboxRow(id: club.id, name: club.name, count: openRequests.count)]
        }

        func requests(in id: CommunityID) -> [CommunityJoinRequestRow] { id == Self.bookClub ? openRequests : [] }
        func row(_ id: CommunityID) -> CommunityRow? { rows.first { $0.id == id } }

        func members(of id: CommunityID) -> [CommunityMemberRow] {
            guard id == Self.bookClub || id == Self.run else { return [] }
            return [
                CommunityMemberRow(id: "self", username: DesignFixtures.ownUsername, shortID: "0f1e2d3c", isOwner: id == Self.bookClub, isSelf: true),
                CommunityMemberRow(id: "1a2b3c4d-0000", username: "deniz", shortID: "1a2b3c4d", isOwner: id == Self.run, isSelf: false),
                CommunityMemberRow(id: "2b3c4d5e-0000", username: "mert_k", shortID: "2b3c4d5e", isOwner: false, isSelf: false),
                CommunityMemberRow(id: "3c4d5e6f-0000", username: "ayla_", shortID: "3c4d5e6f", isOwner: false, isSelf: false),
                CommunityMemberRow(id: "7f8e9d0c-0000", username: nil, shortID: "7f8e9d0c", isOwner: false, isSelf: false),
                CommunityMemberRow(id: "4d5e6f70-0000", username: nil, shortID: "4d5e6f70", isOwner: false, isSelf: false),
            ]
        }

        func screenAppeared() {}
        func screenDisappeared() {}
        func refresh() async {}
        func dismissProblem() { problem = nil }

        func search(_ query: String, more: Bool) async throws(CommunityProblem) {
            try? await Task.sleep(for: .milliseconds(450))
            let lowered = query.lowercased()
            let all = [
                CommunityDirectoryEntry(id: Self.run, name: rows[1].name, memberCount: 42, isKnown: true),
                CommunityDirectoryEntry(
                    id: "aa11bb22-0000", name: english ? "Moda film nights" : "Moda film geceleri", memberCount: 18, isKnown: false),
                CommunityDirectoryEntry(id: "cc33dd44-0000", name: english ? "Moda chess" : "Moda satranç", memberCount: 100, isKnown: false),
            ]
            directory = all.filter { $0.name.lowercased().contains(lowered) }
            hasMoreResults = false
        }

        func create(name: String, isPublic: Bool) async throws(CommunityProblem) {
            try? await Task.sleep(for: .milliseconds(450))
            rows.insert(CommunityRow(id: UUID().uuidString.lowercased(), name: name, isPublic: isPublic, memberCount: 1, phase: .owner), at: 0)
        }

        func requestJoin(_ id: CommunityID) async throws(CommunityProblem) {
            try? await Task.sleep(for: .milliseconds(450))
            if let entry = directory.first(where: { $0.id == id }) {
                if entry.isFull { throw .full }
                rows.append(CommunityRow(id: id, name: entry.name, isPublic: true, memberCount: entry.memberCount, phase: .pending))
            }
        }

        func redeem(_ code: String) async throws(CommunityProblem) {
            try? await Task.sleep(for: .milliseconds(450))
            throw .invalidInvite
        }

        func leave(_ id: CommunityID) async throws(CommunityProblem) {
            if row(id)?.phase == .owner { throw .ownerCannotLeave }
            rows.removeAll { $0.id == id }
        }

        func withdraw(_ id: CommunityID) async throws(CommunityProblem) { rows.removeAll { $0.id == id } }
        func forget(_ id: CommunityID) async throws(CommunityProblem) { rows.removeAll { $0.id == id } }
        func open(_ id: CommunityID) async throws(CommunityProblem) {}
        func close(_ id: CommunityID) {}

        func send(_ text: String, to id: CommunityID) async throws(CommunityProblem) {
            messagesByCommunity[id, default: []].append(
                Message(
                    id: UUID().uuidString, direction: .outgoing, sentAt: Date(), content: .text(text), status: .sent,
                    community: CommunityMessageMarks(senderID: "self")))
        }

        func approve(_ request: CommunityID, in id: CommunityID) async throws(CommunityProblem) {
            openRequests.removeAll { $0.id == request }
        }

        func reject(_ request: CommunityID, in id: CommunityID) async throws(CommunityProblem) {
            openRequests.removeAll { $0.id == request }
        }

        func rejectAll(in id: CommunityID) async throws(CommunityProblem) {
            if id == Self.bookClub { openRequests = [] }
        }

        func removeMember(_ member: String, from id: CommunityID) async throws(CommunityProblem) {}
        func invite(_ id: CommunityID) async throws(CommunityProblem) -> String { "U2C-4F7K-9QX2-MD3T" }
        func revokeInvites(_ id: CommunityID) async throws(CommunityProblem) {}
        func delete(_ id: CommunityID) async throws(CommunityProblem) { rows.removeAll { $0.id == id } }

        func conversation(_ id: CommunityID) -> [Message] { messagesByCommunity[id] ?? [] }
        func isOwner(of id: CommunityID) -> Bool { row(id)?.phase == .owner }
        func send(attachment: ConversationAttachment, to id: CommunityID) async {}
        func send(pickedMedia messages: [PickedMediaMessage], to id: CommunityID) async {}

        func mediaPayload(_ message: MessageID, in id: CommunityID) async -> MediaPayload? {
            guard let found = messagesByCommunity[id]?.first(where: { $0.id == message }) else { return nil }
            switch found.content {
            case .photo(.designArtwork(let artwork), _):
                return FixtureMedia.photoJPEG(artwork).map { MediaPayload(data: $0, contentType: "image/jpeg", fileName: nil) }
            case .file(let name, _, _, _):
                return MediaPayload(data: await FixtureMedia.leasePDF(english: english), contentType: "application/pdf", fileName: name)
            default:
                return nil
            }
        }

        func downloadMedia(_ message: MessageID, in id: CommunityID) async {}

        func deleteForEveryone(_ message: MessageID, in id: CommunityID) async throws(CommunityProblem) {
            guard let index = messagesByCommunity[id]?.firstIndex(where: { $0.id == message }) else { return }
            messagesByCommunity[id]?[index].content = .deleted
        }

        func deleteForMe(_ message: MessageID, in id: CommunityID) async throws(CommunityProblem) {
            messagesByCommunity[id]?.removeAll { $0.id == message }
        }

        func resend(_ message: MessageID, in id: CommunityID) async throws(CommunityProblem) {}

        func recipients(of message: MessageID, in id: CommunityID) async -> [CommunityRecipientInfo] {
            let sent = messagesByCommunity[id]?.first { $0.id == message }?.sentAt ?? now
            return [
                CommunityRecipientInfo(
                    id: "1a2b3c4d-0000", username: "deniz", shortID: "1a2b3c4d", state: .accepted,
                    deliveredAt: sent.addingTimeInterval(40), readAt: sent.addingTimeInterval(300), hasLeft: false),
                CommunityRecipientInfo(
                    id: "2b3c4d5e-0000", username: "mert_k", shortID: "2b3c4d5e", state: .accepted,
                    deliveredAt: sent.addingTimeInterval(90), readAt: nil, hasLeft: false),
                CommunityRecipientInfo(
                    id: "3c4d5e6f-0000", username: "ayla_", shortID: "3c4d5e6f", state: .accepted, deliveredAt: nil, readAt: nil,
                    hasLeft: false),
                CommunityRecipientInfo(
                    id: "7f8e9d0c-0000", username: nil, shortID: "7f8e9d0c", state: .failed, deliveredAt: nil, readAt: nil,
                    hasLeft: false),
                CommunityRecipientInfo(
                    id: "4d5e6f70-0000", username: nil, shortID: "4d5e6f70", state: .accepted,
                    deliveredAt: sent.addingTimeInterval(20), readAt: sent.addingTimeInterval(60), hasLeft: true),
            ]
        }
    }
#endif
