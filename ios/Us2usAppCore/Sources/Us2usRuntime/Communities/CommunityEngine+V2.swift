import Foundation
import Us2usKit
import Us2usStorage

// Communities extension v2 (docs/COMMUNITIES.md): photos, videos and files
// uploaded once and shared by pointer, DELIVERY and READ receipts to the
// original sender only, and delete for everyone by the sender or the owner.

extension CommunityEngine {
    // MARK: Receiving

    /// A delete is valid from the target's original sender, or from the owner
    /// of the current signed roster (moderation). Every other kind is
    /// authorized by the v1 membership checks.
    nonisolated static func isAuthorized(
        _ content: BRPCommunityMessage, kind: CommunityContentKind, from sender: AccountId, in state: CommunityState
    ) -> Bool {
        guard kind == .delete else { return true }
        return content.deleteTargetSenderID == sender.bytes || sender == state.owner.accountId
    }

    /// Applies one validated, authorized message in the receive transaction.
    nonisolated static func apply(
        _ content: BRPCommunityMessage, kind: CommunityContentKind, from sender: AccountId, own: AccountId,
        state: CommunityState, tx: StorageTransaction
    ) throws {
        switch kind {
        case .text, .media, .sticker:
            try tx.communities.store(content, sender: sender, outgoing: false)
        case .receipt:
            try tx.communities.applyReceipt(
                content.receipt.type, ids: content.distinctReceiptIds, from: sender, own: own, community: state.id)
        case .delete:
            guard let targetSender = AccountId(bytes: content.deleteTargetSenderID),
                  let target = AccountId(bytes: content.deleteTargetMessageID) else { return }
            try tx.communities.applyDelete(
                community: state.id, targetSender: targetSender, targetId: target,
                by: targetSender == sender ? .bySender : .byOwner, own: own)
        }
    }

    // MARK: Receipts owed

    /// Seals the receipts owed (DELIVERY after every sync pass, READ once
    /// displayed) — one per community and original sender, at most 1000 ids
    /// each — and queues them for that sender alone under the current
    /// revision, with their content (sealed again after a roster change). A
    /// sender who is no longer a member gets nothing; a READ owed while read
    /// receipts are off is dropped.
    func queueReceipts(_ type: BRPCommunityReceipt.TypeEnum, using pass: inout CommunityPass) async {
        guard isActive, let due = try? storage.read({ try $0.communities.dueReceipts(type) }), !due.isEmpty else { return }
        let allowed = type == .delivery || readReceipts.withLock { $0 }
        for group in due {
            guard isActive else { return }
            func drop(_ ids: [AccountId]) {
                try? storage.write { try $0.communities.clearDue(type, community: group.community, sender: group.sender, messageIds: ids) }
            }
            guard allowed else { drop(group.messageIds); continue }
            let state: CommunityState
            switch await lookup(group.community, in: &pass) {
            case .gone: drop(group.messageIds); continue
            case .unavailable: continue
            case .current(let current): state = current
            }
            guard state.member(group.sender) != nil else { drop(group.messageIds); continue }
            for start in stride(from: 0, to: group.messageIds.count, by: CommunityContentLimits.maxReceiptIds) {
                let chunk = Array(group.messageIds[start..<min(start + CommunityContentLimits.maxReceiptIds, group.messageIds.count)])
                let receipt = BRPCommunityMessage.receipt(type, for: chunk.map(\.bytes), community: state.id, revision: state.revision)
                do {
                    let fanout = try await seal(receipt, for: state, only: [group.sender])
                    try check()
                    try storage.write { tx in
                        if let event = AccountId(bytes: receipt.messageID) {
                            for item in fanout.prepared {
                                try tx.communities.enqueue(
                                    community: state.id, message: event, recipient: item.recipient, revision: state.revision,
                                    envelope: item.envelope, event: receipt)
                            }
                        }
                        try tx.communities.clearDue(type, community: group.community, sender: group.sender, messageIds: chunk)
                    }
                } catch let failure as CommunityError {
                    // The sender's key does not match the roster: no receipt.
                    pass.note(CommunityIssue(failure))
                    drop(chunk)
                } catch let failure as RelayError where failure.isNotFound {
                    drop(chunk)
                } catch {
                    // Offline: still owed, the next pass tries again.
                    pass.note(CommunityIssue(error))
                    return
                }
            }
        }
    }

