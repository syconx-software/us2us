import SwiftUI

// Helpers only 04 · Sohbetler uses. Shared pieces (menus, fade covers, the
// QR card, flex rows, CSS text layouts, the collapse transition) live in the
// DesignSystem.

// MARK: - Restore access card (4.1)

/// 4.1 after a restore whose token rotation is unanswered (PROTOCOL §5.5
/// step 3.4): the old phone may still have access. A card in the requests
/// card's place and shape (`surface` r18, pad 13 14, gap 13; 42 pt round
/// tile — here `dangerTint` with `ph:warning-circle` in `danger`; title
/// `rowTitleEmphasis`, subtitle `caption`; chevron), shown only in that
/// state; it opens 7.1, where the notice explains it and checks again.
/// No design reference (proposed).
struct RestoreAccessCard: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 13) {
                PhosphorIcon(.warningCircle, size: 21)
                    .foregroundStyle(Color.u2.danger)
                    .frame(width: 42, height: 42)
                    .background(Circle().fill(Color.u2.dangerTint))
                VStack(alignment: .leading, spacing: 2) {
                    Text("access.card.title", table: .chats)
                        .u2Font(.rowTitleEmphasis)
                        .foregroundStyle(Color.u2.text)
                        .fixedSize(horizontal: false, vertical: true)
                    Text("access.card.subtitle", table: .chats)
                        .u2Font(.caption)
                        .foregroundStyle(Color.u2.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                PhosphorIcon(.caretRight, size: 15)
                    .foregroundStyle(Color.u2.textTertiary)
            }
            .padding(EdgeInsets(top: 13, leading: 14, bottom: 13, trailing: 14))
            .background(RoundedRectangle(cornerRadius: U2Radius.card, style: .continuous).fill(Color.u2.surface))
            .contentShape(RoundedRectangle(cornerRadius: U2Radius.card, style: .continuous))
        }
        .buttonStyle(CardButtonStyle())
        .accessibilityElement(children: .combine)
    }
}

// MARK: - Request content (4.3, 4.4)

/// How a stranger's message is shown before it is accepted: links never
/// become tappable and media is never decoded (inventory 4.3/4.4 notes).
enum RequestContent {
    enum TextKind: Equatable {
        case plain
        /// The whole message is one address (“slides.kuzey.dev/meetup”).
        case link
        /// Text with an address inside (“… tıkla: bit.ly/3xQ”).
        case containsLink
    }

    /// Local pattern only — nothing is fetched or resolved. Scheme optional,
    /// a dotted host with a ≥ 2-letter top-level label, optional path.
    private static var linkPattern: Regex<Substring> {
        #/(?i)(?:https?:\/\/)?(?:[a-z0-9-]+\.)+[a-z]{2,}(?:\/[^\s]*)?/#
    }

    static func classify(_ text: String) -> TextKind {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let match = trimmed.firstMatch(of: linkPattern) else { return .plain }
        return match.range == trimmed.startIndex..<trimmed.endIndex ? .link : .containsLink
    }

    /// Whether a request's thread carries media (shown as a placeholder only).
    static func isMedia(_ content: MessageContent) -> Bool {
        switch content {
        case .photo, .video, .voice, .file, .location, .liveLocation, .viewOnce, .textSticker, .sticker: true
        case .text, .deleted, .system: false
        }
    }
}

// MARK: - Times (4.3 ages, 4.4 date separator)

enum ChatsFormat {
    /// 4.3 request age: “2 sa” · “Dün” · “3 gün” (< 1 h “N dk”; ≥ 7 days `d MMM`).
    static func requestAge(_ date: Date, now: Date, locale: Locale, calendar: Calendar = .current) -> String {
        var calendar = calendar
        calendar.locale = locale
        let elapsed = max(0, now.timeIntervalSince(date))
        if elapsed < 3_600 {
            return StringTable.chats.string("requests.age.minutes \(max(1, Int(elapsed / 60)))", locale: locale)
        }
        if elapsed < 86_400 && calendar.isDate(date, inSameDayAs: now) {
            return StringTable.chats.string("requests.age.hours \(Int(elapsed / 3_600))", locale: locale)
        }
        let days = calendar.dateComponents([.day], from: calendar.startOfDay(for: date), to: calendar.startOfDay(for: now)).day ?? 0
        if days <= 1 {
            return elapsed < 86_400
                ? StringTable.chats.string("requests.age.hours \(Int(elapsed / 3_600))", locale: locale)
                : L10n.string("time.yesterday", locale: locale)
        }
        if days < 7 {
            return StringTable.chats.string("requests.age.days \(days)", locale: locale)
        }
        return U2Format.dayMonth(date, locale: locale, calendar: calendar)
    }

