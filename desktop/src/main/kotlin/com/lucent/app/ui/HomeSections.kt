package com.lucent.app.ui

/**
 * Splits a home list into three labelled sections — **Recent**, **Today**, and **Older** — instead
 * of one long undifferentiated scroll.
 *
 * - **Recent** holds up to [maxRecent] of the most *active* items, ranked by the usage-frequency
 *   score (see [com.lucent.app.data.UsageTracker]) — the things you keep coming back to or just
 *   edited, surfaced whether or not they happen to be from today.
 * - **Today** holds the rest of the items whose own timestamp falls on the current calendar day.
 * - **Older** holds everything else.
 *
 * The three are **disjoint**: an item chosen for Recent never also appears under Today or Older, so
 * nothing is shown twice. Within Recent, items are ordered by score (most active first); Today and
 * Older keep whatever order the caller already sorted them into (the user's chosen sort), so picking
 * "Title A–Z" still orders those sections alphabetically.
 */
enum class HomeSection {
    RECENT, TODAY, OLDER;

    // Live i18n lookup (localization task); call sites keep reading `section.label`.
    val label: String
        get() = when (this) {
            RECENT -> com.lucent.app.i18n.S.sectionRecent
            TODAY -> com.lucent.app.i18n.S.sectionToday
            OLDER -> com.lucent.app.i18n.S.sectionOlder
        }
}

data class Sectioned<T>(
    val recent: List<T>,
    val today: List<T>,
    val older: List<T>
) {
    /** Section/list pairs in display order, skipping any that are empty. */
    fun nonEmpty(): List<Pair<HomeSection, List<T>>> = buildList {
        if (recent.isNotEmpty()) add(HomeSection.RECENT to recent)
        if (today.isNotEmpty()) add(HomeSection.TODAY to today)
        if (older.isNotEmpty()) add(HomeSection.OLDER to older)
    }
}

fun <T> sectionHomeItems(
    items: List<T>,
    now: Long,
    maxRecent: Int,
    id: (T) -> Long,
    timestamp: (T) -> Long,
    activityScore: (T) -> Double
): Sectioned<T> {
    if (items.isEmpty()) return Sectioned(emptyList(), emptyList(), emptyList())

    // Pick the most active items for Recent. Ties fall back to the newer timestamp so the choice is
    // stable and sensible rather than arbitrary.
    val recent = items
        .sortedWith(
            compareByDescending<T> { activityScore(it) }.thenByDescending { timestamp(it) }
        )
        .take(maxRecent.coerceAtLeast(0))
    val recentIds = recent.map(id).toHashSet()

    val remaining = items.filter { id(it) !in recentIds }
    val today = remaining.filter { sameLocalDay(timestamp(it), now) }
    val older = remaining.filter { !sameLocalDay(timestamp(it), now) }

    return Sectioned(recent = recent, today = today, older = older)
}
