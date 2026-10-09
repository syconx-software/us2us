import SwiftUI

/// “Davet et / Invite” (proposed; the design has no screen for it, so it is
/// drawn in the design's language): the sheet bar, the ready-made message as
/// the outgoing bubble the friend will get (editable), the channels as a C13
/// card on `bg` (as in 4.2), and what the app does not learn.
///
/// Shown as a full-height `u2Sheet` (7.1 › “Arkadaşını davet et”, the
/// `-U2Route invite` harness) or pushed inside the 4.2 sheet after an exact
/// search found nothing (with “Geri”).
///
/// Nothing here touches the address book or the relay: the user picks the
/// recipient in Messages, Mail, WhatsApp or the share sheet; the message
/// carries only the username and `https://us2us.app/u/<username>`.
struct InviteView: View {
    let username: String
    var closeTitle = Text("common.close")
    let onClose: () -> Void

    @Environment(\.locale) private var locale

    /// The user's own text; nil = the template in the current language.
    @State private var editedText: String?
    @State private var isEditing = false
    @State private var draft = ""
    @State private var status: InviteStatus?
    @State private var statusTask: Task<Void, Never>?
    @FocusState private var isEditorFocused: Bool

    #if DEBUG
        /// Screenshot harness (`-U2Route invite:<state>`).
        var debug = InviteDebugState()
    #endif

