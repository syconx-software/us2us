package com.syconx.us2us.runtime.vault

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Filesystem state/fault tests. The fake codec is not cryptography or hardware evidence. */
class SecureItemLayoutTest {
    @get:Rule val temporary = TemporaryFolder()

    private class Fixture(val directory: File) {
        var legacyKey = true
        var nextKey = false
        var syncs = 0
        var failSyncAt = -1
        var failCopy: String? = null
        var unreadableLegacy: String? = null
        var unreadableActive: String? = null
        var badVerification = false
        var failCleanup = false
        val openedPlaintexts = mutableListOf<ByteArray>()
        val values = linkedMapOf("device-secret" to ByteArray(32) { 0x41 }, "secondary" to byteArrayOf(7, 8, 9))
        init { directory.mkdirs(); values.forEach { (name, data) -> File(directory, "$name.item").writeBytes(data) } }
        fun layout() = SecureItemLayout(directory) {
            syncs++
            if (syncs == failSyncAt) throw IOException("simulated directory sync interruption")
        }
        fun open(name: String, file: File, hardened: Boolean): ByteArray? {
            if (!file.exists() || (hardened && (!nextKey || name == unreadableActive))
                || (!hardened && (!legacyKey || name == unreadableLegacy))) return null
            val bytes = file.readBytes()
            val plaintext = if (hardened) {
                if (bytes.isEmpty() || bytes[0] != 0x7e.toByte()) return null
                bytes.copyOfRange(1, bytes.size)
            } else bytes
            if (hardened && badVerification) plaintext[0] = (plaintext[0].toInt() xor 1).toByte()
            return plaintext.also(openedPlaintexts::add)
        }
        fun migrate() {
            val layout = layout()
            layout.locked {
                layout.migrate(::open, { name, bytes ->
                    if (name == failCopy) throw IOException("simulated copy interruption")
                    byteArrayOf(0x7e) + bytes
                }, { nextKey = true }, {
                    if (failCleanup) throw IOException("simulated key deletion interruption")
                    legacyKey = false
                })
            }
        }
        fun read(name: String): ByteArray? {
            val layout = layout()
            return layout.locked { val active = layout.active(); open(name, File(active.directory, "$name.item"), active.hardened) }
        }
        fun active() = layout().locked { layout().active().hardened }
        fun assertLegacyPreserved() {
            assertFalse(active()); assertTrue(legacyKey)
            values.forEach { (name, data) -> assertArrayEquals(data, read(name)) }
        }
    }

    private fun fixture() = Fixture(temporary.newFolder())
    private fun expectIo(block: () -> Unit) {
        try { block(); fail("expected interrupted migration") } catch (_: IOException) { }
    }

    @Test fun allItemsSwitchTogetherAndTemporaryPlaintextsAreZeroed() {
        val f = fixture(); f.migrate()
        assertTrue(f.active()); assertFalse(f.legacyKey); assertTrue(f.nextKey)
        assertTrue(f.openedPlaintexts.all { bytes -> bytes.all { it == 0.toByte() } })
        f.values.forEach { (name, data) ->
            assertArrayEquals(data, f.read(name)); assertFalse(File(f.directory, "$name.item").exists())
        }
    }

    @Test fun partialCopyLeavesEveryLegacyItemUsableAndRetryCompletes() {
        val f = fixture(); f.failCopy = "secondary"
        expectIo(f::migrate); f.assertLegacyPreserved()
        f.failCopy = null; f.migrate()
        assertTrue(f.active()); f.values.forEach { (name, data) -> assertArrayEquals(data, f.read(name)) }
    }

    @Test fun unreadableLegacyItemAbortsWithoutDroppingItOrRetiringTheKey() {
        val f = fixture(); f.unreadableLegacy = "secondary"
        expectIo(f::migrate)
        assertTrue(f.legacyKey); assertFalse(f.active())
        f.unreadableLegacy = null; f.assertLegacyPreserved()
    }

    @Test fun failedRoundtripNeverCommitsAndWipesTemporaryPlaintext() {
        val f = fixture(); f.badVerification = true
        expectIo(f::migrate)
        assertTrue(f.openedPlaintexts.all { b -> b.all { it == 0.toByte() } })
        f.assertLegacyPreserved()
    }

    @Test fun markerWriteFailureKeepsTheOldGenerationAndRetries() {
        val f = fixture()
        val blocked = File(f.directory, SecureItemLayout.MARKER_NAME + ".pending").also { it.mkdir() }
        File(blocked, "interrupt").writeText("test")
        expectIo(f::migrate); f.assertLegacyPreserved()
        assertTrue(blocked.deleteRecursively()); f.migrate(); assertTrue(f.active())
    }

    @Test fun stagingDirectorySyncFailurePreventsCommit() {
        val f = fixture(); f.failSyncAt = 1
        expectIo(f::migrate); f.assertLegacyPreserved()
        f.failSyncAt = -1; f.migrate(); assertTrue(f.active())
    }

    @Test fun postRenameSyncFailurePreservesLegacyKeyUntilDurableRetry() {
        val f = fixture(); f.failSyncAt = 3
        expectIo(f::migrate)
        assertTrue(f.active()); assertTrue(f.legacyKey)
        f.values.forEach { (name, data) -> assertArrayEquals(data, f.read(name)) }
        f.failSyncAt = -1; f.migrate(); assertFalse(f.legacyKey)
    }

