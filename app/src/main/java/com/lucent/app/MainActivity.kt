package com.lucent.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.lucent.app.AppScope
import com.lucent.app.data.AttachmentMigration
import com.lucent.app.data.SettingsRepository
import com.lucent.app.data.ShareIntegration
import com.lucent.app.data.StartupLog
import com.lucent.app.data.TrashCleanup
import com.lucent.app.reminders.Notifications
import com.lucent.app.reminders.ReminderScheduler
import com.lucent.app.ui.AndroidRobotIcon
import com.lucent.app.ui.AppReady
import com.lucent.app.ui.AppLockController
import com.lucent.app.ui.AssistantConfirmationDialog
import com.lucent.app.ui.AssistantController
import com.lucent.app.ui.AssistantScreen
import com.lucent.app.ui.FluidGlassBackground
import com.lucent.app.ui.LocalHazeState
import com.lucent.app.ui.LocalBottomBarInset
import com.lucent.app.ui.LocalOnGradient
import com.lucent.app.ui.LocalOnGradientMuted
import com.lucent.app.ui.LockScreen
import com.lucent.app.ui.LucentSplash
import com.lucent.app.ui.lucentGlassRim
import com.lucent.app.ui.LucentToast
import com.lucent.app.ui.lucentTypography
import com.lucent.app.ui.LucentPalette
import com.lucent.app.ui.PALETTE_CYCLE
import com.lucent.app.ui.NotesScreen
import com.lucent.app.ui.rememberCyclingPaletteColors
import com.lucent.app.ui.SettingsScreen
import com.lucent.app.ui.ShareIntake
import com.lucent.app.ui.ShareIntakeDialog
import com.lucent.app.ui.WidgetTaskConfirmDialog
import com.lucent.app.ui.TasksScreen
import com.lucent.app.ui.UnsavedChangesGuard
import com.lucent.app.widget.WidgetActions
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

enum class Screen {
    // Bottom-bar order is this declaration order (the bar iterates Screen.entries). Tasks is
    // listed first so it sits on the LEFT of the capsule and Notes on its right.
    //
    // All four are now equal-status top-level destinations (task 13): the app opens on Tasks, but
    // every tab exits the app directly on back rather than routing through Tasks first. The single
    // label source below drives BOTH the bottom-nav capsule text and the top-app-bar page header.
    //
    // The label is a *computed* property over the i18n table (localization task) rather than a
    // constructor constant: reading S inside composition makes every tab caption and page header
    // re-render the instant the language setting changes, while every call site keeps compiling
    // against the same `screen.label` it always used.
    Tasks, Notes, Assistant, Settings;

