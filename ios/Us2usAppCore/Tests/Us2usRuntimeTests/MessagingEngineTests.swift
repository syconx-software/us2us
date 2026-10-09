import Foundation
import Testing
import Us2usKit
import Us2usStorage

@testable import Us2usRuntime

@Suite("Inbound semantics")
struct InboundTests {
    @Test func aStrangersFirstMessageOpensARequest() async throws {
        let fixture = try EngineFixture()
        await fixture.start()
        let stranger = AccountId(uuid: UUID())
        try await fixture.receive(.textMessage("merhaba"), from: stranger, username: "kuzey_dev")
        let contact = try #require(try fixture.contact(stranger))
        #expect(contact.requestState == .incomingRequest)
        #expect(contact.profile.username?.value == "kuzey_dev")
        #expect(contact.profile.usernameStatus == .verified)
        let messages = try await fixture.messages(stranger)
        #expect(messages.count == 1)
        #expect(messages[0].body == .text("merhaba"))
        // No delivery receipt for a request (§8.4).
        #expect(try fixture.outbox().isEmpty)
        let chats = try await fixture.engine.chatSummaries()
        #expect(chats.first?.isRequest == true)
        await fixture.engine.stop()
    }

    @Test func strangersCannotOpenRequestsWithInvisibleContent() async throws {
        let fixture = try EngineFixture()
        await fixture.start()
        let stranger = AccountId(uuid: UUID())
        try await fixture.receive(BRPContent(receipt: .delivery, messageIds: [RandomId.make()]), from: stranger)
        try await fixture.receive(.presence(.online), from: stranger)
        try await fixture.receive(BRPContent(typingStarted: true), from: stranger)
        try await fixture.receive(.accessKeyUpdate(Data(repeating: 1, count: 16)), from: stranger)
        #expect(try fixture.contact(stranger) == nil)
        #expect(await fixture.engine.typingContacts(at: fixture.clock.now()).isEmpty)
        await fixture.engine.stop()
    }

    @Test func blockedContactsAreDroppedLocally() async throws {
        let fixture = try EngineFixture()
        await fixture.start()
        let peer = try fixture.acceptedContact()
        try await fixture.engine.block(peer)
        try await fixture.receive(.textMessage("still there?"), from: peer)
        #expect(try await fixture.messages(peer).isEmpty)
        await fixture.engine.stop()
    }

