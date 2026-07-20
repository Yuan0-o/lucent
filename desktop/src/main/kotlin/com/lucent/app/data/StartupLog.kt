package com.lucent.app.data

import android.content.Context
import com.lucent.app.AppScope
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local startup logging (task 15) — **strictly on-device, never transmitted**.
 *
 * ### The one hard rule
 *
 * These logs never leave the phone. There is no endpoint, no upload, no analytics client, and no
 * network call anywhere in this file — the only thing it touches is a single file in the app's
 * *internal* private storage (`filesDir`), which no other app can read and which is wiped on
 * uninstall. Export is a deliberate, user-initiated act that writes to a location the user picks
 * through the system file picker; nothing is sent anywhere on its own. This is a debugging aid for a
 * privacy-first app, so "local only" is not a nicety, it is the whole contract.
 *
 * ### Off by default
 *
 * Logging does nothing until [setEnabled] flips it on, which only happens when the user turns on the
 * toggle in Settings → Data (and reads the disclaimer there). While disabled, [event] is a no-op
 * that never opens a file, so there is zero cost and zero data captured for a user who hasn't asked
 * for it.
 *
 * ### Shape
 *
 * Each event is one line: an ISO-8601 local timestamp, then the message. Writes happen off the main
 * thread (on [AppScope.io]) and are serialised with [lock] so concurrent events can't corrupt the
 * file. The file is capped at [MAX_BYTES] and trimmed from the front when it grows past that, so a
 * long-running install can never let the log grow without bound.
 */
object StartupLog {

    private const val FILE_NAME = "startup_log.txt"
    private const val MAX_BYTES = 256 * 1024

    private val lock = Any()
    @Volatile private var enabled = false

    // A single formatter, guarded by the write lock (SimpleDateFormat isn't thread-safe).
    private val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun setEnabled(value: Boolean) { enabled = value }
    fun isEnabled(): Boolean = enabled

    private fun logFile(context: Context) = File(context.applicationContext.filesDir, FILE_NAME)

    /**
     * Record one line if logging is enabled. No-op otherwise. The write is dispatched to the IO
     * scope so it never blocks the caller (this is called during startup, from the main thread).
     */
    fun event(context: Context, message: String) {
        if (!enabled) return
        val app = context.applicationContext
        val stamp = synchronized(lock) { format.format(Date()) }
        AppScope.io.launch {
            synchronized(lock) {
                try {
                    val f = logFile(app)
                    f.appendText("$stamp  $message\n")
                    if (f.length() > MAX_BYTES) {
                        // Keep the most recent half. Reading a quarter-MB file to trim it is cheap
                        // and only happens once the log has actually grown large.
                        val kept = f.readText().takeLast(MAX_BYTES / 2)
                        f.writeText(kept)
                    }
                } catch (_: Throwable) {
                    // A diagnostic log must never itself crash the app it's diagnosing.
                }
            }
        }
    }

    /** The full log text, for the in-app export. Empty string if there's nothing (or on error). */
    fun readAll(context: Context): String = synchronized(lock) {
        val f = logFile(context)
        if (!f.exists()) "" else try { f.readText() } catch (_: Throwable) { "" }
    }

    /**
     * What the "Export logs" button actually writes: the event log above, followed by a dump of THIS
     * app's own logcat — which is where the native on-device-model engine's diagnostics (backend
     * count, token counts, decode error codes) land, and the whole reason a bug report is useful.
     * Reading our own process's logcat needs no permission on most devices; if an OEM build blocks it
     * the section is noted as unavailable rather than failing the export, and the event log (which is
     * captured independently, in-process) still carries the high-level trail.
     */
    fun buildExport(context: Context): String {
        val events = readAll(context)
        return buildString {
            append("==== Lucent event log ====\n")
            append(if (events.isBlank()) "(no events recorded)\n" else events)
            append("\n==== logcat: this app only (includes the native model engine) ====\n")
            append(captureOwnLogcat())
        }
    }

    /**
     * Dump this process's own logcat buffer. `--pid` scopes it to us, so it's only ever our own
     * lines (no other app's data), and on API 24+ needs no special permission. Bounded so a huge
     * buffer can't produce a runaway file.
     */
    private fun captureOwnLogcat(): String = try {
        val pid = android.os.Process.myPid()
        val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "--pid=$pid"))
        val text = proc.inputStream.bufferedReader().use { it.readText() }
        try { proc.waitFor() } catch (_: Throwable) {}
        when {
            text.isBlank() -> "(logcat returned nothing — some OEM builds, e.g. MIUI, restrict it; the event log above still applies)\n"
            text.length > MAX_BYTES -> text.takeLast(MAX_BYTES)
            else -> text
        }
    } catch (t: Throwable) {
        "(couldn't read logcat on this device: ${t.message})\n"
    }

    /** Whether there is anything to export yet. */
    fun hasEntries(context: Context): Boolean = synchronized(lock) {
        val f = logFile(context)
        f.exists() && f.length() > 0
    }

    /** Delete the local log file. */
    fun clear(context: Context) {
        synchronized(lock) {
            try { logFile(context).delete() } catch (_: Throwable) {}
        }
    }
}
