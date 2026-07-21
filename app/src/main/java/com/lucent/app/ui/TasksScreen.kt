package com.lucent.app.ui

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lucent.app.AppNavigation
import com.lucent.app.AppScope
import com.lucent.app.Screen
import com.lucent.app.data.AppDatabase
import com.lucent.app.data.Attachment
import com.lucent.app.data.AttachmentLimits
import com.lucent.app.data.AttachmentStore
import com.lucent.app.data.Attachments
import com.lucent.app.data.Checklist
import com.lucent.app.data.ChecklistItem
import com.lucent.app.data.RepeatRule
import com.lucent.app.data.SearchQuery
import com.lucent.app.data.SettingsRepository
import com.lucent.app.data.Task
import com.lucent.app.data.TaskPriority
import com.lucent.app.data.TrashCleanup
import com.lucent.app.data.filterBySearch
import com.lucent.app.reminders.Notifications
import com.lucent.app.reminders.ReminderScheduler
import com.lucent.app.tools.TaskActions
import dev.chrisbanes.haze.hazeSource
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TasksScreen(active: Boolean = true) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getInstance(context) }
    val settingsRepo = remember { SettingsRepository(context) }
    val scope = rememberCoroutineScope()

    // The home page shows only pending, non-trashed tasks. As soon as a task is marked complete
    // (via the checkbox dialog or the assistant tool) it disappears from this flow and reappears on
    // the history page — the split is a plain SQL WHERE clause, not an in-memory filter, so moving
    // between the two is instantaneous.
    // The list flows are remembered so their Room subscription is stable across recompositions — a
    // fresh Flow per recomposition could miss the invalidation a just-inserted task fires, so a new
    // task now appears immediately instead of only after leaving and returning to the tab.
    val activeTasks by remember { db.taskDao().getActive() }.collectAsState(initial = com.lucent.app.data.DataCache.activeTasks)
    // All tasks (active + completed) are read too so search can honour is:done and the detail page can
    // resolve a completed task opened from history.
    val allTasks by remember { db.taskDao().getAll() }.collectAsState(initial = com.lucent.app.data.DataCache.activeTasks + com.lucent.app.data.DataCache.completedTasks)
    val onGradient = LocalOnGradient.current
    val onGradientMuted = LocalOnGradientMuted.current
    val hazeState = LocalHazeState.current

    // Remembered sort choice, persisted across app restarts (see SettingsRepository.tasksSort).
    val sortKey by settingsRepo.tasksSort.collectAsState(initial = "recent")
    val sortOption = TaskSort.fromKey(sortKey)

    // Frequency scores for the "Recent" home section (id -> activity), see UsageTracker.
    val taskUsage by remember { com.lucent.app.data.UsageTracker.scores(context, com.lucent.app.data.UsageTracker.Kind.TASK) }
        .collectAsState(initial = emptyMap())

    // View modes: home list (active tasks), completed history, trash, read-only detail, composer.
    var composing by remember { mutableStateOf(false) }
    var showingHistory by remember { mutableStateOf(false) }
    var showTrash by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    // Whether the header's secondary-action cluster (date filter, sort, overflow) is expanded.
    // Collapsed by default; survives config changes (task 16).
    var actionsExpanded by rememberSaveable { mutableStateOf(false) }
    var viewingId by remember { mutableStateOf<Long?>(null) }

    // Where to go when the task currently open was reached from *another* tab (task 4). Null for a
    // task opened from this tab's own list, which is the ordinary case and needs no return trip.
    var returnToOnClose by remember { mutableStateOf<Screen?>(null) }

    // A task asked for from elsewhere — a unified-search result tapped while on the Notes tab.
    // Consumed once; see AppNavigation. The origin tab is consumed in the same breath and held until
    // this task is closed, so closing it lands the user back where they started their search rather
    // than stranding them on whichever tab happened to own the result.
    LaunchedEffect(AppNavigation.pendingTaskId) {
        AppNavigation.consumeTaskId()?.let { id ->
            showSearch = false
            showingHistory = false
            showTrash = false
            viewingId = id
            returnToOnClose = AppNavigation.consumeReturnScreen()
        }
    }
    var editingTask by remember { mutableStateOf<Task?>(null) }
    var newTitle by remember { mutableStateOf(TextFieldValue("")) }
    var newNotes by remember { mutableStateOf("") }
    var pendingAttachments by remember { mutableStateOf<List<Attachment>>(emptyList()) }
    var dueAt by remember { mutableStateOf<Long?>(null) }
    var priority by remember { mutableStateOf(TaskPriority.NONE) }
    var pinned by remember { mutableStateOf(false) }
    var subtasks by remember { mutableStateOf<List<ChecklistItem>>(emptyList()) }
    var newSubtaskText by remember { mutableStateOf("") }
    // Whether the subtasks section is switched on (task 11). Like a note's "Checklist" toggle, it
    // lives between the title and details and only reveals the subtask editor when on — a task that
    // doesn't need a checklist isn't cluttered with one. It defaults on when editing a task that
    // already has subtasks so they stay visible.
    var subtasksEnabled by remember { mutableStateOf(false) }
    var repeatRule by remember { mutableStateOf(RepeatRule.NONE) }
    var reminderEnabled by remember { mutableStateOf(false) }
    // Floor for the due-date picker: this task's real creation time when editing, or the instant
    // the composer was opened when creating (which becomes the task's createdAt).
    var composerMinMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var searchText by remember { mutableStateOf("") }
    // Optional date-range filter (start-of-day, end-of-day millis). Null means no filter; the end
    // can't precede the start (the picker enforces it).
    var dateRange by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var taskToDelete by remember { mutableStateOf<Task?>(null) }
    // Pin/unpin now confirms first (dialog below) so a stray tap can't silently reorder the list.
    var taskToTogglePin by remember { mutableStateOf<Task?>(null) }

    // Hoisted above the view-mode `when` so the home list keeps its scroll position across opening a
    // task and coming back (see feature: preserve scroll position).
    val listState = rememberLazyListState()

    // Long-press multi-select for batch delete (see feature: batch operations).
    var selectionMode by remember { mutableStateOf(false) }
    var selectedTaskIds by remember { mutableStateOf(setOf<Long>()) }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }
    fun exitSelection() { selectionMode = false; selectedTaskIds = emptySet() }
    // Confirmation gate for "mark this task as complete". The checkbox is NEVER flipped
    // optimistically; only after the user taps Confirm here is isDone = true written to the
    // database. Cancelling (tapping outside, tapping Cancel, or pressing back) leaves the task
    // exactly as it was.
    var taskToComplete by remember { mutableStateOf<Task?>(null) }
    // The mirror of the above for the *reverse* action (task 7): sending a finished task back to the
    // active list moves it between two pages and re-arms its reminder, so it asks first too. The
    // rule is symmetric on purpose — a confirmation that only guards one direction teaches people
    // that the other direction is safe to tap by accident.
    var taskToRestore by remember { mutableStateOf<Task?>(null) }

    var showUnsavedDialog by remember { mutableStateOf(false) }

    // The notification permission is requested the first time the user actually switches a reminder
    // on — never at launch. If they decline, the preference is still stored: the reminder simply
    // won't alert until the permission is granted, which is recoverable, whereas silently discarding
    // their setting because of one dialog answer is not.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            LucentToast.show(
                context,
                com.lucent.app.i18n.S.notifPermissionRationale,
                longDuration = true
            )
        }
    }

    fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Notifications.canPost(context)) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun resetComposer() {
        editingTask = null
        newTitle = TextFieldValue("")
        newNotes = ""
        pendingAttachments = emptyList()
        dueAt = null
        priority = TaskPriority.NONE
        pinned = false
        subtasks = emptyList()
        newSubtaskText = ""
        subtasksEnabled = false
        repeatRule = RepeatRule.NONE
        reminderEnabled = false
        composerMinMillis = System.currentTimeMillis()
    }

    fun startCreate() {
        resetComposer()
        composing = true
    }

    // A home-screen widget "new task" tap routes here (task 9): open a fresh composer, exactly once.
    LaunchedEffect(AppNavigation.composeTaskRequested) {
        if (AppNavigation.consumeComposeTask()) {
            showSearch = false
            showingHistory = false
            showTrash = false
            viewingId = null
            startCreate()
        }
    }

    /**
     * Close the detail page, returning to the tab the task was opened *from* when it came from
     * another one (task 4). An ordinary in-tab open has no origin, so this is just "go back to the
     * list" — the behaviour it always had.
     */
    fun closeDetail() {
        viewingId = null
        val back = returnToOnClose
        returnToOnClose = null
        if (back != null) AppNavigation.requestScreen(back)
    }

    fun openDetail(task: Task) {
        viewingId = task.id
        // Opening a task from this tab's own list is not a cross-tab trip, so any pending return
        // is stale and is dropped rather than firing later from an unrelated close.
        returnToOnClose = null
        // Feed the "Recent" section: opening a task counts toward how active it is.
        AppScope.io.launch {
            com.lucent.app.data.UsageTracker.recordOpen(context, com.lucent.app.data.UsageTracker.Kind.TASK, task.id)
        }
    }

    fun startEdit(task: Task) {
        editingTask = task
        newTitle = TextFieldValue(task.title, selection = TextRange(task.title.length))
        newNotes = task.notes
        pendingAttachments = Attachments.parse(task.attachments)
        dueAt = task.dueAt
        priority = TaskPriority.fromValue(task.priority)
        pinned = task.pinned
        subtasks = Checklist.parse(task.subtasks)
        newSubtaskText = ""
        // Show the subtasks section pre-opened if this task already has any, so editing doesn't hide
        // them behind an off switch.
        subtasksEnabled = subtasks.isNotEmpty()
        repeatRule = RepeatRule.fromKey(task.repeatRule)
        reminderEnabled = task.reminderEnabled
        composerMinMillis = task.createdAt
        composing = true
    }

    fun saveTask() {
        // A subtask typed into the add field but never committed with "+" still counts (settings
        // tasks B1/B4): it is folded in here, so typing the last item and hitting Save directly
        // never silently drops it.
        val pendingSubtask = newSubtaskText.trim()
        val composedSubtasks =
            if (pendingSubtask.isNotEmpty()) subtasks + Checklist.newItem(pendingSubtask) else subtasks
        // With the subtasks switch off, the task has no subtasks even if the editor still holds some
        // from before it was toggled off — so an off switch means "no checklist" both for the
        // "nothing to save" check and for what actually gets written.
        val effectiveSubtasks = if (subtasksEnabled) composedSubtasks else emptyList()
        if (newTitle.text.isBlank() && newNotes.isBlank() && pendingAttachments.isEmpty() && effectiveSubtasks.isEmpty()) {
            composing = false
            return
        }
        // Snapshot every composer field before the background write — same reasoning as
        // NotesScreen.saveNote: resetComposer() runs the instant this function returns, so reading
        // these vars from inside the coroutine would race it.
        val title = newTitle.text
        val notesText = newNotes
        val attachmentsJson = Attachments.serialize(pendingAttachments)
        val due = dueAt
        val prioritySnapshot = priority.value
        val pinnedSnapshot = pinned
        val subtasksJson = Checklist.serialize(effectiveSubtasks)
        val repeatSnapshot = repeatRule.key
        val reminderSnapshot = reminderEnabled
        val original = editingTask
        val createdAt = composerMinMillis
        val appContext = context.applicationContext

        // App-lifetime scope so saving-then-navigating (e.g. the unsaved-changes dialog) can't
        // cancel the write before it commits.
        AppScope.io.launch {
            val saved: Task = if (original != null) {
                val updated = original.copy(
                    title = title,
                    notes = notesText,
                    attachments = attachmentsJson,
                    dueAt = due,
                    priority = prioritySnapshot,
                    pinned = pinnedSnapshot,
                    subtasks = subtasksJson,
                    repeatRule = repeatSnapshot,
                    reminderEnabled = reminderSnapshot
                )
                db.taskDao().update(updated)
                updated
            } else {
                // createdAt is pinned to the same instant the due-date picker was bounded by, so
                // the stored creation time can never end up after a due date it constrained.
                val toInsert = Task(
                    title = title,
                    createdAt = createdAt,
                    notes = notesText,
                    attachments = attachmentsJson,
                    dueAt = due,
                    priority = prioritySnapshot,
                    pinned = pinnedSnapshot,
                    subtasks = subtasksJson,
                    repeatRule = repeatSnapshot,
                    reminderEnabled = reminderSnapshot
                )
                val newId = db.taskDao().insert(toInsert)
                toInsert.copy(id = newId)
            }
            // One call, whatever changed: sync() decides for itself whether this task should now
            // have an alarm, so there's no "did I need to cancel that" branch to get wrong.
            ReminderScheduler.sync(appContext, saved)
            withContext(Dispatchers.Main) {
                LucentToast.show(appContext, com.lucent.app.i18n.S.taskSaved)
            }
        }
        composing = false
        resetComposer()
    }

    // True while the composer holds edits that haven't been saved yet.
    val taskDirty = composing && run {
        val original = editingTask
        if (original != null) {
            newTitle.text != original.title || newNotes != original.notes || dueAt != original.dueAt ||
                Attachments.serialize(pendingAttachments) != original.attachments ||
                priority.value != original.priority || pinned != original.pinned ||
                Checklist.serialize(subtasks) != original.subtasks ||
                // Text sitting in the add field is work too (B4): losing it on exit because no "+"
                // was pressed is exactly the bug this flag exists to prevent.
                newSubtaskText.isNotBlank() ||
                repeatRule.key != original.repeatRule || reminderEnabled != original.reminderEnabled
        } else {
            newTitle.text.isNotBlank() || newNotes.isNotBlank() || pendingAttachments.isNotEmpty() ||
                dueAt != null || priority != TaskPriority.NONE || pinned || subtasks.isNotEmpty() ||
                newSubtaskText.isNotBlank() ||
                repeatRule != RepeatRule.NONE || reminderEnabled
        }
    }

    fun discardComposer() {
        composing = false
        resetComposer()
    }

    fun leaveComposer() {
        if (taskDirty) showUnsavedDialog = true else discardComposer()
    }

    // Back priority, most specific first: composer -> ask, then close; detail -> back to whichever
    // list we came from; history/trash -> back to the active-tasks home.
    BackHandler(enabled = composing) { leaveComposer() }
    BackHandler(enabled = !composing && viewingId != null) { closeDetail() }
    BackHandler(enabled = !composing && viewingId == null && showingHistory) { showingHistory = false }
    BackHandler(enabled = !composing && viewingId == null && !showingHistory && showTrash) { showTrash = false }
    BackHandler(enabled = !composing && viewingId == null && !showingHistory && !showTrash && showSearch) { showSearch = false }
    // On the home list, back first exits multi-select rather than leaving the screen.
    BackHandler(enabled = selectionMode && !composing && viewingId == null && !showingHistory && !showTrash && !showSearch) { exitSelection() }

    // Leaving this tab folds it back to the task list (task 3). Sub-pages — a task's detail page,
    // the composer, history, trash, search — are places you *went*, not places you live, and coming
    // back to the Tasks tab three screens deep in something you opened ten minutes ago is
    // disorienting: the tab looks like it has forgotten what it is. Note this can't lose work: an
    // unsaved composer routes through UnsavedChangesGuard *before* the tab switch is allowed to
    // happen, so by the time this runs the edit has already been saved, discarded, or cancelled
    // (and if cancelled, the switch never occurred and this never runs).
    LaunchedEffect(active) {
        if (!active) {
            if (composing) discardComposer()
            viewingId = null
            returnToOnClose = null
            showingHistory = false
            showTrash = false
            showSearch = false
            showOverflowMenu = false
            // Collapse the header's action cluster too (task): leaving the tab and coming back should
            // find it tucked away again, not still expanded.
            actionsExpanded = false
            exitSelection()
        }
    }

    // Collapse the action cluster when the app leaves the foreground (screen off, home, another
    // app), so reopening finds it tucked away rather than as it was left (task). ON_STOP fires when
    // the activity is no longer visible, which covers all of those without touching the tab state.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) actionsExpanded = false
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SideEffect {
        if (taskDirty) {
            UnsavedChangesGuard.register("tasks", ::saveTask, ::discardComposer)
        } else {
            UnsavedChangesGuard.clear("tasks")
        }
    }
    DisposableEffect(Unit) { onDispose { UnsavedChangesGuard.clear("tasks") } }

    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedDialog = false },
            title = { Text(com.lucent.app.i18n.S.unsavedChangesTitle) },
            text = { Text(if (editingTask != null) com.lucent.app.i18n.S.unsavedTaskExistingBody else com.lucent.app.i18n.S.unsavedTaskNewBody) },
            confirmButton = {
                TextButton(onClick = {
                    showUnsavedDialog = false
                    saveTask()
                }) { Text(com.lucent.app.i18n.S.actionSave) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        showUnsavedDialog = false
                        discardComposer()
                    }) { Text(com.lucent.app.i18n.S.actionDiscard) }
                    TextButton(onClick = { showUnsavedDialog = false }) { Text(com.lucent.app.i18n.S.actionCancel) }
                }
            }
        )
    }

    // The active list already excludes completed and trashed tasks, so the structured query plus the
    // creation-date filter give "search my open tasks" with real operators — priority:high,
    // due:today, is:overdue, has:reminder — rather than only a title substring.
    val query = remember(searchText) { SearchQuery.parse(searchText) }
    // `is:done` is the one filter that changes which tasks are in scope, since completed tasks live
    // on the history page. Honouring it here makes the history searchable without navigating to it.
    val searchPool = remember(activeTasks, allTasks, query) {
        if ("done" in query.flags) allTasks.filter { it.trashedAt == null } else activeTasks
    }
    val filteredActive = remember(searchPool, query, dateRange) {
        searchPool.filterBySearch(query).filter { task ->
            dateRange?.let { (start, end) -> withinLocalDayRange(task.createdAt, start, end) } ?: true
        }
    }
    val sortedActive = remember(filteredActive, sortOption, query) {
        filteredActive.sortedForDisplay(sortOption, query)
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            scope.launch {
                // Import the whole selection on IO. The only limit now is per file (600 MB) — no cap
                // on combined volume — so each file is checked on its own and rejected individually.
                val (accepted, lastMessage) = withContext(Dispatchers.IO) {
                    var runningPending = pendingAttachments.toList()
                    var message = ""
                    for (uri in uris) {
                        val hint = AttachmentStore.sizeHint(context, uri)
                        if (hint > 0) {
                            val preCheck = AttachmentLimits.checkSingle(hint)
                            if (!preCheck.allowed) { message = preCheck.message; continue }
                        }
                        val newAtt = uriToAttachment(context, uri) ?: run { message = com.lucent.app.i18n.S.couldNotReadOneFile; null } ?: continue
                        val incoming = AttachmentLimits.sizeOf(context, newAtt)
                        val postCheck = AttachmentLimits.checkSingle(incoming)
                        if (postCheck.allowed) {
                            runningPending = Attachments.upsert(context, runningPending, newAtt)
                        } else {
                            if (AttachmentStore.looksLikeId(newAtt.data)) AttachmentStore.delete(context, newAtt.data)
                            message = postCheck.message
                        }
                    }
                    runningPending to message
                }
                pendingAttachments = accepted
                if (lastMessage.isNotBlank()) LucentToast.show(context, lastMessage, longDuration = true)
            }
        }
    }

    taskToDelete?.let { task ->
        AlertDialog(
            onDismissRequest = { taskToDelete = null },
            title = { Text(com.lucent.app.i18n.S.moveToTrashTitle) },
            text = {
                Text(com.lucent.app.i18n.S.moveTaskTrashBody(task.title.ifBlank { com.lucent.app.i18n.S.untitledTask }, TrashCleanup.RETENTION_DAYS))
            },
            confirmButton = {
                TextButton(onClick = {
                    val toTrash = task
                    taskToDelete = null
                    if (viewingId == toTrash.id) viewingId = null
                    AppScope.io.launch { TaskActions.trash(context, db, toTrash) }
                }) { Text(com.lucent.app.i18n.S.moveToTrash) }
            },
            dismissButton = { TextButton(onClick = { taskToDelete = null }) { Text(com.lucent.app.i18n.S.actionCancel) } }
        )
    }

    // The single gate through which "check this checkbox" becomes an actual isDone = true write. If
    // it's dismissed for any reason, nothing runs and the task stays exactly as it was.
    taskToComplete?.let { task ->
        val repeats = RepeatRule.fromKey(task.repeatRule) != RepeatRule.NONE
        AlertDialog(
            onDismissRequest = { taskToComplete = null },
            title = { Text(com.lucent.app.i18n.S.completeTaskTitle) },
            text = {
                Text(com.lucent.app.i18n.S.completeTaskBody(task.title.ifBlank { com.lucent.app.i18n.S.untitledTask }))
            },
            confirmButton = {
                TextButton(onClick = {
                    val toComplete = task
                    taskToComplete = null
                    // The shared action: marks done, cancels the reminder, and — when the task
                    // repeats — spawns the next occurrence and arms its reminder. Exactly what the
                    // assistant's complete_task does, because it is literally the same function.
                    AppScope.io.launch { TaskActions.complete(context, db, toComplete) }
                }) { Text(com.lucent.app.i18n.S.actionConfirm) }
            },
            dismissButton = { TextButton(onClick = { taskToComplete = null }) { Text(com.lucent.app.i18n.S.actionCancel) } }
        )
    }

    // Confirm before sending a completed task back to the active list (task 7).
    taskToRestore?.let { task ->
        val hasFutureDue = task.dueAt != null
        AlertDialog(
            onDismissRequest = { taskToRestore = null },
            title = { Text(com.lucent.app.i18n.S.markNotDoneTitle) },
            text = {
                Text(com.lucent.app.i18n.S.notDoneTaskBody(task.title.ifBlank { com.lucent.app.i18n.S.untitledTask }))
            },
            confirmButton = {
                TextButton(onClick = {
                    val toRestore = task
                    taskToRestore = null
                    AppScope.io.launch { TaskActions.restore(context, db, toRestore) }
                }) { Text(com.lucent.app.i18n.S.markNotDone) }
            },
            dismissButton = { TextButton(onClick = { taskToRestore = null }) { Text(com.lucent.app.i18n.S.actionCancel) } }
        )
    }

    // Confirm-before-pin: pinning (or unpinning) reorders the list, so it asks first.
    taskToTogglePin?.let { task ->
        val willPin = !task.pinned
        AlertDialog(
            onDismissRequest = { taskToTogglePin = null },
            title = { Text(if (willPin) com.lucent.app.i18n.S.pinTaskTitle else com.lucent.app.i18n.S.unpinTaskTitle) },
            text = {
                Text(
                    if (willPin) com.lucent.app.i18n.S.pinTaskBody(task.title.ifBlank { com.lucent.app.i18n.S.untitledTask })
                    else com.lucent.app.i18n.S.unpinTaskBody(task.title.ifBlank { com.lucent.app.i18n.S.untitledTask })
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = task
                    taskToTogglePin = null
                    AppScope.io.launch { db.taskDao().update(target.copy(pinned = !target.pinned)) }
                }) { Text(if (willPin) com.lucent.app.i18n.S.actionPin else com.lucent.app.i18n.S.actionUnpin) }
            },
            dismissButton = { TextButton(onClick = { taskToTogglePin = null }) { Text(com.lucent.app.i18n.S.actionCancel) } }
        )
    }

    // Batch move-to-trash for the multi-selected tasks.
    if (showBatchDeleteConfirm) {
        val count = selectedTaskIds.size
        AlertDialog(
            onDismissRequest = { showBatchDeleteConfirm = false },
            title = { Text(com.lucent.app.i18n.S.moveToTrashTitle) },
            text = {
                Text(
                    if (count == 1) com.lucent.app.i18n.S.moveOneTaskTrashBody(TrashCleanup.RETENTION_DAYS)
                    else com.lucent.app.i18n.S.moveNTasksTrashBody(count, TrashCleanup.RETENTION_DAYS)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val ids = selectedTaskIds
                    showBatchDeleteConfirm = false
                    exitSelection()
                    AppScope.io.launch {
                        ids.forEach { id ->
                            db.taskDao().getByIdOnce(id)?.let { TaskActions.trash(context, db, it) }
                        }
                    }
                }) { Text(com.lucent.app.i18n.S.moveToTrash) }
            },
            dismissButton = { TextButton(onClick = { showBatchDeleteConfirm = false }) { Text(com.lucent.app.i18n.S.actionCancel) } }
        )
    }

    // Resolved against all tasks (not just active) so opening a completed task from the history page
    // works too — but trashed tasks are excluded, so a task trashed by any path makes the detail
    // page fall away rather than keep showing it.
    val viewingTask = remember(allTasks, viewingId) {
        allTasks.firstOrNull { it.id == viewingId && it.trashedAt == null }
    }

    when {
        composing -> {
            // ---- Create / edit page ----
            // Opening the editor of a task that already has subtasks jumps straight to the bottom
            // of its subtask list — the add field under the last item — because that is where
            // editing a checklist almost always continues (settings task B7). Decided once, when
            // this page enters composition (startEdit has already populated the state by then), so
            // later add/remove edits never re-trigger the jump.
            val jumpToLastSubtask = remember { editingTask != null && subtasks.isNotEmpty() }
            val subtaskEndRequester = remember { BringIntoViewRequester() }
            if (jumpToLastSubtask) {
                LaunchedEffect(Unit) {
                    // Two frames so the scrollable column has laid out before it is asked to move.
                    withFrameNanos { }
                    withFrameNanos { }
                    subtaskEndRequester.bringIntoView()
                }
            }
            Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()).padding(bottom = LocalBottomBarInset.current)) {
                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { leaveComposer() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = com.lucent.app.i18n.S.actionBack, tint = onGradient)
                    }
                    Text(if (editingTask != null) com.lucent.app.i18n.S.editTask else com.lucent.app.i18n.S.newTask, color = onGradient, fontSize = 20.sp)
                }

                Column(modifier = Modifier.fillMaxWidth().frostedGlass().padding(16.dp)) {
                    OutlinedTextField(
                        value = newTitle,
                        onValueChange = { newTitle = it },
                        placeholder = { Text(com.lucent.app.i18n.S.fieldTitle) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Subtasks toggle (task 11): like a note's "Checklist" switch, it sits between the
                    // title and the details and only reveals the subtask editor when switched on, so a
                    // task that doesn't need a checklist stays uncluttered. The list is kept in composer
                    // state regardless of the switch, so toggling off and back on never discards it.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.AutoMirrored.Filled.FormatListBulleted,
                            contentDescription = null,
                            tint = if (subtasksEnabled) onGradient else onGradientMuted
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(com.lucent.app.i18n.S.labelSubtasks, color = onGradient, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Switch(checked = subtasksEnabled, onCheckedChange = { subtasksEnabled = it })
                    }
                    if (subtasksEnabled) {
                        Spacer(modifier = Modifier.height(6.dp))
                        ChecklistEditorSection(
                            items = subtasks,
                            newItemText = newSubtaskText,
                            onNewItemTextChange = { newSubtaskText = it },
                            onAdd = {
                                subtasks = subtasks + Checklist.newItem(newSubtaskText)
                                newSubtaskText = ""
                            },
                            onToggle = { item -> subtasks = subtasks.map { if (it.id == item.id) it.copy(done = !it.done) else it } },
                            onRemove = { item -> subtasks = subtasks.filterNot { it.id == item.id } },
                            // Items are editable in place after being added (settings task B2).
                            onEditText = { item, text -> subtasks = subtasks.map { if (it.id == item.id) it.copy(text = text) else it } },
                            addLabel = com.lucent.app.i18n.S.addSubtask,
                            addRowModifier = Modifier.bringIntoViewRequester(subtaskEndRequester)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    // Details field with the same expand toggle the Notes screen uses, so the two
                    // editors are identical in size and behaviour. "Details" rather than "Notes": on a
                    // task, a field literally labelled "Notes" read as redundant ("this note says note").
                    ExpandableGlassTextField(
                        value = newNotes,
                        onValueChange = { newNotes = it },
                        placeholder = com.lucent.app.i18n.S.detailsPlaceholder,
                        expandedTitle = if (editingTask != null) com.lucent.app.i18n.S.editTask else com.lucent.app.i18n.S.newTask,
                        collapsedMinHeight = 120.dp,
                        collapsedMaxHeight = 320.dp
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Priority sits directly under the details box: how important a task is gets
                    // decided while it is still being described, so the chips moved up here from
                    // below the scheduling block (where they used to trail the due date).
                    PriorityPickerRow(selected = priority, onSelect = { priority = it })
                    Spacer(modifier = Modifier.height(12.dp))

                    // Pin-to-top, exactly like a note's pin switch (task 11) — so the two composers
                    // line up rather than burying the task's pin far down among the scheduling
                    // controls.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.PushPin,
                            contentDescription = null,
                            tint = if (pinned) onGradient else onGradientMuted
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(com.lucent.app.i18n.S.pinToTop, color = onGradient, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Switch(checked = pinned, onCheckedChange = { pinned = it })
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    DueDateRow(
                        dueAt = dueAt,
                        minMillis = composerMinMillis,
                        onChange = { newDue ->
                            dueAt = newDue
                            // Repeat and the reminder both only mean anything while there's a due date
                            // to act on, so clearing the due date clears them too. A task carrying a
                            // repeat rule or a reminder it can never fire isn't a setting, it's a lie.
                            if (newDue == null) {
                                repeatRule = RepeatRule.NONE
                                reminderEnabled = false
                            }
                        }
                    )

                    // The reminder toggle only appears once a due date/time is set (task 8): there's
                    // nothing to remind about until then, so an always-visible "remind me at the due
                    // time" row with no due time was dead UI. It's grouped here with Repeat, which is
                    // gated the same way.
                    if (dueAt != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        ReminderToggleRow(
                            enabled = reminderEnabled,
                            hasDueDate = true,
                            onToggle = { checked ->
                                reminderEnabled = checked
                                if (checked) ensureNotificationPermission()
                            }
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        RepeatRuleRow(selected = repeatRule, onSelect = { repeatRule = it })
                    }

                    // Attachments close the form as a due-date-style row: same icon + label anatomy
                    // as the rows above, tap anywhere on it to pick a file, chips listed beneath.
                    // See [AttachmentSection].
                    Spacer(modifier = Modifier.height(12.dp))
                    AttachmentSection(
                        attachments = pendingAttachments,
                        onPick = { filePicker.launch("*/*") },
                        onRemove = { att ->
                            pendingAttachments = Attachments.removeByName(context, pendingAttachments, att.name)
                        }
                    )

                    Spacer(modifier = Modifier.height(20.dp))
                    // Enlarged so this primary action clearly outweighs the slim attachment row
                    // above it.
                    Button(
                        onClick = { saveTask() },
                        contentPadding = PaddingValues(horizontal = 30.dp, vertical = 15.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(22.dp))
                        Text(
                            if (editingTask != null) " " + com.lucent.app.i18n.S.saveChanges else " " + com.lucent.app.i18n.S.addTaskBtn,
                            fontSize = 17.sp
                        )
                    }
                }
            }
        }

        viewingTask != null -> {
            // ---- Read-only detail page ----
            val task = viewingTask
            val attachments = remember(task.attachments) { Attachments.parse(task.attachments) }
            val subtaskItems = remember(task.subtasks) { Checklist.parse(task.subtasks) }
            val taskPriority = remember(task.priority) { TaskPriority.fromValue(task.priority) }
            val taskRepeat = remember(task.repeatRule) { RepeatRule.fromKey(task.repeatRule) }
            val overdue = isOverdue(task.dueAt, task.isDone)

            // Swipe horizontally to move between tasks in the current (sorted, filtered) order:
            // swipe left for the next, right for the previous. Does nothing at the ends or for a task
            // not in the visible active list (e.g. one opened from history).
            //
            // The gesture lives on this full-page Box, not the inner scrolling Column, so it works
            // anywhere on the page rather than only over the body (task 7). The top band (back / title
            // / action header) and the bottom band are excluded: a drag is only armed when it *starts*
            // in the central band. The inner Column keeps owning vertical scrolling.
            // ---- Animated page-to-page swipe (task 7) ----
            //
            // The gesture already worked; what it lacked was any sense of movement. A drag past the
            // threshold simply swapped the content, so the next task appeared fully formed with no
            // indication that anything had travelled — indistinguishable, from the user's side, from
            // the page having been redrawn for some unrelated reason. Direction is the whole meaning
            // of a horizontal swipe, and the old version showed none of it.
            //
            // So the page now follows the finger and completes the journey on release:
            //
            //  - **While dragging**, the content tracks the finger 1:1 and fades slightly as it goes,
            //    so the gesture is reversible — let go early and it springs back, which is the only
            //    way a user can discover the threshold without being punished for finding it.
            //  - **At the ends of the list** the drag is damped to a third (RESIST) instead of being
            //    ignored. A dead gesture reads as a broken one; a stiff one reads as a wall, which is
            //    what it actually is.
            //  - **On release past the threshold** the outgoing page finishes its exit in the
            //    direction of travel, then the incoming page enters from the opposite edge. Exit is
            //    faster than entry (accelerating out, decelerating in) — the standard asymmetry that
            //    makes the pair read as one continuous movement rather than two separate slides.
            //
            // [swipeOffset] is deliberately remembered WITHOUT a key on the task id: it has to
            // survive the content swap in the middle of the animation, because the exit and the entry
            // are two halves of one gesture, not two animations.
            val swipeList = sortedActive
            val swipeOffset = remember { Animatable(0f) }
            var pageWidth by remember { mutableFloatStateOf(0f) }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { pageWidth = it.width.toFloat() }
                    .pointerInput(task.id, swipeList) {
                        var armed = false
                        var atEnd = false
                        val threshold = 72.dp.toPx()
                        val topExclusion = 88.dp.toPx()
                        val bottomExclusion = 88.dp.toPx()
                        detectHorizontalDragGestures(
                            onDragStart = { start ->
                                armed = start.y >= topExclusion && start.y <= size.height - bottomExclusion
                                atEnd = false
                            },
                            onDragEnd = {
                                if (armed) {
                                    val idx = swipeList.indexOfFirst { it.id == task.id }
                                    val travelled = swipeOffset.value
                                    val goNext = travelled <= -threshold && idx >= 0 && idx < swipeList.lastIndex
                                    val goPrev = travelled >= threshold && idx > 0
                                    val width = if (pageWidth > 0f) pageWidth else threshold * 6f
                                    scope.launch {
                                        when {
                                            goNext -> {
                                                swipeOffset.animateTo(-width, tween(SWIPE_EXIT_MS, easing = FastOutLinearInEasing))
                                                openDetail(swipeList[idx + 1])
                                                swipeOffset.snapTo(width)
                                                swipeOffset.animateTo(0f, tween(SWIPE_ENTER_MS, easing = LinearOutSlowInEasing))
                                            }
                                            goPrev -> {
                                                swipeOffset.animateTo(width, tween(SWIPE_EXIT_MS, easing = FastOutLinearInEasing))
                                                openDetail(swipeList[idx - 1])
                                                swipeOffset.snapTo(-width)
                                                swipeOffset.animateTo(0f, tween(SWIPE_ENTER_MS, easing = LinearOutSlowInEasing))
                                            }
                                            // Not far enough, or nothing to move to: spring home.
                                            else -> swipeOffset.animateTo(
                                                0f,
                                                spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)
                                            )
                                        }
                                    }
                                }
                                armed = false
                            },
                            onDragCancel = {
                                armed = false
                                scope.launch { swipeOffset.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                if (armed) {
                                    val idx = swipeList.indexOfFirst { it.id == task.id }
                                    // Damp the drag when there is nothing in that direction to reach,
                                    // so the end of the list feels like a wall rather than a fault.
                                    val blocked = idx < 0 ||
                                        (dragAmount < 0 && idx >= swipeList.lastIndex) ||
                                        (dragAmount > 0 && idx <= 0)
                                    atEnd = blocked
                                    change.consume()
                                    scope.launch {
                                        swipeOffset.snapTo(swipeOffset.value + if (blocked) dragAmount * SWIPE_RESIST else dragAmount)
                                    }
                                }
                            }
                        )
                    }
            ) {
              Column(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = swipeOffset.value
                        // A gentle fade tied to how far the page has travelled. It never reaches
                        // fully transparent during a drag — a page you can still see is a page you
                        // can still change your mind about.
                        alpha = 1f - (kotlin.math.abs(swipeOffset.value) / (pageWidth.takeIf { it > 0f } ?: 1f))
                            .coerceIn(0f, 1f) * 0.35f
                    }
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = LocalBottomBarInset.current)
            ) {
                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { closeDetail() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = com.lucent.app.i18n.S.actionBack, tint = onGradient)
                    }
                    Text(com.lucent.app.i18n.S.screenTask, color = onGradient, fontSize = 20.sp)
                    Spacer(modifier = Modifier.width(8.dp))

                    // The action strip (task 6). A task's detail page used to offer three actions
                    // where a note's offered five, so the two pages behaved differently for no reason
                    // anyone could see: the two things you most want to do to a task — edit it, or
                    // tick it off — were the two that weren't here. Both are now in the strip, which
                    // scrolls horizontally exactly like the note page's so nothing is clipped on a
                    // narrow screen.
                    Row(
                        modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Pinning only affects the active list, so it's hidden for a completed task —
                        // which lives in history, where pinning would mean nothing.
                        if (!task.isDone) {
                            PinIconButton(
                                pinned = task.pinned,
                                onToggle = { taskToTogglePin = task }
                            )
                        }
                        // The completion mark: tick a pending task off, or send a finished one back.
                        // It replaces the checkbox that used to sit inside the card below (see
                        // there), and like the checkbox it never writes anything itself — both
                        // directions go through their confirmation dialog first.
                        IconButton(onClick = {
                            if (task.isDone) taskToRestore = task else taskToComplete = task
                        }) {
                            Icon(
                                if (task.isDone) Icons.AutoMirrored.Filled.Undo else Icons.Default.CheckCircle,
                                contentDescription = if (task.isDone) com.lucent.app.i18n.S.markNotDone else com.lucent.app.i18n.S.markDone,
                                tint = onGradient
                            )
                        }
                        // Edit — the same action as the button at the bottom of the page, brought up
                        // here so it's reachable without scrolling past a long set of details.
                        IconButton(onClick = { startEdit(task) }) {
                            Icon(Icons.Default.Edit, contentDescription = com.lucent.app.i18n.S.actionEdit, tint = onGradient)
                        }
                        IconButton(onClick = {
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, task.title.ifBlank { "Task" })
                                putExtra(Intent.EXTRA_TEXT, shareTextForTask(task))
                            }
                            context.startActivity(Intent.createChooser(sendIntent, com.lucent.app.i18n.S.shareTaskChooser))
                        }) {
                            Icon(Icons.Default.Share, contentDescription = com.lucent.app.i18n.S.actionShare, tint = onGradient)
                        }
                        IconButton(onClick = { taskToDelete = task }) {
                            Icon(Icons.Default.Delete, contentDescription = com.lucent.app.i18n.S.actionDelete, tint = onGradient)
                        }
                    }
                }

                // Long-press anywhere on this card to copy the task's content (task 1). Same
                // reasoning as the note detail page: the whole card is the target so a press that
                // lands in the padding still counts, and the SelectionContainer inside still wins
                // long-press over the title/date block for native text selection.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .longPressCopy(context, copyTextForTask(task))
                        .frostedGlass()
                        .padding(16.dp)
                ) {
                    // No checkbox here any more (task 6). A tick-box on the detail page was a second
                    // control for something the header now owns, and — because the page is *read*
                    // rather than scanned — it was the one most likely to be hit by accident while
                    // scrolling. The box belongs on the home list, where ticking things off is the
                    // whole activity; here, completion is the header's deliberate, confirmed action.
                    //
                    // Title/dates/notes sit in a SelectionContainer for the native selection toolbar.
                    // The subtask checklist and the attachments stay outside it so their taps still
                    // reach them.
                    Row(verticalAlignment = Alignment.Top) {
                        SelectionContainer(modifier = Modifier.weight(1f)) {
                            Column {
                                Text(
                                    task.title.ifBlank { com.lucent.app.i18n.S.untitledTask },
                                    color = onGradient,
                                    fontSize = 22.sp,
                                    textDecoration = if (task.isDone) TextDecoration.LineThrough else null
                                )
                                if (taskPriority != TaskPriority.NONE) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    PriorityBadge(taskPriority)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(com.lucent.app.i18n.S.createdOn(formatTimestamp(task.createdAt)), color = onGradientMuted, fontSize = 12.sp)
                                task.dueAt?.let { due ->
                                    Text(
                                        if (overdue) friendlyDue(due) else com.lucent.app.i18n.S.dueWhen(friendlyDue(due)),
                                        color = if (overdue) OverdueColor else onGradientMuted,
                                        fontSize = 12.sp
                                    )
                                }
                                if (taskRepeat != RepeatRule.NONE) {
                                    Text(com.lucent.app.i18n.S.repeatsEvery(taskRepeat.uiLabel), color = onGradientMuted, fontSize = 12.sp)
                                }
                                if (task.reminderEnabled && task.dueAt != null && !task.isDone) {
                                    Text(com.lucent.app.i18n.S.reminderOn, color = onGradientMuted, fontSize = 12.sp)
                                }
                                task.completedAt?.let { done ->
                                    Text(com.lucent.app.i18n.S.completedOn(formatTimestamp(done)), color = onGradientMuted, fontSize = 12.sp)
                                }
                                if (task.notes.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(task.notes, color = onGradient)
                                }
                            }
                        }
                    }

                    if (subtaskItems.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        ChecklistView(
                            items = subtaskItems,
                            header = com.lucent.app.i18n.S.labelSubtasks,
                            // A completed task is locked along with its subtasks, so the checkboxes
                            // become genuinely non-interactive rather than merely greyed out.
                            onToggle = if (task.isDone) {
                                null
                            } else {
                                { item, checked ->
                                    AppScope.io.launch {
                                        db.taskDao().update(
                                            task.copy(subtasks = Checklist.setDone(task.subtasks, item.id, checked))
                                        )
                                    }
                                }
                            }
                        )
                    }

                    CardAttachments(attachments, onGradient, onGradientMuted)
                }

                Spacer(modifier = Modifier.height(16.dp))
                // Full-width bottom action (task 17): the Edit button used to size to its content
                // (~half the screen). Stretching it to the full width makes it a clear primary action
                // and matches the note editor's bottom row. The completed-task "undo" control uses the
                // same width so the slot looks identical in both states.
                if (task.isDone) {
                    GlassCapsuleButton(
                        text = com.lucent.app.i18n.S.markNotDone,
                        icon = Icons.AutoMirrored.Filled.Undo,
                        // Asks first (task 7) rather than firing on the tap.
                        onClick = { taskToRestore = task },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    GlassCapsuleButton(
                        text = com.lucent.app.i18n.S.editTask,
                        icon = Icons.Default.Edit,
                        onClick = { startEdit(task) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            }
        }

        showingHistory -> {
            CompletedTasksScreen(
                onBack = { showingHistory = false },
                onOpen = { openDetail(it) },
                onDeleteRequest = { taskToDelete = it }
            )
        }

        showTrash -> {
            TrashTasksScreen(onBack = { showTrash = false })
        }

        showSearch -> {
            // The same unified search the Notes tab hosts — one screen, reachable from either side,
            // because "where did I put that" is not a question that knows which tab it belongs to.
            SearchScreen(
                // A note lives on the other tab, so this is a cross-tab jump: it records that it
                // started here (task 4) and deliberately leaves this search *open* behind it, so
                // closing the note comes back to these results rather than to a blank list.
                onOpenNote = { note -> AppNavigation.openNote(note.id, from = Screen.Tasks) },
                onOpenTask = { task ->
                    showSearch = false
                    viewingId = task.id
                    returnToOnClose = null
                },
                onBack = { showSearch = false }
            )
        }

        else -> {
            // ---- Clean home list (active tasks only) ----
            // Sections apply only while browsing (no search/date filter); a search gets a flat list.
            val browsing = searchText.isBlank() && dateRange == null
            val now = remember(sortedActive) { System.currentTimeMillis() }
            val sections = remember(sortedActive, taskUsage, browsing, now) {
                if (!browsing) null else sectionHomeItems(
                    items = sortedActive,
                    now = now,
                    maxRecent = 6,
                    id = { it.id },
                    // A task's own time for the Today bucket is its creation time.
                    timestamp = { it.createdAt },
                    activityScore = { com.lucent.app.data.UsageTracker.score(taskUsage[it.id] ?: 0.0, it.createdAt, now) }
                )
            }
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                if (selectionMode) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { exitSelection() }) {
                            Icon(Icons.Default.Close, contentDescription = com.lucent.app.i18n.S.a11yCancelSelection, tint = onGradient)
                        }
                        Text(
                            "${selectedTaskIds.size} selected",
                            color = onGradient,
                            fontSize = 18.sp,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            val allIds = sortedActive.map { it.id }.toSet()
                            selectedTaskIds = if (selectedTaskIds.containsAll(allIds)) emptySet() else allIds
                        }) {
                            Text(if (selectedTaskIds.containsAll(sortedActive.map { it.id }.toSet()) && sortedActive.isNotEmpty()) com.lucent.app.i18n.S.clearAllSelection else com.lucent.app.i18n.S.selectAll)
                        }
                        IconButton(
                            onClick = { if (selectedTaskIds.isNotEmpty()) showBatchDeleteConfirm = true },
                            enabled = selectedTaskIds.isNotEmpty()
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = com.lucent.app.i18n.S.a11yDeleteSelected, tint = onGradient)
                        }
                    }
                } else {
                    // Header with a collapsible action cluster (settings task 16), synthesized with the
                    // tasks/notes header changes: the "+" stays put; the date-range filter, sort and
                    // overflow buttons tuck behind the "<" chevron and slide out to the left on tap while
                    // the search box smoothly resizes. No placeholder text in the field (tasks items
                    // 6 & 15) so nothing can be clipped; the leading icon conveys its purpose.
                    CollapsibleActionBar(
                        expanded = actionsExpanded,
                        onToggleExpanded = { actionsExpanded = !actionsExpanded },
                        search = {
                            OutlinedTextField(
                                value = searchText,
                                onValueChange = { searchText = it },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = com.lucent.app.i18n.S.a11ySearchTasks) },
                                trailingIcon = { SearchHelpButton() },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        actions = {
                            DateFilterIconButton(
                                active = dateRange != null,
                                onClick = {
                                    showDateRangePicker(context, dateRange?.first, dateRange?.second) { start, end ->
                                        dateRange = start to end
                                    }
                                }
                            )
                            SortMenuButton(
                                current = sortOption,
                                options = TaskSort.entries.toList(),
                                label = { it.label },
                                onSelect = { option -> scope.launch { settingsRepo.setTasksSort(option.key) } },
                                tint = onGradientMuted,
                                activeTint = onGradient
                            )
                            // Completed history, Trash and Select live behind one overflow menu, so the
                            // bar doesn't grow an icon each time another action appears.
                            Box {
                                IconButton(onClick = { showOverflowMenu = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = com.lucent.app.i18n.S.a11yMoreOptions, tint = onGradientMuted)
                                }
                                DropdownMenu(expanded = showOverflowMenu, onDismissRequest = { showOverflowMenu = false }) {
                                    // "Select tasks" is no longer here — selection is entered by
                                    // long-pressing a task (see onLongPress below). The overflow menu
                                    // now holds only the three navigation destinations.
                                    DropdownMenuItem(
                                        text = { Text(com.lucent.app.i18n.S.searchEverything) },
                                        leadingIcon = { Icon(Icons.Default.TravelExplore, contentDescription = null) },
                                        onClick = { showOverflowMenu = false; showSearch = true }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(com.lucent.app.i18n.S.screenCompletedTasks) },
                                        leadingIcon = { Icon(Icons.Default.History, contentDescription = null) },
                                        onClick = { showOverflowMenu = false; showingHistory = true }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(com.lucent.app.i18n.S.screenTrash) },
                                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                        onClick = { showOverflowMenu = false; showTrash = true }
                                    )
                                }
                            }
                        },
                        trailing = {
                            NewItemButton(contentDescription = com.lucent.app.i18n.S.newTask, onClick = { startCreate() })
                        }
                    )

                    dateRange?.let { (start, end) ->
                        Spacer(modifier = Modifier.height(8.dp))
                        DateFilterChip(startMillis = start, endMillis = end, onClear = { dateRange = null })
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // One scrollable region, always, filling everything below the header (task 5).
                //
                // It used to be either an EmptyState *or* a wrap-height LazyColumn, and both leaked
                // the same bug: vertical drags are only picked up inside the scrollable container's
                // bounds, so with no tasks (no container at all) or a short list (a container only as
                // tall as its cards) the rest of the page was dead to touch. That matters more than
                // it sounds, because this page's scrolling is what raises and lowers the "Tasks"
                // title in the top bar — so anyone who scrolled the header away and then cleared or
                // filtered the list was left with a hidden title and no gesture anywhere on screen
                // that could bring it back.
                //
                // fillMaxSize() makes the list own the whole area, and the empty state rides inside
                // it as a full-height item, so a drag started anywhere below the header is heard —
                // and the header can always be pulled back down, whether there are zero tasks or two
                // hundred.
                // Wrapped in a Box so the scroll-edge jump buttons can overlay the list (task E2).
                Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().hazeSource(state = hazeState),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    // Reserve the floating capsule's height at the bottom so the last card can
                    // scroll clear of the pill instead of ending up trapped behind it — the list
                    // itself still extends under the capsule (that's what its blur samples).
                    contentPadding = PaddingValues(bottom = LocalBottomBarInset.current)
                ) {
                    if (sortedActive.isEmpty()) {
                        item(key = "empty_state") {
                            Box(modifier = Modifier.fillMaxWidth().fillParentMaxHeight()) {
                                EmptyState(
                                    isFiltered = searchText.isNotBlank() || dateRange != null,
                                    emptyMessage = com.lucent.app.i18n.S.emptyTasksHint,
                                    noMatchMessage = com.lucent.app.i18n.S.noTasksMatchSearch
                                )
                            }
                        }
                    } else {
                        val renderCard: @Composable (Task) -> Unit = { task ->
                            TaskCard(
                                task = task,
                                selectionMode = selectionMode,
                                selected = task.id in selectedTaskIds,
                                onOpen = { openDetail(task) },
                                onLongPress = { selectionMode = true; selectedTaskIds = setOf(task.id) },
                                onToggleSelect = {
                                    selectedTaskIds = if (task.id in selectedTaskIds) selectedTaskIds - task.id else selectedTaskIds + task.id
                                },
                                onArmComplete = { taskToComplete = task },
                                onTogglePin = { taskToTogglePin = task },
                                onDelete = { taskToDelete = task }
                            )
                        }
                        if (sections != null) {
                            sections.nonEmpty().forEach { (section, list) ->
                                item(key = "header_${section.name}") { TaskSectionHeader(section.label) }
                                items(list, key = { it.id }) { task -> renderCard(task) }
                            }
                        } else {
                            items(sortedActive, key = { it.id }) { task -> renderCard(task) }
                        }
                    }
                }
                ScrollEdgeJumpButtons(
                    canUp = listState.canScrollBackward,
                    canDown = listState.canScrollForward,
                    tint = onGradient,
                    onUp = { scope.launch { listState.animateScrollToItem(0) } },
                    onDown = { scope.launch { listState.animateScrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)) } },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = LocalBottomBarInset.current + 14.dp)
                )
                }
            }
        }
    }
}

