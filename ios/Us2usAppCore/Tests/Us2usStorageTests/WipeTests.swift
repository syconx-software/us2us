// us2us — AGPL-3.0-only

import Foundation
import LibSignalClient
import Testing
import Us2usKit

@testable import Us2usStorage

private func enableGhost(
    _ storage: LocalStorage, _ id: ConversationId, timeout: UInt32, wipeContact: Bool = false, at now: Date
) throws {
    _ = try storage.write { tx throws in
        try tx.conversations.updateSettings(of: id, now: now) { settings in
            settings.ghost.enabled = true
            settings.ghost.timeoutSeconds = timeout
            settings.ghost.wipeContact = wipeContact
        }
    }
}

@Suite("Crypto-shredding (§5.3)")
struct CryptoShreddingTests {
    @Test("wiping destroys the slot first; nothing of the conversation is recoverable, even with the DEK")
    func shreddingIsUnrecoverable() throws {
        let environment = try TestEnvironment()
        let storage = try environment.open()
        _ = try TestAccount(storage: storage)
        let (_, conversation) = try makeContactWithConversation(storage)
        let attachmentId = try storage.write { tx -> Data in
            try tx.messages.storeIncoming(textMessage("SECRET-TEXT"), in: conversation.id)
            var data = BRPDataMessage(payload: .attachment(sampleAttachment()))
            data.messageID = RandomId.make()
            try tx.messages.storeIncoming(data, in: conversation.id)
            let attachment = try #require(try tx.attachments.forMessage(data.messageID, in: conversation.id).first)
            try tx.attachments.storeFile(Data("SECRET-FILE".utf8), for: attachment.id)
            return attachment.id
        }
        // The attacker's copy: database files and the item file, taken before the wipe.
        let itemCopy = try Data(contentsOf: storage.files.url(for: attachmentId))
        let databaseCopy = try environment.copyDatabase(to: environment.directory.appendingPathComponent("copy"))
        let row = try #require(
            try storage.database.locked { try $0.queryOne("SELECT message_id, body FROM messages LIMIT 1") })
        let body = try #require(row.blob("body"))
        let bodyId = try #require(row.blob("message_id"))

        let wiped = try #require(try storage.wipeConversation(conversation.id, reason: .requested))
        #expect(wiped.reason == .requested)
        #expect(!FileManager.default.fileExists(atPath: storage.keyring.slotURL(conversation.slotId).path))
        #expect(!storage.files.exists(attachmentId))
        #expect(try storage.read { try $0.conversations.get(conversation.id) } == nil)
        #expect(try storage.database.locked { try $0.query("SELECT 1 FROM messages") }.isEmpty)
        #expect(!environment.databaseBytes().contains(Data("SECRET".utf8)))

