import SwiftUI
import UIKit

/// us2us — a privacy-first 1:1 messenger (AGPL-3.0).
///
/// The SwiftUI scene renders `RootView` in the main window; `SceneDelegate`
/// adds the security window (lock + privacy cover) and forwards the scene
/// lifecycle to `AppModel`, which needs the earliest UIKit callbacks (the
/// cover must be in place before the app-switcher snapshot).
@main
struct Us2usApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(AppEnvironment.shared.model)
        }
    }
}

/// Process-wide owner of the app model (shared by the SwiftUI scene and the
/// UIKit scene delegate).
@MainActor
final class AppEnvironment {
    static let shared = AppEnvironment()

    let model: AppModel

    private init() {
        U2Fonts.registerBundledFonts()
        model = AppModel(configuration: .fromLaunchArguments(), preferences: AppPreferences())
        #if DEBUG
            FrameProbe.shared.installIfRequested()
        #endif
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication, configurationForConnecting connectingSceneSession: UISceneSession,
        options: UIScene.ConnectionOptions
    ) -> UISceneConfiguration {
        let configuration = UISceneConfiguration(name: nil, sessionRole: connectingSceneSession.role)
        configuration.delegateClass = SceneDelegate.self
        return configuration
    }
}

/// Receives the scene lifecycle (SwiftUI still owns the main window).
final class SceneDelegate: NSObject, UIWindowSceneDelegate {
    private var security: SecurityOverlayController?

    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options connectionOptions: UIScene.ConnectionOptions) {
        guard let windowScene = scene as? UIWindowScene else { return }
        MainActor.assumeIsolated {
            let model = AppEnvironment.shared.model
            let controller = SecurityOverlayController(windowScene: windowScene, model: model)
            security = controller
            model.securityWindow = controller
            if model.overlay != .none {
                // Launching locked: the lock is up before any content renders.
                controller.show()
            }
        }
    }

    func sceneWillResignActive(_ scene: UIScene) {
        MainActor.assumeIsolated {
            ScreenAwakeController.shared.setActive(false)
            let mainWindow = (scene as? UIWindowScene)?.windows.first { $0.windowLevel == .normal }
            AppEnvironment.shared.model.sceneWillResignActive {
                PrivacySnapshot.capture(mainWindow)
            }
        }
    }

    func sceneDidEnterBackground(_ scene: UIScene) {
        MainActor.assumeIsolated {
            ScreenAwakeController.shared.setActive(false)
            AppEnvironment.shared.model.sceneDidEnterBackground()
        }
    }

    func sceneWillEnterForeground(_ scene: UIScene) {
        MainActor.assumeIsolated { AppEnvironment.shared.model.sceneWillEnterForeground() }
    }

    func sceneDidBecomeActive(_ scene: UIScene) {
        MainActor.assumeIsolated {
            let model = AppEnvironment.shared.model
            model.sceneDidBecomeActive()
            ScreenAwakeController.shared.configure(
                preference: model.preferences.keepScreenOn,
                contentVisible: model.phase == .unlocked && model.overlay == .none)
            ScreenAwakeController.shared.setActive(true)
        }
    }
}
