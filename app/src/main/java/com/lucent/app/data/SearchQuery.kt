package com.lucent.app.data

/**
 * Structured search over notes and tasks: free-text terms, quoted phrases, and field filters,
 * combined with AND, plus a relevance score so the best hit lands at the top instead of merely the
 * most recently edited one.
 *
 * ### Why this and not SQLite FTS
 *
 * The obvious "grown-up" answer is a full-text index — FTS5, or Room's `@Fts4`. It was tried and
 * deliberately abandoned, and the decisive reason has nothing to do with performance.
 *
 * **No built-in FTS tokeniser segments CJK text.** `simple`, `porter`, and `unicode61` all split on
 * whitespace and punctuation. Chinese, Japanese, and Korean don't use whitespace between words, so a
 * whole run of Han characters becomes one indivisible token — and an index built from those tokens
 * can only ever match the *entire* run. Search a Chinese note for a two-character word buried inside
 * a longer phrase, and an FTS index returns nothing at all: the word is not a token, only the phrase
 * is. It would not fail loudly. It would simply, silently, stop finding the user's notes — in
 * exchange for a speed-up nobody would have noticed. For an app whose owner writes in Chinese, that
 * is not a trade-off, it is a regression. (Segmenting CJK properly means shipping an ICU tokeniser or
 * a dictionary: a large dependency and an entire second problem.)
 *
 * Two lesser reasons point the same way. Room supports only FTS3/FTS4, not FTS5, because FTS5 isn't
 * present in the SQLite bundled with older Android versions. And a Room FTS entity means hand-writing
 * a virtual table and its sync triggers inside a schema migration, which Room validates against a
 * compile-time hash on every open — get one column or one trigger wrong and the app doesn't degrade,
 * it throws on database open and won't start at all. This repository is built on CI with no local
 * toolchain, so an unbootable database is a far worse outcome than a scan.
 *
 * So matching stays **substring-based**, which behaves identically in every script. What *did* move
 * into SQLite is the part that actually pays: [com.lucent.app.data.NoteDao.searchNotes] and
 * [com.lucent.app.data.TaskDao.searchTasks] run the coarse `LIKE` and the structural filters
 * (archived, done, trashed, priority, due window) as SQL predicates, so a search never drags the
 * whole database into memory. The expressive part — phrases, `has:`, `link:`, and relevance ranking —
 * then runs in Kotlin over the small candidate set the database handed back. Indexing was the half of
 * the "indexed search" advice that would have cost more than it returned; pushing the filter down was
 * the half that was worth taking.
 *
 * ### Supported syntax
 *
 * ```
 *   milk bread          both words must appear somewhere
 *   "shopping list"     exact phrase
 *   tag:work  #work     has that tag
 *   is:pinned           pinned
 *   is:checklist        checklist-mode note
 *   is:archived         archived note (otherwise archived notes are simply not in scope)
 *   is:done             completed task
 *   is:overdue          task past its due time and not done
 *   has:attachment      has at least one attached file
 *   has:due             has a due date
 *   has:reminder        has a reminder armed
 *   has:subtasks        has at least one subtask
 *   priority:high       none | low | medium | high
 *   due:today           today | tomorrow | week (next 7 days) | overdue
 *   link:Recipes        note links to [[Recipes]]
 * ```
 *
 * Anything that doesn't parse as a filter is treated as an ordinary search term, so a stray colon
 * never turns a search into an error — it just searches for the literal text.
 */
