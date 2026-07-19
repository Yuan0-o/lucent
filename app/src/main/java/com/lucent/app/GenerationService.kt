package com.lucent.app

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * A short-lived foreground service that runs only while the assistant is generating a reply, so the
 * work survives the app being sent to the background (issue 17).
 *
 * ### Why a foreground service
 * Coroutine work in [com.lucent.app.ui.AssistantController] already lives on a process-lifetime
 * scope, so leaving the Assistant *tab* never interrupted a reply. What this adds is protection from
 * the OS reclaiming the whole process once the app is in the background: a running foreground service
 * raises the process's importance, so Android is far less likely to kill it mid-reply. The service is
 * started when a send begins and stopped the instant the reply finishes (or is stopped), so there is
 * no lingering notification and no battery cost once the work is done.
 *
 * ### Defensive by design
 * The controller starts and stops this best-effort, inside try/catch: modern Android places real
 * restrictions on starting foreground services and on service types, and the *correct* failure mode
 * for "couldn't keep the process alive extra-hard" is to simply generate on the background scope as
 * before — never to crash. The service itself also stops immediately if it can't enter the foreground
 * state, for the same reason.
 */
class GenerationService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val name = intent?.getStringExtra(EXTRA_NAME)?.takeIf { it.isNotBlank() } ?: "Assistant"
        ensureChannel(this)
        val notification = buildNotification(this, name)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // API 34+ requires a declared service type that matches a held permission.
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (t: Throwable) {
            // Couldn't enter the foreground — bail out cleanly. Generation continues on the
            // controller's own scope regardless.
            stopSelf()
        }
        // Not sticky: if the process is killed anyway, we don't want the OS respawning a bare service
        // with no generation attached to it.
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL_ID = "assistant_generation"
        private const val CHANNEL_NAME = "Assistant replies"
        private const val CHANNEL_DESC = "Shown briefly while the assistant is generating a reply"
        private const val NOTIF_ID = 5170
        private const val EXTRA_NAME = "assistant_name"

        fun start(context: Context, assistantName: String) {
            val intent = Intent(context.applicationContext, GenerationService::class.java)
                .putExtra(EXTRA_NAME, assistantName)
            ContextCompat.startForegroundService(context.applicationContext, intent)
        }

        fun stop(context: Context) {
            context.applicationContext.stopService(
                Intent(context.applicationContext, GenerationService::class.java)
            )
        }

        /** A quiet, low-importance channel — this is an activity indicator, not an alert. */
        private fun ensureChannel(context: Context) {
            val channel = NotificationChannelCompat
                .Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(CHANNEL_NAME)
                .setDescription(CHANNEL_DESC)
                .setVibrationEnabled(false)
                .setShowBadge(false)
                .build()
            NotificationManagerCompat.from(context.applicationContext).createNotificationChannel(channel)
        }

        private fun buildNotification(context: Context, name: String): Notification {
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("$name is replying…")
                .setContentText("Finishing your reply in the background")
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(open)
                .build()
        }
    }
}
