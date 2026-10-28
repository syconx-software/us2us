import SwiftUI

/// 06 · Güvenlik numarası — the `.safetyNumber` route (5.1 › header name):
/// the contact screen (proposed), with 6.1 as its first tab (Doğrulama) and
/// the chat's photos and documents beside it (`ContactScreen`).
///
/// Hosts the whole verification flow of one chat: 6.1 itself, 6.3 pushed
/// over it, and the 6.2 scanner cover. All state lives in
/// `VerificationModel` (and `ContactMediaModel` for the other tabs), all
/// data comes through `MessengerService`.
struct SafetyNumberView: View {
    let chatID: ChatID
    @Environment(AppModel.self) private var app
    @State private var model: VerificationModel?
    @State private var media: ContactMediaModel?
    @State private var actions: ContactActionsModel?

    private var chat: ChatID { chatID }

    var body: some View {
        ZStack {
            #if DEBUG
                if VerificationHarness.showsContactScanner {
                    ContactScanHarnessHost()
                } else {
                    flow
                }
            #else
                flow
            #endif
        }
    }

    @ViewBuilder private var flow: some View {
        ZStack {
            if let model, let media, let actions {
                VerificationFlowView(
                    model: model, media: media, actions: actions, onBack: app.router.pop, onBackToChat: backToChat)
            } else {
                U2Screen { Color.clear }
            }
        }
        .onAppear {
            guard model == nil else { return }
            #if DEBUG
                // Scripted flows start from 5.1 › header, like the app does.
                if VerificationHarness.startsFromChat, app.router.path == [.safetyNumber(chatID)] {
                    app.router.setPath([.conversation(chat), .safetyNumber(chatID)])
                    return
                }
            #endif
            model = VerificationModel(chatID: chat, messenger: app.messenger)
            let media = ContactMediaModel(chatID: chat, messenger: app.messenger, saveNotice: app.preferences)
            if let tab = VerificationHarness.initialTab { media.show(tab) }
            self.media = media
            actions = ContactActionsModel(chatID: chat, messenger: app.messenger)
        }
        // The chat went while this screen was open (“Engeli kaldır” made a
        // blocked request a request again, or Ghost erased it): back past
        // the chat as well, not onto its empty shell. Not for a lock, which
        // empties the live service's chats under the cover for a while.
        .onChange(of: app.messenger.chat(chat) == nil) { _, isGone in
            if isGone, app.overlay == .none { leaveGoneChat() }
        }
    }

    /// Pops this screen and the chat under it (5.1 › header) in one soft pop.
    private func leaveGoneChat() {
        let path = app.router.path
        let own: Set<AppRoute> = [.conversation(chat), .safetyNumber(chatID)]
        guard let top = path.last, own.contains(top) else { return }
        let below = path.reversed().drop { own.contains($0) }.first
        if let below {
            app.router.pop(to: below)
        } else {
            app.router.popToRoot()
        }
    }

    /// 6.3 “Sohbete dön” / close → 5.1: a pop when 6.1 was opened from the
    /// chat (flows.md), otherwise the chat replaces this screen.
    private func backToChat() {
        let path = app.router.path
        if path.count >= 2, path[path.count - 2] == .conversation(chat) {
            app.router.pop()
        } else {
            app.router.replaceTop(with: .conversation(chat))
        }
    }
}

/// 6.1 (inside the contact screen's tabs) with 6.3 pushed over it, the 6.2
/// cover and the confirmations.
struct VerificationFlowView: View {
    @Bindable var model: VerificationModel
    let media: ContactMediaModel
    let actions: ContactActionsModel
    /// 6.1 back (pops the route).
    let onBack: () -> Void
    /// 6.3 close / “Sohbete dön”.
    let onBackToChat: () -> Void

