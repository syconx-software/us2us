package com.syconx.us2us.runtime.support

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import androidx.annotation.MainThread
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration

/** The parts of the system clipboard the hygiene helper uses, so it can be tested. */
interface ClipboardWriting {
    /**
     * Makes [text] the primary clip. [sensitive] marks it so the system never shows it in a
     * preview (`ClipDescription.EXTRA_IS_SENSITIVE`). Returns the new clip's [stamp], or `null`
     * when it cannot be read back.
     */
    fun write(text: String, sensitive: Boolean): Long?

    /**
     * What identifies the current primary clip (when it was set), or `null` when the clipboard is
     * empty or this app may not look at it right now: since Android 10 only the app in focus may.
     */
    fun stamp(): Long?

    fun clear()

    /** Called (on the main thread) whenever the primary clip changes while this app may see it; `null` stops. */
    fun onChange(listener: (() -> Unit)?)
}

/** The system clipboard. */
class SystemClipboard(context: Context) : ClipboardWriting {
    private val manager = requireNotNull(context.getSystemService(ClipboardManager::class.java)) { "no clipboard" }
    private var listener: ClipboardManager.OnPrimaryClipChangedListener? = null

    override fun write(text: String, sensitive: Boolean): Long? {
        // No label: a label is visible to every app that reads the clipboard.
        val clip = ClipData.newPlainText("", text)
        if (sensitive) {
            clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
        }
        manager.setPrimaryClip(clip)
        return stamp()
    }

    // The description alone: reading the clip itself would show the system's "pasted from your
    // clipboard" notice on Android 12 and later.
    override fun stamp(): Long? = manager.primaryClipDescription?.timestamp

    override fun clear() {
        manager.clearPrimaryClip()
    }

    override fun onChange(listener: (() -> Unit)?) {
        this.listener?.let { manager.removePrimaryClipChangedListener(it) }
        this.listener = listener?.let { ClipboardManager.OnPrimaryClipChangedListener { it() } }
        this.listener?.let { manager.addPrimaryClipChangedListener(it) }
    }

    companion object {
        /** `ClipDescription.EXTRA_IS_SENSITIVE` (API 33), which older versions read under the same name. */
        const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
    }
}

/** Runs an action at a wall-clock time (the expiry of a copy). */
fun interface ExpiryTimer {
    /** Schedules [action] for [at]; the returned function cancels it. */
    fun schedule(at: Instant, action: () -> Unit): () -> Unit
}

/** [ExpiryTimer] on the main looper. */
class MainLooperExpiryTimer(private val now: () -> Instant = Instant::now) : ExpiryTimer {
    private val handler = Handler(Looper.getMainLooper())

    override fun schedule(at: Instant, action: () -> Unit): () -> Unit {
        val delay = java.time.Duration.between(now(), at).toKotlinDuration().inWholeMilliseconds.coerceAtLeast(0)
        val token = Any()
        handler.postAtTime(action, token, SystemClock.uptimeMillis() + delay)
        return { handler.removeCallbacksAndMessages(token) }
    }
}

/**
 * Clipboard hygiene (owner decision):
 *
 * - Every copy stays on this phone: Android has no clipboard sync of its own, and a copy is never
 *   labelled, so cross-device clipboards of other vendors get nothing that names its source.
 * - Sensitive values (safety numbers, codes, usernames shown for sharing) are marked sensitive,
 *   expire after [SENSITIVE_LIFETIME] and are also cleared when the app locks, but only if the
 *   clipboard still holds what we put there.
 * - A value copied to be pasted into another app ([Sensitivity.HAND_OFF]: the generated backup
 *   password, for a password manager) expires after [SENSITIVE_LIFETIME] too, but is left in place
 *   when the app leaves the foreground, which is exactly when it is pasted.
 * - Text from disappearing or view-once messages is never copied (the UI disables the action;
 *   [copy] refuses [Sensitivity.FORBIDDEN]).
 *
 * Android keeps no expiry for a clip, so the expiry is this helper's own timer. Whether the clip is
 * still ours is told by its timestamp while the app may look at the clipboard (in focus), and by
 * the change notifications it received before; from the background, where the platform answers
 * neither, a clip nothing has reported replaced counts as ours.
 */
