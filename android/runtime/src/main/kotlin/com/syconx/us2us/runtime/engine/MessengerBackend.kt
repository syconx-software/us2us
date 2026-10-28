package com.syconx.us2us.runtime.engine

import com.google.protobuf.ByteString
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.contacts.Contact
import com.syconx.us2us.core.contacts.ContactState
import com.syconx.us2us.core.contacts.ContactStore
import com.syconx.us2us.core.contacts.MessageRequests
import com.syconx.us2us.core.messenger.AvatarRecordStatus as CoreAvatarRecordStatus
import com.syconx.us2us.core.messenger.ConnectionRejectedException
import com.syconx.us2us.core.messenger.ConnectionState
import com.syconx.us2us.core.messenger.IncomingMessage
import com.syconx.us2us.core.messenger.Messenger
import com.syconx.us2us.core.messenger.OutgoingAttachment
import com.syconx.us2us.core.messenger.TokenRotationResult as CoreTokenRotationResult
import com.syconx.us2us.core.profile.ProfilePicture
import com.syconx.us2us.core.proto.Attachment
import com.syconx.us2us.core.proto.Content
import com.syconx.us2us.core.relay.RelayClient
import com.syconx.us2us.core.safety.SafetyNumber
import com.syconx.us2us.core.username.Username
import com.syconx.us2us.runtime.net.RelayStream
import com.syconx.us2us.runtime.net.RelayStreamException
import com.syconx.us2us.runtime.storage.ContactIdentity
import com.syconx.us2us.runtime.storage.IdentityVerification
import com.syconx.us2us.runtime.storage.LocalStorage
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.InvalidKeyException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds

/**
 * Marks the coroutines of the core's receive loop: what runs there writes contacts on behalf of a
 * received message ([ReceiveGuardedContactStore]).
 */
internal object ReceivePath {
    private val marker = ThreadLocal<Boolean?>()

    val isActive: Boolean get() = marker.get() == true

    fun element() = marker.asContextElement(true)
}

/**
 * The core's `ContactStore` between the core's `Messenger` and the storage's contacts table.
 *
 * The core records what a received message says about its sender (a new request, `ACCEPT`, the
 * verified `sender_username`, an access key, a profile) *before* it hands the message over. Here
 * the engine applies all of that itself, in the one transaction that stores the message (as the
 * iOS engine does), so those writes are not passed on: the table keeps the state before the
 * message. The verified `sender_username` of a message travels with it
 * (`IncomingMessage.verifiedSenderUsername`), also when the name was verified before, so the
 * §6.4 re-accept rule fires every time (audit 2026-10-28 M-9). Bookkeeping of the core's own sends
 * on the receive path (which contact holds our access key and profile, an access key the relay
 * refused) is written through.
 */
internal class ReceiveGuardedContactStore(private val delegate: ContactStore) : ContactStore {
    override fun get(accountId: AccountId): Contact? = delegate.get(accountId)

    override fun all(): List<Contact> = delegate.all()

    override fun remove(accountId: AccountId) = delegate.remove(accountId)

    override fun put(contact: Contact) {
        if (!ReceivePath.isActive) {
            delegate.put(contact)
            return
        }
        val stored = delegate.get(contact.accountId)
        if (stored != null && isBookkeeping(stored, contact)) delegate.put(contact)
    }

    /** Only which keys and profiles the contact holds changed, or an access key the relay refused was dropped. */
    private fun isBookkeeping(stored: Contact, new: Contact): Boolean {
        val comparable = new.copy(
            accessKeyShared = stored.accessKeyShared,
            profileShared = stored.profileShared,
            theirAccessKey = if (new.theirAccessKey == null) stored.theirAccessKey else new.theirAccessKey,
        )
        return comparable == stored
    }
}

/**
 * [RelayStream] on the core's `Messenger.runReceiveLoop`, one socket at a time.
 *
 * Atomic receive: the loop runs inside the storage's `withStagedInboundWrites`, so the libsignal
 * state a decryption advances is committed in the same transaction that stores the message
 * ([MessagingEngine.receive] does one write per message).
 *
 * The core's loop reconnects by itself; here the run ends when its socket closes (the supervisor
 * backs off and opens the next one), and `queue_empty` is read from the core's connection state.
 */
