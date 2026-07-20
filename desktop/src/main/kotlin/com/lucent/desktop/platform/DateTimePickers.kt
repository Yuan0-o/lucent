// Compose date & time pickers for the desktop.
//
// Android's `DatePickerDialog` / `TimePickerDialog` are platform dialogs that don't exist off-device.
// Material3 ships in-composition equivalents (DatePicker / TimePicker); these wrappers package them
// as drop-in dialogs and add a combined date→time flow that mirrors exactly what the task/reminder
// screens do today: pick a day, then a time, then hand back a single epoch-millis instant clamped to
// a minimum. When a screen is ported, the Android `openPicker()` two-step becomes a single
// `if (showPicker) LucentDateTimePickerFlow(...)`.
//
// Timezone note: Material3's DatePickerState reports the chosen day as a UTC-midnight millis. To
// combine it with a wall-clock time we read the calendar fields back out in UTC and rebuild the
// instant in the local zone, so "3pm on the 4th" is 3pm local, never shifted by the UTC offset.

package com.lucent.desktop.platform

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.lucent.app.i18n.S
import java.util.Calendar
import java.util.TimeZone

private const val DAY_MS = 24L * 60L * 60L * 1000L

/**
 * Single-shot date picker dialog. Compose only while it should be shown.
 *
 * @param initialDateMillis pre-selected instant (epoch millis).
 * @param minMillis         earliest selectable instant; earlier days are greyed out.
 * @param onConfirm         called with the chosen day as a UTC-midnight epoch-millis value.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LucentDatePickerDialog(
    initialDateMillis: Long,
    minMillis: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    val selectable = remember(minMillis) {
        object : SelectableDates {
            // isSelectableDate is handed a UTC-midnight millis per day; keep any day whose end is
            // still at or after the minimum so the min day itself stays selectable.
            override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                utcTimeMillis + DAY_MS >= minMillis
        }
    }
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initialDateMillis,
        selectableDates = selectable,
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { state.selectedDateMillis?.let(onConfirm) ?: onDismiss() }) {
                Text(S.actionOk)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(S.actionCancel) } },
    ) {
        DatePicker(state = state)
    }
}

/**
 * Single-shot time picker dialog. Compose only while it should be shown.
 *
 * @param is24Hour whether to show a 24-hour clock (see android.text.format.DateFormat.is24HourFormat).
 * @param onConfirm called with the chosen hour (0–23) and minute (0–59).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LucentTimePickerDialog(
    initialHour: Int,
    initialMinute: Int,
    is24Hour: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (hour: Int, minute: Int) -> Unit,
) {
    val state = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = is24Hour,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour, state.minute) }) { Text(S.actionOk) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(S.actionCancel) } },
        text = { TimePicker(state = state) },
    )
}

/**
 * Combined "pick a day, then a time" flow that returns one instant — the desktop equivalent of the
 * screens' nested DatePickerDialog→TimePickerDialog. Compose only while the picker should be shown
 * (e.g. `if (showDueDatePicker) LucentDateTimePickerFlow(...)`); dismissing at either stage calls
 * [onDismiss] and selects nothing.
 *
 * @param initialMillis starting instant for both the date and the time-of-day.
 * @param minMillis     floor applied to the final result and to the selectable days.
 * @param is24Hour      clock style for the time stage.
 * @param onConfirm     called once, with the combined local instant, clamped to [minMillis].
 */
@Composable
fun LucentDateTimePickerFlow(
    initialMillis: Long,
    minMillis: Long,
    is24Hour: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    var pickingTime by remember { mutableStateOf(false) }
    var chosenDayMillis by remember { mutableStateOf(initialMillis) }

    if (!pickingTime) {
        LucentDatePickerDialog(
            initialDateMillis = initialMillis,
            minMillis = minMillis,
            onDismiss = onDismiss,
            onConfirm = { dayMillis ->
                chosenDayMillis = dayMillis
                pickingTime = true
            },
        )
    } else {
        val base = remember { Calendar.getInstance().apply { timeInMillis = initialMillis } }
        LucentTimePickerDialog(
            initialHour = base.get(Calendar.HOUR_OF_DAY),
            initialMinute = base.get(Calendar.MINUTE),
            is24Hour = is24Hour,
            onDismiss = onDismiss,
            onConfirm = { hour, minute ->
                // Read the picked day's Y/M/D back out in UTC (that's the zone M3 reported it in)...
                val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                    timeInMillis = chosenDayMillis
                }
                // ...then rebuild the instant in the local zone with the chosen wall-clock time.
                val local = Calendar.getInstance().apply {
                    clear()
                    set(Calendar.YEAR, utc.get(Calendar.YEAR))
                    set(Calendar.MONTH, utc.get(Calendar.MONTH))
                    set(Calendar.DAY_OF_MONTH, utc.get(Calendar.DAY_OF_MONTH))
                    set(Calendar.HOUR_OF_DAY, hour)
                    set(Calendar.MINUTE, minute)
                }
                onConfirm(local.timeInMillis.coerceAtLeast(minMillis))
            },
        )
    }
}
