package com.focusguard.net

import com.focusguard.net.DomainFilter.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainFilterTest {

    private val filter = DomainFilter()

    private fun isBlocked(host: String) = filter.decide(host) is Decision.Block
    private fun isAllowed(host: String) = filter.decide(host) is Decision.Allow

    @Test
    fun `ordinary study sites are allowed`() {
        for (h in listOf("wikipedia.org", "fr.wikipedia.org", "codeforces.com", "doc-solus.fr", "docs.google.com",
            "www.google.fr", "stackoverflow.com", "open.spotify.com", "cdn.discordapp.com", "whatsapp.net", "play.google.com")) {
            assertTrue("expected allow for $h: ${filter.decide(h)}", isAllowed(h) || filter.decide(h) is Decision.Rewrite)
        }
    }

    @Test
    fun `video and social hosts and all their subdomains are blocked`() {
        for (h in listOf("youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be", "i.ytimg.com", "r3---sn-abc.googlevideo.com",
            "tiktok.com", "www.tiktok.com", "v16-webapp.tiktok.com", "instagram.com", "twitch.tv", "reddit.com", "twitter.com", "x.com", "netflix.com")) {
            assertTrue("expected block for $h: ${filter.decide(h)}", isBlocked(h))
        }
    }

    @Test
    fun `youtube media and api hosts used by embedded players and Discord activities are blocked`() {
        for (h in listOf("music.youtube.com", "rr1---sn-25glenee.googlevideo.com", "i.ytimg.com", "yt3.ggpht.com",
            "youtubei.googleapis.com", "www.youtube-nocookie.com")) {
            assertTrue("expected block for $h", isBlocked(h))
        }
        // but the rest of googleapis / ggpht stays usable
        assertTrue(isAllowed("translate.googleapis.com"))
        assertTrue(isAllowed("lh3.googleusercontent.com"))
        assertTrue(isAllowed("play-lh.googleusercontent.com"))
    }

    @Test
    fun `blocklist source hosts are reachable despite the nsfw keyword`() {
        for (src in BlocklistUpdater.SOURCES) {
            val host = java.net.URL(src).host
            assertTrue("expected allow for $host", isAllowed(host))
        }
    }

    @Test
    fun `google images and lens are blocked but web search is rewritten to safe search`() {
        assertTrue(isBlocked("images.google.com"))
        assertTrue(isBlocked("lens.google.com"))
        assertTrue(isBlocked("encrypted-tbn0.gstatic.com"))
        assertTrue(isBlocked("th.bing.com"))
        assertTrue(isBlocked("external-content.duckduckgo.com"))
        assertTrue(isAllowed("www.gstatic.com"))
        assertTrue(isAllowed("fonts.gstatic.com"))
        assertEquals(Decision.Rewrite("forcesafesearch.google.com"), filter.decide("www.google.com"))
        assertEquals(Decision.Rewrite("forcesafesearch.google.com"), filter.decide("google.fr"))
        assertTrue(isAllowed("forcesafesearch.google.com"))
    }

    @Test
    fun `other search engines are forced to safe variants`() {
        assertEquals(Decision.Rewrite("strict.bing.com"), filter.decide("www.bing.com"))
        assertEquals(Decision.Rewrite("safe.duckduckgo.com"), filter.decide("duckduckgo.com"))
        assertTrue(isBlocked("yandex.com"))
        assertTrue(isBlocked("images.yandex.ru"))
    }

    @Test
    fun `adult keywords in host names are blocked even for unknown domains`() {
        for (h in listOf("freeporn-site.example", "xxxmovies.net", "best-hentai.to", "sexy-cams.live", "nude4u.xyz", "adult-videos.co")) {
            assertTrue("expected block for $h", isBlocked(h))
        }
    }

    @Test
    fun `pirate streaming, gambling, game portals and meme feeds are blocked including unknown mirrors`() {
        for (h in listOf(
            // seeds
            "fmovies.to", "ww4.fmovies.to", "egybest.com", "123movies.to", "soap2day.to", "hianime.to", "anime-sama.fr",
            "streameast.io", "thepiratebay.org", "1337x.to", "bet365.com", "winamax.fr", "stake.com", "poki.com",
            "crazygames.com", "roblox.com", "4chan.org", "9gag.com", "buzzfeed.com", "jeuxvideo.com",
            // mirror names never seen before
            "fmovies-official.xyz", "egybest2.pw", "soap2dayhd.co", "new123movies.la", "gogoanime3.net", "streameast.live",
            "cpasbien-fr.icu", "1xbet-mobile.app",
            // token heuristics
            "free-movies.site", "watch-anime.tv", "manga-scan.fr", "poker-online.example", "online-casino.fr", "temu.com",
            "best-memes.io", "unblocked-games.dev",
        )) {
            assertTrue("expected block for $h: ${filter.decide(h)}", isBlocked(h))
        }
    }

    @Test
    fun `AI companion and uncensored generator sites are blocked, study assistants are not`() {
        for (h in listOf(
            "character.ai", "beta.character.ai", "c.ai", "janitorai.com", "crushon.ai", "spicychat.ai", "candy.ai",
            "replika.com", "civitai.com", "perchance.org", "nudify.online", "mrdeepfakes.com", "chub.ai", "venus.chub.ai",
            // unseen clones caught by keyword
            "janitorai-mirror.xyz", "my-ai-girlfriend.app", "undress-ai.pro", "uncensored-chat.example", "deepfake-studio.example",
        )) {
            assertTrue("expected block for $h: ${filter.decide(h)}", isBlocked(h))
        }
        // every AI assistant except Devin is blocked (decision 19 Nov 2026), and the block is final
        for (h in listOf("chatgpt.com", "chat.openai.com", "gemini.google.com", "claude.ai", "copilot.microsoft.com",
            "perplexity.ai", "chat.mistral.ai", "huggingface.co", "openai.com", "anthropic.com", "chat.deepseek.com")) {
            assertTrue("expected block for $h: ${filter.decide(h)}", isBlocked(h))
            assertTrue("block must be final for $h", filter.blockedRegardless(h))
        }
        for (h in listOf("kaggle.com", "devin.ai", "app.devin.ai", "cognition.ai", "docs.google.com", "google.com")) {
            assertFalse("false positive for $h: ${filter.decide(h)}", isBlocked(h))
        }
    }

    @Test
    fun `sexting and stranger-chat sites are blocked, innocent sext- and -horny words are not`() {
        for (h in listOf("justsext.com", "www.justsext.com", "sextfriend.com", "arousr.com", "dirtyroulette.com",
            "chatrandom.com", "emeraldchat.com", "omegle.tv", "sext-buddies.example", "omegle-clone.example",
            "free-sexchat.example", "hornymatches.example")) {
            assertTrue("expected block for $h: ${filter.decide(h)}", isBlocked(h))
        }
        for (h in listOf("sextant.example", "sexton-parish.org", "sextet-music.example", "thorny-devils.example")) {
            assertFalse("false positive for $h: ${filter.decide(h)}", isBlocked(h))
        }
    }

    @Test
    fun `stock-photo, wallpaper and Moroccan VOD sites are blocked including unknown mirrors`() {
        for (h in listOf("gettyimages.com", "www.gettyimages.fr", "gettyimages.co.jp", "media.gettyimages.com",
            "istockphoto.com", "shutterstock.com", "unsplash.com", "images.unsplash.com", "pexels.com", "pixabay.com",
            "flickr.com", "live.staticflickr.com", "wallhaven.cc", "wallpapercave.com", "4k-wallpaper-hub.example",
            "fr.pinterest.com", "forja.ma", "www.forja.ma", "forja-plus.example", "shahid.mbc.net", "weyyak.com",
            "sugoapp.com", "api.tigolive.com", "yalla.live", "ihago.net", "chamet.com", "litmatch.net", "imo.im",
            "cdn.sugoapp-mirror.example", "talktostrangers.example",
            "yacine-tv.example", "koora.live", "tf1.fr", "mycanal.fr")) {
            assertTrue("expected block for $h: ${filter.decide(h)}", isBlocked(h))
        }
        for (h in listOf("photos.google.com", "docs.google.com", "photomath.net", "openphoto.example", "shahidul-university.example")) {
            assertFalse("false positive for $h: ${filter.decide(h)}", isBlocked(h))
        }
    }

    @Test
    fun `brainrot heuristics spare university and reference hosts`() {
        for (h in listOf("stream.microsoft.com", "web.microsoftstream.com", "proxy.univ-lyon1.fr", "ezproxy.univ-paris1.fr",
            "themoviedb.org", "imdb.com", "idea.com", "area.com", "alphabet.com", "bettercallsaul.example",
            "animation.example", "radiology.hospital.example", "stackoverflow.com")) {
            assertFalse("false positive for $h: ${filter.decide(h)}", isBlocked(h))
        }
    }

    @Test
    fun `keyword heuristics do not hit ordinary words containing the token`() {
        for (h in listOf("essex.gov.uk", "sussex.ac.uk", "classroom.google.com", "assassinscreed.com", "bassoon.org", "cumulus.example", "dickens-society.org")) {
            assertFalse("false positive for $h: ${filter.decide(h)}", isBlocked(h))
        }
    }

    @Test
    fun `downloaded list blocks host and subdomains`() {
        val f = DomainFilter(downloaded = setOf("bad.example"))
        assertEquals(Decision.Block("${DomainFilter.REASON_DOWNLOAD}bad.example"), f.decide("bad.example"))
        assertTrue(f.decide("cdn.bad.example") is Decision.Block)
        assertTrue(f.decide("notbad.example") is Decision.Allow)
    }

    @Test
    fun `user block rules win over built-in allow`() {
        val f = DomainFilter(userBlock = listOf("cdn.discordapp.com"))
        assertTrue(f.decide("cdn.discordapp.com") is Decision.Block)
        assertTrue(f.decide("discord.com") is Decision.Allow)
    }

    @Test
    fun `user allow can carve a subdomain out of a downloaded block but not out of a built-in block`() {
        val f = DomainFilter(userAllow = listOf("ok.bad.example", "www.youtube.com"), downloaded = setOf("bad.example"))
        assertTrue(f.decide("ok.bad.example") is Decision.Allow)
        assertTrue(f.decide("other.bad.example") is Decision.Block)
        assertTrue(f.decide("www.youtube.com") is Decision.Block)
    }

    @Test
    fun `names are normalised`() {
        assertTrue(filter.decide("WWW.YouTube.COM.") is Decision.Block)
        assertTrue(filter.decide("") is Decision.Allow)
    }

    @Test
    fun `host validation`() {
        assertTrue(DomainFilter.isValidHost("example.com"))
        assertTrue(DomainFilter.isValidHost("a.b-c.example.co.uk"))
        assertFalse(DomainFilter.isValidHost("example"))
        assertFalse(DomainFilter.isValidHost("exa mple.com"))
        assertFalse(DomainFilter.isValidHost("http://example.com"))
        assertFalse(DomainFilter.isValidHost("192.168.1.1"))
    }
}