    val label: String
        get() = when (this) {
            Tasks -> com.lucent.app.i18n.S.tabTasks
            Notes -> com.lucent.app.i18n.S.tabNotes
            Assistant -> com.lucent.app.i18n.S.tabAssistant
            Settings -> com.lucent.app.i18n.S.tabSettings
        }
}

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Transparent system bars, explicitly (tasks 5/10).
        //
        // Bare enableEdgeToEdge() defaults to SystemBarStyle.auto(), which paints a translucent
        // SCRIM behind the status and navigation bars on the devices that need one. That scrim is
        // the "rectangular block" at the bottom of the screen: a full-width band, a shade lighter
        // than the wallpaper, with the nav capsule sitting inside it. No amount of work on the
        // capsule's own material could remove it, because it was never part of the capsule — it was
        // the window behind it. Lucent draws its own gradient edge to edge and every surface on top
        // is glass, so a scrim adds nothing but a visible seam.
        // auto() rather than dark(): it keeps the automatic light/dark choice for the system ICONS
        // (Lucent ships light and dark glass, and hard-coding white icons would lose them against a
        // pale background) while making both scrims transparent. Only the scrim is being removed
        // here — nothing else about the edge-to-edge behaviour changes.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            )
        )

        val settingsRepo = SettingsRepository(applicationContext)
        // Read the saved appearance synchronously *before* the first frame is composed. DataStore
        // emits asynchronously, so without this the first frame renders with the defaults ("system"
        // theme, SUNSET palette) and then snaps to the user's real choice a moment later — the
        // visible startup "blink".
        //
        // ONE blocking read for everything the first frame depends on.
        //
        // This used to be four separate DataStore round-trips (display prefs, app lock, logging,
        // share integration), each one serialized and each one on the main thread before a single
        // pixel could be drawn. They all live in the same preferences file, so they are now one
        // access. Everything else that used to be read here has moved off the critical path entirely.
        val startup = try {
            runBlocking { settingsRepo.startupPrefsOnce() }
        } catch (t: Throwable) {
            SettingsRepository.StartupPrefs(
                display = SettingsRepository.DisplayPrefs("system", "SUNSET", "system"),
                appLockEnabled = false,
                startupLoggingEnabled = false,
                systemIntegrationEnabled = false
            )
        }
        val display = startup.display
        val initialThemeMode = display.themeMode
        val initialPalette = display.palette
        val initialFont = display.font

        // Apply the saved UI language before anything composes (localization task) — for the same
        // reason the display prefs are read synchronously above: a first frame in English that
        // snaps to Chinese a beat later is exactly the startup blink this block exists to prevent.
        com.lucent.app.i18n.L.apply(startup.appLanguage)

        val lockEnabled = startup.appLockEnabled
        AppLockController.markProcessStarted(lockEnabled)

        StartupLog.setEnabled(startup.startupLoggingEnabled)

        // The share-sheet component is kept in step with its setting, but off the main thread: it is
        // a PackageManager write, it has nothing to do with the first frame, and it was costing
        // startup time on every single launch to correct a state that is almost never wrong.
        val integrationEnabled = startup.systemIntegrationEnabled
        AppScope.io.launch { ShareIntegration.setEnabled(applicationContext, integrationEnabled) }

        StartupLog.event(applicationContext, "App starting (lock=${if (lockEnabled) "on" else "off"})")

        // A share that launched us cold. handleShareIntent re-checks the setting before acting.
        handleShareIntent(intent)
        handleWidgetIntent(intent)

        // Three startup chores, all on the process-lifetime IO scope so a fast tab-switch can't
        // cancel them, and all off the main thread so none of them can stall the first frame. Each
        // is cheap and idempotent, which is exactly why they can simply run on every launch instead
        // of needing a scheduler and a "have I done this today" flag.

        // 1. Move legacy Base64 attachments to disk (a one-shot), encrypt any attachment still
        //    stored in the clear (lazy, resumable), and sweep orphaned files (routine).
        AppScope.io.launch {
            AttachmentMigration.runIfNeeded(applicationContext)
            AttachmentMigration.encryptExistingAttachments(applicationContext)
        }

        // 2. Empty the expired half of the Trash: anything soft-deleted more than
        //    TrashCleanup.RETENTION_DAYS ago loses its row, its attachment files, and its history.
        AppScope.io.launch { TrashCleanup.purgeExpired(applicationContext) }

        // 2b. Wipe any decrypted attachment-preview copies left in the cache from a previous session.
        //     Previews are written as plaintext on demand (to hand a file to a viewer or the share
        //     sheet); clearing them at launch means a decrypted copy never outlives the session that
        //     needed it. The encrypted originals are untouched.
        AppScope.io.launch { com.lucent.app.data.AttachmentAccess.clearPreviewCache(applicationContext) }

        // 3. Re-arm task reminders. Alarms are OS state and don't survive a reboot, an app update,
        //    or a force-stop; BootReceiver covers the reboot, and this covers everything else. It
        //    also self-corrects, since rescheduleAll cancels alarms that shouldn't exist any more.
        AppScope.io.launch { Notifications.ensureChannel(applicationContext) }
        AppScope.io.launch { ReminderScheduler.rescheduleAll(applicationContext) }

        // ---- The one that actually cost a second ----
        //
        // `AppDatabase.getInstance()` is not a cheap accessor. The first call *builds* the database,
        // and building it runs DatabaseEncryption.ensureReady: load the SQLCipher native library,
        // fetch the passphrase from the Android Keystore, and then **open the encrypted file to
        // prove the key works** — which means a full SQLCipher key derivation (hundreds of thousands
        // of PBKDF2 rounds). That is hundreds of milliseconds of pure CPU, and it was happening
        // here, on the main thread, before setContent had even been reached. Nothing could be drawn
        // until it finished, which is exactly the second-plus of blank screen.
        //
        // It moves to the IO scope. Nothing on the first frame needs the database — the splash
        // certainly doesn't — and the screens that do need it are held back until it's ready (see
        // AppReady below), so no composition can stumble into a half-built database and block the
        // main thread anyway.
        AppScope.io.launch {
            val db = com.lucent.app.data.AppDatabase.getInstance(applicationContext)
            com.lucent.app.data.DataCache.warm(db)
            AssistantController.ensureMessagesLoaded(applicationContext)
            com.lucent.app.ui.AppReady.databaseReady = true
        }
        StartupLog.event(applicationContext, "Startup tasks dispatched; composing UI")

        setContent {
            val themeMode by settingsRepo.themeMode.collectAsState(initial = initialThemeMode)
            val paletteName by settingsRepo.palette.collectAsState(initial = initialPalette)
            val fontKey by settingsRepo.font.collectAsState(initial = initialFont)
            // Whether the drifting background animates (task: background on/off toggle). The
            // initial value comes from the synchronous startup read, NOT a hard-coded `true`:
            // with `initial = true`, a user who had switched the effect OFF still got one or two
            // frames of drifting blobs behind the splash cat before the async DataStore emission
            // arrived — the splash and the app disagreed about the setting. Seeding with the real
            // stored value keeps splash and app in step from the very first frame.
            val backgroundAnimated by settingsRepo.backgroundAnimationEnabled.collectAsState(
                initial = startup.backgroundAnimationEnabled
            )

            // Keep the runtime language in step with the setting (localization task). L.current is
            // snapshot state, so this LaunchedEffect flipping it recomposes every S-reading text in
            // the app at once — the switch in Settings takes effect instantly, no restart.
            val languageKey by settingsRepo.appLanguage.collectAsState(initial = startup.appLanguage)
            LaunchedEffect(languageKey) { com.lucent.app.i18n.L.apply(languageKey) }

            // Which appearance is actually in force. Everything a theme decides now comes from the
            // theme itself (see ui/ThemeModes.kt) rather than from string comparisons scattered
            // across this file — which is what makes adding the four Monet tints a one-line change
            // here instead of a new branch in four different `when`s.
            val systemDark = isSystemInDarkTheme()
            val themeChoice = com.lucent.app.ui.LucentThemeMode.fromKey(themeMode)
            val isDarkTheme = themeChoice.isDark(systemDark)
            val colors = if (isDarkTheme) darkColorScheme() else lightColorScheme()
            val onGradient = if (isDarkTheme) Color.White else Color(0xFF20202B)
            val onGradientMuted = onGradient.copy(alpha = 0.65f)
            // The Monet options are light themes with a tinted backdrop, so this is the only place
            // they differ from plain Light at all.
            val backdropColor = themeChoice.backdrop(systemDark)
            val paletteColors = if (paletteName == PALETTE_CYCLE) {
                // Auto-cycling background: drifts smoothly through every palette over time. The
                // backdrop and text colours stay theme-based (below), so contrast is unaffected.
                rememberCyclingPaletteColors(LucentPalette.entries.map { it.colors })
            } else {
                LucentPalette.entries.firstOrNull { it.name == paletteName }?.colors
                    ?: LucentPalette.SUNSET.colors
            }

            MaterialTheme(colorScheme = colors, typography = lucentTypography(fontKey)) {
                CompositionLocalProvider(
                    LocalOnGradient provides onGradient,
                    LocalOnGradientMuted provides onGradientMuted
                ) {
                    // Keep the controller's mirror of the setting current, so toggling App Lock in
                    // Settings takes effect on the next background/relaunch without a restart (task 2).
                    val appLockOn by settingsRepo.appLockEnabled.collectAsState(initial = lockEnabled)
                    LaunchedEffect(appLockOn) { AppLockController.enabled = appLockOn }

                    // The launch animation (added task). Saved rather than plain state, so a
                    // rotation doesn't replay it — but not persisted beyond the process, so a genuine
                    // cold start (the only time there is a gap to cover) always gets it.
                    var splashDone by rememberSaveable { mutableStateOf(false) }

                    // Deliberately a Box with the splash LAYERED OVER the app rather than an
                    // either/or. The whole point is that the real content composes *underneath*
                    // during those five seconds: the first composition of a screen this size is a
                    // large chunk of the cold-start cost, and doing it behind the animation means the
                    // splash spends time that was already being spent instead of adding five seconds
                    // to the launch. By the time the cat dissolves, what it dissolves into is
                    // already built.
                    Box(modifier = Modifier.fillMaxSize()) {
                        // The heavy content waits for the database.
                        //
                        // Composing it immediately would defeat the whole point of moving the build
                        // off the main thread: TasksScreen asks for the database as it composes, and
                        // that call blocks until the build finishes — on the main thread, in the
                        // first frame, which is the thing we just spent an effort avoiding. Holding
                        // it back until the background build reports ready means the first frame has
                        // only the splash in it, and the app composes a moment later against a
                        // database that is already open. `|| splashDone` is a failsafe: if the build
                        // somehow never reports, the app still appears rather than never arriving.
                        if (AppReady.databaseReady || splashDone) {
                            // The gate: while locked, the app content isn't composed at all — the
                            // lock screen stands completely in front of it, so nothing behind it can
                            // be read or interacted with. Unlocking flips the flag and the real app
                            // slides in.
                            if (AppLockController.locked) {
                                LockScreen(paletteColors = paletteColors, backdropColor = backdropColor, backgroundAnimated = backgroundAnimated)
                            } else {
                                LucentApp(paletteColors = paletteColors, backdropColor = backdropColor, backgroundAnimated = backgroundAnimated)
                            }
                        }

                        if (!splashDone) {
                            LucentSplash(
                                paletteColors = paletteColors,
                                backdropColor = backdropColor,
                                onFinished = { splashDone = true },
                                // The cat's backdrop follows the SAME setting as the app behind it:
                                // drifting off in Settings means a still splash too (fix task).
                                backgroundAnimated = backgroundAnimated
                            )
                        }
                    }
                }
            }
        }
    }

    // Drive the App Lock's background re-lock (task 2). onStop stamps when the app left the
    // foreground; onStart re-locks only if it was away longer than the grace window, so an in-app
    // file picker or share sheet that briefly leaves and returns doesn't demand the password again.
    // A configuration change (rotation) also passes through here, but its ~instant round-trip is far
    // inside the grace window, so it never re-locks.
    override fun onStart() {
        super.onStart()
        AppLockController.onStart()
    }

    /**
     * Leaving the foreground stops an in-flight LOCAL reply, unless the user has opted into
     * background replies (task 2).
     *
     * The default matters more than it looks. A local reply holds the whole model in RAM for as long
     * as it runs, and a user who switches away mid-reply has no idea that leaving the screen is what
     * makes their phone crawl — from the outside it just gets slow, some minutes after they stopped
     * using the app that caused it. So the default is to stop and free, and the conversation is left
     * saying exactly that, with a pointer to the setting that would have kept it running.
     *
     * The read is a one-shot on the IO scope rather than a blocking read: onStop is on the main
     * thread during a transition the user can see, and a DataStore round-trip here would show up as
     * a stutter on the way out of the app.
     */
    override fun onStop() {
        super.onStop()
        if (AssistantController.sending && AssistantController.localTurnInFlight) {
            val repo = SettingsRepository(applicationContext)
            AppScope.io.launch {
                val keepGoing = try {
                    repo.localBackgroundReplyEnabledOnce()
                } catch (t: Throwable) {
                    false
                }
                withContext(Dispatchers.Main) { AssistantController.onAppBackgrounded(keepGoing) }
            }
        }
        AppLockController.onStop()
        // Refresh the content widgets as the app leaves the foreground, so the home screen shows
        // current data the moment the launcher reappears (ported from the first settings variant).
        try { com.lucent.app.widget.WidgetUpdater.refreshContent(applicationContext) } catch (t: Throwable) { }
    }

    // A share sent to Lucent while it's already running arrives here (MainActivity is singleTask, so
    // the share re-enters the existing instance rather than stacking a new one). Update the stored
    // intent and route it through the same intake path as a cold-start share.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
        handleWidgetIntent(intent)
    }

    /**
     * Release the on-device model the moment the user actually leaves the app (task: memory must
     * be freed automatically on exit). `isFinishing` distinguishes a real exit — back out of the
     * app, swipe it away — from a configuration change like rotation, where the Activity is
     * destroyed only to be immediately recreated and unloading a multi-gigabyte model would force
     * a pointless reload. If the OS kills the process instead, the kernel reclaims the memory
     * anyway, so every exit path ends with the model gone.
     */
    override fun onDestroy() {
        if (isFinishing) com.lucent.app.local.LocalLlm.shutdown()
        super.onDestroy()
    }

    /**
     * Turn an inbound ACTION_SEND into a pending share (task 6), if — and only if — the user has
     * turned system integration on. The share-sheet component can't deliver anything while the
     * setting is off, but this re-check is a cheap belt-and-braces so a stale component state can
     * never sneak content in. The actual "note or task?" choice is made in ShareIntakeDialog.
     */
    private fun handleShareIntent(intent: Intent?) {
        val shared = ShareIntegration.parse(intent) ?: return
        val enabled = try {
            runBlocking { SettingsRepository(applicationContext).systemIntegrationEnabledOnce() }
        } catch (t: Throwable) {
            false
        }
        if (enabled) ShareIntake.offer(shared)
    }

    /**
     * Route a home-screen widget tap (task 9). The widget launches us with an action extra; here we
     * translate it into the matching one-shot navigation request, which the destination screen picks
     * up as it composes. Consuming happens screen-side, so a stale extra can't re-fire on rotation.
     */
    private fun handleWidgetIntent(intent: Intent?) {
        when (intent?.getStringExtra(WidgetActions.EXTRA_ACTION)) {
            WidgetActions.NEW_NOTE -> AppNavigation.requestComposeNote()
            WidgetActions.NEW_TASK -> AppNavigation.requestComposeTask()
            WidgetActions.ASK -> AppNavigation.requestScreen(Screen.Assistant)
            WidgetActions.OPEN_TASKS -> AppNavigation.requestScreen(Screen.Tasks)
            // Item-level taps from the two content widgets (tasks-list row / pinned note). The id
            // rides in EXTRA_ID; a missing id (shouldn't happen) degrades to just opening the tab.
            WidgetActions.OPEN_TASK_ITEM -> {
                val id = intent.getLongExtra(WidgetActions.EXTRA_ID, -1L)
                if (id > 0) AppNavigation.openTask(id) else AppNavigation.requestScreen(Screen.Tasks)
            }
            // The quick-complete check on a tasks-list row: confirmation-first, like every other
            // action in this app. The id is parked in WidgetTaskConfirm; the dialog hosted below
            // asks, and only a Confirm actually completes (or reopens) the task. The Tasks tab is
            // brought up behind the dialog so the question has its context.
            WidgetActions.TOGGLE_TASK_ITEM -> {
                val id = intent.getLongExtra(WidgetActions.EXTRA_ID, -1L)
                if (id > 0) com.lucent.app.ui.WidgetTaskConfirm.offer(id)
                AppNavigation.requestScreen(Screen.Tasks)
            }
            WidgetActions.OPEN_NOTE_ITEM -> {
                val id = intent.getLongExtra(WidgetActions.EXTRA_ID, -1L)
                if (id > 0) AppNavigation.openNote(id) else AppNavigation.requestScreen(Screen.Notes)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalHazeMaterialsApi::class)
@Composable
fun LucentApp(paletteColors: List<Color>, backdropColor: Color, backgroundAnimated: Boolean = true) {
    // The app opens on Tasks — the leftmost tab and the requested default landing screen. Saved
    // across process death by name (rememberSaveable), so a config change or a return from the
    // background restores whatever tab the user was actually on rather than snapping back here.
    var currentScreen by rememberSaveable { mutableStateOf(Screen.Tasks) }
    val hazeState = rememberHazeState()
    val onGradient = LocalOnGradient.current
    val context = LocalContext.current

    // The "Notes"/"Tasks" title collapses as you scroll the list down and reappears the moment you
    // scroll up (enterAlways) — but ONLY on those two list screens. Assistant and Settings keep a
    // fixed header.
    //
    // Notes and Tasks each get their OWN scroll behaviour, with its own independent height offset
    // (task 5). They used to share a single one, which quietly coupled the two pages: scrolling Notes
    // far enough to hide its header also hid the Tasks header, and on an empty Tasks list there was
    // nothing to scroll up to bring it back — the header was stuck off-screen with no way to recover
    // it. Two separate behaviours mean each page collapses and restores entirely on its own, and one
    // page's scroll position can never strand the other's header.
    val notesScrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val tasksScrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    // Assistant and Settings use a pinned (never-collapsing) behaviour so their header is always
    // fully shown and can never inherit a half-collapsed offset left over from a list page — the two
    // list behaviours above are entirely their own.
    val pinnedScrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val headerCollapsible = currentScreen == Screen.Notes || currentScreen == Screen.Tasks
    val scrollBehavior = when (currentScreen) {
        Screen.Notes -> notesScrollBehavior
        Screen.Tasks -> tasksScrollBehavior
        else -> pinnedScrollBehavior
    }

    // Back behaviour (top level). Sub-screens (Settings sub-menus, the note editor) install
    // their own higher-priority handlers; this only runs once those are exhausted. Tasks, Notes,
    // Assistant and Setting are all equal top-level destinations now (task 13): from ANY of them
    // the first back arms a "press again to exit" toast and a second back within 2s closes the app.
    // Back no longer funnels through Tasks first.
    var backArmed by remember { mutableStateOf(false) }
    LaunchedEffect(backArmed) {
        if (backArmed) {
            delay(2000)
            backArmed = false
        }
    }

    fun finishActivity() {
        var c: android.content.Context = context
        while (c is android.content.ContextWrapper && c !is Activity) c = c.baseContext
        (c as? Activity)?.finish()
    }

    // Any navigation that would leave the current screen — switching tabs or exiting the app —
    // funnels through here first. If the screen currently on-screen has unsaved edits (registered
    // via UnsavedChangesGuard), the action is held and a confirmation dialog decides whether to
    // save, discard, or stay before it actually runs. Otherwise it runs immediately.
    var pendingNavigation by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun runOrConfirm(action: () -> Unit) {
        if (UnsavedChangesGuard.dirty) pendingNavigation = action else action()
    }

    // Exiting while the on-device model is mid-reply (task 2).
    //
    // Leaving the app frees the model — that is the deliberate behaviour, and the right default for
    // something holding gigabytes of RAM. But it also means the reply the user is waiting on stops,
    // and there is no way to know that from the outside: you press back, the app closes, and when
    // you come back the answer is simply not there. So the exit asks first, and says what the cost
    // is. Only the LOCAL model gets this: a cloud reply survives the app closing.
    var confirmExitWhileReplying by remember { mutableStateOf(false) }

    pendingNavigation?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingNavigation = null },
            title = { Text(com.lucent.app.i18n.S.unsavedChangesTitle) },
            text = { Text(com.lucent.app.i18n.S.unsavedChangesBody) },
            confirmButton = {
                TextButton(onClick = {
                    UnsavedChangesGuard.save()
                    pendingNavigation = null
                    action()
                }) { Text(com.lucent.app.i18n.S.actionSave) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        UnsavedChangesGuard.discard()
                        pendingNavigation = null
                        action()
                    }) { Text(com.lucent.app.i18n.S.actionDiscard) }
                    TextButton(onClick = { pendingNavigation = null }) { Text(com.lucent.app.i18n.S.actionCancel) }
                }
            }
        )
    }

    // A search result on the wrong tab asks to be shown here. Routed through the same unsaved-changes
    // guard as any other tab switch, so a half-written note can't be lost to a search result tap.
    LaunchedEffect(AppNavigation.requestedScreen) {
        AppNavigation.consumeScreen()?.let { target ->
            if (target != currentScreen) runOrConfirm { currentScreen = target }
        }
    }

    BackHandler(enabled = true) {
        when {
            // Every top-level tab is treated equally (task 13): there is no "return to Tasks first"
            // step any more. The first back on any tab arms a "press again to exit" toast, and only
            // a second back within 2s actually closes the app. The exit still goes through
            // runOrConfirm so a half-written note or task can't be lost to a stray back press.
            !backArmed -> {
                backArmed = true
                LucentToast.show(context, com.lucent.app.i18n.S.pressBackAgainToExit)
            }
            // Second back with a local reply in flight: warn instead of closing. Deliberately
            // placed AFTER the press-again-to-exit arming, so the warning appears at the moment the
            // app would actually have closed rather than interrupting the first back press.
            AssistantController.sending && AssistantController.localTurnInFlight ->
                confirmExitWhileReplying = true
            else -> runOrConfirm { finishActivity() }
        }
    }

    if (confirmExitWhileReplying) {
        AlertDialog(
            onDismissRequest = { confirmExitWhileReplying = false },
            title = { Text(com.lucent.app.i18n.S.lmExitWhileReplyingTitle) },
            text = { Text(com.lucent.app.i18n.S.lmExitWhileReplyingBody) },
            confirmButton = {
                TextButton(onClick = {
                    confirmExitWhileReplying = false
                    // Stop explicitly rather than relying on the process going away: this writes the
                    // "reply stopped" marker into each conversation, so every thread the user comes
                    // back to explains itself instead of just missing an answer. ALL turns, because
                    // several conversations can be generating and process death would lose every
                    // background partial unmarked.
                    AssistantController.stopAllGeneration()
                    runOrConfirm { finishActivity() }
                }) { Text(com.lucent.app.i18n.S.lmExitAnyway) }
            },
            dismissButton = {
                TextButton(onClick = { confirmExitWhileReplying = false }) {
                    Text(com.lucent.app.i18n.S.lmKeepWaiting)
                }
            }
        )
    }

    // The assistant's tool-confirmation modal, hosted here rather than on the Assistant screen so it
    // can appear on ANY tab (task 3). A reply keeps generating while the user browses their notes, so
    // the question it needs answered has to be askable wherever they happen to be standing; hosted on
    // the Assistant screen it simply never appeared, and the turn waited for an answer forever.
    AssistantConfirmationDialog()

    CompositionLocalProvider(LocalHazeState provides hazeState) {
        Box(modifier = Modifier.fillMaxSize()) {
            // The drifting blob background is decoration and nothing else. Hiding it from
            // accessibility services means a screen reader starts on the screen's actual content
            // instead of announcing an unlabelled canvas first, every single time.
            FluidGlassBackground(
                palette = paletteColors,
                backdropColor = backdropColor,
                animated = backgroundAnimated,
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(state = hazeState)
                    .clearAndSetSemantics { }
            )
            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    TopAppBar(
                        title = { Text(currentScreen.label, color = onGradient, fontSize = 30.sp) },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                        // The scroll behaviour only actually moves the bar on Notes/Tasks: those are
                        // the screens whose lists dispatch the nested scroll it listens to. On the
                        // other tabs nothing scrolls into it, so it stays put.
                        scrollBehavior = scrollBehavior,
                        // The blur is tinted with the theme's own **backdrop**, not with an
                        // arbitrary colour. That matters most here: a bar tinted near-black sat on
                        // the light theme as a grey slab with a hard horizontal edge across the
                        // screen — the single ugliest thing in the app. Tinted with the backdrop it
                        // has no boundary of its own; it is simply the background, softened, with
                        // the title floating on it.
                        modifier = Modifier.hazeEffect(
                            state = hazeState,
                            style = HazeMaterials.ultraThin(
                                if (onGradient.luminance() > 0.5f) com.lucent.app.ui.LucentGlass.HazeContainerDark
                                else com.lucent.app.ui.LucentGlass.HazeContainerLight
                            )
                        )
                    )
                },
                bottomBar = {
                    // A single FLOATING frosted-glass capsule (7/8 width, four segments).
                    //
                    // ### What makes it read as glass
                    //
                    // The capsule carries a real Haze blur, so whatever is behind it — the drifting
                    // colour background AND any note or task list sliding underneath — comes through
                    // TINTED and SOFTENED rather than sharp. That blur is the whole point: the colour
                    // still renders through (so the pill is obviously translucent), but text behind it
                    // is smeared out and unreadable, which is exactly what a pane of frosted glass does.
                    //
                    // Applying the blur here is safe. The card bug (a hard-edged block on every card)
                    // came from putting a blurred layer INSIDE the `hazeSource` container that captures
                    // the background. This Box is not there — it lives in the Scaffold's bottomBar slot,
                    // a sibling of the content, exactly like the top bar, which blurs the same way.
                    //
                    // On top of the blur sit only the things that model a small curved pane floating
                    // over the page: a fill light enough to LIFT the surface without coating it (the
                    // blur already lays down a tint, so this uses BLURRED_FILL, lighter than a card's
                    // fill), one hairline rim shaded bright along the top edge, and a soft drop shadow
                    // so the pill hovers ABOVE the page rather than being printed onto it (task 5).
                    val capsuleShape = RoundedCornerShape(percent = 50)
                    val glassDark = onGradient.luminance() > 0.5f
                    val capsuleFill = Color.White.copy(
                        alpha = if (glassDark) com.lucent.app.ui.LucentGlass.BLURRED_FILL_DARK
                        else com.lucent.app.ui.LucentGlass.BLURRED_FILL_LIGHT
                    )
                    val capsuleRim = lucentGlassRim(strong = true)
                    // Fainter than before: at the old 0.12/0.16 the three dividers read as structure —
                    // as if the pill were a frame holding four separate panels — which is half of
                    // what made it look like a plate with buttons sunk into it.
                    val capsuleDivider = if (glassDark) Color.White.copy(alpha = 0.08f) else onGradient.copy(alpha = 0.10f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            // Sit the pill ABOVE the system navigation bar, then leave a clear gap
                            // below it, so it reads as hovering over the app rather than pinned to the
                            // very bottom edge. With edge-to-edge on, a bare fixed bottom padding put
                            // the pill down in the gesture-bar zone with no air beneath it, which is
                            // what made it look sunk into the bottom of the screen.
                            .navigationBarsPadding()
                            .padding(top = 12.dp, bottom = 26.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.875f)
                                // 76dp — the pill height, raised from 66 by ~1.15x so the bar sits a
                                // little taller under the thumb. The 50% corner radius still reads as a
                                // pill rather than a toolbar slab at this height.
                                .height(76.dp)
                                // Drop shadow FIRST (before clip), with the capsule shape and a soft
                                // ambient/spot colour, so the pill visibly floats above whatever is
                                // behind it. clip = false lets the shadow's own soft edge spill past
                                // the bounds. Safe here, unlike inside a card: this Box is in the
                                // bottomBar slot, not inside the hazeSource capture layer that the card
                                // bug came from.
                                .shadow(
                                    elevation = if (glassDark) 18.dp else 12.dp,
                                    shape = capsuleShape,
                                    clip = false,
                                    ambientColor = if (glassDark) Color.Black.copy(alpha = 0.35f) else Color(0xFF2A2A3A).copy(alpha = 0.26f),
                                    spotColor = if (glassDark) Color.Black.copy(alpha = 0.45f) else Color(0xFF2A2A3A).copy(alpha = 0.34f)
                                )
                                .clip(capsuleShape)
                                // The frosted-glass blur. The .clip above confines it to the pill
                                // shape; the ultraThin material (the same one the top bar uses) tints
                                // it with the theme's own backdrop, so the blur has no hard colour of
                                // its own and the background's colour still reads through — just
                                // softened, with anything sharp behind the pill blurred out. This
                                // draws under the fill/rim/content, so the tab icons and labels on top
                                // stay crisp.
                                .hazeEffect(
                                    state = hazeState,
                                    style = HazeMaterials.ultraThin(
                                        if (glassDark) com.lucent.app.ui.LucentGlass.HazeContainerDark
                                        else com.lucent.app.ui.LucentGlass.HazeContainerLight
                                    )
                                )
                                .background(capsuleFill)
                                // Gradient rim: a bright catch of light along the top, fading to a
                                // faint edge at the bottom — the tell-tale of a curved glass surface.
                                // With the sheens gone this is now the only thing modelling the
                                // curve, so it stays at 1.5dp rather than the cards' hairline.
                                .border(
                                    1.5.dp,
                                    capsuleRim,
                                    capsuleShape
                                )
                        ) {
                            Row(
                                modifier = Modifier
                                    .matchParentSize()
                                    .padding(horizontal = 6.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Screen.entries.forEachIndexed { index, screen ->
                                    if (index > 0) {
                                        Box(
                                            modifier = Modifier
                                                .width(1.dp)
                                                .height(24.dp)
                                                .background(capsuleDivider)
                                        )
                                    }
                                    CapsuleNavItem(
                                        screen = screen,
                                        selected = currentScreen == screen,
                                        onClick = { runOrConfirm { currentScreen = screen } },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
            ) { padding ->
                // The capsule floats over the content now (task 12): the content is NOT inset by
                // the bar's height any more — only the top bar's inset is applied — so lists and
                // cards extend to the very bottom edge and slide *under* the pill, which is what
                // lets its blur pick them up. The bottom inset the Scaffold measured for the bar is
                // instead handed to the screens via LocalBottomBarInset, and each one reserves that
                // much at the bottom of its own scrollable region so nothing ends up trapped behind
                // the capsule. (Previously this was `.padding(padding)`, which reserved the whole
                // bar height and left the pill hovering over a blank band — a docked bar, not a
                // floating one.)
                val topPad = padding.calculateTopPadding()
                val bottomInset = padding.calculateBottomPadding()
                val contentModifier = if (headerCollapsible) {
                    Modifier.padding(top = topPad).fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection)
                } else {
                    Modifier.padding(top = topPad).fillMaxSize()
                }
                // The four tabs are kept alive across switches rather than torn down and rebuilt
                // (see KeepAliveTabs) — this is what stops the drifting background from stuttering
                // every time you change tabs.
                CompositionLocalProvider(LocalBottomBarInset provides bottomInset) {
                    KeepAliveTabs(active = currentScreen, modifier = contentModifier)
                }
            }

            // A share sent into Lucent (task 6) surfaces here as a small "note or task?" chooser,
            // layered above whatever screen is showing. It's a no-op unless something was shared.
            ShareIntakeDialog()
            WidgetTaskConfirmDialog()
        }
    }
}

@Composable
private fun CapsuleNavItem(
    screen: Screen,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val onGradient = LocalOnGradient.current
    val onGradientMuted = LocalOnGradientMuted.current
    val tint = if (selected) onGradient else onGradientMuted
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(percent = 50))
            // The selected pill follows the theme's content colour: a white wash on dark, a dark
            // wash on light. A fixed white one vanished against the light theme's smoked capsule.
            //
            // Eased from 0.14 to 0.10 along with the rest of the capsule. It used to be the most
            // opaque thing on the bar, which inverted the intended reading: the selected tab looked
            // like a solid chip embedded in a frame, rather than a highlight drawn on glass.
            .then(if (selected) Modifier.background(onGradient.copy(alpha = 0.10f)) else Modifier)
            .clickable {
                com.lucent.app.ui.Haptics.tick(context)
                onClick()
            }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (screen == Screen.Assistant) {
            // The classic Android robot, drawn as a single-colour line icon so it tints exactly
            // like the other nav icons.
            Icon(AndroidRobotIcon, contentDescription = screen.label, tint = tint)
        } else {
            Icon(iconFor(screen), contentDescription = screen.label, tint = tint)
        }
        // maxLines = 1 with no ellipsis: every label in all four languages is short enough to fit
        // at this size, and pinning it to one line means a long translation would overflow visibly
        // in testing rather than silently truncating to a clipped part-word in a shipped build (task 5).
        Text(screen.label, color = tint, fontSize = 11.sp, maxLines = 1)
    }
}

