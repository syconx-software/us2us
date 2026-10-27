import Foundation
import Us2usKit
import Us2usStorage

extension MessagingEngine {
    // MARK: Presence (§8.6)

    /// Eligible contacts with the key their ephemeral sends need.
    func presenceTargets(_ filter: ((AccountId) -> Bool)? = nil) -> [(account: AccountId, accessKey: Data)] {
        guard settings.presenceSharing, let contacts = try? read({ try $0.contacts.accepted() }) else { return [] }
        return contacts.compactMap { contact in
            guard isPresenceEligible(contact), let key = contact.theirAccessKey, !contact.identityChanged,
                filter?(contact.accountId) ?? true
            else { return nil }
            return (contact.accountId, key)
        }
    }

    /// After `queue_empty`: ONLINE to every eligible contact in **one**
    /// `POST /v1/messages/ephemeral`.
    func announceOnline() async {
        let targets = presenceTargets()
        guard !targets.isEmpty else { return }
        _ = try? await backend.sendPresence(PresencePolicy.online(), to: targets)
    }

    /// End of the grace period: OFFLINE only to the contacts we see online.
    func announceOffline() async {
        let online = Set(presence.onlineContacts(at: clock.now()))
        let targets = presenceTargets { online.contains($0) }
        guard !targets.isEmpty else { return }
        _ = try? await backend.sendPresence(PresencePolicy.offline(), to: targets)
    }

    /// Answers ONLINE announcements once, batched: contacts that came online
    /// together share one ephemeral request.
    func queuePresenceReply(to peer: AccountId) {
        pendingPresenceReplies.insert(peer)
        guard presenceReplyTask == nil else { return }
        presenceReplyTask = Task { [weak self, clock, configuration] in
            try? await clock.sleep(seconds: configuration.presenceReplyDelay)
            await self?.sendPresenceReplies()
        }
    }

    private func sendPresenceReplies() async {
        presenceReplyTask = nil
        let peers = pendingPresenceReplies
        pendingPresenceReplies = []
        guard running else { return }
        let targets = presenceTargets { peers.contains($0) }
        guard !targets.isEmpty else { return }
        _ = try? await backend.sendPresence(PresencePolicy.online(reply: true), to: targets)
    }

    /// Every 240 s: ONLINE again to the contacts currently seen online.
    func presenceRefreshLoop() async {
        while running && !Task.isCancelled {
            do {
                try await clock.sleep(seconds: PresencePolicy.refreshInterval)
            } catch {
                return
            }
            guard running, connected else { continue }
            let now = clock.now()
            presence.prune(at: now)
            let online = Set(presence.onlineContacts(at: now))
            let targets = presenceTargets { online.contains($0) }
            if !targets.isEmpty {
                _ = try? await backend.sendPresence(PresencePolicy.online(), to: targets)
            }
            emit(.presence)
        }
    }

    public func presenceStatus(at now: Date) -> [AccountId: PresenceStatus] {
        guard settings.presenceSharing, let contacts = try? read({ try $0.contacts.accepted() }) else { return [:] }
        var result: [AccountId: PresenceStatus] = [:]
        for contact in contacts where isPresenceEligible(contact) {
            let status = presence.status(of: contact.accountId, at: now)
            if status != .none { result[contact.accountId] = status }
        }
        return result
    }

    public func typingContacts(at now: Date) -> Set<AccountId> {
        Set(typing.filter { $0.value > now }.map(\.key))
    }

    // MARK: Pre-keys (§5)

    /// Refills one-time pre-keys and rotates signed / last-resort keys, at
    /// most every few hours while connected; finishes a pending access-key rotation.
    func maintainKeysIfDue() async {
        await rotateAccessKeyIfPending()
        let now = clock.now()
        if let last = lastMaintenance, now.timeIntervalSince(last) < configuration.maintenanceInterval { return }
        do {
            try await backend.performMaintenance()
            lastMaintenance = now
        } catch {
            RuntimeLog.messaging.info("key maintenance postponed: \(error.logLabel, privacy: .public)")
        }
    }

    // MARK: Live location (§8.3)

