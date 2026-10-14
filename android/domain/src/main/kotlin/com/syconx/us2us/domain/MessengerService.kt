package com.syconx.us2us.domain

import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.MessageId
import com.syconx.us2us.domain.sticker.BrandStickerSelection
import java.net.URI
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Everything the screens need from the messenger, and nothing else.
 *
 * Screens and feature models talk only to this interface. Two implementations:
 * * `FixturesMessengerService` (:fixtures) reproduces exactly the data drawn in the design (names,
 *   messages, times, statuses), so every screen can be built and compared with
 *   the PNGs in `ios/design/reference`;
 * * `RealMessengerService` (:live) maps the runtime (vault, storage, relay) onto the same surface;
 *   it is the default, the fixtures run with `U2Fixtures` (debug builds).
 *
 * Observable state is a [StateFlow] (screens collect it with the lifecycle); per-chat state is a
 * function returning one; actions are `suspend` functions; streams are [Flow]s. Everything is
 * called from the main thread; implementations move work off it themselves.
 *
 * Implementations return the same flow instance for the same property or chat (the derived
 * defaults below make a new one per call: implementations override them with stored ones), so a
 * screen may read `messenger.messages(chat)` during composition; still `remember` it per chat.
 *
 * Failures the screens tell apart are thrown as [MessengerException]s (documented per member);
 * anything else thrown means the action did not happen.
 */
interface MessengerService {
    // MARK: Account & onboarding (01–02)

    /** `null` before onboarding finished on this device. */
    val account: StateFlow<OwnAccount?>

    /** Live availability of a username while typing (2.1). Local rules are checked first; the relay lookup is exact-match only. */
    suspend fun usernameAvailability(username: String): UsernameAvailability

    /** Why a newly chosen secret code is refused, or `null` (2.2; PROTOCOL §5.2: six digits, no repeats, no runs, not common). */
    fun secretCodeProblem(code: String): SecretCodeError?

    /**
     * Creates identity keys and the vault bound to the device secret + code, registers the username
     * with the relay (2.3 “Kurulumu tamamla”).
     *
     * @throws MessengerException.AccountCreation the name was taken first.
     * @throws MessengerException.SetupInterrupted the account was kept locked on this phone.
     */
    suspend fun createAccount(username: String, secretCode: String, faceIdEnabled: Boolean)

    /**
     * After a restore (7.3, from 1.1 or 3.3): binds the restored vault to this device with the new
     * secret code chosen in 2.2 (“2 / 2” → 4.1).
     *
     * @throws MessengerException.Restore the backup can never be restored.
     * @throws MessengerException.SetupInterrupted the account (or the previous vault) was kept locked.
     */
    suspend fun completeRestore(secretCode: String, faceIdEnabled: Boolean)

    // MARK: Lock (03)

    /** Failures so far, the next allowed attempt and what further wrong codes cost (PROTOCOL §5.2 delays). */
    val unlockStatus: StateFlow<UnlockStatus>

    /** Checks the code (6 digits, auto-submitted) and unlocks the vault. */
    suspend fun unlock(code: String): UnlockAttemptResult

    /**
     * Biometrics (3.1 and the keypad's biometric key): asks with [reason] and opens the vault with
     * its biometric-protected key. `false` on failure or cancel: the code keypad stays.
     * [cancelTitle] labels the system prompt's negative button (“Kodla aç”); there is no
     * device-credential fallback.
     */
    suspend fun unlockWithBiometrics(reason: String, cancelTitle: String): Boolean

    /** 3.3 / 7.1 “Her şeyi sil” after the second confirmation: crypto-shreds all local data. The app returns to onboarding. */
    suspend fun eraseEverything()

    /**
     * 7.1 “Her şeyi sil”, first: deletes the account on the relay, authenticated (its keys,
     * mailbox, uploaded media and username, PROTOCOL §4.3). Nothing on this phone is erased here;
     * the app calls [eraseEverything] once the confirmation has gone. An account the relay no
     * longer knows counts as deleted.
     *
     * @throws MessengerException.RelayUnreachable the relay did not confirm (offline, a timeout, a server error).
     */
    suspend fun deleteAccountOnRelay()

    // MARK: Line & chats (04)

    val connection: StateFlow<ConnectionState>
    val chats: StateFlow<List<ChatSummary>>
    val requests: StateFlow<List<MessageRequest>>