        // With the DEK and the old database, CK cannot be rebuilt: the slot is gone.
        #expect(throws: StorageError.conversationShredded) { try storage.keyring.key(for: conversation.slotId) }
        // CK was random, so nothing derived from the DEK opens the copies.
        for candidate in [environment.dek, LocalKeyDerivation.databaseKey(dek: environment.dek)] {
            #expect(throws: LocalStorageError.self) {
                try ItemCipher.open(itemCopy, itemId: attachmentId, conversationKey: candidate)
            }
            #expect(throws: LocalStorageError.self) {
                try ItemCipher.open(body, itemId: bodyId, conversationKey: candidate)
            }
        }
        #expect(FileManager.default.fileExists(atPath: databaseCopy.path))
    }

    @Test("a background wipe while locked shreds without the DEK; the next unlock collects the rest")
    func lockedBackgroundWipe() throws {
        let environment = try TestEnvironment()
        let storage = try environment.open(now: t0)
        _ = try TestAccount(storage: storage)
        let (contact, conversation) = try makeContactWithConversation(storage, now: t0)
        try enableGhost(storage, conversation.id, timeout: 3600, at: t0)
        let attachmentId = try storage.write { tx -> Data in
            var data = BRPDataMessage(payload: .attachment(sampleAttachment()))
            data.messageID = RandomId.make()
            try tx.messages.storeIncoming(data, in: conversation.id, receivedAt: t0)
            let attachment = try #require(try tx.attachments.forMessage(data.messageID, in: conversation.id).first)
            try tx.attachments.storeFile(Data("FILE".utf8), for: attachment.id)
            return attachment.id
        }
        storage.close()  // vault locked: no DEK anywhere

        #expect(LocalStorage.nextBackgroundWipeDate(layout: environment.layout) == t0.addingTimeInterval(3600))
        #expect(LocalStorage.runBackgroundWipes(layout: environment.layout, now: t0.addingTimeInterval(3599)) == 0)
        #expect(LocalStorage.runBackgroundWipes(layout: environment.layout, now: t0.addingTimeInterval(3600)) == 1)
        #expect(!FileManager.default.fileExists(atPath: environment.layout.slotDirectory
            .appendingPathComponent(conversation.slotId.hexString + ".slot").path))
        #expect(LocalStorage.nextBackgroundWipeDate(layout: environment.layout) == nil)

        let opened = try environment.openReport(now: t0.addingTimeInterval(7200))
        let reopened = opened.storage
        #expect(opened.wipeReport.conversations.map(\.conversationId) == [conversation.id])
        #expect(opened.wipeReport.conversations.first?.reason == .ghostTimeout)
        #expect(try reopened.read { try $0.conversations.get(conversation.id) } == nil)
        #expect(!reopened.files.exists(attachmentId))
        // The other phone is told (§8.2).
        let outbox = try reopened.read { try $0.outbox.all() }
        #expect(outbox.count == 1)
        #expect(outbox.first?.recipient == contact)
        guard case .control(let control)? = outbox.first?.content.body, case .wipe(let wipe)? = control.action else {
            Issue.record("expected a ConversationWipe")
            return
        }
        #expect(wipe.reason == .ghostTimeout)
        #expect(outbox.first?.conversationId == nil)
    }

    @Test("orphaned slots and files are garbage-collected on unlock")
    func garbageCollection() throws {
        let environment = try TestEnvironment()
        let storage = try environment.open()
        _ = try TestAccount(storage: storage)
        let (_, conversation) = try makeContactWithConversation(storage)
        // An orphan slot (e.g. a crash after creating it), an orphan item and a stray temp file.
        let orphanSlot = try storage.keyring.createSlot()
        let orphanItem = RandomId.make()
        try storage.files.write(Data("x".utf8), itemId: orphanItem, key: orphanSlot.key)
        let stray = storage.files.directory.appendingPathComponent(".dat.nosync1234")
        try Data("partial".utf8).write(to: stray)
        storage.close()

        let opened = try environment.openReport()
        #expect(opened.wipeReport.orphanSlotsRemoved == 1)
        #expect(opened.wipeReport.orphanFilesRemoved == 2)
        #expect(!opened.storage.keyring.slotExists(orphanSlot.slotId))
        #expect(opened.storage.keyring.slotExists(conversation.slotId))
        #expect(!opened.storage.files.exists(orphanItem))
        #expect(!FileManager.default.fileExists(atPath: stray.path))
    }

    @Test("a crash between shredding and deleting rows is finished at the next unlock")
    func crashMidWipe() throws {
        let environment = try TestEnvironment()
        let storage = try environment.open()
        _ = try TestAccount(storage: storage)
        let (_, conversation) = try makeContactWithConversation(storage)
        _ = try storage.write { tx throws in try tx.messages.storeIncoming(textMessage("x"), in: conversation.id) }
        // Step 1 only (the slot), then "crash".
        try storage.keyring.destroy(conversation.slotId)
        storage.close()

        let opened = try environment.openReport()
        #expect(opened.wipeReport.conversations.first?.reason == .shreddedEarlier)
        #expect(try opened.storage.read { try $0.conversations.get(conversation.id) } == nil)
    }

    @Test("duress / account deletion destroys everything")
    func destroyAll() throws {
        let environment = try TestEnvironment()
        let storage = try environment.open()
        _ = try TestAccount(storage: storage)
        _ = try makeContactWithConversation(storage)
        storage.close()
        try LocalStorage.destroyAll(layout: environment.layout)
        #expect(!FileManager.default.fileExists(atPath: environment.layout.root.path))
    }
}

@Suite("Ghost mode deadlines (§8.2)")
struct GhostModeTests {
    @Test("deadline = max(lastActivity, enabledAt) + G, exactly")
    func exactDeadline() throws {
        let environment = try TestEnvironment()
        let storage = try environment.open(now: t0)
        _ = try TestAccount(storage: storage)
        let (_, conversation) = try makeContactWithConversation(storage, now: t0)
        _ = try storage.write { tx throws in
            try tx.messages.storeIncoming(textMessage("old"), in: conversation.id, receivedAt: t0)
        }
        // Enabled later: a full timeout from enabling, not from the old message.
        let enabled = t0.addingTimeInterval(86_400)
        try enableGhost(storage, conversation.id, timeout: 600, at: enabled)
        var current = try #require(try storage.read { try $0.conversations.get(conversation.id) })
        #expect(current.ghostDeadline == enabled.addingTimeInterval(600))

        // New activity moves it: received at t, deadline t + G (ms precision).
        let received = enabled.addingTimeInterval(123.456)
        _ = try storage.write { tx throws in
            try tx.messages.storeIncoming(textMessage("new"), in: conversation.id, receivedAt: received)
        }
        current = try #require(try storage.read { try $0.conversations.get(conversation.id) })
        #expect(current.ghostDeadline?.storageMilliseconds == received.storageMilliseconds + 600_000)
        #expect(current.lastActivityAt?.storageMilliseconds == received.storageMilliseconds)

        let deadline = try #require(current.ghostDeadline)
        #expect(try storage.performDueWipes(now: deadline.addingTimeInterval(-0.001)).conversations.isEmpty)
        #expect(try storage.performDueWipes(now: deadline).conversations.map(\.conversationId) == [conversation.id])
    }

