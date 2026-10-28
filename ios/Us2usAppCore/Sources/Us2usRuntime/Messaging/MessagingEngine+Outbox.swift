import Foundation
import Us2usKit
import Us2usStorage

extension MessagingEngine {
    enum SendResult {
        case sent
        /// Transient: back off (`retryAfter` from the relay, when it said so).
        case retry(code: String, retryAfter: TimeInterval?)
        /// Blocked until the user acts (changed safety number) or the token
        /// problem is solved; keep it as it is.
        case hold
        /// Permanent: give up; the message shows *Failed*.
        case drop(code: String)
        /// Must never be sent (an unaccepted requester): removed from the queue.
        case discard
    }

    func outboxLoop() async {
        while running && !Task.isCancelled {
            let next = await flushOutbox()
            let wait = next.map { max(0.2, $0.timeIntervalSince(clock.now())) } ?? 300
            try? await outboxWake.wait(seconds: min(wait, 300), clock: clock)
        }
    }

    /// Sends what can be sent now, strictly in order per contact: only the
    /// oldest entry of each contact is considered, so a retrying message
    /// never lets a later one overtake it. Returns when the next entry is due.
    func flushOutbox() async -> Date? {
        guard running, let entries = try? read({ try $0.outbox.all() }) else { return nil }
        var queues: [AccountId: [OutboxEntry]] = [:]
        var order: [AccountId] = []
        for entry in entries where entry.state != .failed {
            if queues[entry.recipient] == nil { order.append(entry.recipient) }
            queues[entry.recipient, default: []].append(entry)
        }
        var nextDue: Date?
        for recipient in order {
            guard running, !Task.isCancelled else { return nil }
            var queue = queues[recipient] ?? []
            while running, !Task.isCancelled, var head = queue.first {
                let now = clock.now()
                if head.state == .waitingForUpload {
                    if let wait = await upload(head) {
                        nextDue = min(nextDue ?? wait, wait)
                        break
                    }
                    // Uploaded (or given up): continue with its current state.
                    guard let updated = try? read({ try $0.outbox.get(head.id) }), updated.state == .ready else {
                        queue.removeFirst()
                        continue
                    }
                    head = updated
                    queue[0] = updated
                }
                if head.nextAttemptAt > now {
                    nextDue = min(nextDue ?? head.nextAttemptAt, head.nextAttemptAt)
                    break
                }
                if held.contains(recipient) { break }
                let batch = Self.mergeableReceipts(queue, now: now)
                // While the relay call runs, its outcome is unknown: an
                // unsend of these entries must send a `Delete`.
                let ids = Set(batch.entries.map(\.id))
                inFlightOutbox.formUnion(ids)
                let result = await send(batch.content, entry: head)
                inFlightOutbox.subtract(ids)
                record(result, for: batch.entries)
                if case .discard = result {
                    queue.removeFirst(batch.entries.count)
                    continue
                }
                guard case .sent = result else {
                    if case .retry = result, let next = try? read({ try $0.outbox.get(head.id) }) {
                        nextDue = min(nextDue ?? next.nextAttemptAt, next.nextAttemptAt)
                    }
                    break
                }
                queue.removeFirst(batch.entries.count)
            }
        }
        return nextDue
    }

    /// Consecutive delivery / read / viewed receipts to one contact go out as
    /// one `ReceiptMessage` (≤ 1000 ids, §8.4); anything else alone.
    static func mergeableReceipts(_ queue: [OutboxEntry], now: Date) -> (content: BRPContent, entries: [OutboxEntry]) {
        guard let head = queue.first, case .receipt(let first) = head.content.body else {
            return (queue[0].content, [queue[0]])
        }
        var ids = first.messageIds
        var entries = [head]
        for entry in queue.dropFirst() {
            guard entry.state == .ready, entry.nextAttemptAt <= now, case .receipt(let next) = entry.content.body,
                next.type == first.type, ids.count + next.messageIds.count <= ReceiptPolicy.maxMessageIds
            else { break }
            ids.append(contentsOf: next.messageIds)
            entries.append(entry)
        }
        return (BRPContent(receipt: first.type, messageIds: ids), entries)
    }

