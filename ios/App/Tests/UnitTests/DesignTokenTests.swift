import CoreText
import SwiftUI
import XCTest

@testable import us2us

/// The design system mirrors ios/design/tokens.json 1:1.
@MainActor
final class DesignTokenTests: XCTestCase {
    private func hexAlpha(_ value: String) -> (UInt32, Double)? {
        let parts = value.split(separator: "@")
        guard let hex = parts.first, hex.hasPrefix("#"), let rgb = UInt32(hex.dropFirst(), radix: 16) else { return nil }
        let alpha = parts.count > 1 ? (Double(parts[1].dropLast()) ?? 100) / 100 : 1
        return (rgb, alpha)
    }

    func testEveryColorTokenMatchesTokensJSON() throws {
        let tokens = try DesignSpec.json("tokens.json")
        let color = try XCTUnwrap(tokens["color"] as? [String: Any])
        var checked = 0
        for group in ["base", "semantic"] {
            let entries = try XCTUnwrap(color[group] as? [String: [String: Any]])
            for (name, entry) in entries {
                let token = try XCTUnwrap(U2ColorTokens.all[name], "missing colour token \(name)")
                let dark = try XCTUnwrap(hexAlpha(entry["dark"] as? String ?? ""), name)
                let light = try XCTUnwrap(hexAlpha(entry["light"] as? String ?? ""), name)
                XCTAssertEqual(token.dark, dark.0, "\(name) dark")
                XCTAssertEqual(token.darkAlpha, dark.1, accuracy: 0.001, "\(name) dark alpha")
                XCTAssertEqual(token.light, light.0, "\(name) light")
                XCTAssertEqual(token.lightAlpha, light.1, accuracy: 0.001, "\(name) light alpha")
                checked += 1
            }
        }
        XCTAssertGreaterThan(checked, 70)
    }

    func testEveryTypeStyleMatchesTokensJSON() throws {
        let tokens = try DesignSpec.json("tokens.json")
        let typography = try XCTUnwrap(tokens["typography"] as? [String: Any])
        let styles = try XCTUnwrap(typography["styles"] as? [String: [String: Any]])
        let byName = Dictionary(uniqueKeysWithValues: U2TextStyle.all.map { ($0.name, $0) })
        for (name, entry) in styles where name != "displayPunctuation" {
            let style = try XCTUnwrap(byName[name], "missing type style \(name)")
            XCTAssertEqual(style.weight, entry["weight"] as? Int, name)
            XCTAssertEqual(Double(style.size), entry["size"] as? Double ?? -1, accuracy: 0.001, name)
            XCTAssertEqual(Double(style.lineHeight), entry["lineHeight"] as? Double ?? -1, accuracy: 0.001, name)
            XCTAssertEqual(Double(style.tracking), entry["letterSpacing"] as? Double ?? 0, accuracy: 0.001, name)
            XCTAssertEqual(style.uppercase, (entry["textCase"] as? String) == "uppercase", name)
        }
        XCTAssertEqual(U2TextStyle.all.count, styles.count - 1)
    }

    /// The axes that make the design's measured widths: Doto ROND 100, Martian
    /// Mono at wdth 100, Inter's text cut at every size.
    func testFontsRenderTheDesignsWidths() {
        func width(_ text: String, _ style: U2TextStyle) -> CGFloat {
            (text as NSString).size(withAttributes: [.font: style.uiFont()]).width
        }
        XCTAssertEqual(width("1 / 3", .monoMeta), 38.5, accuracy: 0.05)
        XCTAssertEqual(width("deniz", .monoUsername), 52.5, accuracy: 0.05)
        XCTAssertEqual(width("Kullanılabilir", .caption), 73.17, accuracy: 0.05)
        XCTAssertEqual(width("Bu akşam boş musun?", .bubble), 169.5, accuracy: 0.05)
        XCTAssertEqual(width("us2us", .brandWordmark), 60, accuracy: 0.05)
        XCTAssertEqual(width("23:41:07", .displayCountdownL), 307.2, accuracy: 0.05)
        XCTAssertEqual(width("18:10", .caption.tabular), 37.22, accuracy: 0.05)
    }

