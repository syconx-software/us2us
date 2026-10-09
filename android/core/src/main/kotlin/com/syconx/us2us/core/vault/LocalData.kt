package com.syconx.us2us.core.vault

import com.syconx.us2us.core.crypto.AesGcm
import com.syconx.us2us.core.crypto.Random
import javax.crypto.AEADBadTagException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Source of the 32-byte device-bound secret mixed into the vault key (PROTOCOL.md §5.2). It never
 * leaves the device and is never part of a backup. On Android it is wrapped by a Keystore key; on
 * iOS it lives in the Keychain with a `ThisDeviceOnly` accessibility.
 */
interface DeviceSecretProvider {
    /** Returns the secret, creating it on first use. */
    fun deviceSecret(): ByteArray

    /** Irrevocably destroys the secret, which makes every vault on this device unopenable. */
    fun destroy()
}

/** A [DeviceSecretProvider] for tests and desktop tools: the secret lives only in this object. */
class InMemoryDeviceSecretProvider(initial: ByteArray? = null) : DeviceSecretProvider {
    private var secret: ByteArray? = initial?.copyOf()?.also {
        require(it.size == Vault.DEVICE_SECRET_LENGTH) { "device secret must be ${Vault.DEVICE_SECRET_LENGTH} bytes" }
    }

    @Synchronized
    override fun deviceSecret(): ByteArray =
        (secret ?: Random.bytes(Vault.DEVICE_SECRET_LENGTH).also { secret = it }).copyOf()

    @Synchronized
    override fun destroy() {
        secret?.fill(0)
        secret = null
    }
}

/**
 * The delay the user is heading for: after [attemptsLeft] more wrong codes they have to wait
 * [delay] ("2 yanlış daha olursa 1 dakika beklemen gerekir").
 */
data class UpcomingDelay(val attemptsLeft: Int, val delay: Duration)

/** The unlock UI's delays after wrong codes (PROTOCOL.md §5.2). */
object UnlockAttemptPolicy {
    /** Offered as an option, off by default: wipe everything after this many wrong codes in a row. */
    const val WIPE_AFTER_FAILURES = 10

    /** D(N): how long to wait before the next attempt after [consecutiveFailures] wrong codes. */
    fun delayAfter(consecutiveFailures: Int): Duration = when {
        consecutiveFailures < 3 -> Duration.ZERO
        consecutiveFailures == 3 -> 1.minutes
        consecutiveFailures == 4 -> 5.minutes
        consecutiveFailures == 5 -> 15.minutes
        consecutiveFailures == 6 -> 30.minutes
        else -> 1.hours
    }

    /**
     * What the next wrong codes cost after [consecutiveFailures]: the smallest k with
     * D(failures + k) > 0, and that delay. Every N ≥ 3 has a delay, so this is never `null` for
     * the spec's table.
     */
    fun upcomingDelay(consecutiveFailures: Int): UpcomingDelay? {
        val current = consecutiveFailures.coerceAtLeast(0)
        // From the 7th failure on, every further one costs the same, so three steps always suffice.
        for (next in current + 1..current + 3) {
            val delay = delayAfter(next)
            if (delay > Duration.ZERO) return UpcomingDelay(next - current, delay)
        }
        return null
    }

    fun shouldWipe(consecutiveFailures: Int, wipeEnabled: Boolean): Boolean =
        wipeEnabled && consecutiveFailures >= WIPE_AFTER_FAILURES
}

/**
 * A conversation key slot (PROTOCOL.md §5.3). Deleting the slot file destroys the conversation,
 * even while the app is locked.
 *
 * ```
 * slotKey  = HKDF-SHA256(DEK, salt = empty, info = "BRP/slot/v1", 32)
 * slotFile = "BRPS" ‖ 0x01 ‖ nonce(12) ‖ AES-256-GCM(slotKey, nonce, aad = "BRPS" ‖ 0x01 ‖ slotId(16), CK) ‖ tag(16)
 * ```
 */
object KeySlot {
    const val SLOT_ID_LENGTH = 16
    const val KEY_LENGTH = 32
    private val prefix = byteArrayOf('B'.code.toByte(), 'R'.code.toByte(), 'P'.code.toByte(), 'S'.code.toByte(), 0x01)

