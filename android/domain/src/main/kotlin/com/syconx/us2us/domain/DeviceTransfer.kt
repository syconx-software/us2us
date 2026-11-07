package com.syconx.us2us.domain

import java.net.URI

/** Moving the account to a new phone next to this one (PROTOCOL.md §5.6). */
enum class DeviceTransferFailure {
    /** This phone is on no local network: no Wi-Fi, no hotspot. */
    NoLocalNetwork,

    /** None of the addresses in the code answered: not on the same network. */
    Unreachable,

    /** The connection broke on the way. */
    Interrupted,

    /** The account could not be prepared; nothing left this phone. */
    NotPrepared,

    /** What is moving does not fit on the new phone. */
    NoSpace,
}

/** What the new phone received: a backup file and the password that opens it. */
data class DeviceTransferDeliveryInfo(val file: URI, val password: String)

sealed interface DeviceTransferEvent {
    /** A phone that read the code connected. */
    data object Started : DeviceTransferEvent
    data class Progress(val fraction: Double) : DeviceTransferEvent

    /** That phone's connection broke; the code still works. */
    data object Interrupted : DeviceTransferEvent

    /** What that phone offered does not fit on this one. */
    data object NoSpace : DeviceTransferEvent
}

/** The new phone's side while its code is on screen. */
interface DeviceTransferIncoming {
    /** The text of the QR code. */
    val invitation: String
    var onEvent: ((DeviceTransferEvent) -> Unit)?

    /** The first complete file; connections that fail do not end the wait. */
    suspend fun waitForDelivery(): DeviceTransferDeliveryInfo

    /** Ends the invitation. What is underway stops. */
    fun stop()

    /** The received file, once it has been handed on or given up. */
    fun removeFiles()
}

/** The device preferences a direct transfer carries along (PROTOCOL.md §5.6), by their raw names. */
data class DevicePreferencesSnapshot(val theme: String, val language: String, val keepScreenOn: Boolean)
