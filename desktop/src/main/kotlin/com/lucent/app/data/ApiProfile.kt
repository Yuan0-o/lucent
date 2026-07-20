package com.lucent.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * One saved API configuration. The app can hold up to [ApiProfiles.MAX] of these and switch the
 * active one from Settings → Assistant → API. [apiKey] is held in plain text in memory here; it
 * is encrypted with [CryptoUtil] whenever it's written to disk or into a backup (see
 * [ApiProfiles.serialize]).
 */
data class ApiProfile(
    val name: String = "New API",
    val spec: String = "openai",      // "openai" | "anthropic" | "google"
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = ""
)

object ApiProfiles {

    /** Maximum number of API configurations a user can save (task requirement). */
    const val MAX = 5

    /**
     * Serialize a profile list to the JSON stored in settings. The API key is encrypted per entry
     * so the on-disk preferences file never holds a plaintext key. [encryptKeys] = false is used
     * when producing a human-diffable value only in tests; production always encrypts.
     */
    fun serialize(profiles: List<ApiProfile>, encryptKeys: Boolean = true): String {
        val arr = JSONArray()
        profiles.take(MAX).forEach { p ->
            arr.put(
                JSONObject()
                    .put("name", p.name)
                    .put("spec", p.spec)
                    .put("baseUrl", p.baseUrl)
                    .put("keyEnc", if (encryptKeys) CryptoUtil.encrypt(p.apiKey) else p.apiKey)
                    .put("model", p.model)
            )
        }
        return arr.toString()
    }

    /** Parse the stored JSON back into profiles, decrypting each key. Never throws. */
    fun parse(json: String?): List<ApiProfile> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                ApiProfile(
                    name = o.optString("name", "API ${i + 1}"),
                    spec = o.optString("spec", "openai"),
                    baseUrl = o.optString("baseUrl", ""),
                    apiKey = CryptoUtil.decrypt(o.optString("keyEnc", "")),
                    model = o.optString("model", "")
                )
            }.take(MAX)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Build the profile JSON exactly as it should appear inside a backup file: identical shape to
     * the on-disk form, keys encrypted. Kept separate so backup code reads clearly even though it
     * currently delegates to [serialize].
     */
    fun serializeForBackup(profiles: List<ApiProfile>): String = serialize(profiles, encryptKeys = true)

    /**
     * The default name for a newly-added profile: "API N" where N is the *smallest positive
     * integer not currently used as an auto-name*. Only names of the exact form "API <number>"
     * count as taken; a custom name like "Cerebras" frees its old number for reuse.
     *
     * Example: profiles named ["API 2", "Cerebras"] → next default is "API 1" (1 is free), not
     * "API 3". Deleting "API 1" from ["API 1", "API 2"] then adding → "API 1" again.
     */
    fun nextDefaultName(existing: List<ApiProfile>): String {
        val taken = existing.mapNotNull { p ->
            Regex("^API (\\d+)$").find(p.name.trim())?.groupValues?.get(1)?.toIntOrNull()
        }.toSet()
        var n = 1
        while (n in taken) n++
        return "API $n"
    }
}
