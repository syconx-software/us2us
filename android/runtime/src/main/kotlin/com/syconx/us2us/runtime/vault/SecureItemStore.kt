package com.syconx.us2us.runtime.vault

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.syconx.us2us.core.vault.DeviceSecretProvider
import com.syconx.us2us.core.vault.Vault
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small secrets kept outside the vault: the device secret of PROTOCOL.md §5.2. Nothing here is
 * ever exported, synced or part of a backup.
 */
interface SecureItemStore {
    /** The item, or `null` when there is none (or it can never be read on this phone again). */
    fun read(name: String): ByteArray?

    /** Stores [data], replacing any previous value. */
    fun write(name: String, data: ByteArray)

    fun delete(name: String)

    /** Deletes every item of this store ("erase everything", leftovers of an earlier installation). */
    fun deleteAll()
}

/** A Keystore or file error that may pass (the phone is locked, the Keystore is busy); not a missing item. */
class SecureItemException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Items sealed under a non-exportable AndroidKeyStore key, the Android counterpart of the iOS
 * Keychain items `WhenUnlockedThisDeviceOnly`:
 *
 * * AES-256-GCM, `setUnlockedDeviceRequired(true)`: the key works only while the phone is
 *   unlocked (a phone without a screen lock gets the key without that condition); StrongBox
 *   where the phone has it, else the TEE.
 * * Each item is a file in `noBackupFilesDir` (never in a backup, never moved to another phone):
 *   `nonce(12) ‖ AES-256-GCM(K, nonce, aad = "us2us/item/v1" ‖ UTF-8(name), data) ‖ tag(16)`,
 *   with the nonce chosen by the Keystore.
 * * [deleteAll] deletes the key as well, so an item file left on flash can never be read again.
 *
 * An item whose key is gone (or that no longer authenticates) reads as missing: it can never be
 * opened on this phone again.
 */
class KeystoreItemStore(
    private val directory: File,
    private val keyAlias: String = DEFAULT_ALIAS,
) : SecureItemStore {
    private val keyStore: KeyStore by lazy { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) } }

    @Synchronized
    override fun read(name: String): ByteArray? {
        val sealed = VaultFiles.read(file(name)) ?: return null
        if (sealed.size < NONCE_LENGTH + TAG_LENGTH) return null
        try {
            val key = keyStore.getKey(keyAlias, null) as? SecretKey ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH * 8, sealed, 0, NONCE_LENGTH))
            cipher.updateAAD(aad(name))
            return cipher.doFinal(sealed, NONCE_LENGTH, sealed.size - NONCE_LENGTH)
        } catch (_: AEADBadTagException) {
            return null
        } catch (e: GeneralSecurityException) {
            throw SecureItemException("item not readable", e)
        } catch (e: ProviderException) {
            throw SecureItemException("item not readable", e)
        }
    }

    @Synchronized
    override fun write(name: String, data: ByteArray) {
        val sealed = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD(aad(name))
            val iv = cipher.iv
            check(iv.size == NONCE_LENGTH) { "unexpected nonce length" }
            iv + cipher.doFinal(data)
        } catch (e: GeneralSecurityException) {
            throw SecureItemException("item not sealed", e)
        } catch (e: ProviderException) {
            throw SecureItemException("item not sealed", e)
        }
        VaultFiles.write(file(name), sealed)
    }

    @Synchronized
    override fun delete(name: String) {
        VaultFiles.delete(file(name))
    }

    @Synchronized
    override fun deleteAll() {
        try {
            if (keyStore.containsAlias(keyAlias)) keyStore.deleteEntry(keyAlias)
        } catch (e: GeneralSecurityException) {
            throw SecureItemException("key not deleted", e)
        } finally {
            directory.listFiles()?.forEach { it.delete() }
        }
    }

    /** The security level of the wrapping key (`KeyProperties.SECURITY_LEVEL_*`), or `null` without one. */
    @Synchronized
    fun securityLevel(): Int? {
        val key = keyStore.getKey(keyAlias, null) as? SecretKey ?: return null
        return KeystoreKeys.securityLevel(key)
    }

    private fun key(): SecretKey {
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return try {
            generateKey(unlockedDeviceRequired = true)
        } catch (_: ProviderException) {
            // Without a screen lock, Keystore 2 (Android 12–13) cannot create an unlocked-device
            // key ("User ECDH key missing"); the condition protects nothing on such a phone, as
            // `WhenUnlocked` on an iPhone without a passcode.
            if (keyStore.containsAlias(keyAlias)) keyStore.deleteEntry(keyAlias)
            generateKey(unlockedDeviceRequired = false)
        }
    }

    private fun generateKey(unlockedDeviceRequired: Boolean): SecretKey = KeystoreKeys.generatePreferringStrongBox { strongBox ->
        val spec = KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUnlockedDeviceRequired(unlockedDeviceRequired)
            .setIsStrongBoxBacked(strongBox)
            .build()
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply { init(spec) }.generateKey()
    }

    private fun file(name: String): File {
        require(name.isNotEmpty() && name.all { it.isLetterOrDigit() || it == '-' }) { "item names are letters, digits and dashes" }
        return File(directory, "$name.item")
    }

    private fun aad(name: String): ByteArray = AAD_PREFIX + name.toByteArray(Charsets.UTF_8)

    companion object {
        const val DEFAULT_ALIAS = "us2us.secure-items"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val NONCE_LENGTH = 12
        private const val TAG_LENGTH = 16
        private val AAD_PREFIX = "us2us/item/v1".toByteArray(Charsets.UTF_8)

        /** `noBackupFilesDir/us2us-items`, outside the app's file tree so that erasing the files leaves the keys to [deleteAll]. */
        fun standard(context: Context): KeystoreItemStore = KeystoreItemStore(File(context.noBackupFilesDir, "us2us-items"))
    }
}

