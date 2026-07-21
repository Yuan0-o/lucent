package com.lucent.app.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File

/** Same shipped default the Android build uses when the user hasn't customized the chat style. */
const val DEFAULT_ASSISTANT_STYLE = "lively and friendly, relaxed and natural."

/**
 * Desktop twin of the Android SettingsRepository.
 *
 * ### Same API, different floor
 *
 * Every Flow, every setter, every one-shot read, and every rule the Android repository enforces
 * (the encrypted-at-rest secrets, the mirror between API profiles and the flat connection keys,
 * the local-mode park/restore dance) exists here under the same names — so every shared file that
 * takes a SettingsRepository compiles and behaves identically. Only the storage differs: instead
 * of DataStore, values live in one JSON file (`lucent_settings.json`) mirrored through a
 * [MutableStateFlow], written atomically (temp file + rename) on every edit. The stored key
 * strings are the same ones Android uses, purely so the two implementations stay diffable.
 *
 * ### One deliberate default change (user requirement)
 *
 * `backgroundAnimationEnabled` defaults to **false** on desktop. The drifting-blob background is a
 * phone-first flourish; the Windows requirement is that it exists, is switchable in Settings, and
 * ships OFF by default. A restored Android backup that carries `true` still turns it on — the
 * default only governs a fresh install.
 */
class SettingsRepository(private val context: Context) {

    // ---- Stored key names (string-identical to Android's SettingsKeys) ----
    private object K {
        const val THEME_MODE = "theme_mode"
        const val PALETTE = "palette"
        const val FONT = "font"
        const val BASE_URL_ENC = "base_url_enc"
        const val API_SPEC_ENC = "api_spec_enc"
        const val MODEL_ENC = "model_enc"
        const val ASSISTANT_NAME_ENC = "assistant_name_enc"
        const val ASSISTANT_STYLE_ENC = "assistant_style_enc"
        const val ATTACHMENTS_MIGRATED = "attachments_migrated_v1"
        const val API_KEY_ENC = "api_key_enc"
        const val API_PROFILES_ENC = "api_profiles_json_enc"
        const val BACKUP_PASSWORD_ENC = "backup_password_enc"
        const val API_PROFILE_SELECTED = "api_profile_selected"
        const val NOTES_SORT = "notes_sort"
        const val TASKS_SORT = "tasks_sort"
        const val MEMORY_TIER = "memory_tier"
        const val WEB_SEARCH_ENABLED = "web_search_enabled"
        const val TYPING_HAPTICS = "typing_haptics"
        const val MARKDOWN_ENABLED = "markdown_enabled"
        const val LINKS_ENABLED = "links_enabled"
        const val BACKGROUND_ANIMATION_ENABLED = "background_animation_enabled"
        const val APP_LOCK_ENABLED = "app_lock_enabled"
        const val APP_LOCK_CREDENTIALS_ENC = "app_lock_credentials_enc"
        // Desktop-only: whether the App Lock also accepts a Windows Hello unlock. Has no effect (and
        // is never shown) on machines without Hello hardware — see security/WindowsHello.
        const val APP_LOCK_HELLO_ENABLED = "app_lock_hello_enabled"
        const val SYSTEM_INTEGRATION_ENABLED = "system_integration_enabled"
        const val STARTUP_LOGGING_ENABLED = "startup_logging_enabled"
        const val APP_LANGUAGE = "app_language"
        const val LOCAL_MODEL_ENABLED = "local_model_enabled"
        const val LOCAL_TOOLS_ENABLED = "local_tools_enabled"
        const val LOCAL_GPU_ENABLED = "local_gpu_enabled"
        const val LOCAL_BACKGROUND_REPLY = "local_background_reply"
        const val MEMORY_TIER_PRELOCAL = "memory_tier_prelocal"
        const val WEB_SEARCH_PRELOCAL = "web_search_prelocal"
    }

    // ---- The store itself: one JSON object, one StateFlow, one mutex ----

    private val file: File get() = File(context.applicationContext.filesDir, "lucent_settings.json")

    companion object {
        // Shared across instances (screens construct their own SettingsRepository, exactly as on
        // Android where DataStore is process-wide). One state, one writer queue.
        private val state = MutableStateFlow<Map<String, Any>>(emptyMap())
        private val writeMutex = Mutex()
        @Volatile private var loadedFrom: String? = null
    }

    init {
        ensureLoaded()
    }

