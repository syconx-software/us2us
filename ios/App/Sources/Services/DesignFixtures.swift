import Foundation

/// The data drawn in the design, exactly: names, palettes, messages, times,
/// statuses, Ghost counters, requests, the safety number, settings and the
/// backup estimate. Times are wall-clock times on the design's day
/// (Thursday 8 October 2026) in the device's time zone, so “18:10” always
/// renders as 18:10.
///
/// User content (the demo messages) exists in Turkish and in the English the
/// design's section 09 shows.
enum DesignFixtures {
    /// The design's moment: 8 Oct 2026, 18:52 (the 6.3 verification time).
    static var referenceDate: Date { date(day: 8, 18, 52) }

    /// The demo account.
    static let ownUsername = "selin.n"

    /// The demo secret code (any valid, non-trivial six digits would do).
    static let demoSecretCode = "246813"

    /// 6.1 safety number with deniz — 12 groups of 5 digits.
    static let safetyNumberGroups = [
        "08223", "78626", "74024", "23263",
        "12493", "59214", "00841", "88304",
        "26120", "14864", "87953", "64169",
    ]

    /// 5.1 voice message waveform: the design's 30 bars (heights of a 28 pt box).
    static let voiceWaveform: [Double] = [
        6, 10, 16, 12, 20, 14, 8, 18, 22, 12, 9,
        15, 20, 11, 7, 13, 19, 16, 10, 6, 12, 17, 21, 14, 9, 11, 16, 8, 12, 18,
    ].map { $0 / 28 }

    /// 11 of the 30 bars are drawn as played.
    static let voicePlayedFraction = 11.0 / 30.0

    // MARK: Time helpers

    /// A wall-clock time in October 2026 (or September with `month: 9`).
    static func date(month: Int = 10, day: Int, _ hour: Int, _ minute: Int, _ second: Int = 0) -> Date {
        var components = DateComponents()
        components.calendar = Calendar(identifier: .gregorian)
        components.timeZone = .current
        components.year = 2026
        components.month = month
        components.day = day
        components.hour = hour
        components.minute = minute
        components.second = second
        return components.date ?? Date(timeIntervalSince1970: 0)
    }

    static func remaining(hours: Int = 0, minutes: Int = 0, seconds: Int = 0, days: Int = 0) -> TimeInterval {
        TimeInterval(days * 86_400 + hours * 3_600 + minutes * 60 + seconds)
    }

    // MARK: Peers

    static let deniz = Peer(username: "deniz", palette: .a, isVerified: true, isAcceptedContact: true, presence: .justNow)
    static let mert = Peer(username: "mert.k", palette: .b, isVerified: false, isAcceptedContact: true, presence: .online)
    static let ayla = Peer(username: "ayla_", palette: .c, isVerified: false, isAcceptedContact: true, presence: .hidden)
    static let kaan = Peer(username: "kaan", palette: .d, isVerified: false, isAcceptedContact: true, presence: .hidden)
    static let ece = Peer(username: "ece.y", palette: .a, isVerified: false, isAcceptedContact: true, presence: .hidden)
    static let bora = Peer(username: "bora", palette: .b, isVerified: false, isAcceptedContact: true, presence: .hidden)
    static let oya = Peer(username: "oya.t", palette: .c, isVerified: false, isAcceptedContact: true, presence: .hidden)

    // MARK: Chats (4.1 / 8.1 / 9.1)

