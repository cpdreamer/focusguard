package com.focusguard.core

import com.focusguard.data.AppDatabase
import com.focusguard.data.AppRule
import com.focusguard.data.BlockMode
import com.focusguard.data.DomainRule
import com.focusguard.data.Settings
import com.focusguard.net.FilterStore
import com.focusguard.policy.PolicyController

/** [Directives.Store] backed by the real settings, database, filter and device policy. */
class RulesStore(
    private val settings: Settings,
    private val db: AppDatabase,
    private val policy: PolicyController,
    private val filterStore: FilterStore,
) : Directives.Store {

    override var rules: RuleBook
        get() = settings.rules
        set(v) {
            settings.rules = v
            filterStore.reloadRules()
        }

    override val window: Window get() = settings.window()

    override fun blockedRegardless(host: String): Boolean = filterStore.filter.blockedRegardless(host)

    override suspend fun blockHost(host: String) {
        db.domainRules().upsert(DomainRule(host, blocked = true))
    }

    override suspend fun blockApp(packageName: String): Boolean {
        if (!policy.isInstalled(packageName)) return false
        db.appRules().upsert(AppRule(packageName, BlockMode.ALWAYS))
        return true
    }
}
