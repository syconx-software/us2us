import SwiftUI
import UIKit

/// The contact screen (5.1 › the person in the header; proposed, in 6.1's
/// language): the C7 header — back · the name in `monoUsernameL` (with the
/// seal once verified, `ph:prohibit` in `danger` while blocked) · “…” —
/// over C22 tabs (Doğrulama · Fotoğraflar · Belgeler, 16 pt below the
/// header, 20 pt gutters) and the tab's content: 6.1 as it was
/// (`SafetyNumberTab`), the grid of the chat's photos and videos, the list
/// of its files. Tabs cross-fade (0.36 s).
///
/// “…” opens the 4.4 menu's look (`ContactActionsModel`): “Güvenlik
/// numarasını paylaş” on Doğrulama, “Bildir” (the report sheet) and
/// “Engelle” after the request block's confirmation — “Engeli kaldır” once
/// blocked, which Doğrulama also offers in its note (App Review 1.2).
///
/// Over it: the photo viewer and the player swiping through the grid
/// (`MediaGalleryViewer`, the 5.6 fade cover), a file's preview, and the
/// notice for a file Quick Look will not show. Everything decrypted closes
/// with the app (the lock follows), or when its message goes.
struct ContactScreen: View {
    let verification: VerificationModel
    @Bindable var media: ContactMediaModel
    @Bindable var actions: ContactActionsModel
    let onBack: () -> Void

