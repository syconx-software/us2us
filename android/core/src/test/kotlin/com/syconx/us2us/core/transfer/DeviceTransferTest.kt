package com.syconx.us2us.core.transfer

import com.syconx.us2us.core.encoding.Hex
import com.syconx.us2us.core.proto.TransferRecord
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPrivateKey
import org.signal.libsignal.protocol.ecc.ECPublicKey
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID

/**
 * PROTOCOL.md §5.6: the invitation, the keys and the frames against
 * spec/test-vectors/device-transfer.json (iOS verifies the same file), and both sides of the
 * exchange over an in-memory connection.
 */
class DeviceTransferTest {
    private val vectors = Json.parseToJsonElement(
        File(System.getProperty("us2us.specDir"), "test-vectors/device-transfer.json").readText(),
    ).jsonObject

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.hex(key: String) = Hex.decode(text(key))

    /** Two ends joined back to back, like a TCP connection. */
    private class Pipe {
        class End(private val incoming: Channel<ByteArray>, private val outgoing: Channel<ByteArray>) : DeviceTransferStream {
            private var pending = ByteArray(0)
            var written = 0L
                private set

            override suspend fun read(count: Int): ByteArray {
                while (pending.size < count) {
                    val next = incoming.receiveCatching().getOrNull() ?: throw DeviceTransferException(DeviceTransferError.CLOSED)
                    pending += next
                }
                return pending.copyOfRange(0, count).also { pending = pending.copyOfRange(count, pending.size) }
            }

            override suspend fun write(bytes: ByteArray) {
                written += bytes.size
                outgoing.send(bytes)
            }

            override fun close() {
                incoming.close()
                outgoing.close()
            }
        }

        private val toReceiver = Channel<ByteArray>(Channel.UNLIMITED)
        private val toSender = Channel<ByteArray>(Channel.UNLIMITED)
        val sender = End(toSender, toReceiver)
        val receiver = End(toReceiver, toSender)
    }

    private fun temporaryFile(bytes: ByteArray? = null): File {
        val file = File(System.getProperty("java.io.tmpdir"), UUID.randomUUID().toString())
        if (bytes != null) file.writeBytes(bytes)
        file.deleteOnExit()
        return file
    }

    private val invite get() = listOf(DeviceTransferInvite.Endpoint("192.168.1.2", 5000))

    private fun errorOf(block: suspend () -> Unit): DeviceTransferError =
        assertThrows<DeviceTransferException> { runBlocking { block() } }.error

    // Shared vectors

    @Test
    fun theInvitationReadsAndWritesAsTheVectorsSayAndAnythingElseIsRefused() {
        val invite = checkNotNull(DeviceTransferInvite.parse(vectors.text("invite")))
        assertArrayEquals(vectors.hex("receiverKeyHex"), invite.receiverKey)
        assertArrayEquals(vectors.hex("secretHex"), invite.secret)
        assertEquals(
            vectors.getValue("endpoints").jsonArray.map { it.jsonPrimitive.content },
            invite.endpoints.map { "${it.host}:${it.port}" },
        )
        assertEquals(vectors.text("invite"), invite.text)
        vectors.getValue("invalidInvites").jsonArray.forEach {
            assertNull(DeviceTransferInvite.parse(it.jsonPrimitive.content), it.toString())
        }
    }

