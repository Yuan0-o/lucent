package com.lucent.desktop

import android.content.DesktopContext
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lucent.app.AppNavigation
import com.lucent.app.Screen
import com.lucent.app.data.SettingsRepository
import com.lucent.app.ui.FluidGlassBackground
import com.lucent.app.ui.LocalOnGradient
import com.lucent.app.ui.LocalOnGradientMuted
import com.lucent.app.ui.LockScreen
import com.lucent.app.ui.LucentSplash
import com.lucent.app.ui.LucentFont
import com.lucent.app.ui.LucentPalette
import com.lucent.app.ui.PALETTE_CYCLE
import com.lucent.app.ui.rememberCyclingPaletteColors
import com.lucent.app.ui.LucentThemeMode
import com.lucent.app.ui.AssistantScreen
import com.lucent.app.ui.InsightsScreen
import com.lucent.app.ui.LucentToast
import com.lucent.app.ui.NotesScreen
import com.lucent.app.ui.SearchScreen
import com.lucent.app.ui.SettingsScreen
import com.lucent.app.ui.TasksScreen
import com.lucent.app.ui.frostedGlass
import com.lucent.app.ui.lucentTypography
import kotlinx.coroutines.delay

/**
 * The desktop app root, the peer of Android's `LucentApp`. It reproduces the same three responsibilities
 * that composable has — resolve the appearance from settings, gate the whole UI behind the App Lock,
 * and host the toast overlay — and then, in place of Android's bottom-nav Scaffold, lays the window
 * out as the mockup asks: a vertical sidebar on the left and the active screen filling the rest.
 *
 * The appearance plumbing is intentionally identical to Android's (theme → colours → on-gradient ink
 * → backdrop → palette), so the shared [FluidGlassBackground], [LockScreen], and every glass surface
 * read exactly the same colours here as on the phone. The one deliberate difference is the default:
 * the drifting background starts OFF on desktop (see [SettingsRepository]).
 */
@Composable
fun DesktopApp(startup: SettingsRepository.StartupPrefs) {
    val context = DesktopContext
    val repo = remember { SettingsRepository(context) }
    val systemDark = isSystemInDarkTheme()

    val themeMode by repo.themeMode.collectAsState(initial = startup.display.themeMode)
    val paletteName by repo.palette.collectAsState(initial = startup.display.palette)
    val fontKey by repo.font.collectAsState(initial = startup.display.font)
    val backgroundAnimated by repo.backgroundAnimationEnabled.collectAsState(
        initial = startup.backgroundAnimationEnabled
    )

    // Keep the runtime language following the setting, so switching it in Settings re-renders every
    // S-reading string at once with no restart — the same contract as Android's LaunchedEffect.
    val languageKey by repo.appLanguage.collectAsState(initial = startup.appLanguage)
    LaunchedEffect(languageKey) { com.lucent.app.i18n.L.apply(languageKey) }

    // Mirror the App Lock setting into the controller so toggling it takes effect on the next lock.
    val appLockOn by repo.appLockEnabled.collectAsState(initial = startup.appLockEnabled)
    LaunchedEffect(appLockOn) { com.lucent.app.ui.AppLockController.enabled = appLockOn }

    val themeChoice = LucentThemeMode.fromKey(themeMode)
    val isDark = themeChoice.isDark(systemDark)
    val colors = if (isDark) darkColorScheme() else lightColorScheme()
    val onGradient = if (isDark) Color.White else Color(0xFF20202B)
    val onGradientMuted = onGradient.copy(alpha = 0.65f)
    val backdropColor = themeChoice.backdrop(systemDark)
    val paletteColors = if (paletteName == PALETTE_CYCLE) {
        // Auto-cycling background: drifts smoothly through every palette over time. Backdrop and
        // text colours stay theme-based (above), so contrast is unaffected. (Parity with Android.)
        rememberCyclingPaletteColors(LucentPalette.entries.map { it.colors })
    } else {
        LucentPalette.entries.firstOrNull { it.name == paletteName }?.colors
            ?: LucentPalette.SUNSET.colors
    }

    MaterialTheme(colorScheme = colors, typography = lucentTypography(LucentFont.fromKey(fontKey))) {
        CompositionLocalProvider(
            LocalOnGradient provides onGradient,
            LocalOnGradientMuted provides onGradientMuted
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // The lock stands entirely in front of the app: while locked, the shell isn't composed,
                // so nothing behind it can be read. Unlocking (password or Windows Hello) swaps it out.
                if (com.lucent.app.ui.AppLockController.locked) {
                    LockScreen(
                        paletteColors = paletteColors,
                        backdropColor = backdropColor,
                        backgroundAnimated = backgroundAnimated
                    )
                } else {
                    DesktopShell(
                        repo = repo,
                        paletteColors = paletteColors,
                        backdropColor = backdropColor,
                        backgroundAnimated = backgroundAnimated
                    )
                }

                ToastOverlay()

                // The launch animation, layered OVER everything (lock screen + shell) so the real
                // content composes underneath while it plays — same idea as Android's splash. Kept in
                // plain process state, so it shows once per launch and a recomposition doesn't replay it.
                var splashDone by remember { mutableStateOf(false) }
                if (!splashDone) {
                    LucentSplash(
                        paletteColors = paletteColors,
                        backdropColor = backdropColor,
                        onFinished = { splashDone = true },
                        backgroundAnimated = backgroundAnimated
                    )
                }
            }
        }
    }
}

