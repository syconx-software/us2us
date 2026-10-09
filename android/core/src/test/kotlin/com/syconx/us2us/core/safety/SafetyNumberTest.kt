package com.syconx.us2us.core.safety

import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.keys.Keys
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class SafetyNumberTest {
    private val aliceId = AccountId(UUID.randomUUID())
    private val bobId = AccountId(UUID.randomUUID())
    private val aliceKey = Keys.generateIdentityKeyPair().publicKey
    private val bobKey = Keys.generateIdentityKeyPair().publicKey

    @Test
    fun `both sides see the same 60 digits and accept each other's QR code`() {
        val atAlice = SafetyNumber.compute(aliceId, aliceKey, bobId, bobKey)
        val atBob = SafetyNumber.compute(bobId, bobKey, aliceId, aliceKey)
        assertEquals(60, atAlice.digits.length)
        assertTrue(atAlice.digits.all(Char::isDigit))
        assertEquals(atAlice.digits, atBob.digits)
        assertEquals(12, atAlice.groups.size)
        assertTrue(atAlice.matchesScanned(atBob.scannable))
        assertTrue(atBob.matchesScanned(atAlice.scannable))
    }

    @Test
    fun `a different key or garbage does not match`() {
        val real = SafetyNumber.compute(aliceId, aliceKey, bobId, bobKey)
        val mitm = SafetyNumber.compute(bobId, Keys.generateIdentityKeyPair().publicKey, aliceId, aliceKey)
        assertNotEquals(real.digits, mitm.digits)
        assertFalse(real.matchesScanned(mitm.scannable))
        assertFalse(real.matchesScanned(byteArrayOf(1, 2, 3)))
    }
}
