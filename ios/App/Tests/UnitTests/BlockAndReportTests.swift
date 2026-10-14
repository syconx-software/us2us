import Foundation
import Testing

@testable import us2us

// Blocking and reporting (App Review 1.2): the live mapping of a blocked
// contact and of who a report names, the fixtures' block / unblock, the
// report e-mail in both languages (subject, body, and that nothing but the
// username goes into it), and when “Bu kişiyi de engelle” blocks.

private let now = Date(timeIntervalSince1970: 1_791_000_000)  // 2026-10-03 04:00 UTC
private let peerID = "6f1c2a8e-41d2-4d55-9a0e-2f7c1b3d9e10"
private let turkish = Locale(identifier: "tr_TR")
private let english = Locale(identifier: "en_TR")

private func record(
    _ state: LiveRequestState, username: String? = "deniz", usernameVerified: Bool = true, displayName: String? = nil
) -> LiveChatRecord {
    LiveChatRecord(
        peer: peerID, username: username, usernameVerified: usernameVerified, displayName: displayName,
        requestState: state, verified: true, safetyNumberChanged: false, presenceSharing: true, ghostEnabled: false,
        ghostTimeoutSeconds: 0, ghostDeadline: nil, expireTimerSeconds: 0, unreadCount: 0, lastActivity: now,
        lastMessage: nil, avatar: .preset(3))
}

@MainActor
private func makeService() -> DesignFixturesService {
    DesignFixturesService(hasAccount: true, clock: .frozen(at: DesignFixtures.referenceDate), latency: .zero)
}

/// A mailer that answers as Mail (or the phone) would, and keeps what it was given.
@MainActor
private final class FakeMailer: ReportMailing {
    let delivery: ReportDelivery
    private(set) var mails: [ReportMail] = []

    init(_ delivery: ReportDelivery) {
        self.delivery = delivery
    }

    func deliver(_ mail: ReportMail) async -> ReportDelivery {
        mails.append(mail)
        return delivery
    }
}

// MARK: - Live mapping

@Suite("Live service · blocked contacts and reports")
@MainActor
struct BlockedMappingTests {
    @Test func aBlockedContactIsBlockedAndShowsNothingOfThem() {
        let peer = LiveMapping.peer(record(.blocked), presence: .active, now: now, ownSharing: true)
        #expect(peer.isBlocked)
        #expect(!peer.isAcceptedContact)
        #expect(peer.presence == .hidden, "no presence for someone blocked")
        #expect(peer.avatar == .initials, "no picture for someone blocked")
        #expect(peer.username == "deniz")
    }

    @Test(arguments: [LiveRequestState.accepted, .outgoingRequest, .incomingRequest])
    func nobodyElseIsBlocked(_ state: LiveRequestState) {
        #expect(!LiveMapping.peer(record(state), presence: nil, now: now, ownSharing: true).isBlocked)
    }

    @Test func aChatSummaryCarriesTheBlock() {
        let summary = LiveMapping.chatSummary(record(.blocked), presence: nil, now: now, ownSharing: true)
        #expect(summary.peer.isBlocked)
    }

    /// A request blocked from 4.3 keeps messages that were never displayed:
    /// 4.1 shows no badge for it (nor for a contact blocked before reading).
    @Test func someoneBlockedHasNoUnreadCount() {
        var blocked = record(.blocked)
        blocked.unreadCount = 3
        #expect(LiveMapping.chatSummary(blocked, presence: nil, now: now, ownSharing: true).unreadCount == 0)
        var accepted = record(.accepted)
        accepted.unreadCount = 3
        #expect(LiveMapping.chatSummary(accepted, presence: nil, now: now, ownSharing: true).unreadCount == 3)
    }

    @Test func aReportNamesTheUsernameAndWhetherItWasConfirmed() {
        #expect(LiveMapping.reportSubject(record(.accepted)) == ReportSubject(username: "deniz", isUsernameVerified: true))
        #expect(
            LiveMapping.reportSubject(record(.incomingRequest, username: "hediye_kazan", usernameVerified: false))
                == ReportSubject(username: "hediye_kazan", isUsernameVerified: false))
    }

    @Test func withoutAUsernameAReportNamesNobodyNeverTheAccountOrDisplayName() {
        let subject = LiveMapping.reportSubject(record(.incomingRequest, username: nil, displayName: "Deniz Y."))
        #expect(subject == ReportSubject(username: nil, isUsernameVerified: false))
        // What the screens show instead (the display name) never reaches the e-mail.
        #expect(LiveMapping.username(of: record(.incomingRequest, username: nil, displayName: "Deniz Y.")) == "Deniz Y.")
        let mail = ReportMail.make(
            about: subject, reason: .spam, note: "", version: "1.0.8", build: "11", date: now, locale: turkish)
        #expect(mail.subject == "us2us bildirimi")
        #expect(mail.body.hasPrefix("Bildirilen kullanıcı: kullanıcı adı bilinmiyor\n"))
        for leak in [peerID, String(peerID.prefix(8)), "Deniz Y."] {
            #expect(!mail.subject.contains(leak) && !mail.body.contains(leak), "\(leak) is not in the report")
        }
    }
}

