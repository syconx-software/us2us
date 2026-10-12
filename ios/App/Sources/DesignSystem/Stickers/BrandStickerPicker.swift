import SwiftUI

/// The sticker picker (proposed — the design has the composer's `ph:sticker`
/// button, C21, but no picker). Opened from that button, either
///
/// * in a C18 sheet that hugs its content — `.u2Sheet(style: .content)`
///   gives the grabber, the `surface` fill and the home-indicator padding;
///   the header carries the title, the TR/EN switch and close; or
/// * as a panel in the keyboard's place under the composer
///   (`.panel(height:)`, the keyboard's height): `surface`, r20 top corners,
///   the TR/EN switch at the end of the pack tabs.
///
/// From the top: pack tabs (C22 chips with each pack's cover sticker),
/// “Son kullanılanlar” (`recents`, kept by the caller, each in the language
/// it was sent in) and the pack's grid — 4 columns of ~72 pt stickers, 3 from
/// accessibility sizes on, 2 from AX3. Text stickers are shown in the app's
/// language; the TR/EN switch (only over a pack with words) shows them in
/// the other one. Tap sends (`onSend`, with the language shown); touch and
/// hold opens a large preview with “Gönder” (`onPreview` is told). VoiceOver:
/// each sticker is a button with a “Önizle” action; the switch is adjustable.
///
/// Strings are keys of the Conversation table (`sticker.…`).
struct BrandStickerPicker: View {
    enum Presentation: Equatable, Sendable {
        /// Inside `u2Sheet(style: .content)`.
        case sheet
        /// In place of the keyboard, `height` high.
        case panel(height: CGFloat)
    }

    /// The content height inside the sheet (the sheet adds its grabber and
    /// the home indicator).
    static let sheetHeight: CGFloat = 468

    let recents: [BrandStickerSelection]
    var presentation: Presentation = .sheet
    let onSend: (BrandStickerSelection) -> Void
    var onPreview: (BrandStickerSelection) -> Void = { _ in }
    /// Shows a close button in the sheet's header.
    var onClose: (() -> Void)? = nil

    @State private var packID = BrandStickerCatalog.packs[0].id
    /// The TR/EN switch; nil follows the app's language.
    @State private var chosenLanguage: BrandStickerLanguage?
    @State private var preview: BrandStickerSelection?
    @ScaledMetric(relativeTo: .body) private var recentSide: CGFloat = 60
    @Environment(\.locale) private var locale
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    init(
        recents: [BrandStickerSelection], presentation: Presentation = .sheet,
        onSend: @escaping (BrandStickerSelection) -> Void,
        onPreview: @escaping (BrandStickerSelection) -> Void = { _ in },
        onClose: (() -> Void)? = nil
    ) {
        self.recents = recents
        self.presentation = presentation
        self.onSend = onSend
        self.onPreview = onPreview
        self.onClose = onClose
    }

    private var uiLanguage: BrandStickerLanguage { BrandStickerLanguage(locale: locale) }
    private var language: BrandStickerLanguage { chosenLanguage ?? uiLanguage }
    private var pack: BrandStickerPack { BrandStickerCatalog.pack(id: packID) ?? BrandStickerCatalog.packs[0] }
    private var stickers: [BrandSticker] { BrandStickerCatalog.stickers(in: packID) }
    private var packHasWords: Bool { stickers.contains(where: \.isText) }

    /// Recents without repeats (the caller's order).
    private var uniqueRecents: [BrandStickerSelection] {
        var seen = Set<String>()
        return recents.filter { seen.insert($0.id).inserted }
    }

    private var columns: Int {
        if dynamicTypeSize >= .accessibility3 { return 2 }
        if dynamicTypeSize >= .accessibility1 { return 3 }
        return 4
    }

    private var height: CGFloat {
        switch presentation {
        case .sheet: Self.sheetHeight
        case .panel(let height): height
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            if presentation == .sheet { header }
            tabs
            ScrollView { content }
                .scrollIndicators(.hidden)
        }
        .frame(maxWidth: .infinity)
        .frame(height: height)
        .background {
            if presentation != .sheet { panelBackground }
        }
        .overlay {
            if let preview {
                previewOverlay(preview)
                    .transition(.u2Pop)
            }
        }
        .u2Animation(U2Motion.state, value: packID)
        .u2Animation(U2Motion.state, value: preview)
    }

    // MARK: Header, tabs, language

    private var header: some View {
        HStack(spacing: 12) {
            Text("sticker.picker.title", table: .conversation)
                .u2Font(.navTitle)
                .foregroundStyle(Color.u2.text)
                .lineLimit(1)
                .accessibilityAddTraits(.isHeader)
            Spacer(minLength: 8)
            languageSwitch
            if let onClose {
                U2GlassIconButton(
                    .x, diameter: U2Size.iconButtonSheet, glyphSize: 17, label: Text("a11y.close"), action: onClose)
            }
        }
        .padding(.horizontal, U2Spacing.screenGutterS)
        .padding(.top, 6)
        .padding(.bottom, 12)
    }

