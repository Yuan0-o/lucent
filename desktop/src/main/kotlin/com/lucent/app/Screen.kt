package com.lucent.app

/**
 * Desktop twin of the Android `Screen` enum (which lives in MainActivity there).
 *
 * The four shared destinations keep their exact names — Tasks, Notes, Assistant, Settings — so
 * AppNavigation and the cross-screen "open this note/task from there" links compile and behave
 * verbatim. Desktop adds two first-class destinations the phone reached only indirectly or not at
 * all: **Search** (a button on the phone; a permanent sidebar item here, since a desktop window has
 * room for it) and **Insights** (the desktop-only statistics/suggestions overview the Windows
 * requirement asks for). As on Android, [label] is a computed property over the i18n table so every
 * caption re-renders the instant the language changes.
 */
enum class Screen {
    Tasks, Notes, Assistant, Search, Insights, Settings;

    val label: String
        get() = when (this) {
            Tasks -> com.lucent.app.i18n.S.tabTasks
            Notes -> com.lucent.app.i18n.S.tabNotes
            Assistant -> com.lucent.app.i18n.S.tabAssistant
            Search -> com.lucent.app.i18n.S.tabSearch
            Insights -> com.lucent.app.i18n.S.tabInsights
            Settings -> com.lucent.app.i18n.S.tabSettings
        }
}
