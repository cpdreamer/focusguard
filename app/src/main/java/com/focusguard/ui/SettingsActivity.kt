package com.focusguard.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.focusguard.R
import com.focusguard.core.Graph
import com.focusguard.databinding.ActivitySettingsBinding
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

/**
 * Planner parameters. Sleep hours may only move in the stricter direction (earlier bedtime,
 * later wake-up is NOT stricter, so wake-up is frozen) while the commitment is active;
 * block/break/streak lengths are always editable because they do not weaken the blocking.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.toolbar.setNavigationOnClickListener { finish() }

        val s = Graph.settings
        val locked = s.isCommitmentActive(Instant.now())
        val end = s.commitmentEnd()
        b.commitment.text = when {
            end == null -> getString(R.string.main_not_provisioned)
            locked -> getString(
                R.string.main_commitment,
                Duration.between(Instant.now(), end).toDays(),
                Ui.dateTime(end.toEpochMilli()),
            )
            else -> getString(R.string.main_commitment_over)
        }
        b.lockedHint.visibility = if (locked) View.VISIBLE else View.GONE

        b.bedtime.setOnClickListener {
            Ui.pickTime(this, R.string.setup_bedtime, s.bedtimeMinute) { m ->
                if (locked && !earlierBedtime(m, s.bedtimeMinute)) { Ui.toast(this, R.string.locked_toast); return@pickTime }
                s.bedtimeMinute = m; renderTimes(); replan()
            }
        }
        b.wake.setOnClickListener {
            if (locked) { Ui.toast(this, R.string.locked_toast); return@setOnClickListener }
            Ui.pickTime(this, R.string.setup_wake, s.wakeMinute) { m -> s.wakeMinute = m; renderTimes(); replan() }
        }
        b.planning.setOnClickListener {
            Ui.pickTime(this, R.string.setup_planning, s.planningMinute) { m -> s.planningMinute = m; renderTimes(); replan() }
        }
        b.blockMinutes.setText(s.blockMinutes.toString())
        b.breakMinutes.setText(s.breakMinutes.toString())
        b.dailyMin.setText(s.dailyMinimumMinutes.toString())
        b.save.setOnClickListener { save() }
        renderTimes()
    }

    /** Bedtime is measured on the evening clock: 22:00 is earlier than 23:00, and 00:30 is later than 23:00. */
    private fun earlierBedtime(candidate: Int, current: Int): Boolean {
        fun evening(m: Int) = if (m < 12 * 60) m + 24 * 60 else m
        return evening(candidate) < evening(current)
    }

    private fun renderTimes() {
        val s = Graph.settings
        b.bedtime.text = getString(R.string.setup_bedtime) + " " + Ui.minuteOfDay(s.bedtimeMinute)
        b.wake.text = getString(R.string.setup_wake) + " " + Ui.minuteOfDay(s.wakeMinute)
        b.planning.text = getString(R.string.setup_planning) + " " + Ui.minuteOfDay(s.planningMinute)
    }

    private fun save() {
        val s = Graph.settings
        s.blockMinutes = b.blockMinutes.text?.toString()?.toIntOrNull()?.coerceIn(15, 120) ?: s.blockMinutes
        s.breakMinutes = b.breakMinutes.text?.toString()?.toIntOrNull()?.coerceIn(0, 30) ?: s.breakMinutes
        s.dailyMinimumMinutes = b.dailyMin.text?.toString()?.toIntOrNull()?.coerceIn(10, 600) ?: s.dailyMinimumMinutes
        b.blockMinutes.setText(s.blockMinutes.toString())
        b.breakMinutes.setText(s.breakMinutes.toString())
        b.dailyMin.setText(s.dailyMinimumMinutes.toString())
        replan()
        Ui.toast(this, R.string.review_saved)
    }

    private fun replan() {
        lifecycleScope.launch { Graph.coordinator.refresh("settings") }
    }
}
