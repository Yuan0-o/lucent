package com.lucent.app.security

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Windows Hello support for the App Lock (a desktop-only capability).
 *
 * ### Why this exists at all — and why there is no Android counterpart
 *
 * The Android build of Lucent has no biometric code of any kind: its App Lock is a password plus an
 * optional security question ([com.lucent.app.data.AppLock]), and nothing else. So this is NOT a
 * port of an existing feature — it is a genuine desktop addition. Modern Windows laptops expose a
 * fingerprint reader, an IR camera, or a device PIN through Windows Hello, and a note app that can
 * be locked ought to let that hardware do the unlocking when it is present. On a desktop tower with
 * none of that hardware the feature simply does not exist, and the UI must reflect that rather than
 * offer a button that cannot work.
 *
 * ### The contract this object guarantees
 *
 *  1. **Never throws to the caller.** Every path that could fail — no Windows, no PowerShell, no
 *     WinRT, a hung prompt, a malformed reply — is caught here and converted into an ordinary return
 *     value. A caller can treat [availability] and [verify] as total functions.
 *  2. **Conservative availability.** [availability] returns [Availability.AVAILABLE] only when
 *     Windows itself reports Hello is set up and usable. Anything else — including every error — maps
 *     to [Availability.UNAVAILABLE], so the calling UI hides the option instead of showing a dead one.
 *  3. **Clean fallback.** A failed or cancelled verification is a normal outcome, not an error: the
 *     lock screen keeps the password field and the user carries on. Windows Hello is only ever an
 *     *additional* way in, never the only one.
 *
 * ### How it talks to Windows Hello without a native library
 *
 * Windows Hello lives in WinRT (`Windows.Security.Credentials.UI.UserConsentVerifier`). The JVM has
 * no WinRT projection, and pulling in a JNI/JNA binding — plus building and shipping a native stub —
 * would add exactly the kind of native-compile fragility the rest of this desktop port works to
 * avoid. Instead we drive the WinRT API through Windows PowerShell, which every Windows 10/11 host
 * ships with and which *does* have a WinRT projection. The script is handed to `powershell.exe` as a
 * Base64 `-EncodedCommand` (so no quoting can corrupt it), it prints one bare status token, and we
 * read that token back. The whole exchange runs off the UI thread under a timeout, and if PowerShell
 * is missing, disabled by policy, or hangs, we fall back to "unavailable" — never a crash.
 *
 * `powershell.exe` (Windows PowerShell 5.1) is targeted rather than `pwsh` because it is present on
 * every supported Windows out of the box and runs its main thread as STA, which the Hello consent
 * prompt requires.
 */
object WindowsHello {

    /** Whether Windows Hello can be used on this machine right now. */
    enum class Availability { AVAILABLE, UNAVAILABLE }

    /** The outcome of a single verification attempt. */
    enum class Result {
        /** The user proved their identity (fingerprint, face, or device PIN). Unlock. */
        VERIFIED,

        /** The user dismissed the prompt. Stay locked, no error shown — this was a choice. */
        CANCELED,

        /** Verification did not succeed (wrong biometric, retries exhausted, device busy). */
        FAILED,

        /** Hello could not run at all here. The caller should behave as if the feature is absent. */
        UNAVAILABLE
    }

    // The availability probe is comparatively slow (it spins up PowerShell + WinRT), and the answer
    // rarely changes within a session, so the last result is cached. It is NOT cached forever: a
    // user can enrol a fingerprint while the app is open, so [refresh] re-probes and the lock-screen
    // gate re-reads on each display. Kept @Volatile because the cache is written from an IO thread
    // and read from callers on other threads.
    @Volatile
    private var cached: Availability? = null

    private val isWindows: Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("win")

    /**
     * Report whether Windows Hello is usable, caching the answer for the session. Returns
     * [Availability.UNAVAILABLE] immediately and without launching anything when the host is not
     * Windows. Safe to call from any coroutine; the PowerShell probe runs on [Dispatchers.IO].
     */
    suspend fun availability(): Availability {
        cached?.let { return it }
        val result = if (!isWindows) Availability.UNAVAILABLE else probeAvailability()
        cached = result
        return result
    }

    /** Discard the cached availability and probe again (e.g. after the user enrols a credential). */
    suspend fun refresh(): Availability {
        cached = null
        return availability()
    }

    /**
     * Show the Windows Hello consent prompt and wait for its outcome. [reason] is the single line
     * Windows shows the user ("Unlock Lucent", say). Returns [Result.UNAVAILABLE] without prompting
     * when Hello is not available, so a caller may call this directly and let the result decide.
     */
    suspend fun verify(reason: String): Result {
        if (!isWindows || availability() != Availability.AVAILABLE) return Result.UNAVAILABLE
        return runVerification(reason)
    }

    // ---- PowerShell / WinRT plumbing -------------------------------------------------------------