    /// The READs owed now leave (after the coalescing delay).
    func sendReads() async -> CommunityIssue? {
        var pass = CommunityPass()
        await queueReceipts(.read, using: &pass)
        await flush(using: &pass)
        return pass.issue
    }

    func markDisplayed(_ id: AccountId, readReceipts: Bool) throws -> Int {
        try check()
        return try storage.write { try $0.communities.markDisplayed(community: id, readReceipts: readReceipts) }
    }

    // MARK: Media

    /// Stores an own photo, video or file at once — the plaintext file
    /// encrypted at rest, the caption as its text — so its bubble shows;
    /// ``deliverMedia(_:in:using:)`` uploads it once and fans the pointer out.
    func storeMedia(_ prepared: PreparedMedia, caption: String, messageId: Data?, to id: AccountId) throws -> AccountId {
        try check()
        let kind: BRPAttachment.Kind
        switch prepared.kind {
        case .image: kind = .image
        case .video: kind = .video
        case .file: kind = .file
        case .voice: kind = .voice
        case .stickerImage: throw CommunityError.unsupported
        }
        if prepared.kind == .voice {
            guard prepared.contentType == "audio/mp4", (1...16 * 1024 * 1024).contains(prepared.size),
                  (1000...300000).contains(prepared.durationMs), (prepared.waveform?.count ?? 0) <= 100,
                  prepared.width == 0, prepared.height == 0, (prepared.thumbnail?.isEmpty ?? true),
                  (prepared.fileName?.isEmpty ?? true), CommunityCaption.normalized(caption).isEmpty
            else { throw CommunityError.invalidState }
        }
        let text = CommunityCaption.normalized(caption)
        guard text.utf8.count <= CommunityContentLimits.maxTextBytes,
              (prepared.thumbnail?.count ?? 0) <= CommunityContentLimits.maxThumbnailBytes,
              prepared.contentType.utf8.count <= CommunityContentLimits.maxNameBytes
        else { throw CommunityError.invalidState }
        guard let group = try storage.read({ try $0.communities.get(id) }), group.isActive, let state = group.state,
              state.member(backend.accountId) != nil
        else { throw CommunityIssue.notFound }
        guard let message = AccountId(bytes: messageId ?? RandomId.make()) else { throw CommunityError.invalidState }
        var draft = prepared.pendingAttachment
        draft.kind = kind
        if let name = prepared.fileName { draft.fileName = Self.displayName(name) }
        let content = BRPCommunityMessage.media(draft, caption: text, community: id, revision: state.revision, messageId: message.bytes)
        let own = backend.accountId
        try storage.write { tx in
            guard try tx.communities.store(content, sender: own, outgoing: true) == .stored else { throw CommunityError.invalidState }
            try tx.communities.importAttachmentFile(at: prepared.file, community: id, sender: own, messageId: message)
        }
        return message
    }

    /// A file name as a display name of at most 255 UTF-8 bytes (never a path).
    nonisolated static func displayName(_ name: String) -> String {
        var result = (name as NSString).lastPathComponent
        while result.utf8.count > CommunityContentLimits.maxNameBytes { result.removeFirst() }
        return result
    }

    /// Own media messages whose upload or fanout did not finish (a crash, a
    /// network failure) go on.
    func resumeMedia(using pass: inout CommunityPass) async {
        guard isActive, let unsent = try? storage.read({ try $0.communities.unsentMedia(sender: backend.accountId) }) else { return }
        for item in unsent {
            guard isActive else { return }
            await deliverMedia(item.messageId, in: item.community, using: &pass)
        }
    }

