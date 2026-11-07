package com.syconx.us2us.runtime.transfer

import com.syconx.us2us.core.transfer.DeviceTransferError
import com.syconx.us2us.core.transfer.DeviceTransferException
import com.syconx.us2us.core.transfer.DeviceTransferHistorySource
import com.syconx.us2us.core.transfer.DeviceTransferInvite
import com.syconx.us2us.core.transfer.DeviceTransferReceiver
import com.syconx.us2us.core.transfer.DeviceTransferSender
import com.syconx.us2us.core.transfer.DeviceTransferSpool
import com.syconx.us2us.core.transfer.DeviceTransferStream
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The network of a direct transfer (PROTOCOL.md §5.6): plain TCP between two phones on the same
// network. Everything secret is already encrypted by the channel in core; this file only moves
// bytes and never touches the relay.

/** What the new phone received: a backup file in the app's private storage and its password. */
class DeviceTransferDelivery(
    val file: File,
    val password: String,
    /** The history, until the new vault takes it in. */
    val history: DeviceTransferSpool,
)

class DeviceTransferNetworkException(val reason: Reason) : Exception(reason.name) {
    enum class Reason {
        /** This phone has no address on a local network (no Wi-Fi, no hotspot). */
        NO_LOCAL_NETWORK,

        /** None of the invitation's addresses answered: not on the same network. */
        UNREACHABLE,
    }
}

/** A read or write that stays unanswered ends the connection: between two phones side by side nothing takes this long. */
private const val STALL_LIMIT_MS = 20_000

internal class SocketTransferStream(private val socket: Socket) : DeviceTransferStream {
    override suspend fun read(count: Int): ByteArray = withContext(Dispatchers.IO) {
        val bytes = ByteArray(count)
        var filled = 0
        try {
            val input = socket.getInputStream()
            while (filled < count) {
                val read = input.read(bytes, filled, count - filled)
                if (read < 0) throw DeviceTransferException(DeviceTransferError.CLOSED)
                filled += read
            }
        } catch (_: SocketTimeoutException) {
            close()
            throw DeviceTransferException(DeviceTransferError.TIMED_OUT)
        } catch (_: IOException) {
            throw DeviceTransferException(DeviceTransferError.CLOSED)
        }
        bytes
    }

    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        // The socket's timeout only covers reads: a peer that stopped reading ends here.
        val stall = launch {
            delay(STALL_LIMIT_MS.toLong())
            close()
        }
        try {
            socket.getOutputStream().write(bytes)
            socket.getOutputStream().flush()
        } catch (_: IOException) {
            throw DeviceTransferException(DeviceTransferError.CLOSED)
        } finally {
            stall.cancel()
        }
    }

    override fun close() {
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }
}

/** The new phone's listener for one invitation. */
class DeviceTransferListening(private val directory: File) {
    private val receiver = DeviceTransferReceiver()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val server = ServerSocket(0)
    private val delivery = CompletableDeferred<DeviceTransferDelivery>()
    private val open = CopyOnWriteArrayList<SocketTransferStream>()
    @Volatile private var stopped = false

    var invitation = ""
        private set

    /** A phone that read the QR code connected. */
    @Volatile var onStarted: (() -> Unit)? = null
    @Volatile var onProgress: ((Double) -> Unit)? = null

    /** The connection of a phone that read the QR code broke: it may try again. */
    @Volatile var onInterrupted: (() -> Unit)? = null

    /** What was offered does not fit on this phone, and the old phone was told. */
    @Volatile var onNoSpace: (() -> Unit)? = null

    /** Up and listening: [invitation] is ready to show. */
    fun start() {
        val addresses = DeviceTransferAddresses.local()
        if (addresses.isEmpty()) {
            server.close()
            throw DeviceTransferNetworkException(DeviceTransferNetworkException.Reason.NO_LOCAL_NETWORK)
        }
        // Whatever a killed app left behind goes: it cannot be opened without its password anyway.
        directory.deleteRecursively()
        directory.mkdirs()
        invitation = receiver.invite(
            addresses.take(DeviceTransferInvite.MAX_ENDPOINTS).map { DeviceTransferInvite.Endpoint(it, server.localPort) },
        ).text
        scope.launch {
            while (!stopped) {
                val socket = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                if (open.size >= MAX_CONNECTIONS) {
                    socket.close()
                } else {
                    serve(socket)
                }
            }
        }
    }

    /** The first complete file. Connections that fail do not end the wait. */
    suspend fun waitForDelivery(): DeviceTransferDelivery = delivery.await()

    /** Ends the invitation: no further connection is taken. */
    fun stop() {
        stopped = true
        try {
            server.close()
        } catch (_: IOException) {
        }
        open.forEach { it.close() }
        delivery.cancel()
        scope.cancel()
    }

