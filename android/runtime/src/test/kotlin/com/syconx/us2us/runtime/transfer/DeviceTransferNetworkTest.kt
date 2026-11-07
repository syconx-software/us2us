package com.syconx.us2us.runtime.transfer

import com.syconx.us2us.core.transfer.DeviceTransferInvite
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Real sockets on this machine's own network addresses: the whole of PROTOCOL.md §5.6 but the phones. */
class DeviceTransferNetworkTest {
    private fun directory(): File = Files.createTempDirectory("transfer").toFile().also { it.deleteOnExit() }

    private fun source(bytes: ByteArray): File = File(directory(), "backup").also { it.writeBytes(bytes) }

    private fun listening(): DeviceTransferListening = try {
        DeviceTransferListening(directory()).also { it.start() }
    } catch (e: DeviceTransferNetworkException) {
        throw AssertionError("this machine needs a local network address for the test", e)
    }

    /** Before connecting: the new phone gives a connection only seconds to reach OFFER. */
    @Test
    fun theEstimateIsMadeBeforeConnecting() = runBlocking {
        class Failure : RuntimeException()
        val history = object : com.syconx.us2us.core.transfer.DeviceTransferHistorySource {
            override suspend fun estimatedBytes(): Long = throw Failure()
            override suspend fun export(emit: suspend (ByteArray) -> Unit) = Unit
        }
        // Nobody answers here: reaching for it first would fail differently.
        val invitation = com.syconx.us2us.core.transfer.DeviceTransferReceiver()
            .invite(listOf(DeviceTransferInvite.Endpoint("10.255.255.1", 9))).text
        try {
            DeviceTransferSending.send(source(byteArrayOf(1)), "p", invitation, history) {}
            fail("it fails on the estimate")
        } catch (_: Failure) {
        }
    }

    @Test
    fun theFileAndItsPasswordCrossARealConnection() = runBlocking {
        val bytes = ByteArray(1_500_000) { (it * 17).toByte() }
        val listener = listening()
        val started = AtomicInteger(0)
        var lastProgress = 0.0
        listener.onStarted = { started.incrementAndGet() }
        listener.onProgress = { lastProgress = it }
        try {
            withTimeout(30_000) {
                val delivered = async { listener.waitForDelivery() }
                var sent = 0.0
                DeviceTransferSending.send(source(bytes), "k7mq-x2pd", listener.invitation) { sent = it }
                val delivery = delivered.await()
                assertEquals("k7mq-x2pd", delivery.password)
                assertArrayEquals(bytes, delivery.file.readBytes())
                assertEquals(1.0, sent, 0.0)
            }
            assertEquals(1, started.get())
            assertTrue("the meter got to the end", lastProgress >= 0.99)
        } finally {
            listener.stop()
            listener.removeFiles()
        }
    }

    @Test
    fun aStrangerWithoutTheCodeDoesNotEndTheWait() = runBlocking {
        val bytes = "backup".toByteArray()
        val listener = listening()
        val interrupted = AtomicInteger(0)
        listener.onInterrupted = { interrupted.incrementAndGet() }
        val invite = checkNotNull(DeviceTransferInvite.parse(listener.invitation))
        val forged = DeviceTransferInvite(invite.receiverKey, ByteArray(32) { 9 }, invite.endpoints)
        try {
            try {
                DeviceTransferSending.send(source(bytes), "x", forged.text) {}
                fail("a phone without the secret gets no answer it can read")
            } catch (_: Exception) {
            }
            // The real phone still gets through afterwards, on the same code.
            withTimeout(30_000) {
                val delivered = async { listener.waitForDelivery() }
                DeviceTransferSending.send(source(bytes), "right", listener.invitation) {}
                assertEquals("right", delivered.await().password)
            }
            assertEquals("one that never proved itself is no interruption", 0, interrupted.get())
        } finally {
            listener.stop()
            listener.removeFiles()
        }
    }

