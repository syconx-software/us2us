import SwiftUI

/// C17 — Ghost indicators. Calm: `accent900` + `accent300`/`accent200` ink,
/// regular ghost. Urgent (last hour): `danger·14%` + `danger`, **filled**
/// ghost; colours cross-fade over 0.8 s when the hour starts. Off (header
/// chip only): no fill, inner `divider`, “kapalı”.
enum GhostIndicatorState: Equatable, Sendable {
    case off
    case calm
    case urgent

    init(_ ghost: GhostState, now: Date) {
        if !ghost.isEnabled || ghost.deadline == nil {
            self = .off
        } else {
            self = ghost.isUrgent(at: now) ? .urgent : .calm
        }
    }
}

/// The chat-list pill: 22 high, pad 0 8, r11, gap 4 — `ph:ghost` 12 + short
/// remaining time in `monoTag` (“23 sa”, “38 dk”, “6 gün”). Updates every
/// half minute — from the screen's shared `\.u2Ticker` when there is one
/// (4.1: one timer for every row), else from its own timeline. A new value
/// rolls in (`numericText`).
struct GhostPill: View {
    let ghost: GhostState
    @Environment(\.locale) private var locale
    @Environment(\.u2Ticker) private var ticker
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        if let ticker {
            pill(at: ticker.now)
        } else {
            U2TimeView(interval: 30) { now in pill(at: now) }
        }
    }

    @ViewBuilder private func pill(at now: Date) -> some View {
        if let remaining = ghost.remaining(at: now) {
            let urgent = U2Format.ghostIsUrgent(remaining)
            let label = U2Format.ghostShort(remaining, locale: locale)
            HStack(spacing: 4) {
                PhosphorIcon(.ghost, weight: urgent ? .fill : .regular, size: 12)
                // “23 sa” → “22 sa” rolls down instead of swapping.
                Text(verbatim: label)
                    .u2Font(.monoTag, matchLineBox: false)
                    .lineLimit(1)
                    .fixedSize()
                    .contentTransition(reduceMotion ? .opacity : .numericText(countsDown: true))
                    .u2Animation(U2Motion.state, value: label)
            }
            .foregroundStyle(urgent ? Color.u2.danger : Color.u2.accent300)
            .padding(.horizontal, 8)
            .frame(height: 22)
            .background(Capsule().fill(urgent ? Color.u2.dangerTint : Color.u2.accent900))
            .u2Animation(U2Motion.ambient, value: urgent)
            .accessibilityElement()
            .accessibilityLabel(Text("a11y.ghost.remaining \(label)"))
        }
    }
}

/// The chat-header chip: 34 high, pad 0 12 0 10, r17, gap 6 — `ph:ghost` 16 +
/// `HH:MM:SS` ticking every second in `monoCountdownChip` (tabular). Tapping
/// opens the Ghost sheet (5.5).
struct GhostChip: View {
    let ghost: GhostState
    let action: () -> Void
    @Environment(\.locale) private var locale

    var body: some View {
        U2TimeView(interval: 1) { now in
            let state = GhostIndicatorState(ghost, now: now)
            let remaining = ghost.remaining(at: now) ?? 0
            Button(action: action) {
                HStack(spacing: 6) {
                    PhosphorIcon(.ghost, weight: state == .urgent ? .fill : .regular, size: 16)
                    Group {
                        if state == .off {
                            Text("ghost.chip.off")
                        } else {
                            Text(verbatim: U2Format.ghostLong(remaining))
                        }
                    }
                    .u2Font(.monoCountdownChip, matchLineBox: false)
                    .lineLimit(1)
                    .fixedSize()
                    .contentTransition(.numericText(countsDown: true))
                }
                .foregroundStyle(ink(state))
                .padding(.leading, 10)
                .padding(.trailing, 12)
                .frame(height: 34)
                .background(Capsule().fill(fill(state)))
                .overlay(Capsule().strokeBorder(ring(state), lineWidth: 1))
                .contentShape(Capsule())
            }
            .buttonStyle(CardButtonStyle())
            .u2Animation(U2Motion.ambient, value: state)
            .accessibilityLabel(
                state == .off
                    ? Text("a11y.ghost.off")
                    : Text("a11y.ghost.remaining \(U2Format.ghostShort(remaining, locale: locale))"))
            .accessibilityHint(Text("a11y.ghost.hint"))
        }
    }