    /** Exact username lookup (4.2): no suggestions, no prefix search. */
    suspend fun lookup(username: String): UserLookup

    suspend fun accept(request: MessageRequest): ChatId?

    /**
     * 4.3 / 4.4 “Engelle”: the request leaves 4.3. It stays as a blocked chat (read only) in 4.1,
     * where “Engeli kaldır” makes it a request again.
     */
    suspend fun block(request: MessageRequest)

    suspend fun delete(request: MessageRequest)

    // MARK: Blocking and reports (App Review 1.2)

    /**
     * The contact screen › Engelle, after its confirmation (PROTOCOL §6.4): what the person sends is
     * dropped on this phone from now on (they are not told), nothing more is sent to them, messages
     * still waiting to go to them are cancelled (they fail; “Tekrar gönder” after an unblock), a live
     * location shared with them ends, and the access key they had is replaced. The chat stays, read
     * only ([Peer.isBlocked]). `false` when the block could not be stored (and for a service that
     * cannot block).
     */
    suspend fun block(chat: ChatId): Boolean = false

    /**
     * “Engeli kaldır”: the person is back in the state before the block: a contact gets the current
     * access key and the profile again, a blocked request is a request again. What they sent
     * meanwhile was never kept. `false` when nothing was unblocked.
     */
    suspend fun unblock(chat: ChatId): Boolean = false

    /**
     * 7.1 › Engellenenler: everyone blocked, by name: with a chat (read only in 4.1) or without one
     * (Ghost erased it, or it was deleted; the block stays). The default lists the blocked chats.
     */
    val blockedPeople: StateFlow<List<BlockedPerson>>
        get() = chats.mapState { list ->
            BlockedPerson.listed(
                list.filter { it.peer.isBlocked }.map { BlockedPerson(it.id, it.peer.username, palette = it.peer.palette, avatar = it.peer.avatar) },
            )
        }

    /**
     * “Engeli kaldır” on 7.1 › Engellenenler: exactly [unblock] for the chat, also for someone without
     * a chat: back in the state before the block (a contact gets the current access key and the
     * profile again; a blocked request is a request again, back in 4.3 while its chat is still there).
     * Someone with nothing to go back to (no chat, and no contact before the block: a Ghost wipe with
     * `wipe_contact` kept only the block) is forgotten: a stranger again, whom 4.2 › “Mesaj yaz”
     * starts a new chat with. The person leaves [blockedPeople].
     */
    suspend fun unblock(person: BlockedPerson): Boolean = unblock(person.id)

    /**
     * What a report about the chat's person names (“Bildir”): their username, nothing else. `null`
     * when there is no such chat (the report then names nobody: [ReportSubject.Unknown]).
     */
    fun reportSubject(chat: ChatId): ReportSubject? = null

    /** The same for the sender of a message request (4.3, 4.4). */
    fun reportSubject(request: MessageRequest): ReportSubject? = null

    /**
     * 4.2 “Mesaj yaz”: opens the chat with [username] (exact match), creating an empty,
     * pending-request conversation when there is none yet. The first message becomes a request on
     * the other side; neither side sees the other's presence until it is accepted.
     */
    suspend fun startChat(username: String): ChatId?

    /**
     * The user's own contact code ([ContactCode]: username + identity-key fingerprint) for 4.1 /
     * 4.2 / 6.2 “Kodumu göster” and the 7.1 profile QR. `null` until the account exists.
     */
    suspend fun ownContactCode(): String?

    // MARK: Profile pictures (PROTOCOL §8.9, §8.10)

    /** The user's own picture, “Aramada göster” and where the copy for search stands (7.1). The initial while locked. */
    val ownAvatar: StateFlow<OwnAvatarState> get() = DefaultOwnAvatar

    /**
     * 7.1 › Profil görseli: the initial, a preset or a photo. A photo is cropped to the square the
     * user framed, scaled to at most 256 × 256, stripped of all metadata and re-encoded on the phone
     * first. Accepted contacts get the new picture end to end; while “Aramada göster” is on it is
     * also published, encrypted under the username, for search.
     *
     * @throws MessengerException.AvatarEdit nothing changed (the photo cannot be used, or saving failed).
     */
    suspend fun setAvatar(edit: AvatarEdit) {
        throw MessengerException.AvatarEdit(AvatarEditError.Failed)
    }

