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
    fun `google images and lens are blocked but web search is rewritten to safe search`() {
        assertTrue(isBlocked("images.google.com"))
        assertTrue(isBlocked("lens.google.com"))
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