    func deliverMedia(_ messageId: AccountId, in id: AccountId) async -> CommunityIssue? {
        var pass = CommunityPass()
        await deliverMedia(messageId, in: id, using: &pass)
        await flush(using: &pass)
        return pass.issue
    }

    /// Uploads the blob once (unless it is uploaded), then seals the same
    /// pointer separately for every current member and queues it. A failed
    /// upload fails the message; a fanout repeated later reuses the blob.
    func deliverMedia(_ messageId: AccountId, in id: AccountId, using pass: inout CommunityPass) async {
        let key = id.string + "/" + messageId.string
        guard isActive, !deliveringMedia.contains(key) else { return }
        deliveringMedia.insert(key)
        defer { deliveringMedia.remove(key) }
        let own = backend.accountId
        func current() -> CommunityTextItem? {
            guard let item = try? storage.read({ try $0.communities.message(id, sender: own, messageId: messageId) }),
                  item.deletion == nil, item.status == .sending else { return nil }
            return item
        }
        /// The message fails; a blob already uploaded stays so (“Tekrar gönder” reuses it).
        func fail(uploaded: Bool = false) {
            try? storage.write { tx in
                if !uploaded { try tx.communities.setAttachmentState(.failed, community: id, sender: own, messageId: messageId) }
                try tx.communities.setStatus(.failed, community: id, message: messageId)
            }
        }
        guard let item = current(), let attachment = item.attachment else { return }
        if attachment.state != .uploaded {
            do {
                let staging = try storage.read { try $0.communities.exportAttachmentToStaging(community: id, sender: own, messageId: messageId) }
                defer { try? FileManager.default.removeItem(at: staging) }
                try storage.write { try $0.communities.setAttachmentState(.uploading, community: id, sender: own, messageId: messageId) }
                var uploaded = try await backend.messenger.uploadAttachment(
                    fileAt: staging, kind: attachment.kind, contentType: attachment.contentType, fileName: attachment.fileName)
                let draft = attachment.descriptor
                uploaded.width = draft.width
                uploaded.height = draft.height
                uploaded.durationMs = draft.durationMs
                uploaded.waveform = draft.waveform
                uploaded.thumbnail = draft.thumbnail
                if !draft.fileName.isEmpty { uploaded.fileName = draft.fileName }
                // Deleted (or the community left) meanwhile: the blob must not stay.
                guard isActive, current() != nil else {
                    try? await relay.deleteMedia(id: uploaded.mediaID, deleteToken: uploaded.deleteToken)
                    return
                }
                try storage.write { try $0.communities.setUploaded(uploaded, community: id, sender: own, messageId: messageId) }
            } catch MessengerError.attachmentTooLarge {
                fail()
                return
            } catch let failure as RelayError where Self.isFinal(failure) {
                fail()
                return
            } catch StorageError.notFound {
                fail()
                return
            } catch {
                try? storage.write { try $0.communities.setAttachmentState(.pendingUpload, community: id, sender: own, messageId: messageId) }
                pass.note(CommunityIssue(error))
                return
            }
        }
        guard let ready = current(), let pointer = ready.attachment?.descriptor, pointer.mediaID.count == RandomId.byteCount else { return }
        let state: CommunityState
        switch await lookup(id, in: &pass) {
        case .gone:
            fail(uploaded: true)
            return
        case .unavailable:
            return
        case .current(let current):
            state = current
        }
        let content = BRPCommunityMessage.media(
            pointer, caption: ready.text, community: id, revision: state.revision, messageId: messageId.bytes,
            sentAt: Date(timeIntervalSince1970: TimeInterval(ready.sentAt) / 1000))
        do {
            let fanout = try await seal(content, for: state)
            try check()
            try storage.write { tx in
                // Deleted while it was sealed: nothing goes out.
                guard try tx.communities.message(id, sender: own, messageId: messageId)?.deletion == nil else { return }
                try commit(fanout, of: messageId, in: id, revision: state.revision, tx: tx)
            }
            if let member = fanout.mismatched.first { pass.note(.memberKey(member)) }
        } catch CommunityError.memberKeyMismatch(let member) {
            fail(uploaded: true)
            pass.note(.memberKey(member))
        } catch let failure as RelayError where failure.isNotFound {
            fail(uploaded: true)
        } catch {
            pass.note(CommunityIssue(error))
        }
    }

