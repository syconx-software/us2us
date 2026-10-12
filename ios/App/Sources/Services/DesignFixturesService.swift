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
    let clock: U2Clock
    private let english: Bool
    /// Simulated network latency for lookups (0 in tests).
    let latency: Duration
    /// The own profile picture and “Aramada göster” (memory only;
    /// DesignFixturesService+Avatars.swift).
    var ownAvatar = OwnAvatarState()
    /// SHA-256 of the passwords of backups exported in this session (never the plain text).
    private var exportedPasswordDigests: Set<Data> = []
    private var restoreTask: Task<Void, Never>?
    private var nextMessageNumber = 0
    /// Simulated downloads (harness, `downloadMedia`), by message id.
    @ObservationIgnored private var downloadTasks: [MessageID: Task<Void, Never>] = [:]
    /// The voice messages recorded in this session, by message id (memory only).
    @ObservationIgnored private var voiceAudio: [MessageID: Data] = [:]
    /// The sticker picker's recents (ids only, memory only): the stickers
    /// selin_n sent in the demo chat.
    @ObservationIgnored private var stickerRecents: [String] = []
    /// Harness: media requests wait instead of answering (the player's loading state).
    @ObservationIgnored var holdsMediaPayloads = false
    /// The contact screen's older photos, videos and files of each chat,
    /// beyond its conversation (`DesignFixtures.mediaArchive`), made on first
    /// use; memory only.
    @ObservationIgnored private var mediaArchive: [ChatID: [Message]] = [:]
    /// The live shares' latest positions by message id (5.2 › harita),
    /// memory only; observable, so the map follows every simulated tick.
    var livePositions: [MessageID: LivePosition] = ["m4": DesignFixturesService.ownFixturePosition]
    /// Harness: what the map's own-position stream reports instead of the
    /// simulated position (`5.2-map-ask`, `5.2-map-denied`).
    @ObservationIgnored var ownPositionOverride: OwnPositionState?
    /// Demo shares walking across the map (harness), by message id.
    @ObservationIgnored var liveDemoTasks: [MessageID: Task<Void, Never>] = [:]
    /// Harness and tests: the simulated relay cannot be reached when 7.1
    /// deletes the account.
    @ObservationIgnored var failsAccountDeletion = false
    /// Accounts the simulated relay deleted (tests).
    @ObservationIgnored private(set) var accountDeletionsOnRelay = 0
    /// The optional “wipe after 10 wrong codes” (PROTOCOL §5.2; off by
    /// default, as in the live app): the 10th wrong code in a row erases
    /// everything (harness `lock:wiped`, tests).
    @ObservationIgnored var wipesAfterTenWrongCodes = false
    /// Harness and tests: a restore's token rotation is still unanswered
    /// (PROTOCOL §5.5 step 3.4); 4.1 and 7.1 show the notice.
    var restoreAccessPending = false
    /// What “Şimdi kontrol et” finds out next (harness, tests).
    @ObservationIgnored var restoreAccessAnswer: RestoreAccessCheck = .closed
    /// Blocked chats as they were before the block (“Engeli kaldır” restores them).
    @ObservationIgnored private var peersBeforeBlock: [ChatID: Peer] = [:]
    /// Requests blocked from 4.3 / 4.4: unblocking makes them requests again.
    @ObservationIgnored private var blockedRequests: [ChatID: MessageRequest] = [:]
    /// People blocked whose chat is gone (Ghost erased it): only 7.1 ›
    /// Engellenenler shows them, and 4.2 › “Mesaj yaz” brings the chat back.
    private var blockedWithoutChat: [BlockedPerson] = []

    /// The password that opens the fixtures' backups (14 characters — the
    /// 7.3 reference draws 14 dots). Backups exported in this session open
    /// with their own password too.
    nonisolated static let demoBackupPassword = "kuzey7deniz42k"

    /// The 7.2 reference password: 14 random lower-case letters and digits,
    /// “Güçlü” with 82 % of the meter lit (72.4 bits). It contains no word,
    /// year or run, so the strength estimate counts every character.
    nonisolated static let demoExportPassword = "v7qz3kd9xw2mtr"

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
        stickerRecents = DesignFixtures.stickerRecents(english: english)
        messagesByChat = ChatMessageTable([
            "deniz": DesignFixtures.messages(chat: "deniz", english: english),
            "mert_k": DesignFixtures.messages(chat: "mert_k", english: english),
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
        // Like the live service, which cannot ask before setup: this one
        // meets the rules, and turns out taken at “Kurulumu tamamla”.
        if username == DesignFixtures.takenAtSetupUsername { return .meetsRules }
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
        // The relay's 409: someone registered the name first.
        if username == DesignFixtures.takenAtSetupUsername || DesignFixtures.takenUsernames.contains(username) {
            throw AccountCreationFailure.usernameTaken
        }
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
        peersBeforeBlock = [:]
        blockedRequests = [:]
        blockedWithoutChat = []
        settings = DesignFixtures.settings
        stickerRecents = DesignFixtures.stickerRecents(english: english)
        messagesByChat.replace(with: [
            "deniz": DesignFixtures.messages(chat: "deniz", english: english),
            "mert_k": DesignFixtures.messages(chat: "mert_k", english: english),
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
        if wipesAfterTenWrongCodes, failures >= 10 {
            await eraseEverything()
            return .wiped
        }
        let delay = throttle.delay(afterFailures: failures)
        unlockStatus = UnlockStatus(
            failures: failures,
            nextAttemptAllowedAt: delay > 0 ? now.addingTimeInterval(delay) : nil,
            upcoming: throttle.upcomingDelay(afterFailures: failures))
        return .wrongCode(unlockStatus)
    }

    /// Harness and tests: `failures` wrong codes in a row so far, with no
    /// wait running (the delays would take an hour to reach the 10th).
    func setWrongCodesForHarness(_ failures: Int) {
        unlockStatus = UnlockStatus(
            failures: failures, nextAttemptAllowedAt: nil, upcoming: throttle.upcomingDelay(afterFailures: failures))
    }

    func checkRestoreAccess() async -> RestoreAccessCheck {
        try? await Task.sleep(for: latency)
        let answer = restoreAccessAnswer
        if answer != .unanswered { restoreAccessPending = false }
        return answer
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
        for task in liveDemoTasks.values { task.cancel() }
        liveDemoTasks = [:]
        for task in downloadTasks.values { task.cancel() }
        downloadTasks = [:]
        livePositions = ["m4": Self.ownFixturePosition]
        ownPositionOverride = nil
        account = nil
        chats = []
        requests = []
        peersBeforeBlock = [:]
        blockedRequests = [:]
        blockedWithoutChat = []
        messagesByChat.replace(with: [:])
        mediaArchive = [:]
        stickerRecents = []
        safetyNumbers = [:]
        settings = AppSettings()
        ownAvatar = OwnAvatarState()
        restoreAccessPending = false
        unlockStatus = UnlockStatus(failures: 0, nextAttemptAllowedAt: nil, upcoming: throttle.upcomingDelay(afterFailures: 0))
    }

    func deleteAccountOnRelay() async throws {
        try? await Task.sleep(for: latency)
        if failsAccountDeletion { throw RelayError.network(.notConnectedToInternet) }
        accountDeletionsOnRelay += 1
    }

    // MARK: Chats

    func lookup(username: String) async -> UserLookup {
        let name = UsernameRules.normalize(username)
        guard UsernameRules.isValid(name) else { return .notFound }
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
        // Accepted: the contact's profile update brings their picture (§8.9).
        let peer = Peer(
            username: request.sender, palette: .forUsername(request.sender), isVerified: false, isAcceptedContact: true,
            presence: .hidden, avatar: DesignFixtures.requestAvatar(for: request.sender))
        let last = request.messages.last
        let summary = ChatSummary(
            id: request.sender, peer: peer,
            lastMessage: last.map { MessagePreview(kind: request.preview, direction: .incoming, status: nil, sentAt: $0.sentAt) },
            unreadCount: 0, ghost: .off, disappearing: .off)
        chats.insert(summary, at: 0)
        messagesByChat[request.sender] = request.messages
        return summary.id
    }

    /// As in the live app: the request leaves 4.3 and stays in 4.1 as a
    /// blocked chat with its messages; unblocking makes it a request again.
    func block(_ request: MessageRequest) async {
        guard requests.contains(where: { $0.id == request.id }) else { return }
        requests.removeAll { $0.id == request.id }
        let peer = Peer(
            username: request.sender, palette: .forUsername(request.sender), isVerified: false,
            isAcceptedContact: false, presence: .hidden, isBlocked: true)
        let last = request.messages.last
        let summary = ChatSummary(
            id: request.id, peer: peer,
            lastMessage: last.map { MessagePreview(kind: request.preview, direction: .incoming, status: nil, sentAt: $0.sentAt) },
            unreadCount: 0, ghost: .off, disappearing: .off)
        chats.removeAll { $0.id == request.id }
        // Where its last message puts it (the design's own order stays).
        let date = last?.sentAt ?? request.receivedAt
        let position = chats.firstIndex { ($0.lastMessage?.sentAt ?? .distantPast) < date } ?? chats.endIndex
        chats.insert(summary, at: position)
        messagesByChat[request.id] = request.messages
        blockedRequests[request.id] = request
    }

    func delete(_ request: MessageRequest) async {
        requests.removeAll { $0.id == request.id }
    }

    // MARK: Blocking and reports

    /// Read only from now on: presence and the picture go (the live service
    /// shows the initial for anyone blocked) and nothing can be sent.
    func block(chat: ChatID) async -> Bool {
        setBlocked(chat)
    }

    /// The block itself (also the harness's blocked states, set before the first frame).
    @discardableResult
    func setBlocked(_ chat: ChatID) -> Bool {
        guard let index = chats.firstIndex(where: { $0.id == chat }) else { return false }
        guard !chats[index].peer.isBlocked else { return true }
        peersBeforeBlock[chat] = chats[index].peer
        chats[index].peer.isBlocked = true
        chats[index].peer.isAcceptedContact = false
        chats[index].peer.presence = .hidden
        chats[index].peer.avatar = .initials
        return true
    }

    /// Back as before the block; a blocked request goes back to 4.3.
    func unblock(chat: ChatID) async -> Bool {
        guard let index = chats.firstIndex(where: { $0.id == chat }), chats[index].peer.isBlocked else { return false }
        if let request = blockedRequests.removeValue(forKey: chat) {
            chats.remove(at: index)
            messagesByChat[chat] = nil
            let position = requests.firstIndex { $0.receivedAt < request.receivedAt } ?? requests.endIndex
            requests.insert(request, at: position)
            return true
        }
        if let peer = peersBeforeBlock.removeValue(forKey: chat) {
            chats[index].peer = peer
        } else {
            chats[index].peer.isBlocked = false
        }
        return true
    }

    /// The blocked chats of 4.1 and the people blocked without a chat, by name.
    var blockedPeople: [BlockedPerson] {
        let withChat = chats.filter(\.peer.isBlocked).map { chat in
            BlockedPerson(id: chat.id, name: chat.peer.username, palette: chat.peer.palette, avatar: chat.peer.avatar)
        }
        return BlockedPerson.listed(withChat + blockedWithoutChat)
    }

    /// As `unblock(chat:)`; someone without a chat is back as before the
    /// block, still without one (4.1 shows nothing until they write).
    func unblock(_ person: BlockedPerson) async -> Bool {
        guard let index = blockedWithoutChat.firstIndex(where: { $0.id == person.id }) else {
            return await unblock(chat: person.id)
        }
        blockedWithoutChat.remove(at: index)
        peersBeforeBlock[person.id] = nil
        return true
    }

    /// Harness and tests: 7.1 › Engellenenler's two kinds — bora blocked
    /// (the chat stays in 4.1, read only) and lale, a contact blocked
    /// before Ghost erased the chat (none any more).
    func loadBlockedPeople() {
        setBlocked("bora")
        guard chat("lale") == nil, !blockedWithoutChat.contains(where: { $0.id == "lale" }) else { return }
        let palette = AvatarPalette.forUsername("lale")
        peersBeforeBlock["lale"] = Peer(
            username: "lale", palette: palette, isVerified: false, isAcceptedContact: true, presence: .hidden)
        blockedWithoutChat.append(BlockedPerson(id: "lale", name: "lale", palette: palette))
    }

    func reportSubject(for chat: ChatID) -> ReportSubject? {
        self.chat(chat).map { ReportSubject(username: $0.peer.username, isUsernameVerified: true) }
    }

    /// The design's suspicious request (hediye_kazan) is one whose name the
    /// directory did not confirm.
    func reportSubject(for request: MessageRequest) -> ReportSubject? {
        ReportSubject(username: request.sender, isUsernameVerified: !request.flags.contains(.suspicious))
    }

    func startChat(username: String) async -> ChatID? {
        let name = UsernameRules.normalize(username)
        guard UsernameRules.isValid(name), name != account?.username else { return nil }
        if let existing = chats.first(where: { $0.peer.username == name }) { return existing.id }
        // Someone blocked whose chat is gone gets it back, read only, with
        // “Engeli kaldır” (as in the live app).
        if let index = blockedWithoutChat.firstIndex(where: { $0.id == name }) {
            let person = blockedWithoutChat.remove(at: index)
            let peer = Peer(
                username: name, palette: person.palette, isVerified: false, isAcceptedContact: false,
                presence: .hidden, isBlocked: true)
            chats.insert(
                ChatSummary(id: person.id, peer: peer, lastMessage: nil, unreadCount: 0, ghost: .off, disappearing: .off),
                at: 0)
            messagesByChat[person.id] = []
            return person.id
        }
        // A new, empty conversation; the first message becomes a request on
        // the other side (no presence until it is accepted).
        // The user's own request shows the search copy of the account (§8.9).
        let peer = Peer(
            username: name, palette: .forUsername(name), isVerified: false, isAcceptedContact: false, presence: .hidden,
            avatar: DesignFixtures.searchAvatar(for: name))
        chats.insert(ChatSummary(id: name, peer: peer, lastMessage: nil, unreadCount: 0, ghost: .off, disappearing: settings.defaultDisappearing), at: 0)
        messagesByChat[name] = []
        return name
    }

    func ownContactCode() async -> String? {
        guard let username = account?.username else { return nil }
        return ContactCode.encode(username: username, keyFingerprint: Self.fixtureKeyFingerprint(username))
    }

    /// The fixtures' stand-in for a user's identity-key fingerprint — what
    /// their contact code carries (the live code: SHA-256 of the key).
    nonisolated static func fixtureKeyFingerprint(_ username: String) -> String {
        let digest = SHA256.hash(data: Data("us2us-identity-\(username)".utf8))
        return digest.prefix(16).map { String(format: "%02x", $0) }.joined()
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
            case .video:
                .video(.designArtwork(.sunsetVideo), duration: 18, byteCount: 12_400_000)
            case .document(let url):
                .file(
                    name: url.lastPathComponent, byteCount: 0, typeLabel: url.pathExtension.uppercased(),
                    pageCount: nil)
            }
        appendOutgoing(content, replyTo: nil, in: chat)
        // A new live share starts where selin_n is (5.2 › harita).
        if case .liveLocation = attachment, let sent = messagesByChat[chat]?.last, case .liveLocation = sent.content {
            livePositions[sent.id] = Self.ownFixturePosition
        }
    }

    /// The sticker becomes an outgoing sticker message (“Gönderildi”) and
    /// goes first in the recents.
    func send(sticker: BrandStickerSelection, replyTo: QuotedMessage?, in chat: ChatID) async {
        guard appendOutgoing(sticker.messageContent, replyTo: replyTo, in: chat) != nil else { return }
        stickerRecents = BrandStickerRecents.adding(sticker, to: stickerRecents)
    }

    func recentStickers() async -> [String] {
        account == nil ? [] : stickerRecents
    }

    /// “+” › Fotoğraf ve video with the design's pictures, as the live
    /// service shows a pick: every bubble at once, in the order picked,
    /// “Hazırlanıyor”; then they are prepared one after another, each sent
    /// in its own bubble. The fixtures never open the real picker: a picked
    /// copy handed over anyway is not kept.
    func send(pickedMedia messages: [PickedMediaMessage], in chat: ChatID) async {
        var ids: [MessageID] = []
        for message in messages {
            let content: MessageContent
            switch message.item.source {
            case .artwork(let artwork):
                switch message.item.kind {
                case .photo: content = .photo(.designArtwork(artwork), caption: message.caption)
                case .video: content = .video(.designArtwork(artwork), duration: 18, byteCount: 0)
                }
            case .copy:
                SystemPickers.discard([message.item])
                continue
            }
            guard let id = appendOutgoing(content, replyTo: nil, in: chat, status: .sending, availability: .preparing)
            else { continue }
            ids.append(id)
        }
        finishPreparing(ids, in: chat, each: latency == .zero ? .zero : .milliseconds(700), updatingList: true)
    }

    /// The recording becomes an outgoing voice bubble (“Gönderildi”); its
    /// bytes stay in memory so it plays back.
    @discardableResult
    func send(voice: VoiceRecording, replyTo: QuotedMessage?, in chat: ChatID) async -> Bool {
        let content = MessageContent.voice(
            duration: voice.duration, waveform: VoiceWaveform.bars(voice.waveform, count: DesignFixtures.voiceWaveform.count),
            playedFraction: 0)
        guard let id = appendOutgoing(content, replyTo: replyTo, in: chat) else { return false }
        voiceAudio[id] = voice.audio
        return true
    }

    func markVoicePlayed(_ message: MessageID, in chat: ChatID) async {
        updateMessage(message, in: chat) { $0.isVoicePlayed = true }
    }

    /// Saved from a viewer: shows as saved here (the fixtures have no other
    /// side to send SAVED to). Never for disappearing or view-once media.
    func markSaved(_ id: MessageID, in chat: ChatID) async {
        if let found = messagesByChat[chat]?.first(where: { $0.id == id }) {
            guard MediaExportPolicy.allowsExport(found) else { return }
            updateMessage(id, in: chat) { $0.savedByMe = true }
        } else if let index = mediaArchive[chat]?.firstIndex(where: { $0.id == id }) {
            mediaArchive[chat]?[index].savedByMe = true
        }
    }

    /// The contact screen's tabs: the conversation's photos, videos or files
    /// and the older ones of the archive, newest first, a page at a time.
    func media(in chat: ChatID, kind: ChatMediaKind, before: MessageID?, limit: Int) async -> [Message] {
        guard self.chat(chat) != nil else { return [] }
        if mediaArchive[chat] == nil { mediaArchive[chat] = DesignFixtures.mediaArchive(chat: chat, english: english) }
        try? await Task.sleep(for: latency / 3)
        let all = (mediaArchive[chat] ?? []) + (messagesByChat[chat] ?? [])
        return DesignFixtures.mediaPage(all, kind: kind, before: before, limit: limit)
    }

    /// Harness: the other side saved one of the user's own photos, videos or
    /// files (the “Kaydetti” chip).
    func setSavedByPeer(_ id: MessageID, in chat: ChatID) {
        let now = clock.now
        updateMessage(id, in: chat) {
            $0.savedByPeer = true
            $0.savedByPeerAt = now
        }
    }

    func resend(_ message: MessageID, in chat: ChatID) async {
        guard let original = messagesByChat[chat]?.first(where: { $0.id == message }) else { return }
        if original.mediaAvailability == .preparationFailed {
            // A photo or video that could not be prepared is prepared again, in its own bubble.
            updateMessage(message, in: chat) {
                $0.mediaAvailability = .preparing
                $0.status = .sending
                $0.failedAt = nil
            }
            finishPreparing(message, in: chat, after: .seconds(2.4))
            return
        }
        messagesByChat[chat]?.removeAll { $0.id == message }
        let audio = voiceAudio.removeValue(forKey: message)
        let id = appendOutgoing(original.content, replyTo: original.replyTo, in: chat, disappearing: original.disappearing)
        if let id, let audio { voiceAudio[id] = audio }
    }

    /// A new outgoing message (status “Gönderildi” unless `status` says
    /// otherwise), the row moved to the top of 4.1, and the Ghost timer
    /// restarted. Its id; `nil` without the chat.
    @discardableResult
    private func appendOutgoing(
        _ content: MessageContent, replyTo: QuotedMessage?, in chat: ChatID, disappearing: DisappearingTimer? = nil,
        status: MessageStatus = .sent, availability: MediaAvailability = .ready
    ) -> MessageID? {
        // Like the runtime: nothing is sent to someone blocked.
        guard let index = chats.firstIndex(where: { $0.id == chat }), !chats[index].peer.isBlocked else { return nil }
        let now = clock.now
        nextMessageNumber += 1
        var message = Message(
            id: "out-\(nextMessageNumber)-\(UUID().uuidString.prefix(8))", direction: .outgoing, sentAt: now,
            content: content, status: status, replyTo: replyTo, disappearing: disappearing ?? chats[index].disappearing)
        message.mediaAvailability = availability
        messagesByChat[chat, default: []].append(message)
        var summary = chats.remove(at: index)
        summary.lastMessage = Self.preview(of: message)
        if summary.ghost.isEnabled {
            // Every new message restarts the Ghost timer.
            summary.ghost.deadline = now.addingTimeInterval(summary.ghost.period.duration)
        }
        chats.insert(summary, at: 0)
        return message.id
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
        case .textSticker, .sticker: kind = .sticker
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
        liveDemoTasks.removeValue(forKey: message)?.cancel()
        voiceAudio[message] = nil
        updateMessage(message, in: chat) {
            $0.content = .deleted
            $0.reactions = []
        }
        refreshPreview(of: chat)
    }

    func deleteForMe(_ message: MessageID, in chat: ChatID) async {
        liveDemoTasks.removeValue(forKey: message)?.cancel()
        voiceAudio[message] = nil
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
        liveDemoTasks.removeValue(forKey: message)?.cancel()
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

    // MARK: Media

    /// The design's content as real bytes (`FixtureMedia`): photos as JPEG,
    /// the video as MP4, the PDF. A file still “on the relay” downloads first.
    func mediaPayload(_ message: MessageID, in chat: ChatID) async -> MediaPayload? {
        while !Task.isCancelled {
            guard
                let found = messagesByChat[chat]?.first(where: { $0.id == message })
                    ?? mediaArchive[chat]?.first(where: { $0.id == message })
            else { return nil }
            if holdsMediaPayloads {
                try? await Task.sleep(for: .milliseconds(200))
                continue
            }
            switch found.mediaAvailability {
            case .ready:
                return await payload(of: found)
            case .remote:
                simulateDownload(of: message, in: chat)
            case .downloading:
                break
            case .held, .unavailable, .preparing, .preparationFailed:
                return nil
            }
            try? await Task.sleep(for: .milliseconds(100))
        }
        return nil
    }

    private func payload(of message: Message) async -> MediaPayload? {
        switch message.content {
        case .photo(let source, _):
            switch source {
            case .designArtwork(let artwork):
                return FixtureMedia.photoJPEG(artwork).map { MediaPayload(data: $0, contentType: "image/jpeg", fileName: nil) }
            case .memory(let data):
                return MediaPayload(data: data, contentType: "image/jpeg", fileName: nil)
            case .file(let url):
                return (try? Data(contentsOf: url)).map { MediaPayload(data: $0, contentType: "image/jpeg", fileName: nil) }
            }
        case .video:
            return await FixtureMedia.videoMP4().map { MediaPayload(data: $0, contentType: "video/mp4", fileName: nil) }
        case .voice(let duration, let waveform, _):
            if let audio = voiceAudio[message.id] {
                return MediaPayload(data: audio, contentType: VoiceRecording.contentType, fileName: nil)
            }
            // The design's voice message has no recording: a soft hum that
            // follows its waveform stands in.
            let samples = VoiceWaveform.data(waveform)
            let audio = await Task.detached(priority: .userInitiated) {
                FixtureMedia.voiceWAV(duration: duration, waveform: samples)
            }.value
            return MediaPayload(data: audio, contentType: "audio/wav", fileName: nil)
        case .file(let name, _, _, _):
            switch MediaFileKind(fileName: name) {
            case .pdf:
                return MediaPayload(
                    data: await FixtureMedia.leasePDF(english: english), contentType: "application/pdf", fileName: name)
            case .movie:
                return await FixtureMedia.videoMP4().map { MediaPayload(data: $0, contentType: "video/mp4", fileName: name) }
            case .image:
                return FixtureMedia.photoJPEG(.nightPier).map {
                    MediaPayload(data: $0, contentType: "image/jpeg", fileName: name)
                }
            case .audio, .other:
                return MediaPayload(data: Data(name.utf8), contentType: "application/octet-stream", fileName: name)
            }
        default:
            return nil
        }
    }

    func downloadMedia(_ message: MessageID, in chat: ChatID) async {
        guard let found = messagesByChat[chat]?.first(where: { $0.id == message }), found.mediaAvailability == .remote
        else { return }
        simulateDownload(of: message, in: chat)
    }

    /// “Downloads” over `duration`, the ring filling in 24 steps.
    func simulateDownload(of message: MessageID, in chat: ChatID, duration: Duration = .seconds(2.4)) {
        guard downloadTasks[message] == nil else { return }
        setMediaAvailability(.downloading(0), of: message, in: chat)
        downloadTasks[message] = Task { @MainActor [weak self] in
            let steps = 24
            for step in 1...steps {
                try? await Task.sleep(for: duration / steps)
                guard !Task.isCancelled else { return }
                self?.setMediaAvailability(.downloading(Double(step) / Double(steps)), of: message, in: chat)
            }
            self?.setMediaAvailability(.ready, of: message, in: chat)
            self?.downloadTasks[message] = nil
        }
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

    func markVerified(_ chat: ChatID, comparing number: Data) async -> Bool {
        // Bound to the number the user compared, like the live service.
        guard let info = await safetyNumber(for: chat), info.scannablePayload == number else { return false }
        await markVerified(chat, method: .manual)
        return true
    }

    /// Verifies `chat` at once (fixtures, harness and tests: no number to compare).
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
            chats[index].peer.safetyNumberChanged = false
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

    func verifyScannedSafetyCode(_ code: String, for chat: ChatID) async -> SafetyCodeComparison {
        guard let scanned = SafetyQRCodec.decode(code) else { return .notASafetyCode }
        guard let info = await safetyNumber(for: chat) else { return .mismatch }
        // Fixture semantics: the raw payloads (the live comparison is libsignal's).
        guard SafetyQRCodec.constantTimeEquals(scanned, info.scannablePayload) else { return .mismatch }
        await markVerified(chat, method: .faceToFace)
        return .match
    }

    func acceptSafetyNumberChange(_ chat: ChatID, showing number: Data) async -> Bool {
        guard let info = safetyNumbers[chat], info.hasUnverifiedChange, info.scannablePayload == number else {
            return false
        }
        safetyNumbers[chat]?.changedAt = nil
        if let index = chats.firstIndex(where: { $0.id == chat }) { chats[index].peer.safetyNumberChanged = false }
        // “Yeni güvenlik numarası kabul edildi · doğrulanmadı” (system line).
        nextMessageNumber += 1
        messagesByChat[chat, default: []].append(
            Message(
                id: "sys-accepted-\(nextMessageNumber)", direction: .outgoing, sentAt: clock.now,
                content: .system(.safetyNumberAccepted)))
        return true
    }

    func resolveContactCode(_ code: String) async -> ContactCodeResolution {
        guard let decoded = ContactCode.decode(code) else { return .notAContactCode }
        let name = decoded.username
        if name == account?.username { return .ownCode }
        var exists = chats.contains(where: { $0.peer.username == name })
        if !exists, case .found = await lookup(username: name) { exists = true }
        guard exists else { return .unknown }
        // Fixture semantics: every user's key has `fixtureKeyFingerprint`;
        // a code for another key is a key mismatch, never an unknown code.
        if let fingerprint = decoded.keyFingerprint, fingerprint.lowercased() != Self.fixtureKeyFingerprint(name) {
            return .keyMismatch
        }
        return .contact(username: name)
    }

    func addContact(fromScannedCode code: String) async -> ContactCodeAddition {
        guard let decoded = ContactCode.decode(code), case .contact(let name) = await resolveContactCode(code) else {
            return .failed
        }
        let chat: ChatID
        if let existing = chats.first(where: { $0.peer.username == name }) {
            chat = existing.id
        } else {
            // Scanned side by side: an accepted contact at once.
            let peer = Peer(
                username: name, palette: .forUsername(name), isVerified: false, isAcceptedContact: true,
                presence: .hidden, identityFingerprint: Self.fixtureKeyFingerprint(name))
            chats.insert(ChatSummary(id: name, peer: peer, lastMessage: nil, unreadCount: 0, ghost: .off, disappearing: settings.defaultDisappearing), at: 0)
            messagesByChat[name] = []
            chat = name
        }
        // Without the key's fingerprint the code proves nothing: added, not verified.
        guard decoded.keyFingerprint != nil else { return .added(chat) }
        await markVerified(chat, method: .faceToFace)
        return .verified(chat)
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

    /// Harness and tests: Face ID is turned on through the code page (as
    /// in the live app) instead of the system prompt.
    @ObservationIgnored var biometricUnlockNeedsCode = false

    func enableBiometricUnlock(code: String) async -> UnlockAttemptResult {
        let result = await unlock(code: code)
        if result == .unlocked { settings.faceIDEnabled = true }
        return result
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

    /// Where a photo, video or file message's file is (5.2 states).
    func setMediaAvailability(_ availability: MediaAvailability, of message: MessageID, in chat: ChatID) {
        updateMessage(message, in: chat) { $0.mediaAvailability = availability }
    }

    /// Makes a message disappearing (its file then previews without sharing).
    func setDisappearing(_ timer: DisappearingTimer, of message: MessageID, in chat: ChatID) {
        updateMessage(message, in: chat) { $0.disappearing = timer }
    }

    /// The contact's own live share (5.2 › harita, harness): an incoming live
    /// location from the chat's peer, starting at Moda İskelesi with 38
    /// minutes left. With a running clock it walks towards selin_n.
    @discardableResult
    func startDemoLiveLocation(in chat: ChatID) -> MessageID {
        let id = Self.demoLiveShareID
        if messagesByChat[chat]?.contains(where: { $0.id == id }) == true { return id }
        let now = clock.now
        let expiresAt = now.addingTimeInterval(38 * 60)
        messagesByChat[chat, default: []].append(
            Message(id: id, direction: .incoming, sentAt: now, content: .liveLocation(expiresAt: expiresAt, isActive: true)))
        livePositions[id] = Self.peerFixturePosition
        refreshPreview(of: chat)
        if !clock.isFrozen { walkDemoShare(id, until: expiresAt) }
        return id
    }

    /// An incoming PDF from the chat's peer (the download states need one
    /// that is not this phone's own): “kat-plani.pdf”, 1,4 MB, two pages.
    @discardableResult
    func addIncomingFile(in chat: ChatID, availability: MediaAvailability) -> MessageID {
        let id = "\(chat)-file-in"
        if messagesByChat[chat]?.contains(where: { $0.id == id }) == true {
            setMediaAvailability(availability, of: id, in: chat)
            return id
        }
        let last = messagesByChat[chat]?.last?.sentAt ?? clock.now
        var message = Message(
            id: id, direction: .incoming, sentAt: last.addingTimeInterval(120),
            content: .file(
                name: english ? "floor-plan.pdf" : "kat-plani.pdf", byteCount: 1_400_000, typeLabel: "PDF", pageCount: 2))
        message.mediaAvailability = availability
        messagesByChat[chat, default: []].append(message)
        return id
    }

    /// An incoming voice message from the chat's peer, not played yet
    /// (0:09, the design's waveform), with its file where `availability` says.
    @discardableResult
    func addIncomingVoice(in chat: ChatID, availability: MediaAvailability = .ready) -> MessageID {
        let id = "\(chat)-voice-in"
        if messagesByChat[chat]?.contains(where: { $0.id == id }) == true {
            setMediaAvailability(availability, of: id, in: chat)
            return id
        }
        let last = messagesByChat[chat]?.last?.sentAt ?? clock.now
        var message = Message(
            id: id, direction: .incoming, sentAt: last.addingTimeInterval(60),
            content: .voice(duration: 9, waveform: DesignFixtures.voiceWaveform, playedFraction: 0))
        message.mediaAvailability = availability
        messagesByChat[chat, default: []].append(message)
        refreshPreview(of: chat)
        return id
    }

    /// A video sent from this phone while it is prepared (5.2 “Hazırlanıyor”,
    /// proposed): the sunset clip, 0:18, not yet sent. `failed`: preparing it
    /// failed (“İletilemedi · Video hazırlanamadı” + “Tekrar gönder”, which
    /// sends it as prepared).
    @discardableResult
    func addOutgoingVideo(in chat: ChatID, failed: Bool = false) -> MessageID {
        let id = "\(chat)-video-out"
        let availability: MediaAvailability = failed ? .preparationFailed : .preparing
        let status: MessageStatus = failed ? .failed : .sending
        let failedAt: Date? = failed ? clock.now : nil
        if messagesByChat[chat]?.contains(where: { $0.id == id }) == true {
            updateMessage(id, in: chat) {
                $0.mediaAvailability = availability
                $0.status = status
                $0.failedAt = failedAt
            }
            return id
        }
        let last = messagesByChat[chat]?.last?.sentAt ?? clock.now
        var message = Message(
            id: id, direction: .outgoing, sentAt: last.addingTimeInterval(120),
            content: .video(.designArtwork(.sunsetVideo), duration: 18, byteCount: 0), status: status,
            disappearing: chats.first { $0.id == chat }?.disappearing ?? .off)
        message.mediaAvailability = availability
        message.failedAt = failed ? message.sentAt : nil
        messagesByChat[chat, default: []].append(message)
        return id
    }

    /// The video picked in “+”, as the live service shows it: preparing for
    /// `duration`, then sent (12,4 MB) in the same bubble.
    func simulateVideoSend(in chat: ChatID, duration: Duration = .seconds(2.4)) {
        finishPreparing(addOutgoingVideo(in: chat), in: chat, after: duration)
    }

    /// The item is prepared after `duration`: sent (a video with its size).
    private func finishPreparing(_ id: MessageID, in chat: ChatID, after duration: Duration) {
        finishPreparing([id], in: chat, each: duration)
    }

    /// The items are prepared one after another, in order, `each` apart:
    /// each one sent in its own bubble (a video with its size).
    /// `updatingList`: the 4.1 row follows (“Gönderildi”).
    private func finishPreparing(
        _ ids: [MessageID], in chat: ChatID, each duration: Duration, updatingList: Bool = false
    ) {
        guard !ids.isEmpty else { return }
        Task { @MainActor [weak self] in
            for id in ids {
                if duration > .zero { try? await Task.sleep(for: duration) }
                guard let self else { return }
                self.updateMessage(id, in: chat) { message in
                    guard message.mediaAvailability == .preparing else { return }
                    if case .video(let poster, let length, _) = message.content {
                        message.content = .video(poster, duration: length, byteCount: 12_400_000)
                    }
                    message.mediaAvailability = .ready
                    message.status = .sent
                }
                if updatingList { self.refreshPreview(of: chat) }
            }
        }
    }

    /// The peer's safety number changed (new device): 6.1's danger banner, no seal.
    func setSafetyNumberChanged(_ chat: ChatID) async {
        if safetyNumbers[chat] == nil { safetyNumbers[chat] = await safetyNumber(for: chat) }
        safetyNumbers[chat]?.changedAt = clock.now
        safetyNumbers[chat]?.verifiedAt = nil
        safetyNumbers[chat]?.method = nil
        if let index = chats.firstIndex(where: { $0.id == chat }) {
            chats[index].peer.isVerified = false
            chats[index].peer.safetyNumberChanged = true
        }
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