    @Test func deliveryReceiptsAreAlwaysSentAndMerged() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        // Messages arrive while nothing is connected yet (backlog).
        await fixture.start()
        let ids = (0..<3).map { _ in RandomId.make() }
        for id in ids { try await fixture.receive(.textMessage("hi", id: id), from: peer) }
        try await eventually("receipt sent") { fixture.backend.sent(to: peer).contains { $0.receipt != nil } }
        let receipts = fixture.backend.sent(to: peer).compactMap(\.receipt)
        #expect(receipts.allSatisfy { $0.type == .delivery })
        #expect(receipts.flatMap(\.messageIds) == ids)
        // One ReceiptMessage for the backlog, not one per message (§1.1, §8.4).
        #expect(receipts.count <= 2)
        try await eventually("outbox drained") { try fixture.outbox().isEmpty }
        await fixture.engine.stop()
    }

    @Test func duplicateRelayIdsAndMessageIdsAreIgnored() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        await fixture.start()
        let id = RandomId.make()
        try await fixture.receive(.textMessage("once", id: id), from: peer, relayId: "r1")
        try await fixture.receive(.textMessage("once", id: id), from: peer, relayId: "r1")
        try await fixture.receive(.textMessage("once", id: id), from: peer, relayId: "r2")
        #expect(try await fixture.messages(peer).count == 1)
        await fixture.engine.stop()
    }

    @Test func reactionsDeletesAndQuotes() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        await fixture.start()
        let target = RandomId.make()
        try await fixture.receive(.textMessage("photo?", id: target), from: peer)
        var reaction = BRPReaction()
        reaction.targetMessageID = target
        reaction.emoji = "❤️"
        try await fixture.receive(.data(.reaction(reaction)), from: peer)
        var items = try await fixture.messages(peer)
        #expect(items.first?.reactions[peer] == "❤️")
        var delete = BRPDelete()
        delete.targetMessageID = target
        try await fixture.receive(.data(.delete(delete)), from: peer)
        items = try await fixture.messages(peer)
        #expect(items.first?.body == .deleted)
        await fixture.engine.stop()
    }

    @Test func settingsAreLastWriterWinsWithASystemLine() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        await fixture.start()
        var settings = BRPConversationSettings()
        settings.updatedAt = fixture.clock.now().millisecondsSince1970
        settings.ghost = GhostSettings(enabled: true, timeoutSeconds: 3600).proto
        try await fixture.receive(BRPContent(control: .settings(settings)), from: peer)
        let conversation = try #require(try await fixture.engine.conversationInfo(peer))
        #expect(conversation.ghost == GhostSettings(enabled: true, timeoutSeconds: 3600))
        #expect(conversation.ghostDeadline != nil)
        let items = try await fixture.messages(peer)
        guard case .notice(.settingsChanged(let change))? = items.last?.body else {
            Issue.record("no settings line")
            return
        }
        #expect(change.ghost.enabled)
        // An older write loses.
        var older = BRPConversationSettings()
        older.updatedAt = settings.updatedAt - 10
        try await fixture.receive(BRPContent(control: .settings(older)), from: peer)
        #expect(try await fixture.engine.conversationInfo(peer)?.ghost?.enabled == true)
        await fixture.engine.stop()
    }

    @Test func screenshotNoticesFromTheContact() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        await fixture.start()
        var notice = BRPScreenshotNotice()
        notice.takenAt = 1
        try await fixture.receive(BRPContent(control: .screenshot(notice)), from: peer)
        let items = try await fixture.messages(peer)
        #expect(items.last?.body == .notice(.screenshotByContact))
        #expect(items.last?.direction == .system)
        let text = SystemNotice.screenshotByContact.text(contactName: "deniz", localizer: Localizer(languageCode: "en"))
        #expect(text.contains("deniz"))
        await fixture.engine.stop()
    }

    @Test func aReceivedWipeDeletesTheConversation() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        await fixture.start()
        try await fixture.receive(.textMessage("secret"), from: peer)
        var wipe = BRPConversationWipe()
        wipe.reason = .userRequest
        try await fixture.receive(BRPContent(control: .wipe(wipe)), from: peer)
        #expect(try await fixture.messages(peer).isEmpty)
        #expect(try await fixture.engine.conversationInfo(peer) == nil)
        // A received wipe is never answered.
        try await eventually { try fixture.outbox().isEmpty }
        #expect(!fixture.backend.sent(to: peer).contains { if case .wipe? = $0.control { return true } else { return false } })
        await fixture.engine.stop()
    }

    @Test func safetyNumberChangeHoldsSendsAndAddsALine() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        await fixture.start()
        try await fixture.receive(.textMessage("new phone"), from: peer, identityChanged: true)
        let items = try await fixture.messages(peer)
        #expect(items.contains { $0.body == .notice(.safetyNumberChanged) })
        try await fixture.engine.send(.text("are you sure?"), to: peer)
        await settle()
        // The receipt and the message wait until the user accepts the new number.
        #expect(fixture.backend.sent(to: peer).isEmpty)
        try await fixture.engine.acceptSafetyNumberChange(peer)
        try await eventually("sent after accepting") {
            fixture.backend.sent(to: peer).contains { $0.data?.text.body == "are you sure?" }
        }
        await fixture.engine.stop()
    }
}

@Suite("Message requests (§6.4)")
struct RequestTests {
    @Test func acceptingSendsAcceptThenKeyThenProfile() async throws {
        let fixture = try EngineFixture()
        await fixture.start()
        let requester = AccountId(uuid: UUID())
        try await fixture.receive(.textMessage("selam"), from: requester, username: "mert_k")
        try await fixture.engine.acceptRequest(requester)
        #expect(try fixture.contact(requester)?.requestState == .accepted)
        try await eventually { fixture.backend.sent(to: requester).count >= 3 }
        let sent = fixture.backend.sent(to: requester)
        #expect(sent[0].isAccept)
        #expect(sent[0].requestState == .incomingRequest)
        #expect(sent.contains { $0.isAccessKeyUpdate })
        // The request's message gets its delivery receipt now.
        #expect(sent.contains { $0.receipt?.type == .delivery })
        // No profile until their key arrives.
        #expect(!sent.contains { $0.isProfile })
        try await fixture.receive(.accessKeyUpdate(Data(repeating: 4, count: 16)), from: requester)
        try await eventually("profile after the exchange") { fixture.backend.sent(to: requester).contains { $0.isProfile } }
        // From now on with their access key, not proof-of-work.
        try await fixture.engine.send(.text("hoş geldin"), to: requester)
        try await eventually { fixture.backend.sent(to: requester).contains { $0.data?.text.body == "hoş geldin" } }
        let last = try #require(fixture.backend.sent(to: requester).last { $0.data != nil })
        #expect(last.accessKey == Data(repeating: 4, count: 16))
        await fixture.engine.stop()
    }

