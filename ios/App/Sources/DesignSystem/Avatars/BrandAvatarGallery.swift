#if DEBUG
    import SwiftUI

    /// Every preset avatar at 28, 44 and 96 pt in both themes, for reviewing
    /// the art, next to the initial avatar at C12's sizes; `Avatar` drawing
    /// a preset, a photo and the fallbacks, with a search result's loading
    /// ring; how a stored choice is read back; and the full-screen picker
    /// (`-U2Gallery avatars`). Not part of release builds.
    struct BrandAvatarGallery: View {
        private static let sizes: [CGFloat] = [28, 44, 96]

        @State private var showsPicker = false
        @State private var choice = AvatarChoice.default
        @State private var lastAction = ""

        var body: some View {
            U2Screen {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 30) {
                        controls
                        ForEach([ColorScheme.dark, .light], id: \.self) { scheme in
                            grid(scheme)
                        }
                        VStack(alignment: .leading, spacing: 22) {
                            kicker("Presets · 28 / 44 / 96 pt")
                            ForEach(BrandAvatarCatalog.all) { preset in row(preset) }
                        }
                        avatarSizes
                        inUse
                        storedChoices
                    }
                    .padding(EdgeInsets(top: 70, leading: 16, bottom: 60, trailing: 16))
                }
            }
            .u2FadeCover(isPresented: $showsPicker, onEscape: { showsPicker = false }) {
                AvatarPresetPicker(
                    username: "selin_n", current: choice,
                    onChoose: { choice = $0 },
                    onUploadPhoto: { lastAction = "upload a photo" },
                    onTakePhoto: { lastAction = "camera" },
                    onClose: { showsPicker = false })
            }
        }

        private var controls: some View {
            VStack(alignment: .leading, spacing: 12) {
                Text(verbatim: "Avatars · \(BrandAvatarCatalog.all.count)").u2Font(.displayTitle)
                Button {
                    showsPicker = true
                } label: {
                    U2ButtonLabel(verbatim: "Picker")
                }
                .buttonStyle(.u2Secondary)
                Text(verbatim: "Choice: “\(choice.storageValue)”\(lastAction.isEmpty ? "" : " · last: \(lastAction)")")
                    .u2Font(.monoMeta)
                    .foregroundStyle(Color.u2.textSecondary)
            }
        }

        private func kicker(_ title: String) -> some View {
            Text(verbatim: title).u2Font(.monoKicker).foregroundStyle(Color.u2.accent300)
        }

        /// The whole set at the chat list's size, side by side.
        private func grid(_ scheme: ColorScheme) -> some View {
            VStack(alignment: .leading, spacing: 10) {
                kicker("All · 52 pt · \(scheme == .dark ? "dark" : "light")")
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 6), spacing: 8) {
                    ForEach(BrandAvatarCatalog.all) { preset in
                        BrandAvatarView(preset: preset, size: .listRow)
                    }
                }
                .padding(12)
                .background(RoundedRectangle(cornerRadius: U2Radius.card, style: .continuous).fill(Color.u2.bg))
                .environment(\.colorScheme, scheme)
            }
        }

        /// id · key · backdrop, both labels, then dark over light at 28 / 44 / 96.
        private func row(_ preset: BrandAvatarPreset) -> some View {
            VStack(alignment: .leading, spacing: 8) {
                Text(verbatim: "\(preset.id) · \(preset.key) · \(preset.backdrop.rawValue)")
                    .u2Font(.monoUsername)
                    .foregroundStyle(Color.u2.text)
                Text(verbatim: "TR “\(preset.label.turkish)” · EN “\(preset.label.english)”")
                    .u2Font(.captionTight)
                    .foregroundStyle(Color.u2.textSecondary)
                ForEach([ColorScheme.dark, .light], id: \.self) { scheme in
                    HStack(alignment: .bottom, spacing: 14) {
                        ForEach(Self.sizes, id: \.self) { side in
                            BrandAvatarView(preset: preset, size: side)
                        }
                        Spacer(minLength: 0)
                        Text(verbatim: scheme == .dark ? "dark" : "light")
                            .u2Font(.monoTag)
                            .foregroundStyle(Color.u2.textSecondary)
                    }
                    .padding(10)
                    .background(RoundedRectangle(cornerRadius: U2Radius.card, style: .continuous).fill(Color.u2.bg))
                    .environment(\.colorScheme, scheme)
                }
            }
        }

        /// A preset and the initial at every C12 size.
        private var avatarSizes: some View {
            let sizes: [Avatar.Size] = [.header, .chatsSelf, .request, .verify, .settings, .listRow, .profile]
            return VStack(alignment: .leading, spacing: 12) {
                kicker("C12 sizes · 38 … 64")
                ForEach([BrandAvatarCatalog.all[1], BrandAvatarCatalog.all[7]]) { preset in
                    HStack(alignment: .bottom, spacing: 8) {
                        ForEach(sizes.indices, id: \.self) { index in
                            BrandAvatarView(preset: preset, size: sizes[index])
                        }
                    }
                }
                HStack(alignment: .bottom, spacing: 8) {
                    ForEach(sizes.indices, id: \.self) { index in
                        Avatar(username: "selin_n", palette: .d, size: sizes[index])
                    }
                }
            }
        }

        /// `Avatar` with a picture, as the screens draw it: a preset, the
        /// fixtures' photo, an unknown preset and a photo that does not decode
        /// (both the initial), and 4.2's loading ring.
        private var inUse: some View {
            let pictures: [(String, AvatarPicture)] = [
                ("preset 2", .preset(2)),
                ("photo", DesignFixtures.photoAvatar),
                ("preset 999", .preset(999)),
                ("bad photo", .photo(AvatarPhotoData(data: Data([0xFF, 0xD8, 0xFF, 0x00]), contentType: "image/jpeg"))),
            ]
            return VStack(alignment: .leading, spacing: 12) {
                kicker("Avatar · picture: · 38 … 64")
                ForEach(pictures, id: \.0) { name, picture in
                    HStack(alignment: .bottom, spacing: 8) {
                        Avatar(username: "selin_n", palette: .d, size: .header, picture: picture)
                        Avatar(username: "selin_n", palette: .d, size: .listRow, showsOnlineDot: true, picture: picture)
                        Avatar(username: "selin_n", palette: .d, size: .profile, picture: picture)
                        Text(verbatim: name)
                            .u2Font(.captionTight)
                            .foregroundStyle(Color.u2.textSecondary)
                    }
                }
                HStack(spacing: 14) {
                    Avatar(username: "lale", palette: .forUsername("lale"), size: .listRow)
                        .overlay { AvatarLoadingRing(diameter: Avatar.Size.listRow.diameter) }
                    Text(verbatim: "4.2 · loading")
                        .u2Font(.captionTight)
                        .foregroundStyle(Color.u2.textSecondary)
                }
            }
        }

        /// How stored values read back (a newer build's preset falls back to the initial).
        private var storedChoices: some View {
            VStack(alignment: .leading, spacing: 10) {
                kicker("Stored choices")
                ForEach(["initials", "preset:2", "preset:999", "photo", "preset:abc", ""], id: \.self) { stored in
                    let choice = AvatarChoice(storageValue: stored)
                    HStack(spacing: 14) {
                        Group {
                            switch choice.drawn {
                            case .preset:
                                if let preset = choice.preset { BrandAvatarView(preset: preset, size: .request) }
                            case .initials, .photo:
                                Avatar(username: "selin_n", palette: .d, size: .request)
                            }
                        }
                        .frame(width: 44, height: 44)
                        Text(verbatim: "“\(stored)” → \(choice.storageValue) → draws \(choice.drawn.storageValue)")
                            .u2Font(.captionTight)
                            .foregroundStyle(Color.u2.textSecondary)
                    }
                }
            }
        }
    }

    #Preview("Avatar gallery") {
        BrandAvatarGallery()
    }
#endif