/**
 * One active task as a card.
 *
 * Checkbox, priority dot, title, created/due times, and a compact meta line for repeat and subtask
 * progress. Attachments and the full checklist are one tap away on the detail page — a card that
 * tries to show everything shows nothing.
 *
 * The checkbox is deliberately never flipped in state here: its handler only *arms* the confirmation
 * dialog, and the database write happens after the user confirms. That's the difference between
 * "tap to complete" and "tap to complete by accident".
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TaskCard(
    task: Task,
    selectionMode: Boolean,
    selected: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onToggleSelect: () -> Unit,
    onArmComplete: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit
) {
    val onGradient = LocalOnGradient.current
    val onGradientMuted = LocalOnGradientMuted.current
    val priority = remember(task.priority) { TaskPriority.fromValue(task.priority) }
    val progress = remember(task.subtasks) { Checklist.progress(task.subtasks) }
    val repeats = remember(task.repeatRule) { RepeatRule.fromKey(task.repeatRule) != RepeatRule.NONE }
    // Cache the "created" label keyed on the raw value (task 8): same rationale as NoteCard — avoid
    // re-formatting a Date on every recomposition during a scroll when the value hasn't changed.
    val createdLabel = remember(task.createdAt) { formatTimestamp(task.createdAt) }
    val overdue = isOverdue(task.dueAt, task.isDone)
    val selShape = RoundedCornerShape(20.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .frostedGlass()
            // Long press enters multi-select and grabs this card; in select mode a tap toggles it.
            .combinedClickable(
                onClick = { if (selectionMode) onToggleSelect() else onOpen() },
                onLongClick = { if (!selectionMode) onLongPress() }
            )
            .then(if (selected) Modifier.border(2.dp, onGradient, selShape) else Modifier)
            .padding(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (selectionMode) {
                // While selecting, a tick stands in for the checkbox so a tap can't both select and
                // arm completion.
                Icon(
                    if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = if (selected) com.lucent.app.i18n.S.a11ySelected else com.lucent.app.i18n.S.a11yNotSelected,
                    tint = if (selected) onGradient else onGradientMuted,
                    modifier = Modifier.padding(horizontal = 12.dp).size(22.dp)
                )
            } else {
                Checkbox(
                    checked = task.isDone,
                    onCheckedChange = { checked ->
                        // The home list never contains completed tasks, so unchecking is unreachable.
                        if (checked) onArmComplete()
                    }
                )
            }
            Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (task.pinned) {
                        PinnedMarker(modifier = Modifier.padding(end = 4.dp))
                    }
                    PriorityDot(priority)
                    if (priority != TaskPriority.NONE) Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        task.title.ifBlank { com.lucent.app.i18n.S.untitledTask },
                        color = onGradient,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(createdLabel, color = onGradientMuted, fontSize = 12.sp)
                task.dueAt?.let { due ->
                    Text(
                        if (overdue) friendlyDue(due) else com.lucent.app.i18n.S.dueWhen(friendlyDue(due)),
                        color = if (overdue) OverdueColor else onGradientMuted,
                        fontSize = 12.sp
                    )
                }
                if (repeats || progress != null) {
                    Row(
                        modifier = Modifier.padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (repeats) {
                            Icon(
                                Icons.Default.Repeat,
                                contentDescription = com.lucent.app.i18n.S.a11yRepeats,
                                tint = onGradientMuted,
                                modifier = Modifier.size(13.dp)
                            )
                            if (progress != null) Spacer(modifier = Modifier.width(8.dp))
                        }
                        progress?.let { (done, total) ->
                            Text(com.lucent.app.i18n.S.nSubtasks(done, total), color = onGradientMuted, fontSize = 12.sp)
                        }
                    }
                }
            }
            if (!selectionMode) {
                PinIconButton(pinned = task.pinned, onToggle = onTogglePin)
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = com.lucent.app.i18n.S.actionDelete, tint = onGradient)
                }
            }
        }
    }
}

/** A section header ("Recent" / "Today" / "Older") between task rows on the home list. */
@Composable
private fun TaskSectionHeader(label: String) {
    val onGradientMuted = LocalOnGradientMuted.current
    Text(
        label.uppercase(),
        color = onGradientMuted,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
    )
}

