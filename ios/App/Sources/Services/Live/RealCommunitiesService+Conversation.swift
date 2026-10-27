import Foundation
import Us2usKit
import Us2usRuntime
import Us2usStorage

/// How the communities bridge prepares picked media: the runtime's steps
/// (images re-encoded without metadata at ≤ 2048 px, videos re-exported at
/// ≤ 720p, documents as they are — photos rewritten from their pixels).
struct CommunityMediaPreparation {
    var image: @MainActor (URL, String?) async throws -> PreparedMedia
    var video: @MainActor (URL) async throws -> PreparedMedia
    var file: @MainActor (URL, String, String) async throws -> PreparedMedia
    var voice: @MainActor (VoiceRecording) async throws -> PreparedMedia = { _ in throw Unavailable() }
    var noteSticker: @MainActor (String) async -> Void = { _ in }

    struct Unavailable: Error {}

    /// Nothing can be prepared (previews, tests that send none).
    static let unavailable = CommunityMediaPreparation(
        image: { _, _ in throw Unavailable() }, video: { _ in throw Unavailable() }, file: { _, _, _ in throw Unavailable() })

    static func live(_ runtime: AppRuntime) -> CommunityMediaPreparation {
        CommunityMediaPreparation(
            image: { url, caption in try await runtime.prepareImage(at: url, caption: caption) },
            video: { url in try await runtime.prepareVideo(at: url) },
            file: { url, name, type in try await runtime.prepareFile(at: url, fileName: name, contentType: type) },
            voice: { recording in
                try await runtime.prepareVoiceNote(recording.audio, durationMs: recording.durationMilliseconds, waveform: recording.waveform)
            },
            noteSticker: { id in try? await runtime.noteRecentSticker(id) })
    }
}

/// The ids of community messages in the conversation model: an own message
/// by its message id (as the bubbles of picked media already have it), a
/// received one by sender and message id — two members can never take each
/// other's place.
enum CommunityMessageKey {
    static func id(_ item: CommunityTextItem, own: AccountId) -> MessageID {
        let message = LiveMapping.messageID(item.messageId.bytes)
        return item.sender == own ? message : LiveMapping.messageID(item.sender.bytes) + ":" + message
    }

    /// (sender, message id) of a conversation id.
    static func parse(_ id: MessageID, own: AccountId) -> (sender: AccountId, message: AccountId)? {
        let parts = id.split(separator: ":").map(String.init)
        switch parts.count {
        case 1:
            guard let message = LiveMapping.messageData(parts[0]).flatMap(AccountId.init(bytes:)) else { return nil }
            return (own, message)
        case 2:
            guard let sender = LiveMapping.messageData(parts[0]).flatMap(AccountId.init(bytes:)),
                  let message = LiveMapping.messageData(parts[1]).flatMap(AccountId.init(bytes:)) else { return nil }
            return (sender, message)
        default:
            return nil
        }
    }
}

extension RealCommunitiesService {
    /// The open conversation, mapped once per change.
    struct ConversationCache {
        let id: AccountId
        let items: [CommunityTextItem]
        let names: [AccountId: String]
        let media: Int
        let downloads: [String: Double]
        let outgoing: [OutgoingMediaItem]
        let readReceipts: Bool
        let value: [Message]
    }

    static func mediaSteps(service: CommunityService, preparation: CommunityMediaPreparation) -> OutgoingMedia.Steps {
        OutgoingMedia.Steps(
            preview: { url, kind in
                if kind == .photo { return await PhotoPreviewReader.read(url) }
                return await VideoPreviewReader.read(url)
            },
            prepare: { url, kind, caption in
                if kind == .photo { return try await preparation.image(url, caption) }
                return try await preparation.video(url)
            },
            send: { prepared, id, chat in
                guard let community = AccountId(string: chat) else { throw OutgoingMedia.Failure.unknownChat }
                try await service.sendMedia(prepared, messageId: id, to: community)
            },
            discardSource: { url in SystemPickers.discardPicked(url) },
            discardPrepared: { url in _ = try? FileManager.default.removeItem(at: url) })
    }

    // MARK: Lifecycle

    /// The app left the foreground: decrypted photos leave memory (the lock
    /// follows); a pick that failed is given up.
    func didEnterBackground() {
        media = [:]
        mediaLoading = []
        conversationCache = nil
        outgoingMedia.didEnterBackground()
    }

    func didBecomeActive() {
        outgoingMedia.didBecomeActive()
    }

    /// “Her şeyi sil”, or a restore replaced the vault: everything stops and
    /// every picked copy goes.
    func removeAll() {
        outgoingMedia.removeAll()
        media = [:]
        mediaLoading = []
        conversationCache = nil
    }

