@preconcurrency import MessageUI
import UIKit

/// Hands a report's e-mail to whatever can send it on this phone.
@MainActor
protocol ReportMailing {
    func deliver(_ mail: ReportMail) async -> ReportDelivery
}

/// Like the invite's e-mail channel: Mail inside the app when it can send
/// (`MFMailComposeViewController`, presented from the top of the main window
/// — over the report sheet — through `SystemPresenter`; the app stays
/// active, so neither the privacy cover nor the lock comes up while it is
/// open), else another mail app through `mailto:` (it opens outside the
/// app, so the lock follows on return, as after anything else), else the
/// address to copy (`.addressShown`: the simulator, a phone without any
/// mail app).
struct SystemReportMailer: ReportMailing {
    func deliver(_ mail: ReportMail) async -> ReportDelivery {
        #if DEBUG
            // `-U2ReportMail address`: the copy-the-address path whatever is
            // installed (UI tests, screenshots).
            if UserDefaults.standard.string(forKey: "U2ReportMail") == "address" { return .addressShown }
        #endif
        if MFMailComposeViewController.canSendMail() {
            let controller = MFMailComposeViewController()
            controller.setToRecipients([ReportMail.address])
            controller.setSubject(mail.subject)
            controller.setMessageBody(mail.body, isHTML: false)
            return await ReportComposeDelegate.present(controller)
        }
        if let url = mail.mailtoURL, await UIApplication.shared.open(url) {
            return .handedOff
        }
        return .addressShown
    }
}

#if DEBUG
    /// Harness: the copy-the-address path, whatever this phone has.
    struct ReportAddressOnlyMailer: ReportMailing {
        func deliver(_ mail: ReportMail) async -> ReportDelivery { .addressShown }
    }
#endif

/// Keeps Mail's delegate alive while it is on screen, dismisses it when the
/// user sends or cancels, and reports how it ended. A draft kept in Mail is
/// not a report sent.
@MainActor
private final class ReportComposeDelegate: NSObject, MFMailComposeViewControllerDelegate {
    private static var current: ReportComposeDelegate?
    private var continuation: CheckedContinuation<ReportDelivery, Never>?

    static func present(_ controller: MFMailComposeViewController) async -> ReportDelivery {
        await withCheckedContinuation { continuation in
            let delegate = ReportComposeDelegate()
            delegate.continuation = continuation
            current = delegate
            controller.mailComposeDelegate = delegate
            Task { @MainActor in
                // Nothing settled to present from: nothing was shown.
                if await !SystemPresenter.present(controller) { delegate.finish(.failed) }
            }
        }
    }

    nonisolated func mailComposeController(
        _ controller: MFMailComposeViewController, didFinishWith result: MFMailComposeResult, error: (any Error)?
    ) {
        let delivery: ReportDelivery =
            switch result {
            case .sent: .sent
            case .cancelled, .saved: .notSent
            default: .failed
            }
        MainActor.assumeIsolated {
            controller.dismiss(animated: true) {
                MainActor.assumeIsolated { self.finish(delivery) }
            }
        }
    }

    private func finish(_ delivery: ReportDelivery) {
        continuation?.resume(returning: delivery)
        continuation = nil
        if Self.current === self { Self.current = nil }
    }
}
