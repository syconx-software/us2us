import Foundation
import Observation
import Us2usKit

/// What the shared conversation screen (05) needs to know when it shows a
/// community instead of a one-to-one chat: the header, the composer, the
/// long-press menu and “Bilgi” follow it.
@MainActor
protocol CommunityConversationContext: AnyObject {
    var communityID: CommunityID { get }
    /// Changes after the vault is unlocked again; no session while locked.
    var sessionID: ObjectIdentifier? { get }
    /// The community's row (its name and this account's phase), `nil` once it is gone here.
    var row: CommunityRow? { get }
    /// This account owns it: “Herkesten sil” on every message (moderation).
    var isOwner: Bool { get }
    /// The last problem of a send or delete here, or of the background refresh.
    var problem: CommunityProblem? { get }
    func dismissProblem()
    /// Sends a text; `false` when it could not be queued (the composer keeps it).
    func sendText(_ text: String) async -> Bool
    /// Every recipient of an own message with its delivered and read times.
    func recipients(of message: MessageID) async -> [CommunityRecipientInfo]
}

/// One community as a conversation of the shared 05 screens: the
/// one-to-one `MessengerService` surface, its conversation calls answered
/// by the communities service (text, photos, videos, files, delete for
/// everyone, delete for me) and everything else handed to the app's
/// messenger. What communities do not have (reactions, replies,
/// locations, Ghost, view-once, SAVED) is not offered by the screen
/// and does nothing here.
@Observable
@MainActor
final class CommunityChatMessenger: MessengerService, CommunityConversationContext {
    @ObservationIgnored let base: any MessengerService
    let communityID: CommunityID
    /// A send or delete that failed here (the screen shows it until dismissed).
    private(set) var localProblem: CommunityProblem?

    /// Tests hand in their own communities service; the app uses the messenger's.
    @ObservationIgnored private let override: (any CommunitiesService)?
    @ObservationIgnored private let pickerActivity: SystemPickerActivity
    @ObservationIgnored private weak var openedService: (any CommunitiesService)?

    init(
        base: any MessengerService, communityID: CommunityID, service: (any CommunitiesService)? = nil,
        pickerActivity: SystemPickerActivity = .shared
    ) {
        self.pickerActivity = pickerActivity
        self.base = base
        self.communityID = communityID
        override = service
    }

    var service: (any CommunitiesService)? { override ?? base.communities }
    var sessionID: ObjectIdentifier? { service.map { ObjectIdentifier($0) } }

    // MARK: CommunityConversationContext

    var row: CommunityRow? { service?.row(communityID) }
    var isOwner: Bool { service?.isOwner(of: communityID) ?? false }
    var problem: CommunityProblem? { localProblem ?? service?.problem }

    func dismissProblem() {
        if localProblem != nil { localProblem = nil } else { service?.dismissProblem() }
    }

    func sendText(_ text: String) async -> Bool {
        guard let service else { return false }
        do throws(CommunityProblem) {
            try await service.send(text, to: communityID)
            return true
        } catch {
            localProblem = error
            return false
        }
    }

    func recipients(of message: MessageID) async -> [CommunityRecipientInfo] {
        await service?.recipients(of: message, in: communityID) ?? []
    }

    // MARK: Conversation (05)

    var communities: (any CommunitiesService)? { service }

    func chat(_ id: ChatID) -> ChatSummary? {
        guard id == communityID, let row = service?.row(communityID) else { return nil }
        // The conversation's own id (the chats list uses a prefixed one); read
        // often by the screen, so built from the row alone.
        let listed = CommunityChatRow(
            id: row.id, name: row.name, isPublic: row.isPublic, lastMessage: nil, lastActivity: nil, unreadCount: 0
        ).chatSummary()
        return ChatSummary(
            id: communityID, peer: listed.peer, lastMessage: nil, unreadCount: 0, ghost: .off, disappearing: .off,
            community: listed.community)
    }

    func messages(in chat: ChatID) -> [Message] {
        guard chat == communityID else { return [] }
        return service?.conversation(communityID) ?? []
    }

    func loadOlderMessages(in chat: ChatID) async throws -> Bool {
        guard chat == communityID, let service else { return false }
        return try await service.loadOlderMessages(in: communityID)
    }

    func conversationAppeared(_ chat: ChatID) async {
        guard let service else { return }
        if openedService !== service {
            openedService?.screenDisappeared()
            service.screenAppeared()
            openedService = service
        }
        do throws(CommunityProblem) { try await service.open(communityID) } catch { localProblem = error }
    }