    @Environment(\.locale) private var locale
    @Environment(\.u2Clock) private var clock
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        ZStack {
            ContactScreen(verification: model, media: media, actions: actions, onBack: onBack)
                // 6.3 is “pushed”: 6.1 fades out drifting 16 pt to the leading side.
                .opacity(model.showsVerified ? 0 : 1)
                .offset(x: model.showsVerified && !reduceMotion ? -16 : 0)
                .allowsHitTesting(!model.showsVerified)
                .accessibilityHidden(model.showsVerified)
            if model.showsVerified {
                VerifiedView(model: model, onDone: onBackToChat)
                    .transition(.u2Push)
                    .zIndex(1)
            }
        }
        .task {
            model.exitToChat = onBackToChat
            model.localeForAnnouncements = locale
            await model.load()
            await VerificationHarness.apply(to: model)
            await VerificationHarness.apply(to: actions)
        }
        .onChange(of: locale) { _, new in model.localeForAnnouncements = new }
        // A new key (or a decision made elsewhere) while 6.1 is open: the
        // number on screen is always the current one.
        .onChange(of: model.identityState) { _, _ in
            Task { await model.load(animation: U2Motion.state) }
        }
        .u2FadeCover(
            isPresented: $model.isScannerPresented, fadeOut: { model.scannerFadeOut }, onEscape: model.closeScanner
        ) {
            QRScanView(scanner: model.scanner, chromeScheme: colorScheme)
                .environment(\.locale, locale)
                .environment(\.u2Clock, clock)
        }
        .alert(Text("safety.confirm.title", table: .verification), isPresented: $model.confirmsMarkVerified) {
            Button(role: .cancel) {
                model.cancelMarkVerified()
            } label: {
                Text("common.cancel")
            }
            Button {
                Task { await model.markVerifiedManually() }
            } label: {
                Text("safety.confirm.mark", table: .verification)
            }
        } message: {
            Text("safety.confirm.message \(model.peerName)", table: .verification)
        }
        .alert(Text("safety.remove.title", table: .verification), isPresented: $model.confirmsRemoval) {
            Button(role: .cancel) {
            } label: {
                Text("common.cancel")
            }
            Button(role: .destructive) {
                Task { await model.removeVerification() }
            } label: {
                Text("safety.remove.confirm", table: .verification)
            }
        } message: {
            Text("safety.remove.message", table: .verification)
        }
        .alert(Text("safety.accept.title", table: .verification), isPresented: $model.confirmsAccept) {
            Button(role: .cancel) {
                model.cancelAccept()
            } label: {
                Text("common.cancel")
            }
            Button {
                Task { await model.acceptChangeWithoutVerifying() }
            } label: {
                Text("safety.accept.confirm", table: .verification)
            }
        } message: {
            Text("safety.accept.message", table: .verification)
        }
    }
}

// MARK: - 6.1

/// 6.1 · Güvenlik numarası, the contact screen's Doğrulama tab (under its
/// header — back · the name · share — and the tabs): the sen ↔ peer line
/// (seal once verified), the QR card of the scannable fingerprint, the
/// 12 × 5 digits; “QR kodu okut” (C1) and “Doğrulandı olarak işaretle”
/// (C3) — “Doğrulamayı kaldır” once verified. A changed number shows a
/// danger note above the line (proposed), and so does a blocked person,
/// with “Engeli kaldır” (`ContactBlockedNote`).
struct SafetyNumberTab: View {
    let model: VerificationModel
    /// The contact screen's block state and “Engeli kaldır”.
    var contact: ContactActionsModel? = nil
    @Environment(\.locale) private var locale

    var body: some View {
        FitOrScroll { content }
    }

    private var content: some View {
        VStack(spacing: 0) {
            if let contact, contact.isBlocked {
                ContactBlockedNote(name: model.peerName) {
                    Task { await contact.unblock() }
                }
                .padding(.horizontal, U2Spacing.screenGutterS)
                .padding(.top, 16)
                .transition(.u2Drop)
            }
            if model.numberChanged || model.changedWhileOpen {
                changedNote
            }
            safetyNumber
            if let info = model.info, info.isVerified {
                VerifiedSincePill(info: info)
                    .padding(.top, 24)
                    .transition(.u2Rise)
            } else if model.info != nil, !model.numberChanged, !model.changedWhileOpen, contact?.isBlocked != true {
                // What comparing the numbers proves, and how (proposed). Not
                // under a changed-number or blocked note: those already say
                // what to do, and the actions must stay on screen.
                Text("safety.explainer \(L10n.possessive(model.peerName, locale: locale))", table: .verification)
                    .u2Font(.captionCompact)
                    .foregroundStyle(Color.u2.textSecondary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: 330)
                    .padding(.horizontal, U2Spacing.screenGutterL)
                    .padding(.top, 20)
                    .accessibilityIdentifier("safety.explainer")
                    .transition(.u2Fade)
            }
            Spacer(minLength: 24)
            actions
        }
        .u2Animation(U2Motion.state, value: contact?.isBlocked ?? false)
    }

    private var changedNote: some View {
        NoteCard(
            glyph: .warningCircle,
            title: Text("safety.changed.title", table: .verification),
            message: model.changedWhileOpen
                ? Text("safety.changedWhileOpen.message", table: .verification)
                : Text("safety.changed.message \(model.peerName)", table: .verification)
        )
        .padding(.horizontal, U2Spacing.screenGutterS)
        .padding(.top, 16)
        .transition(.u2Drop)
        .accessibilityIdentifier("safety.changed")
    }

