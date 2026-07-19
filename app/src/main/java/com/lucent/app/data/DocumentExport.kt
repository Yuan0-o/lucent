package com.lucent.app.data

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The formats a notes/tasks export can be written in.
 *
 * Markdown was the original (and only) option — see [MarkdownExport]. This adds Word, Excel, and PDF
 * so the same "walk away with your data in a format anything can open" promise extends to the file
 * types people actually have to hand in to work, print, or drop into a spreadsheet. [MarkdownExport]
 * still produces the Markdown; the three office formats are produced here.
 */
enum class ExportFormat(val label: String, val extension: String, val mime: String) {
    MARKDOWN("Markdown (.md)", "md", "text/markdown"),
    WORD("Word (.docx)", "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    PDF("PDF (.pdf)", "pdf", "application/pdf"),
    EXCEL("Excel (.xlsx)", "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
}

/**
 * Builds Word (.docx), Excel (.xlsx), and PDF (.pdf) exports of notes and tasks.
 *
 * ### Why it's written by hand
 * The whole app is deliberately dependency-light, and pulling in Apache POI (Word/Excel) would add
 * many megabytes to the APK for what is, in the end, a handful of paragraphs and a table. So the
 * office files are assembled from scratch: a .docx and a .xlsx are just ZIP archives of a few small
 * XML parts (Office Open XML), which [ZipOutputStream] and string-building handle perfectly well for
 * documents this simple. The PDF uses Android's own [PdfDocument], so it needs no library at all.
 *
 * ### What goes in
 * The content mirrors [MarkdownExport] exactly, so every format tells the same story: trashed items
 * are excluded, archived notes and completed tasks are kept and labelled, and each item carries its
 * metadata, its body/details, its checklist/subtasks, and its attachment *names* (bytes can't ride
 * in these formats any more than in Markdown — the .json backup remains the way to carry files).
 * Word and PDF are laid out as a readable document; Excel is laid out as one row per item so it can
 * be sorted and filtered like a spreadsheet.
 */
object DocumentExport {

    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private fun formatTime(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(stamp)

    // ============================ Public entry points ============================

    fun exportNotes(notes: List<Note>, format: ExportFormat): ByteArray {
        val live = notes.filter { it.trashedAt == null }
            .sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.updatedAt })
        return when (format) {
            ExportFormat.MARKDOWN -> MarkdownExport.render(notes).toByteArray(Charsets.UTF_8)
            ExportFormat.WORD -> notesDocx(live)
            ExportFormat.PDF -> notesPdf(live)
            ExportFormat.EXCEL -> notesXlsx(live)
        }
    }

    fun exportTasks(tasks: List<Task>, format: ExportFormat): ByteArray {
        val live = tasks.filter { it.trashedAt == null }
            .sortedWith(
                compareByDescending<Task> { it.pinned }.thenBy { it.isDone }.thenByDescending { it.createdAt }
            )
        return when (format) {
            ExportFormat.MARKDOWN -> MarkdownExport.renderTasks(tasks).toByteArray(Charsets.UTF_8)
            ExportFormat.WORD -> tasksDocx(live)
            ExportFormat.PDF -> tasksPdf(live)
            ExportFormat.EXCEL -> tasksXlsx(live)
        }
    }

    // ============================ Shared content model ============================

    // A single item flattened into the pieces every format needs: a title, a one-line metadata
    // string, the body/details, the checklist/subtask lines, and the attachment names. Building this
    // once keeps the Word/PDF/Excel writers from each re-deriving the same fields.
    private data class Block(
        val title: String,
        val meta: String,
        val body: String,
        val checklist: List<Pair<Boolean, String>>,
        val checklistLabel: String,
        val attachments: List<String>
    )

    private fun noteBlock(note: Note): Block {
        val meta = buildList {
            add("Updated ${formatTime(note.updatedAt)}")
            if (note.pinned) add("Pinned")
            if (note.archived) add("Archived")
            val tags = note.tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (tags.isNotEmpty()) add(tags.joinToString(" ") { "#$it" })
        }.joinToString(" · ")
        val checklist = if (note.isChecklist) Checklist.parse(note.checklist).map { it.done to it.text } else emptyList()
        return Block(
            title = note.title.ifBlank { "Untitled" },
            meta = meta,
            body = if (note.isChecklist) "" else note.body.trim(),
            checklist = checklist,
            checklistLabel = if (note.isChecklist) "Checklist" else "",
            attachments = Attachments.parse(note.attachments).map { it.name }
        )
    }

    private fun taskBlock(task: Task): Block {
        val meta = buildList {
            add("Created ${formatTime(task.createdAt)}")
            task.dueAt?.let { add("Due ${formatTime(it)}") }
            if (task.pinned) add("Pinned")
            TaskPriority.fromValue(task.priority).takeIf { it != TaskPriority.NONE }?.let { add("Priority: ${it.label}") }
            RepeatRule.fromKey(task.repeatRule).takeIf { it != RepeatRule.NONE }?.let { add("Repeats: ${it.label}") }
            add(if (task.isDone) "Done" else "Open")
        }.joinToString(" · ")
        val box = if (task.isDone) "\u2611" else "\u2610" // ☑ / ☐
        return Block(
            title = "$box ${task.title.ifBlank { "Untitled task" }}",
            meta = meta,
            body = task.notes.trim(),
            checklist = Checklist.parse(task.subtasks).map { it.done to it.text },
            checklistLabel = "Subtasks",
            attachments = Attachments.parse(task.attachments).map { it.name }
        )
    }

    // ============================ Word (.docx) ============================

    private fun notesDocx(notes: List<Note>): ByteArray =
        docx("Lucent notes", notes.size, "note", notes.map { noteBlock(it) })

    private fun tasksDocx(tasks: List<Task>): ByteArray =
        docx("Lucent tasks", tasks.size, "task", tasks.map { taskBlock(it) })

    private fun docx(heading: String, count: Int, noun: String, blocks: List<Block>): ByteArray {
        val body = StringBuilder()
        body.append(docxPara(heading, bold = true, sizeHalfPt = 40))
        body.append(docxPara("$count $noun${if (count == 1) "" else "s"}, exported ${formatTime(System.currentTimeMillis())}", italic = true, sizeHalfPt = 18))
        body.append(docxPara("Attachments are listed by name but not embedded. Use the .json backup if you need the attachment bytes.", italic = true, sizeHalfPt = 18))

        if (blocks.isEmpty()) {
            body.append(docxPara("No ${noun}s yet.", italic = true))
        } else {
            for (b in blocks) {
                body.append(docxPara(b.title, bold = true, sizeHalfPt = 30, spaceBeforeTwips = 240))
                if (b.meta.isNotBlank()) body.append(docxPara(b.meta, italic = true, sizeHalfPt = 18))
                if (b.body.isNotBlank()) body.append(docxPara(b.body))
                if (b.checklist.isNotEmpty()) {
                    body.append(docxPara("${b.checklistLabel}:", bold = true, sizeHalfPt = 20))
                    for ((done, text) in b.checklist) {
                        body.append(docxPara("${if (done) "\u2611" else "\u2610"} $text"))
                    }
                }
                if (b.attachments.isNotEmpty()) {
                    body.append(docxPara("Attachments: ${b.attachments.joinToString(", ")}", bold = true, sizeHalfPt = 18))
                }
            }
        }

        val documentXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>${body}<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr></w:body></w:document>"""

        return zip(
            "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""",
            "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""",
            "word/document.xml" to documentXml
        )
    }

