package com.lucent.app.local

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.lucent.app.data.LocalSecrets
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Where imported local models live, and how they get there.
 *
 * The feature is still "pick a file, done" — no paths, no server, no config — but it now holds up to
 * [MAX_MODELS] models at once. Each model is a *slot* with a user-editable name and its own `.gguf`
 * file; the user picks which slot is *active*, and only that one is ever loaded into memory. So:
 *
 *  - The user picks any file in the system picker. A plain `.gguf` is copied straight in; a `.zip`
 *    is looked through and the first `.gguf` inside is extracted automatically, so a model
 *    downloaded as an archive needs no manual unpacking.
 *  - The bytes are **verified to actually be GGUF** (magic `GGUF`) before anything is kept — a
 *    wrong file is rejected with a clear message instead of surfacing as a native crash later.
 *  - The copy lands in a temp file and is renamed into place only when complete, so a cancelled or
 *    failed import can never leave a half-written model that the engine then chokes on.
 *  - Up to three slots are kept ([MAX_MODELS]); importing when full is refused with a clear error.
 *    A freshly imported model becomes the active one automatically.
 *  - **Only one model is resident at a time.** The engine loads whichever slot is active; switching
 *    the active slot (or deleting/replacing it) makes the engine release the outgoing model's memory
 *    before the next generation loads the incoming one (see [LocalLlm.ensureLoaded]).
 *
 * All state lives in [INDEX_FILE] (a small JSON manifest) beside the `.gguf` files. Nothing about
 * models is stored in app settings, so this class is fully self-contained.
 */
object LocalModelStore {

    /** Maximum number of local models a user can keep imported at once (task requirement). */
    const val MAX_MODELS = 3

    private const val DIR = "local_model"

    // The JSON manifest of slots and which one is active. Sits beside the .gguf files it describes.
    private const val INDEX_FILE = "models.json"

    // Legacy single-model layout (before multi-model support). Migrated into a slot on first read.
    private const val LEGACY_FILE_NAME = "model.gguf"
    private const val LEGACY_NAME_FILE = "model.name"

    private val GGUF_MAGIC = byteArrayOf(0x47, 0x47, 0x55, 0x46) // "GGUF"

    class NotGgufException : IOException("Not a GGUF model file")
    class NoGgufInZipException : IOException("No .gguf file inside the zip")
    class TooManyModelsException : IOException("Model slots are full")

    /**
     * One imported model. [id] is a stable, filesystem-safe token used to name the model's file and
     * to reference it; [name] is the user-facing label the user can edit; [fileName] is the actual
     * on-disk file (kept in the manifest so a legacy model can point at the old `model.gguf`).
     */
    data class ModelSlot(val id: String, val name: String, val fileName: String)

    // A tiny in-memory snapshot of the manifest, so a page can render slots + active id together.
    data class ModelIndex(val slots: List<ModelSlot>, val activeId: String?)

    private fun dir(context: Context): File = File(context.filesDir, DIR)

    // ---------------------------------------------------------------------------------------
    // Manifest read / write
    // ---------------------------------------------------------------------------------------

