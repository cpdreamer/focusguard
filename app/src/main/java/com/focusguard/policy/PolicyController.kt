package com.focusguard.policy

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.UserManager
import android.util.Log
import com.focusguard.data.AppRule
import com.focusguard.data.BlockMode

/**
 * The only place that talks to [DevicePolicyManager]. Every method is idempotent and
 * safe to call when the app is *not* device owner (it then does nothing and returns false),
 * so the rest of the app never has to branch on ownership.
 */
class PolicyController(private val context: Context) {

    private val dpm: DevicePolicyManager = context.getSystemService(DevicePolicyManager::class.java)
    private val pm: PackageManager = context.packageManager
    private val admin: ComponentName = GuardAdminReceiver.component(context)

    val isDeviceOwner: Boolean get() = dpm.isDeviceOwnerApp(context.packageName)

    // ---------------------------------------------------------------- baseline lockdown

    /**
     * Restrictions that make the setup non-trivial to bypass. Applied once at
     * commitment start and re-applied on every boot (cheap, idempotent).
     *
     * Deliberately NOT restricted: factory reset (recovery-mode reset must remain a
     * last-resort exit), USB debugging (needed to sideload app updates), and
     * DISALLOW_INSTALL_APPS (new apps are allowed but auto-suspended, see
     * [com.focusguard.core.PackageChangeReceiver]).
     */
    fun applyBaseline(): Boolean {
        if (!isDeviceOwner) return false
        val restrictions = listOf(
            UserManager.DISALLOW_SAFE_BOOT,
            UserManager.DISALLOW_CONFIG_VPN,
            UserManager.DISALLOW_CONFIG_PRIVATE_DNS,
            UserManager.DISALLOW_APPS_CONTROL,
            UserManager.DISALLOW_UNINSTALL_APPS,
            UserManager.DISALLOW_ADD_USER,
            UserManager.DISALLOW_USER_SWITCH,
            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
            UserManager.DISALLOW_CONFIG_DATE_TIME,
        )
        for (r in restrictions) runCatching { dpm.addUserRestriction(admin, r) }
            .onFailure { Log.w(TAG, "restriction $r failed", it) }
        runCatching { dpm.setUninstallBlocked(admin, context.packageName, true) }
        // user cannot force-stop / clear data / battery-restrict the guard from Settings
        runCatching { dpm.setUserControlDisabledPackages(admin, listOf(context.packageName)) }
        // Private DNS forced to opportunistic (plain DNS to our VPN resolver, no DoT hostname)
        runCatching { dpm.setGlobalPrivateDnsModeOpportunistic(admin) }
        runCatching {
            dpm.setPermissionGrantState(
                admin, context.packageName, Manifest.permission.POST_NOTIFICATIONS,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
            )
        }
        runCatching {
            dpm.setPermissionPolicy(admin, DevicePolicyManager.PERMISSION_POLICY_PROMPT)
        }
        applyChromePolicy()
        return true
    }

    fun clearBaseline() {
        if (!isDeviceOwner) return
        for (r in listOf(
            UserManager.DISALLOW_SAFE_BOOT, UserManager.DISALLOW_CONFIG_VPN,
            UserManager.DISALLOW_CONFIG_PRIVATE_DNS, UserManager.DISALLOW_APPS_CONTROL,
            UserManager.DISALLOW_UNINSTALL_APPS, UserManager.DISALLOW_ADD_USER,
            UserManager.DISALLOW_USER_SWITCH, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
            UserManager.DISALLOW_CONFIG_DATE_TIME,
        )) runCatching { dpm.clearUserRestriction(admin, r) }
        runCatching { dpm.setUninstallBlocked(admin, context.packageName, false) }
        runCatching { dpm.setUserControlDisabledPackages(admin, emptyList()) }
        runCatching { dpm.setApplicationRestrictions(admin, CHROME, Bundle()) }
    }

    // ---------------------------------------------------------------- VPN

    /** Lock our DNS-filter VPN as always-on. `lockdown` = traffic is dropped when the VPN is down. */
    fun setAlwaysOnVpn(enabled: Boolean): Boolean {
        if (!isDeviceOwner) return false
        return runCatching {
            dpm.setAlwaysOnVpnPackage(admin, if (enabled) context.packageName else null, enabled)
            true
        }.onFailure { Log.e(TAG, "setAlwaysOnVpnPackage failed", it) }.getOrDefault(false)
    }

    // ---------------------------------------------------------------- Chrome

    /**
     * Managed configuration for Chrome (applied through the app-restrictions channel,
     * which Chrome reads as enterprise policy). Blocks incognito, forces SafeSearch and
     * restricted YouTube, disables Chrome's own DoH so our DNS filter sees every lookup,
     * and blocks image/video search result pages by URL pattern.
     */
    fun applyChromePolicy() {
        if (!isDeviceOwner) return
        val b = Bundle().apply {
            putInt("IncognitoModeAvailability", 1)          // 1 = disabled
            putBoolean("ForceGoogleSafeSearch", true)
            putInt("ForceYouTubeRestrict", 2)              // 2 = strict
            putString("DnsOverHttpsMode", "off")
            putBoolean("BrowserGuestModeEnabled", false)
            putBoolean("SavingBrowserHistoryDisabled", false)
            putStringArray("URLBlocklist", ChromePolicy.urlBlocklist.toTypedArray())
            putStringArray("URLAllowlist", ChromePolicy.urlAllowlist.toTypedArray())
        }
        runCatching { dpm.setApplicationRestrictions(admin, CHROME, b) }
            .onFailure { Log.w(TAG, "chrome policy failed", it) }
    }

