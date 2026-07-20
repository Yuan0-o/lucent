package com.lucent.app

import android.content.Context

/**
 * Desktop twin of the Android GenerationService.
 *
 * On Android this is a foreground service whose sole job is keeping the process alive (with a
 * status notification) while a local-model reply streams in the background. A desktop process
 * does not get killed for being backgrounded, so the correct desktop behaviour is: nothing.
 * The same start/stop API exists so AssistantController compiles verbatim; both calls are no-ops.
 */
object GenerationService {
    fun start(context: Context, assistantName: String) { /* no-op on desktop */ }
    fun stop(context: Context) { /* no-op on desktop */ }
}