    // MARK: Sending again

    /// What “Tekrar gönder” did.
    enum Resend: Sendable, Equatable {
        /// Not a failed own message (any more): nothing to do.
        case nothing
        /// Sealed again and queued; members whose key does not match are named.
        case queued(unreachable: [AccountId])
        /// Its blob never reached the relay: it is uploaded first (``deliverMedia(_:in:using:)``).
        case upload
    }

    /// “Tekrar gönder” for an own text or media message that failed (a `409`,
    /// a refused member, a failed upload or fanout). A blob that never
    /// reached the relay is uploaded first; otherwise the message is sealed
    /// again under the current roster only for the members that have not got
    /// it — those of its recipient set (everyone, if it never had one) the
    /// relay did not accept it for; members who left since are skipped. An
    /// uploaded blob is reused, never uploaded again.
    func resend(_ messageId: AccountId, in id: AccountId) async throws -> Resend {
        try check()
        let own = backend.accountId
        func failed() throws -> CommunityTextItem? {
            guard let item = try storage.read({ try $0.communities.message(id, sender: own, messageId: messageId) }),
                  item.deletion == nil, item.status == .failed else { return nil }
            return item
        }
        guard let item = try failed() else { return .nothing }
        if let attachment = item.attachment, attachment.descriptor.mediaID.count != RandomId.byteCount {
            try storage.write { tx in
                try tx.communities.setAttachmentState(.pendingUpload, community: id, sender: own, messageId: messageId)
                try tx.communities.setStatus(.sending, community: id, message: messageId)
            }
            return .upload
        }
        let state = try await current(id)
        let rows = try storage.read { try $0.communities.recipients(community: id, messageId: messageId) }
        let done = Set(rows.filter { $0.state == .accepted || $0.state == .skipped }.map(\.recipient))
        let fixed = rows.isEmpty ? nil : Set(rows.map(\.recipient))
        let targets = state.members.map(\.accountId).filter { $0 != own && (fixed?.contains($0) ?? true) && !done.contains($0) }
        let sentAt = Date(timeIntervalSince1970: TimeInterval(item.sentAt) / 1000)
        let content: BRPCommunityMessage
        if item.kind == .media, let pointer = item.attachment?.descriptor {
            content = .media(pointer, caption: item.text, community: id, revision: state.revision, messageId: messageId.bytes, sentAt: sentAt)
        } else if let sticker = item.sticker {
            content = .sticker(sticker, community: id, revision: state.revision, messageId: messageId.bytes, sentAt: sentAt)
        } else {
            content = .text(item.text, community: id, revision: state.revision, messageId: messageId.bytes, sentAt: sentAt)
        }
        var fanout = Fanout()
        if !targets.isEmpty { fanout = try await seal(content, for: state, only: targets, allowingNone: true) }
        // Members of its recipient set who are no longer in the community.
        fanout.gone += (fixed ?? []).subtracting(done).subtracting(targets)
        try check()
        let sealed = fanout
        let committed = try storage.write { tx -> Bool in
            // Deleted, or sent again, meanwhile: nothing goes out.
            guard try tx.communities.message(id, sender: own, messageId: messageId).map({ $0.deletion == nil && $0.status == .failed }) == true
            else { return false }
            try tx.communities.unqueue(community: id, message: messageId)
            try tx.communities.setStatus(.sending, community: id, message: messageId)
            try commit(sealed, of: messageId, in: id, revision: state.revision, tx: tx)
            return true
        }
        return committed ? .queued(unreachable: fanout.mismatched) : .nothing
    }