    @Test func aFailedAcceptChangesNothing() async throws {
        let fixture = try EngineFixture()
        await fixture.start()
        let requester = AccountId(uuid: UUID())
        try await fixture.receive(.textMessage("selam"), from: requester)
        fixture.backend.failNextSend(to: requester, with: RelayError.network(.notConnectedToInternet))
        await #expect(throws: RelayError.self) { try await fixture.engine.acceptRequest(requester) }
        #expect(try fixture.contact(requester)?.requestState == .incomingRequest)
        try await fixture.engine.acceptRequest(requester)
        #expect(try fixture.contact(requester)?.requestState == .accepted)
        await fixture.engine.stop()
    }

    @Test func answeringBeforeAcceptingIsRefused() async throws {
        let fixture = try EngineFixture()
        await fixture.start()
        let requester = AccountId(uuid: UUID())
        try await fixture.receive(.textMessage("selam"), from: requester)
        await #expect(throws: EngineError.notAllowed) { try await fixture.engine.send(.text("hi"), to: requester) }
        await fixture.engine.stop()
    }

    @Test func decliningDeletesSilently() async throws {
        let fixture = try EngineFixture()
        await fixture.start()
        let requester = AccountId(uuid: UUID())
        try await fixture.receive(.textMessage("tebrikler! bit.ly/…"), from: requester)
        try await fixture.engine.declineRequest(requester)
        #expect(try fixture.contact(requester) == nil)
        #expect(try await fixture.engine.chatSummaries().isEmpty)
        await settle()
        #expect(fixture.backend.sent.isEmpty)
        await fixture.engine.stop()
    }

    @Test func outgoingRequestFlow() async throws {
        let fixture = try EngineFixture()
        await fixture.start()
        let bob = AccountId(uuid: UUID())
        fixture.backend.register("bob_99", as: bob)
        let found = try await fixture.engine.startConversation(username: try NormalizedUsername("bob_99"))
        #expect(found == bob)
        #expect(try fixture.contact(bob)?.requestState == .outgoingRequest)
        try await fixture.engine.send(.text("hey bob"), to: bob)
        try await eventually { !fixture.backend.sent(to: bob).isEmpty }
        // While our request is pending: proof-of-work + sender_username (Messenger does both).
        #expect(fixture.backend.sent(to: bob)[0].requestState == .outgoingRequest)
        // Bob accepts and sends his key.
        try await fixture.receive(.messageRequestResponse(.accept), from: bob)
        #expect(try fixture.contact(bob)?.requestState == .accepted)
        try await fixture.receive(.accessKeyUpdate(Data(repeating: 6, count: 16)), from: bob)
        try await eventually("our key and profile") {
            let sent = fixture.backend.sent(to: bob)
            return sent.contains { $0.isAccessKeyUpdate } && sent.contains { $0.isProfile }
        }
        let items = try await fixture.messages(bob)
        #expect(items.contains { $0.body == .notice(.requestAccepted) })
        await #expect(throws: EngineError.usernameNotFound) {
            _ = try await fixture.engine.startConversation(username: try NormalizedUsername("nobody"))
        }
        await fixture.engine.stop()
    }

    @Test func blockingRotatesTheAccessKeyForEveryoneElse() async throws {
        let fixture = try EngineFixture()
        let friend = try fixture.acceptedContact()
        let pest = try fixture.acceptedContact()
        await fixture.start()
        try await fixture.engine.block(pest)
        #expect(try fixture.contact(pest)?.requestState == .blocked)
        try await eventually { fixture.backend.sent(to: friend).contains { $0.isAccessKeyUpdate } }
        #expect(fixture.backend.state.withLock { $0.rotations } == 1)
        let update = try #require(fixture.backend.sent(to: friend).first { $0.isAccessKeyUpdate })
        guard case .accessKey(let key)? = update.control else { return }
        #expect(key.accessKey == fixture.backend.state.withLock { $0.accessKey })
        #expect(!fixture.backend.sent(to: pest).contains { $0.isAccessKeyUpdate })
        await fixture.engine.stop()
    }
}

