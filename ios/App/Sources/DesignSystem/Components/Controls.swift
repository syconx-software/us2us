import SwiftUI

// MARK: - C14 · Toggle

/// C14 — 51 × 31, r16, padding 2. On: track `accent`, `#f5f4ff` knob (27)
/// at the right with `0 2px 6px #000@25%`. Off (not drawn in the design,
/// proposed): track `divider`, knob at the left. The knob glides with the
/// critically damped spring — no overshoot.
struct U2ToggleStyle: ToggleStyle {
    func makeBody(configuration: Configuration) -> some View {
        U2ToggleBody(configuration: configuration)
    }
}

private struct U2ToggleBody: View {
    let configuration: ToggleStyleConfiguration
    @Environment(\.isEnabled) private var isEnabled
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        HStack(spacing: 12) {
            configuration.label
            Spacer(minLength: 0)
            ZStack(alignment: configuration.isOn ? .trailing : .leading) {
                Capsule()
                    .fill(configuration.isOn ? Color.u2.accent : Color.u2.divider)
                    .frame(width: U2Size.toggleWidth, height: U2Size.toggleHeight)
                Circle()
                    .fill(Color.u2.toggleKnob)
                    .frame(width: U2Size.toggleKnob, height: U2Size.toggleKnob)
                    .u2KnobShadow()
                    .padding(U2Size.togglePadding)
            }
            // springSoft (C14, 7.1, 7.2): response 0.55, critically damped.
            .animation(reduceMotion ? U2Motion.reducedCrossFade : U2Motion.spring, value: configuration.isOn)
            .opacity(isEnabled ? 1 : U2Opacity.disabled)
        }
        .contentShape(Rectangle())
        .onTapGesture {
            guard isEnabled else { return }
            U2Haptics.selection()
            configuration.isOn.toggle()
        }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isToggle)
        .accessibilityValue(configuration.isOn ? Text("a11y.on") : Text("a11y.off"))
        .accessibilityAction {
            configuration.isOn.toggle()
        }
    }
}

extension ToggleStyle where Self == U2ToggleStyle {
    /// C14 design switch.
    static var u2: U2ToggleStyle { U2ToggleStyle() }
}

// MARK: - Checkbox (2.3)

/// 24 × 24, r7. Checked: fill `accent` with a 16 pt `ph:check` in `bg`;
/// unchecked (proposed): inner 1.5 pt `text·40%` ring. Fill and tick
/// cross-fade/scale over 0.24 s.
struct U2CheckboxStyle: ToggleStyle {
    func makeBody(configuration: Configuration) -> some View {
        Button {
            U2Haptics.selection()
            configuration.isOn.toggle()
        } label: {
            HStack(alignment: .top, spacing: 12) {
                CheckboxBox(isOn: configuration.isOn)
                configuration.label
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(.isToggle)
        .accessibilityValue(configuration.isOn ? Text("a11y.on") : Text("a11y.off"))
    }
}

private struct CheckboxBox: View {
    let isOn: Bool

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: U2Radius.checkbox, style: .continuous)
        ZStack {
            shape.strokeBorder(Color.u2.textTertiary, lineWidth: 1.5)
                .opacity(isOn ? 0 : 1)
            shape.fill(Color.u2.accent)
                .opacity(isOn ? 1 : 0)
            PhosphorIcon(.check, size: 16)
                .foregroundStyle(Color.u2.bg)
                .scaleEffect(isOn ? 1 : 0.6)
                .opacity(isOn ? 1 : 0)
        }
        .frame(width: U2Size.checkbox, height: U2Size.checkbox)
        .u2Animation(U2Motion.micro, value: isOn)
    }
}

extension ToggleStyle where Self == U2CheckboxStyle {
    /// The 2.3 acknowledgement checkbox.
    static var u2Checkbox: U2CheckboxStyle { U2CheckboxStyle() }
}

// MARK: - C22 · Segmented chips (5.5)

/// C22 — a row of equal chips, gap 6, 40 high, r12, `chip` (Inter 400 14).
/// Unselected: inner 1 pt `divider`, `text`; selected: fill `accent900`,
/// inner 1.5 pt `accent`, `accent300`. Selection cross-fades over 0.24 s.
/// Also the contact screen's tabs (proposed).
struct SegmentedChips<Value: Hashable>: View {
    let options: [Value]
    @Binding var selection: Value
    /// Each chip's accessibility identifier is `<prefix>.<option>` (UI tests).
    var identifierPrefix: String? = nil
    let title: (Value) -> Text

    @Environment(\.isEnabled) private var isEnabled

