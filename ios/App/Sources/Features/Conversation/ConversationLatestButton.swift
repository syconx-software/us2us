import SwiftUI

struct ConversationLatestButton: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            MediaGlyphIcon(.arrowDown, size: 18)
                .foregroundStyle(Color.u2.accent300)
                .frame(width: 48, height: 48)
                .background(Circle().fill(Color.u2.surface.opacity(0.96)))
                .u2InnerStroke(Color.u2.divider, width: 0.5, in: Circle())
                .shadow(color: .black.opacity(0.12), radius: 8, y: 3)
                .contentShape(Circle())
        }
        .buttonStyle(CardButtonStyle())
        .accessibilityLabel(Text("scroll.latest", table: .conversation))
        .accessibilityIdentifier("conversation.latest")
    }
}
