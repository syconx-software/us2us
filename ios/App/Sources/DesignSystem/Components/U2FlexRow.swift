import SwiftUI

/// A horizontal row whose children share the width by CSS `flex: N` (basis
/// 0 %) arithmetic on content boxes, e.g. the request decision buttons
/// 1 : 1 : 1.3 (4.3) and 1 : 1 : 1.4 (4.4). A child's fixed horizontal
/// border (`.u2Flex(1.3, border: 2)` for the outlined “Kabul et”, 1 pt each
/// side) is taken off the free space first and added back to that child —
/// that is why the design's buttons are 97.58 / 97.58 / 128.84 (4.3) and
/// 103.53 / 103.53 / 146.94 (4.4) rather than a plain 1 : 1 : 1.3 split.
/// Children without a factor get 1. Height = tallest child.
struct U2FlexRow: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout Void) -> CGSize {
        let width = proposal.width ?? subviews.reduce(0) { $0 + $1.sizeThatFits(.unspecified).width }
        let widths = layout(width: width, subviews: subviews)
        let height = zip(subviews, widths).map { subview, w in
            subview.sizeThatFits(ProposedViewSize(width: w, height: proposal.height)).height
        }.max() ?? 0
        return CGSize(width: width, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout Void) {
        var x = bounds.minX
        for (subview, w) in zip(subviews, layout(width: bounds.width, subviews: subviews)) {
            subview.place(at: CGPoint(x: x, y: bounds.midY), anchor: .leading, proposal: ProposedViewSize(width: w, height: bounds.height))
            x += w + spacing
        }
    }

    private func layout(width: CGFloat, subviews: Subviews) -> [CGFloat] {
        Self.widths(width: width, spacing: spacing, items: subviews.map { ($0[U2FlexKey.self].grow, $0[U2FlexKey.self].border) })
    }

    /// The children's widths for `width`: grow factors share what is left
    /// after the gaps and the fixed borders; each border is added back.
    static func widths(width: CGFloat, spacing: CGFloat, items: [(grow: CGFloat, border: CGFloat)]) -> [CGFloat] {
        let grow = items.reduce(0) { $0 + $1.grow }
        let borders = items.reduce(0) { $0 + $1.border }
        let free = max(0, width - spacing * CGFloat(max(0, items.count - 1)) - borders)
        return items.map { item in (grow > 0 ? free * item.grow / grow : 0) + item.border }
    }
}

private struct U2FlexItem: Sendable {
    var grow: CGFloat
    var border: CGFloat
}

private struct U2FlexKey: LayoutValueKey {
    static let defaultValue = U2FlexItem(grow: 1, border: 0)
}

extension View {
    /// The CSS `flex` grow factor of this child inside a `U2FlexRow`, and its
    /// fixed horizontal border width (both sides together).
    func u2Flex(_ grow: CGFloat, border: CGFloat = 0) -> some View {
        layoutValue(key: U2FlexKey.self, value: U2FlexItem(grow: grow, border: border))
    }
}
