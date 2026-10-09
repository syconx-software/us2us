import LinkPresentation
@preconcurrency import MessageUI
import SwiftUI
import UIKit

/// The ways to send an invitation. The recipient is always picked in the
/// system's own UI (Messages, Mail, WhatsApp, the share sheet): us2us never
/// reads, asks for or uploads the address book, and never learns whom the
/// user invited.
enum InviteChannel: String, CaseIterable, Identifiable, Sendable {
    /// Messages (`MFMessageComposeViewController`).
    case sms
    /// `https://wa.me/?text=…`.
    case whatsApp = "whatsapp"
    /// Mail (`MFMailComposeViewController`), else `mailto:`.
    case email
    /// The system share sheet.
    case more
    /// The link to the pasteboard (local only, expires).
    case copyLink = "copy"

    var id: String { rawValue }
}

/// What came of a channel, as far as the app can know.
enum InviteOutcome: Equatable, Sendable {
    /// Messages / Mail sent it, or the share sheet finished an activity.
    case sent
    /// The link is on the pasteboard.
    case copied
    /// Another app took over (WhatsApp, a mail app via `mailto:`); the result is not known.
    case handedOff
    case cancelled
    case failed
}

/// Presents the channels from the main window's top view controller (above
/// any `u2Sheet`), like `SystemShareSheet`. Every channel that cannot work on
/// this device falls back to the share sheet, so nothing is a dead end.
@MainActor
enum InviteSender {
    static func send(_ channel: InviteChannel, message: InviteMessage, completion: @escaping @MainActor (InviteOutcome) -> Void) {
        switch channel {
        case .sms:
            guard MFMessageComposeViewController.canSendText(), let presenter = topViewController() else {
                return share(message, completion: completion)
            }
            let controller = MFMessageComposeViewController()
            controller.body = message.text
            ComposeDelegate.present(controller, from: presenter, completion: completion)
        case .email:
            guard MFMailComposeViewController.canSendMail(), let presenter = topViewController() else {
                return openOrShare(InviteChannelURL.mailto(subject: message.subject, body: message.text), message, completion: completion)
            }
            let controller = MFMailComposeViewController()
            controller.setSubject(message.subject)
            controller.setMessageBody(message.text, isHTML: false)
            ComposeDelegate.present(controller, from: presenter, completion: completion)
        case .whatsApp:
            openOrShare(InviteChannelURL.whatsApp(text: message.text), message, completion: completion)
        case .more:
            share(message, completion: completion)
        case .copyLink:
            InvitePasteboard.copy(message.link)
            completion(.copied)
        }
    }

    /// The share sheet with the text (and the subject for Mail).
    static func share(_ message: InviteMessage, completion: @escaping @MainActor (InviteOutcome) -> Void) {
        SystemShareSheet.present([InviteActivityItem(message: message)], excluding: [.addToReadingList, .assignToContact]) { completed in
            completion(completed ? .sent : .cancelled)
        }
    }

    private static func openOrShare(_ url: URL?, _ message: InviteMessage, completion: @escaping @MainActor (InviteOutcome) -> Void) {
        guard let url else { return share(message, completion: completion) }
        UIApplication.shared.open(url, options: [:]) { opened in
            Task { @MainActor in
                if opened { completion(.handedOff) } else { share(message, completion: completion) }
            }
        }
    }

    static func topViewController() -> UIViewController? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        // The main window: the normal-level key window (the security window sits above it).
        let windows = scenes.flatMap(\.windows).filter { $0.windowLevel == .normal && !$0.isHidden }
        guard var top = (windows.first(where: \.isKeyWindow) ?? windows.first)?.rootViewController else { return nil }
        while let presented = top.presentedViewController, !presented.isBeingDismissed {
            top = presented
        }
        return top
    }
}

/// Keeps the compose controller's delegate alive while it is on screen and
/// dismisses it when the user sends or cancels.
@MainActor
private final class ComposeDelegate: NSObject, MFMessageComposeViewControllerDelegate, MFMailComposeViewControllerDelegate {
    private static var current: ComposeDelegate?
    private let completion: @MainActor (InviteOutcome) -> Void

    private init(completion: @escaping @MainActor (InviteOutcome) -> Void) {
        self.completion = completion
    }

    static func present(_ controller: UIViewController, from presenter: UIViewController, completion: @escaping @MainActor (InviteOutcome) -> Void) {
        let delegate = ComposeDelegate(completion: completion)
        current = delegate
        if let message = controller as? MFMessageComposeViewController { message.messageComposeDelegate = delegate }
        if let mail = controller as? MFMailComposeViewController { mail.mailComposeDelegate = delegate }
        presenter.present(controller, animated: true)
    }

    nonisolated func messageComposeViewController(_ controller: MFMessageComposeViewController, didFinishWith result: MessageComposeResult) {
        let outcome: InviteOutcome =
            switch result {
            case .sent: .sent
            case .cancelled: .cancelled
            default: .failed
            }
        MainActor.assumeIsolated { finish(controller, outcome) }
    }

    nonisolated func mailComposeController(_ controller: MFMailComposeViewController, didFinishWith result: MFMailComposeResult, error: (any Error)?) {
        let outcome: InviteOutcome =
            switch result {
            case .sent, .saved: .sent
            case .cancelled: .cancelled
            default: .failed
            }
        MainActor.assumeIsolated { finish(controller, outcome) }
    }

    private func finish(_ controller: UIViewController, _ outcome: InviteOutcome) {
        let completion = completion
        controller.dismiss(animated: true) {
            MainActor.assumeIsolated {
                completion(outcome)
                ComposeDelegate.current = nil
            }
        }
    }
}

/// The share sheet's item: the text for every activity, the subject for
/// Mail, and a header built locally (subject + link; nothing is fetched).
private final class InviteActivityItem: NSObject, UIActivityItemSource {
    private let text: String
    private let subject: String
    private let link: URL

    init(message: InviteMessage) {
        text = message.text
        subject = message.subject
        link = message.link
    }

    func activityViewControllerLinkMetadata(_ activityViewController: UIActivityViewController) -> LPLinkMetadata? {
        let metadata = LPLinkMetadata()
        metadata.title = subject
        metadata.originalURL = link
        metadata.url = link
        return metadata
    }

    func activityViewControllerPlaceholderItem(_ activityViewController: UIActivityViewController) -> Any { text }

    func activityViewController(_ activityViewController: UIActivityViewController, itemForActivityType activityType: UIActivity.ActivityType?) -> Any? {
        text
    }

    func activityViewController(_ activityViewController: UIActivityViewController, subjectForActivityType activityType: UIActivity.ActivityType?) -> String {
        subject
    }
}