    func testDotoIsRound() throws {
        let font = U2TextStyle.displayTitle.uiFont() as CTFont
        let variation = try XCTUnwrap(CTFontCopyVariation(font) as? [NSNumber: NSNumber])
        XCTAssertEqual(variation[U2Fonts.axis("ROND")]?.doubleValue, 100)
        // wght 900 is the font's default, so Core Text may omit it.
        XCTAssertEqual(variation[U2Fonts.axis("wght")]?.doubleValue ?? 900, 900)
        let clock = U2TextStyle.displayClock.uiFont() as CTFont
        let clockVariation = try XCTUnwrap(CTFontCopyVariation(clock) as? [NSNumber: NSNumber])
        XCTAssertEqual(clockVariation[U2Fonts.axis("wght")]?.doubleValue, 800)
    }

    func testThemedColorsResolvePerScheme() {
        let text = U2ColorTokens.text
        XCTAssertEqual(text.components(for: .dark).red, Double(0xe9) / 255, accuracy: 0.001)
        XCTAssertEqual(text.components(for: .light).red, Double(0x29) / 255, accuracy: 0.001)
        let secondary = U2ColorTokens.textSecondary
        XCTAssertEqual(secondary.components(for: .dark).alpha, 0.68, accuracy: 0.001)
        let resolved = text.uiColor.resolvedColor(with: UITraitCollection(userInterfaceStyle: .light))
        var red: CGFloat = 0
        resolved.getRed(&red, green: nil, blue: nil, alpha: nil)
        XCTAssertEqual(Double(red), Double(0x29) / 255, accuracy: 0.001)
    }

    func testEveryDesignIconIsBundled() throws {
        let fm = FileManager.default
        for weight in ["regular", "fill"] {
            let folder = DesignSpec.directory.appendingPathComponent("icons/phosphor/\(weight)")
            for file in try fm.contentsOfDirectory(atPath: folder.path) where file.hasSuffix(".svg") {
                let name = String(file.dropLast(4))
                let glyph = try XCTUnwrap(PhosphorGlyph(rawValue: name), "glyph \(name) not generated")
                let phWeight: PhosphorWeight = weight == "fill" ? .fill : .regular
                XCTAssertTrue(glyph.availableWeights.contains(phWeight))
                XCTAssertNotNil(UIImage(named: glyph.assetName(phWeight)), "asset \(glyph.assetName(phWeight)) missing")
            }
        }
    }

    func testMotionIsSoft() throws {
        let tokens = try DesignSpec.json("tokens.json")
        let motion = try XCTUnwrap(tokens["motion"] as? [String: Any])
        let proposed = try XCTUnwrap(motion["proposed"] as? [String: Any])
        let durations = try XCTUnwrap(proposed["durations"] as? [String: Double])
        for (name, value) in [
            "press": U2Motion.Duration.press, "micro": U2Motion.Duration.micro,
            "state": U2Motion.Duration.state, "screen": U2Motion.Duration.screen,
            "sheet": U2Motion.Duration.sheet, "ambient": U2Motion.Duration.ambient,
            "reducedCrossFade": U2Motion.Duration.reducedCrossFade,
        ] {
            XCTAssertEqual(value, try XCTUnwrap(durations[name]), accuracy: 0.0001, name)
        }
        let spring = try XCTUnwrap(proposed["springSoft"] as? [String: Any])
        XCTAssertEqual(U2Motion.springResponse, spring["response"] as? Double)
        XCTAssertEqual(U2Motion.springQuickResponse, spring["quickResponse"] as? Double)
        XCTAssertEqual(U2Motion.resolve(U2Motion.spring, reduceMotion: true), U2Motion.reducedCrossFade)
    }
}