    var body: some View {
        HStack(spacing: 6) {
            ForEach(options, id: \.self) { option in
                let isSelected = option == selection
                let shape = RoundedRectangle(cornerRadius: U2Radius.chip, style: .continuous)
                Button {
                    U2Haptics.selection()
                    selection = option
                } label: {
                    title(option)
                        .u2Font(.chip, matchLineBox: false)
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                        .foregroundStyle(isSelected ? Color.u2.accent300 : Color.u2.text)
                        .frame(maxWidth: .infinity, minHeight: 40, maxHeight: 40)
                        .background(shape.fill(Color.u2.accent900.opacity(isSelected ? 1 : 0)))
                        .overlay(
                            shape.strokeBorder(
                                isSelected ? Color.u2.accent : Color.u2.divider, lineWidth: isSelected ? 1.5 : 1)
                        )
                        .contentShape(shape)
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(isSelected ? .isSelected : [])
                .modifier(OptionalAccessibilityIdentifier(identifier: identifierPrefix.map { "\($0).\(option)" }))
            }
        }
        .opacity(isEnabled ? 1 : U2Opacity.disabled)
        .u2Animation(U2Motion.micro, value: selection)
    }
}

/// An identifier only when there is one: chips without a prefix keep none
/// (never an empty one).
private struct OptionalAccessibilityIdentifier: ViewModifier {
    let identifier: String?

    func body(content: Content) -> some View {
        if let identifier {
            content.accessibilityIdentifier(identifier)
        } else {
            content
        }
    }
}

// MARK: - Count badge

/// The unread / requests count badge: 22 high, min width 22, pad 0 7, r11,
/// fill `accent`, number Inter 600 12.5 in `bg`. A new count rolls in
/// (`numericText`, 0.36 s; a cross-fade under Reduce Motion) and the pill
/// eases to its new width.
struct CountBadge: View {
    let count: Int
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Text(verbatim: "\(count)")
            .u2Font(.badge, matchLineBox: false)
            .foregroundStyle(Color.u2.bg)
            .contentTransition(reduceMotion ? .opacity : .numericText(value: Double(count)))
            .padding(.horizontal, 7)
            .frame(minWidth: 22, minHeight: 22, maxHeight: 22)
            .background(Capsule().fill(Color.u2.accent))
            .u2Animation(U2Motion.state, value: count)
            .accessibilityLabel(Text("a11y.unread \(count)"))
    }
}

// MARK: - Pulse rings (3.1 Face ID, 5.2 live location)

/// `pulseRing`: two 116 pt circles with a 1.5 pt `accent400` stroke, each
/// scaling 0.15 → 1 while fading 0.9 → 0 over 2.8 s ease-out, forever, the
/// second 1.4 s later. Hidden under Reduce Motion (“halkalar durur”).
struct PulseRings: View {
    var diameter: CGFloat = 116
    var color: Color = Color.u2.accent400
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.u2Clock) private var clock
    /// Paused on screens nobody can see (under a pushed screen or the lock),
    /// and while the app is not active.
    @Environment(\.u2IsLayerVisible) private var isLayerVisible
    @Environment(\.scenePhase) private var scenePhase

    private static let period: Double = 2.8

    var body: some View {
        if reduceMotion {
            Color.clear.frame(width: diameter, height: diameter)
        } else if clock.isFrozen {
            // Frozen like the references: rings paused 2 s into the loop.
            rings(at: 2.0)
        } else {
            TimelineView(.animation(minimumInterval: nil, paused: scenePhase != .active || !isLayerVisible)) { context in
                rings(at: context.date.timeIntervalSinceReferenceDate)
            }
        }
    }

    private func rings(at time: TimeInterval) -> some View {
        ZStack {
            ring(phase: (time.truncatingRemainder(dividingBy: Self.period)) / Self.period)
            ring(phase: ((time + Self.period / 2).truncatingRemainder(dividingBy: Self.period)) / Self.period)
        }
        .frame(width: diameter, height: diameter)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    private func ring(phase: Double) -> some View {
        // ease-out = cubic-bezier(0, 0, 0.58, 1)
        let t = UnitCurve.bezier(startControlPoint: UnitPoint(x: 0, y: 0), endControlPoint: UnitPoint(x: 0.58, y: 1))
            .value(at: phase)
        // Inner stroke, like the CSS border inside the 116 pt box; it scales with the ring.
        return Circle()
            .strokeBorder(color, lineWidth: 1.5)
            .scaleEffect(0.15 + 0.85 * t)
            .opacity(0.9 * (1 - t))
    }
}

#Preview("Controls") {
    struct Demo: View {
        @State private var on = true
        @State private var ack = false
        @State private var period = "24 sa"
        var body: some View {
            VStack(spacing: 24) {
                Toggle(isOn: $on) { Text(verbatim: "Ghost mod").u2Font(.rowTitleEmphasis) }
                    .toggleStyle(.u2)
                Toggle(isOn: $ack) {
                    Text(verbatim: "Anladım: kodu unutursam verilerim kalıcı olarak silinir.")
                        .u2Font(.calloutL)
                }
                .toggleStyle(.u2Checkbox)
                SegmentedChips(options: ["1 sa", "6 sa", "24 sa", "3 gün", "7 gün"], selection: $period) {
                    Text(verbatim: $0)
                }
                HStack { CountBadge(count: 2); CountBadge(count: 12) }
                ZStack {
                    Circle().fill(Color.u2.accent900).frame(width: 116, height: 116)
                    PulseRings()
                }
            }
            .foregroundStyle(Color.u2.text)
            .padding(24)
            .background(Color.u2.bg)
        }
    }
    return Demo()
}
