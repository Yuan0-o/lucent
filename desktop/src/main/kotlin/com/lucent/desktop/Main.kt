package com.lucent.desktop

import android.content.DesktopContext
import androidx.activity.compose.DesktopBackDispatcher
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import com.lucent.app.AppScope
import com.lucent.app.data.AttachmentAccess
import com.lucent.app.data.AttachmentMigration
import com.lucent.app.data.SettingsRepository
import com.lucent.app.data.TrashCleanup
import com.lucent.app.reminders.ReminderScheduler
import com.lucent.app.ui.AppLockController
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * The Windows entry point — the desktop peer of Android's MainActivity.onCreate.
 *
 * It does the same startup work in the same order: read the appearance/lock/language the first frame
 * depends on synchronously (so the window opens already in the user's chosen look rather than blinking
 * from defaults), apply the language and lock state, kick off the idempotent startup chores off the
 * UI thread, then open the window. Instead of an Activity it opens a Compose for Desktop [Window]
 * maximized, wires F11 to a fullscreen toggle and Esc to the shared back dispatcher, and routes task
 * reminders to Windows tray notifications. On exit it frees the on-device model and clears the
 * decrypted-preview cache, exactly as MainActivity.onDestroy does.
 */
fun main() {
    val context = DesktopContext

    // ONE synchronous read for everything the first frame needs, mirroring MainActivity. Falls back
    // to defaults on any failure so startup can never be blocked by it.
    val startup = try {
        runBlocking { SettingsRepository(context).startupPrefsOnce() }
    } catch (t: Throwable) {
        SettingsRepository.StartupPrefs(
            display = SettingsRepository.DisplayPrefs("system", "SUNSET", "system"),
            appLockEnabled = false,
            startupLoggingEnabled = false,
            systemIntegrationEnabled = false
        )
    }

    // Language + lock decided before anything composes (prevents the startup "blink").
    com.lucent.app.i18n.L.apply(startup.appLanguage)
    AppLockController.markProcessStarted(startup.appLockEnabled)

    // Startup chores: the same idempotent set MainActivity runs on launch, each on the process-lifetime
    // IO scope and each wrapped so one failing can't take down the others or the app.
    AppScope.io.launch { runCatching { ReminderScheduler.rescheduleAll(context) } }
    AppScope.io.launch { runCatching { TrashCleanup.purgeExpired(context) } }
    AppScope.io.launch {
        runCatching {
            AttachmentMigration.runIfNeeded(context)
            AttachmentMigration.encryptExistingAttachments(context)
        }
    }
    AppScope.io.launch { runCatching { AttachmentAccess.clearPreviewCache(context) } }
    AppScope.io.launch { runCatching { com.lucent.app.data.AppDatabase.getInstance(context) } }

    application {
        val windowState = rememberWindowState(placement = WindowPlacement.Maximized)
        val trayState = rememberTrayState()
        val icon = rememberVectorPainter(Icons.Default.AutoAwesome)

        // Desktop has no OS alarm service, so reminders can only fire while the app runs; when they do,
        // ReminderScheduler calls this notifier, which raises a Windows tray notification.
        LaunchedEffect(trayState) {
            ReminderScheduler.notifier = { title, message ->
                trayState.sendNotification(Notification(title, message))
            }
        }

        Tray(
            state = trayState,
            icon = icon,
            tooltip = "Lucent",
            menu = { Item("Exit", onClick = ::exitApplication) }
        )

        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = "Lucent",
            icon = icon,
            onKeyEvent = { event ->
                when {
                    // F11 toggles fullscreen (returns to maximized), as the requirement asks.
                    event.type == KeyEventType.KeyDown && event.key == Key.F11 -> {
                        windowState.placement =
                            if (windowState.placement == WindowPlacement.Fullscreen) WindowPlacement.Maximized
                            else WindowPlacement.Fullscreen
                        true
                    }
                    // Esc drives the shared back dispatcher (close the open editor, then the sub-screen),
                    // reproducing Android's system-back for the verbatim screens' BackHandlers.
                    event.type == KeyEventType.KeyDown && event.key == Key.Escape ->
                        DesktopBackDispatcher.dispatch()
                    else -> false
                }
            }
        ) {
            DesktopApp(startup)
        }
    }

    // application { } returns once all windows close. Free the model and wipe decrypted previews.
    runCatching { com.lucent.app.local.LocalLlm.shutdown() }
    runCatching { AttachmentAccess.clearPreviewCache(context) }
}