    @Test
    fun anAddressNobodyAnswersIsReportedAsUnreachable() = runBlocking {
        // A port just freed on the loopback: refused straight away.
        val port = ServerSocket(0).use { it.localPort }
        val invite = DeviceTransferInvite(
            checkNotNull(DeviceTransferInvite.parse(listening().also { it.stop() }.invitation)).receiverKey,
            ByteArray(32) { 1 },
            listOf(DeviceTransferInvite.Endpoint("127.0.0.1", port)),
        )
        try {
            DeviceTransferSending.send(source("x".toByteArray()), "p", invite.text) {}
            fail("nobody listens there")
        } catch (e: DeviceTransferNetworkException) {
            assertEquals(DeviceTransferNetworkException.Reason.UNREACHABLE, e.reason)
        }
    }

    @Test
    fun aCodeThatPointsAtTheInternetIsNeverConnectedTo() = runBlocking {
        val listener = listening()
        val invite = checkNotNull(DeviceTransferInvite.parse(listener.invitation))
        val trap = DeviceTransferInvite(invite.receiverKey, invite.secret, listOf(DeviceTransferInvite.Endpoint("8.8.8.8", 443)))
        try {
            DeviceTransferSending.send(source("x".toByteArray()), "p", trap.text) {}
            fail("nothing leaves for a public address")
        } catch (e: DeviceTransferNetworkException) {
            assertEquals(DeviceTransferNetworkException.Reason.UNREACHABLE, e.reason)
        } finally {
            listener.stop()
            listener.removeFiles()
        }
    }

    @Test
    fun stoppingEndsATransferUnderway() = runBlocking {
        val listener = listening()
        listener.onStarted = { listener.stop() }
        try {
            DeviceTransferSending.send(source(ByteArray(3_000_000) { 7 }), "p", listener.invitation) {}
            fail("the old phone does not hear “received”")
        } catch (_: Exception) {
        } finally {
            listener.removeFiles()
        }
    }

    @Test
    fun oneAddressThatAnswersIsEnoughWhenTheOtherDoesNot() = runBlocking {
        val listener = listening()
        val invite = checkNotNull(DeviceTransferInvite.parse(listener.invitation))
        val refused = ServerSocket(0).use { it.localPort }
        val mixed = DeviceTransferInvite(
            invite.receiverKey,
            invite.secret,
            listOf(DeviceTransferInvite.Endpoint("127.0.0.1", refused)) + invite.endpoints.take(1),
        )
        try {
            withTimeout(30_000) {
                val delivered = async { listener.waitForDelivery() }
                DeviceTransferSending.send(source("backup".toByteArray()), "p", mixed.text) {}
                assertEquals("p", delivered.await().password)
            }
        } finally {
            listener.stop()
            listener.removeFiles()
        }
    }

    @Test
    fun stoppingEndsTheInvitationAndRemoveFilesLeavesNothing() = runBlocking {
        val folder = directory()
        val listener = DeviceTransferListening(folder).also { it.start() }
        val invitation = listener.invitation
        assertNotNull(DeviceTransferInvite.parse(invitation))
        listener.stop()
        try {
            DeviceTransferSending.send(source("x".toByteArray()), "p", invitation) {}
            fail("a stopped invitation takes no connection")
        } catch (e: DeviceTransferNetworkException) {
            assertEquals(DeviceTransferNetworkException.Reason.UNREACHABLE, e.reason)
        }
        listener.removeFiles()
        assertFalse(folder.exists())
    }

    @Test
    fun onlyPrivateAddressesCount() {
        listOf("10.0.0.1", "192.168.1.20", "172.16.0.1", "172.31.255.255", "169.254.1.1").forEach {
            assertTrue(it, DeviceTransferAddresses.isPrivate(it))
        }
        listOf("8.8.8.8", "172.15.0.1", "172.32.0.1", "100.64.0.1", "127.0.0.1", "not-an-ip").forEach {
            assertFalse(it, DeviceTransferAddresses.isPrivate(it))
        }
        DeviceTransferAddresses.local().forEach { assertTrue(it, DeviceTransferAddresses.isPrivate(it)) }
    }
}
