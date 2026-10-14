package com.syconx.us2us.domain.harness

import com.syconx.us2us.domain.AvatarPhotoData
import com.syconx.us2us.domain.Biometry
import com.syconx.us2us.domain.Bytes
import com.syconx.us2us.domain.ConnectionState
import com.syconx.us2us.domain.CropRect
import com.syconx.us2us.domain.DesignArtwork
import com.syconx.us2us.domain.DisappearingTimer
import com.syconx.us2us.domain.MediaAvailability
import com.syconx.us2us.domain.MessageStatus
import com.syconx.us2us.domain.MessengerException
import com.syconx.us2us.domain.OwnAvatarState
import com.syconx.us2us.domain.OwnPositionState
import com.syconx.us2us.domain.BackupOpenResult
import com.syconx.us2us.domain.RestoreAccessCheck
import com.syconx.us2us.domain.UnlockAttemptResult
import com.syconx.us2us.domain.VerificationMethod
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.MessageId
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The knobs the harnesses (and UI tests) turn on the design's data: every state the iOS harnesses
 * set on `DesignFixturesService`, plus a few that iOS sets on its view models directly and that are
 * easier to reach here on Android. Implemented by `FixturesMessengerService`; the live service has
 * none (harnesses get `null`).
 *
 * Changes show at once through the service's flows. Every member is called on the main thread.
 */
interface FixtureControls {
    // MARK: Line & lists (04)

    /** Switches the line at the top of 4.1 (`4.1-connecting`, `4.1-closed`). */
    fun setConnection(state: ConnectionState)

    /** No chats left (`4.1-empty`). */
    fun clearChats()

    /** No requests left (`4.1-empty`, `4.3-empty`). */
    fun clearRequests()

    /** The design's chats again, with Ghost counters in their last hour or not (`U2GhostUrgent`). */
    fun setGhostUrgent(urgent: Boolean)

    /**
     * The chat's person blocked before the first frame, as `block(chat)` does (`chats:4.1-blocked`,
     * `conversation:deniz/5.1-blocked`, `safety:<chat>/blocked`). `false` without that chat.
     */
    fun setBlocked(chat: ChatId): Boolean = false

    /**
     * 7.1 › Engellenenler's two kinds (`settings:7.1-blocked`, `settings:blocked`): bora blocked (the
     * chat stays in 4.1, read only) and lale, a contact blocked before Ghost erased the chat.
     */
    fun loadBlockedPeople() {}

    // MARK: Lock (03) and setup (02)

    /** [failures] wrong codes in a row so far, with no wait running (the delays would take an hour to reach the 10th). */
    fun setWrongCodes(failures: Int)

    /** The optional “wipe after 10 wrong codes” (PROTOCOL §5.2; off by default, as in the live app): the 10th wrong code in a row erases everything (`lock:wiped`). */
    var wipesAfterTenWrongCodes: Boolean

    /**
     * The next unlock or code check answers this, once, instead of checking the code
     * ([UnlockAttemptResult.VaultUnavailable] for `lock:unavailable`, [UnlockAttemptResult.VaultUnusable]
     * for `lock:unusable`); `null` checks the code.
     */
    var nextUnlockResult: UnlockAttemptResult?

    /**
     * What biometric unlock answers: `true` opens, `false` fails (the keypad stays), `null` asks the
     * platform check the app installed (and fails without one). The harness never evaluates biometrics.
     */
    var biometricsResult: Boolean?

    /**
     * The biometrics the phone reports to the lock instead of the real ones (`lock:faceid` draws
     * [Biometry.Face], `lock:touchid` [Biometry.Fingerprint]); `null` asks the device. The app's
     * biometry provider reads it in debug builds.
     */
    var biometry: Biometry?

    /** 7.1 › biometric unlock is turned on through the code page (as in the live app) instead of the system prompt (`settings:faceid`). */
    var biometricUnlockNeedsCode: Boolean

    /**
     * The next account creation or restore completion (2.3) throws this, once: a taken name
     * ([MessengerException.AccountCreation]), an account kept locked
     * ([MessengerException.SetupInterrupted]), a backup gone or damaged ([MessengerException.Restore]),
     * or any other exception for a network error. `null` lets it succeed (the name `ilgaz` is still
     * taken at setup, as on iOS).
     */
    var nextSetupFailure: Exception?

    // MARK: Backup and restore (07)

    /** A restore's token rotation is still unanswered (PROTOCOL §5.5 step 3.4); 4.1 and 7.1 show the notice (`7.1-access`). */
    fun setRestoreAccessPending(pending: Boolean)

    /** What “Şimdi kontrol et” finds out next. */
    var restoreAccessAnswer: RestoreAccessCheck

    /** What the next backup password check answers, once, whatever the password; `null` checks it. */
    var nextBackupOpenResult: BackupOpenResult?

