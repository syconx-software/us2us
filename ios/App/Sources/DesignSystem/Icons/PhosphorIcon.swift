import SwiftUI

/// Phosphor weights bundled by the app (regular + fill, icons.md).
enum PhosphorWeight: String, Sendable, Hashable {
    case regular
    case fill
}

extension PhosphorGlyph {
    /// Asset-catalog name: `ph-<name>` or `ph-<name>-fill`.
    func assetName(_ weight: PhosphorWeight) -> String {
        weight == .fill ? "ph-\(rawValue)-fill" : "ph-\(rawValue)"
    }
}

/// A Phosphor 2.1.1 glyph drawn exactly like the design: the CSS font-size of a
/// Phosphor glyph equals its square box, so “ph:ghost 16” is a 16 × 16 pt
/// frame (icons.md › Sizing rule). The colour comes from the foreground style.
///
/// ```swift
/// PhosphorIcon(.ghost, size: 16).foregroundStyle(Color.u2.accent300)
/// PhosphorIcon(.checks, weight: .fill, size: 15)
/// ```
///
/// Icons are decorative by default (hidden from VoiceOver); pass a label when
/// the glyph carries meaning on its own.
struct PhosphorIcon: View {
    let glyph: PhosphorGlyph
    let weight: PhosphorWeight
    let size: CGFloat
    let label: Text?

    init(_ glyph: PhosphorGlyph, weight: PhosphorWeight = .regular, size: CGFloat, label: Text? = nil) {
        assert(glyph.availableWeights.contains(weight), "ph:\(glyph.rawValue) (\(weight)) is not bundled — see icons.md")
        self.glyph = glyph
        self.weight = weight
        self.size = size
        self.label = label
    }

    var body: some View {
        let image = Image(glyph.assetName(weight))
            .renderingMode(.template)
            .resizable()
            .interpolation(.high)
            .frame(width: size, height: size)
        if let label {
            image.accessibilityLabel(label)
        } else {
            image.accessibilityHidden(true)
        }
    }
}

#Preview("Phosphor glyphs") {
    ScrollView {
        LazyVGrid(columns: Array(repeating: GridItem(.fixed(44)), count: 6), spacing: 14) {
            ForEach(PhosphorGlyph.allCases, id: \.self) { glyph in
                ForEach(Array(glyph.availableWeights).sorted { $0.rawValue > $1.rawValue }, id: \.self) { weight in
                    PhosphorIcon(glyph, weight: weight, size: 24)
                        .foregroundStyle(weight == .fill ? Color.u2.accent300 : Color.u2.text)
                }
            }
        }
        .padding(20)
    }
    .background(Color.u2.bg)
}
