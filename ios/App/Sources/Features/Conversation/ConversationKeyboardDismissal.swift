import SwiftUI
import UIKit

/// A native observer on the conversation's scroll view. It neither covers
/// message controls nor consumes their taps or the existing interactive drag.
struct ConversationKeyboardDismissal: UIViewRepresentable {
    func makeUIView(context: Context) -> ConversationKeyboardAnchor { ConversationKeyboardAnchor() }
    func updateUIView(_ view: ConversationKeyboardAnchor, context: Context) {}
    static func dismantleUIView(_ view: ConversationKeyboardAnchor, coordinator: ()) { view.detach() }
}

final class ConversationKeyboardAnchor: UIView, UIGestureRecognizerDelegate {
    private let tap = ConversationKeyboardTap()

    init() {
        super.init(frame: .zero)
        isUserInteractionEnabled = false
        isAccessibilityElement = false
        tap.cancelsTouchesInView = false
        tap.delaysTouchesBegan = false
        tap.delaysTouchesEnded = false
        tap.delegate = self
        tap.addTarget(self, action: #selector(dismissKeyboard))
    }
    required init?(coder: NSCoder) { nil }

    override func didMoveToWindow() { super.didMoveToWindow(); attach() }
    override func didMoveToSuperview() { super.didMoveToSuperview(); attach() }

    private func attach() {
        guard window != nil else { detach(); return }
        var ancestor = superview
        while let candidate = ancestor, !(candidate is UIScrollView) { ancestor = candidate.superview }
        guard let scrollView = ancestor as? UIScrollView else { detach(); return }
        if tap.view !== scrollView { detach(); scrollView.addGestureRecognizer(tap) }
    }
    func detach() { tap.view?.removeGestureRecognizer(tap) }

    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
        // A safe-area composer may share a hosting ancestor with the list.
        // Tapping an editable field must retain its new focus.
        var view = touch.view
        while let candidate = view {
            if candidate is UITextField { return false }
            if let textView = candidate as? UITextView, textView.isEditable { return false }
            if candidate === tap.view { break }
            view = candidate.superview
        }
        return window != nil
    }

    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
        shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer) -> Bool { true }

    @objc private func dismissKeyboard() {
        guard tap.state == .ended else { return }
        // Let the touched control finish its action before ending editing;
        // dismissing synchronously can move a SwiftUI link before it activates.
        DispatchQueue.main.async { U2Keyboard.dismiss() }
    }
}

/// Observation must never win a failure relationship against a SwiftUI link.
private final class ConversationKeyboardTap: UITapGestureRecognizer {
    override func canPrevent(_ preventedGestureRecognizer: UIGestureRecognizer) -> Bool { false }
    override func canBePrevented(by preventingGestureRecognizer: UIGestureRecognizer) -> Bool { false }
}
