import SwiftUI

/// 2.1 · Kullanıcı adı — pick the (immutable) username; availability is
/// checked while typing (debounced), exact-match discovery is explained.
///
/// The keyboard does not move the layout (the design is drawn without it):
/// the keyboard's “Devam” key continues when the name is available, and a
/// tap outside the field hides the keyboard to reveal the button.
struct UsernameStepView: View {
    let model: UsernameStepModel
    let step: (step: Int, count: Int)
    var focusesOnAppear = true
    let onBack: () -> Void
    let onContinue: () -> Void

    @Environment(\.locale) private var locale

    var body: some View {
        U2Screen {
            OnboardingFitOrScroll {
                VStack(spacing: 0) {
                    SetupHeader(step: step.step, stepCount: step.count, onBack: onBack)
                    VStack(alignment: .leading, spacing: U2Spacing.titleToBodyGap) {
                        SetupTitle(
                            StringTable.onboarding.resource("onboarding.username.title"), style: .displayTitleM)
                        LocalizedText("onboarding.username.body", table: .onboarding)
                            .u2Font(.body)
                            .foregroundStyle(Color.u2.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.top, 18)
                    .padding(.horizontal, U2Spacing.screenGutterL)

                    VStack(spacing: 8) {
                        UsernameField(
                            text: Bindable(model).text, status: model.fieldStatus,
                            prompt: StringTable.onboarding.text("onboarding.username.placeholder", locale: locale)
                                .foregroundStyle(Color.u2.textPlaceholder),
                            focusesOnAppear: focusesOnAppear, onSubmit: onContinue
                        )
                        .accessibilityIdentifier("onboarding.username.field")
                        statusRow
                    }
                    .padding(.top, 22)
                    .padding(.horizontal, U2Spacing.screenGutter)

                    VStack(alignment: .leading, spacing: 8) {
                        OnboardingRuleRow(
                            text: StringTable.onboarding.text("onboarding.username.rule.length", locale: locale),
                            isBroken: model.isBroken(.length))
                        OnboardingRuleRow(
                            text: StringTable.onboarding.text("onboarding.username.rule.characters", locale: locale),
                            isBroken: model.isBroken(.characters))
                        OnboardingRuleRow(
                            text: StringTable.onboarding.text("onboarding.username.rule.turkish", locale: locale),
                            isBroken: model.isBroken(.turkishCharacters))
                    }
                    .padding(.top, 14)
                    .padding(.horizontal, U2Spacing.screenGutterL)

                    ExactMatchCard(name: model.canShowOwnExample ? model.name : nil)
                        .padding(.top, 22)
                        .padding(.horizontal, U2Spacing.screenGutter)

                    Spacer(minLength: 0)
                        .contentShape(Rectangle())
                        .onTapGesture { U2Keyboard.dismiss() }

                    Button(action: onContinue) { LocalizedText("onboarding.continue", table: .onboarding) }
                        .buttonStyle(.u2Primary)
                        .disabled(!model.canContinue)
                        .accessibilityIdentifier("onboarding.username.continue")
                        .padding(.horizontal, U2Spacing.screenGutter)
                        .u2Bottom(U2Spacing.ctaBottom)
                }
            }
        }
        .ignoresSafeArea(.keyboard)
        .onChange(of: model.status) { _, status in
            // The availability result is announced (the label alone is easy to miss).
            let key: String.LocalizationValue? =
                switch status {
                case .available: "onboarding.username.status.available"
                case .taken: "onboarding.username.status.taken"
                default: nil
                }
            if let key {
                AccessibilityNotification.Announcement(StringTable.onboarding.string(key, locale: locale)).post()
            }
        }
    }

    /// “Kullanılabilir” / “Alınmış” / “Kontrol ediliyor…” / “Geçersiz
    /// karakter” (`caption`) left, “7 / 20” (`monoCounter`) right.
    private var statusRow: some View {
        HStack(alignment: .top) {
            ZStack(alignment: .leading) {
                if let label = statusLabel {
                    StringTable.onboarding.text(label.key, locale: locale)
                        .u2Font(.caption)
                        .foregroundStyle(label.color)
                        .id(label.id)
                        .transition(.u2Fade)
                }
            }
            .frame(height: 20.15, alignment: .topLeading)
            .u2Animation(U2Motion.state, value: statusLabel?.id)
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.updatesFrequently)
            Spacer(minLength: 8)
            Text(verbatim: model.counter)
                .u2Font(.monoCounter)
                .foregroundStyle(Color.u2.textSecondary)
                .accessibilityLabel(
                    StringTable.onboarding.text("a11y.onboarding.username.counter \(model.name.count)", locale: locale))
        }
        .padding(.horizontal, 4)
    }

