package com.focusguard.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.focusguard.R
import com.focusguard.core.Coordinator
import com.focusguard.core.Graph
import com.focusguard.core.Progress
import com.focusguard.data.Task
import com.focusguard.databinding.ActivityMainBinding
import com.focusguard.databinding.ItemTaskBinding
import com.focusguard.scheduler.Assignment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Home: what to do now (one thing), the open tasks, tomorrow's plan, commitment status.
 * All decisions come from [Coordinator.state]; this screen only renders and forwards taps.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val tasksAdapter = TaskAdapter { startActivity(TaskEditActivity.intent(this, it.id)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)

        b.tasks.layoutManager = LinearLayoutManager(this)
        b.tasks.adapter = tasksAdapter
        b.fab.setOnClickListener { startActivity(TaskEditActivity.intent(this, null)) }
        b.status.setOnClickListener { if (!Graph.settings.isProvisioned()) startActivity(Intent(this, SetupActivity::class.java)) }

        b.btnStart.setOnClickListener { Graph.coordinator.markStarted(); render(Graph.coordinator.state.value) }
        b.btnDone.setOnClickListener { currentTaskId()?.let { id -> lifecycleScope.launch { Graph.coordinator.markDone(id) } } }
        b.btnSkip.setOnClickListener { lifecycleScope.launch { Graph.coordinator.skipCurrentSlot() } }
        b.btnSlipped.setOnClickListener { SlippedDialog.show(this) }

        lifecycleScope.launch { Graph.coordinator.state.collectLatest { render(it) } }
        lifecycleScope.launch { Graph.db.tasks().observeOpen().collectLatest { tasks ->
            tasksAdapter.submit(tasks)
            b.tasksEmpty.visibility = if (tasks.isEmpty()) View.VISIBLE else View.GONE
        } }
        if (intent.getIntExtra(EXTRA_TAB, 0) == TAB_TOMORROW) b.scroll.post { b.scroll.smoothScrollTo(0, b.tomorrowHeader.top) }
    }

    override fun onResume() {
        super.onResume()
        if (!Graph.settings.isProvisioned()) {
            startActivity(Intent(this, SetupActivity::class.java))
            return
        }
        Graph.coordinator.refreshAsync("ui")
        renderStatus()
        renderStreak()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: android.view.Menu): Boolean {
        val active = Graph.settings.isCommitmentActive(Instant.now())
        menu.findItem(R.id.menu_release).isVisible = !active && Graph.settings.isProvisioned()
        menu.findItem(R.id.menu_renew).isVisible = !active && Graph.settings.isProvisioned()
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_timetable -> startActivity(Intent(this, TimetableActivity::class.java))
            R.id.menu_blocking -> startActivity(Intent(this, BlockingActivity::class.java))
            R.id.menu_review -> startActivity(Intent(this, ReviewActivity::class.java))
            R.id.menu_settings -> startActivity(Intent(this, SettingsActivity::class.java))
            R.id.menu_escrow -> escrowDialog()
            R.id.menu_renew -> renew()
            R.id.menu_release -> confirmRelease()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun renew() {
        if (Graph.settings.isCommitmentActive(Instant.now())) { Ui.toast(this, R.string.locked_toast); return }
        Ui.pickCommitmentLength(this) { duration ->
            if (Graph.settings.isCommitmentActive(Instant.now())) { Ui.toast(this, R.string.locked_toast); return@pickCommitmentLength }
            Graph.settings.startCommitment(Instant.now(), duration)
            Graph.coordinator.refreshAsync("renew")
            Toast.makeText(this, getString(R.string.renew_done, Ui.durationText(duration)), Toast.LENGTH_SHORT).show()
            renderStatus()
        }
    }

    private fun escrowDialog() {
        val now = Instant.now()
        when {
            com.focusguard.core.Escrow.isRevealed(now) -> MaterialAlertDialogBuilder(this)
                .setTitle(R.string.menu_escrow)
                .setMessage(getString(R.string.escrow_show, Graph.settings.escrowAccount, Graph.settings.escrowPassword))
                .setPositiveButton(android.R.string.ok, null).show()
            !com.focusguard.core.Escrow.isSealed -> EscrowUi.setupDialog(this)
            com.focusguard.core.Escrow.due != null -> MaterialAlertDialogBuilder(this)
                .setTitle(R.string.menu_escrow)
                .setMessage(getString(R.string.escrow_requested, com.focusguard.core.Escrow.fmt(com.focusguard.core.Escrow.due!!)))
                .setNegativeButton(android.R.string.ok, null)
                .setPositiveButton(R.string.escrow_cancel_request) { _, _ -> com.focusguard.core.Escrow.cancel(this); Ui.toast(this, R.string.escrow_cancelled_toast) }
                .show()
            else -> {
                val phrase = android.widget.EditText(this).apply { hint = getString(R.string.escrow_phrase) }
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.escrow_request_title)
                    .setMessage(getString(R.string.escrow_request_text, com.focusguard.core.Escrow.DELAY_HOURS))
                    .setView(phrase)
                    .setNegativeButton(R.string.action_cancel, null)
                    .setPositiveButton(R.string.escrow_request_button) { _, _ ->
                        if (phrase.text.toString().trim() != getString(R.string.escrow_phrase)) { Ui.toast(this, R.string.escrow_phrase_wrong); return@setPositiveButton }
                        val at = com.focusguard.core.Escrow.request(this)
                        if (at != null) android.widget.Toast.makeText(this, getString(R.string.escrow_requested, com.focusguard.core.Escrow.fmt(at)), android.widget.Toast.LENGTH_LONG).show()
                    }
                    .show()
            }
        }
    }

    private fun confirmRelease() {
        if (Graph.settings.isCommitmentActive(Instant.now())) { Ui.toast(this, R.string.locked_toast); return }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.release_confirm_title)
            .setMessage(R.string.release_confirm_text)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.menu_release) { _, _ ->
                if (Graph.settings.requestRelease(Instant.now())) {
                    lifecycleScope.launch { Graph.coordinator.refresh("release"); finish() }
                } else Ui.toast(this, R.string.locked_toast)
            }
            .show()
    }

    private fun currentTaskId(): Long? = Graph.coordinator.state.value?.current?.task?.id

    private fun renderStatus() {
        val s = Graph.settings
        val now = Instant.now()
        b.status.text = when {
            !s.isProvisioned() -> getString(R.string.main_not_provisioned)
            s.isCommitmentActive(now) -> {
                val end = s.commitmentEnd()!!
                val days = Duration.between(now, end).toDays().toInt() + 1
                getString(R.string.main_commitment, days, Ui.instantDate(end))
            }
            else -> getString(R.string.main_commitment_over)
        }
        invalidateOptionsMenu()
    }

    private fun renderStreak() {
        lifecycleScope.launch {
            val zone = ZoneId.systemDefault()
            val since = LocalDate.now(zone).minusDays(60).atStartOfDay(zone).toInstant().toEpochMilli()
            val sessions = withContext(Dispatchers.IO) { Graph.db.sessions().since(since) }
            val today = LocalDate.now(zone)
            b.streak.text = getString(
                R.string.main_streak,
                Progress.streak(sessions, zone, today, Graph.settings.dailyMinimumMinutes),
                Progress.minutesThisWeek(sessions, zone, today),
            )
        }
    }

    private fun render(state: Coordinator.State?) {
        if (state == null) return
        val now = ZonedDateTime.now()
        val cur = state.current
        when {
            state.mode == Coordinator.Mode.SLEEP -> {
                b.nowTitle.text = getString(R.string.sleep_title)
                b.nowSub.text = getString(R.string.main_sleep, Ui.minuteOfDay(Graph.settings.wakeMinute))
                b.nowAction.text = ""
                showButtons(false)
            }
            cur != null -> {
                b.nowTitle.text = cur.task.title
                val left = Duration.between(now, cur.slot.end).toMinutes().coerceAtLeast(0)
                b.nowSub.text = getString(R.string.main_until, Ui.time(cur.slot.end), left) +
                    (cur.task.deadlineMillis?.let { " · " + getString(R.string.task_deadline, Ui.dateTime(it)) } ?: "")
                b.nowAction.text = if (cur.task.firstAction.isNotBlank()) getString(R.string.main_first_action, cur.task.firstAction) else ""
                showButtons(true)
                b.btnStart.visibility = if (Graph.settings.currentStarted) View.GONE else View.VISIBLE
            }
            else -> {
                b.nowTitle.text = getString(R.string.main_nothing)
                val next = state.next
                b.nowSub.text = if (next != null) getString(R.string.main_free_next, Ui.time(next.slot.start), next.task.title)
                else getString(R.string.main_free_no_next)
                b.nowAction.text = ""
                showButtons(false)
            }
        }
        if (state.infeasible.isNotEmpty()) {
            b.infeasible.visibility = View.VISIBLE
            b.infeasible.text = getString(R.string.main_infeasible, state.infeasible.joinToString(", ") { it.title })
        } else b.infeasible.visibility = View.GONE

        val tomorrow = now.toLocalDate().plusDays(1)
        val tomorrowPlan = state.plan.assignments.filter { it.slot.start.toLocalDate() == tomorrow }
        b.tomorrow.text = if (tomorrowPlan.isEmpty()) getString(R.string.main_tomorrow_empty)
        else tomorrowPlan.joinToString("\n") { "${Ui.time(it.slot.start)}–${Ui.time(it.slot.end)}  ${it.task.title}" }
    }

    private fun showButtons(visible: Boolean) {
        b.nowButtons.visibility = if (visible) View.VISIBLE else View.GONE
        b.nowButtons2.visibility = View.VISIBLE // skip/slipped are always meaningful
        b.btnSkip.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private class TaskAdapter(private val onClick: (Task) -> Unit) : RecyclerView.Adapter<TaskAdapter.VH>() {
        private var items: List<Task> = emptyList()

        fun submit(list: List<Task>) {
            items = list.sortedWith(compareBy<Task> { it.deadlineMillis ?: Long.MAX_VALUE }.thenByDescending { it.importance })
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemTaskBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val t = items[position]
            val ctx = h.b.root.context
            h.b.title.text = if (t.subject.isNotBlank()) ctx.getString(R.string.task_line, t.subject, t.title) else t.title
            val deadline = t.deadlineMillis?.let {
                val late = it < System.currentTimeMillis()
                ctx.getString(R.string.task_deadline, Ui.dateTime(it)) + if (late) " · " + ctx.getString(R.string.task_late) else ""
            } ?: ctx.getString(R.string.task_no_deadline)
            h.b.sub.text = deadline + " · " + ctx.getString(R.string.task_remaining, t.remainingMinutes)
            h.b.root.setOnClickListener { onClick(t) }
        }

        class VH(val b: ItemTaskBinding) : RecyclerView.ViewHolder(b.root)
    }

    companion object {
        const val EXTRA_TAB = "tab"
        const val TAB_TOMORROW = 1

        fun intent(context: Context): Intent =
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}

/** Shared "Slipped" dialog: optional one-line note, then a 10-minute reset. */
object SlippedDialog {
    fun show(activity: AppCompatActivity, onDone: () -> Unit = {}) {
        val input = com.google.android.material.textfield.TextInputEditText(activity).apply {
            hint = activity.getString(R.string.slipped_dialog_hint)
            setSingleLine()
        }
        val container = android.widget.FrameLayout(activity).apply {
            val pad = (20 * activity.resources.displayMetrics.density).toInt()
            setPadding(pad, 0, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.slipped_dialog_title)
            .setView(container)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_ok) { _, _ ->
                val note = input.text?.toString()
                activity.lifecycleScope.launch { Graph.coordinator.slipped(note); onDone() }
            }
            .show()
    }
}

/** Used by [ReminderActivity] and [MainActivity] to describe an assignment consistently. */
internal fun Assignment.untilText(context: Context): String = context.getString(R.string.main_until, Ui.time(slot.end),
    Duration.between(ZonedDateTime.now(), slot.end).toMinutes().coerceAtLeast(0))
