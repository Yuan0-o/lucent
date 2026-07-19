package com.lucent.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.lucent.app.AppScope
import com.lucent.app.R
import com.lucent.app.data.AppDatabase
import com.lucent.app.data.Checklist
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Lucent's home-screen widgets (task 9). Five small, deliberately compact widgets:
 *
 *  - [NewNoteWidget], [NewTaskWidget], [AssistantWidget] — one-tap shortcuts that open a fresh note
 *    composer, a fresh task composer, or the assistant. These are the three the brief asked for.
 *  - [QuickActionsWidget] — all three of the above in a single tidy bar, for people who'd rather
 *    spend one cell on the lot than three.
 *  - [TaskSummaryWidget] — shows how many tasks are still open and opens the task list when tapped;
 *    the one widget that surfaces live data rather than just launching something.
 *
 * Each is a thin launcher over [WidgetActions.pendingIntent]; only the summary reads the database,
 * and it does so off the main thread under [android.content.BroadcastReceiver.goAsync].
 */

/** One-tap "new note". */
class NewNoteWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_new_note)
            views.setOnClickPendingIntent(R.id.widget_root, WidgetActions.pendingIntent(context, WidgetActions.NEW_NOTE))
            appWidgetManager.updateAppWidget(id, views)
        }
    }
}

/** One-tap "new task". */
class NewTaskWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_new_task)
            views.setOnClickPendingIntent(R.id.widget_root, WidgetActions.pendingIntent(context, WidgetActions.NEW_TASK))
            appWidgetManager.updateAppWidget(id, views)
        }
    }
}

/** One-tap "ask the assistant". */
class AssistantWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_assistant)
            views.setOnClickPendingIntent(R.id.widget_root, WidgetActions.pendingIntent(context, WidgetActions.ASK))
            appWidgetManager.updateAppWidget(id, views)
        }
    }
}

/** All three shortcuts in one compact bar. */
class QuickActionsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_quick_actions)
            views.setOnClickPendingIntent(R.id.cell_note, WidgetActions.pendingIntent(context, WidgetActions.NEW_NOTE))
            views.setOnClickPendingIntent(R.id.cell_task, WidgetActions.pendingIntent(context, WidgetActions.NEW_TASK))
            views.setOnClickPendingIntent(R.id.cell_ask, WidgetActions.pendingIntent(context, WidgetActions.ASK))
            appWidgetManager.updateAppWidget(id, views)
        }
    }
}

/** Shows the number of open tasks; opens the task list when tapped. */
class TaskSummaryWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // Reading the (encrypted) database can block, so hop off the main thread and keep the
        // broadcast alive until we've finished with goAsync(). A failure just shows a dash rather
        // than crashing the launcher.
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        AppScope.io.launch {
            val count = try {
                AppDatabase.getInstance(appContext).taskDao().activeCountOnce()
            } catch (t: Throwable) {
                -1
            }
            try {
                for (id in appWidgetIds) {
                    val views = RemoteViews(appContext.packageName, R.layout.widget_task_summary)
                    views.setTextViewText(R.id.summary_count, if (count >= 0) count.toString() else "—")
                    views.setTextViewText(
                        R.id.summary_label,
                        if (count == 1) "open task" else "open tasks"
                    )
                    views.setOnClickPendingIntent(R.id.widget_root, WidgetActions.pendingIntent(appContext, WidgetActions.OPEN_TASKS))
                    appWidgetManager.updateAppWidget(id, views)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}

// ---------------------------------------------------------------------------------------
// Content widgets ported from the first settings variant: a scrollable tasks list and a
// pinned-note preview. They surface data rather than just launching something, and read only
// the local, already-decrypted database — nothing touches the network.
// ---------------------------------------------------------------------------------------

/** A scrollable list of the tasks still to do, with a quick-add "+" in its header. */
class TodayTasksWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (widgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_today_tasks).apply {
                // Header "+" opens a new-task composer; tapping the title opens the Tasks tab.
                setOnClickPendingIntent(R.id.widget_today_add, WidgetActions.pendingIntent(context, WidgetActions.NEW_TASK))
                setOnClickPendingIntent(R.id.widget_today_title, WidgetActions.pendingIntent(context, WidgetActions.OPEN_TASKS))

                // Bind the ListView to the RemoteViewsService that supplies the task rows. The data
                // URI keeps each widget instance's service intent distinct.
                val serviceIntent = Intent(context, TodayTasksWidgetService::class.java).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                    data = Uri.parse("lucent://widget/today/" + widgetId)
                }
                setRemoteAdapter(R.id.widget_today_list, serviceIntent)
                setEmptyView(R.id.widget_today_list, R.id.widget_today_empty)

                // One template PendingIntent for the whole list; each row's fill-in supplies its id.
                setPendingIntentTemplate(R.id.widget_today_list, WidgetActions.taskListTemplate(context))
            }
            appWidgetManager.updateAppWidget(widgetId, views)
        }
        // Ask the list to re-fetch, in case this update was triggered by a data change.
        appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.widget_today_list)
    }
}

class TodayTasksWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = TodayTasksFactory(applicationContext)
}

