import SwiftUI

/// A plain design card: `surface`, r18 (or r16/r20), with padding.
struct U2Card<Content: View>: View {
    var fill: Color = Color.u2.surface
    var cornerRadius: CGFloat = U2Radius.card
    var padding = EdgeInsets(top: 14, leading: 16, bottom: 14, trailing: 16)
    @ViewBuilder let content: Content

    init(
        fill: Color = Color.u2.surface, cornerRadius: CGFloat = U2Radius.card,
        padding: EdgeInsets = EdgeInsets(top: 14, leading: 16, bottom: 14, trailing: 16),
        @ViewBuilder content: () -> Content
    ) {
        self.fill = fill
        self.cornerRadius = cornerRadius
        self.padding = padding
        self.content = content()
    }

    var body: some View {
        content
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous).fill(fill))
    }
}

/// 3.3 option card: `bg` r18, pad 14 14 14 16, gap 14 — icon tile 40 r12 ·
/// title `rowTitleEmphasis` + description `captionTighter` (`text·68%`) ·
/// chevron 15 `text·40%`.
struct OptionCard: View {
    let glyph: PhosphorGlyph
    var tone: IconTile.Tone = .accent
    let title: Text
    let description: Text
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 14) {
                IconTile(
                    content: .glyph(glyph), size: U2Size.iconTileSheetOption, cornerRadius: U2Radius.iconTileXL,
                    glyphSize: 21, tone: tone)
                VStack(alignment: .leading, spacing: 3) {
                    title
                        .u2Font(.rowTitleEmphasis)
                        .foregroundStyle(tone == .danger ? Color.u2.danger : Color.u2.text)
                    description
                        .u2Font(.captionTighter)
                        .foregroundStyle(Color.u2.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                PhosphorIcon(.caretRight, size: 15)
                    .foregroundStyle(Color.u2.textTertiary)
            }
            .padding(EdgeInsets(top: 14, leading: 16, bottom: 14, trailing: 14))
            .background(RoundedRectangle(cornerRadius: U2Radius.card, style: .continuous).fill(Color.u2.bg))
            .contentShape(RoundedRectangle(cornerRadius: U2Radius.card, style: .continuous))
        }
        .buttonStyle(CardButtonStyle())
        .accessibilityElement(children: .combine)
    }
}

/// Cards press softly (scale 0.98 + slight dim).
struct CardButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .opacity(configuration.isPressed ? 0.85 : 1)
            .modifier(U2PressEffect(isPressed: configuration.isPressed, scale: 0.98))
    }
}

/// 5.4 note card: transparent, r20, inner 1 pt `danger·40%`, pad 16, gap 14:
/// a 22 pt glyph + title `noteTitle` + body `captionTight`.
struct NoteCard: View {
    var glyph: PhosphorGlyph = .warningCircle
    let title: Text
    let message: Text

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            PhosphorIcon(glyph, size: 22)
                .foregroundStyle(Color.u2.danger)
            VStack(alignment: .leading, spacing: 4) {
                title
                    .u2Font(.noteTitle)
                    .foregroundStyle(Color.u2.text)
                message
                    .u2Font(.captionTight)
                    .foregroundStyle(Color.u2.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(16)
        .u2InnerStroke(Color.u2.dangerStrokeSoft, width: 1, cornerRadius: U2Radius.cardL)
        .accessibilityElement(children: .combine)
    }
}

/// 4.3 privacy banner: `accent900` r18 + inner 1 pt `accent800`, pad 14 16,
/// gap 12: `ph:shield-check` 22 `accent300` + `callout` in `accent200`.
struct InfoBanner: View {
    var glyph: PhosphorGlyph = .shieldCheck
    let message: Text

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            PhosphorIcon(glyph, size: 22)
                .foregroundStyle(Color.u2.accent300)
            message
                .u2Font(.callout)
                .foregroundStyle(Color.u2.accent200)
                .frame(maxWidth: .infinity, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(EdgeInsets(top: 14, leading: 16, bottom: 14, trailing: 16))
        .background(RoundedRectangle(cornerRadius: U2Radius.card, style: .continuous).fill(Color.u2.accent900))
        .u2InnerStroke(Color.u2.accent800, width: 1, cornerRadius: U2Radius.card)
        .accessibilityElement(children: .combine)
    }
}

/// Floating danger-ringed banners: 5.1 urgent Ghost banner (r16, blur 16) and
/// 5.6 screenshot warning (r20, blur 20) — `surface·92%` + backdrop blur,
/// inner ring `danger·45%`, soft drop shadow. Enter with fade + 8 pt rise/drop.
struct FloatingBanner<Content: View>: View {
    enum Kind {
        /// 5.1: r16, pad 11 14, gap 10, `0 10px 30px #000@30%`.
        case urgent
        /// 5.6: r20, pad 14 16, gap 12, `0 14px 40px #000@45%`.
        case warning
    }

    let kind: Kind
    @ViewBuilder let content: Content

