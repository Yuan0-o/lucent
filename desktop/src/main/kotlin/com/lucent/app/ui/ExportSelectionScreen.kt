package com.lucent.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lucent.app.data.Attachment

/**
 * A pick-what-to-export screen: a searchable, checkbox list with Select-All, used for exporting a
 * *chosen subset* of notes (or tasks) rather than only ever all of them, in a format the user picks
 * (Markdown, Word, PDF, or Excel).
 *
 * Generic over the item type so notes and tasks share one implementation. Items are shown newest
 * first (the app's usual order) and can be narrowed with the search box; Select-All applies to
 * whatever the search currently shows, so you can e.g. search a tag and tick everything under it in
 * one tap.
 *
 * ### Attachments
 * Once an item is ticked, any files attached to it appear indented beneath it, each with its own
 * circular tick. Ticking an attachment bundles the *actual file* into the export (the document is
 * then written into a .zip alongside an `attachments/` folder); leaving it unticked keeps the old
 * behaviour where the document only names the attachment. Tapping an attachment's name opens it in
 * the standard viewer so you can check what it is before deciding — closing the viewer returns here
 * with every tick preserved. [onExport] receives the selected items, the chosen format, and the flat
 * list of attachments the user ticked to embed.
 */
@Composable
fun <T> ExportSelectionScreen(
    title: String,
    items: List<T>,
    id: (T) -> Long,
    label: (T) -> String,
    subtitle: (T) -> String,
    timestamp: (T) -> Long,
    searchText: (T) -> String,
    attachmentsOf: (T) -> List<Attachment>,
    onExport: (List<T>, com.lucent.app.data.ExportFormat, List<Attachment>) -> Unit,
    onBack: () -> Unit
) {
    val onGradient = LocalOnGradient.current
    val onGradientMuted = LocalOnGradientMuted.current

    // Whether any user-imported fonts exist, read once per visit: the PDF hint below states what
    // a PDF written right now would embed (font library task; see DocumentExport.loadPdfFonts).
    val context = androidx.compose.ui.platform.LocalContext.current
    val hasImportedFonts = remember { com.lucent.app.data.FontStore.fonts(context).isNotEmpty() }

    var query by remember { mutableStateOf("") }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    // Which attachments are ticked for embedding, keyed by "itemId\u0000attachmentName" so two items
    // that happen to share an attachment name never clash. Cleared for an item when it is unticked.
    var selectedAttachmentKeys by remember { mutableStateOf(setOf<String>()) }
    var selectedFormat by remember { mutableStateOf(com.lucent.app.data.ExportFormat.MARKDOWN) }
    // The attachment currently open in the viewer overlay, or null when the list is showing.
    var viewingAttachment by remember { mutableStateOf<Attachment?>(null) }

    fun attKey(itemId: Long, name: String) = "$itemId\u0000$name"

    // Newest first, then narrowed by the search box (case-insensitive substring).
    val ordered = remember(items) { items.sortedByDescending { timestamp(it) } }
    val visible = remember(ordered, query) {
        if (query.isBlank()) ordered
        else ordered.filter { searchText(it).contains(query.trim(), ignoreCase = true) }
    }
    val visibleIds = visible.map(id).toSet()
    val allVisibleSelected = visibleIds.isNotEmpty() && selectedIds.containsAll(visibleIds)

    // Untick an item and forget any attachment ticks that belonged to it.
    fun deselectItem(itemId: Long) {
        selectedIds = selectedIds - itemId
        val prefix = "$itemId\u0000"
        selectedAttachmentKeys = selectedAttachmentKeys.filterNot { it.startsWith(prefix) }.toSet()
    }

    fun toggleItem(itemId: Long) {
        if (itemId in selectedIds) deselectItem(itemId) else selectedIds = selectedIds + itemId
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = com.lucent.app.i18n.S.actionBack, tint = onGradient)
            }
            Text(title, color = onGradient, fontSize = 20.sp, modifier = Modifier.weight(1f))
            // Export lives up here in the title row (task E1) so the floating bottom bar can never sit
            // on top of it. Same enabled state and label logic as the old bottom button.
            Button(
                onClick = {
                    val chosen = ordered.filter { id(it) in selectedIds }
                    val chosenAttachments = chosen.flatMap { item ->
                        attachmentsOf(item).filter { attKey(id(item), it.name) in selectedAttachmentKeys }
                    }
                    if (chosen.isNotEmpty()) onExport(chosen, selectedFormat, chosenAttachments)
                },
                enabled = selectedIds.isNotEmpty(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(if (selectedIds.isEmpty()) com.lucent.app.i18n.S.actionExport else com.lucent.app.i18n.S.exportNSelected(selectedIds.size))
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Select-all / clear-all for the currently visible items. Clearing also forgets the attachment
        // ticks of the items being cleared, so nothing lingers selected under a now-unticked note.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable {
                    if (allVisibleSelected) visibleIds.forEach { deselectItem(it) }
                    else selectedIds = selectedIds + visibleIds
                }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = allVisibleSelected, onCheckedChange = {
                if (allVisibleSelected) visibleIds.forEach { deselectItem(it) }
                else selectedIds = selectedIds + visibleIds
            })
            Text(
                if (query.isBlank()) com.lucent.app.i18n.S.selectAll else com.lucent.app.i18n.S.selectAllMatching,
                color = onGradient,
                modifier = Modifier.weight(1f).padding(start = 4.dp)
            )
            Text(com.lucent.app.i18n.S.nSelected(selectedIds.size), color = onGradientMuted, fontSize = 13.sp)
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = LocalBottomBarInset.current)
        ) {
            items(visible, key = { id(it) }) { item ->
                val checked = id(item) in selectedIds
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { toggleItem(id(item)) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = checked, onCheckedChange = { toggleItem(id(item)) })
                    Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                        Text(label(item).ifBlank { com.lucent.app.i18n.S.untitled }, color = onGradient, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sub = subtitle(item)
                        if (sub.isNotBlank()) {
                            Text(sub, color = onGradientMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }

                // Attachments appear only once the item itself is ticked. Each is a circle you can
                // tick to embed the file; tapping its name opens the viewer (closing it returns here).
                if (checked) {
                    val atts = attachmentsOf(item)
                    if (atts.isNotEmpty()) {
                        Text(
                            com.lucent.app.i18n.S.exportAttachmentsHint,
                            color = onGradientMuted,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(start = 44.dp, bottom = 2.dp)
                        )
                        atts.forEach { att ->
                            val key = attKey(id(item), att.name)
                            val attChecked = key in selectedAttachmentKeys
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 44.dp, top = 2.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (attChecked) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                                    contentDescription = null,
                                    tint = if (attChecked) onGradient else onGradientMuted,
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .clickable {
                                            selectedAttachmentKeys =
                                                if (attChecked) selectedAttachmentKeys - key
                                                else selectedAttachmentKeys + key
                                        }
                                )
                                Text(
                                    att.name,
                                    color = onGradient,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(start = 8.dp)
                                        .clickable { viewingAttachment = att }
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Format picker: the same selection can be written as Markdown, Word, PDF, or Excel.
        Text(com.lucent.app.i18n.S.labelFormat, color = onGradientMuted, fontSize = 12.sp)
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            com.lucent.app.data.ExportFormat.entries.forEach { fmt ->
                FilterChip(
                    selected = selectedFormat == fmt,
                    onClick = { selectedFormat = fmt },
                    label = { Text(fmt.label) }
                )
            }
        }

        // Desktop-only truth-telling for PDF (font library task): a PDF embeds the user's imported
        // fonts — or, with none imported, falls back to a Latin-only face (see
        // DocumentExport.loadPdfFonts). Whichever holds, it is said HERE, while PDF is being
        // chosen, rather than discovered later in a file full of "\u00B7" placeholders.
        if (selectedFormat == com.lucent.app.data.ExportFormat.PDF) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                if (hasImportedFonts) com.lucent.app.i18n.S.exportPdfFontHint
                else com.lucent.app.i18n.S.exportPdfNoFontHint,
                color = onGradientMuted,
                fontSize = 12.sp
            )
        }

        // The format picker is now the last element (Export moved up to the title row, task E1), so a
        // spacer carries the floating-bottom-bar inset to keep it clear of the bar. On desktop that
        // inset is 0, so this collapses to nothing.
        Spacer(modifier = Modifier.height(LocalBottomBarInset.current))
    }

    // Attachment viewer overlay. Dismissing returns to the selection list with all ticks intact.
    viewingAttachment?.let { att ->
        AttachmentViewerDialog(att = att, onDismiss = { viewingAttachment = null })
    }
}
