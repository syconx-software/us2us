package com.syconx.us2us.core.community

import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.encoding.Base64Url
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.signal.libsignal.protocol.IdentityKeyPair
import java.util.UUID

class CommunityStateTest {
    private val owner = IdentityKeyPair.generate()
    private val ownerId = AccountId(UUID(0, 1))
    private val ownerKey = Base64Url.encode(owner.publicKey.serialize())
    private fun state(members: Int = 1) = CommunityState(AccountId(UUID(1, 0)), 1, "public", ownerId, ownerKey,
        "Us2us Türkçe", (1..members).map { CommunityMember(AccountId(UUID(0, it.toLong())), ownerKey) })

    @Test fun `100 members roundtrip and a 101st is refused`() {
        val full = state(100)
        assertEquals(full, CommunityState.decode(full.encode()))
        assertEquals(full, full.sign(owner).verify(ownerKey))
        assertThrows<IllegalArgumentException> { state(101).encode() }
        assertTrue(full.encode().size < 8192)
    }

    @Test fun `a changed state or signer cannot forge membership`() {
        val state = state(2)
        val signed = state.sign(owner)
        val bytes = Base64Url.decode(signed.state)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        assertThrows<IllegalArgumentException> { SignedCommunityState(Base64Url.encode(bytes), signed.signature).verify(ownerKey) }
        val impostor = IdentityKeyPair.generate()
        assertThrows<IllegalArgumentException> { signed.verify(Base64Url.encode(impostor.publicKey.serialize())) }
        assertThrows<IllegalArgumentException> { state.sign(impostor) }
        assertThrows<Exception> { CommunityState.decode(state.encode() + byteArrayOf(0)) }
    }

    @Test fun `duplicate unsorted ownerless and malformed rosters are refused`() {
        val original = state(3)
        assertThrows<IllegalArgumentException> { original.copy(members = original.members.reversed()).encode() }
        assertThrows<IllegalArgumentException> { original.copy(members = listOf(original.members.first(), original.members.first())).encode() }
        assertThrows<IllegalArgumentException> { original.copy(members = original.members.drop(1)).encode() }
        assertThrows<IllegalArgumentException> { original.copy(revision = 0).encode() }
        assertThrows<IllegalArgumentException> { original.copy(name = "Community\nspoof").encode() }
        assertThrows<IllegalArgumentException> { original.copy(visibility = "private").encode() }
    }

    @Test fun `rollback same revision fork immutable changes and substituted identity are refused`() {
        val first = state(2)
        val next = first.copy(revision = 2, members = state(3).members)
        assertEquals(next, next.sign(owner).verify(ownerKey, first))
        assertThrows<IllegalArgumentException> { first.sign(owner).verify(ownerKey, next) }
        assertThrows<IllegalArgumentException> { first.copy(members = state(3).members).sign(owner).verify(ownerKey, first) }
        assertThrows<IllegalArgumentException> { next.copy(name = "renamed").sign(owner).verify(ownerKey, first) }
        val newKey = Base64Url.encode(IdentityKeyPair.generate().publicKey.serialize())
        val switched = next.copy(members = next.members.map { if (it.accountId == AccountId(UUID(0, 2))) it.copy(identityKey = newKey) else it })
        assertThrows<IllegalArgumentException> { switched.sign(owner).verify(ownerKey, first) }
    }

    @Test fun `names are trimmed and refuse controls and bidi overrides but keep emoji sequences`() {
        assertEquals("Aile 👨‍👩‍👧", CommunityState.normalizedName("  Aile 👨‍👩‍👧\u3000"))
        assertEquals("Kalp ❤️", CommunityState.normalizedName("Kalp ❤️"))
        assertEquals("a\u200Db", CommunityState.normalizedName("a\u200Db"))
        assertEquals("x".repeat(120), CommunityState.normalizedName("x".repeat(120)))
        assertEquals("ş".repeat(60), CommunityState.normalizedName("ş".repeat(60)))
        for (bad in listOf("", "   ", "x".repeat(121), "ş".repeat(61), "a\u0000b", "a\u001Fb", "a\u007Fb", "a\u0085b", "a\u009Fb",
            "a\u061Cb", "a\u200Eb", "a\u200Fb", "a\u202Ab", "a\u202Eb", "a\u2066b", "a\u2069b", "a\uD800b", "a\uDC00",
            "\tKitap", "Kitap\n", "\u001FKitap", "Kitap\u0085")) {
            assertNull(CommunityState.normalizedName(bad), bad)
        }
        assertFalse(CommunityState.validName(" untrimmed"))
        assertEquals("\uFEFFKitap", CommunityState.normalizedName("\u00A0\uFEFFKitap\u2028"))
        assertEquals("", CommunityState.searchPrefix("  "))
        assertNull(CommunityState.searchPrefix("a\u202Eb"))
        assertTrue(CommunityState.validName("Aile 👨‍👩‍👧"))
        assertEquals("Aile 👨‍👩‍👧", CommunityState.decode(state().copy(name = "Aile 👨‍👩‍👧").encode()).name)
        assertThrows<IllegalArgumentException> { state().copy(name = "evil\u202Etxt").encode() }
        assertThrows<IllegalArgumentException> { CommunityInvite(state().id, Base64Url.encode(ByteArray(32)), ownerKey, "a\u2066b").code() }
    }

    @Test fun `private invitation is versioned contains pinned owner and never logs its token`() {
        val token = Base64Url.encode(ByteArray(32) { 42 })
        val invite = CommunityInvite(state().id, token, ownerKey, "Özel topluluk")
        val decoded = CommunityInvite.parse(invite.code())
        assertEquals(token, decoded.token)
        assertEquals(ownerKey, decoded.ownerIdentityKey)
        assertEquals(invite.name, decoded.name)
        assertFalse(invite.toString().contains(token))
        assertThrows<Exception> { CommunityInvite.parse(invite.code().replace(":v1:", ":v2:")) }
        assertThrows<IllegalArgumentException> { CommunityInvite(state().id, "short", ownerKey, "private").code() }
    }
}