    private struct StatusLabel {
        let id: String
        let key: String.LocalizationValue
        let color: Color
    }

    private var statusLabel: StatusLabel? {
        if model.rejectedInput != nil {
            return StatusLabel(id: "invalid", key: "onboarding.username.status.invalid", color: Color.u2.danger)
        }
        switch model.status {
        case .available:
            return StatusLabel(id: "available", key: "onboarding.username.status.available", color: Color.u2.online)
        case .taken:
            return StatusLabel(id: "taken", key: "onboarding.username.status.taken", color: Color.u2.danger)
        case .checking:
            return StatusLabel(
                id: "checking", key: "onboarding.username.status.checking", color: Color.u2.textSecondary)
        case .invalid:
            return StatusLabel(id: "invalid", key: "onboarding.username.status.invalid", color: Color.u2.danger)
        case .empty, .typing, .tooShort:
            return nil
        }
    }
}

extension UsernameStepModel {
    /// The example card illustrates the typed name once it is available.
    var canShowOwnExample: Bool { status == .available && UsernameRules.isValid(name) && name.count >= 4 }
}

/// The “TAM EŞLEŞME” card: a shortened handle is not found, the exact one is
/// (`monoMatch` handles, `monoMatchStatus` results with 16 pt icons), and the
/// footnote. With an available typed name it illustrates that name;
/// otherwise the design's own example (@selin / @selin.n).
private struct ExactMatchCard: View {
    let name: String?
    @Environment(\.locale) private var locale

    private var exact: String { name ?? "selin.n" }

    /// The exact name cut at its last separator (or by a third), never empty.
    private var partial: String {
        guard let name else { return "selin" }
        if let cut = name.lastIndex(where: { $0 == "." || $0 == "_" }), cut > name.startIndex {
            return String(name[..<cut])
        }
        return String(name.dropLast(max(1, name.count / 3)))
    }

    var body: some View {
        U2Card(cornerRadius: 16, padding: EdgeInsets(top: 16, leading: 16, bottom: 16, trailing: 16)) {
            VStack(alignment: .leading, spacing: 12) {
                MonoKicker(title: StringTable.onboarding.text("onboarding.username.example.kicker", locale: locale))
                Grid(alignment: .leading, horizontalSpacing: 0, verticalSpacing: 10) {
                    row(handle: partial, found: false)
                    row(handle: exact, found: true)
                }
                LocalizedText("onboarding.username.example.footnote", table: .onboarding)
                    .u2Font(.captionTight)
                    .foregroundStyle(Color.u2.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .accessibilityElement(children: .combine)
    }

    private func row(handle: String, found: Bool) -> some View {
        GridRow(alignment: .center) {
            Text(verbatim: "@" + handle)
                .u2Font(.monoMatch)
                .foregroundStyle(Color.u2.text)
                .lineLimit(1)
                .truncationMode(.middle)
                .frame(maxWidth: .infinity, alignment: .leading)
            HStack(spacing: 6) {
                PhosphorIcon(found ? .checkCircle : .xCircle, size: 16)
                StringTable.onboarding.text(
                    found ? "onboarding.username.example.found" : "onboarding.username.example.notFound", locale: locale
                )
                .u2Font(.monoMatchStatus)
                .fixedSize()
            }
            .foregroundStyle(found ? Color.u2.online : Color.u2.danger)
        }
    }
}
