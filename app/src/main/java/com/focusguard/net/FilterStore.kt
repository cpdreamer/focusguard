package com.focusguard.net

import android.content.Context
import android.util.Log
import com.focusguard.data.DomainRuleDao
import com.focusguard.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.net.URL
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns the live [DomainFilter]. Rebuilds it when user rules change, when the downloaded list is
 * refreshed, or when the per-commitment [Settings.rules] change. Reads are lock-free ([AtomicReference]).
 *
 * Allow-only mode ([Settings.allowOnly]): the allow list is `assets/allowlist.txt` (the same file
 * the Windows kit ships as `allowlist.txt`) + the hosts of the list/config sources (so updates keep
 * flowing) + the names granted through the window. If the asset is missing or empty the filter
 * does not fall back to blocklist mode: only the source hosts resolve.
 */
class FilterStore(private val context: Context, private val domainRules: DomainRuleDao, private val settings: Settings) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloaded = AtomicReference<HashedDomainSet>(HashedDomainSet.empty())
    private val current = AtomicReference(DomainFilter())
    private var userAllow: List<String> = emptyList()
    private var userBlock: List<String> = emptyList()
    private val baseAllowlist: Set<String> by lazy { readBaseAllowlist() }

    val filter: DomainFilter get() = current.get()

    fun start() {
        reloadDownloaded()
        scope.launch {
            domainRules.observeAll().collectLatest { rules ->
                userAllow = rules.filter { !it.blocked }.map { it.host }
                userBlock = rules.filter { it.blocked }.map { it.host }
                rebuild()
            }
        }
    }

    fun reloadDownloaded() {
        val set = HashedDomainSet.readFrom(BlocklistUpdater.file(context)) ?: HashedDomainSet.empty()
        downloaded.set(set)
        rebuild()
    }

    /** Call after [Settings.rules] or [Settings.allowOnly] changed. */
    fun reloadRules() = rebuild()

    /** Number of domains in the downloaded blocklist currently in use. */
    fun downloadedCount(): Int = downloaded.get().size

    /** Base allow list size (0 when the asset is missing). */
    fun baseAllowlistCount(): Int = baseAllowlist.size

    @Synchronized
    private fun rebuild() {
        val allowOnly = if (settings.allowOnly()) baseAllowlist + sourceHosts() + settings.rules.granted else null
        current.set(DomainFilter(userAllow, userBlock, downloaded.get(), allowOnly, settings.rules.unallowed))
    }

    private fun readBaseAllowlist(): Set<String> = runCatching {
        context.assets.open(ALLOWLIST_ASSET).bufferedReader().useLines { lines ->
            lines.mapNotNull(HashedDomainSet::parseListLine).map(DomainFilter::normalize).filter(DomainFilter::isValidHost).toSet()
        }
    }.onFailure { Log.e(TAG, "allowlist asset unreadable: allow-only mode will deny everything but the list sources", it) }
        .getOrDefault(emptySet())

    companion object {
        private const val TAG = "FilterStore"
        const val ALLOWLIST_ASSET = "allowlist.txt"

        /** Hosts the updaters talk to; always resolvable in allow-only mode. */
        fun sourceHosts(): Set<String> =
            (BlocklistUpdater.SOURCES + BlocklistUpdater.OPTIONAL_SOURCES + ConfigUpdater.CONFIG_URL)
                .mapNotNull { runCatching { DomainFilter.normalize(URL(it).host) }.getOrNull() }.toSet()
    }
}