    private fun ensureLoaded() {
        val path = file.absolutePath
        if (loadedFrom == path) return
        synchronized(SettingsRepository::class.java) {
            if (loadedFrom == path) return
            state.value = readFile()
            loadedFrom = path
        }
    }

    private fun readFile(): Map<String, Any> = try {
        if (!file.exists()) emptyMap() else {
            val obj = JSONObject(file.readText())
            buildMap {
                obj.keys().forEach { k ->
                    when (val v = obj.get(k)) {
                        is Boolean, is Int, is String -> put(k, v)
                        is Number -> put(k, v.toInt())
                    }
                }
            }
        }
    } catch (t: Throwable) {
        emptyMap()
    }

    private fun writeFile(values: Map<String, Any>) {
        try {
            val obj = JSONObject()
            values.forEach { (k, v) -> obj.put(k, v) }
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(obj.toString(2))
            if (!tmp.renameTo(file)) {
                // Windows rename-over-existing can refuse; delete-then-rename is the accepted dance.
                file.delete()
                tmp.renameTo(file)
            }
        } catch (t: Throwable) {
            StartupLog.event(context, "settings: write failed: ${t.message}")
        }
    }

    private suspend fun edit(mutate: (MutableMap<String, Any>) -> Unit) {
        writeMutex.withLock {
            val next = state.value.toMutableMap()
            mutate(next)
            state.value = next
            writeFile(next)
        }
    }

    private fun str(prefs: Map<String, Any>, key: String): String? = prefs[key] as? String
    private fun bool(prefs: Map<String, Any>, key: String): Boolean? = prefs[key] as? Boolean
    private fun int(prefs: Map<String, Any>, key: String): Int? = (prefs[key] as? Number)?.toInt()

    /** Read an encrypted preference — the Android original's `secret()`, minus the legacy keys. */
    private fun secret(prefs: Map<String, Any>, key: String, default: String): String {
        val stored = str(prefs, key) ?: return default
        return LocalSecrets.decrypt(stored).ifEmpty { default }
    }

    // ---- Connection / assistant identity ----

    val baseUrl: Flow<String> = state.map { secret(it, K.BASE_URL_ENC, "") }
    val apiSpec: Flow<String> = state.map { secret(it, K.API_SPEC_ENC, "openai") }
    val model: Flow<String> = state.map { secret(it, K.MODEL_ENC, "") }
    val assistantName: Flow<String> = state.map { secret(it, K.ASSISTANT_NAME_ENC, "Lucent") }
    // Blank until the user customizes it — must never resolve to DEFAULT_ASSISTANT_STYLE here.
    val assistantStyle: Flow<String> = state.map { secret(it, K.ASSISTANT_STYLE_ENC, "") }

    // ---- Display ----

    val themeMode: Flow<String> = state.map { str(it, K.THEME_MODE) ?: "system" }
    val palette: Flow<String> = state.map { str(it, K.PALETTE) ?: "CYCLE" }
    // App-wide font choice: "system" (the platform font, and the out-of-the-box state) or the id
    // of a font imported through data/FontStore. An id that no longer resolves simply renders as
    // the system font — see ui/LucentFonts.
    val font: Flow<String> = state.map { str(it, K.FONT) ?: "system" }

    data class DisplayPrefs(val themeMode: String, val palette: String, val font: String)

    suspend fun displayPrefsOnce(): DisplayPrefs {
        val prefs = state.first()
        return DisplayPrefs(
            themeMode = str(prefs, K.THEME_MODE) ?: "system",
            palette = str(prefs, K.PALETTE) ?: "CYCLE",
            font = str(prefs, K.FONT) ?: "system"
        )
    }

    data class StartupPrefs(
        val display: DisplayPrefs,
        val appLockEnabled: Boolean,
        val startupLoggingEnabled: Boolean,
        val systemIntegrationEnabled: Boolean,
        val appLanguage: String = "system",
        // Desktop default is OFF — see the class comment.
        val backgroundAnimationEnabled: Boolean = false
    )

