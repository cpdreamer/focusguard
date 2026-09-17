package com.focusguard.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.focusguard.R
import com.focusguard.core.Graph
import com.focusguard.core.Progress
import com.focusguard.data.JournalEntry
import com.focusguard.data.JournalKind
import com.focusguard.data.SessionOutcome
import com.focusguard.databinding.ActivityReviewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/** Sunday review: the week in numbers, one question, previous answers. */
class ReviewActivity : AppCompatActivity() {

    private lateinit var b: ActivityReviewBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityReviewBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.toolbar.setNavigationOnClickListener { finish() }
        b.save.setOnClickListener { save() }
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val weekStart = today.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()
            val (sessions, done, journal) = withContext(Dispatchers.IO) {
                Triple(
                    Graph.db.sessions().since(weekStart),
                    Graph.db.tasks().completedSince(weekStart),
                    Graph.db.journal().since(today.minusDays(120).atStartOfDay(zone).toInstant().toEpochMilli()),
                )
            }
            b.stats.text = getString(
                R.string.review_week_stats,
                Progress.minutesPerDay(sessions, zone).values.sum(),
                done.size,
                sessions.count { it.outcome == SessionOutcome.SKIPPED },
                sessions.count { it.outcome == SessionOutcome.SLIPPED },
            )
            val slips = journal.filter { it.kind == JournalKind.SLIP && it.createdMillis >= weekStart }
            b.slips.text = if (slips.isEmpty()) "—" else slips.joinToString("\n") { "• " + it.text }
            val reviews = journal.filter { it.kind == JournalKind.WEEKLY_REVIEW }.sortedByDescending { it.createdMillis }
            b.history.text = if (reviews.isEmpty()) "—" else reviews.joinToString("\n\n") { Ui.dateTime(it.createdMillis) + "\n" + it.text }
        }
    }

    private fun save() {
        val text = b.answer.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        lifecycleScope.launch {
            Graph.db.journal().insert(JournalEntry(createdMillis = System.currentTimeMillis(), kind = JournalKind.WEEKLY_REVIEW, text = text))
            Graph.settings.lastReviewEpochDay = LocalDate.now().toEpochDay()
            b.answer.setText("")
            Ui.toast(this@ReviewActivity, R.string.review_saved)
            load()
        }
    }
}