    @Test
    fun bothPhonesDeriveTheVectorsKeysAndFrames() {
        val receiver = ECPrivateKey(vectors.hex("receiverPrivateHex"))
        val sender = ECPrivateKey(vectors.hex("senderPrivateHex"))
        assertArrayEquals(vectors.hex("receiverKeyHex"), receiver.getPublicKey().serialize())
        assertArrayEquals(vectors.hex("senderKeyHex"), sender.getPublicKey().serialize())
        assertArrayEquals(vectors.hex("helloHex"), DeviceTransferHello.make(vectors.hex("senderKeyHex")))
        assertArrayEquals(vectors.hex("senderKeyHex"), DeviceTransferHello.senderKey(vectors.hex("helloHex")))

        listOf(
            receiver.calculateAgreement(ECPublicKey(vectors.hex("senderKeyHex"))),
            sender.calculateAgreement(ECPublicKey(vectors.hex("receiverKeyHex"))),
        ).forEach { shared ->
            val keys = DeviceTransferKeys(shared, vectors.hex("secretHex"), vectors.hex("receiverKeyHex"), vectors.hex("senderKeyHex"))
            assertArrayEquals(vectors.hex("senderToReceiverKeyHex"), keys.senderToReceiver)
            assertArrayEquals(vectors.hex("receiverToSenderKeyHex"), keys.receiverToSender)
        }

        val s2r = vectors.hex("senderToReceiverKeyHex")
        val r2s = vectors.hex("receiverToSenderKeyHex")
        val seals = mapOf("senderToReceiver" to DeviceTransferCipher(s2r), "receiverToSender" to DeviceTransferCipher(r2s))
        val opens = mapOf("senderToReceiver" to DeviceTransferCipher(s2r), "receiverToSender" to DeviceTransferCipher(r2s))
        vectors.getValue("frames").jsonArray.map { it.jsonObject }.forEach { frame ->
            val type = checkNotNull(DeviceTransferFrame.of(frame.getValue("type").jsonPrimitive.int.toByte()))
            val body = frame.hex("bodyHex")
            val wire = frame.hex("frameHex")
            val direction = frame.text("direction")
            assertArrayEquals(wire, seals.getValue(direction).seal(type, body))
            val (openedType, openedBody) = opens.getValue(direction).open(wire.copyOfRange(4, wire.size))
            assertEquals(type, openedType)
            assertArrayEquals(body, openedBody)
        }
    }

    @Test
    fun theHistorysRecordsReadAsTheVectorsSayAndWriteBackTheSame() {
        val records = vectors.getValue("historyRecords").jsonObject
        val wire = records.hex("messageHex")
        val record = TransferRecord.parseFrom(wire)
        val message = record.message
        assertArrayEquals(ByteArray(16) { 0x11 }, message.messageId.toByteArray())
        assertEquals(true, message.outgoing)
        assertEquals(1, message.kind)
        assertEquals(1000L, message.sentAt)
        assertEquals(1, message.status)
        assertEquals(2000L, message.acceptedAt)
        assertArrayEquals(ByteArray(16) { 0x22 }, message.reactionsList.first().reactor.toByteArray())
        assertEquals("👍", message.reactionsList.first().emoji)
        assertEquals(3000L, message.reactionsList.first().sentAt)
        assertEquals(12, message.attachmentsList.first().state)
        assertEquals(3L, message.attachmentsList.first().byteCount)
        assertEquals(true, message.attachmentsList.first().hasFile)
        assertEquals(3L, message.attachmentsList.first().fileBytes)
        assertArrayEquals(wire, record.toByteArray())
        val part = TransferRecord.parseFrom(records.hex("filePartHex"))
        assertArrayEquals(byteArrayOf(1, 2, 3), part.filePart.data.toByteArray())
    }

    @Test
    fun aFrameOutOfOrderDoesNotOpen() {
        val key = ByteArray(32) { 7 }
        val seal = DeviceTransferCipher(key)
        val open = DeviceTransferCipher(key)
        val first = seal.seal(DeviceTransferFrame.CHUNK, byteArrayOf(1))
        val second = seal.seal(DeviceTransferFrame.CHUNK, byteArrayOf(2))
        assertEquals(DeviceTransferError.NOT_AUTHENTIC, errorOf { open.open(second.copyOfRange(4, second.size)) })
        assertArrayEquals(byteArrayOf(1), open.open(first.copyOfRange(4, first.size)).second)
    }

    // The exchange

