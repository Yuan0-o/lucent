package com.lucent.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * A multi-line text field with an expand toggle in its bottom-right corner. Collapsed, it behaves
 * like an ordinary [OutlinedTextField] bounded by [collapsedMinHeight]/[collapsedMaxHeight].
 * Tapping the expand icon opens a modal editor that fills **almost the entire screen** (edge to
 * edge inside the status/navigation bars) so long notes are comfortable to read and edit.
 *
 * The expanded editor is rendered in its own window (a [Dialog]) rather than inline, which keeps
 * it from squeezing or reflowing the rest of the composer (tags, attachments, the save button …):
 * those stay exactly where they were while the editor floats above them. The dialog window is made
 * transparent with its dim removed, so the app's live animated background still shows through and
 * the panel keeps the app's frosted-glass look. A [Dialog] (instead of the plain popup used
 * before) is what makes this robust: it is a normal focusable window that receives the IME insets
 * and redraws reliably, which fixes the occasional blank/half-drawn panel that could appear after
 * a lot of text had been typed and the editor was then expanded.
 *
 * Both the Notes and the Tasks composer use this one component, so their expanded editors are
 * pixel-for-pixel the same size.
 */
@Composable
fun ExpandableGlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    expandedTitle: String,
    modifier: Modifier = Modifier,
    collapsedMinHeight: Dp = 120.dp,
    collapsedMaxHeight: Dp = 320.dp,
) {
    val onGradientMuted = LocalOnGradientMuted.current
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = collapsedMinHeight, max = collapsedMaxHeight)
        )
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(4.dp)
                .size(32.dp)
        ) {
            Icon(
                Icons.Default.OpenInFull,
                contentDescription = com.lucent.app.i18n.S.expandTextBox,
                tint = onGradientMuted,
                modifier = Modifier.size(18.dp)
            )
        }
    }

    if (expanded) {
        ExpandedEditor(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            title = expandedTitle,
            onCollapse = { expanded = false }
        )
    }
}

/**
 * The near-full-screen editor window. It is a [Dialog] whose own window has been made transparent
 * (and its dim removed) so the app's animated background still shows through our own scrim, exactly
 * like before — but as a real focusable window it receives the IME insets and redraws reliably, so
 * the panel no longer occasionally comes up blank/half-drawn after a long note.
 *
 * The content is a single column, inset only by the system bars (and the keyboard, via
 * the phone keyboard), with the glass editor panel taking all the remaining height. That fills almost the
 * whole screen and guarantees the field is never hidden behind the IME. A slim margin around the
 * panel is a tap target that dismisses; the collapse button and the back gesture dismiss too.
 */
@Composable
private fun ExpandedEditor(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    title: String,
    onCollapse: () -> Unit,
) {
    val onGradient = LocalOnGradient.current
    val onGradientMuted = LocalOnGradientMuted.current
    // A shared, indication-free interaction source so the scrim/panel tap targets add no ripple.
    val noRipple = remember { MutableInteractionSource() }

    // usePlatformDefaultWidth = false lets the content decide the size, so fillMaxSize makes the
    // dialog span the whole window. dismissOnClickOutside is off because the content fills the
    // window; dismissOnBackPress stays on so Esc / the back gesture collapses the editor first.
    // (The Android build also inset system bars and the IME here; desktop has neither, so those
    // knobs and the padding they drove are dropped below.)
    Dialog(
        onDismissRequest = onCollapse,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false
        )
    ) {
        // On Android this poked the platform dialog window to drop its dim and go transparent so
        // the live background showed through. Desktop dialogs have no such window handle, and the
        // scrim below already provides the look, so there is nothing to do here.

        // A darker full-screen scrim (edge to edge) so the panel stands out clearly from the busy
        // animated background behind it. Tapping the scrim (the slim area around the panel)
        // collapses the editor.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(interactionSource = noRipple, indication = null) { onCollapse() }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // (Android inset the system bars + keyboard here; desktop has neither.)
                    .padding(10.dp)
            ) {
                // ---- The editor panel: fills essentially the whole screen ----
                // An opaque surface sits UNDER the glass so the content has a solid, high-contrast
                // backing (the moving background doesn't bleed through and wash out the text): the
                // surface is derived from the inverse of the text colour (a dark panel under light
                // text, a light panel under dark text), keeping the theme-aware look while staying
                // readable in every palette.
                val panelSurface = panelSurfaceColor(onGradient)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(20.dp))
                        .background(panelSurface)
                        .frostedGlass()
                        // Swallow taps so pressing inside the panel never dismisses.
                        .clickable(interactionSource = noRipple, indication = null) {}
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(title, color = onGradient, fontSize = 18.sp)
                        IconButton(onClick = onCollapse, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Default.CloseFullscreen,
                                contentDescription = com.lucent.app.i18n.S.collapseTextBox,
                                tint = onGradientMuted,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.size(8.dp))
                    OutlinedTextField(
                        value = value,
                        onValueChange = onValueChange,
                        placeholder = { Text(placeholder, color = onGradientMuted) },
                        textStyle = LocalTextStyle.current.copy(color = onGradient),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = onGradient,
                            unfocusedTextColor = onGradient,
                            cursorColor = onGradient,
                            focusedBorderColor = onGradient.copy(alpha = 0.5f),
                            unfocusedBorderColor = onGradient.copy(alpha = 0.3f),
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                }
            }
        }
    }
}

/**
 * Picks an opaque backing colour for the expanded editor panel based on the current on-gradient
 * text colour. When the text is light (drawn on dark palettes) we return a near-opaque dark panel;
 * when the text is dark we return a near-opaque light panel. Either way the note/task content sits
 * on a solid, high-contrast surface instead of showing the moving background through, while the
 * thin frosted-glass sheen layered on top keeps it consistent with the rest of the app.
 */
private fun panelSurfaceColor(onGradient: Color): Color =
    if (onGradient.luminance() > 0.5f) {
        // Light text -> dark surface.
        Color(0xFF20202B).copy(alpha = 0.92f)
    } else {
        // Dark text -> light surface.
        Color(0xFFF4F4F8).copy(alpha = 0.92f)
    }
