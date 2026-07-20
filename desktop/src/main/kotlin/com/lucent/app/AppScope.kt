package com.lucent.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * A process-lifetime coroutine scope for writes that must not be cancelled when the composable
 * that triggered them leaves composition.
 *
 * The screens use `rememberCoroutineScope()` for most work, which is exactly right for UI-scoped
 * jobs. But a few writes are triggered *as the user navigates away* — most importantly the "Save"
 * button in the unsaved-changes dialog, which saves and then switches screens in the same click.
 * Switching screens disposes the old screen and cancels its remembered scope, which could cancel
 * the save before it commits and silently lose the note/task/settings. Running those specific
 * writes here keeps them alive until they finish, independent of the UI.
 */
object AppScope {
    val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
