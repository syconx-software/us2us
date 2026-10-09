package com.syconx.us2us.core.vault

import com.syconx.us2us.core.crypto.Hkdf
import com.syconx.us2us.core.crypto.Pbkdf2
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class VaultTest {
    /** Low iteration counts keep the tests fast; production uses [Vault.ITERATIONS]. */
    private val fast = 1000
    private val deviceSecret = ByteArray(32) { 0x11 }

    @Test
    fun `vault opens with the right code and device secret only`() {
        val dek = DataKey.generate()
        val vault = Vault.create("472913", deviceSecret, dek, fast)
        assertEquals(25 + 12 + 32 + 16, vault.size)
        assertArrayEquals("BRPV".toByteArray() + 0x01, vault.copyOf(5))
        assertArrayEquals(dek.toBytes(), Vault.open(vault, "472913", deviceSecret).toBytes())

        assertThrows<WrongSecretCodeException> { Vault.open(vault, "472914", deviceSecret) }
        assertThrows<WrongSecretCodeException> { Vault.open(vault, "472913", ByteArray(32) { 0x12 }) }
    }

    @Test
    fun `the vault key is PBKDF2 over the six ASCII digits, then HKDF with the device secret`() {
        val salt = ByteArray(16) { 3 }
        val nonce = ByteArray(12) { 4 }
        val dek = DataKey(ByteArray(32) { 5 })
        val vault = Vault.create("012345", deviceSecret, salt, fast, nonce, dek)
        val codeKey = Pbkdf2.hmacSha256(byteArrayOf(0x30, 0x31, 0x32, 0x33, 0x34, 0x35), salt, fast, 32)
        val kek = Hkdf.sha256(codeKey + deviceSecret, salt, "BRP/vault/v1".toByteArray(), 32)
        val aad = vault.copyOf(25)
        val plaintext = com.syconx.us2us.core.crypto.AesGcm.open(kek, nonce, aad, vault.copyOfRange(37, vault.size))
        assertArrayEquals(dek.toBytes(), plaintext)
    }

    @Test
    fun `only six ASCII digits are a code, anything else is refused before deriving anything`() {
        val vault = Vault.create("472913", deviceSecret, DataKey.generate(), fast)
        for (input in listOf("", "47291", "4729130", "47291a", " 472913", "472913 ", "４７２９１３", "٤٧٢٩١٣")) {
            assertThrows<MalformedSecretCodeException>(input) { Vault.open(vault, input, deviceSecret) }
            assertThrows<MalformedSecretCodeException>(input) {
                Vault.create(input, deviceSecret, ByteArray(16), fast, ByteArray(12), DataKey.generate())
            }
        }
    }

    @Test
    fun `new codes must follow the rules`() {
        assertEquals(
            SecretCodeProblem.SEQUENCE,
            assertThrows<InvalidSecretCodeException> { Vault.create("123456", deviceSecret, DataKey.generate(), fast) }.problem,
        )
        assertEquals(
            SecretCodeProblem.FORMAT,
            assertThrows<InvalidSecretCodeException> { Vault.create("doğru kod 1903", deviceSecret, DataKey.generate(), fast) }.problem,
        )
        val vault = Vault.create("472913", deviceSecret, DataKey.generate(), fast)
        assertEquals(
            SecretCodeProblem.COMMON,
            assertThrows<InvalidSecretCodeException> { Vault.changeCode(vault, "472913", "121212", deviceSecret, fast) }.problem,
        )
    }

    @Test
    fun `changing the code re-wraps the same DEK`() {
        val dek = DataKey.generate()
        val vault = Vault.create("472913", deviceSecret, dek, fast)
        val changed = Vault.changeCode(vault, "472913", "830561", deviceSecret, fast)
        assertArrayEquals(dek.toBytes(), Vault.open(changed, "830561", deviceSecret).toBytes())
        assertThrows<WrongSecretCodeException> { Vault.open(changed, "472913", deviceSecret) }
        assertThrows<WrongSecretCodeException> { Vault.changeCode(vault, "000001", "830561", deviceSecret, fast) }
    }

    @Test
    fun `tampered or foreign files are rejected`() {
        val vault = Vault.create("472913", deviceSecret, DataKey.generate(), fast)
        // Header bytes are AAD: changing the salt or iteration count breaks authentication.
        for (i in 5 until vault.size) {
            val tampered = vault.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertThrows<Exception>("byte $i") { Vault.open(tampered, "472913", deviceSecret) }
        }
        assertThrows<InvalidVaultFileException> { Vault.open(vault.copyOf(20), "472913", deviceSecret) }
        assertThrows<InvalidVaultFileException> { Vault.open(vault.copyOf().also { it[0] = 'X'.code.toByte() }, "472913", deviceSecret) }
        assertThrows<InvalidVaultFileException> { Vault.open(vault.copyOf().also { it[4] = 2 }, "472913", deviceSecret) }
        // A backup file is not a vault.
        val backup = BackupFile.create("yedek parolası 1", byteArrayOf(1, 2, 3), fast)
        assertThrows<InvalidVaultFileException> { Vault.open(backup, "472913", deviceSecret) }
    }

    @Test
    fun `iteration counts outside 1000 to 10000000 are refused before deriving anything`() {
        val vault = Vault.create("472913", deviceSecret, DataKey.generate(), fast)
        val backup = BackupFile.create("yedek parolası 1", byteArrayOf(1, 2, 3), fast)
        for (iterations in listOf(0, 1, 999, 10_000_001, Int.MAX_VALUE)) {
            assertThrows<InvalidVaultFileException>("$iterations") { Vault.open(vault.withIterations(iterations), "472913", deviceSecret) }
            assertThrows<InvalidVaultFileException>("$iterations") { BackupFile.open(backup.withIterations(iterations), "yedek parolası 1") }
            assertThrows<IllegalArgumentException>("$iterations") { Vault.create("472913", deviceSecret, DataKey.generate(), iterations) }
        }
        // The lower bound itself is allowed: the header parses and only authentication fails.
        assertThrows<WrongSecretCodeException> { Vault.open(vault.withIterations(1000), "472914", deviceSecret) }
        assertThrows<WrongBackupPasswordException> { BackupFile.open(backup.withIterations(1000), "other password") }
    }

    /** The file with its big-endian iteration count (bytes 21..24) replaced. */
    private fun ByteArray.withIterations(iterations: Int): ByteArray =
        copyOf().also { java.nio.ByteBuffer.wrap(it, 21, 4).putInt(iterations) }

    @Test
    fun `derived keys use the spec's HKDF labels`() {
        val dek = DataKey(ByteArray(32) { 0x44 })
        assertArrayEquals(Hkdf.sha256(dek.toBytes(), ByteArray(32), "BRP/db/v1".toByteArray(), 32), dek.databaseKey())
        assertArrayEquals(Hkdf.sha256(dek.toBytes(), ByteArray(32), "BRP/slot/v1".toByteArray(), 32), dek.slotKey())
        assertFalse(dek.databaseKey().contentEquals(dek.slotKey()))
        dek.wipe()
        assertArrayEquals(ByteArray(32), dek.toBytes())
    }

    @Test
    fun `key slots bind the slot id and die with the DEK`() {
        val dek = DataKey.generate()
        val slotId = KeySlot.newSlotId()
        val ck = KeySlot.newConversationKey()
        val slot = KeySlot.create(dek, slotId, ck)
        assertEquals(5 + 12 + 32 + 16, slot.size)
        assertArrayEquals(ck, KeySlot.open(dek, slotId, slot))
        assertThrows<InvalidVaultFileException> { KeySlot.open(dek, KeySlot.newSlotId(), slot) }
        assertThrows<InvalidVaultFileException> { KeySlot.open(DataKey.generate(), slotId, slot) }
        assertThrows<InvalidVaultFileException> { KeySlot.open(dek, slotId, slot.copyOf(slot.size - 1)) }
    }

    @Test
    fun `items bind the item id`() {
        val ck = KeySlot.newConversationKey()
        val id = ByteArray(16) { 1 }
        val item = ItemCipher.seal(ck, id, "mesaj".toByteArray())
        assertEquals(0x01, item[0].toInt())
        assertArrayEquals("mesaj".toByteArray(), ItemCipher.open(ck, id, item))
        assertThrows<ItemDecryptionException> { ItemCipher.open(ck, ByteArray(16) { 2 }, item) }
        assertThrows<ItemDecryptionException> { ItemCipher.open(KeySlot.newConversationKey(), id, item) }
        assertThrows<ItemDecryptionException> { ItemCipher.open(ck, id, item.copyOf().also { it[0] = 2 }) }
        assertArrayEquals(ByteArray(0), ItemCipher.open(ck, id, ItemCipher.seal(ck, id, ByteArray(0))))
    }

    @Test
    fun `backup files open with the backup password alone`() {
        val payload = "AccountBackup bytes".toByteArray()
        val file = BackupFile.create("yedek parolası 42", payload, fast)
        assertArrayEquals("BRPB".toByteArray() + 0x01, file.copyOf(5))
        assertArrayEquals(payload, BackupFile.open(file, "yedek parolası 42"))
        assertThrows<WrongBackupPasswordException> { BackupFile.open(file, "yedek parolası 43") }
    }

    @Test
    fun `the backup key is PBKDF2 over UTF-8(NFKC(password)), untrimmed`() {
        val salt = ByteArray(16) { 6 }
        val nonce = ByteArray(12) { 7 }
        val file = BackupFile.create(" ｙｅｄｅｋ parolası ", salt, fast, nonce, byteArrayOf(1, 2, 3))
        val key = Pbkdf2.hmacSha256(" yedek parolası ".toByteArray(Charsets.UTF_8), salt, fast, 32)
        val plaintext = com.syconx.us2us.core.crypto.AesGcm.open(key, nonce, file.copyOf(25), file.copyOfRange(37, file.size))
        assertArrayEquals(byteArrayOf(1, 2, 3), plaintext)
        assertThrows<WrongBackupPasswordException> { BackupFile.open(file, "yedek parolası") }
    }

    @Test
    fun `backups refuse passwords the rules reject, but always open with theirs`() {
        assertEquals(
            BackupPasswordProblem.TOO_SHORT,
            assertThrows<InvalidBackupPasswordException> { BackupFile.create("472913", byteArrayOf(1), fast) }.problem,
        )
        assertEquals(
            BackupPasswordProblem.COMMON,
            assertThrows<InvalidBackupPasswordException> { BackupFile.create("Password123", byteArrayOf(1), fast) }.problem,
        )
        val file = BackupFile.create("kısa", ByteArray(16), fast, ByteArray(12), byteArrayOf(9))
        assertArrayEquals(byteArrayOf(9), BackupFile.open(file, "kısa"))
    }

    @Test
    fun `device secrets are created once and can be destroyed`() {
        val provider = InMemoryDeviceSecretProvider()
        val first = provider.deviceSecret()
        assertEquals(32, first.size)
        assertArrayEquals(first, provider.deviceSecret())
        provider.destroy()
        assertFalse(first.contentEquals(provider.deviceSecret()))
    }

    @Test
    fun `secret code rules`() {
        for (code in listOf("472913", "830561", "200590", "200000", "012346", "123457", "135792", "000001")) {
            assertNull(SecretCodePolicy.check(code), code)
        }
        for (code in listOf("", "47291", "4729130", " 472913", "47 913", "47291a", "４７２９１３", "٤٧٢٩١٣")) {
            assertEquals(SecretCodeProblem.FORMAT, SecretCodePolicy.check(code), code)
        }
        assertEquals(SecretCodeProblem.REPEATED_DIGIT, SecretCodePolicy.check("000000"))
        assertEquals(SecretCodeProblem.REPEATED_DIGIT, SecretCodePolicy.check("777777"))
        for (code in listOf("123456", "654321", "890123", "321098", "098765", "901234")) {
            assertEquals(SecretCodeProblem.SEQUENCE, SecretCodePolicy.check(code), code)
        }
        for (code in listOf("121212", "112233", "123123", "696969", "111222", "159753", "147258", "102030")) {
            assertEquals(SecretCodeProblem.COMMON, SecretCodePolicy.check(code), code)
        }
    }

    @Test
    fun `exactly 10 repeated and 20 sequence codes exist`() {
        val problems = (0 until 1_000_000).mapNotNull { SecretCodePolicy.check("%06d".format(it)) }.groupingBy { it }.eachCount()
        assertEquals(10, problems[SecretCodeProblem.REPEATED_DIGIT])
        assertEquals(20, problems[SecretCodeProblem.SEQUENCE])
        assertEquals(SecretCodePolicy.commonCodes().size, problems[SecretCodeProblem.COMMON])
    }

    @Test
    fun `the bundled list of common codes is the shared spec file and lists only six-digit codes`() {
        val listed = BundledList.parse(java.io.File(System.getProperty("us2us.specDir"), "common-secret-codes.txt").readLines())
        assertEquals(listed.toSet(), SecretCodePolicy.commonCodes())
        assertEquals(listed.sorted(), listed, "sorted")
        assertEquals(listed.size, listed.toSet().size, "no duplicates")
        for (code in listed) {
            assertTrue(SecretCodePolicy.isWellFormed(code), code)
            // Rules 2 and 3 never apply to a listed code, so the rule order cannot matter.
            assertEquals(SecretCodeProblem.COMMON, SecretCodePolicy.check(code), code)
        }
    }

    @Test
    fun `backup password rules`() {
        assertNull(BackupPasswordPolicy.check("Kırmızı elma 7"))
        assertNull(BackupPasswordPolicy.check("correct horse"))
        assertNull(BackupPasswordPolicy.check("short pass"), "10 code points are enough")
        assertEquals(BackupPasswordProblem.TOO_SHORT, BackupPasswordPolicy.check("short pas"))
        assertEquals(BackupPasswordProblem.TOO_SHORT, BackupPasswordPolicy.check("   abc12345   "))
        assertEquals(BackupPasswordProblem.TOO_SHORT, BackupPasswordPolicy.check("472913"), "a secret code is never a backup password")
        assertEquals(BackupPasswordProblem.TOO_SHORT, BackupPasswordPolicy.check("ｓｈｏｒｔ１２３４"))
        assertEquals(BackupPasswordProblem.COMMON, BackupPasswordPolicy.check("1234567890"))
        assertEquals(BackupPasswordProblem.COMMON, BackupPasswordPolicy.check("  1234567890 "))
        assertEquals(BackupPasswordProblem.COMMON, BackupPasswordPolicy.check("ｐａｓｓｗｏｒｄ１２３")) // NFKC
        assertEquals(BackupPasswordProblem.REPEATED_CHARACTER, BackupPasswordPolicy.check("zzzzzzzzzzzz"))
        assertEquals(BackupPasswordProblem.SEQUENCE, BackupPasswordPolicy.check("abcdefghij"))
        assertEquals(BackupPasswordProblem.SEQUENCE, BackupPasswordPolicy.check("9876543210"))
        assertEquals(BackupPasswordProblem.SEQUENCE, BackupPasswordPolicy.check("POIUYTREWQ"))
        assertEquals(BackupPasswordProblem.SEQUENCE, BackupPasswordPolicy.check("asdfghjklşi"))
    }

    @Test
    fun `backup password rules agree with the iOS app`() {
        // Repetition ignores case.
        assertEquals(BackupPasswordProblem.REPEATED_CHARACTER, BackupPasswordPolicy.check("AAAAAaaaaa"))
        // Turkish F layout rows.
        assertEquals(BackupPasswordProblem.SEQUENCE, BackupPasswordPolicy.check("ieaütkmlyş"))
        assertEquals(BackupPasswordProblem.SEQUENCE, BackupPasswordPolicy.check("wqphnrdoığ"))
        // Trimming uses Unicode White_Space (U+0085 and U+3000 included), as Swift does.
        assertEquals(BackupPasswordProblem.TOO_SHORT, BackupPasswordPolicy.check("\u0085abc123456　"))
        assertEquals(BackupPasswordProblem.COMMON, BackupPasswordPolicy.check("\u00851234567890 "))
        // Lowercasing maps each code point on its own, as Swift does: a final capital sigma stays σ.
        assertEquals(BackupPasswordProblem.REPEATED_CHARACTER, BackupPasswordPolicy.check("ΣΣΣΣΣΣΣΣΣΣ"))
        // U+001C is not White_Space: it is kept and counts towards the length.
        assertEquals(BackupPasswordProblem.TOO_SHORT, BackupPasswordPolicy.check("\u001cabc12345"))
        assertNull(BackupPasswordPolicy.check("\u001cabc123456"))
        // "Simple sequence" is exactly ±1 steps or a row run: steps of 2 or a wrapped row are fine.
        assertNull(BackupPasswordPolicy.check("acegikmoqs"))
        assertNull(BackupPasswordPolicy.check("opqwertyui"))
        assertEquals(BackupPasswordProblem.SEQUENCE, BackupPasswordPolicy.check("ΑΒΓΔΕΖΗΘΙΚ"))
    }

    @Test
    fun `the bundled list of common backup passwords is the shared spec file`() {
        val listed = BundledList.parse(java.io.File(System.getProperty("us2us.specDir"), "common-backup-passwords.txt").readLines())
        assertTrue(listed.size > 50)
        assertEquals(listed.map(BackupPasswordPolicy::comparisonForm).toSet(), BackupPasswordPolicy.commonPasswords())
        for (entry in listed) {
            assertTrue(entry.codePointCount(0, entry.length) >= BackupPasswordPolicy.MIN_LENGTH, entry)
            assertEquals(BackupPasswordProblem.COMMON, BackupPasswordPolicy.check(entry), entry)
        }
    }

    @Test
    fun `wrong attempts are throttled`() {
        assertEquals(Duration.ZERO, UnlockAttemptPolicy.delayAfter(0))
        assertEquals(Duration.ZERO, UnlockAttemptPolicy.delayAfter(2))
        assertEquals(1.minutes, UnlockAttemptPolicy.delayAfter(3))
        assertEquals(5.minutes, UnlockAttemptPolicy.delayAfter(4))
        assertEquals(15.minutes, UnlockAttemptPolicy.delayAfter(5))
        assertEquals(30.minutes, UnlockAttemptPolicy.delayAfter(6))
        assertEquals(1.hours, UnlockAttemptPolicy.delayAfter(7))
        assertEquals(1.hours, UnlockAttemptPolicy.delayAfter(50))
        // The design's sentence after the first wrong code: "2 yanlış daha olursa 1 dakika beklemen gerekir".
        assertEquals(UpcomingDelay(2, 1.minutes), UnlockAttemptPolicy.upcomingDelay(1))
        assertEquals(UpcomingDelay(3, 1.minutes), UnlockAttemptPolicy.upcomingDelay(0))
        assertEquals(UpcomingDelay(1, 5.minutes), UnlockAttemptPolicy.upcomingDelay(3))
        assertEquals(UpcomingDelay(1, 1.hours), UnlockAttemptPolicy.upcomingDelay(40))
        assertFalse(UnlockAttemptPolicy.shouldWipe(10, wipeEnabled = false))
        assertFalse(UnlockAttemptPolicy.shouldWipe(9, wipeEnabled = true))
        assertTrue(UnlockAttemptPolicy.shouldWipe(10, wipeEnabled = true))
    }
}
