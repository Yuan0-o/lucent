// Desktop compatibility shim.
//
// A large share of Lucent's logic (the assistant tools, backup engine, attachment stores, trash
// sweeps, ...) takes an `android.content.Context` parameter but only ever uses it as "where do my
// files live". Rather than fork thirty otherwise platform-neutral files, the desktop build supplies
// this minimal class under the same package/name, so those files compile *verbatim* — every line of
// business logic on Windows is byte-identical to the audited Android code.
//
// This is a shim, not Android: only the members the shared code actually touches exist here.
package android.content

import java.io.File

open class Context {

    /** The shared code chains `context.applicationContext...`; on desktop there is one context. */
    open val applicationContext: Context get() = this

    /**
     * Private per-user application data directory — the desktop equivalent of Android's
     * internal files dir. Windows: %APPDATA%\Lucent. Other OSes get sensible fallbacks so the
     * module also runs on a developer's macOS/Linux machine.
     */
    open val filesDir: File by lazy {
        val os = System.getProperty("os.name").lowercase()
        val home = System.getProperty("user.home")
        val base: File = when {
            os.contains("win") -> {
                val appData = System.getenv("APPDATA")
                if (!appData.isNullOrBlank()) File(appData) else File(home, "AppData/Roaming")
            }
            os.contains("mac") -> File(home, "Library/Application Support")
            else -> File(home, ".local/share")
        }
        File(base, "Lucent").apply { mkdirs() }
    }

    /** Scratch space that is safe to clear; kept under the data dir so everything stays in one place. */
    open val cacheDir: File by lazy { File(filesDir, "cache").apply { mkdirs() } }

    open val packageName: String get() = "com.lucent.desktop"
}

/** The single application-wide context instance used by the desktop entry point. */
object DesktopContext : Context()
