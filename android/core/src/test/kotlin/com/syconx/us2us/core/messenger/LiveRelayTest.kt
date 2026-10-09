package com.syconx.us2us.core.messenger

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * The conversation scenario against a real relay. Skipped unless `RELAY_URL` is set, e.g.
 * `RELAY_URL=http://127.0.0.1:3000 ./gradlew :core:test --tests '*LiveRelayTest'`.
 */
@EnabledIfEnvironmentVariable(named = "RELAY_URL", matches = ".+")
class LiveRelayTest : ConversationScenario() {
    override val relayUrl: String = System.getenv("RELAY_URL").orEmpty()
}
