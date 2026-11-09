import Foundation
import Testing

@testable import us2us

// 5.3 › “Bildir” (App Review 1.2): the report e-mail with the message's
// text (left in by the reporter) or only its kind, never without the
// reporter's choice; who the report names in a chat and in a community;
// and that only someone else's message offers it.

private let now = Date(timeIntervalSince1970: 1_791_000_000)  // 2026-10-03 04:00 UTC
private let turkish = Locale(identifier: "tr_TR")
private let english = Locale(identifier: "en_TR")
private let deniz = ReportSubject(username: "deniz", isUsernameVerified: true)

@MainActor
private final class CapturingMailer: ReportMailing {
    private(set) var mails: [ReportMail] = []

    func deliver(_ mail: ReportMail) async -> ReportDelivery {
        mails.append(mail)
        return .sent
    }
}

private func incoming(_ content: MessageContent, id: String = "m1") -> Message {
    Message(id: id, direction: .incoming, sentAt: now, content: content)
}

@Suite("Report e-mail · from a message")
@MainActor
struct ReportMailMessageTests {
    @Test func turkishBodyQuotesTheText() {
        let mail = ReportMail.make(
            about: deniz, reason: .harassment, note: "", message: ReportedMessage(content: .text("  seni bulacağım \n")),
            version: "1.1.7", build: "19", date: now, locale: turkish)
        #expect(
            mail.body == """
                Bildirilen kullanıcı: @deniz
                Neden: Taciz veya zorbalık
                Not: —
                Bildirilen mesaj: seni bulacağım

                Uygulama: us2us iOS 1.1.7 (19)
                Zaman: 2026-10-03 04:00 UTC

                Bu bildirime, bildirenin seçimiyle yalnızca yukarıdaki mesajın metni eklendi; başka hiçbir mesaj içeriği, medya ya da anahtar eklenmedi.
                """)
    }

    @Test func englishBodyQuotesTheText() {
        let mail = ReportMail.make(
            about: deniz, reason: .spam, note: "again", message: ReportedMessage(content: .text("Win a prize")),
            version: "1.1.7", build: "19", date: now, locale: english)
        #expect(mail.body.contains("\nReported message: Win a prize\n"))
        #expect(mail.body.hasSuffix("Only the text of the message above was added to this report, at the reporter's choice; no other message content, media or key was added."))
        #expect(!mail.body.contains("no message content was added"))
    }

    /// A photo (and its caption), a video, a voice message …: only the kind.
    @Test func mediaIsNamedByItsKindOnly() {
        let photo = ReportedMessage(incoming(.photo(.designArtwork(.nightPier), caption: "secret caption")))
        #expect(photo == ReportedMessage(content: .kind(.photo)), "the caption is not taken")
        for locale in [turkish, english] {
            let mail = ReportMail.make(
                about: deniz, reason: .inappropriate, note: "", message: photo, version: "1", build: "1", date: now,
                locale: locale)
            #expect(!mail.body.contains("secret caption"))
            let line = mail.body.split(separator: "\n")[3]
            #expect(
                line == (locale == turkish ? "Bildirilen mesaj: fotoğraf (içeriği eklenmedi)" : "Reported message: photo (content not added)"))
            #expect(
                mail.body.hasSuffix(StringTable.report.string("report.mail.encrypted", locale: locale)),
                "no message content was added")
        }
    }

    @Test(arguments: ReportedMessage.Kind.allCases)
    func everyKindIsNamedInBothLanguages(_ kind: ReportedMessage.Kind) {
        for locale in [turkish, english] {
            let name = StringTable.report.string(kind.nameKey, locale: locale)
            #expect(!name.hasPrefix("report.kind"), "\(kind) is translated")
        }
    }

    @Test func aLongTextIsCut() {
        let long = String(repeating: "a", count: ReportedMessage.maxTextLength + 50)
        let quoted = ReportedMessage.quoted(long)
        #expect(quoted.count == ReportedMessage.maxTextLength + 1)
        #expect(quoted.hasSuffix("…"))
        #expect(ReportedMessage.quoted(" kısa ") == "kısa")
    }

    @Test func nothingWrittenByNobodyIsReported() {
        #expect(ReportedMessage(incoming(.deleted)) == nil)
        #expect(ReportedMessage(incoming(.system(.ghostDisabled))) == nil)
        #expect(ReportedMessage(incoming(.voice(duration: 3, waveform: [], playedFraction: 0))) == ReportedMessage(content: .kind(.voice)))
        #expect(ReportedMessage(incoming(.text("hey"))) == ReportedMessage(content: .text("hey")))
    }

    /// “Bu mesajın metnini bildirime ekle” on (the default): the text goes;
    /// off: the e-mail is the plain report, with no message content.
    @Test func theTextGoesOnlyWhileTheToggleIsOn() async {
        for include in [true, false] {
            let mailer = CapturingMailer()
            let model = ReportModel(
                subject: deniz, personName: "deniz", offersBlock: false,
                message: ReportedMessage(content: .text("bad words")), mailer: mailer
            ) {}
            #expect(model.includesMessage, "on by default")
            model.includesMessage = include
            model.reason = .harassment
            model.version = "1.1.7"
            model.build = "19"
            await model.send(locale: turkish, now: now)
            let body = mailer.mails.first?.body
            #expect(body != nil)
            #expect(body?.contains("bad words") == include)
            #expect(model.sendsMessageText == include)
            #expect(
                body?.hasSuffix(StringTable.report.string("report.mail.encrypted", locale: turkish)) == !include)
        }
    }

    /// The report sheets that do not start from a message are unchanged.
    @Test func withoutAMessageNothingIsOfferedOrAdded() {
        let model = ReportModel(subject: deniz, personName: "deniz", offersBlock: true, mailer: CapturingMailer()) {}
        #expect(model.reportedMessage == nil)
        #expect(model.includedMessage == nil)
        #expect(!model.sendsMessageText)
    }
}

