import CoreLocation
import Foundation
import Observation
import UniformTypeIdentifiers
import Us2usKit
import Us2usRuntime

/// The live `MessengerService`: the screens' data surface over Us2usRuntime's
/// `AppRuntime` (vault, SQLCipher storage, crypto-shredding, the relay
/// lifecycle, privacy guards) and Us2usKit (libsignal, the relay client).
///
/// * Every observable property is computed from the runtime's observable
///   state, so SwiftUI tracks the runtime directly; the mapping
///   (`LiveMapping`) is cached per input and dropped when the app locks.
/// * Nothing decrypted is kept while locked: the runtime clears its state on
///   every lock and the caches here follow it; decrypted media lives in
///   memory only.
/// * One network endpoint: the relay (`RelayEndpoint`).
@Observable
@MainActor
final class RealMessengerService: MessengerService {
    @ObservationIgnored let runtime: AppRuntime
    @ObservationIgnored private let location = LocationProvider()

    /// Verification method and date of this session (the runtime stores only
    /// the verified flag).
    private var verifications: [ChatID: (date: Date, method: VerificationMethod)] = [:]
    private var lastBackupAt: Date?
    /// A picked backup whose password was checked (7.3), restored with the
    /// new code chosen in 2.2 (`completeRestore`). Memory only.
    @ObservationIgnored private var pendingRestore: (url: URL, password: String)?
    /// Decrypted full images by message id (memory only; cleared on lock).
    private var media: [MessageID: Data] = [:]
    @ObservationIgnored private var mediaLoading: Set<MessageID> = []
    /// Live-location sessions this device runs, by message id.
    @ObservationIgnored private var liveSessions: [Data: (peer: AccountId, sessionId: Data)] = [:]

    @ObservationIgnored private var chatCache: (key: [Us2usRuntime.ChatSummary], presence: [AccountId: PresenceStatus], sharing: Bool, changed: Set<AccountId>, value: [ChatSummary])?
    @ObservationIgnored private var requestCache: (key: [Us2usRuntime.ChatSummary], value: [MessageRequest])?
    @ObservationIgnored private var messageCache: (peer: AccountId?, key: [MessageItem], media: Int, value: [Message])?

    init(runtime: AppRuntime) {
        self.runtime = runtime
    }

    /// The app's runtime: production relay (DEBUG builds may point it at a
    /// local relay with `-U2RelayURL`), Application Support with complete
    /// protection, the Keychain and the Secure Enclave.
    static func makeDefault(relayURL: URL) throws -> RealMessengerService {
        RealMessengerService(runtime: AppRuntime(environment: try .production(relayURL: relayURL)))
    }

    private var now: Date { Date() }

    // MARK: Account & onboarding

    var account: OwnAccount? { runtime.ownUsername.map { OwnAccount(username: $0) } }

    var hasAccount: Bool { runtime.lockState != .needsSetup }

    var sendsDesignContent: Bool { false }

    func usernameAvailability(_ username: String) async -> UsernameAvailability {
        guard !username.isEmpty else { return .empty }
        if let violation = UsernameRules.violation(username) { return .invalid(violation) }
        // The protocol's rules (§3.1) are stricter than the design's (no dot,
        // first a letter): what the kit refuses is refused here.
        guard (try? NormalizedUsername(username)) != nil else { return .invalid(.characters) }
        // The directory answers only authenticated accounts (§4.4), so a taken
        // name shows at “Kurulumu tamamla” (409) instead of while typing.
        return .available
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
        try await runtime.createAccount(username: username, displayName: "", code: secretCode, confirmation: secretCode)
        if faceIDEnabled { try? await runtime.enableQuickUnlock(code: secretCode) }
    }

    func completeRestore(secretCode: String, faceIDEnabled: Bool) async throws {
        guard let pending = pendingRestore else { throw RuntimeError.unreadableBackup }
        // 3.3 › 7.3 inside the lock: the old vault on this phone goes first
        // (the backup's password was already checked).
        if runtime.lockState == .locked { await runtime.eraseEverything() }
        try await runtime.restore(
            backupFile: pending.url, password: pending.password, code: secretCode, confirmation: secretCode)
        pendingRestore = nil
        try? FileManager.default.removeItem(at: pending.url)
        if faceIDEnabled { try? await runtime.enableQuickUnlock(code: secretCode) }
    }

