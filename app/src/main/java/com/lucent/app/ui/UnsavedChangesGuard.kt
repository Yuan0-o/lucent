package com.lucent.app.ui

import androidx.compose.runtime.mutableStateMapOf

/**
 * App-lifetime tracker so navigation that happens *outside* a screen's own composable — switching
 * bottom-nav tabs, or the system back button closing the app — can tell whether the screen
 * currently on-screen has an open editor with unsaved changes, and, if so, ask before losing them.
 *
 * Whichever screen has dirty state (Settings > Assistant, or the Notes/Tasks composer) calls
 * [register] (with its own [owner] key) every time its dirty flag becomes true, and [clear] once
 * it's no longer dirty, e.g. after a save/discard or when the editor closes.
 *
 * ### Why registrations are keyed by owner
 *
 * The top-level tabs are now kept alive across switches (so the drifting background no longer
 * stutters while a heavy screen is torn down and rebuilt on every tab change). That means more than
 * one screen can be *composed* at once, and an off-screen screen can recompose in the background —
 * for example when the assistant edits a note while you're on another tab — and run its "not dirty,
 * so clear the guard" side-effect. With a single shared flag that stray clear would wipe out the
 * *visible* screen's genuine unsaved-changes registration, and a tab switch would then silently drop
 * an in-progress edit.
 *
 * Keying each registration by its owner fixes that: a screen only ever registers or clears its own
 * slot, so one tab clearing itself can never erase another tab's pending edit. Only the on-screen
 * tab can actually be edited, so in practice at most one slot is ever occupied, and [dirty] simply
 * reports whether *any* slot is.
 */
object UnsavedChangesGuard {

    private class Registration(val onSave: () -> Unit, val onDiscard: () -> Unit)

    // Owner key -> its save/discard actions, present only while that owner has unsaved changes.
    // A snapshot-backed map so reads of [dirty] recompose/observe correctly.
    private val registrations = mutableStateMapOf<String, Registration>()

    /** True while any registered owner has unsaved changes. */
    val dirty: Boolean get() = registrations.isNotEmpty()

    /** Mark [owner] as having unsaved changes, recording how to save or discard them. */
    fun register(owner: String, onSave: () -> Unit, onDiscard: () -> Unit) {
        registrations[owner] = Registration(onSave, onDiscard)
    }

    /** Mark [owner] as no longer having unsaved changes. Safe to call when it wasn't registered. */
    fun clear(owner: String) {
        registrations.remove(owner)
    }

    /** Save every pending edit, then clear all registrations. */
    fun save() {
        val pending = registrations.values.toList()
        registrations.clear()
        pending.forEach { it.onSave() }
    }

    /** Discard every pending edit, then clear all registrations. */
    fun discard() {
        val pending = registrations.values.toList()
        registrations.clear()
        pending.forEach { it.onDiscard() }
    }
}
