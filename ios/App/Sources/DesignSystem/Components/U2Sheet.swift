import SwiftUI
import UIKit

/// C18 sheet styles.
enum U2SheetStyle: Sendable, Equatable {
    /// 4.2, 5.4: starts at design y 58 (4 pt under the status bar), top radius 38.
    case fullHeight
    /// 5.5: hugs its content, top radius 38, 40 pt bottom padding.
    case content
    /// 3.3: floating — inset 8 on the sides and bottom, r42, shadow
    /// `0 -12px 40px #000@35%`, over the dimmed lock screen.
    case floating
}

extension View {
    /// Presents a C18 sheet: an opaque backdrop (`mix(bg 55%, #05060c)`, or
    /// the 3.3 dim) fades in over 0.55 s while the sheet rises on the
    /// critically damped spring; it follows the finger down and dismisses past
    /// a third of its height. Under Reduce Motion both simply cross-fade.
    ///
    /// It is a full-screen presentation with a clear background, so it covers
    /// everything (headers, the composer) and traps VoiceOver focus.
    ///
    /// What the sheet shows must reach `content` live — from an `@Observable`
    /// model, or as the item of `u2Sheet(item:)`. SwiftUI evaluates `content`
    /// with the presenting view's `@State` as of that view's last body pass:
    /// state set in the same tap as `isPresented` and never read by the body
    /// itself arrives stale (in 1.0.7 the profile picture sheet rose empty,
    /// a bare grabber, the first time it was opened).
    func u2Sheet<SheetContent: View>(
        isPresented: Binding<Bool>, style: U2SheetStyle = .fullHeight, isDismissible: Bool = true,
        onDismiss: (() -> Void)? = nil, @ViewBuilder content: @escaping () -> SheetContent
    ) -> some View {
        modifier(
            U2SheetModifier(
                isPresented: isPresented, style: style, isDismissible: isDismissible, onDismiss: onDismiss,
                sheetContent: content))
    }

    /// Item-based `u2Sheet`.
    func u2Sheet<Item: Identifiable & Equatable, SheetContent: View>(
        item: Binding<Item?>, style: U2SheetStyle = .fullHeight, isDismissible: Bool = true,
        onDismiss: (() -> Void)? = nil, @ViewBuilder content: @escaping (Item) -> SheetContent
    ) -> some View {
        modifier(U2ItemSheetModifier(item: item, style: style, isDismissible: isDismissible, onDismiss: onDismiss, sheetContent: content))
    }
}

private struct U2ItemSheetModifier<Item: Identifiable & Equatable, SheetContent: View>: ViewModifier {
    @Binding var item: Item?
    let style: U2SheetStyle
    let isDismissible: Bool
    let onDismiss: (() -> Void)?
    let sheetContent: (Item) -> SheetContent
    @State private var shownItem: Item?

    func body(content: Content) -> some View {
        content
            .u2Sheet(
                isPresented: Binding(get: { item != nil }, set: { if !$0 { item = nil } }),
                style: style, isDismissible: isDismissible,
                onDismiss: {
                    shownItem = nil
                    onDismiss?()
                }
            ) {
                if let shown = shownItem ?? item {
                    sheetContent(shown)
                }
            }
            .onChange(of: item) { _, new in
                if let new { shownItem = new }
            }
    }
}

private struct U2SheetModifier<SheetContent: View>: ViewModifier {
    @Binding var isPresented: Bool
    let style: U2SheetStyle
    let isDismissible: Bool
    let onDismiss: (() -> Void)?
    let sheetContent: () -> SheetContent

    /// The full-screen presentation hosting the sheet; flips without animation.
    @State private var isCoverPresented = false

    func body(content: Content) -> some View {
        content
            .fullScreenCover(isPresented: $isCoverPresented, onDismiss: onDismiss) {
                U2SheetContainer(
                    isPresented: $isPresented, style: style, isDismissible: isDismissible,
                    finish: {
                        var transaction = Transaction()
                        transaction.disablesAnimations = true
                        withTransaction(transaction) { isCoverPresented = false }
                    },
                    content: sheetContent
                )
                .presentationBackground(.clear)
            }
            .onChange(of: isPresented, initial: true) { _, presented in
                guard presented, !isCoverPresented else { return }
                var transaction = Transaction()
                transaction.disablesAnimations = true
                withTransaction(transaction) { isCoverPresented = true }
            }
    }
}