fun iconFor(screen: Screen) = when (screen) {
    Screen.Notes -> Icons.AutoMirrored.Filled.Notes
    Screen.Tasks -> Icons.Default.CheckCircle
    Screen.Assistant -> Icons.Default.SmartToy
    Screen.Settings -> Icons.Default.Settings
}

/**
 * Hosts the four top-level tabs and keeps each one *alive* once it has been visited, instead of
 * tearing it down and building it again from scratch on every tab change.
 *
 * ### Why this exists (the "background stutters when switching pages" fix)
 *
 * The drifting blob background is drawn once, at the app root, and its animation clock runs
 * continuously — so on paper switching tabs shouldn't touch it at all. In practice it stuttered on
 * every switch, and the reason wasn't the background: it was the *content*. The old code swapped
 * screens with a bare `when (currentScreen) { … }`. A `when` emits only the branch that matches, so
 * each switch **disposed** the entire outgoing screen (cancelling its Room subscriptions, throwing
 * away its list/detail state) and **recomposed the incoming one from nothing** (re-subscribing,
 * re-measuring, re-laying-out a very large composable). That burst of work lands on the main thread
 * on the switch frame, blows the frame budget, and the background — which shares that thread — visibly
 * hitches while the new screen is built. Because it happened on *every* switch, the background lagged
 * *every* time.
 *
 * Keeping the tabs alive removes the cause. Each tab is composed once, the first time it's opened,
 * and then retained. Switching no longer disposes or rebuilds anything: only which tab is drawn (and
 * receives input) changes, which is nearly free, so the background keeps animating smoothly. The
 * one-time cost of first-composing a tab still exists, but it happens at most once per tab per launch
 * rather than on every switch.
 *
 * ### How "only the active tab is live" is preserved
 *
 * Two invariants the screens rely on assumed a single composed screen. Both are honoured without
 * touching the screens:
 *
 *  - **Back handling.** Inactive tabs are given an *inert* [OnBackPressedDispatcher] via
 *    [LocalOnBackPressedDispatcherOwner], so any `BackHandler` they register lands on a dispatcher
 *    that is never triggered and can't intercept the system back while off-screen. The active tab
 *    keeps the real dispatcher, and when a tab becomes active its `BackHandler`s re-register onto it
 *    (added last, so they take priority) exactly as before.
 *  - **Input.** Inactive tabs are drawn nothing (via [drawWithContent]) and sit below the active tab,
 *    and a transparent scrim over each inactive tab consumes any touch that falls through empty areas
 *    of the active tab, so a control on a hidden tab can never be tapped by accident.
 *
 * The unsaved-changes guard is keyed by owner (see [UnsavedChangesGuard]) for the same reason — an
 * off-screen tab recomposing can no longer clear the on-screen tab's pending edit.
 */
