package com.syconx.us2us.core.relay

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.SerializationException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** What happens on a [RelaySocket], in order. [Closed] and [Failed] are always the last event. */
sealed interface SocketEvent {
    /** The upgrade succeeded; the relay now sends the stored backlog. */
    data object Connected : SocketEvent

    /** An envelope. Acknowledge it with [RelaySocket.ack] once it is safely stored. */
    class Message(val envelope: StoredEnvelope) : SocketEvent

    /** Everything stored at connect time has been sent; later messages arrive live. */
    data object QueueEmpty : SocketEvent

    data object Pong : SocketEvent

    /** The relay closed the connection. See [RelaySocket.CLOSE_REPLACED] and [RelaySocket.CLOSE_UNAUTHORIZED]. */
    data class Closed(val code: Int, val reason: String) : SocketEvent

    /** The connection broke, or the upgrade was rejected ([httpStatus] is then set, e.g. 401). */
    class Failed(val cause: Throwable, val httpStatus: Int?) : SocketEvent

    /** Whether reconnecting can help, as opposed to another connection having taken over or a bad token. */
    val isRetryable: Boolean
        get() = when (this) {
            is Closed -> code != RelaySocket.CLOSE_REPLACED && code != RelaySocket.CLOSE_UNAUTHORIZED
            is Failed -> httpStatus != 401
            else -> true
        }
}

/**
 * One WebSocket connection to the relay (PROTOCOL.md §4.7). Single-use: after [SocketEvent.Closed]
 * or [SocketEvent.Failed], open a new one. While connected, call [ping] every [KEEPALIVE_INTERVAL];
 * OkHttp's own WebSocket pings only detect dead connections.
 */
class RelaySocket private constructor(
    private val webSocket: WebSocket,
    channel: ReceiveChannel<SocketEvent>,
) {
    /** Events of this connection. Collect it once; it completes after the final event. */
    val events: Flow<SocketEvent> = channel.receiveAsFlow()

    /** Acknowledges envelopes so the relay deletes them. Returns false if the socket is gone. */
    fun ack(ids: List<String>): Boolean = ids.isEmpty() || send(SocketFrame.Ack(ids))

    /** An application-level ping; the relay answers with [SocketEvent.Pong]. */
    fun ping(): Boolean = send(SocketFrame.Ping)

    /** Closes the connection normally. The event flow then ends with [SocketEvent.Closed]. */
    fun close() {
        webSocket.close(CLOSE_NORMAL, null)
    }

    private fun send(frame: SocketFrame): Boolean = webSocket.send(RelayJson.encodeToString(SocketFrame.serializer(), frame))

    private class Listener(private val channel: SendChannel<SocketEvent>) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            channel.trySend(SocketEvent.Connected)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = try {
                RelayJson.decodeFromString(SocketFrame.serializer(), text)
            } catch (_: SerializationException) {
                return // Unknown or malformed frames are ignored for forward compatibility.
            } catch (_: IllegalArgumentException) {
                return
            }
            when (frame) {
                is SocketFrame.Message -> channel.trySend(
                    SocketEvent.Message(StoredEnvelope(frame.id, frame.serverTimestamp, frame.envelope)),
                )
                SocketFrame.QueueEmpty -> channel.trySend(SocketEvent.QueueEmpty)
                SocketFrame.Pong -> channel.trySend(SocketEvent.Pong)
                is SocketFrame.Ack, SocketFrame.Ping -> Unit // Client-to-server frames.
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // Answer with a normal close: echoing the relay's code would throw for codes a client
            // may not send (e.g. 1005 for a close frame without a code). [Closed] still reports it.
            webSocket.close(CLOSE_NORMAL, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            channel.trySend(SocketEvent.Closed(code, reason))
            channel.close()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            channel.trySend(SocketEvent.Failed(t, response?.code))
            channel.close()
        }
    }

    companion object {
        /**
         * How often a connected client sends `{"type":"ping"}`: PROTOCOL.md §4.7 requires one at least
         * every 30 s, and this leaves room for scheduling delays. The relay may drop sockets that
         * sent no application frame for 90 s; WebSocket-level pings (which OkHttp sends to detect
         * dead connections) do not count there.
         */
        val KEEPALIVE_INTERVAL: Duration = 25.seconds

        const val CLOSE_NORMAL = 1000

        /** Another connection for the same account took over. Reconnecting would steal it back. */
        const val CLOSE_REPLACED = 4001

        /** The auth token was rejected after the upgrade. */
        const val CLOSE_UNAUTHORIZED = 4401

        internal fun open(http: OkHttpClient, request: Request): RelaySocket {
            val channel = Channel<SocketEvent>(Channel.UNLIMITED)
            return RelaySocket(http.newWebSocket(request, Listener(channel)), channel)
        }
    }
}