/**
 * Feeds task rows into the [TodayTasksWidget] list. RemoteViewsFactory methods are called on a
 * binder thread, so reading the database synchronously here is correct — and cheap, since it's the
 * same one-shot query the app uses, just filtered to active tasks.
 */
private class TodayTasksFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {

    private data class Row(val id: Long, val title: String, val subtitle: String)

    private var rows: List<Row> = emptyList()

    override fun onCreate() {}

    override fun onDataSetChanged() {
        rows = try {
            runBlocking {
                AppDatabase.getInstance(context).taskDao().getAllOnce()
                    .filter { !it.isDone && it.trashedAt == null }
                    .sortedByDescending { it.createdAt }
                    .take(MAX_ROWS)
                    .map { task ->
                        val progress = Checklist.progress(task.subtasks)
                        val subtitle = when {
                            progress != null -> progress.first.toString() + "/" + progress.second + " subtasks"
                            task.notes.isNotBlank() -> task.notes.lineSequence().firstOrNull()?.trim().orEmpty()
                            else -> ""
                        }
                        Row(task.id, task.title.ifBlank { "Untitled task" }, subtitle)
                    }
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    override fun onDestroy() { rows = emptyList() }

    override fun getCount(): Int = rows.size

    override fun getViewAt(position: Int): RemoteViews {
        val row = rows.getOrNull(position)
            ?: return RemoteViews(context.packageName, R.layout.widget_today_tasks_item)
        return RemoteViews(context.packageName, R.layout.widget_today_tasks_item).apply {
            setTextViewText(R.id.widget_task_item_title, row.title)
            if (row.subtitle.isBlank()) {
                setViewVisibility(R.id.widget_task_item_subtitle, android.view.View.GONE)
            } else {
                setViewVisibility(R.id.widget_task_item_subtitle, android.view.View.VISIBLE)
                setTextViewText(R.id.widget_task_item_subtitle, row.subtitle)
            }
            // Fill-in intent: only the id, merged into the list's template PendingIntent.
            val fillIn = Intent().apply { putExtra(WidgetActions.EXTRA_ID, row.id) }
            setOnClickFillInIntent(R.id.widget_task_item_root, fillIn)
        }
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = rows.getOrNull(position)?.id ?: position.toLong()
    override fun hasStableIds(): Boolean = true

    companion object { private const val MAX_ROWS = 25 }
}

/** The most recently updated pinned note at a glance; tapping opens it. */
class PinnedNoteWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // Pick the most recently updated pinned, non-archived, non-trashed note. RemoteViews work is
        // quick; the one DB read is a one-shot on this broadcast, guarded so a failure just renders
        // the empty state rather than crashing the launcher.
        val note = try {
            runBlocking {
                AppDatabase.getInstance(context).noteDao().getAllOnce()
                    .filter { it.pinned && !it.archived && it.trashedAt == null }
                    .maxByOrNull { it.updatedAt }
            }
        } catch (t: Throwable) {
            null
        }

        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_pinned_note).apply {
                if (note == null) {
                    setTextViewText(R.id.widget_pinned_title, "No pinned note")
                    setViewVisibility(R.id.widget_pinned_body, android.view.View.GONE)
                    setOnClickPendingIntent(R.id.widget_pinned_root, WidgetActions.pendingIntent(context, WidgetActions.NEW_NOTE))
                } else {
                    val preview = if (note.isChecklist) {
                        Checklist.parse(note.checklist).take(3).joinToString("\n") { "• " + it.text }
                    } else {
                        note.body
                    }
                    setTextViewText(R.id.widget_pinned_title, note.title.ifBlank { "Untitled" })
                    if (preview.isBlank()) {
                        setViewVisibility(R.id.widget_pinned_body, android.view.View.GONE)
                    } else {
                        setViewVisibility(R.id.widget_pinned_body, android.view.View.VISIBLE)
                        setTextViewText(R.id.widget_pinned_body, preview)
                    }
                    setOnClickPendingIntent(
                        R.id.widget_pinned_root,
                        WidgetActions.itemPendingIntent(context, WidgetActions.OPEN_NOTE_ITEM, note.id)
                    )
                }
            }
            appWidgetManager.updateAppWidget(id, views)
        }
    }
}

/**
 * Nudge the content widgets to redraw. Called when the app goes to the background (MainActivity
 * onStop) so the home screen is fresh as the launcher reappears. Cheap and safe to call often — it
 * only enqueues a redraw, and does nothing when no such widget is placed.
 */
object WidgetUpdater {
    fun refreshContent(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val appContext = context.applicationContext

        val todayIds = manager.getAppWidgetIds(ComponentName(appContext, TodayTasksWidget::class.java))
        if (todayIds.isNotEmpty()) {
            manager.notifyAppWidgetViewDataChanged(todayIds, R.id.widget_today_list)
            appContext.sendBroadcast(
                Intent(appContext, TodayTasksWidget::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, todayIds)
                }
            )
        }

        val pinnedIds = manager.getAppWidgetIds(ComponentName(appContext, PinnedNoteWidget::class.java))
        if (pinnedIds.isNotEmpty()) {
            appContext.sendBroadcast(
                Intent(appContext, PinnedNoteWidget::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, pinnedIds)
                }
            )
        }
    }
}