    private var tabs: some View {
        HStack(spacing: 8) {
            ScrollView(.horizontal) {
                HStack(spacing: 6) {
                    ForEach(BrandStickerCatalog.packs) { pack in tab(pack) }
                }
                .padding(.horizontal, U2Spacing.screenGutterS)
            }
            .scrollIndicators(.hidden)
            if presentation != .sheet {
                languageSwitch
                    .padding(.trailing, U2Spacing.screenGutterS)
            }
        }
        .padding(.top, presentation == .sheet ? 0 : 12)
        .padding(.bottom, 4)
    }

    /// C22's chip, with the pack's cover sticker.
    private func tab(_ pack: BrandStickerPack) -> some View {
        let isSelected = pack.id == packID
        let shape = RoundedRectangle(cornerRadius: U2Radius.chip, style: .continuous)
        return Button {
            guard !isSelected else { return }
            U2Haptics.selection()
            packID = pack.id
        } label: {
            HStack(spacing: 6) {
                if let cover = BrandStickerCatalog.sticker(id: pack.coverID) {
                    BrandStickerCanvas(sticker: cover, language: language)
                        .frame(width: 28, height: 28)
                }
                Text(LocalizedStringKey(pack.titleKey), table: .conversation)
                    .u2Font(.chip, matchLineBox: false)
                    .lineLimit(1)
                    .foregroundStyle(isSelected ? Color.u2.accent300 : Color.u2.text)
            }
            .padding(.leading, 6)
            .padding(.trailing, 12)
            .frame(minHeight: 40)
            .background(shape.fill(Color.u2.accent900.opacity(isSelected ? 1 : 0)))
            .overlay(
                shape.strokeBorder(isSelected ? Color.u2.accent : Color.u2.divider, lineWidth: isSelected ? 1.5 : 1)
            )
            .contentShape(shape)
        }
        .buttonStyle(CardButtonStyle())
        .accessibilityAddTraits(isSelected ? .isSelected : [])
        .u2Animation(U2Motion.micro, value: isSelected)
    }