private struct U2SheetContainer<SheetContent: View>: View {
    @Binding var isPresented: Bool
    let style: U2SheetStyle
    let isDismissible: Bool
    let finish: () -> Void
    let content: () -> SheetContent

    @State private var isShown = false
    /// The backdrop has its own flag: it fades out on `U2Motion.state` while
    /// the sheet travels down over the longer sheet duration.
    @State private var isBackdropShown = false
    /// The rise has finished (published as `\.u2SheetSettled`: the 4.2 field
    /// raises the keyboard only then, so the rise never stalls).
    @State private var isSettled = false
    @State private var dragOffset: CGFloat = 0
    /// The sheet's own drawn height (nil until the first layout).
    @State private var sheetHeight: CGFloat?
    /// Set when a downward fling dismissed the sheet: its speed (pt/s), so
    /// the sheet carries on at the finger's pace instead of starting again
    /// from rest.
    @State private var releaseVelocity: CGFloat?
    @State private var hasResignedForDrag = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// How far the sheet moves to be just off screen: its height plus the
    /// floating inset (+1), so the whole curve plays on screen. Before the
    /// first measurement: the container's height (never more than that).
    private var hiddenOffset: CGFloat {
        guard let sheetHeight else { return containerHeight ?? 1_000 }
        return sheetHeight + (style == .floating ? 8 : 0) + 1 + flingMargin
    }
    /// Extra travel past the bottom edge after a fling: the spring's long
    /// tail then plays off screen, so the sheet is gone when it completes.
    @State private var flingMargin: CGFloat = 0
    @State private var containerHeight: CGFloat?

    var body: some View {
        U2Screen(background: .clear) {
            ZStack(alignment: .bottom) {
                backdrop
                    .opacity(isBackdropShown ? max(0, 1 - Double(dragOffset / max(sheetHeight ?? 600, 1)) * 0.6) : 0)
                    .ignoresSafeArea()
                    .onTapGesture { if isDismissible && style != .fullHeight { isPresented = false } }
                    .accessibilityHidden(true)
                sheet
                    .offset(y: isShown ? max(0, dragOffset) : (reduceMotion ? 0 : hiddenOffset))
                    .opacity(reduceMotion && !isShown ? 0 : 1)
            }
            .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { containerHeight = $0 }
        }
        .onAppear {
            // The rise starts one run-loop turn after the sheet appears: its
            // first frame — presenting the cover, building the content (4.2
            // creates its model up front), measuring the sheet — is the
            // heaviest, and it is spent with the sheet parked just off screen
            // behind a clear backdrop (nothing visible). The entrance then
            // plays from rest with every frame, from the sheet's measured
            // height, instead of jumping its first ~15 % while that frame was
            // being made.
            DispatchQueue.main.async { rise() }
        }
        .onChange(of: isPresented) { _, presented in
            guard !presented else { return }
            dismissAnimated()
        }
        .accessibilityAction(.escape) {
            if isDismissible { isPresented = false }
        }
    }

    private func rise() {
        guard isPresented, !isShown else { return }
        withAnimation(reduceMotion ? U2Motion.reducedCrossFade : U2Motion.sheet) { isBackdropShown = true }
        // A finite entrance publishes readiness at the end of the sheet duration;
        // keyboard focus never waits for a spring's settling tail.
        let end = U2Signposts.animation("sheetRise", category: .presentation)
        withAnimation(reduceMotion ? U2Motion.reducedCrossFade : U2Motion.sheet, completionCriteria: .removed) {
            isShown = true
        } completion: {
            end()
            isSettled = true
        }
    }

    private var backdrop: some View {
        Group {
            switch style {
            case .fullHeight, .content:
                Color.u2.sheetBackdrop
            case .floating:
                // 3.3: the lock content at 30 % (= 70 % bg over it) under #06070e@55%.
                ZStack {
                    Color.u2.bg.opacity(1 - U2Opacity.lockBehindSheet)
                    Color.u2.lockOverlay
                }
            }
        }
    }