    /// Starts sharing; the start message goes through the outbox (stored by
    /// the relay), ticks are ephemeral, the stop message is normal again.
    /// The conversation's disappearing timer applies like to any message
    /// (§8.1): the bubble, which ends up holding the last precise position,
    /// disappears on both phones — never while sharing still runs.
    @discardableResult
    public func startLiveLocation(
        peer: AccountId, latitude: Double, longitude: Double, accuracy: Float, duration: TimeInterval
    ) throws -> Data {
        let now = clock.now()
        let sessionId = RandomId.make()
        let expiresAt = now.addingTimeInterval(min(max(duration, 60), 8 * 3600))
        var live = BRPLiveLocation()
        live.sessionID = sessionId
        live.latitude = latitude
        live.longitude = longitude
        live.accuracyMeters = accuracy
        live.headingDegrees = -1
        live.speedMps = -1
        live.expiresAt = expiresAt.millisecondsSince1970
        live.sequence = 0
        let id = RandomId.make()
        try write { tx in
            guard try tx.contacts.requestState(of: peer) == .accepted else { throw EngineError.notAllowed }
            let conversation = try tx.conversations.ensure(for: peer, now: now)
            let data = BRPDataMessage(
                payload: .liveLocation(live), messageId: id, sentAt: now,
                expireTimerSeconds: conversation.settings.defaultExpireTimerSeconds)
            _ = try tx.messages.storeOutgoing(data, in: conversation.id, at: now)
            try tx.outbox.enqueue(BRPContent(data: data), to: peer, conversation: conversation.id, messageId: id, now: now)
        }
        liveSessions[sessionId] = (peer, expiresAt)
        afterLocalChange(peer)
        return sessionId
    }

    /// One intermediate update (every 15–30 s): the bubble here changes in
    /// place, and the tick reaches the contact only while their app is open.
    public func updateLiveLocation(
        _ sessionId: Data, peer: AccountId, latitude: Double, longitude: Double, accuracy: Float, heading: Float = -1,
        speed: Float = -1
    ) async throws {
        let now = clock.now()
        guard let session = liveSessions[sessionId], session.peer == peer, session.expiresAt > now else {
            throw EngineError.invalidMessage
        }
        let (data, contact) = try write { tx -> (BRPDataMessage, Contact) in
            guard let contact = try tx.contacts.get(peer), contact.requestState == .accepted,
                let conversation = try tx.conversations.forContact(peer)
            else { throw EngineError.notAllowed }
            let (current, timer) = try Self.liveLocation(sessionId, in: conversation.id, tx: tx, now: now)
            var live = current
            live.latitude = latitude
            live.longitude = longitude
            live.accuracyMeters = accuracy
            live.headingDegrees = heading
            live.speedMps = speed
            live.sequence = current.sequence + 1
            // The bubble's timer travels along: a tick that reaches a phone
            // without the bubble (the start was lost) starts one that disappears too.
            let data = BRPDataMessage(payload: .liveLocation(live), sentAt: now, expireTimerSeconds: timer)
            _ = try tx.messages.storeOutgoing(data, in: conversation.id, at: now)
            return (data, contact)
        }
        emit(.messages(peer))
        guard let key = contact.theirAccessKey, !contact.identityChanged else { return }
        _ = try? await backend.send(
            BRPContent(data: data), to: peer, requestState: contact.requestState, theirAccessKey: key, ephemeral: true)
    }

    public func stopLiveLocation(_ sessionId: Data, peer: AccountId) throws {
        let now = clock.now()
        try write { tx in
            guard let conversation = try tx.conversations.forContact(peer) else { throw EngineError.unknownContact }
            let (current, timer) = try Self.liveLocation(sessionId, in: conversation.id, tx: tx, now: now)
            var live = current
            live.stopped = true
            live.sequence += 1
            let data = BRPDataMessage(payload: .liveLocation(live), sentAt: now, expireTimerSeconds: timer)
            _ = try tx.messages.storeOutgoing(data, in: conversation.id, at: now)
            try tx.outbox.enqueue(BRPContent(data: data), to: peer, conversation: conversation.id, now: now)
        }
        liveSessions[sessionId] = nil
        afterLocalChange(peer)
    }

