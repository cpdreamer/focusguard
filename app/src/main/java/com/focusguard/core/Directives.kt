package com.focusguard.core

import com.focusguard.net.DomainFilter
import java.time.Instant

/**
 * Per-commitment web rules that sit next to the block list (which lives in the `domain_rules`
 * table and only ever grows while committed).
 *
 * - [granted]: names added to the allow list through the window
 * - [unallowed]: names removed from the allow list (any hour); a later `allow` goes through the window
 * - [pending]: `allow` requests waiting for their window start (epoch millis)
 * - [grantedApps]: packages un-suspended through the window (`allowapp`)
 * - [pendingApps]: `allowapp` requests waiting for their window start (epoch millis)
 */
data class RuleBook(
    val granted: Set<String> = emptySet(),
    val unallowed: Set<String> = emptySet(),
    val pending: Map<String, Long> = emptyMap(),
    val grantedApps: Set<String> = emptySet(),
    val pendingApps: Map<String, Long> = emptyMap(),
)

/**
 * One directive per line, the same grammar as the Windows kit:
 *
 *     block <host>        block rule, now, for good (beats every allow; nothing removes it)
 *     unallow <host>      out of the allow list, now
 *     blockapp <package>  app blocked, now, for good
 *     cancel <host>       drop a waiting allow request
 *     allow <host>        lands at the first window start >= 8 h from now, never if a block rule matches
 *     allowapp <package>  same window as `allow`: the app is usable from the window start on; never
 *                         for an app that is blocked for good (always-block list, blocked category,
 *                         browser, `blockapp`) - only for apps installed after the commitment or
 *                         focus-only ones
 *     cancel <package>    drop a waiting allowapp request
 *
 * `unblock`, `unblockapp`, `mode`, `window`, `release`, `unlock` do not exist: those lists move one
 * way until the commitment ends. `browsers on/off` is not a phone directive either (a browser is
 * an app: `blockapp com.android.chrome`, permanent like every app block).
 */
class Directives(private val store: Store) {

    interface Store {
        var rules: RuleBook
        val window: Window

        /** Would a block rule (built-in, downloaded, keyword, user) hit this name even if it were allowed? */
        fun blockedRegardless(host: String): Boolean
        suspend fun blockHost(host: String)

        /** False if no such package is installed. */
        suspend fun blockApp(packageName: String): Boolean

        /** What `allowapp` may do with this package. */
        suspend fun appState(packageName: String): AppState

        /** Google account ID the reset protection is locked to, null if none yet. */
        val frpAccount: String?

        /** Points Factory Reset Protection at this account ID; false if the policy could not be applied. */
        suspend fun setFrpAccount(accountId: String): Boolean
    }

    enum class AppState { NOT_INSTALLED, BLOCKED_FOR_GOOD, REQUESTABLE }

    data class Result(val ok: Boolean, val message: String) {
        override fun toString() = (if (ok) "ok: " else "refused: ") + message
    }

