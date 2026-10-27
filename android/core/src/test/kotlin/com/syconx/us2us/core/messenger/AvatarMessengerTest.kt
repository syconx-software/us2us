package com.syconx.us2us.core.messenger

import com.google.protobuf.kotlin.toByteString
import com.syconx.us2us.core.contacts.ContactState
import com.syconx.us2us.core.contacts.InMemoryContactStore
import com.syconx.us2us.core.contacts.UsernameStatus
import com.syconx.us2us.core.profile.AvatarKey
import com.syconx.us2us.core.profile.AvatarRecord
import com.syconx.us2us.core.profile.InvalidAvatarRecordException
import com.syconx.us2us.core.profile.ProfilePicture
import com.syconx.us2us.core.profile.TestImages
import com.syconx.us2us.core.proto.AccountBackup
import com.syconx.us2us.core.proto.Attachment
import com.syconx.us2us.core.proto.ProfileUpdate
import com.syconx.us2us.core.proto.ProfileUpdateKt
import com.syconx.us2us.core.proto.attachment
import com.syconx.us2us.core.proto.avatarPhoto
import com.syconx.us2us.core.proto.content
import com.syconx.us2us.core.proto.controlMessage
import com.syconx.us2us.core.proto.profileAvatar
import com.syconx.us2us.core.proto.profileUpdate
import com.syconx.us2us.core.relay.RelayClient
import com.syconx.us2us.core.store.InMemoryProtocolStorage
import com.syconx.us2us.core.store.ProtocolStore
import com.syconx.us2us.core.testing.FakeRelay
import com.syconx.us2us.core.testing.MutableClock
import com.syconx.us2us.core.testing.TestClient
import com.syconx.us2us.core.username.Username
import com.syconx.us2us.core.vault.BackupFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Profile pictures end to end against [FakeRelay] (PROTOCOL.md §8.7, §8.9, §8.10, §4.12). */
class AvatarMessengerTest {
    private val relay = FakeRelay().start()
    private val relayUrl = relay.url

    @AfterEach
    fun stopRelay() {
        assertEquals(emptyList<String>(), relay.violations)
        relay.close()
    }

    private val photo = ProfilePicture.photo("image/jpeg", TestImages.jpeg(128, 128))
    private val otherPhoto = ProfilePicture.photo("image/heic", TestImages.heic(sizes = listOf(96 to 96)))

    private suspend fun acceptedPair(scope: CoroutineScope, aliceName: String = "alice", searchable: Boolean = true): Pair<TestClient, TestClient> {
        val alice = TestClient.register(relayUrl, aliceName, scope)
        val bob = TestClient.register(relayUrl, "bob", scope)
        // Publication scenarios opt in explicitly. The private-default case below does not.
        if (searchable) alice.messenger.setShowPictureInSearch(true)
        alice.awaitUpToDate()
        bob.awaitUpToDate()
        alice.messenger.findByUsername("bob")
        alice.messenger.sendText(bob.accountId, "hi")
        bob.nextData()
        bob.messenger.acceptMessageRequest(alice.accountId)
        // Both profiles have arrived once each side holds the other's access key and sent its own.
        withTimeout(30.seconds) {
            while (alice.messenger.contact(bob.accountId)?.profileReceived != true || bob.messenger.contact(alice.accountId)?.profileReceived != true) delay(20)
        }
        return alice to bob
    }

    /**
     * The next `ProfileUpdate` matching [predicate]. Earlier messages, such as the profile both
     * sides sent when they became contacts, are skipped.
     */
    private suspend fun TestClient.nextProfile(predicate: (ProfileUpdate) -> Boolean = { true }): ProfileUpdate =
        next { it.content.hasControl() && it.content.control.hasProfile() && predicate(it.content.control.profile) }.content.control.profile

    @Test
    fun `new accounts keep photos private until opting in and backup keeps the preference`() = runBlocking {
        coroutineScope {
            val (alice, bob) = acceptedPair(this, searchable = false)
            assertFalse(alice.messenger.account.showPictureInSearch)
            assertFalse(bob.messenger.account.showPictureInSearch)
            alice.messenger.setProfilePicture(photo)
            bob.nextProfile { it.hasProfileAvatar() }
            assertNull(relay.avatarRecord(alice.accountId))
            val backup = AccountBackup.parseFrom(BackupFile.open(alice.messenger.exportBackup("yedek parolam 7", iterations = 1000), "yedek parolam 7"))
            assertTrue(backup.avatarSearchOff)
            alice.messenger.setShowPictureInSearch(true)
            assertTrue(alice.messenger.account.showPictureInSearch)
            assertEquals(photo, ProfilePicture.fromProto(AvatarRecord.openAvatar(relay.avatarRecord(alice.accountId)!!, AvatarKey.derive(Username("alice"), alice.accountId))))
            alice.messenger.setShowPictureInSearch(false)
            assertNull(relay.avatarRecord(alice.accountId))
            listOf(alice, bob).forEach { it.job.cancelAndJoin() }
        }
    }

