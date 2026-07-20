package com.lucent.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * A one-shot request to open something on a different tab.
 *
 * ### Why this exists
 *
 * Every screen in Lucent owns its own state — Notes knows which note is open, Tasks knows which task
 * is open — and nothing has ever needed to reach across that line. The unified search screen breaks
 * it: it can be opened from the Notes tab and hand back a *task*, and a search result you cannot open
 * is not a search result. The obvious workaround is to show a toast saying "that one lives on the
 * Tasks tab", which is honest and useless: the user found the thing, and the app's answer was to
 * describe where it is instead of taking them there.
 *
 * So this is the smallest possible bridge. Nothing here is a navigation framework and it should not
 * grow into one. It is three pieces of Compose state, written by whoever wants to navigate and read
 * exactly once by whoever can honour it, and the whole contract is:
 *
 *  - [requestedScreen] — `MainActivity` switches tabs and clears it.
 *  - [pendingNoteId] — `NotesScreen` opens that note's detail page and clears it.
 *  - [pendingTaskId] — `TasksScreen` opens that task's detail page and clears it.
 *
 * **Each field is cleared by its consumer**, which is what makes the request one-shot rather than a
 * standing instruction. Miss that and switching away from Tasks and back would helpfully reopen a
 * task the user closed ten minutes ago.
 *
 * The screen is requested *before* the id, and consumed in the other order: `MainActivity` switches
 * the tab, the destination screen then composes, its `LaunchedEffect` sees the pending id, and it
 * opens. No coordination is needed because Compose already does it — recomposition is the handshake.
 */
object AppNavigation {

    /** The tab to switch to, or null. Consumed by `MainActivity`. */
    var requestedScreen by mutableStateOf<Screen?>(null)
        private set

    /** A note to open on the Notes tab, or null. Consumed by `NotesScreen`. */
    var pendingNoteId by mutableStateOf<Long?>(null)
        private set

    /** A task to open on the Tasks tab, or null. Consumed by `TasksScreen`. */
    var pendingTaskId by mutableStateOf<Long?>(null)
        private set

    /**
     * The tab a cross-tab jump *came from* (task 4), or null for a jump with no origin (a widget
     * tap, say, which comes from outside the app entirely).
     *
     * ### Why a one-way trip was wrong
     *
     * "Search everything" can be opened from either tab and hand back either kind of result, and the
     * bridge above happily took you there. It just never brought you back. Search your tasks, find a
     * note, read it, close it — and you were standing on the Notes tab, because that is where the
     * note lives. Nothing was broken, but the app had quietly moved you somewhere you never asked to
     * go, and the only way back was to notice what had happened and fix it by hand.
     *
     * So a jump now records where it started. The destination screen picks this up along with the id
     * and holds onto it; when the item is closed it asks to go back, and you land where you were. The
     * "somewhere you were" is deliberately the search screen itself rather than the tab's list — the
     * results you were part-way through are still there, so checking a second result costs one tap
     * instead of re-running the search.
     */
    var returnScreen by mutableStateOf<Screen?>(null)
        private set

    // One-shot "start composing a new item" requests, used by the home-screen widgets (task 9). A
    // widget can't open a composer directly, so it launches MainActivity with an action extra; that
    // sets one of these, the relevant screen's LaunchedEffect consumes it and opens its create
    // composer. Same one-shot contract as the ids above — the consumer reads-and-clears via
    // consumeComposeNote/Task, so switching tabs away and back later never re-opens the composer.
    var composeNoteRequested by mutableStateOf(false)
        private set

    var composeTaskRequested by mutableStateOf(false)
        private set

    /**
     * Open a note, switching to the Notes tab first if we aren't already there. [from] is the tab the
     * request came from, remembered so closing the note can return there (task 4); pass null when
     * there is nowhere meaningful to go back to.
     */
    fun openNote(id: Long, from: Screen? = null) {
        pendingNoteId = id
        returnScreen = from
        requestedScreen = Screen.Notes
    }

    /** Open a task, switching to the Tasks tab first if we aren't already there. See [openNote]. */
    fun openTask(id: Long, from: Screen? = null) {
        pendingTaskId = id
        returnScreen = from
        requestedScreen = Screen.Tasks
    }

    /** Ask the Notes tab to open a fresh note composer (from a widget). */
    fun requestComposeNote() {
        composeNoteRequested = true
        requestedScreen = Screen.Notes
    }

    /** Ask the Tasks tab to open a fresh task composer (from a widget). */
    fun requestComposeTask() {
        composeTaskRequested = true
        requestedScreen = Screen.Tasks
    }

    /** Just switch to a tab (e.g. the Assistant) with no further action. */
    fun requestScreen(screen: Screen) {
        requestedScreen = screen
    }

    fun consumeScreen(): Screen? = requestedScreen.also { requestedScreen = null }

    fun consumeNoteId(): Long? = pendingNoteId.also { pendingNoteId = null }

    fun consumeTaskId(): Long? = pendingTaskId.also { pendingTaskId = null }

    /**
     * Read-and-clear the origin tab. Called by the destination screen at the same moment it consumes
     * the id, so the origin is held by whoever can actually honour it and can't leak into a later,
     * unrelated navigation.
     */
    fun consumeReturnScreen(): Screen? = returnScreen.also { returnScreen = null }

    fun consumeComposeNote(): Boolean = composeNoteRequested.also { composeNoteRequested = false }

    fun consumeComposeTask(): Boolean = composeTaskRequested.also { composeTaskRequested = false }
}
