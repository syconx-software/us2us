import Foundation
import LibSignalClient
import Testing

@testable import Us2usKit

/// The reverse direction of ``LiveEchoBotTests``: this phone is the echo
/// responder and a client on another platform drives the conversation
/// (normally the Kotlin `LiveEchoBotTest`, pointed at the state file written
/// here). It registers, writes `{"accountId", "username"}` to `ECHO_STATE`,
/// answers as ``EchoContract`` says until the peer's `ConversationWipe`, and
/// deletes its account. Skipped unless `RELAY_URL` and `ECHO_STATE` are set
/// (`TEST_RUNNER_…` with xcodebuild); `ECHO_TIMEOUT_SECONDS` (default 300)
/// bounds the wait for the peer.
@Suite(
    "Live echo responder for another platform",
    .enabled(
        if: LiveRelay.url != nil && EchoState.responderFile != nil,
        "set RELAY_URL and ECHO_STATE (TEST_RUNNER_… with xcodebuild)"),
    .timeLimit(.minutes(10)))
struct LiveEchoResponderTests {
    @Test("answers one conversation from another platform until its ConversationWipe")
    func respond() async throws {
        let timeout = ProcessInfo.processInfo.environment["ECHO_TIMEOUT_SECONDS"].flatMap(TimeInterval.init) ?? 300
        let store = InMemoryProtocolStore(identity: .generate(), registrationId: LocalIdentity.generateRegistrationId())
        let me = try await Messenger.register(
            username: try NormalizedUsername("ios_echo_" + UUID().uuidString.lowercased().prefix(8)), store: store,
            relay: RelayClient(baseURL: LiveRelay.url!))
        let responder = EchoResponder(messenger: me, username: await me.account.username)

        let connection = try me.relay.connect()
        let drained = Locked(false)
        let runner = Task {
            try await me.run(
                connection, deliver: responder.handle, onQueueEmpty: { drained.withLock { $0 = true } })
        }
        defer { connection.close() }
        try await waitFor("the backlog") { drained.withLock { $0 } }
        // Announced only once connected, so the peer's first message arrives live.
        try EchoState(accountId: await me.account.accountId.string, username: await me.account.username.value)
            .write(to: EchoState.responderFile!)

        try await waitFor("the peer's ConversationWipe", timeout: timeout) { responder.isFinished }
        #expect(
            responder.answered
                == [.text, .attachment, .location, .presence, .settings, .wipe, .accepted, .profile])
        #expect(responder.failures.isEmpty, "\(responder.failures)")

        connection.close()
        _ = await runner.result
        try await me.deleteAccount()
    }
}

/// Accepts every message request and answers like the Kotlin `EchoBot`:
/// one text per text, attachment, location, settings and wipe message; an
/// eligible contact's ONLINE is answered once (§8.6); delivery receipts and
/// the access-key and profile exchange follow §6.4 and §8.4. The app's role
/// (contact states, keys, profiles) is played with the kit's policies.
final class EchoResponder: Sendable {
    enum Answer: Hashable, Sendable {
        case accepted, profile, text, attachment, location, presence, settings, wipe
    }

    private struct Peer {
        var state: ConversationRequestState
        var theirAccessKey: Data?
        var theyShare = false
        var profileSent = false
    }

    private struct State {
        var peers: [AccountId: Peer] = [:]
        var answered: Set<Answer> = []
        var failures: [String] = []
        var finished = false
    }

    private let messenger: Messenger
    private let username: NormalizedUsername
    private let state = Locked(State())

    init(messenger: Messenger, username: NormalizedUsername) {
        self.messenger = messenger
        self.username = username
    }

    var isFinished: Bool { state.withLock { $0.finished } }
    var answered: Set<Answer> { state.withLock { $0.answered } }
    var failures: [String] { state.withLock { $0.failures } }