    // MARK: Lock

    var unlockStatus: UnlockStatus {
        runtime.unlockStatus
            ?? UnlockStatus(
                failures: 0, nextAttemptAllowedAt: nil, upcoming: UnlockThrottle.standard.upcomingDelay(afterFailures: 0))
    }

    func unlock(code: String) async -> UnlockAttemptResult {
        guard SecretCode.isWellFormed(code) else { return .malformed }
        do {
            try await runtime.unlock(code: code)
            return runtime.lockState == .unlocked ? .unlocked : .wrongCode(unlockStatus)
        } catch {
            return Self.attemptResult(error, status: unlockStatus)
        }
    }

    static func attemptResult(_ error: any Error, status: UnlockStatus) -> UnlockAttemptResult {
        switch error {
        case UnlockError.vault(.lockedOut(let until)): .throttled(until: until)
        case UnlockError.vault(.malformedCode): .malformed
        default: .wrongCode(status)
        }
    }

    func unlockWithBiometrics(reason: String, cancelTitle: String) async -> Bool {
        do {
            try await runtime.unlockWithBiometrics()
            return runtime.lockState == .unlocked
        } catch {
            return false
        }
    }

    func eraseEverything() async {
        location.stopLiveUpdates()
        liveSessions = [:]
        pendingRestore = nil
        clearCaches()
        verifications = [:]
        lastBackupAt = nil
        await runtime.eraseEverything()
    }

    // MARK: Lifecycle

    func scenePhaseChanged(_ phase: MessengerScenePhase) {
        switch phase {
        case .active: runtime.scenePhaseChanged(.active)
        case .inactive: runtime.scenePhaseChanged(.inactive)
        case .background:
            runtime.scenePhaseChanged(.background)
            clearCaches()
        }
    }

    private func clearCaches() {
        chatCache = nil
        requestCache = nil
        messageCache = nil
        media = [:]
        mediaLoading = []
    }

    // MARK: Line & chats

    var connection: ConnectionState { LiveMapping.connection(.init(runtime.connection)) }

    var chats: [ChatSummary] {
        let source = runtime.chats
        let presence = runtime.presence
        let sharing = runtime.settings.presenceSharing
        let changed = runtime.safetyNumberChanges
        if let cache = chatCache, cache.key == source, cache.presence == presence, cache.sharing == sharing,
            cache.changed == changed
        {
            return cache.value
        }
        let own = runtime.ownAccountId
        let date = now
        let value = source.map { summary in
            var record = LiveChatRecord(summary, ownAccount: own)
            if changed.contains(summary.id) { record.safetyNumberChanged = true }
            return LiveMapping.chatSummary(
                record, presence: presence[summary.id].map(LivePresence.init), now: date, ownSharing: sharing)
        }
        chatCache = (source, presence, sharing, changed, value)
        return value
    }

    var requests: [MessageRequest] {
        let source = runtime.requests
        if let cache = requestCache, cache.key == source { return cache.value }
        let own = runtime.ownAccountId
        let date = now
        let value = source.map { LiveMapping.request(LiveChatRecord($0, ownAccount: own), now: date) }
        requestCache = (source, value)
        return value
    }

    func lookup(username: String) async -> UserLookup {
        guard (try? NormalizedUsername(username)) != nil else { return .notFound }
        guard let entry = try? await runtime.lookupAccount(username: username) else { return .notFound }
        let id = entry.account.string
        if let existing = chat(id) { return .found(existing.peer, isExistingChat: true) }
        return .found(
            Peer(
                username: NormalizedUsername.normalize(username), palette: .forUsername(username), isVerified: false,
                isAcceptedContact: false, presence: .hidden), isExistingChat: false)
    }

    func accept(_ request: MessageRequest) async -> ChatID? {
        guard let peer = AccountId(string: request.id) else { return nil }
        do {
            try await runtime.acceptRequest(peer)
            return request.id
        } catch {
            return nil
        }
    }

