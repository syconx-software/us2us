package com.syconx.us2us.core.session

import com.syconx.us2us.core.messenger.Messenger
import com.syconx.us2us.core.relay.RelayClient
import com.syconx.us2us.core.testing.FakeRelay
import com.syconx.us2us.core.testing.MutableClock
import com.syconx.us2us.core.testing.TestClient
import com.syconx.us2us.core.username.Username
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The username cache of [RelaySenderDirectory]: names move (§3.3), so answers expire. */
class RelaySenderDirectoryTest {
    private val relay = FakeRelay().start()
    private val clock = MutableClock()
    private val alice = Username("alice")

    @AfterEach
    fun stopRelay() = relay.close()

    private suspend fun register(name: String): Messenger =
        Messenger.register(Username(name), RelayClient(relay.url), config = TestClient.testConfig)

    /** The directory of a fresh account, as its Messenger would build it. */
    private suspend fun directory(): RelaySenderDirectory {
        val owner = register("bob")
        return RelaySenderDirectory(RelayClient(relay.url).withAuthToken(owner.account.authToken), clock)
    }

    /** Moves [alice] to a new account the way the relay allows it at once: delete, then register. */
    private suspend fun moveAlice(from: Messenger): Messenger {
        from.deleteAccount()
        return register(alice.value)
    }

    @Test
    fun `an owner is reused until the TTL runs out`() = runBlocking {
        val directory = directory()
        val first = register(alice.value)
        val lookups = relay.directoryLookups
        assertEquals(first.accountId, directory.accountFor(alice))

        // Within the hour the cached answer is used, even though the name has moved meanwhile.
        val second = moveAlice(first)
        clock.advance(59.minutes)
        assertEquals(first.accountId, directory.accountFor(alice))
        assertEquals(lookups + 1, relay.directoryLookups)

        clock.advance(1.minutes)
        assertEquals(second.accountId, directory.accountFor(alice))
        assertEquals(second.accountId, directory.accountFor(alice))
        assertEquals(lookups + 2, relay.directoryLookups)
    }

    @Test
    fun `nobody is an answer too and expires the same way`() = runBlocking {
        val directory = directory()
        val lookups = relay.directoryLookups
        assertNull(directory.accountFor(alice))
        val owner = register(alice.value)
        clock.advance(30.minutes)
        assertNull(directory.accountFor(alice))
        assertEquals(lookups + 1, relay.directoryLookups)

        clock.advance(30.minutes)
        assertEquals(owner.accountId, directory.accountFor(alice))
        assertEquals(lookups + 2, relay.directoryLookups)
    }

    @Test
    fun `a refresh asks the relay and replaces the cached owner`() = runBlocking {
        val directory = directory()
        val first = register(alice.value)
        assertEquals(first.accountId, directory.accountFor(alice))
        val second = moveAlice(first)

        val lookups = relay.directoryLookups
        assertEquals(second.accountId, directory.accountFor(alice, refresh = true))
        assertEquals(second.accountId, directory.accountFor(alice))
        assertEquals(lookups + 1, relay.directoryLookups)
    }

    @Test
    fun `forced refreshes of one name are limited to one a minute`() = runBlocking {
        val directory = directory()
        register(alice.value)
        val lookups = relay.directoryLookups
        repeat(5) { directory.accountFor(alice, refresh = true) }
        assertEquals(lookups + 1, relay.directoryLookups)

        clock.advance(60.seconds)
        directory.accountFor(alice, refresh = true)
        assertEquals(lookups + 2, relay.directoryLookups)
    }

    @Test
    fun `an answer from a search replaces a stale owner`() = runBlocking {
        val directory = directory()
        val first = register(alice.value)
        assertEquals(first.accountId, directory.accountFor(alice))
        val second = moveAlice(first)

        val lookups = relay.directoryLookups
        directory.remember(alice, second.accountId)
        assertEquals(second.accountId, directory.accountFor(alice))
        assertEquals(second.accountId, directory.accountFor(alice, refresh = true))
        assertEquals(lookups, relay.directoryLookups)
    }

    @Test
    fun `a failed lookup throws and caches nothing`() = runBlocking {
        val directory = directory()
        val owner = register(alice.value)
        relay.failNext { it.method == "GET" && it.url.encodedPath.startsWith("/v1/directory/") }
        assertThrows<IOException> { runBlocking { directory.accountFor(alice) } }

        val lookups = relay.directoryLookups
        assertEquals(owner.accountId, directory.accountFor(alice))
        assertEquals(owner.accountId, directory.accountFor(alice))
        assertEquals(lookups + 1, relay.directoryLookups)
    }

    @Test
    fun `a clock that went backwards does not keep an answer alive`() = runBlocking {
        val directory = directory()
        register(alice.value)
        val lookups = relay.directoryLookups
        directory.accountFor(alice)
        clock.advance((-2).hours)
        directory.accountFor(alice)
        assertEquals(lookups + 2, relay.directoryLookups)
    }
}
