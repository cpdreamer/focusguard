package com.focusguard.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.focusguard.R
import com.focusguard.core.Graph
import com.focusguard.data.Task
import com.focusguard.databinding.ActivityTaskEditBinding
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Create or edit one task. Saving triggers a re-plan. */
class TaskEditActivity : AppCompatActivity() {

    private lateinit var b: ActivityTaskEditBinding
    private var existing: Task? = null
    private var deadline: Instant? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityTaskEditBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.toolbar.setNavigationOnClickListener { finish() }
        b.importance.check(R.id.imp_normal)
        b.estimate.setText("60")

        val id = intent.getLongExtra(EXTRA_ID, -1L)
        b.toolbar.title = getString(if (id == -1L) R.string.task_new else R.string.task_edit)
        if (id != -1L) lifecycleScope.launch {
            val t = Graph.db.tasks().byId(id) ?: run { finish(); return@launch }
            existing = t
            b.title.setText(t.title)
            b.subject.setText(t.subject)
            b.estimate.setText(t.estimateMinutes.toString())
            b.firstAction.setText(t.firstAction)
            deadline = t.deadlineMillis?.let(Instant::ofEpochMilli)
            b.importance.check(when (t.importance) { 1 -> R.id.imp_low; 3 -> R.id.imp_high; else -> R.id.imp_normal })
            b.btnDelete.visibility = View.VISIBLE
            renderDeadline()
        }

        b.btnDeadline.setOnClickListener { pickDeadline() }
        b.btnDeadlineClear.setOnClickListener { deadline = null; renderDeadline() }
        b.btnSave.setOnClickListener { save() }
        b.btnDelete.setOnClickListener { confirmDelete() }
        renderDeadline()
    }

    private fun renderDeadline() {
        b.deadline.text = deadline?.let { Ui.dateTime(it.toEpochMilli()) } ?: getString(R.string.task_deadline_none)
    }

    private fun pickDeadline() {
        val zone = ZoneId.systemDefault()
        val initial = (deadline ?: Instant.now().plus(java.time.Duration.ofDays(2))).atZone(zone)
        val datePicker = MaterialDatePicker.Builder.datePicker()
            .setSelection(initial.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            .build()
        datePicker.addOnPositiveButtonClickListener { utcMillis ->
            val date = Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate()
            val currentMinute = deadline?.atZone(zone)?.toLocalTime()?.let { it.hour * 60 + it.minute } ?: (8 * 60)
            Ui.pickTime(this, R.string.task_deadline_pick, currentMinute) { minute ->
                deadline = date.atTime(LocalTime.of(minute / 60, minute % 60)).atZone(zone).toInstant()
                renderDeadline()
            }
        }
        datePicker.show(supportFragmentManager, "date")
    }

    private fun save() {
        val title = b.title.text?.toString()?.trim().orEmpty()
        if (title.isEmpty()) { b.title.error = getString(R.string.task_error_title); return }
        val estimate = b.estimate.text?.toString()?.trim()?.toIntOrNull()
        if (estimate == null || estimate !in 5..600) { b.estimate.error = getString(R.string.task_error_estimate); return }
        val importance = when (b.importance.checkedButtonId) { R.id.imp_low -> 1; R.id.imp_high -> 3; else -> 2 }
        val task = (existing ?: Task(
            title = title, subject = "", deadlineMillis = null, estimateMinutes = estimate, createdMillis = System.currentTimeMillis()
        )).copy(
            title = title,
            subject = b.subject.text?.toString()?.trim().orEmpty(),
            deadlineMillis = deadline?.toEpochMilli(),
            estimateMinutes = estimate,
            importance = importance,
            firstAction = b.firstAction.text?.toString()?.trim().orEmpty(),
            // an edited estimate below what is already done would make the task vanish; clamp
            doneMinutes = minOf(existing?.doneMinutes ?: 0, estimate - 1).coerceAtLeast(0),
        )
        lifecycleScope.launch {
            if (existing == null) Graph.db.tasks().insert(task) else Graph.db.tasks().update(task)
            Graph.coordinator.refresh("task-saved")
            finish()
        }
    }

    private fun confirmDelete() {
        val t = existing ?: return
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.commitment_delete_confirm, t.title))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                lifecycleScope.launch { Graph.db.tasks().delete(t); Graph.coordinator.refresh("task-deleted"); finish() }
            }
            .show()
    }

    companion object {
        private const val EXTRA_ID = "id"
        fun intent(context: Context, id: Long?): Intent =
            Intent(context, TaskEditActivity::class.java).putExtra(EXTRA_ID, id ?: -1L)
    }
}
