import SwiftUI
import UIKit

/// Observe the bubble without covering its links or controls with another hit target.
/// The native long press fails when the surrounding scroll view takes the movement.
struct MessagePressObserver: UIViewRepresentable {
    let enabled: Bool
    let onPressing: (Bool) -> Void
    let onLongPress: (CGRect) -> Void

    func makeUIView(context: Context) -> MessagePressAnchor { MessagePressAnchor() }
    func updateUIView(_ view: MessagePressAnchor, context: Context) {
        // Disabling/detaching a recognizer can reset it during a SwiftUI update.
        // Queue visual feedback on the main queue instead of changing State in that update.
        view.press.onPressing = { [onPressing] pressing in
            DispatchQueue.main.async { onPressing(pressing) }
        }
        view.onLongPress = onLongPress
        view.press.isEnabled = enabled
    }
    static func dismantleUIView(_ view: MessagePressAnchor, coordinator: ()) { view.detach() }
}

final class MessagePressAnchor: UIView, UIGestureRecognizerDelegate {
    let press = MessagePressRecognizer()
    var onLongPress: (CGRect) -> Void = { _ in }

    init() {
        super.init(frame: .zero)
        isUserInteractionEnabled = false
        isAccessibilityElement = false
        press.minimumPressDuration = 0.4
        press.allowableMovement = 12
        press.delegate = self
        press.addTarget(self, action: #selector(pressed))
    }
    required init?(coder: NSCoder) { nil }

    override func didMoveToWindow() { super.didMoveToWindow(); attach() }
    override func didMoveToSuperview() { super.didMoveToSuperview(); attach() }

    private func attach() {
        guard window != nil, let superview else { detach(); return }
        var ancestor: UIView? = superview
        while let candidate = ancestor, !(candidate is UIScrollView) { ancestor = candidate.superview }
        let target = ancestor ?? superview
        if press.view !== target { detach(); target.addGestureRecognizer(press) }
    }
    func detach() { press.view?.removeGestureRecognizer(press) }

    func gestureRecognizer(_ recognizer: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
        window != nil && bounds.contains(touch.location(in: self))
    }
    @objc private func pressed() {
        if press.state == .began {
            press.onPressing(false)
            onLongPress(convert(bounds, to: nil))
        }
    }
}

final class MessagePressRecognizer: UILongPressGestureRecognizer {
    var onPressing: (Bool) -> Void = { _ in }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent) {
        super.touchesBegan(touches, with: event)
        onPressing(true)
    }
    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent) {
        super.touchesMoved(touches, with: event)
        if state == .failed || state == .cancelled { onPressing(false) }
    }
    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent) {
        super.touchesEnded(touches, with: event)
        onPressing(false)
    }
    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent) {
        super.touchesCancelled(touches, with: event)
        onPressing(false)
    }
    override func reset() { super.reset(); onPressing(false) }
}
