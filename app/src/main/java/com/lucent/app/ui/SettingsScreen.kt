package com.lucent.app.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lucent.app.AppScope
import com.lucent.app.data.AppDatabase
import com.lucent.app.data.AppLock
import com.lucent.app.data.AttachmentLimits
import com.lucent.app.data.BackupManager
import com.lucent.app.data.MemoryTier
import com.lucent.app.data.SettingsRepository
import com.lucent.app.data.ShareIntegration
import com.lucent.app.data.StartupLog
import com.lucent.app.i18n.AppLanguage
import com.lucent.app.i18n.S
import com.lucent.app.local.LocalLlm
import com.lucent.app.local.LocalModelStore
import com.lucent.app.network.ApiSpec
import com.lucent.app.network.LlmClient
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Memory and Web are recombined into a single "Memory & web" page (task 10 of the earlier round),
// reached via the Memory route; there is no separate Web route.
//
// Security and Privacy used to be one page. They are now two (task 10): *Security* is about keeping
// other people out of your data — today that is the app lock — while *Privacy* is about what leaves
// this device, or is recorded on it, at all: the share-sheet surface and local diagnostic logging.
// Those are different questions asked by different worries, and a single page called "Security and
// Privacy" answered neither of them clearly. Splitting them also gives each page room to grow
// without becoming the drawer where every remaining switch is kept.
//
// Two routes arrived with the localization / local-model round:
//   - Language: the in-app UI language picker (system / en / zh / ja / ko). It sits directly after
//     Appearance at the root, because "what language is this in" is the same kind of question as
//     "what does this look like".
//   - LocalModel: the on-device GGUF assistant — import, enable, inspect, delete. It lives under
//     Assistant beside API and Memory, because it IS an assistant backend: the fourth answer to
//     "where do replies come from".
private enum class SettingsRoute { Root, Language, Assistant, Personalization, Memory, Network, Api, LocalModel, Appearance, Theme, Background, Font, Editor, Security, Privacy, Data }

/** Which kind of item the selective Markdown-export picker is currently choosing. */
private enum class ExportKind { NOTES, TASKS }

/** Sentinel distinguishing "wrong password, try again" from "this file is damaged". */
private const val WRONG_PASSWORD = "__wrong_password__"

