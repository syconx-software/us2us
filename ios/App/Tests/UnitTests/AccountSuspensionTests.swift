import Foundation
import Testing
import Us2usRuntime

@testable import us2us

// A suspended account (PROTOCOL §4.3): the relay's `403 account_suspended`
// and close code `4403` reach the screens as their own state — not the
// revoked token's — and “Hesabın askıya alındı” covers the unlocked app,
// with an e-mail to support that carries nothing but the username, the
// app's version and the time.

private let now = Date(timeIntervalSince1970: 1_791_000_000)  // 2026-10-03 04:00 UTC

@MainActor
private final class RecordingMailer: ReportMailing {
    var answer: ReportDelivery
    private(set) var mails: [ReportMail] = []

    init(_ answer: ReportDelivery) { self.answer = answer }

    func deliver(_ mail: ReportMail) async -> ReportDelivery {
        mails.append(mail)
        return answer
    }
}

@MainActor
private func makeApp() -> (AppModel, DesignFixturesService) {
    var configuration = AppConfiguration()
    configuration.start = .chats
    let defaults = UserDefaults(suiteName: "us2us.tests.\(UUID().uuidString)")!
    let service = DesignFixturesService(hasAccount: true, clock: .frozen(at: DesignFixtures.referenceDate), latency: .zero)
    let app = AppModel(configuration: configuration, preferences: AppPreferences(defaults: defaults), messenger: service)
    return (app, service)
}

@Suite("Suspended account")
@MainActor
struct AccountSuspensionTests {
    @Test("the runtime's suspended line is its own state; revoked and the others are unchanged")
    func connectionMapping() {
        #expect(LiveMapping.connection(.init(ConnectionStatus.suspended)) == .suspended)
        #expect(LiveMapping.connection(.init(ConnectionStatus.revoked)) == .revoked)
        #expect(LiveMapping.connection(.init(ConnectionStatus.open)) == .open)
        #expect(LiveMapping.connection(.init(ConnectionStatus.retrying(at: now))) == .connecting)
        #expect(LiveMapping.connection(.init(ConnectionStatus.waitingForNetwork)) == .closed)
    }

    @Test("the notice covers the unlocked app only while the account is suspended")
    func noticeFollowsTheState() {
        let (app, service) = makeApp()
        #expect(!app.showsAccountSuspended)
        service.setConnection(.suspended)
        #expect(app.showsAccountSuspended)
        service.setConnection(.revoked)
        #expect(!app.showsAccountSuspended, "a revoked token keeps its own line")
        service.setConnection(.suspended)
        app.returnToLock()
        #expect(!app.showsAccountSuspended, "never over the lock")
    }

    @Test("the e-mail to support names the username, the app and the time — in both languages")
    func mail() {
        let turkish = SuspensionMail.make(
            username: "kaan_s", version: "1.2.0", build: "31", date: now, locale: Locale(identifier: "tr_TR"))
        #expect(turkish.subject == "us2us: askıya alınan hesap (@kaan_s)")
        #expect(
            turkish.body == """
                Kullanıcı adı: @kaan_s
                Uygulama: us2us iOS 1.2.0 (31)
                Zaman: 2026-10-03 04:00 UTC

                Neden bir hata olduğunu düşündüğünü aşağıya yaz:

                """)
        #expect(turkish.mailtoURL?.absoluteString.hasPrefix("mailto:support@us2us.app?subject=") == true)
        let english = SuspensionMail.make(
            username: "kaan_s", version: "1.2.0", build: "31", date: now, locale: Locale(identifier: "en_TR"))
        #expect(english.subject == "us2us: suspended account (@kaan_s)")
        #expect(english.body.hasPrefix("Username: @kaan_s\nApp: us2us iOS 1.2.0 (31)\nTime: 2026-10-03 04:00 UTC"))
    }

    @Test("without a username the e-mail names nobody")
    func mailWithoutUsername() {
        for username in [nil, ""] as [String?] {
            let mail = SuspensionMail.make(
                username: username, version: "1", build: "1", date: now, locale: Locale(identifier: "tr_TR"))
            #expect(mail.subject == "us2us: askıya alınan hesap")
            #expect(!mail.body.contains("@"))
        }
    }

    @Test("“Destek ekibine yaz”: Mail sends it; without a mail app the address is shown to copy")
    func writeToSupport() async {
        let sent = RecordingMailer(.sent)
        let model = AccountSuspendedModel(mailer: sent)
        model.version = "1.2.0"
        model.build = "31"
        await model.writeToSupport(username: "kaan_s", locale: Locale(identifier: "tr_TR"), now: now)
        #expect(model.phase == .idle)
        #expect(sent.mails.map(\.subject) == ["us2us: askıya alınan hesap (@kaan_s)"])

        let none = RecordingMailer(.addressShown)
        let fallback = AccountSuspendedModel(mailer: none)
        await fallback.writeToSupport(username: nil, locale: Locale(identifier: "en_TR"), now: now)
        #expect(fallback.phase == .address)
        #expect(none.mails.count == 1)

        // Mail closed without sending: back as it was, ready to try again.
        let cancelled = RecordingMailer(.notSent)
        let again = AccountSuspendedModel(mailer: cancelled)
        await again.writeToSupport(username: "kaan_s", locale: Locale(identifier: "tr_TR"), now: now)
        #expect(again.phase == .idle)
    }

    @Test("every suspended-screen text exists in Turkish and English")
    func strings() {
        let keys: [String.LocalizationValue] = [
            "suspended.title", "suspended.message", "suspended.note", "suspended.write", "suspended.terms",
            "suspended.terms.hint", "suspended.address", "suspended.line", "suspended.mail.prompt",
        ]
        for key in keys {
            let tr = StringTable.suspension.string(key, locale: Locale(identifier: "tr"))
            let en = StringTable.suspension.string(key, locale: Locale(identifier: "en"))
            #expect(!tr.hasPrefix("suspended.") && !en.hasPrefix("suspended.") && tr != en)
        }
        #expect(
            StringTable.suspension.string("suspended.title", locale: Locale(identifier: "tr")) == "Hesabın askıya alındı")
        #expect(
            StringTable.suspension.string("suspended.message", locale: Locale(identifier: "tr"))
                == "Bu hesap kullanım koşullarını ihlal ettiği için askıya alındı. Bir hata olduğunu düşünüyorsan support@us2us.app adresine yaz.")
        #expect(StringTable.suspension.string("suspended.message", locale: Locale(identifier: "en")).contains(ReportMail.address))
    }
}
