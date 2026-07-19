package com.lucent.app.data

import android.content.Context
import android.util.Base64
import com.lucent.app.reminders.ReminderScheduler
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream

/**
 * Backup / restore — a single encrypted `.lcb` file, and nothing else.
 *
 * ### The only format: an encrypted envelope (`.lcb`)
 *
 * Export produces one [BackupCrypto] envelope. Inside it is a self-contained JSON manifest with
 * every note (archived ones included), task, note-version, chat, conversation, setting, and every
 * attachment inlined as Base64. The whole thing is sealed — nothing readable survives outside the
 * envelope except the small plaintext header import needs to know whether a password is required.
 *
 * **Legacy formats have been removed (task 5).** Earlier builds also wrote and read a ZIP bundle
 * (`manifest.json` + `attachments/<id>` entries) and a bare `.json`. All of that reader/writer code
 * is gone: import now accepts *only* a `.lcb` envelope and rejects anything else with a clear
 * message, and export only ever writes a `.lcb`. Keeping three half-tested code paths alive for
 * files almost nobody still had was a liability out of proportion to the benefit.
 *
 * ### Cross-device restore (task 5)
 *
 * The manifest is portable by construction, and the two places that used to make it *not* portable
 * are both handled here:
 *
 *  - **Attachments** are inlined as the file's **plaintext** bytes (decrypted through
 *    [AttachmentStore] on the way out), never the on-disk ciphertext — because the on-disk key is
 *    device-bound, so inlined ciphertext would round-trip on the origin device and be permanent
 *    garbage anywhere else. On import each blob is re-encrypted with *this* device's key.
 *  - **API keys** inside the manifest are sealed with [CryptoUtil], which derives its key from an
 *    app-embedded passphrase (not the Keystore), so they decrypt on any install.
 *
 * The remaining cross-device failure was a *usability* one, and it's fixed in the export UI rather
 * than here: the old default silently re-used a password saved only on the origin device, so a
 * backup that "just worked" when re-imported on the same phone demanded a forgotten password on a
 * new one. The portable built-in key is now the default; a password is an explicit opt-in that the
 * UI is clear you must re-enter elsewhere. See [BackupCrypto] and the export dialog in SettingsScreen.
 *
 * The payload is streamed through the envelope, so a large backup full of photos never has to fit in
 * memory twice.
 */
object BackupManager {

    // Bumped whenever the manifest shape changes. Import reads this only for information; every
    // field added since is read back with a default, so an older manifest inside a `.lcb` still
    // restores cleanly. 8 (this build) covers pin/colour/checklist/trash state, task
    // priority/repeat/reminder/subtasks, and note version history.
    private const val BACKUP_VERSION = 8

    // ---------------------------------------------------------------------------------------
    // Export
    // ---------------------------------------------------------------------------------------

    /**
     * Build the self-contained manifest JSON — every attachment inlined as Base64 — that gets sealed
     * inside the `.lcb`. Reads **all** notes (archived included) and tasks via the one-shot DAO
     * queries so nothing is filtered out of the backup.
     *
     * Note the memory trade-off: inlining puts the full attachment payload into one JSON string, so a
     * backup with very large attachments is held in memory while it's built. This is a deliberate
     * choice in favour of a single, directly-importable, portable file — the same inline form is what
     * makes cross-device restore work (the bytes travel in the file, not as device-bound ids).
     */
    suspend fun exportJsonFull(context: Context, db: AppDatabase, settings: SettingsRepository): String {
        val notes = db.noteDao().getAllOnce()          // archived notes included
        val tasks = db.taskDao().getAllOnce()
        val noteVersions = db.noteVersionDao().getAllOnce()
        val chats = db.chatDao().getAll().first()
        val conversations = db.chatConversationDao().getAllOnce()
        return buildManifest(context, notes, tasks, noteVersions, chats, conversations, settings, inlineAttachments = true)
            .toString(2)
    }