    @Test
    fun `a photo reaches contacts end to end and searchers through the avatar record`() = runBlocking {
        coroutineScope {
            val (alice, bob) = acceptedPair(this)
            val carol = Messenger.register(Username("carol"), RelayClient(relayUrl), config = TestClient.testConfig)

            assertEquals(emptyList<Any>(), alice.messenger.setProfilePicture(photo))
            assertEquals(photo, alice.messenger.account.profilePicture)
            assertFalse(PendingSetup.SYNC_AVATAR in alice.messenger.account.pendingSetup)
            assertEquals(1L, alice.messenger.account.avatarVersion)

            // Contacts: in the ProfileUpdate, field 6 only.
            val update = bob.nextProfile { it.hasProfileAvatar() }
            assertFalse(update.hasAvatar(), "the deprecated field 2 is never sent")
            assertEquals(photo, bob.messenger.contact(alice.accountId)?.picture)

            // The relay holds one opaque record of the smallest length, under the username's key.
            val record = relay.avatarRecord(alice.accountId)!!
            assertEquals(8192, record.size)
            assertEquals(1L, AvatarRecord.version(record))
            assertEquals(photo, ProfilePicture.fromProto(AvatarRecord.openAvatar(record, AvatarKey.derive(Username("alice"), alice.accountId))))
            assertThrows<InvalidAvatarRecordException> { AvatarRecord.open(record, AvatarKey.derive(Username("alicea"), alice.accountId)) }

            // An accepted contact uses its own copy: the relay is not asked.
            val fetches = relay.avatarFetches
            assertEquals(photo, bob.messenger.searchPicture(alice.accountId))
            assertEquals(fetches, relay.avatarFetches)

            // A stranger who types the exact username sees it, after the directory hit only.
            assertEquals(ContactState.OUTGOING_REQUEST, carol.findByUsername("alice")?.state)
            assertEquals(fetches, relay.avatarFetches, "a lookup alone fetches nothing")
            val found = carol.searchPicture(alice.accountId)
            assertEquals(photo, found)
            assertTrue(found!!.isPhoto)
            assertEquals(photo, carol.searchPicture(alice.accountId))
            assertEquals(fetches + 2, relay.avatarFetches)
            assertEquals(1, relay.avatarNotModified, "the second search revalidated the cached copy")

            // A new picture: a new version, and the cached copy is replaced.
            alice.messenger.setProfilePicture(otherPhoto)
            bob.nextProfile { ProfilePicture.fromProto(it.profileAvatar) == otherPhoto }
            assertEquals(otherPhoto, carol.searchPicture(alice.accountId))
            assertEquals(2L, relay.avatarHighestVersion(alice.accountId))

            // The initial letter: the record goes, and contacts get a ProfileUpdate without a picture.
            alice.messenger.setProfilePicture(null)
            bob.nextProfile { !it.hasProfileAvatar() }
            assertNull(bob.messenger.contact(alice.accountId)?.picture)
            assertNull(relay.avatarRecord(alice.accountId))
            assertNull(carol.searchPicture(alice.accountId))
            listOf(alice, bob).forEach { it.job.cancelAndJoin() }
        }
    }

    @Test
    fun `turning show in search off deletes the record, and contacts keep the picture`() = runBlocking {
        coroutineScope {
            val (alice, bob) = acceptedPair(this)
            val carol = Messenger.register(Username("carol"), RelayClient(relayUrl), config = TestClient.testConfig)
            alice.messenger.setProfilePicture(photo)
            bob.nextProfile { it.hasProfileAvatar() }
            carol.findByUsername("alice")
            assertEquals(photo, carol.searchPicture(alice.accountId))

            assertTrue(alice.messenger.setShowPictureInSearch(false))
            assertFalse(alice.messenger.account.showPictureInSearch)
            assertNull(relay.avatarRecord(alice.accountId))
            assertNull(carol.searchPicture(alice.accountId))
            assertNull(withTimeoutOrNull(300.milliseconds) { bob.nextProfile() }, "the setting is not a profile change (§8.7)")
            assertEquals(photo, bob.messenger.contact(alice.accountId)?.picture)

            // A new picture while it is off reaches contacts only.
            alice.messenger.setProfilePicture(otherPhoto)
            bob.nextProfile { it.hasProfileAvatar() }
            assertNull(relay.avatarRecord(alice.accountId))
            assertEquals(2L, relay.avatarHighestVersion(alice.accountId), "nothing was written for it")

            assertTrue(alice.messenger.setShowPictureInSearch(true))
            assertEquals(3L, relay.avatarHighestVersion(alice.accountId))
            assertEquals(otherPhoto, carol.searchPicture(alice.accountId))
            listOf(alice, bob).forEach { it.job.cancelAndJoin() }
        }
    }

