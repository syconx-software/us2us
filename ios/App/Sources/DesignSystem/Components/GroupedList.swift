import SwiftUI

// MARK: - C13 · Grouped list (settings style)

/// A titled group: `SectionLabel` 7 pt above a `GroupedCard` (C13).
struct GroupedSection<Content: View>: View {
    let title: Text?
    @ViewBuilder let content: Content

    init(_ title: Text?, @ViewBuilder content: () -> Content) {
        self.title = title
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: U2Spacing.sectionLabelGap) {
            if let title {
                SectionLabel(title)
            }
            GroupedCard { content }
        }
    }
}

/// The card of a group: `surface` (or `bg` on a sheet), r18. Rows inside are
/// separated by `GroupedSeparator`.
struct GroupedCard<Content: View>: View {
    var fill: Color = Color.u2.surface
    @ViewBuilder let content: Content

    init(fill: Color = Color.u2.surface, @ViewBuilder content: () -> Content) {
        self.fill = fill
        self.content = content()
    }

    var body: some View {
        VStack(spacing: 0) { content }
            .background(
                RoundedRectangle(cornerRadius: U2Radius.group, style: .continuous).fill(fill)
            )
            .clipShape(RoundedRectangle(cornerRadius: U2Radius.group, style: .continuous))
    }
}

/// The dotted separator between grouped rows, inset 57 from the card's left edge.
struct GroupedSeparator: View {
    var inset: CGFloat = U2Spacing.groupedSeparatorInset

    var body: some View {
        DottedRule()
            .padding(.leading, inset)
    }
}

/// C13 icon tile: 30 × 30 r8 `accent900`, inner 1 pt `accent800`, 18 pt `accent300` glyph.
struct IconTile: View {
    enum Content {
        case glyph(PhosphorGlyph)
        case faceID
    }

    let content: Content
    var size: CGFloat = U2Size.iconTileSettings
    var cornerRadius: CGFloat = U2Radius.iconTile
    var glyphSize: CGFloat = 18
    var tone: Tone = .accent

    enum Tone {
        case accent
        case danger
    }

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
        ZStack {
            switch tone {
            case .accent:
                shape.fill(Color.u2.accent900)
                shape.strokeBorder(Color.u2.accent800, lineWidth: 1)
            case .danger:
                shape.fill(Color.u2.dangerTint)
            }
            Group {
                switch content {
                case .glyph(let glyph):
                    PhosphorIcon(glyph, size: glyphSize)
                case .faceID:
                    FaceIDGlyph(size: glyphSize, lineWidth: 1.8)
                }
            }
            .foregroundStyle(tone == .accent ? Color.u2.accent300 : Color.u2.danger)
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

/// C13 row: tile · title (+ subtitle) · trailing detail + chevron, or a toggle.
/// ≥ 48 high (62 with a subtitle); pad 0 14 (9 14 with a subtitle); gap 13.
struct GroupedRow<Trailing: View>: View {
    let tile: IconTile?
    let title: Text
    var subtitle: Text? = nil
    @ViewBuilder let trailing: Trailing

    init(tile: IconTile?, title: Text, subtitle: Text? = nil, @ViewBuilder trailing: () -> Trailing) {
        self.tile = tile
        self.title = title
        self.subtitle = subtitle
        self.trailing = trailing()
    }

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        HStack(spacing: U2Spacing.rowGap) {
            if let tile { tile }
            if dynamicTypeSize <= .large {
                // As drawn: the column fills, the (unshrinkable) detail keeps its width.
                titleColumn
                    .frame(maxWidth: .infinity, alignment: .leading)
            } else {
                // Larger text: the title is laid out first (whole words), the
                // detail gets what is left and truncates.
                titleColumn
                    .layoutPriority(1)
                Spacer(minLength: 0)
            }
            trailing
        }
        .padding(.horizontal, 14)
        .padding(.vertical, subtitle == nil ? 0 : 9)
        .frame(minHeight: 48)
    }

    private var titleColumn: some View {
        VStack(alignment: .leading, spacing: 2) {
            title
                .u2Font(.rowTitle)
                .foregroundStyle(Color.u2.text)
            if let subtitle {
                subtitle
                    .u2Font(.captionCompact)
                    .foregroundStyle(Color.u2.textSecondary)
            }
        }
    }
}

/// Trailing detail (`label15`, `text·68%`) + chevron (`ph:caret-right` 15, `text·40%`).
struct DisclosureDetail: View {
    var detail: Text? = nil
    var showsChevron = true
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        HStack(spacing: 13) {
            if let detail {
                // `white-space: nowrap`, as drawn: at the design's text size the
                // detail keeps its full width and the row's title wraps instead
                // (EN “Activity status · People I've accepted”). Larger text:
                // the detail gives way (one line, truncated) so the title never
                // breaks inside a word.
                if dynamicTypeSize <= .large {
                    detailText(detail).fixedSize()
                } else {
                    detailText(detail).truncationMode(.tail)
                }
            }
            if showsChevron {
                PhosphorIcon(.caretRight, size: 15)
                    .foregroundStyle(Color.u2.textTertiary)
            }
        }
    }

