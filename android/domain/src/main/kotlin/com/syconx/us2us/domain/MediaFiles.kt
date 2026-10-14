package com.syconx.us2us.domain

import java.io.File
import java.net.URI
import java.text.BreakIterator
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What a file message holds, from its name (and content type): the glyph its tile shows and where a
 * tap opens it (5.2 › Transitions: file → preview, video → player).
 */
enum class MediaFileKind {
    Pdf,
    Image,
    Movie,
    Audio,
    Other,
    ;

    companion object {
        private val pdfExtensions = setOf("pdf")
        private val movieExtensions = setOf(
            "mov", "mp4", "m4v", "avi", "mkv", "webm", "3gp", "3g2", "mpg", "mpeg", "mpe", "wmv", "flv", "qt", "mts", "m2ts",
        )
        private val audioExtensions = setOf(
            "mp3", "m4a", "m4b", "aac", "wav", "wave", "aif", "aiff", "aifc", "caf", "flac", "ogg", "oga", "opus", "amr", "mid",
            "midi", "wma", "au", "snd",
        )
        private val imageExtensions = setOf(
            "jpg", "jpeg", "jpe", "png", "gif", "heic", "heif", "webp", "tif", "tiff", "bmp", "ico", "svg", "avif", "dng", "cr2",
            "nef", "arw", "orf", "raf", "rw2", "psd",
        )

        /** The image formats the app's own photo viewer decodes on every supported Android version. */
        private val viewerExtensions = setOf("jpg", "jpeg", "jpe", "png", "gif", "webp", "bmp", "heic", "heif", "ico")

        /** From the name's extension and the content type, in this order of precedence: PDF, movie, audio, image. */
        fun of(fileName: String, contentType: String? = null): MediaFileKind {
            val ext = extension(fileName)
            val mime = contentType?.lowercase(Locale.ROOT)?.substringBefore(';')?.trim()
            return when {
                ext in pdfExtensions || mime == "application/pdf" -> Pdf
                ext in movieExtensions || mime?.startsWith("video/") == true -> Movie
                ext in audioExtensions || mime?.startsWith("audio/") == true -> Audio
                ext in imageExtensions || mime?.startsWith("image/") == true -> Image
                else -> Other
            }
        }

        /**
         * An image file the app's own photo viewer shows (a format the platform decoder reads)
         * instead of an outside viewer, which would hand the picture to other apps without telling
         * the sender, also for a disappearing message.
         */
        fun opensInPhotoViewer(fileName: String): Boolean = extension(fileName) in viewerExtensions

        internal fun extension(fileName: String): String {
            val last = fileName.substringAfterLast('/')
            val dot = last.lastIndexOf('.')
            return if (dot > 0 && dot < last.length - 1) last.substring(dot + 1).lowercase(Locale.ROOT) else ""
        }
    }
}

/**
 * A file name from the other side made safe to put on disk and to show in a preview: the sender
 * chooses it, so it may try to leave the folder (`../`), hide its real extension behind a
 * right-to-left override (`fatura‮fdp.exe` shows as “fatura exe.pdf”) or be empty.
 */
object FileNameSanitizer {
    /** The longest name kept (the extension always survives). */
    const val MAX_LENGTH = 120

    private val preferredExtensions = mapOf(
        "image/jpeg" to "jpeg", "image/png" to "png", "image/heic" to "heic", "image/heif" to "heif", "image/gif" to "gif",
        "image/webp" to "webp", "video/mp4" to "mp4", "video/quicktime" to "mov", "audio/mp4" to "m4a", "audio/mpeg" to "mp3",
        "audio/wav" to "wav", "audio/x-wav" to "wav", "application/pdf" to "pdf", "text/plain" to "txt",
        "application/zip" to "zip",
    )

    fun sanitized(name: String?, contentType: String? = null): String {
        // Only the last path component, whichever separator the sender used.
        var candidate = (name ?: "").split('/', '\\').lastOrNull { it.isNotEmpty() } ?: ""
        // No control, format (bidi overrides and isolates, zero-width marks) or line/paragraph
        // separators; no ':' (a path separator for some file systems).
        candidate = buildString {
            var index = 0
            while (index < candidate.length) {
                val cp = candidate.codePointAt(index)
                if (isKept(cp)) appendCodePoint(cp)
                index += Character.charCount(cp)
            }
        }
        // No leading dots (hidden files, “..”) or surrounding blanks.
        candidate = candidate.trimStart { it == '.' || it.isWhitespace() || Character.isSpaceChar(it) }
        candidate = candidate.trimEnd { it.isWhitespace() || Character.isSpaceChar(it) }

        val dot = candidate.lastIndexOf('.')
        var base = if (dot > 0 && dot < candidate.length - 1) candidate.substring(0, dot) else candidate
        var ext = if (dot > 0 && dot < candidate.length - 1) candidate.substring(dot + 1) else ""
        if (ext.isEmpty()) {
            contentType?.lowercase(Locale.ROOT)?.substringBefore(';')?.trim()?.let { preferredExtensions[it] }?.let { ext = it }
        }
        if (base.isEmpty()) base = "file"
        ext = prefixCharacters(ext, 16)
        val room = maxOf(1, MAX_LENGTH - if (ext.isEmpty()) 0 else characterCount(ext) + 1)
        if (characterCount(base) > room) base = prefixCharacters(base, room)
        return if (ext.isEmpty()) base else "$base.$ext"
    }

    private fun isKept(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
        Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
        Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED,
        -> false
        else -> cp != ':'.code
    }