    /**
     * 7.1 › “Aramada göster”: off removes the search copy from the relay (contacts keep the picture),
     * on publishes it again. Returns the setting in effect.
     */
    suspend fun setAvatarShownInSearch(shown: Boolean): Boolean = ownAvatar.value.showInSearch

    /**
     * The picture of a 4.2 result: call it only after [lookup] found exactly [username] (never
     * while the user is typing). An accepted contact's comes from their last profile update with no
     * request; anyone else's search copy is fetched and decrypted. Every failure is the initial.
     */
    suspend fun searchAvatar(username: String): AvatarPicture = AvatarPicture.Initials

    /**
     * 4.4 › “Profil resmini göster”, after an explicit tap on the opened request (never for a list
     * row or anything shown on its own): the sender's search copy, fetched once under their verified
     * username. The initial when there is none.
     */
    suspend fun revealAvatar(request: MessageRequest): AvatarPicture = AvatarPicture.Initials

    /** 7.1 appeared: the own picture's state is read again from the vault (the copy for search may have been written since). */
    suspend fun refreshOwnAvatar() {}

    // MARK: Conversation (05)

    /** The chat's summary (its row of 4.1), `null` while there is no such chat. */
    fun chat(id: ChatId): StateFlow<ChatSummary?>

    /** The chat's messages, oldest first. A change in one chat notifies only that chat's collectors. */
    fun messages(chat: ChatId): StateFlow<List<Message>>

    /** Sends text, optionally as a reply (the quote travels inside the encrypted message). Restarts the Ghost timer. */
    suspend fun sendText(text: String, replyTo: QuotedMessage?, chat: ChatId)

    /** Sends an attachment chosen from “+” (photo, file, place, live location). */
    suspend fun sendAttachment(attachment: ConversationAttachment, chat: ChatId)

    /**
     * Sends a built-in sticker (PROTOCOL §8.8): its ids, its emoji and, for a sticker with words,
     * the language they were shown in, so the other side draws exactly that; optionally as a reply.
     * It goes first in the recents.
     */
    suspend fun sendSticker(sticker: BrandStickerSelection, replyTo: QuotedMessage?, chat: ChatId)

    /**
     * The sticker picker's “Son kullanılanlar”: ids only ([com.syconx.us2us.domain.sticker.StickerRecents]),
     * newest first. The live service keeps them in the vault's encrypted runtime state, the fixtures
     * in memory; never in preferences. Empty while locked.
     */
    suspend fun recentStickers(): List<String>

    /**
     * Sends a voice message recorded with the mic (5.1), optionally as a reply. Its bubble shows at
     * once (“Gönderiliyor”) with the usual states; the recording is in memory only. `false` when it
     * could not even be stored (no bubble; the recording is gone).
     */
    suspend fun sendVoice(voice: VoiceRecording, replyTo: QuotedMessage?, chat: ChatId): Boolean

    /**
     * “+” › Fotoğraf ve video: the photos and videos of one pick (at most
     * [PickedMediaPlan.SELECTION_LIMIT]), each sent as a message of its own in the order it was
     * selected, the composer's text as the first photo's caption ([PickedMediaPlan]). Every bubble
     * shows at once (“Hazırlanıyor”) while the picker still hands the files over; they are prepared
     * one at a time and one that fails (“Tekrar gönder”) never holds up the rest.
     */
    suspend fun sendPickedMedia(messages: List<PickedMediaMessage>, chat: ChatId)

    /**
     * A received voice message was played for the first time: it shows as played, and (only while
     * read receipts are on, like “Okundu”) the sender learns it (VIEWED receipt, PROTOCOL §8.4).
     */
    suspend fun markVoicePlayed(message: MessageId, chat: ChatId)

    /** “Tekrar gönder” on a message that was not delivered within 14 days: queues the same content as a new message (5.4 note). */
    suspend fun resend(message: MessageId, chat: ChatId)

    suspend fun updateGhost(ghost: GhostState, disappearing: DisappearingTimer, chat: ChatId)

    suspend fun deleteForEveryone(message: MessageId, chat: ChatId)

    suspend fun deleteForMe(message: MessageId, chat: ChatId)