    suspend fun startupPrefsOnce(): StartupPrefs {
        val prefs = state.first()
        return StartupPrefs(
            display = DisplayPrefs(
                themeMode = str(prefs, K.THEME_MODE) ?: "system",
                palette = str(prefs, K.PALETTE) ?: "CYCLE",
                font = str(prefs, K.FONT) ?: "system"
            ),
            appLockEnabled = bool(prefs, K.APP_LOCK_ENABLED) ?: false,
            startupLoggingEnabled = bool(prefs, K.STARTUP_LOGGING_ENABLED) ?: false,
            systemIntegrationEnabled = bool(prefs, K.SYSTEM_INTEGRATION_ENABLED) ?: false,
            appLanguage = str(prefs, K.APP_LANGUAGE) ?: "system",
            backgroundAnimationEnabled = bool(prefs, K.BACKGROUND_ANIMATION_ENABLED) ?: false
        )
    }

    // ---- UI language ----

    val appLanguage: Flow<String> = state.map { str(it, K.APP_LANGUAGE) ?: "system" }
    suspend fun setAppLanguage(value: String) { edit { it[K.APP_LANGUAGE] = value } }
    suspend fun appLanguageOnce(): String = str(state.first(), K.APP_LANGUAGE) ?: "system"

    // ---- Local model ----

    val localModelEnabled: Flow<Boolean> = state.map { bool(it, K.LOCAL_MODEL_ENABLED) ?: false }

    /**
     * Flip local-model mode with the same one-edit park/restore semantics as Android: turning it on
     * parks the memory tier and web-search choice and resets tools/GPU to off; turning it off hands
     * the parked values back.
     */
    suspend fun setLocalModelEnabled(value: Boolean) {
        edit { prefs ->
            val wasEnabled = prefs[K.LOCAL_MODEL_ENABLED] as? Boolean ?: false
            prefs[K.LOCAL_MODEL_ENABLED] = value
            if (value) {
                if (!wasEnabled) {
                    prefs[K.MEMORY_TIER_PRELOCAL] = prefs[K.MEMORY_TIER] as? String ?: MemoryTier.DEFAULT.key
                    prefs[K.WEB_SEARCH_PRELOCAL] = prefs[K.WEB_SEARCH_ENABLED] as? Boolean ?: false
                }
                prefs[K.MEMORY_TIER] = MemoryTier.LOW.key
                prefs[K.WEB_SEARCH_ENABLED] = false
                prefs[K.LOCAL_TOOLS_ENABLED] = false
                prefs[K.LOCAL_GPU_ENABLED] = false
            } else {
                prefs[K.MEMORY_TIER] = prefs[K.MEMORY_TIER_PRELOCAL] as? String ?: MemoryTier.DEFAULT.key
                prefs[K.WEB_SEARCH_ENABLED] = prefs[K.WEB_SEARCH_PRELOCAL] as? Boolean ?: false
                prefs.remove(K.MEMORY_TIER_PRELOCAL)
                prefs.remove(K.WEB_SEARCH_PRELOCAL)
            }
        }
    }

    val localBackgroundReplyEnabled: Flow<Boolean> = state.map { bool(it, K.LOCAL_BACKGROUND_REPLY) ?: false }
    suspend fun setLocalBackgroundReplyEnabled(value: Boolean) { edit { it[K.LOCAL_BACKGROUND_REPLY] = value } }
    suspend fun localBackgroundReplyEnabledOnce(): Boolean =
        bool(state.first(), K.LOCAL_BACKGROUND_REPLY) ?: false

    val localToolsEnabled: Flow<Boolean> = state.map { bool(it, K.LOCAL_TOOLS_ENABLED) ?: false }
    suspend fun setLocalToolsEnabled(value: Boolean) { edit { it[K.LOCAL_TOOLS_ENABLED] = value } }

    val localGpuEnabled: Flow<Boolean> = state.map { bool(it, K.LOCAL_GPU_ENABLED) ?: false }
    suspend fun setLocalGpuEnabled(value: Boolean) { edit { it[K.LOCAL_GPU_ENABLED] = value } }

    // ---- API credentials ----

    val apiKey: Flow<String> = state.map { prefs ->
        val stored = str(prefs, K.API_KEY_ENC) ?: ""
        if (stored.isEmpty()) "" else LocalSecrets.decrypt(stored)
    }

    val apiProfilesJson: Flow<String> = state.map { prefs ->
        val stored = str(prefs, K.API_PROFILES_ENC) ?: ""
        if (stored.isEmpty()) "" else LocalSecrets.decrypt(stored)
    }

    val apiProfileSelected: Flow<Int> = state.map { int(it, K.API_PROFILE_SELECTED) ?: 0 }