    /// The conversation with `peer` is about to be deleted while we share
    /// our location with them: each running share gets its normal stop,
    /// queued without the conversation so the wipe does not take it along
    /// (their bubble stops instead of showing a frozen position as live
    /// until `expires_at`, §8.3). Nothing is stored in the conversation.
    func enqueueStopsBeforeDeleting(_ conversation: Conversation, tx: StorageTransaction, now: Date) {
        for (sessionId, session) in liveSessions where session.peer == conversation.contact {
            guard let (current, timer) = try? Self.liveLocation(sessionId, in: conversation.id, tx: tx, now: now)
            else { continue }
            var live = current
            live.stopped = true
            live.sequence += 1
            let data = BRPDataMessage(payload: .liveLocation(live), sentAt: now, expireTimerSeconds: timer)
            _ = try? tx.outbox.enqueue(BRPContent(data: data), to: conversation.contact, now: now)
        }
    }

    /// Our live-location bubbles that still look live although this engine
    /// sends nothing for them: the process ended while sharing (killed in
    /// the background, a crash, a relaunch) or the vault was locked, so the
    /// session's memory is gone. Each one gets its normal stop message now,
    /// at the first start after that (§8.3), so the contact never watches a
    /// frozen position that claims to be live for up to 8 hours. Sharing is
    /// never resumed by itself: that needs the user's tap. Expired sessions
    /// end on their own on both phones and are left alone.
    func stopOrphanedLiveLocations() {
        let now = clock.now()
        let nowMilliseconds = now.millisecondsSince1970
        let running = liveSessions
        let orphans: [(session: Data, peer: AccountId)]
        do {
            orphans = try read { tx -> [(session: Data, peer: AccountId)] in
                // Only the rows that carry a session are read and decrypted:
                // this runs at every unlock.
                let candidates = try tx.messages.outgoingLiveLocations(now: now)
                guard !candidates.isEmpty else { return [] }
                var contacts: [ConversationId: AccountId] = [:]
                for conversation in try tx.conversations.all() { contacts[conversation.id] = conversation.contact }
                var found: [(session: Data, peer: AccountId)] = []
                var seen: Set<Data> = []
                for message in candidates where message.isOutgoing && !message.isDeleted {
                    guard case .liveLocation(let live)? = message.content?.payload, !live.stopped,
                        live.expiresAt > nowMilliseconds, running[live.sessionID] == nil,
                        let peer = contacts[message.conversationId], seen.insert(live.sessionID).inserted
                    else { continue }
                    found.append((live.sessionID, peer))
                }
                return found
            }
        } catch {
            RuntimeLog.messaging.error("orphaned live locations not checked: \(error.logLabel, privacy: .public)")
            return
        }
        for orphan in orphans {
            do {
                try stopLiveLocation(orphan.session, peer: orphan.peer)
                RuntimeLog.messaging.info("stopped a live location this process no longer shares")
            } catch {
                RuntimeLog.messaging.error("orphaned live location not stopped: \(error.logLabel, privacy: .public)")
            }
        }
    }

    /// Our live-location sessions still running (the app keeps the store
    /// open in the background only for these, §8.3).
    public func activeLiveLocationSessions() -> [Data] {
        let now = clock.now()
        return liveSessions.filter { $0.value.expiresAt > now }.map(\.key)
    }

    /// When the last running session ends by itself, `nil` when none runs.
    public func liveLocationEnd() -> Date? {
        let now = clock.now()
        return liveSessions.values.map(\.expiresAt).filter { $0 > now }.max()
    }

    /// The session's bubble and its disappearing timer.
    private static func liveLocation(
        _ sessionId: Data, in conversationId: ConversationId, tx: StorageTransaction, now: Date
    ) throws -> (BRPLiveLocation, UInt32) {
        // The session's bubble is one of the latest messages.
        let page = try tx.messages.page(in: conversationId, limit: 500, now: now)
        for message in page.reversed() where message.isOutgoing && message.liveSessionId == sessionId {
            if case .liveLocation(let live)? = message.content?.payload { return (live, message.expireTimerSeconds) }
        }
        throw EngineError.invalidMessage
    }
}
