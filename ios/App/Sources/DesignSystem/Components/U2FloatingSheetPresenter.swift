import SwiftUI
import UIKit

/// A C18 floating sheet (3.3's) over whatever is on screen right now — the
/// conversation, a viewer's full-screen cover, the file preview — presented
/// from the top view controller, like the share sheet and the Files
/// exporter. It rises and leaves exactly like `u2Sheet(style: .floating)`
/// (it is one, inside a transparent host), follows the app's theme and the
/// given UI language, and goes when the app leaves the foreground (as a
/// cancel). `onDismiss` runs once the sheet is gone, however it went — also
/// when what it was presented over closes underneath it.
@MainActor
enum U2FloatingSheetPresenter {
    private static weak var current: UIViewController?

    /// A sheet is up.
    static var isPresenting: Bool { current != nil }

    /// `false` (and `onDismiss` is not called) when nothing could present it
    /// or another such sheet is already up.
    @discardableResult
    static func present<Content: View>(
        locale: Locale, @ViewBuilder content: @escaping (_ close: @escaping @MainActor () -> Void) -> Content,
        onDismiss: @escaping @MainActor () -> Void
    ) -> Bool {
        guard current == nil, let presenter = SystemPresenter.topViewController() else { return false }
        let link = HostLink()
        func complete(dismissingHost: Bool) {
            guard !link.isFinished else { return }
            link.isFinished = true
            if current === link.host { current = nil }
            guard dismissingHost, let host = link.host, host.presentingViewController != nil, !host.isBeingDismissed
            else {
                onDismiss()
                return
            }
            host.dismiss(animated: false) { onDismiss() }
        }
        let root = FloatingSheetHost(
            content: content,
            finish: { complete(dismissingHost: true) },
            disappeared: {
                // Only when the host itself went (its presenter closed), not
                // when something was presented over it.
                guard let host = link.host else {
                    complete(dismissingHost: false)
                    return
                }
                if host.isBeingDismissed || host.presentingViewController == nil { complete(dismissingHost: false) }
            }
        )
        .environment(\.locale, locale)
        let host = UIHostingController(rootView: root)
        host.view.backgroundColor = .clear
        host.modalPresentationStyle = .overFullScreen
        // The app's theme (a window override, `RootView`), also over a cover
        // that forces dark (the viewers).
        host.overrideUserInterfaceStyle = presenter.view.window?.overrideUserInterfaceStyle ?? .unspecified
        link.host = host
        current = host
        presenter.present(host, animated: false)
        return true
    }

    @MainActor
    private final class HostLink {
        weak var host: UIViewController?
        var isFinished = false
    }
}

/// Transparent: only the sheet (and its backdrop) can be seen.
private struct FloatingSheetHost<Content: View>: View {
    let content: (_ close: @escaping @MainActor () -> Void) -> Content
    let finish: @MainActor () -> Void
    let disappeared: @MainActor () -> Void
    @State private var isPresented = false

    var body: some View {
        Color.clear
            .ignoresSafeArea()
            .u2Sheet(isPresented: $isPresented, style: .floating, onDismiss: { finish() }) {
                content { isPresented = false }
            }
            .onAppear { isPresented = true }
            .onDisappear { disappeared() }
            .onReceive(NotificationCenter.default.publisher(for: UIApplication.didEnterBackgroundNotification)) { _ in
                isPresented = false
            }
    }
}
