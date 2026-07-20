// Desktop shim for the sliver of android.text.format.DateFormat the screens use.
//
// The screens call `DateFormat.is24HourFormat(context)` to decide whether a time picker shows a
// 24-hour or AM/PM clock. Android answers from the user's system setting; on the desktop there is no
// such per-user toggle, so we derive it from the JVM's locale — the short time pattern uses an 'a'
// (am/pm) marker for 12-hour locales and omits it for 24-hour ones. Keeping the class/name/package
// identical lets the screen call sites compile verbatim.

package android.text.format

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Locale

object DateFormat {

    /** True when this machine's locale formats times on a 24-hour clock. [context] is unused on desktop. */
    @Suppress("UNUSED_PARAMETER")
    fun is24HourFormat(context: Context): Boolean = try {
        val df = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT, Locale.getDefault())
        val pattern = (df as? SimpleDateFormat)?.toPattern().orEmpty()
        // 'a' is the am/pm marker; its presence means a 12-hour clock.
        !pattern.contains('a', ignoreCase = true)
    } catch (t: Throwable) {
        true
    }
}
