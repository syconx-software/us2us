package com.syconx.us2us.core.messenger

import com.google.protobuf.InvalidProtocolBufferException
import com.google.protobuf.kotlin.toByteString
import com.syconx.us2us.core.AccessKey
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.AuthToken
import com.syconx.us2us.core.attachment.AttachmentCipher
import com.syconx.us2us.core.attachment.AttachmentIntegrityException
import com.syconx.us2us.core.contacts.Contact
import com.syconx.us2us.core.contacts.ContactState
import com.syconx.us2us.core.contacts.ContactStore
import com.syconx.us2us.core.contacts.InMemoryContactStore
import com.syconx.us2us.core.contacts.MessageRequests
import com.syconx.us2us.core.contacts.UsernameStatus
import com.syconx.us2us.core.crypto.Random
import com.syconx.us2us.core.keys.InvalidBundleException
import com.syconx.us2us.core.keys.Keys
import com.syconx.us2us.core.keys.PreKeyManager
import com.syconx.us2us.core.keys.toPreKeyBundle
import com.syconx.us2us.core.pow.ProofOfWork
import com.syconx.us2us.core.presence.PresencePolicy
import com.syconx.us2us.core.presence.PresenceStatus
import com.syconx.us2us.core.presence.PresenceTracker
import com.syconx.us2us.core.proto.AccountBackup
import com.syconx.us2us.core.proto.Attachment
import com.syconx.us2us.core.proto.BackupContact
import com.syconx.us2us.core.proto.Content
import com.syconx.us2us.core.proto.ControlMessage
import com.syconx.us2us.core.proto.DataMessage
import com.syconx.us2us.core.proto.DataMessageKt
import com.syconx.us2us.core.proto.PresenceMessage
import com.syconx.us2us.core.proto.ProfileUpdate
import com.syconx.us2us.core.proto.ReceiptMessage
import com.syconx.us2us.core.proto.accountBackup
import com.syconx.us2us.core.proto.attachment
import com.syconx.us2us.core.proto.backupContact
import com.syconx.us2us.core.proto.content
import com.syconx.us2us.core.proto.controlMessage
import com.syconx.us2us.core.proto.dataMessage
import com.syconx.us2us.core.proto.delete
import com.syconx.us2us.core.proto.location
import com.syconx.us2us.core.proto.profileUpdate
import com.syconx.us2us.core.proto.quote
import com.syconx.us2us.core.proto.reaction
import com.syconx.us2us.core.proto.receiptMessage
import com.syconx.us2us.core.proto.sealedContent
import com.syconx.us2us.core.proto.text
import com.syconx.us2us.core.proto.typingMessage
import com.syconx.us2us.core.relay.AccountIdentity
import com.syconx.us2us.core.relay.DeliveryAuthorization
import com.syconx.us2us.core.relay.EphemeralEnvelope
import com.syconx.us2us.core.relay.PowSubmission
import com.syconx.us2us.core.relay.RegistrationRequest
import com.syconx.us2us.core.relay.RelayClient
import com.syconx.us2us.core.relay.RelayErrorCode
import com.syconx.us2us.core.relay.RelayException
import com.syconx.us2us.core.relay.SendReceipt
import com.syconx.us2us.core.relay.SocketEvent
import com.syconx.us2us.core.relay.StoredEnvelope
import com.syconx.us2us.core.safety.SafetyNumber
import com.syconx.us2us.core.session.MessageReceiver
import com.syconx.us2us.core.session.ReceiveResult
import com.syconx.us2us.core.session.RelaySenderDirectory
import com.syconx.us2us.core.session.SealedEnvelope
import com.syconx.us2us.core.session.SenderVerifier
import com.syconx.us2us.core.session.SessionCrypto
import com.syconx.us2us.core.store.IdentityTrust
import com.syconx.us2us.core.store.InMemoryProtocolStorage
import com.syconx.us2us.core.store.ProtocolStorage
import com.syconx.us2us.core.store.ProtocolStore
import com.syconx.us2us.core.username.Username
import com.syconx.us2us.core.vault.BackupFile
import com.syconx.us2us.core.vault.BackupPasswordPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyException
import org.signal.libsignal.protocol.UntrustedIdentityException
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.Collections

/** A message that left this phone: its id (for receipts, reactions, deletes) and the relay's answer. */
class SentMessage(val messageId: ByteArray, val sentAt: Long, val receipt: SendReceipt)

/**
 * The high-level API of the protocol library: one account, its contacts, sessions and relay
 * connection. Used by the CLI bot and the apps.
 *
 * Sending: [send] (and the typed helpers) set up sessions on demand, enforce the message-request
 * rules of PROTOCOL.md §6.4, seal the envelope (§6) and deliver it with the contact's access key, or
 * with proof-of-work while no key is known. Receiving: [runReceiveLoop] holds the WebSocket and
 * hands each verified, decrypted message to a [MessageHandler] before acknowledging it; it also
 * sends delivery receipts (§8.4), keeps pre-keys in shape (§5) and runs presence (§8.6).
 *
 * All functions are main-safe; CPU-heavy work runs on [Dispatchers.Default], file I/O on
 * [Dispatchers.IO].
 */
