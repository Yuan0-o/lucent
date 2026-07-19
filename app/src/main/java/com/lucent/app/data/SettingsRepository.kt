package com.lucent.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "lucent_settings")

private object SettingsKeys {
    // ---- Display preferences: deliberately NOT encrypted ----
    //
    // Every value here is drawn from a fixed vocabulary of about ten strings ("dark", "SUNSET",
    // "title_az"). Encrypting them would protect nothing — an attacker learns that you like dark
    // mode — while putting a Keystore round-trip on the startup path, which MainActivity reads
    // *synchronously before the first frame* precisely to avoid a visible theme flash. Paying a real
    // cost for zero benefit isn't security, it's superstition, and pretending otherwise would make
    // the encryption story less honest, not more.
    val THEME_MODE = stringPreferencesKey("theme_mode")
    val PALETTE = stringPreferencesKey("palette")
    val FONT = stringPreferencesKey("font")

    // ---- Everything the user wrote or configured: encrypted at rest ----
    //
    // Which provider you use, which model, what you named your assistant and how you asked it to
    // speak — these say something about you, and the endpoint plus the key together are enough to
    // spend your money. All of them go through LocalSecrets (AES-GCM under an Android Keystore key).
    val BASE_URL_ENC = stringPreferencesKey("base_url_enc")
    val API_SPEC_ENC = stringPreferencesKey("api_spec_enc")
    val MODEL_ENC = stringPreferencesKey("model_enc")
    val ASSISTANT_NAME_ENC = stringPreferencesKey("assistant_name_enc")
    val ASSISTANT_STYLE_ENC = stringPreferencesKey("assistant_style_enc")

    // Legacy plaintext keys, read-only. Kept solely so an existing install keeps working and upgrades
    // itself on the next save; nothing writes them again.
    val LEGACY_BASE_URL = stringPreferencesKey("base_url")
    val LEGACY_API_SPEC = stringPreferencesKey("api_spec")
    val LEGACY_MODEL = stringPreferencesKey("model")
    val LEGACY_ASSISTANT_NAME = stringPreferencesKey("assistant_name")
    val LEGACY_ASSISTANT_STYLE = stringPreferencesKey("assistant_style")

    // Set once the one-shot Base64 → on-disk attachment migration has moved every legacy
    // attachment out of the database. See AttachmentMigration.runIfNeeded.
    val ATTACHMENTS_MIGRATED = booleanPreferencesKey("attachments_migrated_v1")

    // ---- Secrets, encrypted at rest with LocalSecrets (AndroidKeyStore) ----
    //
    // These replace the previous plaintext `api_key` / `api_profiles_json` entries. The old keys
    // are still *read* (see below) so an existing install keeps working and silently upgrades to
    // the encrypted form on its next save; nothing ever writes them again.
    val API_KEY_ENC = stringPreferencesKey("api_key_enc")
    val API_PROFILES_ENC = stringPreferencesKey("api_profiles_json_enc")

    // The optional password used to encrypt exported backups. Stored here, Keystore-encrypted like
    // every other secret, so exporting stays one tap on this device — and demanded fresh on any
    // *other* device, which is precisely where it's doing its job. Remembering it here costs nothing:
    // anyone who can read this file is already inside the app and can read the notes directly.
    val BACKUP_PASSWORD_ENC = stringPreferencesKey("backup_password_enc")

    // Legacy plaintext keys. Read-only, kept solely to migrate existing installs forward.
    val LEGACY_API_KEY = stringPreferencesKey("api_key")
    val LEGACY_API_PROFILES = stringPreferencesKey("api_profiles_json")

    val API_PROFILE_SELECTED = intPreferencesKey("api_profile_selected")

    // Remembered sort choice for each home list (a NoteSort/TaskSort key — see ui/SortOptions.kt).
    val NOTES_SORT = stringPreferencesKey("notes_sort")
    val TASKS_SORT = stringPreferencesKey("tasks_sort")

    // ---- Assistant behaviour: memory tier & web access (issues 9 and 16) ----
    //
    // Deliberately NOT encrypted, exactly like the display preferences above: each is one value
    // from a tiny fixed vocabulary ("low"/"medium"/"high", true/false). Encrypting them would
    // protect nothing and only add a Keystore round-trip to a value the assistant reads on every
    // send. They describe how the assistant behaves, not anything the user wrote.
    val MEMORY_TIER = stringPreferencesKey("memory_tier")
    val WEB_SEARCH_ENABLED = booleanPreferencesKey("web_search_enabled")
    // Whether the typewriter's per-character tick and finish pulse fire (assistant issue 11's
    // haptics, made optional by the second assistant variant). On by default.
    val TYPING_HAPTICS = booleanPreferencesKey("typing_haptics")