    /// Downloads a received attachment (never deleting the blob: it is
    /// shared), checking digest and HMAC and stopping at `blobSize(size)`.
    func download(
        sender: AccountId, messageId: AccountId, in id: AccountId, progress: @escaping @Sendable (Double) -> Void
    ) async throws {
        try check()
        guard let item = try storage.read({ try $0.communities.attachment(id, sender: sender, messageId: messageId) }),
              !item.hasFile, [.remote, .failed, .unavailable, .downloading].contains(item.state)
        else { return }
        let staging = storage.makeStagingURL()
        defer { try? FileManager.default.removeItem(at: staging) }
        func set(_ state: CommunityAttachmentState) {
            try? storage.write { try $0.communities.setAttachmentState(state, community: id, sender: sender, messageId: messageId) }
        }
        set(.downloading)
        do {
            try await backend.messenger.downloadAttachment(item.descriptor, to: staging, deleteFromRelay: false, progress: progress)
            try check()
            // A message deleted meanwhile keeps nothing.
            _ = try storage.write { try $0.communities.importAttachmentFile(at: staging, community: id, sender: sender, messageId: messageId) }
        } catch let failure as RelayError where failure.isNotFound {
            set(.unavailable)
        } catch is AttachmentError {
            set(.failed)
        } catch {
            set(.remote)
            throw error
        }
    }

    func mediaData(sender: AccountId, messageId: AccountId, in id: AccountId) throws -> Data {
        try check()
        return try storage.read { try $0.communities.readAttachmentFile(community: id, sender: sender, messageId: messageId) }
    }

    // MARK: Deletes

    /// Delete for everyone: by the original sender, or by the owner of the
    /// current roster on anyone's message. The delete is sealed for every
    /// current member but this account first; then, in one transaction,
    /// this phone's copy becomes the placeholder (an own blob is queued for
    /// deletion) and the delete is queued with its content (sealed again for
    /// the members still there after a roster change). Nothing changes when
    /// the roster cannot be read (offline).
    func deleteForEveryone(sender targetSender: AccountId, messageId target: AccountId, in id: AccountId) async throws {
        try check()
        let own = backend.accountId
        guard let item = try storage.read({ try $0.communities.message(id, sender: targetSender, messageId: target) }),
              item.deletion == nil else { return }
        let state = try await current(id)
        let role: CommunityDeletion = targetSender == own ? .bySender : .byOwner
        guard role == .bySender || state.owner.accountId == own else { throw CommunityError.invalidState }
        let event = BRPCommunityMessage.delete(target: target.bytes, sender: targetSender, community: id, revision: state.revision)
        var fanout = Fanout()
        do {
            fanout = try await seal(event, for: state)
        } catch is CommunityError {
            // Nobody's key matches the roster: the delete stays local.
        } catch let failure as RelayError where failure.isNotFound {
            // Nobody else is left.
        }
        try check()
        guard let eventId = AccountId(bytes: event.messageID) else { return }
        try storage.write { tx in
            try tx.communities.applyDelete(community: id, targetSender: targetSender, targetId: target, by: role, own: own)
            for prepared in fanout.prepared {
                try tx.communities.enqueue(
                    community: id, message: eventId, recipient: prepared.recipient, revision: state.revision, envelope: prepared.envelope,
                    event: event)
            }
        }
    }

    /// "Delete for me": local only.
    func deleteForMe(sender: AccountId, messageId: AccountId, in id: AccountId) throws {
        try check()
        try storage.write { try $0.communities.deleteForMe(community: id, sender: sender, messageId: messageId) }
    }