// MARK: - Fixtures

@Suite("Fixtures · block and unblock")
@MainActor
struct FixtureBlockingTests {
    @Test func blockingAContactMakesTheChatReadOnlyAndUnblockingRestoresIt() async throws {
        let service = makeService()
        let before = try #require(service.chat("mert_k")).peer
        #expect(before.isOnline)
        #expect(await service.block(chat: "mert_k"))
        let blocked = try #require(service.chat("mert_k")).peer
        #expect(blocked.isBlocked && !blocked.isAcceptedContact && !blocked.isOnline)
        #expect(blocked.avatar == .initials)
        // Nothing goes to someone blocked.
        let count = service.messages(in: "mert_k").count
        await service.send(text: "Selam", replyTo: nil, in: "mert_k")
        #expect(service.messages(in: "mert_k").count == count)
        #expect(await service.unblock(chat: "mert_k"))
        #expect(service.chat("mert_k")?.peer == before)
        await service.send(text: "Selam", replyTo: nil, in: "mert_k")
        #expect(service.messages(in: "mert_k").count == count + 1)
    }

    @Test func unknownChatsAreNeitherBlockedNorUnblocked() async {
        let service = makeService()
        #expect(!(await service.block(chat: "nobody")))
        #expect(!(await service.unblock(chat: "deniz")), "deniz is not blocked")
    }

    @Test func aBlockedRequestStaysAsABlockedChatAndUnblockingMakesItARequestAgain() async throws {
        let service = makeService()
        let request = try #require(service.requests.first { $0.id == "kuzey_dev" })
        await service.block(request)
        #expect(!service.requests.contains { $0.id == "kuzey_dev" })
        let chat = try #require(service.chat("kuzey_dev"))
        #expect(chat.peer.isBlocked && !chat.peer.isAcceptedContact)
        #expect(service.messages(in: "kuzey_dev").count == request.messages.count, "its messages stay, read only")
        #expect(service.chats.prefix(2).map(\.id) == ["deniz", "mert_k"], "the design's order stays")
        #expect(await service.unblock(chat: "kuzey_dev"))
        #expect(service.chat("kuzey_dev") == nil)
        #expect(service.requests.first?.id == "kuzey_dev", "back where it was")
    }

    @Test func aReportNamesTheUsernameAndASuspiciousSenderIsUnconfirmed() throws {
        let service = makeService()
        #expect(service.reportSubject(for: "deniz") == ReportSubject(username: "deniz", isUsernameVerified: true))
        #expect(service.reportSubject(for: "nobody") == nil)
        let suspicious = try #require(service.requests.first { $0.flags.contains(.suspicious) })
        #expect(service.reportSubject(for: suspicious) == ReportSubject(username: suspicious.sender, isUsernameVerified: false))
        let plain = try #require(service.requests.first { $0.id == "kuzey_dev" })
        #expect(service.reportSubject(for: plain)?.isUsernameVerified == true)
    }
}

// MARK: - The e-mail

@Suite("Report e-mail")
@MainActor
struct ReportMailTests {
    private let deniz = ReportSubject(username: "deniz", isUsernameVerified: true)

