package com.syconx.us2us.core.transfer

import com.syconx.us2us.core.crypto.AesGcm
import com.syconx.us2us.core.crypto.Hkdf
import com.syconx.us2us.core.crypto.Random
import com.syconx.us2us.core.encoding.Base64Url
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import javax.crypto.AEADBadTagException
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey

// Direct transfer to a new phone (PROTOCOL.md §5.6): the invitation in the QR code, the keys, the
// frames, and both sides of the exchange over any stream. The network itself is the runtime's.

/** What the new phone shows as a QR code. */
class DeviceTransferInvite(val receiverKey: ByteArray, val secret: ByteArray, val endpoints: List<Endpoint>) {
    data class Endpoint(val host: String, val port: Int)

    val text: String
        get() = PREFIX + Base64Url.encode(receiverKey) + ":" + Base64Url.encode(secret) + ":" +
            endpoints.joinToString(",") { "${it.host}:${it.port}" }

    companion object {
        private const val PREFIX = "us2us-transfer:1:"
        const val MAX_ENDPOINTS = 4

        /** `null` for anything but a valid invitation of this version. */
        fun parse(text: String): DeviceTransferInvite? {
            if (!text.startsWith(PREFIX)) return null
            val parts = text.removePrefix(PREFIX).split(":", limit = 3)
            if (parts.size != 3) return null
            val key = decode(parts[0])?.takeIf(::isKey) ?: return null
            val secret = decode(parts[1])?.takeIf { it.size == 32 } ?: return null
            val list = parts[2].split(",")
            if (list.size !in 1..MAX_ENDPOINTS) return null
            val endpoints = list.map { item ->
                val pieces = item.split(":")
                if (pieces.size != 2 || !isIpv4(pieces[0])) return null
                val port = pieces[1].takeIf { it.isNotEmpty() && it.all(Char::isAsciiDigit) && it.length <= 5 }?.toInt()
                if (port == null || port !in 1..65535) return null
                Endpoint(pieces[0], port)
            }
            return DeviceTransferInvite(key, secret, endpoints)
        }

        private fun decode(text: String): ByteArray? = try {
            Base64Url.decode(text)
        } catch (_: IllegalArgumentException) {
            null
        }

        internal fun isKey(key: ByteArray): Boolean = key.size == 33 && key[0] == 0x05.toByte() &&
            runCatching { ECPublicKey(key) }.isSuccess

        private fun isIpv4(host: String): Boolean {
            val octets = host.split(".")
            return octets.size == 4 && octets.all { octet ->
                octet.length in 1..3 && octet.all(Char::isAsciiDigit) && octet.toInt() <= 255
            }
        }
    }
}

private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

enum class DeviceTransferError {
    /** Not the expected opening, or a frame out of order or over a limit. */
    BAD_MESSAGE,

    /** A frame did not open: not the phone in the QR code, or not its secret. */
    NOT_AUTHENTIC,
    TOO_LARGE,

    /** The connection ended before the exchange did. */
    CLOSED,
    TIMED_OUT,

    /** The new phone has no room for what was offered, and said so. */
    NO_SPACE,
}

class DeviceTransferException(val error: DeviceTransferError) : Exception(error.name)

enum class DeviceTransferFrame(val raw: Byte) {
    READY(1),
    OFFER(2),
    CHUNK(3),
    END(4),
    RECEIVED(5),

    /** One `TransferRecord` of the history stage. */
    RECORD(6),
    ACCEPT(7),
    REFUSED(8),
    ;

    companion object {
        fun of(raw: Byte): DeviceTransferFrame? = entries.firstOrNull { it.raw == raw }
    }
}

object DeviceTransferLimits {
    const val MAX_FRAME_BYTES = 1 shl 20
    const val CHUNK_BYTES = 256 * 1024
    const val MAX_FILE_BYTES = 64L * 1024 * 1024

