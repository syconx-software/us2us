package com.syconx.us2us.core.vault

import com.syconx.us2us.core.crypto.AesGcm
import com.syconx.us2us.core.crypto.Hkdf
import com.syconx.us2us.core.crypto.Pbkdf2
import com.syconx.us2us.core.crypto.Random
import java.nio.ByteBuffer
import javax.crypto.AEADBadTagException

/** The secret code (or the device secret) does not open this vault. */
class WrongSecretCodeException : Exception("wrong secret code")

/**
 * The input is not six ASCII digits, so it cannot be a secret code (PROTOCOL.md §5.2). Refused
 * before anything is derived; [VaultUnlocker] does not count it as an attempt.
 */
class MalformedSecretCodeException : IllegalArgumentException("a secret code is exactly ${SecretCodePolicy.LENGTH} ASCII digits")

/** The backup password does not open this backup file. */
class WrongBackupPasswordException : Exception("wrong backup password")

/** The file is not a vault/backup of a supported version, or is truncated. */
class InvalidVaultFileException(message: String) : Exception(message)

/**
 * The 32-byte data encryption key. Everything on the phone is encrypted under keys derived from it
 * (PROTOCOL.md §5.3). [wipe] it when the app locks.
 */
class DataKey(bytes: ByteArray) {
    private val bytes = bytes.copyOf()

    init {
        require(bytes.size == LENGTH) { "DEK must be $LENGTH bytes" }
    }

    fun toBytes(): ByteArray = bytes.copyOf()

    /** SQLCipher key: `HKDF-SHA256(DEK, salt = empty, info = "BRP/db/v1", 32)`. */
    fun databaseKey(): ByteArray = Hkdf.sha256(bytes, ByteArray(0), DB_INFO, LENGTH)

    /** Key-slot wrapping key: `HKDF-SHA256(DEK, salt = empty, info = "BRP/slot/v1", 32)`. */
    fun slotKey(): ByteArray = Hkdf.sha256(bytes, ByteArray(0), SLOT_INFO, LENGTH)

    fun wipe() = bytes.fill(0)

    override fun toString(): String = "DataKey(redacted)"

    companion object {
        const val LENGTH = 32
        private val DB_INFO = "BRP/db/v1".toByteArray(Charsets.UTF_8)
        private val SLOT_INFO = "BRP/slot/v1".toByteArray(Charsets.UTF_8)

        fun generate(): DataKey = DataKey(Random.bytes(LENGTH))
    }
}

/**
 * The vault file (PROTOCOL.md §5.2): the DEK wrapped under a key derived from the six-digit secret
 * code and the device secret.
 *
 * ```
 * codeKey = PBKDF2-HMAC-SHA256(UTF-8(code), salt, iterations, 32)      // code = six ASCII digits
 * KEK     = HKDF-SHA256(ikm = codeKey ‖ deviceSecret, salt = salt, info = "BRP/vault/v1", 32)
 * vault   = header ‖ nonce(12) ‖ AES-256-GCM(KEK, nonce, aad = header, DEK) ‖ tag(16)
 * header  = "BRPV" ‖ 0x01 ‖ salt(16) ‖ uint32_be(iterations)
 * ```
 *
 * Because the device secret never leaves the phone, a copied vault file cannot be brute-forced
 * elsewhere. Key derivation takes about a second by design: call these off the main thread.
 */
object Vault {
    const val ITERATIONS = 600_000
    const val DEVICE_SECRET_LENGTH = 32
    private val format = PasswordFileFormat(magic = "BRPV")
    private val KEK_INFO = "BRP/vault/v1".toByteArray(Charsets.UTF_8)

    /**
     * Creates a vault under a newly chosen [code].
     *
     * @throws InvalidSecretCodeException if [code] breaks a rule of §5.2.
     */
    fun create(code: String, deviceSecret: ByteArray, dek: DataKey, iterations: Int = ITERATIONS): ByteArray {
        SecretCodePolicy.requireValid(code)
        return create(code, deviceSecret, Random.bytes(PasswordFileFormat.SALT_LENGTH), iterations, Random.bytes(AesGcm.NONCE_LENGTH), dek)
    }

