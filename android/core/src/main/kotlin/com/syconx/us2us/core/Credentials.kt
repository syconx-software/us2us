package com.syconx.us2us.core

import com.syconx.us2us.core.crypto.ConstantTime
import com.syconx.us2us.core.crypto.Random
import com.syconx.us2us.core.crypto.Sha256
import com.syconx.us2us.core.encoding.Base64Url

/**
 * The 32-byte bearer token for the account's mailbox (`X-Auth-Token`, PROTOCOL.md §4). The relay
 * stores only its SHA-256.
 */
class AuthToken(bytes: ByteArray) {
    private val bytes = bytes.copyOf()

    init {
        require(bytes.size == LENGTH) { "auth token must be $LENGTH bytes" }
    }

    fun toBytes(): ByteArray = bytes.copyOf()

    val headerValue: String get() = Base64Url.encode(bytes)

    override fun equals(other: Any?): Boolean = other is AuthToken && ConstantTime.equals(bytes, other.bytes)
    override fun hashCode(): Int = bytes.contentHashCode()
    override fun toString(): String = "AuthToken(redacted)"

    companion object {
        const val LENGTH = 32

        fun parse(base64Url: String): AuthToken = AuthToken(Base64Url.decode(base64Url, LENGTH))
    }
}

/**
 * The 16-byte key a recipient hands to accepted contacts so they can deliver sealed messages
 * without proof-of-work (`X-Access-Key`, PROTOCOL.md §4.6). The relay stores only [hash].
 */
class AccessKey(bytes: ByteArray) {
    private val bytes = bytes.copyOf()

    init {
        require(bytes.size == LENGTH) { "access key must be $LENGTH bytes" }
    }

    fun toBytes(): ByteArray = bytes.copyOf()

    /** `accessKeyHash = SHA-256(accessKey)` (§4.3). */
    fun hash(): ByteArray = Sha256.digest(bytes)

    val headerValue: String get() = Base64Url.encode(bytes)

    override fun equals(other: Any?): Boolean = other is AccessKey && ConstantTime.equals(bytes, other.bytes)
    override fun hashCode(): Int = bytes.contentHashCode()
    override fun toString(): String = "AccessKey(redacted)"

    companion object {
        const val LENGTH = 16

        fun generate(): AccessKey = AccessKey(Random.bytes(LENGTH))
    }
}
