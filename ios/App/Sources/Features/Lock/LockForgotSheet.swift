import SwiftUI

/// 3.3 · Kodu unuttum — the floating sheet over the dimmed lock.
///
/// No recovery path: only the real options. Pad 10 18 28 18 (the grabber is
/// the sheet's), gap 18: “Kod kurtarılamaz.” (Doto 30/30.9, Inter “.”) and the
/// paragraph (`sheetBody`, `text·68%`); two option cards (gap 10); C2
/// “Tekrar dene”.
///
/// “Her şeyi sil, baştan başla” asks a second time in the same sheet (the
/// inventory's proposal): the content cross-fades to “Her şey silinsin mi?”
/// with a danger outline button and “Vazgeç”; the sheet's height follows
/// softly (0.5 s, the design curve).
struct LockForgotSheet: View {
    let flow: LockFlowModel
    @Environment(\.locale) private var locale
    /// Natural heights of the two steps, measured on hidden copies: the
    /// sheet's height animates between them while the contents cross-fade.
    @State private var heights: [LockFlowModel.ForgotStep: CGFloat] = [:]

    var body: some View {
        ZStack(alignment: .top) {
            // Only the current step is live (touches, VoiceOver, UI tests);
            // the other one is in the tree just for its fade-out.
            switch flow.forgotStep {
            case .options:
                options(live: true)
                    .fixedSize(horizontal: false, vertical: true)
                    .transition(.u2Fade)
            case .confirmErase:
                confirmation(live: true)
                    .fixedSize(horizontal: false, vertical: true)
                    .transition(.u2Fade)
            }
        }
        .frame(height: heights[flow.forgotStep], alignment: .top)
        .frame(maxWidth: .infinity, alignment: .top)
        .background(alignment: .top) { measurements }
        .padding(EdgeInsets(top: 18, leading: 18, bottom: 28, trailing: 18))
        .onChange(of: flow.forgotStep) {
            // VoiceOver moves to the new step's title.
            UIAccessibility.post(notification: .layoutChanged, argument: nil)
        }
    }

    /// Both steps laid out at the sheet's width, never drawn, never hit, and
    /// moved far off-screen so no automation hit-test can land on them.
    private var measurements: some View {
        ZStack(alignment: .top) {
            measured(.options) { options(live: false) }
            measured(.confirmErase) { confirmation(live: false) }
        }
        .hidden()
        .allowsHitTesting(false)
        .accessibilityHidden(true)
        .offset(x: 4_000)
    }

    private func measured<Content: View>(_ step: LockFlowModel.ForgotStep, @ViewBuilder content: () -> Content) -> some View {
        content()
            .fixedSize(horizontal: false, vertical: true)
            .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { heights[step] = $0 }
    }

    /// `live: false` — the measuring copy: no identifiers, so UI tests only
    /// ever find the live controls.
    private func options(live: Bool) -> some View {
        VStack(alignment: .leading, spacing: 18) {
            textBlock(title: "lock.forgot.title", body: StringTable.lock.text("lock.forgot.body", locale: locale))
            VStack(spacing: 10) {
                OptionCard(
                    glyph: .fileLock, title: StringTable.lock.text("lock.forgot.restore.title", locale: locale),
                    description: StringTable.lock.text("lock.forgot.restore.body", locale: locale), action: flow.chooseBackup
                )
                .accessibilityIdentifier(live ? "lock.forgot.restore" : "")
                OptionCard(
                    glyph: .trash, tone: .danger, title: StringTable.lock.text("lock.forgot.erase.title", locale: locale),
                    description: StringTable.lock.text("lock.forgot.erase.body", locale: locale), action: flow.askEraseConfirmation
                )
                .accessibilityIdentifier(live ? "lock.forgot.erase" : "")
            }
            Button(action: flow.closeForgot) { U2ButtonLabel(StringTable.lock.text("lock.forgot.retry", locale: locale)) }
                .buttonStyle(.u2Secondary)
                .accessibilityIdentifier(live ? "lock.forgot.retry" : "")
        }
    }

    private func confirmation(live: Bool) -> some View {
        EraseConfirmation(
            isWorking: flow.isErasing, identifiers: live ? ("lock.erase.confirm", "lock.erase.cancel") : nil,
            onConfirm: flow.confirmErase, onCancel: flow.cancelErase)
    }

    /// Text block: pad 6 4 0 4, gap 10. The title's Inter 700 “.” grows its
    /// CSS line box from 30.9 to 31.89 (`U2DisplayText` does that itself).
    private func textBlock(title: String.LocalizationValue, body: Text) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            U2DisplayText(verbatim: StringTable.lock.string(title, locale: locale), style: .displaySheetTitle.balanced, isHeader: true)
            body
                .u2Font(.sheetBody)
                .foregroundStyle(Color.u2.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(EdgeInsets(top: 6, leading: 4, bottom: 0, trailing: 4))
    }
}

#Preview("3.3 · Kodu unuttum") {
    let app = AppModel.preview(start: .locked)
    LockForgotSheet(flow: LockFlowModel(messenger: app.messenger, biometry: .faceID, onUnlock: {}, onErase: {}))
        .background(Color.u2.surface)
}
