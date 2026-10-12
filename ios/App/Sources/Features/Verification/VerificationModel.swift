import SwiftUI

/// State and actions of the 06 flow for one chat: 6.1 (safety number), the
/// 6.2 scanner cover and 6.3 (verified), driven only by `MessengerService`.
///
/// Presentation (flows.md):
/// * 6.1 is the `.safetyNumber` route on the main stack;
/// * 6.2 is a full-screen cover that fades in and out (0.36 s);
/// * 6.3 is pushed over 6.1 (`screenPush`) after “Doğrulandı olarak
///   işaretle”, or revealed under the fading cover (0.5 s) after a match;
/// * “Sohbete dön” / close pops back to the chat.
@Observable
@MainActor
final class VerificationModel {
    let chatID: ChatID
    let messenger: any MessengerService

    private(set) var info: SafetyNumberInfo?
    private(set) var qrModules: QRModules?
    private(set) var numberChanged = false
    /// A decision about the number on screen was refused because the
    /// number changed meanwhile; 6.1 now shows the new one and says so.
    private(set) var changedWhileOpen = false
    private(set) var hasLoaded = false

    /// 6.3 pushed over 6.1.
    var showsVerified = false
    /// Whether 6.3 plays its entrance (seal 0.6 → 1, staggered title).
    var verifiedEntrance = true
    /// How long 6.3 waits before its entrance: arriving from the scanner,
    /// until the 6.2 cover over it has faded away (otherwise the seal pops
    /// and the text staggers in under the fading camera image).
    var verifiedEntranceDelay: Double = 0

    // 6.2
    let scanner: QRScannerModel
    var isScannerPresented = false
    /// The cover's fade-out duration: 0.36 s on close, 0.5 s on a match.
    private(set) var scannerFadeOut: Double = U2Motion.Duration.state

    /// 6.3 › “Sohbete dön”, set by the flow view (the harness drives it too).
    var exitToChat: (() -> Void)?

    // Confirmations (6.1)
    var confirmsMarkVerified = false
    var confirmsRemoval = false
    var confirmsAccept = false

    init(chatID: ChatID, messenger: any MessengerService) {
        self.chatID = chatID
        self.messenger = messenger
        let peerName = messenger.peer(for: chatID)?.username ?? chatID
        scanner = QRScannerModel(purpose: .safetyNumber(peer: peerName))
        scanner.evaluate = { [weak self] code in
            guard let self else { return .ignore }
            // A match is stored as verified in the same step (bound to the
            // key it matched), before 6.3 says so.
            switch await self.messenger.verifyScannedSafetyCode(code, for: self.chatID) {
            case .notASafetyCode: return .ignore
            case .mismatch: return .warn(.mismatch)
            case .match: return .accept
            }
        }
        scanner.onAccept = { [weak self] in await self?.didMatch() }
        scanner.onClose = { [weak self] in self?.closeScanner() }
        scanner.simulatedCodes = { [weak self] in
            let match = self?.info.map { SafetyQRCodec.encode($0.scannablePayload) }
            return (match, SafetyQRCodec.encode(Data("not-this-chat's-safety-number".utf8)))
        }
    }

    var peer: Peer? { messenger.peer(for: chatID) }
    var peerName: String { peer?.username ?? chatID }
    var ownUsername: String { messenger.ownUsername }
    var isVerified: Bool { info?.isVerified ?? false }

    /// What 6.1 shows besides the digits: the peer's key, a pending change
    /// and the verification. When any of them changes (a new key arrives
    /// while 6.1 is open), 6.1 reloads.
    struct IdentityState: Equatable {
        let fingerprint: String?
        let changed: Bool
        let verified: Bool
    }

    var identityState: IdentityState {
        let current = peer
        return IdentityState(
            fingerprint: current?.identityFingerprint, changed: current?.safetyNumberChanged ?? false,
            verified: current?.isVerified ?? false)
    }

    // MARK: Loading

    /// Reads the safety number; `animation` softens the change (seal, pill).
    func load(animation: Animation? = nil) async {
        let info = await messenger.safetyNumber(for: chatID)
        if let info, info.scannablePayload != self.info?.scannablePayload || qrModules == nil {
            qrModules = QRModules(payload: SafetyQRCodec.encode(info.scannablePayload))
        }
        let changed = info?.hasUnverifiedChange ?? false
        // 6.3 says “Hat doğrulandı.” only while the chat really is verified:
        // if the key changed under it, 6.1 comes back with the new number.
        let hidesVerified = showsVerified && !(info?.isVerified ?? false)
        if let animation, hasLoaded {
            withU2Animation(animation) {
                self.info = info
                numberChanged = changed
                if hidesVerified { showsVerified = false }
            }
        } else {
            self.info = info
            numberChanged = changed
            if hidesVerified { showsVerified = false }
        }
        // “Kendi kodumu göster”: the same safety number — both sides see one
        // number; libsignal's comparison accepts either side's code.
        scanner.setOwnCode(QRScannerModel.OwnCode(username: ownUsername, modules: qrModules, groups: info?.groups))
        hasLoaded = true
    }

