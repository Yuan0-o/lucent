package com.lucent.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lucent.app.Screen
import com.lucent.app.ui.Haptics
import com.lucent.app.ui.LocalOnGradient
import com.lucent.app.ui.LocalOnGradientMuted
import com.lucent.app.ui.frostedGlass

/**
 * The left navigation rail from the mockup: the "\u2726 Lucent" wordmark, a prominent New button, one
 * row per [Screen] destination, and a bottom info card. It carries the same warm-glass look as the
 * rest of the app (a frosted panel, on-gradient ink), and every caption reads from the i18n table via
 * [Screen.label] so it re-renders the instant the language changes.
 */
@Composable
fun Sidebar(current: Screen, onSelect: (Screen) -> Unit) {
    val onGradient = LocalOnGradient.current
    val onGradientMuted = LocalOnGradientMuted.current

    Column(
        modifier = Modifier
            .width(248.dp)
            .fillMaxHeight()
            .padding(12.dp)
            .frostedGlass(cornerRadius = 24.dp)
            .padding(horizontal = 14.dp, vertical = 18.dp)
    ) {
        // ---- Wordmark: ✦ Lucent ----
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 6.dp)) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = onGradient)
            Spacer(modifier = Modifier.width(10.dp))
            Text("Lucent", color = onGradient, fontSize = 22.sp)
        }

        Spacer(modifier = Modifier.height(22.dp))

        // ---- New (opens a fresh Assistant chat) ----
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(onGradient.copy(alpha = 0.12f))
                .clickable { Haptics.tick(android.content.DesktopContext); onSelect(Screen.Assistant) }
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = null, tint = onGradient)
            Spacer(modifier = Modifier.width(10.dp))
            Text(com.lucent.app.i18n.S.tabAssistant, color = onGradient, fontSize = 15.sp)
        }

        Spacer(modifier = Modifier.height(18.dp))

        // ---- Destinations, one per Screen ----
        Screen.entries.forEach { screen ->
            NavRow(
                icon = iconFor(screen),
                label = screen.label,
                selected = current == screen,
                onGradient = onGradient,
                onGradientMuted = onGradientMuted,
                onClick = { Haptics.tick(android.content.DesktopContext); onSelect(screen) }
            )
            Spacer(modifier = Modifier.height(4.dp))
        }

        Spacer(modifier = Modifier.weight(1f))

        // ---- Bottom info card ----
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(onGradient.copy(alpha = 0.08f))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = onGradient)
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text("Lucent", color = onGradient, fontSize = 14.sp)
                Text("Windows", color = onGradientMuted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun NavRow(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onGradient: Color,
    onGradientMuted: Color,
    onClick: () -> Unit
) {
    val tint = if (selected) onGradient else onGradientMuted
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(if (selected) Modifier.background(onGradient.copy(alpha = 0.10f)) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        Icon(icon, contentDescription = label, tint = tint)
        Spacer(modifier = Modifier.width(12.dp))
        Text(label, color = tint, fontSize = 15.sp, maxLines = 1)
    }
}

private fun iconFor(screen: Screen): ImageVector = when (screen) {
    Screen.Tasks -> Icons.Default.CheckCircle
    Screen.Notes -> Icons.AutoMirrored.Filled.Notes
    Screen.Assistant -> Icons.Default.SmartToy
    Screen.Search -> Icons.Default.Search
    Screen.Insights -> Icons.Default.Insights
    Screen.Settings -> Icons.Default.Settings
}
