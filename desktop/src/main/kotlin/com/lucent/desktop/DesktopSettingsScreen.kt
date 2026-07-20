package com.lucent.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lucent.app.data.AppLock
import com.lucent.app.data.SettingsRepository
import com.lucent.app.i18n.AppLanguage
import com.lucent.app.security.WindowsHello
import com.lucent.app.ui.LocalOnGradient
import com.lucent.app.ui.LocalOnGradientMuted
import com.lucent.app.ui.LucentPalette
import com.lucent.app.ui.LucentThemeMode
import com.lucent.app.ui.frostedGlass
import kotlinx.coroutines.launch

/**
 * A working (if deliberately bounded) desktop Settings screen. It covers the settings a first
 * runnable build needs — appearance, language, and security — and is the home of the desktop-only
 * Windows Hello toggle. The full Android Settings surface (API profiles, local model, backup/restore,
 * privacy, diagnostics) is the larger remaining port and is called out in the work report.
 *
 * Every control writes straight through [SettingsRepository], so choices persist and the rest of the
 * app (which reads the same flows) reacts immediately: pick a theme and the background recolours;
 * switch language and every caption re-renders.
 */
@Composable
fun DesktopSettingsScreen(repo: SettingsRepository) {
    val scope = rememberCoroutineScope()
    val onGradient = LocalOnGradient.current
    val onGradientMuted = LocalOnGradientMuted.current

    val themeMode by repo.themeMode.collectAsState(initial = "system")
    val palette by repo.palette.collectAsState(initial = "SUNSET")
    val bgAnim by repo.backgroundAnimationEnabled.collectAsState(initial = false)
    val language by repo.appLanguage.collectAsState(initial = "system")
    val lockEnabled by repo.appLockEnabled.collectAsState(initial = false)
    val lockCredentials by repo.appLockCredentials.collectAsState(initial = "")
    val helloEnabled by repo.appLockHelloEnabled.collectAsState(initial = false)

    // The Windows Hello section only exists on a machine that actually has Hello set up. On any other
    // PC this stays false and the toggle is never shown — the requirement's "hide it, don't crash".
    var helloAvailable by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        helloAvailable = WindowsHello.availability() == WindowsHello.Availability.AVAILABLE
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp, vertical = 28.dp)
    ) {
        Text(com.lucent.app.i18n.S.tabSettings, color = onGradient, fontSize = 30.sp)
        Spacer(modifier = Modifier.height(20.dp))

        // ---- Appearance ----
        Section(com.lucent.app.i18n.S.settingsAppearanceTitle, onGradient) {
            RowLabel(com.lucent.app.i18n.S.settingsThemeTitle, onGradientMuted)
            ChipRow(
                options = LucentThemeMode.entries.map { it.key to it.label },
                selectedKey = themeMode,
                onGradient = onGradient,
                onGradientMuted = onGradientMuted,
                onPick = { scope.launch { repo.setThemeMode(it) } }
            )
            Spacer(modifier = Modifier.height(14.dp))

            RowLabel(com.lucent.app.i18n.S.settingsBackgroundTitle, onGradientMuted)
            ChipRow(
                options = LucentPalette.entries.map { it.name to it.label },
                selectedKey = palette,
                onGradient = onGradient,
                onGradientMuted = onGradientMuted,
                onPick = { scope.launch { repo.setPalette(it) } }
            )
            Spacer(modifier = Modifier.height(14.dp))

            ToggleRow(
                title = com.lucent.app.i18n.S.backgroundAnimationTitle,
                subtitle = com.lucent.app.i18n.S.backgroundAnimationDesc,
                checked = bgAnim,
                onGradient = onGradient,
                onGradientMuted = onGradientMuted,
                onChange = { scope.launch { repo.setBackgroundAnimationEnabled(it) } }
            )
        }

        // ---- Language ----
        Section(com.lucent.app.i18n.S.settingsLanguageTitle, onGradient) {
            ChipRow(
                options = AppLanguage.entries.map { it.key to it.label },
                selectedKey = language,
                onGradient = onGradient,
                onGradientMuted = onGradientMuted,
                onPick = { scope.launch { repo.setAppLanguage(it) } }
            )
        }

        // ---- Security ----
        Section(com.lucent.app.i18n.S.settingsSecurityTitle, onGradient) {
            ToggleRow(
                title = com.lucent.app.i18n.S.appLockTitle,
                subtitle = com.lucent.app.i18n.S.appLockDesc,
                checked = lockEnabled,
                onGradient = onGradient,
                onGradientMuted = onGradientMuted,
                onChange = { turnOn ->
                    // Turning OFF clears the credentials. Turning ON reveals the setup form below
                    // (a lock with no password would lock nobody out), so it doesn't flip the flag here.
                    if (!turnOn) scope.launch { repo.setAppLock(false, "") }
                }
            )

            // Password setup: shown whenever no credentials exist yet (i.e. the lock is being turned
            // on). Once a password is stored, this is replaced by a short "app lock is on" line.
            if (lockCredentials.isEmpty()) {
                PasswordSetup(onGradient, onGradientMuted) { password ->
                    val creds = AppLock.createCredentials(password, "", "")
                    scope.launch { repo.setAppLock(true, creds) }
                }
            } else {
                Spacer(modifier = Modifier.height(10.dp))
                Text(com.lucent.app.i18n.S.appLockOnToast, color = onGradientMuted, fontSize = 13.sp)
            }

            // Windows Hello — desktop only, and only when the hardware is present AND a lock exists
            // (Hello augments the password lock, it doesn't replace having one).
            if (helloAvailable && lockCredentials.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
                ToggleRow(
                    title = com.lucent.app.i18n.S.helloTitle,
                    subtitle = com.lucent.app.i18n.S.helloDesc,
                    checked = helloEnabled,
                    onGradient = onGradient,
                    onGradientMuted = onGradientMuted,
                    onChange = { scope.launch { repo.setAppLockHelloEnabled(it) } }
                )
            }
        }

        Spacer(modifier = Modifier.height(40.dp))
    }
}

