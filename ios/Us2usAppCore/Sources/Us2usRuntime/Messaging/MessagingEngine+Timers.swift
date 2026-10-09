import Foundation
import Us2usKit
import Us2usStorage

extension MessagingEngine {
    // MARK: Ghost mode and disappearing messages (§8.1, §8.2)

    /// Sleeps until the next deadline (a disappearing message, a ghost
    /// timeout, the end of a live-location session) and executes it; woken
    /// early whenever a deadline may have moved.
    func schedulerLoop() async {
        while running && !Task.isCancelled {
            runDueWipes()
            expireLiveSessions()
            let next = nextDeadline()
            let wait = next.map { max(0.05, $0.timeIntervalSince(clock.now())) } ?? 3600
            try? await schedulerWake.wait(seconds: min(wait, 3600), clock: clock)
        }
    }

    func nextDeadline() -> Date? {
        let storageDeadline = try? storage.nextDeadline()
        let live = liveSessions.values.map(\.expiresAt).min()
        return [storageDeadline ?? nil, live].compactMap { $0 }.min()
    }

    /// Ghost timeouts (slot first, rows, files; a `ConversationWipe` to the
    /// contact is queued by Us2usStorage) and expired disappearing messages.
    func runDueWipes() {
        guard let report = try? storage.performDueWipes(now: clock.now(), collectGarbage: false) else { return }
        handleWipeReport(report)
    }

    func handleWipeReport(_ report: WipeReport) {
        guard !report.isEmpty else { return }
        for wiped in report.conversations {
            forgetNotices(of: wiped.conversationId)
            presence.forget(wiped.contact)
            typing[wiped.contact] = nil
            emit(.wiped(wiped.contact))
        }
        for conversationId in report.expiredMessages.keys {
            if let peer = try? read({ try $0.conversations.get(conversationId)?.contact }) { emit(.messages(peer)) }
        }
        emit(.conversations)
        outboxWake.signal()
    }

    private func expireLiveSessions() {
        let now = clock.now()
        for (session, value) in liveSessions where value.expiresAt <= now {
            liveSessions[session] = nil
            emit(.messages(value.peer))
        }
    }

    /// "Delete for both" (§8.2 `USER_REQUEST`): wipe here first, then ask
    /// the contact to wipe too.
    public func deleteConversationForBoth(_ peer: AccountId) throws {
        let now = clock.now()
        try write { tx in
            guard let conversation = try tx.conversations.forContact(peer) else { throw EngineError.unknownContact }
            _ = try storage.wipeConversation(conversation.id, reason: .requested, now: now)
            forgetNotices(of: conversation.id)
            if let contact = try tx.contacts.get(peer), contact.requestState == .accepted {
                var wipe = BRPConversationWipe()
                wipe.requestedAt = now.millisecondsSince1970
                wipe.reason = .userRequest
                try tx.outbox.enqueue(BRPContent(control: .wipe(wipe)), to: peer, now: now)
            }
        }
        emit(.wiped(peer))
        emit(.conversations)
        outboxWake.signal()
    }

    /// Deletes the conversation on this phone only.
    public func deleteConversationLocally(_ peer: AccountId) throws {
        try write { tx in
            guard let conversation = try tx.conversations.forContact(peer) else { throw EngineError.unknownContact }
            _ = try storage.wipeConversation(conversation.id, reason: .requested, now: clock.now())
            forgetNotices(of: conversation.id)
        }
        emit(.wiped(peer))
        emit(.conversations)
    }

    // MARK: Notices

    /// Appends a local system line to the contact's conversation (created if needed).
    func addNotice(_ notice: SystemNotice, peer: AccountId, at time: Date) {
        guard
            let conversation = try? write({ tx -> Conversation? in
                guard try tx.contacts.get(peer) != nil else { return nil }
                return try tx.conversations.ensure(for: peer, now: time)
            })
        else { return }
        let key = conversation.id.rawValue.hexString
        let existing = runtime.noticeSlots[key].flatMap { Data(hexString: $0) }
        let record = NoticeRecord(id: RandomId.make(), at: time, notice: notice)
        guard let slot = try? notices.append(record, slotId: existing, deadline: conversation.ghostDeadline) else { return }
        if existing != slot {
            runtime.noticeSlots[key] = slot.hexString
            saveRuntime()
        }
        emit(.messages(peer))
    }

    func notices(of conversationId: ConversationId) -> [NoticeRecord] {
        guard let slot = runtime.noticeSlots[conversationId.rawValue.hexString].flatMap({ Data(hexString: $0) }) else {
            return []
        }
        return notices.notices(slotId: slot)
    }

    /// The notice slot follows the conversation's ghost deadline, so the
    /// background task shreds both at the same time.
    func mirrorNoticeDeadline(_ peer: AccountId) {
        guard let conversation = try? read({ try $0.conversations.forContact(peer) }),
            let slot = runtime.noticeSlots[conversation.id.rawValue.hexString].flatMap({ Data(hexString: $0) })
        else { return }
        notices.setDeadline(conversation.ghostDeadline, slotId: slot)
    }

    func forgetNotices(of conversationId: ConversationId) {
        let key = conversationId.rawValue.hexString
        guard let slot = runtime.noticeSlots.removeValue(forKey: key).flatMap({ Data(hexString: $0) }) else { return }
        notices.destroy(slotId: slot)
        saveRuntime()
    }

    /// Drops notices of conversations that no longer exist (wiped while
    /// locked, crash leftovers) and re-mirrors every deadline.
    func collectNoticeGarbage() {
        let conversations = (try? read { try $0.conversations.all() }) ?? []
        let existing = Dictionary(uniqueKeysWithValues: conversations.map { ($0.id.rawValue.hexString, $0) })
        var keep: Set<Data> = []
        var changed = false
        for (key, slotHex) in runtime.noticeSlots {
            guard let conversation = existing[key], let slot = Data(hexString: slotHex) else {
                runtime.noticeSlots[key] = nil
                changed = true
                continue
            }
            keep.insert(slot)
            notices.setDeadline(conversation.ghostDeadline, slotId: slot)
        }
        notices.collectGarbage(keep: keep)
        if changed { saveRuntime() }
    }
}