    /**
     * @throws MalformedSecretCodeException if [code] is not six ASCII digits (nothing is derived).
     * @throws WrongSecretCodeException if [code] or [deviceSecret] is wrong (a GCM failure).
     * @throws InvalidVaultFileException if the file is malformed.
     */
    fun open(vault: ByteArray, code: String, deviceSecret: ByteArray): DataKey {
        if (!SecretCodePolicy.isWellFormed(code)) throw MalformedSecretCodeException()
        val header = format.parseHeader(vault)
        val kek = kek(code, deviceSecret, header.salt, header.iterations)
        val dek = format.open(vault, kek, ::WrongSecretCodeException)
        try {
            return DataKey(dek)
        } finally {
            dek.fill(0)
        }
    }

    /**
     * Re-wraps the DEK under [newCode]; no data is re-encrypted (§5.2).
     *
     * @throws InvalidSecretCodeException if [newCode] breaks a rule of §5.2.
     */
    fun changeCode(vault: ByteArray, oldCode: String, newCode: String, deviceSecret: ByteArray, iterations: Int = ITERATIONS): ByteArray {
        SecretCodePolicy.requireValid(newCode)
        val dek = open(vault, oldCode, deviceSecret)
        try {
            return create(newCode, deviceSecret, dek, iterations)
        } finally {
            dek.wipe()
        }
    }

