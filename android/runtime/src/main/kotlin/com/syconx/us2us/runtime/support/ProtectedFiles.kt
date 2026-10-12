package com.syconx.us2us.runtime.support

import com.syconx.us2us.runtime.vault.VaultFiles
import java.io.File
import java.io.IOException

/**
 * File helpers for PROTOCOL.md §5.3 and the owner's "no backups" rule.
 *
 * Every file of the app lives under [com.syconx.us2us.runtime.vault.AppDirectories], inside
 * `noBackupFilesDir`, which Auto Backup never copies (the manifest also turns backup and device
 * transfer off), so there is nothing to exclude per file. Android has no per-file protection class
 * like iOS "complete": app files are in credential-encrypted storage, and what matters is
 * encrypted under keys that exist only while the app is unlocked.
 */
object ProtectedFiles {
    /** Writes [data] atomically: synced to a temporary file, then renamed over [file]. */
    @Throws(IOException::class)
    fun write(data: ByteArray, file: File) {
        VaultFiles.write(file, data)
    }

    /** The size of [file] in bytes, 0 if it is missing. */
    fun size(file: File): Long = if (file.isFile) file.length() else 0L
}
