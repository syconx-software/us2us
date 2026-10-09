import SwiftUI

/// 4.1 / 4.2 › “QR kodla ekle” → 6.2 → (match) 6.3 → 5.1 (flows.md › By QR:
/// “adds the contact **and** verifies it”).
///
/// Hosted by 4.1 and 4.2 (`ChatsView`, `NewChatSheet`): the host
/// presents it in its full-screen cover (fade 0.36 s). Close (✕) calls
/// `onClose` (back to 4.1 / 4.2). On a match the contact is added and
/// verified face to face, and 6.3 replaces the scanner inside the cover
/// (cross-fade 0.5 s); “Sohbete dön” / close then opens the chat
/// (`onOpenChat`, default: push 5.1 on the main stack) and closes the cover.
struct ContactQRScanView: View {
    let onClose: () -> Void
    /// How the host opens the new chat (4.2 also closes its sheet). `nil`:
    /// push `.conversation` on the main stack.
    var onOpenChat: ((ChatID) -> Void)?

    @Environment(AppModel.self) private var app
    @Environment(\.locale) private var locale
    @Environment(\.colorScheme) private var colorScheme
    @State private var model: ContactScanModel?
    /// The app's theme, read before the scanner asks the cover for dark
    /// (6.3 and the camera buttons' hairline follow it).
    @State private var appScheme: ColorScheme?

    var body: some View {
        ZStack {
            if let model {
                ContactScanContent(model: model, appScheme: appScheme ?? colorScheme)
            } else {
                Color.u2.cameraBg.ignoresSafeArea()
            }
        }
        .onAppear {
            guard model == nil else { return }
            appScheme = colorScheme
            let router = app.router
            let model = ContactScanModel(
                messenger: app.messenger, onClose: onClose,
                onOpenChat: onOpenChat ?? { router.push(.conversation($0)) })
            model.locale = locale
            self.model = model
            Task {
                await model.load()
                #if DEBUG
                    await VerificationHarness.apply(to: model)
                #endif
            }
        }
        .onChange(of: locale) { _, new in model?.locale = new }
    }
}

/// The scanner, and 6.3 over it once a contact matched.
private struct ContactScanContent: View {
    let model: ContactScanModel
    let appScheme: ColorScheme

    var body: some View {
        ZStack {
            QRScanView(scanner: model.scanner, chromeScheme: appScheme)
                .opacity(model.verification == nil ? 1 : 0)
                .allowsHitTesting(model.verification == nil)
                .accessibilityHidden(model.verification != nil)
            if let verification = model.verification {
                VerifiedView(model: verification, onDone: model.finish)
                    .environment(\.colorScheme, appScheme)
                    // Back to the app's theme (and status bar) for 6.3.
                    .preferredColorScheme(appScheme)
                    .transition(.opacity)
                    .zIndex(1)
            }
        }
    }
}

/// State of the add-by-QR flow, behind `MessengerService` only.
@Observable
@MainActor
final class ContactScanModel {
    let messenger: any MessengerService
    let scanner = QRScannerModel(purpose: .addContact)
    /// 6.3 for the contact just added and verified.
    private(set) var verification: VerificationModel?

    @ObservationIgnored private let onClose: () -> Void
    @ObservationIgnored private let onOpenChat: (ChatID) -> Void
    /// The contact code being accepted (between evaluate and accept).
    @ObservationIgnored private var pendingCode: String?
    @ObservationIgnored private var isFinishing = false

    var locale = Locale(identifier: "tr") {
        didSet { scanner.locale = locale }
    }

    init(messenger: any MessengerService, onClose: @escaping () -> Void, onOpenChat: @escaping (ChatID) -> Void) {
        self.messenger = messenger
        self.onClose = onClose
        self.onOpenChat = onOpenChat
        scanner.isActive = true
        scanner.evaluate = { [weak self] code in
            guard let self else { return .ignore }
            switch await self.messenger.resolveContactCode(code) {
            case .notAContactCode: return .ignore
            case .ownCode: return .warn(.ownCode)
            case .unknown: return .warn(.unrecognized)
            case .contact:
                self.pendingCode = code
                return .accept
            }
        }
        scanner.onAccept = { [weak self] in await self?.didMatch() }
        scanner.onClose = { [weak self] in self?.close() }
        scanner.simulatedCodes = {
            // The design's mock is deniz's phone (“deniz’in telefonundaki …”).
            (ContactCode.encode(username: "deniz"), ContactCode.encode(username: "bilinmeyen"))
        }
    }

    /// The own contact code for “Kendi kodumu göster”.
    func load() async {
        guard let code = await messenger.preparedOwnContactCode() else { return }
        scanner.setOwnCode(QRScannerModel.OwnCode(username: messenger.ownUsername, modules: QRModules.cached(code), groups: nil))
    }

    private func close() {
        scanner.isActive = false
        onClose()
    }

    /// Match: add + verify, then 6.3 replaces the scanner (cross-fade 0.5 s).
    private func didMatch() async {
        guard let code = pendingCode else { return }
        pendingCode = nil
        scanner.isActive = false
        guard let chat = await messenger.addContact(fromScannedCode: code) else {
            scanner.isActive = true
            return
        }
        let verification = VerificationModel(chatID: chat, messenger: messenger)
        verification.localeForAnnouncements = locale
        await verification.load()
        verification.verifiedEntrance = true
        // 6.3 cross-fades in over the scanner (0.5 s); its entrance follows.
        verification.verifiedEntranceDelay = U2Motion.Duration.screen
        U2Haptics.success()
        withAnimation(U2Motion.soft(U2Motion.Duration.screen)) { self.verification = verification }
        AccessibilityNotification.Announcement(
            StringTable.verification.string("verified.title", locale: locale)
        ).post()
    }

    /// 6.3 “Sohbete dön” / close → 5.1 (the chat opens under the fading cover).
    func finish() {
        guard !isFinishing, let chat = verification?.chatID else { return }
        isFinishing = true
        onOpenChat(chat)
        onClose()
    }

    #if DEBUG
        /// Harness: 6.3 for `username` without scanning.
        func simulateMatch(_ username: String) async {
            pendingCode = ContactCode.encode(username: username)
            await didMatch()
        }
    #endif
}

#Preview("6.2 QR kodla ekle") {
    let app = AppModel.preview()
    return ContactQRScanView(onClose: {})
        .environment(app)
        .environment(\.locale, app.locale)
        .environment(\.u2Clock, app.clock)
}
