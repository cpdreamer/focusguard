package com.focusguard.net

import android.content.Context
import com.focusguard.data.DomainRuleDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns the live [DomainFilter]. Rebuilds it when user rules change or when the
 * downloaded list is refreshed. Reads are lock-free ([AtomicReference]).
 */
class FilterStore(private val context: Context, private val domainRules: DomainRuleDao) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloaded = AtomicReference<HashedDomainSet>(HashedDomainSet.empty())
    private val current = AtomicReference(DomainFilter())
    private var userAllow: List<String> = emptyList()
    private var userBlock: List<String> = emptyList()

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

    /** Number of domains in the downloaded blocklist currently in use. */
    fun downloadedCount(): Int = downloaded.get().size

    @Synchronized
    private fun rebuild() {
        current.set(DomainFilter(userAllow, userBlock, downloaded.get()))
    }
}
