package com.lucent.app.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lucent.app.AppScope
import kotlinx.coroutines.launch

/**
 * Re-arms every task reminder after the device reboots or the app is updated — alarms set with
 * AlarmManager survive neither.
 *
 * This receiver never shows a notification itself. All it does is put the alarms back, by handing
 * off to [ReminderScheduler.rescheduleAll], which decides task by task what should and shouldn't be
 * pending.
 *
 * `goAsync()` is what makes that safe. `onReceive` runs on the main thread and the process is free
 * to be killed the moment it returns, so firing off a background job and returning immediately
 * would be a race between the database read and process death — one the reschedule loses often
 * enough to matter, and silently, which is the worst way to lose. Holding the broadcast open until
 * the work actually finishes turns that into a guarantee.
 *
 * `MY_PACKAGE_REPLACED` is included alongside `BOOT_COMPLETED` because an app update wipes alarms
 * just as thoroughly as a reboot does, and it's the case people never think to test. The QUICKBOOT
 * actions cover OEMs (older HTC and Samsung builds, mostly) that use a proprietary fast-boot
 * broadcast instead of the standard one.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val relevant = action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON"
        if (!relevant) return

        val appContext = context.applicationContext
        val pendingResult = goAsync()
        AppScope.io.launch {
            try {
                ReminderScheduler.rescheduleAll(appContext)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
