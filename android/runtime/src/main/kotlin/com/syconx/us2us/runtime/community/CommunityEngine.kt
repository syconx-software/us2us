package com.syconx.us2us.runtime.community

import com.google.protobuf.ByteString
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.contacts.UsernameStatus
import com.syconx.us2us.core.community.CommunityInvite
import com.syconx.us2us.core.community.CommunityMember
import com.syconx.us2us.core.community.CommunityState
import com.syconx.us2us.core.community.SignedCommunityState
import com.syconx.us2us.core.encoding.Base64Url
import com.syconx.us2us.core.messenger.Messenger
import com.syconx.us2us.core.proto.communityMessage
import com.syconx.us2us.core.relay.CommunityDirectory
import com.syconx.us2us.core.relay.CommunityRequest
import com.syconx.us2us.core.relay.CommunitySummary
import com.syconx.us2us.core.relay.RelayClient
import com.syconx.us2us.core.relay.RelayException
import com.syconx.us2us.runtime.storage.CommunityDelivery
import com.syconx.us2us.runtime.storage.CommunityRepository
import com.syconx.us2us.runtime.storage.ContactIdentity
import com.syconx.us2us.runtime.storage.LocalStorage
import com.syconx.us2us.runtime.storage.StoredCommunity
import com.syconx.us2us.runtime.storage.StoredCommunityMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * An unlocked account's community engine. No personal contacts or credentials are changed.
 * Building one touches nothing: the database is first written on first use.
 */
class CommunityEngine(private val messenger: Messenger, private val storage: LocalStorage) {
    private val mutex = Mutex()
    private val repository = CommunityRepository(storage)
    private val preparation = Any()

    @Volatile
    private var prepared = false

    /** Recipients whose community inbox is full, and until when (this session only). */
    private val deferredUntil = HashMap<AccountId, Long>()
    private val relay get() = messenger.communityRelay
    val accountId get() = messenger.accountId
    private val ownKey get() = Base64Url.encode(messenger.account.identityKeyPair.publicKey.serialize())
    var supported: Boolean = true
        private set

    /**
     * The last pass met a roster it could not accept: a rollback, a fork, a substituted owner or
     * member identity, or one without this account's own key. Nothing of it was applied.
     */
    @Volatile
    var securityWarning: Boolean = false
        private set

    fun communities(): List<StoredCommunity> = prepare().let { repository.all() }
    fun messages(id: AccountId): List<StoredCommunityMessage> = prepare().let { repository.messages(id) }

    /**
     * The verified username of each member who is also a contact with the very identity key the
     * roster lists; anyone else stays unnamed.
     */
    fun memberNames(state: CommunityState): Map<AccountId, String> {
        val names = HashMap<AccountId, String>()
        for (member in state.members) {
            if (member.accountId == accountId) continue
            val contact = storage.read { it.contacts.get(member.accountId) } ?: continue
            val username = contact.profile.username?.takeIf { contact.profile.usernameStatus == UsernameStatus.VERIFIED } ?: continue
            val trust = storage.protocolStorage.identityTrust(member.accountId)
            if (trust is ContactIdentity.Trusted && trust.key.serialize().contentEquals(member.keyBytes())) names[member.accountId] = username.value
        }
        return names
    }

    suspend fun search(query: String, after: AccountId? = null): CommunityDirectory {
        // The relay applies the name rule to queries: what it would refuse is never sent.
        val prefix = requireNotNull(CommunityState.searchPrefix(query)) { "invalid search query" }
        return relay.communityDirectory(prefix, after).also { result ->
            require(result.communities.size <= 20)
            result.communities.forEach { require(it.visibility == "public" && CommunityState.validName(it.name) && it.memberCount in 1..100); CommunityMember(it.ownerAccountId, it.ownerIdentityKey).keyBytes() }
        }
    }

    suspend fun create(name: String, isPublic: Boolean): AccountId = mutex.withLock {
        prepare()
        val normalized = requireNotNull(CommunityState.normalizedName(name)) { "invalid community name" }
        val id = AccountId(UUID.randomUUID())
        val state = CommunityState(id, 1, if (isPublic) "public" else "private", accountId, ownKey, if (isPublic) normalized else "", listOf(CommunityMember(accountId, ownKey)))
        val signed = messenger.signCommunityState(state)
        repository.pin(id, ownKey, normalized)
        val saved = relay.createCommunity(signed)
        require(saved.verify(ownKey).encode().contentEquals(state.encode()))
        repository.save(saved)
        id
    }