    var body: some View {
        VStack(spacing: 0) {
            SheetBar(title: Text("invite.title", table: .invite), cancelTitle: closeTitle, onCancel: close)
                .accessibilityIdentifier("invite.bar")
            ScrollView {
                VStack(spacing: 0) {
                    messageSection
                    channelsSection
                        .padding(.top, 22)
                    privacyNote
                        .padding(EdgeInsets(top: 16, leading: 8, bottom: 0, trailing: 8))
                }
                .padding(EdgeInsets(top: 12, leading: 16, bottom: 24, trailing: 16))
            }
            .scrollIndicators(.hidden)
            .scrollBounceBehavior(.basedOnSize)
            .scrollDismissesKeyboard(.interactively)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("invite.sheet")
        .onDisappear { statusTask?.cancel() }
        #if DEBUG
            .task { applyDebug() }
        #endif
    }

    // MARK: Message

    /// The message as it would be sent now (the draft while editing).
    private var message: InviteMessage? {
        guard var message = InviteMessage.make(username: username, locale: locale) else { return nil }
        if isEditing {
            message.text = sendableDraft ?? message.text
        } else if let editedText {
            message.text = editedText
        }
        return message
    }

    private var defaultText: String {
        InviteMessage.make(username: username, locale: locale)?.text ?? ""
    }

    /// The draft, unless it is empty (an empty invitation is never sent).
    private var sendableDraft: String? {
        draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : draft
    }

    /// “Arkadaşına gidecek mesaj” + Düzenle / Bitti, then the bubble or the editor.
    private var messageSection: some View {
        VStack(alignment: .leading, spacing: U2Spacing.sectionLabelGap) {
            messageHeader
            messageCard
        }
    }

    private var isDraftChanged: Bool { isEditing && draft != defaultText }

    private var messageHeader: some View {
        HStack(spacing: 4) {
            SectionLabel(Text("invite.message.label", table: .invite))
            if isDraftChanged {
                Button {
                    resetDraft()
                } label: {
                    Text("invite.reset", table: .invite)
                }
                .buttonStyle(U2TextButtonStyle(tone: .quiet, height: 32, fillsWidth: false))
                .accessibilityIdentifier("invite.reset")
                .transition(.u2Fade)
            }
            editButton
        }
        .u2Animation(U2Motion.state, value: isDraftChanged)
    }

    private var editButton: some View {
        let key: LocalizedStringKey = isEditing ? "invite.done" : "invite.edit"
        return Button {
            if isEditing { endEditing() } else { beginEditing() }
        } label: {
            Text(key, table: .invite)
                .contentTransition(.opacity)
        }
        .buttonStyle(U2TextButtonStyle(tone: .accent, height: 32, fillsWidth: false))
        .padding(.trailing, 16)
        .accessibilityIdentifier(isEditing ? "invite.done" : "invite.edit")
    }

    private var messageCard: some View {
        ZStack(alignment: .topTrailing) {
            if isEditing {
                editor
                    .transition(.u2Fade)
            } else if let message {
                preview(message)
                    .transition(.u2Fade)
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .trailing)
        .background(RoundedRectangle(cornerRadius: U2Radius.cardL, style: .continuous).fill(Color.u2.bg))
        .u2Animation(U2Motion.state, value: isEditing)
    }

    /// The outgoing bubble (C15, tail), the link underlined.
    private func preview(_ message: InviteMessage) -> some View {
        MessageBubble(direction: .outgoing, isTail: true) {
            Text(previewText(message))
                .u2Font(.bubble)
                .foregroundStyle(Color.u2.onBubbleOutgoing)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .trailing)
        .contentTransition(.opacity)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("a11y.invite.preview", table: .invite))
        .accessibilityValue(Text(verbatim: message.text))
        .accessibilityIdentifier("invite.preview")
    }

    private func previewText(_ message: InviteMessage) -> AttributedString {
        var text = AttributedString(message.text)
        if let range = text.range(of: message.link.absoluteString) {
            text[range].underlineStyle = .single
        }
        return text
    }

    private var editor: some View {
        let shape = RoundedRectangle(cornerRadius: U2Radius.field, style: .continuous)
        return TextEditor(text: $draft)
            .focused($isEditorFocused)
            .u2Font(.bubble, matchLineBox: false)
            .foregroundStyle(Color.u2.text)
            .tint(Color.u2.accent)
            .scrollContentBackground(.hidden)
            .frame(minHeight: 168, maxHeight: 280)
            .padding(EdgeInsets(top: 6, leading: 9, bottom: 6, trailing: 9))
            .background(shape.fill(Color.u2.surface))
            .overlay(shape.strokeBorder(Color.u2.accent, lineWidth: 1.5).opacity(isEditorFocused ? 1 : 0))
            .u2Animation(U2Motion.state, value: isEditorFocused)
            .onChange(of: draft) { _, new in
                let clamped = InviteMessage.clamped(new)
                if clamped != new { draft = clamped }
            }
            .accessibilityLabel(Text("a11y.invite.editor", table: .invite))
            .accessibilityIdentifier("invite.editor")
    }

    // MARK: Channels

    private var channelsSection: some View {
        VStack(alignment: .leading, spacing: U2Spacing.sectionLabelGap) {
            SectionLabel(Text("invite.channels.label", table: .invite))
            GroupedCard(fill: Color.u2.bg) {
                ForEach(Array(InviteChannel.allCases.enumerated()), id: \.element) { index, channel in
                    if index > 0 { GroupedSeparator() }
                    InviteChannelRow(
                        channel: channel, subtitle: subtitle(for: channel), isDone: status?.channel == channel,
                        action: { perform(channel) })
                }
            }
        }
    }

    private func subtitle(for channel: InviteChannel) -> InviteChannelRow.Subtitle? {
        if let status, status.channel == channel {
            return .status(status.outcome == .copied ? Text("invite.copied", table: .invite) : Text("invite.sent", table: .invite))
        }
        if channel == .copyLink, let link = InviteLink.displayString(for: username) {
            return .link(link)
        }
        return nil
    }

    /// The privacy line (as the 4.2 info row: glyph 18 + `callout`).
    private var privacyNote: some View {
        HStack(alignment: .top, spacing: 12) {
            PhosphorIcon(.shieldCheck, size: 18)
                .foregroundStyle(Color.u2.accent300)
                .padding(.top, 1)
            Text("invite.privacy", table: .invite)
                .u2Font(.callout)
                .foregroundStyle(Color.u2.textSecondary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)
        }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("invite.privacy")
    }

    // MARK: Actions

    private func beginEditing() {
        draft = editedText ?? defaultText
        withU2Animation(U2Motion.state) { isEditing = true }
        Task { @MainActor in
            // After the cross-fade has started, so the keyboard and the fade do not stall each other.
            try? await Task.sleep(for: .milliseconds(200))
            isEditorFocused = true
        }
    }

    private func endEditing() {
        isEditorFocused = false
        let text = sendableDraft
        editedText = text == nil || text == defaultText ? nil : text
        withU2Animation(U2Motion.state) { isEditing = false }
    }

    private func resetDraft() {
        withU2Animation(U2Motion.state) { draft = defaultText }
    }

    private func perform(_ channel: InviteChannel) {
        guard let message else { return }
        if isEditing { endEditing() }
        InviteSender.send(channel, message: message) { outcome in
            show(outcome, for: channel)
        }
    }

    private func show(_ outcome: InviteOutcome, for channel: InviteChannel) {
        guard outcome == .sent || outcome == .copied else { return }
        if outcome == .copied { U2Haptics.success() }
        withU2Animation(U2Motion.state) { status = InviteStatus(channel: channel, outcome: outcome) }
        let announcement = StringTable.invite.string(outcome == .copied ? "invite.copied" : "invite.sent", locale: locale)
        AccessibilityNotification.Announcement(announcement).post()
        statusTask?.cancel()
        statusTask = Task { @MainActor in
            try? await Task.sleep(for: .seconds(3))
            guard !Task.isCancelled else { return }
            withU2Animation(U2Motion.state) { status = nil }
        }
    }

    private func close() {
        isEditorFocused = false
        onClose()
    }

    #if DEBUG
        private func applyDebug() {
            if let text = debug.editedText { editedText = text }
            if debug.startsEditing { beginEditing() }
            if let channel = debug.status {
                status = InviteStatus(channel: channel, outcome: channel == .copyLink ? .copied : .sent)
            }
        }
    #endif
}

/// A transient result shown on the channel's own row.
struct InviteStatus: Equatable {
    let channel: InviteChannel
    let outcome: InviteOutcome
}

#if DEBUG
    /// What the `-U2Route invite:<state>` harness opens the sheet with.
    struct InviteDebugState {
        var startsEditing = false
        var editedText: String?
        var status: InviteChannel?
    }
#endif

/// The invitation in its own full-height sheet (7.1, harness).
struct InviteSheet: View {
    let username: String
    let onClose: () -> Void
    #if DEBUG
        var debug = InviteDebugState()
    #endif