    @Test
    fun `a username change publishes the record again under the new name`() = runBlocking {
        val alice = Messenger.register(Username("alice"), RelayClient(relayUrl), config = TestClient.testConfig)
        alice.setShowPictureInSearch(true) // Explicit opt-in for this publication scenario.
        val carol = Messenger.register(Username("carol"), RelayClient(relayUrl), config = TestClient.testConfig)
        alice.setProfilePicture(photo)
        carol.findByUsername("alice")
        assertEquals(photo, carol.searchPicture(alice.accountId))

        alice.changeUsername(Username("alice_new"))
        assertFalse(PendingSetup.SYNC_AVATAR in alice.account.pendingSetup)
        assertEquals(2L, relay.avatarHighestVersion(alice.accountId))
        val record = relay.avatarRecord(alice.accountId)!!
        AvatarRecord.open(record, AvatarKey.derive(Username("alice_new"), alice.accountId))

        // The old name is held and finds nobody; its cached picture is dropped with the lookup.
        assertNull(carol.findByUsername("alice"))
        assertEquals(alice.accountId, carol.findByUsername("alice_new")?.accountId)
        assertEquals(photo, carol.searchPicture(alice.accountId))

        // With "Show in search" off, a rename leaves nothing to publish.
        alice.setShowPictureInSearch(false)
        val writes = relay.avatarWrites
        alice.changeUsername(Username("alice_3"))
        assertEquals(writes, relay.avatarWrites)
        assertFalse(PendingSetup.SYNC_AVATAR in alice.account.pendingSetup)
    }

    @Test
    fun `a restored phone brings the record in line, answering a stale version once`() = runBlocking {
        coroutineScope {
            val (alice, bob) = acceptedPair(this)
            bob.messenger.setProfilePicture(otherPhoto)
            alice.nextProfile { it.hasProfileAvatar() }
            alice.messenger.setProfilePicture(photo)
            bob.nextProfile { it.hasProfileAvatar() }
            alice.messenger.setShowPictureInSearch(false)
            alice.messenger.setShowPictureInSearch(true)
            assertEquals(3L, relay.avatarHighestVersion(alice.accountId))

            val file = alice.messenger.exportBackup("yedek parolam 7", iterations = 1000)
            val saved = AccountBackup.parseFrom(BackupFile.open(file, "yedek parolam 7"))
            assertEquals(photo, ProfilePicture.fromProto(saved.avatar))
            assertFalse(saved.avatarSearchOff)
            assertEquals(otherPhoto, ProfilePicture.fromProto(saved.contactsList.single().avatar))
            alice.job.cancelAndJoin()

            val writes = relay.avatarWrites
            val restored = Messenger.restore(file, "yedek parolam 7", RelayClient(relayUrl), config = TestClient.testConfig)
            assertEquals(photo, restored.account.profilePicture)
            assertTrue(restored.account.showPictureInSearch)
            // The backup holds no version: version 1 is stale, and the one repeat goes above the highest.
            assertEquals(setOf(PendingSetup.ANNOUNCE_PROFILE), restored.account.pendingSetup)
            assertEquals(writes + 2, relay.avatarWrites)
            assertEquals(4L, relay.avatarHighestVersion(alice.accountId))
            assertEquals(4L, restored.account.avatarVersion)
            assertEquals(otherPhoto, restored.contact(bob.accountId)?.picture)
            assertTrue(restored.contact(bob.accountId)!!.profileReceived)

            // The new phone's ProfileUpdate carries the picture, too.
            val newPhone = TestClient(restored, this)
            newPhone.awaitUpToDate()
            assertEquals(photo, ProfilePicture.fromProto(bob.nextProfile().profileAvatar), "the old phone's profiles were all read before")
            listOf(newPhone, bob).forEach { it.job.cancelAndJoin() }
        }
    }