    suspend fun request(summary: CommunitySummary) = mutex.withLock {
        prepare()
        require(summary.visibility == "public" && CommunityState.validName(summary.name))
        CommunityMember(summary.ownerAccountId, summary.ownerIdentityKey).keyBytes()
        repository.pin(summary.id, summary.ownerIdentityKey, summary.name)
        relay.requestCommunity(summary.id)
    }

    suspend fun redeem(code: String): AccountId = mutex.withLock {
        prepare()
        val invite = CommunityInvite.parse(code.trim())
        repository.pin(invite.communityId, invite.ownerIdentityKey, invite.name)
        val result = relay.redeemCommunityInvite(invite.communityId, invite.token)
        require(result.id == invite.communityId && result.ownerIdentityKey == invite.ownerIdentityKey)
        invite.communityId
    }

    suspend fun requests(id: AccountId): List<CommunityRequest> = mutex.withLock {
        prepare()
        require(current(id).ownerAccountId == accountId)
        relay.communityRequests(id).requests.also { list -> require(list.size <= 100); list.forEach { CommunityMember(it.accountId, it.identityKey).keyBytes() } }
    }

    suspend fun approve(id: AccountId, request: CommunityRequest) = mutex.withLock {
        prepare()
        val state = current(id)
        require(state.ownerAccountId == accountId && state.members.size < 100)
        val pending = relay.communityRequests(id).requests.single { it.accountId == request.accountId }
        require(pending.identityKey == request.identityKey) { "request identity changed; review again" }
        require(state.member(pending.accountId) == null)
        val added = CommunityMember(pending.accountId, pending.identityKey).also { it.keyBytes() }
        update(state.copy(revision = state.revision + 1, members = (state.members + added).sortedBy { it.accountId.toString() }))
    }

    suspend fun reject(id: AccountId, account: AccountId) = mutex.withLock { prepare(); relay.rejectCommunityRequest(id, account) }
    suspend fun removeMember(id: AccountId, member: AccountId) = mutex.withLock {
        prepare()
        val state = current(id)
        require(state.ownerAccountId == accountId && member != accountId && state.member(member) != null)
        update(state.copy(revision = state.revision + 1, members = state.members.filter { it.accountId != member }))
    }
    suspend fun invite(id: AccountId): String = mutex.withLock {
        prepare()
        val state = current(id)
        require(state.ownerAccountId == accountId)
        val created = relay.createCommunityInvite(id)
        CommunityInvite(id, created.token, state.ownerIdentityKey, repository.get(id)!!.name).code()
    }
    suspend fun revokeInvites(id: AccountId) = mutex.withLock { prepare(); relay.revokeCommunityInvites(id) }
    suspend fun delete(id: AccountId) = mutex.withLock {
        prepare()
        relay.deleteCommunity(id)
        repository.remove(id)
    }

    /** A member leaves; the community, its history and its key are then forgotten on this phone. The owner deletes instead. */
    suspend fun leave(id: AccountId) = mutex.withLock {
        prepare()
        val known = repository.get(id) ?: return@withLock
        require(known.signed == null || known.state?.ownerAccountId != accountId) { "the owner deletes the community instead" }
        try {
            relay.leaveCommunity(id)
        } catch (e: RelayException) {
            // Not a member (any more): nothing left to leave on the relay.
            if (e.httpStatus != 404) throw e
        }
        repository.remove(id)
    }

    /** Withdraws this account's pending request (idempotent on the relay), then forgets the community here. */
    suspend fun withdraw(id: AccountId) = mutex.withLock {
        prepare()
        try {
            relay.withdrawCommunityRequest(id)
        } catch (e: RelayException) {
            if (e.httpStatus != 404) throw e
        }
        repository.remove(id)
    }

    /** Forgets an ended membership on this phone only: rows, queued sends and the key slot. */
    suspend fun forget(id: AccountId) = mutex.withLock {
        prepare()
        val known = repository.get(id) ?: return@withLock
        require(!known.active) { "a current membership is left, not forgotten" }
        repository.remove(id)
    }

