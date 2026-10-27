import UIKit

/// One idle-timer writer for the user preference and independent voice requests.
/// A request never overrides the foreground or security-cover requirements.
@MainActor
final class ScreenAwakeController {
    static let shared = ScreenAwakeController { UIApplication.shared.isIdleTimerDisabled = $0 }

    private let apply: (Bool) -> Void
    private var active = false
    private var contentVisible = false
    private var preference = false
    private var mediaOwners = Set<UUID>()
    private var applied = false

    init(apply: @escaping (Bool) -> Void) {
        self.apply = apply
        apply(false)
    }

    func configure(preference: Bool, contentVisible: Bool) {
        self.preference = preference
        self.contentVisible = contentVisible
        update()
    }

    func setActive(_ active: Bool) {
        self.active = active
        update()
    }

    func setMediaActive(_ enabled: Bool, owner: UUID) {
        if enabled { mediaOwners.insert(owner) } else { mediaOwners.remove(owner) }
        update()
    }

    private func update() {
        let next = active && contentVisible && (preference || !mediaOwners.isEmpty)
        guard next != applied else { return }
        applied = next
        apply(next)
    }
}
