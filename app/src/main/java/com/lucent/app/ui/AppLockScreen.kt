package com.lucent.app.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lucent.app.data.AppLock
import com.lucent.app.data.SettingsRepository
import kotlinx.coroutines.launch

/**
 * The process-wide App Lock state (task 2).
 *
 * This is a plain global holder, not persisted, so it naturally survives configuration changes (the
 * process outlives an Activity recreate) but resets when the process is killed — which is exactly
 * what "lock on next cold start" needs. [enabled] mirrors the stored setting; [locked] is the live
 * gate the UI reads.
 *
 * ### When it locks
 *
 *  - **Cold start:** the first Activity creation of a fresh process locks if the feature is on (see
 *    [markProcessStarted] / MainActivity). A configuration change does NOT re-lock, because
 *    [processStarted] is already true by then and [locked] keeps its value.
 *  - **Return from a real background:** [onStop] stamps the time; [onStart] re-locks only if the app
 *    was away longer than [GRACE_MS]. The grace window is what stops an in-app file picker or share
 *    sheet — which also fires stop/start — from demanding the password again the instant it returns,
 *    while a genuine "left the app and came back later" still locks.
 */
object AppLockController {

    private const val GRACE_MS = 30_000L

    var enabled by mutableStateOf(false)
    var locked by mutableStateOf(false)

    private var processStarted = false
    private var backgroundedAt = 0L

    /** Call once from the first onCreate of the process. Locks on a fresh start if enabled. */
    fun markProcessStarted(lockEnabled: Boolean) {
        enabled = lockEnabled
        if (!processStarted) {
            processStarted = true
            locked = lockEnabled
        }
    }

    fun onStop() {
        backgroundedAt = SystemClock.elapsedRealtime()
    }

    fun onStart() {
        if (enabled && backgroundedAt != 0L &&
            SystemClock.elapsedRealtime() - backgroundedAt > GRACE_MS
        ) {
            locked = true
        }
        backgroundedAt = 0L
    }

    fun unlock() { locked = false }
}

private enum class LockStage { ENTER_PASSWORD, ANSWER_QUESTION, SET_NEW_PASSWORD }

/**
 * The full-screen lock shown while [AppLockController.locked] is true. Rendered over the same fluid
 * background as the rest of the app so unlocking feels like part of Lucent, not a system dialog.
 *
 * Password entry is the default. "Forgot password?" reveals the security question; a correct answer
 * unlocks the path to setting a *new* password, which is saved and then unlocks the app. A wrong
 * password or answer just shows an inline error and lets the user try again — there is no lockout
 * counter, because the data is already encrypted at rest and a counter mostly punishes the owner.
 */
@Composable
fun LockScreen(paletteColors: List<Color>, backdropColor: Color) {
    val context = LocalContext.current
    val repo = remember { SettingsRepository(context) }
    val scope = rememberCoroutineScope()
    val onGradient = LocalOnGradient.current
    val onGradientMuted = LocalOnGradientMuted.current

    // Enabled ⇒ credentials exist; collected here so verification has them. Until the first emission
    // arrives the unlock button stays disabled, so the opening frame can't produce a false "wrong
    // password" by comparing against an empty blob.
    val credentials by repo.appLockCredentials.collectAsState(initial = "")

    var stage by remember { mutableStateOf(LockStage.ENTER_PASSWORD) }
    var password by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    Box(modifier = Modifier.fillMaxSize()) {
        FluidGlassBackground(palette = paletteColors, backdropColor = backdropColor, modifier = Modifier.fillMaxSize())
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().frostedGlass().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Default.Lock, contentDescription = null, tint = onGradient)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Lucent is locked", color = onGradient, fontSize = 20.sp)
                Spacer(modifier = Modifier.height(16.dp))

                when (stage) {
                    LockStage.ENTER_PASSWORD -> {
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it; error = "" },
                            label = { Text("Password") },
                            singleLine = true,
                            isError = error.isNotEmpty(),
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (error.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(error, color = Color(0xFFFF8A80), fontSize = 13.sp)
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            enabled = password.isNotEmpty() && credentials.isNotEmpty(),
                            onClick = {
                                if (AppLock.verifyPassword(credentials, password)) {
                                    password = ""
                                    AppLockController.unlock()
                                } else {
                                    error = "Wrong password. Try again."
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Unlock") }
                        Spacer(modifier = Modifier.height(4.dp))
                        // "Forgot password?" is only offered when there is actually a security
                        // question behind it (task 9). A lock set up without one has no recovery
                        // path, and saying so plainly is kinder than a link that leads to a question
                        // nobody can answer.
                        if (AppLock.hasRecovery(credentials)) {
                            TextButton(onClick = {
                                error = ""
                                answer = ""
                                stage = LockStage.ANSWER_QUESTION
                            }) { Text("Forgot password?") }
                        } else if (credentials.isNotEmpty()) {
                            Text(
                                "No security question was set for this lock, so the password can't be " +
                                    "reset. If it's lost, the only way back in is to clear all data.",
                                color = onGradientMuted,
                                fontSize = 12.sp
                            )
                        }
                    }

                    LockStage.ANSWER_QUESTION -> {
                        val question = AppLock.question(credentials)
                        Text(
                            "Answer your security question to set a new password.",
                            color = onGradientMuted, fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(question.ifBlank { "Security question" }, color = onGradient, fontSize = 15.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = answer,
                            onValueChange = { answer = it; error = "" },
                            label = { Text("Answer") },
                            singleLine = true,
                            isError = error.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (error.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(error, color = Color(0xFFFF8A80), fontSize = 13.sp)
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            enabled = answer.isNotEmpty() && credentials.isNotEmpty(),
                            onClick = {
                                if (AppLock.verifyAnswer(credentials, answer)) {
                                    error = ""
                                    newPassword = ""
                                    confirmPassword = ""
                                    stage = LockStage.SET_NEW_PASSWORD
                                } else {
                                    error = "That answer doesn't match."
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Continue") }
                        Spacer(modifier = Modifier.height(4.dp))
                        TextButton(onClick = { error = ""; stage = LockStage.ENTER_PASSWORD }) {
                            Text("Back to password")
                        }
                    }

                    LockStage.SET_NEW_PASSWORD -> {
                        Text("Choose a new password.", color = onGradientMuted, fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = newPassword,
                            onValueChange = { newPassword = it; error = "" },
                            label = { Text("New password") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = confirmPassword,
                            onValueChange = { confirmPassword = it; error = "" },
                            label = { Text("Confirm new password") },
                            singleLine = true,
                            isError = error.isNotEmpty(),
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (error.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(error, color = Color(0xFFFF8A80), fontSize = 13.sp)
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            enabled = newPassword.isNotEmpty() && confirmPassword.isNotEmpty(),
                            onClick = {
                                if (newPassword != confirmPassword) {
                                    error = "The passwords don't match."
                                    return@Button
                                }
                                val updated = AppLock.changePassword(credentials, newPassword)
                                if (updated == null) {
                                    error = "Couldn't update the password. Try again."
                                    return@Button
                                }
                                scope.launch { repo.setAppLockCredentials(updated) }
                                newPassword = ""
                                confirmPassword = ""
                                password = ""
                                AppLockController.unlock()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Set password & unlock") }
                    }
                }
            }
        }
    }
}