    /**
     * Read the manifest, migrating a legacy single-model layout into a slot the first time it is
     * seen. Never throws — a corrupt manifest reads as "no models" rather than taking the app down.
     */
    @Synchronized
    fun index(context: Context): ModelIndex {
        val dir = dir(context)
        val indexFile = File(dir, INDEX_FILE)

        if (!indexFile.exists()) {
            // First run on the multi-model layout: adopt any legacy model.gguf as the first slot so
            // an upgrading user keeps the model they already imported.
            val legacy = File(dir, LEGACY_FILE_NAME)
            if (legacy.exists() && legacy.length() > 0L) {
                val legacyName = File(dir, LEGACY_NAME_FILE).let {
                    if (it.exists()) it.readText().trim().ifBlank { null } else null
                } ?: "model.gguf"
                val slot = ModelSlot(id = newId(), name = legacyName, fileName = LEGACY_FILE_NAME)
                val migrated = ModelIndex(listOf(slot), slot.id)
                writeIndex(context, migrated)
                File(dir, LEGACY_NAME_FILE).delete() // its content now lives in the manifest
                return migrated
            }
            return ModelIndex(emptyList(), null)
        }

        return try {
            // Sealed by writeIndex. LocalSecrets.decrypt returns the input unchanged when it has no
            // envelope prefix, so a manifest written by an older build (plain JSON) still reads —
            // and is re-sealed the next time anything writes it.
            val raw = indexFile.readText()
            val decrypted = LocalSecrets.decrypt(raw)
            // decrypt() returns "" when a genuinely sealed value cannot be opened — the Keystore key
            // having been invalidated by a lock-screen change is the realistic case. Falling through
            // to the catch below would report "no models", and the user would open Settings to find
            // every model gone while several gigabytes still sat on disk. Rebuild from the files
            // instead: the names are lost, the models are not.
            if (decrypted.isEmpty() && raw.isNotEmpty()) return rebuildFromFiles(context)
            val root = JSONObject(decrypted)
            val arr = root.optJSONArray("slots") ?: JSONArray()
            val slots = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id", "").ifBlank { return@mapNotNull null }
                val fileName = o.optString("file", "").ifBlank { return@mapNotNull null }
                // Drop manifest entries whose file has vanished — the model is gone, so hiding a dead
                // slot is better than offering one that can only fail to load.
                if (!File(dir, fileName).exists()) return@mapNotNull null
                ModelSlot(id = id, name = o.optString("name", "model.gguf"), fileName = fileName)
            }
            val active = root.optString("active", "").ifBlank { null }
                ?.takeIf { a -> slots.any { it.id == a } }
                ?: slots.firstOrNull()?.id
            ModelIndex(slots, active)
        } catch (_: Throwable) {
            // A corrupt or unreadable manifest is recoverable for the same reason: the .gguf files
            // are the data, and the manifest is only a label for them.
            rebuildFromFiles(context)
        }
    }

    /**
     * Reconstruct the slot list by looking at what is actually on disk, for when the manifest can no
     * longer be read (an invalidated Keystore key, a corrupt write).
     *
     * Import names files `model_<id>.gguf`, so the id is recoverable from the filename and the
     * slots keep their identity across the rebuild — which matters, because the settings block of a
     * backup and the engine's own resident-slot tracking both key on that id. Only the user-chosen
     * NAME is genuinely lost, and it is replaced with the file name so nothing is label-less. The
     * rebuilt manifest is written back immediately, sealed with whatever key works now, so this
     * recovery runs once rather than on every read.
     */
    @Synchronized
    private fun rebuildFromFiles(context: Context): ModelIndex {
        val dir = dir(context)
        val files = dir.listFiles()?.filter {
            it.isFile && it.name.lowercase().endsWith(".gguf") && it.length() > 0L
        }.orEmpty().sortedBy { it.name }
        if (files.isEmpty()) return ModelIndex(emptyList(), null)
        val slots = files.take(MAX_MODELS).map { f ->
            val id = f.name.removePrefix("model_").removeSuffix(".gguf").ifBlank { newId() }
            ModelSlot(id = id, name = f.name, fileName = f.name)
        }
        val rebuilt = ModelIndex(slots, slots.firstOrNull()?.id)
        try {
            writeIndex(context, rebuilt)
        } catch (_: Throwable) {
        }
        return rebuilt
    }

    @Synchronized
    private fun writeIndex(context: Context, idx: ModelIndex) {
        val dir = dir(context)
        if (!dir.exists()) dir.mkdirs()
        val arr = JSONArray()
        idx.slots.forEach { s ->
            arr.put(JSONObject().put("id", s.id).put("name", s.name).put("file", s.fileName))
        }
        val root = JSONObject().put("slots", arr)
        idx.activeId?.let { root.put("active", it) }
        // Sealed, not written in the clear (encryption audit).
        //
        // The WEIGHTS are deliberately left as plain bytes — see the class comment — but this
        // manifest is not weights, it is text the user typed. A model slot's name is whatever they
        // chose to call it, and people name things after what they use them for. It is small, it is
        // written rarely, and it sits in the same app-private directory as everything else, so
        // sealing it costs nothing measurable and closes the one place in this feature where user
        // input was landing on disk readable.
        File(dir, INDEX_FILE).writeText(LocalSecrets.encrypt(root.toString()))
    }

    // ---------------------------------------------------------------------------------------
    // Public queries
    // ---------------------------------------------------------------------------------------

    fun slots(context: Context): List<ModelSlot> = index(context).slots

    fun activeSlot(context: Context): ModelSlot? {
        val idx = index(context)
        return idx.slots.firstOrNull { it.id == idx.activeId }
    }

    /** The file backing a given slot id, or null when the id is unknown. */
    fun modelFile(context: Context, id: String): File? =
        index(context).slots.firstOrNull { it.id == id }?.let { File(dir(context), it.fileName) }

    /** The file for the active slot — this is what the engine loads. Null when nothing is active. */
    fun activeModelFile(context: Context): File? {
        val slot = activeSlot(context) ?: return null
        val f = File(dir(context), slot.fileName)
        return if (f.exists() && f.length() > 0L) f else null
    }

    /** True when there is an active model with real bytes on disk (kept name for existing callers). */
    fun hasModel(context: Context): Boolean = activeModelFile(context) != null

    /** The active model's user-facing name, or null when none is active (kept for existing callers). */
    fun displayName(context: Context): String? = activeSlot(context)?.name

    /** The active model's size in bytes (kept for existing callers). */
    fun modelSizeBytes(context: Context): Long = activeModelFile(context)?.length() ?: 0L

    fun modelSizeBytes(context: Context, id: String): Long =
        modelFile(context, id)?.takeIf { it.exists() }?.length() ?: 0L

    /** Whether another model may still be imported (a free slot exists). */
    fun canImportMore(context: Context): Boolean = slots(context).size < MAX_MODELS

    // ---------------------------------------------------------------------------------------
    // Mutations
    // ---------------------------------------------------------------------------------------

    /**
     * Make [id] the active model. No-op if the id is unknown. Callers should release the currently
     * resident model (via [LocalLlm.shutdown]) around this so only one model is ever in memory; the
     * next generation then loads the newly active slot.
     */
    @Synchronized
    fun setActive(context: Context, id: String) {
        val idx = index(context)
        if (idx.slots.none { it.id == id }) return
        writeIndex(context, idx.copy(activeId = id))
    }

    /** Rename a slot. Blank names fall back to the file name so a slot is never label-less. */
    @Synchronized
    fun rename(context: Context, id: String, newName: String) {
        val idx = index(context)
        val clean = newName.trim().take(60)
        val updated = idx.slots.map {
            if (it.id == id) it.copy(name = clean.ifBlank { it.fileName }) else it
        }
        writeIndex(context, idx.copy(slots = updated))
    }

    /**
     * Import the model at [uri] into a NEW slot and make it active. Blocking I/O — call from a
     * background dispatcher. On success a new slot exists, named [customName] (or the picked file's
     * name when that is blank), and it is the active model.
     *
     * @throws TooManyModelsException the [MAX_MODELS] limit is already reached
     * @throws NotGgufException       the picked file (or the extracted entry) is not GGUF
     * @throws NoGgufInZipException   the picked zip holds no `.gguf` entry
     * @throws IOException            any read/write failure
     */
    @Throws(IOException::class)
    fun import(context: Context, uri: Uri, customName: String? = null): ModelSlot {
        val existing = index(context)
        if (existing.slots.size >= MAX_MODELS) throw TooManyModelsException()

        val dir = dir(context)
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Could not create model directory")

        val id = newId()
        val fileName = "model_$id.gguf"
        val tmp = File(dir, "$fileName.tmp")
        var pickedName = queryDisplayName(context, uri) ?: "model.gguf"

        try {
            context.contentResolver.openInputStream(uri)?.use { raw ->
                val head = ByteArray(4)
                val headRead = readUpTo(raw, head)

                if (headRead == 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) {
                    // A zip: scan for the first .gguf entry and extract just it. The output path is
                    // fixed ([tmp]) — entry names are never used as paths, so zip-slip cannot apply.
                    val stitched = StitchedInputStream(head, headRead, raw)
                    val zip = ZipInputStream(stitched)
                    var entry = zip.nextEntry
                    var found = false
                    while (entry != null) {
                        if (!entry.isDirectory && entry.name.lowercase().endsWith(".gguf")) {
                            pickedName = entry.name.substringAfterLast('/')
                            copyVerifyingGguf(zip, tmp)
                            found = true
                            break
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                    if (!found) throw NoGgufInZipException()
                } else {
                    // A plain file: must itself start with the GGUF magic.
                    if (headRead < 4 || !head.contentEquals(GGUF_MAGIC)) throw NotGgufException()
                    copyPrefixed(head, headRead, raw, tmp)
                }
            } ?: throw IOException("Could not open the selected file")

            val target = File(dir, fileName)
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) throw IOException("Could not finalize the model file")

            val name = (customName?.trim()?.take(60)).let { if (it.isNullOrBlank()) pickedName else it }
            val slot = ModelSlot(id = id, name = name, fileName = fileName)
            // A newly imported model becomes active: it is the one the user just chose to add.
            writeIndex(context, ModelIndex(existing.slots + slot, slot.id))
            return slot
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /**
     * Remove one slot and its file. If the removed slot was active, the active pointer moves to the
     * first remaining slot (or none). The engine must be shut down by the caller first when the
     * removed slot is the resident model.
     */
    @Synchronized
    fun delete(context: Context, id: String) {
        val idx = index(context)
        val slot = idx.slots.firstOrNull { it.id == id } ?: return
        File(dir(context), slot.fileName).delete()
        File(dir(context), "${slot.fileName}.tmp").delete()
        val remaining = idx.slots.filter { it.id != id }
        val newActive = if (idx.activeId == id) remaining.firstOrNull()?.id else idx.activeId
        writeIndex(context, ModelIndex(remaining, newActive))
    }

    // ---------------------------------------------------------------------------------------
    // Backup support (task 9)
    // ---------------------------------------------------------------------------------------

    /**
     * The manifest, as the JSON that goes into a backup file.
     *
     * This is the metadata half of a local-model backup: which slots exist, what the user named
     * them, and which one is active. It is a few hundred bytes, so it always travels when the
     * local-assistant module is selected — the multi-gigabyte `.gguf` payloads are a separate,
     * opt-in module (see [readModelBytes] / [restoreFromBackup]).
     *
     * Keeping the two separable is what makes a partial restore sensible rather than merely
     * possible. Restoring names without files gives an honest empty state that says which models
     * this profile expects; restoring files without names would give you three anonymous blobs.
     */
    @Synchronized
    fun exportManifestJson(context: Context): String {
        // Deliberately plaintext JSON. It is about to be sealed inside the backup envelope, and
        // LocalSecrets is device-bound — re-using it here would produce a manifest that only the
        // origin phone could ever read, which is the exact cross-device failure the backup format
        // was fixed to avoid.
        val idx = index(context)
        val arr = JSONArray()
        idx.slots.forEach { s ->
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("name", s.name)
                    .put("file", s.fileName)
                    .put("size", File(dir(context), s.fileName).length())
            )
        }
        val root = JSONObject().put("slots", arr)
        idx.activeId?.let { root.put("active", it) }
        return root.toString()
    }

    /** Total bytes of every imported model — what the backup dialog quotes before you opt in. */
    fun totalModelBytes(context: Context): Long =
        slots(context).sumOf { File(dir(context), it.fileName).length() }

    /** The file backing [slot], for streaming into a backup. Null when it has vanished. */
    fun modelFileForSlot(context: Context, slot: ModelSlot): File? =
        File(dir(context), slot.fileName).takeIf { it.exists() && it.length() > 0L }

    /**
     * Where a restoring backup should write a model file. Returns the destination for [fileName],
     * creating the directory if needed. Kept here rather than in BackupManager so the on-disk
     * layout stays this class's private business — a restore that hard-coded "local_model/" would
     * silently rot the day the directory name changed.
     */
    @Synchronized
    fun prepareRestoreTarget(context: Context, fileName: String): File {
        val d = dir(context)
        if (!d.exists()) d.mkdirs()
        // Entry names from a backup file are never used as paths — only the bare name is taken —
        // so a hand-edited backup cannot write outside this directory.
        return File(d, File(fileName).name)
    }

    /**
     * Adopt a manifest restored from a backup, keeping only the slots whose files actually landed.
     *
     * The filter is the important part. A backup may carry slot metadata without the payloads
     * (because the user did not select the model-files module, or the restore ran out of space), and
     * a manifest listing models that are not on disk produces a picker full of entries that can only
     * fail to load. [index] already drops such entries defensively on read; doing it here as well
     * means the file written is correct rather than merely tolerated.
     *
     * Returns how many slots survived, so the caller can report honestly.
     */
    @Synchronized
    fun restoreFromBackup(context: Context, manifestJson: String): Int {
        val d = dir(context)
        if (!d.exists()) d.mkdirs()
        val root = try {
            JSONObject(manifestJson)
        } catch (_: Throwable) {
            return 0
        }
        val arr = root.optJSONArray("slots") ?: JSONArray()
        val existing = index(context)
        val kept = mutableListOf<ModelSlot>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", "").ifBlank { continue }
            val fileName = File(o.optString("file", "").ifBlank { continue }).name
            if (!File(d, fileName).let { it.exists() && it.length() > 0L }) continue
            // A slot id that already exists on this device wins: the local file is the one the
            // engine may currently have resident, and quietly relabelling it would be worse than
            // skipping the incoming entry.
            if (existing.slots.any { it.id == id }) continue
            kept.add(ModelSlot(id = id, name = o.optString("name", fileName), fileName = fileName))
        }
        val merged = (existing.slots + kept).take(MAX_MODELS)
        val restoredActive = root.optString("active", "").ifBlank { null }
        val active = restoredActive?.takeIf { a -> merged.any { it.id == a } }
            ?: existing.activeId?.takeIf { a -> merged.any { it.id == a } }
            ?: merged.firstOrNull()?.id
        writeIndex(context, ModelIndex(merged, active))
        return kept.size
    }

    /** Remove every slot and file (used when wiping all data). Leaves an empty manifest. */
    @Synchronized
    fun deleteAll(context: Context) {
        val idx = index(context)
        idx.slots.forEach { s ->
            File(dir(context), s.fileName).delete()
            File(dir(context), "${s.fileName}.tmp").delete()
        }
        writeIndex(context, ModelIndex(emptyList(), null))
    }

    // ---------------------------------------------------------------------------------------

    // A short, collision-free, filesystem-safe id for a slot (used to name its file).
    private fun newId(): String = java.util.UUID.randomUUID().toString().replace("-", "").take(12)

    private fun queryDisplayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Throwable) {
        null
    }

    private fun readUpTo(input: InputStream, buffer: ByteArray): Int {
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n < 0) break
            read += n
        }
        return read
    }

    /** Copy [zipEntry] to [out], verifying its first four bytes are the GGUF magic. */
    private fun copyVerifyingGguf(zipEntry: InputStream, out: File) {
        val head = ByteArray(4)
        val n = readUpTo(zipEntry, head)
        if (n < 4 || !head.contentEquals(GGUF_MAGIC)) throw NotGgufException()
        copyPrefixed(head, n, zipEntry, out)
    }

    /** Write [prefixLen] bytes of [prefix] then the rest of [input] into [out]. */
    private fun copyPrefixed(prefix: ByteArray, prefixLen: Int, input: InputStream, out: File) {
        out.outputStream().use { os ->
            os.write(prefix, 0, prefixLen)
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                os.write(buf, 0, n)
            }
            os.flush()
        }
    }

    /** Re-attach already-consumed header bytes in front of the remaining stream. */
    private class StitchedInputStream(
        private val head: ByteArray,
        private val headLen: Int,
        private val rest: InputStream
    ) : InputStream() {
        private var pos = 0
        override fun read(): Int =
            if (pos < headLen) head[pos++].toInt() and 0xFF else rest.read()

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos < headLen) {
                val take = minOf(len, headLen - pos)
                System.arraycopy(head, pos, b, off, take)
                pos += take
                return take
            }
            return rest.read(b, off, len)
        }
    }
}
