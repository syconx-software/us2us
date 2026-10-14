package com.syconx.us2us.runtime.support

import android.util.Log
import kotlin.coroutines.cancellation.CancellationException

/**
 * The runtime's only log sink (besides the vault's own lines, which follow the same rules).
 *
 * Privacy rules (checked by `PrivacyLintTest`):
 *
 * - Messages are fixed event names ("connected", "unlock failed"). Message content, account ids,
 *   usernames, relay ids, keys, codes, file names and URLs are never logged. Android has no
 *   redacted interpolation like Apple's `.private`, so nothing else may be appended: the only
 *   variable part a line can carry is a [LogLabel], a fixed category such as `"network"`.
 * - No `println`, `System.out`, `printStackTrace` or `android.util.Log` anywhere else in the
 *   runtime.
 * - [RuntimeLogger.debug] is written only when the tag is switched on (`adb shell setprop
 *   log.tag.us2us.relay DEBUG`); [RuntimeLogger.error] is used only for failures the user may
 *   report, again without identifiers.
 */
object RuntimeLog {
    const val SUBSYSTEM = "us2us"

    val vault = RuntimeLogger("$SUBSYSTEM.vault")
    val relay = RuntimeLogger("$SUBSYSTEM.relay")
    val messaging = RuntimeLogger("$SUBSYSTEM.messaging")
    val privacy = RuntimeLogger("$SUBSYSTEM.privacy")
    val lifecycle = RuntimeLogger("$SUBSYSTEM.lifecycle")
    val media = RuntimeLogger("$SUBSYSTEM.media")
}

/** One category of [RuntimeLog]. */
class RuntimeLogger internal constructor(val tag: String) {
    fun debug(event: String, label: String? = null) {
        if (Log.isLoggable(tag, Log.DEBUG)) Log.d(tag, line(event, label))
    }

    fun info(event: String, label: String? = null) {
        Log.i(tag, line(event, label))
    }

    fun notice(event: String, label: String? = null) {
        Log.w(tag, line(event, label))
    }

    fun error(event: String, label: String? = null) {
        Log.e(tag, line(event, label))
    }

    private fun line(event: String, label: String?): String = if (label == null) event else "$event: $label"
}

/** A value that may appear in a log line in clear: a fixed category that says nothing about who or what. */
interface LogLabel {
    val logLabel: String
}

/** A non-identifying category of the error, safe to log: its class name, never its message. */
val Throwable.logLabel: String
    get() = when (this) {
        is CancellationException -> "cancelled"
        is LogLabel -> logLabel
        else -> this::class.java.simpleName.ifEmpty { "error" }
    }
