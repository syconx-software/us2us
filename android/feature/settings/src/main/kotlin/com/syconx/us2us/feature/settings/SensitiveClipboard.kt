package com.syconx.us2us.feature.settings

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Hands a secret (the generated backup password) to the clipboard for a short while, so it can be
 * pasted into a password manager (iOS `SecurePasteboard` `.handOff`).
 */
fun interface SensitiveClipboard {
    /** Copies [text], marked sensitive, for [lifetime]; `false` when nothing was copied. */
    fun copy(text: String, lifetime: Duration): Boolean

    companion object {
        /** How long a copied secret stays: one minute. */
        val LIFETIME: Duration = 60.seconds
    }
}

/**
 * The system clipboard. The clip is marked sensitive (the system's paste preview and keyboards hide
 * it), and removed again after its lifetime if it is still ours. Android has no device-only or
 * expiring clips, so the removal is done by the app while its process lives.
 */
class SystemSensitiveClipboard(context: Context) : SensitiveClipboard {
    private val clipboard = context.applicationContext.getSystemService(ClipboardManager::class.java)

    override fun copy(text: String, lifetime: Duration): Boolean {
        val manager = clipboard ?: return false
        val clip = ClipData.newPlainText("", text).apply {
            description.extras = PersistableBundle().apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                } else {
                    putBoolean(SENSITIVE_EXTRA, true)
                }
            }
        }
        val copied = runCatching { manager.setPrimaryClip(clip) }.isSuccess
        if (!copied) return false
        pending?.let(handler::removeCallbacks)
        val clear = Runnable {
            pending = null
            val current = runCatching { manager.primaryClip }.getOrNull()
            val still = current?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
            // Unreadable (the app is not in front): cleared anyway, it can only be ours or newer.
            if (still == null || still == text) runCatching { manager.clearPrimaryClip() }
        }
        pending = clear
        handler.postDelayed(clear, lifetime.inWholeMilliseconds)
        return true
    }

    private companion object {
        /** The key keyboards read before Android 13 added [ClipDescription.EXTRA_IS_SENSITIVE]. */
        const val SENSITIVE_EXTRA = "android.content.extra.IS_SENSITIVE"

        val handler by lazy { Handler(Looper.getMainLooper()) }
        var pending: Runnable? = null
    }
}