    fun newSlotId(): ByteArray = Random.bytes(SLOT_ID_LENGTH)

    fun newConversationKey(): ByteArray = Random.bytes(KEY_LENGTH)

    fun create(dek: DataKey, slotId: ByteArray, conversationKey: ByteArray): ByteArray =
        create(dek, slotId, Random.bytes(AesGcm.NONCE_LENGTH), conversationKey)

    /** @throws InvalidVaultFileException if the slot does not belong to this DEK and id, or is malformed. */
    fun open(dek: DataKey, slotId: ByteArray, slotFile: ByteArray): ByteArray {
        require(slotId.size == SLOT_ID_LENGTH) { "slot id must be $SLOT_ID_LENGTH bytes" }
        if (slotFile.size != prefix.size + AesGcm.NONCE_LENGTH + KEY_LENGTH + AesGcm.TAG_LENGTH ||
            !slotFile.copyOfRange(0, prefix.size).contentEquals(prefix)
        ) {
            throw InvalidVaultFileException("not a key slot")
        }
        val nonce = slotFile.copyOfRange(prefix.size, prefix.size + AesGcm.NONCE_LENGTH)
        val slotKey = dek.slotKey()
        try {
            return AesGcm.open(slotKey, nonce, prefix + slotId, slotFile.copyOfRange(prefix.size + AesGcm.NONCE_LENGTH, slotFile.size))
        } catch (_: AEADBadTagException) {
            throw InvalidVaultFileException("key slot authentication failed")
        } finally {
            slotKey.fill(0)
        }
    }

    internal fun create(dek: DataKey, slotId: ByteArray, nonce: ByteArray, conversationKey: ByteArray): ByteArray {
        require(slotId.size == SLOT_ID_LENGTH) { "slot id must be $SLOT_ID_LENGTH bytes" }
        require(conversationKey.size == KEY_LENGTH) { "conversation key must be $KEY_LENGTH bytes" }
        val slotKey = dek.slotKey()
        try {
            return prefix + nonce + AesGcm.seal(slotKey, nonce, prefix + slotId, conversationKey)
        } finally {
            slotKey.fill(0)
        }
    }
}

/** The item does not open under this conversation key and item id: tampered, swapped or shredded. */
class ItemDecryptionException : Exception("item authentication failed")

/**
 * A message body, attachment or thumbnail encrypted under its conversation key (PROTOCOL.md §5.3):
 *
 * ```
 * item = 0x01 ‖ nonce(12) ‖ AES-256-GCM(CK, nonce, aad = 0x01 ‖ itemId(16), plaintext) ‖ tag(16)
 * ```
 *
 * Binding the item id (the message id, or a random id for files) means rows cannot be swapped.
 */
object ItemCipher {
    const val ITEM_ID_LENGTH = 16
    private const val VERSION: Byte = 0x01

    fun seal(conversationKey: ByteArray, itemId: ByteArray, plaintext: ByteArray): ByteArray =
        seal(conversationKey, itemId, Random.bytes(AesGcm.NONCE_LENGTH), plaintext)

    /** @throws ItemDecryptionException if the item does not authenticate. */
    fun open(conversationKey: ByteArray, itemId: ByteArray, item: ByteArray): ByteArray {
        require(itemId.size == ITEM_ID_LENGTH) { "item id must be $ITEM_ID_LENGTH bytes" }
        if (item.size < 1 + AesGcm.NONCE_LENGTH + AesGcm.TAG_LENGTH || item[0] != VERSION) throw ItemDecryptionException()
        val nonce = item.copyOfRange(1, 1 + AesGcm.NONCE_LENGTH)
        try {
            return AesGcm.open(conversationKey, nonce, byteArrayOf(VERSION) + itemId, item.copyOfRange(1 + AesGcm.NONCE_LENGTH, item.size))
        } catch (_: AEADBadTagException) {
            throw ItemDecryptionException()
        }
    }

    internal fun seal(conversationKey: ByteArray, itemId: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray {
        require(itemId.size == ITEM_ID_LENGTH) { "item id must be $ITEM_ID_LENGTH bytes" }
        return byteArrayOf(VERSION) + nonce + AesGcm.seal(conversationKey, nonce, byteArrayOf(VERSION) + itemId, plaintext)
    }
}
