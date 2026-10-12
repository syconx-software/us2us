package com.syconx.us2us.runtime.engine

import com.google.protobuf.ByteString
import com.syconx.us2us.core.AccessKey
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.contacts.ContactState
import com.syconx.us2us.core.messenger.AccessKeyRequiredException
import com.syconx.us2us.core.messenger.IncomingMessage
import com.syconx.us2us.core.messenger.SendNotAllowedException
import com.syconx.us2us.core.profile.ProfilePicture
import com.syconx.us2us.core.proto.Attachment
import com.syconx.us2us.core.proto.Content
import com.syconx.us2us.core.proto.ControlMessage
import com.syconx.us2us.core.proto.DataMessage
import com.syconx.us2us.core.proto.MessageRequestResponse
import com.syconx.us2us.core.proto.PresenceMessage
import com.syconx.us2us.core.proto.ReceiptMessage
import com.syconx.us2us.core.proto.accessKeyUpdate
import com.syconx.us2us.core.proto.attachment
import com.syconx.us2us.core.proto.content
import com.syconx.us2us.core.proto.controlMessage
import com.syconx.us2us.core.proto.dataMessage
import com.syconx.us2us.core.proto.messageRequestResponse
import com.syconx.us2us.core.proto.presenceMessage
import com.syconx.us2us.core.proto.receiptMessage
import com.syconx.us2us.core.proto.text
import com.syconx.us2us.core.proto.typingMessage
import com.syconx.us2us.core.safety.SafetyNumber
import com.syconx.us2us.core.store.IdentityTrust
import com.syconx.us2us.core.store.StoredIdentity
import com.syconx.us2us.core.username.Username
import com.syconx.us2us.core.vault.DataKey
import com.syconx.us2us.runtime.net.RelayStream
import com.syconx.us2us.runtime.storage.ContactIdentity
import com.syconx.us2us.runtime.storage.ContactProfile
import com.syconx.us2us.runtime.storage.IdentityVerification
import com.syconx.us2us.runtime.storage.LocalStorage
import com.syconx.us2us.runtime.storage.OutboxEntry
import com.syconx.us2us.runtime.storage.PlainSqlDriver
import com.syconx.us2us.runtime.storage.RandomIds
import com.syconx.us2us.runtime.storage.StorageLayout
import com.syconx.us2us.runtime.storage.StoredContact
import com.syconx.us2us.runtime.storage.newAccount
import com.syconx.us2us.runtime.support.ManualClock
import com.syconx.us2us.runtime.vault.AppDirectories
import com.syconx.us2us.runtime.vault.SecretKeyMaterial
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import org.signal.libsignal.protocol.IdentityKey
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.UUID
import kotlin.time.Duration

/** One recorded send. */
class SentMessage(
    val content: Content,
    val to: AccountId,
    val requestState: ContactState,
    val accessKey: ByteString?,
    val ephemeral: Boolean,
) {
    val control: ControlMessage? get() = if (content.hasControl()) content.control else null
    val data: DataMessage? get() = if (content.hasData()) content.data else null
    val receipt: ReceiptMessage? get() = if (content.hasReceipt()) content.receipt else null
    val isAccessKeyUpdate: Boolean get() = control?.hasAccessKey() == true
    val isProfile: Boolean get() = control?.hasProfile() == true
    val isAccept: Boolean
        get() = control?.hasRequestResponse() == true && control?.requestResponse?.type == MessageRequestResponse.Type.ACCEPT
}

/** A relay stream driven by the test. */
class FakeStream : RelayStream {
    sealed interface Event {
        class Message(val message: IncomingMessage) : Event

        data object QueueEmpty : Event

        class Fail(val error: Exception) : Event
    }

    private val events = Channel<Event>(Channel.UNLIMITED)
    private val lock = Any()
    private val deliveredIds = ArrayList<String>()

    @Volatile
    var isClosed = false
        private set

    @Volatile
    var isRunning = false
        private set

