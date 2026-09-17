package com.focusguard.net

/**
 * Built-in domain rules. These are the *seed*; the bulk adult/soft list is downloaded
 * and refreshed daily by [BlocklistUpdater]. Names are matched as suffixes.
 */
object BuiltinLists {

    /**
     * Explicit allow. Keep small: an allow here is a hole. No YouTube Music: it streams from
     * googlevideo.com, the same servers as YouTube, so it cannot be allowed without opening YouTube.
     */
    val allow: Set<String> = setOf(
        "docs.google.com", "drive.google.com", "classroom.google.com", "mail.google.com",
        "calendar.google.com", "accounts.google.com", "play.google.com", "translate.googleapis.com",
        "forcesafesearch.google.com", "strict.bing.com", "safe.duckduckgo.com",
        "discord.com", "discordapp.com", "discord.gg", "discordapp.net", "discord.media", "discordsays.com",
        "whatsapp.com", "whatsapp.net",
        "slack.com", "slack-edge.com", "slack-msgs.com", "slack-files.com", "slack-imgs.com",
        "chess.com", "chesscomfiles.com", "lichess.org", "lichess1.org",
        "spotify.com", "scdn.co", "spotifycdn.com",
        "doc-solus.fr", "codeforces.com", "wikipedia.org", "wikimedia.org",
        // blocklist sources (the "nsfw" host would otherwise trip the keyword heuristic)
        "oisd.nl", "raw.githubusercontent.com",
    )

