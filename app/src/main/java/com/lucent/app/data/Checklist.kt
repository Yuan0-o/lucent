package com.lucent.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A single checklist entry. Used in two places: a NOTE in checklist mode (Keep-style check-off
 * items instead of a plain body), and a TASK's own subtasks. Both are stored the same way — a small
 * JSON array on the owning row — via [Checklist], which is the same pattern [Attachments] already
 * uses for the attachments column. One model, one serializer, two call sites.
 *
 * [id] is a short opaque token (not the list position) so a specific item can be toggled or removed
 * even when two items share the same text, and so a concurrent edit elsewhere can't shift the item
 * a click was aimed at.
 */
data class ChecklistItem(
    val id: String,
    val text: String,
    val done: Boolean = false
)

object Checklist {

    /** Parse the JSON-array string stored on a Note/Task into a list. Never throws. */
    fun parse(json: String?): List<ChecklistItem> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                ChecklistItem(
                    id = o.optString("id", "").ifBlank { UUID.randomUUID().toString() },
                    text = o.optString("text", ""),
                    done = o.optBoolean("done", false)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Serialize a list back into the JSON-array string form stored in the database. */
    fun serialize(list: List<ChecklistItem>): String {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("text", it.text)
                    .put("done", it.done)
            )
        }
        return arr.toString()
    }

    /** A brand-new, not-done item with a fresh id. */
    fun newItem(text: String): ChecklistItem =
        ChecklistItem(id = UUID.randomUUID().toString(), text = text.trim())

    /** Append one new, not-done item. Blank text is a no-op (returns [json] unchanged). */
    fun add(json: String, text: String): String {
        if (text.isBlank()) return json
        return serialize(parse(json) + newItem(text))
    }

    /**
     * Append several items at once, splitting [raw] on newlines and semicolons. This is what the
     * assistant uses when it creates a task with an initial checklist in a single tool call, so
     * "milk; eggs; bread" becomes three items rather than one long one.
     */
    fun addAll(json: String, raw: String): String {
        val texts = raw.split('\n', ';').map { it.trim() }.filter { it.isNotEmpty() }
        if (texts.isEmpty()) return json
        return serialize(parse(json) + texts.map { newItem(it) })
    }

    /** Flip one item's done state by id. No-op if the id isn't present. */
    fun toggle(json: String, id: String): String =
        serialize(parse(json).map { if (it.id == id) it.copy(done = !it.done) else it })

    /** Set one item's done state explicitly by id. No-op if the id isn't present. */
    fun setDone(json: String, id: String, done: Boolean): String =
        serialize(parse(json).map { if (it.id == id) it.copy(done = done) else it })

    /** Change one item's text by id. No-op if the id isn't present. */
    fun updateText(json: String, id: String, newText: String): String =
        serialize(parse(json).map { if (it.id == id) it.copy(text = newText) else it })

    /** Drop one item by id. No-op if the id isn't present. */
    fun remove(json: String, id: String): String =
        serialize(parse(json).filterNot { it.id == id })

    /** "done, total" — null if the list is empty, so callers can hide an empty progress badge. */
    fun progress(json: String?): Pair<Int, Int>? {
        val list = parse(json)
        if (list.isEmpty()) return null
        return list.count { it.done } to list.size
    }

    /**
     * Every item unchecked, keeping the items themselves. Used when a repeating task spawns its
     * next occurrence: the steps carry over, the ticks don't.
     */
    fun resetDone(json: String?): String = serialize(parse(json).map { it.copy(done = false) })

    /**
     * Fuzzy-match one item by its text — the same "exact match wins, otherwise a single unambiguous
     * partial match" rule the assistant's note/task title matching already uses. Returns null if
     * nothing matches, or if more than one item partially matches (too ambiguous to guess at, so
     * the assistant is told to ask rather than pick).
     */
    fun findByText(json: String?, query: String): ChecklistItem? {
        val q = query.trim()
        if (q.isEmpty()) return null
        val list = parse(json)
        list.firstOrNull { it.text.equals(q, ignoreCase = true) }?.let { return it }
        val partial = list.filter { it.text.contains(q, ignoreCase = true) }
        return if (partial.size == 1) partial.first() else null
    }

    /** Plain-text rendering, e.g. for sharing a note or exporting it to Markdown. */
    fun toMarkdown(json: String?): String =
        parse(json).joinToString("\n") { "- [${if (it.done) "x" else " "}] ${it.text}" }
}
