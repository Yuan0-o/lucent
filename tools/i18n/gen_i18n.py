#!/usr/bin/env python3
"""Generate app/src/main/java/com/lucent/app/i18n/I18n.kt from catalog.py.

Catalog entry forms:
  ("key", en, zh, ja, ko)                      -> open val key: String = "en" / override ...
  ("key(param: Type, ...)", en, zh, ja, ko)    -> open fun key(...): String = "en with ${param}"
  "// comment"                                  -> emitted verbatim (indented) into Tr ONLY,
                                                   so notes travel with the keys they describe;
                                                   an empty string item emits a blank line.
Templates use {param}; converted to Kotlin ${param}. Quotes/backslashes are escaped.
A missing (None) translation simply omits the override -> falls back to English.
"""
import os, re, sys, importlib.util

# Both paths are script-relative so the tool runs from any checkout location:
#   tools/i18n/gen_i18n.py  ->  catalog beside it, output inside app/.
_HERE = os.path.dirname(os.path.abspath(__file__))
_REPO = os.path.abspath(os.path.join(_HERE, "..", ".."))

spec = importlib.util.spec_from_file_location("catalog", os.path.join(_HERE, "catalog.py"))
cat = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cat)

ENTRIES = cat.ENTRIES

def esc(s: str) -> str:
    s = s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$")
    s = s.replace("\n", "\\n")
    # {param} -> ${param}
    return re.sub(r"\\?\{(\w+)\}", r"${\1}", s)

def is_fn(key: str) -> bool:
    return "(" in key

def fn_name(key: str) -> str:
    return key.split("(")[0]

seen = set()
for e in ENTRIES:
    if isinstance(e, str):
        continue  # a comment line, not a key
    k = fn_name(e[0]) if is_fn(e[0]) else e[0]
    if k in seen:
        sys.exit(f"DUPLICATE KEY: {k}")
    seen.add(k)

def base_decl(key, en):
    if is_fn(key):
        return f'    open fun {key}: String = "{esc(en)}"'
    return f'    open val {key}: String = "{esc(en)}"'

def override_decl(key, val):
    if val is None:
        return None
    if is_fn(key):
        return f'    override fun {key}: String = "{esc(val)}"'
    return f'    override val {key}: String = "{esc(val)}"'

def lang_object(name, idx):
    lines = [f"object {name} : Tr() {{"]
    for e in ENTRIES:
        if isinstance(e, str):
            continue  # comments are emitted into Tr only
        d = override_decl(e[0], e[idx])
        if d:
            lines.append(d)
    lines.append("}")
    return "\n".join(lines)

header = '''package com.lucent.app.i18n

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The app's runtime localization system (localization task: Chinese, English, Japanese, Korean).
 *
 * ### Why a Kotlin catalog instead of res/values-xx strings
 *
 * Lucent's UI text lives inline in composables, and the language is an *in-app* setting that must
 * switch instantly, for the whole app, without recreating the Activity and without depending on
 * the device locale. So the catalog is Kotlin: [Tr] declares every string in English, and each
 * language object overrides them. What that buys, concretely:
 *
 *  - **A missing translation can never blank the UI.** An un-overridden entry inherits its English
 *    base — the safety net is structural, not a runtime lookup that can miss.
 *  - **A typo cannot ship.** `S.saev` fails compilation; `override val saev` fails compilation.
 *  - **Instant switching.** [L.current] is Compose snapshot state, so every composable that reads
 *    [S] recomposes the moment the setting changes — no restart, no flash of the old language.
 *
 * (The launcher widgets are the one surface that can't read app state, so they localize the
 * classic way through res/values-*; see res/values-zh-rCN|ja|ko/strings.xml.)
 *
 * This file is GENERATED from the translation catalog (see returned project notes); edit
 * translations there, not here, or the two will drift.
 */
enum class AppLanguage(val key: String, val label: String) {
    SYSTEM("system", "System"),
    EN("en", "English"),
    ZH("zh", "\\u4e2d\\u6587"),
    JA("ja", "\\u65e5\\u672c\\u8a9e"),
    KO("ko", "\\ud55c\\uad6d\\uc5b4");

    companion object {
        fun fromKey(key: String?): AppLanguage = entries.firstOrNull { it.key == key } ?: SYSTEM

        /** What "follow the system" resolves to on this device right now. */
        fun systemDefault(): AppLanguage {
            val tag = java.util.Locale.getDefault().language.lowercase()
            return when {
                tag.startsWith("zh") -> ZH
                tag.startsWith("ja") -> JA
                tag.startsWith("ko") -> KO
                else -> EN
            }
        }
    }
}

/** Language state holder. [current] is snapshot state so composables follow it automatically. */
object L {
    var current: Tr by mutableStateOf(resolve(AppLanguage.SYSTEM))
        private set

    var language: AppLanguage = AppLanguage.SYSTEM
        private set

    fun apply(key: String?) {
        language = AppLanguage.fromKey(key)
        current = resolve(language)
    }

    private fun resolve(lang: AppLanguage): Tr = when (
        if (lang == AppLanguage.SYSTEM) AppLanguage.systemDefault() else lang
    ) {
        AppLanguage.ZH -> Zh
        AppLanguage.JA -> Ja
        AppLanguage.KO -> Ko
        else -> En
    }
}

/** The string table for the active language. `S.key` anywhere; recomposes on switch. */
val S: Tr get() = L.current

/** The java.util.Locale matching the ACTIVE UI language (after resolving "system"). */
fun lucentLocale(): java.util.Locale = when (
    if (L.language == AppLanguage.SYSTEM) AppLanguage.systemDefault() else L.language
) {
    AppLanguage.ZH -> java.util.Locale.SIMPLIFIED_CHINESE
    AppLanguage.JA -> java.util.Locale.JAPANESE
    AppLanguage.KO -> java.util.Locale.KOREAN
    else -> java.util.Locale.ENGLISH
}

/**
 * Date formatters that follow the app language. Patterns themselves are catalog entries (see
 * Tr.patternMonthDay etc.), so Chinese gets its own month-day shape rather than an English
 * pattern rendered with a Chinese locale. Cached per pattern and invalidated when the language
 * changes, so list rows can
 * call this every bind without re-parsing patterns.
 */
object LDates {
    private var cachedFor: Tr? = null
    private val cache = HashMap<String, java.time.format.DateTimeFormatter>()

    fun of(pattern: String): java.time.format.DateTimeFormatter {
        val now = L.current
        if (cachedFor !== now) {
            cache.clear()
            cachedFor = now
        }
        return cache.getOrPut(pattern) { java.time.format.DateTimeFormatter.ofPattern(pattern, lucentLocale()) }
    }
}

'''

out = [header]
out.append("open class Tr {")
for e in ENTRIES:
    if isinstance(e, str):
        out.append("    " + e if e else "")
    else:
        out.append(base_decl(e[0], e[1]))
out.append("}")
out.append("")
out.append("object En : Tr()")
out.append("")
out.append(lang_object("Zh", 2))
out.append("")
out.append(lang_object("Ja", 3))
out.append("")
out.append(lang_object("Ko", 4))
out.append("")

path = os.path.join(_REPO, "app", "src", "main", "java", "com", "lucent", "app", "i18n", "I18n.kt")
with open(path, "w", encoding="utf-8") as f:
    f.write("\n".join(out))
print(f"Wrote {path}: {len(ENTRIES)} entries")
