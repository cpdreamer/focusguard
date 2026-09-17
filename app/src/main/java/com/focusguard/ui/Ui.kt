package com.focusguard.ui

import android.content.Context
import android.widget.Toast
import com.focusguard.R
import com.focusguard.data.CommitmentKind
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
