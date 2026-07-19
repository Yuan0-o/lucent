package com.lucent.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.lucent.app.data.Note
import com.lucent.app.data.SearchQuery
import com.lucent.app.data.Task

/** Sort choices for the Notes home list. Persisted via [com.lucent.app.data.SettingsRepository.notesSort]. */
enum class NoteSort(val key: String, val label: String) {
    RECENT("recent", "Last edited"),
    OLDEST("oldest", "Oldest first"),
    TITLE_AZ("title_az", "Title A–Z");

    companion object {
        fun fromKey(key: String?): NoteSort = entries.firstOrNull { it.key == key } ?: RECENT
    }
}

/** Sort choices for the Tasks home list. Persisted via [com.lucent.app.data.SettingsRepository.tasksSort]. */
enum class TaskSort(val key: String, val label: String) {
    RECENT("recent", "Newest first"),
    OLDEST("oldest", "Oldest first"),
    TITLE_AZ("title_az", "Title A–Z"),
    PRIORITY("priority", "Priority"),
    DUE_DATE("due", "Due date");

    companion object {
        fun fromKey(key: String?): TaskSort = entries.firstOrNull { it.key == key } ?: RECENT
    }
}

/**
 * Order notes for display: pinned first, then — when the user is actually searching — best match,
 * then their chosen sort.
 *
 * The three-way precedence is the whole design, and it's worth spelling out. **Pinned always wins**,
 * because pinning is an explicit instruction to keep something at the top and nothing should
 * override it. **Relevance only leads when the query has search terms in it** — a plain `is:pinned`
 * filter or an empty box has nothing to be "relevant" to, so every row ties at zero and the user's
 * sort takes over, exactly as it did before search existed. And **the user's sort always decides the
 * rest**, so choosing "Title A–Z" and then typing a letter doesn't silently throw that choice away.
 *
 * The alternative — letting relevance override the sort whenever the box is non-empty — sounds
 * tidier and is much worse to use: you pick an order, start filtering, and watch your order
 * evaporate.
 */
fun List<Note>.sortedForDisplay(sort: NoteSort, query: SearchQuery = SearchQuery()): List<Note> {
    val chosen: Comparator<Note> = when (sort) {
        NoteSort.RECENT -> compareByDescending { it.updatedAt }
        NoteSort.OLDEST -> compareBy { it.updatedAt }
        NoteSort.TITLE_AZ -> compareBy { it.title.lowercase() }
    }
    val ranked = query.terms.isNotEmpty() || query.phrases.isNotEmpty()
    val comparator = if (ranked) {
        compareByDescending<Note> { it.pinned }
            .thenByDescending { query.rank(it) }
            .then(chosen)
    } else {
        compareByDescending<Note> { it.pinned }.then(chosen)
    }
    return sortedWith(comparator)
}

/** The same rule for tasks. See [sortedForDisplay] above for why the precedence is what it is. */
fun List<Task>.sortedForDisplay(sort: TaskSort, query: SearchQuery = SearchQuery()): List<Task> {
    val chosen: Comparator<Task> = when (sort) {
        TaskSort.RECENT -> compareByDescending { it.createdAt }
        TaskSort.OLDEST -> compareBy { it.createdAt }
        TaskSort.TITLE_AZ -> compareBy { it.title.lowercase() }
        TaskSort.PRIORITY -> compareByDescending<Task> { it.priority }.thenByDescending { it.createdAt }
        // Undated tasks sort last rather than first: a task with no due date isn't "due at the
        // beginning of time", it's simply not on the calendar, and burying the ones that *are* under
        // it would make the sort useless for the only question it exists to answer.
        TaskSort.DUE_DATE -> compareBy<Task> { it.dueAt == null }
            .thenBy { it.dueAt ?: Long.MAX_VALUE }
            .thenByDescending { it.createdAt }
    }
    val ranked = query.terms.isNotEmpty() || query.phrases.isNotEmpty()
    val comparator = if (ranked) {
        compareByDescending<Task> { it.pinned }
            .thenByDescending { query.rank(it) }
            .then(chosen)
    } else {
        compareByDescending<Task> { it.pinned }.then(chosen)
    }
    return sortedWith(comparator)
}

/**
 * A compact sort button that opens a dropdown of [options], generic over whichever sort enum the
 * caller passes so Notes and Tasks share one implementation rather than each growing their own.
 *
 * The icon is tinted with [activeTint] while a non-default sort is active, mirroring how the
 * date-filter icon already signals "a filter is on" — so a list that isn't in its usual order says
 * so, instead of leaving the user to wonder why the top item moved.
 */
@Composable
fun <T> SortMenuButton(
    current: T,
    options: List<T>,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    tint: Color,
    activeTint: Color,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val isDefault = options.isNotEmpty() && current == options.first()
    Box(modifier = modifier) {
        IconButton(onClick = { expanded = true }) {
            Icon(
                Icons.AutoMirrored.Filled.Sort,
                contentDescription = "Sort by ${label(current)}",
                tint = if (isDefault) tint else activeTint
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            label(option),
                            fontWeight = if (option == current) FontWeight.Bold else null
                        )
                    },
                    leadingIcon = if (option == current) {
                        { Icon(Icons.Default.Check, contentDescription = null) }
                    } else {
                        null
                    },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    }
                )
            }
        }
    }
}
