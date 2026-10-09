import CryptoKit
import Foundation
import LocalAuthentication
import Us2usKit

/// A `MessengerService` that serves the design's own data (DesignFixtures),
/// in memory, with believable behaviour: availability checks, the real
/// secret-code rules and wrong-attempt delays from Us2usKit, sending (text,
/// replies, attachments, reactions), Ghost changes posting system lines,
/// view-once, request triage, verification (QR and manual), the code
/// change, backup export (a real file for the share sheet) and restore.
/// Nothing is persisted.
///
/// Used until the live Us2usKit wiring lands, by SwiftUI previews and by the
/// UI tests.
@Observable
@MainActor
final class DesignFixturesService: MessengerService {
    private(set) var account: OwnAccount?
    private(set) var connection: ConnectionState = .open
    private(set) var chats: [ChatSummary]
    private(set) var requests: [MessageRequest]
    private(set) var settings: AppSettings
    private(set) var unlockStatus = UnlockStatus(failures: 0, nextAttemptAllowedAt: nil, upcoming: UnlockThrottle.standard.upcomingDelay(afterFailures: 0))

    /// One observable list per chat: a change in one chat re-renders only
    /// the screens showing that chat (not every open conversation).
    @ObservationIgnored private var messagesByChat: ChatMessageTable
    private var secretCode: String
    private var safetyNumbers: [ChatID: SafetyNumberInfo] = [:]
    private let throttle = UnlockThrottle.standard
    private let clock: U2Clock
    private let english: Bool
    /// Simulated network latency for lookups (0 in tests).
    private let latency: Duration
    /// SHA-256 of the passwords of backups exported in this session (never the plain text).
    private var exportedPasswordDigests: Set<Data> = []
    private var restoreTask: Task<Void, Never>?
    private var nextMessageNumber = 0

    /// The password that opens the fixtures' backups (14 characters, “Güçlü”
    /// — the 7.2 / 7.3 references draw 14 dots). Backups exported in this
    /// session open with their own password too.
    nonisolated static let demoBackupPassword = "kuzey7deniz42k"

    /// 7.3 — “8 Ekim 2026, 21:14”.
    nonisolated static var designBackupDate: Date { DesignFixtures.date(day: 8, 21, 14) }

    /// - Parameters:
    ///   - hasAccount: start as an installed account (`false` = fresh install, onboarding).
    ///   - clock: the clock countdowns are relative to (frozen for references).
    ///   - ghostUrgent: the design's last-hour variant.
    ///   - english: serve the demo user content in English (9.1 / 9.2).
    init(hasAccount: Bool, clock: U2Clock, ghostUrgent: Bool = false, english: Bool = false, latency: Duration = .milliseconds(450)) {
        let now = clock.now
        self.clock = clock
        self.english = english
        self.latency = latency
        account = hasAccount ? OwnAccount(username: DesignFixtures.ownUsername) : nil
        chats = DesignFixtures.chats(now: now, ghostUrgent: ghostUrgent, english: english)
        requests = DesignFixtures.requests(now: now, english: english)
        settings = DesignFixtures.settings
        secretCode = DesignFixtures.demoSecretCode
        messagesByChat = ChatMessageTable([
            "deniz": DesignFixtures.messages(chat: "deniz", english: english),
            "mert.k": DesignFixtures.messages(chat: "mert.k", english: english),
        ])
        safetyNumbers["deniz"] = SafetyNumberInfo(
            groups: DesignFixtures.safetyNumberGroups,
            scannablePayload: Data(DesignFixtures.safetyNumberGroups.joined().utf8),
            verifiedAt: nil, method: nil)
    }

    // MARK: Account & onboarding

    func usernameAvailability(_ username: String) async -> UsernameAvailability {
        guard !username.isEmpty else { return .empty }
        if let violation = UsernameRules.violation(username) { return .invalid(violation) }
        try? await Task.sleep(for: latency)
        return DesignFixtures.takenUsernames.contains(username) ? .taken : .available
    }

    func secretCodeProblem(_ code: String) -> SecretCodeError? {
        do {
            try SecretCode.validate(code)
            return nil
        } catch {
            return error
        }
    }

    func createAccount(username: String, secretCode: String, faceIDEnabled: Bool) async throws {
        try? await Task.sleep(for: latency)
        account = OwnAccount(username: username)
        self.secretCode = secretCode
        settings.faceIDEnabled = faceIDEnabled
    }