    val delivered: List<String> get() = synchronized(lock) { deliveredIds.toList() }

    fun push(event: Event) {
        events.trySend(event)
    }

    override suspend fun run(deliver: suspend (IncomingMessage) -> Unit, onQueueEmpty: suspend () -> Unit) {
        isRunning = true
        try {
            for (event in events) {
                when (event) {
                    is Event.Message -> {
                        deliver(event.message)
                        synchronized(lock) { deliveredIds += event.message.envelopeId }
                    }
                    Event.QueueEmpty -> onQueueEmpty()
                    is Event.Fail -> throw event.error
                }
            }
        } finally {
            isRunning = false
        }
    }

    override fun close() {
        isClosed = true
        events.close()
    }
}

/**
 * An in-memory relay + crypto stand-in: records what the runtime sends and lets tests inject
 * failures. Identity decisions go to the storage's protocol rows, as the production backend's do.
 */
class FakeBackend(override val accountId: AccountId, var storage: LocalStorage) : MessagingBackend {
    private val lock = Any()
    private val sentMessages = ArrayList<SentMessage>()
    private val presence = ArrayList<Pair<Content, List<AccountId>>>()
    private val streams = ArrayList<FakeStream>()
    private val sendFailures = ArrayList<Pair<AccountId?, Exception>>()
    private val directory = HashMap<String, AccountId>()
    private val identityKeys = HashMap<AccountId, ByteArray>()
    private val numbers = HashMap<AccountId, ByteArray>()
    val uploads = HashMap<ByteString, ByteArray>()
    private val downloads = HashMap<ByteString, ByteArray>()
    private val downloadFailures = HashMap<ByteString, Exception>()
    private val deleted = ArrayList<ByteString>()
    var maintenanceRuns = 0
        private set
    var rotations = 0
        private set
    var ownPicture: ProfilePicture? = null
    var showInSearch = true
    var avatarSyncs = 0
        private set
    val searchPictures = HashMap<AccountId, ProfilePicture>()
    var presenceRunsInside = false
    var announcesChanges = false
    val profileUpdates = ArrayList<Pair<String, Boolean>>()
    var offlineAnnouncements = 0
        private set

    /** Downloads wait for it while set. */
    @Volatile var downloadGate: CompletableDeferred<Unit>? = null
    var downloadsStarted = 0
        private set

    val sent: List<SentMessage> get() = synchronized(lock) { sentMessages.toList() }
    val presenceSends: List<Pair<Content, List<AccountId>>> get() = synchronized(lock) { presence.toList() }
    val latestStream: FakeStream? get() = synchronized(lock) { streams.lastOrNull() }
    val streamCount: Int get() = synchronized(lock) { streams.size }
    fun stream(index: Int): FakeStream = synchronized(lock) { streams[index] }
    val deletedMedia: List<ByteString> get() = synchronized(lock) { deleted.toList() }

    fun sent(to: AccountId): List<SentMessage> = sent.filter { it.to == to }

    fun failNextSend(to: AccountId? = null, error: Exception) = synchronized(lock) { sendFailures += to to error }

    fun register(username: String, account: AccountId) = synchronized(lock) { directory[username] = account }

    fun setDownload(bytes: ByteArray, mediaId: ByteString) = synchronized(lock) { downloads[mediaId] = bytes }

    fun failDownload(mediaId: ByteString, error: Exception) = synchronized(lock) { downloadFailures[mediaId] = error }

    /** Gives [contact] a stored key (changed when [changed]) and the number the key-bound calls compare with. */
    fun setIdentity(contact: AccountId, key: IdentityKey, number: ByteArray, changed: Boolean) {
        storage.protocolStorage.storeIdentity(
            contact.toString(),
            StoredIdentity(key.serialize(), if (changed) IdentityTrust.CHANGED else IdentityTrust.TRUSTED, false, 0),
        )
        synchronized(lock) {
            identityKeys[contact] = key.serialize()
            numbers[contact] = number
        }
    }

    override suspend fun username(): Username = storage.read { it.account.load()!!.username }