    /** Sets (or with `null` removes) the user's reaction. */
    suspend fun setReaction(emoji: String?, message: MessageId, chat: ChatId)

    /** “Durdur” in the live-location bubble: ends sharing at once. */
    suspend fun stopLiveLocation(message: MessageId, chat: ChatId)

    /**
     * 5.2 › harita: the open chat's live shares (the contact's and the user's own) with their latest
     * positions. Changes with every tick that arrives; memory only: empty while the chat is not open
     * and while the app is locked. Never stored or logged here.
     */
    fun liveLocations(chat: ChatId): StateFlow<LiveLocationSnapshot> = EmptyLiveLocations

    /**
     * This device's position for the live-location map, while the flow is collected (foreground
     * only). Shown on this phone, never sent or stored; never asks for permission by itself.
     */
    fun ownPositionUpdates(): Flow<OwnPositionState> = flowOf(OwnPositionState.Unavailable)

    /** The map's “İzin ver”: asks for location while in use, once (only while it has not been decided). `true` when it is allowed. */
    suspend fun requestOwnPositionAccess(): Boolean = false

    /**
     * Android: whether a live share would go on once the app leaves, which it does only while its
     * notification can be seen (notifications allowed, its channel on). When not, “+” › Canlı konum
     * opens the share sheet first: the share stops when the app leaves unless notifications are allowed.
     */
    fun liveSharingContinuesInBackground(): Boolean = true

    /** The share sheet's “Bildirimlere izin ver”: the system's question while it still asks it. */
    suspend fun allowLiveSharingNotice(): NoticePermission = NoticePermission.Allowed

    /** The decrypted media of a view-once message, held in memory only while 5.6 is open. */
    suspend fun viewOnceMedia(message: MessageId, chat: ChatId): MediaSource?

    /** 5.6 closed: deletes the media on both ends and sends the opened receipt. */
    suspend fun markViewOnceOpened(message: MessageId, chat: ChatId)

    /** A screenshot was taken while 5.6 showed the media: the sender gets a system line. */
    suspend fun reportScreenshot(message: MessageId, chat: ChatId)

    /**
     * The decrypted bytes of a photo, video, voice or file message, in memory only (the viewer, the
     * players and file previews). A file still on the relay is downloaded first and this waits until
     * it is here; cancelling the calling coroutine stops the wait (not the download). `null`: gone,
     * held until a request is accepted, failed, or view-once (5.6 has its own path).
     */
    suspend fun mediaPayload(message: MessageId, chat: ChatId): MediaPayload?

    /** Starts (or retries) the download of an attachment still on the relay. */
    suspend fun downloadMedia(message: MessageId, chat: ChatId)

    /**
     * A decrypted copy of a file message, named as sent, for the file preview and “Dosyalara
     * kaydet”, in the protected preview folder ([ProtectedPreviewFiles]). `null` when the file is not
     * available.
     */
    suspend fun previewFile(message: MessageId, chat: ChatId): URI? {
        val payload = mediaPayload(message, chat) ?: return null
        return ProtectedPreviewFiles.write(payload)
    }

    /** The preview or the export ended: the copy is deleted now. */
    fun discardPreviewFile(uri: URI) {
        ProtectedPreviewFiles.discard(uri)
    }

    /**
     * A photo, video or file was saved or handed on from one of our viewers (“Kaydet”, “Dosyalara
     * kaydet”, the share sheet): the sender is told (a SAVED receipt, PROTOCOL §8.4, always,
     * independent of read receipts; the user was told before the first save) and the message shows
     * as saved here. Never for view-once or disappearing media, which cannot be exported.
     */
    suspend fun markSaved(message: MessageId, chat: ChatId)

    /**
     * The contact screen's media tabs: this chat's photos and videos ([ChatMediaKind.Visual]) or
     * files ([ChatMediaKind.Documents]), newest first, at most [limit], older than [before] when set
     * (paging). Never view-once, deleted or expired messages.
     */
    suspend fun media(chat: ChatId, kind: ChatMediaKind, before: MessageId?, limit: Int): List<Message>

    /**
     * Grows whenever the chat's stored messages change, also while the chat is not open (a download
     * finished, an item was saved, deleted or wiped): the media tabs read their pages again.
     * A service whose open chats hold every item (the design's data) keeps it at 0: the tabs follow
     * [messages] instead.
     */
    fun mediaRevision(chat: ChatId): StateFlow<Int> = ZeroRevision

