import SwiftUI

/// “Profil görseli” (proposed — the design has no avatar picker): opened
/// from the 7.1 profile card as a full-screen page on `bg` (`U2Screen`), in
/// the app's fade cover (`u2FadeCover`, 0.36 s on the design curve) — never
/// a half-height sheet. A C7 header: the close button (44, `ph:x`) at the
/// left, the title centred.
///
/// Then, scrolling, a C13 group (rows ≥ 56, 38 pt leading, gap 13):
/// “Baş harfim kalsın” with the user's own initial avatar (C12), then
/// “Fotoğraf yükle” and “Kamera” (`ph:image`, `ph:camera` tiles) — those two
/// only call back (`onUploadPhoto`, `onTakePhoto`): the caller presents the
/// photo picker or the camera and keeps the bytes. Then “Hazır avatarlar”
/// and the presets' grid — 5 columns, 4 from accessibility sizes on.
///
/// The current choice is marked: a check on its row, or an `accent` ring and
/// check badge round its preset. A tap on the initial or a preset chooses it
/// at once (`onChoose`, selection haptic) and the mark moves; the caller
/// saves it and may close the page. VoiceOver: every option is a button,
/// the chosen one is “selected”; presets read their names.
///
/// Under the group, an optional `footnote` (with “Aramada göster” on: who
/// can see the picture, PROTOCOL §8.10) and, in `danger`, an `alert` when a
/// choice could not be saved.
///
/// Strings are keys of the Settings table (`avatar.picker.…`).
struct AvatarPresetPicker: View {
    let username: String
    let palette: AvatarPalette
    let onChoose: (AvatarChoice) -> Void
    let onUploadPhoto: () -> Void
    let onTakePhoto: () -> Void
    var onClose: (() -> Void)? = nil
    var footnote: Text? = nil
    var alert: Text? = nil

    private let current: AvatarChoice
    @State private var selection: AvatarChoice
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    /// `palette` is the initial avatar's (`.d` for the user's own, C12).
    init(
        username: String, palette: AvatarPalette = .d, current: AvatarChoice,
        onChoose: @escaping (AvatarChoice) -> Void,
        onUploadPhoto: @escaping () -> Void,
        onTakePhoto: @escaping () -> Void,
        onClose: (() -> Void)? = nil,
        footnote: Text? = nil,
        alert: Text? = nil
    ) {
        self.username = username
        self.palette = palette
        self.current = current
        self.onChoose = onChoose
        self.onUploadPhoto = onUploadPhoto
        self.onTakePhoto = onTakePhoto
        self.onClose = onClose
        self.footnote = footnote
        self.alert = alert
        _selection = State(initialValue: current.drawn)
    }

    private var columns: Int { dynamicTypeSize >= .accessibility1 ? 4 : 5 }

