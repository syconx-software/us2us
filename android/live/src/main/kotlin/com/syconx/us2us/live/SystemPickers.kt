package com.syconx.us2us.live

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.net.toUri
import com.syconx.us2us.domain.FileNameSanitizer
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.UUID

/**
 * Where the picks of “+” go before they are sent. The system photo picker (photos and videos, no
 * library permission, up to ten at a time) and the document picker run out of process and hand
 * over `content:` URIs (the conversation launches them, under the external-activity guard). Each
 * picked file is copied into a private folder of its own (`<cache>/picked/<random>/`) that the
 * runtime prepares it from (metadata stripped, re-encoded). The copy goes with its folder once it
 * was sent or given up ([discardPicked]), and every copy at launch and on “Her şeyi sil”
 * ([removeAllPicked]).
 */
class SystemPickers(private val root: File) {
    constructor(context: Context) : this(File(context.cacheDir, "picked"))

    /** A fresh private folder for one picked copy. */
    fun pickDirectory(): File? {
        val directory = File(root, UUID.randomUUID().toString())
        return try {
            root.mkdirs()
            if (directory.mkdir()) directory else null
        } catch (_: SecurityException) {
            null
        }
    }

    /** The picked copy was sent or given up: it goes, with its folder. */
    fun discardPicked(file: File) {
        val folder = file.absoluteFile.parentFile
        if (folder != null && folder.parentFile?.canonicalPath == root.canonicalPath) {
            folder.deleteRecursively()
        } else {
            file.delete()
        }
    }

    /** Every picked copy (launch, “Her şeyi sil”). */
    fun removeAllPicked() {
        root.deleteRecursively()
    }

    /** Whether [file] is a copy in the pick folder. */
    fun isPicked(file: File): Boolean = file.absoluteFile.parentFile?.parentFile?.canonicalPath == root.canonicalPath

    /**
     * [uri] (a picker's `content:` URI, or a `file:` URI) as a copy of its own in the pick folder;
     * a file already there is used as it is. `null` when it cannot be read. Call it off the main
     * thread.
     */
    fun copy(uri: URI, resolver: ContentResolver): File? {
        if (uri.scheme == "file") {
            val file = runCatching { File(uri) }.getOrNull() ?: return null
            if (isPicked(file)) return file.takeIf { it.isFile }
            return copyFile(file)
        }
        val android = uri.toString().toUri()
        val name = FileNameSanitizer.sanitized(displayName(resolver, android), resolver.getType(android))
        val directory = pickDirectory() ?: return null
        val destination = File(directory, name)
        return try {
            val input = resolver.openInputStream(android) ?: throw IOException("no stream")
            input.use { stream -> destination.outputStream().use { stream.copyTo(it) } }
            destination
        } catch (_: Exception) {
            directory.deleteRecursively()
            null
        }
    }

    private fun copyFile(file: File): File? {
        val directory = pickDirectory() ?: return null
        val destination = File(directory, file.name)
        return try {
            file.copyTo(destination)
        } catch (_: IOException) {
            directory.deleteRecursively()
            null
        }
    }

    companion object {
        /** The name the provider gives the file, if any (the document picker's; the photo picker's are generic). */
        fun displayName(resolver: ContentResolver, uri: Uri): String? = try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }

        /** The size the provider reports, if any. */
        fun size(resolver: ContentResolver, uri: Uri): Long? = try {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }
}
