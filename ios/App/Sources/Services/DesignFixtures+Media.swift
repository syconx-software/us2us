import Foundation

/// The contact screen's media in the fixtures (proposed): older photos,
/// videos and files of the design's chats, beyond what their conversations
/// show — enough of them that the grid pages (deniz: 40 photos and videos)
/// — in the design's painted stand-ins. Times run back from the design's day
/// (8 October 2026) through September and August, in both directions.
extension DesignFixtures {
    static func mediaArchive(chat: ChatID, english: Bool) -> [Message] {
        switch chat {
        case "deniz": visualArchive(prefix: "dz", count: 40, videoEvery: 5) + documentArchive(prefix: "dz", english: english, count: 6)
        case "mert_k": visualArchive(prefix: "mk", count: 12, videoEvery: 3) + documentArchive(prefix: "mk", english: english, count: 9)
        default: []
        }
    }

    /// `count` photos and videos, one every ~19 hours back from 7 October.
    private static func visualArchive(prefix: String, count: Int, videoEvery: Int) -> [Message] {
        let start = date(day: 7, 20, 15)
        return (0..<count).map { index in
            let sentAt = start.addingTimeInterval(-Double(index) * 19 * 3_600 - Double(index % 4) * 7 * 60)
            let direction: MessageDirection = index % 3 == 1 ? .outgoing : .incoming
            let artwork: DesignArtwork = index % 2 == 0 ? .nightPier : .sunsetVideo
            let content: MessageContent =
                index % videoEvery == videoEvery - 1
                ? .video(.designArtwork(artwork), duration: Double(9 + (index * 7) % 50), byteCount: Int64(4_200_000 + index * 310_000))
                : .photo(.designArtwork(artwork), caption: nil)
            return Message(
                id: "\(prefix)-media-\(index)", direction: direction, sentAt: sentAt, content: content,
                status: direction == .outgoing ? .read : nil)
        }
    }

    /// Files of everyday life, newest first from 6 October.
    private static func documentArchive(prefix: String, english: Bool, count: Int) -> [Message] {
        let files: [(tr: String, en: String, bytes: Int64, type: String, pages: Int?)] = [
            ("ucak-bileti.pdf", "plane-ticket.pdf", 184_000, "PDF", 2),
            ("toplanti-notlari.docx", "meeting-notes.docx", 42_000, "DOCX", nil),
            ("butce-2026.xlsx", "budget-2026.xlsx", 96_000, "XLSX", nil),
            ("tatil.mov", "holiday.mov", 18_600_000, "MOV", nil),
            ("kira-makbuzu.pdf", "rent-receipt.pdf", 310_000, "PDF", 1),
            ("ev-plani.png", "floor-plan.png", 1_250_000, "PNG", nil),
            ("fotograflar.zip", "photos.zip", 24_800_000, "ZIP", nil),
            ("sigorta-police.pdf", "insurance-policy.pdf", 2_900_000, "PDF", 12),
            ("davetiye.pdf", "invitation.pdf", 640_000, "PDF", 1),
        ]
        let start = date(day: 6, 11, 20)
        return (0..<min(count, files.count)).map { index in
            let file = files[index]
            let sentAt = start.addingTimeInterval(-Double(index) * 4 * 86_400 - Double(index % 3) * 3 * 3_600)
            let direction: MessageDirection = index % 2 == 0 ? .incoming : .outgoing
            return Message(
                id: "\(prefix)-file-\(index)", direction: direction, sentAt: sentAt,
                content: .file(name: english ? file.en : file.tr, byteCount: file.bytes, typeLabel: file.type, pageCount: file.pages),
                status: direction == .outgoing ? .read : nil)
        }
    }

    /// One page of a chat's media, newest first: photos and videos
    /// (`.visual`) or files (`.documents`) — never view-once, disappearing,
    /// deleted, system lines or a pick still being prepared — older than `before`
    /// when set (an empty page when `before` is not there any more).
    static func mediaPage(_ messages: [Message], kind: ChatMediaKind, before: MessageID?, limit: Int) -> [Message] {
        guard limit > 0 else { return [] }
        let matching = messages.enumerated()
            .filter { _, message in
                // Never disappearing items (§8.1), like the runtime's list.
                guard !message.isDeleted, !message.isSystem, !message.isPendingMedia, !message.isDisappearing else {
                    return false
                }
                switch (message.content, kind) {
                case (.photo, .visual), (.video, .visual), (.file, .documents): return true
                default: return false
                }
            }
            // Newest first; the later one in the conversation first on a tie.
            .sorted { $0.element.sentAt != $1.element.sentAt ? $0.element.sentAt > $1.element.sentAt : $0.offset > $1.offset }
            .map(\.element)
        guard let before else { return Array(matching.prefix(limit)) }
        guard let index = matching.firstIndex(where: { $0.id == before }) else { return [] }
        return Array(matching[(index + 1)...].prefix(limit))
    }
}
