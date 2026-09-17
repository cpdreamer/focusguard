package com.focusguard.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.focusguard.core.Graph
import com.focusguard.databinding.ActivityReminderBinding
import kotlinx.coroutines.launch

/**
 * Full-screen "do this now" card, shown over the lock screen at the start of a slot.
 * Shows exactly one task and its first action. Buttons: Start / Done / Skip / Slipped.
 */
class ReminderActivity : AppCompatActivity() {

    private lateinit var b: ActivityReminderBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        b = ActivityReminderBinding.inflate(layoutInflater)
        setContentView(b.root)

        val taskId = intent.getLongExtra(EXTRA_TASK, -1L)
        val state = Graph.coordinator.state.value
        val a = state?.current?.takeIf { it.task.id == taskId }
        if (a == null) {
            // Slot changed between the alarm and now; nothing to show.
            finish(); return
        }
        b.title.text = a.task.title
        b.subject.text = a.task.subject
        b.until.text = a.untilText(this)
        b.action.text = a.task.firstAction.ifBlank { getString(com.focusguard.R.string.reminder_default_action) }

        b.btnStart.setOnClickListener { Graph.coordinator.markStarted(); finish() }
        b.btnDone.setOnClickListener { lifecycleScope.launch { Graph.coordinator.markDone(taskId); finish() } }
        b.btnSkip.setOnClickListener { lifecycleScope.launch { Graph.coordinator.skipCurrentSlot(); finish() } }
        b.btnSlipped.setOnClickListener { SlippedDialog.show(this) { finish() } }
    }

    companion object {
        private const val EXTRA_TASK = "task"
        fun intent(context: Context, taskId: Long): Intent =
            Intent(context, ReminderActivity::class.java).putExtra(EXTRA_TASK, taskId)
    }
}
