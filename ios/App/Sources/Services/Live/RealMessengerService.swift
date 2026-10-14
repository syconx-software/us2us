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
/// * One network endpoint: the relay (`RelayEndpoint`). Besides it, MapKit
///   loads Apple's map tiles while the live-location map is on screen.
@Observable
@MainActor
final class RealMessengerService: MessengerService {
    /// The runtime's community service, mapped for the screens (one bridge per unlock).
    var communities: (any CommunitiesService)? {
        guard let service = runtime.communities else { return nil }
        if let bridge = communitiesBridge, bridge.service === service { return bridge }
        let bridge = RealCommunitiesService(service: service)
        communitiesBridge = bridge
        return bridge
    }
    @ObservationIgnored private var communitiesBridge: RealCommunitiesService?
    @ObservationIgnored let runtime: AppRuntime
    @ObservationIgnored private let location = LocationProvider()

    /// Counts the vaults this process has seen: bumped whenever this phone's
    /// vault is erased or replaced, so a backup exported from one vault is
    /// never recorded for the next (`forgetExportedBackups`).
    @ObservationIgnored private var vaultGeneration = 0
    /// Exported backup files whose share sheet is still open, with the vault
    /// they came from.
    @ObservationIgnored private var exportedBackups: [URL: Int] = [:]
    /// A backup saved while the vault's store was closed (the share sheet
    /// finished after the app had left the foreground and locked): recorded
    /// in the vault at the next unlock. Memory only; the time alone.
    @ObservationIgnored private var unrecordedBackup: (date: Date, vault: Int)?
    /// A picked backup whose password was checked (7.3), restored with the
    /// new code chosen in 2.2 (`completeRestore`). Memory only.
    @ObservationIgnored private var pendingRestore: (url: URL, password: String)?
    /// Why the last opened backup can never be restored, after it was
    /// dropped with its password: 2.3 says the same if asked again.
    @ObservationIgnored private var unrestorable: RestoreFailure?
    /// Decrypted full images by message id, the photos sent from here this
    /// session as they were sent, and the posters of videos sent from here
    /// (memory only; cleared on lock).
    private var media: [MessageID: Data] = [:]
    /// Photos and videos picked in “+” while they are prepared, one at a
    /// time: their bubbles show at once.
    @ObservationIgnored let outgoingMedia: OutgoingMedia
    @ObservationIgnored private var mediaLoading: Set<MessageID> = []
    /// Attachments of the open conversation the user asked to download, so
    /// the bubble shows its ring at once (the runtime reports the transfer
    /// only once it runs).
    private var downloads: [MessageID: LiveDownload] = [:]
    /// Photos, videos and files the media list (6.1) returned, by message
    /// id, so their bytes load also while their chat is not open. Memory
    /// only; cleared with the other caches.
    @ObservationIgnored private var listedMedia: [Data: (peer: AccountId, item: MessageItem)] = [:]
    /// Live-location sessions this device runs, by message id.
    @ObservationIgnored private var liveSessions: [Data: (peer: AccountId, sessionId: Data)] = [:]

    @ObservationIgnored private var chatCache: (key: [Us2usRuntime.ChatSummary], presence: [AccountId: PresenceStatus], sharing: Bool, changed: Set<AccountId>, searched: [ChatID: LiveAvatar], value: [ChatSummary])?
    @ObservationIgnored private var requestCache: (key: [Us2usRuntime.ChatSummary], value: [MessageRequest])?
    @ObservationIgnored private var blockedCache: (key: [ContactInfo], value: [BlockedPerson])?
    @ObservationIgnored private var messageCache:
        (
            peer: AccountId?, key: [MessageItem], media: Int, downloads: [MessageID: LiveDownload],
            outgoing: [OutgoingMediaItem], readReceipts: Bool, value: [Message]
        )?
    /// The live-location map's tick times and snapshot (memory only).
    @ObservationIgnored var liveMapCache = LiveMapCache()
    /// Accounts the exact lookups of this session found, by normalized
    /// username: what a 4.2 result's picture is fetched for
    /// (RealMessengerService+Avatars.swift). Memory only; cleared on lock.
    @ObservationIgnored var avatarAccounts: [String: AccountId] = [:]
    /// Search copies found this session, by chat: what the user's own
    /// message request draws (§8.9). Memory only; cleared on lock.
    var searchedAvatars: [ChatID: LiveAvatar] = [:]
    /// What 4.4 › “Profil resmini göster” found this session, by request:
    /// shown again without asking the relay a second time. Memory only;
    /// cleared on lock.
    @ObservationIgnored var revealedAvatars: [ChatID: LiveAvatar] = [:]

    init(runtime: AppRuntime) {
        self.runtime = runtime
        outgoingMedia = OutgoingMedia(steps: RealMessengerService.mediaSteps(runtime))
        outgoingMedia.onSent = { [weak self] item in self?.keepImage(of: item) }
    }