    /// 4.4 date separator: “Bugün 16:12” / “Dün 16:12” / “24 Eyl 16:12”
    /// (upper-cased by `monoSectionLabel` in the current language).
    static func separator(_ date: Date, now: Date, locale: Locale, calendar: Calendar = .current) -> String {
        var calendar = calendar
        calendar.locale = locale
        let time = U2Format.clockTime(date, locale: locale, calendar: calendar)
        if calendar.isDate(date, inSameDayAs: now) {
            return StringTable.chats.string("preview.date.today \(time)", locale: locale)
        }
        if let yesterday = calendar.date(byAdding: .day, value: -1, to: now), calendar.isDate(date, inSameDayAs: yesterday) {
            return StringTable.chats.string("preview.date.yesterday \(time)", locale: locale)
        }
        return "\(U2Format.dayMonth(date, locale: locale, calendar: calendar)) \(time)"
    }
}

// MARK: - Sheet pages

extension AnyTransition {
    /// Mini navigation inside a sheet (4.2 → “Kodumu göster”): the soft
    /// `screenPush` (cross-fade + 16 pt drift) in either direction.
    static func chatsPage(forward: Bool) -> AnyTransition {
        .asymmetric(
            insertion: .u2Soft(offset: CGSize(width: forward ? 16 : -16, height: 0), blur: 0),
            removal: .u2Soft(offset: CGSize(width: forward ? -16 : 16, height: 0), blur: 0))
    }
}

// MARK: - Scroll-aware header backdrop

/// The fixed header band of 4.1 / 4.3 (y 0…`designHeight`): clear at rest —
/// exactly as drawn — and, once content scrolls beneath it, the C8 chat-header
/// treatment (`bg·80%` + blur, dotted rule) fading in softly (proposed).
struct ChatsHeaderBackdrop: View {
    let isVisible: Bool
    var designHeight: CGFloat = 108
    @Environment(\.u2SafeArea) private var safeArea

    var body: some View {
        U2Backdrop(style: .header)
            .frame(height: safeArea.top(designHeight))
            .overlay(alignment: .bottom) {
                DottedRule(color: Color.u2.dotRuleHeader)
                    .padding(.bottom, 2)
            }
            .frame(maxWidth: .infinity)
            .opacity(isVisible ? 1 : 0)
            .u2Animation(U2Motion.state, value: isVisible)
            .allowsHitTesting(false)
            .accessibilityHidden(true)
    }
}

// MARK: - List separator

/// The 4.1 row separator — exactly `DottedRule()` (C19 `rule`: r .75 / 1.1,
/// pitch 5, 2 high, `dotRule`), but drawn as one pre-rendered dot tile
/// repeated across the width instead of a `Canvas` that fills 60-odd
/// gradient discs for every row that scrolls in (the canvas cost more than
/// the whole row: ≈ 2.4 ms against ≈ 1 ms in DEBUG). The tile is rendered
/// once per theme and screen scale by the same renderer, from the same
/// `DottedRule`, and the dots keep their phase (first centre at pitch / 2),
/// so the pixels are the same.
struct ChatListSeparator: View {
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.displayScale) private var displayScale

    var body: some View {
        Group {
            if let tile = DotTileCache.shared.tile(scheme: colorScheme, scale: displayScale) {
                Image(uiImage: tile)
                    .resizable(resizingMode: .tile)
            } else {
                DottedRule()
            }
        }
        .frame(height: DotPattern.rule.thickness)
        .frame(maxWidth: .infinity)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

@MainActor
private final class DotTileCache {
    static let shared = DotTileCache()
    private var tiles: [String: UIImage] = [:]

    func tile(scheme: ColorScheme, scale: CGFloat) -> UIImage? {
        let key = "\(scheme == .dark ? "d" : "l")@\(scale)"
        if let tile = tiles[key] { return tile }
        let pattern = DotPattern.rule
        let renderer = ImageRenderer(
            content: DottedRule(pattern: pattern)
                .frame(width: pattern.pitch, height: pattern.thickness)
                .environment(\.colorScheme, scheme))
        renderer.scale = scale
        renderer.isOpaque = false
        guard let image = renderer.uiImage else { return nil }
        tiles[key] = image
        return image
    }
}
