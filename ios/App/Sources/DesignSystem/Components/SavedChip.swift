import SwiftUI

/// “Kaydetti” under the user's own photo, video or file once the other side
/// saved it to their phone (their SAVED receipt, PROTOCOL §8.4; proposed):
/// the reaction chip's look — 27 high, a capsule in `accent800` with the
/// 2.5 pt `bg` cut-out ring — holding ↓ (11) and the word in `monoTag`
/// `accent100`. It sits next to the reaction, 12 pt in from the bubble's
/// leading edge, overlapping the bubble's bottom by 15 pt
/// (`MessageBubbleView`).
struct SavedChip: View {
    var body: some View {
        HStack(spacing: 4) {
            MediaGlyphIcon(.arrowDown, size: 11)
            Text("bubble.saved", table: .conversation)
                .u2Font(.monoTag)
                .lineLimit(1)
                .fixedSize()
        }
        .foregroundStyle(Color.u2.accent100)
        .padding(.horizontal, 9)
        .frame(minHeight: 27, maxHeight: 27)
        .background(Capsule().fill(Color.u2.accent800))
        .u2OuterRing(Color.u2.bg, width: 2.5, in: Capsule())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("bubble.saved.a11y", table: .conversation))
        .accessibilityIdentifier("bubble.saved")
    }
}

#Preview("Kaydetti") {
    HStack(spacing: 6) {
        ReactionChip(reaction: Reaction(emoji: "❤️"))
        SavedChip()
    }
    .padding(30)
    .background(Color.u2.bg)
}
