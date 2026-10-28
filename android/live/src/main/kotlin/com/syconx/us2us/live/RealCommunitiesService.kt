package com.syconx.us2us.live

import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.encoding.Base64Url
import com.syconx.us2us.core.relay.CommunityRequest
import com.syconx.us2us.core.relay.CommunitySummary
import com.syconx.us2us.domain.*
import com.syconx.us2us.runtime.facade.AppRuntime
import com.syconx.us2us.runtime.facade.LockState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Results are loaded under the unlocked runtime, never cached in this adapter. */
internal class RealCommunitiesService(private val runtime: AppRuntime) : CommunitiesService {
    override val unlocked: Flow<Boolean> = runtime.lockState.map { it == LockState.UNLOCKED }.distinctUntilChanged()

    override suspend fun refresh() = runtime.withCommunities { engine ->
        engine.sync()
        CommunitySnapshot(engine.communities().map { group ->
            val state = try { group.state } catch (_: IllegalArgumentException) { null }
            val names = state?.let(engine::memberNames).orEmpty()
            CommunityCard(group.id.toString(), group.name, state?.visibility == "public", state?.members?.size ?: 0,
                state?.ownerAccountId?.toString().orEmpty(), group.ownerKey, state?.revision ?: 0, group.active,
                state?.members?.map { member ->
                    CommunityMemberCard(member.accountId.toString(), names[member.accountId], member.accountId == state.ownerAccountId, member.accountId == engine.accountId)
                }.orEmpty(),
                state?.ownerAccountId == engine.accountId, group.pending)
        }, engine.supported, engine.securityWarning)
    }
    override suspend fun search(name: String, after: String?) = runtime.withCommunities {
        val page = it.search(name, after?.let(AccountId::parse))
        CommunityPage(page.communities.map { group -> CommunityCard(group.id.toString(), group.name, true, group.memberCount,
            group.ownerAccountId.toString(), group.ownerIdentityKey, group.revision) }, page.nextCursor?.toString())
    }
    override suspend fun create(name: String, public: Boolean): String = runtime.withCommunities { it.create(name, public) }.toString()
    override suspend fun request(group: CommunityCard) { runtime.withCommunities {
        it.request(CommunitySummary(AccountId.parse(group.id), group.name, "public", AccountId.parse(group.owner), group.ownerKey, group.count, group.revision))
    } }
    override suspend fun redeem(code: String) { runtime.withCommunities { it.redeem(code) } }
    override suspend fun applications(id: String) = runtime.withCommunities { it.requests(AccountId.parse(id)).map { r -> CommunityApplication(r.accountId.toString(), r.identityKey, r.createdAt,
        // The engine has checked that every key is a 33-byte identity key.
        CommunityFormat.fingerprint(Base64Url.decode(r.identityKey, 33))) } }
    override suspend fun approve(id: String, application: CommunityApplication) { runtime.withCommunities {
        it.approve(AccountId.parse(id), CommunityRequest(AccountId.parse(application.account), application.identity, application.createdAt))
    } }
    override suspend fun reject(id: String, account: String) { runtime.withCommunities { it.reject(AccountId.parse(id), AccountId.parse(account)) } }
    override suspend fun rejectAll(id: String) { runtime.withCommunities { it.rejectAll(AccountId.parse(id)) } }
    override suspend fun remove(id: String, account: String) { runtime.withCommunities { it.removeMember(AccountId.parse(id), AccountId.parse(account)) } }
    override suspend fun invite(id: String) = runtime.withCommunities { it.invite(AccountId.parse(id)) }
    override suspend fun revokeInvites(id: String) { runtime.withCommunities { it.revokeInvites(AccountId.parse(id)) } }
    override suspend fun delete(id: String) { runtime.withCommunities { it.delete(AccountId.parse(id)) } }
    override suspend fun leave(id: String) { runtime.withCommunities { it.leave(AccountId.parse(id)) } }
    override suspend fun withdraw(id: String) { runtime.withCommunities { it.withdraw(AccountId.parse(id)) } }
    override suspend fun forget(id: String) { runtime.withCommunities { it.forget(AccountId.parse(id)) } }
}
