package com.syconx.us2us.runtime.vault

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Where the app keeps its files, all under one directory in `noBackupFilesDir`, which Auto Backup
 * never copies (the manifest also turns backups and device transfer off).
 *
 * Layout (names carry no user data):
 * ```
 * <root>/vault/     vault file, attempt counter, quick-unlock record (PROTOCOL.md §5.2)
 * <root>/storage/   SQLCipher database, key slots (storage/slots), ghost schedule, encrypted
 *                   media (§5.3); prepared by the storage layer itself
 * <root>/runtime/   the runtime's encrypted state and notice items
 * <root>/staging/   prepared media before it is encrypted, exported backups; emptied on every
 *                   lock and at every launch
 * ```
 *
 * Android has no per-file protection class like iOS "complete": app files live in
 * credential-encrypted storage, readable from the first unlock after boot. Everything that matters
 * is encrypted under keys derived from the DEK, which exists only while the app is unlocked.
 */
class AppDirectories(val root: File) {
    val vault: File get() = File(root, "vault")
    val storage: File get() = File(root, "storage")
    val runtime: File get() = File(root, "runtime")
    val staging: File get() = File(root, "staging")

    /** The key-slot directory of the storage layout (§5.3), next to the database. */
    val slots: File get() = File(storage, "slots")

    /** Creates the directories (the storage layer creates its own). Idempotent. */
    fun prepare() {
        for (directory in listOf(root, vault, runtime, staging)) {
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("cannot create ${directory.name}")
        }
    }

    /** Deletes everything in the staging directory (plaintext never outlives a lock). */
    fun purgeStaging() {
        staging.listFiles()?.forEach { it.deleteRecursively() }
    }

    /** A fresh staging file (random name, nothing derived from content). */
    fun stagingFile(extension: String = "bin"): File = File(staging, "${UUID.randomUUID()}.$extension")

    /**
     * Removes every file of the app. The keys are destroyed separately (vault, device secret,
     * biometric key) *before* this runs, so the ciphertext left on flash during deletion is
     * already unreadable.
     */
    fun eraseAll() {
        if (root.exists() && !root.deleteRecursively()) throw IOException("cannot erase ${root.name}")
    }

    companion object {
        /** `noBackupFilesDir/us2us` of the app. */
        fun standard(context: Context): AppDirectories = AppDirectories(File(context.noBackupFilesDir, "us2us"))
    }
}
