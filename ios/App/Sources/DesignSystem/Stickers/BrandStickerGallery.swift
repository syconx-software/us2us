#if DEBUG
    import SwiftUI

    /// Every built-in sticker in both languages and both themes, for reviewing
    /// the art: size chips (72 = a picker cell … 160 = the largest in the
    /// chat), a replay of the entrance, the picker in both presentations and
    /// the fallbacks of a received sticker. Not part of release builds.
    struct BrandStickerGallery: View {
        private static let sizes: [CGFloat] = [72, 96, 132, 160]

        @State private var side: CGFloat = 132
        @State private var replay = 0
        @State private var showsSheet = false
        @State private var lastSent: BrandStickerSelection?

        var body: some View {
            U2Screen {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 30) {
                        controls
                        ForEach(BrandStickerCatalog.packs) { pack in
                            VStack(alignment: .leading, spacing: 18) {
                                Text(verbatim: "\(pack.id) · \(BrandStickerCatalog.stickers(in: pack.id).count)")
                                    .u2Font(.monoKicker)
                                    .foregroundStyle(Color.u2.accent300)
                                ForEach(BrandStickerCatalog.stickers(in: pack.id)) { sticker in
                                    row(sticker)
                                }
                            }
                        }
                        fallbacks
                        pickerSamples
                    }
                    .padding(EdgeInsets(top: 70, leading: 16, bottom: 60, trailing: 16))
                }
            }
            .u2Sheet(isPresented: $showsSheet, style: .content) {
                BrandStickerPicker(
                    recents: lastSent.map { [$0] } ?? [],
                    onSend: { selection in
                        lastSent = selection
                        showsSheet = false
                    },
                    onClose: { showsSheet = false })
            }
        }

        private var controls: some View {
            VStack(alignment: .leading, spacing: 12) {
                Text(verbatim: "Stickers · \(BrandStickerCatalog.all.count)").u2Font(.displayTitle)
                SegmentedChips(options: Self.sizes, selection: $side) { Text(verbatim: "\(Int($0)) pt") }
                HStack(spacing: 10) {
                    Button { replay += 1 } label: { U2ButtonLabel(verbatim: "Replay entrance") }
                        .buttonStyle(.u2Secondary)
                    Button { showsSheet = true } label: { U2ButtonLabel(verbatim: "Picker sheet") }
                        .buttonStyle(.u2Secondary)
                }
                if let lastSent {
                    Text(verbatim: "Sent: \(lastSent.sticker.packID) / \(lastSent.sticker.id) / lang “\(lastSent.wireLanguage)”")
                        .u2Font(.monoMeta)
                        .foregroundStyle(Color.u2.textSecondary)
                }
            }
        }

        /// id · key · emoji, both labels, then dark (TR, EN) over light (TR, EN).
        private func row(_ sticker: BrandSticker) -> some View {
            VStack(alignment: .leading, spacing: 8) {
                Text(verbatim: "\(sticker.id) · \(sticker.key) · \(sticker.emoji)")
                    .u2Font(.monoUsername)
                    .foregroundStyle(Color.u2.text)
                Text(verbatim: "TR “\(sticker.label.turkish)” · EN “\(sticker.label.english)”")
                    .u2Font(.captionTight)
                    .foregroundStyle(Color.u2.textSecondary)
                ForEach([ColorScheme.dark, .light], id: \.self) { scheme in
                    HStack(spacing: 12) {
                        ForEach(BrandStickerLanguage.allCases) { language in
                            VStack(spacing: 4) {
                                BrandStickerView(sticker: sticker, language: language, animatesEntrance: true)
                                    .id(replay)
                                    .frame(width: side, height: side)
                                Text(verbatim: "\(language.wireCode) · \(scheme == .dark ? "dark" : "light")")
                                    .u2Font(.monoTag)
                                    .foregroundStyle(Color.u2.textSecondary)
                            }
                        }
                    }
                    .padding(10)
                    .background(RoundedRectangle(cornerRadius: U2Radius.card, style: .continuous).fill(Color.u2.bg))
                    .environment(\.colorScheme, scheme)
                }
            }
        }

        /// What a received sticker falls back to (PROTOCOL.md › Stickers).
        private var fallbacks: some View {
            VStack(alignment: .leading, spacing: 12) {
                Text(verbatim: "Received fallbacks").u2Font(.monoKicker).foregroundStyle(Color.u2.accent300)
                let cases: [(String, BrandStickerResolution)] = [
                    ("words.ok, no lang (older sender)", BrandStickerCatalog.resolve(packID: "us2us.words", stickerID: 109, emoji: "👌", lang: "", fallback: .english)),
                    ("words.hello, lang “de”", BrandStickerCatalog.resolve(packID: "us2us.words", stickerID: 101, emoji: "👋", lang: "de", fallback: .turkish)),
                    ("unknown id 999", BrandStickerCatalog.resolve(packID: "us2us.cups", stickerID: 999, emoji: "🫶", lang: "tr", fallback: .turkish)),
                    ("legacy us2us.text", BrandStickerCatalog.resolve(packID: "us2us.text", stickerID: 0, emoji: "tamam!", lang: "", fallback: .turkish)),
                ]
                ForEach(cases.indices, id: \.self) { index in
                    let (title, resolution) = cases[index]
                    HStack(spacing: 14) {
                        Group {
                            switch resolution {
                            case .sticker(let selection): BrandStickerView(selection)
                            case .emoji(let emoji): StickerEmojiFallback(emoji: emoji)
                            case .legacyText(let text):
                                Text(verbatim: text).u2Font(.displaySticker).foregroundStyle(Color.u2.accent900)
                                    .padding(10).background(RoundedRectangle(cornerRadius: U2Radius.sticker).fill(Color.u2.accent200))
                            case .unavailable: Text(verbatim: "—")
                            }
                        }
                        .frame(width: 96, height: 96)
                        Text(verbatim: title).u2Font(.captionTight).foregroundStyle(Color.u2.textSecondary)
                    }
                }
            }
        }

        private var pickerSamples: some View {
            VStack(alignment: .leading, spacing: 12) {
                Text(verbatim: "Picker · keyboard panel").u2Font(.monoKicker).foregroundStyle(Color.u2.accent300)
                BrandStickerPicker(
                    recents: [1, 103, 302].compactMap { BrandStickerCatalog.sticker(id: $0).map { BrandStickerSelection($0, language: .english) } },
                    presentation: .panel(height: 336), onSend: { lastSent = $0 })
                    .padding(.horizontal, -16)
            }
        }
    }

    #Preview("Sticker gallery") {
        BrandStickerGallery()
    }
#endif
