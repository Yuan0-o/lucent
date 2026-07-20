package com.lucent.app.ui

import android.app.DatePickerDialog
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Calendar

/**
 * The primary pill button used for "Edit note" / "Edit task" / "Archive".
 *
 * ### Why the Haze blur was removed (task 2)
 *
 * This used to run a live Haze blur of the background behind it, on the theory that a genuine blur
 * is more "liquid glass" than a flat translucent fill. In practice it looked *dirty*: the blur
 * sampled the shared background layer and smeared a lump of whatever colour happened to be nearby
 * across the inside of the button — a lavender-grey bruise sitting in one corner with no relation to
 * anything behind it. On a small, isolated pill there is nothing for a blur to reveal anyway; a blur
 * only reads as glass when there is legible content behind it to be softened, and behind a button
 * 48dp tall there is one flat gradient. All it could contribute was the artefact.
 *
 * So the effect is gone. The button now uses the same honest, flat frosted treatment as the cards:
 * a translucent fill, a faint top sheen, a hairline rim — theme-aware in the same direction as
 * everything else (lighter than the backdrop on dark, smoked on light). It is quieter, it is
 * consistent with every other surface in the app, and it cannot smear.
 */
@Composable
fun GlassCapsuleButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val onGradient = LocalOnGradient.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val shape = RoundedCornerShape(percent = 50)
    val glassDark = isDarkGlass()
    val fill = Color.White.copy(
        alpha = if (glassDark) LucentGlass.CARD_FILL_DARK else LucentGlass.CARD_FILL_LIGHT
    )
    val rim = lucentGlassRim(strong = true)
    Row(
        modifier = modifier
            // Fill and rim, nothing else — the same subtraction the cards went through. No shadow
            // (it creates a graphicsLayer, which is what put a pale block inside every card), and no
            // sheen overlay on top of the fill.
            .clip(shape)
            .background(fill)
            .border(1.dp, rim, shape)
            .clickable {
                Haptics.tick(context)
                onClick()
            }
            .padding(horizontal = 28.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = null, tint = onGradient)
        Spacer(modifier = Modifier.width(8.dp))
        Text(text, color = onGradient, fontSize = 16.sp)
    }
}

/**
 * The app's standard button, in the same glass as everything else (task 11).
 *
 * ### Why this exists
 *
 * Lucent draws one material — a translucent fill, a hairline gradient rim, no shadow — and every
 * surface in it obeys that rule except the buttons on Settings > Data, which were still Material 3's
 * `Button`. M3 draws a *filled, opaque* pill in the theme's primary colour, so the Data page ended
 * up with six solid lilac slabs and four solid red ones sitting on top of a page made entirely of
 * glass. Not ugly in isolation — it is a perfectly good button — but visibly borrowed, which is the
 * one thing a design language cannot afford at its most consequential screen: the page where the
 * buttons erase your notes should not be the page that looks like it came from somewhere else.
 *
 * So this is the same subtraction [GlassCapsuleButton] went through, generalized: fill, rim, label.
 * No `Modifier.shadow` anywhere near it — a shadow creates a `graphicsLayer`, and one of those
 * nested inside the `hazeSource` container that captures the background is what once painted a pale
 * rectangle inside every card in the app.
 *
 * [danger] switches the tint to red for destructive actions, keeping the *material* identical while
 * making the meaning unmistakable — the difference between "this is a button" and "this button
 * deletes things" should be carried by colour, not by suddenly changing what buttons are made of.
 *
 * A disabled button fades rather than disappearing, and swallows its tap: callers that want a
 * *reason* shown on tap (the greyed-out controls in local-model mode, task 8) keep [enabled] true
 * and answer inside [onClick] with a bottom toast instead — a dead control that ignores touches
 * teaches nothing.
 */