    var body: some View {
        #if DEBUG
            InviteView(username: username, closeTitle: Text("common.close"), onClose: onClose, debug: debug)
        #else
            InviteView(username: username, closeTitle: Text("common.close"), onClose: onClose)
        #endif
    }
}

// MARK: - Channel row

/// A C13 row (tile 30 · gap 13 · title `rowTitle` + subtitle · chevron,
/// ≥ 48 high, 62 with a subtitle) for one channel. It mirrors
/// `GroupedNavigationRow`, with a tile that can also show the two glyphs the
/// app's Phosphor set does not bundle (WhatsApp, envelope).
struct InviteChannelRow: View {
    enum Subtitle {
        /// “us2us.app/u/selin.n” in mono.
        case link(String)
        /// “Kopyalandı …” / “Gönderildi”.
        case status(Text)
    }

    let channel: InviteChannel
    let subtitle: Subtitle?
    let isDone: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: U2Spacing.rowGap) {
                InviteTile(glyph: channel.glyph)
                VStack(alignment: .leading, spacing: 2) {
                    channel.title
                        .u2Font(.rowTitle)
                        .foregroundStyle(Color.u2.text)
                    subtitleView
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                ZStack {
                    if isDone {
                        PhosphorIcon(.checkCircle, weight: .fill, size: 18)
                            .foregroundStyle(Color.u2.online)
                            .transition(.u2Pop)
                    } else if channel != .copyLink {
                        DisclosureDetail()
                            .transition(.u2Fade)
                    }
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, subtitle == nil ? 0 : 9)
            .frame(minHeight: 48)
            .contentShape(Rectangle())
            .u2Animation(U2Motion.state, value: isDone)
        }
        .buttonStyle(GroupedRowButtonStyle())
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
        .accessibilityHint(channel.hint)
        .accessibilityIdentifier("invite.channel.\(channel.rawValue)")
    }

