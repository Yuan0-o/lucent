package com.lucent.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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

// ---------------------------------------------------------------------------------------
// Size buckets (this round's widget task). The user can resize every widget from 1x1 up to
// its max, and each size deserves its own composition rather than one squashed layout:
//  - SMALL  (~1x1): the glyph alone, centred — instantly readable, never the app icon.
//  - MEDIUM (~2x1): glyph over a one-line function label.
//  - WIDE   (>=3 columns): a banner with room for the label (and, for the assistant, the
//    "ask anything" pill; for progress, the next-due line).
// Chosen from the launcher-reported OPTION_APPWIDGET_MIN_* dp in onAppWidgetOptionsChanged,
// which works on every API level — no reliance on the S+ mapped-RemoteViews API, so Huawei
// and other forked launchers get the same behaviour.
// ---------------------------------------------------------------------------------------

private const val WIDE_MIN_DP = 176   // three launcher columns and up
private const val MEDIUM_MIN_DP = 100 // two columns (or a tall single column)

private fun sizeBucketLayout(options: Bundle?, small: Int, medium: Int, wide: Int): Int {
    val w = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ?: 0
    val h = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) ?: 0
    return when {
        w >= WIDE_MIN_DP -> wide
        w >= MEDIUM_MIN_DP || h >= MEDIUM_MIN_DP -> medium
        else -> small
    }
}

/**
 * Base for the three one-tap launchers: renders the size-appropriate layout and wires the whole
 * card to its action. Re-renders on every resize via [onAppWidgetOptionsChanged].
 */
abstract class ResponsiveActionWidget(
    private val small: Int,
    private val medium: Int,
    private val wide: Int,
    private val action: String
) : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) render(context, appWidgetManager, id)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle?
    ) {
        render(context, appWidgetManager, appWidgetId)
    }

    private fun render(context: Context, manager: AppWidgetManager, id: Int) {
        val layout = sizeBucketLayout(manager.getAppWidgetOptions(id), small, medium, wide)
        val views = RemoteViews(context.packageName, layout)
        views.setOnClickPendingIntent(R.id.widget_root, WidgetActions.pendingIntent(context, action))
        manager.updateAppWidget(id, views)
    }
}

/** One-tap "new note". */
class NewNoteWidget : ResponsiveActionWidget(
    R.layout.widget_new_note_small, R.layout.widget_new_note, R.layout.widget_new_note_wide,
    WidgetActions.NEW_NOTE
)

/** One-tap "new task". */
class NewTaskWidget : ResponsiveActionWidget(
    R.layout.widget_new_task_small, R.layout.widget_new_task, R.layout.widget_new_task_wide,
    WidgetActions.NEW_TASK
)

/** One-tap "ask the assistant"; at banner width it shows the quiet "ask anything" pill. */
class AssistantWidget : ResponsiveActionWidget(
    R.layout.widget_assistant_small, R.layout.widget_assistant, R.layout.widget_assistant_wide,
    WidgetActions.ASK
)

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

/**
 * Live task progress: how much of what's on the list is DONE — "3 / 8" plus a bar — with the next
 * due task named at banner width. Opens the task list when tapped. This replaces the old bare
 * open-count: the user asked to *see progress*, and a fraction with a bar answers "how am I
 * doing" where a lone count only answered "how much is left".
 */
class TaskSummaryWidget : AppWidgetProvider() {

    private data class Progress(val done: Int, val total: Int, val next: String?)

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        renderAll(context, appWidgetManager, appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: android.os.Bundle?
    ) {
        renderAll(context, appWidgetManager, intArrayOf(appWidgetId))
    }