    suspend fun send(id: AccountId, text: String) = mutex.withLock {
        prepare()
        require(text.isNotBlank() && text.toByteArray(Charsets.UTF_8).size <= CommunityState.MAX_TEXT_BYTES)
        val state = current(id)
        val messageId = AccountId(UUID.randomUUID())
        val sentAt = System.currentTimeMillis()
        val message = communityMessage {
            communityId = ByteString.copyFrom(id.toBytes())
            revision = state.revision
            this.messageId = ByteString.copyFrom(messageId.toBytes())
            this.sentAt = sentAt
            this.text = text
        }
        repository.add(StoredCommunityMessage(id, accountId, messageId, sentAt, text, "preparing"))
        try {
            // A bounded 99-envelope batch; no delivery until every ciphertext is durable.
            val deliveries = state.members.filter { it.accountId != accountId }.map { member ->
                currentCoroutineContext().ensureActive()
                CommunityDelivery(id, messageId, member.accountId, state.revision, messenger.encryptCommunityMessage(member, message))
            }
            storage.write {
                deliveries.forEach(repository::enqueue)
                repository.mark(id, messageId, if (deliveries.isEmpty()) "sent" else "pending")
            }
        } catch (e: Throwable) {
            repository.mark(id, messageId, "failed", clearOutbox = true)
            throw e
        }
        // Every ciphertext is queued: a relay that cannot take them now gets them on a later pass.
        try {
            flush()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /** Invoked only while unlocked. An old relay disables just this extension. */
    suspend fun sync() = mutex.withLock {
        prepare()
        securityWarning = false
        val mine = try { relay.myCommunities() } catch (e: RelayException) {
            if (e.httpStatus == 404 || e.httpStatus == 405) { supported = false; return@withLock }
            throw e
        }
        require(mine.communities.size <= 50)
        supported = true
        val active = HashSet<AccountId>()
        var malformedState = false
        for (signed in mine.communities) {
            val decoded = try { signed.verify() } catch (_: IllegalArgumentException) { malformedState = true; securityWarning = true; continue }
            // Restored clients must recover owner pins by discovery or a new private invitation.
            val known = repository.get(decoded.id)
            if (known == null && !(decoded.ownerAccountId == accountId && decoded.ownerIdentityKey == ownKey)) continue
            if (decoded.member(accountId)?.identityKey != ownKey) { malformedState = true; securityWarning = true; continue }
            // A rollback, fork or substituted identity against the pinned state is refused, never applied.
            try { repository.save(signed) } catch (_: IllegalArgumentException) { malformedState = true; securityWarning = true; continue }
            active += decoded.id
        }
        // A malformed response cannot force valid locally pinned memberships to disappear.
        if (!malformedState) repository.deactivateMissing(active)
        // The outbox never holds up the inbox: what fails here is retried on the next pass.
        try {
            flush()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        // A page may be empty and still have more behind it; the rest waits for the next pass.
        var pages = 0
        while (pages++ < MAX_INBOX_PAGES && receive()) currentCoroutineContext().ensureActive()
    }

    /** One inbox page, acknowledged; whether the relay has more. */
    private suspend fun receive(): Boolean {
        val inbox = relay.communityInbox()
        require(inbox.envelopes.size <= 100 && inbox.envelopes.sumOf { it.envelope.size.toLong() } <= 1048576)
        val acknowledgements = ArrayList<String>()
        for (entry in inbox.envelopes) {
            currentCoroutineContext().ensureActive()
            if (repository.wasProcessed(entry.id)) { acknowledgements += entry.id; continue }
            val known = repository.get(entry.communityId)
            if (known?.active != true) { acknowledgements += entry.id; continue }
            val state = try { current(entry.communityId) } catch (_: IllegalArgumentException) {
                securityWarning = true
                repository.processed(entry.id)
                acknowledgements += entry.id
                continue
            } catch (e: RelayException) {
                // Gone, or no longer ours to read: this record can never be opened, and must not block the ones behind it.
                if (e.httpStatus != 403 && e.httpStatus != 404) throw e
                repository.processed(entry.id)
                acknowledgements += entry.id
                continue
            }
            if (entry.revision != state.revision || entry.envelope.size !in 1..RelayClient.MAX_COMMUNITY_ENVELOPE_BYTES) { acknowledgements += entry.id; continue }
            storage.protocolStorage.withStagedInboundWrites {
                try {
                    val decoded = messenger.decryptCommunityEnvelope(entry.envelope, state)
                    storage.write {
                        if (decoded != null) {
                            val (sender, message) = decoded
                            repository.add(StoredCommunityMessage(state.id, sender, AccountId.fromBytes(message.messageId.toByteArray()), message.sentAt, message.text, "received"))
                        }
                        repository.processed(entry.id)
                    }
                } catch (_: IllegalArgumentException) {
                    // Malformed/unauthorised payloads are poison records, not a retryable disk/network failure.
                    securityWarning = true
                    storage.protocolStorage.discardStagedInboundWrites()
                    repository.processed(entry.id)
                } catch (e: Throwable) {
                    storage.protocolStorage.discardStagedInboundWrites()
                    throw e
                }
            }
            acknowledgements += entry.id
        }
        relay.acknowledgeCommunityEnvelopes(acknowledgements)
        return inbox.more
    }

    private fun prepare() {
        if (prepared) return
        synchronized(preparation) {
            if (prepared) return
            // A send cut off before its ciphertexts were queued never went anywhere.
            repository.recoverInterruptedPreparations()
            prepared = true
        }
    }

    private suspend fun current(id: AccountId): CommunityState {
        require(repository.get(id) != null) { "unknown community owner; discover or redeem an invitation first" }
        val signed = relay.community(id)
        val decoded = signed.verify()
        require(decoded.id == id)
        require(decoded.member(accountId)?.identityKey == ownKey) { "not a current member" }
        return repository.save(signed)
    }
    private suspend fun update(state: CommunityState) {
        val signed = messenger.signCommunityState(state)
        val result = relay.updateCommunity(state.id, signed)
        require(result.verify(state.ownerIdentityKey).encode().contentEquals(state.encode()))
        repository.save(result)
    }

    /**
     * One pass over the outbox. A full recipient inbox (`429`) only defers that recipient, until its
     * `Retry-After`; a recipient no longer in the community (`404`) is skipped and the roster
     * fetched again; any other refusal of one recipient (`4xx`, an envelope too large) fails that
     * recipient alone. Only a changed roster (another revision, `409`) stops the whole message.
     */
    private suspend fun flush() {
        val stopped = HashSet<Pair<AccountId, AccountId>>()
        val gone = HashSet<AccountId>()
        val now = System.currentTimeMillis()
        deferredUntil.values.removeAll { it <= now }
        for (delivery in repository.pending()) {
            if (delivery.communityId to delivery.messageId in stopped || delivery.recipient in deferredUntil) continue
            currentCoroutineContext().ensureActive()
            val state = try { repository.get(delivery.communityId)?.state } catch (_: IllegalArgumentException) { securityWarning = true; null }
            if (state == null || state.revision != delivery.revision || state.member(delivery.recipient) == null) {
                repository.mark(delivery.communityId, delivery.messageId, "stale", clearOutbox = true)
                stopped += delivery.communityId to delivery.messageId
                continue
            }
            if (delivery.envelope.size !in 1..RelayClient.MAX_COMMUNITY_ENVELOPE_BYTES) {
                repository.failed(delivery)
                continue
            }
            try {
                relay.sendCommunityEnvelope(delivery.communityId, delivery.recipient, delivery.messageId, delivery.revision, delivery.envelope)
                repository.delivered(delivery)
            } catch (e: CancellationException) {
                throw e
            } catch (_: IllegalArgumentException) {
                repository.failed(delivery)
            } catch (e: RelayException) {
                when {
                    e.httpStatus == HTTP_CONFLICT -> {
                        repository.mark(delivery.communityId, delivery.messageId, "stale", clearOutbox = true)
                        stopped += delivery.communityId to delivery.messageId
                    }
                    e.httpStatus == HTTP_TOO_MANY_REQUESTS ->
                        deferredUntil[delivery.recipient] = System.currentTimeMillis() + 1000 * (e.retryAfterSeconds ?: 0).coerceIn(0, MAX_RETRY_AFTER_SECONDS)
                    e.httpStatus == HTTP_NOT_FOUND -> {
                        // Left, removed or deleted: there is no one to deliver to any more.
                        repository.delivered(delivery)
                        gone += delivery.communityId
                    }
                    e.httpStatus in 400..499 -> repository.failed(delivery)
                    else -> throw e
                }
            }
        }
        for (id in gone) {
            try {
                current(id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: IllegalArgumentException) {
                securityWarning = true
            } catch (_: java.io.IOException) {
            }
        }
    }

    private companion object {
        const val HTTP_NOT_FOUND = 404
        const val HTTP_CONFLICT = 409
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val MAX_RETRY_AFTER_SECONDS = 3600L
        const val MAX_INBOX_PAGES = 5
    }
}