    /** Deterministic variant for test vectors: any six-digit [code], the rules are not checked. */
    internal fun create(code: String, deviceSecret: ByteArray, salt: ByteArray, iterations: Int, nonce: ByteArray, dek: DataKey): ByteArray {
        if (!SecretCodePolicy.isWellFormed(code)) throw MalformedSecretCodeException()
        PasswordFileFormat.requireIterations(iterations) // before the slow derivation
        val kek = kek(code, deviceSecret, salt, iterations)
        val plaintext = dek.toBytes()
        try {
            return format.seal(salt, iterations, nonce, kek, plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun kek(code: String, deviceSecret: ByteArray, salt: ByteArray, iterations: Int): ByteArray {
        require(deviceSecret.size == DEVICE_SECRET_LENGTH) { "device secret must be $DEVICE_SECRET_LENGTH bytes" }
        val codeKey = Pbkdf2.hmacSha256(SecretCodePolicy.keyMaterial(code), salt, iterations, 32)
        val ikm = codeKey + deviceSecret
        try {
            return Hkdf.sha256(ikm, salt, KEK_INFO, 32)
        } finally {
            codeKey.fill(0)
            ikm.fill(0)
        }
    }
}

/**
 * The account backup file (PROTOCOL.md §5.5): an `AccountBackup` protobuf encrypted under the
 * separate backup password alone (never the secret code, and no device secret, so it can be
 * restored on a new phone).
 *
 * ```
 * backupKey = PBKDF2-HMAC-SHA256(UTF-8(NFKC(password)), salt, iterations = 1000000, 32)
 * file      = header ‖ nonce(12) ‖ AES-256-GCM(backupKey, nonce, aad = header, AccountBackup) ‖ tag(16)
 * header    = "BRPB" ‖ 0x01 ‖ salt(16) ‖ uint32_be(iterations)
 * ```
 */
object BackupFile {
    const val ITERATIONS = 1_000_000
    private val format = PasswordFileFormat(magic = "BRPB")

    /**
     * Encrypts [accountBackup] under a newly chosen [password]; the export UI asks for it twice.
     *
     * @throws InvalidBackupPasswordException if [password] breaks a rule of §5.5.
     */
    fun create(password: String, accountBackup: ByteArray, iterations: Int = ITERATIONS): ByteArray {
        BackupPasswordPolicy.requireValid(password)
        return create(password, Random.bytes(PasswordFileFormat.SALT_LENGTH), iterations, Random.bytes(AesGcm.NONCE_LENGTH), accountBackup)
    }

    /**
     * Decrypts a backup with its password. The password rules are not checked: a backup always
     * opens with the password it was made with.
     *
     * @return the `AccountBackup` protobuf bytes.
     * @throws WrongBackupPasswordException if [password] is wrong (a GCM failure).
     * @throws InvalidVaultFileException if the file is malformed.
     */
    fun open(file: ByteArray, password: String): ByteArray {
        val header = format.parseHeader(file)
        return format.open(file, backupKey(password, header.salt, header.iterations), ::WrongBackupPasswordException)
    }

    /** Deterministic variant for test vectors: the rules are not checked. */
    internal fun create(password: String, salt: ByteArray, iterations: Int, nonce: ByteArray, accountBackup: ByteArray): ByteArray {
        PasswordFileFormat.requireIterations(iterations) // before the slow derivation
        return format.seal(salt, iterations, nonce, backupKey(password, salt, iterations), accountBackup)
    }

    /** `PBKDF2-HMAC-SHA256(UTF-8(NFKC(password)), salt, iterations, 32)`, untrimmed. */
    private fun backupKey(password: String, salt: ByteArray, iterations: Int): ByteArray =
        Pbkdf2.hmacSha256(BackupPasswordPolicy.keyMaterial(password), salt, iterations, 32)
}

/** `magic(4) ‖ 0x01 ‖ salt(16) ‖ uint32_be(iterations) ‖ nonce(12) ‖ ciphertext ‖ tag(16)`, header as AAD. */
private class PasswordFileFormat(magic: String) {
    private val magicBytes = magic.toByteArray(Charsets.US_ASCII)

    class Header(val salt: ByteArray, val iterations: Int)

    fun seal(salt: ByteArray, iterations: Int, nonce: ByteArray, key: ByteArray, plaintext: ByteArray): ByteArray {
        require(salt.size == SALT_LENGTH) { "salt must be $SALT_LENGTH bytes" }
        requireIterations(iterations)
        val header = ByteBuffer.allocate(HEADER_LENGTH).put(magicBytes).put(VERSION).put(salt).putInt(iterations).array()
        try {
            return header + nonce + AesGcm.seal(key, nonce, header, plaintext)
        } finally {
            key.fill(0)
        }
    }

    fun parseHeader(file: ByteArray): Header {
        if (file.size < HEADER_LENGTH + AesGcm.NONCE_LENGTH + AesGcm.TAG_LENGTH) throw InvalidVaultFileException("file too short")
        if (!file.copyOfRange(0, 4).contentEquals(magicBytes)) throw InvalidVaultFileException("not a ${String(magicBytes)} file")
        if (file[4] != VERSION) throw InvalidVaultFileException("unsupported version ${file[4]}")
        val iterations = ByteBuffer.wrap(file, 5 + SALT_LENGTH, 4).int
        // Bounded so that a hostile file cannot make the phone derive keys for hours.
        if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) throw InvalidVaultFileException("iterations out of range")
        return Header(file.copyOfRange(5, 5 + SALT_LENGTH), iterations)
    }

    /** Opens with [key]; an authentication failure throws [wrongKey]'s exception. */
    fun open(file: ByteArray, key: ByteArray, wrongKey: () -> Exception): ByteArray {
        val header = file.copyOfRange(0, HEADER_LENGTH)
        val nonce = file.copyOfRange(HEADER_LENGTH, HEADER_LENGTH + AesGcm.NONCE_LENGTH)
        try {
            return AesGcm.open(key, nonce, header, file.copyOfRange(HEADER_LENGTH + AesGcm.NONCE_LENGTH, file.size))
        } catch (_: AEADBadTagException) {
            throw wrongKey()
        } finally {
            key.fill(0)
        }
    }

    companion object {
        const val SALT_LENGTH = 16
        const val HEADER_LENGTH = 4 + 1 + SALT_LENGTH + 4
        const val VERSION: Byte = 0x01
        const val MIN_ITERATIONS = 1_000
        const val MAX_ITERATIONS = 10_000_000

        fun requireIterations(iterations: Int) = require(iterations in MIN_ITERATIONS..MAX_ITERATIONS) { "iterations out of range" }
    }
}