    suspend fun apply(line: String, now: Instant): Result {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return Result(true, "ignored")
        val sp = trimmed.indexOf(' ')
        val verb = (if (sp < 0) trimmed else trimmed.substring(0, sp)).lowercase()
        val arg = if (sp < 0) "" else trimmed.substring(sp + 1).trim()
        return when (verb) {
            "block" -> host(arg) { h ->
                store.blockHost(h)
                store.rules = store.rules.copy(pending = store.rules.pending.filterKeys { !under(it, h) })
                Result(true, "$h blocked for good, effective now")
            }
            "unallow" -> host(arg) { h ->
                val r = store.rules
                store.rules = r.copy(
                    unallowed = r.unallowed + h,
                    granted = r.granted.filter { !under(it, h) }.toSet(),
                    pending = r.pending.filterKeys { !under(it, h) },
                )
                Result(true, "$h removed from the allow list now; asking again goes through the window")
            }
            "cancel" -> {
                val r = store.rules
                if (arg in r.pendingApps) { store.rules = r.copy(pendingApps = r.pendingApps - arg); Result(true, "$arg no longer waiting") }
                else host(arg) { h ->
                    if (h !in r.pending) Result(false, "$h is not waiting")
                    else { store.rules = r.copy(pending = r.pending - h); Result(true, "$h no longer waiting") }
                }
            }
            "allow" -> host(arg) { h ->
                val r = store.rules
                when {
                    store.blockedRegardless(h) -> Result(false, "$h matches a block rule; that is final")
                    h in r.pending -> Result(false, "$h already waiting (lands ${Instant.ofEpochMilli(r.pending.getValue(h))})")
                    r.pending.size >= MAX_PENDING -> Result(false, "already $MAX_PENDING names waiting")
                    else -> {
                        val lands = store.window.lands(now)
                        store.rules = r.copy(pending = r.pending + (h to lands.toEpochMilli()))
                        Result(true, "$h lands at $lands (window ${store.window})")
                    }
                }
            }
            "blockapp" -> {
                if (!PACKAGE.matches(arg)) Result(false, "blockapp needs a package name, e.g. com.android.chrome")
                else if (store.blockApp(arg)) {
                    val r = store.rules
                    store.rules = r.copy(grantedApps = r.grantedApps - arg, pendingApps = r.pendingApps - arg)
                    Result(true, "$arg blocked for good, effective now")
                } else Result(false, "$arg is not installed")
            }
            "allowapp" -> {
                val r = store.rules
                if (!PACKAGE.matches(arg)) Result(false, "allowapp needs a package name, e.g. com.fabernovel.ratp")
                else when (store.appState(arg)) {
                    AppState.NOT_INSTALLED -> Result(false, "$arg is not installed")
                    AppState.BLOCKED_FOR_GOOD -> Result(false, "$arg is blocked for good; that is final")
                    AppState.REQUESTABLE -> when {
                        arg in r.grantedApps -> Result(false, "$arg is already allowed")
                        arg in r.pendingApps -> Result(false, "$arg already waiting (lands ${Instant.ofEpochMilli(r.pendingApps.getValue(arg))})")
                        r.pendingApps.size >= MAX_PENDING -> Result(false, "already $MAX_PENDING apps waiting")
                        else -> {
                            val lands = store.window.lands(now)
                            store.rules = r.copy(pendingApps = r.pendingApps + (arg to lands.toEpochMilli()))
                            Result(true, "$arg usable from $lands (window ${store.window})")
                        }
                    }
                }
            }
            "frp" -> {
                val current = store.frpAccount
                when {
                    !FRP_ACCOUNT.matches(arg) -> Result(false, "frp needs the 21-digit Google account ID (People API people/me), not the e-mail")
                    current != null && current != arg -> Result(false, "reset protection is locked to …${current.takeLast(4)}; changing it waits for the commitment end")
                    store.setFrpAccount(arg) -> Result(true, "reset protection locked to account …${arg.takeLast(4)}, effective now")
                    else -> Result(false, "reset protection not applied (not device owner, or unsupported on this phone)")
                }
            }
            "browsers" -> Result(false, "no browsers directive on the phone: blockapp <package> (permanent)")
            "unblock", "unblockapp", "mode", "window", "release", "unlock", "extend", "unfrp" ->
                Result(false, "$verb does not exist until the commitment ends")
            else -> Result(false, "unknown directive '$verb'")
        }
    }

    /** Grants every pending request (hosts and apps) whose window start has come. Returns what landed or was dropped. */
    suspend fun promote(now: Instant): List<String> {
        val r = store.rules
        val due = r.pending.filterValues { it <= now.toEpochMilli() }.keys
        val dueApps = r.pendingApps.filterValues { it <= now.toEpochMilli() }.keys
        if (due.isEmpty() && dueApps.isEmpty()) return emptyList()
        val landed = due.filter { !store.blockedRegardless(it) }.toSet()
        val landedApps = dueApps.filter { store.appState(it) == AppState.REQUESTABLE }.toSet()
        store.rules = r.copy(
            granted = r.granted + landed,
            unallowed = r.unallowed - landed,
            pending = r.pending - due,
            grantedApps = r.grantedApps + landedApps,
            pendingApps = r.pendingApps - dueApps,
        )
        return due.map { if (it in landed) "$it allowed" else "$it dropped (block rule)" } +
            dueApps.map { if (it in landedApps) "$it usable" else "$it dropped (blocked for good or gone)" }
    }

    private inline fun host(arg: String, body: (String) -> Result): Result {
        val h = DomainFilter.normalize(arg)
        return if (!DomainFilter.isValidHost(h)) Result(false, "'$arg' is not a host name") else body(h)
    }

    private fun under(name: String, parent: String) = name == parent || name.endsWith(".$parent")

    companion object {
        const val MAX_PENDING = 8
        private val FRP_ACCOUNT = Regex("""^[0-9]{21}$""")
        private val PACKAGE = Regex("""^[a-zA-Z_][a-zA-Z0-9_]*(\.[a-zA-Z_][a-zA-Z0-9_]*)+$""")
    }
}