    /// A picked photo or video goes out through the runtime: a photo
    /// re-encoded (≤ 2048 px JPEG, no metadata) with its caption, a video
    /// re-exported (≤ 720p, no metadata), then stored and queued — under the
    /// id its bubble already has.
    private static func mediaSteps(_ runtime: AppRuntime) -> OutgoingMedia.Steps {
        OutgoingMedia.Steps(
            preview: { url, kind in
                if kind == .photo { return await PhotoPreviewReader.read(url) }
                return await VideoPreviewReader.read(url)
            },
            prepare: { url, kind, caption in
                if kind == .photo { return try await runtime.prepareImage(at: url, caption: caption) }
                return try await runtime.prepareVideo(at: url)
            },
            send: { prepared, id, chat in
                guard let peer = AccountId(string: chat) else { throw OutgoingMedia.Failure.unknownChat }
                _ = try await runtime.sendMedia(prepared, to: peer, messageId: id)
            },
            discardSource: { url in SystemPickers.discardPicked(url) },
            discardPrepared: { url in _ = try? FileManager.default.removeItem(at: url) })
    }

    /// An item was stored: a photo as it was sent, a video's poster, stays
    /// for the stored bubble (memory only, like the photos), which would
    /// otherwise show the small inline thumbnail until the upload finished.
    private func keepImage(of item: OutgoingMediaItem) {
        guard let image = item.keptImage, runtime.openPeer?.string == item.chat else { return }
        media[item.id] = image
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
        // The directory answers only authenticated accounts (§4.4): whether
        // the name is free shows at “Kurulumu tamamla” (409), not while typing.
        return .meetsRules
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
        // A new vault: a backup of an erased one is never recorded for it.
        forgetExportedBackups()
        do {
            try await runtime.createAccount(
                username: username, displayName: "", code: secretCode, confirmation: secretCode)
        } catch {
            // Registered and saved, then something failed: the account is
            // kept, locked under this code; 2.3 sends the user to the lock.
            if runtime.lockState == .locked, runtime.keptAccountAfterFailedSetup {
                throw SetupInterruption.accountKept
            }
            throw Self.creationFailure(error)
        }
        if faceIDEnabled { try? await runtime.enableQuickUnlock(code: secretCode) }
    }

    /// The relay refused the name (409 `username_taken`): nothing was kept,
    /// so 2.3 offers another name. Any other error passes through.
    nonisolated static func creationFailure(_ error: any Error) -> any Error {
        if let relay = error as? RelayError, relay.code == .usernameTaken { return AccountCreationFailure.usernameTaken }
        return error
    }

    func completeRestore(secretCode: String, faceIDEnabled: Bool) async throws {
        guard let pending = pendingRestore else { throw unrestorable ?? RestoreFailure.backupDamaged }
        // 3.3 › 7.3 inside the lock: the runtime replaces the locked vault,
        // and only once the backup is fully valid and the relay still
        // accepts its token (PROTOCOL §5.5); before that nothing is erased.
        let replacing = runtime.lockState == .locked
        defer {
            // The vault this phone held is gone once the restore erased it
            // (it succeeded, failed after erasing, or kept the restored
            // account): what the service kept of it goes too.
            if replacing, runtime.lockState != .locked || runtime.keptAccountAfterFailedSetup {
                forgetReplacedVault()
            }
        }
        // A backup exported before is never recorded for the restored
        // account: the restore rotates the token, so that file stops working.
        forgetExportedBackups()
        do {
            try await runtime.restore(
                backupFile: pending.url, password: pending.password, code: secretCode, confirmation: secretCode,
                replacingLockedVault: replacing)
        } catch {
            if runtime.lockState == .locked, runtime.keptAccountAfterFailedSetup {
                // Restored and saved, then something failed: the restored
                // account stays, locked under the new code.
                dropPendingRestore()
                throw SetupInterruption.accountKept
            }
            // A dead or damaged backup is said as such on 2.3, instead of
            // asking to check the connection.
            let failure = Self.restoreFailure(for: error)
            if let failure {
                dropPendingRestore()
                unrestorable = failure
            }
            if replacing, runtime.lockState == .locked, failure != nil {
                // Refused before anything was erased: the vault this phone
                // held is untouched, and only the lock can go on from here.
                throw SetupInterruption.previousVaultKept
            }
            if let failure { throw failure }
            // No connection, the relay busy: nothing changed, 2.3 offers to
            // try again (with the backup still opened).
            throw error
        }
        dropPendingRestore()
        if faceIDEnabled { try? await runtime.enableQuickUnlock(code: secretCode) }
    }

    /// The opened backup is not needed any more (restored, or it can never
    /// be): its protected copy and its password go.
    private func dropPendingRestore() {
        if let pending = pendingRestore { try? FileManager.default.removeItem(at: pending.url) }
        pendingRestore = nil
    }

    /// A restore replaced the vault this phone held: what the service kept
    /// of it (live-location updates, preview and recording files, caches)
    /// goes as well.
    private func forgetReplacedVault() {
        location.stopLiveUpdates()
        ProtectedPreviewFiles.removeAll()
        VoiceRecordingFiles.removeAll()
        outgoingMedia.removeAll()
        liveSessions = [:]
        clearCaches()
        forgetExportedBackups()
    }

    /// The restore failures that trying again cannot fix; `nil` for the rest
    /// (no connection, the relay busy), after which nothing has changed and
    /// 2.3 offers to try again.
    nonisolated static func restoreFailure(for error: any Error) -> RestoreFailure? {
        if let error = error as? RuntimeError {
            switch error {
            case .backupRevoked: return .backupNoLongerValid
            case .unreadableBackup: return .backupDamaged
            default: return nil
            }
        }
        if let error = error as? RelayError { return refusesBackup(error) ? .backupNoLongerValid : nil }
        if let error = error as? BackupRestoreError { return error == .backupNoLongerValid ? .backupNoLongerValid : nil }
        if error is AccountBackupError || (error as? MessengerError) == .invalidBackup { return .backupDamaged }
        return nil
    }

