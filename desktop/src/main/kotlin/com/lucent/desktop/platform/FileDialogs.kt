// Desktop-native file chooser primitives.
//
// On Android the screens reach the file system through the Storage Access Framework:
// `rememberLauncherForActivityResult(GetContent()/GetMultipleContents()/CreateDocument())` hands
// back a content `Uri`, which the data layer then reads via `contentResolver`. None of that exists
// on the desktop — and it doesn't need to, because the desktop data layer was already rewritten to
// be plain-`File` based (see AttachmentStore.importFile / openOutputStream). These helpers are the
// missing half: they put a real OS "open" / "save" dialog in front of the user and return the
// chosen `java.io.File`, which slots directly into that file-based data layer.
//
// This is a NEW desktop capability, not a shim of an Android class: when a screen is ported, its
// `filePicker.launch("*/*")` call site becomes `DesktopFiles.openFile(...)` and its
// `saveLauncher.launch(name)` becomes `DesktopFiles.saveFile(...)`.
//
// AWT's FileDialog is used rather than Swing's JFileChooser because it renders the genuine native
// Windows Explorer dialog (JFileChooser looks foreign on Windows). FileDialog is modal and must run
// on the AWT event thread; Compose-Desktop click handlers already execute there, so calling these
// straight from an onClick is correct. The call blocks only for as long as the dialog is open, which
// is exactly the expected behaviour of a native picker.

package com.lucent.desktop.platform

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.FilenameFilter

/** A named set of extensions used to filter what the dialog shows (best-effort — see [extFilter]). */
data class FileFilter(val description: String, val extensions: List<String>) {
    companion object {
        val ANY = FileFilter("All files", emptyList())
        val IMAGES = FileFilter("Images", listOf("png", "jpg", "jpeg", "gif", "webp", "bmp"))
        val TEXT = FileFilter("Text", listOf("txt", "md", "json", "csv"))
    }
}

object DesktopFiles {

    /**
     * Show a native "open" dialog for a single file. Returns null if the user cancels.
     *
     * @param title    dialog window title.
     * @param filter   which files to surface; extension filtering is honoured on macOS/Linux and
     *                 quietly ignored by the Windows native dialog (which shows everything) — the
     *                 caller must not assume the returned file matches an extension.
     */
    fun openFile(title: String = "Open", filter: FileFilter = FileFilter.ANY): File? =
        openInternal(title, filter, multiple = false).firstOrNull()

    /** Show a native "open" dialog allowing several files. Returns an empty list on cancel. */
    fun openFiles(title: String = "Open", filter: FileFilter = FileFilter.ANY): List<File> =
        openInternal(title, filter, multiple = true)

    /**
     * Show a native "save as" dialog. Returns the destination [File] (which may not yet exist), or
     * null if the user cancels. The dialog itself performs the platform's own overwrite
     * confirmation, so callers can write to the result directly.
     *
     * @param suggestedName pre-filled file name, e.g. "lucent-reply.txt".
     */
    fun saveFile(title: String = "Save", suggestedName: String = ""): File? {
        val owner: Frame? = null
        val dialog = FileDialog(owner, title, FileDialog.SAVE).apply {
            if (suggestedName.isNotBlank()) file = suggestedName
        }
        return try {
            dialog.isVisible = true
            val dir = dialog.directory
            val name = dialog.file
            if (dir != null && name != null) File(dir, name) else null
        } catch (t: Throwable) {
            null
        } finally {
            dialog.dispose()
        }
    }

    private fun openInternal(title: String, filter: FileFilter, multiple: Boolean): List<File> {
        val owner: Frame? = null
        val dialog = FileDialog(owner, title, FileDialog.LOAD).apply {
            isMultipleMode = multiple
            extFilter(filter)?.let { filenameFilter = it }
        }
        return try {
            dialog.isVisible = true
            if (multiple) {
                dialog.files?.toList() ?: emptyList()
            } else {
                val dir = dialog.directory
                val name = dialog.file
                if (dir != null && name != null) listOf(File(dir, name)) else emptyList()
            }
        } catch (t: Throwable) {
            emptyList()
        } finally {
            dialog.dispose()
        }
    }

    /** Build a case-insensitive extension filter, or null for "no filtering" (all files). */
    private fun extFilter(filter: FileFilter): FilenameFilter? {
        if (filter.extensions.isEmpty()) return null
        val wanted = filter.extensions.map { it.lowercase().removePrefix(".") }.toSet()
        return FilenameFilter { _, name ->
            val dot = name.lastIndexOf('.')
            dot >= 0 && name.substring(dot + 1).lowercase() in wanted
        }
    }
}
