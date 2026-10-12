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
import java.time.Clock
import java.util.Collections
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

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

    /**
     * The account that owns [username] (§4.4), or `null` if nobody does.
     *
     * @param refresh ask the relay instead of trusting a cached answer, e.g. because the cached
     *   owner is not the account that claims the name. Usernames can move (§3.3).
     */
    suspend fun accountFor(username: Username, refresh: Boolean = false): AccountId?
}

/**
 * [SenderDirectory] backed by the relay, with small in-memory caches, because both endpoints are
 * rate limited (§4.4).
 *
 * An identity key belongs to its account for the account's whole life, so it is kept until the
 * cache is full. A username owner is kept only for [usernameTtl]: a name moves when its owner
 * deletes the account (the relay releases it at once) or changes it (after the hold of §3.3).
 * Answers of `404` (nobody has the name) are cached the same way. A caller that gets an owner
 * other than the one it expected asks again with `refresh = true` before refusing; such forced
 * lookups are made at most once per username per [minRefreshInterval], so a backlog of
 * envelopes with the same stale claim costs one lookup, not one each.
 */
class RelaySenderDirectory(
    private val relay: RelayClient,
    private val clock: Clock = Clock.systemUTC(),
    private val usernameTtl: Duration = DEFAULT_USERNAME_TTL,
    private val minRefreshInterval: Duration = DEFAULT_MIN_REFRESH_INTERVAL,
    cacheSize: Int = 256,
) : SenderDirectory {
    init {
        require(usernameTtl.isPositive()) { "usernameTtl must be positive" }
        require(!minRefreshInterval.isNegative() && minRefreshInterval <= usernameTtl) { "invalid minRefreshInterval" }
    }

    /**
     * One directory answer. [refreshed] marks an answer the relay just gave on purpose (a forced
     * refresh or an explicit search), which a forced refresh may reuse for [minRefreshInterval].
     */
    private class Owner(val accountId: AccountId?, val fetchedAt: Long, val refreshed: Boolean)

    private val identities = lruCache<AccountId, IdentityKey>(cacheSize)
    private val owners = lruCache<String, Owner>(cacheSize)

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

    /**
     * @throws IOException if the relay could not be asked (network, `429`, `5xx`); nothing is
     *   cached then, so the caller can keep its envelope and try again later.
     */
    override suspend fun accountFor(username: Username, refresh: Boolean): AccountId? {
        owners[username.value]?.let { cached ->
            val age = clock.millis() - cached.fetchedAt
            // A clock that went backwards makes the age negative: ask again rather than trust it.
            val reusable = age >= 0 && if (refresh) {
                cached.refreshed && age < minRefreshInterval.inWholeMilliseconds
            } else {
                age < usernameTtl.inWholeMilliseconds
            }
            if (reusable) return cached.accountId
        }
        val hash = withContext(Dispatchers.Default) { username.hash() }
        val owner = relay.lookupUsername(hash)?.accountId
        owners[username.value] = Owner(owner, clock.millis(), refreshed = refresh)
        return owner
    }

    /**
     * Records an answer the relay has just given for [username] outside this class, e.g. an
     * explicit search, so a stale cached owner is replaced at once.
     */
    fun remember(username: Username, owner: AccountId?) {
        owners[username.value] = Owner(owner, clock.millis(), refreshed = true)
    }

    private inline fun <T> notFoundAsNull(block: () -> T): T? = try {
        block()
    } catch (e: RelayException) {
        if (e.code == RelayErrorCode.NOT_FOUND) null else throw e
    }

    companion object {
        /** How long a username owner is trusted without asking again; the same as the iOS app. */
        val DEFAULT_USERNAME_TTL: Duration = 1.hours

        val DEFAULT_MIN_REFRESH_INTERVAL: Duration = 60.seconds
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
        if (claimedUsername != null) {
            // The cached owner may be out of date (the name moved, §3.3): ask the relay once more
            // before refusing, so a new owner's message request is not dropped for good.
            val owner = directory.accountFor(claimedUsername)
            if (owner != sender && directory.accountFor(claimedUsername, refresh = true) != sender) return false
        }
        return true
    }
}

private fun <K : Any, V : Any> lruCache(maxSize: Int): MutableMap<K, V> = Collections.synchronizedMap(
    object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>) = size > maxSize
    },
)
