package com.lucent.app.tools

import android.content.Context
import com.lucent.app.data.AppDatabase
import com.lucent.app.data.Attachment
import com.lucent.app.data.AttachmentLimits
import com.lucent.app.data.AttachmentStore
import com.lucent.app.data.Attachments
import com.lucent.app.data.Checklist
import com.lucent.app.data.DueParsing
import com.lucent.app.data.Note
import com.lucent.app.data.RepeatRule
import com.lucent.app.data.SearchQuery
import com.lucent.app.data.Task
import com.lucent.app.data.TaskPriority
import com.lucent.app.data.filterBySearch
import com.lucent.app.network.ToolDefinition
import com.lucent.app.network.ToolExecResult
import com.lucent.app.network.ToolImage
import com.lucent.app.network.ToolParam
import com.lucent.app.network.WebSearchClient
import com.lucent.app.reminders.ReminderScheduler
import org.json.JSONObject

/**
 * The function-calling tools the assistant can invoke.
 *
 * Notes and tasks are exposed as *separate* tools (create_note vs create_task, delete_note vs
 * delete_task, …) rather than one generic tool with a "type" argument, so a model can't blur the
 * two — "remind me to call the dentist" is a task and "the dentist's number is 555-0134" is a note,
 * and a single ambiguous tool makes that distinction the model's problem instead of the schema's.
 *
 * Three rules hold across every tool here, and they're what make the assistant trustworthy rather
 * than merely capable:
 *
 * **It only reports what actually happened.** Every failure path returns
 * [ToolExecResult.success] = false, and the attachment-removal tools re-read the row after writing
 * to confirm the file is really gone before claiming it is. The assistant can only say "done" when
 * the store agrees.
 *
 * **It can't touch what the user can't see.** Every lookup goes through [activeNotes] /
 * [activeTasks], which exclude trashed rows. Without that, the assistant could read, edit, or attach
 * files to something the user has deleted and can no longer even find — which is exactly the kind of
 * spooky action that destroys trust in an assistant with write access.
 *
 * **It behaves identically to the UI.** Completing, trashing, and rescheduling all route through
 * [TaskActions], the same code the buttons call. Ask the assistant to tick off a repeating task and
 * next week's occurrence appears, because it is literally the same function that runs when you tap
 * the checkbox.
 */
object AppTools {

    /**
     * The tools the assistant may call. [includeWebSearch] appends the opt-in `web_search` tool,
     * which is only offered when the user has turned Web Search on in Settings (issue 16) — the
     * assistant should never even see it as an option otherwise.
     */
    fun definitions(includeWebSearch: Boolean = false): List<ToolDefinition> =
        baseDefinitions() + if (includeWebSearch) listOf(webSearchDefinition()) else emptyList()

    private fun webSearchDefinition(): ToolDefinition = ToolDefinition(
        name = "web_search",
        description = "Search the public web for current or factual information — news, prices, recent events, definitions, or anything you might not know or that could have changed since your training. This is only available because the user turned Web Search on. It returns a short digest of results; read it, then answer in the user's language, and say plainly if nothing useful came back.",
        params = listOf(ToolParam("query", "string", "What to look up on the web"))
    )

    /**
     * Tools that only *read* — they never change the user's data, so the confirmation flow (issue 13)
     * lets them run without a prompt. Everything not in this set mutates something and is gated behind
     * an explicit confirm before it executes.
     */
    private val READ_ONLY_TOOLS = setOf(
        "list_notes", "read_note", "list_tasks", "read_task", "search_items", "web_search"
    )

    /** Whether calling [name] would change the user's notes/tasks (and therefore needs confirmation). */
    fun isMutating(name: String): Boolean = name !in READ_ONLY_TOOLS

    /**
     * A short, human sentence describing what a tool call *would do*, shown in the confirmation modal
     * so the user approves a real action they can read ("Create task \"Call the dentist\"") rather
     * than a raw function name. Best-effort: unknown tools fall back to a generic phrasing.
     */
    fun describeToolCall(name: String, argumentsJson: String): String {
        val a = try { JSONObject(argumentsJson) } catch (e: Exception) { JSONObject() }
        fun s(vararg keys: String): String {
            for (k in keys) { val v = a.optString(k, ""); if (v.isNotBlank()) return v }
            return ""
        }
        val title = s("title", "note_title", "task_title", "new_title")
        return when (name) {
            "create_note" -> "Create a note titled \"${s("title")}\""
            "update_note" -> "Edit the note \"$title\"" + newTitleSuffix(a)
            "delete_note" -> "Move the note \"$title\" to Trash"
            "pin_note" -> if (a.optBoolean("pinned", true)) "Pin the note \"$title\"" else "Unpin the note \"$title\""
            "set_note_attachment" -> "Save the file \"${s("file_name")}\" onto the note \"$title\""
            "remove_note_attachment" -> "Remove the file \"${s("file_name")}\" from the note \"$title\""
            "attach_upload_to_note" -> "Attach your uploaded file to the note \"$title\""
            "create_task" -> "Create a task titled \"${s("title")}\"" + dueSuffix(s("due"))
            "complete_task" -> "Mark the task \"$title\" as done"
            "update_task" -> "Edit the task \"$title\"" + newTitleSuffix(a)
            "delete_task" -> "Move the task \"$title\" to Trash"
            "pin_task" -> if (a.optBoolean("pinned", true)) "Pin the task \"$title\"" else "Unpin the task \"$title\""
            "set_task_priority" -> "Set the priority of \"$title\" to ${s("priority")}"
            "set_task_due_date" -> "Set the due date of \"$title\" to ${s("due_at")}"
            "add_subtask" -> "Add the subtask \"${s("item")}\" to \"$title\""
            "set_subtask_done" -> "Check off the subtask \"${s("item")}\" on \"$title\""
            "remove_subtask" -> "Remove the subtask \"${s("item")}\" from \"$title\""
            "set_task_attachment" -> "Save the file \"${s("file_name")}\" onto the task \"$title\""
            "remove_task_attachment" -> "Remove the file \"${s("file_name")}\" from the task \"$title\""
            "attach_upload_to_task" -> "Attach your uploaded file to the task \"$title\""
            else -> "Run \"$name\""
        }
    }

    private fun newTitleSuffix(a: JSONObject): String {
        val nt = a.optString("new_title", "")
        return if (nt.isNotBlank()) " (rename to \"$nt\")" else ""
    }