    func conversationDisappeared(_ chat: ChatID) {
        // UIKit's full-screen picker temporarily disappears the SwiftUI conversation.
        // Closing here would clear selected/messages and discard the pending media bubble.
        guard let openedService else { return }
        if pickerActivity.isPresented {
            openedService.cover(communityID)
            return
        }
        openedService.screenDisappeared()
        openedService.close(communityID)
        self.openedService = nil
    }

    func send(text: String, replyTo: QuotedMessage?, in chat: ChatID) async {
        _ = await sendText(text)
    }

    func send(attachment: ConversationAttachment, in chat: ChatID) async {
        await service?.send(attachment: attachment, to: communityID)
    }

    func send(pickedMedia messages: [PickedMediaMessage], in chat: ChatID) async {
        await service?.send(pickedMedia: messages, to: communityID)
    }

    func deleteForEveryone(_ message: MessageID, in chat: ChatID) async {
        guard let service else { return }
        do throws(CommunityProblem) { try await service.deleteForEveryone(message, in: communityID) } catch { localProblem = error }
    }

    func deleteForMe(_ message: MessageID, in chat: ChatID) async {
        guard let service else { return }
        do throws(CommunityProblem) { try await service.deleteForMe(message, in: communityID) } catch { localProblem = error }
    }

    func mediaPayload(_ message: MessageID, in chat: ChatID) async -> MediaPayload? {
        await service?.mediaPayload(message, in: communityID)
    }

    func downloadMedia(_ message: MessageID, in chat: ChatID) async {
        await service?.downloadMedia(message, in: communityID)
    }

    /// Community v2 has no SAVED receipt: saving is not reported.
    func markSaved(_ id: MessageID, in chat: ChatID) async {}

    func media(in chat: ChatID, kind: ChatMediaKind, before: MessageID?, limit: Int) async -> [Message] { [] }

    // Not part of a community conversation.
    func send(sticker: BrandStickerSelection, replyTo: QuotedMessage?, in chat: ChatID) async {
        await service?.send(sticker: sticker, to: communityID)
    }
    func recentStickers() async -> [String] { await base.recentStickers() }
    func send(voice: VoiceRecording, replyTo: QuotedMessage?, in chat: ChatID) async -> Bool {
        await service?.send(voice: voice, to: communityID) ?? false
    }
    func markVoicePlayed(_ message: MessageID, in chat: ChatID) async {}
    func resend(_ message: MessageID, in chat: ChatID) async {
        guard let service else { return }
        do throws(CommunityProblem) { try await service.resend(message, in: communityID) } catch { localProblem = error }
    }
    func updateGhost(_ ghost: GhostState, disappearing: DisappearingTimer, in chat: ChatID) async {}
    func setReaction(_ emoji: String?, on message: MessageID, in chat: ChatID) async {}
    func stopLiveLocation(_ message: MessageID, in chat: ChatID) async {}
    func viewOnceMedia(_ message: MessageID, in chat: ChatID) async -> MediaSource? { nil }
    func markViewOnceOpened(_ message: MessageID, in chat: ChatID) async {}
    func reportScreenshot(of message: MessageID, in chat: ChatID) async {}
    func sendCurrentLocation(in chat: ChatID) async {}

    // MARK: Everything else: the app's messenger

    var account: OwnAccount? { base.account }
    var hasAccount: Bool { base.hasAccount }
    var sendsDesignContent: Bool { base.sendsDesignContent }
    var backupsIncludeHistory: Bool { base.backupsIncludeHistory }
    var restoreAccessPending: Bool { base.restoreAccessPending }
    var biometricUnlockNeedsCode: Bool { base.biometricUnlockNeedsCode }
    var ownAvatar: OwnAvatarState { base.ownAvatar }
    var unlockStatus: UnlockStatus { base.unlockStatus }
    var connection: ConnectionState { base.connection }
    var chats: [ChatSummary] { base.chats }
    var requests: [MessageRequest] { base.requests }
    var blockedPeople: [BlockedPerson] { base.blockedPeople }
    var settings: AppSettings { base.settings }

