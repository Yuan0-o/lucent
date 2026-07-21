package com.lucent.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Where the user's imported fonts live, and how they get there.
 *
 * The app no longer bundles any typeface (font library task): out of the box it renders in the
 * platform's system font, and every other choice in the font picker is a file the user imported
 * here. That turned twelve shipped TTFs — most of the APK — into zero, and made the font list the
 * user's own instead of ours.
 *
 * The shape is deliberately the same as [com.lucent.app.local.LocalModelStore], because it is the
 * same problem at a smaller scale: user-picked binary files in an app-private directory, described
 * by a tiny manifest, referenced from settings by a stable id.
 *
 *  - The user picks any file in the system picker. The bytes are **verified to actually be an
 *    OpenType container** (TTF / OTF / TTC magic) before anything is kept — a wrong file is
 *    rejected with a clear message instead of surfacing as invisible text or a render crash later.
 *  - The copy lands in a temp file and is renamed into place only when complete, so a cancelled or
 *    failed import can never leave a half-written font that the renderer then chokes on.
 *  - Each font is a *slot* with a user-chosen display name (given at import time) and its own file.
 *    Up to [MAX_FONTS] fonts are kept; importing when full is refused with a clear error.
 *  - The **selection** does not live here. The app-wide font preference (SettingsRepository.font)
 *    stores either "system" or a slot's [FontSlot.id]; this store is only the library that id
 *    points into. An id that no longer resolves simply renders as the system font.
 *
 * All state lives in [INDEX_FILE] (a small JSON manifest) beside the font files, so this class is
 * fully self-contained.
 */
object FontStore {

    /**
     * Maximum number of fonts a user can keep imported at once. Twelve is the count the app used
     * to bundle, so nobody's ambitions shrink — and a bound keeps the picker scannable and the
     * disk use honest.
     */
    const val MAX_FONTS = 12

    private const val DIR = "fonts"

    // The JSON manifest of imported fonts. Sits beside the font files it describes.
    private const val INDEX_FILE = "fonts.json"

    // The four OpenType container signatures worth accepting. "sfnt 1.0" and Apple's legacy "true"
    // are both TrueType-glyph files (.ttf); "OTTO" is CFF-flavoured OpenType (.otf); "ttcf" is a
    // TrueType collection (.ttc). Skia (desktop) and the Android font stack load all four; web
    // formats (WOFF/WOFF2) are deliberately NOT accepted because neither renderer can.
    private val MAGIC_TTF = byteArrayOf(0x00, 0x01, 0x00, 0x00)
    private val MAGIC_TRUE = byteArrayOf(0x74, 0x72, 0x75, 0x65)  // "true"
    private val MAGIC_OTF = byteArrayOf(0x4F, 0x54, 0x54, 0x4F)   // "OTTO"
    private val MAGIC_TTC = byteArrayOf(0x74, 0x74, 0x63, 0x66)   // "ttcf"

    class NotFontException : IOException("Not a usable font file")
    class TooManyFontsException : IOException("Font slots are full")

    /**
     * One imported font. [id] is a stable, filesystem-safe token used to name the font's file and
     * as the value the font *setting* stores; [name] is the user-facing label chosen at import;
     * [fileName] is the actual on-disk file.
     */
    data class FontSlot(val id: String, val name: String, val fileName: String)

    // A tiny in-memory snapshot of the manifest, so a page can render the list in one read.
    data class FontIndex(val slots: List<FontSlot>)

    private fun dir(context: Context): File = File(context.filesDir, DIR)

    // ---------------------------------------------------------------------------------------
    // Manifest read / write
    // ---------------------------------------------------------------------------------------