    private func send(_ content: BRPContent, entry: OutboxEntry) async -> SendResult {
        guard let contact = try? read({ try $0.contacts.get(entry.recipient) }) else { return .drop(code: "no_contact") }
        guard contact.requestState != .blocked else { return .drop(code: "blocked") }
        // §6.4: nothing reaches a requester before the user accepted (the
        // ACCEPT itself is sent directly, not through the queue).
        guard contact.requestState != .incomingRequest else { return .discard }
        if contact.identityChanged {
            held.insert(contact.accountId)
            return .hold
        }
        do {
            _ = try await backend.send(
                content, to: entry.recipient, requestState: contact.requestState,
                theirAccessKey: contact.theirAccessKey, ephemeral: false)
            return .sent
        } catch {
            return classify(error, contact: contact)
        }
    }

    /// Maps a send error to what the outbox does with the entry.
    func classify(_ error: Error, contact: Contact) -> SendResult {
        switch error {
        case let error as MessengerError:
            switch error {
            case .blocked: return .drop(code: "blocked")
            case .attachmentTooLarge: return .drop(code: "attachment_too_large")
            case .contentTooLarge, .invalidContent, .ephemeralRequiresAccessKey, .invalidBackup:
                return .drop(code: "invalid")
            }
        case let error as SessionError:
            if case .identityChanged = error {
                // Found while sending (the bundle carried another key than
                // the trusted one): the same warning as on receive (§5) —
                // once, not again for every message that waits behind it.
                if held.insert(contact.accountId).inserted {
                    addNotice(.safetyNumberChanged, peer: contact.accountId, at: clock.now())
                    emit(.safetyNumberChanged(contact.accountId))
                    emit(.conversations)
                }
                return .hold
            }
            return .retry(code: "session", retryAfter: nil)
        case let error as RelayError:
            switch error {
            case .server(404, _, _): return .drop(code: "not_found")
            case .server(403, let code, _) where code == .accessDenied:
                if contact.theirAccessKey != nil {
                    // Their key was rotated (they blocked someone); the new one
                    // arrives end to end. Until then, proof-of-work.
                    _ = try? write { try $0.contacts.setTheirAccessKey(nil, for: contact.accountId) }
                    return .retry(code: "access_key_rotated", retryAfter: 0)
                }
                return .retry(code: "access_denied", retryAfter: nil)
            case .server(403, _, _): return .retry(code: "pow", retryAfter: 0)
            case .server(400, _, _), .server(413, _, _), .payloadTooLarge: return .drop(code: "invalid")
            case .server(401, _, _), .notAuthenticated: return .hold
            case .rateLimited(let retryAfter): return .retry(code: "rate_limited", retryAfter: retryAfter ?? 60)
            default: return .retry(code: "network", retryAfter: nil)
            }
        case is CancellationError:
            return .retry(code: "cancelled", retryAfter: 0)
        default:
            return .retry(code: "other", retryAfter: nil)
        }
    }

    private func record(_ result: SendResult, for entries: [OutboxEntry]) {
        let now = clock.now()
        var touched: Set<AccountId> = []
        _ = try? write { tx in
            for entry in entries {
                switch result {
                case .sent:
                    try tx.outbox.markSent(entry.id, now: now)
                    // §6.4: our outgoing request now exists on the other phone (M-9).
                    if case .data(let data) = entry.content.body, Self.opensRequest(data) {
                        try tx.contacts.markRequestSent(to: entry.recipient, now: now)
                    }
                case .retry(let code, let retryAfter):
                    var policy = configuration.retryPolicy
                    if let retryAfter {
                        // The relay said when; otherwise exponential backoff.
                        policy = RetryPolicy(
                            base: max(0.5, retryAfter), maximum: max(0.5, min(retryAfter, 3600)), jitter: 0.1,
                            maximumAge: policy.maximumAge)
                    }
                    try tx.outbox.markFailed(entry.id, error: code, retryable: true, policy: policy, now: now)
                case .drop(let code):
                    RuntimeLog.messaging.notice("send dropped: \(code, privacy: .public)")
                    try tx.outbox.markFailed(entry.id, error: code, retryable: false, now: now)
                case .hold:
                    continue
                case .discard:
                    RuntimeLog.messaging.notice("send discarded: request not accepted")
                    try tx.outbox.remove(entry.id)
                }
                if entry.messageId != nil { touched.insert(entry.recipient) }
            }
        }
        for peer in touched { emit(.messages(peer)) }
        if case .sent = result, entries.contains(where: { $0.messageId != nil }) { emit(.conversations) }
    }

