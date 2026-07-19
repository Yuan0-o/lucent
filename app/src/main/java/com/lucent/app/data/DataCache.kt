package com.lucent.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lucent.app.AppScope
import kotlinx.coroutines.launch

/**
 * A tiny process-lifetime cache of the notes/tasks lists, kept as Compose state.
 *
 * Screens normally do `db.dao().getAll().collectAsState(initial = emptyList())`. That initial
 * empty list is what the user sees for the first frame every time they open or switch to a
 * screen — a brief flash of "no content" before the Room Flow emits. By collecting each list once
 * here (warmed at app start) and reading the cached value as the `initial` for those
 * collectAsState calls, the first frame already shows the real, latest content — no empty flash,
 * no visible load.
 *
 * This is deliberately just a fast-path seed: the screens still collect the live Flow, so the
 * cache never becomes a second source of truth that could go stale. It only removes the initial
 * blank frame.
 */
object DataCache {

    var notes by mutableStateOf<List<Note>>(emptyList())
        private set
    var activeTasks by mutableStateOf<List<Task>>(emptyList())
        private set
    var completedTasks by mutableStateOf<List<Task>>(emptyList())
        private set

    private var started = false

    /** Begin keeping the cache warm. Idempotent; safe to call from Application/Activity start. */
    fun warm(db: AppDatabase) {
        if (started) return
        started = true
        AppScope.io.launch { db.noteDao().getAll().collect { notes = it } }
        AppScope.io.launch { db.taskDao().getActive().collect { activeTasks = it } }
        AppScope.io.launch { db.taskDao().getCompleted().collect { completedTasks = it } }
    }
}
