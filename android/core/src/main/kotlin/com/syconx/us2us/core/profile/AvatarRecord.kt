package com.syconx.us2us.core.profile

import com.google.protobuf.InvalidProtocolBufferException
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.crypto.AesGcm
import com.syconx.us2us.core.crypto.Hkdf
import com.syconx.us2us.core.crypto.Pbkdf2
import com.syconx.us2us.core.crypto.Random
import com.syconx.us2us.core.proto.ProfileAvatar
import com.syconx.us2us.core.username.Username
import javax.crypto.AEADBadTagException

/**
 * The key of an account's avatar record for search (PROTOCOL.md §8.10):
 *
 * ```
 * seed      = PBKDF2-HMAC-SHA256(UTF-8(normalizedUsername), "BRP/avatar/v1", 100000, 32)
 * avatarKey = HKDF-SHA256(ikm seed, salt accountId (16 raw bytes), info "BRP/avatar/key/v1", 32)
 * ```
 *
 * The PBKDF2 salt is not the username hash's (`BRP/username/v1`, §3.2), so the relay cannot compute
 * [seed] from the hash it stores; it needs the username itself (or a guess of it, §9). The account
 * id binds the key, and through the AAD every record, to one account.
 */
class AvatarKey private constructor(private val key: ByteArray, val accountId: AccountId) {
    internal fun bytes(): ByteArray = key.copyOf()

    override fun toString(): String = "AvatarKey($accountId)"

    companion object {
        const val SEED_ITERATIONS = 100_000
        const val LENGTH = 32

        private val SEED_SALT = "BRP/avatar/v1".toByteArray(Charsets.UTF_8)
        private val KEY_INFO = "BRP/avatar/key/v1".toByteArray(Charsets.UTF_8)

        /**
         * The username's 32-byte `seed`. As slow as [Username.hash] by design: run it off the main
         * thread and keep the result in memory only, per username.
         */
        fun seed(username: Username): ByteArray =
            Pbkdf2.hmacSha256(username.value.toByteArray(Charsets.UTF_8), SEED_SALT, SEED_ITERATIONS, LENGTH)

        /** The account's avatar key from a [seed] of its current username. */
        fun derive(seed: ByteArray, accountId: AccountId): AvatarKey {
            require(seed.size == LENGTH) { "seed must be $LENGTH bytes" }
            return AvatarKey(Hkdf.sha256(seed, accountId.toBytes(), KEY_INFO, LENGTH), accountId)
        }

        fun derive(username: Username, accountId: AccountId): AvatarKey = derive(seed(username), accountId)
    }
}

/** A record that is not one of [AvatarRecord]'s, or does not open: shown as the initial letter (§8.10). */
class InvalidAvatarRecordException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The avatar record an account keeps on the relay while "Show in search" is on (PROTOCOL.md §4.12,
 * §8.10):
 *
 * ```
 * header    = "BRPA" ‖ 0x01 ‖ uint64_be(version)                      // 13 bytes
 * plaintext = ProfileAvatar protobuf ‖ 0x80 ‖ 0x00 * k                // exactly len(record) − 41 bytes
 * record    = header ‖ nonce(12) ‖ AES-256-GCM(avatarKey, nonce, aad = header ‖ accountId, plaintext) ‖ tag(16)
 * ```
 *
 * A record is 8192, 16384, 32768 or 49152 bytes long, the smallest that fits, so the relay learns
 * only which of the four it is.
 */
object AvatarRecord {
    const val HEADER_LENGTH = 13
    const val NONCE_LENGTH = AesGcm.NONCE_LENGTH
    const val TAG_LENGTH = AesGcm.TAG_LENGTH

    /** Header, nonce and tag: everything of a record that is not plaintext. */
    const val OVERHEAD = HEADER_LENGTH + NONCE_LENGTH + TAG_LENGTH

    /** The four record lengths, smallest first. */
    val LENGTHS: List<Int> = listOf(8192, 16384, 32768, 49152)

    const val MAX_LENGTH = 49152

    /** The largest serialized `ProfileAvatar` a record holds: 49110 bytes (one byte goes to the `0x80`). */
    const val MAX_PROFILE_AVATAR_BYTES = MAX_LENGTH - OVERHEAD - 1

    private val MAGIC = byteArrayOf('B'.code.toByte(), 'R'.code.toByte(), 'P'.code.toByte(), 'A'.code.toByte())
    private const val FORMAT: Byte = 0x01
    private const val MARKER: Byte = 0x80.toByte()

    /** The record length for a `ProfileAvatar` of [size] bytes, or `null` above [MAX_PROFILE_AVATAR_BYTES]. */
    fun lengthFor(size: Int): Int? = LENGTHS.firstOrNull { size >= 0 && size + OVERHEAD + 1 <= it }

