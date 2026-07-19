package com.lucent.app.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lucent.app.AppScope
import com.lucent.app.data.AppDatabase
import com.lucent.app.data.Task
import com.lucent.app.data.TaskPriority
import com.lucent.app.data.TrashCleanup
import com.lucent.app.tools.TaskActions
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.launch

/**
 * Tasks the user deleted, held for [TrashCleanup.RETENTION_DAYS] days before they're removed for
 * good. Mirrors [TrashNotesScreen] in shape and reasoning — see that file for why a Trash exists at
 * all and why these cards own their actions rather than opening a detail page.
 *
 * The one thing this screen has that its notes counterpart doesn't is alarms. Trashing a task
 * cancels its reminder (nothing should be nudging you about something you deleted) and restoring it
 * re-arms it — both handled by [TaskActions], so the rule holds no matter which screen or which tool
 * did the trashing.
 */
@Composable
fun TrashTasksScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getInstance(context) }
    val trashed by db.taskDao().getTrashed().collectAsState(initial = emptyList())
    val onGradient = LocalOnGradient.current
    val onGradientMuted = LocalOnGradientMuted.current
    val hazeState = LocalHazeState.current

    var searchQuery by remember { mutableStateOf("") }
    var taskToPurge by remember { mutableStateOf<Task?>(null) }
    var confirmEmptyTrash by remember { mutableStateOf(false) }
    // Restoring is confirmed as well (task 7): it puts the task back in the active list *and*
    // re-arms whatever reminder it had, so it can start notifying again — which is not something
    // that should be able to happen from a single stray tap next to "delete forever".
    var taskToRestore by remember { mutableStateOf<Task?>(null) }

    taskToRestore?.let { task ->
        AlertDialog(
            onDismissRequest = { taskToRestore = null },
            title = { Text("Restore this task?") },
            text = {
                Text(
                    "\"${task.title.ifBlank { "Untitled task" }}\" will be moved out of Trash and back " +
                        "into your tasks. Any reminder it had is re-armed if its due time is still ahead."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = task
                    taskToRestore = null
                    AppScope.io.launch { TaskActions.untrash(context, db, target) }
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { taskToRestore = null }) { Text("Cancel") } }
        )
    }

    taskToPurge?.let { task ->
        AlertDialog(
            onDismissRequest = { taskToPurge = null },
            title = { Text("Delete forever?") },
            text = { Text("\"${task.title.ifBlank { "Untitled task" }}\" will be permanently deleted, along with its attachments. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    val toPurge = task
                    taskToPurge = null
                    AppScope.io.launch { TrashCleanup.purgeTask(context, db, toPurge) }
                }) { Text("Delete forever") }
            },
            dismissButton = { TextButton(onClick = { taskToPurge = null }) { Text("Cancel") } }
        )
    }

    if (confirmEmptyTrash) {
        AlertDialog(
            onDismissRequest = { confirmEmptyTrash = false },
            title = { Text("Empty trash?") },
            text = { Text("All ${trashed.size} task${if (trashed.size == 1) "" else "s"} in Trash will be permanently deleted. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    val toPurge = trashed.toList()
                    confirmEmptyTrash = false
                    AppScope.io.launch { toPurge.forEach { TrashCleanup.purgeTask(context, db, it) } }
                }) { Text("Empty trash") }
            },
            dismissButton = { TextButton(onClick = { confirmEmptyTrash = false }) { Text("Cancel") } }
        )
    }

    val filtered = remember(trashed, searchQuery) {
        trashed.filter { task ->
            searchQuery.isBlank() ||
                task.title.contains(searchQuery, ignoreCase = true) ||
                task.notes.contains(searchQuery, ignoreCase = true)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = onGradient)
            }
            Text("Trash", color = onGradient, fontSize = 20.sp, modifier = Modifier.weight(1f))
            if (trashed.isNotEmpty()) {
                TextButton(onClick = { confirmEmptyTrash = true }) { Text("Empty trash") }
            }
        }

        Text(
            "Kept for ${TrashCleanup.RETENTION_DAYS} days, then deleted automatically.",
            color = onGradientMuted,
            fontSize = 12.sp
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("Search trash") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (filtered.isEmpty()) {
            EmptyState(
                isFiltered = searchQuery.isNotBlank(),
                emptyMessage = "Trash is empty.",
                noMatchMessage = "No trashed tasks match that search."
            )
            return
        }

        LazyColumn(
            modifier = Modifier.hazeSource(state = hazeState),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(filtered, key = { it.id }) { task ->
                TrashedTaskCard(
                    task = task,
                    onRestore = { taskToRestore = task },
                    onPurge = { taskToPurge = task },
                    onGradient = onGradient,
                    onGradientMuted = onGradientMuted
                )
            }
        }
    }
}

@Composable
private fun TrashedTaskCard(
    task: Task,
    onRestore: () -> Unit,
    onPurge: () -> Unit,
    onGradient: Color,
    onGradientMuted: Color
) {
    val priority = remember(task.priority) { TaskPriority.fromValue(task.priority) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .frostedGlass()
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PriorityDot(priority)
                    if (priority != TaskPriority.NONE) Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        task.title.ifBlank { "Untitled task" },
                        color = onGradient,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textDecoration = if (task.isDone) TextDecoration.LineThrough else null
                    )
                }
                val stamp = task.trashedAt ?: task.createdAt
                Text(
                    "Trashed ${formatTimestamp(stamp)}" + if (task.isDone) " · was completed" else "",
                    color = onGradientMuted,
                    fontSize = 12.sp
                )
            }
            IconButton(onClick = onRestore) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Restore", tint = onGradient)
            }
            IconButton(onClick = onPurge) {
                Icon(Icons.Default.Delete, contentDescription = "Delete forever", tint = onGradient)
            }
        }
        if (task.notes.isNotBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(task.notes, color = onGradientMuted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
