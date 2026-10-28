import SwiftUI

/// C23 field chrome: r16, `surface` (on a `surface` sheet: `bg`). Focused:
/// inner 1.5 pt `accent` ring + outer glow `0 0 26px -10px accent` (`.glow`),
/// or the ring alone (`.ring`, 7.2 password). The ring fades in/out softly.
struct U2FieldChrome: ViewModifier {
    enum Focus: Sendable {
        case glow
        case ring
    }

    var isFocused: Bool
    var focus: Focus = .glow
    var fill: Color = Color.u2.surface
    var ringColor: Color = Color.u2.accent
    var height: CGFloat

    func body(content: Content) -> some View {
        let shape = RoundedRectangle(cornerRadius: U2Radius.field, style: .continuous)
        content
            .frame(height: height)
            .background(shape.fill(fill))
            .overlay(shape.strokeBorder(ringColor, lineWidth: 1.5).opacity(isFocused ? 1 : 0))
            .background {
                if focus == .glow {
                    Color.clear
                        .u2BoxShadow(.init(color: ringColor, blur: 26, spread: -10), in: shape)
                        .opacity(isFocused ? 1 : 0)
                }
            }
            .u2Animation(U2Motion.state, value: isFocused)
    }
}

/// The trailing status glyph of a username field.
enum UsernameFieldStatus: Equatable, Sendable {
    case none
    case checking
    case positive
    case negative
}

/// C23 username field (2.1: 60 high, Mono 19, pad 0 16, on `surface`):
/// `text·68%` “@” prefix, the name in Mono, accent caret, trailing
/// status glyph (`ph:check-circle` fill `online` / `ph:x-circle` fill `danger`
/// / a softly spinning `ph:circle-notch`), or a clear button.
///
/// No autocorrect, no auto-capitalisation, no smart punctuation, ASCII
/// keyboard. What is typed is shown in lower case (`UsernameRules.displayForm`)
/// and otherwise kept as it is — nothing is dropped, converted or cut, so a
/// name that breaks a rule stays visible and the screen says which.
struct UsernameField: View {
    @Binding var text: String
    var status: UsernameFieldStatus = .none
    var showsClearButton = false
    var prompt: Text? = nil
    /// Focus the field when it appears (2.1 and 4.2 open with the keyboard up).
    var focusesOnAppear = false
    var onSubmit: () -> Void = {}

    @FocusState private var isFocused: Bool
    @State private var didAutoFocus = false
    @Environment(\.u2LayerMotion) private var layerMotion
    /// The insertion point is a caret at the end of the name (the usual case):
    /// the design's caret is drawn and the system one hidden. Anywhere else
    /// (the user moved it, or selected text) the system caret shows.
    @State private var caretAtEnd = true
    /// The name's width and the input's. Nothing typed is cut, so a long
    /// name (24 characters nearly fill the field) can be wider than the
    /// input: it scrolls, and the system caret shows where it ends.
    @State private var textWidth: CGFloat = 0
    @State private var inputWidth: CGFloat = .infinity

    private let style = U2TextStyle.monoInputL

    /// The design's caret: focused, at the end of a name that fits.
    private var drawsCaret: Bool { isFocused && caretAtEnd && textWidth + 5 <= inputWidth }

