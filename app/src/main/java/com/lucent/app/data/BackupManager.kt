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

    /**
     * The parts of a backup a user can include or leave out (task 9).
     *
     * ### Why a backup became selectable at all
     *
     * It used to be one indivisible artefact: everything, always. That is the right default and a
     * poor only-option, because the things inside a backup have wildly different sizes and wildly
     * different sensitivities. Someone moving to a new phone wants all of it. Someone archiving
     * their writing wants notes and nothing else. Someone sending a backup to a second device they
     * share with family very reasonably does not want their API keys in it, and someone whose model
     * file is four gigabytes does not want it in a file they are about to email themselves.
     *
     * Splitting the file into modules answers all four with one mechanism, and — because the reader
     * checks for each section rather than assuming it — a partial file restores exactly as cleanly
     * as a complete one.
     *
     * ### Why the model FILES are their own module
     *
     * [LOCAL_MODEL_FILES] is separate from [LOCAL_ASSISTANT] and off by default. The settings are
     * kilobytes; the payloads are gigabytes. Bundling them would mean nobody could back up their
     * local-assistant configuration without also moving a model file around, which is the sort of
     * all-or-nothing that made the old format worth changing in the first place.
     */
    enum class BackupModule {
        NOTES,
        TASKS,
        CHATS,
        SETTINGS,
        API,
        LOCAL_ASSISTANT,
        LOCAL_MODEL_FILES
    }

    /**
     * Everything except the model files — the sensible default for the export dialog, and exactly
     * what the old unselectable format produced, so an ordinary export is unchanged.
     */
    val DEFAULT_MODULES: Set<BackupModule> =
        BackupModule.entries.toSet() - BackupModule.LOCAL_MODEL_FILES

    /**
     * A backup selection: which modules, and — optionally — which individual notes and tasks.
     *
     * ### Why per-item, on top of per-module
     *
     * Modules answer "I don't want my chats in this file". They cannot answer "I want these four
     * notes", which is the request behind every "can you back up just my journal" — and which the
     * app could already do for *document* export (Settings > Data > choose notes to export) but not
     * for a real restorable backup. So the same granularity now reaches the backup.
     *
     * [noteIds] and [taskIds] are null by default, meaning **everything in that module**, which is
     * both the sensible default and the shape every existing caller already implies. A non-null set
     * is an explicit subset; an EMPTY set is an explicit "none", not "all" — the distinction matters
     * because a user who unticks every note in the second-level list means it.
     *
     * Note history follows its note: unselecting a note takes its revisions with it, since versions
     * of a note you did not back up would restore as history attached to nothing.
     */
    data class BackupSelection(
        val modules: Set<BackupModule> = DEFAULT_MODULES,
        val noteIds: Set<Long>? = null,
        val taskIds: Set<Long>? = null,
        // Which chat conversations and which API profiles ride along (task F1). Same convention as
        // [noteIds]/[taskIds]: null = "everything in that module" (the default and what every prior
        // release did), a non-null set is an explicit subset, and an EMPTY set is an explicit "none".
        //
        // Chats are chosen by CONVERSATION, not by individual message: a conversation is the thing a
        // user can see and name, a message is not, and "back up this chat" always means the thread.
        // Messages follow their conversation, so unselecting a conversation takes its messages with it.
        //
        // API profiles are identified by NAME (task F1 follow-up): the name is what the user sees and
        // chooses in the picker, and matching on it keeps a selection meaningful even if the profile
        // list is reordered. Names are distinct in normal use — the app's auto-naming (nextDefaultName)
        // never repeats one — so a name maps to a single profile; a user who hand-duplicates a name
        // simply selects both, the predictable reading of "include the profiles called X".
        val conversationIds: Set<Long>? = null,
        val apiProfileNames: Set<String>? = null
    ) {
        fun has(m: BackupModule) = m in modules
        fun wantsNote(id: Long) = noteIds?.contains(id) ?: true
        fun wantsTask(id: Long) = taskIds?.contains(id) ?: true
        fun wantsConversation(id: Long) = conversationIds?.contains(id) ?: true
        fun wantsApiProfileName(name: String) = apiProfileNames?.contains(name) ?: true
        /** Nothing at all would be written — the export button stays disabled on this. */
        val isEmpty: Boolean
            get() = modules.isEmpty() ||
                modules.all { m ->
                    when (m) {
                        BackupModule.NOTES -> noteIds?.isEmpty() == true
                        BackupModule.TASKS -> taskIds?.isEmpty() == true
                        BackupModule.CHATS -> conversationIds?.isEmpty() == true
                        BackupModule.API -> apiProfileNames?.isEmpty() == true
                        else -> false
                    }
                }
    }

    // ---------------------------------------------------------------------------------------
    // Payload framing (task 9)
    // ---------------------------------------------------------------------------------------
    //
    // Inside the encrypted envelope the payload used to be, simply, the manifest JSON. Model files
    // cannot travel that way: inlining a 4 GB file as Base64 would inflate it by a third, require
    // the whole thing to exist as one Java String on the way out AND as one parsed JSON document on
    // the way in, and fall over on any phone long before it finished.
    //
    // So the payload is now optionally FRAMED: a length-prefixed manifest followed by raw binary
    // blobs, each with its own name and length. Blobs are streamed straight through — from disk to
    // the cipher on export, from the cipher to disk on import — and never held in memory whole.
    //
    // Backwards compatibility is free and needs no version flag: a legacy payload is raw JSON, so it
    // begins with '{'. The framed payload begins with [FRAME_MAGIC], which is not '{'. One byte
    // tells the reader which it is holding, and every previously written .lcb still restores.
    private const val FRAME_MAGIC = 0x4C.toByte()   // 'L'
    private const val FRAME_VERSION = 1.toByte()

    // Framed blob names are name-spaced by destination: an imported font travels as
    // "font:<fileName>" and is routed to FontStore on restore; any other name is a local model
    // file — which is also what every pre-v11 backup contains, so old files restore unchanged
    // without a version check. The prefix never reaches the filesystem (restore strips it before
    // resolving a target), so no platform's filename rules are in play.
    private const val FONT_BLOB_PREFIX = "font:"

    // Bumped whenever the manifest shape changes. Import reads this only for information; every
    // field added since is read back with a default, so an older manifest inside a `.lcb` still
    // restores cleanly. 8 (this build) covers pin/colour/checklist/trash state, task
    // priority/repeat/reminder/subtasks, and note version history.
    // 9: the settings block became complete — every user-visible preference travels now, not just
    // the API/theme subset it started as (task 17). Purely additive: nothing reads this number to
    // gate behaviour, because every restored key is guarded by has(), so a v8 file restores exactly
    // as it always did and a v9 file restores fully on any build that understands the keys.
    // 10: sections became selectable (task 9). The manifest now carries a "modules" list, each
    // section is written only when its module was chosen, and the local-model slot manifest travels
    // too. Still purely additive on the read side — every section is optional and every key is
    // guarded by has() — so a v8 or v9 file restores exactly as it always did.
    // 11: the imported font library travels (font library task). The settings block gains
    // "fontLibrary" (the FontStore manifest) and the font files themselves ride as framed blobs
    // prefixed with FONT_BLOB_PREFIX whenever the SETTINGS module is chosen — unlike model files
    // they are small enough that opting in per-export would be a question without a point.
    // Additive as ever: every new key is guarded by has(), and unprefixed blobs still restore as
    // model files, so a v10 file restores exactly as it always did.
    private const val BACKUP_VERSION = 11

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
    suspend fun exportJsonFull(
        context: Context,
        db: AppDatabase,
        settings: SettingsRepository,
        selection: BackupSelection = BackupSelection()
    ): String {
        val modules = selection.modules
        // Only read what is actually going into the file. Skipping the query as well as the write
        // is not just tidiness: a notes-only backup on a phone with thousands of chat messages
        // should not pay to load and inline them before throwing them away.
        val notes = if (BackupModule.NOTES in modules) {
            db.noteDao().getAllOnce().filter { selection.wantsNote(it.id) }
        } else emptyList()
        val tasks = if (BackupModule.TASKS in modules) {
            db.taskDao().getAllOnce().filter { selection.wantsTask(it.id) }
        } else emptyList()
        // History is filtered by the notes that survived, not by the selection directly, so a
        // deselected note cannot leave orphaned revisions in the file.
        val keptNoteIds = notes.map { it.id }.toHashSet()
        val noteVersions = if (BackupModule.NOTES in modules) {
            db.noteVersionDao().getAllOnce().filter { it.noteId in keptNoteIds }
        } else emptyList()
        // Conversations narrowed to the chosen subset (task F1); a null selection keeps them all.
        val conversations =
            if (BackupModule.CHATS in modules) {
                db.chatConversationDao().getAllOnce().filter { selection.wantsConversation(it.id) }
            } else emptyList()
        // Messages follow the conversations that survived: a message whose conversation was unticked
        // (or a legacy orphan message whose conversation id wasn't chosen) is dropped, so a
        // per-conversation choice never leaves stray messages behind. A null selection keeps every
        // message exactly as before.
        val chats = if (BackupModule.CHATS in modules) {
            db.chatDao().getAll().first().filter { selection.wantsConversation(it.conversationId) }
        } else emptyList()
        return buildManifest(
            context, notes, tasks, noteVersions, chats, conversations, settings,
            inlineAttachments = true, modules = modules, apiProfileNames = selection.apiProfileNames
        ).toString(2)
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
        password: String?,
        selection: BackupSelection = BackupSelection()
    ) {
        val modules = selection.modules
        val json = exportJsonFull(context, db, settings, selection)
        val jsonBytes = json.toByteArray(Charsets.UTF_8)

        // Which model files, if any, ride along as framed blobs after the manifest.
        val modelFiles: List<Pair<String, java.io.File>> =
            if (BackupModule.LOCAL_MODEL_FILES in modules) {
                com.lucent.app.local.LocalModelStore.slots(context).mapNotNull { slot ->
                    com.lucent.app.local.LocalModelStore.modelFileForSlot(context, slot)
                        ?.let { slot.fileName to it }
                }
            } else emptyList()

        // Which imported font files ride along, name-spaced with FONT_BLOB_PREFIX so restore can
        // route them to FontStore. Fonts travel with the SETTINGS module — they are what the
        // "font" preference points at, and a restored appearance that silently dropped back to the
        // system font would be the settings equivalent of an attachment restored as a dead id.
        val fontFiles: List<Pair<String, java.io.File>> =
            if (BackupModule.SETTINGS in modules) {
                FontStore.fonts(context).mapNotNull { slot ->
                    FontStore.fontFileForSlot(context, slot)
                        ?.let { (FONT_BLOB_PREFIX + slot.fileName) to it }
                }
            } else emptyList()

        val blobs = modelFiles + fontFiles
        BackupCrypto.encryptingStream(out, password).use { cipherOut ->
            if (blobs.isEmpty()) {
                // No blobs: write the plain JSON payload, byte-for-byte the format every previous
                // release produced. An ordinary backup is therefore completely unchanged, and an
                // older build could still read it.
                cipherOut.write(jsonBytes)
                return@use
            }
            cipherOut.write(byteArrayOf(FRAME_MAGIC, FRAME_VERSION))
            writeInt(cipherOut, jsonBytes.size)
            cipherOut.write(jsonBytes)
            val buffer = ByteArray(1 shl 16)
            for ((name, file) in blobs) {
                val nameBytes = name.toByteArray(Charsets.UTF_8)
                writeInt(cipherOut, nameBytes.size)
                cipherOut.write(nameBytes)
                writeLong(cipherOut, file.length())
                // Streamed in 64 KB pieces: a multi-gigabyte model passes through this loop without
                // ever existing in memory as a whole, which is the entire reason for the framing.
                // Fonts share the pipe for uniformity, not need — they are merely megabytes.
                file.inputStream().use { input ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        cipherOut.write(buffer, 0, n)
                    }
                }
            }
        }
        // Diagnostic breadcrumb (task F4). Counts and flags only — never a title, a key, or any
        // content — so the log stays safe to share even after an encrypted export. A no-op unless the
        // user turned logging on. Per-item counts appear only when that dimension was narrowed.
        StartupLog.event(
            context,
            "Backup exported: modules=${modules.joinToString(",") { it.name }}" +
                (selection.noteIds?.let { "; notes=${it.size}" } ?: "") +
                (selection.taskIds?.let { "; tasks=${it.size}" } ?: "") +
                (selection.conversationIds?.let { "; chats=${it.size}" } ?: "") +
                (selection.apiProfileNames?.let { "; apiProfiles=${it.size}" } ?: "") +
                "; models=${modelFiles.size}; fonts=${fontFiles.size}" +
                "; password=${if (password.isNullOrEmpty()) "no" else "yes"}"
        )
    }

    private fun writeInt(out: OutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF); out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF); out.write(value and 0xFF)
    }

    private fun writeLong(out: OutputStream, value: Long) {
        for (shift in 56 downTo 0 step 8) out.write(((value ushr shift) and 0xFF).toInt())
    }

    /**
     * Split a payload into its manifest and (if framed) the byte range where the blobs begin.
     *
     * Returns the manifest JSON plus the offset of the first blob, or -1 when the payload is a
     * legacy plain-JSON one with no blobs at all.
     */
    private fun readPayload(payload: ByteArray): Pair<String, Int> {
        val framed = payload.size > 6 && payload[0] == FRAME_MAGIC && payload[1] == FRAME_VERSION
        if (!framed) return payload.toString(Charsets.UTF_8) to -1
        val jsonLen = readInt(payload, 2)
        val start = 6
        if (jsonLen < 0 || start + jsonLen > payload.size) {
            // A truncated or corrupt frame: fall back to treating the whole thing as JSON so the
            // failure surfaces as "this backup couldn't be read" rather than as a slice exception.
            return payload.toString(Charsets.UTF_8) to -1
        }
        val json = String(payload, start, jsonLen, Charsets.UTF_8)
        return json to (start + jsonLen)
    }

    private fun readInt(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

    private fun readLong(b: ByteArray, at: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
        return v
    }

    /**
     * Write every framed blob out to its destination, returning (model files, fonts) written.
     *
     * A blob's name carries its destination: a [FONT_BLOB_PREFIX]-prefixed name is an imported
     * font (routed to FontStore with the prefix stripped), anything else is a local model file —
     * which is also what every pre-v11 backup contains, so old files keep restoring without a
     * version check. Each side is written only when its module was chosen, and each blob is
     * deliberately best-effort: one unwritable file (out of space, a name the filesystem rejects)
     * must not abort a restore that has already put the user's notes back.
     */
    private fun restoreBlobs(
        context: Context,
        payload: ByteArray,
        from: Int,
        wantModels: Boolean,
        wantFonts: Boolean
    ): Pair<Int, Int> {
        var at = from
        var models = 0
        var fonts = 0
        while (at + 4 <= payload.size) {
            val nameLen = readInt(payload, at); at += 4
            if (nameLen <= 0 || at + nameLen + 8 > payload.size) break
            val name = String(payload, at, nameLen, Charsets.UTF_8); at += nameLen
            val dataLen = readLong(payload, at); at += 8
            if (dataLen < 0 || at + dataLen > payload.size) break
            val isFont = name.startsWith(FONT_BLOB_PREFIX)
            if ((isFont && wantFonts) || (!isFont && wantModels)) {
                try {
                    val target = if (isFont) {
                        FontStore.prepareRestoreTarget(context, name.removePrefix(FONT_BLOB_PREFIX))
                    } else {
                        com.lucent.app.local.LocalModelStore.prepareRestoreTarget(context, name)
                    }
                    // Straight to a temp file and renamed into place, so an interrupted restore can
                    // never leave a half-written payload that the app would then try to load.
                    val tmp = java.io.File(target.absolutePath + ".tmp")
                    tmp.outputStream().use { out -> out.write(payload, at, dataLen.toInt()) }
                    if (target.exists()) target.delete()
                    if (tmp.renameTo(target)) {
                        if (isFont) fonts++ else models++
                    } else tmp.delete()
                } catch (_: Throwable) {
                }
            }
            at += dataLen.toInt()
        }
        return models to fonts
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
        inlineAttachments: Boolean,
        modules: Set<BackupModule> = DEFAULT_MODULES,
        // Null = every saved API profile travels (unchanged). A non-null set of profile NAMES narrows
        // the API section to just those profiles (task F1); see the API block below.
        apiProfileNames: Set<String>? = null
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
                    // The per-turn token estimate (task F3 completeness). Absent from older files, so
                    // import reads it back with a 0 default — exactly the value a pre-token row had.
                    .put("tokens", it.tokens)
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

        // The settings block is assembled in three independently selectable parts (task 9): the
        // connection/credentials (API), everything about how the app looks and behaves (SETTINGS),
        // and the on-device assistant's own switches plus its slot manifest (LOCAL_ASSISTANT).
        // Each key is written only when its module is in, and every key is read back guarded by
        // has() — so a file missing a section leaves those preferences untouched rather than
        // stamping defaults over them.
        val wantApi = BackupModule.API in modules
        val wantSettings = BackupModule.SETTINGS in modules
        val wantLocal = BackupModule.LOCAL_ASSISTANT in modules

        val settingsObj = JSONObject()
        // The API section, optionally narrowed to a chosen subset of profiles (task F1).
        //
        // When no per-profile choice was made ([apiProfileNames] == null) or there are no saved
        // profiles to narrow, the whole API state travels exactly as it always did: the flat
        // connection keys mirror the active profile and every saved profile is included.
        //
        // When a subset was chosen, only those profiles travel; the selected index is remapped into
        // the kept list, and the flat mirror keys are taken from whichever profile ends up selected
        // there — so a profile the user deliberately excluded can never leak out through the legacy
        // flat fields. The profile JSON already stores each key encrypted (see ApiProfiles.serialize),
        // and the flat key is sealed with CryptoUtil the same way it always was.
        if (wantApi) {
            val rawProfilesJson = settings.apiProfilesJson.first()
            val allProfiles = ApiProfiles.parse(rawProfilesJson)
            if (apiProfileNames == null || allProfiles.isEmpty()) {
                settingsObj
                    .put("baseUrl", settings.baseUrl.first())
                    .put("apiSpec", settings.apiSpec.first())
                    .put("apiKeyEncrypted", CryptoUtil.encrypt(settings.apiKey.first()))
                    .put("model", settings.model.first())
                    .put("apiProfiles", rawProfilesJson)
                    .put("apiProfileSelected", settings.apiProfileSelected.first())
            } else {
                val origSelected = settings.apiProfileSelected.first()
                val keptIndexed = allProfiles.withIndex().filter { it.value.name in apiProfileNames }
                val keptProfiles = keptIndexed.map { it.value }
                val newSelected =
                    keptIndexed.indexOfFirst { it.index == origSelected }.let { if (it >= 0) it else 0 }
                val mirror = keptProfiles.getOrNull(newSelected)
                settingsObj
                    .put("baseUrl", mirror?.baseUrl ?: "")
                    .put("apiSpec", mirror?.spec ?: "openai")
                    .put("apiKeyEncrypted", CryptoUtil.encrypt(mirror?.apiKey ?: ""))
                    .put("model", mirror?.model ?: "")
                    .put("apiProfiles", ApiProfiles.serializeForBackup(keptProfiles))
                    .put("apiProfileSelected", newSelected)
            }
        }
        if (wantSettings) settingsObj
            .put("themeMode", settings.themeMode.first())
            .put("palette", settings.palette.first())
            .put("font", settings.font.first())
            // The imported font library's manifest: which fonts exist and what the user named
            // them. The font *files* ride as framed blobs (see exportEncrypted); this is the
            // labelling half, kept in the settings block the same way the model slot manifest is
            // kept in the local-assistant block — names without files restore as an honest nothing,
            // files without names would restore as anonymous blobs.
            .put("fontLibrary", FontStore.exportManifestJson(context))
            .put("assistantName", settings.assistantName.first())
            .put("assistantStyle", settings.assistantStyle.first())
        // (The multi-API profile JSON is written in the consolidated API block above, task F1.)
        if (wantSettings) settingsObj
            // ---- Everything else the app remembers about how it should behave (task 17) ----
            //
            // The block above is the original backup, written when settings *were* the API and the
            // theme. Several releases have added preferences since, and each one silently widened
            // the gap between "a backup holds everything" — which the Data page promises in those
            // words — and what a restore actually put back. A user who restored onto a new phone got
            // their notes and their API key and then found the app in a language they hadn't chosen,
            // with Markdown off, links off, their sort orders reset and the assistant's memory tier
            // back to default. Nothing was lost that could not be re-set by hand, which is precisely
            // why it went unnoticed for so long, and precisely why it was worth fixing: a backup you
            // have to spend twenty minutes correcting is not a backup, it is a starting point.
            //
            // So every user-visible preference the app stores now travels with the file. Three
            // things are deliberately still excluded, and each for a reason that would survive the
            // question "why isn't this in my backup?":
            //
            //  - **App-lock credentials.** The hashes are sealed with a key that lives in THIS
            //    device's hardware Keystore and cannot leave it. Putting them in a portable file
            //    would ship material that is unusable on the restoring device at best, and at worst
            //    would lock someone out of the app on a phone where the recovery answer can't be
            //    verified. The lock is re-set after a restore, on purpose.
            //  - **The backup password.** Storing the password for a file inside that same file is
            //    not encryption, it is theatre.
            //  - **attachments_migrated.** A one-shot marker about *this* install's disk layout. The
            //    migrator re-derives it correctly on first launch; carrying a stale one across
            //    devices could skip a migration that the new device still needs.
            .put("memoryTier", settings.memoryTier.first())
            .put("webSearchEnabled", settings.webSearchEnabled.first())
            .put("typingHaptics", settings.typingHapticsEnabled.first())
            .put("markdownEnabled", settings.markdownEnabled.first())
            .put("linksEnabled", settings.linksEnabled.first())
            .put("backgroundAnimationEnabled", settings.backgroundAnimationEnabled.first())
            .put("appLanguage", settings.appLanguage.first())
            .put("notesSort", settings.notesSort.first())
            .put("tasksSort", settings.tasksSort.first())
            .put("systemIntegrationEnabled", settings.systemIntegrationEnabled.first())
            .put("startupLoggingEnabled", settings.startupLoggingEnabled.first())

        // The local-assistant switches, plus — new in v10 — the model SLOT MANIFEST: the names the
        // user gave their models and which one was active.
        //
        // The manifest was the quiet omission behind "backup doesn't cover the local assistant".
        // Even a user who accepted that a 4 GB file cannot live in a backup lost the labelling of
        // their models on restore, which is the part that made three interchangeable-looking blobs
        // tell-apart-able. It is a few hundred bytes; there was never a reason for it to be absent.
        if (wantLocal) settingsObj
            .put("localModelEnabled", settings.localModelEnabled.first())
            .put("localToolsEnabled", settings.localToolsEnabled.first())
            .put("localGpuEnabled", settings.localGpuEnabled.first())
            .put("localBackgroundReply", settings.localBackgroundReplyEnabled.first())
            .put("localModelManifest", com.lucent.app.local.LocalModelStore.exportManifestJson(context))

        val root = JSONObject()
            .put("version", BACKUP_VERSION)
            .put("exportedAt", System.currentTimeMillis())
            // Which modules this file actually claims to carry. Import shows it, so a restore can
            // say "this backup has no tasks in it" instead of silently restoring nothing and
            // leaving the user to work out whether that was the file or the app.
            .put("modules", JSONArray().apply { modules.forEach { put(it.name) } })
        if (BackupModule.NOTES in modules) root.put("notes", notesArray).put("noteVersions", versionsArray)
        if (BackupModule.TASKS in modules) root.put("tasks", tasksArray)
        if (BackupModule.CHATS in modules) root.put("chats", chatsArray).put("conversations", conversationsArray)
        if (settingsObj.length() > 0) root.put("settings", settingsObj)
        return root
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
        val hasSettings: Boolean,
        /**
         * The modules this file claims to carry. Empty for a pre-v10 backup, which predates the
         * concept entirely — the UI treats that as "everything the sections show" rather than as
         * "nothing", because an old file really did contain all of it.
         */
        val modules: Set<BackupModule> = emptySet(),
        /** How many local model files are attached as framed blobs, and their combined size. */
        val modelFiles: Int = 0,
        val modelBytes: Long = 0L,
        /** How many imported fonts are attached as framed blobs, and their combined size. */
        val fontFiles: Int = 0,
        val fontBytes: Long = 0L,
        /** Where the blobs start in the decrypted payload, or -1 when there are none. */
        internal val blobOffset: Int = -1,
        /** The decrypted payload, retained only when there are blobs to write out on commit. */
        internal val payload: ByteArray? = null,
        /**
         * The conversations found in the file, as (id, title). Drives the import-side chat picker
         * (task F2), exactly as the loaded conversation list drives the export-side one. Empty for a
         * file with no chats, or one predating conversations.
         */
        val conversationList: List<Pair<Long, String>> = emptyList(),
        /**
         * The API profile NAMES found in the file. Drives the import-side API picker (task F2). Empty
         * for a legacy single-API backup (which carries only the flat keys, not a named profile list)
         * — matching the export side, where a file with no named profiles offers no per-profile choice.
         */
        val apiProfileNames: List<String> = emptyList()
    ) {
        /** True when the file parsed but holds nothing worth restoring. */
        val isEmpty: Boolean
            get() = notes == 0 && tasks == 0 && chatMessages == 0 && conversations == 0 &&
                !hasSettings && modelFiles == 0 && fontFiles == 0
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

        val payload = BackupCrypto.decrypt(bytes, password)
        val (manifestJson, blobOffset) = readPayload(payload)

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

        // Walk the blob frames to count and size them, WITHOUT copying any of the payload bytes —
        // the preview only needs to be able to say "2 model files, 3.1 GB; 4 imported fonts",
        // which is exactly the fact a user needs before agreeing to a restore of that size. The
        // short blob NAMES are read, purely to tell font blobs from model blobs by their prefix.
        var modelCount = 0
        var modelBytes = 0L
        var fontCount = 0
        var fontBytes = 0L
        if (blobOffset >= 0) {
            var at = blobOffset
            while (at + 4 <= payload.size) {
                val nameLen = readInt(payload, at); at += 4
                if (nameLen <= 0 || at + nameLen + 8 > payload.size) break
                val name = String(payload, at, nameLen, Charsets.UTF_8); at += nameLen
                val dataLen = readLong(payload, at); at += 8
                if (dataLen < 0 || at + dataLen > payload.size) break
                if (name.startsWith(FONT_BLOB_PREFIX)) {
                    fontCount++
                    fontBytes += dataLen
                } else {
                    modelCount++
                    modelBytes += dataLen
                }
                at += dataLen.toInt()
            }
        }

        // The conversations and API profile names in the file, for the import-side pickers (task F2).
        // Parsed here (not counted), so the confirm dialog can offer "restore only these chats / these
        // APIs" the same way the export dialog offers "back up only these".
        val convList = root.optJSONArray("conversations")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optLong("id", 0L)
                if (id == 0L) null else id to o.optString("title", "")
            }
        } ?: emptyList()
        val profileNames = root.optJSONObject("settings")?.optString("apiProfiles")?.let { pj ->
            if (pj.isBlank()) emptyList() else ApiProfiles.parse(pj).map { it.name }
        } ?: emptyList()

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
            hasSettings = root.optJSONObject("settings") != null,
            modules = root.optJSONArray("modules")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    runCatching { BackupModule.valueOf(arr.optString(i)) }.getOrNull()
                }.toSet()
            } ?: emptySet(),
            modelFiles = modelCount,
            modelBytes = modelBytes,
            fontFiles = fontCount,
            fontBytes = fontBytes,
            blobOffset = blobOffset,
            payload = if (blobOffset >= 0) payload else null,
            conversationList = convList,
            apiProfileNames = profileNames
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
        preview: BackupPreview,
        modules: Set<BackupModule> = BackupModule.entries.toSet(),
        // Per-item restore choices (task F2), same convention as the export selection: null = restore
        // everything in that module, a non-null set is an explicit subset. Chats by conversation id,
        // API by profile name — the same handles the export side and the preview lists use.
        conversationIds: Set<Long>? = null,
        apiProfileNames: Set<String>? = null
    ): String {
        // Blobs first, and deliberately so: the model slot manifest and the font library manifest
        // are each adopted only for files actually present on disk (see the two restoreFromBackup
        // implementations), so the payloads have to land before the settings block is read or
        // every restored slot would be discarded as dangling. Model files restore under their own
        // opt-in module; fonts restore with the SETTINGS module they travelled with.
        var restoredModels = 0
        var restoredFonts = 0
        val payload = preview.payload
        if (payload != null && preview.blobOffset >= 0) {
            val wantModels = BackupModule.LOCAL_MODEL_FILES in modules
            val wantFonts = BackupModule.SETTINGS in modules
            if (wantModels || wantFonts) {
                val (m, f) = restoreBlobs(context, payload, preview.blobOffset, wantModels, wantFonts)
                restoredModels = m
                restoredFonts = f
            }
        }
        val summary = importJson(
            context, db, settings, preview.manifestJson, modules, conversationIds, apiProfileNames
        )
        // Diagnostic breadcrumb (task F4): what a restore touched, counts and flags only. A no-op
        // unless logging is on.
        StartupLog.event(
            context,
            "Backup restored: modules=${modules.joinToString(",") { it.name }}" +
                (conversationIds?.let { "; chats=${it.size}" } ?: "") +
                (apiProfileNames?.let { "; apiProfiles=${it.size}" } ?: "") +
                "; models=$restoredModels; fonts=$restoredFonts"
        )
        var report = summary
        if (restoredModels > 0) report += com.lucent.app.i18n.S.backupModelFilesRestored(restoredModels)
        if (restoredFonts > 0) report += com.lucent.app.i18n.S.backupFontsRestored(restoredFonts)
        return report
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
        json: String,
        modules: Set<BackupModule> = BackupModule.entries.toSet(),
        // Per-item restore choices (task F2): null = everything in that module, a non-null set is an
        // explicit subset. Chats by conversation id, API by profile name.
        conversationIds: Set<Long>? = null,
        apiProfileNames: Set<String>? = null
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
        val wantNotes = BackupModule.NOTES in modules
        val wantTasks = BackupModule.TASKS in modules
        val wantChats = BackupModule.CHATS in modules
        (if (wantChats) root.optJSONArray("conversations") else null)?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val oldId = o.optLong("id", 0)
                // Selective restore (task F2): skip a conversation the user didn't tick. Its messages
                // are dropped below too, since they'd otherwise fall through to the "unknown
                // conversation" bucket and reappear under a fresh thread the user didn't ask for.
                if (conversationIds != null && oldId !in conversationIds) continue
                val title = o.optString("title", "Conversation")
                val createdAt = o.optLong("createdAt", System.currentTimeMillis())
                val updatedAt = o.optLong("updatedAt", createdAt)
                val newId = db.chatConversationDao().insert(
                    ChatConversation(title = title, createdAt = createdAt, updatedAt = updatedAt)
                )
                if (oldId != 0L) convIdRemap[oldId] = newId
            }
        }

        (if (wantNotes) root.optJSONArray("notes") else null)?.let { arr ->
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
        (if (wantTasks) root.optJSONArray("tasks") else null)?.let { arr ->
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
        (if (wantNotes) root.optJSONArray("noteVersions") else null)?.let { arr ->
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

        (if (wantChats) root.optJSONArray("chats") else null)?.let { arr ->
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
                // Selective restore (task F2): drop a message whose conversation the user didn't tick.
                if (conversationIds != null && oldConvId !in conversationIds) continue
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
                        conversationId = newConvId,
                        tokens = o.optInt("tokens", 0)
                    )
                )
                importedChats++
            }
        }

        var settingsRestored = false
        root.optJSONObject("settings")?.let { s ->
            // Each half of the settings block is gated on its own module (task 9), so "restore my
            // preferences but not the API keys from this shared backup" is a real option rather
            // than something the user has to achieve by editing the file.
            val restoreApi = BackupModule.API in modules
            val restoreGeneral = BackupModule.SETTINGS in modules
            val restoreLocal = BackupModule.LOCAL_ASSISTANT in modules
            settingsRestored = restoreApi || restoreGeneral || restoreLocal
            // The flat connection keys are the legacy single-API mirror. They're restored only on a
            // WHOLE-API restore (apiProfileNames == null). On a selective one they're deliberately
            // skipped: the file's flat keys mirror whichever profile was active at export, which may
            // be a profile the user didn't pick — writing it would leak an unselected key. The kept
            // profiles below re-mirror the flat keys correctly instead.
            val wholeApi = restoreApi && apiProfileNames == null
            if (wholeApi && s.has("baseUrl")) settings.setBaseUrl(s.optString("baseUrl"))
            if (wholeApi && s.has("apiSpec")) settings.setApiSpec(s.optString("apiSpec"))
            if (wholeApi && s.has("apiKeyEncrypted")) {
                val decrypted = CryptoUtil.decrypt(s.optString("apiKeyEncrypted"))
                if (decrypted.isNotEmpty()) settings.setApiKey(decrypted)
            }
            if (wholeApi && s.has("model")) settings.setModel(s.optString("model"))
            if (restoreGeneral && s.has("themeMode")) settings.setThemeMode(s.optString("themeMode"))
            if (restoreGeneral && s.has("palette")) settings.setPalette(s.optString("palette"))
            // The imported font library. Adopted BEFORE the font key below, and only for files this
            // restore actually delivered (see FontStore.restoreFromBackup — the blobs were written
            // before importJson was even called), so the key can be validated against what really
            // exists on this device.
            if (restoreGeneral && s.has("fontLibrary")) {
                try {
                    FontStore.restoreFromBackup(context, s.optString("fontLibrary"))
                } catch (_: Throwable) {
                }
            }
            if (restoreGeneral && s.has("font")) {
                // Never restore the font key into a lie: a backup made where a font existed may be
                // restored where its file did not land. "system" and any id that resolves are kept;
                // anything else becomes "system", so the picker never shows a selection that cannot
                // render. The same confirmed-against-reality shape as localModelEnabled below.
                val wantFont = s.optString("font")
                val resolvable = wantFont == "system" ||
                    runCatching { FontStore.fontFile(context, wantFont) }.getOrNull() != null
                settings.setFont(if (resolvable) wantFont else "system")
            }
            if (restoreGeneral && s.has("assistantName")) settings.setAssistantName(s.optString("assistantName"))
            if (restoreGeneral && s.has("assistantStyle")) settings.setAssistantStyle(s.optString("assistantStyle"))
            // API profiles (keys already encrypted inside the JSON).
            //
            //  - WHOLE restore (apiProfileNames == null): unchanged — the file's profiles replace the
            //    current set and the selected one is re-mirrored into the flat keys.
            //  - SELECTIVE restore (task F2): MERGE the chosen profiles into the current set rather
            //    than replacing it — appending only names not already present, capped at the max — so
            //    pulling one API out of a shared backup never wipes the APIs already on this device.
            if (restoreApi && s.has("apiProfiles")) {
                val parsed = com.lucent.app.data.ApiProfiles.parse(s.optString("apiProfiles"))
                if (apiProfileNames == null) {
                    if (parsed.isNotEmpty()) {
                        settings.saveApiProfiles(parsed, s.optInt("apiProfileSelected", 0))
                    }
                } else {
                    val chosen = parsed.filter { it.name in apiProfileNames }
                    if (chosen.isNotEmpty()) {
                        val current = com.lucent.app.data.ApiProfiles.parse(settings.apiProfilesJson.first())
                        val existingNames = current.map { it.name }.toHashSet()
                        val merged = (current + chosen.filter { it.name !in existingNames })
                            .take(com.lucent.app.data.ApiProfiles.MAX)
                        val sel = settings.apiProfileSelected.first()
                            .coerceIn(0, (merged.size - 1).coerceAtLeast(0))
                        settings.saveApiProfiles(merged, sel)
                    }
                }
            }

            // ---- The rest of the preferences (task 17) ----
            //
            // Each is guarded by has(): an OLDER backup simply doesn't carry these keys, and a
            // restore from one must leave the current value alone rather than stamping a default
            // over it. That is what makes this change safe in both directions — a new app reading
            // an old file changes nothing it wasn't told about.
            if (restoreGeneral) {
                if (s.has("memoryTier")) settings.setMemoryTier(s.optString("memoryTier"))
                if (s.has("webSearchEnabled")) settings.setWebSearchEnabled(s.optBoolean("webSearchEnabled"))
                if (s.has("typingHaptics")) settings.setTypingHapticsEnabled(s.optBoolean("typingHaptics", true))
                if (s.has("markdownEnabled")) settings.setMarkdownEnabled(s.optBoolean("markdownEnabled"))
                if (s.has("linksEnabled")) settings.setLinksEnabled(s.optBoolean("linksEnabled"))
                if (s.has("backgroundAnimationEnabled")) {
                    settings.setBackgroundAnimationEnabled(s.optBoolean("backgroundAnimationEnabled", true))
                }
                if (s.has("appLanguage")) settings.setAppLanguage(s.optString("appLanguage"))
                if (s.has("notesSort")) settings.setNotesSort(s.optString("notesSort"))
                if (s.has("tasksSort")) settings.setTasksSort(s.optString("tasksSort"))
            }
            if (restoreLocal && s.has("localBackgroundReply")) {
                settings.setLocalBackgroundReplyEnabled(s.optBoolean("localBackgroundReply"))
            }

            // System integration is a preference AND an OS-level component state; restoring the flag
            // without flipping the manifest component would leave the app claiming a share-sheet
            // entry it doesn't have. Both move together, exactly as the Privacy toggle does.
            if (restoreGeneral && s.has("systemIntegrationEnabled")) {
                val shareOn = s.optBoolean("systemIntegrationEnabled")
                settings.setSystemIntegrationEnabled(shareOn)
                ShareIntegration.setEnabled(context, shareOn)
            }
            // Same shape for logging: the flag and the live logger are one decision.
            if (restoreGeneral && s.has("startupLoggingEnabled")) {
                val loggingOn = s.optBoolean("startupLoggingEnabled")
                settings.setStartupLoggingEnabled(loggingOn)
                StartupLog.setEnabled(loggingOn)
            }

            // Local model: restore the intent, but never restore it into a lie. The GGUF file MAY
            // now travel with the backup (the LOCAL_MODEL_FILES module, task 9) but usually will
            // not, and either way what matters here is the same check: on a phone with no model
            // actually on disk, honouring a stored "local model on" would hand the user an
            // assistant that cannot answer anything and an API page frozen shut with no visible
            // cause. So the flag is confirmed against reality rather than trusted. When there is no
            // model, local mode stays off and the cloud API keeps working — the honest state for
            // that device. Note the ordering dependency: the slot manifest above, and the model
            // blobs written before importJson was even called, both run first precisely so that
            // hasModel() here sees what this restore just delivered.
            // Adopt the backed-up slot manifest before the enable flag is decided, so hasModel()
            // below sees any model files this restore just put on disk. Slots whose file is absent
            // are dropped inside restoreFromBackup — a restore without the model-files module puts
            // the switches back but honestly reports no models.
            if (restoreLocal && s.has("localModelManifest")) {
                try {
                    com.lucent.app.local.LocalModelStore.restoreFromBackup(
                        context, s.optString("localModelManifest")
                    )
                } catch (_: Throwable) {
                }
            }
            if (restoreLocal && s.has("localModelEnabled")) {
                val wantLocal = s.optBoolean("localModelEnabled")
                val hasModel = try {
                    com.lucent.app.local.LocalModelStore.hasModel(context)
                } catch (t: Throwable) {
                    false
                }
                settings.setLocalModelEnabled(wantLocal && hasModel)
                // Tools/GPU are only meaningful with local mode actually on. setLocalModelEnabled
                // has just reset both to off (task 1), so re-applying the backed-up values here
                // would fight that rule; they are restored only when local mode really came back.
                if (wantLocal && hasModel) {
                    if (s.has("localToolsEnabled")) settings.setLocalToolsEnabled(s.optBoolean("localToolsEnabled"))
                    if (s.has("localGpuEnabled")) settings.setLocalGpuEnabled(s.optBoolean("localGpuEnabled"))
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