@Suite("5.3 › Bildir")
@MainActor
struct MessageReportActionTests {
    private func chat() -> (ConversationViewModel, DesignFixturesService) {
        let service = DesignFixturesService(hasAccount: true, clock: .frozen(at: DesignFixtures.referenceDate), latency: .zero)
        return (ConversationViewModel(chatID: "deniz", messenger: service), service)
    }

    @Test func onlySomeoneElsesMessagesOfferIt() throws {
        let (model, service) = chat()
        let messages = service.messages(in: "deniz")
        let theirs = try #require(messages.first { !$0.isOutgoing && !$0.isSystem && !$0.isDeleted })
        let mine = try #require(messages.first { $0.isOutgoing })
        #expect(model.canReport(theirs))
        #expect(!model.canReport(mine), "not your own")
        #expect(!model.canReport(incoming(.deleted)))
    }

    @Test func aChatsReportNamesItsPersonAndOffersTheBlock() throws {
        let (model, service) = chat()
        let theirs = try #require(service.messages(in: "deniz").first { message in
            if case .text = message.content { return !message.isOutgoing }
            return false
        })
        let report = try #require(model.makeReport(for: theirs))
        #expect(report.subject == ReportSubject(username: "deniz", isUsernameVerified: true))
        #expect(report.offersBlock)
        #expect(report.reportedMessage == ReportedMessage(theirs))
        #expect(report.reportedMessage?.isText == true)
    }

    @Test func aCommunityReportNamesTheMemberWhoWroteIt() throws {
        let community = "6f1c2a8e-41d2-4d55-9a0e-2f7c1b3d9e10"
        let stub = StubCommunitiesService()
        stub.rows = [CommunityRow(id: community, name: "Mahalle", isPublic: true, memberCount: 3, phase: .member)]
        var known = incoming(.text("spam spam"), id: "c1")
        known.community = CommunityMessageMarks(
            senderID: "acc-1", sender: CommunitySenderName(username: "mert_k", shortID: "1a2b3c4d"))
        var unknown = incoming(.photo(.designArtwork(.nightPier), caption: nil), id: "c2")
        unknown.community = CommunityMessageMarks(
            senderID: "acc-2", sender: CommunitySenderName(username: nil, shortID: "9f8e7d6c"))
        stub.messages = [known, unknown]
        let base = DesignFixturesService(hasAccount: true, clock: .frozen(at: DesignFixtures.referenceDate), latency: .zero)
        let model = ConversationViewModel(
            chatID: community, messenger: CommunityChatMessenger(base: base, communityID: community, service: stub))

        let named = try #require(model.makeReport(for: known))
        #expect(named.subject == ReportSubject(username: "mert_k", isUsernameVerified: true))
        #expect(named.reportedMessage == ReportedMessage(content: .text("spam spam")))
        #expect(named.offersBlock)

        let nameless = try #require(model.makeReport(for: unknown))
        #expect(nameless.subject == ReportSubject(username: nil, isUsernameVerified: false), "never the account id")
        #expect(nameless.reportedMessage == ReportedMessage(content: .kind(.photo)))
        #expect(nameless.personName.contains("9f8e7d6c"), "the name the screen shows")
    }
}