    private fun dueSuffix(due: String): String = if (due.isNotBlank()) " due $due" else ""

    private fun baseDefinitions(): List<ToolDefinition> = listOf(
        // ---- Notes ----
        ToolDefinition(
            name = "create_note",
            description = "Create a NOTE (a titled piece of written information with a body). Use this for information to remember, never for a to-do item. The body supports Markdown, and [[Note title]] creates a link to another note.",
            params = listOf(
                ToolParam("title", "string", "The title of the note"),
                ToolParam("body", "string", "The body text of the note"),
                ToolParam("tags", "string", "Optional comma-separated tags, e.g. \"Work,Ideas\"", required = false)
            )
        ),
        ToolDefinition(
            name = "list_notes",
            description = "List the user's NOTES, including each note's tags, pin state, and the file names of any attachments. Returns only names/summaries, not attachment contents. If there are many notes, prefer search_items to find the relevant ones.",
            params = emptyList()
        ),
        ToolDefinition(
            name = "read_note",
            description = "Read the FULL contents of a single NOTE (matched by its title): its body or checklist, tags, and its attachments. Text attachments are included as text; image attachments are shown to you directly so you can look at them. Use this whenever you need to actually see what is written in a note or view a file attached to it.",
            params = listOf(ToolParam("title", "string", "The title (or part of it) of the note to read"))
        ),
        ToolDefinition(
            name = "update_note",
            description = "Edit an existing NOTE, matched by its current title text. Provide a new title and/or new body; leave a field out to keep it unchanged. The note's previous text is automatically saved to its version history, so an edit can always be undone by the user.",
            params = listOf(
                ToolParam("title", "string", "The current title (or part of it) of the note to edit"),
                ToolParam("new_title", "string", "The new title for the note", required = false),
                ToolParam("new_body", "string", "The new body text for the note", required = false),
                ToolParam("new_tags", "string", "New comma-separated tags for the note", required = false)
            )
        ),
        ToolDefinition(
            name = "delete_note",
            description = "Delete a NOTE, matched by its title text. This moves it to Trash rather than erasing it — the user can restore it themselves for 30 days afterwards, so you can be matter-of-fact about deleting when asked.",
            params = listOf(ToolParam("title", "string", "The title (or part of it) of the note to delete"))
        ),
        ToolDefinition(
            name = "pin_note",
            description = "Pin or unpin a NOTE, matched by its title text. Pinned notes stay at the top of the notes list.",
            params = listOf(
                ToolParam("title", "string", "The title (or part of it) of the note"),
                ToolParam("pinned", "boolean", "true to pin, false to unpin")
            )
        ),
        ToolDefinition(
            name = "set_note_attachment",
            description = "Add or replace a text-file attachment on a NOTE (matched by title). If a file with the same name already exists it is overwritten, so this both adds and modifies attachments.",
            params = listOf(
                ToolParam("note_title", "string", "The title (or part of it) of the note"),
                ToolParam("file_name", "string", "The attachment file name, e.g. summary.txt"),
                ToolParam("content", "string", "The text content of the attachment")
            )
        ),
        ToolDefinition(
            name = "remove_note_attachment",
            description = "Remove an attachment from a NOTE, matched by the note title and the attachment's file name (a close partial name is accepted). Read the note first so you use the real file name.",
            params = listOf(
                ToolParam("note_title", "string", "The title (or part of it) of the note"),
                ToolParam("file_name", "string", "The file name (or part of it) of the attachment to remove")
            )
        ),
        ToolDefinition(
            name = "attach_upload_to_note",
            description = "Attach the file the user just UPLOADED in this chat message (a photo, image, or document) onto a NOTE, matched by title. Use this whenever the user uploads a file and wants it saved on a note. This is the only way to attach an uploaded file; set_note_attachment cannot (it only writes text).",
            params = listOf(
                ToolParam("note_title", "string", "The title (or part of it) of the note to attach the uploaded file to"),
                ToolParam("file_name", "string", "Optional name to save the file as; leave out to keep the uploaded file's own name", required = false)
            )
        ),

        // ---- Tasks ----
        ToolDefinition(
            name = "create_task",
            description = "Create a TASK (a to-do item that can be marked done). Use this for something the user needs to do, never for storing information. Optionally set notes, a priority, a due date, a repeat schedule, a reminder, and an initial checklist — all in this one call.",
            params = listOf(
                ToolParam("title", "string", "The title of the task"),
                ToolParam("notes", "string", "Optional notes/description for the task", required = false),
                ToolParam("priority", "string", "Optional priority: none, low, medium, or high", required = false),
                ToolParam("due", "string", "Optional due date as an ABSOLUTE local date: \"YYYY-MM-DD\" or \"YYYY-MM-DD HH:mm\" (24-hour). Work the concrete date out yourself from today's date; never pass a word like \"tomorrow\".", required = false),
                ToolParam("repeat", "string", "Optional repeat schedule: daily, weekly, monthly, or yearly. Leave out for a one-off task.", required = false),
                ToolParam("reminder", "boolean", "Optional: true to notify the user at the due time. Needs a due date to have any effect.", required = false),
                ToolParam("subtasks", "string", "Optional initial checklist: item texts separated by a newline or a semicolon", required = false)
            )
        ),
        ToolDefinition(
            name = "list_tasks",
            description = "List the user's TASKS, including which are done or pending, their priority, due date, pin state, repeat schedule, checklist progress, and the file names of any attachments. Returns only names/summaries, not attachment contents. If there are many tasks, prefer search_items.",
            params = emptyList()
        ),
        ToolDefinition(
            name = "read_task",
            description = "Read the FULL contents of a single TASK (matched by its title): its notes/description, done state, priority, due date, repeat schedule, reminder, full checklist, and its attachments. Text attachments are included as text; image attachments are shown to you directly. Read the task before toggling or removing one of its subtasks, so you have the exact subtask text.",
            params = listOf(ToolParam("title", "string", "The title (or part of it) of the task to read"))
        ),
        ToolDefinition(
            name = "complete_task",
            description = "Mark a TASK as done, matched by its title text. If the task repeats, the next occurrence is created automatically with its due date advanced and its checklist reset.",
            params = listOf(ToolParam("title", "string", "The title (or part of it) of the task to complete"))
        ),
        ToolDefinition(
            name = "update_task",
            description = "Edit an existing TASK, matched by its current title text. Provide any of the new_* fields to change them; leave a field out to keep it unchanged.",
            params = listOf(
                ToolParam("title", "string", "The current title (or part of it) of the task to edit"),
                ToolParam("new_title", "string", "The new title for the task", required = false),
                ToolParam("new_notes", "string", "The new notes/description text for the task", required = false),
                ToolParam("new_priority", "string", "New priority: none, low, medium, or high", required = false),
                ToolParam("new_due", "string", "New due date as \"YYYY-MM-DD\" or \"YYYY-MM-DD HH:mm\" (absolute, local). Pass \"none\" to clear it.", required = false),
                ToolParam("new_repeat", "string", "New repeat schedule: daily, weekly, monthly, yearly, or \"none\" to stop repeating", required = false),
                ToolParam("new_reminder", "boolean", "true to notify at the due time, false to turn the reminder off", required = false)
            )
        ),
        ToolDefinition(
            name = "delete_task",
            description = "Delete a TASK, matched by its title text. This moves it to Trash rather than erasing it — the user can restore it themselves for 30 days afterwards.",
            params = listOf(ToolParam("title", "string", "The title (or part of it) of the task to delete"))
        ),
        ToolDefinition(
            name = "pin_task",
            description = "Pin or unpin a TASK, matched by its title text. Pinned tasks stay at the top of the active task list.",
            params = listOf(
                ToolParam("title", "string", "The title (or part of it) of the task"),
                ToolParam("pinned", "boolean", "true to pin, false to unpin")
            )
        ),
        ToolDefinition(
            name = "set_task_priority",
            description = "Set how important a TASK is, matched by its title text. Higher-priority tasks are highlighted and can be sorted to the top.",
            params = listOf(
                ToolParam("title", "string", "The title (or part of it) of the task"),
                ToolParam("priority", "string", "One of: none, low, medium, high")
            )
        ),
        ToolDefinition(
            name = "set_task_due_date",
            description = "Set, change, or clear a TASK's due date, matched by its title text, and optionally make it repeat or turn its reminder on. Pass \"none\" as due_at to clear the due date (which also clears any repeat, since a repeat needs a due date to advance from).",
            params = listOf(
                ToolParam("title", "string", "The title (or part of it) of the task"),
                ToolParam("due_at", "string", "The due date as \"YYYY-MM-DD\" or \"YYYY-MM-DD HH:mm\" (absolute, local), or \"none\" to clear it"),
                ToolParam("repeat", "string", "Optional: none, daily, weekly, monthly, or yearly", required = false),
                ToolParam("reminder", "boolean", "Optional: true to notify the user at the due time", required = false)
            )
        ),
        ToolDefinition(
            name = "add_subtask",
            description = "Add one checklist item to a TASK, matched by its title text.",
            params = listOf(
                ToolParam("task_title", "string", "The title (or part of it) of the task"),
                ToolParam("item", "string", "The text of the checklist item to add")
            )
        ),
        ToolDefinition(
            name = "set_subtask_done",
            description = "Check or uncheck one of a TASK's checklist items, matched by the task title and the item text (a close partial match is accepted). Read the task first if you need its exact item text.",
            params = listOf(
                ToolParam("task_title", "string", "The title (or part of it) of the task"),
                ToolParam("item", "string", "The text (or part of it) of the checklist item"),
                ToolParam("done", "boolean", "true to check it, false to uncheck it. Leave out to toggle its current state.", required = false)
            )
        ),
        ToolDefinition(
            name = "remove_subtask",
            description = "Remove one checklist item from a TASK, matched by the task title and the item text (a close partial match is accepted).",
            params = listOf(
                ToolParam("task_title", "string", "The title (or part of it) of the task"),
                ToolParam("item", "string", "The text (or part of it) of the checklist item to remove")
            )
        ),
        ToolDefinition(
            name = "set_task_attachment",
            description = "Add or replace a text-file attachment on a TASK (matched by title). If a file with the same name already exists it is overwritten.",
            params = listOf(
                ToolParam("task_title", "string", "The title (or part of it) of the task"),
                ToolParam("file_name", "string", "The attachment file name, e.g. notes.txt"),
                ToolParam("content", "string", "The text content of the attachment")
            )
        ),
        ToolDefinition(
            name = "remove_task_attachment",
            description = "Remove an attachment from a TASK, matched by the task title and the attachment's file name (a close partial name is accepted). Read the task first so you use the real file name.",
            params = listOf(
                ToolParam("task_title", "string", "The title (or part of it) of the task"),
                ToolParam("file_name", "string", "The file name (or part of it) of the attachment to remove")
            )
        ),
        ToolDefinition(
            name = "attach_upload_to_task",
            description = "Attach the file the user just UPLOADED in this chat message (a photo, image, or document) onto a TASK, matched by title. Use this whenever the user uploads a file and wants it saved on a task. This is the only way to attach an uploaded file; set_task_attachment cannot (it only writes text).",
            params = listOf(
                ToolParam("task_title", "string", "The title (or part of it) of the task to attach the uploaded file to"),
                ToolParam("file_name", "string", "Optional name to save the file as; leave out to keep the uploaded file's own name", required = false)
            )
        ),

        // ---- Retrieval ----
        ToolDefinition(
            name = "search_items",
            description = "Search the user's notes and/or tasks and get back only the matching ones. Prefer this over list_notes/list_tasks when the user has a lot of them, or when you're looking for something specific. Supports plain words, \"exact phrases\", and filters: tag:work, is:pinned, is:checklist, is:archived, is:done, is:overdue, has:attachment, has:due, has:reminder, has:subtasks, priority:high, due:today (or tomorrow / week / overdue). Everything you give must match.",
            params = listOf(
                ToolParam("query", "string", "The search query, e.g. \"budget tag:work\" or \"priority:high due:week\""),
                ToolParam("type", "string", "What to search: notes, tasks, or both. Defaults to both.", required = false)
            )
        )
    )

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    /**
     * Read the first present key from [keys].
     *
     * Models do get argument names slightly wrong — passing `title` where the schema says
     * `task_title`, or `subtask_text` where it says `item` — and failing the whole call over a
     * synonym helps nobody. Accepting the obvious aliases costs one line and turns a hard failure
     * into a successful action. The schema still advertises exactly one name; this just refuses to
     * be pedantic about it.
     */
    private fun JSONObject.firstString(vararg keys: String): String {
        for (key in keys) {
            if (has(key)) {
                val value = optString(key, "")
                if (value.isNotBlank()) return value
            }
        }
        return ""
    }

