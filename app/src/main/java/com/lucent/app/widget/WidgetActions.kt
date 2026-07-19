package com.lucent.app.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.lucent.app.MainActivity

/**
 * Shared plumbing for Lucent's home-screen widgets (task 9).
 *
 * Every widget is a thin launcher: tapping it starts [MainActivity] with an [EXTRA_ACTION] telling
 * the app what to do (open a fresh note composer, a task composer, the assistant, or the task list).
 * The app is `singleTask`, so these arrive at `MainActivity.handleWidgetIntent` via onNewIntent when
 * it's already running, or onCreate on a cold start — either way the same routing runs.
 */
object WidgetActions {

    const val EXTRA_ACTION = "com.lucent.app.widget.ACTION"

    const val NEW_NOTE = "new_note"
    const val NEW_TASK = "new_task"
    const val ASK = "ask"
    const val OPEN_TASKS = "open_tasks"

    // Item-level actions for the two content widgets ported from the first settings variant: a row
    // of the tasks-list widget opens that task, and the pinned-note widget opens that note.
    const val OPEN_TASK_ITEM = "open_task_item"
    const val OPEN_NOTE_ITEM = "open_note_item"

    /** Extra carrying the row id for [OPEN_TASK_ITEM] / [OPEN_NOTE_ITEM]. */
    const val EXTRA_ID = "com.lucent.app.widget.EXTRA_ID"

    /**
     * A PendingIntent that launches the app to perform [action].
     *
     * Each action needs its own request code: a PendingIntent's identity ignores its extras, so
     * reusing one request code across actions would collide and every widget would do the same
     * thing. FLAG_IMMUTABLE is required on modern Android for a PendingIntent we don't need to
     * mutate; FLAG_UPDATE_CURRENT keeps the extra current if the intent is rebuilt.
     */
    fun pendingIntent(context: Context, action: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            this.action = Intent.ACTION_MAIN
            putExtra(EXTRA_ACTION, action)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(context, action.hashCode(), intent, flags)
    }

    /**
     * A PendingIntent that launches the app to open one specific item ([action] +
     * [WidgetActions.EXTRA_ID]). The id is folded into both the request code and a unique data URI
     * so two widgets pointing at different items never alias to the same PendingIntent (a
     * PendingIntent's identity ignores its extras).
     */
    fun itemPendingIntent(context: Context, action: String, id: Long): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            this.action = Intent.ACTION_MAIN
            putExtra(EXTRA_ACTION, action)
            putExtra(EXTRA_ID, id)
            data = android.net.Uri.parse("lucent://widget/$action/$id")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(context, (action + id).hashCode(), intent, flags)
    }

    /**
     * The single PendingIntent *template* for the tasks-list widget: each row supplies only its id
     * via a fill-in intent, merged into this. It must be FLAG_MUTABLE for the fill-in to apply —
     * safe on modern Android because the intent is explicit (its component is our own activity).
     */
    fun taskListTemplate(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            this.action = Intent.ACTION_MAIN
            putExtra(EXTRA_ACTION, OPEN_TASK_ITEM)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        return PendingIntent.getActivity(context, "task_list_template".hashCode(), intent, flags)
    }
}
