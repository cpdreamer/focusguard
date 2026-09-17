package com.focusguard.net

/**
 * Decides what to do with a DNS name. Pure, thread-safe after construction; rebuilt
 * (not mutated) when rules change.
 *
 * Evaluation order:
 *  1. built-in allow vs block (user + built-in), matched on the host or any parent domain: the most
 *     specific match wins, block wins ties. So "docs.google.com" (allow) would beat a block of
 *     "google.com", but a user block of "docs.google.com" beats the built-in allow. User allows never
 *     take part here: they cannot open anything the built-in block list closes.
 *  2. safe-search rewrite hosts                     -> REWRITE(target) (never defeated by an allow)
 *  3. built-in or user allow                        -> ALLOW
 *  4. downloaded blocklist (host or parent)         -> BLOCK
 *  5. keyword heuristics on the host                -> BLOCK
 *  6. otherwise                                     -> ALLOW (upstream resolver applies category filtering)
 */
class DomainFilter(
    userAllow: Collection<String> = emptyList(),
    userBlock: Collection<String> = emptyList(),
    private val downloaded: Set<String> = emptySet(),
) {
    sealed interface Decision {
        data object Allow : Decision
        data class Block(val reason: String) : Decision
        /** Answer with the address of [target] instead (e.g. forcesafesearch.google.com). */
        data class Rewrite(val target: String) : Decision
    }

    private val userAllow: Set<String> = userAllow.map(::normalize).toSet()
    private val block: Set<String> = userBlock.map(::normalize).toSet() + BuiltinLists.block

    fun decide(rawName: String): Decision {
        val name = normalize(rawName)
        if (name.isEmpty()) return Decision.Allow
        val builtinAllowHit = matchesWhich(name, BuiltinLists.allow)
        val blockHit = matchesWhich(name, block)
        if (blockHit != null && (builtinAllowHit == null || blockHit.length >= builtinAllowHit.length)) {
            return Decision.Block("$REASON_LIST$blockHit")
        }
        BuiltinLists.safeSearchRewrite[name]?.let { return Decision.Rewrite(it) }
        if (builtinAllowHit != null || matchesWhich(name, userAllow) != null) return Decision.Allow
        matchesWhich(name, downloaded)?.let { return Decision.Block("$REASON_DOWNLOAD$it") }
        keywordHit(name)?.let { return Decision.Block("$REASON_KEYWORD$it") }
        return Decision.Allow
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
        for (k in BuiltinLists.strongKeywords) if (name.contains(k)) return k
        val tokens = name.split('.', '-', '_').flatMap { it.split(DIGITS) }.filter { it.isNotEmpty() }
        for (t in tokens) if (t in BuiltinLists.tokenKeywords) return t
        return null
    }

    companion object {
        const val REASON_LIST = "list:"
        const val REASON_DOWNLOAD = "download:"
        const val REASON_KEYWORD = "keyword:"

        private val DIGITS = Regex("[0-9]+")
        private val HOST = Regex("^(?=.{1,253}$)([a-z0-9_](?:[a-z0-9_-]{0,61}[a-z0-9_])?\\.)+[a-z][a-z0-9-]{0,62}$")

        fun normalize(host: String): String = host.trim().trimEnd('.').lowercase()

        /** A registrable-looking host name: at least two labels, valid characters, alphabetic TLD. */
        fun isValidHost(host: String): Boolean = HOST.matches(host)
    }
}