    var body: some View {
        U2Screen {
            VStack(spacing: 0) {
                header
                ScrollView {
                    VStack(alignment: .leading, spacing: U2Spacing.sectionGap) {
                        VStack(alignment: .leading, spacing: U2Spacing.sectionLabelGap) {
                            sources
                            notes
                        }
                        VStack(alignment: .leading, spacing: U2Spacing.sectionLabelGap) {
                            SectionLabel(Text("avatar.picker.presets", table: .settings))
                            grid
                        }
                    }
                    .padding(EdgeInsets(top: 18, leading: 16, bottom: 0, trailing: 16))
                    .u2Bottom(U2Spacing.ctaBottom)
                }
                .scrollIndicators(.hidden)
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("avatarPicker")
        .onChange(of: current) { _, new in selection = new.drawn }
    }

    // MARK: Header

    /// C7, as the app's other full-screen pages: close at the left.
    private var header: some View {
        CenteredTitleHeader(Text("avatar.picker.title", table: .settings)) {
            if let onClose {
                U2GlassIconButton(.x, label: Text("a11y.close"), action: onClose)
                    .accessibilityIdentifier("avatarPicker.close")
            } else {
                HeaderSpacer()
            }
        } trailing: {
            HeaderSpacer()
        }
    }

    // MARK: Initial, photo, camera

    private static let leadingSide: CGFloat = 38

    private var sources: some View {
        GroupedCard {
            row(
                title: Text("avatar.picker.initials", table: .settings), isSelected: selection == .initials,
                showsChevron: false, identifier: "avatarPicker.initials"
            ) {
                Avatar(username: username, palette: palette, size: .header)
            } action: {
                choose(.initials)
            }
            GroupedSeparator(inset: 14 + Self.leadingSide + U2Spacing.rowGap)
            row(
                title: Text("avatar.picker.upload", table: .settings), isSelected: selection == .photo,
                showsChevron: true, identifier: "avatarPicker.upload"
            ) {
                tile(.image)
            } action: {
                onUploadPhoto()
            }
            GroupedSeparator(inset: 14 + Self.leadingSide + U2Spacing.rowGap)
            row(
                title: Text("avatar.picker.camera", table: .settings), isSelected: false, showsChevron: true,
                identifier: "avatarPicker.camera"
            ) {
                tile(.camera)
            } action: {
                onTakePhoto()
            }
        }
    }

    /// The footnote and the alert, as 7.1's footnotes (16 pt in, `footnote`).
    @ViewBuilder private var notes: some View {
        if let alert {
            alert
                .u2Font(.footnote)
                .foregroundStyle(Color.u2.danger)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 16)
                .transition(.u2Fade)
                .accessibilityIdentifier("avatarPicker.alert")
        }
        if let footnote {
            footnote
                .u2Font(.footnote)
                .foregroundStyle(Color.u2.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 16)
                .accessibilityIdentifier("avatarPicker.footnote")
        }
    }

    private func tile(_ glyph: PhosphorGlyph) -> IconTile {
        IconTile(
            content: .glyph(glyph), size: Self.leadingSide, cornerRadius: U2Radius.iconTileXL, glyphSize: 20)
    }

    /// A C13 row: leading 38 · `rowTitle` · the check when chosen · chevron
    /// for the rows that open something.
    private func row<Leading: View>(
        title: Text, isSelected: Bool, showsChevron: Bool, identifier: String,
        @ViewBuilder leading: () -> Leading, action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: U2Spacing.rowGap) {
                leading()
                    .frame(width: Self.leadingSide, height: Self.leadingSide)
                title
                    .u2Font(.rowTitle)
                    .foregroundStyle(Color.u2.text)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if isSelected {
                    PhosphorIcon(.check, size: 18)
                        .foregroundStyle(Color.u2.accent)
                        .transition(.u2Fade)
                }
                if showsChevron {
                    PhosphorIcon(.caretRight, size: 15)
                        .foregroundStyle(Color.u2.textTertiary)
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 9)
            .frame(minHeight: 56)
            .contentShape(Rectangle())
        }
        .buttonStyle(GroupedRowButtonStyle())
        .u2Animation(U2Motion.micro, value: isSelected)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(title)
        .accessibilityAddTraits(isSelected ? [.isButton, .isSelected] : .isButton)
        .accessibilityIdentifier(identifier)
    }

    // MARK: Presets

    private var grid: some View {
        LazyVGrid(
            columns: Array(repeating: GridItem(.flexible(), spacing: 10), count: columns), spacing: 10
        ) {
            ForEach(BrandAvatarCatalog.all) { preset in
                PresetCell(preset: preset, isSelected: selection == .preset(preset.id)) {
                    choose(.preset(preset.id))
                }
            }
        }
        .padding(.horizontal, 2)
    }

    private func choose(_ choice: AvatarChoice) {
        if choice != selection { U2Haptics.selection() }
        selection = choice
        onChoose(choice)
    }
}

/// One preset of the picker: the avatar with room for the selection ring,
/// which sits 3 pt outside it, and the check badge at its lower right.
private struct PresetCell: View {
    let preset: BrandAvatarPreset
    let isSelected: Bool
    let action: () -> Void

    @Environment(\.locale) private var locale

    var body: some View {
        Button(action: action) {
            BrandAvatarCanvas(preset: preset)
                .padding(6)
                .overlay {
                    Circle()
                        .strokeBorder(Color.u2.accent, lineWidth: 2.5)
                        .opacity(isSelected ? 1 : 0)
                }
                .overlay(alignment: .bottomTrailing) {
                    if isSelected {
                        ZStack {
                            Circle().fill(Color.u2.accent)
                            PhosphorIcon(.check, size: 12)
                                .foregroundStyle(Color.u2.accent900)
                        }
                        .frame(width: 20, height: 20)
                        .u2OuterRing(Color.u2.bg, width: 2.5, in: Circle())
                        .transition(.u2Pop)
                    }
                }
                .aspectRatio(1, contentMode: .fit)
                .contentShape(Circle())
        }
        .buttonStyle(AvatarCellButtonStyle())
        .u2Animation(U2Motion.micro, value: isSelected)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(verbatim: preset.label(BrandStickerLanguage(locale: locale))))
        .accessibilityAddTraits(isSelected ? [.isButton, .isImage, .isSelected] : [.isButton, .isImage])
        .accessibilityHint(Text("avatar.picker.hint", table: .settings))
        .accessibilityIdentifier("avatarPicker.preset.\(preset.id)")
    }
}

/// Presses like a picker sticker: scale 0.92 with the design system's press curve.
private struct AvatarCellButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .modifier(U2PressEffect(isPressed: configuration.isPressed, scale: 0.92))
    }
}

#Preview("Avatar picker") {
    struct Demo: View {
        @State private var choice = AvatarChoice.preset(2)

        var body: some View {
            AvatarPresetPicker(
                username: "selin_n", current: choice, onChoose: { choice = $0 }, onUploadPhoto: {},
                onTakePhoto: {}, onClose: {}
            )
        }
    }
    return Demo()
}
