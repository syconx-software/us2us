package com.syconx.us2us.core.relay

import java.io.IOException

/** Machine-readable error codes of the relay (PROTOCOL.md §2.1). Apps map them to localized text. */
enum class RelayErrorCode(val wire: String) {
    BAD_REQUEST("bad_request"),
    UNAUTHORIZED("unauthorized"),
    ACCESS_DENIED("access_denied"),
    POW_INVALID("pow_invalid"),
    NOT_FOUND("not_found"),
    USERNAME_TAKEN("username_taken"),

    /** An avatar write whose version is not above the account's highest (§4.12); see [RelayException.avatarVersion]. */
    STALE_VERSION("stale_version"),

    PAYLOAD_TOO_LARGE("payload_too_large"),
    RATE_LIMITED("rate_limited"),
    INTERNAL("internal"),

    /** A code this client does not know yet, or a non-JSON error from a proxy in front of the relay. */
    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromWire(code: String): RelayErrorCode = entries.firstOrNull { it.wire == code } ?: UNKNOWN
    }
}

/**
 * A non-2xx response from the relay. Transport failures surface as other [IOException]s, so a
 * caller that catches this type first can tell "the relay said no" from "the relay was unreachable".
 */
class RelayException(
    val httpStatus: Int,
    val code: RelayErrorCode,
    /** English text from the relay, for logs and developers; never shown to users as is. */
    val relayMessage: String,
    /** Seconds from the `Retry-After` header of a `429`, if present. */
    val retryAfterSeconds: Long? = null,
    /** The account's highest avatar version from the `X-Avatar-Version` header of a `409 stale_version` (§4.12). */
    val avatarVersion: Long? = null,
) : IOException("relay error $httpStatus ${code.wire}: $relayMessage")
