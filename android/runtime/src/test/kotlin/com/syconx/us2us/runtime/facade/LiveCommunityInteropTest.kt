package com.syconx.us2us.runtime.facade

import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.contacts.ContactState
import com.syconx.us2us.core.encoding.Base64Url
import com.syconx.us2us.core.relay.RelayClient
import com.syconx.us2us.core.relay.RelayException
import com.syconx.us2us.runtime.community.CommunityEngine
import com.syconx.us2us.runtime.engine.MessageBody
import com.syconx.us2us.runtime.engine.MessagingEngine
import com.syconx.us2us.runtime.engine.MessengerBackend
import com.syconx.us2us.runtime.net.Backoff
import com.syconx.us2us.runtime.net.ConnectionStatus
import com.syconx.us2us.runtime.storage.LocalStorage
import com.syconx.us2us.runtime.storage.PlainSqlDriver
import com.syconx.us2us.runtime.support.ManualCaptureEvents
import com.syconx.us2us.runtime.support.NoBackgroundTasks
import com.syconx.us2us.runtime.support.RuntimeStrings
import com.syconx.us2us.runtime.support.SecurePasteboard
import com.syconx.us2us.runtime.support.SystemClipboard
import com.syconx.us2us.runtime.support.SystemRuntimeClock
import com.syconx.us2us.runtime.support.eventually
import com.syconx.us2us.runtime.vault.AppDirectories
import com.syconx.us2us.runtime.vault.InMemorySecureItemStore
import com.syconx.us2us.runtime.vault.NoBiometrics
import com.syconx.us2us.runtime.vault.VaultController
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The communities extension (docs/COMMUNITIES.md) between this runtime and the iOS runtime over a
 * real relay. The other side is the Swift `LiveCommunityInteropTests` (ios/Us2usAppCore): both play
 * the script described there and meet in `COMMUNITY_DIR`, where each writes one small JSON file per
 * step (`kotlin-<step>.json`, `ios-<step>.json`) and waits for the other's. Two app runtimes play
 * the owner "K1" and a third account "K3"; the iOS phone is "S". Skipped unless `RELAY_URL` and
 * `COMMUNITY_DIR` are set (scripts/interop.sh, step 6).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LiveCommunityInteropTest {
    @get:Rule val temporary = TemporaryFolder()
    private val relayUrl = System.getenv("RELAY_URL").orEmpty()
    private val meetingDir = System.getenv("COMMUNITY_DIR").orEmpty()
    private val phones = ArrayList<Phone>()

    @Before
    fun requireTheOtherSide() = assumeTrue("set RELAY_URL and COMMUNITY_DIR", relayUrl.isNotEmpty() && meetingDir.isNotEmpty())

    @After
    fun tearDown() = runBlocking {
        for (phone in phones) {
            try {
                phone.runtime.lockNow()
            } catch (_: Exception) {
            }
            phone.main.close()
        }
    }

    @Test
    fun communitiesWithTheIosRuntime() = runBlocking {
        val meeting = Meeting(File(meetingDir))
        try {
            script(meeting)
        } catch (e: Throwable) {
            meeting.post("kotlin-failed", "error" to e.toString())
            throw e
        }
    }

    private suspend fun script(meeting: Meeting) {
        val ready = meeting.await("ios-ready")
        val s = AccountId.parse(ready.getString("accountId"))
        val sKey = ready.getString("identityKey")
        val suffix = (1..8).map { "abcdefghijklmnopqrstuvwxyz0123456789"[Random.nextInt(36)] }.joinToString("")
        val owner = Phone("kown_$suffix")
        val third = Phone("kthree_$suffix")
        val k1 = owner.id
        val k3 = third.id

        // 1. A public community, found and requested by S, approved here: revision 2.
        val publicId = owner.communities { it.create(PUBLIC_NAME, isPublic = true) }
        meeting.post(
            "kotlin-public", "communityId" to publicId.toString(), "name" to PUBLIC_NAME,
            "ownerId" to k1.toString(), "ownerKey" to owner.key, "thirdId" to k3.toString(), "thirdKey" to third.key,
        )
        meeting.await("ios-requested")
        val request = owner.communities { it.requests(publicId) }.single { it.accountId == s }
        assertEquals("the request carries S's registered identity", sKey, request.identityKey)
        owner.communities { it.approve(publicId, request) }
        val approved = owner.relay.community(publicId).verify(owner.key)
        assertEquals(2L, approved.revision)
        assertEquals(listOf(k1, s).sortedBy { it.toString() }, approved.members.map { it.accountId })
        assertEquals(sKey, approved.member(s)?.identityKey)
        meeting.post("kotlin-approved", "revision" to "2")

        val hello = meeting.await("ios-public-sent").getString("text")
        owner.awaitText(publicId, s, hello)
        owner.send(publicId, PUBLIC_REPLY)
        meeting.post("kotlin-public-sent", "text" to PUBLIC_REPLY)

        // 2. S's private community by invitation (K3 and K1); K3 joins the public one too.
        val invitation = meeting.await("ios-private")
        val privateId = AccountId.parse(invitation.getString("communityId"))
        assertEquals(privateId, third.communities { it.redeem(invitation.getString("inviteThird")) })
        assertEquals(privateId, owner.communities { it.redeem(invitation.getString("inviteOwner")) })
        val summary = third.find(publicId, "Aile")
        assertEquals(PUBLIC_NAME, summary.name)
        third.communities { it.request(summary) }
        val thirdRequest = owner.communities { it.requests(publicId) }.single { it.accountId == k3 }
        assertEquals(third.key, thirdRequest.identityKey)
        owner.communities { it.approve(publicId, thirdRequest) }
        meeting.post("kotlin-redeemed")

        meeting.await("ios-private-approved")
        val joined = third.state(privateId, revision = 3)
        assertEquals(s, joined.ownerAccountId)
        assertEquals("the owner key pinned from the invitation", sKey, joined.ownerIdentityKey)
        assertEquals("", joined.name)
        assertEquals(PRIVATE_NAME, third.communities { it.communities() }.single { it.id == privateId }.name)
        assertEquals(setOf(s, k1, k3), joined.members.map { it.accountId }.toSet())
        owner.state(privateId, revision = 3)
        third.state(publicId, revision = 3)
        third.send(privateId, FROM_THIRD)
        meeting.post("kotlin-private-sent", "text" to FROM_THIRD)

        val welcome = meeting.await("ios-private-sent").getString("text")
        third.awaitText(privateId, s, welcome)
        owner.awaitText(privateId, s, welcome)
        owner.awaitText(privateId, k3, FROM_THIRD)
        // Community traffic made nobody a personal contact.
        for (phone in listOf(owner, third)) {
            assertTrue(phone.runtime.chats.value.isEmpty() && phone.runtime.requests.value.isEmpty())
            assertNull(phone.runtime.contact(s))
        }
        assertNull(owner.runtime.contact(k3))
        assertNull(third.runtime.contact(k1))

        // 3. One-to-one between K1 and S, on the ratchet the communities already use.
        assertEquals(s, owner.runtime.startConversation(ready.getString("username")))
        owner.runtime.sendText(DIRECT_FROM_OWNER, s)
        meeting.post("kotlin-direct", "text" to DIRECT_FROM_OWNER)
        val directReply = meeting.await("ios-direct").getString("text")
        owner.runtime.openConversation(s)
        eventually(60.seconds, "S's one-to-one reply") { owner.runtime.openMessages.value.any { it.body == MessageBody.Text(directReply) } }
        assertEquals(ContactState.ACCEPTED, owner.runtime.contact(s)?.requestState)

        // 4. S removes K3 from its private community. K3 does not fetch in between, so a copy
        // of the next private message waits in its inbox until the removal purges it.
        owner.send(publicId, AFTER_DIRECT)
        owner.send(privateId, QUEUED_FOR_THIRD)
        val thirdRelay = third.relay
        assertTrue("K3's copy is queued (looked at, not acknowledged)", thirdRelay.communityInbox().envelopes.any { it.communityId == privateId })
        meeting.post("kotlin-before-remove", "public" to AFTER_DIRECT, "private" to QUEUED_FOR_THIRD)
        meeting.await("ios-removed")
        assertTrue("the removal purged K3's queued copy", thirdRelay.communityInbox().envelopes.none { it.communityId == privateId })
        assertRefused { thirdRelay.community(privateId) }
        assertEquals(404, assertRefused { thirdRelay.sendCommunityEnvelope(privateId, s, AccountId(UUID.randomUUID()), 4, byteArrayOf(1)) })
        assertTrue("K3 cannot send any more", runCatching { third.communities { it.send(privateId, "hâlâ burada mıyım?") } }.isFailure)
        third.communities { it.sync() }
        assertFalse(third.communities { it.communities() }.single { it.id == privateId }.active)
        val removed = owner.state(privateId, revision = 4)
        assertEquals(setOf(s, k1), removed.members.map { it.accountId }.toSet())
        owner.send(privateId, AFTER_REMOVAL)
        meeting.post("kotlin-after-remove", "text" to AFTER_REMOVAL)
        val stillHere = meeting.await("ios-after-remove").getString("text")
        owner.awaitText(privateId, s, stillHere)
        third.communities { it.sync() }
        val thirdTexts = third.communities { it.messages(privateId) }.map { it.text }
        assertTrue(thirdTexts.toString(), thirdTexts.none { it in setOf(QUEUED_FOR_THIRD, AFTER_REMOVAL, stillHere) })

        // 5. K3 leaves the public community; the owner publishes the next roster without it.
        third.awaitText(publicId, k1, AFTER_DIRECT)
        assertEquals(204, third.leaveOnRelay(publicId))
        third.communities { it.leave(publicId) } // The relay's 404 now: only forgotten here.
        assertTrue(third.communities { it.communities() }.none { it.id == publicId })
        assertRefused { thirdRelay.community(publicId) }
        assertEquals(404, assertRefused { thirdRelay.sendCommunityEnvelope(publicId, k1, AccountId(UUID.randomUUID()), 3, byteArrayOf(1)) })
        meeting.post("kotlin-left")
        val afterLeave = meeting.await("ios-after-leave").getString("text")
        owner.awaitText(publicId, s, afterLeave)
        assertEquals("the leaver stays listed until the owner publishes", 3L, owner.relay.community(publicId).verify(owner.key).revision)
        owner.communities { it.removeMember(publicId, k3) }
        val withoutThird = owner.relay.community(publicId).verify(owner.key)
        assertEquals(4L, withoutThird.revision)
        assertEquals(setOf(k1, s), withoutThird.members.map { it.accountId }.toSet())
        owner.send(publicId, AFTER_LEAVE)
        meeting.post("kotlin-owner-updated", "revision" to "4", "text" to AFTER_LEAVE)

        val lastDirect = meeting.await("ios-final").getString("text")
        eventually(60.seconds, "S's last one-to-one message") { owner.runtime.openMessages.value.any { it.body == MessageBody.Text(lastDirect) } }
        // Neither path leaked into the other.
        val directTexts = owner.runtime.openMessages.value.mapNotNull { (it.body as? MessageBody.Text)?.text }.toSet()
        assertEquals(setOf(DIRECT_FROM_OWNER, directReply, lastDirect), directTexts)
        val communityTexts = owner.communities { engine -> listOf(publicId, privateId).flatMap { engine.messages(it) }.map { it.text } }.toSet()
        assertTrue(communityTexts.intersect(directTexts).isEmpty())
        assertTrue(communityTexts.containsAll(listOf(hello, PUBLIC_REPLY, welcome, FROM_THIRD, stillHere, afterLeave, AFTER_LEAVE)))
        assertEquals(listOf(s), owner.runtime.chats.value.map { it.id })
        assertNull(owner.runtime.contact(k3))
        assertNull(third.runtime.contact(s))
        assertNull(third.runtime.contact(k1))
        assertTrue(third.runtime.chats.value.isEmpty())
        owner.communities { it.delete(publicId) }
        meeting.post("kotlin-done")
    }

    /** An app runtime with its own account on the live relay. */
    private inner class Phone(username: String) {
        val main = Executors.newSingleThreadExecutor { Thread(it, "phone-main") }.asCoroutineDispatcher()
        val runtime: AppRuntime

        init {
            phones += this
            val environment = RuntimeEnvironment(
                directories = AppDirectories(File(temporary.newFolder(), "app")),
                secureItems = InMemorySecureItemStore(),
                biometrics = NoBiometrics,
                backends = RelayBackendFactory(relayUrl),
                clock = SystemRuntimeClock,
                vault = VaultController.Configuration(iterations = 1000),
                engine = MessagingEngine.Configuration(presenceReplyDelay = Duration.ZERO),
                reconnectBackoff = Backoff(base = 200.milliseconds, cap = 1.seconds, floor = 100.milliseconds),
                storage = StorageOpener { layout, key, now -> key.withDataKey { LocalStorage.open(layout, it, now, PlainSqlDriver) } },
            )
            runtime = runBlocking {
                withContext(main) {
                    AppRuntime(
                        environment, NoBackgroundTasks(), ManualCaptureEvents(),
                        SecurePasteboard(SystemClipboard(org.robolectric.RuntimeEnvironment.getApplication())),
                        RuntimeStrings("en") { key, _ -> key.name }, main,
                    )
                }
            }
            runBlocking {
                runtime.createAccount(username, username.uppercase(), CODE, CODE)
                eventually(60.seconds, "$username online") { runtime.connection.value == ConnectionStatus.Open }
            }
        }

        val id: AccountId get() = checkNotNull(runtime.ownAccountId)
        private val messenger get() = (checkNotNull(runtime.session).backend as MessengerBackend).messenger
        val key: String get() = Base64Url.encode(messenger.account.identityKeyPair.publicKey.serialize())

        /** This account's authenticated relay client, for what the engine never does on its own. */
        val relay: RelayClient get() = messenger.communityRelay

        suspend fun <T> communities(action: suspend (CommunityEngine) -> T): T = runtime.withCommunities(action)

        suspend fun find(id: AccountId, query: String): com.syconx.us2us.core.relay.CommunitySummary {
            var after: AccountId? = null
            do {
                val page = communities { it.search(query, after) }
                page.communities.firstOrNull { it.id == id }?.let { return it }
                after = page.nextCursor
            } while (after != null)
            fail("$id not found by \"$query\"")
            error("unreachable")
        }

        /** Syncs until the community is active at [revision]; its verified state. */
        suspend fun state(id: AccountId, revision: Long): com.syconx.us2us.core.community.CommunityState {
            poll("revision $revision of $id") {
                communities { engine -> engine.sync(); engine.communities().firstOrNull { it.id == id }?.takeIf { it.active }?.state?.revision == revision }
            }
            return checkNotNull(communities { engine -> engine.communities().single { it.id == id }.state })
        }

        /** Sends [text] and waits until every recipient of the roster has it. */
        suspend fun send(id: AccountId, text: String) {
            communities { it.send(id, text) }
            poll("\"$text\" delivered") {
                communities { engine -> engine.sync(); engine.messages(id).firstOrNull { it.sender == this.id && it.text == text }?.status == "sent" }
            }
        }

        /** Syncs until [text] from [sender] is stored in the community. */
        suspend fun awaitText(id: AccountId, sender: AccountId, text: String) = poll("\"$text\" in $id") {
            communities { engine -> engine.sync(); engine.messages(id).any { it.sender == sender && it.text == text && it.status == "received" } }
        }

        /** `DELETE /v1/communities/{id}/members/me`, answered with its bare status. */
        fun leaveOnRelay(id: AccountId): Int {
            val request = Request.Builder().url("${relayUrl.trimEnd('/')}/v1/communities/$id/members/me")
                .header(RelayClient.AUTH_HEADER, messenger.account.authToken.headerValue).delete().build()
            return OkHttpClient().newCall(request).execute().use { it.code }
        }
    }

    /** One JSON object of strings per step, in the directory both platforms meet in. */
    private class Meeting(private val directory: File) {
        fun post(step: String, vararg values: Pair<String, String>) {
            val temporary = File(directory, ".$step.json.tmp")
            temporary.writeText(JSONObject(values.toMap()).toString())
            check(temporary.renameTo(File(directory, "$step.json")))
        }

        suspend fun await(step: String): JSONObject {
            val file = File(directory, "$step.json")
            eventually(300.seconds, "the iOS side's $step") {
                check(!File(directory, "ios-failed.json").exists()) { "the iOS side failed before $step" }
                file.exists().also { if (!it) delay(200.milliseconds) }
            }
            return JSONObject(file.readText())
        }
    }

    private suspend fun poll(message: String, condition: suspend () -> Boolean) =
        eventually(90.seconds, message) { condition().also { if (!it) delay(300.milliseconds) } }

    /** The HTTP status of the relay's refusal. */
    private suspend fun assertRefused(call: suspend () -> Unit): Int {
        try {
            call()
        } catch (e: RelayException) {
            assertTrue("refused with ${e.httpStatus}", e.httpStatus == 403 || e.httpStatus == 404)
            return e.httpStatus
        }
        fail("the relay accepted it")
        error("unreachable")
    }

    private companion object {
        const val CODE = "482917"
        const val PUBLIC_NAME = "Aile 👨‍👩‍👧"
        const val PRIVATE_NAME = "Gizli Çay ☕"
        const val PUBLIC_REPLY = "Hoş geldin! Android'den selam 🇹🇷"
        const val FROM_THIRD = "Merhaba, davetle geldim ✉️"
        const val DIRECT_FROM_OWNER = "Birebir: merhaba, burası Android"
        const val AFTER_DIRECT = "Birebirden sonra yine topluluk"
        const val QUEUED_FOR_THIRD = "K3 bunu hiç görmemeli"
        const val AFTER_REMOVAL = "K3 çıkarıldı, biz devam"
        const val AFTER_LEAVE = "Yeni liste: yalnızca ikimiz"
    }
}
