package com.syconx.us2us.core.transfer

import com.syconx.us2us.core.crypto.Random
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * What a new phone received of the history, kept until a vault can take it: the new secret code is
 * chosen only after the transfer (PROTOCOL.md §5.6). Encrypted under a key that lives in memory
 * only, so a spool outlives neither the process nor a crash in a form anyone can read. Its files go
 * as they are read, so the phone never holds the history twice over.
 */
class DeviceTransferSpool(private val directory: File) : DeviceTransferHistorySink {
    private val key = Random.bytes(32)
    private val sealing = DeviceTransferCipher(key)
    private val lock = Any()
    private var raw: FileOutputStream? = null
    private var output: BufferedOutputStream? = null
    private var segment = 0
    private var segmentWritten = 0L
    private var recordBytes = 0L

    init {
        directory.mkdirs()
    }

    override fun canHold(bytes: Long): Boolean {
        if (bytes < 0 || bytes > Long.MAX_VALUE - DeviceTransferLimits.SPARE_BYTES) return false
        return directory.usableSpace >= bytes + DeviceTransferLimits.SPARE_BYTES
    }

    override fun append(record: ByteArray) = synchronized(lock) {
        val frame = sealing.seal(DeviceTransferFrame.RECORD, record)
        if (output == null || segmentWritten >= SEGMENT_BYTES) {
            closeOutput()
            segment += 1
            segmentWritten = 0
            val stream = FileOutputStream(file(segment))
            raw = stream
            output = BufferedOutputStream(stream, 256 * 1024)
        }
        output?.write(frame)
        segmentWritten += frame.size
        recordBytes += record.size
    }

    override fun finish() = synchronized(lock) { closeOutput() }

    /** Reads the records back, in order, removing each file once it is read. */
    fun reader(): DeviceTransferSpoolReader = synchronized(lock) {
        DeviceTransferSpoolReader(key, (1..segment).map(::file), recordBytes)
    }

    /** Everything goes, also what was not read. */
    fun remove() {
        synchronized(lock) {
            try {
                closeOutput()
            } catch (_: java.io.IOException) {
            }
        }
        directory.deleteRecursively()
    }

    private fun closeOutput() {
        output?.flush()
        raw?.fd?.sync()
        output?.close()
        output = null
        raw = null
    }

    private fun file(number: Int) = File(directory, "history-$number")

    internal companion object {
        /** One file's size, before the next one starts. */
        const val SEGMENT_BYTES = 32L * 1024 * 1024
    }
}

class DeviceTransferSpoolReader internal constructor(key: ByteArray, files: List<File>, private val totalBytes: Long) {
    private val cipher = DeviceTransferCipher(key)
    private val files = ArrayDeque(files)
    private var input: DataInputStream? = null
    private var current: File? = null
    private var readBytes = 0L

    /** How much of the history has been read (0…1). */
    val fraction: Double get() = if (totalBytes == 0L) 1.0 else minOf(1.0, readBytes.toDouble() / totalBytes)

    /** The next record; `null` at the end. */
    fun next(): ByteArray? {
        while (true) {
            val stream = input ?: run {
                val file = files.removeFirstOrNull() ?: return null
                current = file
                DataInputStream(BufferedInputStream(FileInputStream(file), 256 * 1024)).also { input = it }
            }
            val length = try {
                stream.readInt()
            } catch (_: EOFException) {
                stream.close()
                input = null
                current?.delete()
                current = null
                continue
            }
            if (length !in 0..DeviceTransferLimits.MAX_FRAME_BYTES) throw DeviceTransferException(DeviceTransferError.CLOSED)
            val sealed = ByteArray(length)
            try {
                stream.readFully(sealed)
            } catch (_: EOFException) {
                throw DeviceTransferException(DeviceTransferError.CLOSED)
            }
            val (type, body) = cipher.open(sealed)
            if (type != DeviceTransferFrame.RECORD) throw DeviceTransferException(DeviceTransferError.BAD_MESSAGE)
            readBytes += body.size
            return body
        }
    }
}
