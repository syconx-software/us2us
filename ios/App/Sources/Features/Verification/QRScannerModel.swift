import SwiftUI

/// The 6.2 scanner's state, shared by its two entries (flows.md):
/// * `.safetyNumber` — 6.1 “QR kodu okut”: compares the scanned code with
///   the chat's safety number (`VerificationModel`);
/// * `.addContact` — 4.1 / 4.2 “QR kodla ekle”: adds the contact and
///   verifies it at once (`ContactScanModel`).
///
/// The owner decides what a scanned text means (`evaluate`) and what a match
/// does (`onAccept`); this model only runs the screen: stage, warning card,
/// torch, and when the camera may run. Scanned texts are never stored or
/// logged — only the last *rejected* one is kept in memory, to warn once.
@Observable
@MainActor
final class QRScannerModel {
    enum Purpose: Equatable {
        /// Scanning `peer`'s safety-number code (hint “deniz’in telefonundaki …”).
        case safetyNumber(peer: String)
        /// Scanning someone's contact code (hint without a name).
        case addContact
    }

    enum Stage: Equatable {
        /// Camera + viewfinder (the design).
        case scanning
        /// “Kendi kodumu göster”: the user's own code; the camera is off.
        case myCode
    }

    /// The floating card under the top bar (5.6 style, `danger`).
    enum Warning: Equatable {
        /// “Numaralar uyuşmuyor” (6.2 note).
        case mismatch
        /// A us2us contact code nobody could be found for (proposed).
        case unrecognized
        /// The user's own contact code (proposed).
        case ownCode
    }

    /// What a scanned text means to the owner.
    enum Verdict: Equatable {
        /// Not a us2us code of the expected kind (a URL, a Wi-Fi code …): ignored.
        case ignore
        case warn(Warning)
        case accept
    }

    /// What “Kendi kodumu göster” shows: the user's avatar and name, the QR
    /// and — for a safety number — the 12 groups of digits.
    struct OwnCode: Equatable {
        let username: String
        let modules: QRModules?
        let groups: [String]?
    }

    let purpose: Purpose
    private(set) var stage: Stage = .scanning
    private(set) var warning: Warning?
    private(set) var torchOn = false
    /// Whether the camera may run (presented and not yet matched).
    var isActive = false
    private(set) var ownCode: OwnCode?

    /// Interprets a scanned text (compared on the owner's side, constant time).
    @ObservationIgnored var evaluate: (String) async -> Verdict = { _ in .ignore }
    /// A code was accepted: the owner moves on (6.3).
    @ObservationIgnored var onAccept: () async -> Void = {}
    /// Close (✕) or the escape gesture.
    @ObservationIgnored var onClose: () -> Void = {}
    /// Simulator only: the codes the design's phone mock stands for.
    @ObservationIgnored var simulatedCodes: () -> (match: String?, mismatch: String) = { (nil, "") }

    /// Set while a code is being evaluated (further reads are ignored).
    @ObservationIgnored private var isEvaluating = false
    @ObservationIgnored private var lastRejected: (code: String, at: Date)?

    init(purpose: Purpose) {
        self.purpose = purpose
    }

    // MARK: Lifecycle

    /// Back to the scanning state (each time the scanner opens).
    func reset() {
        stage = .scanning
        warning = nil
        torchOn = false
        lastRejected = nil
        isEvaluating = false
    }

    func close() {
        torchOn = false
        onClose()
    }

    func setOwnCode(_ code: OwnCode?) {
        ownCode = code
    }

    // MARK: Actions

    func toggleTorch() {
        withU2Animation(U2Motion.state) { torchOn.toggle() }
    }

    func setTorch(_ on: Bool) {
        torchOn = on
    }

    /// “Kendi kodumu göster” (cross-fade; the camera and the torch stop).
    func showOwnCode() {
        torchOn = false
        withU2Animation(U2Motion.state) {
            warning = nil
            stage = .myCode
        }
    }

    /// “Kodu okut” on the own-code view.
    func showScanning() {
        lastRejected = nil
        withU2Animation(U2Motion.state) { stage = .scanning }
    }

    func dismissWarning() {
        withU2Animation(U2Motion.state) { warning = nil }
    }

    /// A QR code was read by the camera (or the simulator's mock).
    func handle(_ code: String) async {
        guard isActive, stage == .scanning, !isEvaluating else { return }
        if let last = lastRejected, last.code == code, warning != nil || Date().timeIntervalSince(last.at) < 2.5 {
            // The same rejected code is still in front of the camera: warn
            // once (until it has been out of sight for 2.5 s).
            lastRejected = (code, Date())
            return
        }
        isEvaluating = true
        defer { isEvaluating = false }
        switch await evaluate(code) {
        case .ignore:
            return
        case .warn(let kind):
            lastRejected = (code, Date())
            U2Haptics.error()
            withU2Animation(U2Motion.state) { warning = kind }
            var title = Self.texts(for: kind).title
            title.locale = locale
            AccessibilityNotification.Announcement(String(localized: title)).post()
        case .accept:
            lastRejected = nil
            torchOn = false
            withU2Animation(U2Motion.state) { warning = nil }
            await onAccept()
        }
    }

    /// Set by the view from `\.locale`, so announcements use the in-app language.
    @ObservationIgnored var locale = Locale(identifier: "tr")
}

extension QRScannerModel {
    /// Title and message of a warning card.
    static func texts(for warning: Warning) -> (title: LocalizedStringResource, message: LocalizedStringResource) {
        switch warning {
        case .mismatch:
            (StringTable.verification.resource("scan.mismatch.title"),
             StringTable.verification.resource("scan.mismatch.message"))
        case .unrecognized:
            (StringTable.verification.resource("scan.unrecognized.title"),
             StringTable.verification.resource("scan.unrecognized.message"))
        case .ownCode:
            (StringTable.verification.resource("scan.ownCode.title"),
             StringTable.verification.resource("scan.ownCode.message"))
        }
    }
}
