package com.syconx.us2us.core.community

import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.encoding.Base64Url
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Domain-separated, owner-signed roster. The wire format is docs/COMMUNITIES.md. */
data class CommunityMember(val accountId: AccountId, val identityKey: String) {
    fun keyBytes(): ByteArray = Base64Url.decode(identityKey, 33).also { require(it[0] == 5.toByte()) }
}

data class CommunityState(
    val id: AccountId,
    val revision: Long,
    val visibility: String,
    val ownerAccountId: AccountId,
    val ownerIdentityKey: String,
    val name: String,
    val members: List<CommunityMember>,
) {
    fun member(id: AccountId): CommunityMember? = members.firstOrNull { it.accountId == id }
    fun encode(): ByteArray {
        require(revision in 1..MAX_REVISION && visibility in setOf("public", "private"))
        require(members.size in 1..MAX_MEMBERS)
        require(members == members.sortedBy { it.accountId.toString() } && members.map { it.accountId }.distinct().size == members.size)
        require(member(ownerAccountId)?.identityKey == ownerIdentityKey)
        require(if (visibility == "private") name.isEmpty() else validName(name))
        val ownerKey = CommunityMember(ownerAccountId, ownerIdentityKey).keyBytes()
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.write(MAGIC)
            data.write(id.toBytes())
            data.writeLong(revision)
            data.writeByte(if (visibility == "public") 1 else 0)
            data.write(ownerAccountId.toBytes())
            data.write(ownerKey)
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            data.writeShort(nameBytes.size)
            data.write(nameBytes)
            data.writeShort(members.size)
            for (member in members) { data.write(member.accountId.toBytes()); data.write(member.keyBytes()) }
        }
        return output.toByteArray().also { require(it.size <= 8192) }
    }

    /** Remote revisions may skip while offline, but cannot roll back or substitute an existing identity. */
    fun checkSuccessor(previous: CommunityState) {
        require(id == previous.id && ownerAccountId == previous.ownerAccountId && ownerIdentityKey == previous.ownerIdentityKey)
        require(visibility == previous.visibility && name == previous.name && revision >= previous.revision)
        if (revision == previous.revision) require(encode().contentEquals(previous.encode()))
        for (old in previous.members) member(old.accountId)?.let { require(it.identityKey == old.identityKey) }
    }

    fun sign(identity: IdentityKeyPair): SignedCommunityState {
        require(Base64Url.encode(identity.publicKey.serialize()) == ownerIdentityKey)
        val bytes = encode()
        return SignedCommunityState(Base64Url.encode(bytes), Base64Url.encode(identity.privateKey.calculateSignature(bytes)))
    }

    companion object {
        const val MAX_MEMBERS = 100
        const val MAX_REVISION = 9_007_199_254_740_991L
        const val MAX_TEXT_BYTES = 8192
        private val MAGIC = "BRP/CommunityState/v1\u0000".toByteArray(Charsets.US_ASCII)
        const val MAX_NAME_BYTES = 120

        /**
         * A public name, or the name carried in an invitation (COMMUNITIES.md, Names): already
         * canonical (nothing of [trimName]'s set at either end), 1..120 UTF-8 bytes, well-formed,
         * and free of C0/C1 controls and of the bidirectional marks, embeddings, overrides and
         * isolates that could reorder what is shown around it. Joiners, variation selectors and
         * emoji sequences are allowed; nothing is normalized.
         */
        fun validName(value: String): Boolean {
            if (value.isEmpty() || value != trimName(value)) return false
            var index = 0
            var bytes = 0
            while (index < value.length) {
                val cp = value.codePointAt(index)
                if (Character.isSurrogate(value[index]) && Character.charCount(cp) == 1) return false
                if (isForbiddenInName(cp)) return false
                bytes += when { cp < 0x80 -> 1; cp < 0x800 -> 2; cp < 0x10000 -> 3; else -> 4 }
                index += Character.charCount(cp)
            }
            return bytes <= MAX_NAME_BYTES
        }

        /** The canonical form of what a name field holds, or `null` if that is not a [validName]. */
        fun normalizedName(input: String): String? = trimName(input).takeIf(::validName)

        /** A directory search prefix: the name rule, except that it may be empty. */
        fun searchPrefix(input: String): String? = trimName(input).let { if (it.isEmpty()) it else normalizedName(it) }

        /**
         * Trims exactly Unicode `White_Space` without its controls, at both ends. Not a platform
         * trim: TAB, LF, U+001C..U+001F, U+0085 and U+FEFF stay, and the rule then refuses the
         * controls among them, as the relay and the iOS app do.
         */
        fun trimName(text: String): String {
            var start = 0
            var end = text.length
            while (start < end) {
                val cp = text.codePointAt(start)
                if (!isNameSpace(cp)) break
                start += Character.charCount(cp)
            }
            while (end > start) {
                val cp = text.codePointBefore(end)
                if (!isNameSpace(cp)) break
                end -= Character.charCount(cp)
            }
            return text.substring(start, end)
        }

        private fun isNameSpace(cp: Int): Boolean = when (cp) {
            0x0020, 0x00A0, 0x1680, in 0x2000..0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000 -> true
            else -> false
        }

        private fun isForbiddenInName(cp: Int): Boolean = when (cp) {
            in 0x0000..0x001F, in 0x007F..0x009F, 0x061C, 0x200E, 0x200F, in 0x202A..0x202E, in 0x2066..0x2069 -> true
            else -> false
        }

        fun decode(bytes: ByteArray): CommunityState {
            require(bytes.size <= 8192)
            val input = DataInputStream(ByteArrayInputStream(bytes))
            fun read(size: Int): ByteArray = ByteArray(size).also(input::readFully)
            require(read(MAGIC.size).contentEquals(MAGIC))
            val id = AccountId.fromBytes(read(16))
            val revision = input.readLong()
            val visibility = when (input.readUnsignedByte()) { 0 -> "private"; 1 -> "public"; else -> throw IllegalArgumentException("invalid visibility") }
            val owner = AccountId.fromBytes(read(16))
            val ownerKey = Base64Url.encode(read(33))
            val nameLength = input.readUnsignedShort().also { require(it <= MAX_NAME_BYTES) }
            val name = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(read(nameLength))).toString()
            val count = input.readUnsignedShort().also { require(it in 1..MAX_MEMBERS) }
            val members = List(count) { CommunityMember(AccountId.fromBytes(read(16)), Base64Url.encode(read(33))) }
            require(input.available() == 0)
            return CommunityState(id, revision, visibility, owner, ownerKey, name, members).also { require(it.encode().contentEquals(bytes)) }
        }
    }
}

