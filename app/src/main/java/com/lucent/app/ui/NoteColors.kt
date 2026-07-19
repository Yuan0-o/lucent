package com.lucent.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The small fixed palette a note can be tinted with — Keep-style colour coding.
 *
 * The swatches are washed through frosted glass at 14% alpha (see [frostedGlass]) as the card tint,
 * so the constraint is real: too pale and neighbouring colours become indistinguishable, too dark
 * and a colour disappears into the backdrop. The earlier palette erred pale — several colours read
 * as "faintly tinted white" once behind the glass and were hard to tell apart. These are the deeper,
 * more saturated Material 600–800 tones: distinctly different from one another even at 14% alpha,
 * while still being genuine colour *through* the glass rather than a flat sticker on top of it.
 *
 * [DEFAULT]'s swatch is plain white, which is exactly [frostedGlass]'s own untinted default — so
 * any [NoteColor.swatch] can be passed straight through as the card tint with no "if no colour"
 * branch at any call site.
 */
enum class NoteColor(val key: String, val label: String, val swatch: Color) {
    DEFAULT("", "Default", Color.White),
    RED("red", "Red", Color(0xFFD32F2F)),
    ORANGE("orange", "Orange", Color(0xFFEF6C00)),
    YELLOW("yellow", "Yellow", Color(0xFFF9A825)),
    GREEN("green", "Green", Color(0xFF2E7D32)),
    TEAL("teal", "Teal", Color(0xFF00897B)),
    BLUE("blue", "Blue", Color(0xFF1565C0)),
    PURPLE("purple", "Purple", Color(0xFF8E24AA)),
    PINK("pink", "Pink", Color(0xFFC2185B));

    companion object {
        fun fromKey(key: String?): NoteColor = entries.firstOrNull { it.key == (key?.trim() ?: "") } ?: DEFAULT
    }
}

/**
 * A row of tappable colour swatches for the note composer.
 *
 * Wraps to a second line rather than overflowing, because nine swatches plus spacing exceeds a
 * small phone's width once the composer's padding is subtracted — a Row would have silently clipped
 * the last two colours off the screen on exactly the devices most likely to be used.
 *
 * [NoteColor.DEFAULT] is drawn as an outlined ring rather than a solid white disc, so it stays
 * visible against a light background instead of vanishing into it. The selected swatch carries a
 * check mark, and each one announces its own name and selected state to a screen reader.
 */
@Composable
fun ColorPickerRow(selected: NoteColor, onSelect: (NoteColor) -> Unit, modifier: Modifier = Modifier) {
    val onGradientMuted = LocalOnGradientMuted.current
    val context = LocalContext.current
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        NoteColor.entries.forEach { option ->
            val isSelected = option == selected
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .then(
                        if (option == NoteColor.DEFAULT) {
                            Modifier.border(1.5.dp, onGradientMuted, CircleShape)
                        } else {
                            Modifier.background(option.swatch)
                        }
                    )
                    .clickable {
                        Haptics.tick(context)
                        onSelect(option)
                    }
                    .semantics {
                        contentDescription = "${option.label} note colour"
                        this.selected = isSelected
                    },
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    // Pick a check colour that contrasts with the (now deeper) swatch: white on a
                    // dark swatch, dark on a light one, so the tick stays visible on every colour.
                    val checkTint = when {
                        option == NoteColor.DEFAULT -> onGradientMuted
                        option.swatch.luminance() < 0.5f -> Color.White
                        else -> Color.Black.copy(alpha = 0.65f)
                    }
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = checkTint,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

/**
 * A small accent dot for places where a full tint isn't available — the archive and trash lists,
 * where cards are already carrying restore/delete affordances and a tinted card would compete with
 * them. Renders nothing at all when the note has no colour.
 */
@Composable
fun NoteColorDot(colorKey: String, modifier: Modifier = Modifier, size: Dp = 9.dp) {
    val color = NoteColor.fromKey(colorKey)
    if (color == NoteColor.DEFAULT) return
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color.swatch)
            .semantics { contentDescription = "${color.label} note" }
    )
}