    // Whether note/task bodies are treated as Markdown (headings, bold, lists, [[links]]) or as
    // plain text. Default OFF: not everyone writes Markdown, and someone who doesn't shouldn't see
    // their asterisks and hashes silently restyled or a "Markdown supported" hint they can't use.
    val MARKDOWN_ENABLED = booleanPreferencesKey("markdown_enabled")

    // Whether tappable links ([[wiki]] and [text](url)) are active inside Markdown mode. A sub-toggle
    // of Markdown: when Markdown is off there are no links regardless. Default ON so that turning
    // Markdown on behaves exactly as it always did — links can then be turned off on their own.
    val LINKS_ENABLED = booleanPreferencesKey("links_enabled")

    // ---- App Lock (task 2) — OFF by default ----
    // A boolean flag plus one encrypted blob holding the salted password/answer hashes and the
    // security question. The blob is one-way material (hashes, not the password itself), but it's
    // stored through LocalSecrets anyway to match every other secret at rest here.
    val APP_LOCK_ENABLED = booleanPreferencesKey("app_lock_enabled")
    val APP_LOCK_CREDENTIALS_ENC = stringPreferencesKey("app_lock_credentials_enc")

    // ---- System share / intent integration (task 6) — OFF by default ----
    // Gates whether Lucent appears in the OS share sheet and accepts shared text/attachments. The
    // actual manifest component is enabled/disabled in step with this flag (see ShareIntegration).
    val SYSTEM_INTEGRATION_ENABLED = booleanPreferencesKey("system_integration_enabled")

    // ---- Local startup logging (task 15) — OFF by default ----
    // Purely local diagnostic logging. There is deliberately no "endpoint" or "upload" key anywhere
    // near this: the logs never leave the device (see StartupLog).
    val STARTUP_LOGGING_ENABLED = booleanPreferencesKey("startup_logging_enabled")
}

// Internal default chat style. Deliberately never surfaced in the Settings UI (the Chat Style
// field shows no prefilled text and no "e.g." hint for it) — it only takes effect when the user
// hasn't set a style of their own. Any custom style the user types always takes priority over
// this default; see AssistantController.buildSystemPrompt, which is where that fallback happens.
// Note: the "no AI-like tone" rule (no asterisks, no robotic phrasing) is NOT part of this
// default — it's enforced unconditionally in buildSystemPrompt regardless of style, custom or not.
const val DEFAULT_ASSISTANT_STYLE = "lively and friendly, relaxed and natural."

class SettingsRepository(private val context: Context) {

    /**
     * Read an encrypted preference, falling back to its legacy plaintext key.
     *
     * [LocalSecrets.decrypt] returns an unprefixed value untouched, so an install that predates
     * at-rest encryption reads back correctly and is silently re-written encrypted on its next save.
     * No migration step, no user-visible event, no chance of stranding anyone.
     */
    private fun secret(
        prefs: androidx.datastore.preferences.core.Preferences,
        encrypted: androidx.datastore.preferences.core.Preferences.Key<String>,
        legacy: androidx.datastore.preferences.core.Preferences.Key<String>,
        default: String
    ): String {
        val stored = prefs[encrypted] ?: prefs[legacy] ?: return default
        return LocalSecrets.decrypt(stored).ifEmpty { default }
    }

    val baseUrl: Flow<String> = context.settingsDataStore.data.map {
        secret(it, SettingsKeys.BASE_URL_ENC, SettingsKeys.LEGACY_BASE_URL, "")
    }
    val apiSpec: Flow<String> = context.settingsDataStore.data.map {
        secret(it, SettingsKeys.API_SPEC_ENC, SettingsKeys.LEGACY_API_SPEC, "openai")
    }
    val model: Flow<String> = context.settingsDataStore.data.map {
        secret(it, SettingsKeys.MODEL_ENC, SettingsKeys.LEGACY_MODEL, "")
    }
    val assistantName: Flow<String> = context.settingsDataStore.data.map {
        secret(it, SettingsKeys.ASSISTANT_NAME_ENC, SettingsKeys.LEGACY_ASSISTANT_NAME, "Lucent")
    }
    // Blank until the user customizes it — this Flow feeds the Settings UI directly, so it must
    // never resolve to DEFAULT_ASSISTANT_STYLE or that text would show up in the Chat Style box.
    val assistantStyle: Flow<String> = context.settingsDataStore.data.map {
        secret(it, SettingsKeys.ASSISTANT_STYLE_ENC, SettingsKeys.LEGACY_ASSISTANT_STYLE, "")
    }