    func block(_ request: MessageRequest) async {
        guard let peer = AccountId(string: request.id) else { return }
        try? await runtime.block(peer)
    }

    func delete(_ request: MessageRequest) async {
        guard let peer = AccountId(string: request.id) else { return }
        try? await runtime.declineRequest(peer)
    }

    func startChat(username: String) async -> ChatID? {
        try? await runtime.startConversation(username: username).string
    }

    func ownContactCode() async -> String? {
        guard let username = runtime.ownUsername else { return nil }
        let key: Data? = (try? await runtime.ownIdentityKey()) ?? nil
        return ContactCode.encode(username: username, keyFingerprint: key.map(LiveMapping.identityFingerprint))
    }

    // MARK: Conversation

    func chat(_ id: ChatID) -> ChatSummary? {
        chats.first { $0.id == id }
    }

    func conversationAppeared(_ chat: ChatID) async {
        guard let peer = AccountId(string: chat) else { return }
        await runtime.openConversation(peer)
    }

    func conversationDisappeared(_ chat: ChatID) {
        guard runtime.openPeer?.string == chat else { return }
        runtime.closeConversation()
        media = [:]
        mediaLoading = []
    }

    func messages(in chat: ChatID) -> [Message] {
        guard let peer = runtime.openPeer, peer.string == chat else { return [] }
        let source = runtime.openMessages
        if let cache = messageCache, cache.peer == peer, cache.key == source, cache.media == media.count {
            return cache.value
        }
        let own = runtime.ownAccountId
        let records = source.map { LiveMessageRecord($0, ownAccount: own) }
        let peerName = self.chat(chat)?.peer.username ?? ""
        let value = LiveMapping.messages(
            records, ownUsername: ownUsername, peerUsername: peerName, now: now, media: media)
        messageCache = (peer, source, media.count, value)
        loadMedia(for: source, peer: peer)
        return value
    }

    /// Full images of the open conversation, decrypted into memory (and
    /// downloaded first when they are still on the relay).
    private func loadMedia(for items: [MessageItem], peer: AccountId) {
        for item in items where !item.viewOnce {
            guard case .attachment(let attachment) = item.body, attachment.kind == .image else { continue }
            let id = LiveMapping.messageID(item.messageId)
            guard media[id] == nil, !mediaLoading.contains(id) else { continue }
            switch attachment.availability {
            case .local:
                mediaLoading.insert(id)
                Task { [weak self] in
                    let data = try? await self?.runtime.mediaData(for: item.messageId, peer: peer)
                    guard let self, self.runtime.openPeer == peer, let data else { return }
                    self.media[id] = data
                }
            case .remote:
                mediaLoading.insert(id)
                Task { [weak self] in
                    try? await self?.runtime.download(item.messageId, peer: peer)
                    self?.mediaLoading.remove(id)
                }
            case .transferring, .heldUntilAccepted, .unavailable:
                continue
            }
        }
    }

    func send(text: String, replyTo: QuotedMessage?, in chat: ChatID) async {
        guard let peer = AccountId(string: chat) else { return }
        var quote: QuoteInfo?
        if let replyTo, let target = replyTo.messageID.flatMap(LiveMapping.messageData) {
            quote = QuoteInfo(targetMessageId: target, previewText: Self.quoteText(replyTo))
        }
        _ = try? await runtime.sendText(text, to: peer, quote: quote)
    }

    private static func quoteText(_ quote: QuotedMessage) -> String {
        switch quote.kind {
        case .text(let text): String(text.prefix(140))
        case .photo: "📷"
        case .video: "🎬"
        case .voice: "🎙"
        case .file(let name): name
        }
    }

