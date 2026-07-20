package com.lucent.app.data

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Turns the due-date string the assistant supplies into a real instant, and back again.
 *
 * Only *absolute* dates are accepted, on purpose. The assistant is told the current local date and
 * asked to resolve "next Friday" itself before calling a tool, which keeps this parser
 * deterministic, locale-safe, and free of the guessing that natural-language date parsing invites.
 * A model that ignores that and passes "tomorrow" gets a clean, honest failure rather than a wrong
 * date silently stored.
 *
 * Accepted, most specific first:
 *   - a full ISO-8601 instant with a zone/offset — `2026-07-15T14:00:00Z`
 *   - a zone-less local date-time — `2026-07-15T14:00`, `2026-07-15 14:00` (device's own time zone,
 *     exactly like the due-date picker in the UI)
 *   - a bare local date — `2026-07-15`, which defaults to 09:00 rather than literal midnight,
 *     because "remind me on Wednesday" almost never means 00:00
 */
object DueParsing {

    private val LOCAL_PATTERNS = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSS",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm"
    )

    /** The hour a bare date defaults to when no time of day was given. */
    private const val DEFAULT_HOUR = 9

    /** Absolute, unambiguous form the tools *report* dates in — the same form they accept. */
    private val OUT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)

    fun parse(input: String?): Long? {
        val s = input?.trim().orEmpty()
        if (s.isEmpty()) return null

        // A full instant ("...Z" or "+02:00") already carries its own zone.
        try {
            return Instant.parse(s).toEpochMilli()
        } catch (t: Throwable) {
            // Not a zoned instant — fall through to the zone-less forms.
        }

        for (pattern in LOCAL_PATTERNS) {
            try {
                val formatter = DateTimeFormatter.ofPattern(pattern, Locale.US)
                return LocalDateTime.parse(s, formatter)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            } catch (t: Throwable) {
                // Try the next pattern.
            }
        }

        return try {
            LocalDate.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US))
                .atTime(DEFAULT_HOUR, 0)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        } catch (t: Throwable) {
            null
        }
    }

    /** The words the assistant may pass to explicitly clear a due date. */
    fun isClearRequest(input: String?): Boolean {
        val s = input?.trim()?.lowercase() ?: return false
        return s in setOf("", "none", "clear", "remove", "no", "unset", "null")
    }

    /**
     * Format an instant for the assistant to read back. Deliberately the same `yyyy-MM-dd HH:mm`
     * shape the tools accept, so a model reads a due date back in exactly the form it would write
     * one — no round-trip ambiguity. The assistant is separately instructed to phrase it naturally
     * ("tomorrow at 9") when speaking to the person.
     */
    fun format(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(OUT_FORMAT)
}
