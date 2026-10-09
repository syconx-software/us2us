package com.syconx.us2us.core.attachment

import com.syconx.us2us.core.crypto.ConstantTime
import com.syconx.us2us.core.crypto.HmacSha256
import com.syconx.us2us.core.crypto.Random
import com.syconx.us2us.core.crypto.Sha256
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The blob's digest or MAC does not match (nothing was decrypted), or the authenticated plaintext is
 * malformed (discard whatever was written). Either way the attachment must not be shown.
 */
class AttachmentIntegrityException(message: String) : Exception(message)

/** What the sender puts into the `Attachment` message besides the relay's media id. */
class EncryptedAttachment(
    /** `SHA-256(blob)`. */
    val digest: ByteArray,
    /** Real plaintext size, before padding. */
    val plaintextSize: Long,
    val blobSize: Long,
)

/** Something that can be read more than once: verification and decryption are separate passes. */
fun interface BlobSource {
    fun open(): InputStream
}

/**
 * Attachment encryption (PROTOCOL.md §7):
 *
 * ```
 * padded = plaintext ‖ 0x00 * (P(n) − n)
 * blob   = iv ‖ AES-256-CBC-PKCS7(aesKey, iv, padded) ‖ HMAC-SHA256(macKey, iv ‖ ciphertext)
 * digest = SHA-256(blob)
 * ```
 *
 * Everything streams, so large videos never need to fit in memory.
 */
object AttachmentCipher {
    const val KEY_LENGTH = 64
    const val IV_LENGTH = 16
    const val MAC_LENGTH = 32
    private const val AES_BLOCK = 16
    private const val BUFFER_SIZE = 64 * 1024

    /** 64 random bytes: AES-256 key ‖ HMAC-SHA256 key. */
    fun generateKey(): ByteArray = Random.bytes(KEY_LENGTH)

    /**
     * Largest plaintext this implementation encrypts or accepts. Far above any relay limit, it
     * only keeps `P(n)` and [blobSize] free of overflow for a hostile `Attachment.size`.
     */
    const val MAX_PLAINTEXT_SIZE = 1L shl 40

    /** `P(n)`: the padded plaintext size, which hides the exact size from the relay. */
    fun paddedSize(size: Long): Long {
        require(size in 0..MAX_PLAINTEXT_SIZE) { "size must be in 0..$MAX_PLAINTEXT_SIZE" }
        var s = MIN_PADDED_SIZE
        while (s < size) s += maxOf(1L, s / 20)
        return s
    }

    /** Size of the uploaded blob for a plaintext of [plaintextSize] bytes. */
    fun blobSize(plaintextSize: Long): Long =
        IV_LENGTH + (paddedSize(plaintextSize) / AES_BLOCK + 1) * AES_BLOCK + MAC_LENGTH

    /**
     * Encrypts [plaintext] into [blob]. The size is counted while streaming, so the input need not
     * have a known length. [iv] is random unless a test supplies one.
     */
    fun encrypt(plaintext: InputStream, key: ByteArray, blob: OutputStream, iv: ByteArray = Random.bytes(IV_LENGTH)): EncryptedAttachment {
        require(key.size == KEY_LENGTH) { "attachment key must be $KEY_LENGTH bytes" }
        require(iv.size == IV_LENGTH) { "IV must be $IV_LENGTH bytes" }
        val cipher = aes(Cipher.ENCRYPT_MODE, key, iv)
        val mac = HmacSha256.mac(key.copyOfRange(32, 64))
        val digest = MessageDigest.getInstance("SHA-256")
        val out = DigestingOutputStream(blob, digest)

        mac.update(iv)
        out.write(iv)
        fun emit(chunk: ByteArray?) {
            if (chunk == null || chunk.isEmpty()) return
            mac.update(chunk)
            out.write(chunk)
        }

        val buffer = ByteArray(BUFFER_SIZE)
        var size = 0L
        while (true) {
            val n = plaintext.read(buffer)
            if (n < 0) break
            size += n
            require(size <= MAX_PLAINTEXT_SIZE) { "attachment exceeds $MAX_PLAINTEXT_SIZE bytes" }
            emit(cipher.update(buffer, 0, n))
        }
        var padding = paddedSize(size) - size
        buffer.fill(0)
        while (padding > 0) {
            val n = minOf(padding, buffer.size.toLong()).toInt()
            emit(cipher.update(buffer, 0, n))
            padding -= n
        }
        emit(cipher.doFinal())
        out.write(mac.doFinal())
        out.flush()
        return EncryptedAttachment(digest.digest(), size, out.count)
    }

    fun encrypt(plaintext: ByteArray, key: ByteArray, iv: ByteArray = Random.bytes(IV_LENGTH)): Pair<ByteArray, EncryptedAttachment> {
        val blob = ByteArrayOutputStream(blobSize(plaintext.size.toLong()).toInt())
        val info = encrypt(ByteArrayInputStream(plaintext), key, blob, iv)
        return blob.toByteArray() to info
    }