/** Plain-text rendering of a task for the OS share sheet. */
private fun shareTextForTask(task: Task): String {
    val sb = StringBuilder()
    sb.append(task.title.ifBlank { com.lucent.app.i18n.S.untitledTask })
    task.dueAt?.let { sb.append("\nDue: ").append(formatTimestamp(it)) }
    TaskPriority.fromValue(task.priority).takeIf { it != TaskPriority.NONE }?.let {
        sb.append("\nPriority: ").append(it.label)
    }
    RepeatRule.fromKey(task.repeatRule).takeIf { it != RepeatRule.NONE }?.let {
        sb.append("\nRepeats: ").append(it.label)
    }
    if (task.notes.isNotBlank()) sb.append("\n\n").append(task.notes)
    val items = Checklist.parse(task.subtasks)
    if (items.isNotEmpty()) {
        sb.append("\n\nSubtasks:\n").append(Checklist.toMarkdown(task.subtasks))
    }
    return sb.toString().trim()
}

/**
 * What a long-press on the detail page copies: the task's CONTENT and nothing else.
 *
 * Deliberately not [shareTextForTask]. Sharing describes a task to someone else, so it leads with
 * the title and carries the due date, priority and repeat rule as context. Copying is a step in
 * moving text into something you are already writing, and there the title, the created/due dates
 * and the badges are precisely what you would have to strip out by hand at the other end.
 *
 * So this is the description text, plus the subtask list when there is one — the two parts of a
 * task that are actually *written* rather than *set*. A task with neither copies as an empty
 * string, and [longPressCopy] no-ops on blank text rather than showing a "copied" toast for
 * nothing.
 */
