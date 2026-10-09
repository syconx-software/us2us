package com.syconx.us2us.core.session

import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.relay.RelayClient
import com.syconx.us2us.core.relay.RelayErrorCode
import com.syconx.us2us.core.relay.RelayException
import com.syconx.us2us.core.store.ProtocolStore
import com.syconx.us2us.core.username.Username
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.InvalidKeyException
import java.io.IOException
import java.util.Collections

/**
 * The relay lookups that sealed-sender verification needs (PROTOCOL.md §6.3). An interface so the
 * receive path can be tested without a relay.
 *
 * Implementations return `null` when the relay says the account or username does not exist, and
 * throw [IOException] when the answer is unknown (network, rate limit), so the envelope is kept and
 * retried rather than dropped.
 */
interface SenderDirectory {
    suspend fun identityKey(accountId: AccountId): IdentityKey?

    suspend fun accountFor(username: Username): AccountId?
}

/**
 * [SenderDirectory] backed by the relay, with small in-memory caches: identity keys and username
 * owners rarely change, and both endpoints are rate limited.
 */
class RelaySenderDirectory(private val relay: RelayClient, cacheSize: Int = 256) : SenderDirectory {
    private val identities = lruCache<AccountId, IdentityKey>(cacheSize)
    private val owners = lruCache<String, AccountId>(cacheSize)

    override suspend fun identityKey(accountId: AccountId): IdentityKey? {
        identities[accountId]?.let { return it }
        val identity = notFoundAsNull { relay.identity(accountId) } ?: return null
        if (identity.accountId != accountId) throw IOException("relay answered for a different account")
        val key = try {
            IdentityKey(identity.identityKey)
        } catch (e: InvalidKeyException) {
            throw IOException("relay returned a malformed identity key", e)
        }
        identities[accountId] = key
        return key
    }

    override suspend fun accountFor(username: Username): AccountId? {
        owners[username.value]?.let { return it }
        val hash = withContext(Dispatchers.Default) { username.hash() }
        val owner = relay.lookupUsername(hash)?.accountId ?: return null
        owners[username.value] = owner
        return owner
    }

    private inline fun <T> notFoundAsNull(block: () -> T): T? = try {
        block()
    } catch (e: RelayException) {
        if (e.code == RelayErrorCode.NOT_FOUND) null else throw e
    }
}

/** Checks that a sealed sender is who the envelope claims (PROTOCOL.md §6.3, step 2). */
class SenderVerifier(private val store: ProtocolStore, private val directory: SenderDirectory) {

    /**
     * @param preKeyIdentity the identity key inside a `PreKeySignalMessage`, or `null` for a
     *   `SignalMessage` (whose sender is authenticated by the existing session).
     * @param claimedUsername the `sender_username` of a message request, if present.
     * @return whether the sender checks out; `false` means drop the envelope.
     * @throws IOException if the relay could not be asked; keep the envelope and retry later.
     */
    suspend fun verify(sender: AccountId, preKeyIdentity: IdentityKey?, claimedUsername: Username?): Boolean {
        if (preKeyIdentity != null) {
            // A key we already hold for this account was obtained from the relay before; asking
            // again would only cost the relay a request.
            val known = store.identity(sender)?.identityKey
            if (known != preKeyIdentity && directory.identityKey(sender) != preKeyIdentity) return false
        }
        if (claimedUsername != null && directory.accountFor(claimedUsername) != sender) return false
        return true
    }
}

private fun <K : Any, V : Any> lruCache(maxSize: Int): MutableMap<K, V> = Collections.synchronizedMap(
    object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>) = size > maxSize
    },
)
