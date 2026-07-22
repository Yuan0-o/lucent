package com.lucent.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.sp
import com.lucent.app.i18n.S

/**
 * The assistant's "are you sure?" modal for an action that will change the user's notes or tasks.
 *
 * ### Why this lives at the app level and not on the Assistant screen (task 3)
 *
 * It used to be declared inside `AssistantScreen`, which meant it could only be *seen* from the
 * Assistant tab. But generation deliberately does not belong to that screen: [AssistantController]
 * runs a turn on a process-lifetime scope precisely so a reply survives the user wandering off to
 * check a note (see the comment on its `genScope`). Those two decisions were in direct conflict.
 *
 * The failure was worse than a missing dialog. When a mutating tool came up while the user was on
 * Notes, Tasks or Settings, the controller set `pendingConfirmation` and parked the generation
 * coroutine on a `CompletableDeferred` waiting for an answer — from a dialog that no longer existed
 * anywhere in the composition. Nothing could ever complete it. The turn hung indefinitely, the
 * thinking indicator span forever, and returning to the Assistant tab did not help either, because
 * by then the confirmation had been set while that screen was disposed. That is the second, and by
 * far the more serious, cause of the "assistant loops forever" report.
 *
 * Hosting it in `LucentApp` fixes it structurally rather than by patching the symptom: the modal now
 * has exactly the same lifetime as the generation it belongs to. Ask on any tab, answer on any tab.
 *
 * ### The editable field
 *
 * When the call has a field worth correcting ([com.lucent.app.tools.AppTools.editableArgument] —
 * a new note or task's title, a rename's new title, a subtask's text) it is shown as a text box
 * pre-filled with what the model proposed, and approving runs the *edited* value.
 *
 * The reason is that the most common thing wrong with a proposed action is a word. The assistant
 * hears "remind me to eat tomorrow" and offers a task called "eat tomorrow"; the user meant "eat
 * breakfast tomorrow". Before, the only options were to accept the wrong thing and go fix it by
 * hand, or decline and re-explain — and a confirmation dialog that makes you do the work twice is
 * one people learn to dismiss without reading. Being able to fix the word in place is what makes
 * reading it worthwhile.
 *
 * Actions with nothing meaningfully editable (deleting, pinning, completing) show no field, because
 * for those the decision genuinely is only yes or no.
 *
 * ### Only the Cancel button cancels (accidental-dismiss fix)
 *
 * This dialog appears on the model's schedule, not the user's — it can pop up right as they are
 * scrolling the thread or reaching for something else. With the default dialog behaviour, a stray
 * touch on the scrim (or a reflexive back press) counted as "no" and silently cancelled an action
 * the user had actually asked for, and they were left waiting for a task that was never going to
 * appear. So dismissal is disabled entirely: [DialogProperties] turns off outside-tap and
 * back-press dismissal, and [AlertDialog]'s onDismissRequest is an inert no-op as belt-and-braces
 * (with both routes off it can no longer fire). The ONLY ways out are the two labelled buttons —
 * Confirm runs the action, Cancel declines it — so a decision is always a deliberate tap on a
 * button the user has read, never an accident of where a finger happened to land.
 */
@Composable
fun AssistantConfirmationDialog() {
    val confirm = AssistantController.pendingConfirmation ?: return

    // Keyed on the confirmation itself so a second action in the same turn starts from ITS proposed
    // value rather than inheriting the text left over from the previous dialog.
    var draft by remember(confirm) { mutableStateOf(confirm.editValue) }

    AlertDialog(
        // Deliberately inert: scrim taps and back presses are disabled below, so this can't fire —
        // and keeping it a no-op guarantees that even if it somehow did, an accidental touch could
        // never count as "cancel". Only the explicit Cancel button declines the action.
        onDismissRequest = { },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        ),
        title = { Text(confirm.actionTitle) },
        text = {
            Column {
                Text(confirm.details)
                if (confirm.editKey != null) {
                    Spacer(modifier = Modifier.height(14.dp))
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        label = { Text(confirm.editLabel) },
                        // Not single-line: a note title can be a whole sentence, and clipping it
                        // invisibly at the right edge is how someone approves text they can't read.
                        singleLine = false,
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(S.confirmEditHint, fontSize = 12.sp)
                }
                if (confirm.editorKind != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    // "Approve and fine-tune": runs the action exactly as shown (including the edit
                    // above, if any) through the normal tool path — so every guarantee the tools
                    // give still holds — and then opens the created or edited item's own page,
                    // where every remaining detail can be adjusted with the real note/task editor
                    // instead of a dialog field.
                    TextButton(
                        onClick = {
                            AssistantController.resolveConfirmation(
                                approved = true,
                                editedValue = if (confirm.editKey != null) draft else null,
                                openInEditor = true
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(S.confirmOpenEditor) }
                    Text(S.confirmOpenEditorHint, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // Only send an edit for calls that actually offered one; the controller treats a
                // blank or unchanged value as "run it as proposed".
                AssistantController.resolveConfirmation(
                    approved = true,
                    editedValue = if (confirm.editKey != null) draft else null
                )
            }) { Text(S.actionConfirm) }
        },
        dismissButton = {
            TextButton(onClick = { AssistantController.resolveConfirmation(false) }) {
                Text(S.actionCancel)
            }
        }
    )
}