    /// The `deliver` callback of ``Messenger/run(_:deliver:onQueueEmpty:)``.
    /// It never throws: a failed answer is recorded and the loop keeps running.
    @Sendable func handle(_ message: IncomingMessage) async throws {
        let sender = message.sender
        let content = message.content
        let arrivedIn = state.withLock { current -> ConversationRequestState in
            var peer = current.peers[sender] ?? Peer(state: .incomingRequest)
            let previous = current.peers[sender]?.state
            peer.state = MessageRequestPolicy.state(afterReceiving: content, current: previous)
            if let key = content.accessKeyUpdate { peer.theirAccessKey = key }
            if let profile = content.profile { peer.theyShare = profile.presenceSharing }
            current.peers[sender] = peer
            return peer.state
        }
        do {
            if arrivedIn == .incomingRequest { try await accept(sender) }
            try await shareProfileIfDue(with: sender)
            // Receipts go only to accepted contacts, never for the request itself (§8.4).
            if ReceiptPolicy.wantsDeliveryReceipt(for: content, state: arrivedIn), case .data(let data) = content.body {
                try await send(BRPContent(receipt: .delivery, messageIds: [data.messageID]), to: sender)
            }
            try await answer(content, from: sender)
        } catch {
            state.withLock { $0.failures.append("\(content.kind) from \(sender): \(error)") }
        }
    }

    private func accept(_ sender: AccountId) async throws {
        let ourKey = await messenger.account.accessKey
        let theirKey = state.withLock { $0.peers[sender]?.theirAccessKey }
        let messages = MessageRequestPolicy.acceptanceMessages(ourAccessKey: ourKey)
        // ACCEPT ends the request once the relay took it; then our access key.
        try await messenger.send(messages[0], to: sender, requestState: .incomingRequest, theirAccessKey: theirKey)
        state.withLock {
            $0.peers[sender]?.state = .accepted
            $0.answered.insert(.accepted)
        }
        for content in messages.dropFirst() { try await send(content, to: sender) }
    }

    /// Our complete profile, once we hold the contact's access key (so it
    /// costs no proof-of-work), as the Kotlin messenger does.
    private func shareProfileIfDue(with peer: AccountId) async throws {
        let due = state.withLock { current in
            current.peers[peer].map { $0.state == .accepted && $0.theirAccessKey != nil && !$0.profileSent } ?? false
        }
        guard due else { return }
        try await send(.profileUpdate(displayName: "", username: username, presenceSharing: true), to: peer)
        state.withLock {
            $0.peers[peer]?.profileSent = true
            $0.answered.insert(.profile)
        }
    }

    private func answer(_ content: BRPContent, from sender: AccountId) async throws {
        let reply: (Answer, String)
        switch content.body {
        case .data(let data):
            switch data.payload {
            case .text(let text): reply = (.text, EchoContract.text(text.body))
            case .attachment(let attachment): reply = (.attachment, try await digest(of: attachment))
            case .location(let location): reply = (.location, EchoContract.location(location))
            default: return
            }
        case .control(let control):
            switch control.action {
            case .settings(let settings): reply = (.settings, EchoContract.settings(settings))
            case .wipe(let wipe): reply = (.wipe, EchoContract.wipe(wipe))
            default: return
            }
        case .presence(let presence):
            try await answerPresence(presence, from: sender)
            return
        default:
            return
        }
        try await send(.text(reply.1), to: sender)
        state.withLock {
            $0.answered.insert(reply.0)
            // The bot keeps no history, so a wipe has nothing to erase: it ends the conversation.
            if reply.0 == .wipe { $0.finished = true }
        }
    }

    private func digest(of attachment: BRPAttachment) async throws -> String {
        let directory = try TemporaryDirectory()
        try await messenger.downloadAttachment(attachment, to: directory.file("plain"))
        return EchoContract.attachment(plaintextSHA256: try Data(contentsOf: directory.file("plain")).sha256Digest)
    }

    private func answerPresence(_ presence: BRPPresenceMessage, from sender: AccountId) async throws {
        let (eligible, key) = state.withLock { current -> (Bool, Data?) in
            guard let peer = current.peers[sender] else { return (false, nil) }
            return (PresencePolicy.isEligible(state: peer.state, weShare: true, theyShare: peer.theyShare), peer.theirAccessKey)
        }
        guard PresencePolicy.shouldReply(to: presence, eligible: eligible), let key else { return }
        try await messenger.send(
            PresencePolicy.online(reply: true), to: sender, authorization: .accessKey(key), ephemeral: true)
        state.withLock { _ = $0.answered.insert(.presence) }
    }

    /// Sends in the contact's current request state, with its access key once known.
    private func send(_ content: BRPContent, to peer: AccountId) async throws {
        let (requestState, key) = state.withLock { ($0.peers[peer]?.state ?? .accepted, $0.peers[peer]?.theirAccessKey) }
        try await messenger.send(content, to: peer, requestState: requestState, theirAccessKey: key)
    }
}
