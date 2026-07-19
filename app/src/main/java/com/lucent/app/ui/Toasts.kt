package com.lucent.app.ui

import android.content.Context
import android.widget.Toast

/**
 * A single, self-replacing toast (task 14).
 *
 * ### The problem this fixes
 *
 * `Toast.makeText(ctx, …).show()` adds each toast to a **system-wide queue**. Fire five of them in
 * quick succession — five saves, five "copied" confirmations, a burst of "task completed" ticks —
 * and the user watches them play out one after another, each for its full duration, long after the
 * action that triggered them is over. The last, most relevant message is stuck behind a backlog of
 * stale ones.
 *
 * ### The fix
 *
 * Keep exactly one live [Toast] and [Toast.cancel] it before showing the next. A new message
 * therefore **immediately dismisses and overwrites** whatever is currently on screen instead of
 * lining up behind it, so the user always sees the newest thing and never a queue draining.
 *
 * The reference is process-global and guarded by [lock] because toasts are fired from many screens
 * and from background coroutines that have just hopped to the main thread; the cancel/show pair must
 * be atomic or two racing callers could each leak a toast. `show()` must run on the main thread —
 * every caller already does (they either run in a composable's event handler or `withContext(Main)`
 * before calling), which is exactly where a Toast is meant to be shown.
 */
object LucentToast {

    private val lock = Any()
    private var current: Toast? = null

    /**
     * Show [message], replacing any toast currently on screen. [longDuration] maps to
     * [Toast.LENGTH_LONG]; the default is [Toast.LENGTH_SHORT], matching the app's previous calls.
     */
    fun show(context: Context, message: String, longDuration: Boolean = false) {
        val appContext = context.applicationContext
        synchronized(lock) {
            // Cancel the outgoing toast first so it doesn't sit in the queue ahead of the new one.
            current?.cancel()
            val duration = if (longDuration) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
            current = Toast.makeText(appContext, message, duration).also { it.show() }
        }
    }
}