    /** Room a new phone keeps free besides what it was offered. */
    const val SPARE_BYTES = 100L * 1024 * 1024

    /** `REFUSED`'s reason: no room for what was offered. */
    internal const val REFUSED_NO_SPACE: Byte = 1
}

/** What the old phone sends after the account: the history stage's `TransferRecord`s. */
interface DeviceTransferHistorySource {
    /** About how many bytes [export] will emit, for the new phone's room and both meters. */
    suspend fun estimatedBytes(): Long

    /** Emits every record in order; a failing [emit] ends it. */
    suspend fun export(emit: suspend (ByteArray) -> Unit)
}

/** Where the new phone keeps the records until a vault can take them. */
interface DeviceTransferHistorySink {
    /** Whether what was offered fits on this phone. */
    fun canHold(bytes: Long): Boolean
    fun append(record: ByteArray)

    /** All records arrived and are stored. */
    fun finish()
}

/** One direction of the channel: frames in order, each under its own nonce. */
internal class DeviceTransferCipher(private val key: ByteArray) {
    private var counter = 0L

    /** `uint32_be(n) ‖ AES-256-GCM(key, nonce, type ‖ body)`; n bytes follow the length. */
    fun seal(type: DeviceTransferFrame, body: ByteArray): ByteArray {
        val ciphertext = AesGcm.seal(key, nonce(), ByteArray(0), byteArrayOf(type.raw) + body)
        counter += 1
        if (ciphertext.size > DeviceTransferLimits.MAX_FRAME_BYTES) throw DeviceTransferException(DeviceTransferError.TOO_LARGE)
        return ByteBuffer.allocate(4 + ciphertext.size).putInt(ciphertext.size).put(ciphertext).array()
    }

    /** The bytes after the length. */
    fun open(ciphertext: ByteArray): Pair<DeviceTransferFrame, ByteArray> {
        if (ciphertext.size < 17) throw DeviceTransferException(DeviceTransferError.BAD_MESSAGE)
        val plain = try {
            AesGcm.open(key, nonce(), ByteArray(0), ciphertext)
        } catch (_: AEADBadTagException) {
            throw DeviceTransferException(DeviceTransferError.NOT_AUTHENTIC)
        }
        counter += 1
        val type = DeviceTransferFrame.of(plain[0]) ?: throw DeviceTransferException(DeviceTransferError.BAD_MESSAGE)
        return type to plain.copyOfRange(1, plain.size)
    }

    private fun nonce(): ByteArray = ByteBuffer.allocate(12).putInt(0).putLong(counter).array()
}

/** The two directions' keys of one connection. */
internal class DeviceTransferKeys(shared: ByteArray, secret: ByteArray, receiverKey: ByteArray, senderKey: ByteArray) {
    private val okm = Hkdf.sha256(shared + secret, receiverKey + senderKey, "BRP/transfer/v1".toByteArray(), 64)
    val senderToReceiver: ByteArray = okm.copyOfRange(0, 32)
    val receiverToSender: ByteArray = okm.copyOfRange(32, 64)
}

/** The connection between the two phones, however it is carried. */
interface DeviceTransferStream {
    /** Exactly [count] bytes; throws [DeviceTransferError.CLOSED] when they never come. */
    suspend fun read(count: Int): ByteArray
    suspend fun write(bytes: ByteArray)
    fun close()
}

internal object DeviceTransferHello {
    val MAGIC = "BRPT".toByteArray() + byteArrayOf(1)
    val LENGTH = MAGIC.size + 33

    fun make(senderKey: ByteArray): ByteArray = MAGIC + senderKey

    fun senderKey(hello: ByteArray): ByteArray {
        if (hello.size != LENGTH || !hello.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw DeviceTransferException(DeviceTransferError.BAD_MESSAGE)
        }
        val key = hello.copyOfRange(MAGIC.size, hello.size)
        if (!DeviceTransferInvite.isKey(key)) throw DeviceTransferException(DeviceTransferError.BAD_MESSAGE)
        return key
    }
}

