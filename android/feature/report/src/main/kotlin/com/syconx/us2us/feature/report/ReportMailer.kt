package com.syconx.us2us.feature.report

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.syconx.us2us.ui.env.ExternalActivityGuard
import com.syconx.us2us.ui.env.LocalExternalActivityGuard
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/** Hands a report's e-mail to whatever can send it on this phone. */
fun interface ReportMailer {
    suspend fun deliver(mail: ReportMail): ReportDelivery
}

/**
 * Like the invite's e-mail channel: a mail app through `ACTION_SENDTO` with the `mailto:` URI to
 * the support address (the subject and body also as extras, which some mail apps read instead of
 * the URI). The mail app opens over us2us under the [ExternalActivityGuard], so coming back from it
 * neither covers nor locks the app. When nothing on this phone handles `mailto:`, the address is
 * shown to copy ([ReportDelivery.AddressShown]).
 *
 * Android cannot tell whether the e-mail was sent: a mail app that came up over us2us is
 * [ReportDelivery.HandedOff]. With several mail apps and no default, the system first asks which
 * one (“Open with”); that sheet dismissed hands nothing off ([ReportDelivery.NotSent]), so nobody is
 * blocked and the form stays. [HandOff] tells the two apart.
 */
class SystemReportMailer(private val context: Context, private val guard: ExternalActivityGuard) : ReportMailer {
    override suspend fun deliver(mail: ReportMail): ReportDelivery {
        val intent = intent(mail)
        val activity = context.findActivity()
        // Outside an activity the system needs a task of its own.
        if (activity == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Watched from before the start: the pause the start causes must not be missed.
        val handOff = (activity as? LifecycleOwner)?.lifecycle
            ?.takeIf { it.currentState.isAtLeast(Lifecycle.State.RESUMED) }
            ?.let(::HandOff)
        try {
            guard.launching { context.startActivity(intent) }
        } catch (_: ActivityNotFoundException) {
            handOff?.stop()
            return ReportDelivery.AddressShown
        } catch (_: SecurityException) {
            handOff?.stop()
            guard.resumed()
            return ReportDelivery.AddressShown
        }
        // Without an activity to watch, a started mail app is all there is to know.
        if (handOff == null) return ReportDelivery.HandedOff
        try {
            return handOff.outcome()
        } finally {
            // Never paused, the app stayed in front: no resume will end the guard, so it ends here.
            if (!handOff.wasPaused) guard.resumed()
        }
    }

    companion object {
        /** The mail app's intent: `mailto:` to the support address with the subject and body. */
        fun intent(mail: ReportMail): Intent = Intent(Intent.ACTION_SENDTO, Uri.parse(mail.mailtoUri))
            .putExtra(Intent.EXTRA_EMAIL, arrayOf(ReportMail.ADDRESS))
            .putExtra(Intent.EXTRA_SUBJECT, mail.subject)
            .putExtra(Intent.EXTRA_TEXT, mail.body)
    }
}

/**
 * What became of a started `mailto:`, from the activity's lifecycle. A mail app covers us2us: the
 * activity stops ([ReportDelivery.HandedOff]). The system's “Open with” sheet only pauses it, and
 * once dismissed the activity resumes without having stopped ([ReportDelivery.NotSent]). Not even a
 * pause within [PAUSE_WAIT_MILLIS]: the mail app opened beside us2us (another window) and has the
 * report.
 */
internal class HandOff(private val lifecycle: Lifecycle) {
    /** The start paused the activity (something came up over it). */
    var wasPaused = false
        private set
    private var result: ReportDelivery? = null
    private var waiting: CancellableContinuation<ReportDelivery>? = null

    private val observer = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_PAUSE -> wasPaused = true
            Lifecycle.Event.ON_STOP -> finish(ReportDelivery.HandedOff)
            // The events up to the current state replay when the observer is added: only a resume
            // after a pause counts.
            Lifecycle.Event.ON_RESUME -> if (wasPaused) finish(ReportDelivery.NotSent)
            else -> Unit
        }
    }

    init {
        lifecycle.addObserver(observer)
    }

    /** Waits for the mail app or the dismissed sheet. */
    suspend fun outcome(): ReportDelivery = try {
        coroutineScope {
            val fallback = launch {
                delay(PAUSE_WAIT_MILLIS)
                if (!wasPaused) finish(ReportDelivery.HandedOff)
            }
            try {
                result ?: suspendCancellableCoroutine { waiting = it }
            } finally {
                fallback.cancel()
            }
        }
    } finally {
        stop()
    }

    /** No longer watched (nothing was started, or the outcome is known). */
    fun stop() {
        lifecycle.removeObserver(observer)
        waiting = null
    }

    private fun finish(delivery: ReportDelivery) {
        if (result != null) return
        result = delivery
        waiting?.let { continuation ->
            waiting = null
            if (continuation.isActive) continuation.resume(delivery)
        }
    }

    companion object {
        /** How long a start may take to pause the activity before the mail app counts as opened elsewhere. */
        const val PAUSE_WAIT_MILLIS = 2_000L
    }
}

/** The copy-the-address path whatever this phone has (the harness's `report-address`, screenshots, UI tests). */
object AddressOnlyReportMailer : ReportMailer {
    override suspend fun deliver(mail: ReportMail): ReportDelivery = ReportDelivery.AddressShown
}

/** A mailer that replaces the system's (harness, tests); `null`: the system's. */
val LocalReportMailer = staticCompositionLocalOf<ReportMailer?> { null }

/** The mailer of this screen: [LocalReportMailer], else the system's under the app's guard. */
@Composable
fun rememberReportMailer(): ReportMailer {
    val override = LocalReportMailer.current
    val context = LocalContext.current
    val guard = LocalExternalActivityGuard.current
    return remember(override, context, guard) { override ?: SystemReportMailer(context, guard) }
}

/**
 * The fallback's “Kopyala”: the address only, as plain text, removed from the clipboard after two
 * minutes if it is still the address there. Android has no expiring or device-only clipboard items,
 * so the removal is done by the app while its process lives.
 */
object ReportPasteboard {
    /** Seconds the address stays on the clipboard. */
    const val LIFETIME_SECONDS = 120L

    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var pendingClear: Runnable? = null

    fun copyAddress(clipboard: ClipboardManager) {
        clipboard.setPrimaryClip(ClipData.newPlainText(ReportMail.ADDRESS, ReportMail.ADDRESS))
        pendingClear?.let(handler::removeCallbacks)
        val clear = Runnable {
            pendingClear = null
            val current = runCatching { clipboard.primaryClip }.getOrNull()
            val text = current?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
            if (text == ReportMail.ADDRESS) runCatching { clipboard.clearPrimaryClip() }
        }
        pendingClear = clear
        handler.postDelayed(clear, LIFETIME_SECONDS * 1_000)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