    func send(attachment: ConversationAttachment, in chat: ChatID) async {
        guard let peer = AccountId(string: chat) else { return }
        switch attachment {
        case .photo(.file(let url), let caption):
            defer { try? FileManager.default.removeItem(at: url) }
            guard let prepared = try? await runtime.prepareImage(at: url, caption: caption) else { return }
            _ = try? await runtime.sendMedia(prepared, to: peer)
        case .document(let url):
            defer { try? FileManager.default.removeItem(at: url) }
            let type = UTTypeName.contentType(for: url)
            guard
                let prepared = try? await runtime.prepareFile(
                    at: url, fileName: url.lastPathComponent, contentType: type)
            else { return }
            _ = try? await runtime.sendMedia(prepared, to: peer)
        case .location(let name, let locality, let latitude, let longitude):
            _ = try? await runtime.sendLocation(
                LocationInfo(
                    latitude: latitude, longitude: longitude, accuracyMeters: 0, name: name.isEmpty ? nil : name,
                    address: locality.isEmpty ? nil : locality), to: peer)
        case .liveLocation(let duration):
            await startLiveLocation(to: peer, duration: duration)
        case .textSticker(let text):
            _ = try? await runtime.sendSticker(packId: "us2us.text", stickerId: 0, emoji: text, to: peer)
        case .photo, .file:
            // The design's sample content exists only in the fixtures.
            break
        }
    }

    func sendCurrentLocation(in chat: ChatID) async {
        guard let peer = AccountId(string: chat), let fix = try? await location.currentLocation() else { return }
        _ = try? await runtime.sendLocation(
            LocationInfo(
                latitude: fix.coordinate.latitude, longitude: fix.coordinate.longitude,
                accuracyMeters: Float(max(0, fix.horizontalAccuracy))), to: peer)
    }

    private func startLiveLocation(to peer: AccountId, duration: TimeInterval) async {
        guard let fix = try? await location.currentLocation(),
            let sessionId = try? await runtime.startLiveLocation(
                to: peer, latitude: fix.coordinate.latitude, longitude: fix.coordinate.longitude,
                accuracy: Float(max(0, fix.horizontalAccuracy)), duration: duration)
        else { return }
        liveSessions[sessionId] = (peer, sessionId)
        let ends = Date().addingTimeInterval(duration)
        try? await location.startLiveUpdates { [weak self] fix in
            guard let self else { return }
            if Date() >= ends {
                Task { await self.endLiveSession(sessionId) }
                return
            }
            Task {
                try? await self.runtime.updateLiveLocation(
                    sessionId, peer: peer, latitude: fix.coordinate.latitude, longitude: fix.coordinate.longitude,
                    accuracy: Float(max(0, fix.horizontalAccuracy)), heading: Float(fix.course),
                    speed: Float(fix.speed))
            }
        }
    }

    private func endLiveSession(_ sessionId: Data) async {
        guard let session = liveSessions.removeValue(forKey: sessionId) else { return }
        try? await runtime.stopLiveLocation(session.sessionId, peer: session.peer)
        if liveSessions.isEmpty { location.stopLiveUpdates() }
    }

    func resend(_ message: MessageID, in chat: ChatID) async {
        guard let peer = AccountId(string: chat), let id = LiveMapping.messageData(message) else { return }
        _ = try? await runtime.resend(id, peer: peer)
    }

    func updateGhost(_ ghost: GhostState, disappearing: DisappearingTimer, in chat: ChatID) async {
        guard let peer = AccountId(string: chat) else { return }
        let settings =
            ghost.isEnabled ? GhostSettings(enabled: true, timeoutSeconds: LiveMapping.seconds(ghost.period)) : nil
        try? await runtime.setConversationSettings(
            peer, ghost: settings, defaultExpireTimerSeconds: LiveMapping.seconds(disappearing))
    }

    func deleteForEveryone(_ message: MessageID, in chat: ChatID) async {
        guard let peer = AccountId(string: chat), let id = LiveMapping.messageData(message) else { return }
        try? await runtime.deleteForEveryone(id, peer: peer)
    }

    func deleteForMe(_ message: MessageID, in chat: ChatID) async {
        guard let peer = AccountId(string: chat), let id = LiveMapping.messageData(message) else { return }
        try? await runtime.deleteForMe(id, peer: peer)
    }

    func setReaction(_ emoji: String?, on message: MessageID, in chat: ChatID) async {
        guard let peer = AccountId(string: chat), let id = LiveMapping.messageData(message) else { return }
        try? await runtime.react(to: id, peer: peer, emoji: emoji)
    }