private suspend fun readFrame(stream: DeviceTransferStream, cipher: DeviceTransferCipher): Pair<DeviceTransferFrame, ByteArray> {
    val length = ByteBuffer.wrap(stream.read(4)).int
    if (length !in 0..DeviceTransferLimits.MAX_FRAME_BYTES) throw DeviceTransferException(DeviceTransferError.TOO_LARGE)
    return cipher.open(stream.read(length))
}

/** How far an offer got. An estimate can be short: it never reads as finished before it is. */
private class DeviceTransferMeter(private val total: Long, private val report: (Double) -> Unit) {
    private var done = 0L
    private var reported = -1

    /** Reports whole percents: a history can be hundreds of thousands of records. */
    fun add(bytes: Int) {
        done += bytes
        val fraction = if (total <= 0) 0.0 else minOf(0.99, done.toDouble() / total)
        val percent = (fraction * 100).toInt()
        if (percent == reported) return
        reported = percent
        report(fraction)
    }

    fun finish() = report(1.0)
}

/** The old phone's side. Sends nothing secret before the new phone has proved that it holds the key from the QR code. */
object DeviceTransferSender {
    suspend fun send(
        file: File,
        password: String,
        invite: DeviceTransferInvite,
        stream: DeviceTransferStream,
        history: DeviceTransferHistorySource? = null,
        progress: (Double) -> Unit = {},
    ) {
        val size = file.length()
        if (size > DeviceTransferLimits.MAX_FILE_BYTES) throw DeviceTransferException(DeviceTransferError.TOO_LARGE)
        val pair = ECKeyPair.generate()
        val senderKey = pair.publicKey.serialize()
        val keys = DeviceTransferKeys(
            pair.privateKey.calculateAgreement(ECPublicKey(invite.receiverKey)), invite.secret, invite.receiverKey, senderKey,
        )
        val outgoing = DeviceTransferCipher(keys.senderToReceiver)
        val incoming = DeviceTransferCipher(keys.receiverToSender)

        stream.write(DeviceTransferHello.make(senderKey))
        if (readFrame(stream, incoming).first != DeviceTransferFrame.READY) throw DeviceTransferException(DeviceTransferError.BAD_MESSAGE)

        val historyBytes = history?.estimatedBytes() ?: 0L
        val offer = ByteBuffer.allocate(16).putLong(size).putLong(historyBytes).array() + password.toByteArray()
        stream.write(outgoing.seal(DeviceTransferFrame.OFFER, offer))
        when (readFrame(stream, incoming).first) {
            DeviceTransferFrame.ACCEPT -> Unit
            DeviceTransferFrame.REFUSED -> throw DeviceTransferException(DeviceTransferError.NO_SPACE)
            else -> throw DeviceTransferException(DeviceTransferError.BAD_MESSAGE)
        }

        val meter = DeviceTransferMeter(size + historyBytes, progress)
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DeviceTransferLimits.CHUNK_BYTES)
            var sent = 0L
            while (sent < size) {
                val count = input.read(buffer)
                if (count <= 0) throw DeviceTransferException(DeviceTransferError.CLOSED)
                stream.write(outgoing.seal(DeviceTransferFrame.CHUNK, buffer.copyOf(count)))
                sent += count
                meter.add(count)
            }
        }
        history?.export { record ->
            stream.write(outgoing.seal(DeviceTransferFrame.RECORD, record))
            meter.add(record.size)
        }
        stream.write(outgoing.seal(DeviceTransferFrame.END, ByteArray(0)))
        if (readFrame(stream, incoming).first != DeviceTransferFrame.RECEIVED) throw DeviceTransferException(DeviceTransferError.BAD_MESSAGE)
        meter.finish()
    }
}

