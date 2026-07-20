package com.lucent.app.nativebridge

import android.content.DesktopContext
import java.io.File

/**
 * Loads the app's optional native libraries on desktop.
 *
 * On Android the .so files ride inside the APK and `System.loadLibrary` finds them. On desktop the
 * DLLs are packaged as classpath resources (`/native/<mapped name>`, put there by the Windows CI
 * workflow), so loading is: try the library path first (a developer running with -Djava.library.path
 * set), then extract the resource beside the app data and `System.load` the absolute path.
 *
 * Both native libraries are pure accelerators/engines with graceful degradation on the Kotlin
 * side, so a missing or unloadable DLL must — and does — resolve to `false`, never to a crash.
 */
object NativeLoader {

    fun load(baseName: String): Boolean {
        // 1) The conventional path, for developers who put the DLL on java.library.path.
        try {
            System.loadLibrary(baseName)
            return true
        } catch (_: Throwable) {
        }
        // 2) The packaged path: extract /native/<mapped> from resources and load it absolutely.
        return try {
            val mapped = System.mapLibraryName(baseName) // lucent_llama -> lucent_llama.dll on Windows
            val resource = NativeLoader::class.java.getResourceAsStream("/native/$mapped") ?: return false
            val dir = File(DesktopContext.filesDir, "native").apply { mkdirs() }
            val target = File(dir, mapped)
            resource.use { input ->
                val bytes = input.readBytes()
                // Re-extract only when the packaged copy differs, so a locked in-use DLL on Windows
                // (from a still-closing previous instance) doesn't fail the load of an identical one.
                if (!target.exists() || target.length() != bytes.size.toLong()) {
                    val tmp = File(dir, "$mapped.tmp")
                    tmp.writeBytes(bytes)
                    if (!tmp.renameTo(target)) {
                        target.delete()
                        if (!tmp.renameTo(target)) return false
                    }
                }
            }
            System.load(target.absolutePath)
            true
        } catch (_: Throwable) {
            false
        }
    }
}
