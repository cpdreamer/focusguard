package com.focusguard.core

import com.focusguard.net.DomainFilter
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory counters for the status screen. Never persisted, never contains full
 * browsing history: only counts and the last few blocked hosts (for "is it working?").
 */
class Stats {
    val dnsQueries = AtomicLong()
    val dnsBlocked = AtomicLong()
    private val recentBlocked = ArrayDeque<String>()

    fun recordDns(name: String, decision: DomainFilter.Decision) {
        dnsQueries.incrementAndGet()
        if (decision is DomainFilter.Decision.Block) {
            dnsBlocked.incrementAndGet()
            synchronized(recentBlocked) {
                if (recentBlocked.lastOrNull() != name) {
                    recentBlocked.addLast(name)
                    while (recentBlocked.size > 10) recentBlocked.removeFirst()
                }
            }
        }
    }

    fun recentBlocked(): List<String> = synchronized(recentBlocked) { recentBlocked.toList() }
}
