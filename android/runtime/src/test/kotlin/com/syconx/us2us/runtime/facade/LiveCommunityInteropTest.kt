package com.syconx.us2us.runtime.facade

import com.google.protobuf.ByteString
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.community.CommunityContent
import com.syconx.us2us.core.community.CommunityState
import com.syconx.us2us.core.contacts.ContactState
import com.syconx.us2us.core.encoding.Base64Url
import com.syconx.us2us.core.proto.Attachment
import com.syconx.us2us.core.relay.RelayClient
import com.syconx.us2us.core.relay.RelayException
import com.syconx.us2us.runtime.community.CommunityEngine
import com.syconx.us2us.runtime.community.CommunityMessageKey
import com.syconx.us2us.runtime.media.PreparedMedia
import com.syconx.us2us.runtime.engine.MessageBody
import com.syconx.us2us.runtime.engine.MessagingEngine
import com.syconx.us2us.runtime.engine.MessengerBackend
import com.syconx.us2us.runtime.net.Backoff
import com.syconx.us2us.runtime.net.ConnectionStatus
import com.syconx.us2us.runtime.storage.CommunityDeletion
import com.syconx.us2us.runtime.storage.CommunityMediaState
import com.syconx.us2us.runtime.storage.CommunityRecipient
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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import java.security.MessageDigest
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

        // 3v. Extension v2: media, receipts and delete for everyone, the one-to-one path alongside.
        val v2Reply = extensionV2(meeting, owner, third, s, publicId, privateId)

        extensionV3(meeting, owner, third, s, privateId)

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
        assertEquals(setOf(DIRECT_FROM_OWNER, directReply, DIRECT_AFTER_MEDIA, v2Reply, lastDirect), directTexts)
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

    /**
     * Extension v2 with the iOS phone, in S's private community (S, K1, K3) and K1's public one:
     * a photo with a caption and a file from each platform, uploaded once and downloaded by every
     * recipient (pointer, digest and bytes compared); DELIVERY and READ per member, where K3 keeps
     * read receipts off throughout and S reads one text with them off; delete for everyone by the
     * sender on each platform (the blob is gone from the relay, `404`) and by the owner on each
     * (the author deletes the blob); a forged delete from each platform dropped; a delete ahead of
     * its message from each platform; and a one-to-one exchange in between. Returns S's reply.
     */
    private suspend fun extensionV2(meeting: Meeting, owner: Phone, third: Phone, s: AccountId, publicId: AccountId, privateId: AccountId): String {
        val k1 = owner.id
        val k3 = third.id
        third.runtime.setReadReceipts(false)

        // A text S reads with read receipts off: DELIVERY comes, READ never.
        val keepId = owner.send(privateId, TEXT_KEEP)
        meeting.post("kotlin-v2-text", "messageId" to keepId.toString(), "text" to TEXT_KEEP)
        meeting.await("ios-v2-text-seen")
        owner.awaitReceipt(privateId, keepId, s, read = false)

        // K1's photo with a caption and K1's file; K3's text in the public community.
        val photo = ByteArray(5000) { (it % 251).toByte() }
        val file = ByteArray(900) { (it % 13).toByte() }
        val imageId = owner.sendMedia(privateId, media(photo, Attachment.Kind.IMAGE), CAPTION_K)
        val fileId = owner.sendMedia(privateId, media(file, Attachment.Kind.FILE, FILE_NAME_K))
        val imageFacts = owner.facts(privateId, imageId)
        val fileFacts = owner.facts(privateId, fileId)
        val publicThirdId = third.send(publicId, PUBLIC_FROM_THIRD)
        meeting.post(
            "kotlin-v2-sent", "imageId" to imageId.toString(), "fileId" to fileId.toString(),
            *imageFacts.pairs("image"), *fileFacts.pairs("file"),
            "publicThirdText" to PUBLIC_FROM_THIRD, "publicThirdId" to publicThirdId.toString(),
        )
        // K3 (read receipts off) downloads both and displays them: DELIVERY only.
        third.receiveMedia(privateId, k1, imageId, imageFacts)
        third.receiveMedia(privateId, k1, fileId, fileFacts)
        third.display(privateId, k1 to imageId, k1 to fileId)

        // S's photo and file: K1 (read receipts on) and K3 (off) download and display them.
        val sent = meeting.await("ios-v2-sent")
        val sImage = AccountId.parse(sent.getString("imageId"))
        val sFile = AccountId.parse(sent.getString("fileId"))
        val sImageFacts = MediaFacts.of(sent, "image")
        val sFileFacts = MediaFacts.of(sent, "file")
        assertEquals(Attachment.Kind.IMAGE, sImageFacts.kind)
        assertEquals(Attachment.Kind.FILE, sFileFacts.kind)
        for (phone in listOf(owner, third)) {
            phone.receiveMedia(privateId, s, sImage, sImageFacts)
            phone.receiveMedia(privateId, s, sFile, sFileFacts)
            phone.display(privateId, s to sImage, s to sFile)
        }
        val moderatedId = AccountId.parse(sent.getString("publicModeratedId"))
        owner.awaitText(publicId, s, sent.getString("publicModeratedText"))
        // K1's own messages: S delivered and read them, K3 delivered only.
        for (id in listOf(imageId, fileId)) {
            owner.awaitReceipt(privateId, id, s, read = true)
            owner.awaitReceipt(privateId, id, k3, read = false)
            assertNull("K3 keeps read receipts off", owner.recipient(privateId, id, k3).readAt)
        }

        // Delete for everyone by its sender: the placeholder here, the blob gone from the relay.
        owner.communities { it.deleteForEveryone(privateId, k1, imageId) }
        owner.assertPlaceholder(privateId, k1, imageId, CommunityDeletion.SENDER)
        poll("K1's blob deleted") { owner.communities { it.sync() }; mediaStatus(imageFacts.mediaId) == 404 }
        meeting.post("kotlin-v2-deleted", "imageId" to imageId.toString())

        // S deleted its photo, removed K1's file as the owner, forged a delete and sent one early.
        val deleted = meeting.await("ios-v2-deleted")
        for (phone in listOf(owner, third)) {
            phone.awaitPlaceholder(privateId, s, sImage, CommunityDeletion.SENDER)
            phone.awaitPlaceholder(privateId, k1, fileId, CommunityDeletion.OWNER)
        }
        assertEquals("S deleted its blob", 404, mediaStatus(sImageFacts.mediaId))
        poll("the author's blob deleted after the owner's delete") { owner.communities { it.sync() }; mediaStatus(fileFacts.mediaId) == 404 }
        assertArrayEquals("S's file stays", sFileFacts.sha256, sha256(checkNotNull(owner.communities { it.mediaData(privateId, s, sFile) })))
        owner.awaitText(publicId, s, deleted.getString("afterForgedText"))
        val kept = checkNotNull(owner.communities { it.message(CommunityMessageKey(publicId, k3, publicThirdId)) })
        assertNull("S's forged delete of K3's message was dropped", kept.deletedBy)
        assertEquals(PUBLIC_FROM_THIRD, kept.text)
        owner.awaitPlaceholder(privateId, s, AccountId.parse(deleted.getString("earlyId")), CommunityDeletion.SENDER)

        // The same from here: moderation in K1's community, K3's forged delete, K1's early delete.
        owner.communities { it.deleteForEveryone(publicId, s, moderatedId) }
        owner.assertPlaceholder(publicId, s, moderatedId, CommunityDeletion.OWNER)
        val privateState = checkNotNull(third.communities { engine -> engine.communities().single { it.id == privateId }.state })
        third.sendRaw(privateState, s, CommunityContent.delete(privateId, privateState.revision, AccountId(UUID.randomUUID()), System.currentTimeMillis(), keepId, k1))
        third.send(privateId, THIRD_AFTER_FORGED)
        val earlyId = AccountId(UUID.randomUUID())
        owner.sendRaw(privateState, s, CommunityContent.delete(privateId, privateState.revision, AccountId(UUID.randomUUID()), System.currentTimeMillis(), earlyId, k1))
        owner.sendRaw(privateState, s, CommunityContent.text(privateId, privateState.revision, earlyId, System.currentTimeMillis(), EARLY_TEXT))
        meeting.post(
            "kotlin-v2-events", "moderatedId" to moderatedId.toString(), "keepId" to keepId.toString(),
            "afterForgedText" to THIRD_AFTER_FORGED, "earlyId" to earlyId.toString(),
        )
        third.awaitPlaceholder(publicId, s, moderatedId, CommunityDeletion.OWNER)

        // One-to-one between the same accounts still works.
        owner.runtime.sendText(DIRECT_AFTER_MEDIA, s)
        meeting.post("kotlin-v2-direct", "text" to DIRECT_AFTER_MEDIA)
        val reply = meeting.await("ios-v2-direct").getString("text")
        eventually(60.seconds, "S's one-to-one reply after media") { owner.runtime.openMessages.value.any { it.body == MessageBody.Text(reply) } }
        // What S read with read receipts off never became READ, also after its later READs.
        assertNull(owner.recipient(privateId, keepId, s).readAt)
        assertNotNull(owner.recipient(privateId, keepId, s).deliveredAt)
        return reply
    }

    private suspend fun extensionV3(meeting: Meeting, owner: Phone, third: Phone, s: AccountId, privateId: AccountId) {
        val sticker = com.syconx.us2us.core.proto.Sticker.newBuilder().setPackId("us2us.cups").setStickerId(1).setEmoji("👋").build()
        val stickerId = owner.communities { it.sendSticker(privateId, sticker) }
        owner.communities { it.sync() }
        val audio = ByteArray(4000) { (it % 239).toByte() }
        val file = File(temporary.newFolder(), "voice.m4a").apply { writeBytes(audio) }
        val voiceId = owner.sendMedia(privateId, PreparedMedia(file, Attachment.Kind.VOICE, "audio/mp4", null, audio.size.toLong(),
            durationMs = 2000, waveform = ByteString.copyFrom(byteArrayOf(0, -128, -1))))
        meeting.post("kotlin-v3-sent", "stickerId" to stickerId.toString(), "voiceId" to voiceId.toString(), *owner.facts(privateId, voiceId).pairs("voice"))
        val sent = meeting.await("ios-v3-sent")
        val remoteSticker = AccountId.parse(sent.getString("stickerId"))
        val remoteVoice = AccountId.parse(sent.getString("voiceId"))
        val facts = MediaFacts.of(sent, "voice")
        for (phone in listOf(owner, third)) {
            phone.receiveMedia(privateId, s, remoteVoice, facts)
            val items = phone.communities { it.messages(privateId) }
            assertEquals(sticker, items.first { it.sender == s && it.messageId == remoteSticker }.sticker)
            val pointer = items.first { it.sender == s && it.messageId == remoteVoice }.attachment!!
            assertEquals(2000, pointer.durationMs)
            assertEquals(ByteString.copyFrom(byteArrayOf(0, -128, -1)), pointer.waveform)
        }
        meeting.post("kotlin-v3-seen")
    }

    /** A staged photo or file as the app's media preparation hands it over. */
    private fun media(bytes: ByteArray, kind: Attachment.Kind, fileName: String? = null): PreparedMedia {
        val file = File(temporary.newFolder(), "staged").apply { writeBytes(bytes) }
        return if (kind == Attachment.Kind.FILE) {
            PreparedMedia(file, kind, "application/pdf", fileName, bytes.size.toLong())
        } else {
            PreparedMedia(file, kind, "image/jpeg", null, bytes.size.toLong(), width = 40, height = 30, thumbnail = ByteString.copyFrom(THUMBNAIL))
        }
    }

    /** What both platforms compare about one media message: the pointer every member got and the plaintext. */
    private data class MediaFacts(val kind: Attachment.Kind, val mediaId: String, val digest: String, val size: Long, val sha256: ByteArray, val caption: String, val fileName: String) {
        fun pairs(prefix: String): Array<Pair<String, String>> = arrayOf(
            "${prefix}Kind" to kind.name, "${prefix}MediaId" to mediaId, "${prefix}Digest" to digest, "${prefix}Size" to size.toString(),
            "${prefix}Sha256" to Base64Url.encode(sha256), "${prefix}Caption" to caption, "${prefix}FileName" to fileName,
        )

        companion object {
            fun of(json: JSONObject, prefix: String) = MediaFacts(
                Attachment.Kind.valueOf(json.getString("${prefix}Kind")), json.getString("${prefix}MediaId"), json.getString("${prefix}Digest"),
                json.getString("${prefix}Size").toLong(), Base64Url.decode(json.getString("${prefix}Sha256"), 32),
                json.getString("${prefix}Caption"), json.getString("${prefix}FileName"),
            )
        }
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    /** `GET /v1/media/{id}` (anonymous, like a recipient's download), answered with its bare status. */
    private fun mediaStatus(mediaId: String): Int {
        val request = Request.Builder().url("${relayUrl.trimEnd('/')}/v1/media/$mediaId").get().build()
        return OkHttpClient().newCall(request).execute().use { it.code }
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

        /** Sends [text] and waits until every recipient of the roster has it; its message id. */
        suspend fun send(id: AccountId, text: String): AccountId {
            val messageId = communities { it.send(id, text) }
            awaitSent(id, messageId, text)
            return messageId
        }

        /** Sends a photo or file (uploaded once) and waits until every recipient has the pointer. */
        suspend fun sendMedia(id: AccountId, media: PreparedMedia, caption: String? = null): AccountId {
            val messageId = communities { it.sendMedia(id, media, caption) }
            awaitSent(id, messageId, "media $messageId")
            val sent = checkNotNull(communities { it.message(CommunityMessageKey(id, this.id, messageId)) })
            assertEquals(CommunityMediaState.LOCAL, sent.media)
            assertTrue("the token never enters the pointer", sent.attachment!!.deleteToken.isEmpty)
            return messageId
        }

        private suspend fun awaitSent(id: AccountId, messageId: AccountId, what: String) = poll("\"$what\" delivered") {
            communities { engine -> engine.sync(); engine.message(CommunityMessageKey(id, this.id, messageId))?.status == "sent" }
        }

        /** The pointer and plaintext of an own media message, for the other side to compare. */
        suspend fun facts(id: AccountId, messageId: AccountId): MediaFacts {
            val message = checkNotNull(communities { it.message(CommunityMessageKey(id, this.id, messageId)) })
            val pointer = checkNotNull(message.attachment)
            val bytes = checkNotNull(communities { it.mediaData(id, this.id, messageId) })
            return MediaFacts(
                pointer.kind, Base64Url.encode(pointer.mediaId.toByteArray()), Base64Url.encode(pointer.digest.toByteArray()),
                pointer.size, sha256(bytes), message.text, pointer.fileName,
            )
        }

        /** Syncs until the media message is here, checks its pointer against [facts], downloads and checks the bytes. */
        suspend fun receiveMedia(id: AccountId, sender: AccountId, messageId: AccountId, facts: MediaFacts) {
            val key = CommunityMessageKey(id, sender, messageId)
            poll("media $messageId from $sender in $id") { communities { engine -> engine.sync(); engine.message(key)?.status == "received" } }
            val received = checkNotNull(communities { it.message(key) })
            val pointer = checkNotNull(received.attachment)
            assertEquals(facts.kind, pointer.kind)
            assertEquals(facts.mediaId, Base64Url.encode(pointer.mediaId.toByteArray()))
            assertEquals(facts.digest, Base64Url.encode(pointer.digest.toByteArray()))
            assertEquals(facts.size, pointer.size)
            assertEquals(facts.caption, received.text)
            assertEquals(facts.fileName, pointer.fileName)
            assertTrue(pointer.deleteToken.isEmpty && pointer.caption.isEmpty())
            assertEquals(CommunityMediaState.REMOTE, received.media)
            assertNull(received.displayedAt)
            communities { it.download(id, sender, messageId) }
            val bytes = checkNotNull(communities { it.mediaData(id, sender, messageId) }) { "no file after the download" }
            assertEquals(facts.size, bytes.size.toLong())
            assertArrayEquals(facts.sha256, sha256(bytes))
            assertEquals(CommunityMediaState.LOCAL, checkNotNull(communities { it.message(key) }).media)
        }

        /** The messages are on screen: READ is queued (while read receipts are on) and sent. */
        suspend fun display(id: AccountId, vararg keys: Pair<AccountId, AccountId>) {
            assertTrue(communities { it.markDisplayed(id, keys.toList()) })
            communities { it.flushReceipts() }
        }

        suspend fun recipient(id: AccountId, messageId: AccountId, member: AccountId): CommunityRecipient =
            checkNotNull(communities { it.recipients(id, messageId) }.firstOrNull { it.member == member }) { "$member is no recipient of $messageId" }

        /** Syncs until [member] has delivered (and, if [read], read) this own message. */
        suspend fun awaitReceipt(id: AccountId, messageId: AccountId, member: AccountId, read: Boolean) = poll("${if (read) "READ" else "DELIVERY"} from $member for $messageId") {
            communities { it.sync() }
            val recipient = recipient(id, messageId, member)
            recipient.transport == CommunityRecipient.ACCEPTED && recipient.deliveredAt != null && (!read || recipient.readAt != null)
        }

        /** The message is the placeholder: nothing of it but who deleted it. */
        suspend fun assertPlaceholder(id: AccountId, sender: AccountId, messageId: AccountId, by: CommunityDeletion) {
            val placeholder = checkNotNull(communities { it.message(CommunityMessageKey(id, sender, messageId)) })
            assertEquals(by, placeholder.deletedBy)
            assertEquals("", placeholder.text)
            assertNull(placeholder.attachment)
            assertNull(communities { it.mediaData(id, sender, messageId) })
        }

        suspend fun awaitPlaceholder(id: AccountId, sender: AccountId, messageId: AccountId, by: CommunityDeletion) {
            poll("placeholder of $messageId in $id") { communities { engine -> engine.sync(); engine.message(CommunityMessageKey(id, sender, messageId))?.deletedBy == by } }
            assertPlaceholder(id, sender, messageId, by)
        }

        /** One envelope straight to [recipient], as a client would that bypasses the engine. */
        suspend fun sendRaw(state: CommunityState, recipient: AccountId, message: com.syconx.us2us.core.proto.CommunityMessage) {
            val member = checkNotNull(state.member(recipient))
            val envelope = messenger.encryptCommunityMessage(member, message)
            relay.sendCommunityEnvelope(state.id, recipient, AccountId.fromBytes(message.messageId.toByteArray()), state.revision, envelope)
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
        const val TEXT_KEEP = "Bu metin kalır (S okundu bilgisi kapalı okur)"
        const val CAPTION_K = "Gün batımı, Android'den 🌅"
        const val FILE_NAME_K = "plan.pdf"
        const val PUBLIC_FROM_THIRD = "K3 herkese açık toplulukta"
        const val THIRD_AFTER_FORGED = "K3: sahte silmeden sonra"
        const val EARLY_TEXT = "Silme önce geldi"
        const val DIRECT_AFTER_MEDIA = "Birebir: medyadan sonra da çalışıyor"
        val THUMBNAIL = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
    }
}