    override suspend fun currentAccessKey(): ByteString =
        ByteString.copyFrom(storage.read { it.account.load()!!.accessKey.toBytes() })

    override suspend fun openStream(): RelayStream = FakeStream().also { synchronized(lock) { streams += it } }

    override suspend fun send(
        content: Content,
        recipient: AccountId,
        requestState: ContactState,
        theirAccessKey: ByteString?,
        ephemeral: Boolean,
    ): SendOutcome {
        synchronized(lock) {
            val failure = sendFailures.indexOfFirst { it.first == null || it.first == recipient }
            if (failure >= 0) throw sendFailures.removeAt(failure).second
            if (ephemeral && theirAccessKey == null) throw AccessKeyRequiredException(recipient)
            if (requestState == ContactState.BLOCKED) throw SendNotAllowedException(recipient, requestState)
            sentMessages += SentMessage(content, recipient, requestState, theirAccessKey, ephemeral)
        }
        return SendOutcome(System.currentTimeMillis())
    }

    override suspend fun sendPresence(presence: Content, contacts: List<Recipient>): List<AccountId> {
        val accounts = contacts.map { it.account }
        synchronized(lock) { this.presence += presence to accounts }
        return accounts
    }

    override val runsPresence: Boolean get() = presenceRunsInside

    override suspend fun announceOffline() {
        offlineAnnouncements += 1
    }

    override suspend fun performMaintenance() {
        maintenanceRuns += 1
    }

    override suspend fun rotateAccessKey(): ByteString {
        val key = AccessKey.generate()
        val account = storage.read { it.account.load()!! }
        storage.accountStore.save(account.copy(accessKey = key))
        rotations += 1
        return ByteString.copyFrom(key.toBytes())
    }

    override suspend fun announceProfile(profile: Content, contacts: List<Recipient>): List<AccountId> = emptyList()

    override suspend fun needsProfileAnnouncement(): Boolean = false

    override val announcesProfileChanges: Boolean get() = announcesChanges

    override suspend fun updateProfile(displayName: String, presenceSharing: Boolean) {
        synchronized(lock) { profileUpdates += displayName to presenceSharing }
    }

    override suspend fun uploadAttachment(file: File, kind: Attachment.Kind, contentType: String, fileName: String?): Attachment {
        val bytes = file.readBytes()
        val uploaded = attachment {
            this.kind = kind
            mediaId = RandomIds.make()
            deleteToken = ByteString.copyFrom(ByteArray(32) { 3 })
            key = ByteString.copyFrom(ByteArray(64) { 4 })
            digest = ByteString.copyFrom(ByteArray(32) { 5 })
            size = bytes.size.toLong()
            this.contentType = contentType
            fileName?.let { this.fileName = it }
        }
        synchronized(lock) { uploads[uploaded.mediaId] = bytes }
        return uploaded
    }

    override suspend fun downloadAttachment(attachment: Attachment, destination: File, progress: (Double) -> Unit) {
        synchronized(lock) { downloadsStarted += 1 }
        downloadGate?.await()
        val failure = synchronized(lock) { downloadFailures.remove(attachment.mediaId) }
        if (failure != null) throw failure
        progress(0.5)
        destination.writeBytes(synchronized(lock) { downloads[attachment.mediaId] } ?: "media".toByteArray())
        progress(1.0)
    }

    override suspend fun deleteUploadedMedia(attachment: Attachment) {
        synchronized(lock) { deleted += attachment.mediaId }
    }

    override suspend fun findAccount(username: Username): DirectoryEntry? =
        synchronized(lock) { directory[username.value] }?.let { DirectoryEntry(it, ByteArray(33)) }

    override suspend fun usernameOwner(username: Username): AccountId? = synchronized(lock) { directory[username.value] }

    override suspend fun changeUsername(username: Username) {
        val account = storage.read { it.account.load()!! }
        storage.accountStore.save(account.copy(username = username))
    }