    // ---------------------------------------------------------------- suspension

    /**
     * Suspend/unsuspend the given packages. Returns packages the system refused
     * (system-critical ones, or ones not installed).
     */
    fun setSuspended(packages: Collection<String>, suspended: Boolean): Set<String> {
        if (!isDeviceOwner || packages.isEmpty()) return packages.toSet()
        val safe = packages.filter { it !in DefaultPolicy.neverSuspend && it != context.packageName }
        if (safe.isEmpty()) return emptySet()
        return runCatching {
            dpm.setPackagesSuspended(admin, safe.toTypedArray(), suspended).toSet()
        }.onFailure { Log.e(TAG, "setPackagesSuspended failed", it) }.getOrDefault(safe.toSet())
    }

    fun isSuspended(pkg: String): Boolean =
        isDeviceOwner && runCatching { dpm.isPackageSuspended(admin, pkg) }.getOrDefault(false)

    // ---------------------------------------------------------------- inventory

    data class InstalledApp(
        val packageName: String,
        val label: String,
        val isSystem: Boolean,
        val category: Int,
        val firstInstallMillis: Long,
        /** Handles http(s) VIEW intents, i.e. can browse the web. */
        val isBrowser: Boolean,
    )

    /** Packages that can open http URLs (browsers, incl. "private" ones). */
    fun browserPackages(): Set<String> {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://example.com/")).addCategory(Intent.CATEGORY_BROWSABLE)
        return pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName }.toSet()
    }

    /**
     * All packages with a launcher icon (what the user can actually open), plus any
     * package in [extra] that is still installed (so suspended apps never fall out of view).
     */
    fun launchableApps(extra: Collection<String> = emptyList()): List<InstalledApp> {
        val browsers = browserPackages()
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val infos = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map { it.activityInfo.applicationInfo }
            .associateBy { it.packageName }
            .toMutableMap()
        for (p in extra) if (p !in infos) runCatching { infos[p] = pm.getApplicationInfo(p, 0) }
        return infos.values.map { toInstalled(it, browsers) }.sortedBy { it.label.lowercase() }
    }

    private fun toInstalled(ai: ApplicationInfo, browsers: Set<String>): InstalledApp {
        val firstInstall = runCatching { pm.getPackageInfo(ai.packageName, 0).firstInstallTime }.getOrDefault(0L)
        return InstalledApp(
            packageName = ai.packageName,
            label = pm.getApplicationLabel(ai).toString(),
            isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            category = ai.category,
            firstInstallMillis = firstInstall,
            isBrowser = ai.packageName in browsers,
        )
    }

    fun isInstalled(pkg: String): Boolean =
        runCatching { pm.getApplicationInfo(pkg, 0); true }.getOrDefault(false)

    fun isSystemApp(pkg: String): Boolean =
        runCatching { (pm.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_SYSTEM) != 0 }
            .getOrDefault(false)

    /**
     * Compute the effective mode of every launchable app given user rules.
     * @param commitmentStartMillis apps first installed after this are blocked by default.
     */
    fun effectiveModes(
        userRules: List<AppRule>,
        commitmentStartMillis: Long,
        extra: Collection<String> = emptyList(),
    ): Map<String, BlockMode> {
        val rules = userRules.associate { it.packageName to it.mode }
        return launchableApps(extra).associate { app -> app.packageName to decide(app, rules[app.packageName], commitmentStartMillis) }
    }

    fun decide(app: InstalledApp, userRule: BlockMode?, commitmentStartMillis: Long): BlockMode {
        if (userRule == null && app.isBrowser && app.packageName !in DefaultPolicy.alwaysAllow) return BlockMode.ALWAYS
        return DefaultPolicy.decide(
            packageName = app.packageName,
            userRule = userRule,
            isSystem = app.isSystem,
            category = app.category,
            installedAfterCommitment = commitmentStartMillis > 0 && app.firstInstallMillis > commitmentStartMillis,
        )
    }

    // ---------------------------------------------------------------- release

    /**
     * Give the phone back: unsuspend everything, drop every restriction and the VPN lock,
     * then clear device-owner status. After this the app is an ordinary app and can be
     * uninstalled normally.
     */
    fun releaseDeviceOwner(allKnownPackages: Collection<String>) {
        if (!isDeviceOwner) return
        setSuspended(allKnownPackages, false)
        setAlwaysOnVpn(false)
        clearBaseline()
        runCatching { dpm.clearDeviceOwnerApp(context.packageName) }
            .onFailure { Log.e(TAG, "clearDeviceOwnerApp failed", it) }
    }

    companion object {
        private const val TAG = "PolicyController"
        const val CHROME = "com.android.chrome"
    }
}
