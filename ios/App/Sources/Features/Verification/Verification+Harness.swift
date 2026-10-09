import SwiftUI

/// Opens every 06 screen and state directly (DEBUG builds): the state is the
/// route's suffix (`-U2Route safety:deniz/scan`) or, equivalently,
/// `-U2Verification scan` with `-U2Route safety:deniz`:
///
/// ```sh
/// xcrun simctl launch <sim> com.syconx.us2us -U2Route safety:deniz/scan \
///   -U2FrozenClock YES -U2Theme dark -U2Language tr
/// ```
///
/// | state | screen |
/// |---|---|
/// | `safety` (or none) | 6.1 as drawn (unverified) |
/// | `safety-verified` | 6.1 already verified: seal on the line, pill, “Doğrulamayı kaldır” |
/// | `changed` | 6.1 with “Güvenlik numarası değişti” |
/// | `confirm` | 6.1 with the “Doğrulandı olarak işaretle” confirmation |
/// | `confirm-remove` | 6.1 verified, with the removal confirmation |
/// | `scan` | 6.2 scanning (design placeholder on the simulator) |
/// | `mismatch` | 6.2 after a wrong code: “Numaralar uyuşmuyor” |
/// | `mycode` | 6.2 “Kendi kodumu göster” |
/// | `denied` | 6.2 without camera access |
/// | `torch` | 6.2 with the torch on |
/// | `verified` | 6.3 after a QR match (“yüz yüze”) |
/// | `verified-manual` | 6.3 after marking manually (“elle işaretlendi”) |
/// | `contact-scan` | 6.2 from 4.1 / 4.2 “QR kodla ekle” |
/// | `contact-unknown` / `contact-own` | … after an unknown code / the user's own code |
/// | `contact-mycode` | … “Kendi kodumu göster” (the contact code) |
/// | `contact-verified` | … 6.3 after adding deniz by QR |
/// | `flow-match` | plays 6.1 → 6.2 → (simulated match) → 6.3 → 5.1, 4 s per step |
/// | `flow-mismatch` | plays 6.1 → 6.2 → (wrong code) → my code → scanning → close |
/// | `flow-manual` | plays 6.1 → confirmation → 6.3 → 5.1 |
/// | `flow-remove` | plays 6.1 verified → removal confirmation → unverified |
/// | `flow-contact` | plays 6.2 (add) → (simulated match) → 6.3 → 5.1 |
///
/// The `contact-*` states show the add-by-QR scanner in its cover over an
/// empty screen (its real hosts, 4.1 and 4.2, belong to the chats feature);
/// close pops back to 4.1. Applied once per launch. Release builds ignore it.
enum VerificationHarness {
    enum State: String {
        case safety
        case safetyVerified = "safety-verified"
        case changed
        case confirm
        case confirmRemove = "confirm-remove"
        case scan
        case mismatch
        case myCode = "mycode"
        case denied
        case torch
        case verified
        case verifiedManual = "verified-manual"
        case contactScan = "contact-scan"
        case contactUnknown = "contact-unknown"
        case contactOwn = "contact-own"
        case contactMyCode = "contact-mycode"
        case contactVerified = "contact-verified"
        case flowMatch = "flow-match"
        case flowMismatch = "flow-mismatch"
        case flowManual = "flow-manual"
        case flowRemove = "flow-remove"
        case flowContact = "flow-contact"

        var isContact: Bool { rawValue.hasPrefix("contact-") || self == .flowContact }
    }

    static let state: State? = {
        #if DEBUG
            if let value = UserDefaults.standard.string(forKey: "U2Verification") { return State(rawValue: value) }
            return LaunchRoute.current?.state(for: .safety).flatMap(State.init(rawValue:))
        #else
            nil
        #endif
    }()

    /// Scripted 6.1 flows run with 5.1 under 6.1 (“Sohbete dön” pops to it).
    static var startsFromChat: Bool { [.flowMatch, .flowManual, .flowMismatch, .flowRemove].contains(state) }

    /// Whether 6.1's route shows the add-by-QR scanner instead (harness only).
    static var showsContactScanner: Bool { state?.isContact ?? false }

    /// 6.2 › camera access denied.
    static var forcesCameraDenied: Bool { state == .denied }

    @MainActor private static var applied = false

    /// One step of a scripted flow (screenshots are taken in between).
    private static func step() async {
        try? await Task.sleep(for: .seconds(4))
    }

    // MARK: 6.1 → 6.2 → 6.3

