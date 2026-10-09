import SwiftUI
import XCTest

@testable import us2us

/// CSS line boxes as the design's renderer (Chrome/Blink) builds them, and the
/// Doto headings measured against the design (inventory.md element tables).
@MainActor
final class DisplayTextMetricsTests: XCTestCase {
    override func setUp() {
        super.setUp()
        U2Fonts.registerBundledFonts()
    }

    func testLineMetricsRoundLikeBlink() {
        // Doto 46: ascent 43.7 → 44, descent 11.5 → 12; half-leading −5.
        let doto = CSSLineMetrics(font: U2TextStyle.displayHero.uiFont(), lineHeight: 46)
        XCTAssertEqual(doto, CSSLineMetrics(ascent: 39, descent: 7))
        // Inter 700 46: ascent 44.56 → 45, descent 11.10 → 11; half-leading −5.
        let inter = CSSLineMetrics(font: U2Fonts.font(.inter, weight: 700, size: 46), lineHeight: 46)
        XCTAssertEqual(inter, CSSLineMetrics(ascent: 40, descent: 6))
        // A line with both grows to 47, baseline 40 below its top.
        XCTAssertEqual(doto.union(inter), CSSLineMetrics(ascent: 40, descent: 7))
        // Doto 40 at 1.02: half-leading −3.6 floors to −4.
        let title = CSSLineMetrics(font: U2TextStyle.displayTitleL.uiFont(), lineHeight: 40.8)
        XCTAssertEqual(title.ascent, 34, accuracy: 0.001)
        XCTAssertEqual(title.height, 40.8, accuracy: 0.001)
    }

    /// 1.1 r.4.0: “İki uç. Bir hat.” 354 × 94 — two 47 pt lines (each holds an Inter “.”).
    func testWelcomeHeadlineIsTwo47PointLines() {
        let size = layoutSize(U2DisplayText(verbatim: "İki uç. Bir hat.", style: .displayHero), width: 354)
        XCTAssertMeasure(size.width, 354)
        XCTAssertMeasure(size.height, 94)
    }

    /// 1.2 r.4.0: “Bildirim yok. Bilerek.” 354 × 81.59 — the full stops fit the 40.8 pt lines.
    func testTitleLinesKeepTheirLineHeight() {
        let size = layoutSize(U2DisplayText(verbatim: "Bildirim yok. Bilerek.", style: .displayTitleL), width: 354)
        XCTAssertMeasure(size.height, 81.6)
    }

    /// A line without a full stop keeps the strut's height.
    func testLineWithoutPunctuationUsesTheStrut() {
        let size = layoutSize(U2DisplayText(verbatim: "Kullanıcı", style: .displayHero), width: 354)
        XCTAssertMeasure(size.height, 46)
    }

    /// With the frozen design clock the welcome scene is pinned to the
    /// reference PNGs' moment; live, it runs.
    func testWelcomeScenePinsToTheReferenceMoment() {
        XCTAssertEqual(WelcomeSceneHook.referenceTime(.dark), 4.5)
        XCTAssertEqual(WelcomeSceneHook.referenceTime(.light), 39.95)
    }
}