/** Items held in memory, for tests and previews. */
class InMemorySecureItemStore : SecureItemStore {
    private val items = mutableMapOf<String, ByteArray>()

    @Synchronized
    override fun read(name: String): ByteArray? = items[name]?.copyOf()

    @Synchronized
    override fun write(name: String, data: ByteArray) {
        items[name] = data.copyOf()
    }

    @Synchronized
    override fun delete(name: String) {
        items.remove(name)?.fill(0)
    }

    @Synchronized
    override fun deleteAll() {
        items.values.forEach { it.fill(0) }
        items.clear()
    }

    val count: Int
        @Synchronized get() = items.size
}

/**
 * The vault's device secret (§5.2) as an item of a [SecureItemStore]: 32 random bytes that never
 * leave the phone. With [KeystoreItemStore] it is wrapped by an AndroidKeyStore key and kept in
 * `noBackupFilesDir`.
 *
 * [deviceSecret] creates the secret on first use, as [DeviceSecretProvider] promises; the vault
 * itself uses [load], because a secret that has gone missing must not be silently replaced: the
 * vault could then never be opened and every code would look wrong.
 */
class DeviceSecretItem(private val store: SecureItemStore) : DeviceSecretProvider {
    /** The stored secret, or `null` when there is none (or it is not 32 bytes). */
    fun load(): ByteArray? = store.read(ITEM_NAME)?.takeIf { it.size == Vault.DEVICE_SECRET_LENGTH }

    /** Stores [secret], replacing any previous one. */
    fun replace(secret: ByteArray) {
        require(secret.size == Vault.DEVICE_SECRET_LENGTH) { "device secret must be ${Vault.DEVICE_SECRET_LENGTH} bytes" }
        store.write(ITEM_NAME, secret)
    }

    @Synchronized
    override fun deviceSecret(): ByteArray = load() ?: generate().also(::replace)

    override fun destroy() = store.delete(ITEM_NAME)

    companion object {
        const val ITEM_NAME = "device-secret"

        fun generate(): ByteArray = ByteArray(Vault.DEVICE_SECRET_LENGTH).also(SecureRandom()::nextBytes)
    }
}
