package com.lucent.app.data

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
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
 * documents this simple. Desktop adaptation: the PDF section uses Apache PDFBox with the app's
 * bundled CJK typefaces embedded, because the JVM has no platform PDF canvas; the docx/xlsx
 * builders and every string in every format are the Android file verbatim.
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
    //
    // Desktop implementation over Apache PDFBox. Layout, wording, sizes, and page metrics mirror
    // the Android PdfDocument version: A4, 42pt margins, the same heading/meta/body cascade, the
    // same word-wrap flow with page breaks. Fonts are the app's own bundled TTFs, embedded so
    // Chinese/Japanese/Korean content renders instead of degrading to placeholder glyphs; a line is
    // drawn with the first embedded face able to encode it, falling back per line rather than per
    // document so mixed-language exports come out whole.

    private fun notesPdf(notes: List<Note>): ByteArray =
        pdf("Lucent notes", notes.size, "note", notes.map { noteBlock(it) })

    private fun tasksPdf(tasks: List<Task>): ByteArray =
        pdf("Lucent tasks", tasks.size, "task", tasks.map { taskBlock(it) })

    // A4 at 72dpi, in points — same metrics as the Android renderer.
    private const val PAGE_W = 595f
    private const val PAGE_H = 842f
    private const val MARGIN = 42f

    /** A text style: which faces to try (regular set or the same set drawn "bold") and the size. */
    private data class PdfStyle(val size: Float, val bold: Boolean, val gray: Boolean = false)

    private fun pdf(heading: String, count: Int, noun: String, blocks: List<Block>): ByteArray {
        PDDocument().use { doc ->
            val fonts = loadPdfFonts(doc)
            val state = PdfState(doc, fonts)
            state.newPage()

            val titleStyle = PdfStyle(20f, bold = true)
            val itemTitleStyle = PdfStyle(15f, bold = true)
            val metaStyle = PdfStyle(10f, bold = false, gray = true)
            val bodyStyle = PdfStyle(11f, bold = false)
            val labelStyle = PdfStyle(11f, bold = true)

            state.drawWrapped(heading, titleStyle, 26f)
            state.drawWrapped("$count $noun${if (count == 1) "" else "s"}, exported ${formatTime(System.currentTimeMillis())}", metaStyle, 14f)
            state.drawWrapped("Attachments are listed by name but not embedded. Use the .json backup for the bytes.", metaStyle, 16f)

            if (blocks.isEmpty()) {
                state.drawWrapped("No ${noun}s yet.", metaStyle, 14f)
            } else {
                for (b in blocks) {
                    state.space(10f)
                    state.drawWrapped(b.title, itemTitleStyle, 20f)
                    if (b.meta.isNotBlank()) state.drawWrapped(b.meta, metaStyle, 14f)
                    if (b.body.isNotBlank()) for (line in b.body.split("\n")) state.drawWrapped(line, bodyStyle, 15f)
                    if (b.checklist.isNotEmpty()) {
                        state.drawWrapped("${b.checklistLabel}:", labelStyle, 15f)
                        for ((done, text) in b.checklist) state.drawWrapped("${if (done) "\u2611" else "\u2610"} $text", bodyStyle, 15f)
                    }
                    if (b.attachments.isNotEmpty()) state.drawWrapped("Attachments: ${b.attachments.joinToString(", ")}", metaStyle, 15f)
                }
            }

            state.finish()
            val baos = ByteArrayOutputStream()
            doc.save(baos)
            return baos.toByteArray()
        }
    }

    /**
     * The embedded faces, tried in order per line. The app bundles no fonts any more (font library
     * task), so the candidates are the fonts the USER has imported — in library order, the same
     * order the settings picker shows — with PDFBox's built-in Helvetica as the floor. A face that
     * fails to load (a .ttc collection, a face PDFBox cannot embed) is simply skipped, exactly as a
     * missing bundled face was before: a device with no imported fonts still produces a
     * (Latin-only) PDF rather than none, and glyphs no face can encode fall back to "\u00B7".
     */
    private fun loadPdfFonts(doc: PDDocument): List<PDFont> {
        val faces = mutableListOf<PDFont>()
        val context = android.content.DesktopContext
        for (slot in FontStore.fonts(context)) {
            try {
                FontStore.fontFile(context, slot.id)?.inputStream()?.use { stream ->
                    faces.add(PDType0Font.load(doc, stream, true))
                }
            } catch (_: Throwable) {
            }
        }
        faces.add(PDType1Font(Standard14Fonts.FontName.HELVETICA))
        return faces
    }

    // Tracks the current page and vertical cursor, starting new pages as content overflows — the
    // same shape as the Android PdfState, with PDFBox's bottom-left origin translated internally.
    private class PdfState(val doc: PDDocument, val fonts: List<PDFont>) {
        private var content: PDPageContentStream? = null
        private var y = MARGIN

        fun newPage() {
            content?.close()
            val page = PDPage(PDRectangle(PAGE_W, PAGE_H))
            doc.addPage(page)
            content = PDPageContentStream(doc, page)
            y = MARGIN
        }

        fun space(dy: Float) { y += dy }

        private fun fontFor(text: String): PDFont {
            for (f in fonts) {
                try {
                    f.encode(text)
                    return f
                } catch (_: Throwable) {
                }
            }
            return fonts.last()
        }

        private fun width(font: PDFont, text: String, size: Float): Float = try {
            font.getStringWidth(text) / 1000f * size
        } catch (_: Throwable) {
            text.length * size * 0.6f
        }

        /** Drop characters the chosen font cannot encode so one exotic glyph never voids a line. */
        private fun encodable(font: PDFont, text: String): String {
            return buildString {
                for (ch in text) {
                    val s = ch.toString()
                    val ok = try { font.encode(s); true } catch (_: Throwable) { false }
                    append(if (ok) s else "\u00B7")
                }
            }
        }

        private fun drawLine(text: String, style: PdfStyle) {
            val stream = content ?: return
            val font = fontFor(text)
            val safe = if (font === fonts.last()) encodable(font, text) else text
            try {
                stream.beginText()
                if (style.gray) stream.setNonStrokingColor(0.33f, 0.33f, 0.33f)
                else stream.setNonStrokingColor(0f, 0f, 0f)
                stream.setFont(font, style.size)
                // PDFBox's origin is bottom-left; the cursor tracks top-down like Android's.
                stream.newLineAtOffset(MARGIN, PAGE_H - y)
                stream.showText(safe)
                if (style.bold) {
                    // Faux bold, matching Paint.isFakeBoldText: re-draw nudged right by a hairline.
                    stream.newLineAtOffset(0.35f, 0f)
                    stream.showText(safe)
                }
                stream.endText()
            } catch (_: Throwable) {
                try { stream.endText() } catch (_: Throwable) {}
            }
        }

        // Word-wrapped drawing with the Android flow, plus character-level splitting for a single
        // "word" wider than the page — which is what an unbroken CJK sentence is.
        fun drawWrapped(text: String, style: PdfStyle, lineAdvance: Float) {
            val maxWidth = PAGE_W - 2 * MARGIN
            val font = fontFor(text)

            fun flushLine(line: String) {
                if (y + lineAdvance > PAGE_H - MARGIN) newPage()
                drawLine(line, style)
                y += lineAdvance
            }

            fun emitLong(word: String) {
                var current = StringBuilder()
                for (ch in word) {
                    val candidate = current.toString() + ch
                    if (width(font, candidate, style.size) > maxWidth && current.isNotEmpty()) {
                        flushLine(current.toString())
                        current = StringBuilder().append(ch)
                    } else {
                        current = StringBuilder(candidate)
                    }
                }
                if (current.isNotEmpty()) flushLine(current.toString())
            }

            val words = if (text.isEmpty()) listOf("") else text.split(" ")
            var line = StringBuilder()
            for (w in words) {
                val candidate = if (line.isEmpty()) w else "$line $w"
                if (width(font, candidate, style.size) > maxWidth && line.isNotEmpty()) {
                    flushLine(line.toString())
                    line = StringBuilder(w)
                } else {
                    line = StringBuilder(candidate)
                }
                if (width(font, line.toString(), style.size) > maxWidth) {
                    emitLong(line.toString())
                    line = StringBuilder()
                }
            }
            flushLine(line.toString())
        }

        fun finish() { content?.close(); content = null }
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
