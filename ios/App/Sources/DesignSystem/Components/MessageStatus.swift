import SwiftUI

/// C16 — the delivery state glyph, identical everywhere (1.2 legend, list
/// rows, bubbles, 5.4): Gönderildi `ph:check` `text·68%` · İletildi
/// `ph:checks` `text·68%` · Okundu `ph:checks` **fill** `accent300` ·
/// İletilemedi `ph:warning-circle` `danger`. Changes cross-fade (0.36 s).
struct MessageStatusIcon: View {
    let status: MessageStatus
    var size: CGFloat = 15

    var body: some View {
        icon
            .id(status)
            .transition(.opacity)
            .u2Animation(U2Motion.state, value: status)
            .accessibilityElement()
            .accessibilityLabel(Text(status.titleKey))
    }

    @ViewBuilder private var icon: some View {
        switch status {
        case .sending:
            PhosphorIcon(.circleNotch, size: size).foregroundStyle(Color.u2.textTertiary)
        case .sent:
            PhosphorIcon(.check, size: size).foregroundStyle(Color.u2.textSecondary)
        case .delivered:
            PhosphorIcon(.checks, size: size).foregroundStyle(Color.u2.textSecondary)
        case .read:
            PhosphorIcon(.checks, weight: .fill, size: size).foregroundStyle(Color.u2.accent300)
        case .failed:
            PhosphorIcon(.warningCircle, size: size).foregroundStyle(Color.u2.danger)
        }
    }
}

extension MessageStatus {
    /// “Gönderildi”, “İletildi”, “Okundu”, “İletilemedi”.
    var titleKey: LocalizedStringKey {
        switch self {
        case .sending: "status.sending"
        case .sent: "status.sent"
        case .delivered: "status.delivered"
        case .read: "status.read"
        case .failed: "status.failed"
        }
    }

    var titleResource: String.LocalizationValue {
        switch self {
        case .sending: "status.sending"
        case .sent: "status.sent"
        case .delivered: "status.delivered"
        case .read: "status.read"
        case .failed: "status.failed"
        }
    }

    /// The sentence under the newest outgoing bubble (5.1): “Alıcı uygulamayı açınca iletilecek”…
    var sentenceResource: String.LocalizationValue? {
        switch self {
        case .sending: nil
        case .sent: "status.sent.sentence"
        case .delivered: "status.delivered.sentence"
        case .read: nil
        case .failed: "status.failed.sentence"
        }
    }

    /// The 1.2 legend explanations (“alıcı açınca iletilecek”…).
    var legendResource: String.LocalizationValue? {
        switch self {
        case .sending, .read: nil
        case .sent: "status.sent.legend"
        case .delivered: "status.delivered.legend"
        case .failed: "status.failed.legend"
        }
    }
}

/// 5.1 item 8 — the newest outgoing message's state as a sentence, right
/// aligned under its bubble (pad-right 4, gap 6): a 14 pt glyph, the state in
/// Inter 500 12 `text`, and “ · explanation” in Inter 400 12 `text·68%` —
/// always one line in both languages (9.2 note).
struct MessageStatusSentence: View {
    let status: MessageStatus
    var onResend: (() -> Void)? = nil
    @Environment(\.locale) private var locale

    var body: some View {
        HStack(spacing: 6) {
            MessageStatusIcon(status: status, size: 14)
            sentence
                .lineLimit(1)
                .minimumScaleFactor(0.85)
            if status == .failed, let onResend {
                Button(action: onResend) {
                    Text("status.failed.resend")
                        .u2Font(.statusLineEmphasis, matchLineBox: false)
                        .foregroundStyle(Color.u2.accent300)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.trailing, 4)
        .frame(maxWidth: .infinity, alignment: .trailing)
        .id(status)
        .transition(.u2Fade)
        .u2Animation(U2Motion.state, value: status)
        .accessibilityElement(children: .combine)
    }

    private var sentence: some View {
        var title = AttributedString(L10n.string(status.titleResource, locale: locale))
        title.font = Font(U2TextStyle.statusLineEmphasis.uiFont() as CTFont)
        title.foregroundColor = Color.u2.text
        if let rest = status.sentenceResource {
            var tail = AttributedString(" · " + L10n.string(rest, locale: locale))
            tail.font = Font(U2TextStyle.statusLine.uiFont() as CTFont)
            tail.foregroundColor = Color.u2.textSecondary
            title += tail
        }
        // Line box of `statusLine` (12/18.6).
        return Text(title).u2Font(.statusLine)
    }
}

/// 1.2 legend row: 17 pt glyph in an 18-wide slot, gap 10, state name
/// (`legendEmphasis`, `text`) + “ · explanation” (`legend`, `text·68%`).
struct MessageStatusLegendRow: View {
    let status: MessageStatus
    @Environment(\.locale) private var locale

    var body: some View {
        HStack(spacing: 10) {
            MessageStatusIcon(status: status, size: 17)
                .frame(width: 18)
            text
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }

    private var text: some View {
        var title = AttributedString(L10n.string(status.titleResource, locale: locale))
        title.font = Font(U2TextStyle.legendEmphasis.uiFont() as CTFont)
        title.foregroundColor = Color.u2.text
        if let legend = status.legendResource {
            var tail = AttributedString(" · " + L10n.string(legend, locale: locale))
            tail.font = Font(U2TextStyle.legend.uiFont() as CTFont)
            tail.foregroundColor = Color.u2.textSecondary
            title += tail
        }
        return Text(title).u2Font(.legend)
    }
}

#Preview("Message status") {
    VStack(alignment: .leading, spacing: 9) {
        HStack(spacing: 12) {
            ForEach(MessageStatus.allCases, id: \.self) { MessageStatusIcon(status: $0) }
        }
        ForEach([MessageStatus.sent, .delivered, .read, .failed], id: \.self) {
            MessageStatusLegendRow(status: $0)
        }
        MessageStatusSentence(status: .sent)
        MessageStatusSentence(status: .failed) {}
    }
    .padding(24)
    .background(Color.u2.bg)
    .environment(\.locale, Locale(identifier: "tr"))
}