@Composable
private fun KeepAliveTabs(active: Screen, modifier: Modifier = Modifier) {
    val realBackOwner = LocalOnBackPressedDispatcherOwner.current
    // A dispatcher we never fire. Off-screen tabs point their BackHandlers here so they stay inert
    // until the tab is on-screen again. Its lifecycle is borrowed from the real owner (the Activity)
    // purely to satisfy the interface; BackHandler reads LocalLifecycleOwner separately, so this
    // lifecycle is never actually consulted.
    val inertBackOwner = remember(realBackOwner) {
        object : OnBackPressedDispatcherOwner {
            override val lifecycle get() = realBackOwner!!.lifecycle
            override val onBackPressedDispatcher = OnBackPressedDispatcher()
        }
    }

    // The real, shared haze state that the top bar and bottom capsule sample. Captured once here so
    // each inactive tab below can be handed a throwaway instead (task G).
    val sharedHaze = LocalHazeState.current

    // Tabs are composed lazily — a tab enters the tree the first time it's shown and then stays.
    // Seeded with the initial tab so the first frame renders it directly; later tabs are appended
    // the first time they're opened.
    val visited = remember { mutableStateListOf(active) }
    if (active !in visited) visited.add(active)

    Box(modifier) {
        visited.forEach { screen ->
            val isActive = screen == active
            key(screen) {
                // Inactive tabs are laid out but never drawn (drawWithContent below). Their screens
                // still call hazeSource(LocalHazeState.current), though, so sharing the real state
                // would keep injecting a stale, undrawn rectangle into the blur that the top bar and
                // bottom capsule sample — which surfaced as a colour band along the bottom while the
                // top bar was partway through collapsing (task G). Hand each inactive tab a throwaway
                // HazeState so its hazeSource goes nowhere; only the active tab feeds the real shared
                // one. (rememberHazeState is called unconditionally to keep the composition stable
                // as a tab flips between active and inactive.)
                val dummyHaze = rememberHazeState()
                val tabHaze = if (isActive) sharedHaze else dummyHaze
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        // Active tab on top so it wins hit-testing; inactive tabs sit underneath.
                        .zIndex(if (isActive) 1f else 0f)
                        // Keep inactive tabs composed and laid out, but draw nothing for them. This
                        // is cheaper than fading them out and it's what makes a return to a tab
                        // instant — no recomposition, just a redraw.
                        .drawWithContent { if (isActive) drawContent() }
                ) {
                    CompositionLocalProvider(
                        LocalHazeState provides tabHaze,
                        LocalOnBackPressedDispatcherOwner provides
                            (if (isActive) realBackOwner!! else inertBackOwner)
                    ) {
                        // Each tab is told whether it is the visible one. A tab that has just been
                        // left uses this to fold itself back to its own root view (task 3) — see the
                        // `active` handling in each screen. Kept-alive no longer means kept *open*
                        // three levels deep in a sub-page nobody asked to return to.
                        when (screen) {
                            Screen.Notes -> NotesScreen(active = isActive)
                            Screen.Tasks -> TasksScreen(active = isActive)
                            Screen.Assistant -> AssistantScreen(active = isActive)
                            Screen.Settings -> SettingsScreen(active = isActive)
                        }
                    }
                    if (!isActive) {
                        // Topmost child of the hidden tab: swallows any touch that fell through the
                        // active tab's empty space so a hidden control can't be triggered.
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .pointerInput(Unit) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            awaitPointerEvent().changes.forEach { it.consume() }
                                        }
                                    }
                                }
                        )
                    }
                }
            }
        }
    }
}