@Suite("Outbox")
struct OutboxTests {
    @Test func messagesGoOutInOrderPerContactDespiteRetries() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        await fixture.start()
        fixture.backend.failNextSend(to: peer, with: RelayError.network(.timedOut))
        let first = try await fixture.engine.send(.text("1"), to: peer)
        let second = try await fixture.engine.send(.text("2"), to: peer)
        await settle()
        #expect(fixture.backend.sent(to: peer).isEmpty)
        var items = try await fixture.messages(peer)
        #expect(items.map(\.status) == [.sending, .sending])
        try await eventually {
            fixture.clock.advance(by: 1)
            return fixture.backend.sent(to: peer).count == 2
        }
        #expect(fixture.backend.sent(to: peer).compactMap { $0.data?.messageID } == [first, second])
        items = try await fixture.messages(peer)
        #expect(items.map(\.status) == [.sent, .sent])
        await fixture.engine.stop()
    }

    @Test func permanentFailuresAreMarkedAndCanBeRetried() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        await fixture.start()
        fixture.backend.failNextSend(to: peer, with: RelayError.server(status: 404, code: .notFound, message: nil))
        let id = try await fixture.engine.send(.text("gone?"), to: peer)
        try await eventually { try await fixture.messages(peer).first?.status == .failed }
        try await fixture.engine.resend(id, peer: peer)
        try await eventually { try await fixture.messages(peer).first?.status == .sent }
        await fixture.engine.stop()
    }

    @Test func aRotatedAccessKeyFallsBackToProofOfWork() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        await fixture.start()
        fixture.backend.failNextSend(to: peer, with: RelayError.server(status: 403, code: .accessDenied, message: nil))
        try await fixture.engine.send(.text("knock"), to: peer)
        try await eventually {
            fixture.clock.advance(by: 1)
            return !fixture.backend.sent(to: peer).isEmpty
        }
        #expect(fixture.backend.sent(to: peer)[0].accessKey == nil)
        #expect(try fixture.contact(peer)?.theirAccessKey == nil)
        await fixture.engine.stop()
    }

    @Test func receiptsReadAndDisappearing() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        await fixture.start()
        let id = RandomId.make()
        try await fixture.receive(.textMessage("kapı kodu 4471", id: id, expire: 300), from: peer)
        var item = try #require(try await fixture.messages(peer).first)
        #expect(item.expiresAt == nil)
        #expect(!item.isCopyable)
        try await fixture.engine.markDisplayed(peer)
        item = try #require(try await fixture.messages(peer).first)
        // §8.1: T seconds after first display.
        let expiresAt = try #require(item.expiresAt)
        #expect(abs(expiresAt.timeIntervalSince(fixture.clock.now().addingTimeInterval(300))) < 0.01)
        try await eventually("read receipt") { fixture.backend.sent(to: peer).contains { $0.receipt?.type == .read } }
        fixture.clock.advance(by: 300)
        try await eventually("expired") {
            fixture.clock.advance(by: 1)
            return try await fixture.messages(peer).isEmpty
        }
        await fixture.engine.stop()
    }

    @Test func readReceiptsCanBeTurnedOff() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        await fixture.start()
        try await fixture.engine.updateUserSettings { $0.readReceipts = false }
        try await fixture.receive(.textMessage("x"), from: peer)
        try await fixture.engine.markDisplayed(peer)
        try await eventually { fixture.backend.sent(to: peer).contains { $0.receipt?.type == .delivery } }
        await settle()
        #expect(!fixture.backend.sent(to: peer).contains { $0.receipt?.type == .read })
        await fixture.engine.stop()
    }

    @Test func deliveryAndReadUpdateOurBubbles() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        await fixture.start()
        let id = try await fixture.engine.send(.text("çok güzelmiş"), to: peer)
        try await eventually { try await fixture.messages(peer).first?.status == .sent }
        try await fixture.receive(BRPContent(receipt: .delivery, messageIds: [id]), from: peer)
        #expect(try await fixture.messages(peer).first?.status == .delivered)
        try await fixture.receive(BRPContent(receipt: .read, messageIds: [id]), from: peer)
        #expect(try await fixture.messages(peer).first?.status == .read)
        await fixture.engine.stop()
    }

    @Test func outgoingDisappearingMessagesExpireAfterSending() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        await fixture.start()
        try await fixture.engine.updateConversationSettings(peer, ghost: nil, defaultExpireTimerSeconds: 30)
        try await fixture.engine.send(.text("30 sn"), to: peer)
        let sent = try #require(try await fixture.messages(peer).first { $0.direction == .outgoing })
        #expect(sent.expireTimerSeconds == 30)
        try await eventually {
            fixture.clock.advance(by: 1)
            return try await fixture.messages(peer).allSatisfy { $0.direction != .outgoing }
        }
        await fixture.engine.stop()
    }
}

