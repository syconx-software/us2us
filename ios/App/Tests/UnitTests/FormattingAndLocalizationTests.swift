import XCTest

@testable import us2us

final class FormattingTests: XCTestCase {
    private let tr = Locale(identifier: "tr_TR")
    private let en = Locale(identifier: "en_US")

    func testGhostShortFormatReproducesTheDesign() {
        XCTAssertEqual(U2Format.ghostShort(23 * 3600 + 41 * 60 + 7, locale: tr), "23 sa")
        XCTAssertEqual(U2Format.ghostShort(42 * 60 + 10, locale: tr), "43 dk")
        XCTAssertEqual(U2Format.ghostShort(37 * 60 + 20, locale: tr), "38 dk")
        XCTAssertEqual(U2Format.ghostShort(6 * 86_400 + 4 * 3600, locale: tr), "6 gün")
        XCTAssertEqual(U2Format.ghostShort(23 * 3600 + 41 * 60 + 7, locale: en), "23 h")
        XCTAssertEqual(U2Format.ghostShort(42 * 60 + 10, locale: en), "43 min")
        XCTAssertEqual(U2Format.ghostShort(6 * 86_400, locale: en), "6 days")
        XCTAssertEqual(U2Format.ghostShort(86_400, locale: en), "1 day")
        XCTAssertEqual(U2Format.ghostShort(5, locale: tr), "1 dk")
    }

    func testGhostLongFormat() {
        XCTAssertEqual(U2Format.ghostLong(23 * 3600 + 41 * 60 + 7), "23:41:07")
        XCTAssertEqual(U2Format.ghostLong(42 * 60 + 10), "00:42:10")
        XCTAssertEqual(U2Format.ghostLong(-3), "00:00:00")
        XCTAssertTrue(U2Format.ghostIsUrgent(42 * 60))
        XCTAssertFalse(U2Format.ghostIsUrgent(3600))
    }

    func testChatListTimes() {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = .current
        let now = DesignFixtures.referenceDate
        XCTAssertEqual(U2Format.chatListTime(DesignFixtures.date(day: 8, 18, 10), now: now, locale: tr, calendar: calendar), "18:10")
        XCTAssertEqual(U2Format.chatListTime(DesignFixtures.date(day: 7, 21, 34), now: now, locale: tr, calendar: calendar), "Dün")
        XCTAssertEqual(U2Format.chatListTime(DesignFixtures.date(day: 7, 21, 34), now: now, locale: en, calendar: calendar), "Yesterday")
        XCTAssertEqual(U2Format.chatListTime(DesignFixtures.date(month: 9, day: 24, 11, 5), now: now, locale: tr, calendar: calendar), "24 Eyl")
        XCTAssertEqual(U2Format.chatListTime(DesignFixtures.date(month: 9, day: 24, 11, 5), now: now, locale: en, calendar: calendar), "Sep 24")
        let lastYear = Calendar(identifier: .gregorian).date(byAdding: .year, value: -1, to: now)!
        XCTAssertTrue(U2Format.chatListTime(lastYear, now: now, locale: tr, calendar: calendar).hasSuffix(".25"))
    }

    func testNumbersFollowTheLanguage() {
        XCTAssertEqual(U2Format.integer(3920, locale: tr), "3.920")
        XCTAssertEqual(U2Format.integer(3920, locale: en), "3,920")
        XCTAssertEqual(U2Format.byteCount(2_100_000, locale: tr), "2,1 MB")
        XCTAssertEqual(U2Format.byteCount(2_100_000, locale: en), "2.1 MB")
        // An English UI on a Turkish-region device writes the design's English.
        let englishOnTurkishRegion = Locale(identifier: "en_TR")
        XCTAssertEqual(U2Format.integer(3920, locale: englishOnTurkishRegion), "3,920")
        XCTAssertEqual(U2Format.byteCount(12_400_000, locale: englishOnTurkishRegion), "12.4 MB")
        XCTAssertEqual(U2Format.voiceDuration(42), "0:42")
        XCTAssertEqual(U2Format.minutesSeconds(59.2), "01:00")
    }
}

final class LocalizationTests: XCTestCase {
    func testEveryStringHasTurkishAndEnglish() throws {
        for name in ["Localizable.xcstrings", "InfoPlist.xcstrings"] {
            let catalog = try DesignSpec.catalog(name)
            XCTAssertEqual(catalog["sourceLanguage"] as? String, "tr")
            let strings = try XCTUnwrap(catalog["strings"] as? [String: [String: Any]])
            XCTAssertFalse(strings.isEmpty)
            for (key, entry) in strings {
                let localizations = try XCTUnwrap(entry["localizations"] as? [String: Any], key)
                XCTAssertNotNil(localizations["tr"], "\(key) has no Turkish")
                XCTAssertNotNil(localizations["en"], "\(key) has no English")
            }
        }
    }

    func testLookupInAnExplicitLanguage() {
        XCTAssertEqual(L10n.string("chats.title", locale: Locale(identifier: "tr")), "Sohbetler")
        XCTAssertEqual(L10n.string("chats.title", locale: Locale(identifier: "en")), "Chats")
        XCTAssertEqual(L10n.string("chats.requests.subtitle \(2)", locale: Locale(identifier: "tr")), "2 kişi seninle yazışmak istiyor")
        XCTAssertEqual(L10n.string("chats.requests.subtitle \(1)", locale: Locale(identifier: "en")), "1 person wants to message you")
        XCTAssertEqual(L10n.string("chats.requests.subtitle \(2)", locale: Locale(identifier: "en")), "2 people want to message you")
    }

