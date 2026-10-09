import CoreText
import UIKit

/// The three families the design uses (tokens.md › Typography › Families).
enum U2FontFamily: String, Sendable, Hashable, CaseIterable {
    /// Doto — brand moments, titles, counters. Always ROND = 100 (round dots).
    case doto
    /// Martian Mono — labels, usernames, codes. Pinned to wdth = 100, the
    /// instance Google Fonts serves for `Martian Mono:wght@400;500` (its
    /// advances match every measured width in the design: “1 / 3” at 11 pt is
    /// 38.5 pt, “deniz” at 15 pt is 52.5 pt). The variable font's own default
    /// is wdth 112.5 (“SemiExpanded”), which is 7 % wider.
    case martianMono
    /// Inter 4 (text optical size, opsz = 14) — all interface text.
    case inter

    /// hhea (ascender + |descender| + lineGap) / unitsPerEm — the height of
    /// one line of text at size 1, what CSS calls the “normal” line height.
    var naturalLineHeightRatio: CGFloat {
        switch self {
        case .doto: (950 + 250) / 1000
        case .martianMono: (1000 + 200) / 1000
        case .inter: (1984 + 494) / 2048
        }
    }
}

/// Loads the bundled variable fonts and creates `UIFont`s with the exact axis
/// values the design uses. Thread-safe; fonts are cached.
///
/// The fonts are variable TTFs straight from the google/fonts repository
/// (Resources/Fonts, SIL OFL 1.1 — Resources/Licenses):
///
/// | file | axes | design values |
/// |---|---|---|
/// | Doto-Variable.ttf | ROND 0–100, wght 100–900 | ROND 100, wght 800/900 |
/// | MartianMono-Variable.ttf | wght 100–800, wdth 75–112.5 | wdth 100, wght 400/500 |
/// | Inter-Variable.ttf | opsz 14–32, wght 100–900 | opsz 14, wght 400–700 (italic = synthetic oblique, as in the design) |
///
/// Every axis is set explicitly. Martian Mono's default instance is wdth 112.5
/// (the design was rendered at 100), and Core Text would otherwise size
/// Inter's opsz automatically — both would change every measured width.
enum U2Fonts {
    private struct Key: Hashable {
        let family: U2FontFamily
        let weight: Int
        let size: CGFloat
        let italic: Bool
        let monospacedDigits: Bool
    }

    /// Fonts and descriptors are immutable; access to the dictionaries is serialised.
    private final class Store: @unchecked Sendable {
        private let lock = NSLock()
        private var descriptors: [String: CTFontDescriptor] = [:]
        private var cache: [Key: UIFont] = [:]
        private var registered = false

        /// Runs `body` once per process.
        func registerOnce(_ body: () -> [String: CTFontDescriptor]) {
            lock.lock()
            defer { lock.unlock() }
            guard !registered else { return }
            registered = true
            descriptors = body()
        }

        func descriptor(_ file: String) -> CTFontDescriptor? {
            lock.lock()
            defer { lock.unlock() }
            return descriptors[file]
        }

        func cached(_ key: Key) -> UIFont? {
            lock.lock()
            defer { lock.unlock() }
            return cache[key]
        }

        func store(_ font: UIFont, for key: Key) {
            lock.lock()
            defer { lock.unlock() }
            cache[key] = font
        }
    }

    private static let store = Store()

    /// Bundle file name (without extension) per face.
    private static func fileName(_ family: U2FontFamily, italic: Bool) -> String {
        switch family {
        case .doto: "Doto-Variable"
        case .martianMono: "MartianMono-Variable"
        case .inter: "Inter-Variable"
        }
    }

    private static let allFiles = ["Doto-Variable", "MartianMono-Variable", "Inter-Variable"]

    /// The design loads no italic face, so the browser slants upright Inter
    /// (the 5.2 tombstone). Reproduce the same synthetic oblique: a 0.25
    /// horizontal shear (≈ 14°), advances unchanged.
    private static let obliqueMatrix = CGAffineTransform(a: 1, b: 0, c: 0.25, d: 1, tx: 0, ty: 0)

    /// Registers the bundled fonts for the process (idempotent). Called at
    /// launch; font creation also does it lazily.
    static func registerBundledFonts() {
        store.registerOnce {
            var descriptors: [String: CTFontDescriptor] = [:]
            for name in allFiles {
                guard let url = fontURL(name) else {
                    assertionFailure("missing bundled font \(name).ttf")
                    continue
                }
                if let faces = CTFontManagerCreateFontDescriptorsFromURL(url as CFURL) as? [CTFontDescriptor],
                    let first = faces.first
                {
                    descriptors[name] = first
                }
                // Process-scope registration also makes the families visible to
                // UIKit by name (UILabel, attributed strings).
                var error: Unmanaged<CFError>?
                if !CTFontManagerRegisterFontsForURL(url as CFURL, .process, &error) {
                    // Already registered (e.g. by a test host) is fine.
                    error?.release()
                }
            }
            return descriptors
        }
    }

    private static func fontURL(_ name: String) -> URL? {
        let bundle = Bundle.main
        return bundle.url(forResource: name, withExtension: "ttf", subdirectory: "Fonts")
            ?? bundle.url(forResource: name, withExtension: "ttf")
    }

    /// A font of the design system. `weight` is the CSS weight (100…900).
    static func font(
        _ family: U2FontFamily, weight: Int, size: CGFloat, italic: Bool = false, monospacedDigits: Bool = false
    ) -> UIFont {
        registerBundledFonts()
        let key = Key(family: family, weight: weight, size: size, italic: italic && family == .inter, monospacedDigits: monospacedDigits)
        if let cached = store.cached(key) { return cached }

        let file = fileName(family, italic: key.italic)
        guard let base = store.descriptor(file) else {
            assertionFailure("font \(file) not loaded")
            return UIFont.systemFont(ofSize: size, weight: .regular)
        }

        var variations: [NSNumber: NSNumber] = [axis("wght"): NSNumber(value: weight)]
        switch family {
        case .doto:
            variations[axis("ROND")] = 100
        case .martianMono:
            variations[axis("wdth")] = 100
        case .inter:
            variations[axis("opsz")] = 14
        }
        var attributes: [CFString: Any] = [kCTFontVariationAttribute: variations]
        if family == .inter {
            // Keep the text cut at every size (no automatic optical sizing).
            attributes[kCTFontOpticalSizeAttribute] = "none" as CFString
        }
        if monospacedDigits {
            attributes[kCTFontFeatureSettingsAttribute] = [
                [kCTFontOpenTypeFeatureTag: "tnum", kCTFontOpenTypeFeatureValue: 1] as [CFString: Any]
            ]
        }
        let descriptor = CTFontDescriptorCreateCopyWithAttributes(base, attributes as CFDictionary)
        var matrix = obliqueMatrix
        let font: UIFont =
            key.italic
            ? CTFontCreateWithFontDescriptor(descriptor, size, &matrix) as UIFont
            : CTFontCreateWithFontDescriptor(descriptor, size, nil) as UIFont
        store.store(font, for: key)
        return font
    }

    /// An OpenType axis tag as Core Text expects it (`'wght'` → 0x77676874).
    static func axis(_ tag: String) -> NSNumber {
        precondition(tag.utf8.count == 4)
        let value = tag.utf8.reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
        return NSNumber(value: value)
    }
}
