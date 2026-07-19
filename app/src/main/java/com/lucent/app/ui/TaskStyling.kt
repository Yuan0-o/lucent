package com.lucent.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.outlined.PushPin as PushPinOutlined
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lucent.app.data.RepeatRule
import com.lucent.app.data.TaskPriority
import com.lucent.app.i18n.S
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ---- Priority colours ----
// Warm-to-cool, so higher priority reads as "hotter" at a glance without needing the label. These
// sit on frosted glass over an animated background, so they're picked mid-tone: bright enough to
// register against a dark backdrop, muted enough not to shout on a light one.
val PriorityHighColor = Color(0xFFE57373)
val PriorityMediumColor = Color(0xFFFFB74D)
val PriorityLowColor = Color(0xFF64B5F6)

/** The colour an overdue due-date is rendered in. Shares the "high priority" red on purpose. */
val OverdueColor = PriorityHighColor

fun TaskPriority.color(): Color = when (this) {
    TaskPriority.NONE -> Color.Transparent
    TaskPriority.LOW -> PriorityLowColor
    TaskPriority.MEDIUM -> PriorityMediumColor
    TaskPriority.HIGH -> PriorityHighColor
}

/** A bare coloured dot, for the tight left edge of a task card. Renders nothing for NONE. */
@Composable
fun PriorityDot(priority: TaskPriority, modifier: Modifier = Modifier, size: Dp = 9.dp) {
    if (priority == TaskPriority.NONE) return
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(priority.color())
            // The label sits next to it in text on the detail page, and the card's own semantics
            // already read the title; a lone "Low priority dot" announcement between them would be
            // noise. Colour here is decoration on top of information that's stated elsewhere.
            .clearAndSetSemantics { }
    )
}

/** A small flag plus its label — used where there's room to say it, not just show it. */
@Composable
fun PriorityBadge(priority: TaskPriority, modifier: Modifier = Modifier) {
    if (priority == TaskPriority.NONE) return
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Default.Flag,
            contentDescription = null,
            tint = priority.color(),
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(3.dp))
        Text(S.priorityBadge(priority.uiLabel), color = priority.color(), fontSize = 12.sp)
    }
}

/**
 * None / Low / Medium / High, always on **one** row (task 2).
 *
 * ### Why these aren't Material `FilterChip`s any more
 *
 * They were, laid out in a `FlowRow`, and on a phone that produced the bug this rework fixes: four
 * chips wrapped onto two lines, with "High" stranded alone on the second. The cause isn't the labels
 * — it's that a `FilterChip` has fixed internal padding (16dp each side, plus its own icon spacing)
 * that no caller can reduce. Four of them simply cannot fit across a phone's content width, and a
 * `FlowRow` does the only thing it can when they don't: it wraps.
 *
 * Wrapping is also *unstable*, which is the worse half of the problem. Whether the row broke at all
 * depended on the chosen typeface and the system font scale, so the composer's layout shifted under
 * the user for reasons they had no way to connect to anything they'd done.
 *
 * So the chips are drawn here instead, and each takes `weight(1f)` — an exact quarter of the width,
 * whatever that width is. The row can't wrap because it has no second line to wrap onto, and the
 * four options stay a single glanceable scale from None to High, which is what a priority control is
 * *for*. Labels are single-line and ellipsised as a last resort, so even an extreme font scale
 * degrades to "Mediu…" on one row rather than silently re-flowing the page.
 */