@MainThread
class SecurePasteboard(
    private val clipboard: ClipboardWriting,
    private val now: () -> Instant = Instant::now,
    private val timer: ExpiryTimer = MainLooperExpiryTimer(now),
) {
    enum class Sensitivity {
        /** Ordinary message text: expires after [ORDINARY_LIFETIME]. */
        ORDINARY,

        /** Codes and numbers: marked sensitive, expires after [SENSITIVE_LIFETIME]. */
        SENSITIVE,

        /**
         * Copied to be pasted into another app (the generated backup password): marked sensitive,
         * expires after [SENSITIVE_LIFETIME], and not cleared by [clearIfOwned] (leaving the app,
         * locking).
         */
        HAND_OFF,

        /** Never copied (disappearing or view-once content). */
        FORBIDDEN,
    }

    private class Copy(val stamp: Long?, val clearsWithApp: Boolean) {
        /** A change we were told of replaced it. */
        var replaced = false
        var cancelExpiry: () -> Unit = {}
    }

    /** Our last copy, while it may still be on the clipboard. */
    private var latest: Copy? = null

    /** When the last copy expires, for tests and diagnostics. */
    var expiresAt: Instant? = null
        private set

    init {
        clipboard.onChange { clipChanged() }
    }

    /** Copies [text]; returns `false` if [sensitivity] forbids it. */
    fun copy(text: String, sensitivity: Sensitivity): Boolean {
        val lifetime = when (sensitivity) {
            Sensitivity.FORBIDDEN -> return false
            Sensitivity.ORDINARY -> ORDINARY_LIFETIME
            Sensitivity.SENSITIVE, Sensitivity.HAND_OFF -> SENSITIVE_LIFETIME
        }
        latest?.cancelExpiry?.invoke()
        val stamp = clipboard.write(text, sensitive = sensitivity != Sensitivity.ORDINARY)
        // A hand-off is meant to outlive this app's foreground: only its expiry removes it.
        val copy = Copy(stamp, clearsWithApp = sensitivity != Sensitivity.HAND_OFF)
        latest = copy
        val expiry = now().after(lifetime)
        expiresAt = expiry
        copy.cancelExpiry = timer.schedule(expiry) { expire(copy) }
        return true
    }

    /**
     * Clears the clipboard if it still holds our last copy (on lock and when the app goes to the
     * background). Something the user copied in another app since then is left alone, and so is a
     * hand-off.
     */
    fun clearIfOwned() {
        val copy = latest ?: return
        if (!copy.clearsWithApp) return
        if (isStillOurs(copy)) {
            clipboard.clear()
            RuntimeLog.privacy.debug("clipboard cleared")
        }
        forget(copy)
    }

    private fun expire(copy: Copy) {
        if (latest !== copy) return
        if (isStillOurs(copy)) {
            clipboard.clear()
            RuntimeLog.privacy.debug("clipboard copy expired")
        }
        forget(copy)
    }

    private fun forget(copy: Copy) {
        copy.cancelExpiry()
        if (latest === copy) {
            latest = null
            expiresAt = null
        }
    }

    private fun isStillOurs(copy: Copy): Boolean {
        if (copy.replaced) return false
        val current = clipboard.stamp() ?: return true // empty, or not ours to look at: nothing replaced it that we know of
        return copy.stamp == null || copy.stamp == current
    }

    private fun clipChanged() {
        val copy = latest ?: return
        val current = clipboard.stamp() ?: return
        if (copy.stamp != null && current != copy.stamp) copy.replaced = true
    }

    companion object {
        val SENSITIVE_LIFETIME: Duration = 60.seconds
        val ORDINARY_LIFETIME: Duration = 10.minutes
    }
}
