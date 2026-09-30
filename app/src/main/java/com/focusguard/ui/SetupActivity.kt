package com.focusguard.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings as SysSettings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.focusguard.R
import com.focusguard.core.Graph
import com.focusguard.core.GuardService
import com.focusguard.data.BlockMode
import com.focusguard.data.Settings
import com.focusguard.databinding.ActivitySetupBinding
import com.focusguard.databinding.ItemTwoLinesBinding
import com.focusguard.policy.PolicyController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant

/**
 * First-run screen: shows whether device-owner rights are present, lets the user set sleep
 * hours, previews what happens to every installed app, collects the runtime permissions the
 * guard needs, then starts the commitment (length picked by the user).
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var b: ActivitySetupBinding
    private val adapter = AppsAdapter()
    private var apps: List<Pair<PolicyController.InstalledApp, BlockMode>> = emptyList()

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshPermissionHint() }
    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refreshPermissionHint() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.apps.layoutManager = LinearLayoutManager(this)
        b.apps.adapter = adapter

        val s = Graph.settings
        b.bedtime.setOnClickListener { Ui.pickTime(this, R.string.setup_bedtime, s.bedtimeMinute) { s.bedtimeMinute = it; renderTimes() } }
        b.wake.setOnClickListener { Ui.pickTime(this, R.string.setup_wake, s.wakeMinute) { s.wakeMinute = it; renderTimes() } }
        b.planning.setOnClickListener { Ui.pickTime(this, R.string.setup_planning, s.planningMinute) { s.planningMinute = it; renderTimes() } }
        b.commit.setOnClickListener { onCommit() }
        b.testOnly.setOnClickListener { confirmCommit(testMode = true) }
        b.permHint.setOnClickListener { requestMissingPermissions() }
        renderTimes()
    }

    override fun onResume() {
        super.onResume()
        if (Graph.settings.isProvisioned()) { finish(); return }
        val owner = Graph.policy.isDeviceOwner
        b.doStatus.text = getString(if (owner) R.string.setup_do_ok else R.string.setup_do_missing)
        b.doStatus.setTextColor(getColor(if (owner) R.color.primary else R.color.warn))
        b.testOnly.visibility = if (owner) View.GONE else View.VISIBLE
        refreshPermissionHint()
        loadApps()
    }

    private fun renderTimes() {
        val s = Graph.settings
        b.bedtime.text = getString(R.string.setup_bedtime) + " " + Ui.minuteOfDay(s.bedtimeMinute)
        b.wake.text = getString(R.string.setup_wake) + " " + Ui.minuteOfDay(s.wakeMinute)
        b.planning.text = getString(R.string.setup_planning) + " " + Ui.minuteOfDay(s.planningMinute)
    }

    private fun loadApps() {
        lifecycleScope.launch {
            apps = withContext(Dispatchers.IO) {
                val rules = Graph.db.appRules().all().associate { it.packageName to it.mode }
                Graph.policy.launchableApps()
                    .filter { it.packageName != packageName }
                    .map { it to Graph.policy.decide(it, rules[it.packageName], -1L) }
                    .sortedWith(compareByDescending<Pair<PolicyController.InstalledApp, BlockMode>> { it.second.ordinal }.thenBy { it.first.label.lowercase() })
            }
            b.appsSummary.text = getString(
                R.string.setup_apps_summary,
                apps.count { it.second == BlockMode.ALWAYS },
                apps.count { it.second == BlockMode.FOCUS_ONLY },
                apps.count { it.second == BlockMode.ALLOW },
            )
            adapter.submit(apps)
        }
    }

    // ------------------------------------------------------------------ permissions

    private fun notificationsGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun vpnPrepared(): Boolean = VpnService.prepare(this) == null

    private fun refreshPermissionHint() {
        val missing = buildList {
            if (!notificationsGranted()) add(getString(R.string.setup_notifications))
            if (Graph.settings.webFilterEnabled && !vpnPrepared()) add(getString(R.string.setup_vpn_needed))
        }
        b.permHint.text = missing.joinToString("\n")
        b.permHint.visibility = if (missing.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Asks for whatever is still missing, one thing at a time; the result callbacks re-render. */
    private fun requestMissingPermissions(): Boolean {
        if (!notificationsGranted()) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return false
        }
        if (Graph.settings.webFilterEnabled) {
            val intent = VpnService.prepare(this)
            if (intent != null) { vpnConsent.launch(intent); return false }
        }
        return true
    }

    // ------------------------------------------------------------------ commit

    private fun onCommit() {
        if (!requestMissingPermissions()) return
        confirmCommit(testMode = false)
    }

    private fun confirmCommit(testMode: Boolean) {
        Ui.pickCommitmentLength(this) { days ->
            val end = Instant.now().plus(Duration.ofDays(days.toLong()))
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.setup_commit_confirm_title, days))
                .setMessage(getString(R.string.setup_commit_confirm_text, Ui.dateTime(end.toEpochMilli())))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.setup_commit) { _, _ -> commit(testMode, days) }
                .show()
        }
    }

    private fun commit(testMode: Boolean, days: Int) {
        val s = Graph.settings
        s.startCommitment(Instant.now(), Duration.ofDays(days.toLong()))
        s.setProvisioned(true)
        if (!testMode) {
            Graph.policy.applyBaseline()
            if (s.webFilterEnabled) Graph.policy.setAlwaysOnVpn(true)
        }
        requestBatteryExemption()
        GuardService.start(this)
        lifecycleScope.launch {
            Graph.coordinator.refresh("commit")
            startActivity(Intent(this@SetupActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
        }
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        runCatching {
            startActivity(
                Intent(SysSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(android.net.Uri.parse("package:$packageName"))
            )
        }
    }

    private class AppsAdapter : RecyclerView.Adapter<AppsAdapter.VH>() {
        private var items: List<Pair<PolicyController.InstalledApp, BlockMode>> = emptyList()

        fun submit(list: List<Pair<PolicyController.InstalledApp, BlockMode>>) { items = list; notifyDataSetChanged() }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemTwoLinesBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val (app, mode) = items[position]
            val ctx = h.b.root.context
            h.b.title.text = app.label
            h.b.sub.text = app.packageName
            h.b.trailing.text = ctx.getString(
                when (mode) {
                    BlockMode.ALLOW -> R.string.mode_allow
                    BlockMode.FOCUS_ONLY -> R.string.mode_focus
                    BlockMode.ALWAYS -> R.string.mode_always
                }
            )
            h.b.trailing.setTextColor(ctx.getColor(if (mode == BlockMode.ALLOW) R.color.muted else R.color.warn))
        }

        class VH(val b: ItemTwoLinesBinding) : RecyclerView.ViewHolder(b.root)
    }
}