@Suite("Ghost mode (§8.2)")
struct GhostTests {
    @Test func silenceWipesBothSidesAndTheNoticeGoesOut() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        await fixture.start()
        try await fixture.engine.updateConversationSettings(
            peer, ghost: GhostSettings(enabled: true, timeoutSeconds: 3600), defaultExpireTimerSeconds: 0)
        try await fixture.engine.send(.text("bu akşam?"), to: peer)
        try await eventually { fixture.backend.sent(to: peer).contains { $0.data != nil } }
        fixture.clock.advance(by: 1800)
        try await fixture.receive(.textMessage("evet"), from: peer)  // resets the timer
        fixture.clock.advance(by: 1800)
        await settle()
        #expect(try await fixture.engine.conversationInfo(peer) != nil)
        fixture.clock.advance(by: 1790)
        await settle()
        #expect(try await fixture.engine.conversationInfo(peer) != nil)
        try await eventually("wiped") {
            fixture.clock.advance(by: 1)
            return try await fixture.engine.conversationInfo(peer) == nil
        }
        try await eventually("ConversationWipe sent") {
            fixture.backend.sent(to: peer).contains {
                if case .wipe(let wipe)? = $0.control { return wipe.reason == .ghostTimeout }
                return false
            }
        }
        // Its system lines are gone with it (own crypto-shredded slot).
        #expect(try await fixture.messages(peer).isEmpty)
        await fixture.engine.stop()
    }
}

@Suite("Presence (§8.6)")
struct PresenceTests {
    @Test func onlineFanOutRepliesAndOffline() async throws {
        let fixture = try EngineFixture()
        let alice = try fixture.acceptedContact()
        let bob = try fixture.acceptedContact()
        let hidden = try fixture.acceptedContact(presenceSharing: false)
        await fixture.start()
        await fixture.engine.connectionReachedQueueEmpty()
        // One request to every eligible contact.
        let first = try #require(fixture.backend.presenceSends.first)
        #expect(Set(first.to) == [alice, bob])
        // Alice comes online: recorded and answered once.
        try await fixture.receive(.presence(.online), from: alice)
        try await eventually { fixture.backend.presenceSends.count == 2 }
        let reply = fixture.backend.presenceSends[1]
        #expect(reply.to == [alice])
        if case .presence(let presence) = reply.content.body { #expect(presence.reply) }
        #expect(await fixture.engine.presenceStatus(at: fixture.clock.now())[alice] == .active)
        // A reply is never answered.
        try await fixture.receive(.presence(.online, reply: true), from: bob)
        await settle()
        #expect(fixture.backend.presenceSends.count == 2)
        // Reciprocity: a contact who hides its status is neither shown nor answered.
        try await fixture.receive(.presence(.online), from: hidden)
        await settle()
        #expect(await fixture.engine.presenceStatus(at: fixture.clock.now())[hidden] == nil)
        // Leaving: OFFLINE only to those seen online.
        await fixture.engine.goingOffline()
        let offline = try #require(fixture.backend.presenceSends.last)
        #expect(Set(offline.to) == [alice, bob])
        // Turning sharing off hides everyone and sends nothing.
        try await fixture.engine.updateUserSettings { $0.presenceSharing = false }
        #expect(await fixture.engine.presenceStatus(at: fixture.clock.now()).isEmpty)
        await fixture.engine.stop()
    }

    @Test func typingIndicators() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        await fixture.start()
        try await fixture.receive(BRPContent(typingStarted: true), from: peer)
        #expect(await fixture.engine.typingContacts(at: fixture.clock.now()) == [peer])
        #expect(await fixture.engine.typingContacts(at: fixture.clock.now().addingTimeInterval(20)).isEmpty)
        await fixture.engine.setTyping(true, peer: peer)
        await fixture.engine.setTyping(true, peer: peer)  // throttled
        let typing = fixture.backend.sent(to: peer).filter { if case .typing = $0.content.body { return true } else { return false } }
        #expect(typing.count == 1)
        #expect(typing[0].ephemeral)
        await fixture.engine.stop()
    }
}