    val themeMode: Flow<String> = context.settingsDataStore.data.map { it[SettingsKeys.THEME_MODE] ?: "system" }
    val palette: Flow<String> = context.settingsDataStore.data.map { it[SettingsKeys.PALETTE] ?: "SUNSET" }
    // App-wide font choice, stored as a LucentFont.key. Defaults to the platform font.
    val font: Flow<String> = context.settingsDataStore.data.map { it[SettingsKeys.FONT] ?: "system" }

    /** The three display preferences read together, for the pre-first-frame startup snapshot. */
    data class DisplayPrefs(val themeMode: String, val palette: String, val font: String)

    /**
     * Read theme, palette, and font in a **single** DataStore access.
     *
     * [MainActivity] needs these synchronously before the first frame to avoid a visible theme flash.
     * Reading the three individual Flows would mean three separate `first()` collections — three
     * round-trips through DataStore's serialized reader, each blocking the main thread on its own.
     * Pulling all three from one emission of `data` collapses that to a single read, which is the one
     * that actually sits on the critical startup path. Defaults match the individual Flows exactly, so
     * behaviour is unchanged; only the cost differs.
     */
    suspend fun displayPrefsOnce(): DisplayPrefs {
        val prefs = context.settingsDataStore.data.first()
        return DisplayPrefs(
            themeMode = prefs[SettingsKeys.THEME_MODE] ?: "system",
            palette = prefs[SettingsKeys.PALETTE] ?: "SUNSET",
            font = prefs[SettingsKeys.FONT] ?: "system"
        )
    }

    /**
     * Every preference the *first frame* depends on, in a single DataStore access.
     *
     * The same reasoning as [displayPrefsOnce], carried to its conclusion. Startup was reading the
     * display prefs, then the app-lock flag, then the logging flag, then the share-integration flag —
     * four separate `first()` collections, each one a serialized round-trip through DataStore, all of
     * them on the main thread before a single pixel could be drawn. They are one file. Reading it once
     * and picking four values out of the same emission removes three of those round-trips outright.
     */
    data class StartupPrefs(
        val display: DisplayPrefs,
        val appLockEnabled: Boolean,
        val startupLoggingEnabled: Boolean,
        val systemIntegrationEnabled: Boolean
    )

    suspend fun startupPrefsOnce(): StartupPrefs {
        val prefs = context.settingsDataStore.data.first()
        return StartupPrefs(
            display = DisplayPrefs(
                themeMode = prefs[SettingsKeys.THEME_MODE] ?: "system",
                palette = prefs[SettingsKeys.PALETTE] ?: "SUNSET",
                font = prefs[SettingsKeys.FONT] ?: "system"
            ),
            appLockEnabled = prefs[SettingsKeys.APP_LOCK_ENABLED] ?: false,
            startupLoggingEnabled = prefs[SettingsKeys.STARTUP_LOGGING_ENABLED] ?: false,
            systemIntegrationEnabled = prefs[SettingsKeys.SYSTEM_INTEGRATION_ENABLED] ?: false
        )
    }

    /**
     * The active API key, decrypted for use.
     *
     * Reads the encrypted entry, and falls back to the legacy plaintext one for an install that
     * predates at-rest encryption — [LocalSecrets.decrypt] returns an unprefixed value untouched,
     * so a plaintext key still reads correctly and gets re-written encrypted on the next save.
     * Callers see a plain String either way and never think about which form it was stored in.
     */
    val apiKey: Flow<String> = context.settingsDataStore.data.map { prefs ->
        val stored = prefs[SettingsKeys.API_KEY_ENC] ?: prefs[SettingsKeys.LEGACY_API_KEY] ?: ""
        LocalSecrets.decrypt(stored)
    }

