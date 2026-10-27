import SwiftUI

// The design's floating menu (5.3 action menu), used everywhere a menu
// appears: 5.3 actions, the 5.1 “+” menu, the 4.1 QR menu and the 4.3 / 4.4
// “…” menus.

/// One row of a floating menu: 46 high, pad 0 16, label `rowTitle` left
/// (optional `menuHint` under it), 19 pt glyph right.
struct FloatingMenuItem: Identifiable {
    enum Tone {
        case standard
        case destructive
    }

    let id: String
    let title: Text
    var hint: Text? = nil
    let glyph: PhosphorGlyph
    /// `ph:map-pin` is bundled only filled (icons.md).
    var glyphWeight: PhosphorWeight = .regular
    var tone: Tone = .standard
    var isEnabled = true
    /// Starts a new group: a 7 pt `text·6%` band instead of a dotted rule above it.
    var startsGroup = false
    let action: @MainActor () -> Void
}

/// 5.3: `surface·94%` + blur 20, r18, `shadowMd`; rows separated by dotted
/// rules (no inset), groups by a 7 pt `fillSubtle` band.
struct FloatingMenu: View {
    let items: [FloatingMenuItem]
    var width: CGFloat = 250

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: U2Radius.menu, style: .continuous)
        VStack(spacing: 0) {
            ForEach(Array(items.enumerated()), id: \.element.id) { index, item in
                if index > 0 {
                    if item.startsGroup {
                        Rectangle().fill(Color.u2.fillSubtle).frame(height: 7)
                    } else {
                        DottedRule(color: Color.u2.dotRule)
                    }
                }
                FloatingMenuRow(item: item)
            }
        }
        .frame(width: width)
        .u2Backdrop(.menu, in: shape)
        .clipShape(shape)
        .u2ShadowMd(cornerRadius: U2Radius.menu)
    }

    /// Height of a menu with `items` (rows 46, rules 2, group bands 7).
    static func height(of items: [FloatingMenuItem]) -> CGFloat {
        let separators = items.dropFirst().reduce(CGFloat(0)) { $0 + ($1.startsGroup ? 7 : 2) }
        return CGFloat(items.count) * 46 + separators
    }
}

private struct FloatingMenuRow: View {
    let item: FloatingMenuItem

    var body: some View {
        Button(action: item.action) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 1) {
                    item.title
                        .u2Font(.rowTitle)
                        .foregroundStyle(titleColor)
                        .lineLimit(1)
                    if let hint = item.hint {
                        hint
                            .u2Font(.menuHint)
                            .foregroundStyle(Color.u2.textSecondary)
                            .lineLimit(1)
                    }
                }
                Spacer(minLength: 0)
                PhosphorIcon(item.glyph, weight: item.glyphWeight, size: 19)
                    .foregroundStyle(titleColor)
            }
            .padding(.horizontal, 16)
            .frame(height: 46)
            .contentShape(Rectangle())
        }
        .buttonStyle(MenuRowButtonStyle())
        .disabled(!item.isEnabled)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("menu.\(item.id)")
    }

    private var titleColor: Color {
        guard item.isEnabled else { return Color.u2.textDisabled }
        return item.tone == .destructive ? Color.u2.danger : Color.u2.text
    }
}

/// Menu rows highlight softly while pressed (`text·7%`, like C2).
private struct MenuRowButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(Color.u2.text.opacity(configuration.isPressed ? 0.07 : 0))
            .animation(configuration.isPressed ? U2Motion.press : U2Motion.release, value: configuration.isPressed)
    }
}

// MARK: - Anchored under a header button

/// A `FloatingMenu` under a header button (4.1 QR, 4.3 / 4.4 “…”). Opens
/// with a fade + scale from 0.96 anchored at the button's corner; a tap
/// outside (or the escape gesture) closes it. A chosen row closes the menu
/// first and acts once it has faded (whatever the row presents never
/// overlaps the fading menu).
private struct AnchoredMenuModifier: ViewModifier {
    @Binding var isPresented: Bool
    let items: [FloatingMenuItem]
    /// Design y of the menu's top edge.
    let designTop: CGFloat
    let trailing: CGFloat

    @Environment(\.u2SafeArea) private var safeArea

    func body(content: Content) -> some View {
        content
            .overlay(alignment: .topTrailing) {
                if isPresented {
                    ZStack(alignment: .topTrailing) {
                        Color.clear
                            .contentShape(Rectangle())
                            .ignoresSafeArea()
                            .onTapGesture { isPresented = false }
                            .accessibilityHidden(true)
                        FloatingMenu(items: items.map(closingFirst))
                            .accessibilityElement(children: .contain)
                            .accessibilityAddTraits(.isModal)
                            .padding(.top, safeArea.top(designTop))
                            .padding(.trailing, trailing)
                            .transition(.u2MenuPop)
                    }
                    .accessibilityAction(.escape) { isPresented = false }
                }
            }
            .u2Animation(isPresented ? U2Motion.state : U2Motion.micro, value: isPresented)
    }

    private func closingFirst(_ item: FloatingMenuItem) -> FloatingMenuItem {
        let action = item.action
        let close = $isPresented
        return FloatingMenuItem(
            id: item.id, title: item.title, hint: item.hint, glyph: item.glyph, glyphWeight: item.glyphWeight,
            tone: item.tone, isEnabled: item.isEnabled, startsGroup: item.startsGroup
        ) {
            close.wrappedValue = false
            Task { @MainActor in
                try? await Task.sleep(for: .seconds(U2Motion.Duration.micro))
                action()
            }
        }
    }
}

extension View {
    /// Shows a `FloatingMenu` under a header button, its top edge at design
    /// y `designTop`, `trailing` points from the screen's trailing edge.
    func u2Menu(isPresented: Binding<Bool>, items: [FloatingMenuItem], designTop: CGFloat = 106, trailing: CGFloat = 16) -> some View {
        modifier(AnchoredMenuModifier(isPresented: isPresented, items: items, designTop: designTop, trailing: trailing))
    }
}

/// Fade + scale from 0.96 anchored at the top trailing corner (the button);
/// only the fade under Reduce Motion.
private struct MenuPopModifier: ViewModifier {
    let progress: Double
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        content
            .opacity(progress)
            .scaleEffect(reduceMotion ? 1 : 0.96 + 0.04 * progress, anchor: .topTrailing)
            .blur(radius: reduceMotion ? 0 : 2 * (1 - progress))
    }
}

extension AnyTransition {
    /// A menu appearing from its button (fade + scale from 0.96).
    static var u2MenuPop: AnyTransition {
        .modifier(active: MenuPopModifier(progress: 0), identity: MenuPopModifier(progress: 1))
    }
}
