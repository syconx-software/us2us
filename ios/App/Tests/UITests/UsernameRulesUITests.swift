import XCTest

/// Usernames as people type them (PROTOCOL §3.1, `spec/username-rules.json`),
/// on the design fixtures in the light theme:
///
/// * 2.1 with the software keyboard up: every rule a name breaks is marked,
///   all four rules stay above the keyboard, and what was typed is never
///   turned into another name;
/// * a name that meets the rules and is taken at setup (the fixtures'
///   “ilgaz”): 2.3 says so, “Başka bir ad seç” goes back to 2.1 with the
///   name kept and marked;
/// * 4.2: a name nobody can have is not rewritten, the card names the rule.
///
/// A screenshot of each state is attached to the result
/// (`xcrun xcresulttool export attachments`).
@MainActor
final class UsernameRulesUITests: XCTestCase {
    /// The four 2.1 rules, in the order they are drawn.
    private enum Rule: String, CaseIterable {
        case length = "3–24 karakter"
        case firstLetter = "Harfle başlar"
        case characters = "Küçük harf, rakam ve alt çizgi"
        case turkish = "Türkçe karakter yok: ı, İ, ş, ğ, ü, ö, ç"
    }

    override func setUp() async throws {
        continueAfterFailure = false
    }

    private func launch(_ arguments: [String]) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-U2Fixtures", "YES", "-U2FrozenClock", "YES", "-U2Language", "tr", "-U2Theme", "light"] + arguments
        app.launch()
        return app
    }

    private func snap(_ app: XCUIApplication, _ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func settle(_ seconds: TimeInterval = 0.8) {
        Thread.sleep(forTimeInterval: seconds)
    }

    private func value(of element: XCUIElement) -> String { element.value as? String ?? "" }

    /// Whether `element`'s value becomes `expected` within `timeout`.
    private func waitForValue(_ element: XCUIElement, _ expected: String, timeout: TimeInterval = 3) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while value(of: element) != expected, Date() < deadline { settle(0.2) }
        return value(of: element) == expected
    }

    /// Replaces the field's text by typing, as a person would.
    private func retype(_ text: String, in field: XCUIElement) {
        // An empty field's value is its placeholder; extra deletes do nothing.
        field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: value(of: field).count) + text)
    }

    /// The keyboard is up, every rule is above it, and exactly `broken` are
    /// marked (VoiceOver: “karşılanmıyor”).
    private func assertRules(
        _ app: XCUIApplication, broken: Set<Rule>, file: StaticString = #filePath, line: UInt = #line
    ) {
        let keyboard = app.keyboards.firstMatch
        XCTAssertTrue(keyboard.waitForExistence(timeout: 5), "keyboard up", file: file, line: line)
        for rule in Rule.allCases {
            let row = app.staticTexts[rule.rawValue]
            XCTAssertTrue(row.exists, "rule “\(rule.rawValue)”", file: file, line: line)
            XCTAssertLessThanOrEqual(
                row.frame.maxY, keyboard.frame.minY, "“\(rule.rawValue)” above the keyboard", file: file, line: line)
            // The tint cross-fades (0.36 s); the value follows the state.
            let expected = broken.contains(rule) ? "karşılanmıyor" : ""
            XCTAssertTrue(
                waitForValue(row, expected), "“\(rule.rawValue)”: “\(value(of: row))”", file: file, line: line)
        }
    }

    /// 1.1 → 1.3 → 2.1 (keyboard up): a dot, a digit first, Turkish letters,
    /// 25 letters, then “ilgaz” (“Kurallara uygun”) → 2.2 → 2.3 → taken at
    /// setup → “Başka bir ad seç” → 2.1 with “ilgaz” kept and “Alınmış”.
    func testEveryBrokenRuleIsMarkedAboveTheKeyboard() {
        let app = launch(["-U2Start", "welcome", "-U2ResetPreferences", "YES"])
        app.buttons["welcome.start"].tap()
        app.buttons["welcome.page2.cta"].tap()
        app.buttons["welcome.page3.cta"].tap()
        let field = app.textFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5), "2.1 opens with the keyboard up")
        let next = app.buttons["onboarding.username.continue"]

        retype("selin.n", in: field)
        XCTAssertTrue(app.staticTexts["Geçersiz karakter"].waitForExistence(timeout: 5))
        XCTAssertEqual(field.value as? String, "selin.n")
        assertRules(app, broken: [.characters])
        XCTAssertFalse(next.isEnabled)
        settle()
        snap(app, "02-username-dot")

        retype("1abc", in: field)
        XCTAssertTrue(app.staticTexts["Harfle başlamalı"].waitForExistence(timeout: 5))
        XCTAssertEqual(field.value as? String, "1abc")
        assertRules(app, broken: [.firstLetter])
        XCTAssertFalse(next.isEnabled)
        settle()
        snap(app, "03-username-first-digit")

        // Shown in lower case, as the name would be; never “kagan” or “kaan”.
        // A lone capital is shown in lower case too.
        retype("K", in: field)
        XCTAssertTrue(waitForValue(field, "k"), "“\(value(of: field))”")
        field.typeText("ağan")
        XCTAssertTrue(app.staticTexts["Türkçe karakter kullanılamaz"].waitForExistence(timeout: 5))
        XCTAssertEqual(field.value as? String, "kağan")
        assertRules(app, broken: [.turkish])
        XCTAssertFalse(next.isEnabled)
        settle()
        snap(app, "04-username-turkish")

        let twentyFive = "abcdefghijklmnopqrstuvwxy"
        retype(twentyFive, in: field)
        XCTAssertTrue(app.staticTexts["En fazla 24 karakter"].waitForExistence(timeout: 5))
        XCTAssertEqual(field.value as? String, twentyFive, "nothing is cut")
        XCTAssertTrue(app.staticTexts["25 / 24 karakter"].exists)
        assertRules(app, broken: [.length])
        XCTAssertFalse(next.isEnabled)
        settle()
        snap(app, "05-username-25")

        retype("ilgaz", in: field)
        XCTAssertTrue(app.staticTexts["Kurallara uygun"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["5 / 24 karakter"].exists)
        assertRules(app, broken: [])
        XCTAssertTrue(next.wait(for: \.isEnabled, toEqual: true, timeout: 5))
        settle()
        snap(app, "01-username-valid")

        // The keyboard covers “Devam”; its Continue key does the same.
        if next.isHittable { next.tap() } else { field.typeText("\n") }
        XCTAssertTrue(app.buttons["2"].waitForExistence(timeout: 5))
        for digit in "246813" { app.buttons[String(digit)].tap() }
        XCTAssertTrue(app.staticTexts["Kodu tekrar gir"].waitForExistence(timeout: 3))
        for digit in "246813" { app.buttons[String(digit)].tap() }
        let ack = app.descendants(matching: .any)["onboarding.acknowledge"].firstMatch
        XCTAssertTrue(ack.waitForExistence(timeout: 5))
        ack.tap()
        let finish = app.buttons["onboarding.finish"]
        finish.tap()
        XCTAssertTrue(app.staticTexts["Bu kullanıcı adı alınmış."].waitForExistence(timeout: 5))
        XCTAssertTrue(finish.wait(for: \.label, toEqual: "Başka bir ad seç", timeout: 5))
        settle()
        snap(app, "06-setup-taken")

        finish.tap()
        XCTAssertTrue(app.staticTexts["Alınmış"].waitForExistence(timeout: 5))
        XCTAssertEqual(field.value as? String, "ilgaz")
        field.tap()
        assertRules(app, broken: [])
        XCTAssertFalse(next.isEnabled)
        settle()
        snap(app, "07-back-to-21-taken")
    }

    /// 4.2: “selin.n” is searched as typed and answered with the rule it
    /// breaks; nothing is looked up, nobody is offered.
    func testSearchNamesTheBrokenRule() {
        let app = launch(["-U2Route", "newChat"])
        let field = app.textFields["newChat.field"]
        XCTAssertTrue(field.waitForExistence(timeout: 8))
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5), "4.2 opens with the keyboard up")
        field.typeText("selin.n")
        let result = app.descendants(matching: .any)["newChat.result"]
        XCTAssertTrue(result.waitForExistence(timeout: 5))
        let card = result.descendants(matching: .any)
            .matching(NSPredicate(format: "label CONTAINS %@", "Geçersiz kullanıcı adı · Yalnızca küçük harf, rakam ve alt çizgi."))
            .firstMatch
        XCTAssertTrue(card.waitForExistence(timeout: 5))
        XCTAssertTrue(card.label.contains("selin.n"), "the card shows the name as typed: “\(card.label)”")
        XCTAssertEqual(field.value as? String, "selin.n")
        XCTAssertFalse(app.buttons["newChat.write"].exists)
        settle()
        snap(app, "08-search-dot")
    }
}
