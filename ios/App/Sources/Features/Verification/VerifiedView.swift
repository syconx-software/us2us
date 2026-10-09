import SwiftUI

/// 6.3 · Doğrulandı — the line with the verified seal on its midpoint,
/// “Hat doğrulandı.” (Doto 40, balanced), the paragraph, the
/// date · time · method pill, and “Sohbete dön” (C1). Close and “Sohbete dön”
/// go back to the chat, whose header now carries the seal.
///
/// Entrance (inventory): the seal scales 0.6 → 1 with its glow over 0.5 s
/// (ease-out); title, paragraph and pill fade in over 0.36 s, staggered
/// 0.08 s; the packets keep flowing. Under Reduce Motion: fades only.
struct VerifiedView: View {
    let model: VerificationModel
    let onDone: () -> Void

    @Environment(\.locale) private var locale
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var textShown = false

    var body: some View {
        U2Screen {
            ZStack {
                DotGridBackdrop(mask: .verified, color: Color.u2.dotGridSoft)
                FitOrScroll { content }
            }
        }
        .task {
            // After the insertion's own (animation-less) transaction, so the
            // staggered entrance is never folded into it.
            await Task.yield()
            textShown = true
        }
    }

    private var content: some View {
        VStack(spacing: 0) {
            HStack {
                U2GlassIconButton(.x, label: Text("a11y.close"), action: onDone)
                    .accessibilityIdentifier("verified.close")
                Spacer(minLength: 0)
                HeaderSpacer()
            }
            .padding(.horizontal, U2Spacing.screenGutterS)
            .frame(height: U2Size.iconButton)
            .u2Top(U2Spacing.headerTopButtons)

            VStack(spacing: 36) {
                if let peer = model.peer {
                    ConnectionLine(
                        ownUsername: model.ownUsername, ownLabel: Text("safety.you", table: .verification),
                        peer: peer, showsSeal: true, animatesSeal: model.verifiedEntrance,
                        entranceDelay: model.verifiedEntranceDelay
                    )
                }
                VStack(spacing: 14) {
                    U2DisplayText(
                        StringTable.verification.resource("verified.title"),
                        style: .displayTitleLBalanced, alignment: .center
                    )
                    .opacity(textShown ? 1 : 0)
                    .animation(stagger(0), value: textShown)
                    Text("verified.body \(model.peerName)", table: .verification)
                        .u2Font(.body)
                        .foregroundStyle(Color.u2.textSecondary)
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                        .opacity(textShown ? 1 : 0)
                        .animation(stagger(1), value: textShown)
                }
                .padding(.horizontal, 30)
                if let info = model.info, let line = VerificationText.verifiedLine(info, locale: locale) {
                    InfoPill(text: Text(verbatim: line))
                        .opacity(textShown ? 1 : 0)
                        .animation(stagger(2), value: textShown)
                        .accessibilityIdentifier("verified.pill")
                }
            }
            .frame(maxHeight: .infinity)

            Button(action: onDone) {
                Text("verified.back", table: .verification)
            }
            .buttonStyle(.u2Primary)
            .padding(.horizontal, U2Spacing.screenGutter)
            .u2Bottom(U2Spacing.ctaBottom)
            .accessibilityIdentifier("verified.back")
        }
    }

    private func stagger(_ step: Int) -> Animation? {
        guard model.verifiedEntrance else { return nil }
        return U2Motion.soft(U2Motion.Duration.state).delay(0.12 + model.verifiedEntranceDelay + 0.08 * Double(step))
    }
}

#Preview("6.3 Doğrulandı") {
    let app = AppModel.preview()
    let model = VerificationModel(chatID: "deniz", messenger: app.messenger)
    return VerifiedView(model: model) {}
        .task {
            await model.messenger.markVerified("deniz", method: .faceToFace)
            await model.load()
        }
        .environment(app)
        .environment(\.locale, app.locale)
        .environment(\.u2Clock, app.clock)
}