    /** The directory and what is in it (after the file was handed on, or given up). */
    fun removeFiles() {
        directory.deleteRecursively()
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = STALL_LIMIT_MS
        val stream = SocketTransferStream(socket)
        open += stream
        // A folder per connection: the file keeps a name fit to show on 7.3's card.
        val folder = File(directory, UUID.randomUUID().toString())
        val destination = File(folder, FILE_NAME)
        var started = false
        val handshake = scope.launch {
            delay(HANDSHAKE_LIMIT_MS)
            stream.close()
        }
        val history = DeviceTransferSpool(File(folder, "history"))
        scope.launch {
            try {
                folder.mkdirs()
                val password = receiver.receive(
                    stream,
                    destination,
                    history,
                    authenticated = {
                        handshake.cancel()
                        started = true
                        onStarted?.invoke()
                    },
                    progress = { onProgress?.invoke(it) },
                )
                delivery.complete(DeviceTransferDelivery(destination, password, history))
            } catch (e: Exception) {
                folder.deleteRecursively()
                if ((e as? DeviceTransferException)?.error == DeviceTransferError.NO_SPACE) {
                    onNoSpace?.invoke()
                } else if (started && !stopped) {
                    onInterrupted?.invoke()
                }
            } finally {
                handshake.cancel()
                stream.close()
                open -= stream
            }
        }
    }

    private companion object {
        /** How long a connection has to prove it read the QR code. */
        const val HANDSHAKE_LIMIT_MS = 10_000L
        const val MAX_CONNECTIONS = 8
        const val FILE_NAME = "transfer.u2u"
    }
}

/** The old phone's side: the first of the invitation's addresses to answer gets the file. */
object DeviceTransferSending {
    /** How long the phones get to find each other. */
    private const val CONNECT_LIMIT_MS = 20_000

    suspend fun send(file: File, password: String, invitation: String, history: DeviceTransferHistorySource? = null, progress: (Double) -> Unit) {
        val invite = DeviceTransferInvite.parse(invitation) ?: throw DeviceTransferException(DeviceTransferError.BAD_MESSAGE)
        // Before connecting: over a long history this takes a moment, and the new phone gives a
        // connection only seconds to reach OFFER.
        val estimated = history?.let { EstimatedHistory(it, it.estimatedBytes()) }
        val stream = SocketTransferStream(connect(invite))
        try {
            coroutineScope {
                // A blocking read or write does not notice cancellation; a closed socket ends it.
                val closer = launch(Dispatchers.IO) {
                    try {
                        awaitCancellation()
                    } finally {
                        stream.close()
                    }
                }
                try {
                    DeviceTransferSender.send(file, password, invite, stream, estimated, progress)
                } finally {
                    closer.cancel()
                }
            }
        } finally {
            stream.close()
        }
    }

    private class EstimatedHistory(private val source: DeviceTransferHistorySource, private val bytes: Long) : DeviceTransferHistorySource {
        override suspend fun estimatedBytes(): Long = bytes

        override suspend fun export(emit: suspend (ByteArray) -> Unit) = source.export(emit)
    }

    private suspend fun connect(invite: DeviceTransferInvite): Socket {
        // A code comes from a phone side by side: one sending this account to the internet is a trap, whoever made it.
        val endpoints = invite.endpoints.filter { DeviceTransferAddresses.isPrivate(it.host) }
        if (endpoints.isEmpty()) throw DeviceTransferNetworkException(DeviceTransferNetworkException.Reason.UNREACHABLE)
        val winner = CompletableDeferred<Socket?>()
        val unanswered = AtomicInteger(endpoints.size)
        val sockets = endpoints.map { Socket().apply { soTimeout = STALL_LIMIT_MS } }
        // Not joined: an address that never answers must not hold up the one that did.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        endpoints.zip(sockets).forEach { (endpoint, socket) ->
            scope.launch {
                try {
                    socket.connect(InetSocketAddress(endpoint.host, endpoint.port), CONNECT_LIMIT_MS)
                    winner.complete(socket)
                } catch (_: IOException) {
                    if (unanswered.decrementAndGet() == 0) winner.complete(null)
                }
            }
        }
        val answered = try {
            winner.await()
        } catch (cancelled: CancellationException) {
            sockets.forEach(::closeQuietly)
            throw cancelled
        }
        sockets.filter { it !== answered }.forEach(::closeQuietly)
        return answered ?: throw DeviceTransferNetworkException(DeviceTransferNetworkException.Reason.UNREACHABLE)
    }

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }
}

/** This phone's IPv4 addresses on local networks (Wi-Fi, hotspot), Wi-Fi first. */
object DeviceTransferAddresses {
    fun local(): List<String> {
        val found = mutableListOf<Pair<String, String>>()
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (_: IOException) {
            emptyList()
        }
        for (network in interfaces) {
            val usable = try {
                network.isUp && !network.isLoopback && !network.isVirtual && !network.isPointToPoint
            } catch (_: IOException) {
                false
            }
            if (!usable || CELLULAR.any(network.name::startsWith)) continue
            for (address in network.inetAddresses) {
                val text = (address as? Inet4Address)?.hostAddress ?: continue
                if (isPrivate(text) && found.none { it.second == text }) found += network.name to text
            }
        }
        return found.sortedBy { (name, _) -> if (name.startsWith("wlan")) 0 else 1 }.map { it.second }
    }

    /** RFC 1918 and link-local addresses: nothing routed over the internet. */
    fun isPrivate(address: String): Boolean {
        val octets = address.split(".").mapNotNull { it.toIntOrNull() }
        if (octets.size != 4) return false
        return when {
            octets[0] == 10 -> true
            octets[0] == 192 && octets[1] == 168 -> true
            octets[0] == 169 && octets[1] == 254 -> true
            octets[0] == 172 && octets[1] in 16..31 -> true
            else -> false
        }
    }

    private val CELLULAR = listOf("rmnet", "r_rmnet", "ccmni", "pdp", "seth", "wwan", "ppp", "tun", "ipsec", "clat", "v4-", "dummy", "sit", "ip6", "vti")
}