    @Test("the schedule file holds only {slotId, deadline}")
    func scheduleContents() throws {
        let environment = try TestEnvironment()
        let storage = try environment.open(now: t0)
        _ = try TestAccount(storage: storage)
        let (_, conversation) = try makeContactWithConversation(storage, now: t0)
        try enableGhost(storage, conversation.id, timeout: 60, at: t0)
        let data = try Data(contentsOf: environment.layout.scheduleURL)
        let json = try #require(try JSONSerialization.jsonObject(with: data) as? [[String: Any]])
        #expect(json.count == 1)
        #expect(Set(json[0].keys) == ["slotId", "deadline"])
        #expect(json[0]["slotId"] as? String == conversation.slotId.hexString)
        #expect((json[0]["deadline"] as? NSNumber)?.int64Value == t0.storageMilliseconds + 60_000)
        #expect(!data.contains(conversation.contact.bytes))
        #expect(!data.contains(Data(conversation.contact.string.utf8)))

        // Disabling ghost mode removes the entry.
        _ = try storage.write { tx throws in
            try tx.conversations.updateSettings(of: conversation.id, now: t0) { $0.ghost.enabled = false }
        }
        #expect(try storage.read { try $0.conversations.get(conversation.id) }?.ghostDeadline == nil)
        #expect(storage.keyring.deadlines().isEmpty)
    }

    @Test("reactions and deletes reset the timer; live-location ticks and duplicates do not")
    func whatCountsAsActivity() throws {
        let environment = try TestEnvironment()
        let storage = try environment.open(now: t0)
        _ = try TestAccount(storage: storage)
        let (_, conversation) = try makeContactWithConversation(storage, now: t0)
        try enableGhost(storage, conversation.id, timeout: 600, at: t0)
        let message = textMessage("hi")
        var live = BRPLiveLocation()
        live.sessionID = RandomId.make()
        live.sequence = 1
        live.expiresAt = t0.addingTimeInterval(3600).millisecondsSince1970
        let liveStart = BRPDataMessage(payload: .liveLocation(live))
        try storage.write { tx throws in
            try tx.messages.storeIncoming(message, in: conversation.id, receivedAt: t0.addingTimeInterval(10))
            try tx.messages.storeIncoming(liveStart, in: conversation.id, receivedAt: t0.addingTimeInterval(20))
        }
        func lastActivity() throws -> Date? {
            try storage.read { try $0.conversations.get(conversation.id) }?.lastActivityAt
        }
        #expect(try lastActivity() == t0.addingTimeInterval(20))

        live.sequence = 2
        try storage.write { tx throws in
            let outcome = try tx.messages.storeIncoming(
                BRPDataMessage(payload: .liveLocation(live)), in: conversation.id, receivedAt: t0.addingTimeInterval(30))
            guard case .liveLocationUpdated = outcome else {
                Issue.record("expected an in-place update, got \(outcome)")
                return
            }
            #expect(try tx.messages.storeIncoming(message, in: conversation.id, receivedAt: t0.addingTimeInterval(40))
                == .duplicate)
        }
        #expect(try lastActivity() == t0.addingTimeInterval(20))

