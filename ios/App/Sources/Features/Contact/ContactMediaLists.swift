import SwiftUI

// MARK: - Fotoğraflar

/// The chat's photos and videos (proposed): three square tiles a row, 3 pt
/// apart, r8, 14 pt gutters (the chat's), newest first; a video carries
/// its length on the 5.2 glass label (▶ 0:18); one still on the relay or on
/// its way shows the bubble's state circle. The next page loads as the end
/// comes near. A tap opens the viewer or the player, swiping through them.
struct ContactPhotosGrid: View {
    let model: ContactMediaModel
    @Environment(\.u2SafeArea) private var safeArea

    private static let spacing: CGFloat = 3
    private let columns = Array(repeating: GridItem(.flexible(), spacing: ContactPhotosGrid.spacing), count: 3)

    var body: some View {
        let items = model.items(.visual)
        let page = model.page(.visual)
        Group {
            if items.isEmpty {
                if page.hasLoaded && !page.isLoading {
                    ContactEmptyState(
                        tile: IconTile(content: .glyph(.image), size: 52, cornerRadius: U2Radius.iconTileXL, glyphSize: 26),
                        title: Text("photos.empty.title", table: .contact),
                        message: Text("photos.empty.body \(model.peerUsername)", table: .contact))
                    .accessibilityIdentifier("contact.photos.empty")
                } else {
                    Color.clear
                }
            } else {
                ScrollView {
                    LazyVGrid(columns: columns, spacing: Self.spacing) {
                        ForEach(items) { message in
                            ContactMediaTile(message: message, peer: model.peerUsername) { model.openVisual(message) }
                                .onAppear { model.itemAppeared(message.id, in: .visual) }
                        }
                    }
                    .padding(.horizontal, U2Spacing.chatGutter)
                    .padding(.top, 16)
                    if page.isLoading && page.hasLoaded {
                        ContactLoadingFooter()
                    }
                    Color.clear.frame(height: safeArea.bottom(24))
                }
                .scrollIndicators(.hidden)
                .accessibilityIdentifier("contact.photos")
            }
        }
        .task { await model.loadFirstPageIfNeeded(.visual) }
    }
}

/// One square of the grid.
struct ContactMediaTile: View {
    let message: Message
    /// Who sent a received item (the label names the sender).
    let peer: String
    let onOpen: () -> Void
    @Environment(\.locale) private var locale