    // One paragraph. Line breaks in [text] become soft breaks within the paragraph, so a multi-line
    // body stays one logical paragraph rather than fragmenting.
    private fun docxPara(
        text: String,
        bold: Boolean = false,
        italic: Boolean = false,
        sizeHalfPt: Int? = null,
        spaceBeforeTwips: Int = 40
    ): String {
        val rPr = buildString {
            if (bold || italic || sizeHalfPt != null) {
                append("<w:rPr>")
                if (bold) append("<w:b/>")
                if (italic) append("<w:i/>")
                if (sizeHalfPt != null) append("<w:sz w:val=\"$sizeHalfPt\"/>")
                append("</w:rPr>")
            }
        }
        val runs = StringBuilder()
        val lines = if (text.isEmpty()) listOf("") else text.split("\n")
        lines.forEachIndexed { i, line ->
            if (i > 0) runs.append("<w:r><w:br/></w:r>")
            runs.append("<w:r>").append(rPr).append("<w:t xml:space=\"preserve\">").append(xmlEscape(line)).append("</w:t></w:r>")
        }
        return "<w:p><w:pPr><w:spacing w:before=\"$spaceBeforeTwips\" w:after=\"40\"/></w:pPr>$runs</w:p>"
    }

    // ============================ Excel (.xlsx) ============================

