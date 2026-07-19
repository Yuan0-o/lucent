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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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

/**
 * A pick-what-to-export screen: a searchable, checkbox list with Select-All, used for exporting a
 * *chosen subset* of notes (or tasks) rather than only ever all of them, in a format the user picks
 * (Markdown, Word, PDF, or Excel).
 *
 * Generic over the item type so notes and tasks share one implementation. Items are shown newest
 * first (the app's usual order) and can be narrowed with the search box; Select-All applies to
 * whatever the search currently shows, so you can e.g. search a tag and tick everything under it in
 * one tap. [onExport] receives the selected items (in newest-first order) and the chosen format.
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
    onExport: (List<T>, com.lucent.app.data.ExportFormat) -> Unit,
    onBack: () -> Unit
) {
    val onGradient = LocalOnGradient.current
    val onGradientMuted = LocalOnGradientMuted.current

    var query by remember { mutableStateOf("") }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var selectedFormat by remember { mutableStateOf(com.lucent.app.data.ExportFormat.MARKDOWN) }

    // Newest first, then narrowed by the search box (case-insensitive substring).
    val ordered = remember(items) { items.sortedByDescending { timestamp(it) } }
    val visible = remember(ordered, query) {
        if (query.isBlank()) ordered
        else ordered.filter { searchText(it).contains(query.trim(), ignoreCase = true) }
    }
    val visibleIds = visible.map(id).toSet()
    val allVisibleSelected = visibleIds.isNotEmpty() && selectedIds.containsAll(visibleIds)

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = onGradient)
            }
            Text(title, color = onGradient, fontSize = 20.sp, modifier = Modifier.weight(1f))
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Select-all / clear-all for the currently visible items.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable {
                    selectedIds = if (allVisibleSelected) selectedIds - visibleIds else selectedIds + visibleIds
                }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = allVisibleSelected, onCheckedChange = {
                selectedIds = if (allVisibleSelected) selectedIds - visibleIds else selectedIds + visibleIds
            })
            Text(
                if (query.isBlank()) "Select all" else "Select all matching",
                color = onGradient,
                modifier = Modifier.weight(1f).padding(start = 4.dp)
            )
            Text("${selectedIds.size} selected", color = onGradientMuted, fontSize = 13.sp)
        }

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(visible, key = { id(it) }) { item ->
                val checked = id(item) in selectedIds
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val i = id(item)
                            selectedIds = if (i in selectedIds) selectedIds - i else selectedIds + i
                        }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = checked, onCheckedChange = {
                        val i = id(item)
                        selectedIds = if (i in selectedIds) selectedIds - i else selectedIds + i
                    })
                    Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                        Text(label(item).ifBlank { "Untitled" }, color = onGradient, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sub = subtitle(item)
                        if (sub.isNotBlank()) {
                            Text(sub, color = onGradientMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Format picker (task 1): the same selection can be written as Markdown, Word, PDF, or Excel.
        Text("Format", color = onGradientMuted, fontSize = 12.sp)
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

        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = {
                val chosen = ordered.filter { id(it) in selectedIds }
                if (chosen.isNotEmpty()) onExport(chosen, selectedFormat)
            },
            enabled = selectedIds.isNotEmpty(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (selectedIds.isEmpty()) "Export" else "Export ${selectedIds.size} selected")
        }
    }
}