    @ViewBuilder private var safetyNumber: some View {
        if let peer = model.peer {
            ConnectionLine(
                ownUsername: model.ownUsername, ownLabel: Text("safety.you", table: .verification),
                peer: peer, showsSeal: model.isVerified, animatesSeal: true
            )
            .padding(.top, 20)
        }
        QRCodeCard(modules: model.qrModules, label: Text("safety.a11y.qr", table: .verification))
            .padding(.top, 22)
        if let groups = model.info?.groups {
            SafetyDigitsGrid(groups: groups)
                .padding(.top, 24)
        } else {
            Color.clear
                .frame(height: 23.25 * 3 + 20)
                .padding(.top, 24)
        }
    }

    private var actions: some View {
        VStack(spacing: 6) {
            Button {
                model.presentScanner()
            } label: {
                HStack(spacing: 8) {
                    PhosphorIcon(.scan, size: 20)
                    Text("safety.scan", table: .verification)
                }
            }
            .buttonStyle(.u2Primary)
            .disabled(model.info == nil)
            .accessibilityIdentifier("safety.scan")
            Button {
                if model.isVerified { model.requestRemoval() } else { model.requestMarkVerified() }
            } label: {
                // The CSS line box (24.8) centred in the 46 pt button, as drawn.
                secondaryTitle.u2Font(.rowTitle)
            }
            .buttonStyle(U2TextButtonStyle(tone: .quiet, height: U2Size.textButtonS))
            .disabled(model.info == nil)
            .accessibilityIdentifier("safety.markVerified")
            // After a change (proposed): go on without verifying — sending
            // resumes, the chat keeps no seal. Distinct from verifying.
            if model.numberChanged {
                Button {
                    model.requestAccept()
                } label: {
                    Text("safety.acceptChange", table: .verification).u2Font(.rowTitle)
                }
                .buttonStyle(U2TextButtonStyle(tone: .quiet, height: U2Size.textButtonS))
                .disabled(model.info == nil)
                .transition(.opacity)
                .accessibilityIdentifier("safety.acceptChange")
            }
        }
        .padding(.horizontal, U2Spacing.screenGutter)
        .u2Bottom(40)
    }

    private var secondaryTitle: Text {
        model.isVerified
            ? Text("safety.removeVerification", table: .verification)
            : Text("safety.markVerified", table: .verification)
    }

    /// 6.1 › share: the digits as text (the contact screen's header button).
    @MainActor
    static func share(_ model: VerificationModel, locale: Locale) {
        guard let groups = model.info?.groups else { return }
        SystemShareSheet.present(
            [VerificationText.shareText(own: model.ownUsername, peer: model.peerName, groups: groups, locale: locale)],
            excluding: [.addToReadingList, .assignToContact, .openInIBooks, .saveToCameraRoll])
    }
}

/// 6.1 verified state (proposed): “Doğrulandı · 8 Ekim 2026 · 18:52” — the
/// 6.3 pill's look (`text·6%` r14, pad 8 14, gap 8, `monoMeta` `text·68%`)
/// with the seal (`ph:seal-check` fill 14 `accent`).
private struct VerifiedSincePill: View {
    let info: SafetyNumberInfo
    @Environment(\.locale) private var locale

    var body: some View {
        HStack(spacing: 8) {
            PhosphorIcon(.sealCheck, weight: .fill, size: 14)
                .foregroundStyle(Color.u2.accent)
            Text(verbatim: text)
                .u2Font(.monoMeta)
                .foregroundStyle(Color.u2.textSecondary)
        }
        .padding(.vertical, 8)
        .padding(.horizontal, 14)
        .background(RoundedRectangle(cornerRadius: U2Radius.systemPill, style: .continuous).fill(Color.u2.fillSubtle))
        .accessibilityElement(children: .combine)
    }

    private var text: String {
        // Verified before dates were kept (or restored from a backup): no date.
        guard let date = info.verifiedAt else { return StringTable.verification.string("safety.verified", locale: locale) }
        let when = VerificationText.longDate(date, locale: locale) + " · " + U2Format.clockTime(date, locale: locale)
        return StringTable.verification.string("safety.verifiedSince \(when)", locale: locale)
    }
}

// MARK: - Layout helper

/// The design's fixed layout when it fits (iPhone 17 Pro, default text
/// size); a scroll view on shorter screens or with large Dynamic Type.
struct FitOrScroll<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        ViewThatFits(in: .vertical) {
            content
            ScrollView(.vertical) {
                content
            }
            .scrollBounceBehavior(.basedOnSize)
            .scrollIndicators(.hidden)
        }
    }
}

#Preview("6.1 Güvenlik numarası") {
    let model = AppModel.preview()
    return SafetyNumberView(chatID: "deniz")
        .environment(model)
        .environment(\.locale, model.locale)
        .environment(\.u2Clock, model.clock)
}
