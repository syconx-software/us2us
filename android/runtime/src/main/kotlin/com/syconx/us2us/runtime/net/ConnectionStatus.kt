package com.syconx.us2us.runtime.net

import com.syconx.us2us.runtime.support.RuntimeString
import com.syconx.us2us.runtime.support.RuntimeStrings
import java.time.Instant

/** The state of the relay line, for the "hat" line on the chat list. */
sealed interface ConnectionStatus {
    /** Not connected on purpose: locked, in the background, or not set up. */
    data object Offline : ConnectionStatus

    data object Connecting : ConnectionStatus

    /** Connected; receiving the stored backlog until `queue_empty`. */
    data object Syncing : ConnectionStatus

    /** Connected and live: new messages arrive immediately. */
    data object Open : ConnectionStatus

    /** The device has no usable network. */
    data object WaitingForNetwork : ConnectionStatus

    /** The connection failed; the next attempt is at [at]. */
    data class Retrying(val at: Instant) : ConnectionStatus

    /**
     * The relay refused our token (rotated by a restore on another phone, or the account was
     * deleted). No more attempts are made.
     */
    data object Revoked : ConnectionStatus

    /** The localized "hat" line. */
    fun line(strings: RuntimeStrings): String = strings.string(
        when (this) {
            Open -> RuntimeString.LINE_OPEN
            Connecting, is Retrying -> RuntimeString.LINE_CONNECTING
            Syncing -> RuntimeString.LINE_SYNCING
            Offline -> RuntimeString.LINE_OFFLINE
            WaitingForNetwork -> RuntimeString.LINE_WAITING_FOR_NETWORK
            Revoked -> RuntimeString.LINE_REVOKED
        },
    )

    val isConnected: Boolean get() = this == Open || this == Syncing
}