    // MARK: Uploads (§7, §4.9)

    /// Uploads the attachments of a waiting entry, puts the pointers into
    /// its content and marks it ready. Returns when to try again after a
    /// transient failure, `nil` when done or given up.
    private func upload(_ entry: OutboxEntry) async -> Date? {
        let now = clock.now()
        if let backoff = uploadBackoff[entry.id], backoff.notBefore > now { return backoff.notBefore }
        guard let messageId = entry.messageId, let conversationId = entry.conversationId,
            case .data(var data) = entry.content.body
        else {
            _ = try? write { try $0.outbox.markReady(entry.id, now: now) }
            return nil
        }
        do {
            let attachments = try read { try $0.attachments.forMessage(messageId, in: conversationId) }
            for attachment in attachments where attachment.state != .uploaded || attachment.descriptor?.mediaID.isEmpty != false {
                guard attachment.hasFile, let draft = attachment.descriptor else { throw AttachmentUploadError.missing }
                let staging = try read { try $0.attachments.exportToStaging(attachment.id) }
                defer { try? FileManager.default.removeItem(at: staging) }
                try write { try $0.attachments.setState(.uploading, of: attachment.id) }
                var uploaded = try await backend.uploadAttachment(
                    fileAt: staging, kind: draft.kind, contentType: draft.contentType,
                    fileName: draft.fileName.isEmpty ? nil : draft.fileName)
                // Unsent or deleted meanwhile: the blob must not stay on the relay.
                guard (try? read({ try $0.outbox.get(entry.id) })) != nil else {
                    try? await backend.deleteUploadedMedia(uploaded)
                    uploadBackoff[entry.id] = nil
                    return nil
                }
                // Keep what the composer set (dimensions, thumbnail, caption, …).
                uploaded.width = draft.width
                uploaded.height = draft.height
                uploaded.durationMs = draft.durationMs
                uploaded.thumbnail = draft.thumbnail
                uploaded.caption = draft.caption
                uploaded.waveform = draft.waveform
                if !draft.fileName.isEmpty { uploaded.fileName = draft.fileName }
                try write { try $0.attachments.setDescriptor(uploaded, of: attachment.id) }
                Self.replaceAttachment(in: &data, with: uploaded)
            }
            var content = entry.content
            content.body = .data(data)
            try write { try $0.outbox.markReady(entry.id, content: content, now: clock.now()) }
            uploadBackoff[entry.id] = nil
            return nil
        } catch is AttachmentUploadError {
            giveUpUpload(entry, code: "attachment_missing")
            return nil
        } catch MessengerError.attachmentTooLarge {
            giveUpUpload(entry, code: "attachment_too_large")
            return nil
        } catch {
            if let messageId = entry.messageId, let conversationId = entry.conversationId {
                _ = try? write { tx in
                    for attachment in try tx.attachments.forMessage(messageId, in: conversationId)
                    where attachment.state == .uploading {
                        try tx.attachments.setState(.pendingUpload, of: attachment.id, countsAsAttempt: true)
                    }
                }
            }
            let attempts = (uploadBackoff[entry.id]?.attempts ?? 0) + 1
            let notBefore = clock.now().addingTimeInterval(configuration.retryPolicy.delay(afterAttempts: attempts))
            uploadBackoff[entry.id] = (attempts, notBefore)
            return notBefore
        }
    }

    private func giveUpUpload(_ entry: OutboxEntry, code: String) {
        uploadBackoff[entry.id] = nil
        _ = try? write { try $0.outbox.markFailed(entry.id, error: code, retryable: false, now: clock.now()) }
        emit(.messages(entry.recipient))
    }

    static func replaceAttachment(in data: inout BRPDataMessage, with attachment: BRPAttachment) {
        switch data.payload {
        case .attachment: data.payload = .attachment(attachment)
        case .sticker(var sticker):
            sticker.custom = attachment
            data.payload = .sticker(sticker)
        default: break
        }
    }

    // MARK: Downloads (§4.9, §7)

