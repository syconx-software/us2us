package com.syconx.us2us.cli

import com.syconx.us2us.core.AccountId
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File

/**
 * Talks to a running echo responder through a real relay: a `us2us-bot`, or the iOS
 * `LiveEchoResponderTests` (both write the same `{accountId, username}` state file). Skipped unless
 * both `RELAY_URL` and `US2US_BOT_STATE` (the responder's state file) are set, e.g.
 * `RELAY_URL=http://127.0.0.1:3000 US2US_BOT_STATE=/tmp/echo-bot.json ./gradlew :cli:test --tests '*LiveEchoBotTest'`.
 * `scripts/interop.sh` at the repository root runs it against both.
 */
@EnabledIfEnvironmentVariable(named = "RELAY_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "US2US_BOT_STATE", matches = ".+")
class LiveEchoBotTest {
    @Test
    fun `a running responder accepts the request and echoes everything`() = runBlocking {
        val state = Json.decodeFromString(BotState.serializer(), File(System.getenv("US2US_BOT_STATE")).readText())
        coroutineScope { talkToEchoResponder(this, System.getenv("RELAY_URL"), AccountId.parse(state.accountId), state.username) }
    }
}
