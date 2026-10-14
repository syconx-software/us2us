package com.syconx.us2us.core.relay

import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.community.SignedCommunityState
import kotlinx.serialization.Serializable

@Serializable
data class CommunitySummary(
    val id: AccountId, val name: String, val visibility: String,
    val ownerAccountId: AccountId, val ownerIdentityKey: String, val memberCount: Int, val revision: Long,
)
@Serializable
class CommunityDirectory(val communities: List<CommunitySummary>, val nextCursor: AccountId? = null)
@Serializable
class CommunityMemberships(val communities: List<SignedCommunityState>)
@Serializable
data class CommunityRequest(val accountId: AccountId, val identityKey: String, val createdAt: Long)
@Serializable
class CommunityRequests(val requests: List<CommunityRequest>)
@Serializable
class CommunityInviteToken(val token: String, val expiresAt: Long) {
    override fun toString(): String = "CommunityInviteToken(redacted)"
}
@Serializable
internal class CommunityInviteRequest(val expiresInSeconds: Int)
@Serializable
internal class CommunityRedeemRequest(val communityId: AccountId, val token: String)
@Serializable
internal class CommunityAckRequest(val ids: List<String>)
@Serializable
class CommunityEnvelope(
    val id: String, val communityId: AccountId, val revision: Long, val serverTimestamp: Long,
    @Serializable(with = Base64UrlBytes::class) val envelope: ByteArray,
)
@Serializable
class CommunityInbox(val envelopes: List<CommunityEnvelope>, val more: Boolean)
