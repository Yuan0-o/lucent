package com.lucent.app.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import kotlin.math.pow

/**
 * Desktop twin of the Android UsageTracker: how often, and how recently, each note and task is
 * opened — the raw material for the "Recent" home sections and the desktop Insights page.
 *
 * Storage is its own small JSON file (`lucent_usage.json`), separate from settings for the same
 * reason Android keeps it in its own DataStore: access counts are throwaway UI state that must
 * never travel in a backup or an export. Scoring math is copied verbatim so the two platforms rank
 * identically for identical histories.
 */
object UsageTracker {

    enum class Kind(val storeKey: String) { NOTE("notes_usage"), TASK("tasks_usage") }

    private const val RECENCY_HALF_LIFE_DAYS = 5.0
    private const val DAY_MILLIS = 24.0 * 60 * 60 * 1000

    private data class Entry(val count: Int, val lastOpened: Long)

    // kind.storeKey -> serialized map. One StateFlow keeps scores() live like the DataStore Flow.
    private val state = MutableStateFlow<Map<String, String>>(emptyMap())
    private val mutex = Mutex()
    @Volatile private var loaded = false

    private fun file(context: Context) = File(context.applicationContext.filesDir, "lucent_usage.json")

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            state.value = try {
                val f = file(context)
                if (!f.exists()) emptyMap() else {
                    val obj = JSONObject(f.readText())
                    buildMap { obj.keys().forEach { k -> put(k, obj.optString(k, "")) } }
                }
            } catch (t: Throwable) {
                emptyMap()
            }
            loaded = true
        }
    }

    private fun persist(context: Context, values: Map<String, String>) {
        try {
            val obj = JSONObject()
            values.forEach { (k, v) -> obj.put(k, v) }
            val f = file(context)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(obj.toString())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (_: Throwable) {
        }
    }

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
        map.forEach { (id, e) -> obj.put(id.toString(), JSONObject().put("c", e.count).put("t", e.lastOpened)) }
        return obj.toString()
    }

    /** Wipe every recorded open. Used by "Clear all data". */
    suspend fun clearAll(context: Context) {
        ensureLoaded(context)
        mutex.withLock {
            state.value = emptyMap()
            persist(context, emptyMap())
        }
    }

    suspend fun recordOpen(context: Context, kind: Kind, id: Long) {
        ensureLoaded(context)
        val now = System.currentTimeMillis()
        mutex.withLock {
            val current = state.value.toMutableMap()
            val map = parse(current[kind.storeKey]).toMutableMap()
            val existing = map[id]
            map[id] = Entry((existing?.count ?: 0) + 1, now)
            current[kind.storeKey] = serialize(map)
            state.value = current
            persist(context, current)
        }
    }

    /** A live map of id → activity score for [kind]. Higher means "more active". */
    fun scores(context: Context, kind: Kind): Flow<Map<Long, Double>> {
        ensureLoaded(context)
        return state.map { values ->
            val now = System.currentTimeMillis()
            parse(values[kind.storeKey]).mapValues { (_, e) -> openScore(e.count, e.lastOpened, now) }
        }
    }

    private fun openScore(count: Int, lastOpened: Long, now: Long): Double {
        if (count <= 0) return 0.0
        val ageDays = ((now - lastOpened).coerceAtLeast(0)).toDouble() / DAY_MILLIS
        val recency = 0.5.pow(ageDays / RECENCY_HALF_LIFE_DAYS)
        return (1.0 + count).pow(0.6) * recency
    }

    /** The final ranking score blending open-activity with edit recency — verbatim Android math. */
    fun score(openActivity: Double, updatedAt: Long, now: Long): Double {
        val ageDays = ((now - updatedAt).coerceAtLeast(0)).toDouble() / DAY_MILLIS
        val editRecency = 0.5.pow(ageDays / RECENCY_HALF_LIFE_DAYS)
        return openActivity + editRecency
    }
}