        var reaction = BRPReaction()
        reaction.targetMessageID = message.messageID
        reaction.emoji = "👍"
        try storage.write { tx throws in
            #expect(try tx.messages.storeIncoming(
                BRPDataMessage(payload: .reaction(reaction)), in: conversation.id, receivedAt: t0.addingTimeInterval(50))
                == .reaction(target: message.messageID))
        }
        #expect(try lastActivity() == t0.addingTimeInterval(50))
    }

    @Test("wipe_contact hides the contact, tells the other phone, then forgets contact and session")
    func wipeContact() throws {
        let environment = try TestEnvironment()
        let storage = try environment.open(now: t0)
        let account = try TestAccount(storage: storage)
        let peer = Party.inMemory()
        let me = Party(account: account.accountId, store: account.store)
        try me.cipher.processBundle(try peer.bundle())
        let (contact, conversation) = try makeContactWithConversation(storage, contact: peer.account, now: t0)
        try enableGhost(storage, conversation.id, timeout: 60, wipeContact: true, at: t0)

        let report = try storage.performDueWipes(now: t0.addingTimeInterval(60))
        #expect(report.conversations.first?.wipeContact == true)
        #expect(try storage.read { try $0.contacts.all() }.isEmpty)  // hidden
        let entry = try #require(try storage.read { try $0.outbox.due(now: t0.addingTimeInterval(60)) }.first)
        // The session is still there to encrypt the ConversationWipe…
        #expect(try account.store.loadSession(for: contact.protocolAddress, context: NullContext()) != nil)
        try storage.write { tx throws in try tx.outbox.markSent(entry.id) }
        // …and gone afterwards, with the contact.
        #expect(try storage.read { try $0.contacts.get(contact) } == nil)
        #expect(try account.store.loadSession(for: contact.protocolAddress, context: NullContext()) == nil)
        #expect(try account.store.identityTrust(for: contact) == .unknown)
    }

    @Test("wipe_contact on a blocked contact keeps the block and nothing else")
    func wipeBlockedContact() throws {
        let environment = try TestEnvironment()
        let storage = try environment.open(now: t0)
        let account = try TestAccount(storage: storage)
        let peer = Party.inMemory()
        try Party(account: account.accountId, store: account.store).cipher.processBundle(try peer.bundle())
        let (contact, conversation) = try makeContactWithConversation(storage, contact: peer.account, now: t0)
        try enableGhost(storage, conversation.id, timeout: 60, wipeContact: true, at: t0)
        try storage.write { tx throws in
            var profile = ContactProfile()
            profile.displayName = "Engellenen"
            try tx.contacts.setProfile(profile, for: contact)
            try tx.contacts.block(contact)
        }
        _ = try storage.performDueWipes(now: t0 + 60)
        let remaining = try #require(try storage.read { try $0.contacts.get(contact) })
        #expect(remaining.isBlocked)
        #expect(remaining.profile.displayName == nil)
        #expect(try storage.read { try $0.outbox.all() }.isEmpty)
        #expect(try account.store.loadSession(for: contact.protocolAddress, context: NullContext()) == nil)
    }

    @Test("settings are last-writer-wins; ties go to the smaller account id")
    func settingsLWW() throws {
        let environment = try TestEnvironment()
        let storage = try environment.open(now: t0)
        _ = try TestAccount(storage: storage)
        let (contact, conversation) = try makeContactWithConversation(storage, now: t0)
        var newer = BRPConversationSettings()
        newer.updatedAt = 2000
        newer.defaultExpireTimerSeconds = 30
        var older = BRPConversationSettings()
        older.updatedAt = 1000
        older.defaultExpireTimerSeconds = 99
        try storage.write { tx throws in
            #expect(try tx.conversations.applySettings(newer, author: contact, to: conversation.id, now: t0) == newer)
            #expect(try tx.conversations.applySettings(older, author: contact, to: conversation.id, now: t0) == newer)
        }
        #expect(try storage.read { try $0.conversations.get(conversation.id) }?.defaultExpireTimerSeconds == 30)

        // A tie on updated_at: the smaller author account id wins, on both phones alike.
        let other = AccountId(uuid: UUID())
        var tie = newer
        tie.defaultExpireTimerSeconds = 77
        let expected = other < contact ? tie : newer
        try storage.write { tx throws in
            #expect(try tx.conversations.applySettings(tie, author: other, to: conversation.id, now: t0) == expected)
        }
    }

    @Test("a damaged schedule file is rebuilt instead of blocking storage")
    func damagedSchedule() throws {
        let environment = try TestEnvironment()
        let storage = try environment.open(now: t0)
        _ = try TestAccount(storage: storage)
        let (_, conversation) = try makeContactWithConversation(storage, now: t0)
        try enableGhost(storage, conversation.id, timeout: 600, at: t0)
        storage.close()
        try Data("{ not json".utf8).write(to: environment.layout.scheduleURL)
        #expect(LocalStorage.runBackgroundWipes(layout: environment.layout, now: t0 + 9999) == 0)
        let reopened = try environment.open(now: t0)
        #expect(reopened.keyring.deadlines() == [conversation.slotId: t0.addingTimeInterval(600)])
        // Writes keep working.
        _ = try reopened.write { tx throws in
            try tx.messages.storeIncoming(textMessage("x"), in: conversation.id, receivedAt: t0 + 10)
        }
        #expect(reopened.keyring.deadlines() == [conversation.slotId: t0.addingTimeInterval(610)])
    }

    @Test("while storage is open, the background entry point runs the full pass through it")
    func backgroundWhileOpen() throws {
        let environment = try TestEnvironment()
        let storage = try environment.open(now: t0)
        _ = try TestAccount(storage: storage)
        let (_, conversation) = try makeContactWithConversation(storage, now: t0)
        _ = try storage.write { tx throws in
            try tx.messages.storeIncoming(textMessage("x"), in: conversation.id, receivedAt: t0)
        }
        try enableGhost(storage, conversation.id, timeout: 60, at: t0)
        _ = try storage.keyring.key(for: conversation.slotId)  // cached
        #expect(LocalStorage.runBackgroundWipes(layout: environment.layout, now: t0 + 60) == 1)
        #expect(throws: StorageError.conversationShredded) { try storage.keyring.key(for: conversation.slotId) }
        #expect(try storage.read { try $0.conversations.get(conversation.id) } == nil)
    }

    @Test("the schedule is repaired from the database on unlock")
    func scheduleReconciled() throws {
        let environment = try TestEnvironment()
        let storage = try environment.open(now: t0)
        _ = try TestAccount(storage: storage)
        let (_, conversation) = try makeContactWithConversation(storage, now: t0)
        try enableGhost(storage, conversation.id, timeout: 600, at: t0)
        // Simulate drift: the schedule lost its entry.
        try storage.keyring.setDeadline(nil, for: conversation.slotId)
        storage.close()
        let reopened = try environment.open(now: t0)
        #expect(reopened.keyring.deadlines()[conversation.slotId] == t0.addingTimeInterval(600))
    }
}

