package com.focusguard.core

import android.content.Context
import com.focusguard.data.AppDatabase
import com.focusguard.data.Settings
import com.focusguard.net.FilterStore
import com.focusguard.policy.PolicyController

/**
 * Hand-rolled service locator. Small app, no DI framework needed; everything is
 * constructed once from the application context.
 */
object Graph {
    lateinit var db: AppDatabase private set
    lateinit var settings: Settings private set
    lateinit var policy: PolicyController private set
    lateinit var filterStore: FilterStore private set
    lateinit var stats: Stats private set
    lateinit var directives: Directives private set
    lateinit var coordinator: Coordinator private set

    fun init(context: Context) {
        val app = context.applicationContext
        db = AppDatabase.build(app)
        settings = Settings(app)
        policy = PolicyController(app)
        filterStore = FilterStore(app, db.domainRules(), settings).also { it.start() }
        stats = Stats()
        directives = Directives(RulesStore(settings, db, policy, filterStore))
        coordinator = Coordinator(app, db, settings, policy, directives)
    }
}