    /// 7.3's answer for a backup that `runtime.checkBackup` refused.
    nonisolated static func openResult(for error: any Error) -> BackupOpenResult {
        if let error = error as? AccountBackupError, error == .wrongPassword { return .wrongPassword }
        switch restoreFailure(for: error) {
        case .backupNoLongerValid?: return .noLongerValid
        case .backupDamaged?: return .damaged
        case nil: return .unverified
        }
    }

    /// The relay's answers to a restore's token rotation that mean the
    /// backup's token is revoked for good (the same answers the runtime
    /// counts as “account gone”): 401 / 404.
    nonisolated static func refusesBackup(_ error: RelayError) -> Bool {
        switch error {
        case .server(status: 401, _, _), .server(status: 404, _, _), .notAuthenticated: true
        default: false
        }
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
            // No error but not unlocked: the app left the foreground while
            // the code was checked (it locks again at once), or another
            // unlock was running. The code was not wrong.
            guard runtime.lockState == .unlocked else { return .interrupted }
            await recordUnrecordedBackup()
            return .unlocked
        } catch {
            let result = Self.attemptResult(error, status: unlockStatus)
            // The 10th wrong code erased everything (the runtime already
            // did): what the service kept goes too, and the lock says so.
            if result == .wiped { forgetErasedVault() }
            return result
        }
    }

    /// Only a code the vault rejected is a wrong code. A Secure Enclave or
    /// Keychain error, a store that did not open, a missing device secret or
    /// hardware key are never counted (PROTOCOL §5.2 › Errors) and must not
    /// read as “Kod yanlış”: the user would retype a right code, or give up
    /// on a vault a retry would open.
    static func attemptResult(_ error: any Error, status: UnlockStatus) -> UnlockAttemptResult {
        switch error {
        case UnlockError.vault(.lockedOut(let until)): .throttled(until: until)
        case UnlockError.vault(.malformedCode): .malformed
        case UnlockError.vault(.wrongCode): .wrongCode(status)
        // The optional wipe fired: everything is gone, which is no “Kod yanlış”.
        case UnlockError.vault(.wiped), VaultError.wiped: .wiped
        case UnlockError.vault(.deviceSecretMissing), UnlockError.vault(.corrupted): .vaultUnusable
        default: .vaultUnavailable
        }
    }

    func unlockWithBiometrics(reason: String, cancelTitle: String) async -> Bool {
        do {
            try await runtime.unlockWithBiometrics()
            guard runtime.lockState == .unlocked else { return false }
            await recordUnrecordedBackup()
            return true
        } catch {
            return false
        }
    }

    func eraseEverything() async {
        forgetErasedVault()
        await runtime.eraseEverything()
    }

    /// Everything the service keeps beside the runtime goes with an erased
    /// vault (“Her şeyi sil”, or the optional wipe after 10 wrong codes):
    /// live-location updates, preview, recording and picked files, an opened
    /// backup with its password, caches.
    private func forgetErasedVault() {
        location.stopLiveUpdates()
        ProtectedPreviewFiles.removeAll()
        VoiceRecordingFiles.removeAll()
        outgoingMedia.removeAll()
        SystemPickers.removeAllPicked()
        liveSessions = [:]
        dropPendingRestore()
        unrestorable = nil
        clearCaches()
        forgetExportedBackups()
    }

    func deleteAccountOnRelay() async throws {
        try await runtime.deleteAccountOnRelay()
    }

    // MARK: Lifecycle

    func scenePhaseChanged(_ phase: MessengerScenePhase) {
        switch phase {
        case .active:
            runtime.scenePhaseChanged(.active)
            outgoingMedia.didBecomeActive()
        case .inactive: runtime.scenePhaseChanged(.inactive)
        case .background:
            runtime.scenePhaseChanged(.background)
            clearCaches()
            outgoingMedia.didEnterBackground()
        }
    }

    private func clearCaches() {
        chatCache = nil
        requestCache = nil
        blockedCache = nil
        messageCache = nil
        liveMapCache = LiveMapCache()
        media = [:]
        mediaLoading = []
        downloads = [:]
        listedMedia = [:]
        avatarAccounts = [:]
        searchedAvatars = [:]
        revealedAvatars = [:]
    }

    // MARK: Restore access (§5.5 step 3.4)

    var restoreAccessPending: Bool { runtime.tokenRotationPending }

    func checkRestoreAccess() async -> RestoreAccessCheck {
        Self.restoreAccess(await runtime.checkPendingTokenRotation())
    }

    nonisolated static func restoreAccess(_ check: TokenRotationCheck) -> RestoreAccessCheck {
        switch check {
        case .settled: .closed
        case .unanswered: .unanswered
        case .refused: .refused
        }
    }

    // MARK: Line & chats

    var connection: ConnectionState { LiveMapping.connection(.init(runtime.connection)) }

    var chats: [ChatSummary] {
        let source = runtime.chats
        let presence = runtime.presence
        let sharing = runtime.settings.presenceSharing
        let changed = runtime.safetyNumberChanges
        let searched = searchedAvatars
        if let cache = chatCache, cache.key == source, cache.presence == presence, cache.sharing == sharing,
            cache.changed == changed, cache.searched == searched
        {
            return cache.value
        }
        let own = runtime.ownAccountId
        let date = now
        let value = source.map { summary in
            var record = LiveChatRecord(summary, ownAccount: own)
            if changed.contains(summary.id) { record.safetyNumberChanged = true }
            if record.requestState == .outgoingRequest, let copy = searched[record.peer] { record.avatar = copy }
            return LiveMapping.chatSummary(
                record, presence: presence[summary.id].map(LivePresence.init), now: date, ownSharing: sharing)
        }
        chatCache = (source, presence, sharing, changed, searched, value)
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
        rememberLookup(username, account: entry.account)
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
        try? await block(peer)
    }

    func delete(_ request: MessageRequest) async {
        guard let peer = AccountId(string: request.id) else { return }
        try? await runtime.declineRequest(peer)
    }

    // MARK: Blocking and reports

    func block(chat: ChatID) async -> Bool {
        guard let peer = AccountId(string: chat) else { return false }
        do {
            try await block(peer)
            return true
        } catch {
            return false
        }
    }

    /// A live location shared with the person ends first: its stop is
    /// queued (the block then cancels it with everything else waiting for
    /// them), the bubble here stops, and with the last share so do the
    /// location updates — nothing keeps tracking for someone blocked.
    private func block(_ peer: AccountId) async throws {
        for (sessionId, session) in liveSessions where session.peer == peer {
            await endLiveSession(sessionId)
        }
        try await runtime.block(peer)
    }

    func unblock(chat: ChatID) async -> Bool {
        guard let peer = AccountId(string: chat) else { return false }
        do {
            try await runtime.unblock(peer)
            return true
        } catch {
            return false
        }
    }

    /// The runtime's blocked contacts, also those without a conversation.
    var blockedPeople: [BlockedPerson] {
        let source = runtime.blockedContacts
        if let cache = blockedCache, cache.key == source { return cache.value }
        let value = LiveMapping.blockedPeople(source.map(LiveBlockedRecord.init))
        blockedCache = (source, value)
        return value
    }

    /// The chat id is the account: the runtime unblocks it with a
    /// conversation or without one.
    func unblock(_ person: BlockedPerson) async -> Bool {
        await unblock(chat: person.id)
    }

    func reportSubject(for chat: ChatID) -> ReportSubject? {
        guard let summary = runtime.chats.first(where: { $0.id.string == chat }) else { return nil }
        return LiveMapping.reportSubject(LiveChatRecord(summary, ownAccount: runtime.ownAccountId))
    }

    func reportSubject(for request: MessageRequest) -> ReportSubject? {
        guard let summary = runtime.requests.first(where: { $0.id.string == request.id }) else { return nil }
        return LiveMapping.reportSubject(LiveChatRecord(summary, ownAccount: runtime.ownAccountId))
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
        await loadRequestAvatar(of: peer)
    }

    func conversationDisappeared(_ chat: ChatID) {
        outgoingMedia.conversationClosed(chat)
        guard runtime.openPeer?.string == chat else { return }
        runtime.closeConversation()
        liveMapCache = LiveMapCache()
        media = [:]
        mediaLoading = []
        downloads = [:]
    }

    func messages(in chat: ChatID) -> [Message] {
        guard let peer = runtime.openPeer, peer.string == chat else { return [] }
        let source = runtime.openMessages
        let downloads = currentDownloads()
        let outgoing = outgoingMedia.items(in: chat)
        // Read receipts are mutual: with them off, a voice message never
        // shows that the other side played it either.
        let readReceipts = runtime.settings.readReceipts
        if let cache = messageCache, cache.peer == peer, cache.key == source, cache.media == media.count,
            cache.downloads == downloads, cache.outgoing == outgoing, cache.readReceipts == readReceipts
        {
            return cache.value
        }
        let own = runtime.ownAccountId
        let records = source.map { LiveMessageRecord($0, ownAccount: own) }
        let peerName = self.chat(chat)?.peer.username ?? ""
        let stored = LiveMapping.messages(
            records, ownUsername: ownUsername, peerUsername: peerName, now: now, media: media, downloads: downloads,
            showsPeerPlayback: readReceipts)
        // Photos and videos picked here and still being prepared show where
        // they were picked, in the order picked.
        let value = OutgoingMedia.merge(outgoing, into: stored, landed: outgoingMedia.landed)
        messageCache = (peer, source, media.count, downloads, outgoing, readReceipts, value)
        loadMedia(for: source, peer: peer)
        settleDownloads(in: source)
        noteLiveTicks(in: source)
        outgoingMedia.settle(shown: stored)
        return value
    }

    /// What is downloading: the user's requests and the runtime's progress.
    private func currentDownloads() -> [MessageID: LiveDownload] {
        var result = downloads
        for (id, fraction) in runtime.downloadProgress {
            result[LiveMapping.messageID(id), default: LiveDownload(fraction: nil)].fraction = fraction
        }
        return result
    }

    /// Requested downloads that finished (or can no longer finish) are no
    /// longer shown as such.
    private func settleDownloads(in items: [MessageItem]) {
        guard !downloads.isEmpty else { return }
        for id in downloads.keys {
            let attachment = items.first { LiveMapping.messageID($0.messageId) == id }.flatMap(Self.attachment(of:))
            switch attachment?.availability {
            case .remote?, .transferring?: continue
            default:
                // Not in this pass: the cache was just filled from these values.
                Task { @MainActor [weak self] in self?.downloads[id] = nil }
            }
        }
    }

    private static func attachment(of item: MessageItem) -> AttachmentItem? {
        guard !item.viewOnce, case .attachment(let attachment) = item.body else { return nil }
        return attachment
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
                    guard let self, self.runtime.openPeer == peer else { return }
                    guard let data else {
                        // Not loaded: the next change of the conversation tries again.
                        self.mediaLoading.remove(id)
                        return
                    }
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

    // MARK: Media of the open conversation

    func mediaPayload(_ message: MessageID, in chat: ChatID) async -> MediaPayload? {
        guard let peer = AccountId(string: chat), let id = LiveMapping.messageData(message),
            let attachment = openAttachment(id, peer: peer)
        else { return nil }
        // A “voice message” far larger than any recording is never decrypted
        // into memory (nor downloaded for it): it shows as unplayable.
        if attachment.kind == .voice, attachment.size > VoiceNoteRules.maximumPlayableBytes { return nil }
        let isImage = attachment.kind == .image || attachment.kind == .stickerImage
        if isImage, let data = media[message] {
            return MediaPayload(data: data, contentType: attachment.contentType, fileName: attachment.fileName)
        }
        guard await waitUntilStored(id, peer: peer),
            let data = try? await runtime.mediaData(for: id, peer: peer)
        else { return nil }
        // Photos stay for the bubble too; video and file bytes belong to
        // the player or the preview that asked, and go with it.
        if isImage, runtime.openPeer == peer { media[message] = data }
        return MediaPayload(data: data, contentType: attachment.contentType, fileName: attachment.fileName)
    }

    func downloadMedia(_ message: MessageID, in chat: ChatID) async {
        guard let peer = AccountId(string: chat), let id = LiveMapping.messageData(message),
            let attachment = openAttachment(id, peer: peer)
        else { return }
        switch attachment.availability {
        case .remote, .unavailable:
            if downloads[message] == nil { downloads[message] = LiveDownload(fraction: nil) }
            try? await runtime.download(id, peer: peer)
        case .local, .transferring, .heldUntilAccepted:
            break
        }
    }

    /// The attachment of a message in the open conversation, or of one the
    /// media list returned (never view-once).
    private func openAttachment(_ id: Data, peer: AccountId) -> AttachmentItem? {
        if runtime.openPeer == peer, let item = runtime.openMessages.last(where: { $0.messageId == id }) {
            return Self.attachment(of: item)
        }
        guard let listed = listedMedia[id], listed.peer == peer else { return nil }
        return Self.attachment(of: listed.item)
    }

    /// A message as it is now: in the open conversation, or — an item of
    /// the media list outside it — read again from storage.
    private func currentItem(_ id: Data, peer: AccountId) async -> MessageItem? {
        if runtime.openPeer == peer, let item = runtime.openMessages.last(where: { $0.messageId == id }) {
            return item
        }
        guard listedMedia[id]?.peer == peer, let item = try? await runtime.messageItem(id, peer: peer) else {
            return nil
        }
        listedMedia[id] = (peer, item)
        return item
    }

    /// Waits until the attachment is stored on this phone, downloading it
    /// first when it is still on the relay. `false` when it never can be
    /// (held, expired, failed, gone), the conversation closed (an item of
    /// the media list is followed in storage instead), or the calling task
    /// was cancelled.
    private func waitUntilStored(_ id: Data, peer: AccountId) async -> Bool {
        let message = LiveMapping.messageID(id)
        var requested = false
        let deadline = Date().addingTimeInterval(15 * 60)
        while !Task.isCancelled, Date() < deadline {
            guard let item = await currentItem(id, peer: peer), let attachment = Self.attachment(of: item) else {
                return false
            }
            switch attachment.availability {
            case .local:
                return true
            case .transferring where item.direction == .outgoing:
                // Uploading: the file is here.
                return true
            case .remote where !requested:
                requested = true
                if downloads[message] == nil { downloads[message] = LiveDownload(fraction: nil) }
                try? await runtime.download(id, peer: peer)
            case .heldUntilAccepted, .unavailable:
                return false
            case .remote, .transferring:
                break
            }
            try? await Task.sleep(for: .milliseconds(250))
        }
        return false
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
            // Its bubble is up at once (“Hazırlanıyor”); preparing and sending follow.
            outgoingMedia.send(
                url, as: .photo, caption: PickedMediaPlan.usableCaption(caption), in: chat,
                disappearing: self.chat(chat)?.disappearing ?? .off)
        case .video(let url):
            outgoingMedia.send(url, as: .video, in: chat, disappearing: self.chat(chat)?.disappearing ?? .off)
        case .document(let url):
            defer { SystemPickers.discardPicked(url) }
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
        case .photo, .file:
            // The design's sample content exists only in the fixtures.
            break
        }
    }

    /// A built-in sticker: `Sticker { pack_id, sticker_id, emoji, lang }`
    /// inside the E2EE message (the relay never learns which). The recents
    /// (ids only) go to the vault's encrypted runtime state.
    func send(sticker: BrandStickerSelection, replyTo: QuotedMessage?, in chat: ChatID) async {
        guard let peer = AccountId(string: chat) else { return }
        var quote: QuoteInfo?
        if let replyTo, let target = replyTo.messageID.flatMap(LiveMapping.messageData) {
            quote = QuoteInfo(targetMessageId: target, previewText: Self.quoteText(replyTo))
        }
        let sent = try? await runtime.sendSticker(
            packId: sticker.sticker.packID, stickerId: sticker.sticker.id, emoji: sticker.sticker.emoji,
            lang: sticker.wireLanguage, quote: quote, to: peer)
        guard sent != nil else { return }
        // One step inside the engine: two stickers sent close together both land.
        try? await runtime.noteRecentSticker(sticker.id)
    }

    func recentStickers() async -> [String] {
        (try? await runtime.recentStickers()) ?? []
    }

    /// The recording goes to the runtime from memory: written to a staging
    /// file (complete protection) that storing it encrypts and deletes at
    /// once, under a bubble that shows at once; the outbox uploads and sends
    /// it (from a protected staging copy of its own, deleted after the
    /// upload). `false` when it could not be stored.
    @discardableResult
    func send(voice: VoiceRecording, replyTo: QuotedMessage?, in chat: ChatID) async -> Bool {
        guard let peer = AccountId(string: chat) else { return false }
        var quote: QuoteInfo?
        if let replyTo, let target = replyTo.messageID.flatMap(LiveMapping.messageData) {
            quote = QuoteInfo(targetMessageId: target, previewText: Self.quoteText(replyTo))
        }
        guard
            let prepared = try? await runtime.prepareVoiceNote(
                voice.audio, durationMs: voice.durationMilliseconds, waveform: voice.waveform)
        else { return false }
        do {
            _ = try await runtime.sendMedia(prepared, to: peer, quote: quote)
            return true
        } catch {
            // Not stored (locked meanwhile, a refused contact): the staged copy goes now.
            try? FileManager.default.removeItem(at: prepared.file)
            return false
        }
    }

    func markVoicePlayed(_ message: MessageID, in chat: ChatID) async {
        guard let peer = AccountId(string: chat), let id = LiveMapping.messageData(message) else { return }
        try? await runtime.markVoiceNotePlayed(id, peer: peer)
    }

    // MARK: Saved media and the media list (PROTOCOL §8.4)

    func markSaved(_ id: MessageID, in chat: ChatID) async {
        guard let peer = AccountId(string: chat), let message = LiveMapping.messageData(id) else { return }
        // View-once, disappearing and deleted items are refused by the runtime.
        try? await runtime.markMediaSaved(message, peer: peer)
    }

    func media(in chat: ChatID, kind: ChatMediaKind, before: MessageID?, limit: Int) async -> [Message] {
        guard let peer = AccountId(string: chat), limit > 0 else { return [] }
        var cursor: Data?
        if let before {
            guard let id = LiveMapping.messageData(before) else { return [] }
            cursor = id
        }
        guard
            let items = try? await runtime.mediaMessages(
                with: peer, kind: Us2usRuntime.MediaKind(kind), before: cursor, limit: limit)
        else { return [] }
        for item in items { listedMedia[item.messageId] = (peer, item) }
        let own = runtime.ownAccountId
        return LiveMapping.messages(
            items.map { LiveMessageRecord($0, ownAccount: own) }, ownUsername: ownUsername,
            peerUsername: self.chat(chat)?.peer.username ?? "", now: now, media: media, downloads: currentDownloads(),
            showsPeerPlayback: runtime.settings.readReceipts)
    }

    func mediaRevision(in chat: ChatID) -> Int {
        guard let peer = AccountId(string: chat) else { return 0 }
        return runtime.messageRevisions[peer] ?? 0
    }

    /// Every bubble first, in the order picked; each picked copy goes to its
    /// bubble as soon as the picker has handed it over (a long or iCloud
    /// item takes a while), and `OutgoingMedia` prepares and stores them one
    /// at a time. The design's pictures (fixtures) have no place here.
    func send(pickedMedia messages: [PickedMediaMessage], in chat: ChatID) async {
        let disappearing = self.chat(chat)?.disappearing ?? .off
        let outgoing = outgoingMedia
        for message in messages {
            guard case .copy(let copy) = message.item.source else { continue }
            let id = outgoing.begin(message.item.kind, caption: message.caption, in: chat, disappearing: disappearing)
            Task {
                let source = await copy.value
                outgoing.provide(source, for: id)
            }
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
        // A photo or video whose preparing failed is prepared again (it never reached the runtime).
        if outgoingMedia.resend(message) { return }
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
        // A photo or video still being prepared never left the phone: it just goes.
        if outgoingMedia.remove(message) { return }
        guard let peer = AccountId(string: chat), let id = LiveMapping.messageData(message) else { return }
        try? await runtime.deleteForEveryone(id, peer: peer)
    }

    func deleteForMe(_ message: MessageID, in chat: ChatID) async {
        if outgoingMedia.remove(message) { return }
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
        // The verification, its date and its method are stored with the key
        // (they survive relaunches and go when the key changes). A
        // verification without a record shows no date, never a made-up one.
        var verified = false
        var changed = false
        let trust = try? await runtime.identityTrust(for: peer)
        switch trust {
        case .trusted(_, let isVerified)?: verified = isVerified
        case .changed?: changed = true
        case .unknown?, nil: break
        }
        var record: IdentityVerification?
        if verified { record = try? await runtime.identityVerification(for: peer) }
        return SafetyNumberInfo(
            groups: number.groups, scannablePayload: number.scannable, verifiedAt: record?.verifiedAt,
            method: record.map { Self.verificationMethod($0.method) }, changedAt: changed ? now : nil,
            isVerifiedWithoutRecord: verified && record == nil)
    }

    private static func verificationMethod(_ method: IdentityVerificationMethod) -> VerificationMethod {
        switch method {
        case .manual: .manual
        case .qrCode: .faceToFace
        }
    }

    func markVerified(_ chat: ChatID, comparing number: Data) async -> Bool {
        guard let peer = AccountId(string: chat) else { return false }
        return (try? await runtime.markVerified(peer, comparedNumber: number)) ?? false
    }

    func clearVerification(_ chat: ChatID) async {
        guard let peer = AccountId(string: chat) else { return }
        try? await runtime.clearVerification(peer)
    }

    func verifyScannedSafetyCode(_ code: String, for chat: ChatID) async -> SafetyCodeComparison {
        guard let payload = SafetyQRCodec.decode(code) else { return .notASafetyCode }
        guard let peer = AccountId(string: chat) else { return .mismatch }
        // Compared with the current number and verified in one step.
        return ((try? await runtime.verifySafetyNumber(peer, scanned: payload)) ?? false) ? .match : .mismatch
    }

    func acceptSafetyNumberChange(_ chat: ChatID, showing number: Data) async -> Bool {
        guard let peer = AccountId(string: chat) else { return false }
        return (try? await runtime.acceptSafetyNumberChange(peer, shownNumber: number)) ?? false
    }

    func resolveContactCode(_ code: String) async -> ContactCodeResolution {
        guard let decoded = ContactCode.decode(code) else { return .notAContactCode }
        if decoded.username == ownUsername { return .ownCode }
        guard (try? NormalizedUsername(decoded.username)) != nil else { return .unknown }
        let found: AppRuntime.DirectoryEntry?
        do {
            found = try await runtime.lookupAccount(username: decoded.username)
        } catch {
            // No answer is not “nobody found”: the user may well exist.
            return .lookupFailed
        }
        guard let entry = found else { return .unknown }
        if let fingerprint = decoded.keyFingerprint {
            // The relay must serve the key the code commits to: another key
            // is what a relay swapping keys looks like, never an unknown
            // code (PROTOCOL §5 › Contact code) …
            guard LiveMapping.fingerprintMatches(fingerprint, identityKey: entry.identityKey) else {
                return .keyMismatch
            }
            // … and this phone must not hold another key for the contact.
            if let stored = storedIdentityKey(of: entry.account),
                !LiveMapping.fingerprintMatches(fingerprint, identityKey: stored)
            {
                return .keyMismatch
            }
        }
        return .contact(username: decoded.username)
    }

    /// The contact's current identity key as this phone holds it, if any.
    private func storedIdentityKey(of peer: AccountId) -> Data? {
        (runtime.chats + runtime.requests).first { $0.contact.account == peer }?.contact.identityKey
    }

    func addContact(fromScannedCode code: String) async -> ContactCodeAddition {
        guard let decoded = ContactCode.decode(code), decoded.username != ownUsername,
            let entry = try? await runtime.lookupAccount(username: decoded.username)
        else { return .failed }
        guard let fingerprint = decoded.keyFingerprint else {
            // A code without the key's fingerprint (older versions) names the
            // user but proves nothing about the key: added, not verified.
            guard let peer = try? await runtime.startConversation(username: decoded.username) else { return .failed }
            return .added(peer.string)
        }
        // The relay must serve the very key the code commits to; otherwise
        // nobody is added.
        guard LiveMapping.fingerprintMatches(fingerprint, identityKey: entry.identityKey) else { return .mismatch }
        guard let peer = try? await runtime.startConversation(username: decoded.username) else { return .failed }
        // The conversation is with the account whose key was checked, or
        // nothing is verified.
        guard peer == entry.account else { return .mismatch }
        do {
            // Pins that key: a different one later — from a bundle or a
            // message — is a safety-number change, so a relay that swaps
            // keys at first contact is caught.
            let verified = try await runtime.verifyContactCode(peer, identityKey: entry.identityKey)
            return verified ? .verified(peer.string) : .mismatch
        } catch {
            return .added(peer.string)
        }
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
        // Kept in the vault's encrypted runtime state: it survives relaunches.
        settings.lastBackupAt = runtime.lastBackupAt
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
        // `lastBackupAt` is not a preference: only a saved backup sets it
        // (`finishExport`).
        chatCache = nil
    }

    func backupEstimate() async -> BackupEstimate {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        // The account backup holds the account, its identity key and the
        // contacts — never messages, media or settings (PROTOCOL §5.5).
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

    /// Quick unlock is off, and this phone's biometrics can be used now:
    /// turning it on needs the code once. Nothing enrolled or locked out:
    /// `false`, so 7.1 says “Face ID açılamadı.” at once instead of asking
    /// for a code that cannot help.
    var biometricUnlockNeedsCode: Bool {
        guard case .notEnabled = runtime.quickUnlock else { return false }
        return runtime.quickUnlockAvailable
    }

    func enableBiometricUnlock(code: String) async -> UnlockAttemptResult {
        guard SecretCode.isWellFormed(code) else { return .malformed }
        do {
            try await runtime.enableQuickUnlock(code: code)
            return .unlocked
        } catch {
            // Biometrics not usable right now (nothing enrolled, locked out)
            // is refused before the code is checked, and maps to
            // `.vaultUnavailable` like any other failure that is no wrong code.
            let result = Self.attemptResult(error, status: unlockStatus)
            if result == .wiped { forgetErasedVault() }
            return result
        }
    }

    func verifySecretCode(_ code: String) async -> UnlockAttemptResult {
        guard SecretCode.isWellFormed(code) else { return .malformed }
        do {
            try await runtime.verifyCode(code)
            return .unlocked
        } catch {
            let result = Self.attemptResult(error, status: unlockStatus)
            if result == .wiped { forgetErasedVault() }
            return result
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
        let vault = vaultGeneration
        let staged = try await runtime.exportBackup(password: password, confirmation: password)
        let named = staged.deletingLastPathComponent().appendingPathComponent(fileName)
        if named != staged {
            try? FileManager.default.removeItem(at: named)
            try FileManager.default.moveItem(at: staged, to: named)
        }
        exportedBackups[named] = vault
        progress(1)
        return named
    }

    func finishExport(at url: URL, saved: Bool) async {
        try? FileManager.default.removeItem(at: url)
        let vault = exportedBackups.removeValue(forKey: url)
        // Only a file that was really saved counts, and only for the vault
        // it came from; a cancelled share records nothing.
        guard saved, let vault else { return }
        await recordBackup(at: now, vault: vault)
    }

    /// Records a saved backup in the vault (`AppRuntime.recordBackupSaved`);
    /// when its store is already closed (the app left the foreground and
    /// locked), at the next unlock. Never for another vault than `vault`.
    private func recordBackup(at date: Date, vault: Int) async {
        guard vault == vaultGeneration else { return }
        do {
            try await runtime.recordBackupSaved(at: date)
            if unrecordedBackup?.vault == vault { unrecordedBackup = nil }
        } catch {
            if vault == vaultGeneration { unrecordedBackup = (date: date, vault: vault) }
        }
    }

    /// The backup saved while the vault was closed, now that it is open.
    private func recordUnrecordedBackup() async {
        guard let pending = unrecordedBackup else { return }
        await recordBackup(at: pending.date, vault: pending.vault)
    }

    /// This phone's vault is erased or replaced: backups exported from it
    /// are never recorded for the next one.
    private func forgetExportedBackups() {
        vaultGeneration &+= 1
        exportedBackups = [:]
        unrecordedBackup = nil
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

    /// Decrypts the backup, verifies every field and asks the relay,
    /// read-only, whether its token still works (`AppRuntime.checkBackup`):
    /// “opened” means a restore will accept it. Nothing changes on this
    /// phone, and on the relay only the account's last-seen day (§4.10); the
    /// restore itself waits for the new code (2.2 › 2.3).
    func openBackup(_ file: BackupFileInfo, password: String) async -> BackupOpenResult {
        pendingRestore = nil
        unrestorable = nil
        let result: BackupOpenResult
        do {
            try await runtime.checkBackup(backupFile: file.url, password: password)
            result = .opened
        } catch {
            result = Self.openResult(for: error)
        }
        switch result {
        case .opened:
            pendingRestore = (file.url, password)
        case .damaged, .noLongerValid:
            // It can never be restored: its protected copy goes now.
            try? FileManager.default.removeItem(at: file.url)
        case .wrongPassword, .unverified:
            break
        }
        return result
    }

    /// An account backup holds the account and the contacts, never message
    /// history (PROTOCOL §5.5).
    var backupsIncludeHistory: Bool { false }

    func restoreBackup(_ file: BackupFileInfo) -> AsyncThrowingStream<RestoreProgress, any Error> {
        // `openBackup` has decrypted the backup, verified every field and
        // checked its token with the relay: the keys step is done. The
        // account itself is restored with the new code (`completeRestore`),
        // because the vault is bound to that code, and there is no message
        // history to restore.
        let (stream, continuation) = AsyncThrowingStream<RestoreProgress, any Error>.makeStream()
        continuation.yield(
            RestoreProgress(
                fraction: 1, stage: .keys, messagesDone: 0, messagesTotal: 0, mediaBytes: 0, estimatedRemaining: 0,
                includesHistory: false))
        continuation.finish()
        return stream
    }

    func cancelRestore() async {
        dropPendingRestore()
    }
}

/// The content type of a picked file, for the runtime's metadata rules.
enum UTTypeName {
    static func contentType(for url: URL) -> String {
        let values = try? url.resourceValues(forKeys: [.contentTypeKey])
        return values?.contentType?.preferredMIMEType ?? "application/octet-stream"
    }
}