    /**
     * Checks [digest] and the HMAC of a blob (both in constant time) without decrypting anything.
     *
     * @throws AttachmentIntegrityException if either does not match.
     */
    fun verify(blob: InputStream, key: ByteArray, digest: ByteArray) {
        require(key.size == KEY_LENGTH) { "attachment key must be $KEY_LENGTH bytes" }
        val sha = MessageDigest.getInstance("SHA-256")
        val mac = HmacSha256.mac(key.copyOfRange(32, 64))
        val body = TrailerSplitter(DigestInputStream(blob, sha), MAC_LENGTH)
        val buffer = ByteArray(BUFFER_SIZE)
        var macInput = 0L
        while (true) {
            val n = body.read(buffer)
            if (n < 0) break
            mac.update(buffer, 0, n)
            macInput += n
        }
        val trailer = body.trailer()
        if (trailer == null || macInput < IV_LENGTH + AES_BLOCK || (macInput - IV_LENGTH) % AES_BLOCK != 0L) {
            throw AttachmentIntegrityException("blob has an impossible length")
        }
        if (!ConstantTime.equals(sha.digest(), digest)) throw AttachmentIntegrityException("digest mismatch")
        if (!ConstantTime.equals(mac.doFinal(), trailer)) throw AttachmentIntegrityException("MAC mismatch")
    }

    /**
     * Verifies, then decrypts a blob, writing exactly [size] plaintext bytes to [plaintext].
     *
     * @throws AttachmentIntegrityException if the digest or MAC does not match, or [size] exceeds
     *   the padded plaintext.
     */
    fun decrypt(blob: BlobSource, key: ByteArray, digest: ByteArray, size: Long, plaintext: OutputStream) {
        require(size >= 0) { "size must not be negative" }
        blob.open().use { verify(it, key, digest) }
        blob.open().use { input ->
            val body = TrailerSplitter(input, MAC_LENGTH)
            val cipher = aes(Cipher.DECRYPT_MODE, key, body.readExactly(IV_LENGTH))
            var remaining = size
            fun emit(chunk: ByteArray?) {
                if (chunk == null || remaining <= 0) return
                val n = minOf(remaining, chunk.size.toLong()).toInt()
                plaintext.write(chunk, 0, n)
                remaining -= n
            }
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val n = body.read(buffer)
                if (n < 0) break
                emit(cipher.update(buffer, 0, n))
            }
            emit(
                try {
                    cipher.doFinal()
                } catch (_: BadPaddingException) {
                    throw AttachmentIntegrityException("bad PKCS#7 padding")
                },
            )
            if (remaining > 0) throw AttachmentIntegrityException("attachment shorter than its declared size")
            plaintext.flush()
        }
    }

    fun decrypt(blob: ByteArray, key: ByteArray, digest: ByteArray, size: Long): ByteArray {
        // The plaintext is never longer than the blob, whatever the (untrusted) declared size says.
        val out = ByteArrayOutputStream(minOf(size, blob.size.toLong()).coerceAtLeast(0).toInt())
        decrypt({ ByteArrayInputStream(blob) }, key, digest, size, out)
        return out.toByteArray()
    }

    fun decrypt(blob: File, key: ByteArray, digest: ByteArray, size: Long, plaintext: OutputStream) =
        decrypt({ blob.inputStream() }, key, digest, size, plaintext)

    /** SHA-256 of [bytes], e.g. to compare a decrypted attachment with what was sent. */
    fun sha256(bytes: ByteArray): ByteArray = Sha256.digest(bytes)

    private const val MIN_PADDED_SIZE = 541L

    private fun aes(mode: Int, key: ByteArray, iv: ByteArray): Cipher =
        Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(mode, SecretKeySpec(key, 0, 32, "AES"), IvParameterSpec(iv))
        }
}

/** Counts and hashes everything written through it. */
private class DigestingOutputStream(out: OutputStream, private val digest: MessageDigest) : FilterOutputStream(out) {
    var count = 0L
        private set

    override fun write(b: Int) {
        digest.update(b.toByte())
        count++
        out.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        digest.update(b, off, len)
        count += len
        out.write(b, off, len)
    }
}

/**
 * Yields every byte of [input] except the last [trailerLength], which become available from
 * [trailer] once the stream is exhausted. Lets one pass both MAC the body and read the MAC.
 */
private class TrailerSplitter(private val input: InputStream, private val trailerLength: Int) : InputStream() {
    private val window = ByteArray(trailerLength + 64 * 1024)
    private var filled = 0
    private var eof = false

    /** The trailing bytes, or `null` if the stream was shorter than the trailer. Call after EOF. */
    fun trailer(): ByteArray? {
        check(eof) { "stream not exhausted" }
        return if (filled == trailerLength) window.copyOf(trailerLength) else null
    }

    fun readExactly(count: Int): ByteArray {
        val out = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = read(out, read, count - read)
            if (n < 0) throw AttachmentIntegrityException("blob too short")
            read += n
        }
        return out
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (!eof && filled <= trailerLength) {
            val n = input.read(window, filled, window.size - filled)
            if (n < 0) eof = true else filled += n
        }
        val available = filled - trailerLength
        if (available <= 0) return -1
        val n = minOf(len, available)
        window.copyInto(b, off, 0, n)
        window.copyInto(window, 0, n, filled)
        filled -= n
        return n
    }
}