    @Test
    fun `a restore with show in search off deletes what the old phone published`() = runBlocking {
        val alice = Messenger.register(Username("alice"), RelayClient(relayUrl), config = TestClient.testConfig)
        alice.setShowPictureInSearch(true) // Explicit opt-in for this publication scenario.
        alice.setProfilePicture(photo)
        val file = alice.exportBackup("yedek parolam 7", iterations = 1000)
        val off = AccountBackup.parseFrom(BackupFile.open(file, "yedek parolam 7")).toBuilder().setAvatarSearchOff(true).build()
        val restored = Messenger.restore(BackupFile.create("yedek parolam 7", off.toByteArray(), 1000), "yedek parolam 7", RelayClient(relayUrl), config = TestClient.testConfig)
        assertFalse(restored.account.showPictureInSearch)
        assertEquals(photo, restored.account.profilePicture)
        assertNull(relay.avatarRecord(alice.accountId))
        assertEquals(2L, relay.avatarHighestVersion(alice.accountId))

        // A picture the restoring client may not send is the initial letter, not a refused backup.
        val erin = Messenger.register(Username("erin"), RelayClient(relayUrl), config = TestClient.testConfig)
        erin.setShowPictureInSearch(true) // Explicit opt-in for this publication scenario.
        val erinBackup = AccountBackup.parseFrom(BackupFile.open(erin.exportBackup("yedek parolam 7", iterations = 1000), "yedek parolam 7"))
        assertFalse(erinBackup.hasAvatar())
        val unknownPreset = erinBackup.toBuilder().setAvatar(profileAvatar { presetId = 77 }).build()
        val restoredErin = Messenger.restore(BackupFile.create("yedek parolam 7", unknownPreset.toByteArray(), 1000), "yedek parolam 7", RelayClient(relayUrl), config = TestClient.testConfig)
        assertNull(restoredErin.account.profilePicture)
        assertTrue(restoredErin.account.showPictureInSearch, "absent in older backups: on")
    }

    @Test
    fun `a relay without avatars is left alone until the next start`() = runBlocking {
        relay.avatarsSupported = false
        val storage = InMemoryProtocolStorage()
        val contacts = InMemoryContactStore()
        val saved = mutableListOf<LocalAccount>()
        val alice = Messenger.register(Username("alice"), RelayClient(relayUrl), storage, contacts, TestClient.testConfig, accountStore = { saved += it })
        alice.setShowPictureInSearch(true) // Explicit opt-in for this publication scenario.
        val carol = Messenger.register(Username("carol"), RelayClient(relayUrl), config = TestClient.testConfig)

        assertEquals(emptyList<Any>(), alice.setProfilePicture(photo), "no error for the user")
        assertTrue(PendingSetup.SYNC_AVATAR in alice.account.pendingSetup)
        assertEquals(1, relay.avatarWrites)
        assertFalse(alice.syncAvatarRecord())
        alice.performMaintenance()
        assertEquals(1, relay.avatarWrites, "not again in this process")
        carol.findByUsername("alice")
        assertNull(carol.searchPicture(alice.accountId))

        // The next app start, on a relay that has learned §4.12.
        relay.avatarsSupported = true
        val account = saved.last()
        val restarted = Messenger(account, ProtocolStore(account.identityKeyPair, account.registrationId, storage), contacts, RelayClient(relayUrl), TestClient.testConfig)
        restarted.performMaintenance()
        assertFalse(PendingSetup.SYNC_AVATAR in restarted.account.pendingSetup)
        assertEquals(2L, relay.avatarHighestVersion(alice.accountId), "version 1 was never accepted, but it is never reused")
        assertEquals(photo, carol.searchPicture(alice.accountId))
    }

    @Test
    fun `a rate-limited write waits for Retry-After`() = runBlocking {
        val clock = MutableClock()
        val alice = Messenger.register(Username("alice"), RelayClient(relayUrl), config = TestClient.testConfig.copy(clock = clock))
        alice.setShowPictureInSearch(true) // Explicit opt-in for this publication scenario.
        relay.rateLimitNext("PUT", "/v1/accounts/me/avatar", 120)
        alice.setProfilePicture(photo)
        assertTrue(PendingSetup.SYNC_AVATAR in alice.account.pendingSetup)
        assertFalse(alice.syncAvatarRecord(), "not before Retry-After")
        assertEquals(0, relay.avatarWrites)
        assertNull(relay.avatarRecord(alice.accountId))

        clock.advance(121.seconds)
        assertTrue(alice.syncAvatarRecord())
        assertEquals(2L, relay.avatarHighestVersion(alice.accountId), "the refused version is not reused")
        assertFalse(PendingSetup.SYNC_AVATAR in alice.account.pendingSetup)
    }