    /// “Tekrar gönder”: a picked photo or video that could not be prepared
    /// is prepared again; a stored own message that failed goes to the
    /// members that did not get it.
    func resend(_ message: MessageID, in id: CommunityID) async throws(CommunityProblem) {
        if outgoingMedia.resend(message) { return }
        guard let current = item(message, in: id), current.outgoing else { return }
        try await run { try await service.resend(current) }
    }

    // MARK: Chats list

    var chatRows: [CommunityChatRow] {
        let own = service.accountId
        let names = service.usernames
        let readReceipts = service.readReceiptsOn
        return service.chats.compactMap { summary -> CommunityChatRow? in
            // Pending requests and unconfirmed creations live in Topluluklar only.
            guard let state = summary.community.state else { return nil }
            return CommunityChatRow(
                id: summary.id.string, name: summary.community.name, isPublic: state.isPublic,
                lastMessage: summary.last.map { Self.preview($0, own: own, names: names, readReceipts: readReceipts) },
                lastActivity: summary.lastActivity.map { Date(timeIntervalSince1970: TimeInterval($0) / 1000) },
                unreadCount: summary.unread)
        }
    }

    static func preview(
        _ item: CommunityTextItem, own: AccountId, names: [AccountId: String], readReceipts: Bool = false
    ) -> MessagePreview {
        let kind: MessagePreviewKind
        if let deletion = item.deletion {
            kind = .communityDeleted(byOwner: deletion == .byOwner)
        } else if item.kind == .media, let attachment = item.attachment {
            switch attachment.kind {
            case .image: kind = .photo
            case .video: kind = .video
            case .voice: kind = .voice
            default: kind = .file(attachment.fileName ?? "")
            }
        } else if item.sticker != nil {
            kind = .sticker
        } else {
            kind = .text(item.text)
        }
        return MessagePreview(
            kind: kind, direction: item.outgoing ? .outgoing : .incoming,
            status: item.outgoing ? status(of: item, readReceipts: readReceipts) : nil,
            sentAt: Date(timeIntervalSince1970: TimeInterval(item.sentAt) / 1000),
            sender: item.outgoing ? nil : CommunitySenderName(username: names[item.sender], shortID: shortID(item.sender)))
    }

    // MARK: Conversation

    func isOwner(of id: CommunityID) -> Bool {
        service.joined.first { $0.id.string == id }?.state?.owner.accountId == service.accountId
    }

    func conversation(_ id: CommunityID) -> [Message] {
        guard let account = AccountId(string: id), service.selected == account else { return [] }
        let items = service.messages
        let names = service.usernames
        let downloads = service.downloadProgress
        let outgoing = outgoingMedia.items(in: id)
        let readReceipts = service.readReceiptsOn
        if let cache = conversationCache, cache.id == account, cache.items == items, cache.names == names,
            cache.media == media.count, cache.downloads == downloads, cache.outgoing == outgoing,
            cache.readReceipts == readReceipts
        {
            return cache.value
        }
        let own = service.accountId
        let stored = items.map {
            Self.message($0, own: own, names: names, media: media, downloads: downloads, readReceipts: readReceipts)
        }
        let value = OutgoingMedia.merge(outgoing, into: stored, landed: outgoingMedia.landed)
        conversationCache = ConversationCache(
            id: account, items: items, names: names, media: media.count, downloads: downloads, outgoing: outgoing,
            readReceipts: readReceipts, value: value)
        loadMedia(of: items, in: account)
        outgoingMedia.settle(shown: stored)
        return value
    }

    /// One stored community message as the conversation draws it.
    static func message(
        _ item: CommunityTextItem, own: AccountId, names: [AccountId: String], media: [MessageID: Data],
        downloads: [String: Double], readReceipts: Bool
    ) -> Message {
        let id = CommunityMessageKey.id(item, own: own)
        let content: MessageContent
        var availability = MediaAvailability.ready
        var size: MediaDimensions?
        var caption: String?
        if item.deletion != nil {
            content = .deleted
        } else if item.kind == .media, let attachment = item.attachment {
            let source = MediaSource.memory(media[id] ?? attachment.thumbnail ?? Data())
            switch attachment.kind {
            case .image:
                content = .photo(source, caption: item.text.isEmpty ? nil : item.text)
            case .voice:
                content = .voice(duration: TimeInterval(attachment.descriptor.durationMs) / 1000,
                                 waveform: LiveMapping.waveform(attachment.descriptor.waveform), playedFraction: 0)
            case .video:
                content = .video(
                    source, duration: TimeInterval(attachment.descriptor.durationMs) / 1000, byteCount: Int64(attachment.size))
            default:
                let name = attachment.fileName ?? ""
                content = .file(
                    name: name, byteCount: Int64(attachment.size), typeLabel: LiveMapping.typeLabel(fileName: name), pageCount: nil)
            }
            availability = Self.availability(attachment, outgoing: item.outgoing, download: downloads[item.id])
            // A photo carries its caption; a video's or a file's is a row under it.
            if attachment.kind != .image, !item.text.isEmpty { caption = item.text }
            if attachment.descriptor.width > 0, attachment.descriptor.height > 0 {
                size = MediaDimensions(width: Int(attachment.descriptor.width), height: Int(attachment.descriptor.height))
            }
        } else if let sticker = item.sticker {
            content = .sticker(packID: sticker.packID, stickerID: sticker.stickerID, emoji: sticker.emoji, lang: sticker.lang)
        } else {
            content = .text(item.text)
        }
        let sentAt = Date(timeIntervalSince1970: TimeInterval(item.sentAt) / 1000)
        var message = Message(
            id: id, direction: item.outgoing ? .outgoing : .incoming, sentAt: sentAt, content: content,
            status: item.outgoing ? status(of: item, readReceipts: readReceipts) : nil)
        message.mediaAvailability = availability
        message.mediaSize = size
        if message.status == .failed { message.failedAt = sentAt }
        message.community = CommunityMessageMarks(
            senderID: item.sender.string,
            sender: item.outgoing ? nil : CommunitySenderName(username: names[item.sender], shortID: shortID(item.sender)),
            removedByOwner: item.deletion == .byOwner, caption: caption)
        return message
    }