    private func detailText(_ detail: Text) -> some View {
        detail
            .u2Font(.label15, matchLineBox: false)
            .foregroundStyle(Color.u2.textSecondary)
            .lineLimit(1)
    }
}

/// A tappable grouped row that pushes a detail screen.
struct GroupedNavigationRow: View {
    let tile: IconTile?
    let title: Text
    var subtitle: Text? = nil
    var detail: Text? = nil
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            GroupedRow(tile: tile, title: title, subtitle: subtitle) {
                DisclosureDetail(detail: detail)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(GroupedRowButtonStyle())
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
    }
}

/// A grouped row with a C14 switch.
struct GroupedToggleRow: View {
    let tile: IconTile?
    let title: Text
    var subtitle: Text? = nil
    @Binding var isOn: Bool

    var body: some View {
        Toggle(isOn: $isOn) {
            GroupedRow(tile: tile, title: title, subtitle: subtitle) { EmptyView() }
                .padding(.trailing, -14)
        }
        .toggleStyle(.u2)
        .padding(.trailing, 14)
    }
}

/// Pressed rows tint with `text·6%` (soft, 0.16 s in / 0.24 s out).
struct GroupedRowButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(Color.u2.fillSubtle.opacity(configuration.isPressed ? 1 : 0))
            .animation(configuration.isPressed ? U2Motion.press : U2Motion.release, value: configuration.isPressed)
    }
}

#Preview("Grouped list") {
    struct Demo: View {
        @State private var receipts = true
        @State private var faceID = true
        var body: some View {
            ScrollView {
                VStack(spacing: 18) {
                    GroupedSection(Text(verbatim: "Gizlilik")) {
                        GroupedNavigationRow(
                            tile: IconTile(content: .glyph(.circleHalf)), title: Text(verbatim: "Aktif durumu"),
                            detail: Text(verbatim: "Kabul ettiklerim")
                        ) {}
                        GroupedSeparator()
                        GroupedToggleRow(tile: IconTile(content: .glyph(.checks)), title: Text(verbatim: "Okundu bilgisi"), isOn: $receipts)
                    }
                    GroupedSection(Text(verbatim: "Güvenlik")) {
                        GroupedToggleRow(tile: IconTile(content: .faceID), title: Text(verbatim: "Face ID ile aç"), isOn: $faceID)
                        GroupedSeparator()
                        GroupedNavigationRow(
                            tile: IconTile(content: .glyph(.lockSimple)), title: Text(verbatim: "Otomatik kilit"),
                            detail: Text(verbatim: "Hemen")
                        ) {}
                    }
                    GroupedCard(fill: Color.u2.bg) {
                        GroupedNavigationRow(
                            tile: IconTile(content: .glyph(.scan)), title: Text(verbatim: "QR kodla ekle"),
                            subtitle: Text(verbatim: "Yan yanaysanız ekler ve aynı anda doğrular")
                        ) {}
                    }
                }
                .padding(16)
            }
            .background(Color.u2.bg)
            .environment(\.locale, Locale(identifier: "tr"))
        }
    }
    return Demo()
}