@Composable
fun PriorityPickerRow(selected: TaskPriority, onSelect: (TaskPriority) -> Unit, modifier: Modifier = Modifier) {
    val onGradient = LocalOnGradient.current
    Column(modifier = modifier.fillMaxWidth()) {
        Text(S.labelPriority, color = onGradient, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TaskPriority.entries.forEach { option ->
                PriorityChip(
                    option = option,
                    selected = option == selected,
                    onSelect = { onSelect(option) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * One quarter-width priority chip: a small flag in the level's own colour, its label, and a fill and
 * rim that light up in that same colour when selected. Selected state is carried by colour *and*
 * border weight rather than colour alone, so it survives a colour-blind reading of the row.
 */
@Composable
private fun PriorityChip(
    option: TaskPriority,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val onGradient = LocalOnGradient.current
    val onGradientMuted = LocalOnGradientMuted.current
    val context = LocalContext.current
    val shape = RoundedCornerShape(10.dp)
    // NONE has no colour of its own (it is the absence of a priority), so it borrows the theme's
    // content colour for its selected state instead of rendering as an invisible transparent chip.
    val accent = if (option == TaskPriority.NONE) onGradient else option.color()
    val fill = if (selected) accent.copy(alpha = 0.20f) else Color.Transparent
    val rim = if (selected) accent.copy(alpha = 0.85f) else onGradientMuted.copy(alpha = 0.40f)

    Row(
        modifier = modifier
            .heightIn(min = 34.dp)
            .clip(shape)
            .background(fill)
            .border(if (selected) 1.5.dp else 1.dp, rim, shape)
            .clickable {
                Haptics.tick(context)
                onSelect()
            }
            .padding(horizontal = 3.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (option != TaskPriority.NONE) {
            Icon(
                Icons.Default.Flag,
                contentDescription = null,
                tint = option.color(),
                modifier = Modifier.size(12.dp)
            )
            Spacer(modifier = Modifier.width(3.dp))
        }
        Text(
            option.label,
            color = if (selected) onGradient else onGradientMuted,
            fontSize = 11.sp,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Repeat cadence chips.
 *
 * The caller only shows this once a due date is set, because recurrence has no meaning without a
 * base instant to advance from (see [com.lucent.app.data.Recurrence]) — a repeat rule on an undated
 * task would be a setting the app could never act on.
 */
@Composable
fun RepeatRuleRow(selected: RepeatRule, onSelect: (RepeatRule) -> Unit, modifier: Modifier = Modifier) {
    val onGradient = LocalOnGradient.current
    val onGradientMuted = LocalOnGradientMuted.current
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Repeat, contentDescription = null, tint = onGradientMuted, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(S.labelRepeat, color = onGradient, fontSize = 14.sp)
        }
        Spacer(modifier = Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RepeatRule.entries.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(option.label) }
                )
            }
        }
    }
}

/**
 * The reminder switch.
 *
 * Disabled — and saying why — until a due date exists, because a reminder needs a moment to fire at.
 * The visible checked state is gated on [hasDueDate] too, so a stale "on" saved earlier can never
 * *look* armed while there's nothing behind it. That matters: a switch that says a reminder is set
 * when none can fire is worse than no switch at all.
 */
@Composable
fun ReminderToggleRow(
    enabled: Boolean,
    hasDueDate: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val onGradient = LocalOnGradient.current
    val onGradientMuted = LocalOnGradientMuted.current
    val armed = enabled && hasDueDate
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (armed) Icons.Default.NotificationsActive else Icons.Default.Notifications,
            contentDescription = null,
            tint = if (armed) onGradient else onGradientMuted
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(S.remindAtDueTime, color = if (hasDueDate) onGradient else onGradientMuted, fontSize = 14.sp)
            if (!hasDueDate) {
                Text(S.setDueDateToEnable, color = onGradientMuted, fontSize = 12.sp)
            }
        }
        Switch(checked = armed, enabled = hasDueDate, onCheckedChange = onToggle)
    }
}

/** Pin/unpin toggle with the same haptic tick every other meaningful tap in the app has. */
@Composable
fun PinIconButton(pinned: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val onGradient = LocalOnGradient.current
    val onGradientMuted = LocalOnGradientMuted.current
    val context = LocalContext.current
    IconButton(
        onClick = {
            Haptics.tick(context)
            onToggle()
        },
        modifier = modifier
    ) {
        Icon(
            if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPinOutlined,
            contentDescription = if (pinned) S.unpin else S.pinToTop,
            tint = if (pinned) onGradient else onGradientMuted
        )
    }
}

/**
 * A non-interactive pin marker for a card that's already carrying its own tap target.
 *
 * Sized to sit level with the title text beside it (roughly a body-text cap height) rather than the
 * former tiny 13dp glyph, which read as a speck next to the title. [size] defaults to that but is
 * overridable for the detail pages, whose titles are larger.
 */
@Composable
fun PinnedMarker(modifier: Modifier = Modifier, size: Dp = 16.dp) {
    val onGradientMuted = LocalOnGradientMuted.current
    Icon(
        Icons.Filled.PushPin,
        contentDescription = S.pinned,
        tint = onGradientMuted,
        modifier = modifier.size(size)
    )
}

// ---- Due-date labelling ----

private val dueDayFormatter get() = com.lucent.app.i18n.LDates.of(S.patternMonthDay)
private val dueTimeFormatter get() = com.lucent.app.i18n.LDates.of(S.patternTime)

/** True when a pending task's due time has already passed. A completed task is never overdue. */
fun isOverdue(dueAt: Long?, isDone: Boolean): Boolean {
    if (dueAt == null || isDone) return false
    return dueAt < System.currentTimeMillis()
}

/**
 * A due date phrased the way a person would say it: "Today 3:00 PM", "Tomorrow 9:00 AM", "Overdue ·
 * Jul 3", or "Jul 8 · 2:00 PM".
 *
 * Relative wording for the two days that matter, an explicit marker once a deadline has passed, and
 * a plain date beyond that. The whole point is that "tomorrow" is instantly legible in a way that
 * "Jul 14, 9:00 AM" is not, and that the difference between *late* and *soon* should be readable
 * without doing arithmetic against today's date in your head.
 */
fun friendlyDue(dueAt: Long): String {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val zoned = Instant.ofEpochMilli(dueAt).atZone(zone)
    val date = zoned.toLocalDate()
    val time = zoned.format(dueTimeFormatter)
    return when {
        date.isEqual(today) -> S.dueTodayAt(time)
        date.isEqual(today.plusDays(1)) -> S.dueTomorrowAt(time)
        date.isEqual(today.minusDays(1)) -> S.dueYesterdayAt(time)
        date.isBefore(today) -> S.dueOverdueOn(zoned.format(dueDayFormatter))
        else -> S.dueOn(zoned.format(dueDayFormatter), time)
    }
}

/**
 * The localized display name for a priority (localization task). [TaskPriority.label] itself
 * stays English on purpose: it feeds the assistant's tool results and file exports' data columns,
 * which must remain stable for the model; the UI reads this instead.
 */
val TaskPriority.uiLabel: String
    get() = when (this) {
        TaskPriority.NONE -> S.priorityNone
        TaskPriority.LOW -> S.priorityLow
        TaskPriority.MEDIUM -> S.priorityMedium
        TaskPriority.HIGH -> S.priorityHigh
    }

/** The localized display name for a repeat rule; same reasoning as [TaskPriority.uiLabel]. */
val RepeatRule.uiLabel: String
    get() = when (this) {
        RepeatRule.NONE -> S.repeatNone
        RepeatRule.DAILY -> S.repeatDaily
        RepeatRule.WEEKLY -> S.repeatWeekly
        RepeatRule.MONTHLY -> S.repeatMonthly
        RepeatRule.YEARLY -> S.repeatYearly
    }