    /// Downloads pending media of accepted contacts (requests keep theirs on
    /// the relay until accepted, §6.4). Us2usKit checks digest and HMAC and
    /// deletes the blob from the relay once the download is complete.
    func startDownloads(force: Bool = false) {
        guard running, settings.autoDownloadMedia || force else { return }
        guard downloadTask == nil else {
            downloadAgain = true
            return
        }
        downloadTask = Task { [weak self] in await self?.downloadLoop() }
    }

    private func downloadLoop() async {
        defer { downloadTask = nil }
        repeat {
            downloadAgain = false
            guard let candidates = try? read({ tx -> [(StoredAttachment, AccountId)] in
                try tx.attachments.inStates([.pendingDownload], limit: 50).compactMap { attachment in
                    guard settings.autoDownloadMedia || requestedDownloads.contains(attachment.id),
                        let conversation = try tx.conversations.get(attachment.conversationId),
                        try tx.contacts.requestState(of: conversation.contact) == .accepted
                    else { return nil }
                    return (attachment, conversation.contact)
                }
            }) else { return }
            for (attachment, peer) in candidates {
                guard running, !Task.isCancelled else { return }
                guard await download(attachment, peer: peer) else { return }  // network: wait for the next trigger
            }
            if candidates.count < 50 && !downloadAgain { return }
        } while running && !Task.isCancelled
    }

    /// Returns `false` on a transient failure (stop for now).
    private func download(_ attachment: StoredAttachment, peer: AccountId) async -> Bool {
        guard let descriptor = attachment.descriptor else {
            _ = try? write { try $0.attachments.setState(.unavailable, of: attachment.id) }
            return true
        }
        let staging = storage.makeStagingURL()
        defer { try? FileManager.default.removeItem(at: staging) }
        let messageId = attachment.messageId
        let progress = DownloadProgressThrottle { [weak self] fraction in
            Task { await self?.emit(.downloadProgress(messageId, peer, fraction)) }
        }
        do {
            try write { try $0.attachments.setState(.downloading, of: attachment.id) }
            // The bubble shows the transfer while it runs, not only its end.
            emit(.messages(peer))
            try await backend.downloadAttachment(descriptor, to: staging) { progress.update($0) }
            try write { try $0.attachments.importFile(at: staging, for: attachment.id, now: clock.now()) }
            requestedDownloads.remove(attachment.id)
            emit(.messages(peer))
            return true
        } catch let error as RelayError where error.isNotFound {
            _ = try? write { try $0.attachments.setState(.unavailable, of: attachment.id) }  // 14-day TTL or unsent
        } catch is AttachmentError {
            _ = try? write { try $0.attachments.setState(.failed, of: attachment.id) }  // integrity: never shown
        } catch {
            _ = try? write { try $0.attachments.setState(.pendingDownload, of: attachment.id, countsAsAttempt: true) }
            return false
        }
        emit(.messages(peer))
        return true
    }

    /// Downloads one attachment now (auto-download off, or a retry).
    public func download(_ messageId: MessageId, peer: AccountId) {
        _ = try? write { tx in
            guard let conversation = try tx.conversations.forContact(peer) else { return }
            for attachment in try tx.attachments.forMessage(messageId, in: conversation.id)
            where attachment.state == .failed || attachment.state == .pendingDownload {
                requestedDownloads.insert(attachment.id)
                if attachment.state == .failed { try tx.attachments.setState(.pendingDownload, of: attachment.id) }
            }
        }
        startDownloads(force: true)
    }
}

/// Passes a download's progress on at most every 2 % (and at the end), so
/// the conversation is not refreshed for every 64 KiB chunk.
final class DownloadProgressThrottle: Sendable {
    private let last = LockedState<Double>(-1)
    private let report: @Sendable (Double) -> Void

    init(report: @escaping @Sendable (Double) -> Void) {
        self.report = report
    }

    func update(_ fraction: Double) {
        let value = min(1, max(0, fraction))
        let due = last.withLock { last -> Bool in
            guard value - last >= 0.02 || (value >= 1 && last < 1) else { return false }
            last = value
            return true
        }
        if due { report(value) }
    }
}

/// The local media of an outgoing attachment is gone and cannot be uploaded.
enum AttachmentUploadError: Error {
    case missing
}
