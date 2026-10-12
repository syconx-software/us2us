import Foundation
import Testing

@testable import us2us

@Suite("Design fixes (comparison round)")
@MainActor
struct DesignFixTests {
    /// 2.1: while a broken rule shows (“Geçersiz karakter” …), the field shows no verdict.
    @Test func brokenRuleHidesTheFieldVerdict() {
        let model = UsernameStepModel(messenger: DesignFixturesService(hasAccount: false, clock: .frozen(at: DesignFixtures.referenceDate)))
        model.setForHarness(name: "selin.n", status: .invalid(.characters))
        #expect(model.fieldStatus == .none)
        model.setForHarness(name: "selin_n", status: .available)
        #expect(model.fieldStatus == .positive)
        model.setForHarness(name: "selin_n", status: .meetsRules)
        #expect(model.fieldStatus == .positive)
    }

    /// 5.2: the sticker's mini line mark is the design's own box (Ø10, stroke 2, 36 wide, bar 2).
    @Test func stickerLineMarkIsTheDesignBox() {
        let metrics = LineMark.Metrics.sticker
        #expect(metrics.ringDiameter == 10)
        #expect(metrics.ringStroke == 2)
        #expect(metrics.width == 36)
        #expect(metrics.barHeight == 2)
    }

    /// C4: 21 pt glyphs sit 2/3 pt low in a standard 44 pt circle, 22 pt glyphs 1/3 pt.
    @Test func glassButtonGlyphNudge() {
        #expect(abs(U2GlassIconButton.glyphBaselineNudge(diameter: 44, glyphSize: 21) - 2.0 / 3.0) < 1e-9)
        #expect(abs(U2GlassIconButton.glyphBaselineNudge(diameter: 44, glyphSize: 22) - 1.0 / 3.0) < 1e-9)
        #expect(U2GlassIconButton.glyphBaselineNudge(diameter: 40, glyphSize: 20) == 0)
    }

    /// 4.4 → 5.1: the swap after the morph is not animated.
    @Test func replaceTopWithoutAnimationKeepsThePath() {
        let router = U2Router<AppRoute>(path: [.requests, .requestPreview("kuzey_dev")])
        router.replaceTop(with: .conversation("kuzey_dev"), animated: false)
        #expect(router.path == [.requests, .conversation("kuzey_dev")])
    }
}