    /**
     * Write a complete, **encrypted** backup to [out].
     *
     * This is what the Export button produces. The payload is the same self-contained JSON as before
     * — notes, tasks, note history, chats, every attachment inlined — but the whole thing is now
     * sealed inside a [BackupCrypto] envelope instead of being written in the clear with only the API
     * key encrypted.
     *
     * That old shape was a strange one for a local-first app: a backup is the single artefact that
     * *deliberately* leaves the device, into a cloud drive or an email to yourself, and it was the one
     * file with nothing protecting it. Now nothing readable survives outside the envelope.
     *
     * [password] blank or null → the built-in key (portable, restores anywhere, honest obfuscation).
     * [password] set → PBKDF2 from that password (real encryption; lose it and the file is gone).
     *
     * The JSON is streamed straight into the cipher, so it is never held in memory twice.
     */
    suspend fun exportEncrypted(
        context: Context,
        db: AppDatabase,
        settings: SettingsRepository,
        out: OutputStream,
        password: String?
    ) {
        val json = exportJsonFull(context, db, settings)
        BackupCrypto.encryptingStream(out, password).use { cipherOut ->
            cipherOut.write(json.toByteArray(Charsets.UTF_8))
        }
    }

    /**
     * Build the manifest JSON.
     *
     * When [inlineAttachments] is true, each note/task's attachment list is rewritten so every
     * `data` field holds the Base64 of the file's bytes (a fully self-contained backup). When
     * false, the list is left as stored — disk ids — which is what the ZIP path wants, since the
     * bytes travel as separate archive entries.
     *
     * Notes always carry their `archived` / `archivedAt` state so archiving survives a round-trip.
     */
    private suspend fun buildManifest(
        context: Context,
        notes: List<Note>,
        tasks: List<Task>,
        noteVersions: List<NoteVersion>,
        chats: List<ChatMessage>,
        conversations: List<ChatConversation>,
        settings: SettingsRepository,
        inlineAttachments: Boolean
    ): JSONObject {
        val notesArray = JSONArray()
        notes.forEach {
            val attachments = if (inlineAttachments) inlineAttachmentBytes(context, it.attachments) else it.attachments
            notesArray.put(
                JSONObject()
                    .put("title", it.title)
                    .put("body", it.body)
                    .put("updatedAt", it.updatedAt)
                    .put("tags", it.tags)
                    .put("attachments", attachments)
                    // Archive state. Older readers simply ignore unknown keys, and older *files*
                    // simply don't have them — every field added here is read back with a default
                    // that reproduces the old behaviour exactly, which is what lets a v5 backup from
                    // two years ago still restore cleanly into this build.
                    .put("archived", it.archived)
                    .put("archivedAt", it.archivedAt ?: JSONObject.NULL)
                    .put("pinned", it.pinned)
                    .put("color", it.color)
                    .put("isChecklist", it.isChecklist)
                    .put("checklist", it.checklist)
                    .put("trashedAt", it.trashedAt ?: JSONObject.NULL)
            )
        }

        val tasksArray = JSONArray()
        tasks.forEach {
            val attachments = if (inlineAttachments) inlineAttachmentBytes(context, it.attachments) else it.attachments
            tasksArray.put(
                JSONObject()
                    .put("title", it.title)
                    .put("isDone", it.isDone)
                    .put("createdAt", it.createdAt)
                    .put("attachments", attachments)
                    .put("dueAt", it.dueAt ?: JSONObject.NULL)
                    .put("notes", it.notes)
                    .put("completedAt", it.completedAt ?: JSONObject.NULL)
                    .put("priority", it.priority)
                    .put("pinned", it.pinned)
                    .put("subtasks", it.subtasks)
                    .put("repeatRule", it.repeatRule)
                    .put("reminderEnabled", it.reminderEnabled)
                    .put("trashedAt", it.trashedAt ?: JSONObject.NULL)
            )
        }

        // Note revision history. It travels by *note title + updatedAt* rather than by noteId,
        // because import inserts notes as new rows and Room hands them brand-new ids — a stored
        // noteId would point at whatever note happened to land on that id, which is worse than
        // useless. The importer re-links each version to the note it actually belongs to.
        val versionsArray = JSONArray()
        val noteById = notes.associateBy { it.id }
        noteVersions.forEach { version ->
            val owner = noteById[version.noteId] ?: return@forEach
            versionsArray.put(
                JSONObject()
                    .put("noteTitle", owner.title)
                    .put("noteUpdatedAt", owner.updatedAt)
                    .put("title", version.title)
                    .put("body", version.body)
                    .put("tags", version.tags)
                    .put("isChecklist", version.isChecklist)
                    .put("checklist", version.checklist)
                    .put("savedAt", version.savedAt)
            )
        }

        val chatsArray = JSONArray()
        chats.forEach {
            chatsArray.put(
                JSONObject()
                    .put("role", it.role)
                    .put("content", it.content)
                    .put("timestamp", it.timestamp)
                    .put("attachmentMime", it.attachmentMime ?: JSONObject.NULL)
                    .put("attachmentData", it.attachmentData ?: JSONObject.NULL)
                    .put("attachmentName", it.attachmentName ?: JSONObject.NULL)
                    .put("conversationId", it.conversationId)
            )
        }

        val conversationsArray = JSONArray()
        conversations.forEach {
            conversationsArray.put(
                JSONObject()
                    .put("id", it.id)
                    .put("title", it.title)
                    .put("createdAt", it.createdAt)
                    .put("updatedAt", it.updatedAt)
            )
        }

        val settingsObj = JSONObject()
            .put("baseUrl", settings.baseUrl.first())
            .put("apiSpec", settings.apiSpec.first())
            .put("apiKeyEncrypted", CryptoUtil.encrypt(settings.apiKey.first()))
            .put("model", settings.model.first())
            .put("themeMode", settings.themeMode.first())
            .put("palette", settings.palette.first())
            .put("font", settings.font.first())
            .put("assistantName", settings.assistantName.first())
            .put("assistantStyle", settings.assistantStyle.first())
            // Full multi-API state. The profile JSON already stores each key encrypted (see
            // ApiProfiles.serialize), so this is safe to include verbatim; on import we re-store
            // it as-is and re-mirror the selected profile into the flat keys.
            .put("apiProfiles", settings.apiProfilesJson.first())
            .put("apiProfileSelected", settings.apiProfileSelected.first())

        return JSONObject()
            .put("version", BACKUP_VERSION)
            .put("exportedAt", System.currentTimeMillis())
            .put("notes", notesArray)
            .put("tasks", tasksArray)
            .put("noteVersions", versionsArray)
            .put("chats", chatsArray)
            .put("conversations", conversationsArray)
            .put("settings", settingsObj)
    }