@Composable
private fun DesktopShell(
    repo: SettingsRepository,
    paletteColors: List<Color>,
    backdropColor: Color,
    backgroundAnimated: Boolean
) {
    // Opens on the Assistant, the mockup's hero screen and the app's centre of gravity. Cross-screen
    // jumps (Insights/Search "open this note") route through AppNavigation exactly as on Android.
    var current by remember { mutableStateOf(Screen.Tasks) }
    LaunchedEffect(AppNavigation.requestedScreen) {
        AppNavigation.consumeScreen()?.let { current = it }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        FluidGlassBackground(
            palette = paletteColors,
            backdropColor = backdropColor,
            animated = backgroundAnimated,
            modifier = Modifier.fillMaxSize()
        )
        Row(modifier = Modifier.fillMaxSize()) {
            Sidebar(current = current, onSelect = { current = it })
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                when (current) {
                    Screen.Assistant -> AssistantScreen()
                    Screen.Tasks -> TasksScreen()
                    Screen.Notes -> NotesScreen()
                    Screen.Insights -> InsightsScreen()
                    Screen.Search -> SearchScreen(
                        // Tapping a result routes through the same AppNavigation "open this item"
                        // channel the Tasks/Notes screens already consume on entry, so the target
                        // opens directly in its editor on the right tab.
                        onOpenNote = { note -> AppNavigation.openNote(note.id, from = Screen.Search) },
                        onOpenTask = { task -> AppNavigation.openTask(task.id, from = Screen.Search) },
                        onBack = { AppNavigation.requestScreen(Screen.Tasks) }
                    )
                    Screen.Settings -> SettingsScreen()
                }
            }
        }
    }
}

/** The floating toast chip, rendered by the shell (Android delegates to the platform Toast). */
@Composable
private fun ToastOverlay() {
    val entry by LucentToast.messages.collectAsState()
    val onGradient = LocalOnGradient.current
    Box(modifier = Modifier.fillMaxSize().padding(bottom = 40.dp), contentAlignment = Alignment.BottomCenter) {
        entry?.let { e ->
            LaunchedEffect(e.id) {
                delay(if (e.longDuration) LucentToast.LONG_MS else LucentToast.SHORT_MS)
                LucentToast.clear(e)
            }
            Box(modifier = Modifier.frostedGlass().padding(horizontal = 18.dp, vertical = 12.dp)) {
                Text(e.message, color = onGradient, fontSize = 14.sp)
            }
        }
    }
}