data class SearchQuery(
    val terms: List<String> = emptyList(),
    val phrases: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val flags: Set<String> = emptySet(),
    val has: Set<String> = emptySet(),
    val priority: TaskPriority? = null,
    val due: DueWindow? = null,
    val linkTo: String? = null
) {

    enum class DueWindow { TODAY, TOMORROW, WEEK, OVERDUE }

    /** True when nothing was typed, or only whitespace — the caller should show everything. */
    val isEmpty: Boolean
        get() = terms.isEmpty() && phrases.isEmpty() && tags.isEmpty() && flags.isEmpty() &&
            has.isEmpty() && priority == null && due == null && linkTo == null

    // -----------------------------------------------------------------------------------------
    // Scope
    // -----------------------------------------------------------------------------------------

    /**
     * True when the query contains a filter only tasks can satisfy.
     *
     * The global search screen uses this to *suppress the notes half entirely* rather than showing an
     * unfiltered list of notes beside a filtered list of tasks. Searching `is:overdue` and getting
     * back every note that happens to contain a word is not a smaller kind of right — it's wrong, and
     * it's the kind of wrong that makes people stop trusting a search box.
     */
    val isTaskOnly: Boolean
        get() = priority != null || due != null ||
            "done" in flags || "overdue" in flags ||
            "due" in has || "reminder" in has

    /** The mirror image: a filter only a note can satisfy. */
    val isNoteOnly: Boolean
        get() = tags.isNotEmpty() || linkTo != null ||
            "archived" in flags || "checklist" in flags

    // -----------------------------------------------------------------------------------------
    // SQL projection
    // -----------------------------------------------------------------------------------------
    //
    // These push the *coarse* half of the query down into SQLite (see NoteDao.searchNotes /
    // TaskDao.searchTasks), so a search never drags the whole database into memory just to throw most
    // of it away in Kotlin. The database narrows; Kotlin then applies the parts SQL can't express —
    // phrases, has:, link:, and relevance ranking — over the small candidate set that comes back.
    //
    // The projection is deliberately *permissive*: it must never exclude a row that the Kotlin
    // matcher would have kept, or a result would silently vanish. Being too generous only costs a few
    // rows of scanning; being too strict loses the user's note.

    /** The single strongest text fragment, for SQL's `LIKE`. Empty means "no text constraint". */
    val sqlText: String
        get() = (phrases + terms).maxByOrNull { it.length } ?: ""

    /** One tag for SQL to pre-filter on. Extra tags are enforced in Kotlin. */
    val sqlTag: String
        get() = tags.firstOrNull() ?: ""

    /** 1 = archived only, 0 = not archived, -1 = don't care. */
    val sqlArchived: Int
        get() = if ("archived" in flags) 1 else FILTER_ANY

    /**
     * 1 = trashed only, 0 = not trashed, -1 = both.
     *
     * The global search screen deliberately passes -1: a note you deleted last week is exactly the
     * thing you cannot find any other way, and refusing to surface it — while it still exists, still
     * restorable, one tap away — would make search useless at the precise moment it matters most.
     * Results say "In trash" so opening one is never a surprise.
     */
    val sqlTrashed: Int get() = FILTER_ANY

    /** 1 = done only, 0 = pending only, -1 = both. */
    val sqlDone: Int
        get() = if ("done" in flags) 1 else FILTER_ANY

    /** Priority floor, or -1. `priority:high` means high, not "high or above", so it's an exact
     *  match in Kotlin — SQL only uses it to skip rows that certainly cannot qualify. */
    val sqlMinPriority: Int
        get() = priority?.value ?: FILTER_ANY

    /** Upper bound on dueAt, or -1. Widened to the end of the day, since SQL compares instants. */
    fun sqlDueBefore(now: Long = System.currentTimeMillis()): Long = when (due) {
        DueWindow.TODAY -> endOfDay(now, 0)
        DueWindow.TOMORROW -> endOfDay(now, 1)
        DueWindow.WEEK -> endOfDay(now, 7)
        DueWindow.OVERDUE -> now
        null -> NO_TIME_FILTER
    }

    /** Lower bound on dueAt, or -1. */
    fun sqlDueAfter(now: Long = System.currentTimeMillis()): Long = when (due) {
        DueWindow.TODAY -> startOfDay(now, 0)
        DueWindow.TOMORROW -> startOfDay(now, 1)
        DueWindow.WEEK -> startOfDay(now, 0)
        // "Overdue" has no floor — a task due in 1970 is still overdue.
        DueWindow.OVERDUE, null -> NO_TIME_FILTER
    }

    // ---------------------------------------------------------------------------------------
    // Matching
    // ---------------------------------------------------------------------------------------

    fun matches(note: Note): Boolean {
        if (isEmpty) return true

        // Text: every term and every phrase must appear somewhere in the note's searchable text.
        val haystack = noteHaystack(note)
        if (terms.any { !haystack.contains(it) }) return false
        if (phrases.any { !haystack.contains(it) }) return false

        val noteTags = note.tags.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (tags.any { wanted -> noteTags.none { it == wanted || it.contains(wanted) } }) return false

        if ("pinned" in flags && !note.pinned) return false
        if ("checklist" in flags && !note.isChecklist) return false
        if ("archived" in flags && !note.archived) return false
        // Task-only flags never match a note, so a query like `is:done` correctly returns no notes
        // rather than silently ignoring the filter and returning all of them.
        if ("done" in flags || "overdue" in flags) return false

        if ("attachment" in has && Attachments.parse(note.attachments).isEmpty()) return false
        if ("subtasks" in has && Checklist.parse(note.checklist).isEmpty()) return false
        if ("due" in has || "reminder" in has) return false

        if (priority != null || due != null) return false

        linkTo?.let { target ->
            if (NoteLinks.linkTargets(note).none { it.lowercase().contains(target) }) return false
        }
        return true
    }

    fun matches(task: Task, now: Long = System.currentTimeMillis()): Boolean {
        if (isEmpty) return true

        val haystack = taskHaystack(task)
        if (terms.any { !haystack.contains(it) }) return false
        if (phrases.any { !haystack.contains(it) }) return false

        // Tasks have no tags, so a tag: filter can never match one.
        if (tags.isNotEmpty()) return false

        if ("pinned" in flags && !task.pinned) return false
        if ("done" in flags && !task.isDone) return false
        if ("overdue" in flags && !isOverdue(task, now)) return false
        // Note-only flags never match a task, for the same reason as above.
        if ("checklist" in flags || "archived" in flags) return false

        if ("attachment" in has && Attachments.parse(task.attachments).isEmpty()) return false
        if ("subtasks" in has && Checklist.parse(task.subtasks).isEmpty()) return false
        if ("due" in has && task.dueAt == null) return false
        if ("reminder" in has && !(task.reminderEnabled && task.dueAt != null)) return false

        priority?.let { if (task.priority != it.value) return false }

        due?.let { window ->
            val dueAt = task.dueAt ?: return false
            if (!inWindow(dueAt, window, task, now)) return false
        }

        if (linkTo != null) return false
        return true
    }

    // ---------------------------------------------------------------------------------------
    // Ranking
    // ---------------------------------------------------------------------------------------

    /**
     * How well a note matches, higher is better. A title hit outranks a tag hit, which outranks a
     * body hit, because a person searching "budget" almost always wants the note *called* budget,
     * not the twelve notes that mention the word in passing. Zero is a perfectly valid score: it
     * just means the row matched on filters alone (`is:pinned` with no search terms), in which case
     * every candidate ties and the caller's chosen sort order decides, exactly as it should.
     */
    fun rank(note: Note): Int {
        if (isEmpty) return 0
        val title = note.title.lowercase()
        val tagText = note.tags.lowercase()
        val body = noteBodyText(note).lowercase()
        var score = 0
        (terms + phrases).forEach { needle ->
            if (title == needle) score += 100
            else if (title.startsWith(needle)) score += 50
            else if (title.contains(needle)) score += 30
            if (tagText.contains(needle)) score += 12
            if (body.contains(needle)) score += 5
        }
        return score
    }

    /** Same idea for tasks: title first, then the free-text notes, then the subtask texts. */
    fun rank(task: Task): Int {
        if (isEmpty) return 0
        val title = task.title.lowercase()
        val notesText = task.notes.lowercase()
        val subtaskText = Checklist.parse(task.subtasks).joinToString(" ") { it.text }.lowercase()
        var score = 0
        (terms + phrases).forEach { needle ->
            if (title == needle) score += 100
            else if (title.startsWith(needle)) score += 50
            else if (title.contains(needle)) score += 30
            if (notesText.contains(needle)) score += 6
            if (subtaskText.contains(needle)) score += 4
        }
        return score
    }

    // ---------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------

    private fun noteBodyText(note: Note): String =
        if (note.isChecklist) Checklist.parse(note.checklist).joinToString(" ") { it.text } else note.body

    private fun noteHaystack(note: Note): String =
        listOf(note.title, noteBodyText(note), note.tags).joinToString(" ").lowercase()

    private fun taskHaystack(task: Task): String =
        listOf(
            task.title,
            task.notes,
            Checklist.parse(task.subtasks).joinToString(" ") { it.text }
        ).joinToString(" ").lowercase()

    private fun isOverdue(task: Task, now: Long): Boolean {
        val dueAt = task.dueAt ?: return false
        return !task.isDone && dueAt < now
    }

    private fun inWindow(dueAt: Long, window: DueWindow, task: Task, now: Long): Boolean {
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val dueDate = java.time.Instant.ofEpochMilli(dueAt).atZone(zone).toLocalDate()
        return when (window) {
            DueWindow.TODAY -> dueDate == today
            DueWindow.TOMORROW -> dueDate == today.plusDays(1)
            // "This week" means the next seven days *including today*, not the calendar week —
            // "what's coming up" is a rolling question, and a Sunday-evening search for due:week
            // that returned nothing because the calendar week ends in four hours would be useless.
            DueWindow.WEEK -> !dueDate.isBefore(today) && !dueDate.isAfter(today.plusDays(7))
            DueWindow.OVERDUE -> isOverdue(task, now)
        }
    }

    companion object {

        /** "This filter is not set." Used across the SQL projection, where null isn't available. */
        const val FILTER_ANY = -1

        /** "No time bound." */
        const val NO_TIME_FILTER = -1L

        /**
         * The operator tokens offered as tappable chips above the search box.
         *
         * Tapping one appends it to the query, which is how anyone actually discovers that a filter
         * syntax exists at all. A help dialog documents it; a row of chips *teaches* it — nobody ever
         * guessed `has:attachment`, and a feature only findable by reading the source was built for
         * nobody. These are only the operators worth one tap; the full grammar is in [HELP].
         */
        val HINTS: List<String> = listOf(
            "tag:", "is:pinned", "is:done", "is:overdue", "is:archived", "is:checklist",
            "has:attachment", "has:due", "has:reminder", "has:subtasks",
            "priority:high", "due:today", "due:week", "link:"
        )

        private fun startOfDay(now: Long, daysFromNow: Int): Long =
            java.time.Instant.ofEpochMilli(now)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDate()
                .plusDays(daysFromNow.toLong())
                .atStartOfDay(java.time.ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()

        private fun endOfDay(now: Long, daysFromNow: Int): Long =
            startOfDay(now, daysFromNow + 1) - 1

        /** Shown in the search help sheet. Kept next to the parser so the two can't drift apart. */
        // A getter, not a stored list, so the *meanings* column re-reads the active language
        // every time the tips dialog opens (localization task). The syntax column stays literal:
        // those are the operators the parser actually accepts.
        val HELP: List<Pair<String, String>>
            get() = listOf(
                "milk bread" to com.lucent.app.i18n.S.helpBothWords,
                "\"shopping list\"" to com.lucent.app.i18n.S.helpExactPhrase,
                "tag:work  #work" to com.lucent.app.i18n.S.helpTag,
                "is:pinned" to com.lucent.app.i18n.S.helpPinned,
                "is:checklist" to com.lucent.app.i18n.S.helpChecklist,
                "is:archived" to com.lucent.app.i18n.S.helpArchived,
                "is:done" to com.lucent.app.i18n.S.helpDone,
                "is:overdue" to com.lucent.app.i18n.S.helpOverdue,
                "has:attachment" to com.lucent.app.i18n.S.helpHasAttachment,
                "has:due" to com.lucent.app.i18n.S.helpHasDue,
                "has:reminder" to com.lucent.app.i18n.S.helpHasReminder,
                "has:subtasks" to com.lucent.app.i18n.S.helpHasSubtasks,
                "priority:high" to com.lucent.app.i18n.S.helpPriority,
                "due:today" to com.lucent.app.i18n.S.helpDue,
                "link:Recipes" to com.lucent.app.i18n.S.helpLink,
                // Last row on purpose: it is the one that changes what every row above it
                // means for a reader who does not type English (task 4).
                "完成 / 完了 / 완료" to com.lucent.app.i18n.S.helpLocalizedFilters
            )

        // =============================================================================
        // Localized filter input (task 4)
        // =============================================================================
        //
        // The chips above the search box have been translated for a while, so a Chinese user taps
        // one and sees 已完成 — and then, typing the same search by hand the next day, discovers
        // that only `is:done` actually filters anything. The filter *language* was English even
        // where the interface was not, which makes the whole feature look like it is for somebody
        // else.
        //
        // The tables below close that gap by canonicalizing a token BEFORE it reaches the parser.
        // Nothing downstream changes: `is:done` is still the one grammar the matcher knows, still
        // what the chips insert, and still what a saved query means. Only the set of spellings that
        // reach it grows.
        //
        // Two deliberate choices:
        //
        //  - **All four languages are accepted at once, whatever the UI is set to.** Language is a
        //    property of the person, not of the app: someone running Lucent in English still thinks
        //    "完了" when they mean done, and a query typed on a phone set to Korean should still
        //    work after switching the interface to Japanese. Keying this off L.current would have
        //    made saved queries silently stop matching when the UI language changed.
        //  - **The standalone words are exactly the chip labels.** What the chip shows you is what
        //    you can type, so the row of chips doubles as the documentation for this feature instead
        //    of being a separate vocabulary to learn.
        //
        // The cost, stated plainly: a note containing the literal word "已完成" can no longer be
        // found by typing that word bare — it now reads as a filter. Quoting it ("已完成") searches
        // for the text, which is the same escape hatch English users already have for `tag:`, and
        // the help sheet says so (helpLocalizedFilters).

        /** Localized whole-token spellings of a filter, mapped to the canonical ASCII operator. */
        private val LOCALIZED_TOKENS: Map<String, String> = buildMap<String, String> {
            fun alias(canonical: String, vararg spellings: String) {
                spellings.forEach { put(it.lowercase(), canonical) }
            }
            // zh / ja / ko spellings, plus the shorter forms people actually type. Where a language
            // writes the chip as a phrase ("첨부 있음"), both the phrase with its space removed and
            // the bare head word are accepted, because a search box is not a place anyone wants to
            // be precise about spacing.
            alias("is:pinned", "已置顶", "置顶", "ピン留め", "固定", "고정됨", "고정")
            alias("is:done", "已完成", "完成", "完了", "완료")
            alias("is:overdue", "已逾期", "逾期", "过期", "期限切れ", "기한초과", "기한지남")
            alias("is:archived", "已归档", "归档", "アーカイブ", "보관됨", "보관")
            alias("is:checklist", "清单", "チェックリスト", "체크리스트")
            alias("has:attachment", "有附件", "附件", "添付あり", "添付", "첨부있음", "첨부")
            alias("has:due", "有截止日", "有截止", "截止日", "截止", "期限あり", "기한있음", "마감있음")
            alias("has:reminder", "有提醒", "提醒", "リマインダー", "알림있음", "알림")
            alias("has:subtasks", "有子任务", "子任务", "サブタスク", "하위작업")
            alias("priority:high", "高优先级", "优先级高", "優先度高", "높은우선순위", "우선순위높음")
            alias("due:today", "今天到期", "今天", "今日期限", "今日", "오늘마감", "오늘")
            alias("due:week", "本周到期", "本周", "今週期限", "今週", "이번주마감", "이번주")
        }

        /** Localized names for the part BEFORE the colon in `field:value`. */
        private val LOCALIZED_FIELDS: Map<String, String> = buildMap<String, String> {
            fun alias(canonical: String, vararg spellings: String) {
                spellings.forEach { put(it.lowercase(), canonical) }
            }
            alias("tag", "标签", "タグ", "태그")
            alias("is", "是", "状态", "状態", "상태")
            alias("has", "有", "含", "あり", "포함")
            alias("priority", "优先级", "優先度", "우선순위")
            alias("due", "到期", "期限", "마감")
            alias("link", "链接", "リンク", "링크")
        }

        /**
         * Localized names for the part AFTER the colon, per canonical field. Kept separate from
         * [LOCALIZED_TOKENS] because the same word means different things in different fields:
         * `due:今天` is a window, while a bare 今天 is the `due:today` filter.
         */
        private val LOCALIZED_VALUES: Map<String, Map<String, String>> = mapOf(
            "is" to buildMap<String, String> {
                listOf("已置顶" to "pinned", "置顶" to "pinned", "ピン留め" to "pinned", "固定" to "pinned", "고정" to "pinned", "고정됨" to "pinned",
                    "已完成" to "done", "完成" to "done", "完了" to "done", "완료" to "done",
                    "已逾期" to "overdue", "逾期" to "overdue", "期限切れ" to "overdue", "기한초과" to "overdue",
                    "已归档" to "archived", "归档" to "archived", "アーカイブ" to "archived", "보관됨" to "archived", "보관" to "archived",
                    "清单" to "checklist", "チェックリスト" to "checklist", "체크리스트" to "checklist"
                ).forEach { (k, v) -> put(k.lowercase(), v) }
            },
            "has" to buildMap<String, String> {
                listOf("附件" to "attachment", "添付" to "attachment", "첨부" to "attachment",
                    "截止" to "due", "截止日" to "due", "期限" to "due", "마감" to "due",
                    "提醒" to "reminder", "リマインダー" to "reminder", "알림" to "reminder",
                    "子任务" to "subtasks", "サブタスク" to "subtasks", "하위작업" to "subtasks"
                ).forEach { (k, v) -> put(k.lowercase(), v) }
            },
            "priority" to buildMap<String, String> {
                listOf("高" to "high", "높음" to "high", "中" to "medium", "보통" to "medium",
                    "低" to "low", "낮음" to "low", "无" to "none", "なし" to "none", "없음" to "none"
                ).forEach { (k, v) -> put(k.lowercase(), v) }
            },
            "due" to buildMap<String, String> {
                listOf("今天" to "today", "今日" to "today", "오늘" to "today",
                    "明天" to "tomorrow", "明日" to "tomorrow", "내일" to "tomorrow",
                    "本周" to "week", "今週" to "week", "이번주" to "week",
                    "逾期" to "overdue", "期限切れ" to "overdue", "기한초과" to "overdue"
                ).forEach { (k, v) -> put(k.lowercase(), v) }
            }
        )

        /**
         * Rewrite one raw token into the canonical ASCII operator grammar, or return it unchanged.
         *
         * Runs before any parsing decision is made, so every rule below it — quoting, `#tag`,
         * unknown-token-is-literal-text — behaves identically no matter which language produced the
         * token. Also normalizes the full-width punctuation a CJK keyboard produces by default:
         * typing 标签：工作 on a Chinese IME gives U+FF1A, not an ASCII colon, and a filter that
         * silently fails because of an invisible codepoint is worse than one that doesn't exist.
         */
        private fun canonicalizeToken(raw: String): String {
            // Full-width colon / hash / number sign -> ASCII. Nothing else is touched: the value
            // half may legitimately contain any character (a tag can be written in any script).
            val token = raw.replace('：', ':').replace('＃', '#')

            LOCALIZED_TOKENS[token.lowercase()]?.let { return it }

            val colon = token.indexOf(':')
            if (colon <= 0 || colon == token.lastIndex) return token

            val rawField = token.substring(0, colon).lowercase()
            val rawValue = token.substring(colon + 1)
            val field = LOCALIZED_FIELDS[rawField] ?: rawField
            // tag: and link: take free text (a tag may be written in any language), so their values
            // are never translated — only the field name is.
            if (field == "tag" || field == "link") return "$field:$rawValue"
            val value = LOCALIZED_VALUES[field]?.get(rawValue.lowercase()) ?: rawValue
            return "$field:$value"
        }

        private val TOKEN = Regex("\"([^\"]*)\"|(\\S+)")

        fun parse(raw: String): SearchQuery {
            val text = raw.trim()
            if (text.isEmpty()) return SearchQuery()

            val terms = mutableListOf<String>()
            val phrases = mutableListOf<String>()
            val tags = mutableListOf<String>()
            val flags = mutableSetOf<String>()
            val has = mutableSetOf<String>()
            var priority: TaskPriority? = null
            var due: DueWindow? = null
            var linkTo: String? = null

            for (match in TOKEN.findAll(text)) {
                val quoted = match.groupValues[1]
                if (match.value.startsWith("\"")) {
                    // A quoted phrase is taken literally, operators and all: searching for
                    // "tag:work" in quotes should find the text, not filter by the tag.
                    if (quoted.isNotBlank()) phrases += quoted.trim().lowercase()
                    continue
                }

                // Localized filters are folded into the canonical ASCII grammar here, once, so
                // everything below this line has exactly one syntax to reason about (task 4).
                val token = canonicalizeToken(match.groupValues[2])
                if (token.isBlank()) continue

                // #tag is the shorthand people already type in the tag field itself.
                if (token.length > 1 && token.startsWith('#')) {
                    tags += token.removePrefix("#").lowercase()
                    continue
                }

                val colon = token.indexOf(':')
                if (colon <= 0 || colon == token.lastIndex) {
                    terms += token.lowercase()
                    continue
                }

                val field = token.substring(0, colon).lowercase()
                val value = token.substring(colon + 1).lowercase()
                when (field) {
                    "tag" -> tags += value
                    "is" -> if (value in setOf("pinned", "checklist", "archived", "done", "overdue")) {
                        flags += value
                    } else {
                        terms += token.lowercase()
                    }
                    "has" -> if (value in setOf("attachment", "attachments", "due", "reminder", "subtasks", "subtask")) {
                        // Accept the plural/singular the user naturally reaches for.
                        has += when (value) {
                            "attachments" -> "attachment"
                            "subtask" -> "subtasks"
                            else -> value
                        }
                    } else {
                        terms += token.lowercase()
                    }
                    "priority", "p" -> priority = TaskPriority.fromKey(value)
                    "due" -> due = when (value) {
                        "today" -> DueWindow.TODAY
                        "tomorrow" -> DueWindow.TOMORROW
                        "week", "7d" -> DueWindow.WEEK
                        "overdue", "late" -> DueWindow.OVERDUE
                        else -> null
                    }.also { if (it == null) terms += token.lowercase() }
                    "link", "links" -> linkTo = value
                    // Not a filter we know — treat the whole token as literal text, so a note
                    // containing "http://x" or "TODO:fix" is still findable by typing it.
                    else -> terms += token.lowercase()
                }
            }

            return SearchQuery(
                terms = terms,
                phrases = phrases,
                tags = tags,
                flags = flags,
                has = has,
                priority = priority,
                due = due,
                linkTo = linkTo
            )
        }
    }
}

/**
 * Keep only the rows a query matches. Ordering is left to the caller (`ui/SortOptions.kt`), which
 * folds relevance in *around* the user's chosen sort rather than replacing it: an explicit "Title
 * A–Z" is the user telling us how they want the list arranged, and silently overriding that the
 * moment they type a letter would be obnoxious. Relevance therefore leads only when the query
 * carries real search terms, and pinned items float above both either way.
 */
// @JvmName is required, not decorative: both overloads erase to filterBySearch(List, SearchQuery)
// on the JVM, so without distinct JVM names they are the same method and the compiler rejects them.
@JvmName("filterNotesBySearch")
fun List<Note>.filterBySearch(query: SearchQuery): List<Note> = filter { query.matches(it) }

@JvmName("filterTasksBySearch")
fun List<Task>.filterBySearch(query: SearchQuery): List<Task> = filter { query.matches(it) }
