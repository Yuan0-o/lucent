// Desktop compatibility shim — the Compose composition local that isn't there on desktop.
//
// A number of Lucent's screens are copied verbatim from the Android app and read
// `androidx.compose.ui.platform.LocalContext.current` to get an `android.content.Context`. That
// composition local is Android-only; Compose Desktop doesn't declare it. Declaring it here, in the
// same package and with the same name, lets those `import androidx.compose.ui.platform.LocalContext`
// lines compile unchanged, with `.current` yielding the desktop [DesktopContext] singleton — the
// same object the rest of the desktop module already threads through as its Context.
//
// A static composition local with a default means the copied screens don't need to be wrapped in a
// provider to read it; the desktop shell can still override it if it ever needs to.
package androidx.compose.ui.platform

import android.content.Context
import android.content.DesktopContext
import androidx.compose.runtime.staticCompositionLocalOf

val LocalContext = staticCompositionLocalOf<Context> { DesktopContext }