    @Test
    fun `a rate-limited store never holds up taking the picture out of search`() = runBlocking {
        val clock = MutableClock()
        val alice = Messenger.register(Username("alice"), RelayClient(relayUrl), config = TestClient.testConfig.copy(clock = clock))
        alice.setShowPictureInSearch(true) // Explicit opt-in for this publication scenario.
        alice.setProfilePicture(photo)
        assertEquals(1L, relay.avatarHighestVersion(alice.accountId))
        relay.rateLimitNext("PUT", "/v1/accounts/me/avatar", 3600)
        alice.setProfilePicture(otherPhoto)
        assertTrue(PendingSetup.SYNC_AVATAR in alice.account.pendingSetup)

        // "Show in search" off while the store waits: the delete goes out at once (§4.11, §8.10).
        assertTrue(alice.setShowPictureInSearch(false))
        assertNull(relay.avatarRecord(alice.accountId))
        assertEquals(3L, relay.avatarHighestVersion(alice.accountId))
        assertFalse(PendingSetup.SYNC_AVATAR in alice.account.pendingSetup)

        // On again: the store still waits for its Retry-After.
        assertFalse(alice.setShowPictureInSearch(true))
        assertNull(relay.avatarRecord(alice.accountId))
        clock.advance(3601.seconds)
        assertTrue(alice.syncAvatarRecord())
        assertEquals(otherPhoto, ProfilePicture.fromProto(AvatarRecord.openAvatar(relay.avatarRecord(alice.accountId)!!, AvatarKey.derive(Username("alice"), alice.accountId))))
    }

    @Test
    fun `a replayed older record is ignored, also after a 404`() = runBlocking {
        val alice = Messenger.register(Username("alice"), RelayClient(relayUrl), config = TestClient.testConfig)
        alice.setShowPictureInSearch(true) // Explicit opt-in for this publication scenario.
        val carol = Messenger.register(Username("carol"), RelayClient(relayUrl), config = TestClient.testConfig)
        alice.setProfilePicture(photo)
        val first = relay.avatarRecord(alice.accountId)!!
        alice.setProfilePicture(otherPhoto)
        val second = relay.avatarRecord(alice.accountId)!!
        carol.findByUsername("alice")
        assertEquals(otherPhoto, carol.searchPicture(alice.accountId))

        // The relay serves the older record: the cached copy stays.
        relay.replaceAvatarRecord(alice.accountId, first)
        val fetches = relay.avatarFetches
        assertEquals(otherPhoto, carol.searchPicture(alice.accountId))
        assertEquals(fetches + 1, relay.avatarFetches)
        assertEquals(0, relay.avatarNotModified)

        // A 404 drops the copy but not the version: the older record still shows nothing.
        relay.replaceAvatarRecord(alice.accountId, null)
        assertNull(carol.searchPicture(alice.accountId))
        relay.replaceAvatarRecord(alice.accountId, first)
        assertNull(carol.searchPicture(alice.accountId))

        // The same version again, and any newer one, is shown.
        relay.replaceAvatarRecord(alice.accountId, second)
        assertEquals(otherPhoto, carol.searchPicture(alice.accountId))
        alice.setProfilePicture(photo)
        assertEquals(photo, carol.searchPicture(alice.accountId))

        // Forgetting the memory (the data key left it) forgets the versions too.
        carol.clearSearchPictures()
        relay.replaceAvatarRecord(alice.accountId, first)
        assertEquals(photo, carol.searchPicture(alice.accountId))
    }