    func completeRestore(secretCode: String, faceIDEnabled: Bool) async throws {
        try? await Task.sleep(for: latency)
        // The restored vault: the design's own account and chats.
        if chats.isEmpty { loadDesignData() }
        account = OwnAccount(username: DesignFixtures.ownUsername)
        self.secretCode = secretCode
        settings.faceIDEnabled = faceIDEnabled
        unlockStatus = UnlockStatus(failures: 0, nextAttemptAllowedAt: nil, upcoming: throttle.upcomingDelay(afterFailures: 0))
    }

    private func loadDesignData() {
        let now = clock.now
        chats = DesignFixtures.chats(now: now, ghostUrgent: false, english: english)
        requests = DesignFixtures.requests(now: now, english: english)
        settings = DesignFixtures.settings
        messagesByChat.replace(with: [
            "deniz": DesignFixtures.messages(chat: "deniz", english: english),
            "mert.k": DesignFixtures.messages(chat: "mert.k", english: english),
        ])
    }

    // MARK: Lock

    func unlock(code: String) async -> UnlockAttemptResult {
        guard SecretCode.isWellFormed(code) else { return .malformed }
        let now = Date()
        if let next = unlockStatus.nextAttemptAllowedAt, next > now {
            return .throttled(until: next)
        }
        if code == secretCode {
            unlockStatus = UnlockStatus(failures: 0, nextAttemptAllowedAt: nil, upcoming: throttle.upcomingDelay(afterFailures: 0))
            return .unlocked
        }
        let failures = unlockStatus.failures + 1
        let delay = throttle.delay(afterFailures: failures)
        unlockStatus = UnlockStatus(
            failures: failures,
            nextAttemptAllowedAt: delay > 0 ? now.addingTimeInterval(delay) : nil,
            upcoming: throttle.upcomingDelay(afterFailures: failures))
        return .wrongCode(unlockStatus)
    }