@Composable
fun SettingsScreen(active: Boolean = true) {
    val context = LocalContext.current
    val repo = remember { SettingsRepository(context) }
    val db = remember { AppDatabase.getInstance(context) }
    val scope = rememberCoroutineScope()
    val onGradient = LocalOnGradient.current
    val onGradientMuted = LocalOnGradientMuted.current

    val savedUrl by repo.baseUrl.collectAsState(initial = "")
    val savedSpec by repo.apiSpec.collectAsState(initial = "openai")
    val savedKey by repo.apiKey.collectAsState(initial = "")
    val savedModel by repo.model.collectAsState(initial = "")
    val savedTheme by repo.themeMode.collectAsState(initial = "system")
    val savedPalette by repo.palette.collectAsState(initial = "SUNSET")
    val savedFont by repo.font.collectAsState(initial = "system")
    val savedAssistantName by repo.assistantName.collectAsState(initial = "Lucent")
    val savedAssistantStyle by repo.assistantStyle.collectAsState(initial = "")
    val savedMemoryTier by repo.memoryTier.collectAsState(initial = MemoryTier.DEFAULT.key)
    val savedWebSearch by repo.webSearchEnabled.collectAsState(initial = false)
    val savedTypingHaptics by repo.typingHapticsEnabled.collectAsState(initial = true)
    // Editor: whether note bodies are treated as Markdown. Off by default. See SettingsRepository.
    val markdownEnabled by repo.markdownEnabled.collectAsState(initial = false)
    // Editor: whether links are active (task 8). A sub-toggle of Markdown — only live when both are
    // on. Defaults to OFF (opt-in).
    val linksEnabled by repo.linksEnabled.collectAsState(initial = false)
    // Whether the drifting background animates (background on/off task). Default on.
    val backgroundAnimationEnabled by repo.backgroundAnimationEnabled.collectAsState(initial = true)
    // The in-app UI language (localization task). "system" resolves against the device locale.
    val savedLanguage by repo.appLanguage.collectAsState(initial = "system")
    // Whether the assistant answers with the imported on-device model (local-model task).
    val localModelEnabled by repo.localModelEnabled.collectAsState(initial = false)
    val localToolsEnabled by repo.localToolsEnabled.collectAsState(initial = false)
    val localGpuEnabled by repo.localGpuEnabled.collectAsState(initial = false)

    // Working copies of the *active* profile's connection fields, used by the API editor page.
    // These mirror the flat saved values; the API page saves through saveApiProfiles (which also
    // re-mirrors them), so they're always in sync with the selected profile.
    var url by remember(savedUrl) { mutableStateOf(savedUrl) }
    var spec by remember(savedSpec) { mutableStateOf(savedSpec) }
    var key by remember(savedKey) { mutableStateOf(savedKey) }
    var selectedModel by remember(savedModel) { mutableStateOf(savedModel) }
    var assistantName by remember(savedAssistantName) { mutableStateOf(savedAssistantName) }
    var assistantStyle by remember(savedAssistantStyle) { mutableStateOf(savedAssistantStyle) }

    // --- Multi-API profiles ---
    val savedProfilesJson by repo.apiProfilesJson.collectAsState(initial = "")
    val savedSelectedIdx by repo.apiProfileSelected.collectAsState(initial = 0)
    // Parsed profile list. If nothing's been saved yet we seed a single profile from the existing
    // flat connection values, so users upgrading from the single-API version keep their config.
    val profiles = remember(savedProfilesJson, savedUrl, savedSpec, savedKey, savedModel) {
        val parsed = com.lucent.app.data.ApiProfiles.parse(savedProfilesJson)
        if (parsed.isNotEmpty()) parsed
        else listOf(
            com.lucent.app.data.ApiProfile(
                name = "API 1", spec = savedSpec, baseUrl = savedUrl, apiKey = savedKey, model = savedModel
            )
        )
    }
    val selectedProfileIdx = savedSelectedIdx.coerceIn(0, (profiles.size - 1).coerceAtLeast(0))

    var models by remember { mutableStateOf(listOf<String>()) }
    var loading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf("") }
    var menuExpanded by remember { mutableStateOf(false) }
    var backupStatus by remember { mutableStateOf("") }

    // --- Privacy toggles: App Lock (task 2), System integration (task 6), Startup logging (task 15) ---
    val appLockOn by repo.appLockEnabled.collectAsState(initial = false)
    val systemIntegrationOn by repo.systemIntegrationEnabled.collectAsState(initial = false)
    val startupLoggingOn by repo.startupLoggingEnabled.collectAsState(initial = false)

    // App Lock setup dialog (only shown while turning the lock ON, to capture the credentials).
    var showAppLockSetup by remember { mutableStateOf(false) }
    var lockPw by remember { mutableStateOf("") }
    var lockPwConfirm by remember { mutableStateOf("") }
    var lockQuestion by remember { mutableStateOf("") }
    var lockAnswer by remember { mutableStateOf("") }
    var lockSetupError by remember { mutableStateOf("") }

    // Shown when the user tries to turn the lock on with no security question (task 9).
    var showNoRecoveryWarning by remember { mutableStateOf(false) }

    // Turning the lock OFF now also requires the password (task): a dialog confirms the user knows
    // it before the protection is removed, and spells out the security risk of removing it. The
    // credentials blob is collected here so the dialog can verify what's typed against it.
    val appLockCreds by repo.appLockCredentials.collectAsState(initial = "")
    var showAppLockDisable by remember { mutableStateOf(false) }
    var disablePw by remember { mutableStateOf("") }
    var disableError by remember { mutableStateOf("") }

    // System-integration privacy warning (shown before enabling the share/intent surface).
    var showShareWarning by remember { mutableStateOf(false) }

    // Writes the local diagnostic log to a user-chosen text file (task 15). Reading is off the main
    // thread; the log lives in internal storage and is only ever copied out by this explicit action.
    val logsExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    try {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(StartupLog.readAll(context).toByteArray())
                        } != null
                    } catch (e: Exception) {
                        false
                    }
                }
                backupStatus = if (ok) S.logsExported else S.logsExportFailed
            }
        }
    }

    // --- Backup encryption ---
    var showExportDialog by remember { mutableStateOf(false) }
    // Starts BLANK every time (task 5). It used to be pre-seeded from the last-used password, which —
    // combined with the old password-first default — meant a user who set a password once kept
    // silently exporting password-locked files, and those files then failed to restore on any other
    // device that didn't have the password. Blank means the default export uses the portable
    // built-in key; a password is only applied if the user deliberately types one this time.
    var exportPasswordDraft by remember { mutableStateOf("") }
    var exportPasswordVisible by remember { mutableStateOf(false) }

    // A backup that needs a password we don't have. The bytes are held here rather than re-read on
    // submit, because the picker's Uri may well not still be readable by the time the user finishes
    // typing — and losing a backup to an expired permission grant would be an absurd way to lose one.
    var pendingImportBytes by remember { mutableStateOf<ByteArray?>(null) }
    var importPasswordDraft by remember { mutableStateOf("") }
    var importPasswordError by remember { mutableStateOf(false) }
    // What's in the file, worked out without writing anything. Non-null means the confirm step is up.
    var importPreview by remember { mutableStateOf<BackupManager.BackupPreview?>(null) }
    // Set when a database could not be decrypted on this launch. Nothing was deleted — see
    // DatabaseEncryption.setAside — but the user has to be told, and told what to do about it.
    val lockedNotice = remember { com.lucent.app.data.DatabaseEncryption.lockedNotice(context) }
    var lockedDismissed by remember { mutableStateOf(false) }
    // The notice is *persisted at fault time* (see DatabaseEncryption.setAside), so an old marker
    // may be in English while the UI is not. The set-aside file name is the only variable part and
    // is always written inside double quotes, so it is extracted here and re-rendered through the
    // catalog; if extraction ever fails (foreign/edited marker), the stored text is shown verbatim
    // rather than nothing — a recovery notice must never be lost to a formatting quibble.
    val lockedNoticeFileName = remember(lockedNotice) {
        lockedNotice?.let { Regex("\"([^\"]+)\"").find(it)?.groupValues?.getOrNull(1) }
    }
    var showClearData by remember { mutableStateOf(false) }
    var showClearNotes by remember { mutableStateOf(false) }
    var showClearTasks by remember { mutableStateOf(false) }
    var showClearChats by remember { mutableStateOf(false) }
    // The API profile the user has asked to delete, held until they confirm (task 1). Deleting an
    // API key is destructive — it can't be undone and the key may be the only copy — so it now goes
    // through an explicit confirmation instead of firing on the first tap of the trash icon.
    var profilePendingDelete by remember { mutableStateOf<Int?>(null) }
    // Name of the profile being edited on the API page (editable so users can rename).
    var editingProfileName by remember(savedProfilesJson, selectedProfileIdx) {
        mutableStateOf(profiles.getOrNull(selectedProfileIdx)?.name ?: "API 1")
    }

    // --- Local model (GGUF) page state (local-model task) ---
    //
    // lmRefresh is a change counter: bumping it makes the remember()s below re-read the store, so
    // the page reflects an import/delete/rename/switch immediately without any second source of truth.
    var lmRefresh by remember { mutableStateOf(0) }
    val lmIndex = remember(lmRefresh) { LocalModelStore.index(context) }
    val lmModels = lmIndex.slots
    val lmActiveId = lmIndex.activeId
    val lmHasModel = lmModels.isNotEmpty() && lmActiveId != null
    val lmCanImportMore = lmModels.size < LocalModelStore.MAX_MODELS
    var lmImporting by remember { mutableStateOf(false) }
    var lmError by remember { mutableStateOf("") }
    // The slot the user has asked to delete, held until they confirm. Deleting always asks first.
    var lmSlotPendingDelete by remember { mutableStateOf<LocalModelStore.ModelSlot?>(null) }
    // The slot being renamed and the working text (null = the rename dialog is closed).
    var lmRenameTarget by remember { mutableStateOf<LocalModelStore.ModelSlot?>(null) }
    var lmRenameText by remember { mutableStateOf("") }
    // A just-picked model file awaiting a name before it is imported (null = no naming dialog up).
    // Holding the Uri lets the user label the model at import time (custom names, task requirement).
    var lmPendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var lmImportName by remember { mutableStateOf("") }
    // Warning dialogs for the opt-in local-model switches (use-local, tools, GPU). Turning any ON
    // asks first; turning OFF is free (back to the safe default), so only the "on" path is gated.
    var lmConfirmToolsOn by remember { mutableStateOf(false) }
    var lmConfirmGpuOn by remember { mutableStateOf(false) }
    // Turning "use local model" ON freezes the cloud API and pulls a multi-gigabyte model into RAM,
    // so it warns first (API frozen, memory cost, don't quit mid-reply, quitting frees the memory).
    var lmConfirmUseLocalOn by remember { mutableStateOf(false) }

    // The GGUF picker. OpenDocument with * / * because .gguf has no registered MIME type and a
    // model downloaded as a .zip must be pickable too; LocalModelStore validates the actual bytes
    // (GGUF magic, or a zip containing a .gguf) and rejects everything else with a clear message.
    // Picking doesn't import straight away: it stages the Uri and opens a naming dialog first, so the
    // user can label the model (custom names, task requirement). The import runs on confirm.
    val lmImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null && !lmImporting) {
            lmError = ""
            // Default the name to the picked file's name (minus extension); the user can edit it.
            val picked = try {
                context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            } catch (_: Throwable) { null }
            lmImportName = (picked ?: "").substringBeforeLast('.').take(60)
            lmPendingImportUri = uri
        }
    }

    // Run the staged import under the chosen name. Frees the resident model first so the peak
    // footprint stays at one model, then adds the new slot (which becomes active) and refreshes.
    fun startLocalImport(uri: Uri, name: String) {
        if (lmImporting) return
        lmImporting = true
        lmError = ""
        scope.launch {
            val error = withContext(Dispatchers.IO) {
                try {
                    LocalLlm.shutdown()
                    LocalModelStore.import(context, uri, name)
                    null
                } catch (e: LocalModelStore.TooManyModelsException) {
                    S.lmImportFailedTooMany(LocalModelStore.MAX_MODELS)
                } catch (e: LocalModelStore.NotGgufException) {
                    S.lmImportFailedNotGguf
                } catch (e: LocalModelStore.NoGgufInZipException) {
                    S.lmImportFailedNoGgufInZip
                } catch (e: Exception) {
                    S.lmImportFailedGeneric(e.message ?: "")
                }
            }
            lmImporting = false
            if (error == null) {
                lmRefresh++
                LucentToast.show(context.applicationContext, S.lmImportedToast)
            } else {
                lmError = error
            }
        }
    }

    // Switch the active model. Only one model is ever resident, so the currently loaded one is
    // released immediately; the next send loads the newly selected slot. No-op if already active.
    fun selectLocalModel(id: String) {
        if (id == lmActiveId) return
        scope.launch {
            withContext(Dispatchers.IO) {
                LocalLlm.shutdown()          // free the outgoing model's memory now
                LocalModelStore.setActive(context, id)
            }
            lmRefresh++
        }
    }

    // Delete a model slot. If it is the resident model, the engine is shut down first so a
    // multi-gigabyte model is never left in memory with nothing on disk to reload.
    fun deleteLocalModel(slot: LocalModelStore.ModelSlot) {
        scope.launch {
            val wasActive = slot.id == lmActiveId
            withContext(Dispatchers.IO) {
                if (wasActive) LocalLlm.shutdown()
                LocalModelStore.delete(context, slot.id)
                // If that was the last model, the "use local model" switch would point at nothing,
                // so turn it off — the assistant reverts to the cloud API cleanly.
                if (LocalModelStore.slots(context).isEmpty()) repo.setLocalModelEnabled(false)
            }
            lmRefresh++
            LucentToast.show(context.applicationContext, S.lmDeletedToast)
        }
    }

    var route by rememberSaveable { mutableStateOf(SettingsRoute.Root) }

    // --- Unsaved-changes guard for the Personalization sub-screen ---
    // Only personalization (name + chat style) can go "dirty"; the API/Appearance/Data pages save
    // each action immediately (an explicit button press), so they never need a guard.
    val assistantDirty = route == SettingsRoute.Personalization && (
        assistantName.ifBlank { "Lucent" } != savedAssistantName ||
            assistantStyle != savedAssistantStyle
        )
    var showUnsavedDialog by remember { mutableStateOf(false) }

    val appContext = context.applicationContext
    fun persistAssistantSettings() {
        // App-lifetime scope: the unsaved-changes dialog saves and then leaves the screen in the
        // same action, which would otherwise cancel this write before it commits.
        AppScope.io.launch {
            repo.setAssistantName(assistantName.ifBlank { "Lucent" })
            repo.setAssistantStyle(assistantStyle)
            withContext(Dispatchers.Main) {
                LucentToast.show(appContext, S.savedToast)
            }
        }
    }

    fun discardAssistantSettings() {
        assistantName = savedAssistantName
        assistantStyle = savedAssistantStyle
    }

    // Saves the currently-edited connection fields into profile [idx] (replacing it) and makes it
    // active. Used by the API editor's Save button. Runs on the app-lifetime scope for the same
    // reason as above.
    //
    // The persisted fallback name deliberately stays the English "API N" pattern:
    // ApiProfiles.nextDefaultName generates the same pattern at the data layer, and a stored name
    // should not depend on which language happened to be active the moment Save was pressed.
    // Display-side fallbacks (list rows, the delete dialog) ARE localized.
    fun saveActiveProfile(idx: Int, activate: Boolean = true) {
        val updated = profiles.toMutableList()
        val edited = com.lucent.app.data.ApiProfile(
            name = editingProfileName.trim().ifBlank { "API ${idx + 1}" },
            spec = spec,
            baseUrl = url.trim(),
            apiKey = key.trim(),
            model = selectedModel
        )
        if (idx in updated.indices) updated[idx] = edited else updated.add(edited)
        val newSelected = if (activate) idx.coerceIn(0, updated.size - 1) else selectedProfileIdx
        AppScope.io.launch {
            repo.saveApiProfiles(updated, newSelected)
            withContext(Dispatchers.Main) { LucentToast.show(appContext, S.apiSavedToast) }
        }
    }

    // Switch the active profile to [idx] and load its fields into the editor. Saves immediately.
    fun selectProfile(idx: Int) {
        val p = profiles.getOrNull(idx) ?: return
        url = p.baseUrl; spec = p.spec; key = p.apiKey; selectedModel = p.model
        editingProfileName = p.name
        models = emptyList()
        AppScope.io.launch { repo.saveApiProfiles(profiles, idx) }
    }

    // Add a new empty profile (up to MAX) and start editing it. Its default name is the smallest
    // free "API N" number, so deleting a lower-numbered profile lets that number be reused instead
    // of always climbing (e.g. after deleting "API 1", the next add is "API 1" again, not "API 3").
    fun addProfile() {
        if (profiles.size >= com.lucent.app.data.ApiProfiles.MAX) return
        val defaultName = com.lucent.app.data.ApiProfiles.nextDefaultName(profiles)
        val newList = profiles + com.lucent.app.data.ApiProfile(name = defaultName)
        val newIdx = newList.size - 1
        url = ""; spec = "openai"; key = ""; selectedModel = ""; editingProfileName = defaultName
        models = emptyList()
        AppScope.io.launch { repo.saveApiProfiles(newList, newIdx) }
    }

    // Delete profile [idx]. Deleting the FINAL profile is allowed too (task requirement): it clears
    // that slot back to a single blank "API 1" rather than leaving the app with no API at all, so the
    // editor stays usable and no stale connection details linger. Any other delete just drops the row.
    fun deleteProfile(idx: Int) {
        if (idx !in profiles.indices) return
        val newList = profiles.toMutableList().also { it.removeAt(idx) }
        if (newList.isEmpty()) {
            val blank = com.lucent.app.data.ApiProfile(name = "API 1")
            url = ""; spec = "openai"; key = ""; selectedModel = ""; editingProfileName = "API 1"
            models = emptyList()
            AppScope.io.launch { repo.saveApiProfiles(listOf(blank), 0) }
        } else {
            val newSelected = selectedProfileIdx.coerceIn(0, newList.size - 1)
            AppScope.io.launch { repo.saveApiProfiles(newList, newSelected) }
        }
    }

    // Leaving the Personalization sub-screen (back arrow or system back) while dirty asks first
    // instead of silently discarding. Every other page saves each action immediately, so this
    // guard only ever engages on the Personalization route.
    fun leavePersonalization() {
        if (assistantDirty) showUnsavedDialog = true else route = SettingsRoute.Assistant
    }

    // Where "back" goes from the current sub-route, reflecting the nesting:
    //   Assistant > { Personalization, API, Memory, Network, Local model }
    //   Appearance > { Theme, Background }
    //   Language  > { Font }
    fun goBack() {
        when (route) {
            SettingsRoute.Personalization -> leavePersonalization()
            SettingsRoute.Memory -> route = SettingsRoute.Assistant
            SettingsRoute.Network -> route = SettingsRoute.Assistant
            SettingsRoute.Api -> route = SettingsRoute.Assistant
            SettingsRoute.LocalModel -> route = SettingsRoute.Assistant
            SettingsRoute.Theme, SettingsRoute.Background -> route = SettingsRoute.Appearance
            SettingsRoute.Font -> route = SettingsRoute.Language
            SettingsRoute.Language, SettingsRoute.Assistant, SettingsRoute.Appearance, SettingsRoute.Editor,
            SettingsRoute.Security, SettingsRoute.Privacy, SettingsRoute.Data -> route = SettingsRoute.Root
            else -> route = SettingsRoute.Root
        }
    }

    // Leaving Settings folds it back to the root list (task 3). Settings is the clearest case of
    // the problem: its sub-pages are deep (Assistant > API, Appearance > Background) and highly
    // specific, so returning to the tab and landing on the API editor — with no memory of having
    // been there — reads as the app having lost its place rather than helpfully kept it. The
    // Personalization guard has already resolved by the time this runs, so no edit can be lost.
    LaunchedEffect(active) {
        if (!active) route = SettingsRoute.Root
    }

    // Registers this screen's dirty state with the app-lifetime guard so switching bottom-nav
    // tabs, or the system back button closing the app, also asks before losing changes here.
    SideEffect {
        if (assistantDirty) {
            UnsavedChangesGuard.register("settings", ::persistAssistantSettings, ::discardAssistantSettings)
        } else {
            UnsavedChangesGuard.clear("settings")
        }
    }
    DisposableEffect(Unit) { onDispose { UnsavedChangesGuard.clear("settings") } }

    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedDialog = false },
            title = { Text(S.unsavedChangesTitle) },
            text = { Text(S.settingsUnsavedBody) },
            confirmButton = {
                TextButton(onClick = {
                    persistAssistantSettings()
                    showUnsavedDialog = false
                    route = SettingsRoute.Assistant
                }) { Text(S.actionSave) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        discardAssistantSettings()
                        showUnsavedDialog = false
                        route = SettingsRoute.Assistant
                    }) { Text(S.actionDiscard) }
                    TextButton(onClick = { showUnsavedDialog = false }) { Text(S.actionCancel) }
                }
            }
        )
    }

    // Inside a sub-menu, the system back button / edge-swipe returns to the Settings root
    // instead of leaving the screen. When already at the root this is disabled, so back falls
    // through to the app-level handler (which returns to the Notes home).
    BackHandler(enabled = route != SettingsRoute.Root) { goBack() }

    // --- API key visibility (task 14) ---
    // Hidden on entry. The eye button reveals for 3s. While the user is actively typing the
    // field is shown, then re-masks 1s after the last keystroke. keystrokeSeq starts at 0 and
    // only advances on real edits, so loading the saved key never triggers a reveal.
    var manualReveal by remember { mutableStateOf(false) }
    var typingReveal by remember { mutableStateOf(false) }
    var keystrokeSeq by remember { mutableStateOf(0) }
    LaunchedEffect(manualReveal) {
        if (manualReveal) {
            delay(3000)
            manualReveal = false
        }
    }
    LaunchedEffect(keystrokeSeq) {
        if (keystrokeSeq > 0) {
            delay(1000)
            typingReveal = false
        }
    }
    val keyVisible = manualReveal || typingReveal

    // =======================================================================================
    // Backup: export
    // =======================================================================================
    //
    // Exporting asks a question before it writes anything, because the answer genuinely matters and
    // the user is the only one who can give it. A backup is the one artefact that deliberately leaves
    // the device — into a cloud drive, an email to yourself, a Downloads folder shared with every app
    // that ever asked for storage access — and how it is locked is a decision with a real trade-off
    // on both sides. Picking silently on the user's behalf would be picking wrong for half of them.

    // The password this particular export will use. Null means "use the built-in key". Held across
    // the launcher round-trip, since the file picker's callback arrives long after the dialog closes.
    var exportPassword by remember { mutableStateOf<String?>(null) }

    // In-flight guard against the "multiple duplicate downloads" bug (task 11).
    //
    // The dialog's confirm/dismiss buttons both call beginExport, which dismisses the dialog and
    // launches the SAF create-document picker. If the button is tapped twice within the same frame —
    // easy to do, and the recomposition that removes the dialog hasn't happened yet — beginExport
    // fires twice and TWO pickers open, each writing its own file. That is exactly how three
    // identically-named lucent-backup.lcb files end up in Downloads from what the user experienced as
    // one action. This flag makes launching idempotent: the second call is ignored until the current
    // export finishes (or its picker is cancelled), so at most one file is ever written per request.
    var exportInFlight by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        if (uri != null) {
            val password = exportPassword
            scope.launch {
                // The full payload — notes (archived included), tasks, note version history, chats,
                // conversations, every attachment, and all settings — sealed as one file. All of it,
                // not just the API key. Written on IO so a large export can't stall the UI; use()
                // closes the cipher stream even if the write throws, which matters because closing is
                // what seals the final frame.
                val result = withContext(Dispatchers.IO) {
                    try {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            BackupManager.exportEncrypted(context, db, repo, out, password)
                        } ?: return@withContext S.backupWriteFailed
                        if (password.isNullOrEmpty()) {
                            S.backupSavedBuiltIn
                        } else {
                            S.backupSavedPassword
                        }
                    } catch (e: Exception) {
                        S.exportFailed(e.message ?: "")
                    }
                }
                backupStatus = result
                // The write is done (or failed) — clear the guard so the user can export again.
                exportInFlight = false
            }
        } else {
            // Picker cancelled: nothing was written, so release the guard immediately.
            exportInFlight = false
        }
    }

    fun beginExport(password: String?) {
        // Ignore a second launch while one is already pending — see exportInFlight above.
        if (exportInFlight) return
        exportInFlight = true
        exportPassword = password
        showExportDialog = false
        exportLauncher.launch("lucent-backup.lcb")
    }

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text(S.exportBackupTitle) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(S.exportBackupBody, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(14.dp))

                    Text(S.addPasswordOptional, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(S.exportPasswordExplain, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = exportPasswordDraft,
                        onValueChange = { exportPasswordDraft = it },
                        label = { Text(S.fieldPasswordOptional) },
                        singleLine = true,
                        visualTransformation = if (exportPasswordVisible) VisualTransformation.None
                            else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { exportPasswordVisible = !exportPasswordVisible }) {
                                Icon(
                                    if (exportPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (exportPasswordVisible) S.hidePassword else S.showPassword
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            // One primary action. A blank password → the portable built-in key (the default that
            // fixes cross-device restore, task 5); a typed password → real encryption. Saving the
            // typed password is only a same-device convenience for a quick re-import; a different
            // device still (correctly) asks for it, so the default file stays portable regardless.
            confirmButton = {
                Button(
                    onClick = {
                        val password = exportPasswordDraft.ifBlank { null }
                        if (password != null) {
                            AppScope.io.launch { repo.setBackupPassword(password) }
                        }
                        beginExport(password)
                    }
                ) { Text(S.actionExport) }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) { Text(S.actionCancel) }
            }
        )
    }

    // =======================================================================================
    // Backup: import
    // =======================================================================================
    //
    // Three steps, and the middle one only when it's needed:
    //
    //   1. Pick a file. Lucent reads its header and works out whether it wants a password.
    //   2. If it does, ask for it. (The header is plaintext precisely so this question can be asked
    //      *before* trying — otherwise the only way to find out would be to demand a password and see
    //      if it worked, which is a miserable thing to do to someone restoring a backup.)
    //   3. Show what is actually inside, and let them cancel.
    //
    // Step 3 is the one that was missing. Restoring merges a stranger's file into a live database,
    // and the old flow did it the instant the file was picked — no idea what was in it, no way back.

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val bytes = withContext(Dispatchers.IO) {
                    try {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    } catch (e: Exception) {
                        null
                    }
                }
                if (bytes == null) {
                    backupStatus = S.couldNotReadThatFile
                    return@launch
                }

                val header = BackupManager.peekPasswordRequirement(bytes)
                if (header != null && header.needsPassword) {
                    // Try the password saved on this device first. On the phone that made the backup,
                    // that means restoring is still a single tap.
                    val stored = repo.backupPassword.first()
                    val preview = if (stored.isEmpty()) null else withContext(Dispatchers.IO) {
                        try {
                            BackupManager.inspect(context, bytes, stored)
                        } catch (e: Exception) {
                            null
                        }
                    }
                    if (preview != null) {
                        importPreview = preview
                    } else {
                        importPasswordDraft = ""
                        importPasswordError = false
                        pendingImportBytes = bytes
                    }
                } else {
                    val result = withContext(Dispatchers.IO) {
                        try {
                            Result.success(BackupManager.inspect(context, bytes, null))
                        } catch (e: Exception) {
                            Result.failure(e)
                        }
                    }
                    result.fold(
                        onSuccess = { importPreview = it },
                        onFailure = { backupStatus = S.importFailed(it.message ?: "") }
                    )
                }
            }
        }
    }

    // --- Step 2: the password prompt (only for a backup made with a custom password) ---
    pendingImportBytes?.let { bytes ->
        AlertDialog(
            onDismissRequest = { pendingImportBytes = null },
            title = { Text(S.backupPasswordTitle) },
            text = {
                Column {
                    Text(S.backupPasswordBody, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = importPasswordDraft,
                        onValueChange = { importPasswordDraft = it; importPasswordError = false },
                        singleLine = true,
                        isError = importPasswordError,
                        visualTransformation = PasswordVisualTransformation(),
                        label = { Text(if (importPasswordError) S.wrongPassword else S.lockPassword) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = importPasswordDraft.isNotEmpty(),
                    onClick = {
                        val attempt = importPasswordDraft
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                try {
                                    Result.success(BackupManager.inspect(context, bytes, attempt))
                                } catch (e: Exception) {
                                    Result.failure(e)
                                }
                            }
                            result.fold(
                                onSuccess = {
                                    pendingImportBytes = null
                                    importPreview = it
                                },
                                onFailure = { error ->
                                    if (error is com.lucent.app.data.BackupCrypto.WrongPasswordException) {
                                        // Stay on the dialog. There is no recovery for a forgotten
                                        // backup password — that is the whole point of it — so the
                                        // only useful thing left to offer is another try.
                                        importPasswordError = true
                                    } else {
                                        pendingImportBytes = null
                                        backupStatus = S.importFailed(error.message ?: "")
                                    }
                                }
                            )
                        }
                    }
                ) { Text(S.lockContinue) }
            },
            dismissButton = { TextButton(onClick = { pendingImportBytes = null }) { Text(S.actionCancel) } }
        )
    }

    // --- Step 3: show what's in the file, and let them say no ---
    importPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = { importPreview = null },
            title = { Text(S.restoreBackupTitle) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    preview.exportedAt?.let {
                        Text(S.exportedWhen(formatTimestamp(it)), fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(2.dp))
                    }
                    Text(
                        if (preview.passwordProtected) S.protectedByPassword
                        else S.protectedBuiltIn,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    if (preview.isEmpty) {
                        Text(S.backupEmpty, fontSize = 13.sp)
                    } else {
                        Text(S.backupContains, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(6.dp))
                        BackupContentLine(S.bkNotes, preview.notes, buildList {
                            if (preview.archivedNotes > 0) add(S.bkNArchived(preview.archivedNotes))
                            if (preview.trashedNotes > 0) add(S.bkNInTrash(preview.trashedNotes))
                        })
                        BackupContentLine(S.bkTasks, preview.tasks, buildList {
                            if (preview.completedTasks > 0) add(S.bkNCompleted(preview.completedTasks))
                            if (preview.trashedTasks > 0) add(S.bkNInTrash(preview.trashedTasks))
                        })
                        BackupContentLine(S.bkNoteVersions, preview.noteVersions, emptyList())
                        BackupContentLine(S.bkConversations, preview.conversations, emptyList())
                        BackupContentLine(S.bkChatMessages, preview.chatMessages, emptyList())
                        BackupContentLine(S.bkAttachments, preview.attachments, emptyList())
                        if (preview.hasSettings) {
                            BackupContentLine(S.bkSettings, 1, listOf(S.bkIncludingApiKeys))
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(S.restoreMergeNote, fontSize = 13.sp)
                }
            },
            confirmButton = {
                Button(
                    enabled = !preview.isEmpty,
                    onClick = {
                        importPreview = null
                        scope.launch {
                            backupStatus = withContext(Dispatchers.IO) {
                                try {
                                    BackupManager.commit(context, db, repo, preview)
                                } catch (e: Exception) {
                                    S.importFailed(e.message ?: "")
                                }
                            }
                            // A restored backup can bring in tasks that want reminders, and alarms
                            // aren't part of a backup file — they're OS state. BackupManager re-arms
                            // them, but the channel has to exist before one can be posted.
                            com.lucent.app.reminders.Notifications.ensureChannel(context)
                            // If a database had been set aside as undecryptable, restoring is the
                            // cure — retire the notice rather than leaving it frightening someone who
                            // has already fixed the problem.
                            com.lucent.app.data.DatabaseEncryption.clearLockedNotice(context)
                        }
                    }
                ) { Text(S.actionRestore) }
            },
            dismissButton = { TextButton(onClick = { importPreview = null }) { Text(S.actionCancel) } }
        )
    }

    // --- App Lock setup ---
    // Captures a password (twice) and, optionally, a security question and its answer, then turns the
    // lock on. The credentials are hashed by AppLock before anything is stored; the raw
    // password/answer are never persisted. Cancelling leaves the lock off.
    //
    // The question is optional as of task 9 — but skipping it is a genuinely consequential choice, so
    // it is confirmed rather than merely allowed (see showNoRecoveryWarning below). Note that this is
    // not just a UI nicety: AppLock.createCredentials stores an *empty* answer hash in that case
    // rather than the hash of an empty string, because the latter would have been matched by typing a
    // single space into the recovery form.
    fun applyAppLock() {
        val creds = AppLock.createCredentials(lockPw, lockQuestion, lockAnswer)
        scope.launch { repo.setAppLock(true, creds) }
        AppLockController.enabled = true
        // Clear the captured secrets from memory now that they're hashed & stored.
        lockPw = ""; lockPwConfirm = ""; lockQuestion = ""; lockAnswer = ""
        lockSetupError = ""
        showAppLockSetup = false
        LucentToast.show(context, S.appLockOnToast)
    }

    if (showAppLockSetup) {
        AlertDialog(
            onDismissRequest = { showAppLockSetup = false },
            title = { Text(S.appLockSetupTitle) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(S.appLockSetupBody, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = lockPw,
                        onValueChange = { lockPw = it; lockSetupError = "" },
                        label = { Text(S.lockPassword) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = lockPwConfirm,
                        onValueChange = { lockPwConfirm = it; lockSetupError = "" },
                        label = { Text(S.fieldConfirmPassword) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = lockQuestion,
                        onValueChange = { lockQuestion = it; lockSetupError = "" },
                        label = { Text(S.fieldSecurityQuestionOptional) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = lockAnswer,
                        onValueChange = { lockAnswer = it; lockSetupError = "" },
                        label = { Text(S.fieldAnswerOptional) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (lockSetupError.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(lockSetupError, color = Color(0xFFFF8A80), fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    when {
                        lockPw.length < 4 -> lockSetupError = S.lockErrTooShort
                        lockPw != lockPwConfirm -> lockSetupError = S.lockErrMismatch
                        // Half a security question is still an error: a question with no answer can
                        // never be verified, and an answer with no question can never be asked.
                        lockQuestion.isNotBlank() && lockAnswer.isBlank() ->
                            lockSetupError = S.lockErrNeedAnswer
                        lockAnswer.isNotBlank() && lockQuestion.isBlank() ->
                            lockSetupError = S.lockErrNeedQuestion
                        // Both blank: allowed, but only after the user has been told what it costs.
                        lockQuestion.isBlank() && lockAnswer.isBlank() -> showNoRecoveryWarning = true
                        else -> applyAppLock()
                    }
                }) { Text(S.turnOn) }
            },
            dismissButton = {
                TextButton(onClick = {
                    lockPw = ""; lockPwConfirm = ""; lockQuestion = ""; lockAnswer = ""
                    showAppLockSetup = false
                }) { Text(S.actionCancel) }
            }
        )
    }

    // --- Disable-lock confirmation (task) ---
    //
    // Turning the lock OFF is a security downgrade, so it is gated exactly like a login: the current
    // password must be entered correctly before the protection is removed. The body spells out what
    // is being given up (anyone can then read everything without a password). A wrong password shows
    // an inline error and changes nothing; only a correct one disables the lock.
    if (showAppLockDisable) {
        AlertDialog(
            onDismissRequest = { showAppLockDisable = false },
            title = { Text(S.appLockDisableTitle) },
            text = {
                Column {
                    Text(S.appLockDisableBody, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = disablePw,
                        onValueChange = { disablePw = it; disableError = "" },
                        label = { Text(S.lockPassword) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (disableError.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(disableError, color = Color(0xFFFF8A80), fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = disablePw.isNotEmpty() && appLockCreds.isNotEmpty(),
                    onClick = {
                        if (AppLock.verifyPassword(appLockCreds, disablePw)) {
                            scope.launch { repo.setAppLock(false, "") }
                            AppLockController.enabled = false
                            AppLockController.unlock()
                            disablePw = ""; disableError = ""
                            showAppLockDisable = false
                            LucentToast.show(context, S.appLockOffToast)
                        } else {
                            disableError = S.lockWrongPassword
                        }
                    }
                ) { Text(S.turnOff) }
            },
            dismissButton = {
                TextButton(onClick = {
                    disablePw = ""; disableError = ""
                    showAppLockDisable = false
                }) { Text(S.actionCancel) }
            }
        )
    }

    // --- "No security question" warning (task 9) ---
    //
    // Skipping the question is permitted, because a lock on a personal device is often protecting
    // against a curious housemate rather than an adversary, and forcing a recovery question on
    // someone who doesn't want one just adds a second secret to lose. But it is irreversible in the
    // worst way: forget the password and there is no reset, only "clear all data" — which is the
    // whole database, every attachment, gone. That deserves a sentence saying so *before* it
    // happens, not a support question afterwards.
    //
    // The setup dialog stays open underneath, so "Add a question" returns to it with the password
    // the user already typed still there.
    if (showNoRecoveryWarning) {
        AlertDialog(
            onDismissRequest = { showNoRecoveryWarning = false },
            title = { Text(S.noRecoveryTitle) },
            text = { Text(S.noRecoveryBody) },
            confirmButton = {
                Button(onClick = {
                    showNoRecoveryWarning = false
                    applyAppLock()
                }) { Text(S.turnOnAnyway) }
            },
            dismissButton = {
                TextButton(onClick = { showNoRecoveryWarning = false }) { Text(S.addAQuestion) }
            }
        )
    }

    // --- System integration privacy warning (task 6) ---
    if (showShareWarning) {
        AlertDialog(
            onDismissRequest = { showShareWarning = false },
            title = { Text(S.shareWarnTitle) },
            text = { Text(S.shareWarnBody) },
            confirmButton = {
                Button(onClick = {
                    scope.launch { repo.setSystemIntegrationEnabled(true) }
                    ShareIntegration.setEnabled(context, true)
                    showShareWarning = false
                    // Toast rather than the Data page's backupStatus line: this control lives on
                    // Security and Privacy now (task 5).
                    LucentToast.show(context, S.systemIntegrationOnToast)
                }) { Text(S.turnOn) }
            },
            dismissButton = {
                TextButton(onClick = { showShareWarning = false }) { Text(S.actionCancel) }
            }
        )
    }

    if (showClearData) {
        AlertDialog(
            onDismissRequest = { showClearData = false },
            title = { Text(S.clearAllDataTitle) },
            text = { Text(S.clearAllDataBody) },
            confirmButton = {
                TextButton(onClick = {
                    showClearData = false
                    AppScope.io.launch {
                        // Cancel every scheduled reminder *before* the rows they point at disappear.
                        // An alarm outlives the task it belongs to: skip this and a notification for
                        // a task that no longer exists anywhere in the app can still fire hours
                        // later, which is both baffling and impossible to make stop.
                        db.taskDao().getAllOnce().forEach {
                            com.lucent.app.reminders.ReminderScheduler.cancel(appContext, it.id)
                        }
                        db.noteVersionDao().clearAll()
                        db.noteDao().clearAll()
                        db.taskDao().clearAll()
                        db.chatDao().clearAll()
                        db.chatConversationDao().clearAll()
                        repo.clearAll()
                        // Nothing references anything on disk any more, so sweeping with an
                        // empty "referenced ids" set drops every stored attachment file. If
                        // we skipped this, the files would just sit there until the next
                        // startup ran the orphan sweep — cleaner to free the space now so
                        // the storage figure matches what the user just did.
                        com.lucent.app.data.AttachmentStore.pruneOrphans(appContext, emptySet())
                        withContext(Dispatchers.Main) {
                            backupStatus = ""
                            LucentToast.show(appContext, S.allDataClearedToast)
                        }
                    }
                }) { Text(S.deleteEverything) }
            },
            dismissButton = { TextButton(onClick = { showClearData = false }) { Text(S.actionCancel) } }
        )
    }

    // Clear only notes. After deleting the rows, re-run the orphan sweep, which recomputes the
    // referenced attachment ids from whatever remains (tasks) and frees files that belonged only
    // to notes while keeping any still referenced elsewhere.
    if (showClearNotes) {
        AlertDialog(
            onDismissRequest = { showClearNotes = false },
            title = { Text(S.clearNotesTitle) },
            text = { Text(S.clearNotesBody) },
            confirmButton = {
                TextButton(onClick = {
                    showClearNotes = false
                    AppScope.io.launch {
                        // A note's revision history belongs to the note. Leaving it behind would
                        // orphan every row and quietly grow a table nothing can ever reach again.
                        db.noteVersionDao().clearAll()
                        db.noteDao().clearAll()
                        com.lucent.app.data.AttachmentMigration.pruneOrphans(appContext)
                        withContext(Dispatchers.Main) { LucentToast.show(appContext, S.notesClearedToast) }
                    }
                }) { Text(S.deleteNotesBtn) }
            },
            dismissButton = { TextButton(onClick = { showClearNotes = false }) { Text(S.actionCancel) } }
        )
    }

    if (showClearTasks) {
        AlertDialog(
            onDismissRequest = { showClearTasks = false },
            title = { Text(S.clearTasksTitle) },
            text = { Text(S.clearTasksBody) },
            confirmButton = {
                TextButton(onClick = {
                    showClearTasks = false
                    AppScope.io.launch {
                        // Same reasoning as "Clear all data": an alarm outlives its task unless it's
                        // explicitly cancelled first.
                        db.taskDao().getAllOnce().forEach {
                            com.lucent.app.reminders.ReminderScheduler.cancel(appContext, it.id)
                        }
                        db.taskDao().clearAll()
                        com.lucent.app.data.AttachmentMigration.pruneOrphans(appContext)
                        withContext(Dispatchers.Main) { LucentToast.show(appContext, S.tasksClearedToast) }
                    }
                }) { Text(S.deleteTasksBtn) }
            },
            dismissButton = { TextButton(onClick = { showClearTasks = false }) { Text(S.actionCancel) } }
        )
    }

    // Clear only the assistant's chat history. Chat attachments are stored inline on each message
    // row, so deleting the rows frees them directly — no disk sweep needed. We also reset the
    // assistant's in-memory conversation cache so it doesn't keep showing a deleted conversation.
    // --- API key deletion confirmation (task 1) ---
    // Only acts on the explicit "Delete" press. The index is re-validated inside the click because
    // the profile list can change between opening the dialog and confirming; deleteProfile itself
    // also refuses to remove the last remaining profile.
    profilePendingDelete?.let { idx ->
        val name = profiles.getOrNull(idx)?.name?.ifBlank { S.apiFallbackName(idx + 1) } ?: S.thisApiFallback
        AlertDialog(
            onDismissRequest = { profilePendingDelete = null },
            title = { Text(S.apiDeleteConfirmTitle) },
            text = { Text(S.apiDeleteConfirmBody(name)) },
            confirmButton = {
                TextButton(onClick = {
                    deleteProfile(idx)
                    profilePendingDelete = null
                }) { Text(S.actionDelete) }
            },
            dismissButton = { TextButton(onClick = { profilePendingDelete = null }) { Text(S.actionCancel) } }
        )
    }

    if (showClearChats) {
        AlertDialog(
            onDismissRequest = { showClearChats = false },
            title = { Text(S.clearChatsTitle) },
            text = { Text(S.clearChatsBody) },
            confirmButton = {
                TextButton(onClick = {
                    showClearChats = false
                    AppScope.io.launch {
                        db.chatDao().clearAll()
                        db.chatConversationDao().clearAll()
                        AssistantController.onAllChatsCleared(appContext)
                        withContext(Dispatchers.Main) { LucentToast.show(appContext, S.chatsClearedToast) }
                    }
                }) { Text(S.deleteChatsBtn) }
            },
            dismissButton = { TextButton(onClick = { showClearChats = false }) { Text(S.actionCancel) } }
        )
    }

    // --- Local model: per-slot delete confirmation (local-model task) ---
    // Deleting always asks first, for ANY model. If the model being deleted is the resident one the
    // engine is freed FIRST, then the file: unloading after deleting would leave a multi-gigabyte
    // model resident with nothing on disk to reload, which is the worst of both.
    lmSlotPendingDelete?.let { slot ->
        AlertDialog(
            onDismissRequest = { lmSlotPendingDelete = null },
            title = { Text(S.lmDeleteTitle) },
            text = { Text(S.lmDeleteBody(slot.name.ifBlank { "model.gguf" })) },
            confirmButton = {
                TextButton(onClick = {
                    deleteLocalModel(slot)
                    lmSlotPendingDelete = null
                }) { Text(S.actionDelete) }
            },
            dismissButton = { TextButton(onClick = { lmSlotPendingDelete = null }) { Text(S.actionCancel) } }
        )
    }

    // --- Local model: name a model at import time (custom names, task requirement) ---
    // The file is already picked; this captures the label before the copy runs. Cancelling here
    // drops the pending import entirely (nothing was copied yet).
    lmPendingImportUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { lmPendingImportUri = null },
            title = { Text(S.lmNameModelTitle) },
            text = {
                Column {
                    Text(S.lmNameModelBody, color = onGradientMuted, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = lmImportName,
                        onValueChange = { lmImportName = it.take(60) },
                        label = { Text(S.lmModelNameField) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    lmPendingImportUri = null
                    startLocalImport(uri, lmImportName)
                }) { Text(S.lmImportConfirm) }
            },
            dismissButton = { TextButton(onClick = { lmPendingImportUri = null }) { Text(S.actionCancel) } }
        )
    }

    // --- Local model: rename an imported model ---
    lmRenameTarget?.let { slot ->
        AlertDialog(
            onDismissRequest = { lmRenameTarget = null },
            title = { Text(S.lmRenameTitle) },
            text = {
                OutlinedTextField(
                    value = lmRenameText,
                    onValueChange = { lmRenameText = it.take(60) },
                    label = { Text(S.lmModelNameField) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val id = slot.id
                    val newName = lmRenameText
                    lmRenameTarget = null
                    scope.launch {
                        withContext(Dispatchers.IO) { LocalModelStore.rename(context, id, newName) }
                        lmRefresh++
                    }
                }) { Text(S.actionSave) }
            },
            dismissButton = { TextButton(onClick = { lmRenameTarget = null }) { Text(S.actionCancel) } }
        )
    }

    // --- Local model: warn before turning "use local model" ON ---
    // Enabling local mode freezes the cloud API and loads a multi-gigabyte model into RAM, so it
    // spells out the consequences first: the API stops being called, memory use is high, quitting the
    // app mid-reply interrupts the answer, and quitting frees that memory. Confirming enables it.
    if (lmConfirmUseLocalOn) {
        AlertDialog(
            onDismissRequest = { lmConfirmUseLocalOn = false },
            title = { Text(S.lmUseLocalWarnTitle) },
            text = { Text(S.lmUseLocalWarnBody) },
            confirmButton = {
                TextButton(onClick = {
                    lmConfirmUseLocalOn = false
                    AppScope.io.launch { repo.setLocalModelEnabled(true) }
                }) { Text(S.lmWarnEnableAnyway) }
            },
            dismissButton = { TextButton(onClick = { lmConfirmUseLocalOn = false }) { Text(S.actionCancel) } }
        )
    }

    // Warn before letting the on-device model call tools: it can be slower and, on a small model,
    // unreliable. Confirming enables it; cancelling leaves it off.
    if (lmConfirmToolsOn) {
        AlertDialog(
            onDismissRequest = { lmConfirmToolsOn = false },
            title = { Text(S.lmToolsWarnTitle) },
            text = { Text(S.lmToolsWarnBody) },
            confirmButton = {
                TextButton(onClick = {
                    lmConfirmToolsOn = false
                    AppScope.io.launch { repo.setLocalToolsEnabled(true) }
                }) { Text(S.lmWarnEnableAnyway) }
            },
            dismissButton = { TextButton(onClick = { lmConfirmToolsOn = false }) { Text(S.actionCancel) } }
        )
    }

    // Warn before switching the on-device model to the GPU: faster on some phones, but Vulkan
    // drivers vary and it can be unstable; it also needs the GPU backend compiled into the build.
    if (lmConfirmGpuOn) {
        AlertDialog(
            onDismissRequest = { lmConfirmGpuOn = false },
            title = { Text(S.lmGpuWarnTitle) },
            text = { Text(S.lmGpuWarnBody) },
            confirmButton = {
                TextButton(onClick = {
                    lmConfirmGpuOn = false
                    AppScope.io.launch { repo.setLocalGpuEnabled(true) }
                }) { Text(S.lmWarnEnableAnyway) }
            },
            dismissButton = { TextButton(onClick = { lmConfirmGpuOn = false }) { Text(S.actionCancel) } }
        )
    }

    // --- Selective Markdown export (choose which notes/tasks) ---
    // Lists for the picker, live so a just-added item is selectable.
    val notesForExport by remember { db.noteDao().getAll() }.collectAsState(initial = emptyList())
    val tasksForExport by remember { db.taskDao().getAll() }.collectAsState(initial = emptyList())
    // Which picker is open (null = none). NOTES or TASKS.
    var exportKind by remember { mutableStateOf<ExportKind?>(null) }
    // Part of the same "fold back to the root on leave" rule as `route` above (task 3); it lives
    // here rather than beside that effect because this is where the state it clears is declared.
    LaunchedEffect(active) {
        if (!active) exportKind = null
    }
    // The generated file bytes waiting for the location the user is about to pick. Bytes rather than
    // a Markdown string now, because Word/Excel/PDF exports are binary (task 1). The MIME rides along
    // so the created document is typed correctly.
    var pendingExportBytes by remember { mutableStateOf<ByteArray?>(null) }
    var pendingExportName by remember { mutableStateOf("lucent-export.md") }
    // The picker is created with a generic type and the real MIME/extension come from the chosen
    // format via the suggested file name — one launcher serves every format.
    val selectiveExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        val bytes = pendingExportBytes
        pendingExportBytes = null
        if (uri != null && bytes != null) {
            scope.launch {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out -> out.write(bytes) }
                }
                backupStatus = S.exportedSelected
            }
        }
    }

    // Turn a chosen subset + format into bytes and kick off the file picker. Shared by the notes and
    // tasks export screens below so the format handling lives in exactly one place. When [asZip] is
    // set the bytes are a .zip bundle (document + attachment files) and the suggested name gets a
    // .zip extension instead of the format's own; the generic launcher writes whatever bytes it's
    // given, so the extension is all that needs to change.
    fun launchExport(
        fileStem: String,
        bytes: ByteArray,
        format: com.lucent.app.data.ExportFormat,
        asZip: Boolean = false
    ) {
        pendingExportBytes = bytes
        pendingExportName = "$fileStem.${if (asZip) "zip" else format.extension}"
        exportKind = null
        selectiveExportLauncher.launch(pendingExportName)
    }

    BackHandler(enabled = exportKind != null) { exportKind = null }

    // A single scroll state for the whole settings body, declared BEFORE the export-selection early
    // return below (task 9). The export picker replaces the settings body entirely for a moment; if
    // the body's scroll position lived inside it, that position would be forgotten while the picker
    // was up and the page would snap back to the top on return. Hoisting it here — above the return —
    // keeps it alive across the detour, so coming back from "Choose … to export" lands exactly where
    // the user left off.
    val rootScroll = rememberScrollState()

    if (exportKind != null) {
        when (exportKind) {
            ExportKind.NOTES -> ExportSelectionScreen(
                title = S.exportNotesScreenTitle,
                items = notesForExport.filter { it.trashedAt == null },
                id = { it.id },
                label = { it.title },
                subtitle = { formatTimestamp(it.updatedAt) },
                timestamp = { it.updatedAt },
                searchText = { it.title + "\n" + it.body },
                attachmentsOf = { com.lucent.app.data.Attachments.parse(it.attachments) },
                onExport = { subset, format, atts ->
                    val doc = com.lucent.app.data.DocumentExport.exportNotes(subset, format)
                    if (atts.isEmpty()) {
                        launchExport("lucent-notes", doc, format)
                    } else {
                        val bundle = com.lucent.app.data.DocumentExport.zipWithAttachments(
                            context, "lucent-notes.${format.extension}", doc, atts
                        )
                        launchExport("lucent-notes", bundle, format, asZip = true)
                    }
                },
                onBack = { exportKind = null }
            )
            ExportKind.TASKS -> ExportSelectionScreen(
                title = S.exportTasksScreenTitle,
                items = tasksForExport.filter { it.trashedAt == null },
                id = { it.id },
                label = { it.title },
                subtitle = { formatTimestamp(it.createdAt) + if (it.isDone) S.doneSuffix else "" },
                timestamp = { it.createdAt },
                searchText = { it.title + "\n" + it.notes },
                attachmentsOf = { com.lucent.app.data.Attachments.parse(it.attachments) },
                onExport = { subset, format, atts ->
                    val doc = com.lucent.app.data.DocumentExport.exportTasks(subset, format)
                    if (atts.isEmpty()) {
                        launchExport("lucent-tasks", doc, format)
                    } else {
                        val bundle = com.lucent.app.data.DocumentExport.zipWithAttachments(
                            context, "lucent-tasks.${format.extension}", doc, atts
                        )
                        launchExport("lucent-tasks", bundle, format, asZip = true)
                    }
                },
                onBack = { exportKind = null }
            )
            null -> {}
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rootScroll).hazeSource(state = LocalHazeState.current).padding(16.dp)
    ) {
        when (route) {
            SettingsRoute.Root -> {
                // Section order (task 11), and it is deliberately a journey from the cosmetic to the
                // irreversible: how it looks, what language it speaks, what it can do for you, how
                // you write, who can get in, what leaves the device, and finally the page that can
                // erase everything. The destructive page being last is the point — it is the one you
                // should have to travel to rather than the one you land on.
                NavCard(S.settingsAppearanceTitle, S.settingsAppearanceSub) { route = SettingsRoute.Appearance }
                Spacer(modifier = Modifier.height(12.dp))
                // Language sits beside Appearance because it answers the same kind of question —
                // "how does this app present itself to me" — and a user hunting for it will look
                // near the top, not under a technical heading (localization task).
                NavCard(S.settingsLanguageTitle, S.settingsLanguageSub) { route = SettingsRoute.Language }
                Spacer(modifier = Modifier.height(12.dp))
                // The subtitle lists what is actually behind this card. It used to stop at the API,
                // which quietly under-sold the section: memory and web search live here too, and a
                // subtitle that names three of four things reads as a complete list rather than a
                // truncated one — so the fourth looks like it isn't there.
                NavCard(S.settingsAssistantTitle, S.settingsAssistantSub) { route = SettingsRoute.Assistant }
                Spacer(modifier = Modifier.height(12.dp))
                NavCard(S.settingsEditorTitle, S.settingsEditorSub) { route = SettingsRoute.Editor }
                Spacer(modifier = Modifier.height(12.dp))
                NavCard(S.settingsSecurityTitle, S.settingsSecuritySub) { route = SettingsRoute.Security }
                Spacer(modifier = Modifier.height(12.dp))
                NavCard(S.settingsPrivacyTitle, S.settingsPrivacySub) { route = SettingsRoute.Privacy }
                Spacer(modifier = Modifier.height(12.dp))
                NavCard(S.settingsDataTitle, S.settingsDataSub) { route = SettingsRoute.Data }
            }

            SettingsRoute.Language -> {
                BackHeader(S.settingsLanguageTitle) { route = SettingsRoute.Root }

                // One flat radio list: "follow the system", then the four languages, each shown in
                // its OWN language (the one universal convention for language pickers — a reader who
                // can't parse the current UI language can still find their own name). Selecting
                // writes the setting; MainActivity's collector applies it, and because the catalog
                // is snapshot state every S-reading text in the app — including this list —
                // recomposes in the new language on the very next frame. No restart, no flash.
                Column(modifier = Modifier.fillMaxWidth().frostedGlass().padding(16.dp)) {
                    Text(S.langPageHint, color = onGradientMuted, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { AppScope.io.launch { repo.setAppLanguage(AppLanguage.SYSTEM.key) } }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = savedLanguage == AppLanguage.SYSTEM.key,
                            onClick = { AppScope.io.launch { repo.setAppLanguage(AppLanguage.SYSTEM.key) } }
                        )
                        Column(modifier = Modifier.padding(start = 4.dp)) {
                            Text(S.langSystem, color = onGradient)
                            Text(
                                S.langSystemDetail(AppLanguage.systemDefault().label),
                                color = onGradientMuted,
                                fontSize = 12.sp
                            )
                        }
                    }

                    listOf(AppLanguage.EN, AppLanguage.ZH, AppLanguage.JA, AppLanguage.KO).forEach { lang ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { AppScope.io.launch { repo.setAppLanguage(lang.key) } }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = savedLanguage == lang.key,
                                onClick = { AppScope.io.launch { repo.setAppLanguage(lang.key) } }
                            )
                            Text(lang.label, color = onGradient, modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                // Font lives here now (moved out of Appearance): a typeface is a writing/language
                // choice as much as a visual one, so it sits with the language picker rather than
                // alongside theme and background.
                NavCard(S.settingsFontTitle, S.settingsFontSub) { route = SettingsRoute.Font }
            }

            SettingsRoute.Assistant -> {
                BackHeader(S.settingsAssistantTitle) { route = SettingsRoute.Root }

                // Order (task 10): Personalization, API, Memory & web, then Local model. The local
                // model comes last because it is the alternative to everything above it: with it on,
                // the API page's connection and the memory tier simply stop being consulted.
                NavCard(S.settingsPersonalizationTitle, S.settingsPersonalizationSub) { route = SettingsRoute.Personalization }

                Spacer(modifier = Modifier.height(12.dp))

                // API is its own hierarchical page. The subtitle shows which profile is active so
                // the user can see their current connection at a glance without opening it.
                val activeName = profiles.getOrNull(selectedProfileIdx)?.name ?: ""
                NavCard(S.settingsApiTitle, S.settingsApiSub(activeName)) { route = SettingsRoute.Api }

                Spacer(modifier = Modifier.height(12.dp))

                // Memory and Networking are now two separate cards: one page for how much the
                // assistant remembers (the memory tier), and a distinct page for going online (the
                // web-search toggle). They used to share a single "Memory & web" card.
                NavCard(S.settingsMemoryTitle, S.settingsMemorySub) { route = SettingsRoute.Memory }
                Spacer(modifier = Modifier.height(12.dp))
                NavCard(S.settingsNetworkTitle, S.settingsNetworkSub) { route = SettingsRoute.Network }

                Spacer(modifier = Modifier.height(12.dp))

                // On-device GGUF assistant (local-model task).
                NavCard(S.settingsLocalModelTitle, S.settingsLocalModelSub) { route = SettingsRoute.LocalModel }
            }

            SettingsRoute.LocalModel -> {
                BackHeader(S.settingsLocalModelTitle) { route = SettingsRoute.Assistant }

                if (!LocalLlm.isSupported()) {
                    // The .so wasn't packaged for this ABI (or failed to load). Everything below
                    // would be a dead end, so say why once, plainly, instead of offering buttons
                    // that can only disappoint.
                    Column(modifier = Modifier.fillMaxWidth().frostedGlass().padding(16.dp)) {
                        Text(S.lmUnsupportedAbiNote, color = onGradientMuted, fontSize = 13.sp)
                    }
                } else {
                    Column(modifier = Modifier.fillMaxWidth().frostedGlass().padding(16.dp)) {
                        // Experimental badge (task): set expectations before anything else on the page.
                        Text(S.lmExperimentalNote, color = onGradient, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(S.lmModelsTitle, color = onGradient, fontSize = 16.sp, modifier = Modifier.weight(1f))
                            Text("${lmModels.size}/${LocalModelStore.MAX_MODELS}", color = onGradientMuted, fontSize = 13.sp)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(S.lmPageIntro, color = onGradientMuted, fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(S.lmSizeHint, color = onGradientMuted, fontSize = 12.sp)
                        Spacer(modifier = Modifier.height(12.dp))

                        if (lmModels.isEmpty()) {
                            Text(S.lmNoModelYet, color = onGradient, fontSize = 14.sp)
                        } else {
                            // One row per imported model: a radio picks the ACTIVE model (only it is
                            // ever loaded), its name and size are shown, and each has rename + delete.
                            // Switching the radio releases the previously loaded model right away.
                            lmModels.forEach { slot ->
                                val active = slot.id == lmActiveId
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    RadioButton(selected = active, onClick = { selectLocalModel(slot.id) })
                                    Column(
                                        modifier = Modifier.weight(1f).clickable { selectLocalModel(slot.id) }.padding(vertical = 4.dp)
                                    ) {
                                        Text(slot.name.ifBlank { "model.gguf" }, color = onGradient, fontSize = 14.sp)
                                        Text(
                                            AttachmentLimits.formatBytes(LocalModelStore.modelSizeBytes(context, slot.id)) +
                                                (if (active) " · " + S.lmActiveTag else ""),
                                            color = onGradientMuted,
                                            fontSize = 12.sp
                                        )
                                    }
                                    IconButton(onClick = {
                                        lmRenameText = slot.name
                                        lmRenameTarget = slot
                                    }) {
                                        Icon(Icons.Default.Edit, contentDescription = S.lmRenameA11y, tint = onGradientMuted)
                                    }
                                    IconButton(onClick = { lmSlotPendingDelete = slot }) {
                                        Icon(Icons.Default.Delete, contentDescription = S.lmDeleteA11y, tint = onGradientMuted)
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        if (lmImporting) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator()
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(S.lmImporting, color = onGradientMuted, fontSize = 13.sp)
                            }
                        } else if (lmCanImportMore) {
                            TextButton(onClick = { lmImportLauncher.launch(arrayOf("*/*")) }) {
                                Icon(Icons.Default.Add, contentDescription = null, tint = onGradient)
                                Text(" " + S.lmImportButton, color = onGradient)
                            }
                        } else {
                            Text(S.lmSlotsFullHint(LocalModelStore.MAX_MODELS), color = onGradientMuted, fontSize = 12.sp)
                        }
                        if (lmError.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(lmError, color = Color(0xFFFFC1C1), fontSize = 13.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Column(modifier = Modifier.fillMaxWidth().frostedGlass().padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(S.lmUseLocalToggle, color = onGradient, fontSize = 16.sp)
                                Text(
                                    S.lmUseLocalToggleDesc,
                                    color = onGradientMuted,
                                    fontSize = 13.sp
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            // Can't be switched ON without a model (that state would only produce an
                            // error on the next send); can always be switched OFF, including the
                            // legacy case of a stored "on" with the model since removed. Turning it
                            // ON goes through a warning first (freezes the API, pulls the model into
                            // RAM); turning it OFF is immediate.
                            Switch(
                                checked = localModelEnabled,
                                enabled = lmHasModel || localModelEnabled,
                                onCheckedChange = { on ->
                                    if (on) lmConfirmUseLocalOn = true
                                    else AppScope.io.launch { repo.setLocalModelEnabled(false) }
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Opt-in: let the on-device model act on notes/tasks. Off by default because the
                    // tool protocol costs extra generation rounds a weak phone feels; turning it ON
                    // asks for confirmation first, turning it OFF is immediate.
                    Column(modifier = Modifier.fillMaxWidth().frostedGlass().padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(S.lmToolsToggle, color = onGradient, fontSize = 16.sp)
                                Text(S.lmToolsToggleDesc, color = onGradientMuted, fontSize = 13.sp)
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Switch(
                                checked = localToolsEnabled,
                                enabled = lmHasModel || localToolsEnabled,
                                onCheckedChange = { on ->
                                    if (on) lmConfirmToolsOn = true
                                    else AppScope.io.launch { repo.setLocalToolsEnabled(false) }
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Opt-in: run the model on the GPU instead of the CPU. Off (= CPU) by default
                    // because CPU never crashes; GPU can be faster but is unstable on some Android
                    // drivers, so switching TO it asks first, switching back to CPU is immediate.
                    // It also can't be switched ON until a model is imported (task): choosing a
                    // backend for a model that doesn't exist yet is meaningless and the setting is
                    // only ever read when a model is actually loaded — so the toggle is disabled with
                    // no model, exactly like "Use local model" above. Can always be switched OFF.
                    Column(modifier = Modifier.fillMaxWidth().frostedGlass().padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(S.lmGpuToggle, color = onGradient, fontSize = 16.sp)
                                Text(S.lmGpuToggleDesc, color = onGradientMuted, fontSize = 13.sp)
                                if (!lmHasModel) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(S.lmGpuNeedsModel, color = onGradientMuted, fontSize = 12.sp)
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Switch(
                                checked = localGpuEnabled,
                                enabled = lmHasModel || localGpuEnabled,
                                onCheckedChange = { on ->
                                    if (on) lmConfirmGpuOn = true
                                    else AppScope.io.launch { repo.setLocalGpuEnabled(false) }
                                }
                            )
                        }
                    }
                }
            }

            SettingsRoute.Personalization -> {
                BackHeader(S.settingsPersonalizationTitle) { leavePersonalization() }
                Column(modifier = Modifier.fillMaxWidth().frostedGlass().padding(16.dp)) {
                    OutlinedTextField(
                        value = assistantName,
                        onValueChange = { assistantName = it },
                        label = { Text(S.fieldAssistantName) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = assistantStyle,
                        onValueChange = { assistantStyle = it },
                        label = { Text(S.fieldChatStyle) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { persistAssistantSettings() }) { Text(S.actionSave) }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Typing haptics lives here now (task 4). It's part of how the chat feels rather than
                // anything to do with memory or the web, so it moved onto Personalization when the old
                // combined "Memory & web" page was split apart. It writes immediately and isn't part
                // of the name/style "unsaved changes" tracking, so leaving without pressing Save above
                // never affects it.
                Column(modifier = Modifier.fillMaxWidth().frostedGlass().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(S.typingHapticsTitle, color = onGradient, fontSize = 16.sp)
                            Text(
                                S.typingHapticsDesc,
                                color = onGradientMuted,
                                fontSize = 13.sp
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Switch(
                            checked = savedTypingHaptics,
                            onCheckedChange = { on -> AppScope.io.launch { repo.setTypingHapticsEnabled(on) } }
                        )
                    }
                }
            }

            SettingsRoute.Memory -> {
                BackHeader(S.settingsMemoryTitle) { route = SettingsRoute.Assistant }

                // Memory tier. Each option explains both what the assistant will remember and the
                // rough cost trade-off, since more context means more tokens per reply.
                Column(modifier = Modifier.fillMaxWidth().frostedGlass().padding(16.dp)) {
                    Text(S.memoryCostTitle, color = onGradient, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        S.memoryCostDesc,
                        color = onGradientMuted,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    val current = MemoryTier.fromKey(savedMemoryTier)

                    MemoryTierRow(
                        selected = current == MemoryTier.LOW,
                        title = S.memoryLowTitle,
                        detail = S.memoryLowDesc,
                        onGradient = onGradient,
                        onGradientMuted = onGradientMuted,
                        onClick = { AppScope.io.launch { repo.setMemoryTier(MemoryTier.LOW.key) } }
                    )
                    MemoryTierRow(
                        selected = current == MemoryTier.MEDIUM,
                        title = S.memoryMediumTitle,
                        detail = S.memoryMediumDesc,
                        onGradient = onGradient,
                        onGradientMuted = onGradientMuted,
                        onClick = { AppScope.io.launch { repo.setMemoryTier(MemoryTier.MEDIUM.key) } }
                    )
                    MemoryTierRow(
                        selected = current == MemoryTier.HIGH,
                        title = S.memoryHighTitle,
                        detail = S.memoryHighDesc,
                        onGradient = onGradient,
                        onGradientMuted = onGradientMuted,
                        onClick = { AppScope.io.launch { repo.setMemoryTier(MemoryTier.HIGH.key) } }
                    )
                }
            }

            SettingsRoute.Network -> {
                BackHeader(S.settingsNetworkTitle) { route = SettingsRoute.Assistant }

                // Web search toggle: lets the cloud assistant look things up online.
                Column(modifier = Modifier.fillMaxWidth().frostedGlass().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(S.webSearchTitle, color = onGradient, fontSize = 16.sp)
                            Text(
                                S.webSearchDesc,
                                color = onGradientMuted,
                                fontSize = 13.sp
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Switch(
                            checked = savedWebSearch,
                            onCheckedChange = { on -> AppScope.io.launch { repo.setWebSearchEnabled(on) } }
                        )
                    }
                }
            }

            SettingsRoute.Api -> {
                BackHeader(S.settingsApiTitle) { route = SettingsRoute.Assistant }

                // When local model mode is on, the cloud API is FROZEN — the assistant answers
                // on-device and never calls the API. Say so plainly at the top of the page (with a
                // one-tap way back to the Local Model page to turn it off), and disable the actions
                // that would call or commit an API below, so the freeze is tangible, not just a claim.
                if (localModelEnabled) {
                    Column(modifier = Modifier.fillMaxWidth().frostedGlass().padding(16.dp)) {
                        Text(S.apiFrozenTitle, color = onGradient, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(S.apiFrozenBody, color = onGradientMuted, fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = { route = SettingsRoute.LocalModel }) {
                            Text(S.apiFrozenManage, color = onGradient)
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // ---- API Selection: pick which saved profile is active ----
                Column(modifier = Modifier.fillMaxWidth().frostedGlass().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(S.apiSelectionTitle, color = onGradient, modifier = Modifier.weight(1f))
                        Text("${profiles.size}/${com.lucent.app.data.ApiProfiles.MAX}", color = onGradientMuted, fontSize = 13.sp)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(S.apiSelectionDesc(com.lucent.app.data.ApiProfiles.MAX), color = onGradientMuted, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    profiles.forEachIndexed { idx, p ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            RadioButton(selected = idx == selectedProfileIdx, onClick = { selectProfile(idx) })
                            Column(modifier = Modifier.weight(1f).clickable { selectProfile(idx) }.padding(vertical = 4.dp)) {
                                Text(p.name.ifBlank { S.apiFallbackName(idx + 1) }, color = onGradient)
                                Text(
                                    "${specLabel(p.spec)} · ${if (p.model.isBlank()) S.apiNoModel else p.model}",
                                    color = onGradientMuted,
                                    fontSize = 12.sp
                                )
                            }
                            // The delete icon is always shown — even for the only profile (task
                            // requirement). Deleting always asks for confirmation first (the dialog
                            // below), and deleting the last one clears it back to a blank slot.
                            IconButton(onClick = { profilePendingDelete = idx }) {
                                Icon(Icons.Default.Delete, contentDescription = S.apiDeleteA11y, tint = onGradientMuted)
                            }
                        }
                    }
                    if (profiles.size < com.lucent.app.data.ApiProfiles.MAX) {
                        Spacer(modifier = Modifier.height(4.dp))
                        TextButton(onClick = { addProfile() }) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = onGradient)
                            Text(" " + S.apiAddButton, color = onGradient)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // ---- Editor for the selected profile ----
                Column(modifier = Modifier.fillMaxWidth().frostedGlass().padding(16.dp)) {
                    Text(S.apiEditTitle, color = onGradient)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editingProfileName,
                        onValueChange = { editingProfileName = it },
                        label = { Text(S.fieldName) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = key,
                        onValueChange = {
                            key = it
                            typingReveal = true
                            keystrokeSeq++
                        },
                        label = { Text(S.fieldApiKey) },
                        visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { manualReveal = true }) {
                                Icon(
                                    if (keyVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = S.a11yToggleKeyVisibility
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(S.apiSpecTitle, color = onGradient)
                    Row {
                        RadioButton(selected = spec == "openai", onClick = { spec = "openai" })
                        Text(S.apiSpecOpenAi, color = onGradient, modifier = Modifier.padding(top = 14.dp))
                    }
                    Row {
                        RadioButton(selected = spec == "anthropic", onClick = { spec = "anthropic" })
                        Text(S.apiSpecAnthropic, color = onGradient, modifier = Modifier.padding(top = 14.dp))
                    }
                    Row {
                        RadioButton(selected = spec == "google", onClick = { spec = "google" })
                        Text(S.apiSpecGoogle, color = onGradient, modifier = Modifier.padding(top = 14.dp))
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(S.apiConnectionTitle, color = onGradient)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text(S.fieldBaseUrl) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        when (spec) {
                            "anthropic" -> S.apiUrlExampleAnthropic
                            "google" -> S.apiUrlExampleGoogle
                            else -> S.apiUrlExampleOpenAi
                        },
                        color = onGradientMuted
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        enabled = !localModelEnabled,
                        onClick = {
                        loading = true
                        errorText = ""
                        scope.launch {
                            val apiSpecEnum = when (spec) {
                                "anthropic" -> ApiSpec.ANTHROPIC
                                "google" -> ApiSpec.GOOGLE
                                else -> ApiSpec.OPENAI
                            }
                            val result = LlmClient.fetchModels(url.trim(), apiSpecEnum, key.trim())
                            loading = false
                            result.onSuccess { models = it }
                                .onFailure { errorText = S.errorWithDetail(it.javaClass.simpleName, it.message ?: S.noDetails) }
                        }
                    }) { Text(S.fetchModels) }

                    if (loading) {
                        Spacer(modifier = Modifier.height(8.dp))
                        CircularProgressIndicator()
                    }
                    if (errorText.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(errorText, color = Color(0xFFFFC1C1))
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(S.fieldModel, color = onGradient)
                    Box {
                        Button(onClick = { menuExpanded = true }, enabled = models.isNotEmpty()) {
                            Text(if (selectedModel.isBlank()) S.chooseModel else selectedModel)
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            Column(
                                modifier = Modifier
                                    .heightIn(max = 320.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                models.forEach { m ->
                                    DropdownMenuItem(text = { Text(m) }, onClick = {
                                        selectedModel = m
                                        menuExpanded = false
                                    })
                                }
                            }
                        }
                    }
                    if (models.isEmpty() && selectedModel.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(S.currentModelHint(selectedModel), color = onGradientMuted, fontSize = 12.sp)
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    // Saving writes the edits into the selected profile and activates it, so the
                    // assistant uses it right away. Disabled while local mode has the API frozen.
                    Button(enabled = !localModelEnabled, onClick = { saveActiveProfile(selectedProfileIdx) }) { Text(S.saveApi) }
                }
            }

            SettingsRoute.Appearance -> {
                BackHeader(S.settingsAppearanceTitle) { route = SettingsRoute.Root }
                // Two hierarchical entries, mirroring the Assistant screen's structure. (Font moved
                // to the Language screen — it's as much a writing choice as a visual one.)
                NavCard(S.settingsThemeTitle, S.settingsThemeSub) { route = SettingsRoute.Theme }
                Spacer(modifier = Modifier.height(12.dp))
                NavCard(S.settingsBackgroundTitle, S.settingsBackgroundSub) { route = SettingsRoute.Background }
            }

            SettingsRoute.Theme -> {
                BackHeader(S.settingsThemeTitle) { route = SettingsRoute.Appearance }
                // One flat list of every appearance, System/Light/Dark and the four Monet tints
                // alike (added task 1). They are peers, not a menu with a sub-menu of "extras":
                // each one is simply an answer to "what should the app look like", and a tint is no
                // less of an answer than "dark" is. Each row previews the actual backdrop colour it
                // selects, so the choice can be made by eye rather than by name.
                val systemDark = isSystemInDarkTheme()
                Column(modifier = Modifier.fillMaxWidth().frostedGlass().padding(16.dp)) {
                    LucentThemeMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { AppScope.io.launch { repo.setThemeMode(mode.key) } }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = savedTheme == mode.key,
                                onClick = { AppScope.io.launch { repo.setThemeMode(mode.key) } }
                            )
                            PaletteSwatch(mode.swatch(systemDark))
                            Column(modifier = Modifier.padding(start = 10.dp)) {
                                Text(mode.label, color = onGradient)
                                Text(mode.detail, color = onGradientMuted, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            SettingsRoute.Background -> {
                BackHeader(S.settingsBackgroundTitle) { route = SettingsRoute.Appearance }
                // At the very top: the master switch for the drifting effect. Off = a still, flat
                // theme colour, and the palette choice below only takes visible effect once it's on.
                Column(modifier = Modifier.fillMaxWidth().frostedGlass().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(S.backgroundAnimationTitle, color = onGradient)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(S.backgroundAnimationDesc, color = onGradientMuted, fontSize = 13.sp)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Switch(
                            checked = backgroundAnimationEnabled,
                            onCheckedChange = { checked -> scope.launch { repo.setBackgroundAnimationEnabled(checked) } }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Column(modifier = Modifier.fillMaxWidth().frostedGlass().padding(16.dp)) {
                    // Auto-cycle: rotates through every palette over time. Its swatch previews the
                    // spread of colours it moves through.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = savedPalette == PALETTE_CYCLE,
                            onClick = { AppScope.io.launch { repo.setPalette(PALETTE_CYCLE) } }
                        )
                        PaletteSwatch(LucentPalette.entries.map { it.colors.first() })
                        Text(S.paletteCycleAuto, color = onGradient, modifier = Modifier.padding(start = 10.dp))
                    }

                    // Palettes grouped by kind, each with a small colour preview.
                    listOf(
                        S.paletteGroupSolid to PaletteGroup.SOLID,
                        S.paletteGroupGradient to PaletteGroup.GRADIENT,
                        S.paletteGroupClassic to PaletteGroup.CLASSIC
                    ).forEach { (heading, group) ->
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(heading, color = onGradientMuted, fontSize = 13.sp)
                        LucentPalette.entries.filter { it.group == group }.forEach { p ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = savedPalette == p.name,
                                    onClick = { AppScope.io.launch { repo.setPalette(p.name) } }
                                )
                                PaletteSwatch(p.colors)
                                Text(p.label, color = onGradient, modifier = Modifier.padding(start = 10.dp))
                            }
                        }
                    }
                }
            }

            SettingsRoute.Font -> {
                BackHeader(S.settingsFontTitle) { route = SettingsRoute.Language }
                Column(modifier = Modifier.fillMaxWidth().frostedGlass().padding(16.dp)) {
                    // One row per font, its label drawn in its own font so the list doubles as a live
                    // preview; selecting saves immediately. The list is grouped by writing system with
                    // a localized header, so all twelve faces are shown but it's always clear which
                    // language each is for. A small reusable row keeps the four groups identical.
                    @Composable
                    fun fontRow(f: LucentFont, shownLabel: String) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable {
                                AppScope.io.launch { repo.setFont(f.key) }
                            }
                        ) {
                            RadioButton(
                                selected = savedFont == f.key,
                                onClick = { AppScope.io.launch { repo.setFont(f.key) } }
                            )
                            Text(
                                shownLabel,
                                color = onGradient,
                                fontFamily = f.fontFamily,
                                fontSize = 16.sp,
                                modifier = Modifier.padding(start = 10.dp)
                            )
                        }
                    }

                    @Composable
                    fun fontGroup(header: String, script: FontScript) {
                        Text(
                            header,
                            color = onGradientMuted,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                        )
                        LucentFont.entries.filter { it.script == script }.forEach { fontRow(it, it.label) }
                    }

                    // System first (localized label), then the four language groups.
                    fontRow(LucentFont.SYSTEM, S.fontSystemLabel)
                    fontGroup(S.fontGroupEnglish, FontScript.LATIN)
                    fontGroup(S.fontGroupChinese, FontScript.CHINESE)
                    fontGroup(S.fontGroupJapanese, FontScript.JAPANESE)
                    fontGroup(S.fontGroupKorean, FontScript.KOREAN)
                }
            }

            SettingsRoute.Editor -> {
                BackHeader(S.settingsEditorTitle) { route = SettingsRoute.Root }
                Column(modifier = Modifier.fillMaxWidth().frostedGlass().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(S.markdownFormattingTitle, color = onGradient)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                S.markdownFormattingDesc,
                                color = onGradientMuted,
                                fontSize = 13.sp
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Switch(
                            checked = markdownEnabled,
                            onCheckedChange = { checked -> scope.launch { repo.setMarkdownEnabled(checked) } }
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    // Links is a fully independent switch now (task 8). It used to be a sub-toggle:
                    // greyed out and forced off whenever Markdown was off, on the theory that links
                    // are a Markdown feature. They aren't. Markdown decides whether text is
                    // *formatted*; links decide whether notes are *connected*. Anyone who wanted to
                    // see their text exactly as typed was made to give up their note graph as well —
                    // every [[link]] they had written went dead, and the switch that would have
                    // fixed it was greyed out with no explanation.
                    //
                    // All four combinations are now real and behave sensibly, plain-text-with-links
                    // included (see ui/Markdown.kt, LinkedPlainText).
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(S.linksTitle, color = onGradient)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                S.linksDesc,
                                color = onGradientMuted,
                                fontSize = 13.sp
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Switch(
                            checked = linksEnabled,
                            onCheckedChange = { checked -> scope.launch { repo.setLinksEnabled(checked) } }
                        )
                    }
                }
            }

            SettingsRoute.Security -> {
                BackHeader(S.settingsSecurityTitle) { route = SettingsRoute.Root }

                // Security is "who can get into this app", and right now that is exactly one control
                // — the app lock, moved here by task 10. It is the page's only occupant on purpose:
                // a page with one clear job is easier to trust than a page with a heading that
                // covers two.
                Column(modifier = Modifier.fillMaxWidth().frostedGlass().padding(16.dp)) {
                    // ---- App Lock ----
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(S.appLockTitle, color = onGradient)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                S.appLockDesc,
                                color = onGradientMuted,
                                fontSize = 13.sp
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Switch(
                            checked = appLockOn,
                            onCheckedChange = { turnOn ->
                                if (turnOn) {
                                    // Capture credentials before enabling; the lock isn't turned on
                                    // until the setup dialog is completed.
                                    lockPw = ""; lockPwConfirm = ""; lockQuestion = ""; lockAnswer = ""
                                    lockSetupError = ""
                                    showAppLockSetup = true
                                } else {
                                    // Don't disable straight away: a dialog confirms the password
                                    // first and explains the risk of removing the lock (task).
                                    disablePw = ""; disableError = ""
                                    showAppLockDisable = true
                                }
                            }
                        )
                    }

                }
            }

            SettingsRoute.Privacy -> {
                BackHeader(S.settingsPrivacyTitle) { route = SettingsRoute.Root }

                // Privacy is the other half of the old combined page (task 10): not "who can get in"
                // but "what gets out, or written down". Both switches here are off by default and
                // both are about visibility beyond this screen — one makes Lucent visible to other
                // apps, the other records a local file about what the app did.
                Column(modifier = Modifier.fillMaxWidth().frostedGlass().padding(16.dp)) {
                    // ---- System share / intent integration ----
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(S.systemIntegrationTitle, color = onGradient)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                S.systemIntegrationDesc,
                                color = onGradientMuted,
                                fontSize = 13.sp
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Switch(
                            checked = systemIntegrationOn,
                            onCheckedChange = { turnOn ->
                                if (turnOn) {
                                    // Show the privacy warning first; only enable on explicit confirm.
                                    showShareWarning = true
                                } else {
                                    scope.launch { repo.setSystemIntegrationEnabled(false) }
                                    ShareIntegration.setEnabled(context, false)
                                    // Disabling now gets the same bottom notification enabling does
                                    // (task): an acknowledgement that the change took effect. No dialog
                                    // — turning a feature *off* needs confirming, not warning about.
                                    LucentToast.show(context, S.systemIntegrationOffToast)
                                }
                            }
                        )
                    }

                    // ---- Local diagnostic logging ----
                    Spacer(modifier = Modifier.height(20.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(S.startupLoggingTitle, color = onGradient)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                S.startupLoggingDesc,
                                color = onGradientMuted,
                                fontSize = 13.sp
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Switch(
                            checked = startupLoggingOn,
                            onCheckedChange = { turnOn ->
                                scope.launch { repo.setStartupLoggingEnabled(turnOn) }
                                StartupLog.setEnabled(turnOn)
                                // The log file itself is a diagnostic artefact and stays English on
                                // purpose, like every other log line — it must read the same in a
                                // bug report no matter what language the UI was in at the time.
                                StartupLog.event(context, if (turnOn) "Logging enabled from Settings" else "")
                            }
                        )
                    }
                    if (startupLoggingOn) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row {
                            Button(onClick = { logsExportLauncher.launch("lucent-startup-log.txt") }) { Text(S.exportLogs) }
                            Spacer(modifier = Modifier.width(12.dp))
                            TextButton(onClick = {
                                StartupLog.clear(context)
                                // Toast rather than the Data page's backupStatus line, which isn't
                                // shown on this page (task 5 moved these controls here).
                                LucentToast.show(context, S.logsClearedToast)
                            }) { Text(S.clearLogs) }
                        }
                    }
                }
            }

            SettingsRoute.Data -> {
                BackHeader(S.settingsDataTitle) { route = SettingsRoute.Root }

                // Shown only in the (rare, alarming) case where the database couldn't be decrypted.
                // Nothing was deleted — the old file was set aside — but silence here would leave
                // someone staring at an empty app with no idea why, and no idea what to do.
                if (lockedNotice != null && !lockedDismissed) {
                    Column(modifier = Modifier.fillMaxWidth().frostedGlass().padding(16.dp)) {
                        Text(S.lockedNoticeTitle, color = Color(0xFFFF8A80))
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            if (lockedNoticeFileName != null) S.lockedNoticeBody(lockedNoticeFileName)
                            else lockedNotice,
                            color = onGradientMuted,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row {
                            Button(onClick = { importLauncher.launch(arrayOf("*/*")) }) { Text(S.importBackup) }
                            Spacer(modifier = Modifier.width(12.dp))
                            TextButton(onClick = {
                                com.lucent.app.data.DatabaseEncryption.clearLockedNotice(context)
                                lockedDismissed = true
                            }) { Text(S.actionDismiss) }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                Column(modifier = Modifier.fillMaxWidth().frostedGlass().padding(16.dp)) {
                    Text(S.backupRestoreTitle, color = onGradient)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        S.backupRestoreDesc,
                        color = onGradientMuted,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row {
                        Button(onClick = { showExportDialog = true }) { Text(S.exportBackup) }
                        Spacer(modifier = Modifier.width(12.dp))
                        // Allow any file so a beginner can always locate their .lcb even when the
                        // device reports an unexpected MIME type for it. The import path validates the
                        // content itself — it requires a Lucent .lcb envelope and rejects anything else
                        // with a clear message (legacy ZIP/JSON support has been removed, task 5).
                        Button(onClick = { importLauncher.launch(arrayOf("*/*")) }) { Text(S.importBackup) }
                    }
                    if (backupStatus.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(backupStatus, color = onGradientMuted)
                    }

                    Spacer(modifier = Modifier.height(20.dp))
                    Text(S.exportNotesTasksTitle, color = onGradient)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        S.exportNotesTasksDesc,
                        color = onGradientMuted,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    // Two full-width buttons, tasks first (task 9). They line up cleanly instead of the
                    // old mismatched row, and each opens the pick-items-and-format screen.
                    Button(onClick = { exportKind = ExportKind.TASKS }, modifier = Modifier.fillMaxWidth()) { Text(S.chooseTasksToExport) }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { exportKind = ExportKind.NOTES }, modifier = Modifier.fillMaxWidth()) { Text(S.chooseNotesToExport) }

                    Spacer(modifier = Modifier.height(20.dp))
                    Text(S.dangerZone, color = onGradient)
                    Spacer(modifier = Modifier.height(8.dp))
                    // Targeted clears, then the full wipe. All four are identical full-width
                    // rectangles (same shape/size) with the same red style; each asks for
                    // confirmation. Labels are kept short so they fit on one line while still
                    // making each button's function obvious.
                    Button(
                        onClick = { showClearNotes = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB3261E)),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(S.clearNotesBtn) }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { showClearTasks = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB3261E)),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(S.clearTasksBtn) }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { showClearChats = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB3261E)),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(S.clearChatsBtn) }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { showClearData = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB3261E)),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(S.clearAllDataBtn) }
                }
            }
        }
    }
}

/**
 * Human label for a stored API spec key. Provider names are proper nouns — identical in every
 * supported language — so this stays a plain map rather than a catalog lookup.
 */
private fun specLabel(spec: String): String = when (spec) {
    "anthropic" -> "Anthropic"
    "google" -> "Google"
    else -> "OpenAI"
}

/** A small rounded chip previewing a palette as a horizontal gradient of its colours. */
@Composable
private fun PaletteSwatch(colors: List<Color>) {
    val preview = if (colors.size >= 2) colors else listOf(
        colors.firstOrNull() ?: Color.Gray,
        colors.firstOrNull() ?: Color.Gray
    )
    Box(
        modifier = Modifier
            .padding(start = 4.dp)
            .width(44.dp)
            .height(22.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Brush.horizontalGradient(preview))
    )
}

@Composable
private fun NavCard(title: String, subtitle: String, onClick: () -> Unit) {
    val onGradient = LocalOnGradient.current
    val onGradientMuted = LocalOnGradientMuted.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .frostedGlass()
            .clickable { onClick() }
            .padding(16.dp)
    ) {
        Text(title, color = onGradient, fontSize = 18.sp)
        Spacer(modifier = Modifier.height(4.dp))
        Text(subtitle, color = onGradientMuted, fontSize = 13.sp)
    }
}

@Composable
private fun BackHeader(title: String, onBack: () -> Unit) {
    val onGradient = LocalOnGradient.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = S.actionBack, tint = onGradient)
        }
        Text(title, color = onGradient, fontSize = 20.sp)
    }
    Spacer(modifier = Modifier.height(8.dp))
}

/** One selectable memory-tier row: a radio button, the tier's name, and its cost explanation (issue 9). */
@Composable
private fun MemoryTierRow(
    selected: Boolean,
    title: String,
    detail: String,
    onGradient: Color,
    onGradientMuted: Color,
    onClick: () -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 4.dp)) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.padding(start = 4.dp, top = 4.dp)) {
            Text(title, color = onGradient)
            Text(detail, color = onGradientMuted, fontSize = 12.sp)
        }
    }
}

/**
 * One line of the "here's what's in this backup" list.
 *
 * A zero renders nothing at all. A restore preview listing "0 chat messages, 0 attachments" is
 * technically complete and practically noise — the point of the screen is to let someone see, at a
 * glance, what is about to arrive, and padding it with everything that *isn't* there makes that
 * harder, not easier.
 */
@Composable
private fun BackupContentLine(label: String, count: Int, details: List<String>) {
    if (count <= 0) return
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text("$count", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(52.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 13.sp)
            if (details.isNotEmpty()) {
                Text(details.joinToString(", "), fontSize = 12.sp)
            }
        }
    }
}