    /** Always blocked. */
    val block: Set<String> = setOf(
        // Google image / lens surfaces (web search itself stays, SafeSearch-forced)
        "images.google.com", "lens.google.com", "yandex.com", "yandex.ru", "yandex.fr",
        // video / short-form
        "youtube.com", "youtu.be", "youtube-nocookie.com", "youtubekids.com",
        // YouTube media/API hosts: embedded players and Discord "Watch Together" stream from these
        "googlevideo.com", "ytimg.com", "yt3.ggpht.com", "yt4.ggpht.com", "youtubei.googleapis.com",
        "youtube.googleapis.com", "youtubeembeddedplayer.googleapis.com", "ytimg.l.google.com",
        "tiktok.com", "tiktokv.com", "tiktokcdn.com", "musical.ly", "byteoversea.com",
        "twitch.tv", "ttvnw.net", "jtvnw.net", "kick.com",
        "netflix.com", "nflxvideo.net", "nflximg.net", "primevideo.com", "disneyplus.com", "hulu.com",
        "dailymotion.com", "vimeo.com", "bilibili.com", "rumble.com", "odysee.com",
        "9gag.com", "ifunny.co", "imgur.com", "giphy.com", "tenor.com",
        // social
        "instagram.com", "cdninstagram.com", "threads.net",
        "snapchat.com", "sc-cdn.net",
        "reddit.com", "redd.it", "redditmedia.com", "redditstatic.com",
        "twitter.com", "x.com", "twimg.com", "t.co",
        "facebook.com", "fbcdn.net", "fb.com", "fb.watch",
        "pinterest.com", "pinimg.com", "tumblr.com", "deviantart.com", "vk.com",
        "bigo.tv", "likee.video", "omegle.com", "ome.tv", "chatroulette.com", "monkey.app",
        "tango.me", "kwai.com", "kuaishou.com", "quora.com",
        "telegram.org", "telegram.me", "t.me", "telesco.pe", "tdesktop.com",
        // fan-fiction / comics (soft-adult heavy)
        "wattpad.com", "wattpad.io", "archiveofourown.org", "fanfiction.net", "webtoons.com", "webtoon.xyz",
        "mangadex.org", "mangadex.network", "tapas.io", "crunchyroll.com", "mangago.me", "mangakakalot.com",
        "manganato.com", "manganelo.com", "mangapark.net", "mangasee123.com", "bato.to", "asurascans.com",
        // dating / hookup
        "tinder.com", "bumble.com", "badoo.com", "grindr.com", "hinge.co", "okcupid.com", "match.com",
        "meetic.fr", "adopteunmec.com", "happn.com", "fruitz.io", "lovoo.com", "seeking.com", "ashleymadison.com",
        // alternative YouTube frontends
        "invidious.io", "yewtu.be", "inv.tux.pizza", "piped.video", "piped.kavin.rocks", "vid.puffyan.us",
        "invidious.snopyta.org", "inv.riverside.rocks", "y.com.sb", "invidious.privacydev.net",
        // proxies / unblockers
        "croxyproxy.com", "proxysite.com", "kproxy.com", "hidester.com", "hide.me", "4everproxy.com",
        "blockaway.net", "plainproxies.com", "proxyium.com", "sitenable.com", "filterbypass.me",
        "unblockit.pro", "unblocked.to", "proxfree.com", "zend2.com", "whoer.net", "vpnbook.com",
        "1.1.1.1", "one.one.one.one",
        // public DoH endpoints (used to bypass system DNS)
        "dns.google", "cloudflare-dns.com", "mozilla.cloudflare-dns.com", "dns.quad9.net", "doh.opendns.com",
        "dns.adguard.com", "dns.adguard-dns.com", "doh.cleanbrowsing.org", "dns.nextdns.io", "doh.dns.sb",
        "dns.alidns.com", "doh.pub", "dns.mullvad.net", "adblock.doh.mullvad.net", "doh.libredns.gr",
        // gambling
        "bet365.com", "unibet.fr", "winamax.fr", "betclic.fr", "pmu.fr", "pokerstars.com", "stake.com",
        "roobet.com", "1xbet.com", "parionssport.fdj.fr",
        // adult: top hosts, seed only (the downloaded list carries the long tail)
        "pornhub.com", "phncdn.com", "xvideos.com", "xvideos-cdn.com", "xnxx.com", "xnxx-cdn.com",
        "xhamster.com", "xhcdn.com", "xhamster.desi", "xhamster2.com", "xhamster3.com", "xhamsterlive.com",
        "redtube.com", "youporn.com", "tube8.com", "spankbang.com", "sb-cd.com", "eporner.com", "porntrex.com",
        "brazzers.com", "onlyfans.com", "fansly.com", "chaturbate.com", "stripchat.com", "livejasmin.com",
        "bongacams.com", "camsoda.com", "cam4.com", "myfreecams.com", "erome.com", "motherless.com",
        "rule34.xxx", "rule34.paheal.net", "nhentai.net", "e-hentai.org", "hentaihaven.xxx", "hanime.tv",
        "fapello.com", "thothub.to", "coomer.su", "kemono.su", "simpcity.su", "beeg.com", "tnaflix.com",
        "hqporner.com", "porn.com", "sex.com", "xxx.com", "literotica.com", "fuq.com", "porndig.com",
        "pornone.com", "4tube.com", "porn300.com", "txxx.com", "hclips.com", "upornia.com", "vjav.com",
        "sxyprn.com", "daftsex.com", "javhd.com", "jable.tv", "missav.com", "supjav.com", "ok.xxx",
        "hentai2read.com", "hitomi.la", "tsumino.com", "pixiv.net", "gelbooru.com", "danbooru.donmai.us",
        "rule34video.com", "xbooru.com", "realbooru.com", "f95zone.to", "nutaku.net", "adultfriendfinder.com",
        "manyvids.com", "pornpics.com", "imagefap.com", "ibradome.com", "bunkr.su", "bunkrr.su", "cyberdrop.me",
        "pimpandhost.com", "sexyandfunny.com", "ehentai.org", "rule34hentai.net", "hentaifox.com",
        "hentaiheroes.com", "camwhores.tv", "recurbate.com", "porntn.com", "leakedzone.com", "influencersgonewild.com",
        "boobpedia.com", "fapdungeon.com", "dirtyship.com", "thefappeningblog.com", "celebjihad.com",
        "xxxbp.tv", "pornzog.com", "xfantazy.com", "sextb.net", "pornktube.tv", "watchmygf.me", "zbporn.com",
        "yespornplease.com", "porndoe.com", "xmoviesforyou.com", "netfapx.com", "hqcollect.net", "xanimu.com",
    )

