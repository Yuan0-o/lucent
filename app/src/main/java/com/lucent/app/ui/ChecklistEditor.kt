package com.lucent.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lucent.app.data.ChecklistItem

/**
 * A fully editable checklist: one row per item (checkbox, text, remove) plus a field at the bottom
 * for adding another.
 *
 * Shared verbatim by checklist-mode *notes* and task *subtasks*. They store the same
 * [ChecklistItem] JSON (see [com.lucent.app.data.Checklist]), so the only thing that differs between
 * the two call sites is which column the result is written back to — which is exactly the kind of
 * duplication that starts identical and drifts, so it doesn't get to start.
 *
 * The add field commits on the keyboard's Done key as well as the "+" button, because typing five
 * list items and having to reach for a button between each one is the difference between a feature
 * people use and one they abandon.
 */
@Composable
fun ChecklistEditorSection(
    items: List<ChecklistItem>,
    newItemText: String,
    onNewItemTextChange: (String) -> Unit,
    onAdd: () -> Unit,
    onToggle: (ChecklistItem) -> Unit,
    onRemove: (ChecklistItem) -> Unit,
    addLabel: String,
    modifier: Modifier = Modifier
) {
    val onGradient = LocalOnGradient.current
    val onGradientMuted = LocalOnGradientMuted.current
    Column(modifier = modifier) {
        items.forEach { item ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = item.done, onCheckedChange = { onToggle(item) })
                Text(
                    item.text.ifBlank { "(empty)" },
                    color = if (item.done) onGradientMuted else onGradient,
                    textDecoration = if (item.done) TextDecoration.LineThrough else null,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { onRemove(item) }) {
                    Icon(Icons.Default.Close, contentDescription = "Remove \"${item.text}\"", tint = onGradientMuted)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newItemText,
                onValueChange = onNewItemTextChange,
                label = { Text(addLabel) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (newItemText.isNotBlank()) onAdd() }),
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(4.dp))
            IconButton(onClick = { if (newItemText.isNotBlank()) onAdd() }) {
                Icon(Icons.Default.Add, contentDescription = addLabel, tint = onGradient)
            }
        }
    }
}

/**
 * A read-only checklist that ticks off in place — for a detail page, where the list is content
 * rather than something being composed.
 *
 * [onToggle] being null makes every checkbox genuinely non-interactive rather than merely
 * greyed-out, which is how a *completed* task's subtasks are shown: a finished task is locked, and a
 * checkbox that looks pressable but silently does nothing is the worst of both worlds.
 */
@Composable
fun ChecklistView(
    items: List<ChecklistItem>,
    onToggle: ((ChecklistItem, Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    header: String? = null
) {
    if (items.isEmpty()) return
    val onGradient = LocalOnGradient.current
    val onGradientMuted = LocalOnGradientMuted.current
    val done = items.count { it.done }
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            "${header ?: "Checklist"} · $done/${items.size}",
            color = onGradientMuted,
            fontSize = 12.sp
        )
        items.forEach { item ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = item.done,
                    onCheckedChange = onToggle?.let { toggle -> { checked -> toggle(item, checked) } }
                )
                Text(
                    item.text.ifBlank { "(empty)" },
                    color = if (item.done) onGradientMuted else onGradient,
                    textDecoration = if (item.done) TextDecoration.LineThrough else null,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * A compact preview for a card: the first few items with shrunken checkboxes, then a "+N more"
 * line. Lets a checklist note's *shape* be visible from the grid without opening it, which is the
 * only reason to render a checklist on a card at all — the point is "three of five ticked", not the
 * items themselves.
 */
@Composable
fun ChecklistPreviewInline(
    items: List<ChecklistItem>,
    modifier: Modifier = Modifier,
    maxVisible: Int = 3
) {
    val onGradientMuted = LocalOnGradientMuted.current
    Column(modifier = modifier) {
        items.take(maxVisible).forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = item.done,
                    onCheckedChange = null,
                    modifier = Modifier.scale(0.7f)
                )
                Text(
                    item.text.ifBlank { "(empty)" },
                    color = onGradientMuted,
                    fontSize = 13.sp,
                    textDecoration = if (item.done) TextDecoration.LineThrough else null,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (items.size > maxVisible) {
            Text("+${items.size - maxVisible} more", color = onGradientMuted, fontSize = 12.sp)
        }
    }
}
