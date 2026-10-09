import Foundation
import LibSignalClient
import Testing

@testable import Us2usKit

/// Cross-platform interop through a real relay: this phone talks to a
/// running echo responder on another platform, normally the Kotlin
/// `us2us-bot` (android/README.md), which answers as ``EchoContract`` says.
/// Skipped unless `RELAY_URL` and `BOT_STATE` (the bot's `--state` file) are
/// set; with xcodebuild pass `TEST_RUNNER_RELAY_URL` and `TEST_RUNNER_BOT_STATE`.
/// `scripts/interop.sh` at the repository root runs it.
@Suite(
    "Live interop with an echo bot",
    .enabled(
        if: LiveRelay.url != nil && EchoState.botFile != nil,
        "set RELAY_URL and BOT_STATE (TEST_RUNNER_… with xcodebuild)"),
    .timeLimit(.minutes(5)))
struct LiveEchoBotTests {
    @Test("message request, echoes, receipts, profiles, presence, ghost settings, wipe and account deletion")
    func conversationWithBot() async throws {
        let bot = try EchoState.read(from: EchoState.botFile!)
        let botId = try #require(AccountId(string: bot.accountId))
        let store = InMemoryProtocolStore(identity: .generate(), registrationId: LocalIdentity.generateRegistrationId())
        let me = try await Messenger.register(
            username: try NormalizedUsername("ios_" + UUID().uuidString.lowercased().prefix(8)), store: store,
            relay: RelayClient(baseURL: LiveRelay.url!))
        let myUsername = await me.account.username
        let found = try #require(try await me.findAccount(username: try NormalizedUsername(bot.username)))
        #expect(found.accountId == botId)

        // Online for the whole conversation, so live and ephemeral frames arrive.
        let connection = try me.relay.connect()
        let inbox = Inbox()
        let drained = Locked(false)
        let runner = Task {
            try await me.run(connection, deliver: inbox.deliver, onQueueEmpty: { drained.withLock { $0 = true } })
        }
        defer { connection.close() }
        try await waitFor("the backlog") { drained.withLock { $0 } }

        // A message request (proof-of-work, our username), accepted with the bot's access key (§6.4).
        var state = ConversationRequestState.outgoingRequest
        try await me.send(.text("Merhaba 👋 dünya"), to: botId, requestState: state, theirAccessKey: nil)
        let accept = try await inbox.first("ACCEPT") { $0.sender == botId && $0.content.isAccept }
        #expect(!accept.identityChanged)
        state = MessageRequestPolicy.state(afterReceiving: accept.content, current: state)
        #expect(state == .accepted)
        let botKey = try #require(
            try await inbox.first("the bot's access key") { $0.sender == botId && $0.content.accessKeyUpdate != nil }
                .content.accessKeyUpdate)
        #expect(botKey.count == AccessKey.byteCount)
        _ = try await inbox.text("echo: Merhaba 👋 dünya", from: botId)

        // Our access key, then our complete profile; the bot's profile comes back (§8.7).
        for content in MessageRequestPolicy.messagesAfterAcceptance(ourAccessKey: await me.account.accessKey) {
            try await me.send(content, to: botId, requestState: state, theirAccessKey: botKey)
        }
        try await me.send(
            .profileUpdate(displayName: "iOS", username: myUsername, presenceSharing: true), to: botId,
            requestState: state, theirAccessKey: botKey)
        let profile = try #require(
            try await inbox.first("the bot's profile") { $0.sender == botId && $0.content.profile != nil }.content.profile)
        #expect(profile.username == bot.username)
        #expect(profile.presenceSharing)
        #expect(try await me.usernameOwner(try NormalizedUsername(profile.username)) == botId)

        // An attachment through the relay's media store: the bot decrypts it and reports the digest.
        let directory = try TemporaryDirectory()
        let plaintext = SecureRandom.bytes(150_000)
        try plaintext.write(to: directory.file("veri.bin"))
        let uploaded = try await me.uploadAttachment(
            fileAt: directory.file("veri.bin"), kind: .file, contentType: "application/octet-stream",
            fileName: "veri.bin")
        let attachment = BRPDataMessage(payload: .attachment(uploaded))
        try await me.send(BRPContent(data: attachment), to: botId, requestState: state, theirAccessKey: botKey)
        _ = try await inbox.text(EchoContract.attachment(plaintextSHA256: plaintext.sha256Digest), from: botId)

        var place = BRPLocation()
        place.latitude = 41.0086
        place.longitude = 28.9802
        place.accuracyMeters = 12
        place.name = "Ayasofya, İstanbul"
        let location = BRPDataMessage(payload: .location(place))
        try await me.send(BRPContent(data: location), to: botId, requestState: state, theirAccessKey: botKey)
        _ = try await inbox.text("echo-location: 41.008600,28.980200 Ayasofya, İstanbul", from: botId)

        // Delivery receipts are always sent for bubbles of accepted contacts (§8.4).
        for message in [attachment, location] {
            _ = try await inbox.first("a delivery receipt") {
                $0.sender == botId && $0.content.isDeliveryReceipt(for: message.messageID)
            }
        }

        // "Active now" (§8.6): one ephemeral batch, answered once by the bot.
        #expect(try await me.sendPresence(PresencePolicy.online(), to: [(botId, botKey)]) == [botId])
        let reply = try await inbox.first("the bot's presence reply") { $0.sender == botId && $0.content.isPresenceReply }
        var presence = PresenceTracker()
        presence.record(reply.content.presence, from: botId)
        #expect(presence.status(of: botId) == .active)

        // Ghost mode on, then "delete for both" (§8.2).
        var settings = BRPConversationSettings()
        settings.updatedAt = Date().millisecondsSince1970
        settings.ghost.enabled = true
        settings.ghost.timeoutSeconds = 3600
        try await me.send(
            BRPContent(control: .settings(settings)), to: botId, requestState: state, theirAccessKey: botKey)
        _ = try await inbox.text("echo-settings: ghost=on,timeout=3600,wipe_contact=false,expire=0", from: botId)
        var wipe = BRPConversationWipe()
        wipe.requestedAt = Date().millisecondsSince1970
        wipe.reason = .userRequest
        try await me.send(BRPContent(control: .wipe(wipe)), to: botId, requestState: state, theirAccessKey: botKey)
        _ = try await inbox.text("echo-wipe: USER_REQUEST", from: botId)

        // Our receipts for the bot's answers, OFFLINE, and the account goes away.
        var receipts = DeliveryReceiptBatch()
        for message in inbox.all where message.sender == botId { receipts.add(message, state: state) }
        for (contact, batch) in receipts.drain() {
            for receipt in batch {
                try await me.send(receipt, to: contact, requestState: state, theirAccessKey: botKey)
            }
        }
        try await me.sendPresence(PresencePolicy.offline(), to: [(botId, botKey)])
        #expect(try await me.safetyNumber(with: botId) != nil)
        #expect(inbox.all.allSatisfy { !$0.identityChanged })

        connection.close()
        _ = await runner.result
        try await me.deleteAccount()
    }
}

/// Polls `condition` until it holds, or throws ``LiveTimeout``.
func waitFor(_ what: String, timeout: TimeInterval = 60, _ condition: @Sendable () -> Bool) async throws {
    let deadline = Date().addingTimeInterval(timeout)
    while !condition() {
        guard Date() < deadline else { throw LiveTimeout(description: "\(what): not within \(Int(timeout)) s") }
        try await Task.sleep(nanoseconds: 50_000_000)
    }
}