    private fun JSONObject.hasAny(vararg keys: String): Boolean = keys.any { has(it) }

    /**
     * Trashed rows are gone as far as the user is concerned — hidden from every screen but the Trash
     * list — so every tool resolves its title match against these rather than the raw, unfiltered
     * DAO. Otherwise the assistant could quietly read or edit something the user believes they
     * deleted.
     */
    private suspend fun activeNotes(db: AppDatabase): List<Note> =
        db.noteDao().getAllOnce().filter { it.trashedAt == null }

    private suspend fun activeTasks(db: AppDatabase): List<Task> =
        db.taskDao().getAllOnce().filter { it.trashedAt == null }

    private fun matchNote(notes: List<Note>, query: String): Note? =
        notes.firstOrNull { it.title.contains(query, ignoreCase = true) }

    private fun matchTask(tasks: List<Task>, query: String): Task? =
        tasks.firstOrNull { it.title.contains(query, ignoreCase = true) }

    private fun attachmentSummary(json: String): String {
        val names = Attachments.parse(json).map { it.name }
        return if (names.isEmpty()) "no attachments" else "attachments: ${names.joinToString(", ")}"
    }

    private fun describeAttachment(context: Context, att: Attachment): String {
        return if (att.isImage) {
            "[image \"${att.name}\" - shown to you below]"
        } else {
            val text = Attachments.decodeText(context, att)
            if (text != null) {
                "----- attachment \"${att.name}\" -----\n$text\n----- end \"${att.name}\" -----"
            } else {
                "[attachment \"${att.name}\" (${att.mime}) - binary file, contents can't be shown as text]"
            }
        }
    }

