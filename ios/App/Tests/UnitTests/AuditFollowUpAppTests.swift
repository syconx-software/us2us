import Foundation
import SwiftUI
import Testing

@testable import us2us

/// App side of the 2026-10-28 audit fixes: the owner's “Tümünü reddet”
/// (M-14) and the strings shared with Android through
/// Resources/Localizable.xcstrings.
@MainActor
@Suite("Security audit 2026-10-28: app follow-ups")
struct AuditFollowUpAppTests {
    private let tr = Locale(identifier: "tr")
    private let en = Locale(identifier: "en")

    @Test("reject all is a confirmed step with the shared wording in both languages")
    func rejectAllConfirmation() {
        let step = CommunityConfirmation.rejectAll("c1")
        #expect(step.id == "rejectAll.c1")
        #expect(step.title(locale: tr) == "Tüm katılım istekleri reddedilsin mi?")
        #expect(step.title(locale: en) == "Reject all join requests?")
        #expect(step.glyph == .prohibit)
        #expect(step != .delete("c1"))
    }

    @Test("the catalogue holds every string Android takes from it, in Turkish and English")
    func sharedStrings() {
        let expected: [(String, String, String)] = [
            ("community.rejectAll", "Tümünü reddet", "Reject all"),
            ("community.rejectAll.title", "Tüm katılım istekleri reddedilsin mi?", "Reject all join requests?"),
            ("community.rejectAll.message",
             "Bekleyen herkes geri çevrilir, kullandıkları davetler de geçersiz olur. Daha sonra yeniden isteyebilirler.",
             "Everyone waiting is turned away, and invitations they used stop working. They can ask again later."),
            ("community.rejectAll.confirm", "Tümünü reddet", "Reject all"),
        ]
        for (key, turkish, english) in expected {
            #expect(L10n.string(String.LocalizationValue(key), locale: tr) == turkish, "\(key) tr")
            #expect(L10n.string(String.LocalizationValue(key), locale: en) == english, "\(key) en")
        }
        for key in ["security.deviceProtection.basic", "security.deviceProtection.limited"] {
            #expect(L10n.string(String.LocalizationValue(key), locale: tr).hasPrefix("Cihaz koruması: "), "\(key) tr")
            #expect(L10n.string(String.LocalizationValue(key), locale: en).hasPrefix("Device protection: "), "\(key) en")
        }
    }

    @Test("the fixtures' owner rejects every request at once")
    func fixtureRejectAll() async throws {
        #if DEBUG
            let service = DesignFixtureCommunities(english: false, now: Date(timeIntervalSince1970: 1_800_000_000))
            let club = DesignFixtureCommunities.bookClub
            #expect(service.requests(in: club).count == 2)
            try await CommunityConfirmation.rejectAll(club).perform(on: service)
            #expect(service.requests(in: club).isEmpty)
            #expect(service.requestInbox.isEmpty)
        #endif
    }
}

