import CoreImage
import SwiftUI
import UIKit

/// Owns the window that shows the lock (03) and the privacy cover (3.4).
///
/// It sits above every other window of the scene (`.alert + 1`), so it
/// covers sheets, full-screen covers and system pickers presented by the app,
/// and it is shown synchronously from `sceneWillResignActive` — before iOS
/// takes the app-switcher snapshot.
@MainActor
final class SecurityOverlayController {
    private let window: UIWindow
    private let host: UIHostingController<AnyView>

    init(windowScene: UIWindowScene, model: AppModel) {
        window = UIWindow(windowScene: windowScene)
        window.windowLevel = .alert + 1
        window.backgroundColor = .clear
        host = UIHostingController(rootView: AnyView(SecurityOverlayView().environment(model)))
        host.view.backgroundColor = .clear
        window.rootViewController = host
        window.isHidden = true
        window.overrideUserInterfaceStyle = model.interfaceStyle
    }

    /// The window that was key before the overlay took over.
    private weak var previousKeyWindow: UIWindow?

    func show() {
        guard window.isHidden else { return }
        // No keyboard (or focused field) may stay above the cover or the lock.
        UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
        previousKeyWindow = window.windowScene?.windows.first { $0.isKeyWindow && $0 !== window }
        window.makeKeyAndVisible()
        // Render now: the snapshot may be taken as soon as we return.
        host.view.setNeedsLayout()
        host.view.layoutIfNeeded()
    }

    func hide() {
        window.isHidden = true
        previousKeyWindow?.makeKey()
    }

    func apply(interfaceStyle: UIUserInterfaceStyle) {
        window.overrideUserInterfaceStyle = interfaceStyle
    }
}

/// Captures the main window for the 3.4 cover: the current screen, blurred.
enum PrivacySnapshot {
    /// The window rendered at half scale and blurred (CSS blur 9 on the
    /// 0.70-scale thumbnail ≈ 13 pt full size). `nil` if capturing fails —
    /// the cover is then a solid background with the dot grid (allowed by 3.4).
    @MainActor
    static func capture(_ window: UIWindow?) -> UIImage? {
        guard let window, window.bounds.width > 0 else { return nil }
        let scale: CGFloat = 0.5
        let format = UIGraphicsImageRendererFormat()
        format.scale = scale
        format.opaque = true
        let renderer = UIGraphicsImageRenderer(bounds: window.bounds, format: format)
        let image = renderer.image { _ in
            window.drawHierarchy(in: window.bounds, afterScreenUpdates: false)
        }
        return blurred(image, sigma: 13 * scale)
    }

    /// Gaussian blur of `image` (σ in image pixels) in the *encoded* sRGB
    /// space, like the browser's CSS `filter: blur()` — Core Image's default
    /// linear working space makes the glow brighter and wider than the design.
    @MainActor
    static func blurred(_ image: UIImage, sigma: CGFloat) -> UIImage? {
        guard let input = CIImage(image: image) else { return nil }
        let blurred = input.clampedToExtent().applyingGaussianBlur(sigma: sigma).cropped(to: input.extent)
        guard let cg = context.createCGImage(blurred, from: input.extent) else { return nil }
        return UIImage(cgImage: cg, scale: image.scale, orientation: .up)
    }

    @MainActor private static let context: CIContext = {
        let sRGB = CGColorSpace(name: CGColorSpace.sRGB) ?? CGColorSpaceCreateDeviceRGB()
        return CIContext(options: [.useSoftwareRenderer: false, .workingColorSpace: sRGB, .outputColorSpace: sRGB])
    }()
}
