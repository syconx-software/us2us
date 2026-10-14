package com.syconx.us2us.domain

import kotlinx.coroutines.flow.Flow

/** UI contract; no protocol keys, account credentials or decrypted data survive a lock. */
data class CommunityCard(val id: String, val name: String, val public: Boolean, val count: Int,
    val owner: String, val ownerKey: String, val revision: Long, val active: Boolean = false,
    val members: List<CommunityMemberCard> = emptyList(), val isOwner: Boolean = false,
    /** A request or an invitation not admitted yet (or turned down). */
    val pending: Boolean = false)

/** A roster entry: [username] only when the member is a verified contact with the listed key. */
data class CommunityMemberCard(val account: String, val username: String? = null, val isOwner: Boolean = false, val isSelf: Boolean = false)
data class CommunityPage(val groups: List<CommunityCard>, val next: String?)

/** [warning]: a roster could not be verified (rollback, fork, substituted owner or identity) and was not applied. */
data class CommunitySnapshot(val groups: List<CommunityCard>, val supported: Boolean, val warning: Boolean = false)
data class CommunityText(val id: String, val sender: String, val text: String, val time: Long, val status: String,
    val senderName: String? = null, val outgoing: Boolean = false)
/** A join request; [fingerprint] is the identity key to review, as [CommunityFormat.fingerprint] writes it. */
data class CommunityApplication(val account: String, val identity: String, val createdAt: Long, val fingerprint: String = "")

/** How the communities screens write account ids and keys (as on iOS). */
object CommunityFormat {
    /** `1a2b3c4d`: the first eight hex digits of an account id (its UUID string), lowercase: “Üye · 1a2b3c4d”. */
    fun shortId(account: String): String = account.lowercase().filter { it in '0'..'9' || it in 'a'..'f' }.take(8)

    /** `05ab cdef …`: a serialized key as lowercase hex in groups of four (an odd byte count ends in a group of two). */
    fun fingerprint(key: ByteArray): String = Bytes.of(key).toHex().chunked(4).joinToString(" ")
}

interface CommunitiesService {
    /** Whether the app is unlocked: community work runs, and its results stay on screen, only then. */
    val unlocked: Flow<Boolean>
    suspend fun refresh(): CommunitySnapshot
    suspend fun search(name: String, after: String? = null): CommunityPage
    suspend fun create(name: String, public: Boolean)
    suspend fun request(group: CommunityCard)
    suspend fun redeem(code: String)
    suspend fun messages(id: String): List<CommunityText>
    suspend fun send(id: String, text: String)
    suspend fun applications(id: String): List<CommunityApplication>
    suspend fun approve(id: String, application: CommunityApplication)
    suspend fun reject(id: String, account: String)
    suspend fun remove(id: String, account: String)
    suspend fun invite(id: String): String
    suspend fun revokeInvites(id: String)
    suspend fun delete(id: String)

    /** A member leaves; the community and its history are then forgotten on this phone. */
    suspend fun leave(id: String)

    /** Withdraws a pending request and forgets the community on this phone. */
    suspend fun withdraw(id: String)

    /** Forgets an ended membership on this phone only. */
    suspend fun forget(id: String)
}
