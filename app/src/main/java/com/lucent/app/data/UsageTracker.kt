package com.lucent.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import kotlin.math.pow

private val Context.usageDataStore by preferencesDataStore(name = "lucent_usage")

/**
 * Tracks how often, and how recently, each note and task is opened — the raw material for the
 * "Recent" section on the home lists (see [com.lucent.app.ui.HomeSection]).
 *
 * ### What "recent" should mean
 *
 * A useful "recent" row is not simply "the last thing I touched". A note you open every single day
 * should live at the top even on a day you haven't opened it yet, and a note you opened once three
 * weeks ago should not. So this blends two signals — **how many times** an item has been opened
 * (frequency) and **how long ago** the last open was (recency) — into one activity score, and the
 * screen shows the highest-scoring handful.
 *
 * ### Why DataStore and not a new column
 *
 * Access counts are throwaway UI state, not user data: losing them costs nothing and they must never
 * end up in a backup or a Markdown export. Keeping them in their own small preferences file (rather
 * than adding columns to the notes/tasks tables) means no Room migration, no risk to the real data,
 * and no chance of an open-count travelling inside an exported note. Each kind is one JSON object
 * mapping id → {count, lastOpenedMillis}; a phone never opens enough distinct items for that to grow
 * concerning.
 */
object UsageTracker {

    enum class Kind(val storeKey: String) { NOTE("notes_usage"), TASK("tasks_usage") }

    private fun keyFor(kind: Kind) = stringPreferencesKey(kind.storeKey)

    /** Half-life of the recency term, in days: an item not opened for this long counts for half. */
    private const val RECENCY_HALF_LIFE_DAYS = 5.0
    private const val DAY_MILLIS = 24.0 * 60 * 60 * 1000

    private data class Entry(val count: Int, val lastOpened: Long)

    private fun parse(json: String?): Map<Long, Entry> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val obj = JSONObject(json)
            buildMap {
                obj.keys().forEach { k ->
                    val id = k.toLongOrNull() ?: return@forEach
                    val e = obj.optJSONObject(k) ?: return@forEach
                    put(id, Entry(e.optInt("c", 0), e.optLong("t", 0L)))
                }
            }
        } catch (t: Throwable) {
            emptyMap()
        }
    }

    private fun serialize(map: Map<Long, Entry>): String {
        val obj = JSONObject()
        map.forEach { (id, e) ->
            obj.put(id.toString(), JSONObject().put("c", e.count).put("t", e.lastOpened))
        }
        return obj.toString()
    }

    /**
     * Record that [id] of [kind] was just opened: bump its count and stamp the open time. Runs a
     * DataStore edit, so call it off the main thread (or from a coroutine) — the callers do.
     */
    /**
     * Wipe every recorded open. Used by "Clear all data" (task 8).
     *
     * This lives in its own DataStore (`lucent_usage`), separate from `lucent_settings`, so the
     * settings repository's clearAll never touched it — which meant a full wipe left the Recent
     * section still ranking notes that no longer existed, scored by a history of the deleted app.
     * Harmless in effect but wrong in principle: after a wipe there is nothing the app should
     * remember about what you used to look at.
     */
    suspend fun clearAll(context: Context) {
        context.usageDataStore.edit { it.clear() }
    }

    suspend fun recordOpen(context: Context, kind: Kind, id: Long) {
        val now = System.currentTimeMillis()
        context.usageDataStore.edit { prefs ->
            val map = parse(prefs[keyFor(kind)]).toMutableMap()
            val existing = map[id]
            map[id] = Entry((existing?.count ?: 0) + 1, now)
            prefs[keyFor(kind)] = serialize(map)
        }
    }

    /**
     * A live map of id → activity score for [kind]. Higher means "more active". An id absent from the
     * map has never been opened and scores zero. Combine with the item's own modification time at the
     * call site (see [score]) so a freshly edited but never-opened item can still surface.
     */
    fun scores(context: Context, kind: Kind): Flow<Map<Long, Double>> =
        context.usageDataStore.data.map { prefs ->
            val now = System.currentTimeMillis()
            parse(prefs[keyFor(kind)]).mapValues { (_, e) -> openScore(e.count, e.lastOpened, now) }
        }

    /** The frequency×recency score from opens alone. Public so it can be reasoned about in one place. */
    private fun openScore(count: Int, lastOpened: Long, now: Long): Double {
        if (count <= 0) return 0.0
        val ageDays = ((now - lastOpened).coerceAtLeast(0)).toDouble() / DAY_MILLIS
        val recency = 0.5.pow(ageDays / RECENCY_HALF_LIFE_DAYS)
        // A gentle sub-linear weighting of frequency so a much-opened item leads without a
        // single runaway count burying everything else.
        return (1.0 + count).pow(0.6) * recency
    }

    /**
     * The final ranking score for one item, blending its open-activity ([openActivity], from
     * [scores]) with how recently it was modified ([updatedAt]). Editing something is itself a strong
     * signal it's "active", so a note saved a minute ago ranks near a note opened repeatedly — even
     * with no opens recorded yet. Pure arithmetic, so the ordering is easy to predict and test.
     */
    fun score(openActivity: Double, updatedAt: Long, now: Long): Double {
        val ageDays = ((now - updatedAt).coerceAtLeast(0)).toDouble() / DAY_MILLIS
        val editRecency = 0.5.pow(ageDays / RECENCY_HALF_LIFE_DAYS)
        return openActivity + editRecency
    }
}
