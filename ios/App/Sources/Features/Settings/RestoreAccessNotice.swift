import SwiftUI

/// After a restore whose token rotation got no answer (PROTOCOL §5.5 step
/// 3.4) the server has not confirmed yet that the old phone lost its
/// access: until it does, the old phone may still connect and receive
/// what is sent to this account, and this phone does not connect. 7.1 says
/// so at the top, honestly, with “Şimdi kontrol et”; 4.1 shows a short card
/// that leads there. The notice goes as soon as the outcome is known — by
/// this button or by any connection attempt, which asks first (from the
/// first unlock after a launch on).
@Observable
@MainActor
final class RestoreAccessModel {
    /// “Şimdi kontrol et” is running.
    private(set) var isChecking = false
    /// The last check got no answer: the notice says so under its button.
    private(set) var lastCheckUnanswered = false

    private let messenger: any MessengerService

    init(messenger: any MessengerService) {
        self.messenger = messenger
    }

    /// The notice is up: the rotation is unconfirmed (or being checked).
    var isShown: Bool { messenger.restoreAccessPending }

    /// “Şimdi kontrol et”. `nil` when a check is already running.
    @discardableResult
    func check() async -> RestoreAccessCheck? {
        guard !isChecking else { return nil }
        withU2Animation(U2Motion.state) {
            isChecking = true
            lastCheckUnanswered = false
        }
        let result = await messenger.checkRestoreAccess()
        withU2Animation(U2Motion.state) {
            isChecking = false
            lastCheckUnanswered = result == .unanswered
        }
        return result
    }

    /// The account this notice was about is gone (erased, wiped, or replaced
    /// by a restore): a later notice starts without the old check's answer.
    func reset() {
        isChecking = false
        lastCheckUnanswered = false
    }

    #if DEBUG
        /// DEBUG harness (`settings:7.1-access-…`).
        func applyHarness(checking: Bool, unanswered: Bool) {
            isChecking = checking
            lastCheckUnanswered = unanswered
        }
    #endif
}

/// The 7.1 notice: the 5.4 note card's danger ring (`danger·40%`, r20,
/// pad 16, gap 14; `ph:warning-circle` 22 in `danger`, title `noteTitle`,
/// body `captionTight` in `text·68%`) with “Şimdi kontrol et” (C3,
/// `accent300`) under the text. No design reference (proposed).
struct RestoreAccessNotice: View {
    let model: RestoreAccessModel
    /// What the check found out, for 7.1's toast.
    let onResult: (RestoreAccessCheck) -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            PhosphorIcon(.warningCircle, size: 22)
                .foregroundStyle(Color.u2.danger)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 4) {
                Text("access.notice.title", table: .settings)
                    .u2Font(.noteTitle)
                    .foregroundStyle(Color.u2.text)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityAddTraits(.isHeader)
                Text("access.notice.body", table: .settings)
                    .u2Font(.captionTight)
                    .foregroundStyle(Color.u2.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                if model.lastCheckUnanswered {
                    Text("access.notice.unanswered", table: .settings)
                        .u2Font(.captionTight)
                        .foregroundStyle(Color.u2.danger)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 4)
                        .transition(.u2Fade)
                        .accessibilityIdentifier("settings.access.unanswered")
                }
                Button {
                    Task {
                        guard let result = await model.check() else { return }
                        onResult(result)
                    }
                } label: {
                    U2ButtonLabel(
                        Text(model.isChecking ? "access.notice.checking" : "access.notice.check", table: .settings),
                        glyphSize: 18, isWorking: model.isChecking)
                }
                .buttonStyle(U2TextButtonStyle(tone: .accent, height: 40, fillsWidth: false))
                .allowsHitTesting(!model.isChecking)
                .accessibilityIdentifier("settings.access.check")
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(EdgeInsets(top: 16, leading: 16, bottom: 6, trailing: 16))
        .u2InnerStroke(Color.u2.dangerStrokeSoft, width: 1, cornerRadius: U2Radius.cardL)
        .u2Animation(U2Motion.state, value: model.lastCheckUnanswered)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("settings.access")
    }
}