    @Test fun cleanupRetryNeverResurrectsOldOrDeletedItemValues() {
        val f = fixture(); f.failCleanup = true
        expectIo(f::migrate); assertTrue(f.active()); assertTrue(f.legacyKey)
        val active = File(f.directory, SecureItemLayout.NEXT_DIRECTORY)
        File(active, "device-secret.item").writeBytes(byteArrayOf(0x7e, 5, 6))
        assertTrue(File(active, "secondary.item").delete())
        f.failCleanup = false; f.migrate()
        assertArrayEquals(byteArrayOf(5, 6), f.read("device-secret")); assertNull(f.read("secondary"))
        assertFalse(f.legacyKey)
    }

    @Test fun missingActiveKeyCannotFallbackToLegacyOrTriggerCleanup() {
        val f = fixture(); f.failCleanup = true; expectIo(f::migrate)
        f.failCleanup = false; f.nextKey = false
        assertNull(f.read("device-secret")); expectIo(f::migrate); assertTrue(f.legacyKey)
    }

    @Test fun unreadableActiveItemPreventsLegacyKeyCleanup() {
        val f = fixture(); f.failCleanup = true; expectIo(f::migrate)
        f.failCleanup = false; f.unreadableActive = "secondary"
        expectIo(f::migrate); assertTrue(f.legacyKey)
    }

    @Test fun invalidMarkerDoesNotSelectTheLegacyGeneration() {
        val f = fixture()
        File(f.directory, SecureItemLayout.MARKER_NAME).writeText("unknown")
        expectIo { f.read("device-secret") }; assertTrue(f.legacyKey)
    }

    @Test fun missingMarkerAfterRetirementCannotSelectAnEmptyLegacyStore() {
        val f = fixture(); f.migrate()
        assertTrue(File(f.directory, SecureItemLayout.MARKER_NAME).delete())
        expectIo { f.read("device-secret") }
        assertTrue(File(f.directory, SecureItemLayout.NEXT_DIRECTORY + "/device-secret.item").isFile)
        assertFalse(f.legacyKey)
    }

    @Test fun lostLegacyKeyOnRetryPreservesEarlierCandidateCopies() {
        val f = fixture(); f.failCopy = "secondary"; expectIo(f::migrate)
        val candidate = File(f.directory, SecureItemLayout.NEXT_DIRECTORY + "/device-secret.item")
        val before = candidate.readBytes()
        f.legacyKey = false; f.failCopy = null
        expectIo(f::migrate)
        assertTrue(f.nextKey); assertArrayEquals(before, candidate.readBytes())
    }

    @Test fun lostMarkerAndRetiredLegacyKeyDoNotDestroyTheNewCopies() {
        val f = fixture(); f.failCleanup = true; expectIo(f::migrate)
        val candidate = File(f.directory, SecureItemLayout.NEXT_DIRECTORY + "/device-secret.item")
        val before = candidate.readBytes()
        f.legacyKey = false
        assertTrue(File(f.directory, SecureItemLayout.MARKER_NAME).delete())
        expectIo(f::migrate)
        assertTrue(f.nextKey); assertArrayEquals(before, candidate.readBytes())
    }

    @Test fun emptyLegacyStoreDoesNotCreateAMarkerOrCandidateKey() {
        val f = fixture(); f.values.keys.forEach { File(f.directory, "$it.item").delete() }
        f.migrate(); assertFalse(f.active()); assertFalse(f.nextKey); assertTrue(f.legacyKey)
    }

    @Test fun candidateSymlinkIsRemovedWithoutFollowingIt() {
        val f = fixture(); val outside = temporary.newFolder()
        val untouched = File(outside, "keep").also { it.writeText("test") }
        Files.createSymbolicLink(File(f.directory, SecureItemLayout.NEXT_DIRECTORY).toPath(), outside.toPath())
        f.migrate(); assertEquals("test", untouched.readText()); assertTrue(f.active())
    }

    @Test fun clearingGenerationsRemovesPendingFilesButKeepsTheLockInode() {
        val f = fixture(); f.migrate()
        File(f.directory, "leftover.pending").writeText("test")
        val layout = f.layout(); layout.locked { layout.clearFiles() }
        assertEquals(listOf(".store.lock"), f.directory.list().orEmpty().toList())
    }

    @Test fun separateInstancesSerializeBeforeResolvingTheirGeneration() {
        val directory = temporary.newFolder()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val second = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val a = pool.submit { SecureItemLayout(directory) {}.locked { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)) } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val b = pool.submit { SecureItemLayout(directory) {}.locked { second.countDown() } }
            assertFalse(second.await(100, TimeUnit.MILLISECONDS))
            release.countDown(); a.get(5, TimeUnit.SECONDS); b.get(5, TimeUnit.SECONDS)
            assertEquals(0L, second.count)
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun unlockedDeviceFlagIsReservedForApi35AndNewer() {
        for (sdk in listOf(28, 30, 31, 32, 33, 34)) assertFalse(unlockedWrappingKeyRequired(sdk))
        for (sdk in listOf(35, 36, 37)) assertTrue(unlockedWrappingKeyRequired(sdk))
    }
}
