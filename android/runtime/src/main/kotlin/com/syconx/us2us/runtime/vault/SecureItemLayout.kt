package com.syconx.us2us.runtime.vault

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** One durable switch for all items; ciphertext copies never become a mixed active store. */
internal class SecureItemLayout(
    private val directory: File,
    private val syncDirectory: (File) -> Unit = ::syncItemDirectory,
) {
    data class Generation(val directory: File, val hardened: Boolean)
    private val next = File(directory, NEXT_DIRECTORY)
    private val marker = File(directory, MARKER_NAME)

    fun <T> locked(block: () -> T): T = synchronized(locks.computeIfAbsent(directory.canonicalPath) { Any() }) {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("secure item directory unavailable")
        RandomAccessFile(File(directory, LOCK_NAME), "rw").use { file ->
            file.channel.lock().use { block() }
        }
    }

    fun active(): Generation {
        if (!marker.exists()) {
            val legacy = Generation(directory, false)
            // Staging is legitimate only while the original items still exist. A missing
            // marker after retirement must not turn an active store into an empty legacy one.
            if (next.exists() && items(legacy).isEmpty()) throw IOException("secure item generation marker missing")
            return legacy
        }
        if (!marker.isFile || Files.isSymbolicLink(marker.toPath()) || marker.length() != MARKER.size.toLong()
            || !marker.readBytes().contentEquals(MARKER) || !next.isDirectory || Files.isSymbolicLink(next.toPath())) {
            throw IOException("secure item generation invalid")
        }
        return Generation(next, true)
    }

    fun sync(generation: Generation) = syncDirectory(generation.directory)

    fun items(generation: Generation): List<Pair<String, File>> {
        val entries = generation.directory.listFiles() ?: throw IOException("secure item inventory unavailable")
        return entries.filter { it.name.endsWith(".item") }.sortedBy { it.name }.map { file ->
            val name = file.name.removeSuffix(".item")
            if (!validItemName(name) || !file.isFile || Files.isSymbolicLink(file.toPath())) {
                throw IOException("secure item inventory invalid")
            }
            name to file
        }
    }

    /** Caller holds [locked]. Opening/sealing uses the existing name-bound AES-GCM implementation. */
    fun migrate(
        open: (String, File, Boolean) -> ByteArray?,
        seal: (String, ByteArray) -> ByteArray,
        prepareKey: () -> Unit,
        deleteLegacyKey: () -> Unit,
    ) {
        val current = active()
        if (!current.hardened) {
            val legacy = items(current)
            if (legacy.isEmpty()) return
            // A previous attempt may already hold useful encrypted copies. If the old key
            // disappeared meanwhile, preserve those copies and their candidate key untouched.
            for ((name, old) in legacy) {
                val checked = open(name, old, false) ?: throw IOException("legacy item unreadable")
                checked.fill(0)
            }
            deleteTree(next)
            if (!next.mkdir()) throw IOException("secure item staging unavailable")
            prepareKey() // Reuse a candidate key; never destroy an existing key on a retry.
            for ((name, old) in legacy) {
                val plaintext = open(name, old, false) ?: throw IOException("legacy item unreadable")
                try {
                    val target = File(next, "$name.item")
                    VaultFiles.write(target, seal(name, plaintext))
                    val checked = open(name, target, true) ?: throw IOException("new item unreadable")
                    try {
                        if (!MessageDigest.isEqual(plaintext, checked)) throw IOException("secure item verification failed")
                    } finally { checked.fill(0) }
                } finally { plaintext.fill(0) }
            }
            syncDirectory(next)
            syncDirectory(directory)
            VaultFiles.write(marker, MARKER)
        } else {
            // A crash may have left a visible but not yet directory-synced commit. Validate and
            // make it durable before ever deleting the legacy key on a cleanup retry.
            for ((name, file) in items(current)) {
                val checked = open(name, file, true) ?: throw IOException("active item unreadable")
                checked.fill(0)
            }
            syncDirectory(next)
        }
        syncDirectory(directory)
        deleteLegacyKey()
        for ((_, file) in items(Generation(directory, false))) VaultFiles.delete(file)
        syncDirectory(directory)
    }

    /** The lock file contains no secret and must keep its inode while this lock is held. */
    fun clearFiles() {
        val entries = directory.listFiles() ?: throw IOException("secure item inventory unavailable")
        for (entry in entries) if (entry.name != LOCK_NAME) deleteTree(entry)
        syncDirectory(directory)
    }

    private fun deleteTree(file: File) {
        if (!file.exists() && !Files.isSymbolicLink(file.toPath())) return
        if (file.isDirectory && !Files.isSymbolicLink(file.toPath())) {
            val entries = file.listFiles() ?: throw IOException("secure item cleanup unavailable")
            for (entry in entries) deleteTree(entry)
        }
        if (!file.delete()) throw IOException("secure item cleanup failed")
    }

    companion object {
        const val NEXT_DIRECTORY = "wrapping-v2"
        const val MARKER_NAME = "wrapping-version"
        private const val LOCK_NAME = ".store.lock"
        private val MARKER = "us2us/items/wrap-v2".toByteArray(Charsets.US_ASCII)
        private val locks = ConcurrentHashMap<String, Any>()

        fun validItemName(name: String) = name.isNotEmpty() && name.all { it.isLetterOrDigit() || it == '-' }
    }
}

private fun syncItemDirectory(directory: File) {
    try {
        if (!directory.isDirectory) throw IOException("secure item directory unavailable")
        val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
    } catch (e: ErrnoException) {
        throw IOException("secure item directory sync failed", e)
    }
}