    /// Deletes the blobs this phone uploaded whose messages were deleted for
    /// everyone: 204, 404 (or any other refusal) is final; a network
    /// failure is tried again later, then the token is forgotten.
    func deleteBlobs() async {
        guard isActive, let items = try? storage.read({ try $0.communities.pendingMediaDeletes() }) else { return }
        for item in items {
            guard isActive else { return }
            do {
                try await relay.deleteMedia(id: item.mediaId, deleteToken: item.deleteToken)
                try? storage.write { try $0.communities.finishMediaDelete(item.mediaId) }
            } catch let failure as RelayError where Self.isFinal(failure) {
                try? storage.write { try $0.communities.finishMediaDelete(item.mediaId) }
            } catch {
                try? storage.write { try $0.communities.retryMediaDelete(item.mediaId) }
                return
            }
        }
    }

    /// A relay answer that will not change on retry: a 4xx other than 408
    /// and 429, or a body the relay refuses as too large.
    nonisolated static func isFinal(_ failure: RelayError) -> Bool {
        switch failure {
        case .payloadTooLarge: return true
        case .server(let status, _, _): return (400..<500).contains(status) && status != 408 && status != 429
        default: return false
        }
    }

    // MARK: Reading

    func recipients(of messageId: AccountId, in id: AccountId) throws -> [CommunityRecipientItem] {
        try check()
        return try storage.read { try $0.communities.recipients(community: id, messageId: messageId) }
    }

    func summaries() throws -> [CommunityChatSummary] {
        try check()
        return try storage.read { try $0.communities.summaries() }
    }
}

extension CommunityService {
    // MARK: Media

    /// Stores an own photo, video or file (`prepared` by `AppRuntime`, its
    /// caption as the message's text) and returns once its bubble can show;
    /// the single upload and the fanout to every member continue in the
    /// background. `messageId`: the id its bubble already has.
    public func sendMedia(_ prepared: PreparedMedia, caption: String? = nil, messageId: Data? = nil, to id: AccountId) async throws {
        let message = try await perform {
            try await engine.storeMedia(prepared, caption: caption ?? prepared.caption ?? "", messageId: messageId, to: id)
        }
        if selected == id { try? await reload(id) }
        await reloadLocal()
        deliverMediaInBackground(message, in: id)
    }

    /// The single upload and the fanout to every member, after the bubble shows.
    private func deliverMediaInBackground(_ message: AccountId, in id: AccountId) {
        Task { [weak self] in
            guard let self, self.active else { return }
            if let problem = await self.engine.deliverMedia(message, in: id) { self.issue = problem }
            if self.selected == id { try? await self.reload(id) }
            await self.reloadLocal()
        }
    }

    /// “Tekrar gönder” on an own text or media message that failed: it goes
    /// again, under the current roster, only to the members that did not get
    /// it (an uploaded blob is reused; one that never reached the relay is
    /// uploaded first). Returns once it is queued; delivery continues in the
    /// background.
    public func resend(_ item: CommunityTextItem) async throws {
        guard item.outgoing, item.deletion == nil, item.status == .failed else { return }
        let id = item.communityId
        let outcome = try await perform { try await engine.resend(item.messageId, in: id) }
        if selected == id { try? await reload(id) }
        await reloadLocal()
        switch outcome {
        case .nothing:
            break
        case .queued(let unreachable):
            if let member = unreachable.first { issue = .memberKey(member) }
            Task { [weak self] in await self?.deliverQueued(id) }
        case .upload:
            deliverMediaInBackground(item.messageId, in: id)
        }
    }

    /// Downloads a received photo, video or file (shown with its progress).
    public func download(_ item: CommunityTextItem) {
        let key = item.id
        guard active, downloads[key] == nil, item.deletion == nil, let attachment = item.attachment, !attachment.hasFile else { return }
        downloadProgress[key] = 0
        let throttle = DownloadProgressThrottle { [weak self] fraction in
            Task { @MainActor in
                guard let self, self.downloads[key] != nil else { return }
                self.downloadProgress[key] = fraction
            }
        }
        let engine = engine
        let task = Task { [weak self] in
            try? await engine.download(sender: item.sender, messageId: item.messageId, in: item.communityId) { throttle.update($0) }
            guard let self else { return }
            self.downloads[key] = nil
            self.downloadProgress[key] = nil
            if self.selected == item.communityId { try? await self.reload(item.communityId) }
        }
        downloads[key] = (item.communityId, task)
    }