    @Test func turkishSubjectAndBody() {
        let mail = ReportMail.make(
            about: deniz, reason: .harassment, note: "  Sürekli rahatsız ediyor \n", version: "1.0.8", build: "11",
            date: now, locale: turkish)
        #expect(mail.subject == "us2us bildirimi: @deniz")
        #expect(
            mail.body == """
                Bildirilen kullanıcı: @deniz
                Neden: Taciz veya zorbalık
                Not: Sürekli rahatsız ediyor

                Uygulama: us2us iOS 1.0.8 (11)
                Zaman: 2026-10-03 04:00 UTC

                Mesajlar uçtan uca şifreli olduğu için bu bildirime hiçbir mesaj içeriği eklenmedi. İstersen ekran görüntüsü ekleyebilirsin.
                """)
    }

    @Test func englishSubjectAndBody() {
        let mail = ReportMail.make(
            about: deniz, reason: .impersonation, note: "", version: "1.0.8", build: "11", date: now, locale: english)
        #expect(mail.subject == "us2us report: @deniz")
        #expect(
            mail.body == """
                Reported user: @deniz
                Reason: Impersonation
                Note: —

                App: us2us iOS 1.0.8 (11)
                Time: 2026-10-03 04:00 UTC

                Messages are end-to-end encrypted, so no message content was added to this report. You may attach screenshots.
                """)
    }

    @Test(arguments: ReportReason.allCases)
    func everyReasonIsNamedInBothLanguages(_ reason: ReportReason) {
        for locale in [turkish, english] {
            let mail = ReportMail.make(
                about: deniz, reason: reason, note: "", version: "1", build: "1", date: now, locale: locale)
            let line = mail.body.split(separator: "\n")[1]
            #expect(!line.contains("report.reason"), "\(reason) is translated")
            #expect(line.hasPrefix(locale == turkish ? "Neden: " : "Reason: "))
        }
    }

    @Test func anUnconfirmedUsernameIsMarkedAsSuch() {
        let claim = ReportSubject(username: "hediye_kazan", isUsernameVerified: false)
        let tr = ReportMail.make(about: claim, reason: .spam, note: "", version: "1", build: "1", date: now, locale: turkish)
        let en = ReportMail.make(about: claim, reason: .spam, note: "", version: "1", build: "1", date: now, locale: english)
        #expect(tr.subject == "us2us bildirimi: @hediye_kazan")
        #expect(tr.body.hasPrefix("Bildirilen kullanıcı: @hediye_kazan (kullanıcı adı doğrulanmadı)\n"))
        #expect(en.body.hasPrefix("Reported user: @hediye_kazan (username not confirmed)\n"))
    }

    /// The design's chat with deniz: none of its messages, captions or the
    /// chat's other people end up in a report about deniz.
    @Test func noMessageContentIsEverAdded() {
        let service = makeService()
        var contents: [String] = []
        for message in service.messages(in: "deniz") {
            switch message.content {
            case .text(let text): contents.append(text)
            case .photo(_, let caption): contents += [caption].compactMap { $0 }
            default: break
            }
        }
        #expect(contents.count >= 3)
        let subject = service.reportSubject(for: "deniz")!
        for locale in [turkish, english] {
            let mail = ReportMail.make(
                about: subject, reason: .inappropriate, note: "", version: "1.0.8", build: "11", date: now, locale: locale)
            for text in contents {
                #expect(!mail.body.contains(text), "“\(text)” is not in the report")
            }
            #expect(!mail.body.contains(DesignFixtures.ownUsername), "nor the reporter's own name")
            // Eight lines, two of them empty: nothing else is ever added.
            #expect(mail.body.split(separator: "\n", omittingEmptySubsequences: false).count == 8)
        }
    }

    @Test func theTimeIsUTCWhereverThePhoneIs() {
        #expect(ReportMail.utcTime(now) == "2026-10-03 04:00 UTC")
        #expect(ReportMail.utcTime(Date(timeIntervalSince1970: 0)) == "1970-01-01 00:00 UTC")
    }

    @Test func theNoteIsKeptShort() {
        let long = String(repeating: "a", count: ReportMail.maxNoteLength + 40)
        #expect(ReportMail.clamped(long).count == ReportMail.maxNoteLength)
        let model = ReportModel(subject: deniz, personName: "deniz", offersBlock: true, mailer: FakeMailer(.sent)) {}
        model.note = long
        #expect(model.note.count == ReportMail.maxNoteLength)
    }

    @Test func mailtoGoesToSupportWithSubjectAndBody() throws {
        let mail = ReportMail.make(
            about: deniz, reason: .spam, note: "a&b", version: "1.0.8", build: "11", date: now, locale: english)
        let url = try #require(mail.mailtoURL)
        #expect(url.absoluteString.hasPrefix("mailto:support@us2us.app?subject=us2us%20report%3A%20%40deniz&body="))
        #expect(url.absoluteString.contains("Note%3A%20a%26b%0D%0A"), "CRLF line breaks, & encoded")
    }
}

// MARK: - Block after send

@Suite("Report · block after send")
@MainActor
struct ReportBlockRuleTests {
    @Test func blocksOnlyOnceTheReportLeftTheSheet() {
        #expect(ReportDelivery.blocks(after: .sent, alsoBlock: true))
        #expect(ReportDelivery.blocks(after: .handedOff, alsoBlock: true))
        #expect(ReportDelivery.blocks(after: .addressShown, alsoBlock: true))
        #expect(!ReportDelivery.blocks(after: .notSent, alsoBlock: true), "cancelled or kept as a draft")
        #expect(!ReportDelivery.blocks(after: .failed, alsoBlock: true))
        for delivery in [ReportDelivery.sent, .handedOff, .addressShown, .notSent, .failed] {
            #expect(!ReportDelivery.blocks(after: delivery, alsoBlock: false))
        }
    }