    /// 9.3 — upper-casing always passes the language.
    func testTurkishUppercase() {
        let tr = Locale(identifier: "tr")
        XCTAssertEqual(L10n.uppercased("mesaj istekleri", locale: tr), "MESAJ İSTEKLERİ")
        XCTAssertEqual(L10n.uppercased("iletildi", locale: tr), "İLETİLDİ")
        XCTAssertEqual(L10n.uppercased("gizli kodu gir", locale: tr), "GİZLİ KODU GİR")
        XCTAssertEqual("İLETİLEMEDİ".lowercased(with: tr), "iletilemedi")
    }

    func testLanguageResolution() {
        let turkey = Locale(identifier: "tr_TR")
        XCTAssertEqual(AppLanguage.system.resolvedLocale(preferredLanguages: ["tr-TR"], current: turkey).identifier, "tr_TR")
        XCTAssertEqual(AppLanguage.system.resolvedLocale(preferredLanguages: ["de-DE", "en-GB"], current: turkey).identifier, "en_TR")
        XCTAssertEqual(AppLanguage.system.resolvedLocale(preferredLanguages: ["de-DE"], current: turkey).identifier, "en_TR")
        XCTAssertEqual(AppLanguage.english.resolvedLocale(preferredLanguages: ["tr"], current: turkey).identifier, "en_TR")
    }
}

final class DomainRuleTests: XCTestCase {
    func testUsernameRules() {
        XCTAssertNil(UsernameRules.violation("selin.n"))
        XCTAssertNil(UsernameRules.violation("ayla_"))
        XCTAssertEqual(UsernameRules.violation("se"), .length)
        XCTAssertEqual(UsernameRules.violation(String(repeating: "a", count: 21)), .length)
        XCTAssertEqual(UsernameRules.violation("selin-n"), .characters)
        XCTAssertEqual(UsernameRules.violation("ayşe"), .turkishCharacters)
        XCTAssertEqual(UsernameRules.sanitize("Selin.N"), "selin.n")
        XCTAssertEqual(UsernameRules.sanitize("İpek"), "ipek")
        XCTAssertEqual(UsernameRules.sanitize("ayşe öz"), "ayeöz".filter { $0.isASCII })
        XCTAssertEqual(UsernameRules.sanitize(String(repeating: "x", count: 30)).count, 20)
    }

    func testAvatarPaletteIsStable() {
        XCTAssertEqual(AvatarPalette.forUsername("deniz"), AvatarPalette.forUsername("deniz"))
        let palettes = Set(["deniz", "mert.k", "ayla_", "kaan", "ece.y", "bora", "oya.t", "selin.n"].map(AvatarPalette.forUsername))
        XCTAssertGreaterThan(palettes.count, 1)
    }

    func testGhostState() {
        let now = DesignFixtures.referenceDate
        let ghost = GhostState(isEnabled: true, period: .oneDay, deadline: now.addingTimeInterval(30 * 60))
        XCTAssertTrue(ghost.isUrgent(at: now))
        XCTAssertEqual(ghost.remaining(at: now), 1800)
        XCTAssertNil(GhostState.off.remaining(at: now))
        XCTAssertEqual(GhostIndicatorState(.off, now: now), .off)
    }

    func testLaunchArgumentRoutes() {
        func route(_ argument: String) -> LaunchRoute? { LaunchRoute(argument: argument) }
        XCTAssertEqual(route("conversation:deniz")?.path, [.conversation("deniz")])
        XCTAssertEqual(route("conversation:deniz/5.3")?.path, [.conversation("deniz")])
        XCTAssertEqual(route("conversation:deniz/5.3")?.state(for: .conversation), "5.3")
        XCTAssertEqual(route("conversation:mert.k/5.2-ended")?.target, "mert.k")
        XCTAssertNil(route("conversation:deniz/5.3")?.state(for: .safety))
        XCTAssertEqual(route("settings")?.path, [.settings])
        XCTAssertEqual(route("settings:7.2-empty")?.state(for: .settings), "7.2-empty")
        XCTAssertEqual(route("safety:deniz/scan")?.path, [.safetyNumber("deniz")])
        XCTAssertEqual(route("safety:deniz/scan")?.state(for: .safety), "scan")
        XCTAssertEqual(route("request:kuzey.dev")?.path, [.requests, .requestPreview("kuzey.dev")])
        XCTAssertEqual(route("requests")?.path, [.requests])
        XCTAssertEqual(route("backup")?.path, [.settings, .backupExport])
        XCTAssertEqual(route("newChat:deniz")?.target, "deniz")
        XCTAssertEqual(route("newChat")?.target, "")
        XCTAssertEqual(route("lock:wrong")?.state(for: .lock), "wrong")
        XCTAssertEqual(route("lock:wrong")?.path, [], "lock states are not navigation routes")
        XCTAssertEqual(route("lock:wrong")?.impliedStart, .locked)
        XCTAssertEqual(route("onboarding:2.2")?.impliedStart, .welcome)
        XCTAssertEqual(route("chats:4.3")?.impliedStart, .chats)
        XCTAssertEqual(route("settings:7.3")?.impliedStart, .chats)
        XCTAssertNil(route("nope"))
        XCTAssertNil(route("conversation"), "a chat route needs its chat")
    }

    func testRouteImpliesStartUnlessGiven() {
        let defaults = UserDefaults(suiteName: "us2us.tests.route.\(UUID().uuidString)")!
        defaults.set("conversation:deniz", forKey: "U2Route")
        XCTAssertEqual(AppConfiguration.fromLaunchArguments(defaults).start, .chats)
        defaults.set("welcome", forKey: "U2Start")
        XCTAssertEqual(AppConfiguration.fromLaunchArguments(defaults).start, .welcome)
        defaults.set("lock:code", forKey: "U2Route")
        XCTAssertEqual(AppConfiguration.fromLaunchArguments(defaults).start, .locked, "the lock harness always starts locked")
    }
}