@Suite("Disappearing messages (§8.1)")
struct DisappearingTests {
    @Test("outgoing: createdAt + T; incoming: displayedAt + T; never shown after the deadline")
    func deadlines() throws {
        let environment = try TestEnvironment()
        let storage = try environment.open(now: t0)
        _ = try TestAccount(storage: storage)
        let (_, conversation) = try makeContactWithConversation(storage, now: t0)
        let outgoing = textMessage("bye", timer: 30)
        let incoming = textMessage("hey", timer: 60)
        var attachmentData = BRPDataMessage(payload: .attachment(sampleAttachment()), expireTimerSeconds: 60)
        attachmentData.messageID = RandomId.make()
        let attachmentId = try storage.write { tx -> Data in
            try tx.messages.storeOutgoing(outgoing, in: conversation.id, at: t0)
            try tx.messages.storeIncoming(incoming, in: conversation.id, receivedAt: t0)
            try tx.messages.storeIncoming(attachmentData, in: conversation.id, receivedAt: t0)
            let id = try #require(try tx.attachments.forMessage(attachmentData.messageID, in: conversation.id).first?.id)
            try tx.attachments.storeFile(Data("pic".utf8), for: id)
            return id
        }
        try storage.read { tx throws in
            #expect(try tx.messages.get(outgoing.messageID, in: conversation.id, now: t0)?.expiresAt
                == t0.addingTimeInterval(30))
            #expect(try tx.messages.get(incoming.messageID, in: conversation.id, now: t0)?.expiresAt == nil)
        }
        // Not displayed for a while: the incoming timer has not started.
        let shown = t0.addingTimeInterval(1000)
        try storage.write { tx throws in
            #expect(try tx.messages.markAllDisplayed(in: conversation.id, at: shown).count == 2)
            #expect(try tx.messages.markDisplayed([incoming.messageID], in: conversation.id, at: shown + 5).isEmpty)
        }
        try storage.read { tx throws in
            #expect(try tx.messages.get(incoming.messageID, in: conversation.id, now: shown)?.expiresAt
                == shown.addingTimeInterval(60))
            // Past the deadline it is hidden even before any wipe ran.
            #expect(try tx.messages.page(in: conversation.id, now: shown.addingTimeInterval(60)).isEmpty)
            #expect(try tx.messages.page(in: conversation.id, now: shown.addingTimeInterval(59)).count == 2)
        }
        #expect(try storage.nextDeadline() == t0.addingTimeInterval(30))

        let report = try storage.performDueWipes(now: shown.addingTimeInterval(60))
        #expect(Set(report.expiredMessages[conversation.id] ?? []) == [outgoing.messageID, incoming.messageID, attachmentData.messageID])
        #expect(try storage.read { try $0.messages.count(in: conversation.id) } == 0)
        #expect(!storage.files.exists(attachmentId))
    }
}