    private fun imagesFrom(context: Context, list: List<Attachment>): List<ToolImage> =
        list.filter { it.isImage }.mapNotNull { att ->
            val base64 = Attachments.readAsBase64(context, att) ?: return@mapNotNull null
            ToolImage(mime = att.mime, data = base64, name = att.name)
        }

    private fun resolveAttachmentName(list: List<Attachment>, query: String): String? {
        list.firstOrNull { it.name.equals(query, ignoreCase = true) }?.let { return it.name }
        val partial = list.filter { it.name.contains(query, ignoreCase = true) }
        return if (partial.size == 1) partial.first().name else null
    }

    /** One-line summary of a task, used by list_tasks and search_items. */
    private fun summarize(task: Task): String {
        val sb = StringBuilder()
        sb.append(task.title)
        sb.append(" [").append(if (task.isDone) "done" else "pending").append("]")
        if (task.pinned) sb.append(" [pinned]")
        TaskPriority.fromValue(task.priority).takeIf { it != TaskPriority.NONE }?.let {
            sb.append(" {priority: ${it.key}}")
        }
        task.dueAt?.let { sb.append(" {due: ${DueParsing.format(it)}}") }
        RepeatRule.fromKey(task.repeatRule).takeIf { it != RepeatRule.NONE }?.let {
            sb.append(" {repeats: ${it.key.lowercase()}}")
        }
        if (task.reminderEnabled && task.dueAt != null && !task.isDone) sb.append(" {reminder: on}")
        Checklist.progress(task.subtasks)?.let { (done, total) -> sb.append(" {checklist: $done/$total}") }
        sb.append(" (").append(attachmentSummary(task.attachments)).append(")")
        return sb.toString()
    }

    /** One-line summary of a note, used by list_notes and search_items. */
    private fun summarize(note: Note): String {
        val sb = StringBuilder()
        sb.append(note.title.ifBlank { "Untitled" })
        if (note.pinned) sb.append(" [pinned]")
        if (note.archived) sb.append(" [archived]")
        if (note.isChecklist) {
            Checklist.progress(note.checklist)?.let { (done, total) -> sb.append(" {checklist: $done/$total}") }
        } else {
            sb.append(": ").append(note.body.take(160))
        }
        if (note.tags.isNotBlank()) sb.append(" {tags: ${note.tags}}")
        sb.append(" (").append(attachmentSummary(note.attachments)).append(")")
        return sb.toString()
    }

    private suspend fun storeUpload(
        context: Context,
        db: AppDatabase,
        uploadMime: String?,
        uploadData: String?,
        uploadName: String?,
        requestedName: String,
        onReady: suspend (Attachment) -> ToolExecResult
    ): ToolExecResult {
        if (uploadData.isNullOrBlank()) {
            return ToolExecResult("There's no uploaded file on this message. Ask the user to attach the file in the chat box, then try again.", success = false)
        }
        val bytes = try {
            android.util.Base64.decode(uploadData, android.util.Base64.DEFAULT)
        } catch (t: Throwable) {
            null
        } ?: return ToolExecResult("Couldn't read the uploaded file.", success = false)

        val check = AttachmentLimits.checkSingle(bytes.size.toLong())
        if (!check.allowed) return ToolExecResult(check.message, success = false)

        val id = AttachmentStore.importBytes(context, bytes)
            ?: return ToolExecResult("Couldn't save the uploaded file (disk error).", success = false)
        val fileName = when {
            requestedName.isNotBlank() -> requestedName
            !uploadName.isNullOrBlank() -> uploadName
            else -> defaultUploadName(uploadMime)
        }
        val mime = if (uploadMime.isNullOrBlank()) "application/octet-stream" else uploadMime
        return onReady(Attachment(mime = mime, data = id, name = fileName))
    }

    private fun defaultUploadName(mime: String?): String = when {
        mime == null -> "upload"
        mime.startsWith("image/jpeg") || mime.startsWith("image/jpg") -> "image.jpg"
        mime.startsWith("image/png") -> "image.png"
        mime.startsWith("image/webp") -> "image.webp"
        mime.startsWith("image/gif") -> "image.gif"
        mime.startsWith("image/") -> "image"
        mime == "application/pdf" -> "document.pdf"
        else -> "upload"
    }

    // ---------------------------------------------------------------------------------------
    // Execution
    // ---------------------------------------------------------------------------------------