    @Environment(\.locale) private var locale
    @Environment(\.u2Clock) private var clock
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        U2Screen {
            VStack(spacing: 0) {
                header
                tabs
                    .padding(.horizontal, U2Spacing.screenGutter)
                    .padding(.top, 16)
                content
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            }
            .u2Menu(isPresented: $actions.showsMenu, items: menuItems)
        }
        // The request block's confirmation and copy (4.3 / 4.4), with “Bildir” beside it.
        .alert(
            Text("requests.block.title \(verification.peerName)", table: .chats), isPresented: $actions.confirmsBlock
        ) {
            Button(role: .destructive) {
                Task { await actions.block() }
            } label: {
                Text("request.block")
            }
            Button {
                actions.openReport(afterAlert: true)
            } label: {
                Text("report.action")
            }
            Button(role: .cancel) {} label: { Text("common.cancel") }
        } message: {
            Text("requests.block.message", table: .chats)
        }
        .u2Sheet(item: $actions.report) { report in
            ReportSheet(model: report)
                .environment(\.locale, locale)
        }
        .u2FadeCover(item: $media.gallery, onEscape: media.closeGallery) { gallery in
            MediaGalleryViewer(model: media, gallery: gallery, themeScheme: colorScheme, onClose: media.closeGallery)
                .environment(\.locale, locale)
                .environment(\.u2Clock, clock)
        }
        .u2Sheet(item: $media.fileNotice, style: .floating, onDismiss: media.fileNoticeDidDismiss) { notice in
            FileNoticeSheet(notice: notice, onSave: media.saveNoticeFile, onClose: media.closeFileNotice)
        }
        .onChange(of: locale, initial: true) { _, locale in
            media.presentationLocale = locale
            actions.locale = locale
        }
        .onChange(of: media.presentedMediaIsGone) { _, isGone in
            if isGone { media.closeMedia() }
        }
        .onChange(of: actions.isBlocked) { _, isBlocked in
            if isBlocked { actions.blockDidShow() }
        }
        // The chat is not open behind this screen: its changes come as a
        // revision, and the loaded lists are read again.
        .onChange(of: media.revision) {
            Task { await media.refreshLoaded() }
        }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.didEnterBackgroundNotification)) { _ in
            media.closeMedia()
        }
    }

    // MARK: Header

    private var peer: Peer? { verification.peer }

    private var header: some View {
        ZStack {
            title
                .frame(maxWidth: 250)
            HStack {
                U2GlassIconButton(.caretLeft, label: Text("a11y.back"), action: onBack)
                    .accessibilityIdentifier("safety.back")
                Spacer(minLength: 0)
                U2GlassIconButton(.dotsThree, label: Text("a11y.more", table: .contact)) { actions.showsMenu.toggle() }
                    .accessibilityIdentifier("contact.more")
            }
        }
        .padding(.horizontal, U2Spacing.screenGutterS)
        .frame(height: U2Size.iconButton)
        .u2Top(U2Spacing.headerTopButtons)
    }

    /// The name as everywhere else (Mono), the seal once verified.
    private var title: some View {
        HStack(spacing: 5) {
            Text(verbatim: verification.peerName)
                .u2Font(.monoUsernameL, matchLineBox: false)
                .foregroundStyle(Color.u2.text)
                .lineLimit(1)
                .truncationMode(.middle)
            if peer?.isVerified == true {
                PhosphorIcon(.sealCheck, weight: .fill, size: 16, label: Text("a11y.verified"))
                    .foregroundStyle(Color.u2.accent)
                    .transition(.u2Fade)
            }
            if actions.isBlocked {
                PhosphorIcon(.prohibit, size: 16, label: Text("block.blocked"))
                    .foregroundStyle(Color.u2.danger)
                    .transition(.u2Fade)
                    .accessibilityIdentifier("contact.blockedMark")
            }
        }
        .u2Animation(U2Motion.state, value: peer?.isVerified == true)
        .u2Animation(U2Motion.state, value: actions.isBlocked)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isHeader)
    }

    // MARK: “…”

    /// Share (Doğrulama only, once the number is known) ‖ Bildir · Engelle
    /// — or “Engeli kaldır” while blocked.
    private var menuItems: [FloatingMenuItem] {
        var items: [FloatingMenuItem] = []
        if media.tab == .verification, verification.info != nil {
            items.append(
                FloatingMenuItem(id: "share", title: Text("menu.share", table: .contact), glyph: .shareNetwork) {
                    SafetyNumberTab.share(verification, locale: locale)
                })
        }
        items.append(
            FloatingMenuItem(id: "report", title: Text("report.action"), glyph: .warning, startsGroup: !items.isEmpty) {
                actions.openReport()
            })
        if actions.isBlocked {
            items.append(
                FloatingMenuItem(id: "unblock", title: Text("block.unblock"), glyph: .prohibit) {
                    Task { await actions.unblock() }
                })
        } else {
            items.append(
                FloatingMenuItem(id: "block", title: Text("request.block"), glyph: .prohibit, tone: .destructive) {
                    actions.requestBlock()
                })
        }
        return items
    }

    // MARK: Tabs

    private var tabs: some View {
        SegmentedChips(
            options: ContactMediaModel.Tab.allCases,
            selection: Binding(get: { media.tab }, set: { media.show($0) }),
            identifierPrefix: "contact.tab"
        ) { tab in
            switch tab {
            case .verification: Text("tab.verification", table: .contact)
            case .photos: Text("tab.photos", table: .contact)
            case .documents: Text("tab.documents", table: .contact)
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityAddTraits(.isTabBar)
    }

    @ViewBuilder private var content: some View {
        ZStack(alignment: .top) {
            switch media.tab {
            case .verification:
                SafetyNumberTab(model: verification, contact: actions)
                    .transition(.u2Fade)
            case .photos:
                ContactPhotosGrid(model: media)
                    .transition(.u2Fade)
            case .documents:
                ContactDocumentsList(model: media)
                    .transition(.u2Fade)
            }
        }
        .u2Animation(U2Motion.state, value: media.tab)
    }
}

// MARK: - Empty and loading

/// A tab with nothing in it yet: a glyph in the C13 tile, a title and one
/// friendly line, in the middle of what is left of the screen.
struct ContactEmptyState: View {
    let tile: IconTile
    let title: Text
    let message: Text

    var body: some View {
        VStack(spacing: 14) {
            tile
            VStack(spacing: 6) {
                title
                    .u2Font(.rowTitleEmphasis)
                    .foregroundStyle(Color.u2.text)
                message
                    .u2Font(.caption)
                    .foregroundStyle(Color.u2.textSecondary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(.horizontal, 40)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityElement(children: .combine)
    }
}

/// The next page on its way (under the grid or the list).
struct ContactLoadingFooter: View {
    var body: some View {
        SpinningGlyph(size: 18)
            .foregroundStyle(Color.u2.textSecondary)
            .frame(maxWidth: .infinity)
            .frame(height: 44)
            .accessibilityElement()
            .accessibilityLabel(Text("a11y.loadingMore", table: .contact))
    }
}
