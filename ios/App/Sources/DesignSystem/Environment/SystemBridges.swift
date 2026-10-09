import SwiftUI
import UIKit

// Small bridges to UIKit that more than one screen needs.

// MARK: - App state & keyboard

extension UIApplication {
    /// The app is frontmost and interactive (biometric prompts need that).
    @MainActor static var isActive: Bool { shared.applicationState == .active }
}

@MainActor
enum U2Keyboard {
    /// Ends editing wherever it is (2.1 → 2.2, a tap outside a field, 5.3 opening).
    static func dismiss() {
        UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
    }
}

// MARK: - System share sheet

/// Presents the system share sheet from the main window's top view
/// controller (not inside a SwiftUI sheet, so it keeps the system's own
/// presentation) and reports whether the items were saved or sent:
/// 6.1 › share (the digits as text), 7.2 › Dışa aktar (the `.u2u` file).
@MainActor
enum SystemShareSheet {
    static func present(
        _ items: [Any], excluding excluded: [UIActivity.ActivityType] = [],
        completion: (@MainActor (Bool) -> Void)? = nil
    ) {
        guard let presenter = topViewController() else {
            completion?(false)
            return
        }
        let controller = UIActivityViewController(activityItems: items, applicationActivities: nil)
        controller.excludedActivityTypes = excluded
        controller.completionWithItemsHandler = { _, completed, _, _ in
            Task { @MainActor in completion?(completed) }
        }
        if let popover = controller.popoverPresentationController {
            popover.sourceView = presenter.view
            popover.sourceRect = CGRect(x: presenter.view.bounds.midX, y: presenter.view.bounds.maxY - 80, width: 1, height: 1)
        }
        presenter.present(controller, animated: true)
    }

    private static func topViewController() -> UIViewController? {
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

// MARK: - Forced interface style (5.6, 6.2)

/// Forces the interface style of the presentation this view is in: 5.6 is
/// always dark with a light status bar, whatever the app theme. SwiftUI's
/// `preferredColorScheme` does not reach a full-screen cover here (the app
/// theme is a window override), so the cover's own controller is set.
struct PresentationInterfaceStyle: UIViewRepresentable {
    let style: UIUserInterfaceStyle

    func makeUIView(context: Context) -> ProbeView {
        let view = ProbeView()
        view.style = style
        return view
    }

    func updateUIView(_ view: ProbeView, context: Context) {
        view.style = style
        view.apply()
    }

    final class ProbeView: UIView {
        var style: UIUserInterfaceStyle = .unspecified

        override func didMoveToWindow() {
            super.didMoveToWindow()
            apply()
        }

        func apply() {
            var responder: UIResponder? = self
            while let next = responder?.next {
                if let controller = next as? UIViewController {
                    // The cover's own hosting controller (presented, full screen).
                    let target = controller.presentingViewController != nil ? controller : (controller.parent ?? controller)
                    if target.overrideUserInterfaceStyle != style {
                        target.overrideUserInterfaceStyle = style
                        target.setNeedsStatusBarAppearanceUpdate()
                    }
                    return
                }
                responder = next
            }
        }
    }
}

// MARK: - Screen capture (5.6)

/// Reports whether the screen is being recorded, mirrored or AirPlayed
/// (`UITraitCollection.sceneCaptureState`) — 5.6 hides view-once media then.
struct ScreenCaptureProbe: UIViewRepresentable {
    let onChange: (Bool) -> Void

    func makeUIView(context: Context) -> ProbeView {
        let view = ProbeView()
        view.onChange = onChange
        return view
    }

    func updateUIView(_ view: ProbeView, context: Context) {
        view.onChange = onChange
    }

    final class ProbeView: UIView {
        var onChange: ((Bool) -> Void)?
        private var last: Bool?

        override init(frame: CGRect) {
            super.init(frame: frame)
            isUserInteractionEnabled = false
            registerForTraitChanges([UITraitSceneCaptureState.self]) { (view: ProbeView, _: UITraitCollection) in
                view.report()
            }
        }

        required init?(coder: NSCoder) { nil }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            report()
        }

        fileprivate func report() {
            guard window != nil else { return }
            let captured = traitCollection.sceneCaptureState == .active
            guard captured != last else { return }
            last = captured
            Task { @MainActor [weak self] in self?.onChange?(captured) }
        }
    }
}
