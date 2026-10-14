package com.syconx.us2us.runtime.storage

import com.google.protobuf.ByteString
import com.syconx.us2us.core.encoding.Hex
import com.syconx.us2us.core.vault.ItemCipher
import com.syconx.us2us.core.vault.ItemDecryptionException
import com.syconx.us2us.runtime.vault.VaultFiles
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Attachment and thumbnail files, each an item under its conversation's key (PROTOCOL.md §5.3):
 *
 * ```
 * item = 0x01 ‖ nonce(12) ‖ AES-256-GCM(CK, nonce, aad = 0x01 ‖ itemId(16), plaintext) ‖ tag(16)
 * ```
 *
 * Files are named only by their random item id (`<hex>.item`), so neither names nor types leak into
 * the file system. Plaintext never touches the disk outside `staging/`, which is cleared on every
 * open and close.
 */
internal class ItemFileStore(layout: StorageLayout) {
    val directory: File = layout.filesDirectory
    val staging: File = layout.stagingDirectory

    fun file(itemId: ByteString): File = File(directory, Hex.encode(itemId.toByteArray()) + SUFFIX)

    fun exists(itemId: ByteString): Boolean = file(itemId).exists()

    /** Encrypts and writes atomically (temporary file, sync, rename). */
    fun write(plaintext: ByteArray, itemId: ByteString, key: ByteArray) {
        val item = ItemCipher.seal(key, itemId.toByteArray(), plaintext)
        try {
            VaultFiles.write(file(itemId), item)
        } catch (_: IOException) {
            storageFailure(StorageError.Io("write item"))
        }
    }

    fun read(itemId: ByteString, key: ByteArray): ByteArray {
        val item = try {
            VaultFiles.read(file(itemId))
        } catch (_: IOException) {
            null
        } ?: storageFailure(StorageError.NotFound)
        return try {
            ItemCipher.open(key, itemId.toByteArray(), item)
        } catch (e: ItemDecryptionException) {
            storageFailure(StorageError.Integrity, e)
        }
    }

    fun remove(itemId: ByteString) = removeIfPresent(file(itemId))

    /** Item ids of every file present (for garbage collection). */
    fun allItemIds(): Set<ByteString> = directory.list().orEmpty().mapNotNullTo(HashSet()) { name ->
        if (!name.endsWith(SUFFIX)) return@mapNotNullTo null
        itemId(name.removeSuffix(SUFFIX))
    }

    /** Leftovers that are not items: temporary files of interrupted atomic writes. */
    fun strayFiles(): List<File> = directory.list().orEmpty()
        .filter { name -> !name.endsWith(SUFFIX) || itemId(name.removeSuffix(SUFFIX)) == null }
        .map { File(directory, it) }

    /** A fresh path for plaintext that is about to be encrypted or was just decrypted (wiped on open/close). */
    fun newStagingFile(): File {
        if (!staging.isDirectory) staging.mkdirs()
        return File(staging, UUID.randomUUID().toString())
    }

    fun clearStaging() {
        staging.listFiles()?.forEach { it.deleteRecursively() }
    }

    private fun itemId(hex: String): ByteString? = try {
        Hex.decode(hex).takeIf { it.size == RandomIds.LENGTH }?.let(ByteString::copyFrom)
    } catch (_: IllegalArgumentException) {
        null
    }

    private companion object {
        const val SUFFIX = ".item"
    }
}
