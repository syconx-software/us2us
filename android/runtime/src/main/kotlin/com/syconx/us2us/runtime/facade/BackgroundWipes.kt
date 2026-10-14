package com.syconx.us2us.runtime.facade

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.syconx.us2us.runtime.support.RuntimeLog
import com.syconx.us2us.runtime.support.logLabel
import com.syconx.us2us.runtime.vault.AppDirectories
import java.time.Instant

/**
 * Wakes the app when a ghost conversation's deadline passes while it is locked or in the
 * background (§5.3): the wipe then needs no key, it only deletes key slots.
 */
fun interface BackgroundWipeScheduler {
    /** Schedules the next wake for [at], or cancels it (`null`). */
    fun schedule(at: Instant?)
}

/**
 * [BackgroundWipeScheduler] on `AlarmManager`: an exact alarm when the app may set one
 * (`SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM`), otherwise an inexact one allowed while idle. The
 * alarm reaches [BackgroundWipeReceiver], which the app's manifest must declare.
 */
class AlarmWipeScheduler(private val context: Context) : BackgroundWipeScheduler {
    private val alarms: AlarmManager? = context.getSystemService(AlarmManager::class.java)

    private fun intent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, BackgroundWipeReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    // An exact alarm only where the app may set one: checked with canScheduleExactAlarms() on
    // Android 12+, which lint cannot follow; a refusal falls back to an inexact alarm.
    @SuppressLint("MissingPermission")
    override fun schedule(at: Instant?) {
        val manager = alarms ?: return
        val operation = intent()
        if (at == null) {
            manager.cancel(operation)
            return
        }
        val time = at.toEpochMilli()
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, operation)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, operation)
            }
        } catch (_: SecurityException) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, operation)
        }
    }

    internal companion object {
        const val ACTION = "com.syconx.us2us.action.BACKGROUND_WIPE"
        private const val REQUEST_CODE = 0x5755
    }
}

/**
 * Runs the locked-path wipe of overdue ghost conversations (no DEK, no network) and schedules the
 * next one. Declared in the app's manifest, not exported.
 */
class BackgroundWipeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmWipeScheduler.ACTION) return
        val pending = goAsync()
        Thread {
            try {
                val directories = AppDirectories.standard(context.applicationContext ?: context)
                AppRuntime.runBackgroundWipes(directories, Instant.now())
                AlarmWipeScheduler(context.applicationContext ?: context).schedule(AppRuntime.nextBackgroundWipeDate(directories))
            } catch (e: Exception) {
                RuntimeLog.lifecycle.error("background wipe failed", e.logLabel)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