    @Test
    fun `an incoming request's picture is fetched only when the user asks for it, and never for blocked accounts`() = runBlocking {
        coroutineScope {
            val alice = TestClient.register(relayUrl, "alice", this)
            alice.awaitUpToDate()
            val dave = Messenger.register(Username("dave"), RelayClient(relayUrl), config = TestClient.testConfig)
            dave.setShowPictureInSearch(true) // Explicit opt-in for this publication scenario.
            dave.setProfilePicture(photo)
            dave.findByUsername("alice")
            val fetches = relay.avatarFetches
            dave.sendText(alice.accountId, "merhaba")
            val request = alice.nextData()
            assertEquals(ContactState.INCOMING_REQUEST, request.contactState)
            assertEquals(fetches, relay.avatarFetches, "nothing is fetched for a request by itself")

            // A ProfileUpdate inside a request carries no picture to show (§8.9).
            dave.send(alice.accountId, content { control = controlMessage { profile = profileUpdate { username = "dave"; updatedAt = 1; profileAvatar = photo.toProto() } } })
            alice.next { it.content.hasControl() && it.content.control.hasProfile() }
            assertNull(alice.messenger.contact(dave.accountId)?.picture)
            assertFalse(alice.messenger.contact(dave.accountId)!!.profileReceived)

            // A list row, or the opened request without a tap: nothing is fetched (§8.9).
            assertEquals(UsernameStatus.VERIFIED, alice.messenger.contact(dave.accountId)?.usernameStatus)
            assertNull(alice.messenger.searchPicture(dave.accountId))
            assertNull(alice.messenger.requestPicture(dave.accountId, userConfirmed = false))
            assertEquals(fetches, relay.avatarFetches)

            // The user taps "Show picture": its username was verified (§6.3), so the record can be opened.
            val picture = alice.messenger.requestPicture(dave.accountId, userConfirmed = true)
            assertEquals(photo, picture)
            assertTrue(picture!!.isPhoto)
            assertEquals(fetches + 1, relay.avatarFetches)

            alice.messenger.block(dave.accountId)
            assertNull(alice.messenger.searchPicture(dave.accountId))
            assertNull(alice.messenger.requestPicture(dave.accountId, userConfirmed = true))
            assertEquals(fetches + 1, relay.avatarFetches)
            alice.job.cancelAndJoin()
        }
    }

    @Test
    fun `a malformed picture in a profile update is the initial letter, and the rest applies`() = runBlocking {
        coroutineScope {
            val (alice, bob) = acceptedPair(this)
            var marker = 0L
            suspend fun sendProfile(configure: ProfileUpdateKt.Dsl.() -> Unit) {
                val sent = ++marker
                alice.messenger.send(bob.accountId, content { control = controlMessage { profile = profileUpdate { username = "alice"; updatedAt = sent; configure() } } })
                bob.nextProfile { it.updatedAt == sent }
            }

            sendProfile {
                displayName = "Alice"
                profileAvatar = profileAvatar { photo = avatarPhoto { contentType = "image/png"; data = TestImages.jpeg(16, 16).toByteString() } }
            }
            val contact = bob.messenger.contact(alice.accountId)!!
            assertEquals("Alice", contact.displayName)
            assertNull(contact.picture)
            assertTrue(contact.profileReceived)

            // The deprecated field 2 is ignored, whatever it holds.
            sendProfile {
                displayName = "Alice 2"
                avatar = attachment { kind = Attachment.Kind.IMAGE; mediaId = ByteArray(3).toByteString() }
            }
            assertEquals("Alice 2", bob.messenger.contact(alice.accountId)?.displayName)
            assertNull(bob.messenger.contact(alice.accountId)?.picture)

            // A preset this build does not know is kept, and shown as the initial letter.
            sendProfile { profileAvatar = profileAvatar { presetId = 99 } }
            val preset = bob.messenger.contact(alice.accountId)?.picture as ProfilePicture.Preset
            assertEquals(99, preset.id)
            assertFalse(preset.isKnown)

            // ...and this client never sends one.
            assertThrows<IllegalArgumentException> { runBlocking { alice.messenger.setProfilePicture(preset) } }
            listOf(alice, bob).forEach { it.job.cancelAndJoin() }
        }
    }

    @Test
    fun `the largest photo still fits one envelope and one record`() = runBlocking {
        coroutineScope {
            val (alice, bob) = acceptedPair(this)
            val jpeg = TestImages.jpeg(256, 256)
            // Filler in the scan keeps the file valid and brings the ProfileAvatar to exactly 49110 bytes.
            var size = ProfilePicture.MAX_SERIALIZED_BYTES - 20
            var largest = ProfilePicture.photo("image/jpeg", TestImages.padded(jpeg, size))
            while (largest.toProto().serializedSize < ProfilePicture.MAX_SERIALIZED_BYTES) {
                size += ProfilePicture.MAX_SERIALIZED_BYTES - largest.toProto().serializedSize
                largest = ProfilePicture.photo("image/jpeg", TestImages.padded(jpeg, size))
            }
            assertEquals(ProfilePicture.MAX_SERIALIZED_BYTES, largest.toProto().serializedSize)

            assertEquals(emptyList<Any>(), alice.messenger.setProfilePicture(largest))
            assertEquals(largest, ProfilePicture.fromProto(bob.nextProfile { it.hasProfileAvatar() }.profileAvatar))
            assertEquals(49152, relay.avatarRecord(alice.accountId)?.size)
            assertArrayEquals(largest.data, (bob.messenger.contact(alice.accountId)?.picture as ProfilePicture.Photo).data)
            listOf(alice, bob).forEach { it.job.cancelAndJoin() }
        }
    }