    func usernameAvailability(_ username: String) async -> UsernameAvailability { await base.usernameAvailability(username) }
    func secretCodeProblem(_ code: String) -> SecretCodeError? { base.secretCodeProblem(code) }
    func createAccount(username: String, secretCode: String, faceIDEnabled: Bool) async throws {
        try await base.createAccount(username: username, secretCode: secretCode, faceIDEnabled: faceIDEnabled)
    }
    func completeRestore(secretCode: String, faceIDEnabled: Bool) async throws {
        try await base.completeRestore(secretCode: secretCode, faceIDEnabled: faceIDEnabled)
    }
    func unlock(code: String) async -> UnlockAttemptResult { await base.unlock(code: code) }
    func unlockWithBiometrics(reason: String, cancelTitle: String) async -> Bool {
        await base.unlockWithBiometrics(reason: reason, cancelTitle: cancelTitle)
    }
    func eraseEverything() async { await base.eraseEverything() }
    func deleteAccountOnRelay() async throws { try await base.deleteAccountOnRelay() }
    func lookup(username: String) async -> UserLookup { await base.lookup(username: username) }
    func accept(_ request: MessageRequest) async -> ChatID? { await base.accept(request) }
    func block(_ request: MessageRequest) async { await base.block(request) }
    func delete(_ request: MessageRequest) async { await base.delete(request) }
    func block(chat: ChatID) async -> Bool { await base.block(chat: chat) }
    func unblock(chat: ChatID) async -> Bool { await base.unblock(chat: chat) }
    /// A community is left from its own screen, never deleted as a chat.
    func deleteChat(_ chat: ChatID) async -> Bool { false }
    func deleteContact(_ chat: ChatID) async -> Bool { false }
    func unblock(_ person: BlockedPerson) async -> Bool { await base.unblock(person) }
    func reportSubject(for chat: ChatID) -> ReportSubject? { base.reportSubject(for: chat) }
    func reportSubject(for request: MessageRequest) -> ReportSubject? { base.reportSubject(for: request) }
    func startChat(username: String) async -> ChatID? { await base.startChat(username: username) }
    func ownContactCode() async -> String? { await base.ownContactCode() }
    func setAvatar(_ edit: AvatarEdit) async throws(AvatarEditError) { try await base.setAvatar(edit) }
    func setAvatarShownInSearch(_ shown: Bool) async -> Bool { await base.setAvatarShownInSearch(shown) }
    func searchAvatar(username: String) async -> AvatarPicture { await base.searchAvatar(username: username) }
    func revealAvatar(of request: MessageRequest) async -> AvatarPicture { await base.revealAvatar(of: request) }
    func refreshOwnAvatar() async { await base.refreshOwnAvatar() }
    func safetyNumber(for chat: ChatID) async -> SafetyNumberInfo? { nil }
    func markVerified(_ chat: ChatID, comparing number: Data) async -> Bool { false }
    func clearVerification(_ chat: ChatID) async {}
    func verifyScannedSafetyCode(_ code: String, for chat: ChatID) async -> SafetyCodeComparison { .notASafetyCode }
    func acceptSafetyNumberChange(_ chat: ChatID, showing number: Data) async -> Bool { false }
    func resolveContactCode(_ code: String) async -> ContactCodeResolution { await base.resolveContactCode(code) }
    func addContact(fromScannedCode code: String) async -> ContactCodeAddition { await base.addContact(fromScannedCode: code) }
    func updateSettings(_ settings: AppSettings) async { await base.updateSettings(settings) }
    func backupEstimate() async -> BackupEstimate { await base.backupEstimate() }
    func setBiometricUnlock(_ enabled: Bool, reason: String) async -> Bool { await base.setBiometricUnlock(enabled, reason: reason) }
    func enableBiometricUnlock(code: String) async -> UnlockAttemptResult { await base.enableBiometricUnlock(code: code) }
    func verifySecretCode(_ code: String) async -> UnlockAttemptResult { await base.verifySecretCode(code) }
    func changeSecretCode(current: String, new: String) async throws(ChangeSecretCodeError) {
        try await base.changeSecretCode(current: current, new: new)
    }
    func backupPasswordIsSecretCode(_ password: String) async -> Bool { await base.backupPasswordIsSecretCode(password) }
    func exportBackup(
        _ contents: BackupContents, password: String, fileName: String,
        progress: @escaping @MainActor @Sendable (Double) -> Void
    ) async throws -> URL {
        try await base.exportBackup(contents, password: password, fileName: fileName, progress: progress)
    }
    func finishExport(at url: URL, saved: Bool) async { await base.finishExport(at: url, saved: saved) }
    func inspectBackup(at url: URL) async throws(BackupFileError) -> BackupFileInfo { try await base.inspectBackup(at: url) }
    func openBackup(_ file: BackupFileInfo, password: String) async -> BackupOpenResult {
        await base.openBackup(file, password: password)
    }
    func restoreBackup(_ file: BackupFileInfo) -> AsyncThrowingStream<RestoreProgress, any Error> { base.restoreBackup(file) }
    func cancelRestore() async { await base.cancelRestore() }
    func scenePhaseChanged(_ phase: MessengerScenePhase) { base.scenePhaseChanged(phase) }
    func checkRestoreAccess() async -> RestoreAccessCheck { await base.checkRestoreAccess() }
}