    private suspend fun probeAvailability(): Availability = withContext(Dispatchers.IO) {
        // CheckAvailabilityAsync returns an enum; we only care whether it is exactly `Available`.
        val script = """
            ${'$'}ErrorActionPreference = 'Stop'
            try {
              $WINRT_AWAIT_PRELUDE
              [void][Windows.Security.Credentials.UI.UserConsentVerifier,Windows.Security.Credentials.UI,ContentType=WindowsRuntime]
              ${'$'}availType = [Windows.Security.Credentials.UI.UserConsentVerifierAvailability]
              ${'$'}a = Await ([Windows.Security.Credentials.UI.UserConsentVerifier]::CheckAvailabilityAsync()) ${'$'}availType
              if (${'$'}a -eq [Windows.Security.Credentials.UI.UserConsentVerifierAvailability]::Available) {
                Write-Output 'AVAILABLE'
              } else {
                Write-Output 'UNAVAILABLE'
              }
            } catch { Write-Output 'UNAVAILABLE' }
        """.trimIndent()

        when (runPowerShell(script, timeoutSeconds = 20)?.trim()) {
            "AVAILABLE" -> Availability.AVAILABLE
            else -> Availability.UNAVAILABLE
        }
    }

    private suspend fun runVerification(reason: String): Result = withContext(Dispatchers.IO) {
        // Escape the message for a PowerShell single-quoted literal (double any embedded quote) and
        // strip line breaks, so an odd reason string can neither break the script nor smuggle in code.
        val safeReason = reason.replace("'", "''").replace("\r", " ").replace("\n", " ")
        val script = """
            ${'$'}ErrorActionPreference = 'Stop'
            try {
              $WINRT_AWAIT_PRELUDE
              [void][Windows.Security.Credentials.UI.UserConsentVerifier,Windows.Security.Credentials.UI,ContentType=WindowsRuntime]
              ${'$'}resType = [Windows.Security.Credentials.UI.UserConsentVerificationResult]
              ${'$'}r = Await ([Windows.Security.Credentials.UI.UserConsentVerifier]::RequestVerificationAsync('$safeReason')) ${'$'}resType
              if (${'$'}r -eq [Windows.Security.Credentials.UI.UserConsentVerificationResult]::Verified) {
                Write-Output 'VERIFIED'
              } elseif (${'$'}r -eq [Windows.Security.Credentials.UI.UserConsentVerificationResult]::Canceled) {
                Write-Output 'CANCELED'
              } else {
                Write-Output 'FAILED'
              }
            } catch { Write-Output 'FAILED' }
        """.trimIndent()

        // The prompt is interactive, so it gets a longer ceiling than the availability probe. If the
        // user simply walks away, the timeout reclaims the process and we report FAILED rather than
        // leaving the unlock coroutine pending forever.
        when (runPowerShell(script, timeoutSeconds = 120)?.trim()) {
            "VERIFIED" -> Result.VERIFIED
            "CANCELED" -> Result.CANCELED
            "FAILED" -> Result.FAILED
            else -> Result.UNAVAILABLE
        }
    }

    /**
     * Run a PowerShell script via `-EncodedCommand` and return its trimmed stdout, or null on any
     * failure (process couldn't start, non-zero exit, or it overran [timeoutSeconds]). This is the
     * one place a Windows/PowerShell/WinRT problem turns into a plain null instead of an exception.
     */
    private fun runPowerShell(script: String, timeoutSeconds: Long): String? {
        return try {
            // -EncodedCommand takes Base64 of the UTF-16LE script text, which sidesteps every
            // shell-quoting and escaping pitfall regardless of what the script contains.
            val encoded = Base64.getEncoder()
                .encodeToString(script.toByteArray(StandardCharsets.UTF_16LE))
            val process = ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy", "Bypass",
                "-EncodedCommand", encoded
            ).redirectErrorStream(false).start()

            // Drain stdout on a separate thread. Reading on THIS thread would block until EOF, which
            // for a hung script never comes — and then waitFor's timeout below would never be
            // reached. With a reader thread, the timeout stays authoritative: on overrun we kill the
            // process, which closes the stream and lets the reader finish.
            val collected = StringBuilder()
            val reader = Thread {
                try {
                    process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { r ->
                        collected.append(r.readText())
                    }
                } catch (_: Throwable) {
                    // Stream closed by destroyForcibly, or read error: whatever we have is enough.
                }
            }.apply { isDaemon = true; start() }

            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                reader.join(2000)
                return null
            }
            reader.join(2000)
            if (process.exitValue() != 0) null else collected.toString()
        } catch (t: Throwable) {
            // No powershell.exe, blocked by policy, spawn failure — all mean "Hello isn't usable here".
            null
        }
    }

    // The shared PowerShell prelude that turns a WinRT IAsyncOperation into something we can wait on
    // synchronously. It reflects out the single-argument AsTask overload and wraps it in `Await`.
    // Kept as one constant so the availability and verification scripts stay in lockstep.
    private const val WINRT_AWAIT_PRELUDE = """
              Add-Type -AssemblyName System.Runtime.WindowsRuntime | Out-Null
              ${'$'}asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
                ${'$'}_.Name -eq 'AsTask' -and ${'$'}_.GetParameters().Count -eq 1 -and ${'$'}_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
              } | Select-Object -First 1)
              function Await(${'$'}op, ${'$'}resultType) {
                ${'$'}m = ${'$'}asTaskGeneric.MakeGenericMethod(${'$'}resultType)
                ${'$'}t = ${'$'}m.Invoke(${'$'}null, @(${'$'}op))
                ${'$'}t.Wait(-1) | Out-Null
                return ${'$'}t.Result
              }
    """
}
