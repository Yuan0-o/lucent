package com.lucent.app.ui

import android.content.Context
import android.provider.OpenableColumns
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.lucent.app.AppNavigation
import com.lucent.app.data.AppDatabase
import com.lucent.app.data.Attachment
import com.lucent.app.data.Attachments
import com.lucent.app.data.AttachmentStore
import com.lucent.app.data.Note
import com.lucent.app.data.ShareIntegration
import com.lucent.app.data.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Holds a single inbound share until the user decides what to do with it (task 6).
 *
 * `MainActivity.handleShareIntent` parses an ACTION_SEND intent and calls [offer]; the UI shows
 * [ShareIntakeDialog], which asks whether to make it a note or a task and then creates the row and
 * opens it. State lives here (process-global) rather than in the intent so it survives the
 * recomposition that opens the dialog, and so a share that arrives while the app is locked can wait
 * until after unlock to be handled.
 */
object ShareIntake {

    var pending by mutableStateOf<ShareIntegration.Shared?>(null)
        private set

    fun offer(shared: ShareIntegration.Shared) { pending = shared }
    fun clear() { pending = null }

    /** Create a note from the shared payload and return its new id. */
    suspend fun createNote(context: Context, shared: ShareIntegration.Shared): Long {
        val db = AppDatabase.getInstance(context.applicationContext)
        val attachment = shared.streamUri?.let { importStream(context, it, shared.mime) }
        val title = deriveTitle(shared, attachment)
        val body = shared.text.orEmpty()
        val attachmentsJson = attachment?.let { Attachments.serialize(listOf(it)) } ?: "[]"
        return db.noteDao().insert(Note(title = title, body = body, attachments = attachmentsJson))
    }

    /** Create a task from the shared payload and return its new id. */
    suspend fun createTask(context: Context, shared: ShareIntegration.Shared): Long {
        val db = AppDatabase.getInstance(context.applicationContext)
        val attachment = shared.streamUri?.let { importStream(context, it, shared.mime) }
        val title = deriveTitle(shared, attachment)
        // Anything after the first line becomes the task's notes, so a long shared blob isn't crammed
        // into the title.
        val remainder = shared.text?.substringAfter('\n', "")?.trim().orEmpty()
        val attachmentsJson = attachment?.let { Attachments.serialize(listOf(it)) } ?: "[]"
        return db.taskDao().insert(Task(title = title, notes = remainder, attachments = attachmentsJson))
    }

    private fun deriveTitle(shared: ShareIntegration.Shared, attachment: Attachment?): String {
        val firstLine = shared.text?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        return when {
            firstLine.isNotEmpty() -> firstLine.take(80)
            attachment != null -> attachment.name
            else -> "Shared"
        }
    }

    /** Copy a shared file into the encrypted attachment store, returning an Attachment or null. */
    private fun importStream(context: Context, uri: android.net.Uri, mime: String?): Attachment? {
        return try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            val id = AttachmentStore.importBytes(context, bytes) ?: return null
            val resolvedMime = mime ?: context.contentResolver.getType(uri) ?: "application/octet-stream"
            Attachment(mime = resolvedMime, data = id, name = queryName(context, uri) ?: "shared")
        } catch (_: Throwable) {
            null
        }
    }

    private fun queryName(context: Context, uri: android.net.Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx) else null
                } else null
            }
        } catch (_: Throwable) {
            null
        }
    }
}

/**
 * Shown while [ShareIntake.pending] holds an inbound share. Lets the user drop it into a new note or
 * task; either choice creates the row, opens it, and clears the pending share.
 */
@Composable
fun ShareIntakeDialog() {
    val shared = ShareIntake.pending ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { ShareIntake.clear() },
        title = { Text("Add to Lucent") },
        text = {
            val preview = shared.text?.take(140)
            Text(
                when {
                    !preview.isNullOrBlank() && shared.streamUri != null -> "Save the shared text and file as a new:"
                    !preview.isNullOrBlank() -> "Save \"$preview\" as a new:"
                    else -> "Save the shared file as a new:"
                }
            )
        },
        confirmButton = {
            Button(onClick = {
                val payload = shared
                ShareIntake.clear()
                scope.launch {
                    val id = withContext(Dispatchers.IO) { ShareIntake.createNote(context, payload) }
                    AppNavigation.openNote(id)
                }
            }) { Text("New note") }
        },
        dismissButton = {
            TextButton(onClick = {
                val payload = shared
                ShareIntake.clear()
                scope.launch {
                    val id = withContext(Dispatchers.IO) { ShareIntake.createTask(context, payload) }
                    AppNavigation.openTask(id)
                }
            }) { Text("New task") }
        }
    )
}
