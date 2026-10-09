import SwiftUI

// MARK: - C9 · Page dots

/// C9 — onboarding page dots: active 22 × 6 `accent400`, inactive 6 × 6
/// `text·26%`, r3, gap 6. The active pill morphs between pages (0.36 s).
struct PageDots: View {
    let count: Int
    let current: Int

    var body: some View {
        HStack(spacing: U2Size.pageDotGap) {
            ForEach(0..<count, id: \.self) { index in
                let isActive = index == current
                RoundedRectangle(cornerRadius: U2Radius.pageDot, style: .continuous)
                    .fill(isActive ? Color.u2.accent400 : Color.u2.pageDotInactive)
                    .frame(width: isActive ? U2Size.pageDotActive.width : U2Size.pageDotInactive.width, height: 6)
            }
        }
        .u2Animation(U2Motion.state, value: current)
        .accessibilityElement()
        .accessibilityLabel(Text("a11y.pager \(current + 1) \(count)"))
    }
}

// MARK: - C10 · Code dots

/// C10 — the six secret-code dots: 15 × 15, gap 20 (190 wide).
/// Empty = inner 1.5 pt `text·40%` ring; filled = `accent400` + glow
/// `0 0 12px accent·70%` (scales in 0.6 → 1 over 0.24 s); error = all six
/// solid `danger` with a soft, damped shake (none under Reduce Motion).
struct CodeDots: View {
    enum Appearance: Equatable, Sendable {
        case normal
        case error
        /// Lock-out wait: dots dimmed with the keypad.
        case dimmed
    }

    var length: Int = 6
    let filled: Int
    var appearance: Appearance = .normal
    /// Increment to play the error shake.
    var shakeTrigger: Int = 0

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let shakes = !reduceMotion
        HStack(spacing: U2Size.codeDotGap) {
            ForEach(0..<length, id: \.self) { index in
                CodeDot(state: dotState(index))
            }
        }
        .opacity(appearance == .dimmed ? U2Opacity.disabled : 1)
        .keyframeAnimator(initialValue: CGFloat(0), trigger: shakeTrigger) { content, offset in
            content.offset(x: shakes ? offset : 0)
        } keyframes: { _ in
            // ±6 pt, three damped cycles, 0.45 s, on the soft curve.
            KeyframeTrack {
                CubicKeyframe(6, duration: 0.07)
                CubicKeyframe(-5, duration: 0.09)
                CubicKeyframe(3.5, duration: 0.09)
                CubicKeyframe(-2, duration: 0.08)
                CubicKeyframe(0.8, duration: 0.06)
                CubicKeyframe(0, duration: 0.06)
            }
        }
        .u2Animation(U2Motion.micro, value: filled)
        .u2Animation(U2Motion.micro, value: appearance)
        .accessibilityElement()
        .accessibilityLabel(Text("a11y.code.entered \(filled) \(length)"))
    }

    private func dotState(_ index: Int) -> CodeDot.State {
        if appearance == .error { return .error }
        return index < filled ? .filled : .empty
    }
}

private struct CodeDot: View {
    enum State: Equatable {
        case empty
        case filled
        case error
    }

    let state: State

    var body: some View {
        ZStack {
            Circle().strokeBorder(Color.u2.textTertiary, lineWidth: 1.5)
                .opacity(state == .empty ? 1 : 0)
            Circle().fill(Color.u2.accent400)
                .u2BoxShadow(.glowCodeDot, in: Circle())
                .scaleEffect(state == .filled ? 1 : 0.6)
                .opacity(state == .filled ? 1 : 0)
            Circle().fill(Color.u2.danger)
                .opacity(state == .error ? 1 : 0)
        }
        .frame(width: U2Size.codeDot, height: U2Size.codeDot)
    }
}

// MARK: - C11 · Numeric keypad

