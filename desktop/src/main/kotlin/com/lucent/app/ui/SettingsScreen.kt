package com.lucent.app.ui

import androidx.activity.compose.BackHandler
import com.lucent.desktop.platform.DesktopFiles
import java.io.File
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.NonRestartableComposable
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lucent.app.AppScope
import com.lucent.app.data.AppDatabase
import com.lucent.app.data.AppLock
import com.lucent.app.data.AttachmentLimits
import com.lucent.app.data.BackupManager
import com.lucent.app.data.FontStore
import com.lucent.app.security.WindowsHello
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
private enum class SettingsRoute { Root, Language, Assistant, Personalization, Memory, Network, Api, LocalModel, Appearance, Theme, Background, Editor, Security, Privacy, Data }

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
    // Whether a local reply survives the app going to the background (task 2). Off by default.
    val localBackgroundReply by repo.localBackgroundReplyEnabled.collectAsState(initial = false)

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
    //
    // "No profiles" and "no profiles yet" are different states, and telling them apart is what makes
    // deleting the last API actually possible (task 6). The stored JSON is the discriminator:
    //
    //   blank   -> nothing was ever saved. This is an install upgrading from the single-API version,
    //              so one profile is seeded from the legacy flat connection values and the user's
    //              existing configuration survives the upgrade untouched.
    //   "[]"    -> a list WAS saved and it is empty: the user deleted their last API on purpose.
    //              Re-seeding a blank "API 1" here is what used to make that deletion impossible —
    //              the row reappeared instantly and the delete looked like it had failed.
    val profiles = remember(savedProfilesJson, savedUrl, savedSpec, savedKey, savedModel) {
        val parsed = com.lucent.app.data.ApiProfiles.parse(savedProfilesJson)
        when {
            parsed.isNotEmpty() -> parsed
            savedProfilesJson.isNotBlank() -> emptyList()
            else -> listOf(
                com.lucent.app.data.ApiProfile(
                    name = "API 1", spec = savedSpec, baseUrl = savedUrl, apiKey = savedKey, model = savedModel
                )
            )
        }
    }
    val selectedProfileIdx = savedSelectedIdx.coerceIn(0, (profiles.size - 1).coerceAtLeast(0))

    var models by remember { mutableStateOf(listOf<String>()) }
    var loading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf("") }
    var menuExpanded by remember { mutableStateOf(false) }
    var backupStatus by remember { mutableStateOf("") }

    // --- Privacy toggles: App Lock (task 2), System integration (task 6), Startup logging (task 15) ---
    val appLockOn by repo.appLockEnabled.collectAsState(initial = false)
    // Windows Hello: whether the user opted in, and whether this machine can actually do it (probed
    // once, off the main thread). The toggle for it is shown in Security only when the lock is on AND
    // Hello is available. See security/WindowsHello.
    val helloEnabled by repo.appLockHelloEnabled.collectAsState(initial = false)
    var helloAvailable by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        helloAvailable = WindowsHello.availability() == WindowsHello.Availability.AVAILABLE
    }
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
    // Desktop log export: native save dialog on the AWT thread (from the button below), write on IO.
    fun exportLogs(suggestedName: String) {
        val file = DesktopFiles.saveFile(suggestedName = suggestedName) ?: return
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    file.writeBytes(StartupLog.buildExport(context).toByteArray())
                    true
                } catch (e: Exception) {
                    false
                }
            }
            backupStatus = if (ok) S.logsExported else S.logsExportFailed
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
    // Which sections the next export writes (task 9). Defaults to everything except the model
    // files, which is byte-for-byte what an export produced before this was selectable — so the
    // common case is unchanged and the choice only costs anyone who wants it.
    var exportModules by remember { mutableStateOf(BackupManager.DEFAULT_MODULES) }
    // Per-item selection (second-level menu). Null means "everything in that module", which is the
    // default and what every previous release did; a non-null set is an explicit subset the user
    // built by hand. Kept null until they actually open the sub-menu, so the common path never pays
    // to enumerate every note and task.
    var exportNoteIds by remember { mutableStateOf<Set<Long>?>(null) }
    var exportTaskIds by remember { mutableStateOf<Set<Long>?>(null) }
    // Same per-item shape for the two sections that gained it in task F1: which chat conversations
    // and which API profiles the export includes. Null = everything in that module (the default);
    // API profiles are held by NAME (the label the user picks, stable across reordering), the same
    // handle BackupManager filters on.
    var exportConversationIds by remember { mutableStateOf<Set<Long>?>(null) }
    var exportApiProfileNames by remember { mutableStateOf<Set<String>?>(null) }
    // The REAL saved API profiles — not the synthetic single profile the API page shows when nothing
    // has been saved yet (see `profiles` above). The per-profile export picker is built from and
    // gated on this list, so its indices line up exactly with what BackupManager re-parses from the
    // same stored JSON. When it's empty (a fresh install still on the legacy flat keys), no API
    // drill-in is offered and the whole-API toggle is the only choice — which is the honest option
    // when there is only one connection to include or leave out.
    val realProfiles = remember(savedProfilesJson) { com.lucent.app.data.ApiProfiles.parse(savedProfilesJson) }
    // Which second-level picker is open, if any.
    var itemPicker by remember { mutableStateOf<ExportItemKind?>(null) }
    // The item lists behind the second-level picker. Loaded only while the export dialog is open:
    // reading every note and task is cheap, but doing it on a Settings screen nobody is exporting
    // from is work for nothing, and on a large database it is work for nothing on every recomposition.
    var allNotes by remember { mutableStateOf<List<com.lucent.app.data.Note>>(emptyList()) }
    var allTasks by remember { mutableStateOf<List<com.lucent.app.data.Task>>(emptyList()) }
    // Conversations behind the chat picker, loaded (like notes/tasks) only while the export dialog is
    // open. API profiles need no load here — realProfiles above already comes from settings.
    var allConversations by remember {
        mutableStateOf<List<com.lucent.app.data.ChatConversation>>(emptyList())
    }
    LaunchedEffect(showExportDialog) {
        if (showExportDialog) {
            val loadedNotes = withContext(Dispatchers.IO) { db.noteDao().getAllOnce() }
            val loadedTasks = withContext(Dispatchers.IO) { db.taskDao().getAllOnce() }
            val loadedConversations = withContext(Dispatchers.IO) { db.chatConversationDao().getAllOnce() }
            allNotes = loadedNotes
            allTasks = loadedTasks
            allConversations = loadedConversations
        }
    }
    // Which sections a restore applies. Populated from the file's own contents when the preview
    // opens, so the list offers what is actually in the file rather than a fixed menu of maybes.
    var restoreModules by remember { mutableStateOf(BackupManager.DEFAULT_MODULES) }
    // Per-item restore choices (task F2), the import-side mirror of the export selection. Reset when a
    // new preview opens (see the ImportPreviewDialog). Chats by conversation id, API by name — the
    // handles the preview's own lists carry.
    var restoreConversationIds by remember { mutableStateOf<Set<Long>?>(null) }
    var restoreApiProfileNames by remember { mutableStateOf<Set<String>?>(null) }
    var restoreItemPicker by remember { mutableStateOf<ExportItemKind?>(null) }
    // Set when confirming a restore whose API profiles would push this device over ApiProfiles.MAX.
    // Drives the API-limit prompt (below), which asks which of the incoming profiles to keep before
    // any of them is written — the alternative, silently dropping the overflow, is never done.
    var apiLimitPrompt by remember { mutableStateOf(false) }
    var exportPasswordVisible by remember { mutableStateOf(false) }

    // A backup that needs a password we don't have. The bytes are held here rather than re-read on
    // submit, because the picker's Uri may well not still be readable by the time the user finishes
    // typing — and losing a backup to an expired permission grant would be an absurd way to lose one.
    var pendingImportBytes by remember { mutableStateOf<ByteArray?>(null) }
    var importPasswordDraft by remember { mutableStateOf("") }
    var importPasswordError by remember { mutableStateOf(false) }
    // What's in the file, worked out without writing anything. Non-null means the confirm step is up.
    var importPreview by remember { mutableStateOf<BackupManager.BackupPreview?>(null) }
    // The actual restore + its post-restore housekeeping, in one place so the confirm button and the
    // API-limit prompt drive identical behaviour; only the API profile selection differs between them.
    // Passing a non-null (possibly empty) profile-name set makes the API restore MERGE into this
    // device's profiles; passing null keeps the legacy whole-API replace (used for old single-API
    // backups that carry only the flat connection keys).
    val runRestore: (BackupManager.BackupPreview, Set<BackupManager.BackupModule>, Set<Long>?, Set<String>?) -> Unit =
        { preview, modules, convIds, apiNames ->
            importPreview = null
            apiLimitPrompt = false
            scope.launch {
                backupStatus = withContext(Dispatchers.IO) {
                    try {
                        BackupManager.commit(context, db, repo, preview, modules, convIds, apiNames)
                    } catch (e: Exception) {
                        S.importFailed(e.message ?: "")
                    }
                }
                // A restored backup can bring in tasks that want reminders, and alarms aren't part of
                // a backup file — they're OS state. BackupManager re-arms them, but the channel has to
                // exist before one can be posted.
                com.lucent.app.reminders.Notifications.ensureChannel(context)
                // If a database had been set aside as undecryptable, restoring is the cure — retire
                // the notice rather than leaving it frightening someone who has already fixed it.
                com.lucent.app.data.DatabaseEncryption.clearLockedNotice(context)
            }
        }
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
        mutableStateOf(profiles.getOrNull(selectedProfileIdx)?.name ?: "")
    }

    // --- Local model (GGUF) page state (local-model task) ---
    //
    // lmRefresh is a change counter: bumping it makes the remember()s below re-read the store, so
    // the page reflects an import/delete/rename/switch immediately without any second source of truth.
    var lmRefresh by remember { mutableStateOf(0) }
    val lmIndex = remember(lmRefresh) { LocalModelStore.index(context) }
    val lmModels = lmIndex.slots
    val lmActiveId = lmIndex.activeId
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
    var lmPendingImportFile by remember { mutableStateOf<File?>(null) }
    var lmImportName by remember { mutableStateOf("") }
    // Warning dialogs for the opt-in local-model switches (use-local, tools, GPU). Turning any ON
    // asks first; turning OFF is free (back to the safe default), so only the "on" path is gated.
    var lmConfirmToolsOn by remember { mutableStateOf(false) }
    var lmConfirmGpuOn by remember { mutableStateOf(false) }
    // Turning "use local model" ON freezes the cloud API and pulls a multi-gigabyte model into RAM,
    // so it warns first (API frozen, memory cost, don't quit mid-reply, quitting frees the memory).
    var lmConfirmUseLocalOn by remember { mutableStateOf(false) }
    // Letting a reply run on in the background keeps a multi-gigabyte model resident while the user
    // is somewhere else entirely, so switching it ON warns first (task 2). Off is always immediate.
    var lmConfirmBackgroundOn by remember { mutableStateOf(false) }

    // The GGUF picker. A native open dialog on the AWT thread; LocalModelStore validates the actual
    // bytes (GGUF magic, or a zip containing a .gguf) and rejects everything else with a clear
    // message. Picking doesn't import straight away: it stages the File and opens a naming dialog
    // first, so the user can label the model (custom names, task requirement). Import runs on confirm.
    fun pickLocalModel() {
        if (lmImporting) return
        val file = DesktopFiles.openFile() ?: return
        lmError = ""
        // Default the name to the picked file's name (minus extension); the user can edit it.
        lmImportName = file.name.substringBeforeLast('.').take(60)
        lmPendingImportFile = file
    }

    // Run the staged import under the chosen name. Frees the resident model first so the peak
    // footprint stays at one model, then adds the new slot (which becomes active) and refreshes.
    fun startLocalImport(file: File, name: String) {
        if (lmImporting) return
        lmImporting = true
        lmError = ""
        scope.launch {
            val error = withContext(Dispatchers.IO) {
                try {
                    LocalLlm.shutdown()
                    LocalModelStore.import(context, file, name)
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

    // --- Imported font state (font library task) ---
    //
    // Same shape as the local-model state above, at a smaller scale: fontRefresh is a change
    // counter, and bumping it makes the remember() below re-read the store so the picker reflects
    // an import/delete immediately without a second source of truth.
    var fontRefresh by remember { mutableStateOf(0) }
    val importedFonts = remember(fontRefresh) { FontStore.index(context) }.slots
    val fontCanImportMore = importedFonts.size < FontStore.MAX_FONTS
    var fontImporting by remember { mutableStateOf(false) }
    var fontError by remember { mutableStateOf("") }
    // The font the user has asked to delete, held until they confirm. Deleting always asks first.
    var fontPendingDelete by remember { mutableStateOf<FontStore.FontSlot?>(null) }
    // A just-picked font file awaiting a name before it is imported (null = no naming dialog up).
    // Holding the File lets the user label the font at import time (custom names, task requirement).
    var fontPendingImportFile by remember { mutableStateOf<File?>(null) }
    var fontImportName by remember { mutableStateOf("") }

    // The font picker. A native open dialog on the AWT thread, unfiltered for the same reason the
    // GGUF picker is: FontStore validates the actual bytes (TTF/OTF/TTC magic) and rejects
    // everything else with a clear message. Picking doesn't import straight away: it stages the
    // File and opens a naming dialog first.
    fun pickImportFont() {
        if (fontImporting) return
        val file = DesktopFiles.openFile() ?: return
        fontError = ""
        // Default the name to the picked file's name (minus extension); the user can edit it.
        fontImportName = file.name.substringBeforeLast('.').take(60)
        fontPendingImportFile = file
    }

    // Run the staged font import under the chosen name. The new font becomes the app font right
    // away — it is the one the user just added, and the immediate whole-app change doubles as the
    // clearest possible confirmation that the import worked.
    fun startFontImport(file: File, name: String) {
        if (fontImporting) return
        fontImporting = true
        fontError = ""
        scope.launch {
            var importedId: String? = null
            val error = withContext(Dispatchers.IO) {
                try {
                    importedId = FontStore.import(context, file, name).id
                    null
                } catch (e: FontStore.TooManyFontsException) {
                    S.fontImportFailedTooMany(FontStore.MAX_FONTS)
                } catch (e: FontStore.NotFontException) {
                    S.fontImportFailedNotFont
                } catch (e: Exception) {
                    S.fontImportFailedGeneric(e.message ?: "")
                }
            }
            fontImporting = false
            if (error == null) {
                importedId?.let { id -> AppScope.io.launch { repo.setFont(id) } }
                fontRefresh++
                LucentToast.show(context.applicationContext, S.fontImportedToast)
            } else {
                fontError = error
            }
        }
    }

    // Delete an imported font. If it is the selected font, the preference falls back to the
    // system font FIRST, so no frame is ever asked to render from a family whose file is gone;
    // the cached FontFamily is dropped for the same reason.
    fun deleteImportedFont(slot: FontStore.FontSlot) {
        scope.launch {
            withContext(Dispatchers.IO) {
                if (savedFont == slot.id) repo.setFont(SYSTEM_FONT_KEY)
                FontStore.delete(context, slot.id)
            }
            LucentFontResolver.evict(slot.id)
            fontRefresh++
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

    // Delete profile [idx] — including the last one, which is now a genuine deletion (task 6).
    //
    // It used to "delete" the final profile by replacing it with a fresh blank "API 1". That was
    // meant to keep the editor usable, and instead produced the one outcome a delete button must
    // never produce: you tapped Delete, confirmed, and a row with the same name was still sitting
    // there. Whether the key had actually been erased was anybody's guess from the outside.
    //
    // Now the list can be empty. The API page shows an explicit empty state instead of an editor
    // bound to nothing, and SettingsRepository.saveApiProfiles clears the mirrored connection keys
    // so the assistant stops using credentials the user just removed.
    fun deleteProfile(idx: Int) {
        if (idx !in profiles.indices) return
        val newList = profiles.toMutableList().also { it.removeAt(idx) }
        if (newList.isEmpty()) {
            url = ""; spec = "openai"; key = ""; selectedModel = ""; editingProfileName = ""
            models = emptyList()
        }
        val newSelected = if (newList.isEmpty()) 0 else selectedProfileIdx.coerceIn(0, newList.size - 1)
        AppScope.io.launch { repo.saveApiProfiles(newList, newSelected) }
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

    // A failed local-model import (e.g. "no .gguf in that zip") should not linger. Clearing it when
    // the Local Model page is (re-)entered means the message shows once, for that attempt, and is
    // gone the next time the user opens the page. (Parity with the Android build.)
    LaunchedEffect(route) {
        if (route == SettingsRoute.LocalModel) lmError = ""
        // Same for the API page's "fetch models" error: a one-off failure (bad URL, network, empty
        // address) shows once and is gone the next time the page is opened, rather than lingering.
        if (route == SettingsRoute.Api) errorText = ""
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

    @Composable
    @NonRestartableComposable
    fun UnsavedChangesDialog() {
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
    if (showUnsavedDialog) { UnsavedChangesDialog() }

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

    // Desktop backup export. beginExport() opens the native save dialog on the AWT thread and hands
    // the chosen File here; the sealed payload is written off the main thread. (On Android this was a
    // CreateDocument launcher writing through contentResolver.openOutputStream.)
    fun runExport(file: File) {
        val password = exportPassword
        val chosenSelection = BackupManager.BackupSelection(
            modules = exportModules,
            noteIds = exportNoteIds,
            taskIds = exportTaskIds,
            conversationIds = exportConversationIds,
            apiProfileNames = exportApiProfileNames
        )
        scope.launch {
            // The full payload — notes (archived included), tasks, note version history, chats,
            // conversations, every attachment, and all settings — sealed as one file. All of it,
            // not just the API key. Written on IO so a large export can't stall the UI; use()
            // closes the cipher stream even if the write throws, which matters because closing is
            // what seals the final frame.
            val result = withContext(Dispatchers.IO) {
                try {
                    file.outputStream().use { out ->
                        BackupManager.exportEncrypted(context, db, repo, out, password, chosenSelection)
                    }
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
    }

    fun beginExport(password: String?) {
        // Ignore a second launch while one is already pending — see exportInFlight above.
        if (exportInFlight) return
        exportInFlight = true
        exportPassword = password
        showExportDialog = false
        val dest = DesktopFiles.saveFile(suggestedName = "lucent-backup.lcb")
        if (dest != null) runExport(dest) else exportInFlight = false
    }

    @Composable
    @NonRestartableComposable
    fun ExportBackupDialog() {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text(S.exportBackupTitle) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(S.exportBackupBody, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(14.dp))

                    // ---- What to include (task 9) ----
                    Text(S.backupChooseWhat, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(4.dp))
                    // Notes and tasks carry a second level: tick the module to include it, or tap
                    // "choose…" to pick individual items. The count in the sub-label is the whole
                    // point of putting it here rather than only inside the sub-menu — "12 of 40"
                    // tells you at a glance that this is not a complete backup, which is the one
                    // fact a partial selection must never hide.
                    BackupModuleRow(
                        label = S.backupModNotes,
                        module = BackupManager.BackupModule.NOTES,
                        selected = exportModules,
                        subLabel = exportNoteIds?.let { S.backupNOfM(it.size, allNotes.size) },
                        onChooseItems = { itemPicker = ExportItemKind.NOTES },
                        onChange = { exportModules = it }
                    )
                    BackupModuleRow(
                        label = S.backupModTasks,
                        module = BackupManager.BackupModule.TASKS,
                        selected = exportModules,
                        subLabel = exportTaskIds?.let { S.backupNOfM(it.size, allTasks.size) },
                        onChooseItems = { itemPicker = ExportItemKind.TASKS },
                        onChange = { exportModules = it }
                    )
                    // Chats carry a second level too (task F1): the tick includes the whole assistant
                    // history, "choose…" narrows it to particular conversations. Offered only when
                    // there is more than nothing to pick.
                    BackupModuleRow(
                        label = S.backupModChats,
                        module = BackupManager.BackupModule.CHATS,
                        selected = exportModules,
                        subLabel = exportConversationIds?.let { S.backupNOfM(it.size, allConversations.size) },
                        onChooseItems = if (allConversations.isNotEmpty()) {
                            { itemPicker = ExportItemKind.CHATS }
                        } else null,
                        onChange = { exportModules = it }
                    )
                    BackupModuleRow(S.backupModSettings, BackupManager.BackupModule.SETTINGS, exportModules) { exportModules = it }
                    // Settings quietly carries the imported font files with it (they are what the
                    // font preference points at — see BackupManager). Unlike model files they are
                    // small, so they get no opt-out of their own, but the size is still quoted:
                    // a module that adds megabytes to the file should say so where it is ticked.
                    val importedFontBytes = remember { FontStore.totalFontBytes(context) }
                    if (importedFontBytes > 0L) {
                        Text(
                            S.backupModSettingsFontsDesc(AttachmentLimits.formatBytes(importedFontBytes)),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(start = 12.dp, bottom = 4.dp)
                        )
                    }
                    // API likewise: include every saved connection, or pick which ones. The drill-in
                    // is gated on realProfiles so its indices match what BackupManager filters on; a
                    // fresh install still on the flat keys just gets the whole-API tick.
                    BackupModuleRow(
                        label = S.backupModApi,
                        module = BackupManager.BackupModule.API,
                        selected = exportModules,
                        subLabel = exportApiProfileNames?.let { S.backupNOfM(it.size, realProfiles.size) },
                        onChooseItems = if (realProfiles.isNotEmpty()) {
                            { itemPicker = ExportItemKind.API }
                        } else null,
                        onChange = { exportModules = it }
                    )
                    BackupModuleRow(S.backupModLocalAssistant, BackupManager.BackupModule.LOCAL_ASSISTANT, exportModules) { exportModules = it }
                    // The model files are offered only when there are some, and always with their
                    // real size attached: "include local model files" means something very
                    // different at 40 MB than at 4 GB, and the number is the only honest way to
                    // say which one this phone is about to do.
                    val modelBytes = remember { LocalModelStore.totalModelBytes(context) }
                    if (modelBytes > 0L) {
                        BackupModuleRow(
                            S.backupModLocalModelFiles,
                            BackupManager.BackupModule.LOCAL_MODEL_FILES,
                            exportModules
                        ) { exportModules = it }
                        Text(
                            S.backupModLocalModelFilesDesc(AttachmentLimits.formatBytes(modelBytes)),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(start = 12.dp, bottom = 4.dp)
                        )
                    }
                    if (BackupManager.BackupSelection(exportModules, exportNoteIds, exportTaskIds, exportConversationIds, exportApiProfileNames).isEmpty) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(S.backupSelectionEmpty, color = DANGER_RED, fontSize = 12.sp)
                    }

                    Spacer(modifier = Modifier.height(16.dp))
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
                    enabled = !BackupManager.BackupSelection(exportModules, exportNoteIds, exportTaskIds, exportConversationIds, exportApiProfileNames).isEmpty,
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
    if (showExportDialog) { ExportBackupDialog() }

    // Second-level picker for individual notes / tasks. Rendered as a sibling of the export dialog
    // rather than nested inside it: an AlertDialog inside an AlertDialog is a platform arrangement
    // with no good behaviour, and this way dismissing the picker returns to an export dialog that
    // never went away and still holds the password the user had already typed.
    itemPicker?.let { kind ->
        // Notes and tasks are keyed on real row ids; chats on the conversation id; API on the
        // profile INDEX carried as a Long, so one generic picker drives all four. In every case a
        // full selection is stored back as null ("everything") rather than an explicit set of every
        // id — that keeps the manifest honest if items are added between choosing and exporting, and
        // it is what makes the "12 of 40" sub-label disappear again when the user re-ticks all.
        val ids: List<Pair<Long, String>> = when (kind) {
            ExportItemKind.NOTES -> allNotes.map { it.id to it.title.ifBlank { S.untitled } }
            ExportItemKind.TASKS -> allTasks.map { it.id to it.title.ifBlank { S.untitledTask } }
            ExportItemKind.CHATS -> allConversations.map { it.id to it.title.ifBlank { S.untitled } }
            ExportItemKind.API -> realProfiles.mapIndexed { i, p -> i.toLong() to p.name.ifBlank { S.backupModApi } }
        }
        val current: Set<Long> = when (kind) {
            ExportItemKind.NOTES -> exportNoteIds
            ExportItemKind.TASKS -> exportTaskIds
            ExportItemKind.CHATS -> exportConversationIds
            ExportItemKind.API -> exportApiProfileNames?.let { names ->
                realProfiles.mapIndexedNotNull { i, p -> if (p.name in names) i.toLong() else null }.toSet()
            }
        } ?: ids.map { it.first }.toSet()
        val title = when (kind) {
            ExportItemKind.NOTES -> S.backupPickNotesTitle
            ExportItemKind.TASKS -> S.backupPickTasksTitle
            ExportItemKind.CHATS -> S.backupPickChatsTitle
            ExportItemKind.API -> S.backupPickApiTitle
        }
        ExportItemPickerDialog(
            title = title,
            items = ids,
            selected = current,
            onDone = { picked ->
                val collapsed: Set<Long>? = if (picked.size == ids.size) null else picked
                when (kind) {
                    ExportItemKind.NOTES -> exportNoteIds = collapsed
                    ExportItemKind.TASKS -> exportTaskIds = collapsed
                    ExportItemKind.CHATS -> exportConversationIds = collapsed
                    ExportItemKind.API -> exportApiProfileNames =
                        collapsed?.let { idx -> idx.mapNotNull { realProfiles.getOrNull(it.toInt())?.name }.toSet() }
                }
                itemPicker = null
            },
            onDismiss = { itemPicker = null }
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

    fun pickImportBackup() {
        val file = DesktopFiles.openFile() ?: return
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                try {
                    file.inputStream().use { it.readBytes() }
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
                // Try the password saved on this device first. On the machine that made the backup,
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

    // --- Step 2: the password prompt (only for a backup made with a custom password) ---
    @Composable
    @NonRestartableComposable
    fun ImportPasswordDialog(bytes: ByteArray) {
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
    pendingImportBytes?.let { ImportPasswordDialog(it) }

    // --- Step 3: show what's in the file, and let them say no ---
    @Composable
    @NonRestartableComposable
    fun ImportPreviewDialog(preview: BackupManager.BackupPreview) {
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
                        if (preview.modelFiles > 0) {
                            BackupContentLine(
                                S.backupModLocalModelFiles,
                                preview.modelFiles,
                                listOf(AttachmentLimits.formatBytes(preview.modelBytes))
                            )
                        }
                        if (preview.fontFiles > 0) {
                            BackupContentLine(
                                S.bkImportedFonts,
                                preview.fontFiles,
                                listOf(AttachmentLimits.formatBytes(preview.fontBytes))
                            )
                        }

                        // ---- Restore only part of it (task 9) ----
                        //
                        // The same module list as the export dialog, but narrowed to what this file
                        // actually contains: offering to restore tasks from a notes-only backup is
                        // a checkbox that can only disappoint. `available` is computed from the
                        // preview's real counts rather than from the manifest's "modules" list, so
                        // a pre-v10 backup — which has no such list — still gets an accurate menu.
                        val available = buildSet {
                            if (preview.notes > 0) add(BackupManager.BackupModule.NOTES)
                            if (preview.tasks > 0) add(BackupManager.BackupModule.TASKS)
                            if (preview.chatMessages > 0 || preview.conversations > 0) {
                                add(BackupManager.BackupModule.CHATS)
                            }
                            if (preview.hasSettings) {
                                add(BackupManager.BackupModule.SETTINGS)
                                add(BackupManager.BackupModule.API)
                                add(BackupManager.BackupModule.LOCAL_ASSISTANT)
                            }
                            if (preview.modelFiles > 0) add(BackupManager.BackupModule.LOCAL_MODEL_FILES)
                        }
                        // Start with everything the file has selected: restoring all of it is what
                        // the button used to do, so that stays the default and opting out is the
                        // deliberate act.
                        LaunchedEffect(preview) {
                            restoreModules = available
                            restoreConversationIds = null
                            restoreApiProfileNames = null
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        Text(S.restoreChooseWhat, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(4.dp))
                        if (BackupManager.BackupModule.NOTES in available) {
                            BackupModuleRow(S.backupModNotes, BackupManager.BackupModule.NOTES, restoreModules) { restoreModules = it }
                        }
                        if (BackupManager.BackupModule.TASKS in available) {
                            BackupModuleRow(S.backupModTasks, BackupManager.BackupModule.TASKS, restoreModules) { restoreModules = it }
                        }
                        if (BackupManager.BackupModule.CHATS in available) {
                            BackupModuleRow(
                                label = S.backupModChats,
                                module = BackupManager.BackupModule.CHATS,
                                selected = restoreModules,
                                subLabel = restoreConversationIds?.let { S.backupNOfM(it.size, preview.conversationList.size) },
                                onChooseItems = if (preview.conversationList.isNotEmpty()) {
                                    { restoreItemPicker = ExportItemKind.CHATS }
                                } else null,
                                onChange = { restoreModules = it }
                            )
                        }
                        if (BackupManager.BackupModule.SETTINGS in available) {
                            BackupModuleRow(S.backupModSettings, BackupManager.BackupModule.SETTINGS, restoreModules) { restoreModules = it }
                            BackupModuleRow(
                                label = S.backupModApi,
                                module = BackupManager.BackupModule.API,
                                selected = restoreModules,
                                subLabel = restoreApiProfileNames?.let { S.backupNOfM(it.size, preview.apiProfileNames.size) },
                                onChooseItems = if (preview.apiProfileNames.isNotEmpty()) {
                                    { restoreItemPicker = ExportItemKind.API }
                                } else null,
                                onChange = { restoreModules = it }
                            )
                            BackupModuleRow(S.backupModLocalAssistant, BackupManager.BackupModule.LOCAL_ASSISTANT, restoreModules) { restoreModules = it }
                        }
                        if (BackupManager.BackupModule.LOCAL_MODEL_FILES in available) {
                            BackupModuleRow(S.backupModLocalModelFiles, BackupManager.BackupModule.LOCAL_MODEL_FILES, restoreModules) { restoreModules = it }
                        }
                        if (restoreModules.isEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(S.backupSelectionEmpty, color = DANGER_RED, fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(S.restoreMergeNote, fontSize = 13.sp)
                }
            },
            confirmButton = {
                Button(
                    enabled = !preview.isEmpty && restoreModules.isNotEmpty(),
                    onClick = {
                        // Which API profiles the restore should carry, and whether that would overflow
                        // the device's cap. Only a MULTI-API backup (one that carries a named profile
                        // list) merges; a legacy single-API backup keeps the whole-API replace by
                        // handing null through. "All" (restoreApiProfileNames == null) is turned into
                        // the explicit full set so it merges too, rather than replacing what's here.
                        val apiSelected = BackupManager.BackupModule.API in restoreModules
                        val isMultiApiBackup = preview.apiProfileNames.isNotEmpty()
                        val effectiveApiNames: Set<String>? = when {
                            !apiSelected -> null
                            !isMultiApiBackup -> null
                            else -> restoreApiProfileNames ?: preview.apiProfileNames.toSet()
                        }
                        // Only NEW names take a slot; a name already saved here is kept by the merge
                        // and costs nothing. If the newcomers wouldn't fit, ask before writing any.
                        val existingNames = com.lucent.app.data.ApiProfiles
                            .parse(savedProfilesJson).map { it.name }.toHashSet()
                        val incomingNew = effectiveApiNames?.count { it !in existingNames } ?: 0
                        val room = com.lucent.app.data.ApiProfiles.MAX - existingNames.size
                        if (incomingNew > room) {
                            apiLimitPrompt = true
                        } else {
                            runRestore(preview, restoreModules, restoreConversationIds, effectiveApiNames)
                        }
                    }
                ) { Text(S.actionRestore) }
            },
            dismissButton = { TextButton(onClick = { importPreview = null }) { Text(S.actionCancel) } }
        )
    }
    importPreview?.let { ImportPreviewDialog(it) }

    // Second-level picker for the restore dialog (task F2) — the import-side twin of the export one.
    // Built from the preview's own lists (not the live database), so it offers exactly what the file
    // contains. Chats key on the conversation id; API keys on the profile INDEX in the file carried as
    // a Long, with the stored selection kept as names — same shape as the export picker.
    restoreItemPicker?.let { kind ->
        importPreview?.let { preview ->
            val ids: List<Pair<Long, String>> = when (kind) {
                ExportItemKind.CHATS -> preview.conversationList.map { it.first to it.second.ifBlank { S.untitled } }
                ExportItemKind.API -> preview.apiProfileNames.mapIndexed { i, name -> i.toLong() to name.ifBlank { S.backupModApi } }
                else -> emptyList()
            }
            val current: Set<Long> = when (kind) {
                ExportItemKind.CHATS -> restoreConversationIds
                ExportItemKind.API -> restoreApiProfileNames?.let { names ->
                    preview.apiProfileNames.mapIndexedNotNull { i, n -> if (n in names) i.toLong() else null }.toSet()
                }
                else -> null
            } ?: ids.map { it.first }.toSet()
            val title = when (kind) {
                ExportItemKind.CHATS -> S.backupPickChatsTitle
                else -> S.backupPickApiTitle
            }
            ExportItemPickerDialog(
                title = title,
                items = ids,
                selected = current,
                onDone = { picked ->
                    val collapsed: Set<Long>? = if (picked.size == ids.size) null else picked
                    when (kind) {
                        ExportItemKind.CHATS -> restoreConversationIds = collapsed
                        ExportItemKind.API -> restoreApiProfileNames =
                            collapsed?.let { idx -> idx.mapNotNull { preview.apiProfileNames.getOrNull(it.toInt()) }.toSet() }
                        else -> {}
                    }
                    restoreItemPicker = null
                },
                onDismiss = { restoreItemPicker = null }
            )
        }
    }

    // API-limit prompt: shown when a confirmed restore's API profiles would exceed ApiProfiles.MAX.
    // Rendered here (not nested inside the preview dialog) so a cancel leaves the preview and its
    // selections intact — the same reason the item picker above lives at this level. The incoming
    // list and remaining room are recomputed from the same inputs the confirm button used, so the two
    // always agree on whether a prompt is warranted.
    if (apiLimitPrompt) {
        importPreview?.let { preview ->
            val existingNames = com.lucent.app.data.ApiProfiles
                .parse(savedProfilesJson).map { it.name }.toHashSet()
            val chosenApi = restoreApiProfileNames ?: preview.apiProfileNames.toSet()
            val incoming = chosenApi.filter { it !in existingNames }
            val room = (com.lucent.app.data.ApiProfiles.MAX - existingNames.size).coerceAtLeast(0)
            ApiImportLimitDialog(
                incoming = incoming,
                canAdd = room,
                max = com.lucent.app.data.ApiProfiles.MAX,
                onDone = { picked -> runRestore(preview, restoreModules, restoreConversationIds, picked) },
                onDismiss = { apiLimitPrompt = false }
            )
        }
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

    @Composable
    @NonRestartableComposable
    fun AppLockSetupDialog() {
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
    if (showAppLockSetup) { AppLockSetupDialog() }

    // --- Disable-lock confirmation (task) ---
    //
    // Turning the lock OFF is a security downgrade, so it is gated exactly like a login: the current
    // password must be entered correctly before the protection is removed. The body spells out what
    // is being given up (anyone can then read everything without a password). A wrong password shows
    // an inline error and changes nothing; only a correct one disables the lock.
    @Composable
    @NonRestartableComposable
    fun AppLockDisableDialog() {
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
    if (showAppLockDisable) { AppLockDisableDialog() }

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
    @Composable
    @NonRestartableComposable
    fun NoRecoveryWarningDialog() {
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
    if (showNoRecoveryWarning) { NoRecoveryWarningDialog() }

    // --- System integration privacy warning (task 6) ---
    @Composable
    @NonRestartableComposable
    fun ShareWarningDialog() {
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
    if (showShareWarning) { ShareWarningDialog() }

    @Composable
    @NonRestartableComposable
    fun ClearDataDialog() {
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
                        // ---- Database rows ----
                        db.noteVersionDao().clearAll()
                        db.noteDao().clearAll()
                        db.taskDao().clearAll()
                        db.chatDao().clearAll()
                        db.chatConversationDao().clearAll()

                        // ---- Preferences ----
                        repo.clearAll()
                        // The usage scores live in their OWN DataStore (lucent_usage), which
                        // repo.clearAll() has never touched — see UsageTracker.clearAll.
                        com.lucent.app.data.UsageTracker.clearAll(appContext)

                        // ---- Files on disk ----
                        // Nothing references anything on disk any more, so sweeping with an
                        // empty "referenced ids" set drops every stored attachment file. If
                        // we skipped this, the files would just sit there until the next
                        // startup ran the orphan sweep — cleaner to free the space now so
                        // the storage figure matches what the user just did.
                        com.lucent.app.data.AttachmentStore.pruneOrphans(appContext, emptySet())
                        // Decrypted attachment previews are copies of the user's files sitting
                        // in cacheDir. The OS clears that eventually; "delete everything" should
                        // not mean "eventually".
                        com.lucent.app.data.AttachmentAccess.clearPreviewCache(appContext)

                        // ---- The imported local models (task 8) ----
                        //
                        // This is the omission that prompted the task, and it was the largest
                        // thing on disk by three orders of magnitude: a wipe could leave four
                        // gigabytes of GGUF behind while reporting that all data had been
                        // cleared. LocalModelStore.deleteAll had existed, correctly written and
                        // documented as "used when wiping all data", and simply was never called
                        // from anywhere — dead code that read as a finished feature.
                        //
                        // Shut the engine down FIRST. The active model may be resident in memory
                        // with its file open; deleting underneath a live llama context risks a
                        // native fault, and on some filesystems the bytes are not freed until the
                        // last handle closes, so the space would not even come back.
                        com.lucent.app.local.LocalLlm.shutdown()
                        com.lucent.app.local.LocalModelStore.deleteAll(appContext)

                        // ---- The imported fonts (font library task) ----
                        //
                        // Same reasoning as the models above, at font scale: imported font files
                        // are user data on disk that repo.clearAll() knows nothing about, so a
                        // wipe must sweep them explicitly. The cached FontFamily objects go too —
                        // the font preference has just been reset to "system" by repo.clearAll(),
                        // and a family built from a deleted file must not survive to be handed
                        // out again if a later import mints a new library.
                        FontStore.deleteAll(appContext)
                        LucentFontResolver.evictAll()

                        // ---- Diagnostics and one-off markers ----
                        com.lucent.app.data.StartupLog.clear(appContext)
                        com.lucent.app.data.StartupLog.setEnabled(false)
                        // A "your database couldn't be decrypted" notice describes a database
                        // that no longer exists, so it must not survive into the fresh app.
                        com.lucent.app.data.DatabaseEncryption.clearLockedNotice(appContext)
                        // Set-aside database copies: a failed decryption parks the old DB beside
                        // the live one rather than deleting it, which is right in normal operation
                        // and wrong here — a wipe that leaves multi-megabyte snapshots of the data
                        // it claims to have erased is not the reinstall it promises.
                        com.lucent.app.data.DatabaseEncryption.purgeSetAsideDatabases(appContext)

                        // ---- OS-level state the app owns ----
                        // The share-sheet entry is a manifest component, not a preference: the
                        // preference has just been reset to its default, so the component has to
                        // follow it or the app keeps advertising an integration it believes is
                        // off. Same class of bug as a reminder outliving its task.
                        com.lucent.app.data.ShareIntegration.setEnabled(appContext, false)
                        // Home-screen widgets keep rendering their last known rows until told
                        // otherwise, so without this the user's launcher would still be showing
                        // tasks from the data they just erased.
                        com.lucent.app.widget.WidgetUpdater.refreshContent(appContext)

                        withContext(Dispatchers.Main) {
                            backupStatus = ""
                            // The assistant holds the current conversation id in memory; the row
                            // it points at is gone, so reset it to the greeting rather than
                            // leaving it observing a conversation that no longer exists.
                            AssistantController.onAllChatsCleared(appContext)
                            LucentToast.show(appContext, S.allDataClearedToast)
                        }
                    }
                }) { Text(S.deleteEverything) }
            },
            dismissButton = { TextButton(onClick = { showClearData = false }) { Text(S.actionCancel) } }
        )
    }
    if (showClearData) { ClearDataDialog() }

    // Clear only notes. After deleting the rows, re-run the orphan sweep, which recomputes the
    // referenced attachment ids from whatever remains (tasks) and frees files that belonged only
    // to notes while keeping any still referenced elsewhere.
    @Composable
    @NonRestartableComposable
    fun ClearNotesDialog() {
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
    if (showClearNotes) { ClearNotesDialog() }

    @Composable
    @NonRestartableComposable
    fun ClearTasksDialog() {
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
    if (showClearTasks) { ClearTasksDialog() }

    // Clear only the assistant's chat history. Chat attachments are stored inline on each message
    // row, so deleting the rows frees them directly — no disk sweep needed. We also reset the
    // assistant's in-memory conversation cache so it doesn't keep showing a deleted conversation.
    // --- API key deletion confirmation (task 1) ---
    // Only acts on the explicit "Delete" press. The index is re-validated inside the click because
    // the profile list can change between opening the dialog and confirming; deleteProfile itself
    // also refuses to remove the last remaining profile.
    @Composable
    @NonRestartableComposable
    fun DeleteApiProfileDialog(idx: Int) {
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
    profilePendingDelete?.let { DeleteApiProfileDialog(it) }

    @Composable
    @NonRestartableComposable
    fun ClearChatsDialog() {
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
    if (showClearChats) { ClearChatsDialog() }

    // --- Local model: per-slot delete confirmation (local-model task) ---
    // Deleting always asks first, for ANY model. If the model being deleted is the resident one the
    // engine is freed FIRST, then the file: unloading after deleting would leave a multi-gigabyte
    // model resident with nothing on disk to reload, which is the worst of both.
    @Composable
    @NonRestartableComposable
    fun DeleteLocalModelDialog(slot: LocalModelStore.ModelSlot) {
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
    lmSlotPendingDelete?.let { DeleteLocalModelDialog(it) }

    // --- Local model: name a model at import time (custom names, task requirement) ---
    // The file is already picked; this captures the label before the copy runs. Cancelling here
    // drops the pending import entirely (nothing was copied yet).
    @Composable
    @NonRestartableComposable
    fun NameLocalModelDialog(file: File) {
        AlertDialog(
            onDismissRequest = { lmPendingImportFile = null },
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
                    lmPendingImportFile = null
                    startLocalImport(file, lmImportName)
                }) { Text(S.lmImportConfirm) }
            },
            dismissButton = { TextButton(onClick = { lmPendingImportFile = null }) { Text(S.actionCancel) } }
        )
    }
    lmPendingImportFile?.let { NameLocalModelDialog(it) }

    // --- Local model: rename an imported model ---
    @Composable
    @NonRestartableComposable
    fun RenameLocalModelDialog(slot: LocalModelStore.ModelSlot) {
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
    lmRenameTarget?.let { RenameLocalModelDialog(it) }

    // --- Imported fonts: per-font delete confirmation (font library task) ---
    // Deleting always asks first. The helper resets the selection to the system font before the
    // file goes, so nothing is ever asked to render from a family whose file is missing.
    @Composable
    @NonRestartableComposable
    fun DeleteImportedFontDialog(slot: FontStore.FontSlot) {
        AlertDialog(
            onDismissRequest = { fontPendingDelete = null },
            title = { Text(S.fontDeleteTitle) },
            text = { Text(S.fontDeleteBody(slot.name.ifBlank { slot.fileName })) },
            confirmButton = {
                TextButton(onClick = {
                    deleteImportedFont(slot)
                    fontPendingDelete = null
                }) { Text(S.actionDelete) }
            },
            dismissButton = { TextButton(onClick = { fontPendingDelete = null }) { Text(S.actionCancel) } }
        )
    }
    fontPendingDelete?.let { DeleteImportedFontDialog(it) }

    // --- Imported fonts: name a font at import time (custom names, task requirement) ---
    // The file is already picked; this captures the label before the copy runs. Cancelling here
    // drops the pending import entirely (nothing was copied yet).
    @Composable
    @NonRestartableComposable
    fun NameImportedFontDialog(file: File) {
        AlertDialog(
            onDismissRequest = { fontPendingImportFile = null },
            title = { Text(S.fontNameTitle) },
            text = {
                Column {
                    Text(S.fontNameBody, color = onGradientMuted, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = fontImportName,
                        onValueChange = { fontImportName = it.take(60) },
                        label = { Text(S.fontNameField) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    fontPendingImportFile = null
                    startFontImport(file, fontImportName)
                }) { Text(S.lmImportConfirm) }
            },
            dismissButton = { TextButton(onClick = { fontPendingImportFile = null }) { Text(S.actionCancel) } }
        )
    }
    fontPendingImportFile?.let { NameImportedFontDialog(it) }

    // --- Local model: warn before turning "use local model" ON ---
    // Enabling local mode freezes the cloud API and loads a multi-gigabyte model into RAM, so it
    // spells out the consequences first: the API stops being called, memory use is high, quitting the
    // app mid-reply interrupts the answer, and quitting frees that memory. Confirming enables it.
    @Composable
    @NonRestartableComposable
    fun ConfirmUseLocalDialog() {
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
    if (lmConfirmUseLocalOn) { ConfirmUseLocalDialog() }

    // Warn before letting the on-device model call tools: it can be slower and, on a small model,
    // unreliable. Confirming enables it; cancelling leaves it off.
    @Composable
    @NonRestartableComposable
    fun ConfirmToolsDialog() {
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
    if (lmConfirmToolsOn) { ConfirmToolsDialog() }

    // Warn before switching the on-device model to the GPU: faster on some phones, but Vulkan
    // drivers vary and it can be unstable; it also needs the GPU backend compiled into the build.
    @Composable
    @NonRestartableComposable
    fun ConfirmGpuDialog() {
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
    if (lmConfirmGpuOn) { ConfirmGpuDialog() }

    // --- Warn before letting a reply keep running after the app leaves the screen (task 2) ---
    // Same shape as the tools/GPU warnings, for the same reason: this is the one setting that lets
    // Lucent hold gigabytes of RAM while the user is doing something else entirely, so it is opt-in
    // behind a plain description of that cost. Turning it back OFF never asks.
    @Composable
    @NonRestartableComposable
    fun ConfirmBackgroundDialog() {
        AlertDialog(
            onDismissRequest = { lmConfirmBackgroundOn = false },
            title = { Text(S.lmBackgroundWarnTitle) },
            text = { Text(S.lmBackgroundWarnBody) },
            confirmButton = {
                TextButton(onClick = {
                    lmConfirmBackgroundOn = false
                    AppScope.io.launch { repo.setLocalBackgroundReplyEnabled(true) }
                }) { Text(S.lmWarnEnableAnyway) }
            },
            dismissButton = { TextButton(onClick = { lmConfirmBackgroundOn = false }) { Text(S.actionCancel) } }
        )
    }
    if (lmConfirmBackgroundOn) { ConfirmBackgroundDialog() }

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
    // Desktop selective export: launchExport() opens the native save dialog on the AWT thread and
    // passes the chosen File here; the staged bytes are written off the main thread. (On Android this
    // was a CreateDocument launcher writing through contentResolver.openOutputStream.)
    fun runSelectiveExport(file: File) {
        val bytes = pendingExportBytes
        pendingExportBytes = null
        if (bytes != null) {
            scope.launch {
                withContext(Dispatchers.IO) {
                    file.outputStream().use { out -> out.write(bytes) }
                }
                backupStatus = S.exportedSelected
            }
        }
    }

    // Turn a chosen subset + format into bytes and kick off the file picker. Shared by the notes and
    // tasks export screens below so the format handling lives in exactly one place. When [asZip] is
    // set the bytes are a .zip bundle (document + attachment files) and the suggested name gets a
    // .zip extension instead of the format's own; the writer writes whatever bytes it's given, so the
    // extension is all that needs to change.
    fun launchExport(
        fileStem: String,
        bytes: ByteArray,
        format: com.lucent.app.data.ExportFormat,
        asZip: Boolean = false
    ) {
        pendingExportBytes = bytes
        pendingExportName = "$fileStem.${if (asZip) "zip" else format.extension}"
        exportKind = null
        val dest = DesktopFiles.saveFile(suggestedName = pendingExportName)
        if (dest != null) runSelectiveExport(dest) else pendingExportBytes = null
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
        modifier = Modifier.fillMaxWidth().verticalScroll(rootScroll).hazeSource(state = LocalHazeState.current).padding(16.dp).padding(bottom = LocalBottomBarInset.current)
    ) {
        @Composable
        @NonRestartableComposable
        fun RootPage() {
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

        @Composable
        @NonRestartableComposable
        fun LanguagePage() {
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
            // Font sits inline here, parallel to the language picker above rather than behind a
            // further tap: a typeface is a writing/language choice as much as a visual one. The app
            // bundles no fonts (font library task): out of the box it follows the platform font,
            // and every other row is a font the user imported, shown under the name they gave it
            // and drawn in its own face so the list doubles as a live preview. Selecting saves
            // immediately; the trailing icon deletes an imported font (after confirming).
            Column(modifier = Modifier.fillMaxWidth().frostedGlass().padding(16.dp)) {
                Text(S.settingsFontTitle, color = onGradient, fontSize = 16.sp)
                Spacer(modifier = Modifier.height(2.dp))
                Text(S.settingsFontSub, color = onGradientMuted, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(6.dp))

                // The system-default row. Also selected when the saved key is a dangling id (a
                // state only reachable by hand-editing storage): the app *renders* the system font
                // then, and the radio must tell the truth about what is on screen.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable {
                        AppScope.io.launch { repo.setFont(SYSTEM_FONT_KEY) }
                    }
                ) {
                    RadioButton(
                        selected = savedFont == SYSTEM_FONT_KEY || importedFonts.none { it.id == savedFont },
                        onClick = { AppScope.io.launch { repo.setFont(SYSTEM_FONT_KEY) } }
                    )
                    Text(
                        S.fontSystemLabel,
                        color = onGradient,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(start = 10.dp)
                    )
                }

                // One row per imported font: radio + the user's name for it + delete. Same anatomy
                // as the system row, plus the trailing delete — an imported font is the user's to
                // remove, the platform default is not.
                importedFonts.forEach { slot ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable {
                            AppScope.io.launch { repo.setFont(slot.id) }
                        }
                    ) {
                        RadioButton(
                            selected = savedFont == slot.id,
                            onClick = { AppScope.io.launch { repo.setFont(slot.id) } }
                        )
                        Text(
                            slot.name.ifBlank { slot.fileName },
                            color = onGradient,
                            fontFamily = LucentFontResolver.resolve(context, slot.id),
                            fontSize = 16.sp,
                            modifier = Modifier.weight(1f).padding(start = 10.dp)
                        )
                        IconButton(onClick = { fontPendingDelete = slot }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = S.fontDeleteA11y,
                                tint = onGradientMuted
                            )
                        }
                    }
                }
                if (importedFonts.isEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(S.fontNoneImportedHint, color = onGradientMuted, fontSize = 12.sp)
                }

                Spacer(modifier = Modifier.height(12.dp))
                if (fontImporting) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(S.fontImporting, color = onGradientMuted, fontSize = 13.sp)
                    }
                } else if (fontCanImportMore) {
                    GlassButton(
                        text = S.fontImportButton,
                        icon = Icons.Default.Add,
                        onClick = { pickImportFont() }
                    )
                } else {
                    Text(S.fontSlotsFullHint(FontStore.MAX_FONTS), color = onGradientMuted, fontSize = 12.sp)
                }
                if (fontError.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(fontError, color = Color(0xFFFFC1C1), fontSize = 13.sp)
                }
            }
        }

        @Composable
        @NonRestartableComposable
        fun AssistantPage() {
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

        @Composable
        @NonRestartableComposable
        fun LocalModelPage() {
            BackHeader(S.settingsLocalModelTitle) { route = SettingsRoute.Assistant }

            if (!LocalLlm.isSupported()) {
                // The .so wasn't packaged for this ABI (or failed to load). Everything below
                // would be a dead end, so say why once, plainly, instead of offering buttons
                // that can only disappoint.
                Column(modifier = Modifier.fillMaxWidth().frostedGlass().padding(16.dp)) {
                    Text(S.lmUnsupportedAbiNote, color = onGradientMuted, fontSize = 13.sp)
                }
            } else {
                // =========================================================================
                // The master switch — and, until it is on, nothing else (task 15)
                // =========================================================================
                //
                // The page used to open with the importer: pick a multi-gigabyte file first,
                // then decide whether you wanted the feature at all. That is backwards. Importing
                // is the expensive, irreversible-feeling step, and asking for it before the user
                // has said yes to anything makes the whole page read as a commitment. Worse, the
                // enable switch sat *below* the importer and was disabled until a model existed,
                // so the one control that explains what the page is for was the last thing you
                // reached and the only one you couldn't touch.
                //
                // So the order is now the order of the decision: do you want the assistant to run
                // on this device — yes — now here is what that involves. Everything below the
                // switch is hidden while it is off, which also settles task 1 more firmly than
                // greying would: a control that isn't there cannot be operated by accident, and
                // the page stops presenting four questions when only the first one is live.
                Column(modifier = Modifier.fillMaxWidth().frostedGlass().padding(16.dp)) {
                    // The experimental badge belongs to the FEATURE, so it moved here from the
                    // models card (task 1) — it is the first thing read by someone deciding
                    // whether to turn this on, rather than a note attached to the importer.
                    Text(S.lmExperimentalNote, color = onGradient, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(S.lmUseLocalToggle, color = onGradient, fontSize = 16.sp)
                            Text(S.lmUseLocalToggleDesc, color = onGradientMuted, fontSize = 13.sp)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        // Switchable ON with no model imported, which the old build forbade.
                        // It has to be: the importer only appears once this is on, so gating it
                        // on a model that can only be imported afterwards was a deadlock. If it
                        // is on with no model the page says so (lmNeedModelNotice below) and the
                        // assistant answers with a clear "no model" error rather than silently
                        // falling back to the cloud API.
                        Switch(
                            checked = localModelEnabled,
                            onCheckedChange = { on ->
                                if (on) lmConfirmUseLocalOn = true
                                else AppScope.io.launch { repo.setLocalModelEnabled(false) }
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    // Task 16: say plainly what this assistant is not. A user who attaches a
                    // photo and gets a reply that ignores it will conclude the model is stupid;
                    // the truth is that local mode is text-only and nothing about a chat box
                    // with a paperclip in it hints at that.
                    Text(S.lmTextOnlyNote, color = onGradientMuted, fontSize = 12.sp)
                    if (!localModelEnabled) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(S.lmEnableToConfigureNote, color = onGradientMuted, fontSize = 12.sp)
                    }
                }

                if (localModelEnabled) {
                    Spacer(modifier = Modifier.height(12.dp))

                    // ---------------------------------------------------------------
                    // Models: import, choose the active one, rename, delete
                    // ---------------------------------------------------------------
                    Column(modifier = Modifier.fillMaxWidth().frostedGlass().padding(16.dp)) {
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
                            // Local mode is on with nothing to run: the single most confusing
                            // state this feature can be in, so it is named rather than implied.
                            Text(S.lmNeedModelNotice, color = onGradient, fontSize = 14.sp)
                        } else {
                            // One row per imported model: a radio picks the ACTIVE model (only it
                            // is ever loaded), its name and size are shown, and each has rename +
                            // delete. Switching the radio releases the loaded model right away.
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

                        Spacer(modifier = Modifier.height(12.dp))

                        if (lmImporting) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator()
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(S.lmImporting, color = onGradientMuted, fontSize = 13.sp)
                            }
                        } else if (lmCanImportMore) {
                            GlassButton(
                                text = S.lmImportButton,
                                icon = Icons.Default.Add,
                                onClick = { pickLocalModel() }
                            )
                        } else {
                            Text(S.lmSlotsFullHint(LocalModelStore.MAX_MODELS), color = onGradientMuted, fontSize = 12.sp)
                        }
                        if (lmError.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(lmError, color = Color(0xFFFFC1C1), fontSize = 13.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // The reset rule is stated ONCE, above both switches it governs (task 1),
                    // rather than repeated inside each card. It is a property of the pair.
                    Text(
                        S.lmSubTogglesResetNote,
                        color = onGradientMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // ---- Opt-in: let the on-device model act on notes/tasks ----
                    Column(modifier = Modifier.fillMaxWidth().frostedGlass().padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(S.lmToolsToggle, color = onGradient, fontSize = 16.sp)
                                Text(S.lmToolsToggleDesc, color = onGradientMuted, fontSize = 13.sp)
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Switch(
                                checked = localToolsEnabled,
                                onCheckedChange = { on ->
                                    if (on) lmConfirmToolsOn = true
                                    else AppScope.io.launch { repo.setLocalToolsEnabled(false) }
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // ---- Opt-in: run the model on the GPU instead of the CPU ----
                    // No "(experimental)" on this one any more (task 7): the page already opens
                    // with an experimental badge on the feature, and stamping the word onto a
                    // sub-option as well starts to read as noise rather than as a warning.
                    Column(modifier = Modifier.fillMaxWidth().frostedGlass().padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(S.lmGpuToggle, color = onGradient, fontSize = 16.sp)
                                Text(S.lmGpuToggleDesc, color = onGradientMuted, fontSize = 13.sp)
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Switch(
                                checked = localGpuEnabled,
                                onCheckedChange = { on ->
                                    if (on) lmConfirmGpuOn = true
                                    else AppScope.io.launch { repo.setLocalGpuEnabled(false) }
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // ---- Opt-in: keep generating after the app leaves the foreground ----
                    // Hidden with the rest of this section when local mode is off, because it
                    // describes something only the local model does (task 2).
                    Column(modifier = Modifier.fillMaxWidth().frostedGlass().padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(S.lmBackgroundToggle, color = onGradient, fontSize = 16.sp)
                                Text(S.lmBackgroundToggleDesc, color = onGradientMuted, fontSize = 13.sp)
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Switch(
                                checked = localBackgroundReply,
                                onCheckedChange = { on ->
                                    if (on) lmConfirmBackgroundOn = true
                                    else AppScope.io.launch { repo.setLocalBackgroundReplyEnabled(false) }
                                }
                            )
                        }
                    }
                }
            }
        }

        @Composable
        @NonRestartableComposable
        fun PersonalizationPage() {
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
                GlassButton(text = S.actionSave, onClick = { persistAssistantSettings() })
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

        @Composable
        @NonRestartableComposable
        fun MemoryPage() {
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
                // Local mode changes what this page is allowed to offer (task 8): an on-device
                // model works from a short prompt, so the high tier is withdrawn while it is on.
                // Saying that here, before the rows, means the greyed row below is explained
                // before it is touched rather than only after.
                if (localModelEnabled) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(S.memoryLocalTierNote, color = onGradientMuted, fontSize = 12.sp)
                }

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
                // The high tier stays visible but greyed, and — importantly — stays TAPPABLE.
                // A control that simply ignores touches teaches the user nothing except that the
                // app is broken; this one answers with a bottom toast explaining why it's off and
                // that their previous choice is being held for them (task 8: a toast, never a
                // dialog — a modal for "you can't do that" is a punishment, not an explanation).
                MemoryTierRow(
                    selected = current == MemoryTier.HIGH,
                    title = S.memoryHighTitle,
                    detail = S.memoryHighDesc,
                    onGradient = onGradient,
                    onGradientMuted = onGradientMuted,
                    dimmed = localModelEnabled,
                    onClick = {
                        if (localModelEnabled) LucentToast.show(context, S.memoryHighLocalDisabledHint)
                        else AppScope.io.launch { repo.setMemoryTier(MemoryTier.HIGH.key) }
                    }
                )
            }
        }

        @Composable
        @NonRestartableComposable
        fun NetworkPage() {
            BackHeader(S.settingsNetworkTitle) { route = SettingsRoute.Assistant }

            // Web search toggle: lets the cloud assistant look things up online.
            //
            // Unavailable while the local model is on (tasks 3/8) — it answers with no network
            // at all, so a web-search switch in that mode would be a promise the app cannot
            // keep. The row is dimmed rather than removed: hiding it would leave the user
            // wondering where their setting went, and the value they had is coming back the
            // moment local mode is switched off (SettingsRepository parks it).
            val webSearchLocked = localModelEnabled
            Column(modifier = Modifier.fillMaxWidth().frostedGlass().padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        // The whole row answers when it's locked, so a tap anywhere near the
                        // switch — not only exactly on it — gets the explanation.
                        .then(
                            if (webSearchLocked) Modifier.clickable {
                                LucentToast.show(context, S.webSearchLocalDisabledHint)
                            } else Modifier
                        )
                ) {
                    Column(modifier = Modifier.weight(1f).alpha(if (webSearchLocked) 0.38f else 1f)) {
                        Text(S.webSearchTitle, color = onGradient, fontSize = 16.sp)
                        Text(
                            S.webSearchDesc,
                            color = onGradientMuted,
                            fontSize = 13.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = savedWebSearch && !webSearchLocked,
                        enabled = !webSearchLocked,
                        onCheckedChange = { on -> AppScope.io.launch { repo.setWebSearchEnabled(on) } }
                    )
                }
                if (webSearchLocked) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(S.webSearchLocalDisabledHint, color = onGradientMuted, fontSize = 12.sp)
                }
            }
        }

        @Composable
        @NonRestartableComposable
        fun ApiPage() {
            BackHeader(S.settingsApiTitle) { route = SettingsRoute.Assistant }

            // When local model mode is on, the cloud API is FROZEN — the assistant answers
            // on-device and never calls the API. Say so plainly at the top of the page, with a
            // one-tap way back to the Local model page to turn it off. That link matters more
            // than usual now that the rest of the page is hidden behind the freeze: it is the
            // only route out, so it has to be right here in the explanation.
            if (localModelEnabled) {
                Column(modifier = Modifier.fillMaxWidth().frostedGlass().padding(16.dp)) {
                    Text(S.apiFrozenTitle, color = onGradient, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(S.apiFrozenBody, color = onGradientMuted, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    // Glass, like every other page-level button in Settings. This was one of
                    // the last Material 3 controls left on a page made entirely of glass.
                    GlassButton(text = S.apiFrozenManage, onClick = { route = SettingsRoute.LocalModel })
                }
            }

            // Task 18: while the freeze is on, the blocks below are HIDDEN rather than shown
            // greyed. They were disabled before, which left a full API editor sitting under a
            // banner saying it would never be used — fields you could type in, a Save button you
            // could not press, a model list that would not load. Disabling communicates "not
            // now"; the honest message here is "not while this mode is on", and the way a screen
            // says that is by not offering the controls at all. Nothing is lost: every profile,
            // key and model stays saved, and flipping local mode off brings this page back
            // exactly as it was.
            if (!localModelEnabled) {

            // ---- API Selection: pick which saved profile is active ----
            Column(modifier = Modifier.fillMaxWidth().frostedGlass().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(S.apiSelectionTitle, color = onGradient, modifier = Modifier.weight(1f))
                    Text("${profiles.size}/${com.lucent.app.data.ApiProfiles.MAX}", color = onGradientMuted, fontSize = 13.sp)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(S.apiSelectionDesc(com.lucent.app.data.ApiProfiles.MAX), color = onGradientMuted, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(8.dp))
                if (profiles.isEmpty()) {
                    // Deleting the last API is allowed now (task 6), so this state is reachable
                    // and has to be a place the user can stand: it names what happened and both
                    // ways forward, rather than an empty card that looks like a rendering bug.
                    Text(S.apiNoneTitle, color = onGradient, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(S.apiNoneBody, color = onGradientMuted, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                }
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
                        // The delete icon is shown on every row, the only profile included, and
                        // every delete goes through the confirmation dialog below first — there
                        // is no quiet path that removes a saved key (task 6).
                        IconButton(onClick = { profilePendingDelete = idx }) {
                            Icon(Icons.Default.Delete, contentDescription = S.apiDeleteA11y, tint = onGradientMuted)
                        }
                    }
                }
                if (profiles.size < com.lucent.app.data.ApiProfiles.MAX) {
                    Spacer(modifier = Modifier.height(4.dp))
                    GlassButton(text = S.apiAddButton, icon = Icons.Default.Add, onClick = { addProfile() })
                }
            }

            // Nothing selected means nothing to edit, so the editor block is hidden entirely
            // rather than bound to a phantom profile (task 6).
            if (profiles.isNotEmpty()) {

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
                // No `enabled = !localModelEnabled` guard any more: this whole block is hidden
                // while the API is frozen (task 18), so the only way to reach this button is
                // with local mode off.
                GlassButton(text = S.fetchModels, onClick = {
                    if (url.trim().isEmpty()) {
                        // No address yet: say so plainly instead of letting the HTTP client throw a
                        // technical "malformed URL" style error the user can't act on.
                        errorText = S.apiUrlRequired
                    } else {
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
                    }
                })

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
                    GlassButton(
                        text = if (selectedModel.isBlank()) S.chooseModel else selectedModel,
                        onClick = { menuExpanded = true },
                        enabled = models.isNotEmpty()
                    )
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
                // assistant uses it right away.
                GlassButton(text = S.saveApi, onClick = { saveActiveProfile(selectedProfileIdx) })
            }
            } // profiles.isNotEmpty()
            } // !localModelEnabled
        }

        @Composable
        @NonRestartableComposable
        fun AppearancePage() {
            BackHeader(S.settingsAppearanceTitle) { route = SettingsRoute.Root }
            // Two hierarchical entries, mirroring the Assistant screen's structure. (Font moved
            // to the Language screen — it's as much a writing choice as a visual one.)
            NavCard(S.settingsThemeTitle, S.settingsThemeSub) { route = SettingsRoute.Theme }
            Spacer(modifier = Modifier.height(12.dp))
            NavCard(S.settingsBackgroundTitle, S.settingsBackgroundSub) { route = SettingsRoute.Background }
        }

        @Composable
        @NonRestartableComposable
        fun ThemePage() {
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

        @Composable
        @NonRestartableComposable
        fun BackgroundPage() {
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
            // The palette list only takes visible effect while the drifting effect is ON, so
            // with the switch off every colour row is disabled and greyed out rather than
            // pretending to work: the radio buttons go grey, the swatches and labels fade, and
            // a tap anywhere on a row answers with a toast at the bottom of the screen saying
            // the drifting background isn't on — instead of silently changing a setting whose
            // result can't be seen (fix task).
            val paletteEnabled = backgroundAnimationEnabled
            // One alpha for everything in a disabled row, so swatch and label fade together.
            val paletteAlpha = if (paletteEnabled) 1f else 0.38f
           fun pickPalette(name: String) {
                if (paletteEnabled) {
                    AppScope.io.launch { repo.setPalette(name) }
                } else {
                    LucentToast.show(context, S.backgroundPaletteDisabledHint)
                }
            }
            Column(modifier = Modifier.fillMaxWidth().frostedGlass().padding(16.dp)) {
                // Auto-cycle: rotates through every palette over time. Its swatch previews the
                // spread of colours it moves through.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    // The whole row stays tappable while disabled so the tap can EXPLAIN itself
                    // (the toast) — a dead row that ignores touches just looks broken.
                    modifier = Modifier.fillMaxWidth().clickable { pickPalette(PALETTE_CYCLE) }
                ) {
                    RadioButton(
                        selected = savedPalette == PALETTE_CYCLE,
                        enabled = paletteEnabled,
                        onClick = { pickPalette(PALETTE_CYCLE) }
                    )
                    Box(modifier = Modifier.alpha(paletteAlpha)) {
                        PaletteSwatch(LucentPalette.entries.map { it.colors.first() })
                    }
                    Text(
                        S.paletteCycleAuto,
                        color = onGradient.copy(alpha = onGradient.alpha * paletteAlpha),
                        modifier = Modifier.padding(start = 10.dp)
                    )
                }

                // Palettes grouped by kind, each with a small colour preview.
                listOf(
                    S.paletteGroupSolid to PaletteGroup.SOLID,
                    S.paletteGroupGradient to PaletteGroup.GRADIENT,
                    S.paletteGroupClassic to PaletteGroup.CLASSIC
                ).forEach { (heading, group) ->
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        heading,
                        color = onGradientMuted.copy(alpha = onGradientMuted.alpha * paletteAlpha),
                        fontSize = 13.sp
                    )
                    LucentPalette.entries.filter { it.group == group }.forEach { p ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { pickPalette(p.name) }
                        ) {
                            RadioButton(
                                selected = savedPalette == p.name,
                                enabled = paletteEnabled,
                                onClick = { pickPalette(p.name) }
                            )
                            Box(modifier = Modifier.alpha(paletteAlpha)) {
                                PaletteSwatch(p.colors)
                            }
                            Text(
                                p.label,
                                color = onGradient.copy(alpha = onGradient.alpha * paletteAlpha),
                                modifier = Modifier.padding(start = 10.dp)
                            )
                        }
                    }
                }
            }
        }

        @Composable
        @NonRestartableComposable
        fun EditorPage() {
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

        @Composable
        @NonRestartableComposable
        fun SecurityPage() {
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

                // ---- Windows Hello ----
                // Shown only once the lock is on AND this machine actually has Hello set up, so it
                // reads as a follow-on choice to "App lock" rather than a dead control. Turning the
                // lock off hides this again (and clears the opt-in via setAppLock).
                if (appLockOn && helloAvailable) {
                    Spacer(modifier = Modifier.height(20.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(S.helloTitle, color = onGradient)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(S.helloDesc, color = onGradientMuted, fontSize = 13.sp)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Switch(
                            checked = helloEnabled,
                            onCheckedChange = { turnOn -> scope.launch { repo.setAppLockHelloEnabled(turnOn) } }
                        )
                    }
                }

            }
        }

        @Composable
        @NonRestartableComposable
        fun PrivacyPage() {
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
                // Turning logging ON asks for consent first — it can capture technical detail and
                // the text you type to the assistant. Turning it OFF is immediate (no dialog).
                var showLoggingConsent by remember { mutableStateOf(false) }
                if (showLoggingConsent) {
                    AlertDialog(
                        onDismissRequest = { showLoggingConsent = false },
                        title = { Text(S.loggingConsentTitle) },
                        text = { Text(S.loggingConsentBody) },
                        confirmButton = {
                            TextButton(onClick = {
                                showLoggingConsent = false
                                scope.launch { repo.setStartupLoggingEnabled(true) }
                                StartupLog.setEnabled(true)
                                // Log lines stay English on purpose so a bug report reads the same
                                // regardless of the UI language at the time.
                                StartupLog.event(context, "Logging enabled from Settings")
                            }) { Text(S.loggingConsentConfirm) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showLoggingConsent = false }) { Text(S.actionCancel) }
                        }
                    )
                }
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
                            if (turnOn) {
                                showLoggingConsent = true          // enable only after consent
                            } else {
                                scope.launch { repo.setStartupLoggingEnabled(false) }
                                StartupLog.setEnabled(false)
                            }
                        }
                    )
                }
                if (startupLoggingOn) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row {
                        GlassButton(text = S.exportLogs, onClick = { exportLogs("lucent-startup-log.txt") })
                        Spacer(modifier = Modifier.width(12.dp))
                        GlassButton(text = S.clearLogs, onClick = {
                            StartupLog.clear(context)
                            // Toast rather than the Data page's backupStatus line, which isn't
                            // shown on this page (task 5 moved these controls here).
                            LucentToast.show(context, S.logsClearedToast)
                        })
                    }
                }
            }
        }

        @Composable
        @NonRestartableComposable
        fun DataPage() {
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
                        GlassButton(text = S.importBackup, onClick = { pickImportBackup() })
                        Spacer(modifier = Modifier.width(12.dp))
                        GlassButton(text = S.actionDismiss, onClick = {
                            com.lucent.app.data.DatabaseEncryption.clearLockedNotice(context)
                            lockedDismissed = true
                        })
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
                    GlassButton(text = S.exportBackup, onClick = { showExportDialog = true })
                    Spacer(modifier = Modifier.width(12.dp))
                    // Allow any file so a beginner can always locate their .lcb even when the
                    // device reports an unexpected MIME type for it. The import path validates the
                    // content itself — it requires a Lucent .lcb envelope and rejects anything else
                    // with a clear message (legacy ZIP/JSON support has been removed, task 5).
                    GlassButton(text = S.importBackup, onClick = { pickImportBackup() })
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
                GlassButton(
                    text = S.chooseTasksToExport,
                    onClick = { exportKind = ExportKind.TASKS },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                GlassButton(
                    text = S.chooseNotesToExport,
                    onClick = { exportKind = ExportKind.NOTES },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(20.dp))
                Text(S.dangerZone, color = onGradient)
                Spacer(modifier = Modifier.height(8.dp))
                // Targeted clears, then the full wipe. All four are identical full-width glass
                // pills in the danger tint; each asks for confirmation. Labels are kept short so
                // they fit on one line while still making each button's function obvious.
                //
                // These are the buttons task 11 was pointing at: they were solid Material red,
                // the only fully opaque objects on a page otherwise made of glass. They keep the
                // red — a destructive action should look destructive — but wear it as a tint on
                // the app's own material instead of arriving in someone else's.
                GlassButton(
                    text = S.clearNotesBtn,
                    onClick = { showClearNotes = true },
                    danger = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                GlassButton(
                    text = S.clearTasksBtn,
                    onClick = { showClearTasks = true },
                    danger = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                GlassButton(
                    text = S.clearChatsBtn,
                    onClick = { showClearChats = true },
                    danger = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                GlassButton(
                    text = S.clearAllDataBtn,
                    onClick = { showClearData = true },
                    danger = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        when (route) {
            SettingsRoute.Root -> RootPage()

            SettingsRoute.Language -> LanguagePage()

            SettingsRoute.Assistant -> AssistantPage()

            SettingsRoute.LocalModel -> LocalModelPage()

            SettingsRoute.Personalization -> PersonalizationPage()

            SettingsRoute.Memory -> MemoryPage()

            SettingsRoute.Network -> NetworkPage()

            SettingsRoute.Api -> ApiPage()

            SettingsRoute.Appearance -> AppearancePage()

            SettingsRoute.Theme -> ThemePage()

            SettingsRoute.Background -> BackgroundPage()

            SettingsRoute.Editor -> EditorPage()

            SettingsRoute.Security -> SecurityPage()

            SettingsRoute.Privacy -> PrivacyPage()

            SettingsRoute.Data -> DataPage()
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
    onClick: () -> Unit,
    // [dimmed] fades the row to show it can't be chosen right now, WITHOUT making it inert: the
    // click still fires so the caller can say why (task 8). Disabling the controls outright would
    // have been less code and a worse answer — the user's question is "why is this grey?", and only
    // a control that still responds can answer it.
    dimmed: Boolean = false
) {
    val fade = if (dimmed) 0.38f else 1f
    Row(modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 4.dp)) {
        // Kept ENABLED even when dimmed. A disabled RadioButton can swallow the touch before the
        // row's clickable ever sees it, which is exactly the dead control this is trying to avoid;
        // the fade carries the "unavailable" meaning and onClick carries the explanation.
        RadioButton(selected = selected && !dimmed, onClick = onClick, modifier = Modifier.alpha(fade))
        Column(modifier = Modifier.padding(start = 4.dp, top = 4.dp).alpha(fade)) {
            Text(title, color = onGradient)
            Text(detail, color = onGradientMuted, fontSize = 12.sp)
        }
    }
}

/**
 * One selectable section in the backup / restore dialogs (task 9).
 *
 * A plain labelled checkbox, with the whole row clickable rather than just the box. That is not
 * politeness — these rows sit in a scrolling dialog on a phone, a checkbox is below the size anyone
 * can hit reliably while a list is still settling, and a mis-tap here is the difference between
 * backing up your API keys and not.
 *
 * The caller owns the set and is handed a new one, so the same component drives both dialogs without
 * either sharing state with the other — an export selection must never leak into a restore, since
 * the same words mean opposite things in the two directions.
 */
@Composable
private fun BackupModuleRow(
    label: String,
    module: BackupManager.BackupModule,
    selected: Set<BackupManager.BackupModule>,
    subLabel: String? = null,
    onChooseItems: (() -> Unit)? = null,
    // Kept last so call sites can pass it as a trailing lambda; with it in fourth position those
    // trailing lambdas were binding to onChooseItems instead, which is what broke the release build.
    onChange: (Set<BackupManager.BackupModule>) -> Unit
) {
    val checked = module in selected
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .clickable { onChange(if (checked) selected - module else selected + module) }
        ) {
            Checkbox(
                checked = checked,
                // Null, not a duplicate handler: the row above already owns the toggle, and letting
                // the box handle its own tap as well is how a fast double-tap cancels itself.
                onCheckedChange = null
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(label, fontSize = 14.sp)
                // Only shown for a partial selection, and it is the reason the second level is
                // safe to offer at all: a backup missing most of your notes must say so on the
                // screen where you press Export, not only inside a sub-menu you may never reopen.
                if (subLabel != null) Text(subLabel, fontSize = 11.sp)
            }
        }
        // The drill-in is a separate hit target from the tick, because they do opposite things:
        // one decides whether this section travels at all, the other decides what is in it.
        // Offered only while the module is actually ticked — choosing which notes to include in a
        // section you have just excluded is a menu that cannot mean anything.
        if (onChooseItems != null && checked) {
            TextButton(onClick = onChooseItems) { Text(S.backupChooseItems, fontSize = 13.sp) }
        }
    }
}

/** Which list the second-level backup picker is showing. */
private enum class ExportItemKind { NOTES, TASKS, CHATS, API }

/**
 * The second-level picker: every note (or task), each with a tick, plus all/none shortcuts.
 *
 * Deliberately a flat list of titles and nothing else. This is a dialog for answering "is this one
 * in or out", and previews, dates or tags would make each row taller without making that question
 * easier — on a list of two hundred notes, height is the scarce resource. Titles are shown exactly
 * as stored, with the same "(untitled)" fallback the rest of the app uses, so an item is never
 * represented by a blank row that cannot be identified or reasoned about.
 */
@Composable
private fun ExportItemPickerDialog(
    title: String,
    items: List<Pair<Long, String>>,
    selected: Set<Long>,
    onDone: (Set<Long>) -> Unit,
    onDismiss: () -> Unit
) {
    var draft by remember(items) { mutableStateOf(selected) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Row {
                    TextButton(onClick = { draft = items.map { it.first }.toSet() }) {
                        Text(S.selectAll, fontSize = 13.sp)
                    }
                    TextButton(onClick = { draft = emptySet() }) {
                        Text(S.clearAllSelection, fontSize = 13.sp)
                    }
                }
                Text(S.backupNOfM(draft.size, items.size), fontSize = 12.sp)
                Spacer(modifier = Modifier.height(8.dp))
                if (items.isEmpty()) {
                    Text(S.backupNothingToPick, fontSize = 13.sp)
                } else {
                    // Lazy, not a scrolled Column: a database with a few hundred notes would
                    // otherwise compose every row up front to open a dialog.
                    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        items(items, key = { it.first }) { (id, label) ->
                            val checked = id in draft
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { draft = if (checked) draft - id else draft + id }
                                    .padding(vertical = 2.dp)
                            ) {
                                Checkbox(checked = checked, onCheckedChange = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(label, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(draft) }) { Text(S.actionDone) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(S.actionCancel) } }
    )
}

/**
 * The import-time cap resolver: when the API profiles a restore would add don't all fit under
 * [ApiProfiles.MAX], this asks which of the newcomers to keep rather than silently dropping the
 * overflow. [incoming] is only the profiles that would actually take a new slot — a name already
 * saved on the device is kept by the merge and never appears here. [canAdd] is how many slots are
 * free; when it is zero there is nothing to choose and the dialog just explains why, returning an
 * empty set so the rest of the restore still proceeds.
 *
 * Selection is held by NAME, the same handle the export and preview pickers use. The tick count can
 * never exceed [canAdd]: at the cap an unticked row simply can't be ticked until another is freed,
 * so the caller always receives a set that fits.
 */
@Composable
private fun ApiImportLimitDialog(
    incoming: List<String>,
    canAdd: Int,
    max: Int,
    onDone: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    // Pre-fill up to the cap in file order, so tapping straight through still imports a full,
    // valid batch rather than nothing.
    var draft by remember(incoming, canAdd) {
        mutableStateOf(incoming.take(canAdd.coerceAtLeast(0)).toSet())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(S.backupPickApiTitle) },
        text = {
            Column {
                if (canAdd <= 0) {
                    Text(S.backupImportApiFull(max), fontSize = 13.sp)
                } else {
                    Text(S.backupImportApiLimit(canAdd, max), fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(S.backupNOfM(draft.size, incoming.size), fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        items(incoming, key = { it }) { name ->
                            val checked = name in draft
                            // At the cap an unticked row is inert until a slot is freed; ticking it is
                            // ignored so the selection can never exceed what will fit.
                            val blocked = !checked && draft.size >= canAdd
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = checked || !blocked) {
                                        draft = if (checked) draft - name else draft + name
                                    }
                                    .padding(vertical = 2.dp)
                            ) {
                                Checkbox(checked = checked, enabled = checked || !blocked, onCheckedChange = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    name.ifBlank { S.backupModApi },
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        },
        // With no room, the only honest action is to acknowledge and import none.
        confirmButton = {
            TextButton(onClick = { onDone(if (canAdd <= 0) emptySet() else draft) }) {
                Text(if (canAdd <= 0) S.actionDone else S.actionRestore)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(S.actionCancel) } }
    )
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