internal class MessengerStream(
    private val messenger: Messenger,
    private val storage: LocalStorage,
    private val contacts: ReceiveGuardedContactStore,
) : RelayStream {
    private val lock = Any()
    private var closed = false
    private var loop: Job? = null

    override suspend fun run(deliver: suspend (IncomingMessage) -> Unit, onQueueEmpty: suspend () -> Unit) {
        supervisorScope {
            val streamScope = this
            val receive: Deferred<Unit> = async(ReceivePath.element(), start = CoroutineStart.LAZY) {
                storage.protocolStorage.withStagedInboundWrites {
                    messenger.runReceiveLoop { message ->
                        val username = message.verifiedSenderUsername
                        try {
                            deliver(
                                IncomingMessage(
                                    envelopeId = message.envelopeId,
                                    serverTimestamp = message.serverTimestamp,
                                    sender = message.sender,
                                    senderUsername = username?.value,
                                    content = message.content,
                                    contactState = message.contactState,
                                    identityChanged = message.identityChanged,
                                    verifiedSenderUsername = username,
                                ),
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // The vault closed under the message: it waits for the next connection
                            // without counting as a failed attempt (only a message the engine cannot
                            // take counts, and is dropped after a few, H-1).
                            if (isStoreUnavailable(e)) throw IOException("local store unavailable")
                            throw e
                        }
                    }
                }
            }
            val alreadyClosed = synchronized(lock) {
                loop = receive
                closed
            }
            if (alreadyClosed) {
                receive.cancel()
                return@supervisorScope
            }
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                var previous: ConnectionState? = null
                var announced = false
                messenger.connection.collect { state ->
                    val ended = when (state) {
                        ConnectionState.DISCONNECTED -> previous != null && previous != ConnectionState.DISCONNECTED
                        ConnectionState.CONNECTING -> previous == ConnectionState.CONNECTED || previous == ConnectionState.UP_TO_DATE
                        else -> false
                    }
                    if (previous == null && state == ConnectionState.DISCONNECTED) return@collect
                    previous = state
                    if (state == ConnectionState.UP_TO_DATE && !announced) {
                        announced = true
                        // A sibling of the watcher: a socket that closes meanwhile does not cut it short.
                        streamScope.launch { onQueueEmpty() }
                    }
                    if (ended) {
                        // A close the core treats as final throws right after this state; let it surface first.
                        delay(SETTLE)
                        receive.cancel()
                    }
                }
            }
            receive.start()
            try {
                receive.await()
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                if (synchronized(lock) { closed }) return@supervisorScope
                throw RelayStreamException(cause = e)
            } finally {
                watcher.cancel()
            }
        }
    }

    override fun close() {
        val job = synchronized(lock) {
            closed = true
            loop
        }
        job?.cancel()
    }

    private companion object {
        val SETTLE = 50.milliseconds
    }
}

/**
 * The production backend: the core's `Messenger` (all libsignal state, contacts on the storage's
 * table through [ReceiveGuardedContactStore]) and its relay client.
 *
 * Where the core does a step itself, the engine leaves it to the core:
 * * presence (§8.6) runs inside the core's receive loop ([runsPresence]);
 * * profile changes are announced by the core ([announcesProfileChanges]), which keeps the
 *   account the backup is made from in step;
 * * the profile announcement after a restore is a pending setup step the core finishes;
 * * a restore's token rotation whose answer was lost is settled by the core, which sends nothing
 *   else with either token until then (§5.5 step 3.4).
 */