    var body: some View {
        HStack(spacing: 2) {
            // Both 1 pt higher than SwiftUI's natural placement: the design's
            // glyph rows (“@” 294.33–314.33, the name from 293.67).
            Text(verbatim: "@")
                .u2Font(style, matchLineBox: false)
                .foregroundStyle(Color.u2.textSecondary)
                .offset(y: -1)
                .accessibilityHidden(true)
            UsernameTextInput(text: $text, prompt: prompt, caretAtEnd: $caretAtEnd)
            .u2Font(style, matchLineBox: false)
            .background(alignment: .leading) {
                Text(verbatim: text).u2Font(style, matchLineBox: false).fixedSize().hidden()
                    .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { textWidth = $0 }
            }
            .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { inputWidth = $0 }
            .foregroundStyle(Color.u2.text)
            .tint(drawsCaret ? Color.clear : Color.u2.accent400)
            .offset(y: -1)
            .keyboardType(.asciiCapable)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .textContentType(.username)
            .submitLabel(.continue)
            .focused($isFocused)
            .onSubmit(onSubmit)
            .onChange(of: text) {
                // After the keystroke's own update: a change made inside it
                // is not always drawn (a lone “K” stayed “K”). Read when it
                // runs, so a key typed meanwhile is kept.
                Task { @MainActor in
                    let shown = UsernameRules.displayForm(text)
                    if shown != text { text = shown }
                }
            }
            trailing
        }
        .overlay(alignment: .leading) {
            if drawsCaret {
                // The design's caret (2 × 24, r1, `accent400`): value end +
                // gap 2 + margin 1, centred in the field.
                HStack(spacing: 0) {
                    Text(verbatim: "@").u2Font(style, matchLineBox: false).hidden()
                    Text(verbatim: text).u2Font(style, matchLineBox: false).hidden()
                        .padding(.leading, 2)
                    // Where the design's renderer painted it (147.0, 291.0 —
                    // its 147.42 / 291.44 snapped to the device pixels).
                    BlinkingCaret()
                        .padding(.leading, 3)
                        .offset(x: -2.0 / 3.0, y: -1.0 / 3.0)
                }
                .fixedSize()
                .allowsHitTesting(false)
                .accessibilityHidden(true)
            }
        }
        .padding(.leading, 16)
        .padding(.trailing, 16)
        .modifier(U2FieldChrome(isFocused: isFocused, fill: Color.u2.surface, height: U2Size.fieldUsername))
        .contentShape(Rectangle())
        .onTapGesture { isFocused = true }
        .onChange(of: layerMotion?.isSettled ?? true, initial: true) { _, settled in
            // Raise the keyboard once the push has arrived (2.1): setting the
            // keyboard up mid-push costs the transition a long frame.
            guard focusesOnAppear, settled, !didAutoFocus else { return }
            didAutoFocus = true
            isFocused = true
        }
    }

    @ViewBuilder private var trailing: some View {
        let size: CGFloat = 22
        ZStack {
            if showsClearButton && !text.isEmpty {
                Button {
                    text = ""
                } label: {
                    PhosphorIcon(.xCircle, weight: .fill, size: 20)
                        .foregroundStyle(Color.u2.textTertiary)
                        .u2ExpandedHitArea(horizontal: 12, vertical: 12)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text("a11y.clear"))
            } else {
                switch status {
                case .none:
                    EmptyView()
                case .checking:
                    SpinningGlyph(size: size).foregroundStyle(Color.u2.textSecondary)
                case .positive:
                    PhosphorIcon(.checkCircle, weight: .fill, size: size)
                        .foregroundStyle(Color.u2.online)
                case .negative:
                    PhosphorIcon(.xCircle, weight: .fill, size: size)
                        .foregroundStyle(Color.u2.danger)
                }
            }
        }
        .transition(.u2Fade)
        .u2Animation(U2Motion.micro, value: status)
    }
}

/// The 2.1 text input. On iOS 18 it follows the selection, so the caller
/// knows whether the insertion point is a plain caret at the end.
private struct UsernameTextInput: View {
    @Binding var text: String
    let prompt: Text?
    @Binding var caretAtEnd: Bool

    var body: some View {
        if #available(iOS 18.0, *) {
            SelectionTrackingTextField(text: $text, prompt: prompt, caretAtEnd: $caretAtEnd)
        } else {
            TextField(text: $text, prompt: prompt) { Text("a11y.username") }
        }
    }
}

@available(iOS 18.0, *)
private struct SelectionTrackingTextField: View {
    @Binding var text: String
    let prompt: Text?
    @Binding var caretAtEnd: Bool
    @State private var selection: TextSelection?

    var body: some View {
        TextField(text: $text, selection: $selection, prompt: prompt) { Text("a11y.username") }
            .onChange(of: selection) { _, _ in update() }
            .onChange(of: text) { _, _ in update() }
    }

    private func update() {
        let atEnd: Bool
        switch selection?.indices {
        case .selection(let range)?:
            atEnd = range.isEmpty && range.lowerBound == text.endIndex
        case nil:
            atEnd = true
        default:
            atEnd = false
        }
        if atEnd != caretAtEnd { caretAtEnd = atEnd }
    }
}

