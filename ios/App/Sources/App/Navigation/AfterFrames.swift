import UIKit

/// Runs `action` after `count` display frames — i.e. after the work queued
/// for the current frame (a whole screen being built) has been committed and
/// shown. A run-loop hop is not enough: the main queue drains *before* the
/// frame's commit.
@MainActor
final class AfterFrames: NSObject {
    private var remaining: Int
    private var action: (() -> Void)?
    private var link: CADisplayLink?
    private static var running: Set<AfterFrames> = []

    static func run(_ count: Int, _ action: @escaping () -> Void) {
        let waiter = AfterFrames(count: count, action: action)
        running.insert(waiter)
        let link = CADisplayLink(target: waiter, selector: #selector(tick(_:)))
        waiter.link = link
        link.add(to: .main, forMode: .common)
    }

    private init(count: Int, action: @escaping () -> Void) {
        remaining = count
        self.action = action
    }

    @objc private func tick(_ link: CADisplayLink) {
        remaining -= 1
        guard remaining <= 0 else { return }
        link.invalidate()
        self.link = nil
        let action = self.action
        self.action = nil
        Self.running.remove(self)
        action?()
    }
}