    @ViewBuilder private var sheet: some View {
        let shape = sheetShape
        VStack(spacing: 0) {
            SheetGrabber()
                .padding(.top, style == .floating ? 10 : 8)
            content()
                .environment(\.u2SheetSettled, isSettled)
        }
        .frame(maxWidth: .infinity, alignment: .top)
        .frame(maxHeight: style == .fullHeight ? .infinity : nil, alignment: .top)
        .background(shape.fill(Color.u2.surface).ignoresSafeArea(edges: style == .floating ? [] : .bottom))
        .clipShape(shape)
        .background {
            if style == .floating {
                Color.clear.u2BoxShadow(.sheetFloating, in: shape)
            }
        }
        .padding(.horizontal, style == .floating ? 8 : 0)
        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { sheetHeight = $0 }
        .modifier(SheetPlacement(style: style))
        .gesture(dismissDrag)
        .accessibilityAddTraits(.isModal)
    }

    private var sheetShape: some InsettableShape {
        let top = style == .floating ? U2Radius.sheetFloating : U2Radius.sheet
        let bottom: CGFloat = style == .floating ? U2Radius.sheetFloating : 0
        return SheetShape(top: top, bottom: bottom)
    }

    /// Follows the finger down; on release it either goes (past a third of
    /// its height, or flung) or settles back into place — both at the
    /// finger's own speed on the critically damped spring, so the hand-off
    /// is seamless and nothing bounces.
    private var dismissDrag: some Gesture {
        DragGesture(minimumDistance: 8)
            .onChanged { value in
                guard isDismissible else { return }
                let dy = value.translation.height
                // Rubber-band upwards, follow the finger downwards.
                dragOffset = dy > 0 ? dy : -sqrt(-dy) * 2
                // A raised keyboard leaves as soon as the sheet is pulled
                // down, so letting go never waits for it.
                if dy > 12, !hasResignedForDrag {
                    hasResignedForDrag = true
                    UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
                }
            }
            .onEnded { value in
                hasResignedForDrag = false
                guard isDismissible else { return }
                let projected = value.predictedEndTranslation.height
                let height = sheetHeight ?? 600
                if value.translation.height > height / 3 || projected > height * 0.6 {
                    releaseVelocity = value.velocity.height
                    isPresented = false
                } else {
                    let offset = max(0, dragOffset)
                    let velocity = U2Motion.relativeVelocity(-value.velocity.height, remaining: offset)
                    withAnimation(U2Motion.resolve(U2Motion.spring(initialVelocity: velocity), reduceMotion: reduceMotion)) {
                        dragOffset = 0
                    }
                }
            }
    }

    /// “Dismiss reverses”: the sheet travels down on the design curve over
    /// the sheet duration — from rest, the whole way on
    /// screen — while the backdrop fades over `U2Motion.state`. Dragged
    /// down and let go, it continues from the finger's speed instead (the
    /// critically damped spring, until it is really off screen).
    private func dismissAnimated() {
        isSettled = false
        // A raised keyboard leaves together with the sheet, not after it.
        UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
        if let velocity = releaseVelocity, !reduceMotion {
            // Let go mid-fling: on at once, at the finger's speed (the
            // keyboard, if any, already left when the drag began).
            releaseVelocity = nil
            flingMargin = 48
            let remaining = hiddenOffset - max(0, dragOffset)
            goDown(U2Motion.spring(initialVelocity: U2Motion.relativeVelocity(velocity, remaining: remaining)))
        } else {
            // From rest: the frame that lowers the keyboard and re-lays the
            // content out passes first, with the sheet still in place; the
            // descent then plays with every frame.
            releaseVelocity = nil
            DispatchQueue.main.async {
                goDown(reduceMotion ? U2Motion.reducedCrossFade : U2Motion.sheet)
            }
        }
    }

    private func goDown(_ animation: Animation) {
        withAnimation(reduceMotion ? U2Motion.reducedCrossFade : U2Motion.state) { isBackdropShown = false }
        let end = U2Signposts.animation("sheetDismiss", category: .presentation)
        withAnimation(animation, completionCriteria: .logicallyComplete) {
            isShown = false
        } completion: {
            end()
            dragOffset = 0
            flingMargin = 0
            finish()
        }
    }
}

extension EnvironmentValues {
    /// Inside a `u2Sheet`: false while the sheet is still rising (or going
    /// down). Content that raises the keyboard waits for it. Outside sheets: true.
    @Entry var u2SheetSettled = true
}

/// Places the sheet: full height from design y 58; content / floating at the bottom.
private struct SheetPlacement: ViewModifier {
    let style: U2SheetStyle
    @Environment(\.u2SafeArea) private var safeArea

