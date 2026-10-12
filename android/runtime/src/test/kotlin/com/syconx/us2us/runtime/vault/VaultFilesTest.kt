package com.syconx.us2us.runtime.vault

import com.syconx.us2us.core.vault.DataKey
import com.syconx.us2us.core.vault.UnlockAttempts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/** Files, key slots, key material and the erase order around the vault. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VaultFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun atomicWritesReplaceWholeFilesAndLeaveNothingBehind() {
        val directory = temporary.newFolder()
        val file = File(directory, "a/b/settings.json")
        VaultFiles.write(file, "one".toByteArray())
        VaultFiles.write(file, "two".toByteArray())
        assertEquals("two", String(VaultFiles.read(file)!!))
        assertEquals(listOf("settings.json"), file.parentFile!!.list()!!.toList())
        VaultFiles.delete(file)
        assertNull(VaultFiles.read(file))
        VaultFiles.delete(file) // deleting twice is fine
    }

    @Test
    fun theAttemptCounterRoundTrips() {
        val store = FileUnlockAttemptStore(File(temporary.newFolder(), "vault-attempts.json"))
        assertEquals(UnlockAttempts(), store.load())
        val attempts = UnlockAttempts(failures = 4, waited = 12.seconds, bootClockReading = 98_765.seconds)
        store.save(attempts)
        assertEquals(attempts, store.load())
        store.save(UnlockAttempts(failures = 1))
        assertEquals(UnlockAttempts(failures = 1), store.load())
    }

    @Test
    fun deviceSecretItemCreatesOnceAndNeverSilentlyReplaces() {
        val items = InMemorySecureItemStore()
        val secrets = DeviceSecretItem(items)
        assertNull(secrets.load())
        val first = secrets.deviceSecret()
        assertEquals(32, first.size)
        assertArrayEquals(first, secrets.deviceSecret())
        assertArrayEquals(first, secrets.load())
        secrets.destroy()
        assertNull(secrets.load())
        items.write(DeviceSecretItem.ITEM_NAME, ByteArray(31))
        assertNull("a secret of the wrong size is not one", secrets.load())
    }

    @Test
    fun keySlotsHoldTheConversationKeyUnderTheDek() {
        val slots = KeySlotStore(temporary.newFolder())
        val dek = DataKey.generate()
        val (slotId, conversationKey) = slots.createSlot(dek)
        assertEquals(32, conversationKey.size)
        assertArrayEquals(conversationKey, slots.conversationKey(slotId, dek))
        assertEquals(listOf(slotId), slots.slotIds())
        try {
            slots.conversationKey(slotId, DataKey.generate())
            fail("a slot opened under another DEK")
        } catch (_: KeySlotException) {
        }
        slots.destroySlot(slotId)
        assertTrue(slots.slotIds().isEmpty())
        try {
            slots.conversationKey(slotId, dek)
            fail("a destroyed slot still opened")
        } catch (_: KeySlotException) {
        }
    }

    @Test
    fun overdueSlotsAreDestroyedWithoutTheDek() {
        val directory = temporary.newFolder()
        val slots = KeySlotStore(directory)
        val dek = DataKey.generate()
        val (early, _) = slots.createSlot(dek)
        val (late, _) = slots.createSlot(dek)
        val (kept, _) = slots.createSlot(dek)
        val now = Instant.parse("2026-10-08T18:52:00Z")
        slots.setDeadline(now.plusSeconds(60), late)
        slots.setDeadline(now.minusSeconds(60), early)
        assertEquals(mapOf(early to now.minusSeconds(60), late to now.plusSeconds(60)), slots.deadlines())
        // The schedule holds ids and deadlines only, sorted by deadline.
        val schedule = org.json.JSONArray(File(directory, KeySlotStore.SCHEDULE_NAME).readText())
        assertEquals(early.hex, schedule.getJSONObject(0).getString("slotId"))
        assertEquals(setOf("slotId", "deadline"), schedule.getJSONObject(0).keys().asSequence().toSet())

        // Another store on the same directory (the app started again, still locked).
        val locked = KeySlotStore(directory)
        assertEquals(listOf(early), locked.destroyExpiredSlots(now))
        assertEquals(setOf(late, kept), locked.slotIds().toSet())
        assertEquals(mapOf(late to now.plusSeconds(60)), locked.deadlines())
        assertEquals(listOf(late), locked.destroyExpiredSlots(now.plusSeconds(60)))
        assertEquals(listOf(kept), locked.slotIds())
        slots.setDeadline(null, kept)
        assertTrue(slots.deadlines().isEmpty())
        slots.destroyAll()
        assertTrue(directory.list()!!.isEmpty())
    }

    @Test
    fun slotIdsAreLowercaseHex() {
        val id = SlotId.random()
        assertEquals(32, id.hex.length)
        assertEquals(id, SlotId.of(id.bytes()))
        assertNull(SlotId.parse("schedule"))
        assertNull(SlotId.parse("ABCDEF0123456789ABCDEF0123456789"))
    }

    @Test
    fun keyMaterialIsGoneAfterWipe() {
        val bytes = ByteArray(32) { it.toByte() }
        val key = SecretKeyMaterial(bytes)
        val copy = SecretKeyMaterial(bytes)
        bytes.fill(0)
        assertTrue(key.matches(copy))
        assertFalse(key.matches(SecretKeyMaterial(ByteArray(32))))
        var seen: ByteArray? = null
        key.withBytes { seen = it; assertEquals(1, it[1].toInt()) }
        assertTrue("the temporary copy is zeroed", seen!!.all { it.toInt() == 0 })
        val databaseKey = key.withDataKey { it.databaseKey() }
        assertNotEquals(0, databaseKey.sumOf { it.toInt() and 0xff })
        key.wipe()
        assertTrue(key.isWiped)
        assertFalse(key.matches(copy))
        try {
            key.withBytes { }
            fail("a wiped key was used")
        } catch (_: SecretKeyWipedException) {
        }
        assertEquals("SecretKeyMaterial(redacted)", copy.toString())
    }

    @Test
    fun directoriesAreCreatedPurgedAndErased() {
        val directories = AppDirectories(File(temporary.newFolder(), "us2us"))
        directories.prepare()
        directories.prepare()
        for (directory in listOf(directories.vault, directories.runtime, directories.staging)) assertTrue(directory.isDirectory)
        val staged = directories.stagingFile("jpg")
        staged.writeText("photo")
        assertTrue(staged.name.endsWith(".jpg"))
        directories.purgeStaging()
        assertTrue(directories.staging.list()!!.isEmpty())
        directories.eraseAll()
        assertFalse(directories.root.exists())
    }

    @Test
    fun eraseEverythingDestroysKeysThenFiles() = runBlocking {
        val fixture = VaultFixture(temporary.newFolder())
        val dek = fixture.vault.create(CODE, CODE)
        fixture.vault.enableQuickUnlock(CODE)
        val slots = KeySlotStore(fixture.directories.slots)
        val (slotId, _) = dek.withDataKey { slots.createSlot(it) }
        File(fixture.directories.runtime, "state.bin").writeText("state")
        WipeAll(fixture.vault, fixture.directories).run()
        assertFalse(fixture.vault.hasVault)
        assertEquals(0, fixture.items.count)
        assertFalse(fixture.biometrics.hasKey)
        assertFalse(File(fixture.directories.slots, "${slotId.hex}.slot").exists())
        assertFalse(fixture.directories.storage.exists())
        assertTrue(fixture.directories.runtime.list()!!.isEmpty())
        assertTrue(fixture.directories.vault.isDirectory)
        // A new installation starts from scratch.
        val fresh = VaultController(fixture.directories, fixture.items, fixture.biometrics, fixture.clock, VaultController.Configuration(1000), fixture.bootClock, Dispatchers.Unconfined)
        assertFalse(fresh.hasVault)
        fresh.create(OTHER_CODE, OTHER_CODE)
        Unit
    }
}