    /// ``download(_:)``, returning once the download ended (or stopped).
    public func downloaded(_ item: CommunityTextItem) async {
        download(item)
        await downloads[item.id]?.task.value
    }

    /// The user's read-receipt setting, as the receipts follow it.
    public var readReceiptsOn: Bool { readReceiptsEnabled() }

    /// The decrypted file of a photo, video or file message, in memory.
    public func mediaData(_ item: CommunityTextItem) async throws -> Data {
        try await perform { try await engine.mediaData(sender: item.sender, messageId: item.messageId, in: item.communityId) }
    }

    /// A download whose message was deleted (or is gone) stops.
    func settleDownloads(of items: [CommunityTextItem], in community: AccountId) {
        guard !downloads.isEmpty else { return }
        let live = Set(items.filter { $0.deletion == nil }.map(\.id))
        for (key, download) in downloads where download.community == community && !live.contains(key) {
            download.task.cancel()
            downloads[key] = nil
            downloadProgress[key] = nil
        }
    }

    // MARK: Read receipts

    /// The open conversation shows its messages: received ones are read now.
    /// With read receipts on, their senders get READ (once, a few seconds
    /// later, together); with them off, nothing — then or later.
    public func markDisplayed(_ id: AccountId) {
        guard active, selected == id, isConversationVisible else { return }
        let allowed = readReceiptsEnabled()
        engine.readReceipts.withLock { $0 = allowed }
        let engine = engine
        Task { [weak self] in
            guard let self, self.active, self.selected == id, self.isConversationVisible else { return }
            let fresh = (try? await engine.markDisplayed(id, readReceipts: allowed)) ?? 0
            guard self.active, fresh > 0 else { return }
            await self.reloadLocal()
            if allowed { self.scheduleReads() }
        }
    }

    private func scheduleReads() {
        guard readTask == nil else { return }
        let delay = readCoalescing
        readTask = Task { [weak self] in
            try? await Task.sleep(for: delay)
            guard let self, self.active, !Task.isCancelled else { return }
            self.readTask = nil
            self.engine.readReceipts.withLock { $0 = self.readReceiptsEnabled() }
            if let problem = await self.engine.sendReads(), problem != .offline { self.issue = problem }
        }
    }

    /// Every recipient of an own message with its delivered and read times (message info).
    public func recipients(of item: CommunityTextItem) async throws -> [CommunityRecipientItem] {
        try await perform { try await engine.recipients(of: item.messageId, in: item.communityId) }
    }

    // MARK: Deletes

    /// Delete for everyone (own messages; the owner on every message). The
    /// placeholder replaces this phone's copy at once; the delete goes to
    /// every current member and an own blob is deleted on the relay.
    public func deleteForEveryone(_ item: CommunityTextItem) async throws {
        try await perform { try await engine.deleteForEveryone(sender: item.sender, messageId: item.messageId, in: item.communityId) }
        downloads.removeValue(forKey: item.id)?.task.cancel()
        downloadProgress[item.id] = nil
        if selected == item.communityId { try? await reload(item.communityId) }
        await reloadLocal()
        Task { [weak self] in await self?.deliverQueued(item.communityId) }
    }

    /// Delete for me: this phone only; nothing is sent.
    public func deleteForMe(_ item: CommunityTextItem) async throws {
        try await perform { try await engine.deleteForMe(sender: item.sender, messageId: item.messageId, in: item.communityId) }
        downloads.removeValue(forKey: item.id)?.task.cancel()
        downloadProgress[item.id] = nil
        if selected == item.communityId { try? await reload(item.communityId) }
        await reloadLocal()
    }
}