    /**
     * Rewrite an attachment-list JSON so every disk-backed entry carries its bytes inline as
     * Base64 (for the self-contained JSON export). Entries that are already Base64 (legacy rows
     * that haven't been migrated to disk yet) are left as-is, and a disk id whose file is missing
     * is left untouched too — best effort, so a single unreadable attachment never breaks the whole
     * export. An empty list short-circuits.
     */
    private fun inlineAttachmentBytes(context: Context, attachmentsJson: String): String {
        val list = Attachments.parse(attachmentsJson)
        if (list.isEmpty()) return attachmentsJson
        val inlined = list.mapNotNull { att ->
            // Not a disk id → it's already an inline Base64 payload; leave it.
            if (!AttachmentStore.looksLikeId(att.data)) return@mapNotNull att
            // Through the store, so the bytes are decrypted. Reading the File directly would inline
            // ciphertext into the backup — which would round-trip on this device (the same key would
            // "decrypt" it again) and be permanently unreadable on any other. Exactly the kind of bug
            // that only surfaces the day someone actually needs their backup.
            //
            // Self-containment fix (from the first settings variant): an attachment whose bytes
            // can't be read is DROPPED from the backup rather than embedded as its on-disk id. A
            // bare id resolves on the phone that minted it (masking the problem on same-device
            // restores) and points at nothing on any other device — the exact per-attachment loss
            // that surfaced only cross-device. The file is already unreadable on this device, so
            // nothing recoverable is lost; what's guaranteed instead is that no .lcb ever
            // references bytes it doesn't actually contain.
            val plain = AttachmentStore.readBytes(context, att.data, maxBytes = Long.MAX_VALUE)
                ?: return@mapNotNull null
            val encoded = try {
                Base64.encodeToString(plain, Base64.NO_WRAP)
            } catch (t: Throwable) {
                return@mapNotNull null
            }
            att.copy(data = encoded)
        }
        return Attachments.serialize(inlined)
    }

    // ---------------------------------------------------------------------------------------
    // Import
    // ---------------------------------------------------------------------------------------