/// C11 — 3 × 78 pt keys, column gap 26, row gap 16 (286 × 360). Keys are
/// `text·9%` circles with an Inter 400 32 digit; the bottom row is
/// [empty | 0 | backspace] in setup (2.2) and [Face ID | 0 | backspace] on
/// the lock screen (3.2). Press: `text·14%` + scale 0.97; light haptic per digit.
struct NumericKeypad: View {
    enum BottomLeading {
        case empty
        case faceID(action: () -> Void)
    }

    var bottomLeading: BottomLeading = .empty
    let onDigit: (Int) -> Void
    let onDelete: () -> Void

    @Environment(\.isEnabled) private var isEnabled

    private let rows: [[Int]] = [[1, 2, 3], [4, 5, 6], [7, 8, 9]]

    var body: some View {
        Grid(horizontalSpacing: U2Size.keypadColumnGap, verticalSpacing: U2Size.keypadRowGap) {
            ForEach(rows, id: \.self) { row in
                GridRow {
                    ForEach(row, id: \.self) { digit in
                        digitKey(digit)
                    }
                }
            }
            GridRow {
                switch bottomLeading {
                case .empty:
                    Color.clear.frame(width: U2Size.keypadKey, height: U2Size.keypadKey).accessibilityHidden(true)
                case .faceID(let action):
                    Button(action: action) {
                        FaceIDGlyph(size: 30, lineWidth: 1.6)
                            .foregroundStyle(Color.u2.text)
                            .frame(width: U2Size.keypadKey, height: U2Size.keypadKey)
                            .contentShape(Circle())
                    }
                    .buttonStyle(KeypadIconButtonStyle())
                    .accessibilityLabel(Text("a11y.keypad.faceID"))
                }
                digitKey(0)
                Button(action: onDelete) {
                    PhosphorIcon(.backspace, size: 28)
                        .foregroundStyle(Color.u2.text)
                        .frame(width: U2Size.keypadKey, height: U2Size.keypadKey)
                        .contentShape(Circle())
                }
                .buttonStyle(KeypadIconButtonStyle())
                .accessibilityLabel(Text("a11y.keypad.delete"))
            }
        }
        .opacity(isEnabled ? 1 : U2Opacity.disabled)
        .u2Animation(U2Motion.state, value: isEnabled)
    }

    private func digitKey(_ digit: Int) -> some View {
        Button {
            U2Haptics.keypadTap()
            onDigit(digit)
        } label: {
            Text(verbatim: "\(digit)")
                .u2Font(.keypadDigit)
                .foregroundStyle(Color.u2.text)
        }
        .buttonStyle(KeypadDigitButtonStyle())
        .accessibilityLabel(Text(verbatim: "\(digit)"))
    }
}

private struct KeypadDigitButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .frame(width: U2Size.keypadKey, height: U2Size.keypadKey)
            .background(Circle().fill(Color.u2.text.opacity(configuration.isPressed ? 0.14 : 0.09)))
            .contentShape(Circle())
            .modifier(U2PressEffect(isPressed: configuration.isPressed))
    }
}

private struct KeypadIconButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .opacity(configuration.isPressed ? 0.55 : 1)
            .modifier(U2PressEffect(isPressed: configuration.isPressed))
    }
}

#Preview("Code entry") {
    struct Demo: View {
        @State private var code = "4471"
        @State private var error = false
        @State private var shakes = 0
        var body: some View {
            VStack(spacing: 40) {
                CodeDots(filled: code.count, appearance: error ? .error : .normal, shakeTrigger: shakes)
                PageDots(count: 3, current: code.count % 3)
                NumericKeypad(bottomLeading: .faceID {}) { digit in
                    guard code.count < 6 else { return }
                    code.append(String(digit))
                    if code.count == 6 { error = true; shakes += 1 }
                } onDelete: {
                    error = false
                    if !code.isEmpty { code.removeLast() }
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color.u2.bg)
        }
    }
    return Demo()
}