    private fun notesXlsx(notes: List<Note>): ByteArray {
        val header = listOf("Title", "Updated", "Tags", "Pinned", "Archived", "Content", "Attachments")
        val rows = notes.map { n ->
            val content = if (n.isChecklist) {
                Checklist.parse(n.checklist).joinToString("\n") { "${if (it.done) "[x]" else "[ ]"} ${it.text}" }
            } else n.body.trim()
            val tags = n.tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ") { "#$it" }
            listOf(
                n.title.ifBlank { "Untitled" },
                formatTime(n.updatedAt),
                tags,
                if (n.pinned) "Yes" else "",
                if (n.archived) "Yes" else "",
                content,
                Attachments.parse(n.attachments).joinToString(", ") { it.name }
            )
        }
        return xlsx("Notes", header, rows)
    }

    private fun tasksXlsx(tasks: List<Task>): ByteArray {
        val header = listOf("Title", "Status", "Created", "Due", "Priority", "Repeat", "Pinned", "Details", "Subtasks", "Attachments")
        val rows = tasks.map { t ->
            val subtasks = Checklist.parse(t.subtasks).joinToString("\n") { "${if (it.done) "[x]" else "[ ]"} ${it.text}" }
            listOf(
                t.title.ifBlank { "Untitled task" },
                if (t.isDone) "Done" else "Open",
                formatTime(t.createdAt),
                t.dueAt?.let { formatTime(it) } ?: "",
                TaskPriority.fromValue(t.priority).takeIf { it != TaskPriority.NONE }?.label ?: "",
                RepeatRule.fromKey(t.repeatRule).takeIf { it != RepeatRule.NONE }?.label ?: "",
                if (t.pinned) "Yes" else "",
                t.notes.trim(),
                subtasks,
                Attachments.parse(t.attachments).joinToString(", ") { it.name }
            )
        }
        return xlsx("Tasks", header, rows)
    }

    private fun xlsx(sheetName: String, header: List<String>, rows: List<List<String>>): ByteArray {
        val sheetData = StringBuilder("<sheetData>")
        // Header row.
        sheetData.append(xlsxRow(1, header))
        rows.forEachIndexed { i, cells -> sheetData.append(xlsxRow(i + 2, cells)) }
        sheetData.append("</sheetData>")

        // A rough column width so the sheet opens legibly rather than every column at default width.
        val cols = StringBuilder("<cols>")
        for (c in header.indices) cols.append("<col min=\"${c + 1}\" max=\"${c + 1}\" width=\"24\" customWidth=\"1\"/>")
        cols.append("</cols>")

        val sheetXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">$cols$sheetData</worksheet>"""

        return zip(
            "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>""",
            "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""",
            "xl/workbook.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="${xmlEscape(sheetName)}" sheetId="1" r:id="rId1"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""",
            "xl/worksheets/sheet1.xml" to sheetXml
        )
    }