    /** User-perceived characters (grapheme clusters), as Swift counts a `String`. */
    private fun characterCount(text: String): Int {
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(text)
        var count = 0
        while (iterator.next() != BreakIterator.DONE) count++
        return count
    }

    private fun prefixCharacters(text: String, count: Int): String {
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(text)
        var end = 0
        repeat(count) {
            val next = iterator.next()
            if (next == BreakIterator.DONE) return text
            end = next
        }
        return text.substring(0, end)
    }
}

/**
 * The one place a decrypted file exists on disk outside the runtime's own staging: outside
 * previews and the document exporter take only files, so a file message is previewed and saved from
 * a named copy here.
 *
 * `<cache>/preview/<random>/<sanitised name>` in the app's private storage. The copy is deleted when
 * its preview or export closes ([discard]), and everything here when the app goes to the
 * background, when the conversation closes, on “Her şeyi sil” and at every launch ([removeAll]).
 * Photos and videos never come here: they are shown and played from memory.
 */
object ProtectedPreviewFiles {
    /** The folder; the app's cache directory by default (`java.io.tmpdir` on Android). */
    @Volatile
    var root: File = File(System.getProperty("java.io.tmpdir"), "preview")

    /** Writes [payload] (off the main thread); `null` if it could not be written. */
    suspend fun write(payload: MediaPayload): URI? = withContext(Dispatchers.IO) {
        writeNow(payload.data, FileNameSanitizer.sanitized(payload.fileName, payload.contentType))
    }

    /** Writes [data] as [name] (already sanitised) into a fresh folder. */
    fun writeNow(data: Bytes, name: String): URI? {
        val folder = File(root, UUID.randomUUID().toString())
        return try {
            root.mkdirs()
            if (!folder.mkdir()) return null
            val file = File(folder, name)
            file.writeBytes(data.toByteArray())
            file.toURI()
        } catch (_: Exception) {
            folder.deleteRecursively()
            null
        }
    }

    /** Deletes one copy and its folder (only inside the preview folder). */
    fun discard(uri: URI) {
        val file = runCatching { File(uri) }.getOrNull() ?: return
        val folder = file.absoluteFile.parentFile ?: return
        if (folder.parentFile?.canonicalPath != root.canonicalPath) return
        folder.deleteRecursively()
    }

    /** Deletes every copy. */
    fun removeAll() {
        root.deleteRecursively()
    }

    /** The copies that exist right now (tests). */
    val existingFiles: List<File>
        get() = root.listFiles().orEmpty().flatMap { it.listFiles().orEmpty().toList() }
}

/**
 * Where a voice message is recorded: the recorder writes only to a file, so it gets one here, built
 * like the preview copies: `<cache>/voice/<random>/voice.m4a` in the app's private storage.
 *
 * The recording lives on disk only while it is being recorded: when it stops, its bytes are read
 * into memory and the file goes with its folder at once ([consume]); a cancelled recording is
 * deleted ([discard]), and everything here when the app goes to the background, on “Her şeyi sil”
 * and at every launch ([removeAll]). Received voice messages never come here: they are played from
 * memory.
 */
object VoiceRecordingFiles {
    @Volatile
    var root: File = File(System.getProperty("java.io.tmpdir"), "voice")

    /** A file in a fresh folder (nothing written yet); `null` if the folder could not be made. */
    fun makeFile(): File? {
        val folder = File(root, UUID.randomUUID().toString())
        return try {
            root.mkdirs()
            if (!folder.mkdir()) null else File(folder, "voice.m4a")
        } catch (_: Exception) {
            folder.deleteRecursively()
            null
        }
    }

    /** Reads a finished recording into memory and deletes it, with its folder, right away. `null` when there is nothing (or nothing readable). */
    fun consume(file: File): Bytes? {
        try {
            val data = runCatching { file.readBytes() }.getOrNull() ?: return null
            return if (data.isEmpty()) null else Bytes.wrap(data)
        } finally {
            discard(file)
        }
    }

    /** Deletes one recording and its folder (only inside the voice folder). */
    fun discard(file: File) {
        val folder = file.absoluteFile.parentFile ?: return
        if (folder.parentFile?.canonicalPath != root.canonicalPath) return
        folder.deleteRecursively()
    }

    /** Deletes every recording left behind (a crash or a kill mid-recording). */
    fun removeAll() {
        root.deleteRecursively()
    }

    /** The recordings on disk right now (tests). */
    val existingFiles: List<File>
        get() = root.listFiles().orEmpty().flatMap { it.listFiles().orEmpty().toList() }
}

/**
 * Plays decrypted media from memory: the player asks for byte ranges and gets slices of the
 * payload, so no plaintext file is written for playback (the rule for every decrypted attachment:
 * memory only, unless a system component cannot work without a file).
 */
object MemoryMediaRanges {
    /** The largest slice answered at once (a request to the end of a 64 MiB clip is served in pieces, not one copy). */
    const val CHUNK_BYTES = 1 shl 20

    /**
     * The bytes to answer for a request: from [offset], [length] of them (or everything up to the
     * end with [toEnd]), clamped to the clip of [total] bytes; `null` when the request starts outside it.
     */
    fun byteRange(offset: Long, length: Int, toEnd: Boolean, total: Int): IntRange? {
        if (offset < 0 || offset >= total) return null
        val start = offset.toInt()
        val end = if (toEnd) total else minOf(total.toLong(), start.toLong() + maxOf(0, length)).toInt()
        return if (end > start) start until end else null
    }
}