    private func makeModel(_ delivery: ReportDelivery, offersBlock: Bool = true, alsoBlock: Bool = true)
        -> (ReportModel, FakeMailer, Counter)
    {
        let mailer = FakeMailer(delivery)
        let blocks = Counter()
        let model = ReportModel(
            subject: ReportSubject(username: "deniz", isUsernameVerified: true), personName: "deniz",
            offersBlock: offersBlock, mailer: mailer
        ) { blocks.value += 1 }
        model.alsoBlock = alsoBlock
        model.version = "1.0.8"
        model.build = "11"
        return (model, mailer, blocks)
    }

    @MainActor final class Counter {
        var value = 0
    }

    @Test func nothingIsSentWithoutAReason() async {
        let (model, mailer, blocks) = makeModel(.sent)
        #expect(!model.canSend)
        await model.send(locale: turkish, now: now)
        #expect(mailer.mails.isEmpty)
        #expect(blocks.value == 0)
    }

    @Test func sentByMailBlocksAndCloses() async {
        let (model, mailer, blocks) = makeModel(.sent)
        var closed: Bool?
        model.onClose = { closed = $0 }
        model.reason = .spam
        model.note = "Reklam"
        #expect(model.canSend)
        await model.send(locale: turkish, now: now)
        #expect(mailer.mails.first?.subject == "us2us bildirimi: @deniz")
        #expect(mailer.mails.first?.body.contains("Not: Reklam") == true)
        await model.blocking?.value
        #expect(blocks.value == 1)
        #expect(model.didBlock)
        #expect(closed == true)
    }

    @Test func handedToAnotherMailAppBlocksToo() async {
        let (model, _, blocks) = makeModel(.handedOff)
        model.reason = .other
        await model.send(locale: english, now: now)
        await model.blocking?.value
        #expect(blocks.value == 1)
    }

    /// The block stores at once but also waits for a new access key from
    /// the relay: the sheet closes without waiting for that.
    @Test func theSheetDoesNotWaitForTheBlockToFinish() async {
        let mailer = FakeMailer(.sent)
        let gate = Gate()
        let model = ReportModel(
            subject: ReportSubject(username: "deniz", isUsernameVerified: true), personName: "deniz",
            offersBlock: true, mailer: mailer
        ) { await gate.wait() }
        var closed: Bool?
        model.onClose = { closed = $0 }
        model.reason = .spam
        await model.send(locale: turkish, now: now)
        #expect(closed == true, "closed while the block is still on its way")
        #expect(model.didBlock)
        gate.open()
        await model.blocking?.value
    }

    @MainActor final class Gate {
        private var waiters: [CheckedContinuation<Void, Never>] = []
        private var isOpen = false

        func wait() async {
            guard !isOpen else { return }
            await withCheckedContinuation { waiters.append($0) }
        }

        func open() {
            isOpen = true
            waiters.forEach { $0.resume() }
            waiters = []
        }
    }

    @Test func cancelledInMailBlocksNobodyAndKeepsTheForm() async {
        let (model, _, blocks) = makeModel(.notSent)
        var closed = false
        model.onClose = { _ in closed = true }
        model.reason = .harassment
        model.note = "Not"
        await model.send(locale: turkish, now: now)
        #expect(blocks.value == 0)
        #expect(!model.didBlock)
        #expect(model.phase == .form)
        #expect(!closed)
        #expect(model.reason == .harassment && model.note == "Not", "nothing typed is lost")
    }

    @Test func aFailureSaysSoAndBlocksNobody() async {
        let (model, _, blocks) = makeModel(.failed)
        model.reason = .spam
        await model.send(locale: turkish, now: now)
        #expect(blocks.value == 0)
        #expect(model.failed)
        #expect(model.canSend, "it can be tried again")
    }

    @Test func withoutMailTheAddressIsShownAndTheBlockFollows() async {
        let (model, _, blocks) = makeModel(.addressShown)
        var closed: Bool?
        model.onClose = { closed = $0 }
        model.reason = .inappropriate
        await model.send(locale: turkish, now: now)
        #expect(model.phase == .address)
        await model.blocking?.value
        #expect(blocks.value == 1)
        #expect(closed == nil, "the address stays until “Tamam”")
        model.close()
        model.close()
        #expect(closed == true)
    }

    @Test func theToggleOffOrSomeoneBlockedAlreadyBlocksNobody() async {
        let (off, _, offBlocks) = makeModel(.sent, alsoBlock: false)
        off.reason = .spam
        await off.send(locale: turkish, now: now)
        #expect(offBlocks.value == 0 && !off.didBlock)
        let (already, _, alreadyBlocks) = makeModel(.sent, offersBlock: false)
        already.reason = .spam
        await already.send(locale: turkish, now: now)
        #expect(alreadyBlocks.value == 0 && !already.didBlock)
    }
}
