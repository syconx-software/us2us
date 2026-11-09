import SwiftUI

extension StringTable {
    /// `Features/Terms/Terms.xcstrings` (Turkish source + English).
    static let terms = StringTable(name: "Terms")
}

/// The Terms of Use (App Review 1.2: the EULA users agree to, with no
/// tolerance for objectionable content or abusive users). The full text is
/// on us2us.app (`WebsitePage.terms`); the app shows its key rules
/// (`TermsSheet`) and keeps, on this phone, which version was accepted.
enum TermsOfUse {
    /// The version the app asks for. A new one asks everyone again once.
    static let version = "2026-11-09"
}

extension AppPreferences {
    /// The current terms were accepted on this phone.
    var hasAcceptedCurrentTerms: Bool { acceptedTermsVersion == TermsOfUse.version }

    /// “Kabul ediyorum”: the current version, kept on this phone.
    func acceptTerms() {
        acceptedTermsVersion = TermsOfUse.version
    }
}

extension AppModel {
    /// An account is open here but the current terms were never accepted on
    /// this phone (it was set up by an older build, or arrived by a restore
    /// that began on the lock): the terms cover the app until accepted.
    var requiresTermsAcceptance: Bool {
        phase == .unlocked && !preferences.hasAcceptedCurrentTerms
    }
}

// MARK: - Onboarding

/// The three ways into us2us from 1.1–1.3.
enum OnboardingStart: String, Identifiable, Sendable {
    /// “Hesabımı oluştur” → 2.1.
    case create
    /// “Yedekten geri yükle” → 7.3.
    case restore
    /// “Eski telefondan taşı” → this phone's code for the old phone.
    case transfer

    var id: String { rawValue }
}

/// 1.1–1.3 ask for the terms before any way in: the chosen way waits while
/// the terms sheet is up, goes on once they are accepted (after the sheet
/// has gone down) and is dropped when the sheet is closed without them.
@Observable
@MainActor
final class OnboardingTermsGate {
    /// The way in waiting for the terms (the sheet is up while it is set).
    var pending: OnboardingStart?
    /// Accepted: taken once the sheet has gone down.
    @ObservationIgnored private var accepted: OnboardingStart?
    @ObservationIgnored private let isAccepted: @MainActor () -> Bool
    @ObservationIgnored private let accept: @MainActor () -> Void
    @ObservationIgnored private let perform: @MainActor (OnboardingStart) -> Void

    /// - Parameters:
    ///   - isAccepted: the current terms were accepted on this phone.
    ///   - accept: keeps the acceptance.
    ///   - perform: takes the way in.
    init(
        isAccepted: @escaping @MainActor () -> Bool, accept: @escaping @MainActor () -> Void,
        perform: @escaping @MainActor (OnboardingStart) -> Void
    ) {
        self.isAccepted = isAccepted
        self.accept = accept
        self.perform = perform
    }

    /// A button of 1.1–1.3: at once when the terms were accepted, else the terms first.
    func request(_ start: OnboardingStart) {
        guard pending == nil else { return }
        if isAccepted() {
            perform(start)
        } else {
            accepted = nil
            pending = start
        }
    }

    /// “Kabul ediyorum”: kept, and the sheet goes down.
    func agree() {
        guard let start = pending else { return }
        accept()
        accepted = start
        pending = nil
    }

    /// “Vazgeç” (or the sheet pulled down): nothing is kept, nothing goes on.
    func decline() {
        accepted = nil
        pending = nil
    }

    /// The sheet has gone: the accepted way in goes on, from 1.1–1.3.
    func sheetDidDismiss() {
        guard let start = accepted else { return }
        accepted = nil
        perform(start)
    }
}

extension OnboardingFlowModel {
    /// One of 1.1–1.3's ways in.
    func start(_ start: OnboardingStart) {
        switch start {
        case .create: startSetup()
        case .restore: startRestore()
        case .transfer: startTransfer()
        }
    }
}