@Composable
private fun Section(title: String, onGradient: Color, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .frostedGlass(cornerRadius = 20.dp)
            .padding(20.dp)
    ) {
        Text(title, color = onGradient, fontSize = 18.sp)
        Spacer(modifier = Modifier.height(12.dp))
        content()
    }
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun RowLabel(text: String, onGradientMuted: Color) {
    Text(text, color = onGradientMuted, fontSize = 13.sp)
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun ChipRow(
    options: List<Pair<String, String>>,
    selectedKey: String,
    onGradient: Color,
    onGradientMuted: Color,
    onPick: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (key, label) ->
            val selected = key == selectedKey
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .then(
                        if (selected) Modifier.background(onGradient.copy(alpha = 0.14f))
                        else Modifier.border(1.dp, onGradientMuted.copy(alpha = 0.5f), RoundedCornerShape(50))
                    )
                    .clickable { onPick(key) }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(label, color = if (selected) onGradient else onGradientMuted, fontSize = 13.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onGradient: Color,
    onGradientMuted: Color,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = onGradient, fontSize = 15.sp)
            Spacer(modifier = Modifier.height(2.dp))
            Text(subtitle, color = onGradientMuted, fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PasswordSetup(
    onGradient: Color,
    onGradientMuted: Color,
    onSet: (String) -> Unit
) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    Spacer(modifier = Modifier.height(10.dp))
    Text(com.lucent.app.i18n.S.appLockSetupBody, color = onGradientMuted, fontSize = 12.sp)
    Spacer(modifier = Modifier.height(10.dp))
    OutlinedTextField(
        value = password,
        onValueChange = { password = it; error = "" },
        label = { Text(com.lucent.app.i18n.S.lockPassword) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(modifier = Modifier.height(8.dp))
    OutlinedTextField(
        value = confirm,
        onValueChange = { confirm = it; error = "" },
        label = { Text(com.lucent.app.i18n.S.fieldConfirmPassword) },
        singleLine = true,
        isError = error.isNotEmpty(),
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth()
    )
    if (error.isNotEmpty()) {
        Spacer(modifier = Modifier.height(6.dp))
        Text(error, color = Color(0xFFFF8A80), fontSize = 13.sp)
    }
    Spacer(modifier = Modifier.height(12.dp))
    Button(
        enabled = password.isNotEmpty() && confirm.isNotEmpty(),
        onClick = {
            when {
                password.length < 4 -> error = com.lucent.app.i18n.S.lockErrTooShort
                password != confirm -> error = com.lucent.app.i18n.S.lockErrMismatch
                else -> onSet(password)
            }
        }
    ) { Text(com.lucent.app.i18n.S.turnOn) }
}
