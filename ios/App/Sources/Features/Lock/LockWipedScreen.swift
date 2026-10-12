import SwiftUI
import UIKit

/// After the optional wipe (PROTOCOL §5.2: the 10th wrong code in a row
/// crypto-shreds everything) — shown at once, instead of “Kod yanlış” until
/// the next launch. The design has no screen for it (proposed): 3.2's top
/// block (the 52 pt badge at y 106, here in the danger tint, and the Doto
/// title), the sentence in `calloutL` · `text·68%`, and 3.3's two option
/// cards on a `surface` panel (they are drawn on `bg`, inside a sheet):
/// restore a backup, or start over. Nothing else of the lock stays live.
struct LockWipedScreen: View {
    let flow: LockFlowModel
    @Environment(\.locale) private var locale

    var body: some View {
        U2Screen(background: .clear) {
            OnboardingFitOrScroll {
                VStack(spacing: 0) {
                    VStack(spacing: 16) {
                        PhosphorIcon(.trash, size: 24)
                            .foregroundStyle(Color.u2.danger)
                            .frame(width: U2Size.lockIcon, height: U2Size.lockIcon)
                            .background(Circle().fill(Color.u2.dangerTint))
                            .accessibilityHidden(true)
                        U2DisplayText(
                            verbatim: StringTable.lock.string("lock.wiped.title", locale: locale),
                            style: .displayTitleXS.balanced, alignment: .center, isHeader: true)
                        StringTable.lock.text("lock.wiped.body", locale: locale)
                            .u2Font(.calloutL)
                            .foregroundStyle(Color.u2.textSecondary)
                            .multilineTextAlignment(.center)
                            .fixedSize(horizontal: false, vertical: true)
                            .frame(maxWidth: 320)
                            .accessibilityIdentifier("lock.wiped.body")
                    }
                    .padding(.horizontal, U2Spacing.screenGutterL)
                    .u2Top(106)

                    Spacer(minLength: 28)

                    VStack(spacing: 10) {
                        OptionCard(
                            glyph: .fileLock, title: StringTable.lock.text("lock.forgot.restore.title", locale: locale),
                            description: StringTable.lock.text("lock.wiped.restore.body", locale: locale),
                            action: flow.restoreAfterWipe
                        )
                        .accessibilityIdentifier("lock.wiped.restore")
                        OptionCard(
                            glyph: .plus, title: StringTable.lock.text("lock.wiped.startOver.title", locale: locale),
                            description: StringTable.lock.text("lock.wiped.startOver.body", locale: locale),
                            action: flow.startOverAfterWipe
                        )
                        .accessibilityIdentifier("lock.wiped.startOver")
                    }
                    .padding(10)
                    .background(
                        RoundedRectangle(cornerRadius: U2Radius.cardL, style: .continuous).fill(Color.u2.surface))
                    .padding(.horizontal, U2Spacing.screenGutterS)
                    .u2Bottom(U2Spacing.ctaBottomOnboarding)
                }
            }
        }
        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
        .onAppear(perform: announce)
    }

    /// VoiceOver hears what happened as soon as the screen is there.
    private func announce() {
        guard UIAccessibility.isVoiceOverRunning else { return }
        let text = StringTable.lock.string("lock.wiped.title", locale: locale) + " "
            + StringTable.lock.string("lock.wiped.body", locale: locale)
        UIAccessibility.post(notification: .announcement, argument: text)
    }
}

#Preview("3.2 › Veriler silindi") {
    let app = AppModel.preview(start: .locked)
    LockWipedScreen(
        flow: LockFlowModel(messenger: app.messenger, biometry: .none, startsWiped: true, onUnlock: {}, onErase: {})
    )
    .background(Color.u2.bg)
}
