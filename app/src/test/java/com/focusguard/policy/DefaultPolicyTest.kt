package com.focusguard.policy

import android.content.pm.ApplicationInfo
import com.focusguard.data.BlockMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultPolicyTest {

    private fun decide(pkg: String, system: Boolean = false, category: Int = -1, afterCommitment: Boolean = false, user: BlockMode? = null) =
        DefaultPolicy.decide(pkg, user, system, category, afterCommitment)

    @Test
    fun `communication and chess apps are always allowed`() {
        for (p in listOf("com.whatsapp", "com.discord", "com.Slack", "com.chess", "org.lichess.mobileapp")) {
            assertEquals(p, BlockMode.ALLOW, decide(p, category = ApplicationInfo.CATEGORY_GAME))
        }
    }

    @Test
    fun `known distractions are always blocked even if the user could install them later`() {
        for (p in listOf(
            "com.google.android.youtube", "com.zhiliaoapp.musically", "com.instagram.android", "com.snapchat.android",
            "com.reddit.frontpage", "com.twitter.android", "tv.twitch.android.app", "com.netflix.mediaclient",
            "com.google.android.googlequicksearchbox",
        )) {
            assertEquals(p, BlockMode.ALWAYS, decide(p, system = true))
        }
    }

    @Test
    fun `other browsers, vpn tools and games are blocked by name`() {
        for (p in listOf(
            "com.sec.android.app.sbrowser", "org.mozilla.firefox", "com.brave.browser", "com.opera.mini.native",
            "com.duckduckgo.mobile.android", "com.kiwibrowser.browser", "com.nordvpn.android", "org.torproject.torbrowser",
            "com.supercell.clashofclans", "com.mojang.minecraftpe",
        )) {
            assertEquals(p, BlockMode.ALWAYS, decide(p))
        }
        assertEquals(BlockMode.ALWAYS, DefaultPolicy.classifyByName("com.example.somebrowser"))
        assertNull(DefaultPolicy.classifyByName("com.example.notes"))
    }

    @Test
    fun `Chrome is the one allowed browser`() {
        assertEquals(BlockMode.ALLOW, decide("com.android.chrome", system = true))
    }

    @Test
    fun `developer-declared game, video and social categories are blocked`() {
        assertEquals(BlockMode.ALWAYS, decide("com.example.puzzle", category = ApplicationInfo.CATEGORY_GAME))
        assertEquals(BlockMode.ALWAYS, decide("com.example.player", category = ApplicationInfo.CATEGORY_VIDEO))
        assertEquals(BlockMode.ALWAYS, decide("com.example.feed", category = ApplicationInfo.CATEGORY_SOCIAL))
        assertEquals(BlockMode.FOCUS_ONLY, decide("com.example.tool", category = ApplicationInfo.CATEGORY_PRODUCTIVITY))
    }

    @Test
    fun `unknown third-party apps are blocked during focus and sleep, system apps are allowed`() {
        assertEquals(BlockMode.FOCUS_ONLY, decide("com.example.bank"))
        assertEquals(BlockMode.ALLOW, decide("com.samsung.android.app.notes", system = true))
    }

    @Test
    fun `apps installed after the commitment started are blocked`() {
        assertEquals(BlockMode.ALWAYS, decide("com.example.newthing", afterCommitment = true))
        assertEquals(BlockMode.ALLOW, decide("com.android.chrome", system = true, afterCommitment = true))
        assertEquals(BlockMode.ALLOW, decide("com.whatsapp", afterCommitment = true))
    }

    @Test
    fun `an explicit user rule wins`() {
        assertEquals(BlockMode.ALWAYS, decide("com.whatsapp", user = BlockMode.ALWAYS))
        assertEquals(BlockMode.ALLOW, decide("com.google.android.youtube", user = BlockMode.ALLOW))
    }

    @Test
    fun `phone-critical packages are never suspended`() {
        for (p in listOf("com.android.settings", "com.android.systemui", "com.samsung.android.dialer", "com.google.android.gms", "com.focusguard")) {
            assertTrue(p, p in DefaultPolicy.neverSuspend)
        }
        assertTrue(DefaultPolicy.neverSuspend.none { it in DefaultPolicy.alwaysBlock })
    }
}