    func unlockWithBiometrics(reason: String, cancelTitle: String) async -> Bool {
        guard settings.faceIDEnabled else { return false }
        let context = LAContext()
        context.localizedCancelTitle = cancelTitle
        // No device-passcode fallback: the secret code is the only other way in.
        context.localizedFallbackTitle = ""
        var error: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error) else { return false }
        do {
            return try await context.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, localizedReason: reason)
        } catch {
            return false
        }
    }

    func eraseEverything() async {
        account = nil
        chats = []
        requests = []
        messagesByChat.replace(with: [:])
        safetyNumbers = [:]
        settings = AppSettings()
        unlockStatus = UnlockStatus(failures: 0, nextAttemptAllowedAt: nil, upcoming: throttle.upcomingDelay(afterFailures: 0))
    }

    // MARK: Chats

    func lookup(username: String) async -> UserLookup {
        let name = UsernameRules.sanitize(username)
        guard UsernameRules.isValid(name) else { return .idle }
        try? await Task.sleep(for: latency)
        if name == "deniz" {
            // 4.2 shows deniz as found and not yet a contact.
            return .found(Peer(username: "deniz", palette: .a, isVerified: false, isAcceptedContact: false, presence: .hidden), isExistingChat: false)
        }
        if let chat = chats.first(where: { $0.peer.username == name }) {
            return .found(chat.peer, isExistingChat: true)
        }
        if DesignFixtures.takenUsernames.contains(name) {
            return .found(Peer(username: name, palette: .forUsername(name), isVerified: false, isAcceptedContact: false, presence: .hidden), isExistingChat: false)
        }
        return .notFound
    }

    func accept(_ request: MessageRequest) async -> ChatID? {
        requests.removeAll { $0.id == request.id }
        let peer = Peer(username: request.sender, palette: .forUsername(request.sender), isVerified: false, isAcceptedContact: true, presence: .hidden)
        let last = request.messages.last
        let summary = ChatSummary(
            id: request.sender, peer: peer,
            lastMessage: last.map { MessagePreview(kind: request.preview, direction: .incoming, status: nil, sentAt: $0.sentAt) },
            unreadCount: 0, ghost: .off, disappearing: .off)
        chats.insert(summary, at: 0)
        messagesByChat[request.sender] = request.messages
        return summary.id
    }

    func block(_ request: MessageRequest) async {
        requests.removeAll { $0.id == request.id }
    }

    func delete(_ request: MessageRequest) async {
        requests.removeAll { $0.id == request.id }
    }

    func startChat(username: String) async -> ChatID? {
        let name = UsernameRules.sanitize(username)
        guard UsernameRules.isValid(name), name != account?.username else { return nil }
        if let existing = chats.first(where: { $0.peer.username == name }) { return existing.id }
        // A new, empty conversation; the first message becomes a request on
        // the other side (no presence until it is accepted).
        let peer = Peer(username: name, palette: .forUsername(name), isVerified: false, isAcceptedContact: false, presence: .hidden)
        chats.insert(ChatSummary(id: name, peer: peer, lastMessage: nil, unreadCount: 0, ghost: .off, disappearing: settings.defaultDisappearing), at: 0)
        messagesByChat[name] = []
        return name
    }

    func ownContactCode() async -> String? {
        guard let username = account?.username else { return nil }
        // A fixture fingerprint of the identity key.
        let digest = SHA256.hash(data: Data("us2us-identity-\(username)".utf8))
        return ContactCode.encode(username: username, keyFingerprint: digest.prefix(16).map { String(format: "%02x", $0) }.joined())
    }

    // MARK: Conversation

    func chat(_ id: ChatID) -> ChatSummary? {
        chats.first { $0.id == id }
    }

    func messages(in chat: ChatID) -> [Message] {
        messagesByChat[chat] ?? []
    }

    func send(text: String, replyTo: QuotedMessage?, in chat: ChatID) async {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        appendOutgoing(.text(trimmed), replyTo: replyTo, in: chat)
    }

    func send(attachment: ConversationAttachment, in chat: ChatID) async {
        let now = clock.now
        let content: MessageContent =
            switch attachment {
            case .photo(let source, let caption): .photo(source, caption: caption)
            case .file(let name, let byteCount, let typeLabel, let pageCount):
                .file(name: name, byteCount: byteCount, typeLabel: typeLabel, pageCount: pageCount)
            case .location(let name, let locality, let latitude, let longitude):
                .location(name: name, locality: locality, latitude: latitude, longitude: longitude)
            case .liveLocation(let duration): .liveLocation(expiresAt: now.addingTimeInterval(duration), isActive: true)
            case .textSticker(let text): .textSticker(text)
            case .document(let url):
                .file(
                    name: url.lastPathComponent, byteCount: 0, typeLabel: url.pathExtension.uppercased(),
                    pageCount: nil)
            }
        appendOutgoing(content, replyTo: nil, in: chat)
    }

    func resend(_ message: MessageID, in chat: ChatID) async {
        guard let original = messagesByChat[chat]?.first(where: { $0.id == message }) else { return }
        messagesByChat[chat]?.removeAll { $0.id == message }
        appendOutgoing(original.content, replyTo: original.replyTo, in: chat, disappearing: original.disappearing)
    }

    /// A new outgoing message (status “Gönderildi”), the row moved to the top
    /// of 4.1, and the Ghost timer restarted.
    private func appendOutgoing(
        _ content: MessageContent, replyTo: QuotedMessage?, in chat: ChatID, disappearing: DisappearingTimer? = nil
    ) {
        guard let index = chats.firstIndex(where: { $0.id == chat }) else { return }
        let now = clock.now
        nextMessageNumber += 1
        let message = Message(
            id: "out-\(nextMessageNumber)-\(UUID().uuidString.prefix(8))", direction: .outgoing, sentAt: now,
            content: content, status: .sent, replyTo: replyTo, disappearing: disappearing ?? chats[index].disappearing)
        messagesByChat[chat, default: []].append(message)
        var summary = chats.remove(at: index)
        summary.lastMessage = Self.preview(of: message)
        if summary.ghost.isEnabled {
            // Every new message restarts the Ghost timer.
            summary.ghost.deadline = now.addingTimeInterval(summary.ghost.period.duration)
        }
        chats.insert(summary, at: 0)
    }

    /// What 4.1 shows for a message (line 2 of the row).
    private static func preview(of message: Message) -> MessagePreview? {
        let kind: MessagePreviewKind
        switch message.content {
        case .text(let text): kind = message.isDisappearing ? .disappearing : .text(text)
        case .photo: kind = .photo
        case .video: kind = .video
        case .voice: kind = .voice
        case .file(let name, _, _, _): kind = .file(name)
        case .location: kind = .location
        case .liveLocation(_, let isActive): kind = isActive ? .liveLocation : .liveLocationEnded
        case .textSticker: kind = .sticker
        case .viewOnce: kind = .photoViewOnce
        case .deleted: kind = .deleted
        case .system: return nil
        }
        return MessagePreview(kind: kind, direction: message.direction, status: message.status, sentAt: message.sentAt)
    }

    /// Keeps the 4.1 row in step with the chat's newest message.
    private func refreshPreview(of chat: ChatID) {
        guard let index = chats.firstIndex(where: { $0.id == chat }) else { return }
        guard let last = messagesByChat[chat]?.last(where: { !$0.isSystem }) else { return }
        if let preview = Self.preview(of: last), preview.sentAt >= (chats[index].lastMessage?.sentAt ?? .distantPast) {
            chats[index].lastMessage = preview
        }
    }

    private func updateMessage(_ id: MessageID, in chat: ChatID, _ change: (inout Message) -> Void) {
        guard let index = messagesByChat[chat]?.firstIndex(where: { $0.id == id }) else { return }
        change(&messagesByChat[chat]![index])
    }

    func updateGhost(_ ghost: GhostState, disappearing: DisappearingTimer, in chat: ChatID) async {
        guard let index = chats.firstIndex(where: { $0.id == chat }) else { return }
        let now = clock.now
        var updated = ghost
        if updated.isEnabled {
            updated.deadline = now.addingTimeInterval(updated.period.duration)
        } else {
            updated.deadline = nil
        }
        let before = chats[index]
        chats[index].ghost = updated
        chats[index].disappearing = disappearing
        // Every change drops a system line into the chat (5.5 note).
        if before.ghost.isEnabled != updated.isEnabled || before.ghost.period != updated.period {
            let event: SystemEvent = updated.isEnabled ? .ghostEnabled(updated.period) : .ghostDisabled
            messagesByChat[chat, default: []].append(
                Message(id: UUID().uuidString, direction: .outgoing, sentAt: now, content: .system(event)))
        }
        if before.disappearing != disappearing {
            messagesByChat[chat, default: []].append(
                Message(id: UUID().uuidString, direction: .outgoing, sentAt: now, content: .system(.disappearingChanged(disappearing))))
        }
    }

    func deleteForEveryone(_ message: MessageID, in chat: ChatID) async {
        updateMessage(message, in: chat) {
            $0.content = .deleted
            $0.reactions = []
        }
        refreshPreview(of: chat)
    }

    func deleteForMe(_ message: MessageID, in chat: ChatID) async {
        messagesByChat[chat]?.removeAll { $0.id == message }
    }

    func setReaction(_ emoji: String?, on message: MessageID, in chat: ChatID) async {
        updateMessage(message, in: chat) { message in
            // The user's own reaction goes; the other side's stay.
            var reactions: [Reaction] = message.reactions.compactMap { reaction in
                guard reaction.includesMe else { return reaction }
                guard reaction.count > 1 else { return nil }
                return Reaction(emoji: reaction.emoji, count: reaction.count - 1, includesMe: false)
            }
            if let emoji {
                if let index = reactions.firstIndex(where: { $0.emoji == emoji }) {
                    reactions[index].count += 1
                    reactions[index].includesMe = true
                } else {
                    reactions.append(Reaction(emoji: emoji, count: 1, includesMe: true))
                }
            }
            message.reactions = reactions
        }
    }

    func stopLiveLocation(_ message: MessageID, in chat: ChatID) async {
        let now = clock.now
        updateMessage(message, in: chat) { message in
            guard case .liveLocation(let expiresAt, true) = message.content else { return }
            message.content = .liveLocation(expiresAt: min(expiresAt, now), isActive: false)
        }
        refreshPreview(of: chat)
    }

    func viewOnceMedia(_ message: MessageID, in chat: ChatID) async -> MediaSource? {
        guard let found = messagesByChat[chat]?.first(where: { $0.id == message }), !found.isOutgoing,
            case .viewOnce(_, .unopened) = found.content
        else { return nil }
        // The fixtures' view-once photo is the design's night-pier painting (5.6).
        return .designArtwork(.nightPier)
    }

    func markViewOnceOpened(_ message: MessageID, in chat: ChatID) async {
        updateMessage(message, in: chat) { message in
            guard case .viewOnce(let isVideo, _) = message.content else { return }
            message.content = .viewOnce(isVideo: isVideo, state: .opened)
        }
    }

    func reportScreenshot(of message: MessageID, in chat: ChatID) async {
        // The system line lands in the sender's chat, not in ours (5.6 note);
        // the fixtures have no other side to deliver it to.
    }

    // MARK: Verification

    func safetyNumber(for chat: ChatID) async -> SafetyNumberInfo? {
        if let info = safetyNumbers[chat] { return info }
        // Any other chat: a deterministic stand-in derived from the username.
        let seed = chat.utf8.reduce(UInt64(1469598103934665603)) { ($0 ^ UInt64($1)) &* 1099511628211 }
        var generator = seed
        let groups = (0..<12).map { _ -> String in
            generator = generator &* 6364136223846793005 &+ 1442695040888963407
            return String(format: "%05d", Int((generator >> 33) % 100_000))
        }
        return SafetyNumberInfo(groups: groups, scannablePayload: Data(groups.joined().utf8), verifiedAt: nil, method: nil)
    }

    func markVerified(_ chat: ChatID, method: VerificationMethod) async {
        var info: SafetyNumberInfo?
        if let existing = safetyNumbers[chat] {
            info = existing
        } else {
            info = await safetyNumber(for: chat)
        }
        let now = clock.now
        let wasVerified = info?.isVerified ?? false
        info?.verifiedAt = now
        info?.method = method
        info?.changedAt = nil
        safetyNumbers[chat] = info
        if let index = chats.firstIndex(where: { $0.id == chat }) {
            chats[index].peer.isVerified = true
        }
        if !wasVerified {
            // “Güvenlik numarası doğrulandı” (the 6.3 system line, proposed).
            nextMessageNumber += 1
            messagesByChat[chat, default: []].append(
                Message(id: "sys-verified-\(nextMessageNumber)", direction: .outgoing, sentAt: now, content: .system(.safetyNumberVerified)))
        }
    }

    func clearVerification(_ chat: ChatID) async {
        if safetyNumbers[chat] == nil { safetyNumbers[chat] = await safetyNumber(for: chat) }
        safetyNumbers[chat]?.verifiedAt = nil
        safetyNumbers[chat]?.method = nil
        if let index = chats.firstIndex(where: { $0.id == chat }) {
            chats[index].peer.isVerified = false
        }
    }

    func compareScannedSafetyCode(_ code: String, for chat: ChatID) async -> SafetyCodeComparison {
        guard let scanned = SafetyQRCodec.decode(code) else { return .notASafetyCode }
        guard let info = await safetyNumber(for: chat) else { return .mismatch }
        // Fixture semantics: the raw payloads (the live comparison is libsignal's).
        return SafetyQRCodec.constantTimeEquals(scanned, info.scannablePayload) ? .match : .mismatch
    }

    func resolveContactCode(_ code: String) async -> ContactCodeResolution {
        guard let name = ContactCode.username(from: code) else { return .notAContactCode }
        if name == account?.username { return .ownCode }
        if chats.contains(where: { $0.peer.username == name }) { return .contact(username: name) }
        if case .found = await lookup(username: name) { return .contact(username: name) }
        return .unknown
    }

    func addContact(fromScannedCode code: String) async -> ChatID? {
        guard case .contact(let name) = await resolveContactCode(code) else { return nil }
        let chat: ChatID
        if let existing = chats.first(where: { $0.peer.username == name }) {
            chat = existing.id
        } else {
            // Scanned side by side: an accepted contact at once.
            let peer = Peer(username: name, palette: .forUsername(name), isVerified: false, isAcceptedContact: true, presence: .hidden)
            chats.insert(ChatSummary(id: name, peer: peer, lastMessage: nil, unreadCount: 0, ghost: .off, disappearing: settings.defaultDisappearing), at: 0)
            messagesByChat[name] = []
            chat = name
        }
        await markVerified(chat, method: .faceToFace)
        return chat
    }

    // MARK: Settings & backup

    func updateSettings(_ settings: AppSettings) async {
        self.settings = settings
    }

    func backupEstimate() async -> BackupEstimate {
        DesignFixtures.backupEstimate
    }

    func setBiometricUnlock(_ enabled: Bool, reason: String) async -> Bool {
        if enabled {
            let context = LAContext()
            context.localizedFallbackTitle = ""
            var error: NSError?
            guard context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error) else {
                return settings.faceIDEnabled
            }
            guard (try? await context.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, localizedReason: reason)) == true else {
                return settings.faceIDEnabled
            }
        }
        settings.faceIDEnabled = enabled
        return enabled
    }

    func verifySecretCode(_ code: String) async -> UnlockAttemptResult {
        await unlock(code: code)
    }

    func changeSecretCode(current: String, new: String) async throws(ChangeSecretCodeError) {
        guard current == secretCode else { throw .wrongCurrentCode }
        guard secretCodeProblem(new) == nil else { throw .failed }
        // Re-wrapping the vault key takes a moment in the live implementation.
        try? await Task.sleep(for: latency)
        secretCode = new
    }

    func backupPasswordIsSecretCode(_ password: String) async -> Bool {
        password == secretCode
    }

    func exportBackup(
        _ contents: BackupContents, password: String, fileName: String,
        progress: @escaping @MainActor @Sendable (Double) -> Void
    ) async throws -> URL {
        let steps = 24
        for step in 1...steps {
            try await Task.sleep(for: .milliseconds(55))
            progress(Double(step) / Double(steps))
        }
        exportedPasswordDigests.insert(Self.digest(password))
        let directory = try Self.exportDirectory()
        let url = directory.appendingPathComponent(fileName)
        // A stand-in file: a header and random bytes (what an encrypted body looks like).
        var data = Data("U2UBACKUP\u{1}".utf8)
        data.append(contentsOf: (0..<4_096).map { _ in UInt8.random(in: 0...255) })
        try data.write(to: url, options: [.atomic, .completeFileProtection])
        return url
    }

    func finishExport(at url: URL, saved: Bool) async {
        try? FileManager.default.removeItem(at: url)
        if saved { settings.lastBackupAt = clock.now }
    }

    /// The protected temporary directory for exports — emptied before every
    /// export, so a file left behind by a killed share sheet never lingers.
    private static func exportDirectory() throws -> URL {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("backup-export", isDirectory: true)
        try? FileManager.default.removeItem(at: directory)
        try FileManager.default.createDirectory(
            at: directory, withIntermediateDirectories: true, attributes: [.protectionKey: FileProtectionType.complete])
        return directory
    }

    private static func digest(_ password: String) -> Data {
        Data(SHA256.hash(data: Data(password.utf8)))
    }

    func inspectBackup(at url: URL) async throws(BackupFileError) -> BackupFileInfo {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard url.pathExtension.lowercased() == "u2u" else { throw .notABackup }
        let attributes: [FileAttributeKey: Any]
        do {
            attributes = try FileManager.default.attributesOfItem(atPath: url.path)
        } catch {
            throw .unreadable
        }
        try? await Task.sleep(for: .milliseconds(350))
        let size = (attributes[.size] as? NSNumber)?.int64Value ?? 0
        // The fixtures describe every backup with the design's 7.3 card.
        return BackupFileInfo(
            url: url, fileName: url.lastPathComponent, byteCount: max(size, DesignFixtures.backupEstimate.totalBytes),
            createdAt: Self.designBackupDate)
    }

    func openBackup(_ file: BackupFileInfo, password: String) async -> BackupOpenResult {
        try? await Task.sleep(for: .milliseconds(700))
        if password == Self.demoBackupPassword || exportedPasswordDigests.contains(Self.digest(password)) {
            return .opened
        }
        return .wrongPassword
    }

    func restoreBackup(_ file: BackupFileInfo) -> AsyncThrowingStream<RestoreProgress, any Error> {
        restoreTask?.cancel()
        let total = DesignFixtures.restoreProgress
        let (stream, continuation) = AsyncThrowingStream<RestoreProgress, any Error>.makeStream()
        let task = Task { @MainActor in
            // keys 0 → 8 %, messages 8 → 80 %, media 80 → 100 % over ~9 s.
            let ticks = 90
            for tick in 0...ticks {
                if Task.isCancelled {
                    continuation.finish(throwing: CancellationError())
                    return
                }
                let fraction = Double(tick) / Double(ticks)
                let stage: RestoreProgress.Stage = fraction < 0.08 ? .keys : fraction < 0.8 ? .messages : .media
                let messageShare = min(max((fraction - 0.08) / 0.72, 0), 1)
                continuation.yield(
                    RestoreProgress(
                        fraction: fraction, stage: stage,
                        messagesDone: Int((Double(total.messagesTotal) * messageShare).rounded()),
                        messagesTotal: total.messagesTotal, mediaBytes: total.mediaBytes,
                        estimatedRemaining: (1 - fraction) * 160))
                try? await Task.sleep(for: .milliseconds(100))
            }
            continuation.finish()
        }
        restoreTask = task
        continuation.onTermination = { termination in
            if case .cancelled = termination { task.cancel() }
        }
        return stream
    }

    func cancelRestore() async {
        restoreTask?.cancel()
        restoreTask = nil
    }

    // MARK: Fixture controls (previews, harness, UI tests)

    /// Switches the connection state.
    func setConnection(_ state: ConnectionState) {
        connection = state
    }

    /// Overrides a message's delivery state (failed / pending demos, the status cross-fade).
    func setStatus(_ status: MessageStatus, of message: MessageID, in chat: ChatID, at date: Date? = nil) {
        let when = date ?? clock.now
        updateMessage(message, in: chat) { message in
            message.status = status
            switch status {
            case .failed: message.failedAt = when
            case .delivered: message.deliveredAt = when
            case .read: message.readAt = when
            default: break
            }
        }
        if let index = chats.firstIndex(where: { $0.id == chat }), chats[index].lastMessage?.direction == .outgoing,
            messagesByChat[chat]?.last(where: { !$0.isSystem })?.id == message
        {
            chats[index].lastMessage?.status = status
        }
    }

    /// The peer's safety number changed (new device): 6.1's danger banner, no seal.
    func setSafetyNumberChanged(_ chat: ChatID) async {
        if safetyNumbers[chat] == nil { safetyNumbers[chat] = await safetyNumber(for: chat) }
        safetyNumbers[chat]?.changedAt = clock.now
        safetyNumbers[chat]?.verifiedAt = nil
        safetyNumbers[chat]?.method = nil
        if let index = chats.firstIndex(where: { $0.id == chat }) { chats[index].peer.isVerified = false }
    }
}