@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    danger: Boolean = false
) {
    val onGradient = LocalOnGradient.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val shape = RoundedCornerShape(percent = 50)
    val glassDark = isDarkGlass()

    // Danger keeps the glass and changes only the hue: a red wash, a red rim, red text. The two
    // alphas differ by theme for the same reason every other surface's do — a light wash reads on a
    // dark backdrop and washes out on a light one.
    val dangerHue = Color(0xFFE5484D)
    val fill = when {
        danger && glassDark -> dangerHue.copy(alpha = 0.22f)
        danger -> dangerHue.copy(alpha = 0.16f)
        glassDark -> Color.White.copy(alpha = LucentGlass.CARD_FILL_DARK)
        else -> Color.White.copy(alpha = LucentGlass.CARD_FILL_LIGHT)
    }
    val border = if (danger) dangerHue.copy(alpha = if (glassDark) 0.55f else 0.45f) else Color.Transparent
    val label = when {
        danger && glassDark -> Color(0xFFFFB4AB)
        danger -> Color(0xFF8C1D18)
        else -> onGradient
    }
    val fade = if (enabled) 1f else 0.38f

    Row(
        modifier = modifier
            .clip(shape)
            .background(fill.copy(alpha = fill.alpha * fade))
            .then(
                if (danger) Modifier.border(1.dp, border.copy(alpha = border.alpha * fade), shape)
                else Modifier.border(1.dp, lucentGlassRim(strong = true), shape)
            )
            .clickable(enabled = enabled) {
                Haptics.tick(context)
                onClick()
            }
            .padding(horizontal = 22.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = label.copy(alpha = label.alpha * fade), modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(text, color = label.copy(alpha = label.alpha * fade), fontSize = 15.sp)
    }
}

/**
 * The attachment block shared by the note and task composers (task 13).
 *
 * ### What was cramped about it
 *
 * Both composers ended the form with a bare `Attach file` row wedged between the priority chips and
 * the big Add button, with no heading, no separation, and — when files were attached — a stack of
 * chips landing directly against the row above. Three unrelated things (choose a priority, attach a
 * file, save) sat at the same visual level with 8dp between them, so the eye had nothing to group
 * on and the one optional step in the form read as an afterthought glued to the primary action.
 *
 * The fix is not more padding, it is structure: a labelled section with a quiet one-line hint, the
 * picker as a real glass button rather than a text row, and the attached files listed underneath it
 * with room to breathe. It occupies slightly more height and is markedly easier to parse, which is
 * the correct trade in a form the user is already scrolling.
 */
@Composable
fun AttachmentSection(
    attachments: List<com.lucent.app.data.Attachment>,
    onPick: () -> Unit,
    onRemove: (com.lucent.app.data.Attachment) -> Unit,
    modifier: Modifier = Modifier
) {
    val onGradient = LocalOnGradient.current
    val onGradientMuted = LocalOnGradientMuted.current
    Column(modifier = modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(com.lucent.app.i18n.S.attachmentsSectionTitle, color = onGradient, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(2.dp))
        Text(com.lucent.app.i18n.S.attachmentsSectionHint, color = onGradientMuted, fontSize = 12.sp)
        Spacer(modifier = Modifier.height(10.dp))
        GlassButton(
            text = com.lucent.app.i18n.S.attachFile,
            icon = Icons.Default.AttachFile,
            onClick = onPick
        )
        if (attachments.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            PendingAttachmentChips(attachments, onGradientMuted, onRemove)
        }
    }
}

/**
 * A static, non-interactive "completed" indicator for tasks that are done: a filled rounded
 * square with a checkmark inside. It is deliberately NOT a [androidx.compose.material3.Checkbox]
 * — it has no click handling at all, so tapping it does nothing. Restoring a completed task is
 * done through the explicit undo control instead. Paired with a strikethrough title, this reads
 * as the familiar "this to-do is finished and locked" style.
 *
 * Drawn in [LocalOnGradient] so it matches the other monochrome icons on the frosted cards; the
 * checkmark is drawn in whichever of black/white contrasts with that fill so it stays legible in
 * both light and dark themes.
 */
@Composable
fun CompletedCheckbox(modifier: Modifier = Modifier, boxSize: Dp = 22.dp) {
    val boxColor = LocalOnGradient.current
    val checkColor = if (boxColor.luminance() > 0.5f) Color(0xFF20202B) else Color.White
    Canvas(modifier = modifier.size(boxSize)) {
        val s = size.minDimension
        val radius = s * 0.28f
        drawRoundRect(
            color = boxColor,
            topLeft = Offset.Zero,
            size = Size(s, s),
            cornerRadius = CornerRadius(radius, radius)
        )
        val check = Path().apply {
            moveTo(s * 0.24f, s * 0.53f)
            lineTo(s * 0.42f, s * 0.72f)
            lineTo(s * 0.77f, s * 0.30f)
        }
        drawPath(
            path = check,
            color = checkColor,
            style = Stroke(width = s * 0.12f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

/**
 * The calendar icon that opens the date filter. Rendered slightly brighter when a date is
 * currently active so the affordance quietly signals whether a filter is on.
 */
@Composable
fun DateFilterIconButton(active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val onGradient = LocalOnGradient.current
    val onGradientMuted = LocalOnGradientMuted.current
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            Icons.Default.CalendarToday,
            contentDescription = com.lucent.app.i18n.S.a11yFilterByDate,
            tint = if (active) onGradient else onGradientMuted
        )
    }
}

/**
 * A small dismissible pill that shows the currently active date-range filter (e.g. "Jul 3, 2026 –
 * Jul 9, 2026", collapsing to a single date when start == end) with an X to clear it. Shown only
 * while a filter is set, so an inactive filter adds no clutter.
 */
@Composable
fun DateFilterChip(startMillis: Long, endMillis: Long, onClear: () -> Unit, modifier: Modifier = Modifier) {
    val onGradient = LocalOnGradient.current
    val shape = RoundedCornerShape(percent = 50)
    // Theme-aware glass (task 1): a white wash reads on dark, a smoke wash reads on light. The old
    // fixed white one disappeared entirely on the light theme.
    val chipDark = isDarkGlass()
    val chipFill = Color.White.copy(alpha = if (chipDark) 0.12f else 0.26f)
    val chipRim = if (chipDark) Color.White.copy(alpha = 0.22f) else onGradient.copy(alpha = 0.20f)
    Row(
        modifier = modifier
            .clip(shape)
            .background(chipFill)
            .border(1.dp, chipRim, shape)
            .padding(start = 12.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.CalendarToday,
            contentDescription = null,
            tint = onGradient,
            modifier = Modifier.size(15.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(formatDateRange(startMillis, endMillis), color = onGradient, fontSize = 13.sp)
        IconButton(onClick = onClear, modifier = Modifier.size(30.dp)) {
            Icon(
                Icons.Default.Close,
                contentDescription = com.lucent.app.i18n.S.a11yClearDateFilter,
                tint = onGradient,
                modifier = Modifier.size(15.dp)
            )
        }
    }
}

/**
 * The Notes/Tasks home header bar with a **collapsible** set of secondary actions (task 16).
 *
 * Layout, left to right: the search field (which flexes to fill the remaining width) · an
 * animated cluster of secondary actions ([actions]: date filter, sort, overflow) · a chevron that
 * expands/collapses that cluster · the always-visible "+" ([trailing]).
 *
 * Collapsed (the default), only the chevron and "+" sit beside the search field, so the search box
 * is as wide as possible. Tapping the "<" chevron expands the cluster *leftwards* — it grows from
 * the chevron toward the search box, which smoothly shrinks to make room — and the chevron flips to
 * ">" to collapse again. The reveal is driven by [expandHorizontally]/[shrinkHorizontally] anchored
 * at the end, so the motion reads as sliding out from behind the chevron rather than appearing all
 * at once, and the weighted search field reflows in step with it.
 *
 * [search] must size itself with `Modifier.fillMaxWidth()` (this bar gives it the flexible slot);
 * [actions] is a normal [RowScope] content lambda holding the buttons to hide/show.
 */
@Composable
fun CollapsibleActionBar(
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    search: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    trailing: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = LocalOnGradientMuted.current
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // The flexible search slot. Everything after it is intrinsically sized, so the search box
        // takes whatever width is left — which changes as the action cluster expands or collapses.
        Box(modifier = Modifier.weight(1f)) { search() }
        Spacer(modifier = Modifier.width(4.dp))

        // The secondary actions, revealed/hidden with a horizontal expand anchored at the end so
        // they slide out toward the search box (i.e. "to the left") rather than popping in place.
        AnimatedVisibility(
            visible = expanded,
            enter = expandHorizontally(expandFrom = Alignment.End) + fadeIn(),
            exit = shrinkHorizontally(shrinkTowards = Alignment.End) + fadeOut()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, content = actions)
        }

        // The expand/collapse toggle. "<" invites expansion (there is more, tucked away to the
        // left); once open it becomes ">" to fold the cluster back up.
        IconButton(onClick = onToggleExpanded) {
            Icon(
                if (expanded) Icons.Default.ChevronRight else Icons.Default.ChevronLeft,
                contentDescription = if (expanded) com.lucent.app.i18n.S.a11yHideActions else com.lucent.app.i18n.S.a11yShowMoreActions,
                tint = tint
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        trailing()
    }
}

/**
 * The compact circular "+" that sits in the top-right corner of the Notes and Tasks home pages
 * and opens the create-new composer. It replaces the old full-width "New note" / "New task" bar:
 * a single glass pill in the corner reads as the primary create action while taking almost no
 * space. Shared between both screens so the affordance looks identical in each.
 */
@Composable
fun NewItemButton(contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val onGradient = LocalOnGradient.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val shape = RoundedCornerShape(percent = 50)
    val plusDark = isDarkGlass()
    val plusFill = Color.White.copy(alpha = if (plusDark) 0.13f else 0.30f)
    val plusRim = if (plusDark) Color.White.copy(alpha = 0.24f) else onGradient.copy(alpha = 0.20f)
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(shape)
            .background(plusFill)
            .border(1.dp, plusRim, shape)
            .clickable {
                Haptics.tick(context)
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.Add, contentDescription = contentDescription, tint = onGradient)
    }
}

private fun startOfDayMillis(year: Int, month: Int, day: Int): Long =
    Calendar.getInstance().apply {
        set(Calendar.YEAR, year)
        set(Calendar.MONTH, month)
        set(Calendar.DAY_OF_MONTH, day)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

/**
 * Opens the platform date picker **twice** — first for the start date, then for the end date — to
 * choose a range to filter by. [currentStart]/[currentEnd] pre-select the range if one is already
 * active. [onPicked] receives (start-of-day-of-start, start-of-day-of-end) in local time, the values
 * the filters store and compare against with [withinLocalDayRange].
 *
 * The end picker's `minDate` is pinned to the chosen start, so the end date **cannot** be earlier
 * than the start — the validation the range needs, enforced at the point of entry rather than
 * checked and rejected afterwards. Picking the same day for both ends is allowed and matches exactly
 * that one day.
 */
fun showDateRangePicker(context: Context, currentStart: Long?, currentEnd: Long?, onPicked: (Long, Long) -> Unit) {
    val startBase = Calendar.getInstance().apply { if (currentStart != null) timeInMillis = currentStart }
    DatePickerDialog(
        context,
        { _, sYear, sMonth, sDay ->
            val startMillis = startOfDayMillis(sYear, sMonth, sDay)
            val endBase = Calendar.getInstance().apply {
                timeInMillis = if (currentEnd != null && currentEnd >= startMillis) currentEnd else startMillis
            }
            DatePickerDialog(
                context,
                { _, eYear, eMonth, eDay ->
                    val endMillis = startOfDayMillis(eYear, eMonth, eDay)
                    // Belt and braces: even though minDate blocks an earlier end, coerce so the range
                    // can never come back inverted.
                    onPicked(startMillis, maxOf(startMillis, endMillis))
                },
                endBase.get(Calendar.YEAR),
                endBase.get(Calendar.MONTH),
                endBase.get(Calendar.DAY_OF_MONTH)
            ).apply { datePicker.minDate = startMillis }.show()
        },
        startBase.get(Calendar.YEAR),
        startBase.get(Calendar.MONTH),
        startBase.get(Calendar.DAY_OF_MONTH)
    ).show()
}

/**
 * The panel shown in place of a list that has nothing in it.
 *
 * There are always two reasons a list is empty and they need different words: there is nothing
 * *yet*, or there is nothing *matching*. Showing "No notes yet" to someone who has two hundred notes
 * and typed a typo is actively confusing — it reads as though the app lost them. So the caller
 * passes both messages and this picks, which keeps the distinction impossible to forget at a call
 * site.
 */
@Composable
fun EmptyState(
    isFiltered: Boolean,
    emptyMessage: String,
    noMatchMessage: String,
    modifier: Modifier = Modifier
) {
    val onGradientMuted = LocalOnGradientMuted.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .frostedGlass()
            .padding(24.dp)
    ) {
        Text(
            if (isFiltered) noMatchMessage else emptyMessage,
            color = onGradientMuted,
            fontSize = 14.sp
        )
    }
}

/**
 * The "?" beside a search box, which opens a sheet listing the search operators.
 *
 * Search syntax that isn't discoverable may as well not exist: nobody guesses `has:attachment`, and
 * a feature only power users find by reading the source is a feature that was built for nobody. One
 * unobtrusive icon, one dialog, and the whole query language becomes something an ordinary user can
 * stumble into.
 */
@Composable
fun SearchHelpButton(modifier: Modifier = Modifier) {
    val onGradientMuted = LocalOnGradientMuted.current
    var showing by remember { mutableStateOf(false) }

    IconButton(onClick = { showing = true }, modifier = modifier) {
        Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = com.lucent.app.i18n.S.searchTipsTitle, tint = onGradientMuted)
    }

    if (showing) {
        AlertDialog(
            onDismissRequest = { showing = false },
            title = { Text(com.lucent.app.i18n.S.searchTipsTitle) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        com.lucent.app.i18n.S.searchTipsIntro,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    com.lucent.app.data.SearchQuery.HELP.forEach { (syntax, meaning) ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Text(
                                syntax,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                fontSize = 13.sp,
                                modifier = Modifier.width(150.dp)
                            )
                            Text(meaning, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showing = false }) { Text(com.lucent.app.i18n.S.gotIt) } }
        )
    }
}

/**
 * A row of tappable note chips — used for a note's outgoing `[[links]]` and for its backlinks.
 *
 * Links live here, as chips under the body, rather than relying solely on tapping the inline
 * `[[text]]` itself. Inline link targets on a phone are a few millimetres of text wedged inside a
 * paragraph, which is a miserable tap target; a chip is a proper one. The inline link is still
 * tappable for anyone who wants it — this is the affordance that makes the graph actually
 * *navigable* rather than merely present.
 */
@Composable
fun NoteLinkChips(
    label: String,
    notes: List<com.lucent.app.data.Note>,
    modifier: Modifier = Modifier,
    onOpen: (com.lucent.app.data.Note) -> Unit
) {
    if (notes.isEmpty()) return
    val onGradient = LocalOnGradient.current
    val onGradientMuted = LocalOnGradientMuted.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val shape = RoundedCornerShape(percent = 50)

    Column(modifier = modifier.fillMaxWidth()) {
        Text(label, color = onGradientMuted, fontSize = 12.sp)
        Spacer(modifier = Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            notes.forEach { note ->
                Row(
                    modifier = Modifier
                        .clip(shape)
                        .background(
                            Color.White.copy(alpha = if (onGradient.luminance() > 0.5f) 0.12f else 0.26f)
                        )
                        .border(
                            1.dp,
                            if (onGradient.luminance() > 0.5f) Color.White.copy(alpha = 0.22f)
                            else onGradient.copy(alpha = 0.20f),
                            shape
                        )
                        .clickable {
                            Haptics.tick(context)
                            onOpen(note)
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Link,
                        contentDescription = null,
                        tint = onGradientMuted,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        note.title.ifBlank { com.lucent.app.i18n.S.untitled },
                        color = onGradient,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * A row of chips for `[[links]]` that point at nothing yet.
 *
 * Tapping one creates that note — which turns a broken link from an error into the fastest way to
 * write the next note. Writing `[[Packing list]]` in the middle of a trip plan and then tapping it
 * to bring the packing list into existence is the single nicest thing about linked notes, and it
 * only works if broken links are surfaced rather than hidden.
 */
@Composable
fun BrokenLinkChips(
    targets: List<String>,
    modifier: Modifier = Modifier,
    onCreate: (String) -> Unit
) {
    if (targets.isEmpty()) return
    val onGradientMuted = LocalOnGradientMuted.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val shape = RoundedCornerShape(percent = 50)

    Column(modifier = modifier.fillMaxWidth()) {
        Text(com.lucent.app.i18n.S.brokenLinksHint, color = onGradientMuted, fontSize = 12.sp)
        Spacer(modifier = Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            targets.forEach { target ->
                Row(
                    modifier = Modifier
                        .clip(shape)
                        .border(1.dp, OverdueColor.copy(alpha = 0.55f), shape)
                        .clickable {
                            Haptics.tick(context)
                            onCreate(target)
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        tint = OverdueColor,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        target,
                        color = OverdueColor,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