    /**
     * What a backup file contains, worked out **without writing anything**.
     *
     * The import flow is two-phase for a reason. Restoring merges a stranger's file into the user's
     * live database, and the old flow did it the instant they picked the file — no idea what was
     * inside, no way back. Now they see exactly what is about to arrive and get to say no. The
     * decrypted payload is carried here so the confirm step doesn't have to re-read the file (whose
     * Uri may no longer be readable by then) or re-derive a PBKDF2 key that took a second the first
     * time.
     */
    data class BackupPreview(
        /** The decrypted manifest. Internal: this is the payload, not a summary of it. */
        internal val manifestJson: String,
        val formatVersion: Int,
        val exportedAt: Long?,
        val encrypted: Boolean,
        val passwordProtected: Boolean,
        val notes: Int,
        val archivedNotes: Int,
        val trashedNotes: Int,
        val tasks: Int,
        val completedTasks: Int,
        val trashedTasks: Int,
        val noteVersions: Int,
        val conversations: Int,
        val chatMessages: Int,
        val attachments: Int,
        val hasSettings: Boolean
    ) {
        /** True when the file parsed but holds nothing worth restoring. */
        val isEmpty: Boolean
            get() = notes == 0 && tasks == 0 && chatMessages == 0 && conversations == 0 && !hasSettings
    }

    /**
     * Decrypt (if needed), parse, and count a backup — **touching nothing**.
     *
     * Throws [BackupCrypto.WrongPasswordException] when the file needs a password and the one given
     * is missing or wrong, and [IllegalArgumentException] when the file isn't a Lucent backup at all.
     * Both are distinct on purpose: "try again", "this isn't a backup", and "this file is damaged"
     * send someone in three different directions, and telling them the wrong one is how a perfectly
     * good backup gets deleted in frustration.
     */
    suspend fun inspect(context: Context, bytes: ByteArray, password: String? = null): BackupPreview {
        val header = BackupCrypto.readHeader(bytes)
            // Only `.lcb` envelopes are accepted now (task 5). A file without our envelope header —
            // a legacy ZIP, a bare JSON, or something that isn't a Lucent backup at all — is refused
            // here rather than being parsed by a reader that no longer exists.
            ?: throw IllegalArgumentException(
                com.lucent.app.i18n.S.notLcbBackup
            )

        val manifestJson = BackupCrypto.decrypt(bytes, password).toString(Charsets.UTF_8)

        val root = try {
            JSONObject(manifestJson)
        } catch (t: Throwable) {
            throw IllegalArgumentException("That backup couldn't be read — the file may be damaged.")
        }

        val notesArr = root.optJSONArray("notes")
        val tasksArr = root.optJSONArray("tasks")

        var archived = 0
        var trashedNotes = 0
        var attachments = 0
        for (i in 0 until (notesArr?.length() ?: 0)) {
            val o = notesArr!!.getJSONObject(i)
            if (o.optBoolean("archived", false)) archived++
            if (!o.isNull("trashedAt")) trashedNotes++
            attachments += Attachments.parse(o.optString("attachments", "[]")).size
        }

        var completed = 0
        var trashedTasks = 0
        for (i in 0 until (tasksArr?.length() ?: 0)) {
            val o = tasksArr!!.getJSONObject(i)
            if (o.optBoolean("isDone", false)) completed++
            if (!o.isNull("trashedAt")) trashedTasks++
            attachments += Attachments.parse(o.optString("attachments", "[]")).size
        }

        return BackupPreview(
            manifestJson = manifestJson,
            formatVersion = root.optInt("version", 0),
            exportedAt = root.optLong("exportedAt", 0L).takeIf { it > 0 },
            encrypted = true,
            passwordProtected = header.needsPassword,
            notes = notesArr?.length() ?: 0,
            archivedNotes = archived,
            trashedNotes = trashedNotes,
            tasks = tasksArr?.length() ?: 0,
            completedTasks = completed,
            trashedTasks = trashedTasks,
            noteVersions = root.optJSONArray("noteVersions")?.length() ?: 0,
            conversations = root.optJSONArray("conversations")?.length() ?: 0,
            chatMessages = root.optJSONArray("chats")?.length() ?: 0,
            attachments = attachments,
            hasSettings = root.optJSONObject("settings") != null
        )
    }

    /**
     * Actually restore a previously [inspect]ed backup. This is the only call that writes.
     *
     * Splitting it out is the point: nothing touches the database until the user has seen what is in
     * the file and said yes.
     */
    suspend fun commit(
        context: Context,
        db: AppDatabase,
        settings: SettingsRepository,
        preview: BackupPreview
    ): String {
        // The manifest carries its attachments inline, so there is nothing to stage first — the JSON
        // reader decodes each blob to disk as it inserts the row it belongs to.
        return importJson(context, db, settings, preview.manifestJson)
    }

