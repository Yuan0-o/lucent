package com.lucent.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lucent.app.data.AppDatabase
import com.lucent.app.data.Checklist
import com.lucent.app.data.Note
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.launch

/**
 * How the archive groups its notes. TIME is a single, flat most-recently-archived-first list (the
 * default). TAG groups notes under each of their tags, and the notes within every tag group are
 * themselves ordered by archive time (newest first). A note with several tags appears once under
 * each of its tags; an untagged note is collected under a synthetic "Untagged" heading so nothing
 * is ever hidden.
 */
private enum class ArchiveGrouping { TIME, TAG }

// Sentinel key for the "no tags" bucket; its visible label is localized at the display site.
private const val UNTAGGED_KEY = "\u0000untagged"

/**
 * The dedicated archive screen for notes. Reached from the archive icon in the Notes header.
 *
 * Only archived notes appear here — the Notes home page queries `noteDao().getAll()` (WHERE
 * archived = 0), so the moment a note is archived it leaves the home list and shows up here
 * instead. Mirrors the Completed-tasks page in look and structure (back button, search box,
 * frosted cards, empty state) so the app feels consistent, but instead of a date filter it offers
 * a Time / Tag grouping toggle, defaulting to Time.
 *
 * Owned by [NotesScreen]: opening a note's detail and deleting a note both delegate back to the
 * parent so the one detail-page and the one delete-confirmation dialog live in a single place. The
 * action this screen implements directly is "unarchive" (restore), which clears the archive flag
 * and sends the note back to the home list — the mirror of the undo-complete button on the
 * completed-tasks page.
 */
