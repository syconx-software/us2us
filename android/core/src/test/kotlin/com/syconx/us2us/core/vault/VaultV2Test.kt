package com.syconx.us2us.core.vault

import com.syconx.us2us.core.crypto.AesGcm
import com.syconx.us2us.core.crypto.Hkdf
import com.syconx.us2us.core.crypto.HmacSha256
import com.syconx.us2us.core.crypto.Pbkdf2
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class VaultV2Test {
    private val code = "482917"
    private val secret = ByteArray(32) { 7 }
    private val dek = DataKey(ByteArray(32) { 8 })

    /** Software key solely for format/negative tests, never production. */
    private class Hardware : VaultHardwareKey {
        val key = ByteArray(32) { 9 }
        var calls = 0
        var response: ((ByteArray) -> ByteArray)? = null
        override val isAvailable = true
        override fun createKey() = Unit
        override fun deleteKey() = Unit
        override fun evaluate(codeKey: ByteArray): ByteArray {
            calls++
            return response?.invoke(codeKey) ?: HmacSha256.compute(key, codeKey)
        }
    }

    @Test
    fun `v2 follows the specified Android HMAC and HKDF with an authenticated version`() {
        val hardware = Hardware()
        val salt = ByteArray(16) { 3 }
        val nonce = ByteArray(12) { 4 }
        val vault = Vault.create(code, secret, salt, 1000, nonce, dek, hardware)
        assertEquals(2, hardware.calls)
        assertEquals(2, Vault.version(vault))
        val codeKey = Pbkdf2.hmacSha256(code.toByteArray(), salt, 1000, 32)
        val hw = HmacSha256.compute(hardware.key, codeKey)
        val kek = Hkdf.sha256(codeKey + secret + hw, salt, "BRP/vault/v2".toByteArray(), 32)
        assertArrayEquals(dek.toBytes(), AesGcm.open(kek, nonce, vault.copyOf(25), vault.copyOfRange(37, vault.size)))
        assertArrayEquals(dek.toBytes(), Vault.open(vault, code, secret, hardware).toBytes())
        assertEquals(3, hardware.calls)
        assertThrows<WrongSecretCodeException> { Vault.open(vault, code, secret, Hardware().also { it.key.fill(2) }) }
        assertThrows<WrongSecretCodeException> { Vault.open(vault.copyOf().also { it[4] = 1 }, code, secret) }
    }

    @Test
    fun `every wrong PIN needs hardware and missing hardware never becomes a PIN failure`() {
        val hardware = Hardware()
        val vault = Vault.create(code, secret, dek, 1000, hardware)
        assertThrows<WrongSecretCodeException> { Vault.open(vault, "730415", secret, hardware) }
        assertEquals(3, hardware.calls)
        assertThrows<VaultHardwareKeyMissingException> { Vault.open(vault, code, secret) }
        hardware.response = { throw VaultHardwareKeyMissingException() }
        assertThrows<VaultHardwareKeyMissingException> { Vault.open(vault, code, secret, hardware) }
    }

    @Test
    fun `unreproducible and malformed hardware output cannot seal a vault`() {
        val hardware = Hardware().also { hw -> hw.response = { ByteArray(32) { hw.calls.toByte() } } }
        assertThrows<VaultHardwareKeyException> { Vault.create(code, secret, dek, 1000, hardware) }
        for (length in listOf(0, 31, 33)) {
            hardware.response = { ByteArray(length) }
            assertThrows<VaultHardwareKeyException> { Vault.create(code, secret, dek, 1000, hardware) }
        }
    }

    @Test
    fun `malformed headers and input are refused without evaluating hardware`() {
        val hardware = Hardware()
        val vault = Vault.create(code, secret, dek, 1000, hardware)
        hardware.calls = 0
        val invalid = listOf(vault.copyOf(40), vault.copyOf().also { it[4] = 3 }, vault.copyOf().also { it[21] = 127 })
        for (file in invalid) assertThrows<InvalidVaultFileException> { Vault.open(file, code, secret, hardware) }
        assertThrows<MalformedSecretCodeException> { Vault.open(vault, "１２３４５６", secret, hardware) }
        assertEquals(0, hardware.calls)
    }

    @Test
    fun `v1 ignores supplied hardware and backup format remains v1 only`() {
        val hardware = Hardware().also { it.response = { throw AssertionError("v1 called hardware") } }
        val vault = Vault.create(code, secret, dek, 1000)
        assertArrayEquals(dek.toBytes(), Vault.open(vault, code, secret, hardware).toBytes())
        val backup = BackupFile.create("yedek parolası 42", byteArrayOf(1), 1000)
        assertThrows<InvalidVaultFileException> { BackupFile.open(backup.copyOf().also { it[4] = 2 }, "yedek parolası 42") }
    }

    @Test
    fun `changing PIN preserves v2 and the same data key`() {
        val hardware = Hardware()
        val vault = Vault.create(code, secret, dek, 1000, hardware)
        val changed = Vault.changeCode(vault, code, "730415", secret, 1000, hardware)
        assertEquals(2, Vault.version(changed))
        assertArrayEquals(dek.databaseKey(), Vault.open(changed, "730415", secret, hardware).databaseKey())
        assertThrows<WrongSecretCodeException> { Vault.open(changed, code, secret, hardware) }
    }
}
