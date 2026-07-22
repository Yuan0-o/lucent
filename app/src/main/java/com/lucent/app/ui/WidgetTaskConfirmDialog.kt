package com.lucent.app.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.lucent.app.data.AppDatabase
import com.lucent.app.data.Task
import com.lucent.app.tools.TaskActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Holds the task id behind a tasks-widget quick-complete tap until the user has answered.
 *
 * The check on a widget row can't be allowed to complete a task by itself: every other completion
 * in this app asks first, and a target that small on a home screen is exactly where a stray thumb
 * lands. So the widget's toggle intent only *parks* the id here (`MainActivity` calls [offer] and
 * brings up the Tasks tab for context); [WidgetTaskConfirmDialog], hosted at the activity root
 * beside [ShareIntakeDialog], asks the question, and only a Confirm actually completes — or, for a
 * task that is already done, reopens — the row. State lives here (process-global) rather than in
 * the intent for the same reason [ShareIntake]'s does: it survives the recomposition that shows
 * the dialog, and a tap that arrives while the app is locked simply waits until after unlock.
 */
object WidgetTaskConfirm {

    var pendingId by mutableStateOf<Long?>(null)
        private set

    fun offer(id: Long) { pendingId = id }
    fun clear() { pendingId = null }
}

/**
 * Shown while [WidgetTaskConfirm.pendingId] holds a widget quick-complete request. Asks with the
 * SAME words the in-app confirmations use ([confirmMarkDone]/[completeTaskBody] to complete,
 * [markNotDoneTitle]/[markNotDoneBody] to reopen), so the question reads identically wherever it
 * was raised; the answer goes through [TaskActions], so completion here ticks the checklist,
 * cancels the reminder, and schedules the repeat exactly like completing in the list would.
 */
@Composable
fun WidgetTaskConfirmDialog() {
    val id = WidgetTaskConfirm.pendingId ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // The row the widget pointed at. Widgets redraw on their own schedule, so the task may have
    // been completed, trashed, or deleted in the app since the widget last looked; a stale tap
    // gets a brief "no longer exists" toast rather than a question about a ghost, and the widget
    // is refreshed so the ghost row disappears too.
    var task by remember(id) { mutableStateOf<Task?>(null) }
    LaunchedEffect(id) {
        val appContext = context.applicationContext
        val loaded = withContext(Dispatchers.IO) {
            AppDatabase.getInstance(appContext).taskDao().getByIdOnce(id)
        }
        if (loaded == null || loaded.trashedAt != null) {
            WidgetTaskConfirm.clear()
            LucentToast.show(appContext, com.lucent.app.i18n.S.widgetTaskGone)
            com.lucent.app.widget.WidgetUpdater.refreshContent(appContext)
        } else {
            task = loaded
        }
    }
    val t = task ?: return

    val displayTitle = t.title.ifBlank { com.lucent.app.i18n.S.untitledTask }
    AlertDialog(
        onDismissRequest = { WidgetTaskConfirm.clear() },
        title = {
            Text(
                if (t.isDone) com.lucent.app.i18n.S.markNotDoneTitle
                else com.lucent.app.i18n.S.confirmMarkDone
            )
        },
        text = {
            Text(
                if (t.isDone) com.lucent.app.i18n.S.markNotDoneBody(displayTitle)
                else com.lucent.app.i18n.S.completeTaskBody(displayTitle)
            )
        },
        confirmButton = {
            Button(onClick = {
                WidgetTaskConfirm.clear()
                scope.launch {
                    val appContext = context.applicationContext
                    // Re-read the row at the moment of action: the dialog may have sat open while
                    // the task changed underneath it, and TaskActions.complete is documented to
                    // want the task AS IT IS before completion (its repeat advances from that
                    // row's own due date). A row that vanished in the meantime degrades to the
                    // same "no longer exists" toast as a stale tap.
                    val done = withContext(Dispatchers.IO) {
                        val db = AppDatabase.getInstance(appContext)
                        val fresh = db.taskDao().getByIdOnce(id)
                        if (fresh == null || fresh.trashedAt != null) return@withContext null
                        if (fresh.isDone) {
                            TaskActions.restore(appContext, db, fresh)
                            false
                        } else {
                            TaskActions.complete(appContext, db, fresh)
                            true
                        }
                    }
                    when (done) {
                        true -> LucentToast.show(appContext, com.lucent.app.i18n.S.widgetTaskDoneToast(displayTitle))
                        false -> LucentToast.show(appContext, com.lucent.app.i18n.S.widgetTaskReopenedToast(displayTitle))
                        null -> LucentToast.show(appContext, com.lucent.app.i18n.S.widgetTaskGone)
                    }
                    // The widget's list is a snapshot; redraw it so the row the user just acted on
                    // moves (or leaves) immediately rather than at the next scheduled refresh.
                    com.lucent.app.widget.WidgetUpdater.refreshContent(appContext)
                }
            }) { Text(com.lucent.app.i18n.S.actionConfirm) }
        },
        dismissButton = {
            TextButton(onClick = { WidgetTaskConfirm.clear() }) {
                Text(com.lucent.app.i18n.S.actionCancel)
            }
        }
    )
}