/// `ph:circle-notch` turning once per 1.2 s, linear (7.3); static under Reduce Motion.
struct SpinningGlyph: View {
    var size: CGFloat = 15
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.u2Clock) private var clock

    var body: some View {
        if reduceMotion || clock.isFrozen {
            PhosphorIcon(.circleNotch, size: size)
        } else {
            TimelineView(.animation) { context in
                let turns = context.date.timeIntervalSinceReferenceDate / 1.2
                PhosphorIcon(.circleNotch, size: size)
                    .rotationEffect(.degrees(turns.truncatingRemainder(dividingBy: 1) * 360))
            }
        }
    }
}

// MARK: - Password field (7.2, 7.3)

/// Which backup-password field has focus.
enum PasswordFieldFocus: Hashable {
    case password
    case confirmation
}

/// The 7.2 / 7.3 password field: 56 high, r16, `surface`, pad 0 16, gap 10 —
/// 9 × 9 `text` dots (gap 7) while hidden; the ring (`inset 0 0 0 1.5px
/// accent`, no glow) while focused or `highlighted`, `danger` on error.
///
/// With an external focus binding, submit label/action, a trailing status,
/// the ring without focus (the reference state), `.privacySensitive()` and
/// the eye exactly where it is drawn (x 350). A `SecureField` holds the
/// text; the dots are drawn over it. A shown password is hidden again when
/// the app leaves the foreground, and cannot be shown while `revealAllowed`
/// is off (the screen is recorded or mirrored).
struct PasswordField: View {
    enum Trailing: Equatable {
        /// `ph:eye` / `ph:eye-slash` 20 `text·68%` — reveal.
        case reveal
        /// `ph:check-circle` fill 20 `online` (7.3 accepted, 7.2 confirmation matches).
        case accepted
        /// A softly spinning `ph:circle-notch` (7.3 checking).
        case checking
    }

    @Binding var text: String
    var focus: FocusState<PasswordFieldFocus?>.Binding
    let field: PasswordFieldFocus
    var prompt: Text? = nil
    var trailing: Trailing = .reveal
    var isError = false
    /// Ring without keyboard focus (the reference screenshots).
    var isHighlighted = false
    var isDisabled = false
    var submitLabel: SubmitLabel = .done
    var contentType: UITextContentType = .password
    /// Off while the screen is recorded or mirrored: the eye does nothing
    /// and the text stays dots.
    var revealAllowed = true
    var onSubmit: () -> Void = {}

    @Environment(\.scenePhase) private var scenePhase
    @State private var isRevealed = false

    private var isFocused: Bool { focus.wrappedValue == field }
    private var showsText: Bool { isRevealed && revealAllowed }

