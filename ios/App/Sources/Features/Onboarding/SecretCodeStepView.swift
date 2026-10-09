import SwiftUI

/// 2.2 · Gizli kod — six digits, auto-advance, asked twice.
///
/// set (0–6 dots) → after a 0.25 s hold the code is checked: a weak code
/// turns the dots `danger` with a soft shake and a sentence, then they clear
/// (0.8 s); a good one swaps the title/paragraph to the confirm copy (0.36 s
/// cross-fade) and the dots reset (0.24 s). The confirmation advances to 2.3
/// or, on a mismatch, flashes the error and returns to the set step.
struct SecretCodeStepView: View {
    let model: SecretCodeStepModel
    let step: (step: Int, count: Int)
    let onBack: () -> Void

    @Environment(\.locale) private var locale

    var body: some View {
        U2Screen {
            VStack(spacing: 0) {
                SetupHeader(step: step.step, stepCount: step.count, onBack: onBack)
                titleBlock
                    .padding(.top, 18)
                    .padding(.horizontal, U2Spacing.screenGutterL)
                CodeDots(filled: model.entered.count, appearance: model.appearance, shakeTrigger: model.shakeTrigger)
                    .padding(.top, 44)
                    .overlay(alignment: .top) {
                        // Sized here: the overlay is otherwise only as wide
                        // as the 190 pt dots row and the sentence would wrap there.
                        problemSentence
                            .frame(width: 300)
                            .offset(y: 44 + U2Size.codeDot + 16)
                    }
                    .accessibilityIdentifier("onboarding.code.dots")
                Spacer(minLength: 0)
                NumericKeypad(onDigit: model.type, onDelete: model.delete)
                    .u2Bottom(56)
            }
        }
        .onChange(of: model.problem) { _, problem in
            guard let problem else { return }
            AccessibilityNotification.Announcement(problemString(problem)).post()
        }
        .onChange(of: model.step) { _, step in
            AccessibilityNotification.Announcement(
                StringTable.onboarding.string(
                    step == .confirm ? "onboarding.code.confirm.title" : "onboarding.code.title", locale: locale)
            ).post()
        }
    }

    /// Title + paragraph of the current step, cross-fading; the block keeps
    /// the taller step's height so the dots never move.
    private var titleBlock: some View {
        ZStack(alignment: .topLeading) {
            copy(for: .set).hidden()
            copy(for: .confirm).hidden()
            copy(for: model.step)
                .id(model.step)
                .transition(.u2Fade)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .u2Animation(U2Motion.state, value: model.step)
    }

    private func copy(for step: SecretCodeStepModel.Step) -> some View {
        VStack(alignment: .leading, spacing: U2Spacing.titleToBodyGap) {
            SetupTitle(
                StringTable.onboarding.resource(step == .set ? "onboarding.code.title" : "onboarding.code.confirm.title"),
                style: .displayTitleM)
            LocalizedText(step == .set ? "onboarding.code.body" : "onboarding.code.confirm.body", table: .onboarding)
                .u2Font(.body)
                .foregroundStyle(Color.u2.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// Under the dots (gap 16, as 3.2): `calloutL`, `danger`, centred, max
    /// 300 wide. It fades in 0.36 s and stays until the next digit.
    private var problemSentence: some View {
        ZStack {
            if let problem = model.problem {
                Text(verbatim: problemString(problem))
                    .u2Font(.calloutL)
                    .foregroundStyle(Color.u2.danger)
                    .multilineTextAlignment(.center)
                    .frame(width: 300)
                    .fixedSize(horizontal: false, vertical: true)
                    .id(problem)
                    .transition(.u2Fade)
            }
        }
        .u2Animation(U2Motion.state, value: model.problem)
        .accessibilityHidden(true)
    }

    private func problemString(_ problem: SecretCodeStepModel.Problem) -> String {
        switch problem {
        case .weak: StringTable.onboarding.string("onboarding.code.weak", locale: locale)
        case .mismatch: StringTable.onboarding.string("onboarding.code.mismatch", locale: locale)
        }
    }
}
