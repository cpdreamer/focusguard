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
    fun `pirate streaming, torrent, gambling and bypass apps are blocked by name`() {
        for (p in listOf(
            "com.stremio.one", "org.xbmc.kodi", "com.delphicoder.flud", "com.winamax.android", "fr.betclic.android",
            "com.bet365Wrapper.Bet365_Application", "com.valvesoftware.android.steam.community",
            "com.acme.torrentclient", "tv.foo.iptvplayer", "com.bar.pokerstars", "io.baz.casino.royale",
            "com.qux.betting.live", "org.torproject.android", "com.psiphon3", "com.wireguard.android",
            "net.openvpn.openvpn", "com.github.shadowsocks", "com.foo.unblockweb",
        )) {
            assertEquals(p, BlockMode.ALWAYS, decide(p, system = true))
        }
        // ordinary utilities whose names brush against the heuristics stay allowed
        for (p in listOf("com.sec.android.app.popupcalculator", "com.samsung.android.calendar", "com.adobe.reader",
            "com.google.android.apps.docs.editors.docs", "com.kodiak.example", "fr.bnpparibas.mescomptes")) {
            assertEquals(p, BlockMode.ALLOW, decide(p, system = true))
        }
    }

    @Test
    fun `AI companion and uncensored generator apps are blocked, study assistants allowed`() {
        for (p in listOf(
            "ai.character.app", "ai.replika.app", "com.Beauchamp.Messenger.external",
            "com.foo.aigirlfriend", "io.bar.janitorai", "ai.crushon.app", "com.baz.undress.photo", "com.qux.deepfake.maker",
            "com.roleplay.chat", "com.acme.uncensored.chat", "ai.civitai.app",
        )) {
            assertEquals(p, BlockMode.ALWAYS, decide(p, system = false))
        }
        // AI assistants: not pre-allowed (installed during a commitment they are suspended like any new app),
        // not hard-blocked either (decision 19 Nov 2026)
        for (p in listOf("com.openai.chatgpt", "com.google.android.apps.bard", "com.anthropic.claude",
            "com.microsoft.copilot", "ai.perplexity.app.android", "ai.mistral.chat")) {
            assertEquals(p, BlockMode.ALWAYS, decide(p, afterCommitment = true))
            assertEquals(p, decide("com.example.newthing"), decide(p))
        }
        assertEquals(BlockMode.ALLOW, decide("com.zello.walkietalkie", system = true))
    }

    @Test
    fun `sexting and stranger video-chat apps are blocked, video-call apps allowed`() {
        for (p in listOf("com.arousr", "com.sextpanther", "com.chatrandom.app", "com.shagle", "com.foo.sextchat",
            "io.bar.randomchat", "com.baz.strangerchat.live", "com.qux.flirt.dating")) {
            assertEquals(p, BlockMode.ALWAYS, decide(p, system = true))
        }
        for (p in listOf("com.google.android.apps.tachyon", "us.zoom.videomeetings", "com.microsoft.teams", "com.skype.raider")) {
            assertEquals(p, BlockMode.ALLOW, decide(p, system = true))
        }
    }

    @Test
    fun `soft-adult prone apps are blocked even when preinstalled or unknown by package`() {
        for (p in listOf(
            "com.samsung.android.app.spage", "com.samsung.android.tvplus", "com.google.android.videos",
            "com.tumblr", "com.pinterest", "com.imgur.mobile", "com.ninegag.android.app",
            "org.telegram.messenger", "kik.android", "com.tinder", "co.hinge.app",
            "wp.wattpad", "com.naver.linewebtoon", "eu.kanade.tachiyomi", "com.crunchyroll.crunchyroid",
            "video.like", "com.kwai.video", "sg.bigo.live",
            "com.gettyimages.android", "com.shutterstock.android", "com.unsplash.app", "net.zedge.android",
            "ma.snrt.forja", "net.mbc.shahid", "fr.tf1.mytf1", "com.canal.android.canal",
            "com.example.forja.player", "tv.yacine.live", "app.koora.live",
        )) {
            assertEquals(p, BlockMode.ALWAYS, decide(p, system = true))
        }
        for (p in listOf("com.example.mangareader", "io.foo.hentaiviewer", "com.acme.datingapp", "org.bar.telegramx", "com.x.animeflix")) {
            assertEquals(p, BlockMode.ALWAYS, decide(p))
        }
        assertTrue(DefaultPolicy.alwaysAllow.none { it in DefaultPolicy.alwaysBlock })
        assertTrue(DefaultPolicy.alwaysAllow.none { DefaultPolicy.classifyByName(it) != null })
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
    fun `voice and video chat-room apps are blocked`() {
        for (p in listOf("com.sugo.chat", "com.tigo.chat", "com.yy.hiyo", "com.yalla.yallachat", "com.yalla.ludo",
            "com.chamet.android", "com.litatom.app", "co.yellw.yellowapp", "com.asiainno.uplive", "com.imo.android.imoim",
            "com.example.sugolite", "io.example.yalla.room", "com.example.livechat.rooms")) {
            assertEquals(p, BlockMode.ALWAYS, decide(p))
        }
        assertEquals(BlockMode.ALLOW, decide("com.whatsapp"))
        assertEquals(BlockMode.ALLOW, decide("com.discord"))
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
