package com.focusguard.ui

import android.content.Context
import android.text.InputType
import android.widget.FrameLayout
import android.widget.Toast
import com.focusguard.R
import com.focusguard.data.CommitmentKind
import com.focusguard.data.Settings
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import androidx.fragment.app.FragmentActivity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Small formatting / dialog helpers shared by the screens. */
object Ui {
    private val hm: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val dayHm: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.getDefault())
    private val date: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())

    fun minuteOfDay(m: Int): String = String.format(Locale.ROOT, "%02d:%02d", (m / 60) % 24, m % 60)

    fun time(t: ZonedDateTime): String = t.format(hm)

    fun dateTime(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(millis).atZone(zone).format(dayHm)

    fun date(d: LocalDate): String = d.format(date)

    fun instantDate(i: Instant, zone: ZoneId = ZoneId.systemDefault()): String = i.atZone(zone).toLocalDate().format(date)

    /** "How long?" — a number of days typed in, 1..[Settings.MAX_COMMITMENT_DAYS]; chosen anew at every commitment. */
    fun pickCommitmentLength(context: Context, onPicked: (days: Int) -> Unit) {
        val input = TextInputEditText(context).apply {
            hint = context.getString(R.string.commit_length_hint, Settings.MAX_COMMITMENT_DAYS)
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine()
        }
        val container = FrameLayout(context).apply {
            val pad = (20 * context.resources.displayMetrics.density).toInt(); setPadding(pad, 0, pad, 0); addView(input)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.commit_length_title)
            .setView(container)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_ok) { _, _ ->
                val days = input.text?.toString()?.trim()?.toIntOrNull()
                if (days == null || days !in 1..Settings.MAX_COMMITMENT_DAYS) Toast.makeText(context, context.getString(R.string.commit_length_invalid, Settings.MAX_COMMITMENT_DAYS), Toast.LENGTH_LONG).show()
                else onPicked(days)
            }
            .show()
    }

    fun kindLabel(context: Context, k: CommitmentKind): String = context.getString(
        when (k) {
            CommitmentKind.CLASS -> R.string.kind_class
            CommitmentKind.MEAL -> R.string.kind_meal
            CommitmentKind.SLEEP -> R.string.kind_sleep
            CommitmentKind.SPORT -> R.string.kind_sport
            CommitmentKind.OTHER -> R.string.kind_other
        }
    )

    fun toast(context: Context, resId: Int) = Toast.makeText(context, resId, Toast.LENGTH_SHORT).show()

    /** Time picker for a minute-of-day value. */
    fun pickTime(activity: FragmentActivity, title: Int, current: Int, onPicked: (Int) -> Unit) {
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setHour(current / 60)
            .setMinute(current % 60)
            .setTitleText(title)
            .build()
        picker.addOnPositiveButtonClickListener { onPicked(picker.hour * 60 + picker.minute) }
        picker.show(activity.supportFragmentManager, "time")
    }
}
