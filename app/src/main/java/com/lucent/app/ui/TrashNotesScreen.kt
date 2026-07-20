package com.lucent.app.ui

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
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lucent.app.AppScope
import com.lucent.app.data.AppDatabase
import com.lucent.app.data.Checklist
import com.lucent.app.data.Note
import com.lucent.app.data.TrashCleanup
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.launch

/**
 * Notes the user deleted, held for [TrashCleanup.RETENTION_DAYS] days before they're removed for
 * good. Reached from the overflow menu on the Notes screen.
 *
 * ### Why a Trash at all
 *
 * The old delete button destroyed a note, its attachments, and (now) its history, immediately and
 * irreversibly, behind a single confirmation dialog. On a phone. With a thumb. That is a design that
 * *works* right up until the one time it doesn't, and then the note is simply gone — no undo, no
 * file on disk, nothing to recover, because a local-first app has no server copy to fall back on.
 * A safety net is not a luxury here; it's the thing that has to replace the one everyone else gets
 * from the cloud.
 *
 * ### Why this screen doesn't open a detail page
 *
 * Unlike [ArchivedNotesScreen], tapping a trashed note doesn't take you anywhere. A note in the bin
 * isn't something you read or edit — it's something you either want back or want gone — so both
 * actions live directly on the card and there's nothing to hand off to [NotesScreen] for. It's also
 * the one place in the app where the words "permanently deleted, can't be undone" are finally true,
 * and they're kept for exactly here so they still mean something.
 */