    override suspend fun safetyNumber(contact: AccountId): SafetyNumber? = null

    override suspend fun identityTrust(contact: AccountId): ContactIdentity = storage.protocolStorage.identityTrust(contact)

    private fun currentKey(contact: AccountId): IdentityKey? = when (val trust = storage.protocolStorage.identityTrust(contact)) {
        ContactIdentity.Unknown -> null
        is ContactIdentity.Trusted -> trust.key
        is ContactIdentity.Changed -> trust.current
    }

    override suspend fun approveIdentityChange(contact: AccountId, shownNumber: ByteArray): Boolean {
        val trust = storage.protocolStorage.identityTrust(contact) as? ContactIdentity.Changed ?: return false
        if (synchronized(lock) { numbers[contact] }?.contentEquals(shownNumber) != true) return false
        return storage.protocolStorage.acceptIdentity(trust.current, contact)
    }

    override suspend fun markVerified(contact: AccountId, comparedNumber: ByteArray, verification: IdentityVerification): Boolean {
        val key = currentKey(contact) ?: return false
        if (synchronized(lock) { numbers[contact] }?.contentEquals(comparedNumber) != true) return false
        return storage.protocolStorage.verifyIdentity(key, contact, verification)
    }

    override suspend fun verifySafetyNumber(contact: AccountId, scanned: ByteArray, verification: IdentityVerification): Boolean =
        markVerified(contact, scanned, verification)

    override suspend fun pinVerifiedIdentity(identityKey: ByteArray, contact: AccountId, verification: IdentityVerification): Boolean =
        storage.protocolStorage.pinVerifiedIdentity(IdentityKey(identityKey), contact, verification)

    override suspend fun clearVerification(contact: AccountId) = storage.protocolStorage.setIdentityVerified(false, contact)

    override suspend fun identityVerification(contact: AccountId): IdentityVerification? = storage.protocolStorage.identityVerification(contact)

    override suspend fun forgetContact(contact: AccountId) {}

    override suspend fun exportBackup(password: String): ByteArray = ByteArray(0)

    override suspend fun deleteAccount() {}

    override suspend fun ownIdentityKey(): ByteArray = storage.read { it.account.load()!!.identityKeyPair.publicKey.serialize() }

    override suspend fun ownAvatar(): OwnProfilePicture = OwnProfilePicture(ownPicture, showInSearch, AvatarRecordStatus.IN_SYNC)

    override suspend fun setOwnAvatar(picture: ProfilePicture?, showInSearch: Boolean) {
        ownPicture = picture
        this.showInSearch = showInSearch
        val account = storage.read { it.account.load()!! }
        storage.accountStore.save(account.copy(profilePicture = picture, showPictureInSearch = showInSearch))
    }

    override suspend fun syncAvatarRecord(): AvatarRecordStatus {
        avatarSyncs += 1
        return AvatarRecordStatus.IN_SYNC
    }

    override suspend fun searchAvatar(username: Username, account: AccountId): ProfilePicture? = searchPictures[account]

    companion object {
        fun networkError() = IOException("offline")
    }
}