    private fun renderAll(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // Reading the (encrypted) database can block, so hop off the main thread and keep the
        // broadcast alive until we've finished with goAsync(). A failure just shows a dash rather
        // than crashing the launcher.
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        AppScope.io.launch {
            val progress = try {
                val tasks = AppDatabase.getInstance(appContext).taskDao().getAllOnce()
                    .filter { it.trashedAt == null }
                val done = tasks.count { it.isDone }
                // "Next" = the open task due soonest; with no dated ones, the newest open task,
                // so the line is never blank while anything at all remains to do.
                val open = tasks.filter { !it.isDone }
                val next = (open.filter { it.dueAt != null }.minByOrNull { it.dueAt!! }
                    ?: open.maxByOrNull { it.createdAt })
                    ?.title?.ifBlank { appContext.getString(R.string.widget_untitled_task) }
                Progress(done, tasks.size, next)
            } catch (t: Throwable) {
                null
            }
            try {
                for (id in appWidgetIds) {
                    val layout = sizeBucketLayout(
                        appWidgetManager.getAppWidgetOptions(id),
                        R.layout.widget_task_summary_small,
                        R.layout.widget_task_summary,
                        R.layout.widget_task_summary_wide
                    )
                    val views = RemoteViews(appContext.packageName, layout)
                    if (progress == null) {
                        views.setTextViewText(R.id.progress_fraction, "—")
                        views.setProgressBar(R.id.progress_bar, 100, 0, false)
                    } else {
                        views.setTextViewText(
                            R.id.progress_fraction,
                            appContext.getString(R.string.widget_fraction_fmt, progress.done, progress.total)
                        )
                        val pct = if (progress.total == 0) 0 else (progress.done * 100 / progress.total)
                        views.setProgressBar(R.id.progress_bar, 100, pct, false)
                        // The next-due line only exists in the wide layout; touching an id a layout
                        // doesn't contain is a RemoteViews error, so it's bound only there.
                        if (layout == R.layout.widget_task_summary_wide) {
                            val nextLine = when {
                                progress.total > 0 && progress.done == progress.total ->
                                    appContext.getString(R.string.widget_all_done)
                                progress.next != null ->
                                    appContext.getString(R.string.widget_next_fmt, progress.next)
                                else -> appContext.getString(R.string.widget_today_empty)
                            }
                            views.setTextViewText(R.id.progress_next, nextLine)
                        }
                    }
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

    private data class Row(val id: Long, val title: String, val subtitle: String, val isDone: Boolean)

    private var rows: List<Row> = emptyList()

    override fun onCreate() {}

    override fun onDataSetChanged() {
        rows = try {
            runBlocking {
                // Done and not-done alike (this round's ask): the open work sorts first — due
                // soonest at the top, undated newest-first after — and the completed rows follow,
                // muted and struck through, so the list reads as "here's the day, and here's what
                // you've already knocked out" rather than pretending finished work never existed.
                AppDatabase.getInstance(context).taskDao().getAllOnce()
                    .filter { it.trashedAt == null }
                    .sortedWith(compareBy({ it.isDone }, { it.dueAt ?: Long.MAX_VALUE }, { -it.createdAt }))
                    .take(MAX_ROWS)
                    .map { task ->
                        val progress = Checklist.progress(task.subtasks)
                        val subtitle = when {
                            progress != null -> progress.first.toString() + "/" + progress.second + " subtasks"
                            task.notes.isNotBlank() -> task.notes.lineSequence().firstOrNull()?.trim().orEmpty()
                            else -> ""
                        }
                        Row(task.id, task.title.ifBlank { context.getString(R.string.widget_untitled_task) }, subtitle, task.isDone)
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
            // Completed rows read as receipts: struck through, dimmed, check filled. Spans travel
            // fine inside RemoteViews, so the strike is a real strike rather than a unicode hack.
            val title: CharSequence = if (row.isDone) {
                android.text.SpannableString(row.title).apply {
                    setSpan(
                        android.text.style.StrikethroughSpan(), 0, length,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            } else row.title
            setTextViewText(R.id.widget_task_item_title, title)
            setTextColor(R.id.widget_task_item_title, if (row.isDone) 0x80FFFFFF.toInt() else 0xFFFFFFFF.toInt())
            setImageViewResource(
                R.id.widget_task_item_check,
                if (row.isDone) R.drawable.ic_widget_check_on else R.drawable.ic_widget_check_off
            )
            setContentDescription(
                R.id.widget_task_item_check,
                context.getString(if (row.isDone) R.string.widget_a11y_reopen else R.string.widget_a11y_mark_done)
            )
            if (row.subtitle.isBlank()) {
                setViewVisibility(R.id.widget_task_item_subtitle, android.view.View.GONE)
            } else {
                setViewVisibility(R.id.widget_task_item_subtitle, android.view.View.VISIBLE)
                setTextViewText(R.id.widget_task_item_subtitle, row.subtitle)
            }
            // TWO fill-ins into the one neutral template: the row body opens the task, the check
            // asks (via the in-app confirmation dialog) to complete or reopen it. Each supplies
            // its own action alongside the id.
            val openFillIn = Intent().apply {
                putExtra(WidgetActions.EXTRA_ACTION, WidgetActions.OPEN_TASK_ITEM)
                putExtra(WidgetActions.EXTRA_ID, row.id)
            }
            setOnClickFillInIntent(R.id.widget_task_item_root, openFillIn)
            val toggleFillIn = Intent().apply {
                putExtra(WidgetActions.EXTRA_ACTION, WidgetActions.TOGGLE_TASK_ITEM)
                putExtra(WidgetActions.EXTRA_ID, row.id)
            }
            setOnClickFillInIntent(R.id.widget_task_item_check_area, toggleFillIn)
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
                    setTextViewText(R.id.widget_pinned_title, context.getString(R.string.widget_no_pinned_note))
                    setViewVisibility(R.id.widget_pinned_body, android.view.View.GONE)
                    setOnClickPendingIntent(R.id.widget_pinned_root, WidgetActions.pendingIntent(context, WidgetActions.NEW_NOTE))
                } else {
                    val preview = if (note.isChecklist) {
                        Checklist.parse(note.checklist).take(3).joinToString("\n") { "• " + it.text }
                    } else {
                        note.body
                    }
                    setTextViewText(R.id.widget_pinned_title, note.title.ifBlank { context.getString(R.string.widget_untitled) })
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

        val summaryIds = manager.getAppWidgetIds(ComponentName(appContext, TaskSummaryWidget::class.java))
        if (summaryIds.isNotEmpty()) {
            appContext.sendBroadcast(
                Intent(appContext, TaskSummaryWidget::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, summaryIds)
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
