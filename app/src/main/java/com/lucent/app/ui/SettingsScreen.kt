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
import androidx.compose.material3.Switch
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
import com.lucent.app.data.BackupManager
import com.lucent.app.data.MemoryTier
import com.lucent.app.data.SettingsRepository
import com.lucent.app.data.ShareIntegration
import com.lucent.app.data.StartupLog
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
private enum class SettingsRoute { Root, Assistant, Personalization, Memory, Api, Appearance, Theme, Background, Font, Editor, Security, Privacy, Data }

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
    // Editor: whether links are active (task 3). A sub-toggle of Markdown — only live when both are
    // on. Defaults to on.
    val linksEnabled by repo.linksEnabled.collectAsState(initial = true)

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
                backupStatus = if (ok) "Logs exported." else "Couldn't export the logs."
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
                LucentToast.show(appContext, "Saved")
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
            withContext(Dispatchers.Main) { LucentToast.show(appContext, "API saved") }
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

    // Delete profile [idx]. Never removes the last one (there must always be at least one API slot).
    fun deleteProfile(idx: Int) {
        if (profiles.size <= 1) return
        val newList = profiles.toMutableList().also { it.removeAt(idx) }
        val newSelected = selectedProfileIdx.coerceIn(0, newList.size - 1)
        AppScope.io.launch { repo.saveApiProfiles(newList, newSelected) }
    }

    // Leaving the Personalization sub-screen (back arrow or system back) while dirty asks first
    // instead of silently discarding. Every other page saves each action immediately, so this
    // guard only ever engages on the Personalization route.
    fun leavePersonalization() {
        if (assistantDirty) showUnsavedDialog = true else route = SettingsRoute.Assistant
    }

    // Where "back" goes from the current sub-route, reflecting the nesting:
    //   Assistant > { Personalization, API, Memory & web }   and   Appearance > { Theme, Background, Font }
    fun goBack() {
        when (route) {
            SettingsRoute.Personalization -> leavePersonalization()
            SettingsRoute.Memory -> route = SettingsRoute.Assistant
            SettingsRoute.Api -> route = SettingsRoute.Assistant
            SettingsRoute.Theme, SettingsRoute.Background, SettingsRoute.Font -> route = SettingsRoute.Appearance
            SettingsRoute.Assistant, SettingsRoute.Appearance, SettingsRoute.Editor,
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
            title = { Text("Unsaved changes") },
            text = { Text("You have unsaved changes to your assistant settings. Save them before leaving?") },
            confirmButton = {
                TextButton(onClick = {
                    persistAssistantSettings()
                    showUnsavedDialog = false
                    route = SettingsRoute.Assistant
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        discardAssistantSettings()
                        showUnsavedDialog = false
                        route = SettingsRoute.Assistant
                    }) { Text("Discard") }
                    TextButton(onClick = { showUnsavedDialog = false }) { Text("Cancel") }
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
                        } ?: return@withContext "Couldn't write to that file."
                        if (password.isNullOrEmpty()) {
                            "Encrypted backup saved, using Lucent's built-in key."
                        } else {
                            "Encrypted backup saved, protected by your password. Don't lose it."
                        }
                    } catch (e: Exception) {
                        "Export failed: ${e.message}"
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
            title = { Text("Export backup") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "Everything goes into one encrypted .lcb file — notes, tasks, version history, " +
                            "chats, attachments, and settings. By default it's locked with Lucent's " +
                            "built-in key, which means it restores on ANY device with nothing but the " +
                            "file. Leave the password blank for that.",
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    Text("Add a password (optional)", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "A password gives stronger protection: the key is derived from it and exists " +
                            "nowhere else, so not even someone holding the app can open the file. The " +
                            "trade-off is that you must type the SAME password to restore it on another " +
                            "device — it isn't saved anywhere else, and a forgotten one can't be " +
                            "recovered. Leave this blank unless you want that.",
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = exportPasswordDraft,
                        onValueChange = { exportPasswordDraft = it },
                        label = { Text("Password (optional)") },
                        singleLine = true,
                        visualTransformation = if (exportPasswordVisible) VisualTransformation.None
                            else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { exportPasswordVisible = !exportPasswordVisible }) {
                                Icon(
                                    if (exportPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (exportPasswordVisible) "Hide password" else "Show password"
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
                ) { Text("Export") }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) { Text("Cancel") }
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
                    backupStatus = "Could not read that file."
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
                        onFailure = { backupStatus = "Import failed: ${it.message}" }
                    )
                }
            }
        }
    }

    // --- Step 2: the password prompt (only for a backup made with a custom password) ---
    pendingImportBytes?.let { bytes ->
        AlertDialog(
            onDismissRequest = { pendingImportBytes = null },
            title = { Text("Backup password") },
            text = {
                Column {
                    Text(
                        "This backup was protected with a password when it was exported. " +
                            "Enter it to see what's inside.",
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = importPasswordDraft,
                        onValueChange = { importPasswordDraft = it; importPasswordError = false },
                        singleLine = true,
                        isError = importPasswordError,
                        visualTransformation = PasswordVisualTransformation(),
                        label = { Text(if (importPasswordError) "Wrong password" else "Password") },
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
                                        backupStatus = "Import failed: ${error.message}"
                                    }
                                }
                            )
                        }
                    }
                ) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { pendingImportBytes = null }) { Text("Cancel") } }
        )
    }

    // --- Step 3: show what's in the file, and let them say no ---
    importPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = { importPreview = null },
            title = { Text("Restore this backup?") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    preview.exportedAt?.let {
                        Text("Exported ${formatTimestamp(it)}", fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(2.dp))
                    }
                    Text(
                        if (preview.passwordProtected) "Protected with your password."
                        else "Encrypted with Lucent's built-in key.",
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    if (preview.isEmpty) {
                        Text("This backup appears to be empty.", fontSize = 13.sp)
                    } else {
                        Text("It contains:", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(6.dp))
                        BackupContentLine("Notes", preview.notes, buildList {
                            if (preview.archivedNotes > 0) add("${preview.archivedNotes} archived")
                            if (preview.trashedNotes > 0) add("${preview.trashedNotes} in trash")
                        })
                        BackupContentLine("Tasks", preview.tasks, buildList {
                            if (preview.completedTasks > 0) add("${preview.completedTasks} completed")
                            if (preview.trashedTasks > 0) add("${preview.trashedTasks} in trash")
                        })
                        BackupContentLine("Note versions", preview.noteVersions, emptyList())
                        BackupContentLine("Conversations", preview.conversations, emptyList())
                        BackupContentLine("Chat messages", preview.chatMessages, emptyList())
                        BackupContentLine("Attachments", preview.attachments, emptyList())
                        if (preview.hasSettings) {
                            BackupContentLine("Settings", 1, listOf("including your API keys"))
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Restoring adds these to what you already have — nothing currently on this " +
                            "device is deleted. Anything identical is skipped rather than duplicated.",
                        fontSize = 13.sp
                    )
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
                                    "Import failed: ${e.message}"
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
                ) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { importPreview = null }) { Text("Cancel") } }
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
        LucentToast.show(context, "App lock is on.")
    }

    if (showAppLockSetup) {
        AlertDialog(
            onDismissRequest = { showAppLockSetup = false },
            title = { Text("Set up app lock") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "Choose a password you'll enter each time Lucent opens. The security question is " +
                            "optional, but it is the only way to reset the password if you forget it. " +
                            "Neither the password nor the answer is stored — only a salted hash — so if " +
                            "you forget BOTH, the only way back in is to clear all data.",
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = lockPw,
                        onValueChange = { lockPw = it; lockSetupError = "" },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = lockPwConfirm,
                        onValueChange = { lockPwConfirm = it; lockSetupError = "" },
                        label = { Text("Confirm password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = lockQuestion,
                        onValueChange = { lockQuestion = it; lockSetupError = "" },
                        label = { Text("Security question (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = lockAnswer,
                        onValueChange = { lockAnswer = it; lockSetupError = "" },
                        label = { Text("Answer (optional)") },
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
                        lockPw.length < 4 -> lockSetupError = "Use a password of at least 4 characters."
                        lockPw != lockPwConfirm -> lockSetupError = "The passwords don't match."
                        // Half a security question is still an error: a question with no answer can
                        // never be verified, and an answer with no question can never be asked.
                        lockQuestion.isNotBlank() && lockAnswer.isBlank() ->
                            lockSetupError = "Enter an answer to your security question."
                        lockAnswer.isNotBlank() && lockQuestion.isBlank() ->
                            lockSetupError = "Enter the question this answer belongs to."
                        // Both blank: allowed, but only after the user has been told what it costs.
                        lockQuestion.isBlank() && lockAnswer.isBlank() -> showNoRecoveryWarning = true
                        else -> applyAppLock()
                    }
                }) { Text("Turn on") }
            },
            dismissButton = {
                TextButton(onClick = {
                    lockPw = ""; lockPwConfirm = ""; lockQuestion = ""; lockAnswer = ""
                    showAppLockSetup = false
                }) { Text("Cancel") }
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
            title = { Text("Turn on without a way to reset it?") },
            text = {
                Text(
                    "Without a security question there is no password reset. If you forget this " +
                        "password, the only way back into Lucent is to clear all data — every note, " +
                        "task, attachment and conversation on this device, permanently. Nobody, " +
                        "including Anthropic or the app itself, can recover it for you, because the " +
                        "password is never stored anywhere."
                )
            },
            confirmButton = {
                Button(onClick = {
                    showNoRecoveryWarning = false
                    applyAppLock()
                }) { Text("Turn on anyway") }
            },
            dismissButton = {
                TextButton(onClick = { showNoRecoveryWarning = false }) { Text("Add a question") }
            }
        )
    }

    // --- System integration privacy warning (task 6) ---
    if (showShareWarning) {
        AlertDialog(
            onDismissRequest = { showShareWarning = false },
            title = { Text("Make Lucent a share target?") },
            text = {
                Text(
                    "This makes Lucent appear in other apps' share sheets so you can send text and files " +
                        "into it. It's the one place Lucent becomes visible to other apps. Anything you " +
                        "choose to share INTO Lucent is copied into your encrypted database like any other " +
                        "note or task; Lucent still sends nothing out on its own. You can turn this off " +
                        "again at any time, and it's off until you confirm."
                )
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch { repo.setSystemIntegrationEnabled(true) }
                    ShareIntegration.setEnabled(context, true)
                    showShareWarning = false
                    // Toast rather than the Data page's backupStatus line: this control lives on
                    // Security and Privacy now (task 5).
                    LucentToast.show(context, "System integration is on.")
                }) { Text("Turn on") }
            },
            dismissButton = {
                TextButton(onClick = { showShareWarning = false }) { Text("Cancel") }
            }
        )
    }

    if (showClearData) {
        AlertDialog(
            onDismissRequest = { showClearData = false },
            title = { Text("Clear all data?") },
            text = { Text("This permanently deletes every note, task, and chat message, and resets all settings (including your API key) to their defaults. This can't be undone.") },
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
                            LucentToast.show(appContext, "All data cleared")
                        }
                    }
                }) { Text("Delete everything") }
            },
            dismissButton = { TextButton(onClick = { showClearData = false }) { Text("Cancel") } }
        )
    }

    // Clear only notes. After deleting the rows, re-run the orphan sweep, which recomputes the
    // referenced attachment ids from whatever remains (tasks) and frees files that belonged only
    // to notes while keeping any still referenced elsewhere.
    if (showClearNotes) {
        AlertDialog(
            onDismissRequest = { showClearNotes = false },
            title = { Text("Clear all notes?") },
            text = { Text("This permanently deletes every note and its attachments. Your tasks and chats are kept. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showClearNotes = false
                    AppScope.io.launch {
                        // A note's revision history belongs to the note. Leaving it behind would
                        // orphan every row and quietly grow a table nothing can ever reach again.
                        db.noteVersionDao().clearAll()
                        db.noteDao().clearAll()
                        com.lucent.app.data.AttachmentMigration.pruneOrphans(appContext)
                        withContext(Dispatchers.Main) { LucentToast.show(appContext, "Notes cleared") }
                    }
                }) { Text("Delete notes") }
            },
            dismissButton = { TextButton(onClick = { showClearNotes = false }) { Text("Cancel") } }
        )
    }

    if (showClearTasks) {
        AlertDialog(
            onDismissRequest = { showClearTasks = false },
            title = { Text("Clear all tasks?") },
            text = { Text("This permanently deletes every task (active and completed) and its attachments. Your notes and chats are kept. This can't be undone.") },
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
                        withContext(Dispatchers.Main) { LucentToast.show(appContext, "Tasks cleared") }
                    }
                }) { Text("Delete tasks") }
            },
            dismissButton = { TextButton(onClick = { showClearTasks = false }) { Text("Cancel") } }
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
        val name = profiles.getOrNull(idx)?.name?.ifBlank { "API ${idx + 1}" } ?: "this API"
        AlertDialog(
            onDismissRequest = { profilePendingDelete = null },
            title = { Text("Delete this API?") },
            text = {
                Text(
                    "This removes \"$name\", including its saved key, from this device. It can't be " +
                        "undone. If the key isn't saved anywhere else you'll need to paste it in again " +
                        "to use this API."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteProfile(idx)
                    profilePendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { profilePendingDelete = null }) { Text("Cancel") } }
        )
    }

    if (showClearChats) {
        AlertDialog(
            onDismissRequest = { showClearChats = false },
            title = { Text("Clear all chat history?") },
            text = { Text("This permanently deletes every assistant conversation and message. Your notes and tasks are kept. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showClearChats = false
                    AppScope.io.launch {
                        db.chatDao().clearAll()
                        db.chatConversationDao().clearAll()
                        AssistantController.onAllChatsCleared(appContext)
                        withContext(Dispatchers.Main) { LucentToast.show(appContext, "Chat history cleared") }
                    }
                }) { Text("Delete chat history") }
            },
            dismissButton = { TextButton(onClick = { showClearChats = false }) { Text("Cancel") } }
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
                backupStatus = "Exported selected items."
            }
        }
    }

    // Turn a chosen subset + format into bytes and kick off the file picker. Shared by the notes and
    // tasks export screens below so the format handling lives in exactly one place.
    fun launchExport(fileStem: String, bytes: ByteArray, format: com.lucent.app.data.ExportFormat) {
        pendingExportBytes = bytes
        pendingExportName = "$fileStem.${format.extension}"
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
                title = "Export notes",
                items = notesForExport.filter { it.trashedAt == null },
                id = { it.id },
                label = { it.title },
                subtitle = { formatTimestamp(it.updatedAt) },
                timestamp = { it.updatedAt },
                searchText = { it.title + "\n" + it.body },
                onExport = { subset, format ->
                    launchExport("lucent-notes", com.lucent.app.data.DocumentExport.exportNotes(subset, format), format)
                },
                onBack = { exportKind = null }
            )
            ExportKind.TASKS -> ExportSelectionScreen(
                title = "Export tasks",
                items = tasksForExport.filter { it.trashedAt == null },
                id = { it.id },
                label = { it.title },
                subtitle = { formatTimestamp(it.createdAt) + if (it.isDone) " · done" else "" },
                timestamp = { it.createdAt },
                searchText = { it.title + "\n" + it.notes },
                onExport = { subset, format ->
                    launchExport("lucent-tasks", com.lucent.app.data.DocumentExport.exportTasks(subset, format), format)
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
                // irreversible: how it looks, what it can do for you, how you write, who can get in,
                // what leaves the device, and finally the page that can erase everything. The
                // destructive page being last is the point — it is the one you should have to travel
                // to rather than the one you land on.
                NavCard("Appearance", "Theme, background palette, and font") { route = SettingsRoute.Appearance }
                Spacer(modifier = Modifier.height(12.dp))
                // The subtitle lists what is actually behind this card. It used to stop at the API,
                // which quietly under-sold the section: memory and web search live here too, and a
                // subtitle that names three of four things reads as a complete list rather than a
                // truncated one — so the fourth looks like it isn't there. Shortened while adding
                // it (36 characters down to 33) so it cannot wrap onto a second line.
                NavCard("Assistant", "Name, style, API, memory, and web") { route = SettingsRoute.Assistant }
                Spacer(modifier = Modifier.height(12.dp))
                NavCard("Editor", "Markdown formatting and links") { route = SettingsRoute.Editor }
                Spacer(modifier = Modifier.height(12.dp))
                NavCard("Security", "App lock") { route = SettingsRoute.Security }
                Spacer(modifier = Modifier.height(12.dp))
                NavCard("Privacy", "System integration and local logging") { route = SettingsRoute.Privacy }
                Spacer(modifier = Modifier.height(12.dp))
                NavCard("Data", "Backup, restore, and clear all data") { route = SettingsRoute.Data }
            }

            SettingsRoute.Assistant -> {
                BackHeader("Assistant") { route = SettingsRoute.Root }

                // Order (task 10): Personalization, API, Memory & web. Personalization is its own
                // sub-page, matching the API card's pattern so the Assistant screen is a consistent
                // list of hierarchical entries.
                NavCard("Personalization", "Assistant name and chat style") { route = SettingsRoute.Personalization }

                Spacer(modifier = Modifier.height(12.dp))

                // API is its own hierarchical page. The subtitle shows which profile is active so
                // the user can see their current connection at a glance without opening it.
                val activeName = profiles.getOrNull(selectedProfileIdx)?.name ?: "None"
                NavCard("API", "Selection and connection · active: $activeName") { route = SettingsRoute.Api }

                Spacer(modifier = Modifier.height(12.dp))

                // Memory and Web are one card again (task 10): a single "Memory & web" page holds the
                // memory tier and the web-search toggle together.
                NavCard("Memory & web", "How much it remembers · web search") { route = SettingsRoute.Memory }
            }

            SettingsRoute.Personalization -> {
                BackHeader("Personalization") { leavePersonalization() }
                Column(modifier = Modifier.fillMaxWidth().frostedGlass().padding(16.dp)) {
                    OutlinedTextField(
                        value = assistantName,
                        onValueChange = { assistantName = it },
                        label = { Text("Assistant name") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = assistantStyle,
                        onValueChange = { assistantStyle = it },
                        label = { Text("Chat Style") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { persistAssistantSettings() }) { Text("Save") }
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
                            Text("Typing haptics", color = onGradient, fontSize = 16.sp)
                            Text(
                                "A faint vibration as each character of a reply appears, and a single " +
                                    "firmer pulse when the reply finishes.",
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
                BackHeader("Memory & web") { route = SettingsRoute.Assistant }

                // Memory tier (issue 9). Each option explains both what the assistant will remember
                // and the rough cost trade-off, since more context means more tokens per reply.
                Column(modifier = Modifier.fillMaxWidth().frostedGlass().padding(16.dp)) {
                    Text("Memory & cost", color = onGradient, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "How much past conversation is sent with each message. More memory gives better " +
                            "continuity but costs more tokens per reply. Changing this never deletes anything — " +
                            "your messages are always saved.",
                        color = onGradientMuted,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    val current = MemoryTier.fromKey(savedMemoryTier)

                    MemoryTierRow(
                        selected = current == MemoryTier.LOW,
                        title = "Low · single message",
                        detail = "Only your latest message is sent. Cheapest, but the assistant won't remember earlier turns.",
                        onGradient = onGradient,
                        onGradientMuted = onGradientMuted,
                        onClick = { AppScope.io.launch { repo.setMemoryTier(MemoryTier.LOW.key) } }
                    )
                    MemoryTierRow(
                        selected = current == MemoryTier.MEDIUM,
                        title = "Medium · this conversation",
                        detail = "The whole current conversation is sent. Balanced — good continuity at a moderate cost.",
                        onGradient = onGradient,
                        onGradientMuted = onGradientMuted,
                        onClick = { AppScope.io.launch { repo.setMemoryTier(MemoryTier.MEDIUM.key) } }
                    )
                    MemoryTierRow(
                        selected = current == MemoryTier.HIGH,
                        title = "High · across conversations",
                        detail = "Also mixes in recent context from your other chats. Most context, highest cost per reply.",
                        onGradient = onGradient,
                        onGradientMuted = onGradientMuted,
                        onClick = { AppScope.io.launch { repo.setMemoryTier(MemoryTier.HIGH.key) } }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Web search toggle (issue 16), now sharing this combined "Memory & web" page (task 10).
                Column(modifier = Modifier.fillMaxWidth().frostedGlass().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Web search", color = onGradient, fontSize = 16.sp)
                            Text(
                                "Let the assistant look things up on the web when you ask about current or " +
                                    "factual topics. When off, it answers from what it already knows.",
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
                BackHeader("API") { route = SettingsRoute.Assistant }

                // ---- API Selection: pick which saved profile is active ----
                Column(modifier = Modifier.fillMaxWidth().frostedGlass().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("API selection", color = onGradient, modifier = Modifier.weight(1f))
                        Text("${profiles.size}/${com.lucent.app.data.ApiProfiles.MAX}", color = onGradientMuted, fontSize = 13.sp)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Choose which saved API the assistant uses. You can keep up to ${com.lucent.app.data.ApiProfiles.MAX}.", color = onGradientMuted, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    profiles.forEachIndexed { idx, p ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            RadioButton(selected = idx == selectedProfileIdx, onClick = { selectProfile(idx) })
                            Column(modifier = Modifier.weight(1f).clickable { selectProfile(idx) }.padding(vertical = 4.dp)) {
                                Text(p.name.ifBlank { "API ${idx + 1}" }, color = onGradient)
                                Text(
                                    "${specLabel(p.spec)} · ${if (p.model.isBlank()) "no model" else p.model}",
                                    color = onGradientMuted,
                                    fontSize = 12.sp
                                )
                            }
                            if (profiles.size > 1) {
                                IconButton(onClick = { profilePendingDelete = idx }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete this API", tint = onGradientMuted)
                                }
                            }
                        }
                    }
                    if (profiles.size < com.lucent.app.data.ApiProfiles.MAX) {
                        Spacer(modifier = Modifier.height(4.dp))
                        TextButton(onClick = { addProfile() }) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = onGradient)
                            Text(" Add API", color = onGradient)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // ---- Editor for the selected profile ----
                Column(modifier = Modifier.fillMaxWidth().frostedGlass().padding(16.dp)) {
                    Text("Edit selected API", color = onGradient)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editingProfileName,
                        onValueChange = { editingProfileName = it },
                        label = { Text("Name") },
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
                        label = { Text("API key") },
                        visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { manualReveal = true }) {
                                Icon(
                                    if (keyVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = "Toggle key visibility"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Text("API specification", color = onGradient)
                    Row {
                        RadioButton(selected = spec == "openai", onClick = { spec = "openai" })
                        Text("OpenAI-compatible", color = onGradient, modifier = Modifier.padding(top = 14.dp))
                    }
                    Row {
                        RadioButton(selected = spec == "anthropic", onClick = { spec = "anthropic" })
                        Text("Anthropic-compatible", color = onGradient, modifier = Modifier.padding(top = 14.dp))
                    }
                    Row {
                        RadioButton(selected = spec == "google", onClick = { spec = "google" })
                        Text("Google-compatible", color = onGradient, modifier = Modifier.padding(top = 14.dp))
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text("API connection", color = onGradient)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("Base URL") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        when (spec) {
                            "anthropic" -> "e.g. https://api.anthropic.com/v1"
                            "google" -> "e.g. https://generativelanguage.googleapis.com/v1beta"
                            else -> "e.g. https://api.openai.com/v1"
                        },
                        color = onGradientMuted
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Button(onClick = {
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
                                .onFailure { errorText = "${it.javaClass.simpleName}: ${it.message ?: "no details"}" }
                        }
                    }) { Text("Fetch available models") }

                    if (loading) {
                        Spacer(modifier = Modifier.height(8.dp))
                        CircularProgressIndicator()
                    }
                    if (errorText.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(errorText, color = Color(0xFFFFC1C1))
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Model", color = onGradient)
                    Box {
                        Button(onClick = { menuExpanded = true }, enabled = models.isNotEmpty()) {
                            Text(if (selectedModel.isBlank()) "Choose a model" else selectedModel)
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
                        Text("Currently: $selectedModel. Fetch models to change it.", color = onGradientMuted, fontSize = 12.sp)
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    // Saving writes the edits into the selected profile and activates it, so the
                    // assistant uses it right away.
                    Button(onClick = { saveActiveProfile(selectedProfileIdx) }) { Text("Save API") }
                }
            }

            SettingsRoute.Appearance -> {
                BackHeader("Appearance") { route = SettingsRoute.Root }
                // Three hierarchical entries, mirroring the Assistant screen's structure.
                NavCard("Theme", "Light, dark, the system, or a Monet tint") { route = SettingsRoute.Theme }
                Spacer(modifier = Modifier.height(12.dp))
                NavCard("Background", "Colour palette behind the glass") { route = SettingsRoute.Background }
                Spacer(modifier = Modifier.height(12.dp))
                NavCard("Font", "Typeface used across the app") { route = SettingsRoute.Font }
            }

            SettingsRoute.Theme -> {
                BackHeader("Theme") { route = SettingsRoute.Appearance }
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
                BackHeader("Background") { route = SettingsRoute.Appearance }
                Column(modifier = Modifier.fillMaxWidth().frostedGlass().padding(16.dp)) {
                    // Auto-cycle: rotates through every palette over time. Its swatch previews the
                    // spread of colours it moves through.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = savedPalette == PALETTE_CYCLE,
                            onClick = { AppScope.io.launch { repo.setPalette(PALETTE_CYCLE) } }
                        )
                        PaletteSwatch(LucentPalette.entries.map { it.colors.first() })
                        Text("Cycle (auto)", color = onGradient, modifier = Modifier.padding(start = 10.dp))
                    }

                    // Palettes grouped by kind, each with a small colour preview.
                    listOf(
                        "Solid" to PaletteGroup.SOLID,
                        "Gradient" to PaletteGroup.GRADIENT,
                        "Classic" to PaletteGroup.CLASSIC
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
                BackHeader("Font") { route = SettingsRoute.Appearance }
                Column(modifier = Modifier.fillMaxWidth().frostedGlass().padding(16.dp)) {
                    // Each option's label is drawn in the font it selects, so the list doubles as a
                    // live preview. Selecting one saves immediately.
                    LucentFont.entries.forEach { f ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = savedFont == f.key,
                                onClick = { AppScope.io.launch { repo.setFont(f.key) } }
                            )
                            Text(
                                f.label,
                                color = onGradient,
                                fontFamily = f.fontFamily,
                                fontSize = 16.sp,
                                modifier = Modifier.padding(start = 10.dp)
                            )
                        }
                    }
                }
            }

            SettingsRoute.Editor -> {
                BackHeader("Editor") { route = SettingsRoute.Root }
                Column(modifier = Modifier.fillMaxWidth().frostedGlass().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Markdown formatting", color = onGradient)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "When on, note bodies are rendered as Markdown — # headings, **bold**, *italic*, `code`, and lists — and the composer shows a formatting hint. When off, notes are shown exactly as typed, with no styling and no hint. Off by default.",
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
                            Text("Links", color = onGradient)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Links come in two kinds. Internal links use double brackets around a note's title, like [[Shopping list]]: they become tappable and jump straight to that note, and the note you link to shows a \"Linked from\" reference back. If the title doesn't exist yet the link shows in red and tapping it creates that note. External links use the standard Markdown form [text](https://example.com) and open in your browser. When this is off, both are shown as plain text and do nothing. This works with or without Markdown formatting — with Markdown off, your text is shown exactly as typed and links still work.",
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
                BackHeader("Security") { route = SettingsRoute.Root }

                // Security is "who can get into this app", and right now that is exactly one control
                // — the app lock, moved here by task 10. It is the page's only occupant on purpose:
                // a page with one clear job is easier to trust than a page with a heading that
                // covers two.
                Column(modifier = Modifier.fillMaxWidth().frostedGlass().padding(16.dp)) {
                    // ---- App Lock ----
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("App lock", color = onGradient)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Require a password each time Lucent is opened from closed. You can add " +
                                    "an optional security question to reset the password if you forget it. " +
                                    "Neither the password nor the answer is stored — only a salted hash.",
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
                                    scope.launch { repo.setAppLock(false, "") }
                                    AppLockController.enabled = false
                                    AppLockController.unlock()
                                }
                            }
                        )
                    }

                }
            }

            SettingsRoute.Privacy -> {
                BackHeader("Privacy") { route = SettingsRoute.Root }

                // Privacy is the other half of the old combined page (task 10): not "who can get in"
                // but "what gets out, or written down". Both switches here are off by default and
                // both are about visibility beyond this screen — one makes Lucent visible to other
                // apps, the other records a local file about what the app did.
                Column(modifier = Modifier.fillMaxWidth().frostedGlass().padding(16.dp)) {
                    // ---- System share / intent integration ----
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("System integration", color = onGradient)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Let Lucent appear in the Android share sheet so you can send text or files " +
                                    "from other apps straight into a new note or task. Off by default. Turning " +
                                    "it on makes Lucent visible to other apps as a share target.",
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
                                }
                            }
                        )
                    }

                    // ---- Local diagnostic logging ----
                    Spacer(modifier = Modifier.height(20.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Startup logging", color = onGradient)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Record app startup events to a local file for troubleshooting. These logs " +
                                    "stay on this device and are never sent anywhere — the only way they leave " +
                                    "is if you export them yourself below.",
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
                                StartupLog.event(context, if (turnOn) "Logging enabled from Settings" else "")
                            }
                        )
                    }
                    if (startupLoggingOn) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row {
                            Button(onClick = { logsExportLauncher.launch("lucent-startup-log.txt") }) { Text("Export logs") }
                            Spacer(modifier = Modifier.width(12.dp))
                            TextButton(onClick = {
                                StartupLog.clear(context)
                                // Toast rather than the Data page's backupStatus line, which isn't
                                // shown on this page (task 5 moved these controls here).
                                LucentToast.show(context, "Logs cleared.")
                            }) { Text("Clear logs") }
                        }
                    }
                }
            }

            SettingsRoute.Data -> {
                BackHeader("Data") { route = SettingsRoute.Root }

                // Shown only in the (rare, alarming) case where the database couldn't be decrypted.
                // Nothing was deleted — the old file was set aside — but silence here would leave
                // someone staring at an empty app with no idea why, and no idea what to do.
                if (lockedNotice != null && !lockedDismissed) {
                    Column(modifier = Modifier.fillMaxWidth().frostedGlass().padding(16.dp)) {
                        Text("Your notes couldn't be unlocked", color = Color(0xFFFF8A80))
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(lockedNotice, color = onGradientMuted, fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Row {
                            Button(onClick = { importLauncher.launch(arrayOf("*/*")) }) { Text("Import backup") }
                            Spacer(modifier = Modifier.width(12.dp))
                            TextButton(onClick = {
                                com.lucent.app.data.DatabaseEncryption.clearLockedNotice(context)
                                lockedDismissed = true
                            }) { Text("Dismiss") }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                Column(modifier = Modifier.fillMaxWidth().frostedGlass().padding(16.dp)) {
                    Text("Backup & restore", color = onGradient)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "One encrypted .lcb file holds everything: notes (archived ones included), tasks, note version history, chats, every attachment, and your settings. The whole file is encrypted, not just your API key. By default it's locked with Lucent's built-in key so it restores on any device with just the file; you can add your own password for stronger protection. Importing shows you what's inside before it changes anything. Only .lcb files exported by this app can be restored.",
                        color = onGradientMuted,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row {
                        Button(onClick = { showExportDialog = true }) { Text("Export backup") }
                        Spacer(modifier = Modifier.width(12.dp))
                        // Allow any file so a beginner can always locate their .lcb even when the
                        // device reports an unexpected MIME type for it. The import path validates the
                        // content itself — it requires a Lucent .lcb envelope and rejects anything else
                        // with a clear message (legacy ZIP/JSON support has been removed, task 5).
                        Button(onClick = { importLauncher.launch(arrayOf("*/*")) }) { Text("Import backup") }
                    }
                    if (backupStatus.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(backupStatus, color = onGradientMuted)
                    }

                    Spacer(modifier = Modifier.height(20.dp))
                    Text("Export notes & tasks", color = onGradient)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Write your notes or tasks to a single file you can keep or open anywhere — choose Markdown, Word, PDF, or Excel on the next screen. Pick exactly which items to include (with a search box and Select-All). These files are NOT encrypted: that is the entire point of them. Attachments are listed by name but not embedded; use the encrypted backup when you need the files themselves.",
                        color = onGradientMuted,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    // Two full-width buttons, tasks first (task 9). They line up cleanly instead of the
                    // old mismatched row, and each opens the pick-items-and-format screen.
                    Button(onClick = { exportKind = ExportKind.TASKS }, modifier = Modifier.fillMaxWidth()) { Text("Choose tasks to export…") }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { exportKind = ExportKind.NOTES }, modifier = Modifier.fillMaxWidth()) { Text("Choose notes to export…") }

                    Spacer(modifier = Modifier.height(20.dp))
                    Text("Danger zone", color = onGradient)
                    Spacer(modifier = Modifier.height(8.dp))
                    // Targeted clears, then the full wipe. All four are identical full-width
                    // rectangles (same shape/size) with the same red style; each asks for
                    // confirmation. Labels are kept short so they fit on one line while still
                    // making each button's function obvious.
                    Button(
                        onClick = { showClearNotes = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB3261E)),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Clear notes") }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { showClearTasks = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB3261E)),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Clear tasks") }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { showClearChats = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB3261E)),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Clear chat history") }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { showClearData = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB3261E)),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Clear all data") }
                }
            }
        }
    }
}

/** Human label for a stored API spec key. */
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
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = onGradient)
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