    @Test
    fun theFileAndItsPasswordArriveWhole() = runBlocking {
        val bytes = ByteArray(700_000) { (it * 31).toByte() }
        val source = temporaryFile(bytes)
        val destination = temporaryFile()
        val receiver = DeviceTransferReceiver()
        val pipe = Pipe()
        withTimeout(20_000) {
            coroutineScope {
                val password = async { receiver.receive(pipe.receiver, destination) }
                DeviceTransferSender.send(source, "k7mq-x2pd", receiver.invite(invite), pipe.sender)
                assertEquals("k7mq-x2pd", password.await())
            }
        }
        assertArrayEquals(bytes, destination.readBytes())
    }

    private class Records(private val records: List<ByteArray>) : DeviceTransferHistorySource {
        override suspend fun estimatedBytes(): Long = records.sumOf { it.size.toLong() }
        override suspend fun export(emit: suspend (ByteArray) -> Unit) = records.forEach { emit(it) }
    }

    private class Collected(private val fits: Boolean = true) : DeviceTransferHistorySink {
        val records = ArrayList<ByteArray>()
        var finished = false
        override fun canHold(bytes: Long) = fits
        override fun append(record: ByteArray) {
            records += record
        }
        override fun finish() {
            finished = true
        }
    }

    @Test
    fun theHistoryFollowsTheFileInOrderAndOnlyThenIsItReceived() = runBlocking {
        val source = temporaryFile("backup".toByteArray())
        val destination = temporaryFile()
        val records = List(5) { index -> ByteArray(1000 * (index + 1)) { index.toByte() } }
        val receiver = DeviceTransferReceiver()
        val collected = Collected()
        val pipe = Pipe()
        withTimeout(20_000) {
            coroutineScope {
                val password = async { receiver.receive(pipe.receiver, destination, collected) }
                DeviceTransferSender.send(source, "p", receiver.invite(invite), pipe.sender, Records(records))
                assertEquals("p", password.await())
            }
        }
        assertEquals(records.map { it.toList() }, collected.records.map { it.toList() })
        assertEquals(true, collected.finished)
    }

    @Test
    fun aNewPhoneWithoutRoomSaysSoAndNothingIsWritten() = runBlocking {
        val source = temporaryFile("backup".toByteArray())
        val destination = temporaryFile()
        val receiver = DeviceTransferReceiver()
        val collected = Collected(fits = false)
        val pipe = Pipe()
        withTimeout(20_000) {
            coroutineScope {
                val received = async { runCatching { receiver.receive(pipe.receiver, destination, collected) } }
                assertEquals(
                    DeviceTransferError.NO_SPACE,
                    errorOf { DeviceTransferSender.send(source, "p", receiver.invite(invite), pipe.sender, Records(listOf(byteArrayOf(1)))) },
                )
                assertEquals(DeviceTransferError.NO_SPACE, (received.await().exceptionOrNull() as DeviceTransferException).error)
            }
        }
        assertFalse(destination.exists())
        assertEquals(0, collected.records.size)
    }

