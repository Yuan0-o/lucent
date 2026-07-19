package com.lucent.app.local

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
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
            val root = JSONObject(indexFile.readText())
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
            ModelIndex(emptyList(), null)
        }
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
        File(dir, INDEX_FILE).writeText(root.toString())
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
