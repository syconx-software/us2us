package com.syconx.us2us.cli

import com.syconx.us2us.core.messenger.Messenger
import com.syconx.us2us.core.proto.ConversationWipe
import com.syconx.us2us.core.proto.conversationSettings
import com.syconx.us2us.core.proto.conversationWipe
import com.syconx.us2us.core.proto.ghostMode
import com.syconx.us2us.core.proto.location
import com.syconx.us2us.core.relay.RelayClient
import com.syconx.us2us.core.relay.RelayErrorCode
import com.syconx.us2us.core.relay.RelayException
import com.syconx.us2us.core.testing.FakeRelay
import com.syconx.us2us.core.testing.TestClient
import com.syconx.us2us.core.username.Username
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

class EchoBotTest {

    @Test
    fun `accepts the request and echoes text, attachments, locations, settings and wipes`() = runBlocking {
        FakeRelay().start().use { relay ->
            coroutineScope {
                val bot = Messenger.register(Username("echo_bot"), RelayClient(relay.url), config = TestClient.testConfig)
                val botJob = launch { EchoBot(bot).run() }

                talkToEchoResponder(this, relay.url, bot.accountId, "echo_bot")
                assertEquals(0, relay.mediaCount(), "the bot deletes media after downloading")
                assertEquals(emptyList<String>(), relay.violations)

                botJob.cancelAndJoin()
            }
        }
    }

    @Test
    fun `answers follow the echo contract`() {
        assertEquals("echo: Merhaba 👋 dünya", Echo.text("Merhaba 👋 dünya"))
        assertEquals("echo-attachment: 00ff", Echo.attachment("00ff"))
        assertEquals("echo-location: 41.008600,-28.980200", Echo.location(location { latitude = 41.0086; longitude = -28.9802 }))
        assertEquals(
            "echo-location: 0.000001,180.000000 Ayasofya, İstanbul",
            Echo.location(location { latitude = 0.000001; longitude = 180.0; name = "Ayasofya, İstanbul" }),
        )
        assertEquals(
            "echo-settings: ghost=on,timeout=60,wipe_contact=true,expire=30",
            Echo.settings(conversationSettings { defaultExpireTimerSeconds = 30; ghost = ghostMode { enabled = true; timeoutSeconds = 60; wipeContact = true } }),
        )
        assertEquals("echo-settings: ghost=off,timeout=0,wipe_contact=false,expire=0", Echo.settings(conversationSettings {}))
        assertEquals("echo-wipe: GHOST_TIMEOUT", Echo.wipe(conversationWipe { reason = ConversationWipe.Reason.GHOST_TIMEOUT }))
        assertEquals("echo-wipe: USER_REQUEST", Echo.wipe(conversationWipe { reason = ConversationWipe.Reason.USER_REQUEST }))
    }

    @Test
    fun `a failed acceptance does not stop the bot`() = runBlocking {
        FakeRelay().start().use { relay ->
            coroutineScope {
                val bot = Messenger.register(Username("echo_bot"), RelayClient(relay.url), config = TestClient.testConfig)
                val botJob = launch { EchoBot(bot).run() }
                val user = TestClient.register(relay.url, "tester", this)
                user.awaitUpToDate()
                user.messenger.findByUsername("echo_bot")

                // The bot's ACCEPT needs a proof-of-work challenge addressed to the user; break the first one.
                relay.failNext { it.url.encodedPath == "/v1/pow" && it.url.queryParameter("to") == user.accountId.toString() }
                user.messenger.sendText(bot.accountId, "first")
                user.messenger.sendText(bot.accountId, "second")
                assertEquals("echo: second", user.nextData().content.data.text.body)

                botJob.cancelAndJoin()
                user.job.cancelAndJoin()
            }
        }
    }

    @Test
    fun `options come from arguments with the relay from the environment as fallback`() {
        val options = Options.parse(arrayOf("--username", " Echo_Bot ", "--state", "/tmp/state.json"), "http://127.0.0.1:3000")
        assertEquals("echo_bot", options.username?.value)
        assertEquals("http://127.0.0.1:3000", options.relayUrl)
        assertEquals("http://relay", Options.parse(arrayOf("--relay", "http://relay", "--username", "abc", "--state", "s"), "http://env").relayUrl)

        assertEquals("echo_bot", Options.parse(arrayOf("--username", "@echo_bot", "--state", "s"), "http://relay").username?.value)
        assertEquals("a".repeat(24), Options.parse(arrayOf("--username", "a".repeat(24), "--state", "s"), "http://relay").username?.value)

        assertThrows<IllegalArgumentException> { Options.parse(arrayOf("--username", "abc", "--state", "s"), null) }
        assertThrows<IllegalArgumentException> { Options.parse(arrayOf("--username", "a", "--state", "s"), "http://relay") }
        assertThrows<IllegalArgumentException> { Options.parse(arrayOf("--username", "abc"), "http://relay") }
        assertThrows<IllegalArgumentException> { Options.parse(arrayOf("--verbose"), "http://relay") }
        assertThrows<IllegalArgumentException> { Options.parse(arrayOf("--state"), "http://relay") }
    }

    @Test
    fun `an invalid username is reported as typed, never changed`() {
        listOf(
            "Kağan" to "(Turkish characters)",
            "ali.veli" to "(characters other than a-z, 0-9 and _)",
            "1abc" to "(does not start with a letter)",
            "a".repeat(25) to "(not 3–24 characters)",
            "şule.x" to "(Turkish characters, characters other than a-z, 0-9 and _)",
        ).forEach { (typed, reasons) ->
            val error = assertThrows<IllegalArgumentException> { Options.parse(arrayOf("--username", typed, "--state", "s"), "http://relay") }
            assertEquals("invalid username '$typed' $reasons: use 3–24 characters a-z, 0-9 or _, starting with a letter", error.message)
        }
    }

    @Test
    fun `a taken username gets a one-line message`() = runBlocking {
        FakeRelay().start().use { relay ->
            Messenger.register(Username("echo_bot"), RelayClient(relay.url), config = TestClient.testConfig)
            val error = assertThrows<RelayException> {
                runBlocking { Messenger.register(Username("echo_bot"), RelayClient(relay.url), config = TestClient.testConfig) }
            }
            assertEquals(
                "registration failed: the username echo_bot is taken, choose another one with --username",
                registrationFailure(Username("echo_bot"), error),
            )
        }
        assertEquals(
            "registration failed: relay error 429 rate_limited: slow down",
            registrationFailure(Username("echo_bot"), RelayException(429, RelayErrorCode.RATE_LIMITED, "slow down")),
        )
    }

    @Test
    fun `the state file holds the account id and username as JSON`(@TempDir dir: File) {
        val file = File(dir, "nested/state.json")
        writeState(file, BotState("0123abcd-4567-89ef-0123-456789abcdef", "echo_bot"))
        val json = Json.parseToJsonElement(file.readText()).jsonObject
        assertEquals("0123abcd-4567-89ef-0123-456789abcdef", json["accountId"]!!.jsonPrimitive.content)
        assertEquals("echo_bot", json["username"]!!.jsonPrimitive.content)
        assertEquals(listOf("state.json"), file.parentFile.list()!!.toList())
    }
}
