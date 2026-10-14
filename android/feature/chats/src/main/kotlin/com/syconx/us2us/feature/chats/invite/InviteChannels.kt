package com.syconx.us2us.feature.chats.invite

import android.app.Activity
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.syconx.us2us.ui.env.ExternalActivityGuard
import com.syconx.us2us.ui.env.LocalExternalActivityGuard
import java.security.SecureRandom

/**
 * The ways to send an invitation. The recipient is always picked in the system's own UI (the SMS
 * app, a mail app, WhatsApp, the share sheet): us2us never reads, asks for or uploads the contacts,
 * and never learns whom the user invited.
 */
enum class InviteChannel(val raw: String) {
    /** The SMS app (`smsto:`). */
    Sms("sms"),

    /** WhatsApp itself when installed, else `https://wa.me/?text=…`. */
    WhatsApp("whatsapp"),

    /** A mail app (`mailto:` with subject and body). */
    Email("email"),

    /** The system share sheet. */
    More("more"),

    /** The link to the clipboard (removed after two minutes). */
    CopyLink("copy"),
}

/** What came of a channel, as far as the app can know. */
enum class InviteOutcome {
    /** The share sheet handed the text to the target the user picked. */
    Sent,

    /** The link is on the clipboard. */
    Copied,

    /** Another app took over (SMS, mail, WhatsApp); the result is not known. */
    HandedOff,

    Cancelled,
    Failed,
}

/**
 * Starts the channels' system activities (under the [ExternalActivityGuard], so coming back does
 * not lock the app). Every channel that cannot work on this device falls back to the share sheet,
 * so nothing is a dead end.
 */
@Stable
class InviteSender(private val context: Context, private val guard: ExternalActivityGuard) {
    fun send(channel: InviteChannel, message: InviteMessage, completion: (InviteOutcome) -> Unit) {
        when (channel) {
            InviteChannel.Sms -> {
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")).putExtra("sms_body", message.text)
                if (start(intent)) completion(InviteOutcome.HandedOff) else share(message, completion)
            }
            InviteChannel.Email -> {
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse(InviteChannelUrl.mailto(message.subject, message.text)))
                    .putExtra(Intent.EXTRA_SUBJECT, message.subject)
                    .putExtra(Intent.EXTRA_TEXT, message.text)
                if (start(intent)) completion(InviteOutcome.HandedOff) else share(message, completion)
            }
            InviteChannel.WhatsApp -> {
                val direct = if (isInstalled(WHATSAPP)) {
                    Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(WHATSAPP).putExtra(Intent.EXTRA_TEXT, message.text)
                } else {
                    null
                }
                val opened = (direct != null && start(direct)) ||
                    start(Intent(Intent.ACTION_VIEW, Uri.parse(InviteChannelUrl.whatsApp(message.text))))
                if (opened) completion(InviteOutcome.HandedOff) else share(message, completion)
            }
            InviteChannel.More -> share(message, completion)
            InviteChannel.CopyLink -> {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                if (clipboard == null) {
                    completion(InviteOutcome.Failed)
                } else {
                    InvitePasteboard.copy(message.link, clipboard)
                    completion(InviteOutcome.Copied)
                }
            }
        }
    }

    /** The share sheet with the text (and the subject for mail apps); [completion] hears [InviteOutcome.Sent] once a target was picked. */
    fun share(message: InviteMessage, completion: (InviteOutcome) -> Unit) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, message.text)
            putExtra(Intent.EXTRA_SUBJECT, message.subject)
            putExtra(Intent.EXTRA_TITLE, message.subject)
        }
        val chooser = Intent.createChooser(send, null, ChooserCallback.intentSender(context) { completion(InviteOutcome.Sent) })
        if (!start(chooser)) completion(InviteOutcome.Failed)
    }

    private fun start(intent: Intent): Boolean = try {
        // Outside an activity (a service or application context) the system needs a task of its own.
        if (context.findActivity() == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        guard.launching { context.startActivity(intent) }
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun isInstalled(packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private companion object {
        const val WHATSAPP = "com.whatsapp"
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** An [InviteSender] for the current activity. */
@Composable
fun rememberInviteSender(): InviteSender {
    val context = LocalContext.current
    val guard = LocalExternalActivityGuard.current
    return remember(context, guard) { InviteSender(context, guard) }
}

/**
 * The share sheet's “a target was picked” callback (the chooser's IntentSender): one receiver for
 * the process, not exported, that tells the latest share only (matched by a random token).
 */
private object ChooserCallback {
    private const val ACTION = "com.syconx.us2us.invite.SHARED"
    private const val EXTRA_TOKEN = "token"
    private val random = SecureRandom()
    private var registered = false
    private var token = 0L
    private var pending: (() -> Unit)? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getLongExtra(EXTRA_TOKEN, 0L) != token) return
            val done = pending
            pending = null
            done?.invoke()
        }
    }

    fun intentSender(context: Context, onPicked: () -> Unit): IntentSender {
        val app = context.applicationContext
        if (!registered) {
            ContextCompat.registerReceiver(app, receiver, IntentFilter(ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
        }
        token = random.nextLong().let { if (it == 0L) 1L else it }
        pending = onPicked
        val intent = Intent(ACTION).setPackage(app.packageName).putExtra(EXTRA_TOKEN, token)
        // Mutable: the chooser fills in the picked component.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(app, 0, intent, flags).intentSender
    }
}