    @Test
    fun `a search shows a stranger's picture without keeping a contact, and asks the relay at most once a minute`() = runBlocking {
        val clock = MutableClock()
        val alice = Messenger.register(Username("alice"), RelayClient(relayUrl), config = TestClient.testConfig)
        alice.setShowPictureInSearch(true) // Explicit opt-in for this publication scenario.
        alice.setProfilePicture(photo)
        val carol = Messenger.register(Username("carol"), RelayClient(relayUrl), config = TestClient.testConfig.copy(clock = clock))
        val name = Username("alice")

        assertEquals(alice.accountId, carol.findAccount(name)?.accountId)
        assertNull(carol.contact(alice.accountId), "a search keeps no contact")
        assertNull(carol.findAccount(Username("nobody")))
        carol.prepareAvatarSearch(name)
        val fetches = relay.avatarFetches
        val found = carol.searchAvatar(name, alice.accountId)
        assertEquals(photo, found)
        assertTrue(found!!.isPhoto)
        assertEquals(fetches + 1, relay.avatarFetches)
        assertNull(carol.contact(alice.accountId))

        // Within a minute: the copy in memory, no request.
        assertEquals(photo, carol.searchAvatar(name, alice.accountId))
        assertEquals(fetches + 1, relay.avatarFetches)
        // Later: revalidated with If-None-Match; 304 keeps the copy.
        clock.advance(120.seconds)
        assertEquals(photo, carol.searchAvatar(name, alice.accountId))
        assertEquals(fetches + 2, relay.avatarFetches)
        assertEquals(1, relay.avatarNotModified)
        // The same search twice at once: one request.
        clock.advance(120.seconds)
        val both = coroutineScope { List(2) { async { carol.searchAvatar(name, alice.accountId) } }.awaitAll() }
        assertEquals(listOf(photo, photo), both)
        assertEquals(fetches + 3, relay.avatarFetches)
        // Our own account shows our own choice, without a request.
        assertEquals(photo, alice.searchAvatar(name, alice.accountId))
        assertEquals(fetches + 3, relay.avatarFetches)
        // Under another username's key the record does not open: the initial letter.
        assertNull(carol.searchAvatar(Username("alicea"), alice.accountId))
        // A new picture shows once the copy is a minute old.
        alice.setProfilePicture(otherPhoto)
        assertEquals(photo, carol.searchAvatar(name, alice.accountId))
        clock.advance(61.seconds)
        assertEquals(otherPhoto, carol.searchAvatar(name, alice.accountId))
    }

    @Test
    fun `search copies go when the directory maps the name elsewhere, and records of another account or tampered ones open as nothing`() = runBlocking {
        val alice = Messenger.register(Username("alice"), RelayClient(relayUrl), config = TestClient.testConfig)
        alice.setShowPictureInSearch(true) // Explicit opt-in for this publication scenario.
        val mallory = Messenger.register(Username("mallory"), RelayClient(relayUrl), config = TestClient.testConfig)
        val carol = Messenger.register(Username("carol"), RelayClient(relayUrl), config = TestClient.testConfig)
        val name = Username("alice")
        alice.setProfilePicture(photo)
        val record = relay.avatarRecord(alice.accountId)!!

        // Mallory serves a record sealed for Alice under her own account: it does not open as hers
        // (the account id is in the key and the AAD).
        val sealed = AvatarRecord.seal(otherPhoto.toProto(), 1, AvatarKey.derive(name, alice.accountId))
        relay.replaceAvatarRecord(mallory.accountId, sealed)
        assertNull(carol.searchAvatar(name, mallory.accountId))
        // A flipped byte in a stored record: the initial letter too, and nothing is kept.
        relay.replaceAvatarRecord(alice.accountId, record.copyOf().also { it[100] = (it[100].toInt() xor 1).toByte() })
        assertNull(carol.searchAvatar(name, alice.accountId))
        relay.replaceAvatarRecord(alice.accountId, record)
        assertEquals(photo, carol.searchAvatar(name, alice.accountId))

        // The account is deleted and the directory says so: the copy goes with the answer.
        alice.deleteAccount()
        assertNull(carol.findAccount(name))
        val fetches = relay.avatarFetches
        assertNull(carol.searchAvatar(name, alice.accountId))
        assertEquals(fetches + 1, relay.avatarFetches, "asked again, not served from memory")
    }