    // MARK: Verification (06)

    suspend fun safetyNumber(chat: ChatId): SafetyNumberInfo?

    // Every verification is bound to the key it is about (PROTOCOL.md §5): the live implementation
    // compares and stores in one step, so a key that changes while 6.1 is open is never sealed by a
    // decision about another.

    /**
     * 6.1 “Doğrulandı olarak işaretle” ([VerificationMethod.Manual]): only if the chat's safety
     * number is still [number] (the `scannablePayload` the user compared). `false` when it changed
     * since (6.1 then shows the new one).
     */
    suspend fun markVerified(chat: ChatId, number: Bytes): Boolean

    /** 6.1 verified › “Doğrulamayı kaldır”: the seal leaves 6.1, 5.1 and 4.1. */
    suspend fun clearVerification(chat: ChatId)

    /**
     * 6.2: compares a scanned QR text with the chat's safety number (libsignal's scannable
     * fingerprint comparison in the live implementation) and, on a match, marks the chat verified
     * face to face in the same step.
     */
    suspend fun verifyScannedSafetyCode(code: String, chat: ChatId): SafetyCodeComparison

    /**
     * 6.1 after a change › “Doğrulamadan devam et”: accepts the changed number without verifying it
     * (sending resumes, no seal), only if it is still [number], the `scannablePayload` on screen.
     * `false` otherwise.
     */
    suspend fun acceptSafetyNumberChange(chat: ChatId, number: Bytes): Boolean

    /** What a contact code scanned from 4.1 / 4.2 “QR kodla ekle” stands for (nothing is added yet). */
    suspend fun resolveContactCode(code: String): ContactCodeResolution

    /**
     * Adds the contact of a scanned contact code (or finds the existing chat). It is verified face to
     * face only when the code carries the identity key's fingerprint, the relay serves that key and
     * this phone holds no other one for the contact; the key is then pinned, so a different key later
     * is a safety-number change.
     */
    suspend fun addContact(code: String): ContactCodeAddition

    // MARK: Settings & backup (07)

    val settings: StateFlow<AppSettings>

    suspend fun updateSettings(settings: AppSettings)

    suspend fun backupEstimate(): BackupEstimate

    /**
     * 7.1 “Face ID ile aç” (biometric unlock): turning it on asks for biometrics once (so the
     * biometric key can be created); turning it off deletes that key. Returns the state in effect.
     */
    suspend fun setBiometricUnlock(enabled: Boolean, reason: String): Boolean

    /**
     * 7.1 biometric unlock turned on while quick unlock is off and this phone can use biometrics:
     * the secret code is needed once (the biometric key wraps the vault key, PROTOCOL §5.2), so 7.1
     * asks for it on a page of its own ([enableBiometricUnlock]).
     */
    val biometricUnlockNeedsCode: Boolean get() = false

    /**
     * Turns quick unlock on with the code: a counted attempt, like the code change's current code.
     * [UnlockAttemptResult.Unlocked]: on now; [UnlockAttemptResult.VaultUnavailable] also when
     * biometrics cannot be used on this phone right now.
     */
    suspend fun enableBiometricUnlock(code: String): UnlockAttemptResult = UnlockAttemptResult.VaultUnavailable

    /**
     * Checks the current code for a code change. It goes through the lock's throttle (PROTOCOL
     * §5.2): a wrong code here counts as a wrong unlock attempt, so this screen is never a way around
     * the delays.
     */
    suspend fun verifySecretCode(code: String): UnlockAttemptResult

    /**
     * Re-keys the vault from [current] to [new] (both already validated).
     *
     * @throws MessengerException.ChangeSecretCode the current code was wrong, or re-keying failed.
     */
    suspend fun changeSecretCode(current: String, new: String)

    /**
     * Owner rule: the backup password must differ from the secret code. Never counted as an unlock
     * attempt. 7.2 no longer asks: the §5.5 minimum of 10 characters already keeps a six-digit code
     * out.
     */
    suspend fun backupPasswordIsSecretCode(password: String): Boolean

    /** Why a newly chosen backup password is refused (PROTOCOL §5.5), or `null`. Opening a backup never checks the rules. */
    fun backupPasswordProblem(password: String): BackupPasswordError?