    /**
     * The saved API profiles, as the portable JSON [ApiProfiles] understands.
     *
     * Two layers of protection are at work and they answer different questions. *Inside* the JSON,
     * each profile's key is [CryptoUtil]-encrypted, which is what makes the blob safe to drop
     * verbatim into a backup file that has to be restorable on another phone. The blob as a *whole*
     * is then [LocalSecrets]-encrypted before it touches the disk, which is what makes the copy on
     * this device useless to anyone who lifts the preferences file off it. Peeling the outer layer
     * here means [BackupManager] can keep writing the inner JSON straight into a backup and knows
     * nothing about either scheme.
     */
    val apiProfilesJson: Flow<String> = context.settingsDataStore.data.map { prefs ->
        val stored = prefs[SettingsKeys.API_PROFILES_ENC] ?: prefs[SettingsKeys.LEGACY_API_PROFILES] ?: ""
        LocalSecrets.decrypt(stored)
    }

    val apiProfileSelected: Flow<Int> = context.settingsDataStore.data.map { it[SettingsKeys.API_PROFILE_SELECTED] ?: 0 }

    /** Whether the one-shot Base64→on-disk attachment migration has finished. */
    val attachmentsMigrated: Flow<Boolean> = context.settingsDataStore.data.map { it[SettingsKeys.ATTACHMENTS_MIGRATED] ?: false }

    /**
     * The backup password, or "" when the user hasn't set one (in which case exports fall back to the
     * built-in key — see [BackupCrypto]).
     */
    val backupPassword: Flow<String> = context.settingsDataStore.data.map { prefs ->
        LocalSecrets.decrypt(prefs[SettingsKeys.BACKUP_PASSWORD_ENC] ?: "")
    }

    val notesSort: Flow<String> = context.settingsDataStore.data.map { it[SettingsKeys.NOTES_SORT] ?: "recent" }
    val tasksSort: Flow<String> = context.settingsDataStore.data.map { it[SettingsKeys.TASKS_SORT] ?: "recent" }

    /** The assistant's memory tier (issue 9). Defaults to MEDIUM — remembers the current thread. */
    val memoryTier: Flow<String> = context.settingsDataStore.data.map {
        it[SettingsKeys.MEMORY_TIER] ?: MemoryTier.DEFAULT.key
    }

    /** Whether the assistant may use the web-search tool (issue 16). Off by default. */
    val webSearchEnabled: Flow<Boolean> = context.settingsDataStore.data.map {
        it[SettingsKeys.WEB_SEARCH_ENABLED] ?: false
    }

    /** Whether the typewriter haptics (per-character tick + finish pulse) fire. On by default. */
    val typingHapticsEnabled: Flow<Boolean> = context.settingsDataStore.data.map {
        it[SettingsKeys.TYPING_HAPTICS] ?: true
    }

    /** Whether Markdown formatting is on for notes and tasks. Defaults to OFF (plain text). */
    val markdownEnabled: Flow<Boolean> = context.settingsDataStore.data.map { it[SettingsKeys.MARKDOWN_ENABLED] ?: false }

    /**
     * Whether links ([[wiki]] / [text](url)) are active. A sub-toggle of Markdown, so links are only
     * ever live when BOTH this and [markdownEnabled] are on. Defaults to ON, which keeps the previous
     * behaviour (links follow Markdown) until someone deliberately turns them off.
     */
    val linksEnabled: Flow<Boolean> = context.settingsDataStore.data.map { it[SettingsKeys.LINKS_ENABLED] ?: true }

    // ---- App Lock (task 2) ----
    val appLockEnabled: Flow<Boolean> = context.settingsDataStore.data.map { it[SettingsKeys.APP_LOCK_ENABLED] ?: false }
    /** The decrypted app-lock credentials JSON (salt + hashes + security question), or "" if unset. */
    val appLockCredentials: Flow<String> = context.settingsDataStore.data.map { prefs ->
        LocalSecrets.decrypt(prefs[SettingsKeys.APP_LOCK_CREDENTIALS_ENC] ?: "")
    }

    /** Synchronous reads for the pre-first-frame startup path (MainActivity), one emission each. */
    suspend fun appLockEnabledOnce(): Boolean =
        context.settingsDataStore.data.first()[SettingsKeys.APP_LOCK_ENABLED] ?: false
    suspend fun appLockCredentialsOnce(): String =
        LocalSecrets.decrypt(context.settingsDataStore.data.first()[SettingsKeys.APP_LOCK_CREDENTIALS_ENC] ?: "")
    suspend fun startupLoggingEnabledOnce(): Boolean =
        context.settingsDataStore.data.first()[SettingsKeys.STARTUP_LOGGING_ENABLED] ?: false