    func body(content: Content) -> some View {
        switch style {
        case .fullHeight:
            content
                .padding(.top, safeArea.top(58))
                .frame(maxHeight: .infinity, alignment: .bottom)
        case .content:
            content
                .frame(maxHeight: .infinity, alignment: .bottom)
        case .floating:
            content
                .padding(.bottom, 8)
                .frame(maxHeight: .infinity, alignment: .bottom)
        }
    }
}

/// Top-rounded (and, floating, bottom-rounded) sheet outline.
private struct SheetShape: InsettableShape {
    let top: CGFloat
    let bottom: CGFloat
    var inset: CGFloat = 0

    func path(in rect: CGRect) -> Path {
        UnevenRoundedRectangle(
            topLeadingRadius: max(0, top - inset), bottomLeadingRadius: max(0, bottom - inset),
            bottomTrailingRadius: max(0, bottom - inset), topTrailingRadius: max(0, top - inset), style: .continuous
        )
        .path(in: rect.insetBy(dx: inset, dy: inset))
    }

    func inset(by amount: CGFloat) -> SheetShape {
        var copy = self
        copy.inset += amount
        return copy
    }
}

/// The grabber: 38 × 5, r3, `text·25%`, centred.
struct SheetGrabber: View {
    var body: some View {
        Capsule()
            .fill(Color.u2.grabber)
            .frame(width: U2Size.grabberWidth, height: U2Size.grabberHeight)
            .accessibilityHidden(true)
    }
}

// MARK: - Toast (proposed)

extension View {
    /// A short confirmation floating under the top edge (fade + 8 pt drop, 2.4 s).
    func u2Toast(_ message: Binding<Text?>) -> some View {
        modifier(U2ToastModifier(message: message))
    }
}

private struct U2ToastModifier: ViewModifier {
    @Binding var message: Text?
    @State private var dismissTask: Task<Void, Never>?

    func body(content: Content) -> some View {
        content
            .overlay(alignment: .top) {
                // 8 pt under the status bar (design y 70), also over screens
                // drawn edge to edge: `U2Screen` knows the window's insets.
                U2Screen(background: .clear) {
                    if let message {
                        message
                            .u2Font(.label15Emphasis)
                            .foregroundStyle(Color.u2.text)
                            .padding(.horizontal, 18)
                            .padding(.vertical, 11)
                            .u2Backdrop(.banner, in: Capsule())
                            .u2InnerStroke(Color.u2.strokeCard, width: 1, in: Capsule())
                            .u2BoxShadow(.floatingBanner, in: Capsule())
                            .u2Top(70)
                            .transition(.u2Drop)
                            .accessibilityAddTraits(.isStaticText)
                            .onTapGesture { self.message = nil }
                    }
                }
            }
            .u2Animation(U2Motion.state, value: message == nil)
            .onChange(of: message == nil) { _, isNil in
                dismissTask?.cancel()
                guard !isNil else { return }
                dismissTask = Task { @MainActor in
                    try? await Task.sleep(for: .seconds(2.4))
                    guard !Task.isCancelled else { return }
                    self.message = nil
                }
            }
    }
}

#Preview("Sheets") {
    struct Demo: View {
        @State private var style: U2SheetStyle?
        var body: some View {
            U2Screen {
                VStack(spacing: 12) {
                    Spacer()
                    Button { style = .fullHeight } label: { U2ButtonLabel(verbatim: "Full height (4.2)") }.buttonStyle(.u2Secondary)
                    Button { style = .content } label: { U2ButtonLabel(verbatim: "Content (5.5)") }.buttonStyle(.u2Secondary)
                    Button { style = .floating } label: { U2ButtonLabel(verbatim: "Floating (3.3)") }.buttonStyle(.u2Secondary)
                    Spacer()
                }
                .padding(20)
            }
            .u2Sheet(isPresented: Binding(get: { style != nil }, set: { if !$0 { style = nil } }), style: style ?? .content) {
                VStack(spacing: 16) {
                    Text(verbatim: "Ghost mod").u2Font(.rowTitleEmphasis).foregroundStyle(Color.u2.text)
                    Button { style = nil } label: { U2ButtonLabel(verbatim: "Kaydet") }.buttonStyle(.u2Primary)
                }
                .padding(EdgeInsets(top: 24, leading: 18, bottom: 0, trailing: 18))
                .u2Bottom(40)
            }
        }
    }
    return Demo()
}