    /// An own message's aggregate state: Sent (accepted for all its
    /// recipients) → Delivered (all delivered) → Read (all read, only while
    /// read receipts are on: they are mutual).
    static func status(of item: CommunityTextItem, readReceipts: Bool) -> MessageStatus {
        if let state = item.attachment?.state, state == .pendingUpload || state == .uploading { return .sending }
        switch item.status {
        case .sending: return .sending
        case .failed: return .failed
        case .received, .sent, .partial:
            guard let receipts = item.receipts else { return .sent }
            if readReceipts, receipts.readByAll { return .read }
            return receipts.deliveredToAll ? .delivered : .sent
        }
    }

    /// Where a community attachment's file is, as its bubble shows it.
    static func availability(_ attachment: CommunityAttachmentItem, outgoing: Bool, download: Double?) -> MediaAvailability {
        if attachment.hasFile { return .ready }
        if let download { return .downloading(download) }
        switch attachment.state {
        case .downloading: return .downloading(nil)
        case .remote: return .remote
        case .unavailable, .failed: return outgoing ? .ready : .unavailable
        case .pendingUpload, .uploading, .uploaded, .downloaded: return outgoing ? .ready : .remote
        }
    }

    /// Photos of the open conversation: decrypted into memory when they are
    /// here, downloaded first when they are still on the relay (videos and
    /// files wait for a tap).
    private func loadMedia(of items: [CommunityTextItem], in community: AccountId) {
        let own = service.accountId
        for item in items where item.deletion == nil {
            guard let attachment = item.attachment, attachment.kind == .image else { continue }
            let id = CommunityMessageKey.id(item, own: own)
            guard media[id] == nil, !mediaLoading.contains(id) else { continue }
            if attachment.hasFile {
                mediaLoading.insert(id)
                Task { [weak self] in
                    let data = try? await self?.service.mediaData(item)
                    guard let self, self.service.selected == community else { return }
                    guard let data else {
                        self.mediaLoading.remove(id)
                        return
                    }
                    self.media[id] = data
                }
            } else if attachment.state == .remote {
                mediaLoading.insert(id)
                Task { [weak self] in
                    await self?.service.downloaded(item)
                    self?.mediaLoading.remove(id)
                }
            }
        }
    }

    private func item(_ message: MessageID, in id: CommunityID) -> CommunityTextItem? {
        guard let account = AccountId(string: id), service.selected == account,
              let key = CommunityMessageKey.parse(message, own: service.accountId) else { return nil }
        return service.messages.last { $0.sender == key.sender && $0.messageId == key.message }
    }

    func send(sticker: BrandStickerSelection, to id: CommunityID) async {
        guard let account = AccountId(string: id) else { return }
        var wire = BRPSticker()
        wire.packID = sticker.sticker.packID
        wire.stickerID = sticker.sticker.id
        wire.emoji = sticker.sticker.emoji
        wire.lang = sticker.wireLanguage
        do {
            try await service.sendSticker(wire, to: account)
            await preparation.noteSticker(sticker.id)
        } catch { /* CommunityService exposes the failure through problem. */ }
    }

    func send(voice: VoiceRecording, to id: CommunityID) async -> Bool {
        guard let account = AccountId(string: id), let prepared = try? await preparation.voice(voice) else { return false }
        defer { try? FileManager.default.removeItem(at: prepared.file) }
        do {
            try await service.sendMedia(prepared, to: account)
            return true
        } catch { return false }
    }

