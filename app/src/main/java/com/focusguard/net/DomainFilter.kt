package com.focusguard.net

/**
 * Decides what to do with a DNS name. Pure, thread-safe after construction; rebuilt
 * (not mutated) when rules change.
 *
 * Evaluation order:
 *  0. user block (host or parent)                   -> BLOCK (beats everything, including built-in allows)
 *  1. built-in allow vs built-in block, matched on the host or any parent domain: the most
 *     specific match wins, block wins ties. So "docs.google.com" (allow) would beat a block of
 *     "google.com". User allows never take part here: they cannot open anything a block list closes.
 *  2. allow-only mode: `unallowed` (host or parent) -> BLOCK until re-allowed through the window
 *  3. safe-search rewrite hosts                     -> REWRITE(target) (never defeated by an allow)
 *  4. built-in allow (allow-only: only if also on the allow list) -> ALLOW
 *  5. downloaded blocklist (host or parent)         -> BLOCK
 *  6. keyword heuristics on the host                -> BLOCK
 *  7. allow-only mode: not on the allow list        -> BLOCK (default-deny)
 *  8. otherwise                                     -> ALLOW
 *
 * Allow-only mode is on when [allowOnly] is non-null: the set is the allow list (parent domains,
 * subdomains implied). Blocklist mode (null) is the historical behaviour; user allows then act as
 * exceptions to the downloaded list and the keywords only.
 */
class DomainFilter(
    userAllow: Collection<String> = emptyList(),
    userBlock: Collection<String> = emptyList(),
    private val downloaded: Set<String> = emptySet(),
    allowOnly: Collection<String>? = null,
    unallowed: Collection<String> = emptyList(),
) {
    sealed interface Decision {
        data object Allow : Decision
        data class Block(val reason: String) : Decision
        /** Answer with the address of [target] instead (e.g. forcesafesearch.google.com). */
        data class Rewrite(val target: String) : Decision
    }

    private val userAllow: Set<String> = userAllow.map(::normalize).toSet()
    private val userBlock: Set<String> = userBlock.map(::normalize).toSet()
    private val allowOnly: Set<String>? = allowOnly?.map(::normalize)?.toSet()
    private val unallowed: Set<String> = unallowed.map(::normalize).toSet()

    val isAllowOnly: Boolean get() = allowOnly != null
    val allowOnlySize: Int get() = allowOnly?.size ?: 0

    fun decide(rawName: String): Decision = decide(rawName, ignoreUnallowed = false)

    private fun decide(rawName: String, ignoreUnallowed: Boolean): Decision {
        val name = normalize(rawName)
        if (name.isEmpty()) return Decision.Allow
        matchesWhich(name, userBlock)?.let { return Decision.Block("$REASON_USER$it") }
        val builtinAllowHit = matchesWhich(name, BuiltinLists.allow)
        val blockHit = matchesWhich(name, BuiltinLists.block)
        if (blockHit != null && (builtinAllowHit == null || blockHit.length >= builtinAllowHit.length)) {
            return Decision.Block("$REASON_LIST$blockHit")
        }
        if (allowOnly != null && !ignoreUnallowed) matchesWhich(name, unallowed)?.let { return Decision.Block("$REASON_UNALLOWED$it") }
        BuiltinLists.safeSearchRewrite[name]?.let { return Decision.Rewrite(it) }
        if (allowOnly == null) {
            if (builtinAllowHit != null || matchesWhich(name, userAllow) != null) return Decision.Allow
        } else if (builtinAllowHit != null && matchesWhich(name, allowOnly) != null) {
            return Decision.Allow
        }
        matchesWhich(name, downloaded)?.let { return Decision.Block("$REASON_DOWNLOAD$it") }
        keywordHit(name)?.let { return Decision.Block("$REASON_KEYWORD$it") }
        if (allowOnly != null && matchesWhich(name, allowOnly) == null) return Decision.Block(REASON_DEFAULT_DENY)
        return Decision.Allow
    }

    /**
     * Would this name be blocked even if it were on the allow list? Allow requests for such names
     * are refused: a granted name never overrides a block rule.
     */
    fun blockedRegardless(rawName: String): Boolean {
        val d = decide(rawName, ignoreUnallowed = true)
        return d is Decision.Block && d.reason != REASON_DEFAULT_DENY
    }

    fun blockedByDownloaded(): Int = downloaded.size

    private fun matchesWhich(name: String, set: Set<String>): String? {
        if (set.isEmpty()) return null
        var n = name
        while (true) {
            if (n in set) return n
            val dot = n.indexOf('.')
            if (dot < 0) return null
            n = n.substring(dot + 1)
        }
    }

    private fun keywordHit(name: String): String? {
        for (k in BuiltinLists.strongKeywords) {
            if (!name.contains(k)) continue
            if (BuiltinLists.strongKeywordExceptions.any { it.contains(k) && name.contains(it) }) continue
            return k
        }
        val tokens = name.split('.', '-', '_').flatMap { it.split(DIGITS) }.filter { it.isNotEmpty() }
        for (t in tokens) if (t in BuiltinLists.tokenKeywords) return t
        return null
    }

    companion object {
        const val REASON_USER = "user:"
        const val REASON_LIST = "list:"
        const val REASON_UNALLOWED = "unallowed:"
        const val REASON_DOWNLOAD = "download:"
        const val REASON_KEYWORD = "keyword:"
        const val REASON_DEFAULT_DENY = "default-deny"

        private val DIGITS = Regex("[0-9]+")
        private val HOST = Regex("^(?=.{1,253}$)([a-z0-9_](?:[a-z0-9_-]{0,61}[a-z0-9_])?\\.)+[a-z][a-z0-9-]{0,62}$")

        fun normalize(host: String): String = host.trim().trimEnd('.').lowercase()

        /** A registrable-looking host name: at least two labels, valid characters, alphabetic TLD. */
        fun isValidHost(host: String): Boolean = HOST.matches(host)
    }
}