#if DEBUG
    extension DesignFixturesService {
        /// `-U2PickBackup YES` (UI tests, flow walks): the system document
        /// picker runs in another process, so 7.3 “picks” a stand-in `.u2u`
        /// file instead (the fixtures open it with `demoBackupPassword`).
        static var pickedBackupForTesting: URL? {
            guard UserDefaults.standard.bool(forKey: "U2PickBackup") else { return nil }
            let url = FileManager.default.temporaryDirectory.appendingPathComponent(DesignFixtures.backupEstimate.fileName)
            if !FileManager.default.fileExists(atPath: url.path) {
                try? Data("U2UBACKUP\u{1}".utf8).write(to: url, options: .atomic)
            }
            return url
        }
    }
#endif

// MARK: - Per-chat message lists

/// The messages of every chat, one `@Observable` list per chat. Reading a
/// chat's messages tracks only that chat; writing one notifies only its
/// readers. A chat read before it exists gets an empty store, so its readers
/// are told when it appears.
@MainActor
final class ChatMessageTable {
    @Observable
    @MainActor
    final class Store {
        var items: [Message]?
        init(_ items: [Message]?) { self.items = items }
    }

    private var stores: [ChatID: Store] = [:]

    init(_ initial: [ChatID: [Message]] = [:]) {
        for (chat, items) in initial { stores[chat] = Store(items) }
    }

    private func store(_ chat: ChatID) -> Store {
        if let store = stores[chat] { return store }
        let store = Store(nil)
        stores[chat] = store
        return store
    }

    subscript(chat: ChatID) -> [Message]? {
        get { store(chat).items }
        set { store(chat).items = newValue }
    }

    subscript(chat: ChatID, default fallback: @autoclosure () -> [Message]) -> [Message] {
        get { store(chat).items ?? fallback() }
        set { store(chat).items = newValue }
    }

    /// Replaces every chat's list (stores are kept, so open screens update).
    func replace(with lists: [ChatID: [Message]]) {
        for (chat, store) in stores where lists[chat] == nil && store.items != nil { store.items = nil }
        for (chat, items) in lists { store(chat).items = items }
    }
}
