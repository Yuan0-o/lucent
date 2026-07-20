// Desktop compatibility shim for androidx.activity.compose.BackHandler.
//
// Desktop windows have no system back gesture, so the shared screens' BackHandler registrations
// become registrations with the app-level Escape-key dispatcher instead: the desktop shell binds
// the Esc key to the most recently registered enabled handler, which reproduces Android's
// "innermost handler wins" semantics closely enough for these screens (close the open editor,
// then leave the sub-screen). When no handler is registered, Esc does nothing.
package androidx.activity.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState

/** The desktop dispatcher the shell's key handler consults. Last registered enabled entry wins. */
object DesktopBackDispatcher {
    private val handlers = ArrayDeque<Entry>()

    class Entry(var enabled: Boolean, var onBack: () -> Unit)

    @Synchronized internal fun register(entry: Entry) { handlers.addLast(entry) }
    @Synchronized internal fun unregister(entry: Entry) { handlers.remove(entry) }

    /** Invoke the innermost enabled handler. Returns true when one consumed the event. */
    @Synchronized fun dispatch(): Boolean {
        val target = handlers.lastOrNull { it.enabled } ?: return false
        target.onBack()
        return true
    }
}

@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val currentOnBack by rememberUpdatedState(onBack)
    val entry = androidx.compose.runtime.remember { DesktopBackDispatcher.Entry(enabled) { currentOnBack() } }
    entry.enabled = enabled
    DisposableEffect(Unit) {
        DesktopBackDispatcher.register(entry)
        onDispose { DesktopBackDispatcher.unregister(entry) }
    }
}
