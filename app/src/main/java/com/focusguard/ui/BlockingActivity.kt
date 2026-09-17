package com.focusguard.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.focusguard.R
import com.focusguard.core.Graph
import com.focusguard.data.AppRule
import com.focusguard.data.BlockMode
import com.focusguard.data.DomainRule
import com.focusguard.databinding.ActivityListBinding
import com.focusguard.databinding.ItemTwoLinesBinding
import com.focusguard.net.DnsFilterVpnService
import com.focusguard.net.DomainFilter
import com.focusguard.policy.PolicyController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * Shows every launchable app with its effective mode, plus user-added blocked domains.
 * Tapping an app cycles its mode; while the commitment is active only stricter changes are accepted.
 */
class BlockingActivity : AppCompatActivity() {

    private lateinit var b: ActivityListBinding
    private val adapter = Adapter(::onRowClick)

    private sealed class Row {
        data class Header(val title: String) : Row()
        data class App(val app: PolicyController.InstalledApp, val mode: BlockMode, val userRule: BlockMode?) : Row()
        data class Domain(val rule: DomainRule) : Row()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityListBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.toolbar.title = getString(R.string.blocking_title)
        b.toolbar.setNavigationOnClickListener { finish() }
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        b.fab.setOnClickListener { addDomainDialog() }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun locked(): Boolean = Graph.settings.isCommitmentActive(Instant.now())

    private fun reload() {
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                val rules = Graph.db.appRules().all().associate { it.packageName to it.mode }
                val start = Graph.settings.commitmentStartMillis()
                val apps = Graph.policy.launchableApps(Graph.settings.managedPackages)
                    .filter { it.packageName != packageName }
                    .map { app -> Row.App(app, Graph.policy.decide(app, rules[app.packageName], start), rules[app.packageName]) }
                    .sortedWith(compareBy<Row.App> { it.mode.ordinal }.thenBy { it.app.label.lowercase() })
                val domains = Graph.db.domainRules().all().filter { it.blocked }.sortedBy { it.host }.map { Row.Domain(it) }
                buildList {
                    add(Row.Header(getString(R.string.blocking_apps)))
                    addAll(apps)
                    add(Row.Header(getString(R.string.blocking_domains)))
                    addAll(domains)
                }
            }
            adapter.submit(rows)
            val stats = Graph.stats
            val recent = stats.recentBlocked().takeLast(5).ifEmpty { listOf(getString(R.string.blocking_none)) }
            b.header.text = listOf(
                getString(
                    R.string.blocking_status,
                    yesNo(Graph.policy.isDeviceOwner),
                    yesNo(DnsFilterVpnService.isTunnelUp),
                    Graph.filterStore.downloadedCount(),
                ),
                getString(R.string.blocking_stats, stats.dnsQueries.get(), stats.dnsBlocked.get()),
                getString(R.string.blocking_recent, recent.joinToString(", ")),
                if (locked()) getString(R.string.blocking_locked_hint) else "",
            ).filter { it.isNotEmpty() }.joinToString("\n")
        }
    }

    private fun yesNo(v: Boolean) = getString(if (v) R.string.yes else R.string.no)

    private fun onRowClick(row: Row) {
        when (row) {
            is Row.App -> cycleApp(row)
            is Row.Domain -> removeDomain(row.rule)
            is Row.Header -> Unit
        }
    }

    /** ALLOW -> FOCUS_ONLY -> ALWAYS -> (back to default if allowed). Relaxing is refused while locked. */
    private fun cycleApp(row: Row.App) {
        val next = when (row.mode) {
            BlockMode.ALLOW -> BlockMode.FOCUS_ONLY
            BlockMode.FOCUS_ONLY -> BlockMode.ALWAYS
            BlockMode.ALWAYS -> BlockMode.ALLOW
        }
        if (locked() && next.ordinal < row.mode.ordinal) { Ui.toast(this, R.string.locked_toast); return }
        lifecycleScope.launch {
            Graph.db.appRules().upsert(AppRule(row.app.packageName, next))
            Graph.coordinator.refresh("app-rule")
            reload()
        }
    }

    private fun addDomainDialog() {
        val input = TextInputEditText(this).apply { hint = getString(R.string.blocking_domain_hint); setSingleLine() }
        val container = android.widget.FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt(); setPadding(pad, 0, pad, 0); addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.blocking_domains)
            .setView(container)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_add) { _, _ ->
                val host = DomainFilter.normalize(input.text?.toString().orEmpty())
                if (!DomainFilter.isValidHost(host)) { Ui.toast(this, R.string.blocking_domain_invalid); return@setPositiveButton }
                lifecycleScope.launch { Graph.db.domainRules().upsert(DomainRule(host, blocked = true)); reload() }
            }
            .show()
    }

    private fun removeDomain(rule: DomainRule) {
        if (locked()) { Ui.toast(this, R.string.locked_toast); return }
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.commitment_delete_confirm, rule.host))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                lifecycleScope.launch { Graph.db.domainRules().delete(rule.host); reload() }
            }
            .show()
    }

    private class Adapter(private val onClick: (Row) -> Unit) : RecyclerView.Adapter<Adapter.VH>() {
        private var items: List<Row> = emptyList()

        fun submit(list: List<Row>) { items = list; notifyDataSetChanged() }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemTwoLinesBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val row = items[position]
            val ctx = h.b.root.context
            when (row) {
                is Row.Header -> {
                    h.b.title.text = row.title
                    h.b.title.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
                    h.b.sub.visibility = View.GONE
                    h.b.trailing.text = ""
                    h.b.root.isClickable = false
                }
                is Row.App -> {
                    h.b.title.text = row.app.label
                    h.b.title.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                    h.b.sub.visibility = View.VISIBLE
                    h.b.sub.text = row.app.packageName
                    h.b.trailing.text = ctx.getString(
                        when (row.mode) {
                            BlockMode.ALLOW -> R.string.mode_allow
                            BlockMode.FOCUS_ONLY -> R.string.mode_focus
                            BlockMode.ALWAYS -> R.string.mode_always
                        }
                    )
                    h.b.trailing.setTextColor(ctx.getColor(if (row.mode == BlockMode.ALLOW) R.color.muted else R.color.warn))
                    h.b.root.isClickable = true
                    h.b.root.setOnClickListener { onClick(row) }
                }
                is Row.Domain -> {
                    h.b.title.text = row.rule.host
                    h.b.title.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                    h.b.sub.visibility = View.GONE
                    h.b.trailing.text = ctx.getString(R.string.mode_always)
                    h.b.trailing.setTextColor(ctx.getColor(R.color.warn))
                    h.b.root.isClickable = true
                    h.b.root.setOnClickListener { onClick(row) }
                }
            }
        }

        class VH(val b: ItemTwoLinesBinding) : RecyclerView.ViewHolder(b.root)
    }
}