    @Test
    fun theSpoolGivesBackWhatItWasGivenAcrossItsFilesAndRemovesThemAsItGoes() {
        val directory = File(System.getProperty("java.io.tmpdir"), UUID.randomUUID().toString())
        try {
            val spool = DeviceTransferSpool(directory)
            val records = List(300) { index -> ByteArray(256 * 1024) { (index % 251).toByte() } }
            records.forEach(spool::append)
            spool.finish()
            assertEquals(true, directory.list().orEmpty().size >= 2, "75 MiB takes more than one file")
            // The bytes on disk are not the records.
            val first = File(directory, "history-1").readBytes()
            assertEquals(false, first.toList().windowed(1024, 4096).any { window -> window.all { it == 5.toByte() } })
            val reader = spool.reader()
            val read = generateSequence { reader.next() }.toList()
            assertEquals(records.size, read.size)
            records.indices.forEach { assertArrayEquals(records[it], read[it]) }
            assertEquals(1.0, reader.fraction)
            assertEquals(0, directory.list().orEmpty().size)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun someoneWithoutTheQrCodesSecretGetsNothingWritten() = runBlocking {
        val source = temporaryFile("backup".toByteArray())
        val destination = temporaryFile()
        val receiver = DeviceTransferReceiver()
        val forged = DeviceTransferInvite(receiver.receiverKey, ByteArray(32) { 9 }, invite)
        val pipe = Pipe()
        withTimeout(20_000) {
            coroutineScope {
                launch {
                    // The attacker cannot open READY with the wrong secret either.
                    runCatching { DeviceTransferSender.send(source, "x", forged, pipe.sender) }
                    pipe.sender.close()
                }
                assertEquals(DeviceTransferError.CLOSED, errorOf { receiver.receive(pipe.receiver, destination) })
            }
        }
        assertFalse(destination.exists())
    }

    @Test
    fun theOldPhoneSendsNothingSecretToAPhoneThatIsNotTheOneInTheQrCode() = runBlocking {
        val source = temporaryFile("backup".toByteArray())
        val destination = temporaryFile()
        val scanned = DeviceTransferReceiver()
        val impostor = DeviceTransferReceiver(ECKeyPair.generate(), scanned.secret)
        val pipe = Pipe()
        withTimeout(20_000) {
            coroutineScope {
                val other = async { runCatching { impostor.receive(pipe.receiver, destination) } }
                assertEquals(
                    DeviceTransferError.NOT_AUTHENTIC,
                    errorOf { DeviceTransferSender.send(source, "secret", scanned.invite(invite), pipe.sender) },
                )
                // The hello and nothing after it.
                assertEquals(DeviceTransferHello.LENGTH.toLong(), pipe.sender.written)
                pipe.sender.close()
                assertNull(other.await().getOrNull())
            }
        }
    }

    /** What an end with the QR code's secret would write, for the cases the sender never produces. */
    private fun forgedSender(receiver: DeviceTransferReceiver): Pair<ByteArray, DeviceTransferCipher> {
        val pair = ECKeyPair.generate()
        val key = pair.publicKey.serialize()
        val keys = DeviceTransferKeys(
            pair.privateKey.calculateAgreement(ECPublicKey(receiver.receiverKey)), receiver.secret, receiver.receiverKey, key,
        )
        return DeviceTransferHello.make(key) to DeviceTransferCipher(keys.senderToReceiver)
    }

    private fun offer(size: Long) = ByteBuffer.allocate(16).putLong(size).putLong(0).array() + "p".toByteArray()

    @Test
    fun aTransferCutShortLeavesNoFile() = runBlocking {
        val destination = temporaryFile()
        val receiver = DeviceTransferReceiver()
        val pipe = Pipe()
        val (hello, cipher) = forgedSender(receiver)
        pipe.sender.write(hello)
        pipe.sender.write(cipher.seal(DeviceTransferFrame.OFFER, offer(10)))
        pipe.sender.write(cipher.seal(DeviceTransferFrame.CHUNK, byteArrayOf(1, 2, 3)))
        pipe.sender.write(cipher.seal(DeviceTransferFrame.END, ByteArray(0)))
        assertEquals(DeviceTransferError.CLOSED, errorOf { receiver.receive(pipe.receiver, destination) })
        assertFalse(destination.exists())
    }

    @Test
    fun anOfferOverTheLimitIsRefusedBeforeAnythingIsWritten() = runBlocking {
        val destination = temporaryFile()
        val receiver = DeviceTransferReceiver()
        val pipe = Pipe()
        val (hello, cipher) = forgedSender(receiver)
        pipe.sender.write(hello)
        pipe.sender.write(cipher.seal(DeviceTransferFrame.OFFER, offer(DeviceTransferLimits.MAX_FILE_BYTES + 1)))
        assertEquals(DeviceTransferError.TOO_LARGE, errorOf { receiver.receive(pipe.receiver, destination) })
        assertFalse(destination.exists())
    }
}
