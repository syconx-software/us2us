package com.syconx.us2us.runtime.storage

import com.syconx.us2us.runtime.vault.AppDirectories
import com.syconx.us2us.runtime.vault.KeySlotStore
import java.io.File
import java.io.IOException

/**
 * Where local storage lives (PROTOCOL.md §5.3), the same tree as on iOS:
 *
 * ```
 * <root>/                   noBackupFilesDir/us2us/storage: never in a backup or device transfer
 *   db/main.sqlite          SQLCipher database (+ -wal, -shm)
 *   slots/<hex>.slot        key slots (BRPS)
 *   slots/schedule.json     ghost schedule {slotId, deadline}, readable without the DEK
 *   files/<hex>.item        attachment and thumbnail items
 *   staging/                plaintext only while being encrypted or decrypted
 * ```
 *
 * Android has no per-file protection classes: app files live in credential-encrypted storage.
 * Everything that matters is encrypted under keys derived from the DEK, which exists only while
 * the app is unlocked; the schedule stays readable so an overdue ghost wipe can run while locked.
 */
data class StorageLayout(val root: File) {
    val databaseDirectory: File get() = File(root, "db")
    val databaseFile: File get() = File(databaseDirectory, "main.sqlite")
    val slotDirectory: File get() = File(root, "slots")
    val scheduleFile: File get() = File(slotDirectory, KeySlotStore.SCHEDULE_NAME)
    val filesDirectory: File get() = File(root, "files")
    val stagingDirectory: File get() = File(root, "staging")

    /** The database's companion files, which hold encrypted pages too. */
    val databaseFiles: List<File> get() = listOf("", "-wal", "-shm", "-journal").map { File(databaseFile.path + it) }

    /** Creates the directories. Idempotent; called on every open. */
    internal fun prepare() {
        for (directory in listOf(root, databaseDirectory, slotDirectory, filesDirectory, stagingDirectory)) {
            if (!directory.isDirectory && !directory.mkdirs()) storageFailure(StorageError.Io("cannot prepare ${directory.name}"))
        }
    }

    internal fun sameRoot(other: StorageLayout): Boolean = canonical(root) == canonical(other.root)

    companion object {
        /** `storage/` of the app's protected directories (the slot directory is `AppDirectories.slots`). */
        fun standard(directories: AppDirectories): StorageLayout = StorageLayout(directories.storage)

        private fun canonical(file: File): String = try {
            file.canonicalPath
        } catch (_: IOException) {
            file.absolutePath
        }
    }
}

/** Removes a file or directory tree; a missing one is fine, one that stays is an error. */
internal fun removeIfPresent(file: File) {
    if (!file.exists()) return
    val removed = if (file.isDirectory) file.deleteRecursively() else file.delete()
    if (!removed && file.exists()) storageFailure(StorageError.Io("remove ${file.name}"))
}