    // MARK: Sending media

    func send(attachment: ConversationAttachment, to id: CommunityID) async {
        guard let account = AccountId(string: id) else { return }
        switch attachment {
        case .photo(.file(let url), let caption):
            outgoingMedia.send(url, as: .photo, caption: PickedMediaPlan.usableCaption(caption), in: id, disappearing: .off)
        case .video(let url):
            outgoingMedia.send(url, as: .video, in: id, disappearing: .off)
        case .document(let url):
            defer { SystemPickers.discardPicked(url) }
            guard let prepared = try? await preparation.file(url, url.lastPathComponent, UTTypeName.contentType(for: url)) else {
                return
            }
            do {
                try await service.sendMedia(prepared, caption: "", to: account)
            } catch {
                try? FileManager.default.removeItem(at: prepared.file)
            }
        case .photo, .file, .location, .liveLocation:
            // Communities carry photos, videos and files only.
            break
        }
    }

    func send(pickedMedia messages: [PickedMediaMessage], to id: CommunityID) async {
        let outgoing = outgoingMedia
        for message in messages {
            guard case .copy(let copy) = message.item.source else { continue }
            let messageID = outgoing.begin(message.item.kind, caption: message.caption, in: id, disappearing: .off)
            Task {
                let source = await copy.value
                outgoing.provide(source, for: messageID)
            }
        }
    }

    // MARK: Media of the open conversation

    func mediaPayload(_ message: MessageID, in id: CommunityID) async -> MediaPayload? {
        guard var current = item(message, in: id), let attachment = current.attachment else { return nil }
        if attachment.kind == .image, let data = media[message] {
            return MediaPayload(data: data, contentType: attachment.contentType, fileName: attachment.fileName)
        }
        // Still on the relay: downloaded first (the calling task can stop waiting).
        if !attachment.hasFile {
            service.download(current)
            let deadline = Date().addingTimeInterval(15 * 60)
            while !Task.isCancelled, Date() < deadline {
                guard let latest = item(message, in: id), let file = latest.attachment else { return nil }
                current = latest
                if file.hasFile { break }
                if file.state == .unavailable || file.state == .failed, service.downloadProgress[latest.id] == nil { return nil }
                try? await Task.sleep(for: .milliseconds(250))
            }
        }
        guard current.attachment?.hasFile == true, let data = try? await service.mediaData(current) else { return nil }
        if attachment.kind == .image { media[message] = data }
        return MediaPayload(data: data, contentType: attachment.contentType, fileName: attachment.fileName)
    }

    func downloadMedia(_ message: MessageID, in id: CommunityID) async {
        guard let current = item(message, in: id) else { return }
        service.download(current)
    }

    // MARK: Deletes and info

    func deleteForEveryone(_ message: MessageID, in id: CommunityID) async throws(CommunityProblem) {
        guard let current = item(message, in: id) else { throw .notFound }
        try await run { try await service.deleteForEveryone(current) }
        media[message] = nil
    }

    func deleteForMe(_ message: MessageID, in id: CommunityID) async throws(CommunityProblem) {
        // A picked photo or video not stored yet only leaves this list.
        if outgoingMedia.remove(message) { return }
        guard let current = item(message, in: id) else { throw .notFound }
        try await run { try await service.deleteForMe(current) }
        media[message] = nil
    }

    func recipients(of message: MessageID, in id: CommunityID) async -> [CommunityRecipientInfo] {
        guard let current = item(message, in: id), current.outgoing,
              let rows = try? await service.recipients(of: current) else { return [] }
        let members = Set(service.joined.first { $0.id == current.communityId }?.state?.members.map(\.accountId) ?? [])
        return Self.recipientInfos(rows, names: service.usernames, members: members)
    }

    /// The recipients as 5.4 lists them, by name: who has left since is
    /// marked, times are local.
    static func recipientInfos(
        _ rows: [CommunityRecipientItem], names: [AccountId: String], members: Set<AccountId>
    ) -> [CommunityRecipientInfo] {
        rows.map { row in
            let state: CommunityRecipientInfo.State =
                switch row.state {
                case .pending: .pending
                case .accepted: .accepted
                case .skipped: .skipped
                case .failed: .failed
                }
            return CommunityRecipientInfo(
                id: row.recipient.string, username: names[row.recipient], shortID: shortID(row.recipient), state: state,
                deliveredAt: row.deliveredAt.map { Date(timeIntervalSince1970: TimeInterval($0) / 1000) },
                readAt: row.readAt.map { Date(timeIntervalSince1970: TimeInterval($0) / 1000) },
                hasLeft: !members.contains(row.recipient))
        }
        .sorted { lhs, rhs in (lhs.username ?? "~" + lhs.shortID) < (rhs.username ?? "~" + rhs.shortID) }
    }
}