    private fun xlsxRow(rowNum: Int, cells: List<String>): String {
        val sb = StringBuilder("<row r=\"$rowNum\">")
        cells.forEachIndexed { i, value ->
            val ref = colLetter(i) + rowNum
            sb.append("<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">").append(xmlEscape(value)).append("</t></is></c>")
        }
        sb.append("</row>")
        return sb.toString()
    }

    private fun colLetter(index: Int): String {
        var i = index
        val sb = StringBuilder()
        while (i >= 0) {
            sb.insert(0, ('A' + (i % 26)))
            i = i / 26 - 1
        }
        return sb.toString()
    }

    // ============================ PDF (.pdf) ============================

    private fun notesPdf(notes: List<Note>): ByteArray =
        pdf("Lucent notes", notes.size, "note", notes.map { noteBlock(it) })

    private fun tasksPdf(tasks: List<Task>): ByteArray =
        pdf("Lucent tasks", tasks.size, "task", tasks.map { taskBlock(it) })

    // A4 at 72dpi, in points.
    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 42f

    private fun pdf(heading: String, count: Int, noun: String, blocks: List<Block>): ByteArray {
        val doc = PdfDocument()

        val titlePaint = Paint().apply { color = Color.BLACK; textSize = 20f; isFakeBoldText = true; isAntiAlias = true }
        val itemTitlePaint = Paint().apply { color = Color.BLACK; textSize = 15f; isFakeBoldText = true; isAntiAlias = true }
        val metaPaint = Paint().apply { color = Color.DKGRAY; textSize = 10f; isAntiAlias = true }
        val bodyPaint = Paint().apply { color = Color.BLACK; textSize = 11f; isAntiAlias = true }
        val labelPaint = Paint().apply { color = Color.BLACK; textSize = 11f; isFakeBoldText = true; isAntiAlias = true }

        val state = PdfState(doc)
        state.newPage()

        state.drawWrapped(heading, titlePaint, 26f)
        state.drawWrapped("$count $noun${if (count == 1) "" else "s"}, exported ${formatTime(System.currentTimeMillis())}", metaPaint, 14f)
        state.drawWrapped("Attachments are listed by name but not embedded. Use the .json backup for the bytes.", metaPaint, 16f)

        if (blocks.isEmpty()) {
            state.drawWrapped("No ${noun}s yet.", metaPaint, 14f)
        } else {
            for (b in blocks) {
                state.space(10f)
                state.drawWrapped(b.title, itemTitlePaint, 20f)
                if (b.meta.isNotBlank()) state.drawWrapped(b.meta, metaPaint, 14f)
                if (b.body.isNotBlank()) for (line in b.body.split("\n")) state.drawWrapped(line, bodyPaint, 15f)
                if (b.checklist.isNotEmpty()) {
                    state.drawWrapped("${b.checklistLabel}:", labelPaint, 15f)
                    for ((done, text) in b.checklist) state.drawWrapped("${if (done) "\u2611" else "\u2610"} $text", bodyPaint, 15f)
                }
                if (b.attachments.isNotEmpty()) state.drawWrapped("Attachments: ${b.attachments.joinToString(", ")}", metaPaint, 15f)
            }
        }

        state.finish()
        val baos = ByteArrayOutputStream()
        doc.writeTo(baos)
        doc.close()
        return baos.toByteArray()
    }

    // Tracks the current page and vertical cursor, starting new pages as content overflows.
    private class PdfState(val doc: PdfDocument) {
        private var page: PdfDocument.Page? = null
        private var y = MARGIN
        private var pageNum = 0

        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNum++
            page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNum).create())
            y = MARGIN
        }

        fun space(dy: Float) { y += dy }

        // Draw [text] word-wrapped to the page width at the current cursor, advancing by [lineAdvance]
        // per rendered line and flowing onto a new page when the bottom margin is reached.
        fun drawWrapped(text: String, paint: Paint, lineAdvance: Float) {
            val maxWidth = PAGE_W - 2 * MARGIN
            val words = if (text.isEmpty()) listOf("") else text.split(" ")
            var line = StringBuilder()
            fun flush() {
                if (y + lineAdvance > PAGE_H - MARGIN) newPage()
                page?.canvas?.drawText(line.toString(), MARGIN, y, paint)
                y += lineAdvance
            }
            for (w in words) {
                val candidate = if (line.isEmpty()) w else "$line $w"
                if (paint.measureText(candidate) > maxWidth && line.isNotEmpty()) {
                    flush()
                    line = StringBuilder(w)
                } else {
                    line = StringBuilder(candidate)
                }
            }
            flush()
        }

        fun finish() { page?.let { doc.finishPage(it) }; page = null }
    }

    // ============================ ZIP + XML helpers ============================

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    private fun xmlEscape(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                // Strip control characters that are illegal in XML 1.0 (tab/newline/CR are allowed).
                else -> if (ch.code < 0x20 && ch != '\t' && ch != '\n' && ch != '\r') sb.append(' ') else sb.append(ch)
            }
        }
        return sb.toString()
    }

    // ============================ Export bundling with attachments ============================
    //
    // When the user ticks attachments to embed, the chosen document is written into a .zip next to an
    // `attachments/` folder holding the actual files. The document itself is unchanged — it still only
    // *names* its attachments — so a reader gets both the readable export and the files it refers to.

    /**
     * Build a .zip holding [documentBytes] (written as [documentName], e.g. "lucent-notes.md") plus
     * the bytes of every attachment in [attachments], each under `attachments/` with a de-duplicated
     * file name. Attachments that can't be read (missing on disk, oversized) are skipped rather than
     * failing the whole export — reading each one fully before writing its entry means a skip never
     * leaves a half-written entry that would corrupt the archive.
     */
    fun zipWithAttachments(
        context: android.content.Context,
        documentName: String,
        documentBytes: ByteArray,
        attachments: List<Attachment>
    ): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry(documentName))
            zos.write(documentBytes)
            zos.closeEntry()

            val usedNames = hashSetOf(documentName)
            for (att in attachments) {
                val bytes = Attachments.readBytes(context, att, maxBytes = 256L * 1024 * 1024) ?: continue
                val entryName = "attachments/" + uniqueEntryName(att.name.ifBlank { "file" }, usedNames)
                zos.putNextEntry(ZipEntry(entryName))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    /** Make [name] unique within [used] by inserting " (2)", " (3)", … before the extension. */
    private fun uniqueEntryName(name: String, used: MutableSet<String>): String {
        if (used.add(name)) return name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 2
        while (true) {
            val candidate = "$stem ($n)$ext"
            if (used.add(candidate)) return candidate
            n++
        }
    }
}