    @ViewBuilder private var subtitleView: some View {
        switch subtitle {
        case .link(let link):
            Text(verbatim: link)
                .u2Font(.monoMeta)
                .foregroundStyle(Color.u2.textSecondary)
                .lineLimit(1)
                .truncationMode(.middle)
                .transition(.u2Fade)
        case .status(let text):
            text
                .u2Font(.captionCompact)
                .foregroundStyle(Color.u2.textSecondary)
                .transition(.u2Fade)
        case nil:
            EmptyView()
        }
    }
}

/// The C13 icon tile (30 × 30 r8 `accent900`, inner 1 pt `accent800`, 18 pt
/// `accent300` glyph) for a bundled Phosphor glyph or one of this feature's
/// own (`Invite.xcassets`: `ph-whatsapp-logo`, `ph-envelope-simple`,
/// Phosphor 2.1.1 regular, MIT).
struct InviteTile: View {
    enum Glyph: Equatable {
        case phosphor(PhosphorGlyph)
        case asset(String)
    }

    let glyph: Glyph

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: U2Radius.iconTile, style: .continuous)
        ZStack {
            shape.fill(Color.u2.accent900)
            shape.strokeBorder(Color.u2.accent800, lineWidth: 1)
            Group {
                switch glyph {
                case .phosphor(let glyph):
                    PhosphorIcon(glyph, size: 18)
                case .asset(let name):
                    Image(name)
                        .renderingMode(.template)
                        .resizable()
                        .interpolation(.high)
                        .frame(width: 18, height: 18)
                }
            }
            .foregroundStyle(Color.u2.accent300)
        }
        .frame(width: U2Size.iconTileSettings, height: U2Size.iconTileSettings)
        .accessibilityHidden(true)
    }
}

extension InviteChannel {
    var glyph: InviteTile.Glyph {
        switch self {
        case .sms: .phosphor(.chatCircleDots)
        case .whatsApp: .asset("ph-whatsapp-logo")
        case .email: .asset("ph-envelope-simple")
        case .more: .phosphor(.export)
        case .copyLink: .phosphor(.copy)
        }
    }

    var title: Text {
        switch self {
        case .sms: Text("invite.channel.sms", table: .invite)
        case .whatsApp: Text("invite.channel.whatsapp", table: .invite)
        case .email: Text("invite.channel.email", table: .invite)
        case .more: Text("invite.channel.more", table: .invite)
        case .copyLink: Text("invite.channel.copy", table: .invite)
        }
    }

    var hint: Text {
        switch self {
        case .sms: Text("a11y.invite.sms", table: .invite)
        case .whatsApp: Text("a11y.invite.whatsapp", table: .invite)
        case .email: Text("a11y.invite.email", table: .invite)
        case .more: Text("a11y.invite.more", table: .invite)
        case .copyLink: Text("a11y.invite.copy", table: .invite)
        }
    }
}

#Preview("Davet et") {
    let model = AppModel.preview()
    return Color.u2.bg
        .u2Sheet(isPresented: .constant(true)) {
            InviteSheet(username: "selin.n", onClose: {})
        }
        .environment(model)
        .environment(\.locale, model.locale)
}