    func stopLiveLocation(_ message: MessageID, in chat: ChatID) async {
        guard let id = LiveMapping.messageData(message),
            let item = runtime.openMessages.first(where: { $0.messageId == id }),
            case .liveLocation(let live) = item.body
        else { return }
        if liveSessions[live.sessionId] != nil {
            await endLiveSession(live.sessionId)
        } else if let peer = AccountId(string: chat) {
            try? await runtime.stopLiveLocation(live.sessionId, peer: peer)
        }
    }

    func viewOnceMedia(_ message: MessageID, in chat: ChatID) async -> MediaSource? {
        guard let peer = AccountId(string: chat), let id = LiveMapping.messageData(message),
            let data = try? await runtime.openViewOnceData(id, peer: peer)
        else { return nil }
        return .memory(data)
    }

    func markViewOnceOpened(_ message: MessageID, in chat: ChatID) async {
        guard let peer = AccountId(string: chat), let id = LiveMapping.messageData(message) else { return }
        try? await runtime.closeViewOnce(id, peer: peer, file: nil)
    }

    func reportScreenshot(of message: MessageID, in chat: ChatID) async {
        // The runtime hears the system's screenshot notification itself and
        // posts the line in the open conversation (one line, not two).
    }

    // MARK: Verification

    func safetyNumber(for chat: ChatID) async -> SafetyNumberInfo? {
        guard let peer = AccountId(string: chat), let number = try? await runtime.safetyNumber(with: peer) else {
            return nil
        }
        let summary = self.chat(chat)
        let verified = summary?.peer.isVerified ?? false
        let record = verifications[chat]
        return SafetyNumberInfo(
            groups: number.groups, scannablePayload: number.scannable,
            verifiedAt: verified ? record?.date ?? Date(timeIntervalSince1970: 0) : nil,
            method: verified ? record?.method ?? .manual : nil,
            changedAt: runtime.safetyNumberChanges.contains(peer) ? now : nil)
    }

    func markVerified(_ chat: ChatID, method: VerificationMethod) async {
        guard let peer = AccountId(string: chat) else { return }
        if runtime.safetyNumberChanges.contains(peer) { try? await runtime.acceptSafetyNumberChange(peer) }
        do {
            try await runtime.setVerified(true, peer: peer)
            verifications[chat] = (now, method)
            chatCache = nil
        } catch {}
    }

    func clearVerification(_ chat: ChatID) async {
        guard let peer = AccountId(string: chat) else { return }
        try? await runtime.setVerified(false, peer: peer)
        verifications[chat] = nil
        chatCache = nil
    }

    func compareScannedSafetyCode(_ code: String, for chat: ChatID) async -> SafetyCodeComparison {
        guard let payload = SafetyQRCodec.decode(code) else { return .notASafetyCode }
        guard let peer = AccountId(string: chat), let number = try? await runtime.safetyNumber(with: peer) else {
            return .mismatch
        }
        return ((try? number.matches(scanned: payload)) ?? false) ? .match : .mismatch
    }

    func resolveContactCode(_ code: String) async -> ContactCodeResolution {
        guard let decoded = ContactCode.decode(code) else { return .notAContactCode }
        if decoded.username == ownUsername { return .ownCode }
        guard (try? NormalizedUsername(decoded.username)) != nil,
            let entry = try? await runtime.lookupAccount(username: decoded.username)
        else { return .unknown }
        if let fingerprint = decoded.keyFingerprint,
            !LiveMapping.fingerprintMatches(fingerprint, identityKey: entry.identityKey)
        {
            return .unknown
        }
        return .contact(username: decoded.username)
    }

    func addContact(fromScannedCode code: String) async -> ChatID? {
        guard let decoded = ContactCode.decode(code), decoded.username != ownUsername,
            let entry = try? await runtime.lookupAccount(username: decoded.username)
        else { return nil }
        let matches = decoded.keyFingerprint.map {
            LiveMapping.fingerprintMatches($0, identityKey: entry.identityKey)
        }
        // A code whose key is not the one the relay serves adds nobody.
        guard matches != false else { return nil }
        guard let peer = try? await runtime.startConversation(username: decoded.username) else { return nil }
        let id = peer.string
        // Verified face to face only when the code carried the key's
        // fingerprint and it matched; the store needs the contact's identity,
        // which it has only after the first exchange, so this may not stick yet.
        if matches == true, (try? await runtime.setVerified(true, peer: peer)) != nil {
            verifications[id] = (now, .faceToFace)
            chatCache = nil
        }
        return id
    }