/**
 * The new phone's side of one connection. The key pair and the secret live as long as the
 * invitation; every connection gets keys of its own.
 */
class DeviceTransferReceiver internal constructor(private val keyPair: ECKeyPair, val secret: ByteArray) {
    /** A fresh key pair and secret for one invitation. */
    constructor() : this(ECKeyPair.generate(), Random.bytes(32))

    val receiverKey: ByteArray = keyPair.publicKey.serialize()

    fun invite(endpoints: List<DeviceTransferInvite.Endpoint>) = DeviceTransferInvite(receiverKey, secret, endpoints)

    /**
     * Writes the file to [destination], keeps the history in [history], and returns the file's
     * password. Anything wrong throws and leaves no file behind (the history is its owner's to
     * remove). [authenticated] runs once the other side has proved it read the QR code.
     */
    suspend fun receive(
        stream: DeviceTransferStream,
        destination: File,
        history: DeviceTransferHistorySink? = null,
        authenticated: () -> Unit = {},
        progress: (Double) -> Unit = {},
    ): String {
        val senderKey = DeviceTransferHello.senderKey(stream.read(DeviceTransferHello.LENGTH))
        val keys = DeviceTransferKeys(
            keyPair.privateKey.calculateAgreement(ECPublicKey(senderKey)), secret, receiverKey, senderKey,
        )
        val outgoing = DeviceTransferCipher(keys.receiverToSender)
        val incoming = DeviceTransferCipher(keys.senderToReceiver)
        stream.write(outgoing.seal(DeviceTransferFrame.READY, ByteArray(0)))

        val (type, offer) = readFrame(stream, incoming)
        if (type != DeviceTransferFrame.OFFER || offer.size <= 16) throw DeviceTransferException(DeviceTransferError.BAD_MESSAGE)
        val header = ByteBuffer.wrap(offer, 0, 16)
        val size = header.long
        val historyBytes = header.long
        if (size !in 0..DeviceTransferLimits.MAX_FILE_BYTES || historyBytes < 0 || historyBytes > Long.MAX_VALUE - size) {
            throw DeviceTransferException(DeviceTransferError.TOO_LARGE)
        }
        val password = String(offer, 16, offer.size - 16)
        authenticated()

        val needed = size + historyBytes
        if (history != null && !history.canHold(needed)) {
            stream.write(outgoing.seal(DeviceTransferFrame.REFUSED, byteArrayOf(DeviceTransferLimits.REFUSED_NO_SPACE)))
            throw DeviceTransferException(DeviceTransferError.NO_SPACE)
        }
        stream.write(outgoing.seal(DeviceTransferFrame.ACCEPT, ByteArray(0)))

        try {
            val meter = DeviceTransferMeter(needed, progress)
            destination.outputStream().use { output ->
                var written = 0L
                var inHistory = false
                while (true) {
                    val (frame, body) = readFrame(stream, incoming)
                    when {
                        frame == DeviceTransferFrame.CHUNK && !inHistory -> {
                            written += body.size
                            if (written > size) throw DeviceTransferException(DeviceTransferError.TOO_LARGE)
                            output.write(body)
                            meter.add(body.size)
                        }
                        frame == DeviceTransferFrame.RECORD -> {
                            // The whole file comes first.
                            if (written != size) throw DeviceTransferException(DeviceTransferError.BAD_MESSAGE)
                            inHistory = true
                            history?.append(body)
                            meter.add(body.size)
                        }
                        frame == DeviceTransferFrame.END -> break
                        else -> throw DeviceTransferException(DeviceTransferError.BAD_MESSAGE)
                    }
                }
                if (written != size) throw DeviceTransferException(DeviceTransferError.CLOSED)
                output.fd.sync()
            }
            history?.finish()
        } catch (e: Exception) {
            destination.delete()
            throw e
        }
        stream.write(outgoing.seal(DeviceTransferFrame.RECEIVED, ByteArray(0)))
        return password
    }
}