class Messenger(
    account: LocalAccount,
    private val store: ProtocolStore,
    private val contacts: ContactStore,
    /** An unauthenticated client: sealed sends and media downloads must not carry the auth token. */
    private val publicRelay: RelayClient,
    private val config: MessengerConfig = MessengerConfig(),
    private val accountStore: AccountStore = AccountStore {},
) {
    init {
        require(store.identityKeyPair.serialize().contentEquals(account.identityKeyPair.serialize())) {
            "the protocol store belongs to a different identity"
        }
    }

    @Volatile
    private var currentAccount = account
    val account: LocalAccount get() = currentAccount
    val accountId: AccountId = account.accountId

    private val relay = publicRelay.withAuthToken(account.authToken)
    private val cryptoLock = Mutex()
    private val contactLock = Any()
    private val sessionSetupLock = Mutex()
    private val sessions = SessionCrypto(store, account.accountId, config.clock)
    private val directory = RelaySenderDirectory(relay)
    private val receiver = MessageReceiver(account.accountId, store, sessions, SenderVerifier(store, directory), cryptoLock)
    private val preKeys = PreKeyManager(store, config.preKeyPolicy, config.clock)
    private val handledEnvelopes = Collections.synchronizedSet(
        Collections.newSetFromMap(
            object : LinkedHashMap<String, Boolean>() {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>) = size > HANDLED_ENVELOPE_MEMORY
            },
        ),
    )

    private val receiptLock = Any()
    private val pendingReceipts = LinkedHashMap<AccountId, MutableList<ByteArray>>()

    private val connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connection: StateFlow<ConnectionState> = connectionState.asStateFlow()

    /** Contacts whose safety number just changed; see [ProtocolStore.identityChanges]. */
    val identityChanges: SharedFlow<AccountId> = store.identityChanges

    private val presenceTracker = PresenceTracker(config.clock)
    private val presenceChanges = MutableSharedFlow<AccountId>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Contacts whose presence just changed; read the new state with [presence]. */
    val presenceUpdates: SharedFlow<AccountId> = presenceChanges.asSharedFlow()

    // Directory and contacts

    /**
     * Looks up a username (exact match, §4.4) and records the account as a contact. Returns `null`
     * if the input is not a valid username, nobody has it, or it is our own.
     */
    suspend fun findByUsername(input: String): Contact? {
        val username = Username.parse(input) ?: return null
        val found: AccountIdentity = relay.lookupUsername(withContext(Dispatchers.Default) { username.hash() }) ?: return null
        if (found.accountId == accountId) return null
        return editContact(found.accountId) {
            (it ?: Contact(found.accountId, username.value, ContactState.OUTGOING_REQUEST))
                .copy(username = username.value, usernameStatus = UsernameStatus.VERIFIED)
        }
    }

    fun contact(accountId: AccountId): Contact? = contacts.get(accountId)

    /**
     * Checks [peer]'s claimed username against the directory (PROTOCOL.md §3.3) and remembers the
     * result on the contact, so each claim costs at most one rate-limited lookup. Call it lazily,
     * e.g. when the conversation is opened; show the username as verified only for
     * [UsernameStatus.VERIFIED]. A contact without a username stays [UsernameStatus.UNCHECKED].
     *
     * @throws IOException if the relay could not be asked (the status stays unchecked).
     */
    suspend fun verifyUsername(peer: AccountId): UsernameStatus {
        val contact = contacts.get(peer) ?: return UsernameStatus.UNCHECKED
        val claimed = contact.username ?: return UsernameStatus.UNCHECKED
        if (contact.usernameStatus != UsernameStatus.UNCHECKED) return contact.usernameStatus
        val status = if (directory.accountFor(Username(claimed)) == peer) UsernameStatus.VERIFIED else UsernameStatus.MISMATCH
        // A newer claim that arrived meanwhile is checked on its own.
        editContact(peer) { current -> current?.takeIf { it.username == claimed }?.copy(usernameStatus = status) }
        return status
    }

    // Sending

    /**
     * Encrypts, seals and delivers [content].
     *
     * @param ephemeral deliver only if the recipient is online right now (typing, live-location
     *   ticks); needs the contact's access key.
     * @throws SendNotAllowedException if the contact is blocked or wrote first and was not accepted.
     * @throws IdentityChangedException if the contact's safety number changed and was not accepted.
     * @throws AccessKeyRequiredException for an ephemeral send without the contact's access key.
     * @throws IOException on network failure, or [RelayException] if the relay refused.
     */
    suspend fun send(to: AccountId, content: Content, ephemeral: Boolean = false): SendReceipt {
        require(to != accountId) { "cannot send to the own account" }
        val contact = checkNotNull(editContact(to) { it ?: Contact(to, null, ContactState.OUTGOING_REQUEST) })
        if (!MessageRequests.maySend(contact.state)) throw SendNotAllowedException(to, contact.state)
        // An accepted contact gets our access key (and our profile) before anything else, so both
        // sides can stop paying proof-of-work. If that fails, it is offered again on the next send.
        if (!ephemeral && contact.state == ContactState.ACCEPTED && !content.sharesOwnState()) shareOwnState(to)
        return deliver(contacts.get(to) ?: contact, content, ephemeral)
    }

    /** Sends a `DataMessage` with a fresh id and timestamp; [configure] fills in the payload. */
    suspend fun sendDataMessage(to: AccountId, ephemeral: Boolean = false, configure: DataMessageKt.Dsl.() -> Unit): SentMessage {
        val message = dataMessage {
            messageId = Random.bytes(MESSAGE_ID_LENGTH).toByteString()
            sentAt = config.clock.millis()
            configure()
        }
        val receipt = send(to, content { data = message }, ephemeral)
        return SentMessage(message.messageId.toByteArray(), message.sentAt, receipt)
    }

    suspend fun sendText(to: AccountId, body: String, expireTimerSeconds: Int = 0, quotedMessageId: ByteArray? = null, quotePreview: String = ""): SentMessage {
        require(body.toByteArray(Charsets.UTF_8).size <= MAX_TEXT_BYTES) { "text exceeds $MAX_TEXT_BYTES UTF-8 bytes" }
        return sendDataMessage(to) {
            text = text { this.body = body }
            expireTimerSeconds.takeIf { it > 0 }?.let { this.expireTimerSeconds = it }
            quotedMessageId?.let { target ->
                quote = quote {
                    targetMessageId = target.toByteString()
                    previewText = quotePreview.take(MAX_QUOTE_PREVIEW_CHARS)
                }
            }
        }
    }

    suspend fun sendAttachment(to: AccountId, attachment: OutgoingAttachment, viewOnce: Boolean = false, expireTimerSeconds: Int = 0): SentMessage {
        val uploaded = uploadAttachment(attachment)
        return sendDataMessage(to) {
            this.attachment = uploaded
            this.viewOnce = viewOnce
            expireTimerSeconds.takeIf { it > 0 }?.let { this.expireTimerSeconds = it }
        }
    }

    suspend fun sendLocation(to: AccountId, latitude: Double, longitude: Double, accuracyMeters: Float, name: String = "", address: String = ""): SentMessage =
        sendDataMessage(to) {
            location = location {
                this.latitude = latitude
                this.longitude = longitude
                this.accuracyMeters = accuracyMeters
                this.name = name
                this.address = address
            }
        }

    suspend fun sendReaction(to: AccountId, targetMessageId: ByteArray, emoji: String, remove: Boolean = false): SentMessage =
        sendDataMessage(to) {
            reaction = reaction {
                this.targetMessageId = targetMessageId.toByteString()
                this.emoji = emoji
                this.remove = remove
            }
        }

    suspend fun deleteForEveryone(to: AccountId, targetMessageId: ByteArray): SentMessage =
        sendDataMessage(to) { delete = delete { this.targetMessageId = targetMessageId.toByteString() } }

    /**
     * Sends a batched receipt for at most [MAX_RECEIPT_IDS] messages, which keeps the envelope far
     * below the relay's 64 KiB limit. Returns `null` without sending while a message request is
     * pending (§6.4, §8.4); delivery receipts are otherwise always sent.
     */
    suspend fun sendReceipt(to: AccountId, type: ReceiptMessage.Type, messageIds: List<ByteArray>): SendReceipt? {
        require(messageIds.size <= MAX_RECEIPT_IDS) { "at most $MAX_RECEIPT_IDS ids per receipt" }
        val state = contacts.get(to)?.state ?: return null
        if (!MessageRequests.maySendReceiptsAndTyping(state) || messageIds.isEmpty()) return null
        return send(to, content { receipt = receiptMessage { this.type = type; this.messageIds += messageIds.map { it.toByteString() } } })
    }

    /**
     * Sends a typing indicator as an ephemeral message. Skipped (returns false) during message
     * requests and while we hold no access key, which ephemeral sends require (§4.6).
     */
    suspend fun sendTyping(to: AccountId, started: Boolean): Boolean {
        val contact = contacts.get(to) ?: return false
        if (!MessageRequests.maySendReceiptsAndTyping(contact.state) || contact.theirAccessKey == null) return false
        send(to, content { typing = typingMessage { this.started = started } }, ephemeral = true)
        return true
    }

    // Message requests (§6.4)

    /**
     * Accepts [from]'s message request: sends `ACCEPT`, then our access key.
     *
     * The conversation counts as accepted once `ACCEPT` is delivered, because the requester leaves
     * its request state on nothing else; if that delivery fails, this throws and can be retried. A
     * failed access-key delivery afterwards is retried before the next send.
     */
    suspend fun acceptMessageRequest(from: AccountId) {
        val contact = checkNotNull(contacts.get(from)) { "no conversation with $from" }
        if (contact.state != ContactState.INCOMING_REQUEST) return
        deliver(contact, MessageRequests.acceptContent(), ephemeral = false)
        editContact(from) { it?.takeIf { c -> c.state == ContactState.INCOMING_REQUEST }?.copy(state = ContactState.ACCEPTED) }
        attempt { shareOwnState(from) }
    }

    /**
     * Blocks [accountId]: its messages are dropped locally and our access key is rotated, so its
     * copy stops working (§6.4).
     *
     * @return accepted contacts that could not be sent the new key yet (it is retried on the next send).
     */
    suspend fun block(accountId: AccountId): List<AccountId> {
        editContact(accountId) { it?.copy(state = ContactState.BLOCKED) ?: Contact(accountId, null, ContactState.BLOCKED) }
        forgetPresence(accountId)
        return rotateAccessKey()
    }

    /** Replaces our access key at the relay and sends the new one to every accepted contact. */
    suspend fun rotateAccessKey(): List<AccountId> {
        val newKey = AccessKey.generate()
        relay.setAccessKey(newKey)
        updateAccount { it.copy(accessKey = newKey, pendingSetup = it.pendingSetup - PendingSetup.PUBLISH_ACCESS_KEY) }
        val accepted = acceptedContacts()
        accepted.forEach { contact -> updateContact(contact.accountId) { it.copy(accessKeyShared = false) } }
        return accepted.filterNot { attempt { shareOwnState(it.accountId) } }.map { it.accountId }
    }

    // Profile (§3.3, §8.6)

    /**
     * Changes the username (§3.3) and announces it to accepted contacts with `ProfileUpdate`.
     *
     * @return contacts that could not be told (they are told before the next send to them).
     */
    suspend fun changeUsername(newUsername: Username): List<AccountId> {
        relay.changeUsername(withContext(Dispatchers.Default) { newUsername.hash() })
        updateAccount { it.copy(username = newUsername) }
        return announceProfile()
    }

    /** Sets the display name shared with accepted contacts. @return contacts that could not be told yet. */
    suspend fun setDisplayName(displayName: String): List<AccountId> {
        require(displayName.toByteArray(Charsets.UTF_8).size <= MAX_DISPLAY_NAME_BYTES) { "display name exceeds $MAX_DISPLAY_NAME_BYTES UTF-8 bytes" }
        updateAccount { it.copy(displayName = displayName) }
        return announceProfile()
    }

    /**
     * "Show when I'm active" (§8.6). Presence is reciprocal: with sharing off, nobody's status is
     * shown either. @return contacts that could not be told yet.
     */
    suspend fun setPresenceSharing(enabled: Boolean): List<AccountId> {
        updateAccount { it.copy(presenceSharing = enabled) }
        if (!enabled) presenceTracker.clear()
        return announceProfile()
    }

    // Presence (§8.6)

    /** What to show for [peer] right now. */
    fun presence(peer: AccountId): PresenceStatus = presenceTracker.status(peer)

    /**
     * Tells the contacts we currently see online that we are going offline. Call it at the end of
     * the background grace period (§5.4), before cancelling [runReceiveLoop].
     */
    suspend fun announceOffline() = sendPresence(PresencePolicy.offline(), presenceTargets(onlyThoseOnline = true))

    // Identity and safety numbers (§5)

    fun safetyNumber(peer: AccountId): SafetyNumber? {
        val remote = store.identity(peer) ?: return null
        return SafetyNumber.compute(accountId, currentAccount.identityKeyPair.publicKey, peer, remote.identityKey)
    }

    /** The user acknowledged the new safety number of [peer]; sending is allowed again. */
    fun acceptIdentityChange(peer: AccountId) = store.acceptIdentity(peer)

    fun setVerified(peer: AccountId, verified: Boolean) = store.setVerified(peer, verified)

    /** Deletes a contact together with its session and identity ("leave no trace", §8.2). */
    suspend fun forgetContact(peer: AccountId) {
        cryptoLock.withLock { store.forget(peer) }
        synchronized(contactLock) { contacts.remove(peer) }
        forgetPresence(peer)
    }

    // Attachments (§7)

    /** Encrypts and uploads an attachment, returning the `Attachment` to embed in a message. */
    suspend fun uploadAttachment(attachment: OutgoingAttachment): Attachment {
        val key = AttachmentCipher.generateKey()
        val staged = withContext(Dispatchers.IO) { File.createTempFile("us2us-", ".blob", config.tempDirectory) }
        try {
            val encrypted = withContext(Dispatchers.IO) {
                attachment.source.open().use { input -> staged.outputStream().buffered().use { AttachmentCipher.encrypt(input, key, it) } }
            }
            val upload = relay.uploadMedia(staged)
            return attachment {
                kind = attachment.kind
                mediaId = upload.mediaId.toByteString()
                deleteToken = upload.deleteToken.toByteString()
                this.key = key.toByteString()
                digest = encrypted.digest.toByteString()
                size = encrypted.plaintextSize
                contentType = attachment.contentType
                fileName = attachment.fileName
                width = attachment.width
                height = attachment.height
                durationMs = attachment.durationMs
                caption = attachment.caption
                attachment.thumbnail?.let { thumbnail = it.toByteString() }
                attachment.waveform?.let { waveform = it.toByteString() }
            }
        } finally {
            withContext(Dispatchers.IO) { staged.delete() }
        }
    }

    /**
     * Downloads, verifies (digest and MAC) and decrypts an attachment into [output], then deletes
     * the blob from the relay as recipients should (§4.9). The download is cut off at the blob size
     * the declared plaintext size implies, so a hostile sender or relay cannot fill the disk.
     *
     * @throws AttachmentIntegrityException if the reference is malformed or the blob was tampered with.
     * @throws IOException on network failure, or if the blob is larger than its declared size allows.
     */
    suspend fun downloadAttachment(attachment: Attachment, output: OutputStream, deleteFromRelay: Boolean = true) {
        if (attachment.mediaId.size() != MEDIA_ID_LENGTH || attachment.key.size() != AttachmentCipher.KEY_LENGTH ||
            attachment.digest.size() != DIGEST_LENGTH || attachment.size !in 0..AttachmentCipher.MAX_PLAINTEXT_SIZE
        ) {
            throw AttachmentIntegrityException("malformed attachment reference")
        }
        val mediaId = attachment.mediaId.toByteArray()
        val staged = withContext(Dispatchers.IO) { File.createTempFile("us2us-", ".blob", config.tempDirectory) }
        try {
            withContext(Dispatchers.IO) { staged.outputStream().buffered() }.use {
                publicRelay.downloadMedia(mediaId, it, maxBytes = AttachmentCipher.blobSize(attachment.size))
            }
            withContext(Dispatchers.IO) {
                AttachmentCipher.decrypt(staged, attachment.key.toByteArray(), attachment.digest.toByteArray(), attachment.size, output)
            }
        } finally {
            withContext(Dispatchers.IO) { staged.delete() }
        }
        if (deleteFromRelay) {
            // Best effort: the relay drops blobs after 14 days anyway, and a bad delete token from
            // the sender must not turn a verified download into a failure.
            try {
                publicRelay.deleteMedia(mediaId, attachment.deleteToken.toByteArray())
            } catch (_: IOException) {
            }
        }
    }

    // Receiving

    /**
     * Holds the WebSocket (§4.7) and hands every decrypted message to [handler], reconnecting with
     * exponential backoff. Returns only by cancellation, or throws [ConnectionRejectedException]
     * when another connection replaced this one or the token is invalid.
     *
     * The envelope is acknowledged after [handler] returns. If the handler throws, the loop stops
     * and the envelope stays at the relay; libsignal has already consumed its message key, though,
     * so the handler must store messages reliably.
     */
    suspend fun runReceiveLoop(handler: MessageHandler) {
        var backoff = config.reconnectMinDelay
        try {
            while (true) {
                connectionState.value = ConnectionState.CONNECTING
                val last = runSocket(handler) { backoff = config.reconnectMinDelay }
                connectionState.value = ConnectionState.DISCONNECTED
                if (!last.isRetryable) {
                    throw ConnectionRejectedException((last as? SocketEvent.Closed)?.code, (last as? SocketEvent.Failed)?.httpStatus)
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(config.reconnectMaxDelay)
            }
        } finally {
            connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    /**
     * Fetches and handles stored envelopes over HTTP (§4.6) until the mailbox is empty, e.g. when
     * the app opens before the socket is up.
     *
     * @return how many envelopes were acknowledged.
     */
    suspend fun fetchPending(handler: MessageHandler): Int {
        var acknowledged = 0
        while (true) {
            val page = relay.fetchMessages(FETCH_BATCH)
            val done = page.messages.filter { process(it, handler) }.map { it.id }
            relay.acknowledge(done)
            acknowledged += done.size
            if (!page.more || done.size < page.messages.size) break
        }
        flushDeliveryReceipts()
        return acknowledged
    }

    // Account

    /**
     * Finishes what registration or restore left for later ([LocalAccount.pendingSetup]), then
     * uploads new pre-keys if the relay is running low or the rotation interval passed (§5). The
     * receive loop runs this after every backlog; call it on app start as well.
     *
     * @return whether pre-keys were uploaded.
     */
    suspend fun performMaintenance(): Boolean {
        completePendingSetup()
        return maintainPreKeys()
    }

    /**
     * Uploads new pre-keys if the relay is running low or the rotation interval passed (§5).
     *
     * @return whether anything was uploaded.
     */
    suspend fun maintainPreKeys(): Boolean {
        val status = relay.keyStatus()
        val upload = cryptoLock.withLock { withContext(Dispatchers.Default) { preKeys.maintain(status) } } ?: return false
        relay.uploadKeys(upload)
        return true
    }

    /** Irreversibly deletes the account at the relay. */
    suspend fun deleteAccount() = relay.deleteAccount()

    /**
     * Exports the account backup file (§5.5): identity, credentials and contacts, never messages,
     * encrypted under the separate backup [password] (never the secret code). The UI asks for the
     * password twice and checks it with [BackupPasswordPolicy] first. Takes a few seconds by design.
     *
     * @throws com.syconx.us2us.core.vault.InvalidBackupPasswordException if [password] breaks a rule of §5.5.
     */
    suspend fun exportBackup(password: String, iterations: Int = BackupFile.ITERATIONS): ByteArray {
        BackupPasswordPolicy.requireValid(password)
        val account = currentAccount
        val backup = accountBackup {
            version = BACKUP_VERSION
            accountId = account.accountId.toBytes().toByteString()
            authToken = account.authToken.toBytes().toByteString()
            identityKeyPair = account.identityKeyPair.serialize().toByteString()
            registrationId = account.registrationId
            username = account.username.value
            displayName = account.displayName
            accessKey = account.accessKey.toBytes().toByteString()
            createdAt = config.clock.millis()
            contacts += this@Messenger.contacts.all()
                .filter { it.state == ContactState.ACCEPTED || it.state == ContactState.BLOCKED }
                .map { contact ->
                    val identity = store.identity(contact.accountId)
                    backupContact {
                        accountId = contact.accountId.toBytes().toByteString()
                        username = contact.username.orEmpty()
                        displayName = contact.displayName.orEmpty()
                        identity?.let {
                            identityKey = it.identityKey.serialize().toByteString()
                            // Restored as changed, so the new phone keeps sending blocked too.
                            identityUnaccepted = it.trust == IdentityTrust.CHANGED
                        }
                        verified = identity?.verified == true
                        blocked = contact.state == ContactState.BLOCKED
                        contact.theirAccessKey?.let { theirAccessKey = it.toBytes().toByteString() }
                        presenceSharing = contact.presenceSharing
                    }
                }
        }
        return withContext(Dispatchers.Default) { BackupFile.create(password, backup.toByteArray(), iterations) }
    }

    // Internals: sending

    private suspend fun deliver(contact: Contact, content: Content, ephemeral: Boolean): SendReceipt {
        val to = contact.accountId
        // While our request is pending we pay proof-of-work even if a key arrived early (§6.4).
        val accessKey = contact.theirAccessKey.takeIf { contact.state != ContactState.OUTGOING_REQUEST }
        if (ephemeral && accessKey == null) throw AccessKeyRequiredException(to)
        val envelope = seal(contact, content)

        if (accessKey != null) {
            // An ephemeral send is answered identically whether or not it was delivered (§4.6).
            if (ephemeral) return publicRelay.sendEnvelope(to, envelope, DeliveryAuthorization.WithAccessKey(accessKey), ephemeral = true)
            try {
                return publicRelay.sendEnvelope(to, envelope, DeliveryAuthorization.WithAccessKey(accessKey))
            } catch (e: RelayException) {
                if (e.code != RelayErrorCode.ACCESS_DENIED) throw e
                // The peer rotated its key without reaching us; fall back to proof-of-work.
                updateContact(to) { if (it.theirAccessKey == accessKey) it.copy(theirAccessKey = null) else it }
            }
        }
        val challenge = publicRelay.messageChallenge(to)
        val solution = ProofOfWork.solve(challenge.token, challenge.difficulty, config.powParallelism)
        return publicRelay.sendEnvelope(to, envelope, DeliveryAuthorization.WithProofOfWork(solution))
    }

    /** Session-encrypts [content] for [contact] and wraps it in a sealed envelope (§6). */
    private suspend fun seal(contact: Contact, content: Content): ByteArray {
        if (store.identity(contact.accountId)?.trust == IdentityTrust.CHANGED) throw IdentityChangedException(contact.accountId)
        ensureSession(contact.accountId)
        return cryptoLock.withLock { sealLocked(contact, content) }
    }

    /** [seal] for a caller that holds [cryptoLock] and knows a usable session exists. */
    private fun sealLocked(contact: Contact, content: Content): ByteArray {
        val to = contact.accountId
        val ciphertext = try {
            sessions.encrypt(to, content)
        } catch (_: UntrustedIdentityException) {
            throw IdentityChangedException(to)
        }
        val sealed = sealedContent {
            senderAccountId = accountId.toBytes().toByteString()
            senderDeviceId = AccountId.DEVICE_ID
            type = ciphertext.type
            this.ciphertext = ciphertext.serialize().toByteString()
            if (MessageRequests.includeSenderUsername(contact.state)) senderUsername = currentAccount.username.value
        }
        return SealedEnvelope.seal(sealed, checkNotNull(store.identity(to)).identityKey)
    }

    /**
     * Fetches a pre-key bundle and starts a session unless a usable one exists. A bundle the relay
     * mangled is reported as an [IOException]: like any relay failure it must not take down the
     * receive loop, and a later attempt fetches a fresh one.
     */
    private suspend fun ensureSession(peer: AccountId) {
        if (cryptoLock.withLock { sessions.hasUsableSession(peer) }) return
        sessionSetupLock.withLock {
            if (cryptoLock.withLock { sessions.hasUsableSession(peer) }) return
            val bundle = try {
                relay.preKeyBundle(peer).toPreKeyBundle(peer)
            } catch (e: InvalidBundleException) {
                throw IOException("relay returned an invalid pre-key bundle", e)
            }
            cryptoLock.withLock {
                try {
                    sessions.startSession(peer, bundle)
                } catch (_: UntrustedIdentityException) {
                    // Record the new key as CHANGED so the app can show the new safety number.
                    store.saveIdentity(peer.protocolAddress(), bundle.identityKey)
                    throw IdentityChangedException(peer)
                } catch (e: InvalidKeyException) {
                    throw IOException("relay returned a pre-key bundle with a bad signature", e)
                }
            }
        }
    }

    /**
     * Gives an accepted contact our current access key, then our profile. The profile waits until
     * we hold the contact's access key, so it never costs proof-of-work.
     */
    private suspend fun shareOwnState(peer: AccountId) {
        var contact = contacts.get(peer)?.takeIf { it.state == ContactState.ACCEPTED } ?: return
        if (!contact.accessKeyShared) {
            val key = currentAccount.accessKey
            deliver(contact, MessageRequests.accessKeyUpdateContent(key), ephemeral = false)
            // A rotation meanwhile reset the flag on purpose: the new key is still due.
            updateContact(peer) { if (currentAccount.accessKey == key) it.copy(accessKeyShared = true) else it }
            contact = contacts.get(peer) ?: return
        }
        if (!contact.profileShared && contact.theirAccessKey != null) sendProfile(peer)
    }

    /** Sends our profile to every accepted contact. @return those that could not be reached. */
    private suspend fun announceProfile(): List<AccountId> {
        val accepted = acceptedContacts()
        accepted.forEach { contact -> updateContact(contact.accountId) { it.copy(profileShared = false) } }
        return accepted.filterNot { attempt { sendProfile(it.accountId) } }.map { it.accountId }
    }

    private suspend fun sendProfile(peer: AccountId) {
        val contact = contacts.get(peer)?.takeIf { it.state == ContactState.ACCEPTED } ?: return
        val account = currentAccount
        // Every field is always set: receivers treat a ProfileUpdate as the complete profile.
        val profile = content {
            control = controlMessage {
                profile = profileUpdate {
                    username = account.username.value
                    displayName = account.displayName
                    presenceSharing = account.presenceSharing
                    updatedAt = config.clock.millis()
                }
            }
        }
        deliver(contact, profile, ephemeral = false)
        // If the profile changed meanwhile, the newer one is still due.
        if (currentAccount.sameProfileAs(account)) updateContact(peer) { it.copy(profileShared = true) }
    }

    private fun acceptedContacts(): List<Contact> = contacts.all().filter { it.state == ContactState.ACCEPTED }

    /** Runs a send whose failure is retried later; returns whether it succeeded. */
    private suspend fun attempt(block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (_: IOException) {
        false
    } catch (_: IdentityChangedException) {
        false
    } catch (_: AccessKeyRequiredException) {
        false
    }

    // Internals: presence (§8.6)

    private fun presenceTargets(onlyThoseOnline: Boolean): List<Contact> {
        val weShare = currentAccount.presenceSharing
        val online = if (onlyThoseOnline) presenceTracker.onlineContacts().toSet() else null
        return contacts.all().filter { PresencePolicy.isEligible(it, weShare) && (online == null || it.accountId in online) }
    }

    /**
     * Seals [content] for every target with a usable session and fans it out in one request.
     * A status update never consumes a contact's one-time pre-keys and never goes to a changed
     * identity.
     */
    private suspend fun sendPresence(content: Content, targets: List<Contact>) {
        if (targets.isEmpty()) return
        val envelopes = cryptoLock.withLock {
            targets.mapNotNull { contact ->
                val key = contact.theirAccessKey ?: return@mapNotNull null
                val trusted = store.identity(contact.accountId)?.trust == IdentityTrust.TRUSTED
                if (!trusted || !sessions.hasUsableSession(contact.accountId)) return@mapNotNull null
                try {
                    EphemeralEnvelope(contact.accountId, key, sealLocked(contact, content))
                } catch (_: IdentityChangedException) {
                    null
                }
            }
        }.filter { it.envelope.size <= RelayClient.MAX_EPHEMERAL_ENVELOPE_BYTES }
        if (envelopes.isNotEmpty()) publicRelay.sendEphemeral(envelopes)
    }

    /** ONLINE to every eligible contact once the backlog is in, then a refresh every 240 s. */
    private suspend fun runPresence() {
        attempt { sendPresence(PresencePolicy.online(), presenceTargets(onlyThoseOnline = false)) }
        while (true) {
            delay(PresencePolicy.REFRESH_INTERVAL)
            attempt { sendPresence(PresencePolicy.online(), presenceTargets(onlyThoseOnline = true)) }
        }
    }

    private suspend fun onPresence(sender: AccountId, presence: PresenceMessage) {
        val contact = contacts.get(sender) ?: return
        if (!PresencePolicy.isEligible(contact, currentAccount.presenceSharing)) return
        presenceTracker.record(sender, presence)
        presenceChanges.tryEmit(sender)
        if (PresencePolicy.shouldReply(presence)) attempt { deliver(contact, PresencePolicy.online(reply = true), ephemeral = true) }
    }

    private fun forgetPresence(peer: AccountId) {
        presenceTracker.forget(peer)
        presenceChanges.tryEmit(peer)
    }

    // Internals: receiving

    private suspend fun runSocket(handler: MessageHandler, onConnected: () -> Unit): SocketEvent = coroutineScope {
        val socket = relay.openSocket()
        var last: SocketEvent = SocketEvent.Failed(IOException("socket ended without a close event"), null)
        var backlogDone = false
        var keepAlive: Job? = null
        var presence: Job? = null
        var maintenance: Job? = null
        try {
            socket.events.collect { event ->
                when (event) {
                    SocketEvent.Connected -> {
                        connectionState.value = ConnectionState.CONNECTED
                        onConnected()
                        keepAlive = launch {
                            while (true) {
                                delay(config.keepAliveInterval)
                                socket.ping()
                            }
                        }
                    }
                    is SocketEvent.Message -> {
                        if (process(event.envelope, handler)) socket.ack(listOf(event.envelope.id))
                        // Receipts for the backlog go out in one batch per contact at queue_empty.
                        if (backlogDone) flushDeliveryReceipts()
                    }
                    SocketEvent.QueueEmpty -> {
                        backlogDone = true
                        connectionState.value = ConnectionState.UP_TO_DATE
                        flushDeliveryReceipts()
                        if (maintenance?.isActive != true) maintenance = launch { runMaintenance() }
                        if (presence == null) presence = launch { runPresence() }
                    }
                    SocketEvent.Pong -> Unit
                    is SocketEvent.Closed, is SocketEvent.Failed -> last = event
                }
            }
        } finally {
            keepAlive?.cancel()
            presence?.cancel()
            maintenance?.cancel()
            socket.close()
        }
        last
    }

    /** Remembers a delivery receipt for a bubble the handler has just stored. */
    private fun queueDeliveryReceipt(message: IncomingMessage) {
        if (!config.sendDeliveryReceipts || message.contactState != ContactState.ACCEPTED) return
        val data = message.dataMessage ?: return
        if (data.payloadCase !in RECEIPTED_PAYLOADS) return
        synchronized(receiptLock) { pendingReceipts.getOrPut(message.sender) { mutableListOf() } += data.messageId.toByteArray() }
    }

    /** Sends queued delivery receipts: per contact, as few `ReceiptMessage`s as the id limit allows. */
    private suspend fun flushDeliveryReceipts() {
        val batches = synchronized(receiptLock) { LinkedHashMap(pendingReceipts).also { pendingReceipts.clear() } }
        for ((peer, ids) in batches) {
            val chunks = ids.chunked(MAX_RECEIPT_IDS)
            for ((index, chunk) in chunks.withIndex()) {
                try {
                    sendReceipt(peer, ReceiptMessage.Type.DELIVERY, chunk)
                } catch (_: IOException) {
                    val unsent = chunks.drop(index).flatten()
                    synchronized(receiptLock) { pendingReceipts.getOrPut(peer) { mutableListOf() }.addAll(0, unsent) }
                    break
                } catch (_: IdentityChangedException) {
                    break // No receipts until the user accepts the new safety number.
                } catch (_: SendNotAllowedException) {
                    break // Blocked meanwhile.
                }
            }
        }
    }

    /**
     * Account upkeep after a backlog. It must never take the receive loop down: pre-key upkeep that
     * fails is retried on the next connection, unfinished [PendingSetup] steps with backoff while
     * this connection lasts (§5.5: the key upload after a restore MUST eventually succeed).
     */
    private suspend fun runMaintenance() {
        var backoff = config.setupRetryMinDelay
        while (true) {
            try {
                performMaintenance()
            } catch (_: IOException) {
                // The relay still has keys or falls back to the last-resort key.
            }
            if (currentAccount.pendingSetup.isEmpty()) return
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(config.setupRetryMaxDelay)
        }
    }

    /**
     * The relay steps registration and restore left for later, in the order of §5.5: the key
     * upload before the profile announcement.
     *
     * @param announce whether to send the post-restore `ProfileUpdate`s now; registration and
     *   restore leave them to the receive loop, so the user is not kept waiting for every contact.
     */
    private suspend fun completePendingSetup(announce: Boolean = true) {
        if (PendingSetup.PUBLISH_ACCESS_KEY in currentAccount.pendingSetup) {
            relay.setAccessKey(currentAccount.accessKey)
            updateAccount { it.copy(pendingSetup = it.pendingSetup - PendingSetup.PUBLISH_ACCESS_KEY) }
        }
        if (PendingSetup.REPLACE_PRE_KEYS in currentAccount.pendingSetup) {
            val keys = cryptoLock.withLock { withContext(Dispatchers.Default) { preKeys.generateFullSet() } }
            relay.uploadKeys(keys.toUploadRequest(replaceAll = true))
            updateAccount { it.copy(pendingSetup = it.pendingSetup - PendingSetup.REPLACE_PRE_KEYS) }
        }
        if (announce && PendingSetup.ANNOUNCE_PROFILE in currentAccount.pendingSetup) {
            // Contacts that cannot be reached now keep profileShared = false and get the profile
            // before our next message to them, or when they next write.
            announceProfile()
            updateAccount { it.copy(pendingSetup = it.pendingSetup - PendingSetup.ANNOUNCE_PROFILE) }
        }
    }

    /**
     * Handles one stored envelope.
     *
     * @return whether it may be acknowledged: true when handled or dropped, false when sender
     *   verification could not reach the relay and the envelope should be retried later.
     */
    private suspend fun process(stored: StoredEnvelope, handler: MessageHandler): Boolean {
        if (stored.id in handledEnvelopes) return true // A redelivery of something already handled.
        val result = try {
            receiver.receive(stored.envelope)
        } catch (_: IOException) {
            return false
        }
        if (result is ReceiveResult.Decrypted) {
            val message = recordIncoming(stored, result)
            if (message != null) {
                handler.onMessage(message)
                queueDeliveryReceipt(message)
                val content = message.content
                if (content.hasPresence()) onPresence(message.sender, content.presence)
                if (content.hasControl() && content.control.hasProfile() && !content.control.profile.presenceSharing) {
                    forgetPresence(message.sender)
                }
                // Once we hold an accepted contact's key, sharing ours and our profile costs nothing.
                val contact = contacts.get(message.sender)
                if (contact != null && contact.state == ContactState.ACCEPTED && contact.theirAccessKey != null &&
                    (!contact.accessKeyShared || !contact.profileShared)
                ) {
                    attempt { shareOwnState(message.sender) }
                }
            }
        }
        handledEnvelopes += stored.id
        return true
    }

    /**
     * Applies the message to the sender's contact (request state, access key, profile) and returns
     * the message for the app, or `null` if the sender is blocked.
     */
    private fun recordIncoming(stored: StoredEnvelope, result: ReceiveResult.Decrypted): IncomingMessage? {
        val contact = editContact(result.sender) { previous ->
            if (!MessageRequests.accepts(previous?.state)) return@editContact null
            val state = MessageRequests.afterIncoming(previous?.state, result.content)
            var contact = previous?.copy(state = state) ?: Contact(result.sender, null, state)
            // Checked against the directory before decryption (§6.3).
            result.senderUsername?.let { contact = contact.copy(username = it.value, usernameStatus = UsernameStatus.VERIFIED) }
            if (result.content.hasControl()) contact = contact.withControl(result.content.control)
            contact
        } ?: return null
        return IncomingMessage(
            envelopeId = stored.id,
            serverTimestamp = stored.serverTimestamp,
            sender = result.sender,
            senderUsername = contact.username,
            content = result.content,
            contactState = contact.state,
            identityChanged = store.identity(result.sender)?.trust == IdentityTrust.CHANGED,
        )
    }

    /** What a control message tells us about its sender: its access key and profile. */
    private fun Contact.withControl(control: ControlMessage): Contact {
        var contact = this
        if (control.hasAccessKey() && control.accessKey.accessKey.size() == AccessKey.LENGTH) {
            contact = contact.copy(theirAccessKey = AccessKey(control.accessKey.accessKey.toByteArray()))
        }
        if (control.hasProfile()) contact = contact.withProfile(control.profile)
        return contact
    }

    /**
     * A `ProfileUpdate` is the complete profile and replaces the stored one (§8.7); an empty or
     * malformed field means "none". Its username is only a claim: a new one is unchecked until
     * [verifyUsername] runs (§3.3).
     */
    private fun Contact.withProfile(profile: ProfileUpdate): Contact {
        val username = Username.parse(profile.username)?.value?.takeIf { it == profile.username }
        val displayName = profile.displayName.takeIf { it.isNotEmpty() && it.toByteArray(Charsets.UTF_8).size <= MAX_DISPLAY_NAME_BYTES }
        return copy(
            username = username,
            usernameStatus = if (username == this.username) usernameStatus else UsernameStatus.UNCHECKED,
            displayName = displayName,
            presenceSharing = profile.presenceSharing,
        )
    }

    /**
     * Read-modify-write of one contact, atomic with respect to the receive loop and concurrent
     * sends. [update] sees `null` for an unknown account; returning `null` leaves the store unchanged.
     */
    private fun editContact(accountId: AccountId, update: (Contact?) -> Contact?): Contact? = synchronized(contactLock) {
        update(contacts.get(accountId))?.also(contacts::put)
    }

    private fun updateContact(accountId: AccountId, update: (Contact) -> Contact) {
        editContact(accountId) { it?.let(update) }
    }

    @Synchronized
    private fun updateAccount(update: (LocalAccount) -> LocalAccount): LocalAccount {
        currentAccount = update(currentAccount)
        accountStore.save(currentAccount)
        return currentAccount
    }

    /** Our own key and profile go out by themselves; they must not trigger another round of sharing. */
    private fun Content.sharesOwnState() = hasControl() && (control.hasAccessKey() || control.hasProfile())

    private fun LocalAccount.sameProfileAs(other: LocalAccount) =
        username == other.username && displayName == other.displayName && presenceSharing == other.presenceSharing

    companion object {
        const val MESSAGE_ID_LENGTH = 16
        const val MAX_TEXT_BYTES = 8192
        const val MAX_QUOTE_PREVIEW_CHARS = 200
        const val MAX_DISPLAY_NAME_BYTES = 64

        /** Ids per `ReceiptMessage`: about 18 KiB of ids, well inside one 64 KiB envelope. */
        const val MAX_RECEIPT_IDS = 1000
        private const val MEDIA_ID_LENGTH = 16
        private const val DIGEST_LENGTH = 32
        private const val BACKUP_VERSION = 1
        private const val IDENTITY_KEY_LENGTH = 33
        private const val HANDLED_ENVELOPE_MEMORY = 2048

        /** Envelopes per HTTP fetch; small enough that a page of 64 KiB envelopes stays a few MiB. */
        private const val FETCH_BATCH = 100

        /** Bubbles get delivery receipts; reactions, deletes and live-location ticks do not. */
        private val RECEIPTED_PAYLOADS = setOf(
            DataMessage.PayloadCase.TEXT,
            DataMessage.PayloadCase.ATTACHMENT,
            DataMessage.PayloadCase.LOCATION,
            DataMessage.PayloadCase.STICKER,
        )

        /**
         * Creates an account (§4.3): generates identity and pre-keys, solves the registration
         * proof-of-work, registers the username hash and publishes a fresh access key.
         *
         * The account is saved to [accountStore] the moment the relay has created it. Publishing
         * the access key is attempted right away; if the network fails there, it stays in
         * [LocalAccount.pendingSetup] and [performMaintenance] finishes it, so neither the
         * credentials nor the username are ever lost.
         *
         * @throws RelayException with [RelayErrorCode.USERNAME_TAKEN] if the username is in use.
         */
        suspend fun register(
            username: Username,
            relay: RelayClient = RelayClient(),
            protocolStorage: ProtocolStorage = InMemoryProtocolStorage(),
            contacts: ContactStore = InMemoryContactStore(),
            config: MessengerConfig = MessengerConfig(),
            accountStore: AccountStore = AccountStore {},
        ): Messenger {
            val identity = Keys.generateIdentityKeyPair()
            val registrationId = Keys.generateRegistrationId()
            val store = ProtocolStore(identity, registrationId, protocolStorage, config.clock)
            val keys = withContext(Dispatchers.Default) { PreKeyManager(store, config.preKeyPolicy, config.clock).generateFullSet() }
            val usernameHash = withContext(Dispatchers.Default) { username.hash() }
            val challenge = relay.registrationChallenge()
            val solution = ProofOfWork.solve(challenge.token, challenge.difficulty, config.powParallelism)
            val registered = relay.register(
                RegistrationRequest(
                    usernameHash = usernameHash,
                    identityKey = identity.publicKey.serialize(),
                    registrationId = registrationId,
                    signedPreKey = keys.signedPreKey,
                    kyberLastResortKey = keys.kyberLastResortKey,
                    oneTimePreKeys = keys.oneTimePreKeys,
                    kyberOneTimePreKeys = keys.kyberOneTimePreKeys,
                    pow = PowSubmission(solution.token, solution.nonce.toString()),
                ),
            )
            val account = LocalAccount(
                accountId = registered.accountId,
                authToken = registered.authToken,
                username = username,
                identityKeyPair = identity,
                registrationId = registrationId,
                accessKey = AccessKey.generate(),
                pendingSetup = setOf(PendingSetup.PUBLISH_ACCESS_KEY),
            )
            accountStore.save(account)
            return Messenger(account, store, contacts, relay, config, accountStore).apply { completePendingSetupQuietly() }
        }

        /**
         * Restores an account from a backup file on a new phone, in the order of §5.5:
         *
         * 1. Decrypts and verifies the **whole** backup (version, every field's size and format)
         *    before anything is written or the relay is contacted, so a bad backup changes nothing.
         * 2. Writes the contacts and their identity keys into [contacts] and [protocolStorage], which
         *    the app keeps in the new vault: trusted keys stay trusted, so nobody sees a
         *    safety-number change, and a change the user had not accepted stays blocked.
         * 3. Rotates the auth token (the old phone loses access) and saves the account to
         *    [accountStore] before anything else can fail; the backup's token no longer works.
         * 4. Uploads a fresh key set with `replaceAll`. A failure stays in
         *    [LocalAccount.pendingSetup] and is retried until it succeeds ([performMaintenance],
         *    and with backoff while [runReceiveLoop] is connected).
         * 5. Then the receive loop sends a complete `ProfileUpdate` to every accepted contact, so
         *    the conversations move to the new phone's sessions.
         *
         * @throws com.syconx.us2us.core.vault.WrongBackupPasswordException if [password] is wrong.
         * @throws InvalidBackupException if the backup is malformed or of an unknown version.
         */
        suspend fun restore(
            backupFile: ByteArray,
            password: String,
            relay: RelayClient = RelayClient(),
            protocolStorage: ProtocolStorage = InMemoryProtocolStorage(),
            contacts: ContactStore = InMemoryContactStore(),
            config: MessengerConfig = MessengerConfig(),
            accountStore: AccountStore = AccountStore {},
        ): Messenger {
            val plaintext = withContext(Dispatchers.Default) { BackupFile.open(backupFile, password) }
            val backup = try {
                AccountBackup.parseFrom(plaintext)
            } catch (e: InvalidProtocolBufferException) {
                throw InvalidBackupException("malformed AccountBackup", e)
            }
            if (backup.version != BACKUP_VERSION) throw InvalidBackupException("unsupported backup version ${backup.version}")
            val accountId = parsed("account id") { AccountId.fromBytes(backup.accountId.toByteArray()) }
            val backupToken = parsed("auth token") { AuthToken(backup.authToken.toByteArray()) }
            val identity = parsed("identity key pair") { IdentityKeyPair(backup.identityKeyPair.toByteArray()) }
            if (backup.registrationId !in 1..Keys.REGISTRATION_ID_MAX) throw InvalidBackupException("registration id out of range")
            val username = Username.parse(backup.username)?.takeIf { it.value == backup.username }
                ?: throw InvalidBackupException("username is not normalized")
            if (backup.displayName.toByteArray(Charsets.UTF_8).size > MAX_DISPLAY_NAME_BYTES) throw InvalidBackupException("display name too long")
            val accessKey = parsed("access key") { AccessKey(backup.accessKey.toByteArray()) }
            val restoredContacts = backup.contactsList.map(::restoredContact)
            val contactIds = restoredContacts.map { it.contact.accountId }
            if (accountId in contactIds) throw InvalidBackupException("the account is its own contact")
            if (contactIds.toSet().size != contactIds.size) throw InvalidBackupException("duplicate contact")

            val store = ProtocolStore(identity, backup.registrationId, protocolStorage, config.clock)
            for (restored in restoredContacts) {
                restored.identityKey?.let { key -> store.restoreIdentity(restored.contact.accountId, key, restored.trust, restored.verified) }
                contacts.put(restored.contact)
            }

            val authToken = relay.withAuthToken(backupToken).rotateAuthToken()
            val account = LocalAccount(
                accountId = accountId,
                authToken = authToken,
                username = username,
                identityKeyPair = identity,
                registrationId = backup.registrationId,
                accessKey = accessKey,
                displayName = backup.displayName,
                pendingSetup = setOf(PendingSetup.REPLACE_PRE_KEYS, PendingSetup.ANNOUNCE_PROFILE),
            )
            // The backup's token is revoked now: never lose the new one, whatever fails next.
            accountStore.save(account)
            return Messenger(account, store, contacts, relay, config, accountStore).apply { completePendingSetupQuietly() }
        }

        private class RestoredContact(val contact: Contact, val identityKey: IdentityKey?, val trust: IdentityTrust, val verified: Boolean)

        /** A contact from the backup. Every field must be well-formed, or the whole backup is refused (§5.5). */
        private fun restoredContact(saved: BackupContact): RestoredContact {
            val peer = parsed("contact account id") { AccountId.fromBytes(saved.accountId.toByteArray()) }
            // A missing key is learned again on first contact (trust on first use).
            val identityKey = when {
                saved.identityKey.isEmpty -> null
                saved.identityKey.size() != IDENTITY_KEY_LENGTH -> throw InvalidBackupException("malformed contact identity key")
                else -> parsed("contact identity key") { IdentityKey(saved.identityKey.toByteArray()) }
            }
            if (saved.identityUnaccepted && identityKey == null) throw InvalidBackupException("unaccepted identity without a key")
            val username = when {
                saved.username.isEmpty() -> null
                else -> Username.parse(saved.username)?.value?.takeIf { it == saved.username }
                    ?: throw InvalidBackupException("contact username is not normalized")
            }
            if (saved.displayName.toByteArray(Charsets.UTF_8).size > MAX_DISPLAY_NAME_BYTES) throw InvalidBackupException("contact display name too long")
            val theirAccessKey = when {
                saved.theirAccessKey.isEmpty -> null
                else -> parsed("contact access key") { AccessKey(saved.theirAccessKey.toByteArray()) }
            }
            val contact = Contact(
                accountId = peer,
                username = username,
                state = if (saved.blocked) ContactState.BLOCKED else ContactState.ACCEPTED,
                theirAccessKey = theirAccessKey,
                // The access key is part of the backup, so contacts already have it.
                accessKeyShared = true,
                displayName = saved.displayName.ifEmpty { null },
                presenceSharing = saved.presenceSharing,
                // Sent once the keys are replaced (PendingSetup.ANNOUNCE_PROFILE).
                profileShared = false,
                // A claim as far as this phone knows; checked again lazily (§3.3).
                usernameStatus = UsernameStatus.UNCHECKED,
            )
            val trust = if (saved.identityUnaccepted) IdentityTrust.CHANGED else IdentityTrust.TRUSTED
            return RestoredContact(contact, identityKey, trust, saved.verified)
        }

        private inline fun <T> parsed(what: String, parse: () -> T): T = try {
            parse()
        } catch (e: IllegalArgumentException) {
            throw InvalidBackupException("malformed $what", e)
        } catch (e: InvalidKeyException) {
            throw InvalidBackupException("malformed $what", e)
        }
    }

    /**
     * Registration and restore finish their relay steps later if the network fails now. The
     * profile announcement after a restore is left to the receive loop.
     */
    private suspend fun completePendingSetupQuietly() {
        try {
            completePendingSetup(announce = false)
        } catch (_: IOException) {
        }
    }
}

/** The user-visible `DataMessage`, or `null` for receipts, typing, presence and control messages. */
val IncomingMessage.dataMessage: DataMessage? get() = content.takeIf { it.hasData() }?.data