    // MARK: 6.1 actions

    /// “Doğrulandı olarak işaretle” → confirmation.
    func requestMarkVerified() {
        confirmsMarkVerified = true
    }

    /// Confirmed: mark manually — bound to the number on screen, the one the
    /// user compared — and push 6.3. If the number changed meanwhile nothing
    /// is marked: 6.1 shows the new number and says so.
    func markVerifiedManually() async {
        guard let compared = info?.scannablePayload else { return }
        guard await messenger.markVerified(chatID, comparing: compared) else {
            await numberChangedMeanwhile()
            return
        }
        changedWhileOpen = false
        await load(animation: U2Motion.state)
        guard isVerified else { return }
        verifiedEntrance = true
        verifiedEntranceDelay = 0
        U2Haptics.success()
        withU2Animation(U2Motion.screen) { showsVerified = true }
        announce(StringTable.verification.string("verified.title", locale: localeForAnnouncements))
    }

    /// “Doğrulamayı kaldır” → confirmation.
    func requestRemoval() {
        confirmsRemoval = true
    }

    func removeVerification() async {
        await messenger.clearVerification(chatID)
        // The seal and the pill leave softly.
        await load(animation: U2Motion.state)
    }

    /// After a change › “Doğrulamadan devam et” → confirmation.
    func requestAccept() {
        confirmsAccept = true
    }

    /// Confirmed: the changed number is accepted without verifying it —
    /// sending resumes, no seal — bound to the number on screen; then back
    /// to the chat. Refused like marking if the number changed meanwhile.
    func acceptChangeWithoutVerifying() async {
        guard let shown = info?.scannablePayload else { return }
        guard await messenger.acceptSafetyNumberChange(chatID, showing: shown) else {
            await numberChangedMeanwhile()
            return
        }
        changedWhileOpen = false
        await load(animation: U2Motion.state)
        announce(StringTable.verification.string("safety.accepted.announcement", locale: localeForAnnouncements))
        exitToChat?()
    }

    /// A decision about the number on screen was refused: the key changed
    /// since it was shown. 6.1 shows the new number with a note.
    private func numberChangedMeanwhile() async {
        U2Haptics.error()
        await load(animation: U2Motion.state)
        withU2Animation(U2Motion.state) { changedWhileOpen = true }
        announce(StringTable.verification.string("safety.changedWhileOpen.message", locale: localeForAnnouncements))
    }

    // MARK: 6.2

    func presentScanner() {
        scanner.reset()
        scanner.locale = localeForAnnouncements
        scannerFadeOut = U2Motion.Duration.state
        scanner.isActive = true
        isScannerPresented = true
    }

    /// Close (✕): fade the cover out (0.36 s) back to 6.1.
    func closeScanner() {
        scannerFadeOut = U2Motion.Duration.state
        scanner.isActive = false
        scanner.setTorch(false)
        isScannerPresented = false
    }

    /// 6.2 match → 6.3: verified face to face (stored by the match itself,
    /// bound to the key it matched); 6.3 is put under the cover, which then
    /// fades away over 0.5 s (“replace cover content, cross-fade”).
    private func didMatch() async {
        scanner.isActive = false
        changedWhileOpen = false
        await load()
        guard isVerified else {
            // The key changed again right after the match: back to 6.1.
            closeScanner()
            return
        }
        U2Haptics.success()
        var transaction = Transaction()
        transaction.disablesAnimations = true
        scannerFadeOut = U2Motion.Duration.screen
        withTransaction(transaction) {
            verifiedEntrance = true
            verifiedEntranceDelay = scannerFadeOut
            showsVerified = true
        }
        isScannerPresented = false
        announce(StringTable.verification.string("verified.title", locale: localeForAnnouncements))
    }

    // MARK: Accessibility

    /// Set by the view from `\.locale`, so announcements use the in-app language.
    var localeForAnnouncements = Locale(identifier: "tr") {
        didSet { scanner.locale = localeForAnnouncements }
    }

    private func announce(_ text: String) {
        AccessibilityNotification.Announcement(text).post()
    }
}
