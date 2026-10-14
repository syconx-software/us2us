import SwiftUI
import UIKit

/// 3.2 · Kod — the code keypad on the lock.
///
/// Top block at y 106 (lock badge 52, gap 16, “Gizli kodunu gir” Doto 30);
/// C10 dots 36 below (all `danger` + a soft shake on a wrong code); the
/// sentence (`calloutL`, `danger`, max 300 wide, 2 lines); C11 keypad with
/// the Face ID key bottom-left at y 414; C3 “Kodu unuttum” (pad 0 24 40).
/// While a wait runs the dots dim and the keypad fades to 45 % (0.36 s) with a
/// live `mm:ss`.
struct LockCodeScreen: View {
    let flow: LockFlowModel
    var showsDemoHint = true
    @Environment(\.locale) private var locale

    private var code: LockCodeModel { flow.code }

    var body: some View {
        U2Screen(background: .clear) {
            VStack(spacing: 0) {
                VStack(spacing: 16) {
                    PhosphorIcon(.lockSimple, size: 24)
                        .foregroundStyle(Color.u2.accent300)
                        .frame(width: U2Size.lockIcon, height: U2Size.lockIcon)
                        .background(Circle().fill(Color.u2.accent900))
                        .overlay(Circle().strokeBorder(Color.u2.accent800, lineWidth: 1))
                        .accessibilityHidden(true)
                    // One line in Turkish (as drawn); wraps inside the 24 pt
                    // gutters when a language needs it (EN “Enter your secret code”).
                    U2DisplayText(
                        verbatim: StringTable.lock.string("lock.code.title", locale: locale), style: .displayTitleXS.balanced,
                        alignment: .center, isHeader: true
                    )
                    .padding(.horizontal, U2Spacing.screenGutterL)
                }
                .u2Top(106)
                VStack(spacing: 16) {
                    CodeDots(filled: code.entered.count, appearance: code.appearance, shakeTrigger: code.shakeTrigger)
                    messageView
                        .frame(maxWidth: 300, minHeight: 42, alignment: .top)
                        .u2Animation(U2Motion.state, value: code.message)
                }
                .padding(.top, 36)
                Spacer(minLength: 12)
                // Behind the 3.3 sheets only the top block and the dots stay
                // (dimmed by the backdrop); the keypad and “Kodu unuttum” fade
                // out with it, so the shorter “Her şey silinsin mi?” sheet
                // shows nothing else above it.
                VStack(spacing: 0) {
                    NumericKeypad(bottomLeading: keypadLeading, onDigit: code.type, onDelete: code.deleteLast)
                        .disabled(code.isThrottled)
                        .padding(.bottom, 14)
                    Button(action: flow.openForgot) { U2ButtonLabel(StringTable.lock.text("lock.code.forgot", locale: locale)) }
                        .buttonStyle(U2TextButtonStyle(tone: .accent, height: U2Size.textButtonS))
                        .padding(.horizontal, U2Spacing.screenGutterL)
                        .u2Bottom(U2Spacing.ctaBottomOnboarding)
                        .accessibilityIdentifier("lock.forgot")
                }
                .opacity(flow.showsForgot ? 0 : 1)
                .u2Animation(U2Motion.state, value: flow.showsForgot)
            }
            #if DEBUG
                // The fixtures' demo code, outside the layout (debug builds only;
                // hidden in design-comparison runs with a frozen clock).
                .overlay(alignment: .bottom) {
                    if showsDemoHint {
                        Text(verbatim: "demo \(DesignFixtures.demoSecretCode)")
                            .u2Font(.monoMicro)
                            .foregroundStyle(Color.u2.textTertiary)
                            .padding(.bottom, 14)
                            .accessibilityIdentifier("lock.demoHint")
                    }
                }
            #endif
        }
        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
        .onChange(of: code.message) { _, message in
            announce(message)
        }
    }

    private var keypadLeading: NumericKeypad.BottomLeading {
        guard flow.biometry != .none else { return .empty }
        return .faceID { flow.useBiometrics(locale: locale) }
    }

    @ViewBuilder private var messageView: some View {
        switch code.message {
        case .none:
            Color.clear
        case .wrongPlain:
            sentence(StringTable.lock.text("lock.code.wrong", locale: locale))
        case .wrong(let attemptsLeft, let delay):
            sentence(
                StringTable.lock.text(
                    "lock.code.wrong.warning \(attemptsLeft) \(LockFormat.waitDuration(delay, locale: locale))",
                    locale: locale))
        case .throttled(let until):
            LockCountdown(until: until, frozen: flow.isFrozenForHarness) { remaining in
                sentence(StringTable.lock.text("lock.code.throttled \(U2Format.minutesSeconds(remaining))", locale: locale))
                    .accessibilityAddTraits(.updatesFrequently)
            }
        case .unavailable:
            sentence(StringTable.lock.text("lock.code.unavailable", locale: locale))
        case .unusable:
            sentence(StringTable.lock.text("lock.code.unusable", locale: locale))
        }
    }

    private func sentence(_ text: Text) -> some View {
        text
            .u2Font(.calloutL)
            .foregroundStyle(Color.u2.danger)
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)
            .transition(.u2Fade)
            .accessibilityIdentifier("lock.message")
    }

    /// VoiceOver hears the sentence as soon as it appears.
    private func announce(_ message: LockCodeModel.Message?) {
        guard UIAccessibility.isVoiceOverRunning, let message else { return }
        let text: String
        switch message {
        case .wrongPlain:
            text = StringTable.lock.string("lock.code.wrong", locale: locale)
        case .wrong(let attemptsLeft, let delay):
            text = StringTable.lock.string(
                "lock.code.wrong.warning \(attemptsLeft) \(LockFormat.waitDuration(delay, locale: locale))",
                locale: locale)
        case .throttled(let until):
            text = StringTable.lock.string(
                "lock.code.throttled \(U2Format.minutesSeconds(until.timeIntervalSinceNow))", locale: locale)
        case .unavailable:
            text = StringTable.lock.string("lock.code.unavailable", locale: locale)
        case .unusable:
            text = StringTable.lock.string("lock.code.unusable", locale: locale)
        }
        UIAccessibility.post(notification: .announcement, argument: text)
    }
}

/// The live wait: re-renders every second with the real time (the wait is
/// measured by the vault, not the design clock). Frozen in the harness.
private struct LockCountdown<Content: View>: View {
    let until: Date
    var frozen = false
    @ViewBuilder let content: (TimeInterval) -> Content

    var body: some View {
        if frozen {
            content(60)
        } else {
            TimelineView(.periodic(from: .now, by: 1)) { context in
                content(until.timeIntervalSince(context.date))
            }
        }
    }
}

#Preview("3.2 · Kod") {
    let app = AppModel.preview(start: .locked)
    LockCodeScreen(
        flow: LockFlowModel(messenger: app.messenger, biometry: .faceID, onUnlock: {}, onErase: {}),
        showsDemoHint: false
    )
    .background(Color.u2.bg)
}