    /**
     * Read the manifest. Never throws — a corrupt manifest reads as whatever font files are
     * actually on disk rather than taking the app down (see [rebuildFromFiles]).
     */
    @Synchronized
    fun index(context: Context): FontIndex {
        val dir = dir(context)
        val indexFile = File(dir, INDEX_FILE)
        if (!indexFile.exists()) return FontIndex(emptyList())

        return try {
            // Sealed by writeIndex. LocalSecrets.decrypt returns the input unchanged when it has no
            // envelope prefix, and "" when a genuinely sealed value cannot be opened — the Keystore
            // key having been invalidated by a lock-screen change is the realistic case. In that
            // case rebuild from the files instead of reporting an empty library while the fonts
            // still sit on disk: the names are lost, the fonts are not.
            val raw = indexFile.readText()
            val decrypted = LocalSecrets.decrypt(raw)
            if (decrypted.isEmpty() && raw.isNotEmpty()) return rebuildFromFiles(context)
            val root = JSONObject(decrypted)
            val arr = root.optJSONArray("fonts") ?: JSONArray()
            val slots = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id", "").ifBlank { return@mapNotNull null }
                val fileName = o.optString("file", "").ifBlank { return@mapNotNull null }
                // Drop manifest entries whose file has vanished — the font is gone, so hiding a
                // dead row is better than offering one that can only fall back to the system font.
                if (!File(dir, fileName).exists()) return@mapNotNull null
                FontSlot(id = id, name = o.optString("name", fileName), fileName = fileName)
            }
            FontIndex(slots)
        } catch (_: Throwable) {
            // A corrupt or unreadable manifest is recoverable for the same reason: the font files
            // are the data, and the manifest is only a label for them.
            rebuildFromFiles(context)
        }
    }

    /**
     * Reconstruct the font list by looking at what is actually on disk, for when the manifest can
     * no longer be read (an invalidated Keystore key, a corrupt write).
     *
     * Import names files `font_<id>.<ext>`, so the id is recoverable from the filename and the
     * slots keep their identity across the rebuild — which matters, because the font *setting* and
     * the settings block of a backup both key on that id. Only the user-chosen NAME is genuinely
     * lost, and it is replaced with the file name so nothing is label-less. The rebuilt manifest is
     * written back immediately, sealed with whatever key works now, so this recovery runs once.
     */
    @Synchronized
    private fun rebuildFromFiles(context: Context): FontIndex {
        val dir = dir(context)
        val files = dir.listFiles()?.filter { f ->
            f.isFile && f.length() > 0L && listOf(".ttf", ".otf", ".ttc").any {
                f.name.lowercase().endsWith(it)
            }
        }.orEmpty().sortedBy { it.name }
        if (files.isEmpty()) return FontIndex(emptyList())
        val slots = files.take(MAX_FONTS).map { f ->
            val id = f.name.removePrefix("font_").substringBeforeLast('.').ifBlank { newId() }
            FontSlot(id = id, name = f.name, fileName = f.name)
        }
        val rebuilt = FontIndex(slots)
        try {
            writeIndex(context, rebuilt)
        } catch (_: Throwable) {
        }
        return rebuilt
    }

    @Synchronized
    private fun writeIndex(context: Context, idx: FontIndex) {
        val dir = dir(context)
        if (!dir.exists()) dir.mkdirs()
        val arr = JSONArray()
        idx.slots.forEach { s ->
            arr.put(JSONObject().put("id", s.id).put("name", s.name).put("file", s.fileName))
        }
        val root = JSONObject().put("fonts", arr)
        // Sealed, not written in the clear, for the same reason the model manifest is: a font
        // slot's name is text the user typed, it is small, it is written rarely, and it sits in the
        // same app-private directory as everything else — sealing it costs nothing measurable.
        File(dir, INDEX_FILE).writeText(LocalSecrets.encrypt(root.toString()))
    }

    // ---------------------------------------------------------------------------------------
    // Public queries
    // ---------------------------------------------------------------------------------------

    fun fonts(context: Context): List<FontSlot> = index(context).slots

    /** The file backing a given font id with real bytes on disk, or null when it can't be used. */
    fun fontFile(context: Context, id: String): File? =
        index(context).slots.firstOrNull { it.id == id }
            ?.let { File(dir(context), it.fileName) }
            ?.takeIf { it.exists() && it.length() > 0L }

    /** Whether another font may still be imported (a free slot exists). */
    fun canImportMore(context: Context): Boolean = fonts(context).size < MAX_FONTS

    // ---------------------------------------------------------------------------------------
    // Mutations
    // ---------------------------------------------------------------------------------------

    /**
     * Import the font at [uri] into a NEW slot. Blocking I/O — call from a background dispatcher.
     * On success a new slot exists, named [customName] (or the picked file's name when that is
     * blank). Making it the *selected* font is the caller's decision, since the selection lives in
     * settings, not here.
     *
     * @throws TooManyFontsException the [MAX_FONTS] limit is already reached
     * @throws NotFontException      the picked file is not a TTF/OTF/TTC
     * @throws IOException           any read/write failure
     */
    @Throws(IOException::class)
    fun import(context: Context, uri: Uri, customName: String? = null): FontSlot {
        val existing = index(context)
        if (existing.slots.size >= MAX_FONTS) throw TooManyFontsException()

        val dir = dir(context)
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Could not create the font directory")

        val id = newId()
        val pickedName = queryDisplayName(context, uri) ?: "font"

        context.contentResolver.openInputStream(uri)?.use { raw ->
            val head = ByteArray(4)
            val headRead = readUpTo(raw, head)
            // The magic decides both acceptance and the extension the file is stored under, so
            // what lands on disk is self-describing even if the manifest is ever lost.
            val ext = classify(head, headRead) ?: throw NotFontException()
            val fileName = "font_$id.$ext"
            val tmp = File(dir, "$fileName.tmp")
            try {
                copyPrefixed(head, headRead, raw, tmp)
                val target = File(dir, fileName)
                if (target.exists()) target.delete()
                if (!tmp.renameTo(target)) throw IOException("Could not finalize the font file")
            } finally {
                if (tmp.exists()) tmp.delete()
            }
            val name = (customName?.trim()?.take(60)).let {
                if (it.isNullOrBlank()) pickedName.substringBeforeLast('.') else it
            }
            val slot = FontSlot(id = id, name = name, fileName = fileName)
            writeIndex(context, FontIndex(existing.slots + slot))
            return slot
        } ?: throw IOException("Could not open the selected file")
    }

    /**
     * Remove one font and its file. The caller owns the consequences for the *selection*: if the
     * removed id is the current font setting, reset that setting to "system" (see SettingsScreen)
     * — this store neither reads nor writes preferences.
     */
    @Synchronized
    fun delete(context: Context, id: String) {
        val idx = index(context)
        val slot = idx.slots.firstOrNull { it.id == id } ?: return
        File(dir(context), slot.fileName).delete()
        File(dir(context), "${slot.fileName}.tmp").delete()
        writeIndex(context, FontIndex(idx.slots.filter { it.id != id }))
    }

    /** Remove every imported font and its file (used when wiping all data). Leaves an empty manifest. */
    @Synchronized
    fun deleteAll(context: Context) {
        val idx = index(context)
        idx.slots.forEach { s ->
            File(dir(context), s.fileName).delete()
            File(dir(context), "${s.fileName}.tmp").delete()
        }
        writeIndex(context, FontIndex(emptyList()))
    }

    // ---------------------------------------------------------------------------------------
    // Backup support
    // ---------------------------------------------------------------------------------------

    /**
     * The manifest, as the JSON that goes into a backup file's settings block.
     *
     * Deliberately plaintext JSON: it is about to be sealed inside the backup envelope, and
     * LocalSecrets is device-bound — re-using it here would produce a manifest only the origin
     * device could read, the exact cross-device failure the backup format exists to avoid.
     */
    @Synchronized
    fun exportManifestJson(context: Context): String {
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
        return JSONObject().put("fonts", arr).toString()
    }

    /** Total bytes of every imported font — what the export dialog quotes next to the settings module. */
    fun totalFontBytes(context: Context): Long =
        fonts(context).sumOf { File(dir(context), it.fileName).length() }

    /** The file backing [slot], for streaming into a backup. Null when it has vanished. */
    fun fontFileForSlot(context: Context, slot: FontSlot): File? =
        File(dir(context), slot.fileName).takeIf { it.exists() && it.length() > 0L }

    /**
     * Where a restoring backup should write a font file. Kept here rather than in BackupManager so
     * the on-disk layout stays this class's private business. Only the bare name of [fileName] is
     * used, so a hand-edited backup cannot write outside this directory.
     */
    @Synchronized
    fun prepareRestoreTarget(context: Context, fileName: String): File {
        val d = dir(context)
        if (!d.exists()) d.mkdirs()
        return File(d, File(fileName).name)
    }

    /**
     * Adopt a font manifest restored from a backup, keeping only the slots whose files actually
     * landed on disk (the blobs are written before this runs — see BackupManager.commit). A slot id
     * that already exists on this device wins over the incoming entry, and the result is capped at
     * [MAX_FONTS]. Returns how many slots survived, so the caller can report honestly.
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
        val arr = root.optJSONArray("fonts") ?: JSONArray()
        val existing = index(context)
        val kept = mutableListOf<FontSlot>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", "").ifBlank { continue }
            val fileName = File(o.optString("file", "").ifBlank { continue }).name
            if (!File(d, fileName).let { it.exists() && it.length() > 0L }) continue
            if (existing.slots.any { it.id == id }) continue
            kept.add(FontSlot(id = id, name = o.optString("name", fileName), fileName = fileName))
        }
        val merged = (existing.slots + kept).take(MAX_FONTS)
        writeIndex(context, FontIndex(merged))
        return kept.size
    }

    // ---------------------------------------------------------------------------------------

    // A short, collision-free, filesystem-safe id for a slot (used to name its file and as the
    // value the font setting stores).
    private fun newId(): String = java.util.UUID.randomUUID().toString().replace("-", "").take(12)

    private fun queryDisplayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Throwable) {
        null
    }

    /** Map the file's first four bytes to a storage extension, or null when it isn't a font. */
    private fun classify(head: ByteArray, headLen: Int): String? {
        if (headLen < 4) return null
        return when {
            head.contentEquals(MAGIC_TTF) || head.contentEquals(MAGIC_TRUE) -> "ttf"
            head.contentEquals(MAGIC_OTF) -> "otf"
            head.contentEquals(MAGIC_TTC) -> "ttc"
            else -> null
        }
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
}