    init(_ kind: Kind, @ViewBuilder content: () -> Content) {
        self.kind = kind
        self.content = content()
    }

    var body: some View {
        let radius: CGFloat = kind == .urgent ? 16 : 20
        let shape = RoundedRectangle(cornerRadius: radius, style: .continuous)
        content
            .padding(kind == .urgent ? EdgeInsets(top: 11, leading: 14, bottom: 11, trailing: 14) : EdgeInsets(top: 14, leading: 16, bottom: 14, trailing: 16))
            .frame(maxWidth: .infinity, alignment: .leading)
            .u2Backdrop(kind == .urgent ? .banner : .warning, in: shape)
            .u2InnerStroke(Color.u2.dangerStroke, width: 1, in: shape)
            .u2BoxShadow(kind == .urgent ? .floatingBanner : .floatingWarning, in: shape)
    }
}

/// 5.1 system pill: `text·6%`, r14, pad 6 12, gap 7 — a 14 pt glyph +
/// `monoSystemPill` (Mono 11/15.4) `text·68%`; centred, max width 330.
struct SystemPill: View {
    var glyph: PhosphorGlyph? = .ghost
    /// `ph:seal-check` is bundled only filled (icons.md).
    var glyphWeight: PhosphorWeight = .regular
    let text: Text

    var body: some View {
        HStack(spacing: 7) {
            if let glyph {
                PhosphorIcon(glyph, weight: glyphWeight, size: 14)
            }
            text
                .u2Font(.monoSystemPill)
                .multilineTextAlignment(.center)
        }
        .foregroundStyle(Color.u2.textSecondary)
        .padding(.vertical, 6)
        .padding(.horizontal, 12)
        .background(RoundedRectangle(cornerRadius: U2Radius.systemPill, style: .continuous).fill(Color.u2.fillSubtle))
        .frame(maxWidth: 330)
        .accessibilityElement(children: .combine)
    }
}

/// A small mono flag line: a 13 pt glyph + `monoTag` (gap 6) — 4.3
/// “Medya kabul edene kadar gizli” (`text·68%`), “Bağlantı içeriyor · açılmaz” (`danger`).
struct FlagTag: View {
    let glyph: PhosphorGlyph
    let text: Text
    var color: Color = Color.u2.textSecondary

    var body: some View {
        HStack(spacing: 6) {
            PhosphorIcon(glyph, size: 13)
            text.u2Font(.monoTag)
        }
        .foregroundStyle(color)
        .accessibilityElement(children: .combine)
    }
}

/// 6.3 info pill: `text·6%` r14, pad 8 14, gap 8 — `ph:clock` 14 + `monoMeta`.
struct InfoPill: View {
    var glyph: PhosphorGlyph = .clock
    let text: Text

    var body: some View {
        HStack(spacing: 8) {
            PhosphorIcon(glyph, size: 14)
            text.u2Font(.monoMeta)
        }
        .foregroundStyle(Color.u2.textSecondary)
        .padding(.vertical, 8)
        .padding(.horizontal, 14)
        .background(RoundedRectangle(cornerRadius: U2Radius.systemPill, style: .continuous).fill(Color.u2.fillSubtle))
        .accessibilityElement(children: .combine)
    }
}

#Preview("Cards") {
    ScrollView {
        VStack(spacing: 16) {
            InfoBanner(message: Text(verbatim: "Kabul edene kadar sohbet açılmaz. Okuduğunu ve aktif olduğunu göremezler."))
            OptionCard(glyph: .fileLock, title: Text(verbatim: "Yedekten geri yükle"), description: Text(verbatim: "Şifreli yedeğin ve parolası varsa sohbetlerin geri gelir.")) {}
            OptionCard(glyph: .trash, tone: .danger, title: Text(verbatim: "Her şeyi sil, baştan başla"), description: Text(verbatim: "Bu cihazdaki tüm sohbetler ve anahtarlar kalıcı olarak silinir.")) {}
            NoteCard(title: Text(verbatim: "İletilemedi ne demek?"), message: Text(verbatim: "Alıcı 14 gün içinde uygulamayı açmazsa mesaj sunucudan silinir ve ona hiç ulaşmaz."))
            FloatingBanner(.urgent) {
                HStack(spacing: 10) {
                    PhosphorIcon(.ghost, weight: .fill, size: 18).foregroundStyle(Color.u2.danger)
                    Text(verbatim: "Sohbet 43 dk içinde iki taraftan silinecek.").u2Font(.bullet).foregroundStyle(Color.u2.text)
                }
            }
            SystemPill(text: Text(verbatim: "Ghost mod açık · 24 saat"))
            FlagTag(glyph: .warning, text: Text(verbatim: "Bağlantı içeriyor · açılmaz"), color: Color.u2.danger)
            InfoPill(text: Text(verbatim: "8 Ekim 2026 · 18:52 · yüz yüze"))
        }
        .padding(16)
    }
    .background(Color.u2.bg)
}