    var body: some View {
        Button(action: onOpen) {
            Color.u2.surface
                .aspectRatio(1, contentMode: .fit)
                .overlay {
                    GeometryReader { proxy in
                        if let source {
                            BubbleMediaView(source: source, size: proxy.size)
                                .frame(width: proxy.size.width, height: proxy.size.height)
                        }
                    }
                }
                .overlay {
                    if !message.mediaAvailability.isReady {
                        MediaGlassCircle(diameter: 36) {
                            MediaStateGlyph(availability: message.mediaAvailability, diameter: 36, readyGlyph: .none)
                        }
                        .transition(.u2Fade)
                    }
                }
                .overlay(alignment: .bottomLeading) {
                    if let duration {
                        HStack(spacing: 3) {
                            PhosphorIcon(.play, weight: .fill, size: 9)
                            Text(verbatim: U2Format.voiceDuration(duration))
                                .u2Font(.monoTag, matchLineBox: false)
                        }
                        .foregroundStyle(Color.u2.onMedia)
                        .padding(EdgeInsets(top: 2, leading: 5, bottom: 2, trailing: 6))
                        .background(RoundedRectangle(cornerRadius: 6, style: .continuous).fill(Color.u2.onMediaGlass(0.55)))
                        .padding(6)
                    }
                }
                .clipShape(RoundedRectangle(cornerRadius: U2Radius.iconTile, style: .continuous))
                .contentShape(RoundedRectangle(cornerRadius: U2Radius.iconTile, style: .continuous))
                .u2Animation(U2Motion.state, value: message.mediaAvailability.isReady)
        }
        .buttonStyle(CardButtonStyle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(label)
        .accessibilityValue(MediaAccessibility.state(message.mediaAvailability))
        .accessibilityAddTraits(.isButton)
        .accessibilityIdentifier("contact.tile.\(message.id)")
    }

    private var source: MediaSource? {
        switch message.content {
        case .photo(let source, _): source
        case .video(let poster, _, _): poster
        default: nil
        }
    }

    private var duration: TimeInterval? {
        if case .video(_, let duration, _) = message.content, duration > 0 { return duration }
        return nil
    }

    /// “Fotoğraf, deniz, 8 Eki” / “Video, 0:18, sen, 8 Eki”.
    private var label: Text {
        let date = U2Format.dayMonth(message.sentAt, locale: locale)
        let sender = message.isOutgoing ? StringTable.contact.string("documents.sender.you", locale: locale) : peer
        if case .video(_, let duration, _) = message.content {
            return StringTable.contact.text(
                "a11y.video \(U2Format.voiceDuration(duration)) \(sender) \(date)", locale: locale)
        }
        return StringTable.contact.text("a11y.photo \(sender) \(date)", locale: locale)
    }
}

// MARK: - Belgeler

/// The chat's files (proposed): one C13 card, rows of the 5.2 file tile
/// (42 × 50, r10, the type glyph in `accent300`; the ring while it is
/// decrypted or downloaded), the name (one line, middle truncation) over
/// “2,1 MB · PDF”, and at the end the date over who sent it. Newest first,
/// a page at a time. A tap previews it (Quick Look in the app's screen), a
/// video file plays.
struct ContactDocumentsList: View {
    let model: ContactMediaModel
    @Environment(\.u2SafeArea) private var safeArea

    var body: some View {
        let items = model.items(.documents)
        let page = model.page(.documents)
        Group {
            if items.isEmpty {
                if page.hasLoaded && !page.isLoading {
                    ContactEmptyState(
                        tile: IconTile(content: .glyph(.filePdf), size: 52, cornerRadius: U2Radius.iconTileXL, glyphSize: 26),
                        title: Text("documents.empty.title", table: .contact),
                        message: Text("documents.empty.body \(model.peerUsername)", table: .contact))
                    .accessibilityIdentifier("contact.documents.empty")
                } else {
                    Color.clear
                }
            } else {
                ScrollView {
                    LazyVStack(spacing: 0) {
                        ForEach(Array(items.enumerated()), id: \.element.id) { index, message in
                            if index > 0 { GroupedSeparator(inset: 70) }
                            ContactDocumentRow(
                                message: message, sender: model.sender(of: message),
                                isOpening: model.openingFile == message.id
                            ) {
                                model.openDocument(message)
                            }
                            .onAppear { model.itemAppeared(message.id, in: .documents) }
                        }
                    }
                    .background(RoundedRectangle(cornerRadius: U2Radius.group, style: .continuous).fill(Color.u2.surface))
                    .clipShape(RoundedRectangle(cornerRadius: U2Radius.group, style: .continuous))
                    .padding(.horizontal, U2Spacing.screenGutterS)
                    .padding(.top, 16)
                    if page.isLoading && page.hasLoaded {
                        ContactLoadingFooter()
                    }
                    Color.clear.frame(height: safeArea.bottom(24))
                }
                .scrollIndicators(.hidden)
                .accessibilityIdentifier("contact.documents")
            }
        }
        .task { await model.loadFirstPageIfNeeded(.documents) }
    }
}

/// One file of Belgeler.
struct ContactDocumentRow: View {
    let message: Message
    /// Who sent it: the user's own name or the contact's.
    let sender: String
    let isOpening: Bool
    let onOpen: () -> Void
    @Environment(\.locale) private var locale
    @Environment(\.u2Clock) private var clock