private fun copyTextForTask(task: Task): String {
    val sb = StringBuilder()
    if (task.notes.isNotBlank()) sb.append(task.notes.trim())
    val items = Checklist.parse(task.subtasks)
    if (items.isNotEmpty()) {
        if (sb.isNotEmpty()) sb.append("\n\n")
        sb.append(Checklist.toMarkdown(task.subtasks).trim())
    }
    return sb.toString().trim()
}

/**
 * Sets (or clears) a due date and time, floored so it can never land before [minMillis] — a task's
 * due date can't predate its own creation. The floor is applied twice: `datePicker.minDate` blocks
 * picking an earlier calendar day outright, and `coerceAtLeast` catches the one case that slips
 * through it (same day, earlier time of day) once a time is chosen.
 */
@Composable
private fun DueDateRow(dueAt: Long?, minMillis: Long, onChange: (Long?) -> Unit) {
    val context = LocalContext.current
    val onGradient = LocalOnGradient.current
    val onGradientMuted = LocalOnGradientMuted.current

    fun openPicker() {
        val base = Calendar.getInstance().apply { timeInMillis = dueAt ?: minMillis }
        DatePickerDialog(
            context,
            { _, year, month, day ->
                val chosen = Calendar.getInstance().apply {
                    timeInMillis = base.timeInMillis
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, day)
                }
                TimePickerDialog(
                    context,
                    { _, hour, minute ->
                        chosen.set(Calendar.HOUR_OF_DAY, hour)
                        chosen.set(Calendar.MINUTE, minute)
                        chosen.set(Calendar.SECOND, 0)
                        chosen.set(Calendar.MILLISECOND, 0)
                        onChange(chosen.timeInMillis.coerceAtLeast(minMillis))
                    },
                    base.get(Calendar.HOUR_OF_DAY),
                    base.get(Calendar.MINUTE),
                    DateFormat.is24HourFormat(context)
                ).show()
            },
            base.get(Calendar.YEAR),
            base.get(Calendar.MONTH),
            base.get(Calendar.DAY_OF_MONTH)
        ).apply { datePicker.minDate = minMillis }.show()
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).clickable { openPicker() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.CalendarToday, contentDescription = null, tint = onGradientMuted)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = if (dueAt != null) com.lucent.app.i18n.S.dueWhen(formatTimestamp(dueAt)) else com.lucent.app.i18n.S.setADueDate,
            color = onGradient,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f)
        )
        if (dueAt != null) {
            IconButton(onClick = { onChange(null) }) {
                Icon(Icons.Default.Close, contentDescription = com.lucent.app.i18n.S.a11yClearDueDate, tint = onGradientMuted)
            }
        }
    }
}