    /**
     * A new random backup password (PROTOCOL §5.5, generated passwords): 20 symbols of
     * [BackupPasswordRules.GENERATED_ALPHABET] from a secure random source, as five groups of four
     * joined by `-` (`k7mq-x2pd-9tfa-wn3h-b8ze`). It always passes [backupPasswordProblem].
     */
    fun generateBackupPassword(): String

    /**
     * 7.2: encrypts a backup on the device with [password] (PBKDF2-HMAC-SHA256, 1,000,000
     * iterations, then AES-256-GCM in the live implementation; PROTOCOL.md §5.5) into a single file
     * named [fileName] inside the app's private temporary storage. [progress] gets 0…1. The live
     * account backup holds the account, identity key and contacts, never messages or media, whatever
     * [contents] says.
     *
     * @throws MessengerException.BackupPassword the password breaks a §5.5 rule.
     */
    suspend fun exportBackup(contents: BackupContents, password: String, fileName: String, progress: (Double) -> Unit): URI

    /**
     * The share / export sheet closed. The temporary file is deleted either way; [saved] records
     * the backup date (7.1 › Yedek · Son: …), which the live service keeps in the vault.
     */
    suspend fun finishExport(uri: URI, saved: Boolean)

    /**
     * 7.3: copies the picked file into the app's private storage and reads its unencrypted header
     * (size, date).
     *
     * @throws MessengerException.BackupFile the file is not a backup that can be read.
     */
    suspend fun inspectBackup(uri: URI): BackupFileInfo

    /**
     * Checks the backup password. The live implementation also verifies every field and asks the
     * server, read-only, whether the backup's token still works, so [BackupOpenResult.Opened] means
     * a restore will accept it; nothing changes on the phone.
     */
    suspend fun openBackup(file: BackupFileInfo, password: String): BackupOpenResult

    /**
     * Restores an opened backup: keys → messages → media ([backupsIncludeHistory]), or only the
     * verified keys of a live account backup, whose account is restored with the new code at 2.3.
     * The restore runs while the flow is collected; it completes when the restore is done and
     * throws when it failed.
     */
    fun restoreBackup(file: BackupFileInfo): Flow<RestoreProgress>

    /** Stops a restore and removes what was written so far. */
    suspend fun cancelRestore()

    // MARK: Live wiring (defaults: the fixtures need none of it)

    /** An account exists on this device (also while locked, when [account] is `null` in the live service: nothing decrypted stays in memory). */
    val hasAccount: StateFlow<Boolean> get() = account.mapState { it != null }

    /** “+” sends the design's sample content (fixtures) instead of opening the system pickers and the location. */
    val sendsDesignContent: Boolean get() = true

    /**
     * A restored backup brings message history and media back (the design's planned backup,
     * fixtures). The live account backup never does (PROTOCOL §5.5): 7.3 then lists only the keys
     * step and says so.
     */
    val backupsIncludeHistory: Boolean get() = true

    /** The app's phase (PROTOCOL §5.4: connect only while active and unlocked, the background grace period, lock on return). */
    fun scenePhaseChanged(phase: MessengerScenePhase) {}

    /** 5.1 is on screen: its messages load, receipts and disappearing timers start. */
    suspend fun conversationAppeared(chat: ChatId) {}

    fun conversationDisappeared(chat: ChatId) {}

    /** “+” › Konum: the current place (asks for location while in use the first time). */
    suspend fun sendCurrentLocation(chat: ChatId) {}

    /**
     * A restore's token rotation got no answer yet (PROTOCOL §5.5 step 3.4): until the server
     * confirms it, the old phone may still have access to the account, and this phone does not
     * connect. 4.1 and 7.1 say so; the live service asks again with every connection attempt.
     */
    val restoreAccessPending: StateFlow<Boolean> get() = NotPending

    /** “Şimdi kontrol et”: asks the server now (§5.5 step 3.4). Never a false all-clear: a service that cannot ask says it got no answer. */
    suspend fun checkRestoreAccess(): RestoreAccessCheck = RestoreAccessCheck.Unanswered

    // MARK: Conveniences

    /** The user's own username (“sen”); empty before onboarding finished. */
    val ownUsername: StateFlow<String> get() = account.mapState { it?.username.orEmpty() }