    /**
     * Substrings that are unambiguous adult markers anywhere in a host name.
     * Deliberately excludes "sex", "cam", "ass" etc. (see [tokenKeywords]).
     */
    val strongKeywords: List<String> = listOf(
        "porn", "xxx", "hentai", "nsfw", "onlyfans", "fansly", "xvideo", "xnxx", "xhamster", "redtube",
        "youporn", "brazzers", "chaturbate", "stripchat", "livejasmin", "bongacams", "camsoda", "camgirl",
        "camwhore", "rule34", "nhentai", "spankbang", "eporner", "tnaflix", "motherless", "fapello",
        "fapdu", "fapster", "fappening", "erome", "javhd", "missav", "javtube", "jav.guru", "hanime",
        "thothub", "thotsbay", "coomer", "kemono", "simpcity", "leakedzone", "gonewild", "nudes",
        "nudist", "boobs", "titties", "milfs", "escort", "hookup", "fuckbook", "fetlife", "bdsm",
        "sexcam", "sextape", "sexvid", "sexychat", "pussy", "dildo", "vibrator", "stripper", "striptease",
        "invidious", "piped.", "yewtu", "shorts",
    )

    /**
     * Words that are adult only as a whole label token (split on '.', '-', '_' and digits),
     * so "essex.ac.uk" or "campus.fr" are not hit.
     */
    val tokenKeywords: Set<String> = setOf(
        "sex", "sexy", "sexe", "sexo", "nude", "naked", "adult", "adults", "erotic", "erotica", "erotique",
        "milf", "fetish", "tits", "ass", "anal", "cum", "cock", "dick", "fap", "wank", "orgasm", "orgy",
        "lingerie", "webcam", "webcams", "camgirls",
        "hardcore", "softcore", "playboy", "penthouse", "hustler", "whore", "slut", "sluts", "bitch",
        "boobies", "booty", "twerk", "thot", "thots", "leaks", "leaked", "waifu", "ecchi", "yaoi",
        "yuri", "doujin", "doujinshi", "ahegao", "futa", "futanari", "loli", "shota", "rule34", "porno",
        "pornos", "putas", "escorts", "hookers", "swingers", "hentay", "18plus", "xrated",
    )

    /**
     * Hosts whose A/AAAA answer is replaced by the address of a SafeSearch-enforcing host.
     * Documented by the providers themselves (Google, Bing, DuckDuckGo).
     */
    val safeSearchRewrite: Map<String, String> = buildMap {
        for (h in listOf(
            "google.com", "google.fr", "google.co.uk", "google.de", "google.es", "google.it", "google.be",
            "google.ch", "google.ca", "google.nl", "google.pt", "google.co.ma", "google.dz", "google.tn",
            "google.ru", "google.com.br", "google.com.au", "google.co.in", "google.co.jp", "google.pl",
            "google.se", "google.at", "google.ie", "google.lu", "google.com.tr", "google.com.eg", "google.ae",
            "google.sn", "google.ci", "google.cm", "google.mg", "google.co.za", "google.com.mx", "google.com.ar",
        )) {
            put(h, "forcesafesearch.google.com")
            put("www.$h", "forcesafesearch.google.com")
        }
        put("bing.com", "strict.bing.com")
        put("www.bing.com", "strict.bing.com")
        put("duckduckgo.com", "safe.duckduckgo.com")
        put("www.duckduckgo.com", "safe.duckduckgo.com")
        put("start.duckduckgo.com", "safe.duckduckgo.com")
        put("pixabay.com", "safesearch.pixabay.com")
    }
}
