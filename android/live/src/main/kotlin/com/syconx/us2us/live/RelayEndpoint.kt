package com.syconx.us2us.live

import com.syconx.us2us.core.relay.RelayClient
import com.syconx.us2us.runtime.net.NetworkPolicy
import java.io.IOException

/**
 * The app's only network endpoint: the relay (PROTOCOL §4). Release builds always use
 * `https://relay.us2us.app` (the core's `RelayClient.DEFAULT_URL`); debug builds accept
 * `U2RelayURL <url>` for a local relay, through the same `NetworkPolicy` rule (https, or plain http
 * to a loopback address only).
 */
object RelayEndpoint {
    const val PRODUCTION: String = RelayClient.DEFAULT_URL

    /** The debug override, if it passes the network policy. */
    fun debugOverride(value: String?): String? {
        if (value.isNullOrEmpty()) return null
        return try {
            NetworkPolicy.validatedRelayUrl(value)
            value.trimEnd('/')
        } catch (_: IOException) {
            null
        }
    }
}