    // MARK: Settings & backup

    var settings: AppSettings {
        let current = runtime.settings
        var settings = AppSettings()
        settings.presenceVisibility = current.presenceSharing ? .acceptedContacts : .nobody
        settings.readReceipts = current.readReceipts
        if case .notEnabled = runtime.quickUnlock {
            settings.faceIDEnabled = false
        } else {
            settings.faceIDEnabled = true
        }
        settings.defaultGhost = current.defaultGhost.flatMap {
            $0.enabled ? LiveMapping.ghostPeriod(seconds: $0.timeoutSeconds) : nil
        }
        settings.defaultDisappearing = LiveMapping.disappearing(seconds: current.defaultExpireTimerSeconds)
        settings.lastBackupAt = lastBackupAt
        return settings
    }

    func updateSettings(_ new: AppSettings) async {
        let old = settings
        if new.presenceVisibility != old.presenceVisibility {
            try? await runtime.setPresenceSharing(new.presenceVisibility == .acceptedContacts)
        }
        if new.readReceipts != old.readReceipts { try? await runtime.setReadReceipts(new.readReceipts) }
        if new.defaultGhost != old.defaultGhost || new.defaultDisappearing != old.defaultDisappearing {
            try? await runtime.setDefaults(
                ghost: new.defaultGhost.map { GhostSettings(enabled: true, timeoutSeconds: LiveMapping.seconds($0)) },
                expireTimerSeconds: LiveMapping.seconds(new.defaultDisappearing))
        }
        if new.lastBackupAt != old.lastBackupAt { lastBackupAt = new.lastBackupAt }
        chatCache = nil
    }

    func backupEstimate() async -> BackupEstimate {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        // The account backup holds the identity, the settings and the
        // contacts — never messages or media (PROTOCOL §5.5).
        return BackupEstimate(
            fileName: "us2us-\(formatter.string(from: now)).u2u", chatCount: runtime.chats.count, messageCount: 0,
            mediaBytes: 0, totalBytes: Int64(2_048 + 256 * runtime.chats.count))
    }

    func setBiometricUnlock(_ enabled: Bool, reason: String) async -> Bool {
        if !enabled {
            await runtime.disableQuickUnlock()
            return false
        }
        // Turning it on needs the code (the key is wrapped from the vault):
        // without it the setting stays as it is.
        if case .notEnabled = runtime.quickUnlock { return false }
        return true
    }

    func verifySecretCode(_ code: String) async -> UnlockAttemptResult {
        guard SecretCode.isWellFormed(code) else { return .malformed }
        do {
            try await runtime.verifyCode(code)
            return .unlocked
        } catch {
            return Self.attemptResult(error, status: unlockStatus)
        }
    }

    func changeSecretCode(current: String, new: String) async throws(ChangeSecretCodeError) {
        do {
            try await runtime.changeCode(current: current, new: new, confirmation: new)
        } catch VaultError.wrongCode {
            throw .wrongCurrentCode
        } catch {
            throw .failed
        }
    }

    func backupPasswordIsSecretCode(_ password: String) async -> Bool {
        // Only six-digit passwords are asked about, and the backup password
        // rules (≥ 10 characters) refuse those anyway; comparing with the
        // code would need a counted vault attempt, which this must never be.
        false
    }

