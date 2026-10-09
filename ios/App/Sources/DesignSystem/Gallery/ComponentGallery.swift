#if DEBUG
    import SwiftUI

    /// Every shared component on one scrolling page, with the design's data
    /// (launch with `-U2Gallery YES`). For eyeballing against the references
    /// in both themes and languages; not part of release builds.
    struct ComponentGallery: View {
        @State private var toggle = true
        @State private var checkbox = true
        @State private var chip = GhostPeriod.oneDay
        @State private var username = "selin.n"
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
                            Text(verbatim: "deniz · mert.k · 1 / 3").u2Font(.monoUsername)
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
                        }
                        group("Bubbles") {
                            let messages = DesignFixtures.denizMessages(english: false) + DesignFixtures.mertMessages(english: false)
                            ForEach(Array(messages.enumerated()), id: \.element.id) { index, message in
                                MessageBubbleView(message: message, isTail: ConversationViewModel.isTail(index, in: messages))
                                    .frame(maxWidth: .infinity, alignment: message.isOutgoing ? .trailing : .leading)
                            }
                        }
                        group("Line") {
                            LineMark()
                            ConnectionStatusLine(state: .open)
                            ConnectionLine(ownUsername: "selin.n", ownLabel: Text(verbatim: "sen"), peer: DesignFixtures.deniz, showsSeal: true)
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
        }

        private func group<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
            VStack(alignment: .leading, spacing: 14) {
                Text(verbatim: title).u2Font(.monoKicker).foregroundStyle(Color.u2.accent300)
                content()
            }
        }
    }
#endif
