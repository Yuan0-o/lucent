package com.lucent.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lucent.app.AppScope
import com.lucent.app.data.AppDatabase
import com.lucent.app.data.Checklist
import com.lucent.app.data.Note
import com.lucent.app.data.NoteHistory
import com.lucent.app.data.NoteStats
import com.lucent.app.data.NoteVersion
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.launch

/**
 * A note's local revision history: every earlier version of its text, newest first, each one
 * previewable and restorable.
 *
 * ### What this is for
 *
 * The failure it exists to prevent is mundane and unrecoverable: you select all, you type over it,
 * you tap Save, and the paragraph you'd been building for a week is gone. There is no undo across
 * an app restart, no OS-level file history for a row in a SQLite database, and no cloud to fall
 * back on — by design, since nothing here leaves the phone. So the app has to be the thing that
 * remembers, and it has to remember locally.
 *
 * Restoring is itself recorded as an edit (see [NoteHistory.applyTo] and the restore path below),
 * which means restoring is *also* undoable. That matters more than it sounds: a one-way restore
 * that destroys whatever you had before you restored just moves the cliff edge rather than removing
 * it, and someone browsing their own history is by definition already unsure what they want.
 */
@Composable
fun NoteHistoryScreen(
    note: Note,
    onBack: () -> Unit,
    onRestored: () -> Unit
) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getInstance(context) }
    val onGradient = LocalOnGradient.current
    val onGradientMuted = LocalOnGradientMuted.current
    val hazeState = LocalHazeState.current

    val versions by db.noteVersionDao().getForNote(note.id).collectAsState(initial = emptyList())

    var previewing by remember { mutableStateOf<NoteVersion?>(null) }
    var confirmRestore by remember { mutableStateOf<NoteVersion?>(null) }

    BackHandler(enabled = previewing != null) { previewing = null }

    fun restore(version: NoteVersion) {
        AppScope.io.launch {
            // Re-read the live row rather than trusting the copy this screen was composed with: the
            // assistant could have edited the note while the history page sat open, and restoring
            // over a stale snapshot would silently throw that edit away without recording it.
            val current = db.noteDao().getByIdOnce(note.id) ?: return@launch
            val restored = NoteHistory.applyTo(current, version)
            NoteHistory.recordIfChanged(
                db = db,
                existing = current,
                newTitle = restored.title,
                newBody = restored.body,
                newTags = restored.tags,
                newIsChecklist = restored.isChecklist,
                newChecklist = restored.checklist
            )
            db.noteDao().update(restored)
        }
        previewing = null
        confirmRestore = null
        onRestored()
    }

    confirmRestore?.let { version ->
        AlertDialog(
            onDismissRequest = { confirmRestore = null },
            title = { Text("Restore this version?") },
            text = {
                Text(
                    "The note will go back to how it read on ${formatTimestamp(version.savedAt)}. " +
                        "The current text is saved to history first, so you can undo this too."
                )
            },
            confirmButton = { TextButton(onClick = { restore(version) }) { Text("Restore") } },
            dismissButton = { TextButton(onClick = { confirmRestore = null }) { Text("Cancel") } }
        )
    }

    val preview = previewing
    if (preview != null) {
        // ---- Read-only preview of one old version ----
        Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { previewing = null }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = onGradient)
                }
                Text("Version", color = onGradient, fontSize = 20.sp, modifier = Modifier.weight(1f))
            }

            Column(modifier = Modifier.fillMaxWidth().frostedGlass().padding(16.dp)) {
                Text(preview.title.ifBlank { "Untitled" }, color = onGradient, fontSize = 22.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text("As of ${formatTimestamp(preview.savedAt)}", color = onGradientMuted, fontSize = 12.sp)
                // A quiet size read-out for this revision, so "how much did I have written on July 3rd"
                // is answerable at a glance. Checklist versions store their items separately, so count
                // the checklist text for those and the body for plain-text ones.
                val statsText = if (preview.isChecklist) {
                    Checklist.parse(preview.checklist).joinToString("\n") { it.text }
                } else preview.body
                val versionStats = remember(statsText) { NoteStats.label(NoteStats.of(statsText)) }
                if (versionStats.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(versionStats, color = onGradientMuted, fontSize = 12.sp)
                }
                if (preview.tags.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(preview.tags.split(",").joinToString(" · "), color = onGradientMuted, fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.height(12.dp))
                if (preview.isChecklist) {
                    // Rendered read-only: this is a photograph of the past, not a live checklist,
                    // and offering a checkbox that couldn't persist anywhere would be a lie.
                    ChecklistView(
                        items = Checklist.parse(preview.checklist),
                        onToggle = null,
                        header = "Items"
                    )
                } else if (preview.body.isNotBlank()) {
                    MarkdownText(text = preview.body)
                } else {
                    Text("(empty)", color = onGradientMuted)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            GlassCapsuleButton(
                text = "Restore this version",
                icon = Icons.AutoMirrored.Filled.Undo,
                onClick = { confirmRestore = preview }
            )
        }
        return
    }

    // ---- The list of versions ----
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = onGradient)
            }
            Text("Version history", color = onGradient, fontSize = 20.sp, modifier = Modifier.weight(1f))
        }

        Text(
            "Earlier versions of \"${note.title.ifBlank { "Untitled" }}\", saved on this device each time " +
                "the text changed. The last ${NoteHistory.MAX_VERSIONS_PER_NOTE} are kept.",
            color = onGradientMuted,
            fontSize = 12.sp
        )
        Spacer(modifier = Modifier.height(16.dp))

        if (versions.isEmpty()) {
            EmptyState(
                isFiltered = false,
                emptyMessage = "No earlier versions yet. One is saved automatically the first time you change this note's text.",
                noMatchMessage = ""
            )
            return
        }

        LazyColumn(
            modifier = Modifier.hazeSource(state = hazeState),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(versions, key = { it.id }) { version ->
                VersionCard(
                    version = version,
                    onPreview = { previewing = version },
                    onRestore = { confirmRestore = version }
                )
            }
        }
    }
}

/**
 * One revision in the list: when it was current, and enough of its text to recognise it by.
 *
 * The preview line is what makes the list usable at all — a column of bare timestamps forces the
 * user to open every one to find the paragraph they're after, which is exactly the frustration the
 * feature exists to remove.
 */
@Composable
private fun VersionCard(version: NoteVersion, onPreview: () -> Unit, onRestore: () -> Unit) {
    val onGradient = LocalOnGradient.current
    val onGradientMuted = LocalOnGradientMuted.current

    val preview = remember(version) {
        if (version.isChecklist) {
            val items = Checklist.parse(version.checklist)
            val done = items.count { it.done }
            if (items.isEmpty()) "(empty checklist)" else "Checklist · $done/${items.size} done"
        } else {
            version.body.ifBlank { "(empty)" }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .frostedGlass()
            .clickable { onPreview() }
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    version.title.ifBlank { "Untitled" },
                    color = onGradient,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(formatTimestamp(version.savedAt), color = onGradientMuted, fontSize = 12.sp)
            }
            IconButton(onClick = onRestore) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Restore this version", tint = onGradient)
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            preview,
            color = onGradientMuted,
            fontSize = 13.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}
