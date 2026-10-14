package com.syconx.us2us.runtime.vault

import android.os.SystemClock
import android.util.Log
import com.syconx.us2us.core.vault.BootClock
import com.syconx.us2us.core.vault.UnlockAttemptStore
import com.syconx.us2us.core.vault.UnlockAttempts
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/**
 * Time since boot including deep sleep (`SystemClock.elapsedRealtimeNanos`), the clock the
 * wrong-code delays are measured with (PROTOCOL.md §5.2). `System.nanoTime` stops while the phone
 * sleeps, so it would only make delays last longer, but this is the clock the spec names.
 */
object AndroidBootClock : BootClock {
    override fun sinceBoot(): Duration = SystemClock.elapsedRealtimeNanos().nanoseconds
}

/**
 * Whole-file reads and atomic writes for the small files next to the vault: the new content goes
 * to a temporary file in the same directory, is synced, and is renamed over the old file, so a
 * crash leaves either the old file or the new one, never a torn write. Unlike
 * `androidx.core.util.AtomicFile`, a failed sync or rename is an error here: the attempt counter
 * must be on disk before a code is checked (§5.2).
 */
internal object VaultFiles {
    private const val PENDING_SUFFIX = ".pending"

    fun read(file: File): ByteArray? = try {
        file.readBytes()
    } catch (_: FileNotFoundException) {
        null
    }

    fun write(file: File, bytes: ByteArray) {
        val directory = file.absoluteFile.parentFile ?: throw IOException("no directory for ${file.name}")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("cannot create ${directory.name}")
        val pending = File(directory, file.name + PENDING_SUFFIX)
        try {
            FileOutputStream(pending).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            if (!pending.renameTo(file)) throw IOException("cannot replace ${file.name}")
        } catch (e: IOException) {
            pending.delete()
            throw e
        }
    }

    fun delete(file: File) {
        File(file.absoluteFile.parentFile, file.name + PENDING_SUFFIX).delete()
        if (file.exists() && !file.delete()) throw IOException("cannot delete ${file.name}")
    }
}

/**
 * The wrong-code counter of §5.2 as a small JSON file next to the vault, readable while locked.
 * [save] is durable when it returns (an atomic, synced write): the attempt is recorded before the
 * code is checked.
 *
 * ```
 * {"failures": 3, "waitedNanos": 12000000000, "bootClockNanos": 98765000000000}
 * ```
 *
 * An unreadable file counts as no failures, as on iOS: whoever can rewrite it can delete it.
 */
internal class FileUnlockAttemptStore(private val file: File) : UnlockAttemptStore {
    override fun load(): UnlockAttempts {
        val bytes = VaultFiles.read(file) ?: return UnlockAttempts()
        return try {
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            UnlockAttempts(
                failures = json.getInt(FAILURES).coerceAtLeast(0),
                waited = json.getLong(WAITED).coerceAtLeast(0).nanoseconds,
                bootClockReading = if (json.isNull(READING)) null else json.getLong(READING).nanoseconds,
            )
        } catch (e: JSONException) {
            Log.w(VaultLog.TAG, "attempt counter unreadable")
            UnlockAttempts()
        }
    }

    override fun save(attempts: UnlockAttempts) {
        val json = JSONObject()
            .put(FAILURES, attempts.failures)
            .put(WAITED, attempts.waited.inWholeNanoseconds)
            .put(READING, attempts.bootClockReading?.inWholeNanoseconds ?: JSONObject.NULL)
        VaultFiles.write(file, json.toString().toByteArray(Charsets.UTF_8))
    }

    fun delete() = VaultFiles.delete(file)

    private companion object {
        const val FAILURES = "failures"
        const val WAITED = "waitedNanos"
        const val READING = "bootClockNanos"
    }
}

/** The vault's log lines: no codes, keys, names or paths, only what happened. */
internal object VaultLog {
    const val TAG = "us2us.vault"

    fun info(message: String) {
        Log.i(TAG, message)
    }

    fun notice(message: String) {
        Log.w(TAG, message)
    }

    fun error(message: String) {
        Log.e(TAG, message)
    }
}
