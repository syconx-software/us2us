package com.syconx.us2us.core.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * Thin wrappers over platform crypto (javax.crypto / java.security), available identically on the
 * desktop JVM and on every Android API level the app supports. Only standard constructions are
 * implemented here; protocol formats live next to their spec sections.
 */

internal object Random {
    private val secureRandom = SecureRandom()

    fun bytes(count: Int): ByteArray = ByteArray(count).also(secureRandom::nextBytes)
}

internal object Sha256 {
    const val LENGTH = 32

    fun digest(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach(md::update)
        return md.digest()
    }
}

internal object HmacSha256 {
    const val ALGORITHM = "HmacSHA256"
    const val LENGTH = 32

    fun mac(key: ByteArray): Mac = Mac.getInstance(ALGORITHM).apply { init(SecretKeySpec(key, ALGORITHM)) }

    fun compute(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = mac(key)
        parts.forEach(mac::update)
        return mac.doFinal()
    }
}

/** Comparison whose running time does not depend on where two equal-length inputs differ. */
internal object ConstantTime {
    fun equals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }
}

/**
 * PBKDF2-HMAC-SHA256 (RFC 8018 §5.2) over raw password bytes.
 *
 * Implemented on [Mac] instead of `SecretKeyFactory("PBKDF2WithHmacSHA256")` for two reasons: the
 * factory takes a `char[]` and encodes it to bytes in a provider-specific way (UTF-8 on the JDK,
 * historically not on Android), and it is missing on older Android releases. Callers pass the exact
 * UTF-8 bytes the spec mandates.
 */
internal object Pbkdf2 {
    fun hmacSha256(password: ByteArray, salt: ByteArray, iterations: Int, keyLength: Int): ByteArray {
        require(password.isNotEmpty()) { "password must not be empty" }
        require(iterations >= 1) { "iterations must be positive" }
        require(keyLength >= 1) { "key length must be positive" }

        val mac = HmacSha256.mac(password)
        val output = ByteArray(keyLength)
        val u = ByteArray(HmacSha256.LENGTH)
        val t = ByteArray(HmacSha256.LENGTH)
        var block = 1
        var offset = 0
        while (offset < keyLength) {
            mac.update(salt)
            mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            mac.doFinal(u, 0)
            u.copyInto(t)
            repeat(iterations - 1) {
                mac.update(u)
                mac.doFinal(u, 0)
                for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
            }
            val n = minOf(t.size, keyLength - offset)
            t.copyInto(output, offset, 0, n)
            offset += n
            block++
        }
        u.fill(0)
        t.fill(0)
        return output
    }
}

/** HKDF-SHA256 (RFC 5869). */
internal object Hkdf {
    fun sha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * HmacSha256.LENGTH) { "invalid HKDF output length" }
        // RFC 5869 §2.2: an absent salt is HashLen zero bytes. That is also what HMAC does with an
        // empty key, but SecretKeySpec rejects empty keys, so spell it out.
        val prk = HmacSha256.compute(if (salt.isEmpty()) ByteArray(HmacSha256.LENGTH) else salt, ikm)
        val mac = HmacSha256.mac(prk)
        val output = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val n = minOf(previous.size, length - offset)
            previous.copyInto(output, offset, 0, n)
            offset += n
            counter++
        }
        prk.fill(0)
        return output
    }
}

/** AES-256-GCM with a 12-byte nonce and a 16-byte tag appended to the ciphertext. */
internal object AesGcm {
    const val KEY_LENGTH = 32
    const val NONCE_LENGTH = 12
    const val TAG_LENGTH = 16

    fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray =
        cipher(Cipher.ENCRYPT_MODE, key, nonce, aad).doFinal(plaintext)

    /** @throws AEADBadTagException if the key, nonce, aad or ciphertext do not match. */
    fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertextAndTag: ByteArray): ByteArray {
        if (ciphertextAndTag.size < TAG_LENGTH) throw AEADBadTagException("ciphertext shorter than tag")
        return cipher(Cipher.DECRYPT_MODE, key, nonce, aad).doFinal(ciphertextAndTag)
    }

    private fun cipher(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray): Cipher {
        require(key.size == KEY_LENGTH) { "AES-256-GCM key must be $KEY_LENGTH bytes" }
        require(nonce.size == NONCE_LENGTH) { "AES-GCM nonce must be $NONCE_LENGTH bytes" }
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LENGTH * 8, nonce))
            updateAAD(aad)
        }
    }
}
