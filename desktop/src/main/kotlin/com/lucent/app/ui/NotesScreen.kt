package com.lucent.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalWindowInfo
import com.lucent.desktop.platform.DesktopFiles
import com.lucent.desktop.platform.DesktopShare
import java.io.File
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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
import com.lucent.app.data.Note
import com.lucent.app.data.NoteHistory
import com.lucent.app.data.NoteLinks
import com.lucent.app.data.NoteTemplate
import com.lucent.app.data.SearchQuery
import com.lucent.app.data.SettingsRepository
import com.lucent.app.data.TrashCleanup
import com.lucent.app.data.filterBySearch
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val DEFAULT_TAGS: List<String>
    get() = listOf(com.lucent.app.i18n.S.tagStudy, com.lucent.app.i18n.S.tagWork, com.lucent.app.i18n.S.tagGame, com.lucent.app.i18n.S.tagSports, com.lucent.app.i18n.S.tagOther)

@Composable
fun NotesScreen(active: Boolean = true) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getInstance(context) }
    val settingsRepo = remember { SettingsRepository(context) }
    val scope = rememberCoroutineScope()
    // The list flows are remembered so their Room subscription is stable across recompositions.
    // Creating a fresh Flow on every recomposition (as this used to) could drop the invalidation
    // that a just-inserted note fires, which is why a new note sometimes didn't appear until the
    // user left the tab and came back. A single stable subscription reliably re-emits on insert, so
    // a new note now shows immediately (see feature: reactive UI for new notes).
    val notes by remember { db.noteDao().getAll() }.collectAsState(initial = com.lucent.app.data.DataCache.notes)
    // Archived notes are collected too so that a note opened from the archive screen can still be
    // resolved for its detail page (the `notes` list above excludes archived notes by design).
    val archivedNotes by remember { db.noteDao().getArchived() }.collectAsState(initial = emptyList())
    val onGradient = LocalOnGradient.current
    val onGradientMuted = LocalOnGradientMuted.current

    // Remembered sort choice, persisted across app restarts (see SettingsRepository.notesSort).
    val sortKey by settingsRepo.notesSort.collectAsState(initial = "recent")
    val sortOption = NoteSort.fromKey(sortKey)

    // Whether Markdown formatting is on. Off by default (plain-text mode): the body renders
    // verbatim and the composer drops the "Markdown supported" hint. See SettingsRepository.
    val markdownEnabled by settingsRepo.markdownEnabled.collectAsState(initial = false)
    // Whether links are on. Independent of Markdown now (task 8): a link is a *navigation* feature,
    // not a formatting one, and the old `markdown && links` coupling meant anyone who preferred
    // plain text also silently lost their entire note graph — every [[link]] they had written became
    // dead text, and the Links switch in Settings sat greyed out with no explanation of why.
    // Plain-text mode now still resolves links; it just doesn't style anything else.
    val linksEnabled by settingsRepo.linksEnabled.collectAsState(initial = true)
    val linksActive = linksEnabled

    // Frequency scores for the "Recent" home section (id -> activity), see UsageTracker.
    val noteUsage by remember { com.lucent.app.data.UsageTracker.scores(context, com.lucent.app.data.UsageTracker.Kind.NOTE) }
        .collectAsState(initial = emptyMap())

    // View modes: the clean home grid, a read-only detail page, the create/edit composer, the
    // archive, the trash, and a note's revision history. Tapping a note opens its detail page; an
    // Edit button there opens the composer. New notes are created straight in the composer.
    var composing by remember { mutableStateOf(false) }
    var viewingId by remember { mutableStateOf<Long?>(null) }
    var editingId by remember { mutableStateOf<Long?>(null) }
    var newTitle by remember { mutableStateOf("") }
    var newBody by remember { mutableStateOf("") }
    var selectedTags by remember { mutableStateOf(setOf<String>()) }
    var newCustomTag by remember { mutableStateOf("") }
    var pendingAttachments by remember { mutableStateOf<List<Attachment>>(emptyList()) }
    var pinned by remember { mutableStateOf(false) }
    var selectedColor by remember { mutableStateOf(NoteColor.DEFAULT) }
    var isChecklistMode by remember { mutableStateOf(false) }
    var checklistItems by remember { mutableStateOf<List<ChecklistItem>>(emptyList()) }
    var newChecklistItemText by remember { mutableStateOf("") }
    var searchText by remember { mutableStateOf("") }
    // Optional date-range filter (start-of-day, end-of-day millis), set from the calendar button next
    // to the search box. Null means no date filter. A note carries only one timestamp (updatedAt), so
    // it doubles as the note's creation time for this filter. The end can't precede the start (the
    // picker enforces it).
    var dateRange by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var noteToDelete by remember { mutableStateOf<Note?>(null) }
    // Pin/unpin now asks first (see the confirmation dialog below), so a stray tap on the pin icon
    // can't silently reorder the list. Holds the note whose pin state is pending confirmation.
    var noteToTogglePin by remember { mutableStateOf<Note?>(null) }
    // Archiving (or restoring) moves a note between the home grid and the archive — it vanishes from
    // wherever you were looking — so it now asks first (task 7). One piece of state covers both
    // directions because the note itself already knows which way it's going.
    var noteToToggleArchive by remember { mutableStateOf<Note?>(null) }
    var showArchive by remember { mutableStateOf(false) }
    var showTrash by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    // Whether the header's secondary-action cluster (date filter, sort, overflow) is expanded.
    // Collapsed by default so the search box is full width; survives config changes (task 16).
    var actionsExpanded by rememberSaveable { mutableStateOf(false) }
    // Which note's revision history is open, if any.
    var historyForId by remember { mutableStateOf<Long?>(null) }

    // Hoisted above the view-mode `when` so the home grid's scroll position survives opening a note
    // and coming back — the grid composable is disposed while the detail page is up, but this state
    // object lives on with the screen, so returning restores the exact scroll offset instead of
    // snapping to the top (see feature: preserve scroll position).
    val gridState = rememberLazyGridState()

    // Long-press multi-select for batch actions (see feature: batch operations). When active, cards
    // show a checkbox and tapping toggles selection instead of opening.
    var selectionMode by remember { mutableStateOf(false) }
    var selectedNoteIds by remember { mutableStateOf(setOf<Long>()) }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }
    fun exitSelection() { selectionMode = false; selectedNoteIds = emptySet() }

    // A note asked for from elsewhere — a unified-search result tapped while on another tab. Consumed
    // once, so returning to this tab later doesn't helpfully reopen a note the user closed long ago.
    // Where to go when the note currently open was reached from *another* tab (task 4).
    var returnToOnClose by remember { mutableStateOf<Screen?>(null) }

    LaunchedEffect(AppNavigation.pendingNoteId) {
        AppNavigation.consumeNoteId()?.let { id ->
            showSearch = false
            showArchive = false
            showTrash = false
            viewingId = id
            // Consumed together with the id, so closing this note returns to the tab the user was
            // actually on when they went looking for it.
            returnToOnClose = AppNavigation.consumeReturnScreen()
        }
    }

    var showUnsavedDialog by remember { mutableStateOf(false) }

    fun resetComposer() {
        editingId = null
        newTitle = ""
        newBody = ""
        selectedTags = emptySet()
        newCustomTag = ""
        pendingAttachments = emptyList()
        pinned = false
        selectedColor = NoteColor.DEFAULT
        isChecklistMode = false
        checklistItems = emptyList()
        newChecklistItemText = ""
    }

    fun startCreate(prefillTitle: String = "") {
        resetComposer()
        newTitle = prefillTitle
        composing = true
    }

    // A home-screen widget "new note" tap routes here (task 9): open a fresh composer, exactly once.
    // Consumed so returning to the tab later doesn't reopen an empty composer.
    LaunchedEffect(AppNavigation.composeNoteRequested) {
        if (AppNavigation.consumeComposeNote()) {
            showSearch = false
            showArchive = false
            showTrash = false
            viewingId = null
            startCreate()
        }
    }

    /** Fill the composer from a one-tap template. Only ever offered on a *new*, still-empty note. */
    fun applyTemplate(template: NoteTemplate) {
        val prefill = template.prefill()
        newTitle = prefill.title
        newBody = prefill.body
        selectedTags = prefill.tags
        isChecklistMode = prefill.isChecklist
        checklistItems = prefill.checklist
    }

    /**
     * Close the detail page, returning to the tab this note was opened *from* if it came from
     * another one (task 4). For an ordinary in-tab open there is no origin and this is simply
     * "back to the grid", exactly as before.
     */
    fun closeDetail() {
        viewingId = null
        val back = returnToOnClose
        returnToOnClose = null
        if (back != null) AppNavigation.requestScreen(back)
    }

    fun openDetail(note: Note) {
        viewingId = note.id
        // An in-tab open is not a cross-tab trip; drop any stale return so it can't fire later.
        returnToOnClose = null
        // Feed the "Recent" section: opening a note counts toward how active it is.
        AppScope.io.launch {
            com.lucent.app.data.UsageTracker.recordOpen(context, com.lucent.app.data.UsageTracker.Kind.NOTE, note.id)
        }
    }

    fun startEdit(note: Note) {
        editingId = note.id
        newTitle = note.title
        newBody = note.body
        selectedTags = note.tags.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
        newCustomTag = ""
        pendingAttachments = Attachments.parse(note.attachments)
        pinned = note.pinned
        selectedColor = NoteColor.fromKey(note.color)
        isChecklistMode = note.isChecklist
        checklistItems = Checklist.parse(note.checklist)
        newChecklistItemText = ""
        composing = true
    }

    fun saveNote() {
        // A checklist item typed into the add field but never committed with "+" still counts
        // (settings tasks B1/B4, checklist-note side): it is folded in here, so typing the last
        // item and hitting Save directly never silently drops it. Only in checklist mode — that is
        // the only mode in which the add field is even on screen.
        val pendingItem = newChecklistItemText.trim()
        val composedChecklist =
            if (isChecklistMode && pendingItem.isNotEmpty()) checklistItems + Checklist.newItem(pendingItem)
            else checklistItems
        if (newTitle.isBlank() && newBody.isBlank() && pendingAttachments.isEmpty() && composedChecklist.isEmpty()) {
            composing = false
            return
        }
        // Snapshot every composer field the save needs *before* kicking off the background write.
        // This runs on AppScope's app-lifetime scope (see below), and resetComposer() runs
        // synchronously the moment this function returns — so reading these `var`s from inside the
        // launch block would race the reset and could persist blanks over a perfectly good note.
        val title = newTitle
        val body = newBody
        val tags = selectedTags.joinToString(",")
        val attachmentsJson = Attachments.serialize(pendingAttachments)
        val pinnedSnapshot = pinned
        val colorSnapshot = selectedColor.key
        val isChecklistSnapshot = isChecklistMode
        val checklistJson = Checklist.serialize(composedChecklist)
        val id = editingId
        val appContext = context.applicationContext

        // Run on the app-lifetime scope, not the composable's scope: saving from the
        // unsaved-changes dialog switches screens in the same action, which would otherwise
        // dispose this screen and cancel the write before it commits.
        AppScope.io.launch {
            if (id != null) {
                // Read the existing row and copy onto it so fields the composer doesn't touch —
                // notably the archive state (archived/archivedAt) and trashedAt — are preserved.
                // Rebuilding a fresh Note() here would reset those to their defaults and silently
                // un-archive an archived note the moment it was edited.
                val existing = db.noteDao().getByIdOnce(id)
                if (existing != null) {
                    // Capture the outgoing text as a revision before it's overwritten. This is the
                    // only place an edit from the UI can happen, so it's the only place that has to
                    // remember — and NoteHistory itself decides whether the change is even worth
                    // recording, so an accidental no-op save doesn't evict real history.
                    NoteHistory.recordIfChanged(
                        db = db,
                        existing = existing,
                        newTitle = title,
                        newBody = body,
                        newTags = tags,
                        newIsChecklist = isChecklistSnapshot,
                        newChecklist = checklistJson
                    )
                }
                val updated = (existing ?: Note(id = id, title = title, body = body)).copy(
                    title = title,
                    body = body,
                    updatedAt = System.currentTimeMillis(),
                    tags = tags,
                    attachments = attachmentsJson,
                    pinned = pinnedSnapshot,
                    color = colorSnapshot,
                    isChecklist = isChecklistSnapshot,
                    checklist = checklistJson
                )
                db.noteDao().update(updated)
            } else {
                db.noteDao().insert(
                    Note(
                        title = title,
                        body = body,
                        tags = tags,
                        attachments = attachmentsJson,
                        pinned = pinnedSnapshot,
                        color = colorSnapshot,
                        isChecklist = isChecklistSnapshot,
                        checklist = checklistJson
                    )
                )
            }
            withContext(Dispatchers.Main) {
                LucentToast.show(appContext, com.lucent.app.i18n.S.noteSaved)
            }
        }
        composing = false
        resetComposer()
    }

    // True while the composer holds edits that haven't been saved yet, whether creating a new
    // note from scratch or editing an existing one.
    val editingNote = remember(notes, archivedNotes, editingId) {
        notes.firstOrNull { it.id == editingId } ?: archivedNotes.firstOrNull { it.id == editingId }
    }
    val noteDirty = composing && run {
        val original = editingNote
        if (original != null) {
            val originalTags = original.tags.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
            newTitle != original.title || newBody != original.body || selectedTags != originalTags ||
                Attachments.serialize(pendingAttachments) != original.attachments ||
                pinned != original.pinned || selectedColor.key != original.color ||
                isChecklistMode != original.isChecklist ||
                Checklist.serialize(checklistItems) != original.checklist ||
                // Text sitting in the add field is work too (B4): losing it on exit because no "+"
                // was pressed is exactly the bug this flag exists to prevent.
                (isChecklistMode && newChecklistItemText.isNotBlank())
        } else {
            newTitle.isNotBlank() || newBody.isNotBlank() || pendingAttachments.isNotEmpty() ||
                selectedTags.isNotEmpty() || pinned || selectedColor != NoteColor.DEFAULT ||
                checklistItems.isNotEmpty() || (isChecklistMode && newChecklistItemText.isNotBlank())
        }
    }

    /**
     * Whether the composer holds any CONTENT a template would overwrite (task 12).
     *
     * This is a narrower question than [noteDirty], and conflating the two is what made pinning a
     * new note hide the template row. [noteDirty] answers "would leaving here lose something?" — and
     * for that, pinning and picking a colour absolutely count, because the user chose them and would
     * not expect them to evaporate. But the template row was gated on the same flag, and it is
     * answering a completely different question: "is there anything here a template would destroy?"
     *
     * [applyTemplate] writes exactly five things — title, body, tags, checklist mode, checklist items
     * — and touches neither the pin nor the colour. So a pinned, purple, otherwise-empty new note is
     * still a blank canvas as far as templates are concerned, and hiding them was protecting work
     * that did not exist. Worse, it was silent: the row simply disappeared, with the pin toggle as
     * the unexplained cause, which reads as a bug rather than a safeguard.
     *
     * The fields listed here are precisely the fields [applyTemplate] overwrites. If it ever learns
     * to set another one, that field belongs in this expression too.
     */
    val noteContentDirty = composing && editingId == null && (
        newTitle.isNotBlank() || newBody.isNotBlank() ||
            selectedTags.isNotEmpty() || checklistItems.isNotEmpty() ||
            newChecklistItemText.isNotBlank()
        )

    fun discardComposer() {
        composing = false
        resetComposer()
    }

    // Leaving the composer (back arrow or system back) while dirty asks first instead of
    // silently discarding what was typed.
    fun leaveComposer() {
        if (noteDirty) showUnsavedDialog = true else discardComposer()
    }

    // Back priority, most specific first: composer -> ask/close; history -> detail; detail -> the
    // list it came from; archive/trash -> home. Detail sits above archive/trash because a note
    // opened *from* either should return there, not skip straight to the home grid.
    BackHandler(enabled = composing) { leaveComposer() }
    BackHandler(enabled = !composing && historyForId != null) { historyForId = null }
    BackHandler(enabled = !composing && historyForId == null && viewingId != null) { closeDetail() }
    BackHandler(enabled = !composing && historyForId == null && viewingId == null && showArchive) { showArchive = false }
    BackHandler(enabled = !composing && historyForId == null && viewingId == null && !showArchive && showTrash) { showTrash = false }
    BackHandler(enabled = !composing && historyForId == null && viewingId == null && !showArchive && !showTrash && showSearch) { showSearch = false }
    // On the home grid, a back press first exits multi-select mode rather than leaving the screen.
    BackHandler(enabled = selectionMode && !composing && historyForId == null && viewingId == null && !showArchive && !showTrash && !showSearch) { exitSelection() }

    // Leaving this tab folds it back to the notes grid (task 3). Same reasoning as the Tasks tab:
    // the detail page, composer, archive, trash, history and search are destinations, and returning
    // to a tab should return you to the tab, not to wherever you happened to stop. The
    // unsaved-changes guard has already run by this point, so nothing typed can be lost here.
    LaunchedEffect(active) {
        if (!active) {
            if (composing) discardComposer()
            viewingId = null
            returnToOnClose = null
            historyForId = null
            showArchive = false
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
    // On Android this collapsed the action cluster on ON_STOP (app no longer visible). The desktop
    // equivalent is the window losing focus — reading isWindowFocused here re-runs the effect when
    // focus flips, collapsing the cluster when focus is lost.
    val isWindowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(isWindowFocused) {
        if (!isWindowFocused) actionsExpanded = false
    }

    // Registers with the app-lifetime guard so switching bottom-nav tabs, or the system back
    // button closing the app, also asks before losing an in-progress note.
    SideEffect {
        if (noteDirty) {
            UnsavedChangesGuard.register("notes", ::saveNote, ::discardComposer)
        } else {
            UnsavedChangesGuard.clear("notes")
        }
    }
    DisposableEffect(Unit) { onDispose { UnsavedChangesGuard.clear("notes") } }

    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedDialog = false },
            title = { Text(com.lucent.app.i18n.S.unsavedChangesTitle) },
            text = { Text(if (editingId != null) com.lucent.app.i18n.S.unsavedNoteExistingBody else com.lucent.app.i18n.S.unsavedNoteNewBody) },
            confirmButton = {
                TextButton(onClick = {
                    showUnsavedDialog = false
                    saveNote()
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

    // Keyed on selectedTags too: a tag the user just typed and added isn't in any saved note
    // yet, so without this it wouldn't render as a chip (or its selected highlight) until after
    // the note was saved and the list re-queried. Unioning it in makes it show immediately.
    val allTags = remember(notes, selectedTags) {
        (DEFAULT_TAGS + notes.flatMap { it.tags.split(",").map(String::trim).filter(String::isNotBlank) } + selectedTags).distinct()
    }

    // The typed text is parsed into a structured query (terms, phrases, tag:/is:/has:/link: filters)
    // rather than being string-matched directly — see data/SearchQuery.kt. The calendar date filter
    // then narrows by the note's timestamp. The two combine: a note must satisfy the query AND fall
    // on the picked day when one is set.
    val query = remember(searchText) { SearchQuery.parse(searchText) }
    // `is:archived` is the one filter that changes *which* notes are even in scope, since archived
    // notes are deliberately absent from the home grid. Honouring it here means the archive is
    // searchable from the home screen without first navigating into it.
    val searchPool = remember(notes, archivedNotes, query) {
        if ("archived" in query.flags) notes + archivedNotes else notes
    }
    val filteredNotes = remember(searchPool, query, dateRange) {
        searchPool.filterBySearch(query).filter { note ->
            dateRange?.let { (start, end) -> withinLocalDayRange(note.updatedAt, start, end) } ?: true
        }
    }
    // Pinned first, then relevance when the user is actually searching, then their chosen sort.
    val sortedNotes = remember(filteredNotes, sortOption, query) {
        filteredNotes.sortedForDisplay(sortOption, query)
    }

    // Desktop counterpart of Android's GetMultipleContents launcher: the native file dialog is opened
    // synchronously on the AWT thread from the attach button's click handler, and this function then
    // imports the chosen java.io.Files off the main thread. The only limit is per file (600 MB) —
    // there's no cap on the combined volume — so each file is checked on its own. A file over the
    // ceiling is rejected before it's written where its size is known up front, cleaned up otherwise.
    fun importPickedFiles(files: List<File>) {
        if (files.isEmpty()) return
        scope.launch {
            val (accepted, lastMessage) = withContext(Dispatchers.IO) {
                var runningPending = pendingAttachments.toList()
                var message = ""
                for (file in files) {
                    val hint = file.length()
                    if (hint > 0) {
                        val preCheck = AttachmentLimits.checkSingle(hint)
                        if (!preCheck.allowed) { message = preCheck.message; continue }
                    }
                    val newAtt = fileToAttachment(context, file) ?: run { message = com.lucent.app.i18n.S.couldNotReadOneFile; null } ?: continue
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

    // Deleting is now a *move to Trash*, not an erasure — the row, its files, and its history stay
    // put for 30 days. The dialog says exactly that, because a confirmation that threatens
    // permanent loss when nothing of the sort is happening trains people to ignore confirmations.
    noteToDelete?.let { note ->
        AlertDialog(
            onDismissRequest = { noteToDelete = null },
            title = { Text(com.lucent.app.i18n.S.moveToTrashTitle) },
            text = {
                Text(com.lucent.app.i18n.S.moveNoteTrashBody(note.title.ifBlank { com.lucent.app.i18n.S.untitledNote }, TrashCleanup.RETENTION_DAYS))
            },
            confirmButton = {
                TextButton(onClick = {
                    AppScope.io.launch {
                        db.noteDao().update(note.copy(trashedAt = System.currentTimeMillis()))
                    }
                    if (viewingId == note.id) viewingId = null
                    noteToDelete = null
                }) { Text(com.lucent.app.i18n.S.moveToTrash) }
            },
            dismissButton = { TextButton(onClick = { noteToDelete = null }) { Text(com.lucent.app.i18n.S.actionCancel) } }
        )
    }

    // Batch move-to-trash for the multi-selected notes. Same soft-delete as the single-note path,
    // just applied to the whole selection at once.
    if (showBatchDeleteConfirm) {
        val count = selectedNoteIds.size
        AlertDialog(
            onDismissRequest = { showBatchDeleteConfirm = false },
            title = { Text(com.lucent.app.i18n.S.moveToTrashTitle) },
            text = {
                Text(if (count == 1) com.lucent.app.i18n.S.moveOneNoteTrashBody(TrashCleanup.RETENTION_DAYS) else com.lucent.app.i18n.S.moveNNotesTrashBody(count, TrashCleanup.RETENTION_DAYS))
            },
            confirmButton = {
                TextButton(onClick = {
                    val ids = selectedNoteIds
                    showBatchDeleteConfirm = false
                    exitSelection()
                    AppScope.io.launch {
                        val now = System.currentTimeMillis()
                        ids.forEach { id ->
                            db.noteDao().getByIdOnce(id)?.let { db.noteDao().update(it.copy(trashedAt = now)) }
                        }
                    }
                }) { Text(com.lucent.app.i18n.S.moveToTrash) }
            },
            dismissButton = { TextButton(onClick = { showBatchDeleteConfirm = false }) { Text(com.lucent.app.i18n.S.actionCancel) } }
        )
    }

    // Confirm before archiving or restoring (task 7). Nothing is written until Confirm is pressed;
    // dismissing by any route — Cancel, tapping outside, back — leaves the note exactly as it was.
    noteToToggleArchive?.let { note ->
        val willArchive = !note.archived
        AlertDialog(
            onDismissRequest = { noteToToggleArchive = null },
            title = { Text(if (willArchive) com.lucent.app.i18n.S.archiveNoteTitle else com.lucent.app.i18n.S.restoreNoteTitle) },
            text = {
                Text(if (willArchive) com.lucent.app.i18n.S.archiveNoteBody(note.title.ifBlank { com.lucent.app.i18n.S.untitledNote }) else com.lucent.app.i18n.S.unarchiveNoteBody(note.title.ifBlank { com.lucent.app.i18n.S.untitledNote }))
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = note
                    noteToToggleArchive = null
                    val appContext = context.applicationContext
                    AppScope.io.launch {
                        db.noteDao().update(
                            if (target.archived) target.copy(archived = false, archivedAt = null)
                            else target.copy(archived = true, archivedAt = System.currentTimeMillis())
                        )
                        withContext(Dispatchers.Main) {
                            LucentToast.show(appContext, if (target.archived) com.lucent.app.i18n.S.noteRestoredToast else com.lucent.app.i18n.S.noteArchivedToast)
                        }
                    }
                    // The note is about to leave whichever list is showing, so the detail page can't
                    // keep standing on it.
                    if (viewingId == target.id) closeDetail()
                }) { Text(if (willArchive) com.lucent.app.i18n.S.archive else com.lucent.app.i18n.S.actionRestore) }
            },
            dismissButton = { TextButton(onClick = { noteToToggleArchive = null }) { Text(com.lucent.app.i18n.S.actionCancel) } }
        )
    }

    // Confirm-before-pin: pinning (or unpinning) reorders the whole list, so it asks first rather
    // than acting on the tap. Cancelling leaves the note exactly as it was.
    noteToTogglePin?.let { note ->
        val willPin = !note.pinned
        AlertDialog(
            onDismissRequest = { noteToTogglePin = null },
            title = { Text(if (willPin) com.lucent.app.i18n.S.pinNoteTitle else com.lucent.app.i18n.S.unpinNoteTitle) },
            text = {
                Text(if (willPin) com.lucent.app.i18n.S.pinNoteBody(note.title.ifBlank { com.lucent.app.i18n.S.untitledNote }) else com.lucent.app.i18n.S.unpinNoteBody(note.title.ifBlank { com.lucent.app.i18n.S.untitledNote }))
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = note
                    noteToTogglePin = null
                    AppScope.io.launch { db.noteDao().update(target.copy(pinned = !target.pinned)) }
                }) { Text(if (willPin) com.lucent.app.i18n.S.actionPin else com.lucent.app.i18n.S.actionUnpin) }
            },
            dismissButton = { TextButton(onClick = { noteToTogglePin = null }) { Text(com.lucent.app.i18n.S.actionCancel) } }
        )
    }

    // The note currently open in the detail page, resolved live so edits reflect at once. Trashed
    // notes are excluded, so a note trashed by *any* path — this page's button, the assistant, the
    // grid — makes the detail page fall away rather than sit there showing a deleted note.
    val viewingNote = remember(notes, archivedNotes, viewingId) {
        (notes + archivedNotes).firstOrNull { it.id == viewingId && it.trashedAt == null }
    }

    when {
        composing -> {
            // ---- Create / edit page ----
            Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()).padding(bottom = LocalBottomBarInset.current)) {
                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { leaveComposer() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = com.lucent.app.i18n.S.actionBack, tint = onGradient)
                    }
                    Text(if (editingId != null) com.lucent.app.i18n.S.editNote else com.lucent.app.i18n.S.newNote, color = onGradient, fontSize = 20.sp)
                }

                Column(modifier = Modifier.fillMaxWidth().frostedGlass(tint = selectedColor.swatch).padding(16.dp)) {
                    // Templates are offered only on a brand-new note whose CONTENT is still empty.
                    // Showing them while editing an existing note — or after the user has started
                    // typing — would turn a one-tap convenience into a one-tap way to obliterate your
                    // own work. Pinning or colouring the note is not typing: see [noteContentDirty].
                    if (editingId == null && !noteContentDirty) {
                        Text(com.lucent.app.i18n.S.startFromTemplate, color = onGradientMuted, fontSize = 12.sp)
                        Spacer(modifier = Modifier.height(6.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            NoteTemplate.entries.forEach { template ->
                                FilterChip(
                                    selected = false,
                                    onClick = { applyTemplate(template) },
                                    label = { Text(template.label) },
                                    leadingIcon = {
                                        Icon(
                                            templateIcon(template),
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    OutlinedTextField(
                        value = newTitle,
                        onValueChange = { newTitle = it },
                        placeholder = { Text(com.lucent.app.i18n.S.fieldTitle) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Checklist-mode toggle: switches the editor below between free text and a
                    // Keep-style checkable list. Both live in composer state regardless of which is
                    // showing, so flipping back and forth never discards either one — a note that
                    // was a paragraph, became a checklist, and goes back still has its paragraph.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.AutoMirrored.Filled.FormatListBulleted,
                            contentDescription = null,
                            tint = if (isChecklistMode) onGradient else onGradientMuted
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(com.lucent.app.i18n.S.checklistNote, color = onGradient, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Switch(checked = isChecklistMode, onCheckedChange = { isChecklistMode = it })
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    if (isChecklistMode) {
                        ChecklistEditorSection(
                            items = checklistItems,
                            newItemText = newChecklistItemText,
                            onNewItemTextChange = { newChecklistItemText = it },
                            onAdd = {
                                checklistItems = checklistItems + Checklist.newItem(newChecklistItemText)
                                newChecklistItemText = ""
                            },
                            onToggle = { item ->
                                checklistItems = checklistItems.map { if (it.id == item.id) it.copy(done = !it.done) else it }
                            },
                            onRemove = { item -> checklistItems = checklistItems.filterNot { it.id == item.id } },
                            // Items are editable in place after being added (settings task B3).
                            onEditText = { item, text ->
                                checklistItems = checklistItems.map { if (it.id == item.id) it.copy(text = text) else it }
                            },
                            addLabel = com.lucent.app.i18n.S.addItem
                        )
                    } else {
                        // Body field with an expand toggle in its bottom-right corner. Expanding
                        // opens a modal editor that fills the top 3/4 of the screen (see
                        // ExpandableGlassTextField) so long notes are comfortable to read and edit,
                        // without reflowing the tags/attachments/save controls below.
                        ExpandableGlassTextField(
                            value = newBody,
                            onValueChange = { newBody = it },
                            // The body field now names itself "Details", matching the task composer so
                            // notes and tasks read consistently (task 7). When Markdown is on we also
                            // remind the user what's supported here; the [[links]] part of that hint is
                            // dropped when the Links toggle is off (task 3), and the whole hint is gone
                            // in plain-text mode where a user can't act on it.
                            placeholder = when {
                                markdownEnabled && linksEnabled -> com.lucent.app.i18n.S.detailsMarkdownLinks
                                markdownEnabled -> com.lucent.app.i18n.S.detailsMarkdown
                                // Links without Markdown is now a real combination, so it gets its
                                // own hint rather than falling through to the bare one.
                                linksEnabled -> com.lucent.app.i18n.S.detailsLinks
                                else -> com.lucent.app.i18n.S.detailsPlaceholder
                            },
                            expandedTitle = if (editingId != null) com.lucent.app.i18n.S.editNote else com.lucent.app.i18n.S.newNote,
                            collapsedMinHeight = 120.dp,
                            collapsedMaxHeight = 320.dp
                        )
                        if (newBody.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            // Paragraphs + characters, not words: "word" is meaningless in scripts
                            // that don't separate words with spaces (Chinese, Japanese, Korean),
                            // where a whole note collapsed to "1 word". Both figures below count
                            // correctly in every language. See NoteStats.paragraphCharLabel.
                            val statsLabel = remember(newBody) { com.lucent.app.data.NoteStats.paragraphCharLabel(newBody) }
                            Text(
                                statsLabel,
                                color = onGradientMuted,
                                fontSize = 11.sp
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    // Pin toggle: pinned notes float to the top of the home grid, ahead of sort.
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

                    Text(com.lucent.app.i18n.S.labelColour, color = onGradient, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    ColorPickerRow(selected = selectedColor, onSelect = { selectedColor = it })
                    Spacer(modifier = Modifier.height(12.dp))

                    Text(com.lucent.app.i18n.S.labelTags, color = onGradient)
                    Spacer(modifier = Modifier.height(4.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        allTags.forEach { tag ->
                            FilterChip(
                                selected = selectedTags.contains(tag),
                                onClick = {
                                    selectedTags = if (selectedTags.contains(tag)) selectedTags - tag else selectedTags + tag
                                },
                                label = { Text(tag) }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newCustomTag,
                            onValueChange = { newCustomTag = it },
                            placeholder = { Text(com.lucent.app.i18n.S.newTag) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(onClick = {
                            val tag = newCustomTag.trim()
                            if (tag.isNotBlank()) {
                                selectedTags = selectedTags + tag
                                newCustomTag = ""
                            }
                        }) {
                            Icon(Icons.Default.Add, contentDescription = com.lucent.app.i18n.S.a11yAddTag, tint = onGradient)
                        }
                    }

                    // Attachments get a labelled section of their own instead of a bare text button
                    // wedged between the tag field and Save (task 13). See [AttachmentSection].
                    Spacer(modifier = Modifier.height(16.dp))
                    AttachmentSection(
                        attachments = pendingAttachments,
                        onPick = { importPickedFiles(DesktopFiles.openFiles()) },
                        onRemove = { att ->
                            pendingAttachments = Attachments.removeByName(context, pendingAttachments, att.name)
                        }
                    )

                    Spacer(modifier = Modifier.height(20.dp))
                    // Enlarged so the primary action is the biggest control in this group. Its size
                    // and the smaller "attach file" button above are the reverse of before, when the
                    // optional attachment button was larger than the button that actually saves.
                    Button(
                        onClick = { saveNote() },
                        contentPadding = PaddingValues(horizontal = 26.dp, vertical = 14.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(24.dp))
                        Text(
                            if (editingId != null) " " + com.lucent.app.i18n.S.saveChanges else " " + com.lucent.app.i18n.S.addNoteBtn,
                            fontSize = 16.sp
                        )
                    }
                }
            }
        }

        historyForId != null && viewingNote != null -> {
            // ---- Revision history for the note currently open ----
            NoteHistoryScreen(
                note = viewingNote,
                onBack = { historyForId = null },
                onRestored = { historyForId = null }
            )
        }

        viewingNote != null -> {
            // ---- Read-only detail page ----
            val note = viewingNote
            val attachments = remember(note.attachments) { Attachments.parse(note.attachments) }
            val checklistView = remember(note.checklist) { Checklist.parse(note.checklist) }
            // The link graph is derived from the live note list, so it is always in step with the
            // text — see data/NoteLinks.kt for why there is no index to fall out of date.
            val linkPool = remember(notes, archivedNotes) { notes + archivedNotes }
            val outgoing = remember(note, linkPool) { NoteLinks.outgoing(note, linkPool) }
            val backlinks = remember(note, linkPool) { NoteLinks.backlinks(note, linkPool) }
            val broken = remember(note, linkPool) { NoteLinks.brokenLinks(note, linkPool) }
            val brokenLower = remember(broken) { broken.map { it.lowercase() }.toSet() }
            val versionCount by db.noteVersionDao().getForNote(note.id).collectAsState(initial = emptyList())

            // Swipe horizontally to move between notes in the current (sorted, filtered) order:
            // swipe left for the next note, right for the previous — the same list you were just
            // scrolling, so "next" means what it looks like it means. Does nothing at the ends, or
            // for a note not in the visible list (e.g. one opened from the archive).
            //
            // The gesture lives on this full-page Box, not on the inner scrolling Column, so it's
            // recognised anywhere on the page rather than only over the body text (task 7). Two strips
            // are deliberately excluded: the top band (the back / title / horizontally-scrollable
            // action header) and the bottom band. A drag is only armed when it *starts* inside the
            // central band; the inner Column still owns vertical scrolling, and the top action strip
            // still owns its own horizontal scroll (a child wins that gesture over this ancestor).
            // ---- Animated page-to-page swipe (task 7) ----
            //
            // The gesture already worked; what it lacked was any sense of movement. A drag past the
            // threshold simply swapped the content, so the next note appeared fully formed with no
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
            // [swipeOffset] is deliberately remembered WITHOUT a key on the note id: it has to
            // survive the content swap in the middle of the animation, because the exit and the entry
            // are two halves of one gesture, not two animations.
            val swipeList = sortedNotes
            val swipeOffset = remember { Animatable(0f) }
            var pageWidth by remember { mutableFloatStateOf(0f) }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { pageWidth = it.width.toFloat() }
                    .pointerInput(note.id, swipeList) {
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
                                    val idx = swipeList.indexOfFirst { it.id == note.id }
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
                                    val idx = swipeList.indexOfFirst { it.id == note.id }
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
                    Text(com.lucent.app.i18n.S.screenNote, color = onGradient, fontSize = 20.sp)
                    Spacer(modifier = Modifier.width(8.dp))

                    // Action icons live in a right-aligned, horizontally-scrollable strip (task 17).
                    // Edit and Archive are now duplicated up here so they're reachable without scrolling
                    // to the bottom of a long note. The strip takes the remaining width and scrolls if
                    // every icon can't fit at once, so nothing is ever clipped on a narrow screen.
                    Row(
                        modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Pin/unpin sits at the very front of the strip (task 10) — it's the most-used
                        // quick action, so it's the first thing the thumb reaches. Confirmation is asked
                        // first (the dialog does the write).
                        PinIconButton(
                            pinned = note.pinned,
                            onToggle = { noteToTogglePin = note }
                        )
                        // Edit — opens the composer for this note (same action as the bottom button).
                        IconButton(onClick = { startEdit(note) }) {
                            Icon(Icons.Default.Edit, contentDescription = com.lucent.app.i18n.S.actionEdit, tint = onGradient)
                        }
                        // Archive / Restore — mirrors the bottom button: archives a live note, or
                        // restores one opened from the archive. Runs on the app-lifetime scope and
                        // then leaves the detail page, since the note moves between grid and archive.
                        // Archive / Restore — both ask first now (task 7); the dialog owns the write.
                        IconButton(onClick = { noteToToggleArchive = note }) {
                            Icon(
                                if (note.archived) Icons.Filled.Unarchive else Icons.Default.Archive,
                                contentDescription = if (note.archived) com.lucent.app.i18n.S.actionRestore else com.lucent.app.i18n.S.archive,
                                tint = onGradient
                            )
                        }

                        // History is only offered once there's something in it, so a note that's never
                        // been edited doesn't advertise an empty screen.
                        if (versionCount.isNotEmpty()) {
                            IconButton(onClick = { historyForId = note.id }) {
                                Icon(
                                    Icons.Default.History,
                                    contentDescription = com.lucent.app.i18n.S.a11yVersionHistory(versionCount.size),
                                    tint = onGradient
                                )
                            }
                        }
                        IconButton(onClick = {
                            // Desktop has no system share sheet; the local-first equivalent is to copy
                            // the note's text (with its title as a subject line) to the clipboard and
                            // confirm with a toast — no account, no server, no link that outlives the tap.
                            DesktopShare.shareText(
                                context,
                                subject = note.title.ifBlank { "Note" },
                                text = shareTextForNote(note)
                            )
                        }) {
                            Icon(Icons.Default.Share, contentDescription = com.lucent.app.i18n.S.actionShare, tint = onGradient)
                        }
                        IconButton(onClick = { noteToDelete = note }) {
                            Icon(Icons.Default.Delete, contentDescription = com.lucent.app.i18n.S.actionDelete, tint = onGradient)
                        }
                    }
                }

                // Long-press anywhere on this card to copy the note's content (task 1). It is on
                // the card rather than on the body Text so the target is the whole readable area,
                // including the padding — a long-press that lands a few pixels off the last line
                // should still work. The title/date block below is inside a SelectionContainer,
                // which consumes long-press for the platform's own text-selection toolbar, so a
                // press there still selects rather than copying; that is the correct split, since
                // someone pressing directly on the title is asking for the title.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .longPressCopy(context, copyTextForNote(note))
                        .frostedGlass(tint = NoteColor.fromKey(note.color).swatch)
                        .padding(16.dp)
                ) {
                    // Title/date/tags sit in a SelectionContainer so the reader gets the platform's
                    // native text-selection toolbar. The body is *outside* it, because a Markdown
                    // body carries tappable links and selection would swallow those taps — and the
                    // checklist is outside for the same reason its checkboxes need to stay live.
                    SelectionContainer {
                        Column {
                            Text(note.title.ifBlank { com.lucent.app.i18n.S.untitled }, color = onGradient, fontSize = 22.sp)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(formatTimestamp(note.updatedAt), color = onGradientMuted, fontSize = 12.sp)
                            if (note.tags.isNotBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(note.tags.split(",").joinToString(" · "), color = onGradientMuted, fontSize = 12.sp)
                            }
                        }
                    }

                    if (note.isChecklist) {
                        Spacer(modifier = Modifier.height(12.dp))
                        ChecklistView(
                            items = checklistView,
                            header = com.lucent.app.i18n.S.historyItemsHeader,
                            onToggle = { item, checked ->
                                AppScope.io.launch {
                                    db.noteDao().update(
                                        note.copy(checklist = Checklist.setDone(note.checklist, item.id, checked))
                                    )
                                }
                            }
                        )
                    } else if (note.body.isNotBlank()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        val openLink: (String) -> Unit = { target ->
                            val hit = NoteLinks.resolve(target, linkPool)
                            if (hit != null) viewingId = hit.id else startCreate(prefillTitle = target)
                        }
                        when {
                            markdownEnabled -> MarkdownText(
                                text = note.body,
                                brokenLinks = brokenLower,
                                onWikiLink = openLink,
                                linksEnabled = linksEnabled
                            )
                            // Links on, Markdown off (task 8): the body is shown exactly as typed —
                            // no headings, no bold, no code — but [[links]] and [text](url) are still
                            // live. This is the combination the old coupling made impossible.
                            linksEnabled -> LinkedPlainText(
                                text = note.body,
                                brokenLinks = brokenLower,
                                onWikiLink = openLink
                            )
                            // Both off: exactly what was typed, inert.
                            else -> Text(note.body, color = onGradient)
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        val statsLabel = remember(note.body) { com.lucent.app.data.NoteStats.paragraphCharLabel(note.body) }
                        Text(statsLabel, color = onGradientMuted, fontSize = 12.sp)
                    }

                    // Attachments live only on the detail page, never on the home cards.
                    CardAttachments(attachments, onGradient, onGradientMuted)
                }

                // The link graph (outgoing/broken/backlinks) is part of the [[links]] feature, so it
                // only shows when links are actually active — off in plain-text mode, and off when
                // the Links toggle itself is off (task 3).
                if (linksActive) {
                    if (outgoing.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        NoteLinkChips(com.lucent.app.i18n.S.linksToHeader, outgoing) { target -> viewingId = target.id }
                    }
                    if (broken.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        BrokenLinkChips(broken) { target -> startCreate(prefillTitle = target) }
                    }
                    if (backlinks.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        NoteLinkChips(com.lucent.app.i18n.S.linkedFromHeader, backlinks) { source -> viewingId = source.id }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlassCapsuleButton(
                        text = com.lucent.app.i18n.S.editNote,
                        icon = Icons.Default.Edit,
                        onClick = { startEdit(note) },
                        modifier = Modifier.weight(1f)
                    )
                    // Archive sits right next to Edit in the same capsule style. For a note that's
                    // already archived (opened from the archive) this restores it instead. Both run
                    // on the app-lifetime scope and then leave the detail page, since the note is
                    // about to move between the home grid and the archive.
                    GlassCapsuleButton(
                        text = if (note.archived) com.lucent.app.i18n.S.actionRestore else com.lucent.app.i18n.S.archive,
                        icon = if (note.archived) Icons.Filled.Unarchive else Icons.Default.Archive,
                        // Confirmation first (task 7) — the dialog performs the write and leaves the page.
                        onClick = { noteToToggleArchive = note },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            }
        }

        showArchive -> {
            // ---- Archive screen ----
            // Shown in place of the home grid when "Archived notes" is picked from the overflow
            // menu. Opening a note from here sets viewingId, which the `viewingNote` branch above
            // handles (it looks in archivedNotes too), so tapping an archived note shows its detail
            // page; backing out returns here because showArchive is still true. Delete delegates to
            // the same move-to-trash dialog the home grid uses.
            ArchivedNotesScreen(
                onBack = { showArchive = false },
                onOpen = { note -> viewingId = note.id },
                onDeleteRequest = { note -> noteToDelete = note }
            )
        }

        showTrash -> {
            // ---- Trash screen ----
            TrashNotesScreen(onBack = { showTrash = false })
        }

        showSearch -> {
            // ---- Unified search ----
            // Notes *and* tasks in one pass, archived/completed/trashed included. A task result routes
            // through AppNavigation, so tapping it actually switches tabs and opens the task — a
            // result you can't open isn't a result, and a toast explaining where it lives instead
            // would just be the app describing the destination rather than going there.
            SearchScreen(
                onOpenNote = { note ->
                    showSearch = false
                    viewingId = note.id
                    returnToOnClose = null
                },
                // A task lives on the other tab: record that the jump started here and leave this
                // search open behind it, so closing the task returns to these results (task 4).
                onOpenTask = { task -> AppNavigation.openTask(task.id, from = Screen.Notes) },
                onBack = { showSearch = false }
            )
        }

        else -> {
            // ---- Clean home grid ----
            // When the user is neither searching nor date-filtering, the list is split into
            // Recent / Today / Older sections; otherwise it's a flat result list. Sectioning search
            // results would be noise — you asked for matches, not a timeline of them.
            val browsing = searchText.isBlank() && dateRange == null
            val now = remember(sortedNotes) { System.currentTimeMillis() }
            val sections = remember(sortedNotes, noteUsage, browsing, now) {
                if (!browsing) null else sectionHomeItems(
                    items = sortedNotes,
                    now = now,
                    maxRecent = 6,
                    id = { it.id },
                    timestamp = { it.updatedAt },
                    activityScore = { com.lucent.app.data.UsageTracker.score(noteUsage[it.id] ?: 0.0, it.updatedAt, now) }
                )
            }
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                if (selectionMode) {
                    // A dedicated action bar takes over the top row while selecting: a live count,
                    // a way out, and batch delete for the current selection.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { exitSelection() }) {
                            Icon(Icons.Default.Close, contentDescription = com.lucent.app.i18n.S.a11yCancelSelection, tint = onGradient)
                        }
                        Text(
                            "${selectedNoteIds.size} selected",
                            color = onGradient,
                            fontSize = 18.sp,
                            modifier = Modifier.weight(1f)
                        )
                        // Select-all / clear-all toggle for the notes currently in view.
                        TextButton(onClick = {
                            val allIds = sortedNotes.map { it.id }.toSet()
                            selectedNoteIds = if (selectedNoteIds.containsAll(allIds)) emptySet() else allIds
                        }) {
                            Text(if (selectedNoteIds.containsAll(sortedNotes.map { it.id }.toSet()) && sortedNotes.isNotEmpty()) com.lucent.app.i18n.S.clearAllSelection else com.lucent.app.i18n.S.selectAll)
                        }
                        IconButton(
                            onClick = { if (selectedNoteIds.isNotEmpty()) showBatchDeleteConfirm = true },
                            enabled = selectedNoteIds.isNotEmpty()
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = com.lucent.app.i18n.S.a11yDeleteSelected, tint = onGradient)
                        }
                    }
                } else {
                    // Header with a collapsible action cluster (settings task 16), synthesized with the
                    // tasks/notes header changes: the "+" is always visible; the date-range filter, sort
                    // and overflow buttons hide behind the "<" chevron by default and slide out to the
                    // left when tapped while the search box smoothly resizes to make room. The search
                    // field carries no placeholder text at all (tasks items 6 & 15), so nothing can ever
                    // be clipped on a narrow screen — the leading icon says what the field is for.
                    CollapsibleActionBar(
                        expanded = actionsExpanded,
                        onToggleExpanded = { actionsExpanded = !actionsExpanded },
                        search = {
                            OutlinedTextField(
                                value = searchText,
                                onValueChange = { searchText = it },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = com.lucent.app.i18n.S.a11ySearchNotes) },
                                trailingIcon = { SearchHelpButton() },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        actions = {
                            // Tap to pick a start and end date; the grid then shows only notes in that range.
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
                                options = NoteSort.entries.toList(),
                                label = { it.label },
                                onSelect = { option -> scope.launch { settingsRepo.setNotesSort(option.key) } },
                                tint = onGradientMuted,
                                activeTint = onGradient
                            )
                            // Archive, Trash and Select live behind one overflow menu, so this bar
                            // doesn't grow an icon every time another action is added.
                            Box {
                                IconButton(onClick = { showOverflowMenu = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = com.lucent.app.i18n.S.a11yMoreOptions, tint = onGradientMuted)
                                }
                                DropdownMenu(expanded = showOverflowMenu, onDismissRequest = { showOverflowMenu = false }) {
                                    // Global search is intentionally NOT here on the desktop: the
                                    // sidebar already has a dedicated Search destination, so repeating
                                    // it in this menu would be redundant. Only Archive and Trash remain.
                                    DropdownMenuItem(
                                        text = { Text(com.lucent.app.i18n.S.screenArchivedNotes) },
                                        leadingIcon = { Icon(Icons.Default.Inventory2, contentDescription = null) },
                                        onClick = { showOverflowMenu = false; showArchive = true }
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
                            NewItemButton(contentDescription = com.lucent.app.i18n.S.newNote, onClick = { startCreate() })
                        }
                    )

                    // Only shown while a date filter is active, so it never adds clutter otherwise.
                    dateRange?.let { (start, end) ->
                        Spacer(modifier = Modifier.height(8.dp))
                        DateFilterChip(startMillis = start, endMillis = end, onClear = { dateRange = null })
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Notes render as a two-column card grid, and the grid now fills everything below
                // the header even when it has little or nothing to show (task 5).
                //
                // A scrollable container only hears drags inside its own bounds. Wrapping its height
                // meant the blank space under the last row — or the entire page, with no notes at
                // all — swallowed every gesture, including the one that raises the "Notes" title back
                // into view after it has been scrolled away. Filling the area fixes both: the empty
                // state now rides *inside* the grid as a full-width item rather than replacing it.
                // Wrapped in a Box so the scroll-edge jump buttons can overlay the grid (task E2).
                Box(modifier = Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    state = gridState,
                    // Four across on the desktop's wide window (Android stays at two). A large monitor
                    // has the room, and four cards a row reads as a proper board rather than a phone
                    // list stretched sideways.
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.fillMaxSize().hazeSource(state = LocalHazeState.current),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    // Reserve the floating capsule's height so the last note clears the pill; the
                    // grid still extends under the capsule, which is what its blur samples.
                    contentPadding = PaddingValues(bottom = LocalBottomBarInset.current)
                ) {
                    if (sortedNotes.isEmpty()) {
                        item(key = "empty_state", span = { GridItemSpan(maxLineSpan) }) {
                            EmptyState(
                                isFiltered = searchText.isNotBlank() || dateRange != null,
                                emptyMessage = com.lucent.app.i18n.S.emptyNotesHint,
                                noMatchMessage = com.lucent.app.i18n.S.noNotesMatchSearch
                            )
                        }
                    } else {
                        val renderCard: @Composable (Note) -> Unit = { note ->
                            NoteCard(
                                note = note,
                                selectionMode = selectionMode,
                                selected = note.id in selectedNoteIds,
                                onOpen = { openDetail(note) },
                                onLongPress = { selectionMode = true; selectedNoteIds = setOf(note.id) },
                                onToggleSelect = {
                                    selectedNoteIds = if (note.id in selectedNoteIds) selectedNoteIds - note.id else selectedNoteIds + note.id
                                },
                                onDelete = { noteToDelete = note }
                            )
                        }
                        if (sections != null) {
                            sections.nonEmpty().forEach { (section, list) ->
                                item(key = "header_${section.name}", span = { GridItemSpan(maxLineSpan) }) {
                                    HomeSectionHeader(section.label)
                                }
                                items(list, key = { it.id }) { note -> renderCard(note) }
                            }
                        } else {
                            items(sortedNotes, key = { it.id }) { note -> renderCard(note) }
                        }
                    }
                }
                ScrollEdgeJumpButtons(
                    canUp = gridState.canScrollBackward,
                    canDown = gridState.canScrollForward,
                    tint = onGradient,
                    onUp = { scope.launch { gridState.animateScrollToItem(0) } },
                    onDown = { scope.launch { gridState.animateScrollToItem((gridState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)) } },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = LocalBottomBarInset.current + 14.dp)
                )
                }
            }
        }
    }
}

/** Plain-text rendering of a note for the OS share sheet. */
private fun shareTextForNote(note: Note): String {
    val body = if (note.isChecklist) Checklist.toMarkdown(note.checklist) else note.body
    return if (note.title.isBlank()) body else "${note.title}\n\n$body"
}

/**
 * What a long-press on the detail page copies: the note's CONTENT and nothing else.
 *
 * Deliberately not [shareTextForNote]. Sharing sends someone else a note, so it leads with the
 * title for context. Copying is almost always a step in moving the text somewhere you are already
 * working — a message, a document, another note — and there the title and the timestamp are
 * exactly the two things you would have to delete by hand afterwards. So they are not included.
 *
 * A checklist note copies as its items (one per line, ticked state preserved) because that IS its
 * content; a prose note copies as its body, verbatim, with no Markdown added or stripped.
 */
private fun copyTextForNote(note: Note): String =
    if (note.isChecklist) Checklist.toMarkdown(note.checklist).trim() else note.body.trim()

/**
 * A single note as a card in the two-per-row home grid.
 *
 * Shows the title, the date, and a short preview of the body — or, for a checklist note, its first
 * few items, because "three of five ticked" is the thing you actually want to know at a glance.
 * A pin marker sits beside the title when pinned, the card itself is tinted with the note's colour,
 * and a low-emphasis delete button in the corner routes through the same move-to-trash dialog as
 * everywhere else. The fixed height keeps the grid tidy however much text each note holds.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun NoteCard(
    note: Note,
    selectionMode: Boolean,
    selected: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onToggleSelect: () -> Unit,
    onDelete: () -> Unit
) {
    val onGradient = LocalOnGradient.current
    val onGradientMuted = LocalOnGradientMuted.current
    val selShape = RoundedCornerShape(20.dp)
    // Cache the formatted timestamp keyed on the raw value (settings task 8). formatTimestamp
    // allocates a Date + formatter; recomputing it on every recomposition (a scroll can trigger
    // many) is pure waste when the underlying updatedAt hasn't changed. Keyed so an edit refreshes.
    val formattedTimestamp = remember(note.updatedAt) { formatTimestamp(note.updatedAt) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(172.dp)
            .frostedGlass(tint = NoteColor.fromKey(note.color).swatch)
            // A long press enters multi-select and grabs this card; once in select mode a tap toggles
            // this card instead of opening it. Outside select mode a tap opens as before.
            .combinedClickable(
                onClick = { if (selectionMode) onToggleSelect() else onOpen() },
                onLongClick = { if (!selectionMode) onLongPress() }
            )
            .then(
                if (selected) Modifier.border(2.dp, onGradient, selShape) else Modifier
            )
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Row(modifier = Modifier.weight(1f)) {
                if (note.pinned) {
                    PinnedMarker(modifier = Modifier.padding(top = 2.dp, end = 4.dp))
                }
                Text(
                    note.title.ifBlank { com.lucent.app.i18n.S.untitled },
                    color = onGradient,
                    fontSize = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (selectionMode) {
                // Selection tick replaces the delete affordance while choosing.
                Icon(
                    if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = if (selected) com.lucent.app.i18n.S.a11ySelected else com.lucent.app.i18n.S.a11yNotSelected,
                    tint = if (selected) onGradient else onGradientMuted,
                    modifier = Modifier.padding(start = 6.dp).size(20.dp)
                )
            } else {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = com.lucent.app.i18n.S.actionDelete,
                    tint = onGradientMuted,
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .size(18.dp)
                        .clickable { onDelete() }
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            formattedTimestamp,
            color = onGradientMuted,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        if (note.isChecklist) {
            val items = remember(note.checklist) { Checklist.parse(note.checklist) }
            if (items.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                ChecklistPreviewInline(items = items, modifier = Modifier.weight(1f), maxVisible = 3)
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
        } else if (note.body.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                note.body,
                color = onGradientMuted,
                fontSize = 13.sp,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }

        if (note.tags.isNotBlank()) {
            Text(
                note.tags.split(",").joinToString(" · "),
                color = onGradientMuted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** A full-width section header ("Recent" / "Today" / "Older") between rows of the home grid. */
@Composable
private fun HomeSectionHeader(label: String) {
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

/** The glyph for a template chip. Kept here so `data/NoteTemplates.kt` stays free of Compose types. */
@Composable
private fun templateIcon(template: NoteTemplate) = when (template.iconName) {
    com.lucent.app.data.TemplateIcon.JOURNAL -> Icons.Default.Book
    com.lucent.app.data.TemplateIcon.MEETING -> Icons.Default.Groups
    com.lucent.app.data.TemplateIcon.IDEA -> Icons.Default.Lightbulb
    com.lucent.app.data.TemplateIcon.CHECKLIST -> Icons.AutoMirrored.Filled.FormatListBulleted
}