    /** Read receipts are mutual: off on this device → no “Okundu” anywhere (5.4). */
    val readReceiptsEnabled: StateFlow<Boolean> get() = settings.mapState { it.readReceipts }

    /** The peer of a chat (username, avatar palette, verified flag). */
    fun peer(chat: ChatId): StateFlow<Peer?> = this.chat(chat).mapState { it?.peer }
}

private val DefaultOwnAvatar: StateFlow<OwnAvatarState> = constantStateFlow(OwnAvatarState())
private val EmptyLiveLocations: StateFlow<LiveLocationSnapshot> = constantStateFlow(LiveLocationSnapshot.Empty)
private val ZeroRevision: StateFlow<Int> = constantStateFlow(0)
private val NotPending: StateFlow<Boolean> = constantStateFlow(false)

/** The app phases the messenger cares about. */
enum class MessengerScenePhase { Active, Inactive, Background }

/** Outcome of a secret-code attempt on the lock screen. */
sealed interface UnlockAttemptResult {
    data object Unlocked : UnlockAttemptResult

    /** Wrong code; the status says what the next wrong codes cost. */
    data class WrongCode(val status: UnlockStatus) : UnlockAttemptResult

    /** Too many wrong codes: wait until the time. */
    data class Throttled(val until: Instant) : UnlockAttemptResult

    /** Not six digits: refused before counting (PROTOCOL §5.2). */
    data object Malformed : UnlockAttemptResult

    /**
     * The vault could not be read right now (the keystore refused, the store did not open, another
     * unlock was running). Not a wrong code and not counted: the code may be right, and trying again
     * a little later can work (PROTOCOL §5.2 › Errors).
     */
    data object VaultUnavailable : UnlockAttemptResult

    /**
     * The vault on this phone can never be opened here: its device secret or hardware key is gone,
     * or the file is damaged. Not a wrong code and not counted; only a backup brings the account
     * back.
     */
    data object VaultUnusable : UnlockAttemptResult

    /** The app left the foreground while the code was checked: it stays locked, nothing is said, and the code is entered again. */
    data object Interrupted : UnlockAttemptResult

    /**
     * The optional “wipe after 10 wrong codes” fired (PROTOCOL §5.2): this was the 10th wrong code
     * in a row, and everything on this phone has been erased. Not “Kod yanlış”: the lock says what
     * happened and offers to restore a backup or start over.
     */
    data object Wiped : UnlockAttemptResult
}

/** What “Şimdi kontrol et” found out about a restore's unanswered token rotation (PROTOCOL §5.5 step 3.4). */
enum class RestoreAccessCheck {
    /** Confirmed: the old phone has no access any more. */
    Closed,

    /** Still no answer from the server; tried again with every connection. */
    Unanswered,

    /** The server refused both tokens: the same backup was restored on another phone first, or the account was deleted. This phone is signed out. */
    Refused,
}

/** A failure of a [MessengerService] action that the screens tell apart. */
sealed class MessengerException(message: String) : Exception(message) {
    /** 2.3: the account was not created. */
    class AccountCreation(val failure: AccountCreationFailure) : MessengerException("account not created: $failure")

    /** 2.3: setup could not finish, yet a vault is on this phone, locked. */
    class SetupInterrupted(val interruption: SetupInterruption) : MessengerException("setup interrupted: $interruption")

    /** 2.3 after a restore: the backup can never be restored. */
    class Restore(val failure: RestoreFailure) : MessengerException("restore failed: $failure")

    /** 7.1 › Gizli kodu değiştir. */
    class ChangeSecretCode(val error: ChangeSecretCodeError) : MessengerException("secret code not changed: $error")

    /** 7.3: the picked file. */
    class BackupFile(val error: BackupFileError) : MessengerException("backup file refused: $error")

    /** 7.2: the password breaks a §5.5 rule. */
    class BackupPassword(val error: BackupPasswordError) : MessengerException("backup password refused: $error")

    /** 7.1 › Profil görseli. */
    class AvatarEdit(val error: AvatarEditError) : MessengerException("avatar not set: $error")

    /** The relay did not answer or refused (offline, a timeout, a server error). */
    class RelayUnreachable(detail: String = "relay unreachable") : MessengerException(detail)
}
