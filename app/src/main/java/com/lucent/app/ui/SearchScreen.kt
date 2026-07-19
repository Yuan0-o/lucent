package com.lucent.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lucent.app.data.AppDatabase
import com.lucent.app.data.Checklist
import com.lucent.app.data.Note
import com.lucent.app.data.SearchQuery
import com.lucent.app.data.Task
import com.lucent.app.data.TaskPriority
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.delay

/**
 * How many candidate rows SQLite hands back per kind before Kotlin refines them. Far past what
 * anyone scrolls; it exists so a pathological query can't materialise an unbounded list.
 */
private const val CANDIDATE_LIMIT = 300

/** How many refined results are actually rendered. */
private const val RESULT_LIMIT = 100

/**
 * Search everything, from one place.
 *
 * ### Why this exists
 *
 * Search used to live *inside* each list and could only see what that list already held. The Notes
 * screen filtered the notes it had in memory; the Tasks screen filtered its active tasks; neither
 * could see the other, or anything archived, completed, or trashed. That is fine at ten notes and
 * useless at a thousand — and it fails hardest at exactly the moment search matters most, when you
 * half-remember something and don't know where you put it.
 *
 * This screen looks at **every note and every task there is**, whatever state it's in, and says so
 * on each result. A note you archived in March, a task you finished last week, something you deleted
 * on Tuesday and now want back: all findable, all labelled, all one tap from being open.
 *
 * ### How it runs
 *
 * SQLite does the coarse pass — the text `LIKE` and the structural filters — so the whole database is
 * never dragged into memory to be thrown away. Kotlin then applies what SQL can't express (quoted
 * phrases, `has:`, `link:`, and relevance ranking) to the small candidate set that comes back. See
 * [SearchQuery] for why this is substring matching and not a full-text index — the short version is
 * that no built-in FTS tokeniser segments Chinese, so an index would quietly stop finding CJK notes.
 */
@Composable
fun SearchScreen(
    onOpenNote: (Note) -> Unit,
    onOpenTask: (Task) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getInstance(context) }
    val onGradient = LocalOnGradient.current
    val onGradientMuted = LocalOnGradientMuted.current
    val hazeState = LocalHazeState.current

    var raw by remember { mutableStateOf("") }
    var noteResults by remember { mutableStateOf<List<Note>>(emptyList()) }
    var taskResults by remember { mutableStateOf<List<Task>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }

    BackHandler(enabled = true) { onBack() }

    val query = remember(raw) { SearchQuery.parse(raw) }

    // Debounced, so a fast typist doesn't fire a database query per keystroke. LaunchedEffect cancels
    // the previous delay whenever the query changes, so only the pause after the *last* keystroke
    // actually reaches SQLite. 180ms is below where a search box starts to feel laggy and comfortably
    // above the cost of the queries themselves.
    LaunchedEffect(query) {
        if (query.isEmpty) {
            noteResults = emptyList()
            taskResults = emptyList()
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(180)

        val now = System.currentTimeMillis()

        // A filter only one kind of item can satisfy suppresses the other kind entirely. Searching
        // `is:overdue` and getting back every note containing the word is not a smaller kind of
        // right — it's wrong, and it teaches people not to trust the search box.
        noteResults = if (query.isTaskOnly) {
            emptyList()
        } else {
            db.noteDao()
                .searchNotes(
                    text = query.sqlText,
                    tag = query.sqlTag,
                    archived = query.sqlArchived,
                    trashed = query.sqlTrashed,
                    limit = CANDIDATE_LIMIT
                )
                // SQL narrowed; Kotlin now enforces everything SQL can't say, and ranks what's left.
                .filter { query.matches(it) }
                .sortedWith(compareByDescending<Note> { query.rank(it) }.thenByDescending { it.updatedAt })
                .take(RESULT_LIMIT)
        }

        taskResults = if (query.isNoteOnly) {
            emptyList()
        } else {
            db.taskDao()
                .searchTasks(
                    text = query.sqlText,
                    done = query.sqlDone,
                    trashed = query.sqlTrashed,
                    minPriority = query.sqlMinPriority,
                    dueBefore = query.sqlDueBefore(now),
                    dueAfter = query.sqlDueAfter(now),
                    limit = CANDIDATE_LIMIT
                )
                .filter { query.matches(it, now) }
                .sortedWith(compareByDescending<Task> { query.rank(it) }.thenByDescending { it.createdAt })
                .take(RESULT_LIMIT)
        }
        searching = false
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = onGradient)
            }
            Text("Search everything", color = onGradient, fontSize = 20.sp, modifier = Modifier.weight(1f))
            SearchHelpButton()
        }
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = raw,
            onValueChange = { raw = it },
            placeholder = { Text("Notes, tasks, tags, checklists…") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = onGradientMuted) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(10.dp))

        // The operator chips double as toggles: tapping an inactive one adds it to the query, and
        // tapping an *active* one removes it again — the same tap-to-select/tap-to-deselect
        // behaviour any chip should have. Previously each tap blindly appended the token, so a
        // second tap gave you "is:pinned is:pinned" instead of clearing it. The chip is shown as
        // selected while its token is present, so it always reflects the live query.
        val activeTokens = remember(raw) { raw.split(Regex("\\s+")).filter { it.isNotBlank() }.toSet() }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SearchQuery.HINTS.forEach { token ->
                FilterChip(
                    selected = token in activeTokens,
                    onClick = { raw = toggleSearchToken(raw, token) },
                    label = { Text(token, fontSize = 12.sp) }
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        val total = noteResults.size + taskResults.size
        when {
            query.isEmpty -> EmptyState(
                isFiltered = false,
                emptyMessage = "Search across every note and task — including archived, completed, " +
                    "and trashed ones. Tap a filter above to narrow it down.",
                noMatchMessage = ""
            )

            searching -> EmptyState(
                isFiltered = false,
                emptyMessage = "Searching…",
                noMatchMessage = ""
            )

            total == 0 -> EmptyState(
                isFiltered = true,
                emptyMessage = "",
                noMatchMessage = "Nothing matched \"$raw\"."
            )

            else -> {
                Text(
                    "$total result${if (total == 1) "" else "s"}",
                    color = onGradientMuted,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier.hazeSource(state = hazeState),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (noteResults.isNotEmpty()) {
                        item(key = "notes_header") {
                            SectionHeader("Notes", noteResults.size, Icons.AutoMirrored.Filled.Notes)
                        }
                        items(noteResults, key = { "n${it.id}" }) { note ->
                            NoteResultRow(note = note, onOpen = { onOpenNote(note) })
                        }
                    }
                    if (taskResults.isNotEmpty()) {
                        item(key = "tasks_header") {
                            SectionHeader("Tasks", taskResults.size, Icons.Default.CheckCircle)
                        }
                        items(taskResults, key = { "t${it.id}" }) { task ->
                            TaskResultRow(task = task, onOpen = { onOpenTask(task) })
                        }
                    }
                }
            }
        }
    }
}