    /** The simulated relay cannot be reached when 7.1 deletes the account (`settings:erase-failed`). */
    var failsAccountDeletion: Boolean

    // MARK: Conversation (05)

    /** Overrides a message's delivery state (failed / pending demos, the status cross-fade); [at] defaults to the clock's now. */
    fun setStatus(status: MessageStatus, message: MessageId, chat: ChatId, at: Instant? = null)

    /** Where a photo, video or file message's file is (5.2 states). */
    fun setMediaAvailability(availability: MediaAvailability, message: MessageId, chat: ChatId)

    /** Makes a message disappearing (its file then previews without sharing). */
    fun setDisappearing(timer: DisappearingTimer, message: MessageId, chat: ChatId)

    /** The other side saved one of the user's own photos, videos or files (the “Kaydetti” chip). */
    fun setSavedByPeer(message: MessageId, chat: ChatId)

    /**
     * The contact's own live share (5.2 › harita): an incoming live location from the chat's peer,
     * starting at Moda İskelesi with 38 minutes left. With a running clock it walks towards selin_n.
     */
    fun startDemoLiveLocation(chat: ChatId): MessageId

    /** An incoming PDF from the chat's peer (“kat-plani.pdf”, 1,4 MB, two pages) with its file where [availability] says. */
    fun addIncomingFile(chat: ChatId, availability: MediaAvailability): MessageId

    /** An incoming voice message from the chat's peer, not played yet (0:09, the design's waveform). */
    fun addIncomingVoice(chat: ChatId, availability: MediaAvailability = MediaAvailability.Ready): MessageId

    /** A video sent from this phone while it is prepared (“Hazırlanıyor”); [failed]: preparing it failed (“Tekrar gönder”). */
    fun addOutgoingVideo(chat: ChatId, failed: Boolean = false): MessageId

    /** The video picked in “+”, as the live service shows it: preparing for [duration], then sent in the same bubble. */
    fun simulateVideoSend(chat: ChatId, duration: Duration = 2400.milliseconds)

    /** “Downloads” an attachment over [duration], the ring filling in 24 steps. */
    fun simulateDownload(message: MessageId, chat: ChatId, duration: Duration = 2400.milliseconds)

    /** Media requests wait instead of answering (the player's loading state, `5.2-player-loading`). */
    var holdsMediaPayloads: Boolean

    /** What the map's own-position flow reports instead of the simulated position (`5.2-map-ask`, `5.2-map-denied`); `null` simulates. */
    var ownPositionOverride: OwnPositionState?

    /** Android: notifications are off, so a live share would stop when the app leaves (the share sheet first, `5.1-live-share`). */
    var liveSharingNoticeOff: Boolean

    // MARK: Verification (06)

    /** The peer's safety number changed (new device): 6.1's danger banner, no seal. */
    suspend fun setSafetyNumberChanged(chat: ChatId)

    /** Verifies [chat] at once (no number to compare), with the 6.3 system line the first time. */
    suspend fun markVerified(chat: ChatId, method: VerificationMethod)

    // MARK: Profile pictures

    /** The own picture, “Aramada göster” and the search copy's state, as 7.1 shows them. */
    fun setOwnAvatar(state: OwnAvatarState)
}

// Platform pieces the fixtures cannot do on the JVM. The app's debug wiring installs Android
// implementations on the fixtures service; without them the fixtures fall back as documented.

/** Asks for the user's biometrics (no crypto object: the fixtures have no key to open). */
fun interface BiometricCheck {
    /**
     * `true` when the user authenticated; `false` on failure or cancel. [cancelTitle] labels the
     * negative button (“Kodla aç”); `null` takes the platform's own (turning biometric unlock on).
     */
    suspend fun authenticate(reason: String, cancelTitle: String?): Boolean
}

/**
 * Encodes the design's painted stand-ins as real files, so the viewer's save, the player and the
 * share sheet get bytes (iOS `FixtureMedia`). Without one, a design photo has no payload (screens
 * draw the artwork itself) and the design video does not play.
 */
interface DesignArtworkEncoder {
    /** The artwork as a JPEG, [pixelWidth] wide. */
    suspend fun jpeg(artwork: DesignArtwork, pixelWidth: Int): Bytes?

    /** The 5.2 clip: 18 s of [artwork], slowly drifting, H.264 640 × 360, no sound. */
    suspend fun mp4(artwork: DesignArtwork): Bytes?
}

/**
 * Makes a picked photo into a profile photo: [crop] (in the photo's pixels as displayed; `null`
 * for the whole photo) scaled to at most 256 × 256, stripped of all metadata, re-encoded (§8.9).
 * `null` when the photo cannot be read.
 */
fun interface AvatarPhotoPreparer {
    suspend fun prepare(data: Bytes, crop: CropRect?): AvatarPhotoData?
}