    val attachmentsMigrated: Flow<Boolean> = state.map { bool(it, K.ATTACHMENTS_MIGRATED) ?: false }
    suspend fun setAttachmentsMigrated(value: Boolean) { edit { it[K.ATTACHMENTS_MIGRATED] = value } }

    val backupPassword: Flow<String> = state.map { prefs ->
        val stored = str(prefs, K.BACKUP_PASSWORD_ENC) ?: ""
        if (stored.isEmpty()) "" else LocalSecrets.decrypt(stored)
    }

    /** Set or clear the backup password. A blank value removes it entirely. */
    suspend fun setBackupPassword(value: String) {
        edit { prefs ->
            if (value.isEmpty()) prefs.remove(K.BACKUP_PASSWORD_ENC)
            else prefs[K.BACKUP_PASSWORD_ENC] = LocalSecrets.encrypt(value)
        }
    }

    // ---- List sorts / behaviour toggles ----

    val notesSort: Flow<String> = state.map { str(it, K.NOTES_SORT) ?: "recent" }
    val tasksSort: Flow<String> = state.map { str(it, K.TASKS_SORT) ?: "recent" }
    suspend fun setNotesSort(value: String) { edit { it[K.NOTES_SORT] = value } }
    suspend fun setTasksSort(value: String) { edit { it[K.TASKS_SORT] = value } }

    val memoryTier: Flow<String> = state.map { str(it, K.MEMORY_TIER) ?: MemoryTier.DEFAULT.key }
    suspend fun setMemoryTier(value: String) { edit { it[K.MEMORY_TIER] = value } }

    val webSearchEnabled: Flow<Boolean> = state.map { bool(it, K.WEB_SEARCH_ENABLED) ?: false }
    suspend fun setWebSearchEnabled(value: Boolean) { edit { it[K.WEB_SEARCH_ENABLED] = value } }

    val typingHapticsEnabled: Flow<Boolean> = state.map { bool(it, K.TYPING_HAPTICS) ?: true }
    suspend fun setTypingHapticsEnabled(value: Boolean) { edit { it[K.TYPING_HAPTICS] = value } }

    val markdownEnabled: Flow<Boolean> = state.map { bool(it, K.MARKDOWN_ENABLED) ?: false }
    suspend fun setMarkdownEnabled(value: Boolean) { edit { it[K.MARKDOWN_ENABLED] = value } }

    val linksEnabled: Flow<Boolean> = state.map { bool(it, K.LINKS_ENABLED) ?: false }
    suspend fun setLinksEnabled(value: Boolean) { edit { it[K.LINKS_ENABLED] = value } }

    // Desktop default OFF — the one deliberate divergence from Android. See the class comment.
    val backgroundAnimationEnabled: Flow<Boolean> =
        state.map { bool(it, K.BACKGROUND_ANIMATION_ENABLED) ?: false }
    suspend fun setBackgroundAnimationEnabled(value: Boolean) { edit { it[K.BACKGROUND_ANIMATION_ENABLED] = value } }

    // ---- App lock ----

    val appLockEnabled: Flow<Boolean> = state.map { bool(it, K.APP_LOCK_ENABLED) ?: false }

    val appLockCredentials: Flow<String> = state.map { prefs ->
        val stored = str(prefs, K.APP_LOCK_CREDENTIALS_ENC) ?: ""
        if (stored.isEmpty()) "" else LocalSecrets.decrypt(stored)
    }

    /** Enable/disable the lock and store its credentials JSON — one edit, like Android. */
    suspend fun setAppLock(enabled: Boolean, credentialsJson: String) {
        edit { prefs ->
            prefs[K.APP_LOCK_ENABLED] = enabled
            if (credentialsJson.isEmpty()) prefs.remove(K.APP_LOCK_CREDENTIALS_ENC)
            else prefs[K.APP_LOCK_CREDENTIALS_ENC] = LocalSecrets.encrypt(credentialsJson)
            // Windows Hello only makes sense while the lock is on, so tearing the lock down also
            // clears the Hello opt-in; re-enabling the lock then starts from "off".
            if (!enabled) prefs.remove(K.APP_LOCK_HELLO_ENABLED)
        }
    }

    /** Replace just the credentials (e.g. after a password change) leaving the flag alone. */
    suspend fun setAppLockCredentials(credentialsJson: String) {
        edit { prefs ->
            if (credentialsJson.isEmpty()) prefs.remove(K.APP_LOCK_CREDENTIALS_ENC)
            else prefs[K.APP_LOCK_CREDENTIALS_ENC] = LocalSecrets.encrypt(credentialsJson)
        }
    }