    suspend fun execute(
        context: Context,
        db: AppDatabase,
        name: String,
        argumentsJson: String,
        uploadMime: String? = null,
        uploadData: String? = null,
        uploadName: String? = null
    ): ToolExecResult {
        val appContext = context.applicationContext
        val args = try { JSONObject(argumentsJson) } catch (e: Exception) { JSONObject() }

        return when (name) {

            // ================================ NOTES ================================

            "create_note" -> {
                val title = args.optString("title", "Untitled")
                val body = args.optString("body", "")
                val tags = args.optString("tags", "")
                db.noteDao().insert(Note(title = title, body = body, tags = tags))
                ToolExecResult("Created note \"$title\".")
            }

            "list_notes" -> {
                val notes = activeNotes(db)
                val summary = if (notes.isEmpty()) "There are no notes yet."
                else notes.joinToString("; ") { summarize(it) }
                ToolExecResult(summary)
            }

            "read_note" -> {
                val titleQuery = args.optString("title", "")
                val match = matchNote(activeNotes(db), titleQuery)
                if (match == null) {
                    ToolExecResult("No note found matching \"$titleQuery\".", success = false)
                } else {
                    val attachments = Attachments.parse(match.attachments)
                    val sb = StringBuilder()
                    sb.append("Note \"${match.title}\".\n")
                    if (match.pinned) sb.append("Pinned: yes\n")
                    if (match.archived) sb.append("Archived: yes\n")
                    if (match.tags.isNotBlank()) sb.append("Tags: ${match.tags}\n")
                    if (match.isChecklist) {
                        val items = Checklist.parse(match.checklist)
                        if (items.isEmpty()) {
                            sb.append("Checklist: (empty)\n")
                        } else {
                            sb.append("Checklist (${items.size}):\n")
                            items.forEach { sb.append(if (it.done) "[x] " else "[ ] ").append(it.text).append("\n") }
                        }
                    } else {
                        sb.append("Body:\n${match.body.ifBlank { "(empty)" }}\n")
                    }
                    if (attachments.isEmpty()) {
                        sb.append("Attachments: none.")
                    } else {
                        sb.append("Attachments (${attachments.size}):\n")
                        attachments.forEach { sb.append(describeAttachment(appContext, it)).append("\n") }
                    }
                    ToolExecResult(sb.toString().trim(), imagesFrom(appContext, attachments))
                }
            }

            "update_note" -> {
                val titleQuery = args.optString("title", "")
                val match = matchNote(activeNotes(db), titleQuery)
                if (match == null) {
                    ToolExecResult("No note found matching \"$titleQuery\".", success = false)
                } else if (!args.hasAny("new_title", "new_body", "new_tags")) {
                    ToolExecResult("No changes were provided.", success = false)
                } else {
                    val newTitle = args.optString("new_title", "")
                    val updated = match.copy(
                        title = if (newTitle.isNotBlank()) newTitle else match.title,
                        body = if (args.has("new_body")) args.optString("new_body") else match.body,
                        tags = if (args.has("new_tags")) args.optString("new_tags") else match.tags
                    )
                    // Records the outgoing text as a revision before overwriting it — so an edit made
                    // by asking is exactly as recoverable as one made by typing.
                    TaskActions.updateNoteWithHistory(db, match, updated)
                    ToolExecResult("Updated note \"${updated.title}\". Its previous version was saved to history.")
                }
            }

            "delete_note" -> {
                val titleQuery = args.optString("title", "")
                val match = matchNote(activeNotes(db), titleQuery)
                if (match == null) {
                    ToolExecResult("No note found matching \"$titleQuery\".", success = false)
                } else {
                    // Soft delete: the row, its attachments, and its history all stay on disk,
                    // hidden in Trash, until the user restores it or the 30-day sweep purges it.
                    TaskActions.trashNote(db, match)
                    ToolExecResult("Moved note \"${match.title}\" to Trash. The user can restore it from there.")
                }
            }

            "pin_note" -> {
                val titleQuery = args.optString("title", "")
                val match = matchNote(activeNotes(db), titleQuery)
                if (match == null) {
                    ToolExecResult("No note found matching \"$titleQuery\".", success = false)
                } else {
                    val pinned = args.optBoolean("pinned", true)
                    db.noteDao().update(match.copy(pinned = pinned))
                    ToolExecResult("${if (pinned) "Pinned" else "Unpinned"} note \"${match.title}\".")
                }
            }

            "set_note_attachment" -> {
                val titleQuery = args.firstString("note_title", "title")
                val fileName = args.optString("file_name", "note.txt")
                val content = args.optString("content", "")
                val match = matchNote(activeNotes(db), titleQuery)
                if (match == null) {
                    ToolExecResult("No note found matching \"$titleQuery\".", success = false)
                } else {
                    val att = Attachments.textAttachment(appContext, fileName, content)
                    if (att == null) {
                        ToolExecResult("Couldn't write attachment \"$fileName\" (disk error).", success = false)
                    } else {
                        val list = Attachments.upsert(appContext, Attachments.parse(match.attachments), att)
                        db.noteDao().update(match.copy(attachments = Attachments.serialize(list), updatedAt = System.currentTimeMillis()))
                        ToolExecResult("Saved attachment \"$fileName\" on note \"${match.title}\".")
                    }
                }
            }

            "remove_note_attachment" -> {
                val titleQuery = args.firstString("note_title", "title")
                val fileName = args.optString("file_name", "")
                val match = matchNote(activeNotes(db), titleQuery)
                if (match == null) {
                    ToolExecResult("No note found matching \"$titleQuery\".", success = false)
                } else {
                    val existing = Attachments.parse(match.attachments)
                    val resolved = resolveAttachmentName(existing, fileName)
                    if (resolved == null) {
                        val names = existing.joinToString(", ") { it.name }.ifBlank { "none" }
                        ToolExecResult("No attachment named \"$fileName\" on note \"${match.title}\". It currently has: $names.", success = false)
                    } else {
                        val list = Attachments.removeByName(appContext, existing, resolved)
                        db.noteDao().update(match.copy(attachments = Attachments.serialize(list), updatedAt = System.currentTimeMillis()))
                        // Confirm the write actually stuck before telling the model it worked.
                        val after = db.noteDao().getByIdOnce(match.id)
                        val stillThere = after != null &&
                            Attachments.parse(after.attachments).any { it.name.equals(resolved, ignoreCase = true) }
                        if (stillThere) {
                            ToolExecResult("Tried to remove \"$resolved\" from note \"${match.title}\", but it's still attached — the deletion did not go through.", success = false)
                        } else {
                            ToolExecResult("Removed attachment \"$resolved\" from note \"${match.title}\".")
                        }
                    }
                }
            }

            "attach_upload_to_note" -> {
                val titleQuery = args.firstString("note_title", "title")
                val requestedName = args.optString("file_name", "")
                val match = matchNote(activeNotes(db), titleQuery)
                if (match == null) {
                    ToolExecResult("No note found matching \"$titleQuery\".", success = false)
                } else {
                    storeUpload(appContext, db, uploadMime, uploadData, uploadName, requestedName) { att ->
                        val list = Attachments.upsert(appContext, Attachments.parse(match.attachments), att)
                        db.noteDao().update(match.copy(attachments = Attachments.serialize(list), updatedAt = System.currentTimeMillis()))
                        ToolExecResult("Attached \"${att.name}\" to note \"${match.title}\".")
                    }
                }
            }

            // ================================ TASKS ================================

            "create_task" -> {
                val title = args.optString("title", "Untitled task")
                val notes = args.optString("notes", "")
                val priority = TaskPriority.fromKey(args.optString("priority", "")).value
                val due = if (args.has("due")) DueParsing.parse(args.optString("due")) else null
                // A repeat with no due date can never fire, so it's dropped rather than stored as a
                // setting that quietly does nothing.
                val repeat = if (due != null && args.has("repeat")) {
                    RepeatRule.fromKey(args.optString("repeat"))
                } else {
                    RepeatRule.NONE
                }
                val reminder = args.optBoolean("reminder", false)
                val subtasks = Checklist.addAll("[]", args.optString("subtasks", ""))

                val toInsert = Task(
                    title = title,
                    notes = notes,
                    priority = priority,
                    dueAt = due,
                    repeatRule = repeat.key,
                    reminderEnabled = reminder,
                    subtasks = subtasks
                )
                val newId = db.taskDao().insert(toInsert)
                ReminderScheduler.sync(appContext, toInsert.copy(id = newId))

                val extras = buildList {
                    TaskPriority.fromValue(priority).takeIf { it != TaskPriority.NONE }?.let {
                        add("${it.label.lowercase()} priority")
                    }
                    due?.let { add("due ${DueParsing.format(it)}") }
                    if (repeat != RepeatRule.NONE) add("repeats ${repeat.key.lowercase()}")
                    if (reminder && due != null) add("reminder on")
                    Checklist.progress(subtasks)?.let { (_, total) -> add("$total checklist item${if (total == 1) "" else "s"}") }
                }
                val suffix = if (extras.isEmpty()) "" else " (${extras.joinToString(", ")})"
                val warning = if (args.has("due") && due == null) {
                    " I couldn't understand the due date you gave, so the task has none — pass an absolute date like 2026-07-15 or 2026-07-15 14:00."
                } else {
                    ""
                }
                ToolExecResult("Created task \"$title\"$suffix.$warning")
            }

            "list_tasks" -> {
                val tasks = activeTasks(db)
                val summary = if (tasks.isEmpty()) "There are no tasks yet."
                else tasks.joinToString("; ") { summarize(it) }
                ToolExecResult(summary)
            }

            "read_task" -> {
                val titleQuery = args.optString("title", "")
                val match = matchTask(activeTasks(db), titleQuery)
                if (match == null) {
                    ToolExecResult("No task found matching \"$titleQuery\".", success = false)
                } else {
                    val attachments = Attachments.parse(match.attachments)
                    val subtaskItems = Checklist.parse(match.subtasks)
                    val priority = TaskPriority.fromValue(match.priority)
                    val repeat = RepeatRule.fromKey(match.repeatRule)

                    val sb = StringBuilder()
                    sb.append("Task \"${match.title}\" [${if (match.isDone) "done" else "pending"}].\n")
                    if (match.pinned) sb.append("Pinned: yes\n")
                    if (priority != TaskPriority.NONE) sb.append("Priority: ${priority.label}\n")
                    match.dueAt?.let { sb.append("Due: ${DueParsing.format(it)}\n") }
                    if (repeat != RepeatRule.NONE) sb.append("Repeats: ${repeat.label}\n")
                    if (match.reminderEnabled && match.dueAt != null && !match.isDone) sb.append("Reminder: on\n")
                    if (match.notes.isNotBlank()) sb.append("Notes/description:\n${match.notes}\n")
                    if (subtaskItems.isEmpty()) {
                        sb.append("Checklist: none.\n")
                    } else {
                        sb.append("Checklist (${subtaskItems.size}):\n")
                        subtaskItems.forEach { sb.append(if (it.done) "[x] " else "[ ] ").append(it.text).append("\n") }
                    }
                    if (attachments.isEmpty()) {
                        sb.append("Attachments: none.")
                    } else {
                        sb.append("Attachments (${attachments.size}):\n")
                        attachments.forEach { sb.append(describeAttachment(appContext, it)).append("\n") }
                    }
                    ToolExecResult(sb.toString().trim(), imagesFrom(appContext, attachments))
                }
            }

            "complete_task" -> {
                val titleQuery = args.optString("title", "")
                val match = matchTask(activeTasks(db), titleQuery)
                if (match == null) {
                    ToolExecResult("No task found matching \"$titleQuery\".", success = false)
                } else {
                    // The same function the checkbox calls: marks done, cancels the reminder, and —
                    // when the task repeats — spawns and arms the next occurrence.
                    val next = TaskActions.complete(appContext, db, match)
                    val note = next?.dueAt?.let { " It repeats, so the next occurrence was created, due ${DueParsing.format(it)}." } ?: ""
                    ToolExecResult("Marked \"${match.title}\" as done.$note")
                }
            }

            "update_task" -> {
                val titleQuery = args.optString("title", "")
                val match = matchTask(activeTasks(db), titleQuery)
                if (match == null) {
                    ToolExecResult("No task found matching \"$titleQuery\".", success = false)
                } else if (!args.hasAny("new_title", "new_notes", "new_priority", "new_due", "new_repeat", "new_reminder")) {
                    ToolExecResult("No changes were provided.", success = false)
                } else {
                    val newTitle = args.optString("new_title", "")

                    // new_due: an explicit clear word nulls it; a valid date sets it; an unparseable
                    // value leaves the existing due date alone rather than silently wiping it — the
                    // one thing worse than not understanding a date is destroying the one that was
                    // already there.
                    var dueParseFailed = false
                    val newDue: Long? = if (args.has("new_due")) {
                        val raw = args.optString("new_due")
                        when {
                            DueParsing.isClearRequest(raw) -> null
                            else -> DueParsing.parse(raw) ?: match.dueAt.also { dueParseFailed = true }
                        }
                    } else {
                        match.dueAt
                    }

                    val newPriority = if (args.has("new_priority")) {
                        TaskPriority.fromKey(args.optString("new_priority")).value
                    } else {
                        match.priority
                    }
                    val newRepeat = if (args.has("new_repeat")) {
                        RepeatRule.fromKey(args.optString("new_repeat"))
                    } else {
                        RepeatRule.fromKey(match.repeatRule)
                    }
                    val newReminder = if (args.has("new_reminder")) {
                        args.optBoolean("new_reminder")
                    } else {
                        match.reminderEnabled
                    }

                    val updated = match.copy(
                        title = if (newTitle.isNotBlank()) newTitle else match.title,
                        notes = if (args.has("new_notes")) args.optString("new_notes") else match.notes,
                        priority = newPriority,
                        dueAt = newDue,
                        // Clearing the due date clears the repeat with it.
                        repeatRule = if (newDue == null) RepeatRule.NONE.key else newRepeat.key,
                        reminderEnabled = newReminder
                    )
                    db.taskDao().update(updated)
                    ReminderScheduler.sync(appContext, updated)

                    val warning = if (dueParseFailed) {
                        " (I couldn't understand the date you gave, so the existing due date was left as it was — pass an absolute date like 2026-07-15 14:00.)"
                    } else {
                        ""
                    }
                    ToolExecResult("Updated task \"${updated.title}\".$warning")
                }
            }

            "delete_task" -> {
                val titleQuery = args.optString("title", "")
                val match = matchTask(activeTasks(db), titleQuery)
                if (match == null) {
                    ToolExecResult("No task found matching \"$titleQuery\".", success = false)
                } else {
                    TaskActions.trash(appContext, db, match)
                    ToolExecResult("Moved task \"${match.title}\" to Trash. The user can restore it from there.")
                }
            }

            "pin_task" -> {
                val titleQuery = args.optString("title", "")
                val match = matchTask(activeTasks(db), titleQuery)
                if (match == null) {
                    ToolExecResult("No task found matching \"$titleQuery\".", success = false)
                } else {
                    val pinned = args.optBoolean("pinned", true)
                    db.taskDao().update(match.copy(pinned = pinned))
                    ToolExecResult("${if (pinned) "Pinned" else "Unpinned"} task \"${match.title}\".")
                }
            }

            "set_task_priority" -> {
                val titleQuery = args.optString("title", "")
                val match = matchTask(activeTasks(db), titleQuery)
                if (match == null) {
                    ToolExecResult("No task found matching \"$titleQuery\".", success = false)
                } else {
                    val priority = TaskPriority.fromKey(args.optString("priority", "none"))
                    db.taskDao().update(match.copy(priority = priority.value))
                    ToolExecResult(
                        if (priority == TaskPriority.NONE) "Cleared the priority on \"${match.title}\"."
                        else "Set \"${match.title}\" to ${priority.label.lowercase()} priority."
                    )
                }
            }

            "set_task_due_date" -> {
                val titleQuery = args.optString("title", "")
                val match = matchTask(activeTasks(db), titleQuery)
                if (match == null) {
                    ToolExecResult("No task found matching \"$titleQuery\".", success = false)
                } else {
                    val raw = args.firstString("due_at", "due")
                    if (DueParsing.isClearRequest(raw)) {
                        TaskActions.setSchedule(appContext, db, match, null, RepeatRule.NONE, null)
                        ToolExecResult("Cleared the due date on \"${match.title}\" (and any repeat, since a repeat needs a due date).")
                    } else {
                        val parsed = DueParsing.parse(raw)
                        if (parsed == null) {
                            ToolExecResult(
                                "Couldn't understand the date \"$raw\". Pass an absolute local date such as 2026-07-15 or 2026-07-15 14:00.",
                                success = false
                            )
                        } else {
                            val repeat = if (args.has("repeat")) RepeatRule.fromKey(args.optString("repeat")) else null
                            val reminder = if (args.has("reminder")) args.optBoolean("reminder") else null
                            val updated = TaskActions.setSchedule(appContext, db, match, parsed, repeat, reminder)
                            val bits = buildList {
                                add("due ${DueParsing.format(parsed)}")
                                RepeatRule.fromKey(updated.repeatRule).takeIf { it != RepeatRule.NONE }?.let {
                                    add("repeating ${it.key.lowercase()}")
                                }
                                if (updated.reminderEnabled) add("reminder on")
                            }
                            ToolExecResult("Set \"${match.title}\" to ${bits.joinToString(", ")}.")
                        }
                    }
                }
            }

            "add_subtask" -> {
                val titleQuery = args.firstString("task_title", "title")
                val item = args.firstString("item", "subtask_text", "text")
                val match = matchTask(activeTasks(db), titleQuery)
                when {
                    match == null -> ToolExecResult("No task found matching \"$titleQuery\".", success = false)
                    item.isBlank() -> ToolExecResult("No checklist item text was provided.", success = false)
                    else -> {
                        db.taskDao().update(match.copy(subtasks = Checklist.add(match.subtasks, item)))
                        ToolExecResult("Added \"$item\" to the checklist on task \"${match.title}\".")
                    }
                }
            }

            "set_subtask_done" -> {
                val titleQuery = args.firstString("task_title", "title")
                val itemQuery = args.firstString("item", "subtask_text", "text")
                val match = matchTask(activeTasks(db), titleQuery)
                if (match == null) {
                    ToolExecResult("No task found matching \"$titleQuery\".", success = false)
                } else {
                    val item = Checklist.findByText(match.subtasks, itemQuery)
                    if (item == null) {
                        val names = Checklist.parse(match.subtasks).joinToString(", ") { it.text }.ifBlank { "none" }
                        ToolExecResult("No checklist item matching \"$itemQuery\" on task \"${match.title}\". It has: $names.", success = false)
                    } else {
                        // No explicit `done` means "toggle" — which is what a person means when they
                        // say "tick that off" without saying which way.
                        val done = if (args.has("done")) args.optBoolean("done", true) else !item.done
                        db.taskDao().update(match.copy(subtasks = Checklist.setDone(match.subtasks, item.id, done)))
                        ToolExecResult("${if (done) "Checked" else "Unchecked"} \"${item.text}\" on task \"${match.title}\".")
                    }
                }
            }

            "remove_subtask" -> {
                val titleQuery = args.firstString("task_title", "title")
                val itemQuery = args.firstString("item", "subtask_text", "text")
                val match = matchTask(activeTasks(db), titleQuery)
                if (match == null) {
                    ToolExecResult("No task found matching \"$titleQuery\".", success = false)
                } else {
                    val item = Checklist.findByText(match.subtasks, itemQuery)
                    if (item == null) {
                        val names = Checklist.parse(match.subtasks).joinToString(", ") { it.text }.ifBlank { "none" }
                        ToolExecResult("No checklist item matching \"$itemQuery\" on task \"${match.title}\". It has: $names.", success = false)
                    } else {
                        db.taskDao().update(match.copy(subtasks = Checklist.remove(match.subtasks, item.id)))
                        ToolExecResult("Removed \"${item.text}\" from the checklist on task \"${match.title}\".")
                    }
                }
            }

            "set_task_attachment" -> {
                val titleQuery = args.firstString("task_title", "title")
                val fileName = args.optString("file_name", "task.txt")
                val content = args.optString("content", "")
                val match = matchTask(activeTasks(db), titleQuery)
                if (match == null) {
                    ToolExecResult("No task found matching \"$titleQuery\".", success = false)
                } else {
                    val att = Attachments.textAttachment(appContext, fileName, content)
                    if (att == null) {
                        ToolExecResult("Couldn't write attachment \"$fileName\" (disk error).", success = false)
                    } else {
                        val list = Attachments.upsert(appContext, Attachments.parse(match.attachments), att)
                        db.taskDao().update(match.copy(attachments = Attachments.serialize(list)))
                        ToolExecResult("Saved attachment \"$fileName\" on task \"${match.title}\".")
                    }
                }
            }

            "remove_task_attachment" -> {
                val titleQuery = args.firstString("task_title", "title")
                val fileName = args.optString("file_name", "")
                val match = matchTask(activeTasks(db), titleQuery)
                if (match == null) {
                    ToolExecResult("No task found matching \"$titleQuery\".", success = false)
                } else {
                    val existing = Attachments.parse(match.attachments)
                    val resolved = resolveAttachmentName(existing, fileName)
                    if (resolved == null) {
                        val names = existing.joinToString(", ") { it.name }.ifBlank { "none" }
                        ToolExecResult("No attachment named \"$fileName\" on task \"${match.title}\". It currently has: $names.", success = false)
                    } else {
                        val list = Attachments.removeByName(appContext, existing, resolved)
                        db.taskDao().update(match.copy(attachments = Attachments.serialize(list)))
                        val after = db.taskDao().getByIdOnce(match.id)
                        val stillThere = after != null &&
                            Attachments.parse(after.attachments).any { it.name.equals(resolved, ignoreCase = true) }
                        if (stillThere) {
                            ToolExecResult("Tried to remove \"$resolved\" from task \"${match.title}\", but it's still attached — the deletion did not go through.", success = false)
                        } else {
                            ToolExecResult("Removed attachment \"$resolved\" from task \"${match.title}\".")
                        }
                    }
                }
            }

            "attach_upload_to_task" -> {
                val titleQuery = args.firstString("task_title", "title")
                val requestedName = args.optString("file_name", "")
                val match = matchTask(activeTasks(db), titleQuery)
                if (match == null) {
                    ToolExecResult("No task found matching \"$titleQuery\".", success = false)
                } else {
                    storeUpload(appContext, db, uploadMime, uploadData, uploadName, requestedName) { att ->
                        val list = Attachments.upsert(appContext, Attachments.parse(match.attachments), att)
                        db.taskDao().update(match.copy(attachments = Attachments.serialize(list)))
                        ToolExecResult("Attached \"${att.name}\" to task \"${match.title}\".")
                    }
                }
            }

            // ============================== RETRIEVAL ==============================

            "search_items" -> {
                val raw = args.firstString("query", "q", "search")
                val type = args.optString("type", "both").trim().lowercase()
                if (raw.isBlank()) {
                    ToolExecResult("No search query was provided.", success = false)
                } else {
                    val query = SearchQuery.parse(raw)
                    val wantNotes = type != "tasks"
                    val wantTasks = type != "notes"

                    // The same query engine the search boxes use — one syntax, one implementation, so
                    // an operator that works when the user types it also works when the model does.
                    val noteHits = if (wantNotes) {
                        activeNotes(db).filterBySearch(query).sortedByDescending { query.rank(it) }.take(25)
                    } else {
                        emptyList()
                    }
                    val taskHits = if (wantTasks) {
                        activeTasks(db).filterBySearch(query).sortedByDescending { query.rank(it) }.take(25)
                    } else {
                        emptyList()
                    }

                    if (noteHits.isEmpty() && taskHits.isEmpty()) {
                        // Not a failure: "nothing matched" is a real, useful answer, and flagging it
                        // as an error would push the model into retrying a search that worked fine.
                        ToolExecResult("Nothing matched \"$raw\".")
                    } else {
                        val sb = StringBuilder()
                        if (noteHits.isNotEmpty()) {
                            sb.append("Notes (${noteHits.size}):\n")
                            noteHits.forEach { sb.append("- ").append(summarize(it)).append("\n") }
                        }
                        if (taskHits.isNotEmpty()) {
                            if (sb.isNotEmpty()) sb.append("\n")
                            sb.append("Tasks (${taskHits.size}):\n")
                            taskHits.forEach { sb.append("- ").append(summarize(it)).append("\n") }
                        }
                        ToolExecResult(sb.toString().trim())
                    }
                }
            }

            "web_search" -> {
                val query = args.firstString("query", "q", "search")
                if (query.isBlank()) {
                    ToolExecResult("No search query was provided.", success = false)
                } else {
                    WebSearchClient.search(query).fold(
                        onSuccess = { ToolExecResult(it) },
                        // A network failure is reported honestly so the assistant can tell the user it
                        // couldn't reach the web, rather than inventing an answer.
                        onFailure = { ToolExecResult("Web search couldn't be completed: ${it.message ?: "network error"}.", success = false) }
                    )
                }
            }

            else -> ToolExecResult("Unknown tool: $name", success = false)
        }
    }
}
