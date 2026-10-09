import SwiftUI

/// Invite links the app was opened with (`https://us2us.app/u/<username>`,
/// universal links only), waiting until the main stack can show them: the
/// app locks on every launch and return from the background, so a link that
/// arrives is opened only after the unlock (or after onboarding).
///
/// Memory only — a link is never written anywhere — and only the latest one
/// is kept, for at most ten minutes, so a link tapped long ago never pops up
/// a sheet by surprise. Opening one starts nothing on its own: 4.2 shows the
/// name searched (the same exact lookup as typing it) and nothing is sent
/// until the user taps “Mesaj yaz” (a message request with PoW).
@Observable
@MainActor
final class InviteLinkInbox {
    static let shared = InviteLinkInbox()

    struct Pending: Equatable {
        let username: String
        let receivedAt: Date
        let id = UUID()
    }

    static let lifetime: TimeInterval = 10 * 60

    private(set) var pending: Pending?

    init() {}

    /// Keeps `url` when it is an invite link; anything else is ignored.
    @discardableResult
    func receive(_ url: URL, now: Date = .now) -> Bool {
        guard let username = InviteLink.username(from: url) else { return false }
        pending = Pending(username: username, receivedAt: now)
        return true
    }

    /// The pending username (once), unless it has expired.
    func take(now: Date = .now) -> String? {
        guard let pending else { return nil }
        self.pending = nil
        return now.timeIntervalSince(pending.receivedAt) < Self.lifetime ? pending.username : nil
    }
}

extension View {
    /// App/ (`RootView`): universal links → `InviteLinkInbox`. In DEBUG also
    /// the `-U2Route invite[:<state>]` harness and `-U2OpenURL <url>`.
    func u2InviteLinks() -> some View {
        modifier(InviteLinksModifier())
    }

    /// 4.1 (`ChatsView`): calls `perform` with a pending invite's username
    /// once `isReady` (unlocked, no lock or cover on screen).
    func u2OnInviteLink(isReady: Bool, perform: @escaping (String) -> Void) -> some View {
        modifier(InviteLinkConsumer(isReady: isReady, perform: perform))
    }
}

private struct InviteLinksModifier: ViewModifier {
    @State private var inbox = InviteLinkInbox.shared
    #if DEBUG
        @Environment(AppModel.self) private var model
        @State private var harnessSheet: InviteHarness.Sheet?
    #endif

    func body(content: Content) -> some View {
        content
            .onOpenURL { url in inbox.receive(url) }
            #if DEBUG
                .u2Sheet(item: $harnessSheet) { sheet in
                    InviteSheet(username: InviteHarness.username(model), onClose: { harnessSheet = nil }, debug: sheet.debug)
                }
                .task {
                    harnessSheet = InviteHarness.launchSheet()
                    if let url = InviteHarness.launchLink() { inbox.receive(url) }
                }
            #endif
    }
}

private struct InviteLinkConsumer: ViewModifier {
    let isReady: Bool
    let perform: (String) -> Void
    @State private var inbox = InviteLinkInbox.shared

    func body(content: Content) -> some View {
        content
            .onChange(of: inbox.pending) { deliver() }
            .onChange(of: isReady) { deliver() }
            .onAppear { deliver() }
    }

    private func deliver() {
        guard isReady, inbox.pending != nil, let username = inbox.take() else { return }
        perform(username)
    }
}