    /**
     * Whether the App Lock additionally accepts Windows Hello (desktop-only). Defaults to false: it
     * is opt-in, and the Settings toggle that flips it is itself only shown when Hello hardware is
     * present, so this can only ever become true on a machine that can honour it.
     */
    val appLockHelloEnabled: Flow<Boolean> = state.map { bool(it, K.APP_LOCK_HELLO_ENABLED) ?: false }

    suspend fun setAppLockHelloEnabled(value: Boolean) {
        edit { it[K.APP_LOCK_HELLO_ENABLED] = value }
    }

    // ---- Privacy toggles ----

    val systemIntegrationEnabled: Flow<Boolean> = state.map { bool(it, K.SYSTEM_INTEGRATION_ENABLED) ?: false }
    suspend fun setSystemIntegrationEnabled(value: Boolean) { edit { it[K.SYSTEM_INTEGRATION_ENABLED] = value } }

    val startupLoggingEnabled: Flow<Boolean> = state.map { bool(it, K.STARTUP_LOGGING_ENABLED) ?: false }
    suspend fun setStartupLoggingEnabled(value: Boolean) { edit { it[K.STARTUP_LOGGING_ENABLED] = value } }

    // ---- Secret setters (encrypted at rest, exactly like Android's putSecret) ----

    private suspend fun putSecret(key: String, value: String) {
        val sealed = LocalSecrets.encrypt(value)
        edit { it[key] = sealed }
    }

    suspend fun setBaseUrl(value: String) = putSecret(K.BASE_URL_ENC, value)
    suspend fun setApiSpec(value: String) = putSecret(K.API_SPEC_ENC, value)
    suspend fun setModel(value: String) = putSecret(K.MODEL_ENC, value)
    suspend fun setAssistantName(value: String) = putSecret(K.ASSISTANT_NAME_ENC, value)
    suspend fun setAssistantStyle(value: String) = putSecret(K.ASSISTANT_STYLE_ENC, value)

    suspend fun setThemeMode(value: String) { edit { it[K.THEME_MODE] = value } }
    suspend fun setPalette(value: String) { edit { it[K.PALETTE] = value } }
    suspend fun setFont(value: String) { edit { it[K.FONT] = value } }

    suspend fun setApiKey(value: String) {
        edit { it[K.API_KEY_ENC] = LocalSecrets.encrypt(value) }
    }

    /**
     * Persist the full profile list and selection, mirroring the selected profile's connection
     * fields into the flat keys in the same edit — verbatim Android semantics, including the
     * "last profile deleted clears the mirrored keys" rule.
     */
    suspend fun saveApiProfiles(profiles: List<ApiProfile>, selected: Int) {
        val safe = profiles.take(ApiProfiles.MAX)
        val idx = if (safe.isEmpty()) 0 else selected.coerceIn(0, safe.size - 1)
        val active = safe.getOrNull(idx)
        val profilesEnc = LocalSecrets.encrypt(ApiProfiles.serialize(safe))
        val activeKeyEnc = active?.let { LocalSecrets.encrypt(it.apiKey) }
        val activeBaseUrlEnc = LocalSecrets.encrypt(active?.baseUrl ?: "")
        val activeSpecEnc = LocalSecrets.encrypt(active?.spec ?: "openai")
        val activeModelEnc = LocalSecrets.encrypt(active?.model ?: "")

        edit { prefs ->
            prefs[K.API_PROFILES_ENC] = profilesEnc
            prefs[K.API_PROFILE_SELECTED] = idx
            if (active != null) {
                prefs[K.BASE_URL_ENC] = activeBaseUrlEnc
                prefs[K.API_SPEC_ENC] = activeSpecEnc
                prefs[K.MODEL_ENC] = activeModelEnc
                if (activeKeyEnc != null) prefs[K.API_KEY_ENC] = activeKeyEnc
            } else {
                prefs.remove(K.BASE_URL_ENC)
                prefs.remove(K.MODEL_ENC)
                prefs.remove(K.API_KEY_ENC)
            }
        }
    }

    /** Wipe every stored setting back to its default (used by "clear all data"). */
    suspend fun clearAll() { edit { it.clear() } }
}