@Composable
fun ArchivedNotesScreen(
    onBack: () -> Unit,
    onOpen: (Note) -> Unit,
    onDeleteRequest: (Note) -> Unit,
) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getInstance(context) }
    val scope = rememberCoroutineScope()
    val archived by db.noteDao().getArchived().collectAsState(initial = emptyList())
    val onGradient = LocalOnGradient.current
    val onGradientMuted = LocalOnGradientMuted.current
    val hazeState = LocalHazeState.current

    var searchQuery by remember { mutableStateOf("") }
    var grouping by remember { mutableStateOf(ArchiveGrouping.TIME) }
    // The note whose restore is awaiting confirmation (task 7). Nothing is written until Confirm.
    var noteToRestore by remember { mutableStateOf<Note?>(null) }

    noteToRestore?.let { note ->
        AlertDialog(
            onDismissRequest = { noteToRestore = null },
            title = { Text(com.lucent.app.i18n.S.restoreNoteTitle) },
            text = {
                Text(com.lucent.app.i18n.S.restoreNoteArchiveBody(note.title.ifBlank { com.lucent.app.i18n.S.untitledNote }))
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = note
                    noteToRestore = null
                    scope.launch { db.noteDao().update(target.copy(archived = false, archivedAt = null)) }
                }) { Text(com.lucent.app.i18n.S.actionRestore) }
            },
            dismissButton = { TextButton(onClick = { noteToRestore = null }) { Text(com.lucent.app.i18n.S.actionCancel) } }
        )
    }

    // Text search matches the title, body, or any tag — the same fields the home search covers.
    val filtered = remember(archived, searchQuery) {
        archived.filter { note ->
            searchQuery.isBlank() ||
                note.title.contains(searchQuery, ignoreCase = true) ||
                note.body.contains(searchQuery, ignoreCase = true) ||
                note.tags.contains(searchQuery, ignoreCase = true)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = com.lucent.app.i18n.S.actionBack, tint = onGradient)
            }
            Text(com.lucent.app.i18n.S.screenArchivedNotes, color = onGradient, fontSize = 20.sp, modifier = Modifier.weight(1f))
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text(com.lucent.app.i18n.S.searchArchive) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Grouping toggle: Time (flat, newest first) or Tag (grouped by tag). Time is the default.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(com.lucent.app.i18n.S.groupBy, color = onGradientMuted, fontSize = 13.sp)
            Spacer(modifier = Modifier.width(8.dp))
            FilterChip(
                selected = grouping == ArchiveGrouping.TIME,
                onClick = { grouping = ArchiveGrouping.TIME },
                label = { Text(com.lucent.app.i18n.S.filterTime) }
            )
            Spacer(modifier = Modifier.width(8.dp))
            FilterChip(
                selected = grouping == ArchiveGrouping.TAG,
                onClick = { grouping = ArchiveGrouping.TAG },
                label = { Text(com.lucent.app.i18n.S.filterTag) }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (filtered.isEmpty()) {
            Column(modifier = Modifier.fillMaxWidth().frostedGlass().padding(24.dp)) {
                Text(
                    if (archived.isEmpty()) com.lucent.app.i18n.S.archivedEmpty
                    else com.lucent.app.i18n.S.archivedNoMatch,
                    color = onGradientMuted
                )
            }
            return
        }

        // Restoring asks first (task 7): it moves the note off this page and back into the home
        // grid, so a mis-tap on a small icon silently rearranges two lists. The state is declared
        // here beside its only use; the dialog itself is rendered further down.
        val restore: (Note) -> Unit = { note -> noteToRestore = note }

        when (grouping) {
            ArchiveGrouping.TIME -> {
                // A single flat list; `filtered` is already archive-time ordered by the DAO query.
                LazyColumn(
                    modifier = Modifier.hazeSource(state = hazeState),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    // Reserve the floating capsule's height so the last row clears the pill.
                    contentPadding = PaddingValues(bottom = LocalBottomBarInset.current)
                ) {
                    items(filtered, key = { it.id }) { note ->
                        ArchivedNoteCard(
                            note = note,
                            onOpen = { onOpen(note) },
                            onRestore = { restore(note) },
                            onDelete = { onDeleteRequest(note) },
                            onGradient = onGradient,
                            onGradientMuted = onGradientMuted
                        )
                    }
                }
            }
            ArchiveGrouping.TAG -> {
                // Build tag -> notes, preserving the archive-time order within each tag (filtered is
                // already sorted, so appending in-order keeps each group sorted). Tags are shown
                // alphabetically, with the synthetic "Untagged" group last.
                val groups = remember(filtered) { buildTagGroups(filtered) }
                LazyColumn(
                    modifier = Modifier.hazeSource(state = hazeState),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    // Reserve the floating capsule's height so the last row clears the pill.
                    contentPadding = PaddingValues(bottom = LocalBottomBarInset.current)
                ) {
                    groups.forEach { (tag, notesForTag) ->
                        item(key = "header_$tag") {
                            Text(
                                if (tag == UNTAGGED_KEY) com.lucent.app.i18n.S.untaggedLabel else tag,
                                color = onGradient,
                                fontSize = 15.sp,
                                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                            )
                        }
                        items(notesForTag, key = { "${tag}_${it.id}" }) { note ->
                            ArchivedNoteCard(
                                note = note,
                                onOpen = { onOpen(note) },
                                onRestore = { restore(note) },
                                onDelete = { onDeleteRequest(note) },
                                onGradient = onGradient,
                                onGradientMuted = onGradientMuted
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Groups notes by tag for the Tag view. Returns an ordered list of (tag, notes) pairs: real tags
 * first in alphabetical order, then the "Untagged" bucket last if any untagged notes exist. The
 * incoming [notes] list is assumed already sorted by archive time, so each group keeps that order.
 */
private fun buildTagGroups(notes: List<Note>): List<Pair<String, List<Note>>> {
    val tagged = linkedMapOf<String, MutableList<Note>>()
    val untagged = mutableListOf<Note>()
    for (note in notes) {
        val tags = note.tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (tags.isEmpty()) {
            untagged += note
        } else {
            for (tag in tags) {
                tagged.getOrPut(tag) { mutableListOf() }.add(note)
            }
        }
    }
    val result = tagged.entries
        .sortedBy { it.key.lowercase() }
        .map { it.key to it.value.toList() }
        .toMutableList()
    if (untagged.isNotEmpty()) {
        result += UNTAGGED_KEY to untagged.toList()
    }
    return result
}

/**
 * A single archived-note card: title, when it was archived, a short body preview, and its tags,
 * plus restore (unarchive) and delete actions. Tapping the card opens the note's detail page.
 */
@Composable
private fun ArchivedNoteCard(
    note: Note,
    onOpen: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    onGradient: androidx.compose.ui.graphics.Color,
    onGradientMuted: androidx.compose.ui.graphics.Color,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .frostedGlass()
            .clickable { onOpen() }
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NoteColorDot(note.color)
                    if (NoteColor.fromKey(note.color) != NoteColor.DEFAULT) {
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    if (note.pinned) {
                        PinnedMarker(modifier = Modifier.padding(end = 4.dp))
                    }
                    Text(
                        note.title.ifBlank { com.lucent.app.i18n.S.untitledNote },
                        color = onGradient,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                val stamp = note.archivedAt ?: note.updatedAt
                Text(com.lucent.app.i18n.S.archivedOn(formatTimestamp(stamp)), color = onGradientMuted, fontSize = 12.sp)
            }
            IconButton(onClick = onRestore) {
                Icon(Icons.Filled.Unarchive, contentDescription = com.lucent.app.i18n.S.a11yRestoreToNotes, tint = onGradient)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = com.lucent.app.i18n.S.actionDelete, tint = onGradient)
            }
        }
        // A checklist note has no body to preview, so show its progress instead — "3/5 done" is the
        // only thing worth knowing about a checklist you're not currently looking at.
        val preview = if (note.isChecklist) {
            val items = Checklist.parse(note.checklist)
            if (items.isEmpty()) "" else com.lucent.app.i18n.S.checklistDoneCount(items.count { it.done }, items.size)
        } else {
            note.body
        }
        if (preview.isNotBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                preview,
                color = onGradientMuted,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        val tags = note.tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (tags.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                tags.joinToString("  ") { "#$it" },
                color = onGradientMuted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