    /** Enable/disable the lock and store the credentials blob together, so the two never disagree. */
    suspend fun setAppLock(enabled: Boolean, credentialsJson: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[SettingsKeys.APP_LOCK_ENABLED] = enabled
            if (enabled && credentialsJson.isNotEmpty()) {
                prefs[SettingsKeys.APP_LOCK_CREDENTIALS_ENC] = LocalSecrets.encrypt(credentialsJson)
            } else if (!enabled) {
                prefs.remove(SettingsKeys.APP_LOCK_CREDENTIALS_ENC)
            }
        }
    }

    /** Replace just the credentials (used by password recovery/change) without touching the flag. */
    suspend fun setAppLockCredentials(credentialsJson: String) {
        context.settingsDataStore.edit { it[SettingsKeys.APP_LOCK_CREDENTIALS_ENC] = LocalSecrets.encrypt(credentialsJson) }
    }

    // ---- System share / intent integration (task 6) ----
    val systemIntegrationEnabled: Flow<Boolean> = context.settingsDataStore.data.map { it[SettingsKeys.SYSTEM_INTEGRATION_ENABLED] ?: false }
    suspend fun systemIntegrationEnabledOnce(): Boolean =
        context.settingsDataStore.data.first()[SettingsKeys.SYSTEM_INTEGRATION_ENABLED] ?: false
    suspend fun setSystemIntegrationEnabled(value: Boolean) {
        context.settingsDataStore.edit { it[SettingsKeys.SYSTEM_INTEGRATION_ENABLED] = value }
    }

    // ---- Local startup logging (task 15) ----
    val startupLoggingEnabled: Flow<Boolean> = context.settingsDataStore.data.map { it[SettingsKeys.STARTUP_LOGGING_ENABLED] ?: false }
    suspend fun setStartupLoggingEnabled(value: Boolean) {
        context.settingsDataStore.edit { it[SettingsKeys.STARTUP_LOGGING_ENABLED] = value }
    }

    /** Write an encrypted preference and drop its legacy plaintext twin in the same edit. */
    private suspend fun putSecret(
        encrypted: androidx.datastore.preferences.core.Preferences.Key<String>,
        legacy: androidx.datastore.preferences.core.Preferences.Key<String>,
        value: String
    ) {
        val sealed = LocalSecrets.encrypt(value)
        context.settingsDataStore.edit { prefs ->
            prefs[encrypted] = sealed
            // Removed in the same edit, so an upgrading install stops leaving the old readable copy
            // lying on disk the moment the value is next saved.
            prefs.remove(legacy)
        }
    }

    suspend fun setBaseUrl(value: String) = putSecret(SettingsKeys.BASE_URL_ENC, SettingsKeys.LEGACY_BASE_URL, value)
    suspend fun setApiSpec(value: String) = putSecret(SettingsKeys.API_SPEC_ENC, SettingsKeys.LEGACY_API_SPEC, value)
    suspend fun setModel(value: String) = putSecret(SettingsKeys.MODEL_ENC, SettingsKeys.LEGACY_MODEL, value)
    suspend fun setAssistantName(value: String) = putSecret(SettingsKeys.ASSISTANT_NAME_ENC, SettingsKeys.LEGACY_ASSISTANT_NAME, value)
    suspend fun setAssistantStyle(value: String) = putSecret(SettingsKeys.ASSISTANT_STYLE_ENC, SettingsKeys.LEGACY_ASSISTANT_STYLE, value)

    suspend fun setThemeMode(value: String) { context.settingsDataStore.edit { it[SettingsKeys.THEME_MODE] = value } }
    suspend fun setPalette(value: String) { context.settingsDataStore.edit { it[SettingsKeys.PALETTE] = value } }
    suspend fun setFont(value: String) { context.settingsDataStore.edit { it[SettingsKeys.FONT] = value } }
    suspend fun setAttachmentsMigrated(value: Boolean) { context.settingsDataStore.edit { it[SettingsKeys.ATTACHMENTS_MIGRATED] = value } }
    /** Set or clear the backup password. A blank value removes it entirely. */
    suspend fun setBackupPassword(value: String) {
        context.settingsDataStore.edit { prefs ->
            if (value.isEmpty()) prefs.remove(SettingsKeys.BACKUP_PASSWORD_ENC)
            else prefs[SettingsKeys.BACKUP_PASSWORD_ENC] = LocalSecrets.encrypt(value)
        }
    }

    suspend fun setNotesSort(value: String) { context.settingsDataStore.edit { it[SettingsKeys.NOTES_SORT] = value } }
    suspend fun setTasksSort(value: String) { context.settingsDataStore.edit { it[SettingsKeys.TASKS_SORT] = value } }
    suspend fun setMarkdownEnabled(value: Boolean) { context.settingsDataStore.edit { it[SettingsKeys.MARKDOWN_ENABLED] = value } }
    suspend fun setLinksEnabled(value: Boolean) { context.settingsDataStore.edit { it[SettingsKeys.LINKS_ENABLED] = value } }

    suspend fun setMemoryTier(value: String) { context.settingsDataStore.edit { it[SettingsKeys.MEMORY_TIER] = value } }
    suspend fun setWebSearchEnabled(value: Boolean) { context.settingsDataStore.edit { it[SettingsKeys.WEB_SEARCH_ENABLED] = value } }
    suspend fun setTypingHapticsEnabled(value: Boolean) { context.settingsDataStore.edit { it[SettingsKeys.TYPING_HAPTICS] = value } }

    /**
     * Store the API key, encrypted, and drop any legacy plaintext copy in the same edit — so an
     * upgrading install stops leaving the old readable value behind the moment the key is next
     * saved, rather than keeping a shadow copy of it on disk forever.
     */
    suspend fun setApiKey(value: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[SettingsKeys.API_KEY_ENC] = LocalSecrets.encrypt(value)
            prefs.remove(SettingsKeys.LEGACY_API_KEY)
        }
    }

    /**
     * Persist the full profile list and which one is selected, and mirror the selected profile's
     * connection fields into the flat BASE_URL/API_SPEC/API_KEY/MODEL keys so the assistant (which
     * reads those) immediately uses the chosen API. All in one edit so a reader never observes a
     * half-applied switch. [selected] is clamped to a valid index.
     *
     * Both secret-bearing values — the profiles blob and the mirrored key — go through
     * [LocalSecrets] on the way to disk, and the legacy plaintext entries are removed here too.
     */
    suspend fun saveApiProfiles(profiles: List<ApiProfile>, selected: Int) {
        val safe = profiles.take(ApiProfiles.MAX)
        val idx = if (safe.isEmpty()) 0 else selected.coerceIn(0, safe.size - 1)
        val active = safe.getOrNull(idx)
        // Serialize (and encrypt the inner keys) outside the edit block: this does real crypto
        // work, and DataStore's edit lambda can be re-run under contention.
        val profilesJson = ApiProfiles.serialize(safe)
        val profilesEnc = LocalSecrets.encrypt(profilesJson)
        val activeKeyEnc = active?.let { LocalSecrets.encrypt(it.apiKey) }
        val activeBaseUrlEnc = LocalSecrets.encrypt(active?.baseUrl ?: "")
        val activeSpecEnc = LocalSecrets.encrypt(active?.spec ?: "openai")
        val activeModelEnc = LocalSecrets.encrypt(active?.model ?: "")

        context.settingsDataStore.edit { prefs ->
            prefs[SettingsKeys.API_PROFILES_ENC] = profilesEnc
            prefs[SettingsKeys.API_PROFILE_SELECTED] = idx
            prefs.remove(SettingsKeys.LEGACY_API_PROFILES)
            if (active != null) {
                prefs[SettingsKeys.BASE_URL_ENC] = activeBaseUrlEnc
                prefs[SettingsKeys.API_SPEC_ENC] = activeSpecEnc
                prefs[SettingsKeys.MODEL_ENC] = activeModelEnc
                if (activeKeyEnc != null) prefs[SettingsKeys.API_KEY_ENC] = activeKeyEnc
                prefs.remove(SettingsKeys.LEGACY_API_KEY)
                prefs.remove(SettingsKeys.LEGACY_BASE_URL)
                prefs.remove(SettingsKeys.LEGACY_API_SPEC)
                prefs.remove(SettingsKeys.LEGACY_MODEL)
            }
        }
    }

    // Wipes every stored setting back to its default (used by "clear all data").
    // The attachments-migrated flag is included in that wipe: after a "clear all data" there
    // is nothing left to migrate, so the flag flips back to false only for the migrator to
    // find an empty database and immediately mark it done again on next launch. That's fine.
    // The Keystore key itself is deliberately left alone: it protects nothing once the encrypted
    // values are gone, and rotating it would only risk orphaning a value written concurrently.
    suspend fun clearAll() { context.settingsDataStore.edit { it.clear() } }
}
