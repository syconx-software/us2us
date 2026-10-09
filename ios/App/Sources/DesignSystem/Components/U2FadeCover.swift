import SwiftUI

/// A full-screen cover that fades in (0.36 s on the design curve) and fades
/// out instead of the system slide — flows.md: 4.1 / 4.2 / 6.1 → 6.2 and
/// 5.1 → 5.6, “full-screen cover, fade 0.36 s”. The cover has a clear
/// presentation background, so fading reveals the screen underneath (a 6.2
/// match reveals 6.3 that way, over 0.5 s).
///
/// No swipe dismissal: only the content's own close button (or the
/// VoiceOver escape gesture, when `onEscape` is given) ends it — 5.6 and 6.2
/// must not close by accident.
extension View {
    func u2FadeCover<CoverContent: View>(
        isPresented: Binding<Bool>, fadeOut: @escaping () -> Double = { U2Motion.Duration.state },
        onEscape: (() -> Void)? = nil, @ViewBuilder content: @escaping () -> CoverContent
    ) -> some View {
        modifier(FadeCoverModifier(isPresented: isPresented, fadeOut: fadeOut, onEscape: onEscape, coverContent: content))
    }

    /// The same, for an item: shown while `item` is set (the cover keeps the
    /// item it was opened with while it fades out).
    func u2FadeCover<Item: Identifiable & Equatable, CoverContent: View>(
        item: Binding<Item?>, onEscape: (() -> Void)? = nil, @ViewBuilder content: @escaping (Item) -> CoverContent
    ) -> some View {
        modifier(FadeCoverItemModifier(item: item, onEscape: onEscape, coverContent: content))
    }
}

private struct FadeCoverModifier<CoverContent: View>: ViewModifier {
    @Binding var isPresented: Bool
    let fadeOut: () -> Double
    let onEscape: (() -> Void)?
    let coverContent: () -> CoverContent
    /// The system presentation, held until the fade-out has finished.
    @State private var isHosted = false

    func body(content: Content) -> some View {
        content
            .fullScreenCover(isPresented: $isHosted) {
                FadeCoverContainer(isPresented: isPresented, fadeOut: fadeOut, onEscape: onEscape, finish: unhost) {
                    coverContent()
                }
                .presentationBackground(.clear)
            }
            .onChange(of: isPresented, initial: true) { _, presented in
                guard presented, !isHosted else { return }
                withoutAnimation { isHosted = true }
            }
    }

    private func unhost() {
        withoutAnimation { isHosted = false }
    }
}

private struct FadeCoverItemModifier<Item: Identifiable & Equatable, CoverContent: View>: ViewModifier {
    @Binding var item: Item?
    let onEscape: (() -> Void)?
    let coverContent: (Item) -> CoverContent
    @State private var hosted: Item?

    func body(content: Content) -> some View {
        content
            .fullScreenCover(item: $hosted) { shown in
                FadeCoverContainer(
                    isPresented: item != nil, fadeOut: { U2Motion.Duration.state }, onEscape: onEscape,
                    finish: { withoutAnimation { hosted = nil } }
                ) {
                    coverContent(shown)
                }
                .presentationBackground(.clear)
            }
            .onChange(of: item, initial: true) { _, new in
                guard let new, hosted == nil else { return }
                withoutAnimation { hosted = new }
            }
    }
}

/// Fades its content in on appear and out once `isPresented` turns false,
/// then lets the system presentation go (without its slide).
private struct FadeCoverContainer<CoverContent: View>: View {
    let isPresented: Bool
    let fadeOut: () -> Double
    let onEscape: (() -> Void)?
    let finish: () -> Void
    @ViewBuilder let content: CoverContent
    @State private var isShown = false

    var body: some View {
        content
            .opacity(isShown ? 1 : 0)
            .onAppear {
                withAnimation(U2Motion.soft(U2Motion.Duration.state)) { isShown = true }
            }
            .onChange(of: isPresented) { _, presented in
                guard !presented else { return }
                withAnimation(U2Motion.soft(fadeOut()), completionCriteria: .logicallyComplete) {
                    isShown = false
                } completion: {
                    finish()
                }
            }
            .modifier(EscapeAction(action: onEscape))
    }
}

private struct EscapeAction: ViewModifier {
    let action: (() -> Void)?

    func body(content: Content) -> some View {
        if let action {
            content.accessibilityAction(.escape, action)
        } else {
            content
        }
    }
}

@MainActor
private func withoutAnimation(_ change: () -> Void) {
    var transaction = Transaction()
    transaction.disablesAnimations = true
    withTransaction(transaction, change)
}
