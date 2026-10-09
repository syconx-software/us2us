import Foundation
import Testing

@testable import Us2usKit

@Suite("Localization: Turkish and English")
struct LocalizationTests {
    @Test("every key exists in both languages, with matching placeholders", arguments: LocalizationKey.allCases)
    func bothLanguages(_ key: LocalizationKey) throws {
        let english = try #require(Localizer.lookup(key.rawValue, language: "en"))
        let turkish = try #require(Localizer.lookup(key.rawValue, language: "tr"))
        #expect(!english.isEmpty && !turkish.isEmpty)
        func placeholders(_ text: String) -> [String] {
            let regex = try! NSRegularExpression(pattern: "%[@a-z]+")
            return regex.matches(in: text, range: NSRange(text.startIndex..., in: text))
                .map { String(text[Range($0.range, in: text)!]) }.sorted()
        }
        #expect(placeholders(english) == placeholders(turkish))
    }

    @Test func turkishAndEnglishDiffer() {
        #expect(Localizer(languageCode: "tr").string(.newMessage) == "Yeni mesaj")
        #expect(Localizer(languageCode: "en").string(.newMessage) == "New message")
    }

    @Test("unsupported languages fall back to English")
    func fallback() {
        #expect(Localizer(languageCode: "de").languageCode == "en")
        #expect(Localizer(languageCode: "de").string(.newMessage) == "New message")
        #expect(Localizer.supportedLanguages.contains(Localizer.current.languageCode))
    }

    @Test func formattedStringsAndDurations() {
        let tr = Localizer(languageCode: "tr")
        let en = Localizer(languageCode: "en")
        #expect(en.duration(seconds: 3600) == "1 hour")
        #expect(tr.duration(seconds: 3600) == "1 saat")
        #expect(tr.string(.screenshotTaken, "Ayşe") == "Ayşe ekran görüntüsü aldı.")
        #expect(
            en.string(.vaultLockedOut, en.duration(seconds: 30)) == "Too many wrong attempts. Try again in 30 seconds.")
    }

    @Test("the unlock screen's sentence after a wrong code matches the design")
    func wrongCodeNotice() {
        let tr = Localizer(languageCode: "tr")
        let en = Localizer(languageCode: "en")
        let first = UnlockStatus(
            failures: 1, nextAttemptAllowedAt: nil, upcoming: UnlockThrottle.standard.upcomingDelay(afterFailures: 1))
        #expect(tr.wrongCodeNotice(first) == "Kod yanlış. 2 yanlış daha olursa 1 dakika beklemen gerekir.")
        #expect(en.wrongCodeNotice(first) == "Wrong code. 2 more wrong attempts and you'll have to wait 1 minute.")
        let fourth = UnlockStatus(
            failures: 4, nextAttemptAllowedAt: nil, upcoming: UnlockThrottle.standard.upcomingDelay(afterFailures: 4))
        #expect(tr.wrongCodeNotice(fourth) == "Kod yanlış. 1 yanlış daha olursa 15 dakika beklemen gerekir.")
        #expect(en.wrongCodeNotice(fourth) == "Wrong code. One more wrong attempt and you'll have to wait 15 minutes.")
        let none = UnlockStatus(failures: 1, nextAttemptAllowedAt: nil, upcoming: nil)
        #expect(tr.wrongCodeNotice(none) == "Gizli kod yanlış.")
    }

    @Test("ghost-mode and disappearing-message notices")
    func settingsNotices() {
        let tr = Localizer(languageCode: "tr")
        let en = Localizer(languageCode: "en")
        var settings = BRPConversationSettings()
        settings.ghost.enabled = true
        settings.ghost.timeoutSeconds = 3600
        #expect(
            tr.notices(for: settings, previous: nil) == [
                "Hayalet mod açık: 1 saat boyunca mesajlaşılmazsa sohbet silinir."
            ])
        #expect(en.notices(for: settings, previous: nil).first?.contains("1 hour") == true)

        var off = settings
        off.ghost.enabled = false
        off.defaultExpireTimerSeconds = 86400
        #expect(en.notices(for: off, previous: settings) == ["Ghost mode is off.", "Disappearing messages: 1 day."])
        #expect(en.notices(for: settings, previous: settings).isEmpty)

        var wipe = BRPConversationWipe()
        wipe.reason = .ghostTimeout
        #expect(tr.notice(for: wipe) == "Bu sohbet hayalet mod tarafından silindi.")
    }

    @Test("errors carry localized descriptions")
    func errorDescriptions() {
        let errors: [any LocalizedError] = [
            UsernameError.tooLong, SecretCodeError.sequence, SecretCodeError.notSixDigits, VaultError.wrongCode,
            VaultError.malformedCode, VaultError.lockedOut(until: Date().addingTimeInterval(60)),
            AccountBackupError.wrongPassword, BackupPasswordError.tooShort, BackupPasswordError.mismatch,
            AttachmentError.integrityCheckFailed, RelayError.server(status: 409, code: .usernameTaken, message: nil),
            RelayError.rateLimited(retryAfter: 30), SessionError.identityChanged(AccountId(uuid: UUID())),
            MessengerError.contentTooLarge, LocalStorageError.integrityCheckFailed,
        ]
        for error in errors {
            #expect(error.errorDescription?.isEmpty == false)
        }
    }
}