    @Test
    fun `a replayed older record is ignored in search, also after a 404`() = runBlocking {
        val clock = MutableClock()
        val alice = Messenger.register(Username("alice"), RelayClient(relayUrl), config = TestClient.testConfig)
        alice.setShowPictureInSearch(true) // Explicit opt-in for this publication scenario.
        val carol = Messenger.register(Username("carol"), RelayClient(relayUrl), config = TestClient.testConfig.copy(clock = clock))
        val name = Username("alice")
        alice.setProfilePicture(photo)
        val first = relay.avatarRecord(alice.accountId)!!
        alice.setProfilePicture(otherPhoto)
        val second = relay.avatarRecord(alice.accountId)!!
        assertEquals(otherPhoto, carol.searchAvatar(name, alice.accountId))

        // The relay serves the older record: the kept copy stays.
        relay.replaceAvatarRecord(alice.accountId, first)
        clock.advance(120.seconds)
        assertEquals(otherPhoto, carol.searchAvatar(name, alice.accountId))
        // A 404 drops the copy but not the version: the older record shows nothing.
        relay.replaceAvatarRecord(alice.accountId, null)
        clock.advance(120.seconds)
        assertNull(carol.searchAvatar(name, alice.accountId))
        relay.replaceAvatarRecord(alice.accountId, first)
        clock.advance(120.seconds)
        assertNull(carol.searchAvatar(name, alice.accountId))
        // The same version again is shown.
        relay.replaceAvatarRecord(alice.accountId, second)
        clock.advance(120.seconds)
        assertEquals(otherPhoto, carol.searchAvatar(name, alice.accountId))
        // Forgetting the memory (the data key left it) forgets the versions too.
        carol.clearSearchPictures()
        relay.replaceAvatarRecord(alice.accountId, first)
        assertEquals(photo, carol.searchAvatar(name, alice.accountId))
    }

    @Test
    fun `the record's status says whether it is in line, still owed, or impossible on this relay`() = runBlocking {
        val clock = MutableClock()
        val alice = Messenger.register(Username("alice"), RelayClient(relayUrl), config = TestClient.testConfig.copy(clock = clock))
        alice.setShowPictureInSearch(true) // Explicit opt-in for this publication scenario.
        assertEquals(AvatarRecordStatus.IN_SYNC, alice.avatarRecordStatus.value)

        relay.failNext("PUT", "/v1/accounts/me/avatar")
        alice.setProfilePicture(photo)
        assertEquals(AvatarRecordStatus.PENDING, alice.avatarRecordStatus.value)
        assertEquals(AvatarRecordStatus.IN_SYNC, alice.trySyncAvatarRecord())
        assertEquals(AvatarRecordStatus.IN_SYNC, alice.avatarRecordStatus.value)

        // 429: owed until Retry-After, without a request meanwhile.
        relay.rateLimitNext("PUT", "/v1/accounts/me/avatar", 3600)
        alice.setProfilePicture(otherPhoto)
        val writes = relay.avatarWrites
        assertEquals(AvatarRecordStatus.PENDING, alice.trySyncAvatarRecord())
        assertEquals(writes, relay.avatarWrites)
        clock.advance(3601.seconds)
        assertEquals(AvatarRecordStatus.IN_SYNC, alice.trySyncAvatarRecord())

        // A relay without avatar records: unavailable, and left alone until the next start.
        relay.avatarsSupported = false
        val storage = InMemoryProtocolStorage()
        val contacts = InMemoryContactStore()
        val saved = mutableListOf<LocalAccount>()
        val bob = Messenger.register(Username("bob"), RelayClient(relayUrl), storage, contacts, TestClient.testConfig, accountStore = { saved += it })
        bob.setShowPictureInSearch(true) // Explicit opt-in for this publication scenario.
        bob.setProfilePicture(photo)
        assertEquals(AvatarRecordStatus.UNAVAILABLE, bob.avatarRecordStatus.value)
        val attempts = relay.avatarWrites
        assertEquals(AvatarRecordStatus.UNAVAILABLE, bob.trySyncAvatarRecord())
        assertEquals(attempts, relay.avatarWrites)
        val account = saved.last()
        val restarted = Messenger(account, ProtocolStore(account.identityKeyPair, account.registrationId, storage), contacts, RelayClient(relayUrl), TestClient.testConfig)
        assertEquals(AvatarRecordStatus.PENDING, restarted.avatarRecordStatus.value)
        assertEquals(AvatarRecordStatus.UNAVAILABLE, restarted.trySyncAvatarRecord())
        assertEquals(attempts + 1, relay.avatarWrites)
        relay.avatarsSupported = true
    }
}