    @MainActor
    static func apply(to model: VerificationModel) async {
        #if DEBUG
            guard !applied, let state, !state.isContact else { return }
            applied = true
            let scanner = model.scanner
            switch state {
            case .safety:
                break
            case .changed:
                await (model.messenger as? DesignFixturesService)?.setSafetyNumberChanged(model.chatID)
                await model.load()
            case .safetyVerified:
                await model.messenger.markVerified(model.chatID, method: .faceToFace)
                await model.load()
            case .confirm:
                model.requestMarkVerified()
            case .confirmRemove:
                await model.messenger.markVerified(model.chatID, method: .faceToFace)
                await model.load()
                model.requestRemoval()
            case .scan, .denied:
                model.presentScanner()
            case .torch:
                model.presentScanner()
                scanner.setTorch(true)
            case .myCode:
                model.presentScanner()
                scanner.showOwnCode()
            case .mismatch:
                model.presentScanner()
                try? await Task.sleep(for: .milliseconds(600))
                await scanner.handle(scanner.simulatedCodes().mismatch)
            case .verified, .verifiedManual:
                await model.messenger.markVerified(model.chatID, method: state == .verified ? .faceToFace : .manual)
                await model.load()
                model.verifiedEntrance = true
                model.showsVerified = true
            case .flowMatch:
                await step()
                model.presentScanner()
                await step()
                if let code = scanner.simulatedCodes().match { await scanner.handle(code) }
                await step()
                model.exitToChat?()
            case .flowMismatch:
                await step()
                model.presentScanner()
                await step()
                await scanner.handle(scanner.simulatedCodes().mismatch)
                await step()
                scanner.showOwnCode()
                await step()
                scanner.showScanning()
                await step()
                scanner.close()
            case .flowManual:
                await step()
                model.requestMarkVerified()
                await step()
                model.confirmsMarkVerified = false
                await model.markVerifiedManually()
                await step()
                model.exitToChat?()
            case .flowRemove:
                await model.messenger.markVerified(model.chatID, method: .faceToFace)
                await model.load()
                await step()
                model.requestRemoval()
                await step()
                model.confirmsRemoval = false
                await model.removeVerification()
            case .contactScan, .contactUnknown, .contactOwn, .contactMyCode, .contactVerified, .flowContact:
                break
            }
        #endif
    }

    // MARK: 4.1 / 4.2 → 6.2 → 6.3 → 5.1

    @MainActor
    static func apply(to model: ContactScanModel) async {
        #if DEBUG
            guard !applied, let state, state.isContact else { return }
            applied = true
            let scanner = model.scanner
            switch state {
            case .contactUnknown:
                try? await Task.sleep(for: .milliseconds(600))
                await scanner.handle(scanner.simulatedCodes().mismatch)
            case .contactOwn:
                try? await Task.sleep(for: .milliseconds(600))
                if let own = await model.messenger.ownContactCode() { await scanner.handle(own) }
            case .contactMyCode:
                scanner.showOwnCode()
            case .contactVerified:
                await model.simulateMatch("deniz")
            case .flowContact:
                await step()
                if let code = scanner.simulatedCodes().match { await scanner.handle(code) }
                await step()
                model.finish()
            default:
                break
            }
        #endif
    }
}

#if DEBUG
    /// Harness host of the add-by-QR scanner (`contact-*` states): an empty
    /// screen presenting the scanner in the soft cover, as 4.1 / 4.2 do.
    /// Close pops back to 4.1; “Sohbete dön” replaces this route with 5.1
    /// once the cover has faded.
    struct ContactScanHarnessHost: View {
        @Environment(AppModel.self) private var app
        @Environment(\.locale) private var locale
        @Environment(\.u2Clock) private var clock
        @State private var isPresented = false
        @State private var opened = OpenedChat()

        private final class OpenedChat {
            var chat: ChatID?
        }

        var body: some View {
            U2Screen { Color.clear }
                .u2FadeCover(isPresented: $isPresented, onEscape: close) {
                    ContactQRScanView(onClose: close, onOpenChat: { opened.chat = $0 })
                        .environment(app)
                        .environment(\.locale, locale)
                        .environment(\.u2Clock, clock)
                }
                .task {
                    // Once the route's push has settled (a cover cannot be
                    // presented from a view that is not in a window yet).
                    try? await Task.sleep(for: .milliseconds(400))
                    isPresented = true
                }
        }

        private func close() {
            isPresented = false
            let router = app.router
            if let chat = opened.chat {
                Task {
                    try? await Task.sleep(for: .seconds(U2Motion.Duration.state))
                    router.replaceTop(with: .conversation(chat))
                }
            } else {
                router.pop()
            }
        }
    }
#endif