    /// TR | EN — the language of the words on text stickers.
    private var languageSwitch: some View {
        HStack(spacing: 2) {
            ForEach(BrandStickerLanguage.allCases) { option in
                let isSelected = option == language
                Text(verbatim: option == .turkish ? "TR" : "EN")
                    .u2Font(.monoMeta, matchLineBox: false)
                    .foregroundStyle(isSelected ? Color.u2.accent300 : Color.u2.textSecondary)
                    .frame(minWidth: 34, minHeight: 28)
                    .background(Capsule().fill(Color.u2.accent900.opacity(isSelected ? 1 : 0)))
                    .overlay(Capsule().strokeBorder(Color.u2.accent.opacity(isSelected ? 1 : 0), lineWidth: 1))
                    .contentShape(Capsule())
                    .onTapGesture { choose(option) }
            }
        }
        .padding(2)
        .background(Capsule().fill(Color.u2.fillSubtle))
        .overlay(Capsule().strokeBorder(Color.u2.divider, lineWidth: 0.5))
        .u2Animation(U2Motion.micro, value: language)
        .opacity(packHasWords ? 1 : 0)
        .allowsHitTesting(packHasWords)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("sticker.picker.language", table: .conversation))
        .accessibilityValue(languageName(language))
        .accessibilityHint(Text("sticker.picker.language.hint", table: .conversation))
        .accessibilityAdjustableAction { _ in choose(language.other) }
        .accessibilityHidden(!packHasWords)
    }

    private func languageName(_ language: BrandStickerLanguage) -> Text {
        language == .turkish
            ? Text("sticker.picker.language.tr", table: .conversation)
            : Text("sticker.picker.language.en", table: .conversation)
    }

    private func choose(_ option: BrandStickerLanguage) {
        guard option != language else { return }
        U2Haptics.selection()
        chosenLanguage = option == uiLanguage ? nil : option
    }

    // MARK: Content

    private var content: some View {
        VStack(alignment: .leading, spacing: 10) {
            let recents = uniqueRecents
            if !recents.isEmpty {
                SectionLabel(Text("sticker.picker.recent", table: .conversation), horizontalPadding: 0)
                ScrollView(.horizontal) {
                    HStack(spacing: 6) {
                        ForEach(recents) { selection in
                            StickerCell(selection: selection, onSend: send, onPreview: showPreview)
                                .frame(width: recentSide, height: recentSide)
                        }
                    }
                }
                .scrollIndicators(.hidden)
                .scrollClipDisabled()
                SectionLabel(Text(LocalizedStringKey(pack.titleKey), table: .conversation), horizontalPadding: 0)
                    .padding(.top, 6)
            }
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 6), count: columns), spacing: 6) {
                ForEach(stickers) { sticker in
                    StickerCell(
                        selection: BrandStickerSelection(sticker, language: language), onSend: send,
                        onPreview: showPreview)
                }
            }
        }
        .padding(.horizontal, U2Spacing.screenGutterS)
        .padding(.top, 10)
        .padding(.bottom, 24)
    }

    private var panelBackground: some View {
        let shape = UnevenRoundedRectangle(
            topLeadingRadius: U2Radius.cardL, topTrailingRadius: U2Radius.cardL, style: .continuous)
        return
            shape
            .fill(Color.u2.surface)
            .overlay(shape.stroke(Color.u2.strokeField, lineWidth: 0.5))
            .ignoresSafeArea(edges: .bottom)
    }

    // MARK: Sending and the preview

    private func send(_ selection: BrandStickerSelection) {
        preview = nil
        onSend(selection)
    }

    private func showPreview(_ selection: BrandStickerSelection) {
        U2Haptics.longPress()
        preview = selection
        onPreview(selection)
    }

    /// What the preview says under the sticker: what it shows, in the app's
    /// language — nothing for words already in it.
    private func caption(_ selection: BrandStickerSelection) -> String? {
        let sticker = selection.sticker
        if sticker.isText && selection.language == uiLanguage && sticker.packID == BrandStickerCatalog.wordsPackID {
            return nil
        }
        return sticker.label(uiLanguage)
    }

    private func previewOverlay(_ selection: BrandStickerSelection) -> some View {
        let side: CGFloat = presentation == .sheet ? 176 : 128
        return ZStack {
            Color.u2.surface.opacity(0.94)
                .ignoresSafeArea(edges: .bottom)
                .contentShape(Rectangle())
                .onTapGesture { preview = nil }
                .accessibilityHidden(true)
            VStack(spacing: 14) {
                BrandStickerView(selection, animatesEntrance: true)
                    .frame(width: side, height: side)
                if let caption = caption(selection) {
                    Text(verbatim: caption)
                        .u2Font(.captionTight)
                        .foregroundStyle(Color.u2.textSecondary)
                        .multilineTextAlignment(.center)
                        .lineLimit(2)
                        .accessibilityHidden(true)
                }
                Button {
                    send(selection)
                } label: {
                    U2ButtonLabel(Text("sticker.preview.send", table: .conversation), glyph: .paperPlaneTilt)
                }
                .buttonStyle(.u2PrimaryInCard)
                .frame(maxWidth: 220)
            }
            .padding(24)
            .accessibilityElement(children: .contain)
            .accessibilityAddTraits(.isModal)
        }
        .accessibilityAction(.escape) { preview = nil }
    }
}

/// One sticker of the picker: tap sends, touch and hold previews (with the
/// press scale of the design system).
private struct StickerCell: View {
    let selection: BrandStickerSelection
    let onSend: (BrandStickerSelection) -> Void
    let onPreview: (BrandStickerSelection) -> Void

    @State private var isPressed = false
    @Environment(\.locale) private var locale

    var body: some View {
        let label = selection.sticker.accessibilityLabel(
            shownIn: selection.language, ui: BrandStickerLanguage(locale: locale))
        BrandStickerView(selection)
            .padding(3)
            .aspectRatio(1, contentMode: .fit)
            .contentShape(Rectangle())
            .modifier(U2PressEffect(isPressed: isPressed, scale: 0.92))
            .onTapGesture { onSend(selection) }
            .onLongPressGesture(minimumDuration: 0.35) {
                onPreview(selection)
            } onPressingChanged: { pressing in
                isPressed = pressing
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Text(verbatim: label))
            .accessibilityAddTraits([.isButton, .isImage])
            .accessibilityHint(Text("sticker.picker.hint", table: .conversation))
            .accessibilityAction { onSend(selection) }
            .accessibilityAction(named: Text("sticker.picker.preview", table: .conversation)) { onPreview(selection) }
    }
}

#Preview("Sticker picker") {
    struct Demo: View {
        @State private var recents: [BrandStickerSelection] = [1, 101, 205, 302].compactMap { id in
            BrandStickerCatalog.sticker(id: id).map { BrandStickerSelection($0, language: .turkish) }
        }

        var body: some View {
            VStack(spacing: 0) {
                Spacer()
                BrandStickerPicker(recents: recents, presentation: .panel(height: 336)) { selection in
                    recents.removeAll { $0.id == selection.id }
                    recents.insert(selection, at: 0)
                }
            }
            .background(Color.u2.bg)
        }
    }
    return Demo()
}