    private func ink(_ state: GhostIndicatorState) -> Color {
        switch state {
        case .off: Color.u2.textSecondary
        case .calm: Color.u2.accent200
        case .urgent: Color.u2.danger
        }
    }

    private func fill(_ state: GhostIndicatorState) -> Color {
        switch state {
        case .off: Color.clear
        case .calm: Color.u2.accent900
        case .urgent: Color.u2.dangerTint
        }
    }

    private func ring(_ state: GhostIndicatorState) -> Color {
        switch state {
        case .off: Color.u2.divider
        case .calm: Color.u2.accent800
        case .urgent: Color.u2.dangerStroke
        }
    }
}

/// A big live countdown in Doto (1.3 `displayCountdownL` 64, 5.5
/// `displayCountdownM` 56), `accent200`, tabular by construction.
struct GhostCountdown: View {
    let deadline: Date
    var style: U2TextStyle = .displayCountdownM
    var color: Color = Color.u2.accent200

    var body: some View {
        U2TimeView(interval: 1) { now in
            Text(verbatim: U2Format.ghostLong(deadline.timeIntervalSince(now)))
                .u2Font(style)
                .foregroundStyle(color)
                .contentTransition(.numericText(countsDown: true))
                .accessibilityLabel(Text(verbatim: U2Format.ghostLong(deadline.timeIntervalSince(now))))
        }
    }
}

/// 5.1 urgent banner (last hour): `ph:ghost` fill 18 `danger` + “Sohbet
/// {43 dk} içinde iki taraftan silinecek. Bir mesaj sayacı sıfırlar.” with the
/// duration in Mono `danger`.
struct GhostUrgentBanner: View {
    let deadline: Date
    @Environment(\.locale) private var locale

    var body: some View {
        U2TimeView(interval: 30) { now in
            let remaining = U2Format.ghostShort(deadline.timeIntervalSince(now), locale: locale)
            FloatingBanner(.urgent) {
                HStack(alignment: .center, spacing: 10) {
                    PhosphorIcon(.ghost, weight: .fill, size: 18)
                        .foregroundStyle(Color.u2.danger)
                    bannerText(remaining)
                        .u2FontMixedRuns(.bullet)
                        .foregroundStyle(Color.u2.text)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }

    /// The sentence with the duration set in Mono `danger` (attributed so
    /// translators keep the whole sentence).
    private func bannerText(_ remaining: String) -> Text {
        let sentence = L10n.string("ghost.banner.urgent \(remaining)", locale: locale)
        var attributed = AttributedString(sentence)
        if let range = attributed.range(of: remaining) {
            attributed[range].font = Font(U2TextStyle.monoBannerCount.uiFont() as CTFont)
            attributed[range].foregroundColor = Color.u2.danger
        }
        return Text(attributed)
    }
}

#Preview("Ghost") {
    let now = Date()
    let calm = GhostState(isEnabled: true, period: .oneDay, deadline: now.addingTimeInterval(23 * 3600 + 41 * 60 + 7))
    let urgent = GhostState(isEnabled: true, period: .oneDay, deadline: now.addingTimeInterval(42 * 60 + 10))
    let days = GhostState(isEnabled: true, period: .sevenDays, deadline: now.addingTimeInterval(6 * 86_400 + 4 * 3600))
    return VStack(spacing: 16) {
        HStack { GhostPill(ghost: calm); GhostPill(ghost: urgent); GhostPill(ghost: days) }
        HStack { GhostChip(ghost: calm) {}; GhostChip(ghost: urgent) {} }
        GhostChip(ghost: .off) {}
        GhostCountdown(deadline: calm.deadline!)
        GhostUrgentBanner(deadline: urgent.deadline!).padding(.horizontal, 12)
    }
    .padding(.vertical, 30)
    .frame(maxWidth: .infinity)
    .background(Color.u2.bg)
    .environment(\.locale, Locale(identifier: "tr"))
}
