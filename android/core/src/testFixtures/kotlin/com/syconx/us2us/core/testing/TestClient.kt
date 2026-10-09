package com.syconx.us2us.core.testing

import com.syconx.us2us.core.contacts.InMemoryContactStore
import com.syconx.us2us.core.messenger.ConnectionState
import com.syconx.us2us.core.messenger.IncomingMessage
import com.syconx.us2us.core.messenger.Messenger
import com.syconx.us2us.core.messenger.MessengerConfig
import com.syconx.us2us.core.relay.RelayClient
import com.syconx.us2us.core.store.InMemoryProtocolStorage
import com.syconx.us2us.core.username.Username
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A [Messenger] whose receive loop runs in [scope] and feeds an inbox the test can wait on.
 * Timeouts are generous: they only exist to turn a hang into a failure, also on a loaded machine.
 */
class TestClient(val messenger: Messenger, scope: CoroutineScope) {
    private val inbox = Channel<IncomingMessage>(Channel.UNLIMITED)
    val job: Job = scope.launch { messenger.runReceiveLoop { inbox.send(it) } }

    val accountId get() = messenger.accountId

    suspend fun awaitUpToDate(timeout: Duration = 60.seconds) {
        withTimeout(timeout) { messenger.connection.first { it == ConnectionState.UP_TO_DATE } }
    }

    /** The next message matching [predicate]; earlier non-matching messages are discarded. */
    suspend fun next(timeout: Duration = 60.seconds, predicate: (IncomingMessage) -> Boolean = { true }): IncomingMessage =
        withTimeout(timeout) {
            var message = inbox.receive()
            while (!predicate(message)) message = inbox.receive()
            message
        }

    suspend fun nextData(timeout: Duration = 60.seconds) = next(timeout) { it.content.hasData() }

    /** Waits [duration] and reports whether no message arrived meanwhile. */
    suspend fun receivesNothingWithin(duration: Duration): Boolean = withTimeoutOrNull(duration) { inbox.receive() } == null

    companion object {
        val testConfig = MessengerConfig(
            powParallelism = 4,
            reconnectMinDelay = 0.2.seconds,
            reconnectMaxDelay = 1.seconds,
            setupRetryMinDelay = 0.2.seconds,
            setupRetryMaxDelay = 1.seconds,
        )

        suspend fun register(relayUrl: String, username: String, scope: CoroutineScope): TestClient =
            TestClient(
                Messenger.register(
                    Username(username),
                    RelayClient(relayUrl),
                    InMemoryProtocolStorage(),
                    InMemoryContactStore(),
                    testConfig,
                ),
                scope,
            )
    }
}
