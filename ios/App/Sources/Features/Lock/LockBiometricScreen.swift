import SwiftUI

/// 3.1 · Face ID — the lock while the biometric check runs.
///
/// Dot grid (`text·14%`, 70 % × 38 % at 50 % / 46 %); brand block at y 112
/// (line mark 52 × 14, gap 12, wordmark Doto 24); the 116 pt disc
/// (`accent900`, 1 pt `accent800` ring, glow `0 0 70px -12px accent·60%`)
/// with the 56 pt Face ID glyph (stroke 1.3, `accent300`) and the two soft
/// scanning rings (2.8 s ease-out, 1.4 s apart — none under Reduce Motion);
/// “Face ID ile açılıyor” + “Tanımazsa gizli kodun istenir” 28 pt below;
/// C2 “Kodla aç” with `ph:password` at the bottom (pad 0 24 44).
struct LockBiometricScreen: View {
    let flow: LockFlowModel
    @Environment(\.locale) private var locale

    var body: some View {
        U2Screen(background: .clear) {
            VStack(spacing: 0) {
                VStack(spacing: 12) {
                    LineMark(metrics: .lock)
                    U2Wordmark(style: .brandWordmarkL)
                }
                .u2Top(112)
                .accessibilityElement(children: .combine)

                VStack(spacing: 28) {
                    disc
                    VStack(spacing: 6) {
                        title
                            .u2Font(.lockStatus)
                            .foregroundStyle(Color.u2.text)
                            .accessibilityAddTraits(.isHeader)
                        StringTable.lock.text("lock.faceID.subtitle", locale: locale)
                            .u2Font(.preview)
                            .foregroundStyle(Color.u2.textSecondary)
                    }
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, U2Spacing.screenGutterL)
                    .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .padding(.bottom, 30)

                Button(action: flow.useCode) {
                    U2ButtonLabel(StringTable.lock.text("lock.faceID.useCode", locale: locale), glyph: .password)
                }
                .buttonStyle(.u2Secondary)
                .padding(.horizontal, U2Spacing.screenGutterL)
                .u2Bottom(U2Spacing.ctaBottom)
                .accessibilityIdentifier("lock.useCode")
            }
            .background {
                DotGridBackdrop(mask: .lock, color: Color.u2.dotGridSoft)
                    .ignoresSafeArea()
            }
        }
        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
    }

    private var title: Text {
        flow.biometry == .touchID ? StringTable.lock.text("lock.touchID.title", locale: locale) : StringTable.lock.text("lock.faceID.title", locale: locale)
    }

    /// The disc is also a button: tapping it retries the scan (VoiceOver:
    /// “Face ID ile aç”).
    private var disc: some View {
        Button {
            flow.startScan(locale: locale)
        } label: {
            ZStack {
                Circle().fill(Color.u2.accent900)
                Circle().strokeBorder(Color.u2.accent800, lineWidth: 1)
                PulseRings(diameter: U2Size.faceIdDisc)
                glyph
            }
            .frame(width: U2Size.faceIdDisc, height: U2Size.faceIdDisc)
            .u2BoxShadow(.glowFaceID, in: Circle())
            .contentShape(Circle())
        }
        .buttonStyle(LockDiscButtonStyle())
        .accessibilityLabel(StringTable.lock.text(flow.biometry == .touchID ? "lock.touchID.retry" : "lock.faceID.retry", locale: locale))
        .accessibilityIdentifier("lock.biometricDisc")
    }

    @ViewBuilder private var glyph: some View {
        if flow.biometry == .touchID {
            Image(systemName: "touchid")
                .font(.system(size: 50, weight: .ultraLight))
                .foregroundStyle(Color.u2.accent300)
                .frame(width: 56, height: 56)
                .accessibilityHidden(true)
        } else {
            FaceIDGlyph(size: 56, lineWidth: 1.3)
                .foregroundStyle(Color.u2.accent300)
        }
    }
}

/// The disc presses like a key (scale 0.97), without dimming the glyph.
private struct LockDiscButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .modifier(U2PressEffect(isPressed: configuration.isPressed))
    }
}

#Preview("3.1 · Face ID") {
    let app = AppModel.preview(start: .locked)
    LockBiometricScreen(
        flow: LockFlowModel(
            messenger: app.messenger, biometry: .faceID, isFrozenForHarness: true, onUnlock: {}, onErase: {})
    )
    .background(Color.u2.bg)
    .environment(\.u2Clock, app.clock)
}
