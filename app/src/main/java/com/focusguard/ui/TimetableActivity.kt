package com.focusguard.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.focusguard.R
import com.focusguard.core.Graph
import com.focusguard.data.Commitment
import com.focusguard.data.CommitmentKind
import com.focusguard.databinding.ActivityListBinding
import com.focusguard.databinding.DialogCommitmentBinding
import com.focusguard.databinding.ItemTwoLinesBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/** Weekly fixed events: classes, meals, sport, sleep. One row per (event, day). */
class TimetableActivity : AppCompatActivity() {

    private lateinit var b: ActivityListBinding
    private val adapter = Adapter { confirmDelete(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityListBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.toolbar.title = getString(R.string.timetable_title)
        b.toolbar.setNavigationOnClickListener { finish() }
        b.header.visibility = View.GONE
        b.empty.text = getString(R.string.timetable_empty)
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        b.fab.setOnClickListener { showAddDialog() }
        lifecycleScope.launch {
            Graph.db.commitments().observeAll().collectLatest {
                adapter.submit(it)
                b.empty.visibility = if (it.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun showAddDialog() {
        val d = DialogCommitmentBinding.inflate(LayoutInflater.from(this))
        val kinds = CommitmentKind.values()
        d.kind.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, kinds.map { Ui.kindLabel(this, it) })
        var start = 8 * 60
        var end = 9 * 60
        fun renderTimes() {
            d.start.text = getString(R.string.commitment_start) + " " + Ui.minuteOfDay(start)
            d.end.text = getString(R.string.commitment_end) + " " + Ui.minuteOfDay(end)
        }
        renderTimes()
        d.start.setOnClickListener { Ui.pickTime(this, R.string.commitment_start, start) { start = it; renderTimes() } }
        d.end.setOnClickListener { Ui.pickTime(this, R.string.commitment_end, end) { end = it; renderTimes() } }
        val dayButtons = listOf(d.d1, d.d2, d.d3, d.d4, d.d5, d.d6, d.d7)

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(d.root)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_add, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val days = dayButtons.mapIndexedNotNull { i, btn -> if (btn.isChecked) i + 1 else null }
                val kind = kinds[d.kind.selectedItemPosition]
                val title = d.title.text?.toString()?.trim().orEmpty().ifEmpty { Ui.kindLabel(this, kind) }
                when {
                    days.isEmpty() -> Ui.toast(this, R.string.commitment_error_days)
                    start == end -> Ui.toast(this, R.string.commitment_error_time)
                    else -> {
                        val rows = days.map { Commitment(title = title, kind = kind, dayOfWeek = it, startMinute = start, endMinute = end) }
                        lifecycleScope.launch {
                            Graph.db.commitments().upsertAll(rows)
                            Graph.coordinator.refresh("timetable")
                        }
                        dialog.dismiss()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun confirmDelete(c: Commitment) {
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.commitment_delete_confirm, c.title))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                lifecycleScope.launch { Graph.db.commitments().delete(c); Graph.coordinator.refresh("timetable") }
            }
            .show()
    }

    private class Adapter(private val onLongClick: (Commitment) -> Unit) : RecyclerView.Adapter<Adapter.VH>() {
        private var items: List<Commitment> = emptyList()

        fun submit(list: List<Commitment>) {
            items = list.sortedWith(compareBy<Commitment> { it.dayOfWeek }.thenBy { it.startMinute })
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemTwoLinesBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val c = items[position]
            val ctx = h.b.root.context
            h.b.title.text = c.title
            h.b.sub.text = Ui.kindLabel(ctx, c.kind)
            val day = DayOfWeek.of(c.dayOfWeek).getDisplayName(TextStyle.SHORT, Locale.getDefault())
            h.b.trailing.text = "$day ${Ui.minuteOfDay(c.startMinute)}–${Ui.minuteOfDay(c.endMinute)}"
            h.b.root.setOnLongClickListener { onLongClick(c); true }
            h.b.root.setOnClickListener { onLongClick(c) }
        }

        class VH(val b: ItemTwoLinesBinding) : RecyclerView.ViewHolder(b.root)
    }
}