class MessengerBackend internal constructor(
    val messenger: Messenger,
    private val storage: LocalStorage,
    /** The unauthenticated client (sealed sends, media). */
    private val relay: RelayClient,
    private val contacts: ReceiveGuardedContactStore,
) : MessagingBackend {
    override val accountId: AccountId = messenger.accountId

    override suspend fun username(): Username = messenger.account.username

    override suspend fun currentAccessKey(): ByteString = ByteString.copyFrom(messenger.account.accessKey.toBytes())

    /**
     * Settles a token rotation whose answer was lost first (§5.5 step 3.4): until the relay has said
     * which token the account has, the socket does not open, and the supervisor's backoff asks
     * again. A relay that refuses both tokens ends the connection like any revoked token.
     */
    override suspend fun openStream(): RelayStream {
        if (messenger.finishPendingTokenRotation() == CoreTokenRotationResult.BACKUP_NO_LONGER_VALID) {
            throw ConnectionRejectedException(code = null, httpStatus = HTTP_UNAUTHORIZED)
        }
        return MessengerStream(messenger, storage, contacts)
    }

    override suspend fun send(
        content: Content,
        recipient: AccountId,
        requestState: ContactState,
        theirAccessKey: ByteString?,
        ephemeral: Boolean,
    ): SendOutcome {
        if (requestState == ContactState.INCOMING_REQUEST && MessageRequests.isAcceptance(content)) {
            // ACCEPT ends the request on both sides; the core sends it before anything else.
            messenger.acceptMessageRequest(recipient)
            return SendOutcome(System.currentTimeMillis())
        }
        val receipt = messenger.send(recipient, content, ephemeral)
        return SendOutcome(receipt.serverTimestamp)
    }

    override suspend fun sendPresence(presence: Content, contacts: List<Recipient>): List<AccountId> {
        val addressed = ArrayList<AccountId>()
        for (contact in contacts) {
            try {
                messenger.send(contact.account, presence, ephemeral = true)
                addressed += contact.account
            } catch (_: IOException) {
            }
        }
        return addressed
    }

    override val runsPresence: Boolean get() = true

    override suspend fun announceOffline() = messenger.announceOffline()

    override suspend fun performMaintenance() {
        messenger.performMaintenance()
    }

    override suspend fun rotateAccessKey(): ByteString {
        messenger.rotateAccessKey()
        return currentAccessKey()
    }

    override suspend fun announceProfile(profile: Content, contacts: List<Recipient>): List<AccountId> = emptyList()

    override suspend fun needsProfileAnnouncement(): Boolean = false

    override val announcesProfileChanges: Boolean get() = true

    override suspend fun updateProfile(displayName: String, presenceSharing: Boolean) {
        val account = messenger.account
        if (account.displayName != displayName) messenger.setDisplayName(displayName)
        if (account.presenceSharing != presenceSharing) messenger.setPresenceSharing(presenceSharing)
    }

    override suspend fun uploadAttachment(file: File, kind: Attachment.Kind, contentType: String, fileName: String?): Attachment =
        messenger.uploadAttachment(OutgoingAttachment(kind, contentType, { file.inputStream() }, fileName.orEmpty()))

    override suspend fun downloadAttachment(attachment: Attachment, destination: File, progress: (Double) -> Unit) {
        withContext(Dispatchers.IO) { FileOutputStream(destination) }.use { output ->
            messenger.downloadAttachment(attachment, output, progress = progress)
        }
    }

    override suspend fun deleteUploadedMedia(attachment: Attachment) {
        relay.deleteMedia(attachment.mediaId.toByteArray(), attachment.deleteToken.toByteArray())
    }

    /** Records no contact; while a token rotation is unsettled nothing is asked (the core throws). */
    override suspend fun findAccount(username: Username): DirectoryEntry? =
        messenger.findAccount(username)?.let { DirectoryEntry(it.accountId, it.identityKey) }

    override suspend fun usernameOwner(username: Username): AccountId? = findAccount(username)?.account

    override suspend fun changeUsername(username: Username) {
        messenger.changeUsername(username)
    }

    override suspend fun safetyNumber(contact: AccountId): SafetyNumber? = messenger.safetyNumber(contact)

    override suspend fun identityTrust(contact: AccountId): ContactIdentity = storage.protocolStorage.identityTrust(contact)

    private fun numberOf(contact: AccountId, key: IdentityKey): SafetyNumber =
        SafetyNumber.compute(accountId, messenger.account.identityKeyPair.publicKey, contact, key)

    private fun currentKey(trust: ContactIdentity): IdentityKey? = when (trust) {
        ContactIdentity.Unknown -> null
        is ContactIdentity.Trusted -> trust.key
        is ContactIdentity.Changed -> trust.current
    }

    override suspend fun approveIdentityChange(contact: AccountId, shownNumber: ByteArray): Boolean {
        val trust = storage.protocolStorage.identityTrust(contact) as? ContactIdentity.Changed ?: return false
        if (!numberOf(contact, trust.current).scannable.contentEquals(shownNumber)) return false
        return storage.protocolStorage.acceptIdentity(trust.current, contact)
    }

    override suspend fun markVerified(contact: AccountId, comparedNumber: ByteArray, verification: IdentityVerification): Boolean {
        val key = currentKey(storage.protocolStorage.identityTrust(contact)) ?: return false
        if (!numberOf(contact, key).scannable.contentEquals(comparedNumber)) return false
        return storage.protocolStorage.verifyIdentity(key, contact, verification)
    }

    override suspend fun verifySafetyNumber(contact: AccountId, scanned: ByteArray, verification: IdentityVerification): Boolean {
        val key = currentKey(storage.protocolStorage.identityTrust(contact)) ?: return false
        if (!numberOf(contact, key).matchesScanned(scanned)) return false
        return storage.protocolStorage.verifyIdentity(key, contact, verification)
    }

    override suspend fun pinVerifiedIdentity(identityKey: ByteArray, contact: AccountId, verification: IdentityVerification): Boolean {
        val key = try {
            IdentityKey(identityKey)
        } catch (_: InvalidKeyException) {
            return false
        }
        return storage.protocolStorage.pinVerifiedIdentity(key, contact, verification)
    }

    override suspend fun clearVerification(contact: AccountId) = storage.protocolStorage.setIdentityVerified(false, contact)

    override suspend fun identityVerification(contact: AccountId): IdentityVerification? =
        storage.protocolStorage.identityVerification(contact)

    override suspend fun forgetContact(contact: AccountId) = messenger.forgetContact(contact)

    override suspend fun forgetContactKeepingSession(contact: AccountId) = messenger.forgetContactKeepingSession(contact)

    override suspend fun exportBackup(password: String): ByteArray = messenger.exportBackup(password)

    override suspend fun deleteAccount() = messenger.deleteAccount()

    override suspend fun ownIdentityKey(): ByteArray = messenger.account.identityKeyPair.publicKey.serialize()

    override suspend fun hasPendingTokenRotation(): Boolean = messenger.account.pendingAuthToken != null

    override suspend fun finishPendingTokenRotation(): TokenRotationResult = tokenRotationResult(messenger.finishPendingTokenRotation())

    override suspend fun ownAvatar(): OwnProfilePicture {
        val account = messenger.account
        return OwnProfilePicture(account.profilePicture, account.showPictureInSearch, avatarRecordStatus(messenger.avatarRecordStatus.value))
    }

    override suspend fun setOwnAvatar(picture: ProfilePicture?, showInSearch: Boolean) {
        val account = messenger.account
        if (picture != account.profilePicture) messenger.setProfilePicture(picture)
        if (showInSearch != account.showPictureInSearch) messenger.setShowPictureInSearch(showInSearch)
    }

    override suspend fun syncAvatarRecord(): AvatarRecordStatus = avatarRecordStatus(messenger.trySyncAvatarRecord())

    override suspend fun searchAvatar(username: Username, account: AccountId): ProfilePicture? = messenger.searchAvatar(username, account)

    override fun close() = messenger.clearSearchPictures()

    internal companion object {
        private const val HTTP_UNAUTHORIZED = 401

        /** The core's outcome in the engine's words. */
        fun tokenRotationResult(result: CoreTokenRotationResult): TokenRotationResult = when (result) {
            CoreTokenRotationResult.READY -> TokenRotationResult.READY
            CoreTokenRotationResult.BACKUP_NO_LONGER_VALID -> TokenRotationResult.BACKUP_NO_LONGER_VALID
        }

        /** The core's record status in the engine's words. */
        fun avatarRecordStatus(status: CoreAvatarRecordStatus): AvatarRecordStatus = when (status) {
            CoreAvatarRecordStatus.IN_SYNC -> AvatarRecordStatus.IN_SYNC
            CoreAvatarRecordStatus.PENDING -> AvatarRecordStatus.PENDING
            CoreAvatarRecordStatus.UNAVAILABLE -> AvatarRecordStatus.UNAVAILABLE
        }
    }
}

/** Whether [error] means the engine or its storage is closed (the vault locked), not that the message is bad. */
internal fun isStoreUnavailable(error: Throwable): Boolean = when (error) {
    is EngineException -> error.error == EngineError.STOPPED
    is com.syconx.us2us.runtime.storage.StorageException -> error.error == com.syconx.us2us.runtime.storage.StorageError.Closed
    is IOException -> true
    else -> false
}