@Composable
fun TrashNotesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getInstance(context) }
    val trashed by db.noteDao().getTrashed().collectAsState(initial = emptyList())
    val onGradient = LocalOnGradient.current
    val onGradientMuted = LocalOnGradientMuted.current
    val hazeState = LocalHazeState.current

    var searchQuery by remember { mutableStateOf("") }
    var noteToPurge by remember { mutableStateOf<Note?>(null) }
    // Restoring out of the trash is confirmed too (task 7). It is not destructive, but it *moves*
    // the note back into the main list where the user will next go looking for it — and the restore
    // and delete-forever icons sit side by side on every card, which is exactly the arrangement
    // where an unconfirmed tap is most likely to be the wrong one.
    var noteToRestore by remember { mutableStateOf<Note?>(null) }

    noteToRestore?.let { note ->
        AlertDialog(
            onDismissRequest = { noteToRestore = null },
            title = { Text(com.lucent.app.i18n.S.restoreNoteTitle) },
            text = { Text(com.lucent.app.i18n.S.restoreNoteBody(note.title.ifBlank { com.lucent.app.i18n.S.untitledNote })) },
            confirmButton = {
                TextButton(onClick = {
                    val target = note
                    noteToRestore = null
                    AppScope.io.launch { db.noteDao().update(target.copy(trashedAt = null)) }
                }) { Text(com.lucent.app.i18n.S.actionRestore) }
            },
            dismissButton = { TextButton(onClick = { noteToRestore = null }) { Text(com.lucent.app.i18n.S.actionCancel) } }
        )
    }
    var confirmEmptyTrash by remember { mutableStateOf(false) }

    // Purging runs on the app-lifetime scope, not this screen's: emptying the trash disposes the
    // list this composable is collecting, and a purge cancelled halfway would delete a note's files
    // without deleting its row.
    fun purge(note: Note) {
        AppScope.io.launch { TrashCleanup.purgeNote(context, db, note) }
    }

    noteToPurge?.let { note ->
        AlertDialog(
            onDismissRequest = { noteToPurge = null },
            title = { Text(com.lucent.app.i18n.S.deleteForeverTitle) },
            text = { Text(com.lucent.app.i18n.S.deleteNoteForeverBody(note.title.ifBlank { com.lucent.app.i18n.S.untitledNote })) },
            confirmButton = {
                TextButton(onClick = { purge(note); noteToPurge = null }) { Text(com.lucent.app.i18n.S.deleteForever) }
            },
            dismissButton = { TextButton(onClick = { noteToPurge = null }) { Text(com.lucent.app.i18n.S.actionCancel) } }
        )
    }

    if (confirmEmptyTrash) {
        AlertDialog(
            onDismissRequest = { confirmEmptyTrash = false },
            title = { Text(com.lucent.app.i18n.S.emptyTrashTitle) },
            text = { Text(if (trashed.size == 1) com.lucent.app.i18n.S.emptyTrashNotesBodyOne else com.lucent.app.i18n.S.emptyTrashNotesBody(trashed.size)) },
            confirmButton = {
                TextButton(onClick = {
                    val toPurge = trashed.toList()
                    confirmEmptyTrash = false
                    AppScope.io.launch { toPurge.forEach { TrashCleanup.purgeNote(context, db, it) } }
                }) { Text(com.lucent.app.i18n.S.emptyTrash) }
            },
            dismissButton = { TextButton(onClick = { confirmEmptyTrash = false }) { Text(com.lucent.app.i18n.S.actionCancel) } }
        )
    }

    val filtered = remember(trashed, searchQuery) {
        trashed.filter { note ->
            searchQuery.isBlank() ||
                note.title.contains(searchQuery, ignoreCase = true) ||
                note.body.contains(searchQuery, ignoreCase = true)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = com.lucent.app.i18n.S.actionBack, tint = onGradient)
            }
            Text(com.lucent.app.i18n.S.screenTrash, color = onGradient, fontSize = 20.sp, modifier = Modifier.weight(1f))
            if (trashed.isNotEmpty()) {
                TextButton(onClick = { confirmEmptyTrash = true }) { Text(com.lucent.app.i18n.S.emptyTrash) }
            }
        }

        Text(
            com.lucent.app.i18n.S.trashRetention(TrashCleanup.RETENTION_DAYS),
            color = onGradientMuted,
            fontSize = 12.sp
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text(com.lucent.app.i18n.S.searchTrash) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (filtered.isEmpty()) {
            EmptyState(
                isFiltered = searchQuery.isNotBlank(),
                emptyMessage = com.lucent.app.i18n.S.trashEmpty,
                noMatchMessage = com.lucent.app.i18n.S.noTrashedNotesMatch
            )
            return
        }

        LazyColumn(
            modifier = Modifier.hazeSource(state = hazeState),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            // Reserve the floating capsule's height so the last row clears the pill.
            contentPadding = PaddingValues(bottom = LocalBottomBarInset.current)
        ) {
            items(filtered, key = { it.id }) { note ->
                TrashedNoteCard(
                    note = note,
                    onRestore = { noteToRestore = note },
                    onPurge = { noteToPurge = note },
                    onGradient = onGradient,
                    onGradientMuted = onGradientMuted
                )
            }
        }
    }
}

@Composable
private fun TrashedNoteCard(
    note: Note,
    onRestore: () -> Unit,
    onPurge: () -> Unit,
    onGradient: Color,
    onGradientMuted: Color
) {
    val preview = remember(note) {
        if (note.isChecklist) {
            val items = Checklist.parse(note.checklist)
            if (items.isEmpty()) "(empty checklist)" else com.lucent.app.i18n.S.checklistDoneCount(items.count { it.done }, items.size)
        } else {
            note.body
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .frostedGlass(tint = NoteColor.fromKey(note.color).swatch)
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
                    Text(
                        note.title.ifBlank { com.lucent.app.i18n.S.untitledNote },
                        color = onGradient,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                val stamp = note.trashedAt ?: note.updatedAt
                Text(
                    com.lucent.app.i18n.S.trashedOn(formatTimestamp(stamp)) + if (note.archived) " · was archived" else "",
                    color = onGradientMuted,
                    fontSize = 12.sp
                )
            }
            IconButton(onClick = onRestore) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = com.lucent.app.i18n.S.actionRestore, tint = onGradient)
            }
            IconButton(onClick = onPurge) {
                Icon(Icons.Default.Delete, contentDescription = com.lucent.app.i18n.S.a11yDeleteForever, tint = onGradient)
            }
        }
        if (preview.isNotBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(preview, color = onGradientMuted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