/**
 * Add [token] to the query if it isn't already a standalone token, or remove it if it is — the
 * toggle behaviour behind the operator chips. Whitespace-delimited, so it acts on whole operators
 * (`is:pinned`, `has:due`); a value-taking prefix like `tag:` no longer stands alone once you've
 * typed a value after it, and simply appends again, which is the sensible behaviour there. Always
 * leaves a trailing space so the user can keep typing.
 */
internal fun toggleSearchToken(raw: String, token: String): String {
    val parts = raw.split(Regex("\\s+")).filter { it.isNotBlank() }.toMutableList()
    val idx = parts.indexOf(token)
    if (idx >= 0) {
        parts.removeAt(idx)
    } else {
        parts.add(token)
    }
    return if (parts.isEmpty()) "" else parts.joinToString(" ") + " "
}

@Composable
private fun SectionHeader(label: String, count: Int, icon: ImageVector) {
    val onGradientMuted = LocalOnGradientMuted.current
    Row(
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = onGradientMuted, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text("$label · $count", color = onGradientMuted, fontSize = 12.sp)
    }
}

/**
 * A note result, annotated with whatever state would otherwise be surprising.
 *
 * The "Archived" / "In trash" label is not decoration. This screen deliberately surfaces things the
 * user cannot see anywhere else, and tapping a result that silently turns out to be in the bin —
 * with no warning — is exactly the sort of small betrayal that makes an app feel untrustworthy.
 */
@Composable
private fun NoteResultRow(note: Note, onOpen: () -> Unit) {
    val onGradient = LocalOnGradient.current
    val onGradientMuted = LocalOnGradientMuted.current

    val preview = remember(note.body, note.checklist, note.isChecklist) {
        if (note.isChecklist) Checklist.parse(note.checklist).joinToString(" · ") { it.text }
        else note.body
    }
    val state = when {
        note.trashedAt != null -> "In trash"
        note.archived -> "Archived"
        else -> null
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .frostedGlass(tint = NoteColor.fromKey(note.color).swatch)
            .clickable { onOpen() }
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (note.pinned) {
                PinnedMarker(modifier = Modifier.padding(end = 4.dp))
            }
            NoteColorDot(note.color)
            if (NoteColor.fromKey(note.color) != NoteColor.DEFAULT) Spacer(modifier = Modifier.width(6.dp))
            Text(
                note.title.ifBlank { "Untitled" },
                color = onGradient,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (state != null) {
                Text(state, color = onGradientMuted, fontSize = 11.sp)
            }
        }
        if (preview.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(preview, color = onGradientMuted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (note.tags.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
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

/** A task result, showing the state that made it findable — done, due, priority, progress. */
@Composable
private fun TaskResultRow(task: Task, onOpen: () -> Unit) {
    val onGradient = LocalOnGradient.current
    val onGradientMuted = LocalOnGradientMuted.current

    val priority = remember(task.priority) { TaskPriority.fromValue(task.priority) }
    val progress = remember(task.subtasks) { Checklist.progress(task.subtasks) }
    val state = when {
        task.trashedAt != null -> "In trash"
        task.isDone -> "Completed"
        else -> null
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .frostedGlass()
            .clickable { onOpen() }
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (task.pinned) {
                PinnedMarker(modifier = Modifier.padding(end = 4.dp))
            }
            PriorityDot(priority)
            if (priority != TaskPriority.NONE) Spacer(modifier = Modifier.width(6.dp))
            Text(
                task.title.ifBlank { "Untitled task" },
                color = onGradient,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (state != null) {
                Text(state, color = onGradientMuted, fontSize = 11.sp)
            }
        }
        task.dueAt?.let { due ->
            val overdue = isOverdue(due, task.isDone)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                if (overdue || task.isDone) friendlyDue(due) else "Due ${friendlyDue(due)}",
                color = if (overdue) OverdueColor else onGradientMuted,
                fontSize = 12.sp
            )
        }
        progress?.let { (done, totalItems) ->
            Spacer(modifier = Modifier.height(4.dp))
            Text("$done/$totalItems subtasks done", color = onGradientMuted, fontSize = 11.sp)
        }
        if (task.notes.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(task.notes, color = onGradientMuted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