    /**
     * Look at a file *before* importing it, so the UI knows whether to ask for a password.
     *
     * Import has to be able to answer "does this need a password?" without a password, or the only
     * way to find out would be to demand one and see if it worked — a miserable thing to do to
     * someone who is already anxious because they are restoring a backup. The envelope's header is
     * plaintext for exactly this reason.
     *
     * Returns null for anything that isn't one of our `.lcb` envelopes; the caller treats that as
     * "not a restorable file" (legacy ZIP/JSON support has been removed — task 5).
     */
    fun peekPasswordRequirement(bytes: ByteArray): BackupCrypto.Header? = BackupCrypto.readHeader(bytes)

    /**
     * Restore from a decrypted manifest string (the JSON sealed inside a `.lcb`). Attachments are
     * inline Base64; each blob is decoded to disk and the row rewritten with a disk id as it goes.
     * Rows already carrying disk ids (a manifest that was somehow hand-built) are left as-is.
     */
    suspend fun importJson(
        context: Context,
        db: AppDatabase,
        settings: SettingsRepository,
        json: String
    ): String {
        val root = JSONObject(json)
        var importedNotes = 0
        var importedTasks = 0
        var importedChats = 0
        var skipped = 0

        val existingNotes = db.noteDao().getAllOnce()
        val existingTasks = db.taskDao().getAllOnce()
        val existingChats = db.chatDao().getAll().first()

        // Maps a backed-up note's (title, updatedAt) to the id it was given on *this* device. Note
        // ids are not stable across an import — Room assigns fresh ones — so version history can't
        // travel by id and has to be re-linked through something that survives the trip.
        val noteIdByKey = HashMap<String, Long>()
        var importedVersions = 0

        // Restore conversations first so chat messages can be repointed at them. Backups store
        // each conversation's original id; because the local DB may already have conversations
        // with those ids, we insert fresh rows and remember old-id -> new-id so message rows can
        // be remapped. Backups predating multi-conversation support have no "conversations"
        // array — those messages keep conversationId 1, and we make sure a conversation with a
        // usable id exists for them below.
        val convIdRemap = HashMap<Long, Long>()
        root.optJSONArray("conversations")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val oldId = o.optLong("id", 0)
                val title = o.optString("title", "Conversation")
                val createdAt = o.optLong("createdAt", System.currentTimeMillis())
                val updatedAt = o.optLong("updatedAt", createdAt)
                val newId = db.chatConversationDao().insert(
                    ChatConversation(title = title, createdAt = createdAt, updatedAt = updatedAt)
                )
                if (oldId != 0L) convIdRemap[oldId] = newId
            }
        }

        root.optJSONArray("notes")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val title = o.optString("title")
                val body = o.optString("body")
                val updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
                val tags = o.optString("tags", "")
                val rawAttachments = o.optString("attachments", "[]")
                val attachments = migrateInlineAttachmentsIfNeeded(context, rawAttachments)
                // Archive state — absent in pre-archive backups, so it defaults to not-archived
                // with a null timestamp, which is exactly right for those older files.
                val archived = o.optBoolean("archived", false)
                val archivedAt = if (o.isNull("archivedAt")) null else o.optLong("archivedAt")
                // Pin / colour / checklist / trash — absent from older backups, so each defaults to
                // exactly the inert value a never-touched pre-existing row would have had.
                val pinned = o.optBoolean("pinned", false)
                val color = o.optString("color", "")
                val isChecklist = o.optBoolean("isChecklist", false)
                val checklist = o.optString("checklist", "[]")
                val trashedAt = if (o.isNull("trashedAt")) null else o.optLong("trashedAt")
                val isDuplicate = existingNotes.any { it.title == title && it.body == body && it.updatedAt == updatedAt }
                if (isDuplicate) { skipped++; continue }
                val newNoteId = db.noteDao().insert(
                    Note(
                        title = title, body = body, updatedAt = updatedAt, tags = tags,
                        attachments = attachments, archived = archived, archivedAt = archivedAt,
                        pinned = pinned, color = color, isChecklist = isChecklist,
                        checklist = checklist, trashedAt = trashedAt
                    )
                )
                // Remember where this note landed so its revision history can be re-linked to the
                // id Room just handed it. Keyed on the same (title, updatedAt) pair the export used.
                noteIdByKey["$title\u0000$updatedAt"] = newNoteId
                importedNotes++
            }
        }
        root.optJSONArray("tasks")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val title = o.optString("title")
                val isDone = o.optBoolean("isDone", false)
                val createdAt = o.optLong("createdAt", System.currentTimeMillis())
                val rawAttachments = o.optString("attachments", "[]")
                val attachments = migrateInlineAttachmentsIfNeeded(context, rawAttachments)
                val dueAt = if (o.isNull("dueAt")) null else o.optLong("dueAt")
                val taskNotes = o.optString("notes", "")
                val completedAt = if (o.isNull("completedAt")) null else o.optLong("completedAt")
                // Priority / pin / subtasks / repeat / reminder / trash — absent from older backups,
                // so each defaults to the inert value, same reasoning as the note fields above.
                val priority = o.optInt("priority", 0)
                val taskPinned = o.optBoolean("pinned", false)
                val subtasks = o.optString("subtasks", "[]")
                val repeatRule = o.optString("repeatRule", "NONE")
                val reminderEnabled = o.optBoolean("reminderEnabled", false)
                val taskTrashedAt = if (o.isNull("trashedAt")) null else o.optLong("trashedAt")
                val isDuplicate = existingTasks.any { it.title == title && it.createdAt == createdAt }
                if (isDuplicate) { skipped++; continue }
                db.taskDao().insert(
                    Task(
                        title = title, isDone = isDone, createdAt = createdAt,
                        attachments = attachments, dueAt = dueAt, notes = taskNotes,
                        completedAt = completedAt, priority = priority, pinned = taskPinned,
                        subtasks = subtasks, repeatRule = repeatRule,
                        reminderEnabled = reminderEnabled, trashedAt = taskTrashedAt
                    )
                )
                importedTasks++
            }
        }
        // Note revision history. Re-linked to whichever local id each note actually landed on (see
        // noteIdByKey). Versions whose note wasn't imported — because it was a duplicate and got
        // skipped, or because the file was hand-edited — are simply dropped: an orphaned version row
        // would be invisible history attached to nothing, which is strictly worse than no history.
        root.optJSONArray("noteVersions")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val ownerTitle = o.optString("noteTitle", "")
                val ownerUpdatedAt = o.optLong("noteUpdatedAt", -1L)
                val noteId = noteIdByKey["$ownerTitle\u0000$ownerUpdatedAt"] ?: continue
                db.noteVersionDao().insert(
                    NoteVersion(
                        noteId = noteId,
                        title = o.optString("title", ""),
                        body = o.optString("body", ""),
                        tags = o.optString("tags", ""),
                        isChecklist = o.optBoolean("isChecklist", false),
                        checklist = o.optString("checklist", "[]"),
                        savedAt = o.optLong("savedAt", System.currentTimeMillis())
                    )
                )
                importedVersions++
            }
            // The per-note cap is enforced on the way in as well as on the way out, so a
            // hand-edited backup carrying a thousand revisions of one note can't blow past it.
            noteIdByKey.values.distinct().forEach { id ->
                db.noteVersionDao().trimTo(id, NoteHistory.MAX_VERSIONS_PER_NOTE)
            }
        }

        root.optJSONArray("chats")?.let { arr ->
            // A conversation to hold any messages whose original conversation wasn't in the
            // backup (legacy backups, or hand-edited files). Created lazily on first need so a
            // backup with no such messages doesn't add an empty conversation.
            var fallbackConvId: Long? = null
            suspend fun fallbackConversation(): Long {
                fallbackConvId?.let { return it }
                val id = db.chatConversationDao().insert(ChatConversation(title = com.lucent.app.i18n.S.importedConversationTitle))
                fallbackConvId = id
                return id
            }
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val role = o.optString("role")
                val content = o.optString("content")
                val timestamp = o.optLong("timestamp", System.currentTimeMillis())
                val oldConvId = if (o.has("conversationId")) o.optLong("conversationId", 1) else 1L
                val newConvId = convIdRemap[oldConvId] ?: fallbackConversation()
                val isDuplicate = existingChats.any { it.role == role && it.content == content && it.timestamp == timestamp }
                if (isDuplicate) { skipped++; continue }
                db.chatDao().insert(
                    ChatMessage(
                        role = role,
                        content = content,
                        timestamp = timestamp,
                        attachmentMime = if (o.isNull("attachmentMime")) null else o.optString("attachmentMime"),
                        attachmentData = if (o.isNull("attachmentData")) null else o.optString("attachmentData"),
                        attachmentName = if (o.isNull("attachmentName")) null else o.optString("attachmentName"),
                        conversationId = newConvId
                    )
                )
                importedChats++
            }
        }

        var settingsRestored = false
        root.optJSONObject("settings")?.let { s ->
            settingsRestored = true
            if (s.has("baseUrl")) settings.setBaseUrl(s.optString("baseUrl"))
            if (s.has("apiSpec")) settings.setApiSpec(s.optString("apiSpec"))
            if (s.has("apiKeyEncrypted")) {
                val decrypted = CryptoUtil.decrypt(s.optString("apiKeyEncrypted"))
                if (decrypted.isNotEmpty()) settings.setApiKey(decrypted)
            }
            if (s.has("model")) settings.setModel(s.optString("model"))
            if (s.has("themeMode")) settings.setThemeMode(s.optString("themeMode"))
            if (s.has("palette")) settings.setPalette(s.optString("palette"))
            if (s.has("font")) settings.setFont(s.optString("font"))
            if (s.has("assistantName")) settings.setAssistantName(s.optString("assistantName"))
            if (s.has("assistantStyle")) settings.setAssistantStyle(s.optString("assistantStyle"))
            // Restore all saved API profiles (keys already encrypted inside the JSON) and the
            // selection, re-mirroring the active one into the flat connection keys. Falls back to
            // the flat apiKeyEncrypted above for backups made before multi-API existed.
            if (s.has("apiProfiles")) {
                val profiles = com.lucent.app.data.ApiProfiles.parse(s.optString("apiProfiles"))
                if (profiles.isNotEmpty()) {
                    val sel = s.optInt("apiProfileSelected", 0)
                    settings.saveApiProfiles(profiles, sel)
                }
            }
        }

        // After a JSON import, some rows might reference ids that don't exist on disk (unlikely
        // but not impossible if the backup was hand-edited). And after a ZIP import there may be
        // extra staged attachments the manifest doesn't reference (e.g. duplicates that got
        // skipped by the row-level dedup). Sweep the orphans either way.
        AttachmentMigration.pruneOrphans(context)
        db.noteVersionDao().pruneOrphaned()

        // Alarms are OS state, not data: they are not in the backup and cannot be, so a restored
        // task that wants a reminder has nothing scheduled for it on this device. Re-arm everything
        // now, or a restored reminder would sit silently dead until the task happened to be edited —
        // which is precisely the sort of quiet, invisible failure a restore must not have.
        if (importedTasks > 0) {
            ReminderScheduler.rescheduleAll(context)
        }

        val settingsNote = if (settingsRestored) com.lucent.app.i18n.S.importSettingsRestored else ""
        val historyNote = if (importedVersions > 0) com.lucent.app.i18n.S.importVersionsRestored(importedVersions) else ""
        val dedupNote = if (skipped > 0) com.lucent.app.i18n.S.importDuplicatesSkipped(skipped) else ""
        return com.lucent.app.i18n.S.importSummary(importedNotes, importedTasks, importedChats) + settingsNote + historyNote + dedupNote
    }

    /**
     * If this attachment blob still uses inline Base64 (v7 self-contained JSON, or legacy v5),
     * decode each blob to disk and rewrite it with a disk id. Blobs that already use disk ids (a
     * ZIP manifest, or a row that was already migrated) are returned unchanged.
     */
    private fun migrateInlineAttachmentsIfNeeded(context: Context, attachmentsJson: String): String {
        val list = Attachments.parse(attachmentsJson)
        if (list.isEmpty()) return attachmentsJson
        var changed = false
        val migrated = list.map { att ->
            if (AttachmentStore.looksLikeId(att.data)) return@map att
            val bytes = try {
                android.util.Base64.decode(att.data, android.util.Base64.DEFAULT)
            } catch (t: Throwable) {
                // Undecodable — best we can do is leave the row untouched so the startup
                // migration can retry, rather than replacing it with a UUID that points at
                // nothing on disk. The row simply stays in legacy form.
                return@map att
            }
            // importBytes encrypts on the way to disk.
            val id = AttachmentStore.importBytes(context, bytes)
            if (id != null) { changed = true; att.copy(data = id) } else att
        }
        return if (changed) Attachments.serialize(migrated) else attachmentsJson
    }
}