@Suite("Media")
struct MediaTests {
    @Test func outgoingMediaIsUploadedThenSent() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        await fixture.start()
        let file = fixture.directory.directories.stagingFile(extension: "jpg")
        try Data("jpeg bytes".utf8).write(to: file)
        let media = PreparedMedia(file: file, kind: .image, contentType: "image/jpeg", size: 10, width: 4, height: 3)
        let id = try await fixture.engine.send(.media(media), to: peer)
        // The plaintext was moved into encrypted storage.
        #expect(!FileManager.default.fileExists(atPath: file.path))
        try await eventually { fixture.backend.sent(to: peer).contains { $0.data?.messageID == id } }
        let sent = try #require(fixture.backend.sent(to: peer).first { $0.data?.messageID == id }?.data)
        guard case .attachment(let attachment) = sent.payload else {
            Issue.record("not an attachment")
            return
        }
        #expect(attachment.mediaID.count == 16)
        #expect(attachment.width == 4 && attachment.height == 3)
        #expect(fixture.backend.state.withLock { $0.uploads[attachment.mediaID] } == Data("jpeg bytes".utf8))
        let item = try #require(try await fixture.messages(peer).first)
        guard case .attachment(let shown) = item.body else { return }
        #expect(shown.availability == .local)
        let exported = try await fixture.engine.exportMedia(id, peer: peer)
        #expect(try Data(contentsOf: exported) == Data("jpeg bytes".utf8))
        // Unsending deletes the blob from the relay too.
        try await fixture.engine.deleteForEveryone(id, peer: peer)
        #expect(fixture.backend.deletedMedia == [attachment.mediaID])
        #expect(try await fixture.messages(peer).first?.body == .deleted)
        await fixture.engine.stop()
    }

    @Test func requestMediaWaitsForAcceptance() async throws {
        let fixture = try EngineFixture()
        await fixture.start()
        let stranger = AccountId(uuid: UUID())
        let mediaId = RandomId.make()
        fixture.backend.setDownload(Data("photo".utf8), for: mediaId)
        try await fixture.receive(.attachmentMessage(mediaId: mediaId), from: stranger)
        await settle()
        var item = try #require(try await fixture.messages(stranger).first)
        guard case .attachment(let held) = item.body else { return }
        #expect(held.availability == .heldUntilAccepted)
        try await fixture.engine.acceptRequest(stranger)
        try await eventually("downloaded") {
            guard case .attachment(let shown)? = try await fixture.messages(stranger).first?.body else { return false }
            return shown.availability == .local
        }
        item = try #require(try await fixture.messages(stranger).first)
        let file = try await fixture.engine.exportMedia(item.messageId, peer: stranger)
        #expect(try Data(contentsOf: file) == Data("photo".utf8))
        await fixture.engine.stop()
    }

    @Test func viewOnceIsDestroyedOnBothSides() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        await fixture.start()
        let mediaId = RandomId.make()
        let id = RandomId.make()
        try await fixture.receive(.attachmentMessage(mediaId: mediaId, id: id, viewOnce: true), from: peer)
        try await eventually {
            guard case .attachment(let shown)? = try await fixture.messages(peer).first?.body else { return false }
            return shown.availability == .local
        }
        await #expect(throws: EngineError.invalidMessage) { _ = try await fixture.engine.exportMedia(id, peer: peer) }
        let file = try await fixture.engine.openViewOnce(id, peer: peer)
        #expect(FileManager.default.fileExists(atPath: file.path))
        try await fixture.engine.closeViewOnce(id, peer: peer, displayedFile: file)
        #expect(!FileManager.default.fileExists(atPath: file.path))
        #expect(try await fixture.messages(peer).first?.body == .viewOnceOpened)
        try await eventually("VIEWED") { fixture.backend.sent(to: peer).contains { $0.receipt?.type == .viewed } }
        await #expect(throws: EngineError.invalidMessage) { _ = try await fixture.engine.openViewOnce(id, peer: peer) }
        await fixture.engine.stop()
    }

    @Test func expiredBlobsBecomeUnavailable() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        await fixture.start()
        let mediaId = RandomId.make()
        fixture.backend.failDownload(mediaId, with: RelayError.server(status: 404, code: .notFound, message: nil))
        try await fixture.receive(.attachmentMessage(mediaId: mediaId), from: peer)
        try await eventually {
            guard case .attachment(let shown)? = try await fixture.messages(peer).first?.body else { return false }
            return shown.availability == .unavailable
        }
        await fixture.engine.stop()
    }
}

