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
        // gambling (seed; the downloaded gambling list carries the long tail)
        "bet365.com", "unibet.fr", "winamax.fr", "betclic.fr", "pmu.fr", "pokerstars.com", "stake.com",
        "roobet.com", "1xbet.com", "parionssport.fdj.fr", "fdj.fr", "zebet.fr", "netbet.fr", "bwin.fr",
        "csgoroll.com", "rollbit.com", "duelbits.com", "gamdom.com", "bc.game", "shuffle.com", "rainbet.com",
        // pirate / free streaming, anime, sports streams (seed; the downloaded anti-piracy list carries mirrors)
        "fmovies.to", "fmoviesz.to", "fmovies24.to", "egybest.com", "egybest.org", "egy.best", "123movies.com",
        "123movies.to", "soap2day.to", "soap2day.rs", "gomovies.sx", "yesmovies.ag", "putlocker.vip", "hdtoday.tv",
        "flixhq.to", "sflix.to", "myflixer.to", "hurawatch.cc", "primewire.tf", "solarmovie.pe", "moviesjoy.to",
        "lookmovie2.to", "bflix.to", "movie4kto.net", "watchseries.pe", "cineb.rs", "attackertv.so", "braflix.video",
        "vidsrc.to", "vidsrc.me", "2embed.cc", "streamtape.com", "doodstream.com", "dood.to", "upstream.to",
        "mixdrop.co", "voe.sx", "filemoon.sx", "streamwish.to", "vidoza.net", "uqload.co", "mp4upload.com",
        "wiflix.voto", "french-stream.re", "papadustream.pe", "voirfilms.gratis", "streamcomplet.city", "coflix.tv",
        "cpasmieux.tv", "empire-streaming.bio", "zone-telechargement.zip", "tirexo.net", "wawacity.rocks",
        "shahid4u.com", "cimaclub.io", "arabseed.show", "mycima.wiki", "akwam.to", "faselhd.express",
        "9anime.to", "9animetv.to", "aniwatch.to", "hianime.to", "gogoanime.by", "zoro.to", "animesuge.to",
        "animixplay.to", "kissanime.ru", "animeflix.live", "animepahe.ru", "aniwave.to", "anime-sama.fr",
        "voiranime.com", "neko-sama.fr", "kickassanime.am", "4anime.gg", "zorox.to", "animedao.to",
        "crackstreams.io", "streameast.io", "streameast.to", "sportsurge.net", "buffstreams.tv", "methstreams.com",
        "vipleague.im", "footybite.to", "totalsportek.to", "livetv.sx", "rojadirecta.tv", "hesgoal.io",
        "thepiratebay.org", "1337x.to", "yts.mx", "rarbg.to", "limetorrents.lol", "nyaa.si", "torrentgalaxy.to",
        "eztv.re", "kickasstorrents.to", "torlock.com", "magnetdl.com", "cpasbien.to", "yggtorrent.wtf",
        // meme / "fail" video feeds, gossip, image boards
        "4chan.org", "4channel.org", "8kun.top", "kiwifarms.net", "liveleak.com", "ebaumsworld.com",
        "cheezburger.com", "knowyourmeme.com", "memedroid.com", "boredpanda.com", "buzzfeed.com",
        "tmz.com", "dailymail.co.uk", "thesun.co.uk", "perezhilton.com", "purepeople.com", "public.fr",
        "voici.fr", "closermag.fr", "gala.fr", "jeuxvideo.com", "koreus.com", "topito.com", "demotivateur.fr",
        // infinite-scroll shopping feeds
        "temu.com", "shein.com", "shein.fr", "aliexpress.com", "aliexpress.fr", "wish.com", "shopee.fr",
        // free-game portals
        "poki.com", "crazygames.com", "y8.com", "miniclip.com", "kongregate.com", "armorgames.com",
        "addictinggames.com", "agame.com", "friv.com", "coolmathgames.com", "gamepix.com", "lagged.com",
        "silvergames.com", "kizi.com", "itch.io", "newgrounds.com", "1v1.lol", "krunker.io", "shellshock.io",
        "slither.io", "agar.io", "diep.io", "paper-io.com", "gartic.io", "skribbl.io", "jklm.fun",
        "geoguessr.com", "roblox.com", "rbxcdn.com", "epicgames.com", "store.steampowered.com", "steamcommunity.com",
        "minecraft.net", "ea.com", "supercell.com", "clashofclans.com", "brawlstars.com", "hoyoverse.com",
        "genshin.hoyoverse.com", "leagueoflegends.com", "valorant.com", "playvalorant.com", "riotgames.com",
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
        // pirate streaming / anime / sports-stream / torrent mirror families
        "fmovies", "123movies", "putlocker", "soap2day", "egybest", "gomovies", "yesmovies", "hdtoday",
        "flixhq", "sflix", "myflixer", "hurawatch", "primewire", "solarmovie", "moviesjoy", "lookmovie",
        "bflix", "watchseries", "movie4k", "cineb.", "braflix", "vidsrc", "2embed", "streamtape", "doodstream",
        "mixdrop", "filemoon", "streamwish", "wiflix", "french-stream", "papadustream", "voirfilm", "streamcomplet",
        "coflix", "cpasmieux", "empire-stream", "zone-telechargement", "wawacity", "cimaclub", "arabseed",
        "mycima", "akwam", "faselhd", "shahid4u", "9anime", "aniwatch", "hianime", "gogoanime", "animesuge",
        "animixplay", "kissanime", "animeflix", "animepahe", "aniwave", "anime-sama", "voiranime", "neko-sama",
        "kickassanime", "crackstream", "streameast", "sportsurge", "buffstream", "methstream", "vipleague",
        "footybite", "totalsportek", "rojadirecta", "hesgoal", "piratebay", "1337x", "rarbg", "limetorrent",
        "torrentgalaxy", "kickasstorrent", "yggtorrent", "cpasbien", "torrent",
        // gambling brands
        "1xbet", "bet365", "betclic", "winamax", "unibet", "pokerstars", "roobet", "csgoroll", "rollbit", "duelbits", "gamdom",
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
        // brainrot: pirate streaming, anime/manga readers, gambling, meme feeds
        // ("stream"/"proxy" are deliberately absent: universities use stream.microsoft.com / proxy.univ-*.fr)
        "movies", "movie", "moviez", "flix", "anime", "animes", "manga", "mangas", "scans", "webtoon", "torrents",
        "casino", "casinos", "poker", "slots", "bet", "bets", "betting", "gambling", "jackpot",
        "memes", "meme", "gifs", "unblocked", "unblocker",
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