    var body: some View {
        let ringVisible = isError || isFocused || isHighlighted
        HStack(spacing: 10) {
            ZStack(alignment: .leading) {
                if showsText {
                    TextField(text: $text, prompt: prompt?.foregroundStyle(Color.u2.textPlaceholder)) { Text("a11y.password") }
                        .u2Font(.rowTitle, matchLineBox: false)
                        .foregroundStyle(Color.u2.text)
                        .tint(Color.u2.accent400)
                        .textContentType(contentType)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .focused(focus, equals: field)
                        .submitLabel(submitLabel)
                        .onSubmit(onSubmit)
                } else {
                    SecureField(text: $text, prompt: prompt?.foregroundStyle(Color.u2.textPlaceholder)) { Text("a11y.password") }
                        .u2Font(.rowTitle, matchLineBox: false)
                        .foregroundStyle(Color.clear)
                        .tint(Color.clear)
                        .textContentType(contentType)
                        .focused(focus, equals: field)
                        .submitLabel(submitLabel)
                        .onSubmit(onSubmit)
                        .overlay(alignment: .leading) {
                            MaskedDots(count: text.count, showsCaret: isFocused)
                                .fixedSize()
                                .allowsHitTesting(false)
                        }
                        .clipped()
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .privacySensitive()
            trailingView
                .frame(width: 20, height: 20)
        }
        .padding(.horizontal, 16)
        .frame(height: U2Size.fieldPassword)
        .background(RoundedRectangle(cornerRadius: U2Radius.field, style: .continuous).fill(Color.u2.surface))
        .overlay(
            RoundedRectangle(cornerRadius: U2Radius.field, style: .continuous)
                .strokeBorder(isError ? Color.u2.danger : Color.u2.accent, lineWidth: 1.5)
                .opacity(ringVisible ? 1 : 0)
                .allowsHitTesting(false)
        )
        .u2Animation(U2Motion.state, value: ringVisible)
        .u2Animation(U2Motion.state, value: isError)
        .contentShape(Rectangle())
        .onTapGesture { if !isDisabled { focus.wrappedValue = field } }
        .disabled(isDisabled)
        // Hidden again before the app-switcher snapshot and the lock, and
        // as soon as a recording or mirroring starts.
        .onChange(of: scenePhase) { _, phase in
            if phase != .active { isRevealed = false }
        }
        .onChange(of: revealAllowed) { _, allowed in
            if !allowed { isRevealed = false }
        }
    }

    @ViewBuilder private var trailingView: some View {
        ZStack {
            switch trailing {
            case .reveal:
                Button {
                    guard revealAllowed else { return }
                    isRevealed.toggle()
                    focus.wrappedValue = field
                } label: {
                    PhosphorIcon(showsText ? .eyeSlash : .eye, size: 20)
                        .foregroundStyle(Color.u2.textSecondary)
                        .contentShape(Rectangle().inset(by: -12))
                }
                .buttonStyle(.plain)
                .disabled(!revealAllowed)
                .accessibilityLabel(showsText ? Text("a11y.password.hide") : Text("a11y.password.show"))
                .transition(.u2Fade)
            case .accepted:
                PhosphorIcon(.checkCircle, weight: .fill, size: 20)
                    .foregroundStyle(Color.u2.online)
                    .transition(.u2Pop)
                    .accessibilityLabel(Text("a11y.password.accepted"))
            case .checking:
                SpinningGlyph(size: 20)
                    .foregroundStyle(Color.u2.textSecondary)
                    .transition(.u2Fade)
            }
        }
        .u2Animation(U2Motion.micro, value: trailing)
    }
}

// MARK: - 4.2 search field

/// The 4.2 exact-username field (C23 at 56 pt, Mono 18, on the sheet's `bg`):
/// `text·68%` “@”, the name, the accent caret, and either the soft spinner
/// (while the exact lookup runs) or the clear button.
///
/// Unlike `UsernameField` (2.1, the user's *own* name) the keyboard may offer
/// nothing here: no `.username` content type (that would put the user's *own* saved
/// logins in the QuickType bar), no suggestions, no autocorrection, no
/// auto-capitalisation, ASCII keyboard, “Ara” return key. The text is never
/// rewritten: the lookup normalizes it (`UsernameRules.normalize`).
struct UsernameSearchField: View {
    @Binding var text: String
    var isSearching = false
    var focusesOnAppear = false
    var onSubmit: () -> Void = {}
    #if DEBUG
        /// Screenshot harness: draws the focused chrome without raising the keyboard.
        var forcesFocusChrome = false
    #endif

    @FocusState private var isFocused: Bool
    @State private var didAutoFocus = false
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.u2SheetSettled) private var sheetSettled

    private var showsFocus: Bool {
        #if DEBUG
            return isFocused || forcesFocusChrome
        #else
            return isFocused
        #endif
    }

    var body: some View {
        HStack(spacing: 2) {
            // The “@” sits in the design's line box (27.9 for Mono 18, Chrome's
            // baseline); the field's text shares its baseline.
            HStack(alignment: .firstTextBaseline, spacing: 2) {
                Text(verbatim: "@")
                    .u2Font(.monoInput)
                    .foregroundStyle(Color.u2.textSecondary)
                    .accessibilityHidden(true)
                TextField(text: $text, prompt: Text("newChat.placeholder", table: .chats).foregroundStyle(Color.u2.textPlaceholder)) {
                    Text("a11y.username")
                }
                .font(Font(U2TextStyle.monoInput.uiFont(scale: U2TextStyle.monoInput.scale(for: dynamicTypeSize)) as CTFont))
                .foregroundStyle(Color.u2.text)
                .tint(Color.u2.accent400)
                .keyboardType(.asciiCapable)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .textContentType(nil)
                .submitLabel(.search)
                .focused($isFocused)
                .onSubmit(onSubmit)
                .accessibilityIdentifier("newChat.field")
            }
            #if DEBUG
                .overlay(alignment: .leading) {
                    if forcesFocusChrome && !isFocused {
                        // The design's caret (2 × 24, r1, `accent400`) after the
                        // name, for the keyboard-less harness state only.
                        HStack(spacing: 0) {
                            Text(verbatim: "@\(text)").u2Font(.monoInput).hidden()
                            RoundedRectangle(cornerRadius: U2Radius.caret)
                                .fill(Color.u2.accent400)
                                .frame(width: 2, height: 24)
                                .padding(.leading, 5)
                        }
                        .allowsHitTesting(false)
                        .accessibilityHidden(true)
                    }
                }
            #endif
            trailing
        }
        .padding(.leading, 16)
        .padding(.trailing, 14)
        .modifier(U2FieldChrome(isFocused: showsFocus, fill: Color.u2.bg, height: U2Size.fieldSearch))
        .contentShape(Rectangle())
        .onTapGesture { isFocused = true }
        .onChange(of: sheetSettled, initial: true) { _, settled in
            // Raise the keyboard only once the sheet has finished rising:
            // setting up the keyboard mid-rise stalls the main thread and
            // the sheet would freeze, then jump.
            guard focusesOnAppear, settled, !didAutoFocus else { return }
            didAutoFocus = true
            isFocused = true
        }
    }

    @ViewBuilder private var trailing: some View {
        ZStack {
            if isSearching {
                SpinningGlyph(size: 20)
                    .foregroundStyle(Color.u2.textSecondary)
                    .accessibilityLabel(Text("newChat.searching", table: .chats))
                    .transition(.u2Fade)
            } else if !text.isEmpty {
                Button {
                    text = ""
                    isFocused = true
                } label: {
                    PhosphorIcon(.xCircle, weight: .fill, size: 20)
                        .foregroundStyle(Color.u2.textTertiary)
                        .frame(width: 32, height: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .padding(.horizontal, -6)
                .accessibilityLabel(Text("a11y.clear"))
                .transition(.u2Fade)
            }
        }
        .frame(width: 20, height: 20)
        .u2Animation(U2Motion.micro, value: isSearching)
        .u2Animation(U2Motion.micro, value: text.isEmpty)
    }
}

/// Password dots: 9 × 9 `text`, gap 7, with the accent caret after the last.
private struct MaskedDots: View {
    let count: Int
    let showsCaret: Bool

    var body: some View {
        HStack(spacing: 7) {
            ForEach(0..<min(count, 40), id: \.self) { _ in
                Circle().fill(Color.u2.text).frame(width: 9, height: 9)
                    .transition(.u2Pop)
            }
            if showsCaret {
                BlinkingCaret()
            }
        }
        .u2Animation(U2Motion.micro, value: count)
        .accessibilityHidden(true)
    }
}

/// The 2 × 24 r1 `accent400` caret, blinking softly.
struct BlinkingCaret: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.u2Clock) private var clock

    var body: some View {
        // Steady under Reduce Motion and in frozen-clock (design comparison) runs.
        let steady = reduceMotion || clock.isFrozen
        RoundedRectangle(cornerRadius: U2Radius.caret)
            .fill(Color.u2.accent400)
            .frame(width: 2, height: 24)
            .phaseAnimator([1.0, 0.0]) { caret, opacity in
                caret.opacity(steady ? 1 : opacity)
            } animation: { _ in
                .easeInOut(duration: 0.55)
            }
    }
}

#Preview("Text fields") {
    struct Demo: View {
        @State private var username = "selin_n"
        @State private var search = "deniz"
        @State private var password = "correct horse"
        @FocusState private var focus: PasswordFieldFocus?
        var body: some View {
            VStack(spacing: 20) {
                UsernameField(text: $username, status: .positive)
                UsernameSearchField(text: $search)
                PasswordField(text: $password, focus: $focus, field: .password)
            }
            .padding(20)
            .frame(maxHeight: .infinity)
            .background(Color.u2.bg)
        }
    }
    return Demo()
}