    /// The seven chat-list rows, in the design's order.
    static func chats(now: Date, ghostUrgent: Bool, english: Bool) -> [ChatSummary] {
        let denizRemaining = ghostUrgent ? remaining(minutes: 42, seconds: 10) : remaining(hours: 23, minutes: 41, seconds: 7)
        return [
            ChatSummary(
                id: "deniz", peer: deniz,
                lastMessage: MessagePreview(kind: .disappearing, direction: .outgoing, status: .sent, sentAt: date(day: 8, 18, 10)),
                unreadCount: 0,
                ghost: GhostState(isEnabled: true, period: .oneDay, deadline: now.addingTimeInterval(denizRemaining)),
                disappearing: .off),
            ChatSummary(
                id: "mert.k", peer: mert,
                lastMessage: MessagePreview(kind: .sticker, direction: .incoming, status: nil, sentAt: date(day: 8, 18, 44)),
                unreadCount: 2, ghost: .off, disappearing: .off),
            ChatSummary(
                id: "ayla_", peer: ayla,
                lastMessage: MessagePreview(kind: .photoViewOnce, direction: .outgoing, status: .delivered, sentAt: date(day: 8, 16, 20)),
                unreadCount: 0,
                ghost: GhostState(isEnabled: true, period: .oneDay, deadline: now.addingTimeInterval(remaining(minutes: 37, seconds: 20))),
                disappearing: .off),
            ChatSummary(
                id: "kaan", peer: kaan,
                lastMessage: MessagePreview(
                    kind: .text(english ? "Coming to Sunday breakfast?" : "Pazar kahvaltıya geliyor musun?"),
                    direction: .outgoing, status: .read, sentAt: date(day: 7, 21, 34)),
                unreadCount: 0,
                ghost: GhostState(isEnabled: true, period: .sevenDays, deadline: now.addingTimeInterval(remaining(hours: 4, days: 6))),
                disappearing: .off),
            ChatSummary(
                id: "ece.y", peer: ece,
                lastMessage: MessagePreview(kind: .text(english ? "See you there" : "Orada görüşürüz"), direction: .outgoing, status: .failed, sentAt: date(month: 9, day: 24, 11, 5)),
                unreadCount: 0, ghost: .off, disappearing: .off),
            ChatSummary(
                id: "bora", peer: bora,
                lastMessage: MessagePreview(kind: .liveLocationEnded, direction: .incoming, status: nil, sentAt: date(month: 9, day: 22, 19, 40)),
                unreadCount: 0, ghost: .off, disappearing: .off),
            ChatSummary(
                id: "oya.t", peer: oya,
                lastMessage: MessagePreview(kind: .deleted, direction: .incoming, status: nil, sentAt: date(month: 9, day: 19, 9, 12)),
                unreadCount: 0, ghost: .off, disappearing: .off),
        ]
    }

    // MARK: Conversations (5.1 / 8.2 / 9.2 and 5.2)

    static func messages(chat: ChatID, english: Bool) -> [Message] {
        switch chat {
        case "deniz": denizMessages(english: english)
        case "mert.k": mertMessages(english: english)
        default: []
        }
    }

    /// 5.1 — the chat with deniz.
    static func denizMessages(english: Bool) -> [Message] {
        func t(_ tr: String, _ en: String) -> String { english ? en : tr }
        return [
            Message(id: "d0", direction: .incoming, sentAt: date(day: 8, 18, 0), content: .system(.ghostEnabled(.oneDay))),
            Message(id: "d1", direction: .incoming, sentAt: date(day: 8, 18, 2), content: .text(t("Bu akşam boş musun?", "Are you free tonight?"))),
            Message(
                id: "d2", direction: .incoming, sentAt: date(day: 8, 18, 3),
                content: .photo(.designArtwork(.nightPier), caption: t("Şu yeni yer, Moda’da", "That new place in Moda")),
                reactions: [Reaction(emoji: "❤️")]),
            Message(
                id: "d3", direction: .outgoing, sentAt: date(day: 8, 18, 5),
                content: .text(t("Çok güzelmiş! 8 gibi olur mu?", "Looks great! Around 8?")), status: .read,
                replyTo: QuotedMessage(author: "deniz", kind: .photo, thumbnail: .designArtwork(.nightPier)),
                deliveredAt: date(day: 8, 18, 6), readAt: date(day: 8, 18, 7)),
            Message(
                id: "d4", direction: .incoming, sentAt: date(day: 8, 18, 7),
                content: .voice(duration: 42, waveform: voiceWaveform, playedFraction: voicePlayedFraction)),
            Message(
                id: "d5", direction: .outgoing, sentAt: date(day: 8, 18, 9),
                content: .text(t("Tamam, iskelede buluşalım", "OK, meet you at the pier")), status: .delivered,
                deliveredAt: date(day: 8, 18, 9)),
            Message(
                id: "d6", direction: .outgoing, sentAt: date(day: 8, 18, 10),
                content: .text(t("Kapı kodu 4471", "Door code 4471")), status: .sent, disappearing: .fiveMinutes),
        ]
    }

