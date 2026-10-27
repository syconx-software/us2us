package com.syconx.us2us.runtime.vault

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
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

    /** Optional, atomic device-key maintenance; called only after a successful unlock. */
    fun migrateAfterSuccessfulUnlock() = Unit
}

/** A Keystore or file error that may pass (the phone is locked, the Keystore is busy); not a missing item. */
class SecureItemException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Items sealed under a non-exportable AndroidKeyStore key, the Android counterpart of the iOS
 * Keychain items `WhenUnlockedThisDeviceOnly`:
 *
 * * AES-256-GCM; unlocked-device-required only on API 35+, avoiding the documented key
 *   deletion/unlock bugs on 31–34. App lifecycle locking still applies on every version.
 *   Prefer StrongBox where available, else the TEE.
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
    private val layout = SecureItemLayout(directory)
    private val hardenedAlias = "$keyAlias.wrap-v2"
    /** An installation-specific alias, also isolated between device-test stores. */
    internal fun vaultHardwareKey() = KeystoreVaultHardwareKey("$keyAlias.vault-prf-v2")

    private val keyStore: KeyStore by lazy { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) } }

    @Synchronized
    override fun read(name: String): ByteArray? = layout.locked {
        val current = layout.active()
        open(name, file(current, name), current.hardened)
    }

    private fun open(name: String, file: File, hardened: Boolean): ByteArray? {
        val sealed = VaultFiles.read(file) ?: return null
        if (sealed.size < NONCE_LENGTH + TAG_LENGTH) return null
        try {
            val key = keyStore.getKey(alias(hardened), null) as? SecretKey ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH * 8, sealed, 0, NONCE_LENGTH))
            cipher.updateAAD(aad(name))
            return cipher.doFinal(sealed, NONCE_LENGTH, sealed.size - NONCE_LENGTH)
        } catch (_: AEADBadTagException) {
            return null
        } catch (_: KeyPermanentlyInvalidatedException) {
            return null
        } catch (e: GeneralSecurityException) {
            throw SecureItemException("item not readable", e)
        } catch (e: ProviderException) {
            throw SecureItemException("item not readable", e)
        }
    }

    @Synchronized
    override fun write(name: String, data: ByteArray) = layout.locked {
        val current = layout.active()
        val target = file(current, name)
        val key = key(alias(current.hardened), allowCreate = !current.hardened && layout.items(current).isEmpty())
        VaultFiles.write(target, seal(name, data, key))
        layout.sync(current)
    }

    private fun seal(name: String, data: ByteArray, key: SecretKey): ByteArray = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(aad(name))
            val iv = cipher.iv
            check(iv.size == NONCE_LENGTH) { "unexpected nonce length" }
            iv + cipher.doFinal(data)
        } catch (e: GeneralSecurityException) {
            throw SecureItemException("item not sealed", e)
        } catch (e: ProviderException) {
            throw SecureItemException("item not sealed", e)
        }

    @Synchronized
    override fun delete(name: String) = layout.locked {
        val current = layout.active()
        VaultFiles.delete(file(current, name))
        layout.sync(current)
    }

    @Synchronized
    override fun deleteAll(): Unit = layout.locked {
        var failure: SecureItemException? = null
        try {
            for (alias in listOf(keyAlias, hardenedAlias)) {
                try { deleteKey(alias) } catch (e: SecureItemException) { failure = failure ?: e }
            }
        } finally {
            layout.clearFiles()
        }
        failure?.let { throw it }
    }

    @Synchronized
    override fun migrateAfterSuccessfulUnlock() = layout.locked {
        layout.migrate(
            open = ::open,
            seal = { name, plaintext -> seal(name, plaintext, key(hardenedAlias, allowCreate = false)) },
            prepareKey = { key(hardenedAlias, allowCreate = true); Unit },
            deleteLegacyKey = { deleteKey(keyAlias) },
        )
    }

    /** The security level of the wrapping key (`KeyProperties.SECURITY_LEVEL_*`), or `null` without one. */
    @Synchronized
    fun securityLevel(): Int? = layout.locked {
        val key = keyStore.getKey(alias(layout.active().hardened), null) as? SecretKey ?: return@locked null
        KeystoreKeys.securityLevel(key)
    }

    private fun alias(hardened: Boolean) = if (hardened) hardenedAlias else keyAlias

    private fun key(alias: String, allowCreate: Boolean): SecretKey = try {
        (keyStore.getKey(alias, null) as? SecretKey) ?: run {
            if (!allowCreate) throw SecureItemException("wrapping key missing; existing items preserved")
            try {
                generateKey(alias, unlockedDeviceRequired = unlockedWrappingKeyRequired(Build.VERSION.SDK_INT))
            } catch (e: ProviderException) {
                if (!unlockedWrappingKeyRequired(Build.VERSION.SDK_INT)) throw e
                // Only a fresh alias, never a key protecting existing items. Without a screen
                // lock API 35+ providers may still refuse this optional requirement.
                deleteKey(alias)
                generateKey(alias, unlockedDeviceRequired = false)
            }
        }
    } catch (e: GeneralSecurityException) {
        throw SecureItemException("wrapping key unavailable", e)
    } catch (e: ProviderException) {
        throw SecureItemException("wrapping key unavailable", e)
    }

    private fun deleteKey(alias: String) {
        try {
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        } catch (e: GeneralSecurityException) {
            throw SecureItemException("wrapping key not deleted", e)
        } catch (e: ProviderException) {
            throw SecureItemException("wrapping key not deleted", e)
        }
    }

    private fun generateKey(alias: String, unlockedDeviceRequired: Boolean): SecretKey = KeystoreKeys.generatePreferringStrongBox { strongBox ->
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUnlockedDeviceRequired(unlockedDeviceRequired)
            .setIsStrongBoxBacked(strongBox)
            .build()
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply { init(spec) }.generateKey()
    }

    private fun file(generation: SecureItemLayout.Generation, name: String): File {
        require(SecureItemLayout.validItemName(name)) { "item names are letters, digits and dashes" }
        return File(generation.directory, "$name.item")
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

/** Android's documented API 31–34 bugs are avoided, including on devices upgraded later. */
internal fun unlockedWrappingKeyRequired(sdk: Int): Boolean = sdk >= 35

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
