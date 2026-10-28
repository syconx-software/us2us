import SwiftUI

/// An empty list (proposed): a soft `accent·12%` disc with a Phosphor
/// glyph, the explanation (`callout`, `textSecondary`, centred, at most 290
/// wide) and, under it, the ways forward if there are any. The same look on
/// 4.1 (no chats), 4.3 (no requests) and Engellenenler (nobody blocked), so
/// an empty screen reads as a state, not as something that failed to load.
struct U2EmptyState<Actions: View>: View {
    let glyph: PhosphorGlyph
    let message: Text
    var topPadding: CGFloat = 96
    /// The message's accessibility identifier (UI tests read its label).
    var identifier: String = ""
    @ViewBuilder let actions: Actions

    init(
        glyph: PhosphorGlyph, message: Text, topPadding: CGFloat = 96, identifier: String = "",
        @ViewBuilder actions: () -> Actions
    ) {
        self.glyph = glyph
        self.message = message
        self.topPadding = topPadding
        self.identifier = identifier
        self.actions = actions()
    }

    var body: some View {
        VStack(spacing: 0) {
            ZStack {
                Circle().fill(Color.u2.accent.opacity(0.12))
                PhosphorIcon(glyph, size: 28)
                    .foregroundStyle(Color.u2.accent300)
            }
            .frame(width: 60, height: 60)
            .accessibilityHidden(true)
            message
                .u2Font(.callout)
                .foregroundStyle(Color.u2.textSecondary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 290)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 16)
                .accessibilityIdentifier(identifier)
            actions
        }
        .frame(maxWidth: .infinity)
        .padding(.top, topPadding)
        .padding(.horizontal, U2Spacing.screenGutterL)
    }
}

extension U2EmptyState where Actions == EmptyView {
    init(glyph: PhosphorGlyph, message: Text, topPadding: CGFloat = 96, identifier: String = "") {
        self.init(glyph: glyph, message: message, topPadding: topPadding, identifier: identifier) { EmptyView() }
    }
}