    func exportBackup(
        _ contents: BackupContents, password: String, fileName: String,
        progress: @escaping @MainActor @Sendable (Double) -> Void
    ) async throws -> URL {
        progress(0.05)
        let ticker = Task { @MainActor in
            var fraction = 0.05
            while !Task.isCancelled, fraction < 0.9 {
                try? await Task.sleep(for: .milliseconds(80))
                fraction = min(0.9, fraction + 0.03)
                progress(fraction)
            }
        }
        defer { ticker.cancel() }
        let staged = try await runtime.exportBackup(password: password, confirmation: password)
        let named = staged.deletingLastPathComponent().appendingPathComponent(fileName)
        if named != staged {
            try? FileManager.default.removeItem(at: named)
            try FileManager.default.moveItem(at: staged, to: named)
        }
        progress(1)
        return named
    }

    func finishExport(at url: URL, saved: Bool) async {
        try? FileManager.default.removeItem(at: url)
        if saved { lastBackupAt = now }
    }

    func inspectBackup(at url: URL) async throws(BackupFileError) -> BackupFileInfo {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard let handle = try? FileHandle(forReadingFrom: url) else { throw .unreadable }
        let header = (try? handle.read(upToCount: 8)) ?? Data()
        try? handle.close()
        guard LiveMapping.looksLikeBackup(header) else { throw .notABackup }
        guard let copy = Self.copyIntoRestoreDirectory(url) else { throw .unreadable }
        let attributes = try? FileManager.default.attributesOfItem(atPath: url.path)
        let size = (attributes?[.size] as? NSNumber)?.int64Value ?? 0
        let created = (attributes?[.creationDate] as? Date) ?? (attributes?[.modificationDate] as? Date)
        return BackupFileInfo(url: copy, fileName: url.lastPathComponent, byteCount: size, createdAt: created)
    }

    nonisolated private static func copyIntoRestoreDirectory(_ url: URL) -> URL? {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("backup-import", isDirectory: true)
        try? FileManager.default.removeItem(at: directory)
        do {
            try FileManager.default.createDirectory(
                at: directory, withIntermediateDirectories: true,
                attributes: [.protectionKey: FileProtectionType.complete])
            let destination = directory.appendingPathComponent("restore.u2u")
            try FileManager.default.copyItem(at: url, to: destination)
            return destination
        } catch {
            return nil
        }
    }

    func openBackup(_ file: BackupFileInfo, password: String) async -> BackupOpenResult {
        let url = file.url
        let result: BackupOpenResult = await Task.detached(priority: .userInitiated) {
            guard let data = try? Data(contentsOf: url) else { return .damaged }
            do {
                _ = try AccountBackupFile.import(data, password: password)
                return .opened
            } catch AccountBackupError.wrongPassword {
                return .wrongPassword
            } catch {
                return .damaged
            }
        }.value
        if result == .opened { pendingRestore = (url, password) }
        return result
    }

    func restoreBackup(_ file: BackupFileInfo) -> AsyncThrowingStream<RestoreProgress, any Error> {
        // The backup was decrypted and checked in `openBackup`; the account
        // itself is restored with the new code (`completeRestore`), because
        // the vault on this phone is bound to that code. Here only the
        // stages the screen shows.
        let (stream, continuation) = AsyncThrowingStream<RestoreProgress, any Error>.makeStream()
        let task = Task { @MainActor in
            let stages: [(Double, RestoreProgress.Stage)] = [(0.2, .keys), (0.6, .messages), (1, .media)]
            for (fraction, stage) in stages {
                if Task.isCancelled {
                    continuation.finish(throwing: CancellationError())
                    return
                }
                continuation.yield(
                    RestoreProgress(
                        fraction: fraction, stage: stage, messagesDone: 0, messagesTotal: 0, mediaBytes: 0,
                        estimatedRemaining: (1 - fraction) * 2))
                try? await Task.sleep(for: .milliseconds(250))
            }
            continuation.finish()
        }
        continuation.onTermination = { termination in
            if case .cancelled = termination { task.cancel() }
        }
        return stream
    }

    func cancelRestore() async {
        if let pending = pendingRestore { try? FileManager.default.removeItem(at: pending.url) }
        pendingRestore = nil
    }
}

/// The content type of a picked file, for the runtime's metadata rules.
enum UTTypeName {
    static func contentType(for url: URL) -> String {
        let values = try? url.resourceValues(forKeys: [.contentTypeKey])
        return values?.contentType?.preferredMIMEType ?? "application/octet-stream"
    }
}