@Suite("Local actions")
struct LocalActionTests {
    @Test func screenshotsAddALineAndTellAcceptedContacts() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        await fixture.start()
        try await fixture.engine.screenCaptured(in: peer, recording: false)
        try await fixture.engine.screenCaptured(in: peer, recording: true)
        let lines = try await fixture.messages(peer).map(\.body)
        #expect(lines == [.notice(.screenshotByMe), .notice(.screenRecordingByMe)])
        try await eventually {
            fixture.backend.sent(to: peer).filter { if case .screenshot? = $0.control { return true } else { return false } }
                .count == 2
        }
        await fixture.engine.stop()
    }

    @Test func liveLocationTicksAreEphemeral() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        await fixture.start()
        let session = try await fixture.engine.startLiveLocation(
            peer: peer, latitude: 41.0, longitude: 29.0, accuracy: 5, duration: 3600)
        try await eventually { fixture.backend.sent(to: peer).contains { $0.data != nil } }
        try await fixture.engine.updateLiveLocation(session, peer: peer, latitude: 41.001, longitude: 29.001, accuracy: 5)
        let tick = try #require(fixture.backend.sent(to: peer).last)
        #expect(tick.ephemeral)
        guard case .liveLocation(let shown)? = try await fixture.messages(peer).first?.body else {
            Issue.record("no bubble")
            return
        }
        #expect(shown.latitude == 41.001)
        #expect(shown.sequence == 1)
        #expect(await fixture.engine.activeLiveLocationSessions() == [session])
        try await fixture.engine.stopLiveLocation(session, peer: peer)
        #expect(await fixture.engine.activeLiveLocationSessions().isEmpty)
        try await eventually {
            fixture.backend.sent(to: peer).contains {
                if case .liveLocation(let live)? = $0.data?.payload { return live.stopped && !$0.ephemeral }
                return false
            }
        }
        await fixture.engine.stop()
    }

    @Test func profileChangesGoToContactsThatHaveOne() async throws {
        let fixture = try EngineFixture()
        let peer = try fixture.acceptedContact()
        await fixture.start()
        try await fixture.engine.updateUserSettings { $0.displayName = "Selin" }
        try await eventually { fixture.backend.sent(to: peer).contains { $0.isProfile } }
        let profile = try #require(fixture.backend.sent(to: peer).last { $0.isProfile })
        guard case .profile(let update)? = profile.control else { return }
        #expect(update.displayName == "Selin")
        #expect(update.username == "tester")
        #expect(update.presenceSharing)
        await fixture.engine.stop()
    }

    @Test func runtimeStateIsEncryptedAndSurvivesARestart() async throws {
        let fixture = try EngineFixture()
        await fixture.start()
        try await fixture.engine.updateUserSettings { $0.displayName = "Gizli Ad" }
        await fixture.engine.stop()
        let file = fixture.directory.directories.runtime.appendingPathComponent(RuntimeStateFile.fileName)
        let bytes = try Data(contentsOf: file)
        #expect(bytes.range(of: Data("Gizli Ad".utf8)) == nil)
        let reopened = try RuntimeStateFile(directory: fixture.directory.directories.runtime, dek: fixture.key)
        #expect(reopened.load().settings.displayName == "Gizli Ad")
        let wrong = try RuntimeStateFile(
            directory: fixture.directory.directories.runtime, dek: SecretKeyMaterial(copying: randomKey()))
        #expect(wrong.load().settings.displayName == "")
        // Once the engine stopped, its zeroed keys are never used to write.
        await #expect(throws: SecretKeyMaterialError.wiped) {
            try await fixture.engine.updateUserSettings { $0.displayName = "after lock" }
        }
        #expect(reopened.load().settings.displayName == "Gizli Ad")
    }
}
