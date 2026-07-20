// Desktop replacement for Android's share sheet.
//
// The screens share text with `Intent(ACTION_SEND) … startActivity(createChooser(...))`, which pops
// the Android system share sheet. Windows has no equivalent app-to-app share chooser that is
// reachable from a plain JVM process, so the honest desktop behaviour is: put the text on the system
// clipboard and confirm with the standard toast. That is a NEW desktop behaviour, deliberately
// chosen over a fake/no-op share.
//
// When a screen is ported, a call site like
//     context.startActivity(Intent.createChooser(sendIntent, chooserLabel))
// becomes
//     DesktopShare.shareText(context, subject = task.title, text = shareTextForTask(task))

package com.lucent.desktop.platform

import android.content.Context
import com.lucent.app.ui.copyToClipboard

object DesktopShare {

    /**
     * "Share" [text] by copying it to the system clipboard and showing the localized "copied" toast
     * (via [copyToClipboard]). [subject] is accepted to keep call sites symmetric with the Android
     * intent extras, and is prepended when present so a shared task/note keeps its title line.
     *
     * Never throws: the underlying clipboard write already fails silently on a headless toolkit.
     */
    fun shareText(context: Context, subject: String? = null, text: String) {
        val payload = when {
            subject.isNullOrBlank() -> text
            text.isBlank() -> subject
            else -> "$subject\n\n$text"
        }
        copyToClipboard(context, payload)
    }
}