@Serializable
data class SignedCommunityState(val state: String, val signature: String) {
    fun verify(ownerKey: String? = null, previous: CommunityState? = null): CommunityState {
        val bytes = Base64Url.decode(state)
        require(Base64Url.encode(bytes) == state) { "noncanonical community state" }
        val signatureBytes = Base64Url.decode(signature, 64)
        require(Base64Url.encode(signatureBytes) == signature) { "noncanonical community signature" }
        val decoded = try { CommunityState.decode(bytes) } catch (e: java.io.IOException) {
            throw IllegalArgumentException("malformed community state", e)
        }
        require(ownerKey == null || ownerKey == decoded.ownerIdentityKey) { "community owner identity changed" }
        val valid = try { IdentityKey(Base64Url.decode(decoded.ownerIdentityKey, 33)).publicKey.verifySignature(bytes, signatureBytes) }
        catch (e: org.signal.libsignal.protocol.InvalidKeyException) { throw IllegalArgumentException("invalid community owner key", e) }
        require(valid) { "invalid community signature" }
        if (previous != null) decoded.checkSuccessor(previous)
        return decoded
    }
}

/** The token never appears in a URL, logs or analytics. */
@Serializable
class CommunityInvite(val communityId: AccountId, val token: String, val ownerIdentityKey: String, val name: String = "") {
    /** The name may be absent (empty); one that is present is canonical. */
    fun validate() {
        Base64Url.decode(token, 32)
        CommunityMember(communityId, ownerIdentityKey).keyBytes()
        require(name.isEmpty() || CommunityState.validName(name))
    }
    fun code(): String {
        validate()
        return PREFIX + Base64Url.encode(Json.encodeToString(this).toByteArray(Charsets.UTF_8))
    }
    override fun toString(): String = "CommunityInvite(redacted)"
    companion object {
        private const val PREFIX = "us2us-community:v1:"
        fun parse(code: String): CommunityInvite {
            require(code.length <= 2048 && code.startsWith(PREFIX))
            return Json.decodeFromString<CommunityInvite>(String(Base64Url.decode(code.removePrefix(PREFIX)), Charsets.UTF_8)).also { it.validate() }
        }
    }
}
