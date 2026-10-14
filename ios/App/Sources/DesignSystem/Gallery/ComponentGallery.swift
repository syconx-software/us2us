#if DEBUG
    import SwiftUI

    /// Every shared component on one scrolling page, with the design's data
    /// (launch with `-U2Gallery YES`). For eyeballing against the references
    /// in both themes and languages; not part of release builds.
    struct ComponentGallery: View {
        @State private var toggle = true
        @State private var checkbox = true
        @State private var chip = GhostPeriod.oneDay
        @State private var username = "selin_n"
        @State private var search = "deniz"
        @State private var password = "correct horse battery"
        @State private var draft = ""
        @State private var code = 4
        /// `-U2GallerySheet content|fullHeight|floating` opens a sheet at launch.
        @State private var sheetStyle: U2SheetStyle? = {
            switch UserDefaults.standard.string(forKey: "U2GallerySheet") {
            case "content": .content
            case "fullHeight": .fullHeight
            case "floating": .floating
            default: nil
            }
        }()
        @State private var toast: Text?
        /// The built-in sticker gallery over this one (also `-U2Gallery stickers`).
        @State private var showsStickers = false
        /// The preset avatar gallery over this one (also `-U2Gallery avatars`).
        @State private var showsAvatars = false
        @FocusState private var passwordFocus: PasswordFieldFocus?
        @Environment(\.u2Clock) private var clock

        var body: some View {
            U2Screen {
                ScrollView {
                    VStack(alignment: .leading, spacing: 28) {
                        group("Typography") {
                            U2DisplayText(verbatim: "İki uç. Bir hat.", style: .displayHero)
                            U2DisplayText(verbatim: "Kullanıcı adını seç", style: .displayTitleM)
                            Text(verbatim: "Sohbetler").u2Font(.displayTitle)
                            Text(verbatim: "23:41:07").u2Font(.displayCountdownL).foregroundStyle(Color.u2.accent200)
                            Text(verbatim: "us2us iki kişi arasında şifreli bir hat kurar.").u2Font(.bodyL).foregroundStyle(Color.u2.textSecondary)
                            Text(verbatim: "deniz · mert_k · 1 / 3").u2Font(.monoUsername)
                            SectionLabel(Text(verbatim: "Gizlilik · istekler"), horizontalPadding: 0)
                        }
                        group("Buttons") {
                            Button {} label: { U2ButtonLabel(verbatim: "Başla") }.buttonStyle(.u2Primary)
                            Button {} label: { U2ButtonLabel(verbatim: "Kodla aç", glyph: .password) }.buttonStyle(.u2Secondary)
                            Button {} label: { U2ButtonLabel(verbatim: "Yedekten geri yükle") }.buttonStyle(.u2Text)
                            HStack(spacing: 10) {
                                U2GlassIconButton(.qrCode, label: Text(verbatim: "QR")) {}
                                U2GlassIconButton(.notePencil, tint: Color.u2.accent300, label: Text(verbatim: "Yeni")) {}
                                CountBadge(count: 2)
                            }
                            U2FlexRow(spacing: 8) {
                                Button {} label: { Text(verbatim: "Engelle") }.buttonStyle(U2DecisionButtonStyle(kind: .destructive))
                                Button {} label: { Text(verbatim: "Sil") }.buttonStyle(U2DecisionButtonStyle(kind: .neutral))
                                Button {} label: { Text(verbatim: "Kabul et") }.buttonStyle(U2DecisionButtonStyle(kind: .accept)).u2Flex(1.3, border: 2)
                            }
                        }
                        group("Code entry") {
                            CodeDots(filled: code).frame(maxWidth: .infinity)
                            CodeDots(filled: 6, appearance: .error).frame(maxWidth: .infinity)
                            PageDots(count: 3, current: code % 3)
                            NumericKeypad(bottomLeading: .faceID {}) { _ in code = min(6, code + 1) } onDelete: { code = max(0, code - 1) }
                                .frame(maxWidth: .infinity)
                        }
                        group("Controls") {
                            Toggle(isOn: $toggle) { Text(verbatim: "Ghost mod").u2Font(.rowTitleEmphasis) }.toggleStyle(.u2)
                            Toggle(isOn: $checkbox) { Text(verbatim: "Anladım: kodu unutursam verilerim kalıcı olarak silinir.").u2Font(.calloutL) }
                                .toggleStyle(.u2Checkbox)
                            SegmentedChips(options: GhostPeriod.allCases, selection: $chip) { Text($0.chipKey) }
                        }
                        group("Fields") {
                            UsernameField(text: $username, status: .positive)
                            UsernameSearchField(text: $search)
                            PasswordField(text: $password, focus: $passwordFocus, field: .password)
                        }
                        group("Rows & cards") {
                            RequestsCard(count: 2) {}
                            // 4.1 after a restore whose token rotation is unanswered (proposed).
                            RestoreAccessCard {}
                            ForEach(DesignFixtures.chats(now: clock.now, ghostUrgent: false, english: false).prefix(4)) { chat in
                                ChatListRow(chat: chat)
                            }
                            InfoBanner(message: Text(verbatim: "Kabul edene kadar sohbet açılmaz. Okuduğunu ve aktif olduğunu göremezler."))
                            OptionCard(glyph: .fileLock, title: Text(verbatim: "Yedekten geri yükle"), description: Text(verbatim: "Şifreli yedeğin ve parolası varsa sohbetlerin geri gelir.")) {}
                            NoteCard(title: Text(verbatim: "İletilemedi ne demek?"), message: Text(verbatim: "Alıcı 14 gün içinde uygulamayı açmazsa mesaj sunucudan silinir."))
                            GroupedSection(Text(verbatim: "Güvenlik")) {
                                GroupedToggleRow(tile: IconTile(content: .faceID), title: Text(verbatim: "Face ID ile aç"), isOn: $toggle)
                                GroupedSeparator()
                                GroupedNavigationRow(tile: IconTile(content: .glyph(.lockSimple)), title: Text(verbatim: "Otomatik kilit"), detail: Text(verbatim: "Hemen")) {}
                            }
                        }
                        group("Ghost & status") {
                            HStack {
                                GhostPill(ghost: DesignFixtures.chats(now: clock.now, ghostUrgent: false, english: false)[0].ghost)
                                GhostPill(ghost: DesignFixtures.chats(now: clock.now, ghostUrgent: false, english: false)[2].ghost)
                                GhostChip(ghost: DesignFixtures.chats(now: clock.now, ghostUrgent: false, english: false)[0].ghost) {}
                            }
                            ForEach([MessageStatus.sent, .delivered, .read, .failed], id: \.self) { MessageStatusLegendRow(status: $0) }
                            MessageStatusSentence(status: .sent)
                            SystemPill(text: Text(verbatim: "Ghost mod açık · 24 saat"))
                            // 5.1 verification (proposed): the prompt's look and the held-send banner.
                            SystemPill(glyph: .qrCode, text: Text("verify.prompt", table: .conversation))
                                .frame(maxWidth: .infinity)
                            SafetyNumberChangedBanner {}
                        }
                        group("Bubbles") {
                            let messages = DesignFixtures.denizMessages(english: false) + DesignFixtures.mertMessages(english: false)
                            ForEach(Array(messages.enumerated()), id: \.element.id) { index, message in
                                MessageBubbleView(message: message, isTail: ConversationViewModel.isTail(index, in: messages))
                                    .frame(maxWidth: .infinity, alignment: message.isOutgoing ? .trailing : .leading)
                            }
                        }
                        group("Stickers (proposed)") {
                            // Received: a known sticker in its sender's language, an
                            // older sender without one, a newer pack, nothing to draw.
                            let stickers: [MessageContent] = [
                                .sticker(packID: "us2us.words", stickerID: 103, emoji: "🫂", lang: "en"),
                                .sticker(packID: "us2us.words", stickerID: 109, emoji: "👌", lang: ""),
                                .sticker(packID: "us2us.future", stickerID: 9001, emoji: "🫶", lang: ""),
                                .sticker(packID: "us2us.future", stickerID: 9002, emoji: "", lang: ""),
                            ]
                            ForEach(stickers.indices, id: \.self) { index in
                                MessageBubbleView(
                                    message: Message(
                                        id: "gallery.sticker.\(index)", direction: .incoming,
                                        sentAt: DesignFixtures.referenceDate, content: stickers[index]),
                                    isTail: true
                                )
                                .frame(maxWidth: .infinity, alignment: .leading)
                            }
                            Button { showsStickers = true } label: { U2ButtonLabel(verbatim: "Sticker gallery") }
                                .buttonStyle(.u2Secondary)
                        }
                        group("Avatars (proposed)") {
                            HStack(spacing: 12) {
                                Avatar(username: "deniz", palette: .a)
                                Avatar(username: "kaan", palette: .d, picture: .preset(2))
                                Avatar(username: "oya_t", palette: .c, showsOnlineDot: true, picture: DesignFixtures.photoAvatar)
                                Avatar(username: "lale", palette: .b, picture: .preset(14))
                                    .overlay { AvatarLoadingRing(diameter: Avatar.Size.listRow.diameter) }
                            }
                            Button { showsAvatars = true } label: { U2ButtonLabel(verbatim: "Avatar gallery") }
                                .buttonStyle(.u2Secondary)
                        }
                        group("Media states (proposed)") {
                            HStack(spacing: 12) {
                                ForEach(
                                    [MediaAvailability.ready, .remote, .downloading(nil), .downloading(0.4), .unavailable],
                                    id: \.self
                                ) { state in
                                    MediaGlassCircle { MediaStateGlyph(availability: state) }
                                }
                            }
                            .padding(12)
                            .background(RoundedRectangle(cornerRadius: U2Radius.bubbleMedia).fill(Color.u2.mediaViewerBg))
                            HStack(spacing: 18) {
                                ForEach(MediaGlyph.allCases, id: \.self) { MediaGlyphIcon($0, size: 21) }
                            }
                            .foregroundStyle(Color.u2.text)
                            ForEach([MediaAvailability.remote, .downloading(0.62), .unavailable], id: \.self) { state in
                                MessageBubbleView(message: fileMessage(state))
                                    .frame(maxWidth: .infinity, alignment: .trailing)
                            }
                            ForEach([MediaAvailability.preparing, .preparationFailed], id: \.self) { state in
                                MessageBubbleView(message: outgoingVideo(state))
                                    .frame(maxWidth: .infinity, alignment: .trailing)
                            }
                            ForEach([MediaAvailability.preparing, .preparationFailed], id: \.self) { state in
                                MessageBubbleView(message: outgoingPhoto(state))
                                    .frame(maxWidth: .infinity, alignment: .trailing)
                            }
                        }
                        group("Line") {
                            LineMark()
                            ConnectionStatusLine(state: .open)
                            ConnectionStatusLine(state: .revoked)
                            ConnectionLine(ownUsername: "selin_n", ownLabel: Text(verbatim: "sen"), peer: DesignFixtures.deniz, showsSeal: true)
                            QRCodeCard(payload: SafetyQRCodec.encode(Data("0822378626740242326312493".utf8)), label: Text(verbatim: "QR")).frame(maxWidth: .infinity)
                        }
                        group("Composer & sheet") {
                            Composer(text: $draft, onSend: { draft = "" }, bottomPadding: 0)
                            Button { sheetStyle = .content } label: { U2ButtonLabel(verbatim: "Sheet") }.buttonStyle(.u2Secondary)
                            Button { toast = Text(verbatim: "Kaydedildi") } label: { U2ButtonLabel(verbatim: "Toast") }.buttonStyle(.u2Secondary)
                        }
                    }
                    .padding(EdgeInsets(top: 70, leading: 16, bottom: 60, trailing: 16))
                }
            }
            .u2Sheet(
                isPresented: Binding(get: { sheetStyle != nil }, set: { if !$0 { sheetStyle = nil } }),
                style: sheetStyle ?? .content
            ) {
                GhostSettingsSheet(
                    chat: DesignFixtures.chats(now: clock.now, ghostUrgent: false, english: false)[0],
                    onSave: { _, _, _ in sheetStyle = nil }, onClose: { sheetStyle = nil })
            }
            .u2Toast($toast)
            .overlay {
                if showsStickers {
                    BrandStickerGallery()
                        .overlay(alignment: .topTrailing) {
                            U2GlassIconButton(.x, label: Text("a11y.close")) { showsStickers = false }
                                .padding(.top, 54)
                                .padding(.trailing, 16)
                        }
                } else if showsAvatars {
                    BrandAvatarGallery()
                        .overlay(alignment: .topTrailing) {
                            U2GlassIconButton(.x, label: Text("a11y.close")) { showsAvatars = false }
                                .padding(.top, 54)
                                .padding(.trailing, 16)
                        }
                }
            }
        }

        /// The 5.2 file bubble in another state.
        private func fileMessage(_ state: MediaAvailability) -> Message {
            var message = DesignFixtures.mertMessages(english: false).first { $0.id == "m1" }!
            message.mediaAvailability = state
            return message
        }

        /// A photo of a pick from “+” while it is prepared, or after preparing it failed.
        private func outgoingPhoto(_ state: MediaAvailability) -> Message {
            var message = Message(
                id: "gallery.photo.\(state)", direction: .outgoing, sentAt: DesignFixtures.referenceDate,
                content: .photo(.designArtwork(.nightPier), caption: nil),
                status: state == .preparationFailed ? .failed : .sending)
            message.mediaAvailability = state
            return message
        }

        /// A video sent from “+” while it is prepared, or after preparing it failed.
        private func outgoingVideo(_ state: MediaAvailability) -> Message {
            var message = Message(
                id: "gallery.video.\(state)", direction: .outgoing, sentAt: DesignFixtures.referenceDate,
                content: .video(.designArtwork(.sunsetVideo), duration: 18, byteCount: 0),
                status: state == .preparationFailed ? .failed : .sending)
            message.mediaAvailability = state
            return message
        }

        private func group<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
            VStack(alignment: .leading, spacing: 14) {
                Text(verbatim: title).u2Font(.monoKicker).foregroundStyle(Color.u2.accent300)
                content()
            }
        }
    }
#endif