/** A storage with a saved account, an engine on it, and a fake backend. */
class EngineFixture(
    root: File,
    val clock: ManualClock = ManualClock(),
    configuration: MessagingEngine.Configuration = MessagingEngine.Configuration(presenceReplyDelay = Duration.ZERO),
) {
    val directories = AppDirectories(File(root, "app")).also { it.prepare() }
    private val dek = DataKey(ByteArray(32).also(SecureRandom()::nextBytes))
    val key = SecretKeyMaterial(dek.toBytes())
    val storage: LocalStorage = LocalStorage.open(StorageLayout.standard(directories), dek, clock.now(), PlainSqlDriver).storage
    val account = newAccount(username = "tester").also { storage.accountStore.save(it) }
    val backend = FakeBackend(account.accountId, storage)
    val engine = MessagingEngine(storage, backend, directories.runtime, key, clock, configuration)

    suspend fun start() = engine.start()

    /** Delivers [content] from [sender] as the relay would (already decrypted). */
    suspend fun receive(
        content: Content,
        sender: AccountId,
        username: String? = null,
        identityChanged: Boolean = false,
        relayId: String = UUID.randomUUID().toString(),
    ) = engine.receive(
        IncomingMessage(
            envelopeId = relayId,
            serverTimestamp = clock.now().toEpochMilli(),
            sender = sender,
            senderUsername = username,
            content = content,
            contactState = ContactState.INCOMING_REQUEST,
            identityChanged = identityChanged,
        ),
    )

    /** Makes [peer] an accepted contact holding our key and giving us theirs. */
    fun acceptedContact(
        peer: AccountId = AccountId(UUID.randomUUID()),
        theirKey: ByteString = ByteString.copyFrom(ByteArray(16) { 2 }),
        presenceSharing: Boolean = true,
    ): AccountId {
        val now = clock.now()
        storage.write { tx ->
            tx.contacts.create(peer, ContactState.ACCEPTED, now)
            tx.contacts.setTheirAccessKey(theirKey, peer)
            tx.contacts.setOurAccessKeySent(ByteString.copyFrom(account.accessKey.toBytes()), peer)
            tx.contacts.setProfileSent(now, peer)
            tx.contacts.setProfile(ContactProfile(displayName = "Peer", presenceSharing = presenceSharing), peer)
            tx.conversations.ensure(peer, now)
        }
        return peer
    }

    fun outbox(): List<OutboxEntry> = storage.read { it.outbox.all() }

    suspend fun messages(peer: AccountId): List<MessageItem> = engine.messages(peer)

    fun contact(peer: AccountId): StoredContact? = storage.read { it.contacts.get(peer) }

    fun close() {
        storage.close()
    }
}

// MARK: Content builders

fun dataContent(id: ByteString = RandomIds.make(), expire: Int = 0, viewOnce: Boolean = false, build: com.syconx.us2us.core.proto.DataMessageKt.Dsl.() -> Unit): Content =
    content {
        data = dataMessage {
            messageId = id
            sentAt = 1_800_000_000_000L
            expireTimerSeconds = expire
            this.viewOnce = viewOnce
            build()
        }
    }

fun textContent(body: String, id: ByteString = RandomIds.make(), expire: Int = 0): Content =
    dataContent(id, expire) { text = text { this.body = body } }

fun attachmentContent(mediaId: ByteString, id: ByteString = RandomIds.make(), viewOnce: Boolean = false): Content =
    dataContent(id, viewOnce = viewOnce) {
        attachment = attachment {
            kind = Attachment.Kind.IMAGE
            this.mediaId = mediaId
            deleteToken = ByteString.copyFrom(ByteArray(32) { 3 })
            key = ByteString.copyFrom(ByteArray(64) { 4 })
            digest = ByteString.copyFrom(ByteArray(32) { 5 })
            size = 5
            contentType = "image/jpeg"
            thumbnail = ByteString.copyFromUtf8("thumb")
            caption = if (viewOnce) "" else "a photo"
        }
    }

fun receiptContent(type: ReceiptMessage.Type, ids: List<ByteString>): Content =
    content { receipt = receiptMessage { this.type = type; messageIds += ids } }

fun presenceContent(state: PresenceMessage.State, reply: Boolean = false): Content =
    content { presence = presenceMessage { this.state = state; validForSeconds = 300; this.reply = reply } }

fun typingContent(started: Boolean): Content = content { typing = typingMessage { this.started = started } }

fun accessKeyContent(key: ByteString): Content = content { control = controlMessage { accessKey = accessKeyUpdate { accessKey = key } } }

fun acceptContent(): Content =
    content { control = controlMessage { requestResponse = messageRequestResponse { type = MessageRequestResponse.Type.ACCEPT } } }

fun controlContent(build: com.syconx.us2us.core.proto.ControlMessageKt.Dsl.() -> Unit): Content = content { control = controlMessage(build) }
