import SwiftUI

struct MessageInvitationModifier: ViewModifier {
    private struct Invitation: Identifiable, Equatable {
        let id = UUID()
        let code: String
    }
    @State private var invitation: Invitation?
    @Environment(AppModel.self) private var model
    @Environment(\.locale) private var locale

    func body(content: Content) -> some View {
        content
            .environment(\.messageInviteAction, { code in
                guard model.phase == .unlocked, model.overlay == .none else { return }
                invitation = Invitation(code: code)
            })
            .onChange(of: model.overlay) { _, overlay in
                if overlay != .none { invitation = nil }
            }
            .u2Sheet(item: $invitation, style: .floating) { invite in
                CommunityMessageInviteSheet(code: invite.code) { invitation = nil }
                    .environment(\.locale, locale)
            }
    }
}

/// Opening this sheet is local. Only the explicit confirmation redeems the original code.
struct CommunityMessageInviteSheet: View {
    let code: String
    let onClose: () -> Void
    @Environment(AppModel.self) private var model
    @State private var isWorking = false
    @State private var submitted = false
    @State private var failure: CommunityProblem?
    @State private var request: Task<Void, Never>?

    var body: some View {
        VStack(spacing: U2Spacing.sectionGap) {
            Text(submitted ? "messageInvite.sent" : "messageInvite.title", table: .communities)
                .u2Font(.rowTitle)
                .multilineTextAlignment(.center)
                .accessibilityIdentifier("messageInvite.title")
            if !submitted {
                Text("redeem.hint", table: .communities)
                    .u2Font(.callout)
                    .foregroundStyle(Color.u2.textSecondary)
                CommunityDisclosure()
                if model.messenger.communities?.isSupported != true {
                    Text("communities.unsupported", table: .communities).u2Font(.callout)
                }
                if let failure {
                    CommunityProblemNote(problem: failure) { self.failure = nil }
                }
                Button(action: confirm) {
                    U2ButtonLabel(Text("action.request", table: .communities), isWorking: isWorking)
                }
                .buttonStyle(.u2Primary)
                .disabled(isWorking || model.messenger.communities?.isSupported != true)
                .accessibilityIdentifier("messageInvite.confirm")
            }
            Button(action: onClose) {
                if submitted { Text("messageInvite.done", table: .communities) }
                else { Text("common.cancel") }
            }
                .buttonStyle(.u2TextAccent)
                .disabled(isWorking)
                .accessibilityIdentifier("messageInvite.close")
        }
        .padding(18)
        .onDisappear { request?.cancel() }
    }

    private func confirm() {
        guard !isWorking, !submitted, let service = model.messenger.communities, service.isSupported else { return }
        isWorking = true
        failure = nil
        request = Task {
            defer { isWorking = false }
            do throws(CommunityProblem) {
                try await service.redeem(code)
                guard !Task.isCancelled else { return }
                submitted = true
            } catch {
                guard !Task.isCancelled else { return }
                failure = error
            }
        }
    }
}