    /// 5.2 — the content catalogue in the chat with mert.k (Ghost off).
    static func mertMessages(english: Bool) -> [Message] {
        [
            Message(
                id: "m1", direction: .outgoing, sentAt: date(day: 8, 12, 40),
                content: .file(name: "kira-sozlesmesi.pdf", byteCount: 2_100_000, typeLabel: "PDF", pageCount: 4), status: .read),
            Message(
                id: "m2", direction: .incoming, sentAt: date(day: 8, 12, 52),
                content: .video(.designArtwork(.sunsetVideo), duration: 18, byteCount: 12_400_000)),
            Message(
                id: "m3", direction: .incoming, sentAt: date(day: 8, 17, 58),
                content: .location(name: "Moda İskelesi", locality: "Kadıköy, İstanbul", latitude: 40.9846, longitude: 29.0249)),
            Message(
                id: "m4", direction: .outgoing, sentAt: date(day: 8, 18, 1),
                content: .liveLocation(expiresAt: date(day: 8, 18, 52).addingTimeInterval(47 * 60), isActive: true), status: .read),
            Message(id: "m5", direction: .incoming, sentAt: date(day: 8, 18, 20), content: .deleted),
            Message(id: "m6", direction: .incoming, sentAt: date(day: 8, 18, 40), content: .viewOnce(isVideo: false, state: .unopened)),
            Message(id: "m7", direction: .incoming, sentAt: date(day: 8, 18, 44), content: .textSticker("tamam!")),
        ]
    }

    // MARK: Requests (4.3 / 4.4)

    static func requests(now: Date, english: Bool) -> [MessageRequest] {
        func t(_ tr: String, _ en: String) -> String { english ? en : tr }
        let kuzeyMessages = [
            Message(id: "k1", direction: .incoming, sentAt: date(day: 8, 16, 12), content: .text(t("Merhaba! Dünkü meetup’taki sunumunu çok beğendim.", "Hi! I really liked your talk at yesterday's meetup."))),
            Message(id: "k2", direction: .incoming, sentAt: date(day: 8, 16, 12), content: .text(t("Slaytları paylaşabilir misin? Bağlantıyı da bırakıyorum:", "Could you share the slides? Here's the link:"))),
            Message(id: "k3", direction: .incoming, sentAt: date(day: 8, 16, 13), content: .text("slides.kuzey.dev/meetup")),
        ]
        return [
            MessageRequest(
                id: "kuzey.dev", sender: "kuzey.dev", receivedAt: now.addingTimeInterval(-2 * 3_600),
                preview: .text(t("Merhaba! Dünkü meetup’taki sunumunu çok beğendim, slaytları paylaşabilir misin?", "Hi! I really liked your talk at yesterday's meetup — could you share the slides?")),
                flags: [], messages: kuzeyMessages),
            MessageRequest(
                id: "n.aksoy", sender: "n.aksoy", receivedAt: now.addingTimeInterval(-26 * 3_600),
                preview: .photo, flags: [.hasMedia],
                messages: [Message(id: "n1", direction: .incoming, sentAt: date(day: 7, 15, 30), content: .photo(.designArtwork(.sunsetVideo), caption: nil))]),
            MessageRequest(
                id: "hediye_kazan", sender: "hediye_kazan", receivedAt: now.addingTimeInterval(-3 * 86_400),
                preview: .text(t("Tebrikler! Ödülünü almak için hemen bağlantıya tıkla: bit.ly/…", "Congratulations! Click the link now to claim your prize: bit.ly/…")),
                flags: [.hasLink, .suspicious],
                messages: [Message(id: "h1", direction: .incoming, sentAt: date(day: 5, 10, 2), content: .text(t("Tebrikler! Ödülünü almak için hemen bağlantıya tıkla: bit.ly/3xQ", "Congratulations! Click the link now to claim your prize: bit.ly/3xQ")))]),
        ]
    }

    // MARK: Settings & backup (7.1 / 7.2)

    static var settings: AppSettings {
        AppSettings(
            presenceVisibility: .acceptedContacts, readReceipts: true, faceIDEnabled: true,
            defaultGhost: .oneDay, defaultDisappearing: .off, lastBackupAt: date(day: 8, 9, 30))
    }

    static let backupEstimate = BackupEstimate(
        fileName: "us2us-yedek-2026-10-08.u2u", chatCount: 12, messageCount: 3_920,
        mediaBytes: 162_000_000, totalBytes: 184_000_000)

    /// 7.3 — shown mid-restore at 62 %.
    static let restoreProgress = RestoreProgress(
        fraction: 0.62, stage: .messages, messagesDone: 2_431, messagesTotal: 3_920,
        mediaBytes: 162_000_000, estimatedRemaining: 60)

    /// Usernames the fixtures treat as taken (2.1) — the people of the
    /// design, plus “lale”, someone with no chat yet (4.2 “Mesaj yaz” walks).
    static let takenUsernames: Set<String> = ["selin", "deniz", "mert.k", "ayla_", "kaan", "ece.y", "bora", "oya.t", "kuzey.dev", "lale"]
}