    /** Seals [avatar] under [key] with [version] (1..2^63 − 1) and a fresh random nonce. */
    fun seal(avatar: ProfileAvatar, version: Long, key: AvatarKey): ByteArray = seal(avatar.toByteArray(), version, key)

    /**
     * Seals the serialized `ProfileAvatar` [profileAvatar] with a fresh random nonce.
     *
     * @throws IllegalArgumentException for a version outside 1..2^63 − 1 or a `ProfileAvatar` over
     *   [MAX_PROFILE_AVATAR_BYTES].
     */
    fun seal(profileAvatar: ByteArray, version: Long, key: AvatarKey): ByteArray =
        seal(profileAvatar, version, key, Random.bytes(NONCE_LENGTH))

    /**
     * [seal] with a given [nonce], for the test vectors only: a nonce used twice under one key (one
     * username of one account) breaks AES-GCM, so this is not part of the public API.
     *
     * @throws IllegalArgumentException as [seal], or for a nonce that is not 12 bytes.
     */
    internal fun seal(profileAvatar: ByteArray, version: Long, key: AvatarKey, nonce: ByteArray): ByteArray {
        require(version >= 1) { "avatar record versions are 1..2^63 - 1" }
        require(nonce.size == NONCE_LENGTH) { "nonce must be $NONCE_LENGTH bytes" }
        val length = requireNotNull(lengthFor(profileAvatar.size)) { "ProfileAvatar exceeds $MAX_PROFILE_AVATAR_BYTES bytes" }
        val header = header(version)
        val plaintext = profileAvatar.copyOf(length - OVERHEAD).also { it[profileAvatar.size] = MARKER }
        val sealed = AesGcm.seal(key.bytes(), nonce, header + key.accountId.toBytes(), plaintext)
        return header + nonce + sealed
    }

    /**
     * The version in [record]'s header, or `null` if its length or header is not a record's.
     * Unauthenticated: trust it only after [open] succeeded.
     */
    fun version(record: ByteArray): Long? {
        if (record.size !in LENGTHS) return null
        for (i in MAGIC.indices) if (record[i] != MAGIC[i]) return null
        if (record[MAGIC.size] != FORMAT) return null
        var version = 0L
        for (i in MAGIC.size + 1 until HEADER_LENGTH) version = (version shl 8) or (record[i].toLong() and 0xff)
        return version.takeIf { it >= 1 } // The top bit set reads as negative: 2^63 and above are not versions.
    }

    /**
     * Opens [record] with [key] and returns the serialized `ProfileAvatar` exactly as sealed. The
     * length and header are checked before anything else.
     *
     * @throws InvalidAvatarRecordException for a wrong length or header, a GCM failure (another
     *   username or account, or tampering) or bad padding.
     */
    fun open(record: ByteArray, key: AvatarKey): ByteArray {
        version(record) ?: throw InvalidAvatarRecordException("not an avatar record")
        val header = record.copyOfRange(0, HEADER_LENGTH)
        val nonce = record.copyOfRange(HEADER_LENGTH, HEADER_LENGTH + NONCE_LENGTH)
        val plaintext = try {
            AesGcm.open(key.bytes(), nonce, header + key.accountId.toBytes(), record.copyOfRange(HEADER_LENGTH + NONCE_LENGTH, record.size))
        } catch (e: AEADBadTagException) {
            throw InvalidAvatarRecordException("avatar record does not open", e)
        }
        var end = plaintext.size - 1
        while (end >= 0 && plaintext[end] == 0.toByte()) end--
        if (end < 0 || plaintext[end] != MARKER) throw InvalidAvatarRecordException("bad avatar record padding")
        return plaintext.copyOf(end)
    }

    /**
     * [open], then parses the `ProfileAvatar`. Validating it (§8.9) is [ProfilePicture.fromProto]'s job.
     *
     * @throws InvalidAvatarRecordException as [open], or if the protobuf does not parse.
     */
    fun openAvatar(record: ByteArray, key: AvatarKey): ProfileAvatar = try {
        ProfileAvatar.parseFrom(open(record, key))
    } catch (e: InvalidProtocolBufferException) {
        throw InvalidAvatarRecordException("avatar record holds no ProfileAvatar", e)
    }

    private fun header(version: Long): ByteArray = ByteArray(HEADER_LENGTH).also { header ->
        MAGIC.copyInto(header)
        header[MAGIC.size] = FORMAT
        for (i in 0 until 8) header[HEADER_LENGTH - 1 - i] = (version ushr (8 * i)).toByte()
    }
}