    var body: some View {
        Button(action: onOpen) {
            HStack(spacing: 12) {
                tile
                VStack(alignment: .leading, spacing: 4) {
                    Text(verbatim: name)
                        .u2Font(.bubbleTitleS)
                        .foregroundStyle(Color.u2.text)
                        .lineLimit(1)
                        .truncationMode(.middle)
                    Text(verbatim: meta)
                        .u2Font(.monoFileMeta)
                        .foregroundStyle(Color.u2.textSecondary)
                        .lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                VStack(alignment: .trailing, spacing: 4) {
                    Text(verbatim: U2Format.chatListTime(message.sentAt, now: clock.now, locale: locale))
                        .u2Font(.monoMeta)
                        .foregroundStyle(Color.u2.textSecondary)
                    Text(verbatim: message.isOutgoing ? StringTable.contact.string("documents.sender.you", locale: locale) : sender)
                        .u2Font(.monoMeta)
                        .foregroundStyle(Color.u2.textTertiary)
                        .lineLimit(1)
                }
                .fixedSize()
            }
            .padding(EdgeInsets(top: 11, leading: 14, bottom: 11, trailing: 14))
            .contentShape(Rectangle())
        }
        .buttonStyle(GroupedRowButtonStyle())
        .accessibilityElement(children: .combine)
        .accessibilityValue(MediaAccessibility.state(message.mediaAvailability))
        .accessibilityAddTraits(.isButton)
        .accessibilityIdentifier("contact.document.\(message.id)")
    }

    private var name: String {
        if case .file(let name, _, _, _) = message.content { return name }
        return ""
    }

    /// “2,1 MB · PDF · 4 sayfa”.
    private var meta: String {
        guard case .file(_, let byteCount, let typeLabel, let pageCount) = message.content else { return "" }
        var parts = [U2Format.byteCount(byteCount, locale: locale)]
        if !typeLabel.isEmpty { parts.append(typeLabel) }
        if let pageCount { parts.append(L10n.string("file.pages \(pageCount)", locale: locale)) }
        switch message.mediaAvailability {
        case .unavailable: parts.append(StringTable.conversation.string("file.unavailable", locale: locale))
        case .held: parts.append(StringTable.conversation.string("file.held", locale: locale))
        default: break
        }
        return parts.joined(separator: " · ")
    }

    private var tile: some View {
        let availability = message.mediaAvailability
        return ZStack {
            switch glyph {
            case .pdf: PhosphorIcon(.filePdf, size: 25)
            case .movie: PhosphorIcon(.play, weight: .fill, size: 19)
            case .file: MediaGlyphIcon(.file, size: 25)
            case .download: MediaGlyphIcon(.download, size: 23)
            }
        }
        .foregroundStyle(availability == .unavailable ? Color.u2.textTertiary : Color.u2.accent300)
        .frame(width: 42, height: 50)
        .background(RoundedRectangle(cornerRadius: U2Radius.iconTileL, style: .continuous).fill(Color.u2.fileIconFill))
        .overlay {
            if availability.isDownloading || isOpening {
                MediaProgressRing(
                    fraction: isOpening ? nil : fraction,
                    shape: TopStartRoundedRectangle(cornerRadius: U2Radius.iconTileL + 3),
                    tint: Color.u2.accent300, track: Color.u2.text.opacity(0.18)
                )
                .padding(-3)
                .transition(.u2Fade)
            }
        }
        .u2Animation(U2Motion.state, value: availability.isDownloading || isOpening)
        .accessibilityHidden(true)
    }

    private enum Glyph {
        case pdf, movie, file, download
    }

    private var glyph: Glyph {
        if message.mediaAvailability == .remote { return .download }
        switch MediaFileKind(fileName: name) {
        case .pdf: return .pdf
        case .movie: return .movie
        default: return .file
        }
    }

    private var fraction: Double? {
        if case .downloading(let fraction) = message.mediaAvailability { return fraction }
        return nil
    }
}
