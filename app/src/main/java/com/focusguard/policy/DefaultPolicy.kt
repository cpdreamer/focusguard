package com.focusguard.policy

import com.focusguard.data.BlockMode

/**
 * Built-in classification of packages. Pure data + pure functions, unit-tested.
 *
 * Decision order for an installed package:
 *  1. user rule in the database wins;
 *  2. [alwaysAllow] (essential / explicitly kept by the user);
 *  3. [alwaysBlock] known distraction packages;
 *  4. heuristics on the package name (browsers, games, social);
 *  5. the developer-declared app category (game / video / social) => always blocked;
 *  6. packages installed after the commitment started => always blocked (no bypass by
 *     installing a new browser or game);
 *  7. preinstalled packages: allowed; other third-party packages: [BlockMode.FOCUS_ONLY].
 */
object DefaultPolicy {

    /** Never suspended, regardless of mode. */
    val alwaysAllow: Set<String> = setOf(
        // communication
        "com.whatsapp",
        "com.discord",
        "com.Slack",
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        "com.android.dialer",
        "com.samsung.android.dialer",
        "com.google.android.dialer",
        "com.android.contacts",
        "com.samsung.android.app.contacts",
        "com.google.android.contacts",
        "com.google.android.gm",
        "com.samsung.android.email.provider",
        // essentials
        "com.android.settings",
        "com.samsung.android.app.clockpackage",
        "com.google.android.deskclock",
        "com.sec.android.app.clockpackage",
        "com.android.calculator2",
        "com.sec.android.app.popupcalculator",
        "com.google.android.calculator",
        "com.google.android.calendar",
        "com.samsung.android.calendar",
        "com.google.android.apps.maps",
        "com.sec.android.app.camera",
        "com.google.android.GoogleCamera",
        "com.sec.android.gallery3d",
        "com.google.android.apps.photos",
        "com.android.vending",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.android.chrome",
        "com.sec.android.app.myfiles",
        "com.google.android.apps.docs",
        "com.google.android.apps.docs.editors.docs",
        "com.google.android.apps.docs.editors.sheets",
        "com.google.android.keep",
        "com.samsung.android.app.notes",
        "com.google.android.apps.translate",
        "com.android.systemui",
        "com.samsung.android.spay",
        "com.google.android.apps.walletnfcrel",
        // music
        "com.spotify.music",
        "com.apple.android.music",
        "com.sec.android.app.music",
        "com.soundcloud.android",
        "com.aspiro.tidal",
        "com.deezer.android.app",
        // chess (kept at the user's request)
        "com.chess",
        "org.lichess.mobileapp",
        // study / science
        "org.wikipedia",
        "com.wolfram.android.alpha",
        "com.microsoft.office.onenote",
        "com.adobe.reader",
        "org.geogebra.android",
        "com.anki.android", "com.ichi2.anki",
        "com.duolingo",
        // mainstream AI study assistants (moderated; the companion / uncensored ones are in alwaysBlock)
        "com.openai.chatgpt",
        "com.google.android.apps.bard",
        "com.anthropic.claude",
        "com.microsoft.copilot",
        "ai.perplexity.app.android",
        "ai.mistral.chat",
        // this app
        "com.focusguard",
    )

    /** Suspended in every mode. */
    val alwaysBlock: Set<String> = setOf(
        "com.google.android.googlequicksearchbox",                 // Google app: Discover feed + image search
        "com.google.android.youtube",
        "com.google.android.youtube.tv",
        "com.google.android.apps.youtube.music",                   // shares YouTube's video servers, which are DNS-blocked
        "com.zhiliaoapp.musically", "com.ss.android.ugc.trill",   // TikTok
        "com.instagram.android",
        "com.instagram.barcelona",                                 // Threads
        "com.snapchat.android",
        "com.reddit.frontpage",
        "com.twitter.android",
        "tv.twitch.android.app",
        "com.netflix.mediaclient",
        "com.amazon.avod.thirdpartyclient",
        "com.disney.disneyplus",
        "com.facebook.katana",
        "com.facebook.lite",
        "com.pinterest",
        "com.tumblr",
        "com.vanced.android.youtube", "app.revanced.android.youtube",
        "org.schabi.newpipe",
        "com.kick.mobile",
        // preinstalled feeds / video / game hubs (system apps would otherwise be allowed)
        "com.samsung.android.app.spage",                          // Samsung Free
        "com.samsung.android.tvplus",
        "com.samsung.android.game.gamehome", "com.samsung.android.game.gametools",
        "com.google.android.videos",                               // Google TV
        "com.google.android.play.games",
        // image boards / meme feeds (soft-adult heavy)
        "com.imgur.mobile", "com.ninegag.android.app", "mobi.ifunny",
        "com.quora.android",
        "com.vkontakte.android", "com.sina.weibo",
        // stock-photo / wallpaper browsing
        "com.gettyimages.android", "com.gettyimages.ideas", "com.shutterstock.android", "com.istockphoto.app",
        "com.unsplash.app", "com.pexels.app", "com.pixabay.app", "com.flickr.android", "com.yahoo.mobile.client.android.flickr",
        "com.fivehundredpx.viewer", "net.zedge.android", "com.wallpaperscraft.wallpaper", "com.backdrops.wallpapers",
        "com.pinterest", "com.weheartit", "com.vsco.cam", "com.behance.behance", "com.dribbble.android",
        // Moroccan / Arabic / French VOD and TV replay
        "ma.snrt.forja", "com.forja.app", "ma.snrt.snrtlive", "ma.snrt.live", "com.snrt.snrtlive", "ma.tv2m.tv2mlive",
        "ma.medi1tv.app", "net.mbc.shahid", "com.shahid.app", "com.osn.go", "com.osnplus.app", "com.parsifal.starz",
        "tv.tod.app", "com.bein.beinsportsconnect", "com.weyyak.app", "com.viu.pad", "com.viu.phone",
        "com.rotana.rotanaplus", "com.watchit.app", "fr.tf1.mytf1", "fr.m6.m6replay", "fr.francetv.pluzz",
        "com.canal.android.canal", "tv.molotov.app", "com.cbs.ca", "com.wbd.stream", "com.hbo.hbonow",
        "com.rakuten.tv", "tv.pluto.android", "com.plexapp.android", "com.tubitv", "com.viki.android",
        // short video / live streaming
        "video.like", "com.kwai.video", "com.kwai.bulldog", "co.triller.droid",
        "com.bd.nproject",                                         // Lemon8
        "com.xingin.xhs",                                          // RedNote
        "tv.danmaku.bili", "com.bilibili.app.in",
        "sg.bigo.live", "com.sgiggle.production",                 // Bigo, Tango
        "com.vimeo.android.videoapp", "com.dailymotion.dailymotion",
        "com.rumble.battles", "com.odysee.app",
        "com.crunchyroll.crunchyroid", "com.hulu.plus", "com.hbo.hbonow", "com.wbd.stream",
        "com.canal.android.canal", "tv.molotov.app",
        // messengers full of adult channels / random-stranger chat (WhatsApp, Discord, Slack stay)
        "org.telegram.messenger", "org.telegram.messenger.web", "org.telegram.plus", "org.thunderdog.challegram",
        "kik.android", "co.yellw.yellowapp", "com.wizzapp",
        "com.azarlive.android", "cool.monkey.android", "com.holla.android",
        "com.myyearbook.m",                                        // MeetMe
        "com.chatrandom.app", "com.chatspin", "com.camsurf", "com.shagle", "com.coomeet.app", "com.livu.app",
        "com.tinychat.android", "com.paltalk.chat.android", "com.camshare.camfrog.android", "com.wireclub",
        "com.arousr", "com.sextpanther",
        // voice/video chat rooms & "make friends" apps (SUGO, Tigo, Hago, Yalla, Chamet, Litmatch, Yubo, MICO, Uplive, LiveMe, Wink, Hoop, Lamour, Skout, Waplog, Boo, Ablo, Wakie, SoulChill, Partying…)
        "com.sugo.chat", "com.sugoapp.android", "com.tigo.chat", "com.tigolive", "com.yy.hiyo", "com.yalla.yallachat",
        "com.yalla.yallagames", "com.yalla.ludo", "com.yallaludo.game", "com.yalla.parchis", "com.chamet.android",
        "com.litatom.app", "com.mico", "com.mico.live", "com.asiainno.uplive", "com.cmcm.live",
        "com.wink.android", "com.hoop.app", "com.lamour.chat", "com.skout.android", "com.waplog.social", "enterprises.dating.boo",
        "com.ablo.app", "com.wakie.wakiex", "com.soulchill.app", "com.partying.app", "com.welike.app", "com.whoo.app",
        "com.lemo.chat", "com.popo.chat", "com.yoho.chat", "com.mixu.app", "com.saya.chat", "com.imo.android.imoim",
        "com.imo.android.imoimhd", "com.justalk.android", "com.melon.vpn", "com.tantan.app", "com.mumble.app",
        "com.frill.android", "com.hellotalk", "com.speaky.android", "com.slowly.app",
        // dating
        "com.tinder", "com.bumble.app", "com.badoo.mobile", "com.grindrapp.android",
        "co.hinge.app", "com.okcupid.okcupid", "com.ftw_and_co.happn", "net.lovoo.android",
        "com.pof.android", "com.p1.mobile.putong", "com.match.android.matchmobile",
        // fan-fiction / comics readers (soft-adult heavy)
        "wp.wattpad", "com.naver.linewebtoon", "com.tapastic", "com.webcomicsapp.android",
        "org.mangadex.app", "com.tachiyomi", "eu.kanade.tachiyomi", "app.komikku", "app.mihon",
        "com.crunchyroll.manga", "com.manga.plus", "jp.co.shueisha.mangaplus",
        "com.reddit.frontpage.beta", "com.rubenmayayo.reddit", "com.laurencedawson.reddit_sync",
        "o.o.joey", "com.andrewshu.android.reddit", "ml.docilealligator.infinityforreddit",
        "com.opera.browser", "com.opera.mini.native", "com.opera.gx",
        "org.mozilla.firefox", "org.mozilla.focus", "org.mozilla.fenix",
        "com.brave.browser",
        "com.duckduckgo.mobile.android",
        "com.microsoft.emmx",
        "com.sec.android.app.sbrowser",
        "com.vivaldi.browser",
        "com.kiwibrowser.browser",
        "com.UCMobile.intl",
        "mark.via.gp",
        "com.ecosia.android",
        "acr.browser.lightning",
        "com.yandex.browser",
        "org.torproject.torbrowser",
        "com.cloudflare.onedotonedotone",
        "com.expressvpn.vpn", "com.nordvpn.android", "com.protonvpn.android", "com.surfshark.vpnclient.android",
        "hotspotshield.android.vpn", "com.free.vpn.super.hotspot.open", "com.tunnelbear.android",
        // pirate streaming / torrent clients / media centres
        "com.stremio.one", "org.xbmc.kodi", "com.cinemahd.v2", "com.utorrent.client", "com.bittorrent.client",
        "com.delphicoder.flud", "org.proninyaroslav.libretorrent", "com.frostwire.android", "idm.internet.download.manager",
        "com.plexapp.android", "org.jellyfin.mobile", "com.mxtech.videoplayer.ad", "com.mxtech.videoplayer.pro",
        // gambling / betting
        "com.winamax.android", "fr.betclic.android", "com.fdj.parionssport", "fr.fdj.apps.fdj", "com.unibet.fr",
        "com.bet365Wrapper.Bet365_Application", "com.pmu.paris", "com.zebet.android", "com.pokerstars.fr",
        "com.stake.android", "org.xbet.client1",
        // game platforms / launchers
        "com.valvesoftware.android.steam.community", "com.epicgames.portal", "com.roblox.client",
        // infinite-scroll shopping feeds and news aggregators
        "com.einnovation.temu", "com.zzkko", "com.alibaba.aliexpresshd", "com.contextlogic.wish", "com.shopee.fr",
        "com.google.android.apps.magazines", "de.axelspringer.yana.zeropage", "flipboard.app",
        "com.microsoft.amp.apps.bingnews", "com.opera.app.news", "com.samsung.android.app.news",
        // AI companions / roleplay chat / uncensored generators (ChatGPT, Gemini, Claude, Copilot stay allowed)
        "ai.character.app", "ai.replika.app", "com.Beauchamp.Messenger.external",
    )

    private val browserHints = listOf("browser", "chromium", "webview.app", "incognito", "private")
    private val vpnHints = listOf("vpn", "proxy", "unblock", "torproject", "orbot", "psiphon", "wireguard", "openvpn", "shadowsocks")
    private val aiCompanionHints = listOf(
        "replika", "ai.character", "characterai", "janitorai", "crushon", "spicychat", "kindroid", "dreamgf",
        "girlfriend", "boyfriend", "aigf", "soulgen", "chub.ai", "promptchan", "nudify", "undress", "deepnude",
        "deepfake", "deepswap", "uncensored", "polybuzz", "joyland", "talkie.ai", "talkieai", "nomi.ai", "muah", "candy.ai",
        "roleplay", "aidungeon", "novelai", "civitai", "pixai", "yodayo", "seaart",
    )
    private val brainrotHints = listOf(
        "torrent", "stremio", "xbmc", "cinemahd", "moviebox", "showbox", "fmovies", "egybest", "iptv",
        "bet365", "betclic", "winamax", "unibet", "parionssport", "pokerstars", "casino", "poker", "betting",
        "slots", "roulette", "blackjack", "jackpot", "lottery", "gambl",
    )
    private val gameHints = listOf(
        "game", "games", "supercell", "king.candy", "com.miHoYo", "com.mojang", "com.roblox",
        "com.ea.", "com.gameloft", "com.activision", "com.riotgames", "com.tencent.ig",
        "com.dts.freefire", "com.pubg", "com.epicgames", "com.nianticlabs", "com.zynga", "com.playrix",
        "com.rovio", "com.innersloth", "com.ubisoft", "com.blizzard", "com.netmarble", "com.kiloo",
        "com.halfbrick", "com.outfit7", "com.miniclip", "com.imangi", "com.ketchapp", "com.voodoo",
    )
    private val socialHints = listOf(
        "tiktok", "shorts", "snapchat", "instagram", "reddit", "twitch", "bigo", "likee",
        "telegram", "tinder", "badoo", "bumble", "grindr", "dating", "flirt", "hookup", "lovoo", "meetme",
        "sugo", "tigo", "hiyo", "hago", "yalla", "chamet", "litmatch", "litatom", "yubo", "uplive", "liveme", "lamour",
        "skout", "waplog", "soulchill", "partying", "voicechat", "chatroom", "makefriends", "strangers", "talktostrangers",
        "randomvideo", "livechat", "livevideo", "golive", "livestreaming", "ludo", "parchis",
        "onlyfans", "fansly", "hentai", "manga", "webtoon", "waifu", "nsfw", "xxx", "porn", "sexy", "adult",
        "wattpad", "fanfic", "9gag", "ifunny", "imgur", "meme", "livestream", "streamer", "omegle", "chatroulette", "sext", "sexchat", "strangerchat", "randomchat", "videochat", "livu", "flirt", "hookup", "horny", "naughty",
        "anime", "kwai", "triller", "bilibili", "vimeo", "dailymotion", "rumble", "crunchyroll",
        "gettyimages", "shutterstock", "istockphoto", "unsplash", "pexels", "pixabay", "flickr", "pinterest",
        "forja", "shahid", "weyyak", "starzplay", "osnplus", "yacine", "koora", "yalla", "mytf1", "6play", "mycanal", "molotov",
    )

    /** Heuristic classification for a package not in any explicit list. */
    fun classifyByName(packageName: String): BlockMode? {
        val p = packageName.lowercase()
        if (browserHints.any { p.contains(it) }) return BlockMode.ALWAYS
        if (vpnHints.any { p.contains(it) }) return BlockMode.ALWAYS
        if (socialHints.any { p.contains(it) }) return BlockMode.ALWAYS
        if (brainrotHints.any { p.contains(it) }) return BlockMode.ALWAYS
        if (aiCompanionHints.any { p.contains(it) }) return BlockMode.ALWAYS
        if (gameHints.any { p.contains(it.lowercase()) }) return BlockMode.ALWAYS
        return null
    }

    /** ApplicationInfo.category values that mean "distraction" regardless of name. */
    private val blockedCategories = setOf(
        android.content.pm.ApplicationInfo.CATEGORY_GAME,
        android.content.pm.ApplicationInfo.CATEGORY_VIDEO,
        android.content.pm.ApplicationInfo.CATEGORY_SOCIAL,
    )

    /**
     * Final decision for a launchable package.
     * @param userRule optional rule set by the user, wins over everything.
     * @param isSystem true if preinstalled/system package.
     * @param category ApplicationInfo.category (-1 if undefined).
     * @param installedAfterCommitment true if first installed after the lock started.
     */
    fun decide(
        packageName: String,
        userRule: BlockMode?,
        isSystem: Boolean,
        category: Int = -1,
        installedAfterCommitment: Boolean = false,
    ): BlockMode {
        userRule?.let { return it }
        if (packageName in alwaysAllow) return BlockMode.ALLOW
        if (packageName in alwaysBlock) return BlockMode.ALWAYS
        classifyByName(packageName)?.let { return it }
        if (category in blockedCategories) return BlockMode.ALWAYS
        if (installedAfterCommitment && !isSystem) return BlockMode.ALWAYS
        return if (isSystem) BlockMode.ALLOW else BlockMode.FOCUS_ONLY
    }

    /** Packages that must never be suspended even in focus mode: suspending them breaks the phone. */
    val neverSuspend: Set<String> = setOf(
        "com.android.settings", "com.android.systemui", "com.android.phone", "com.android.dialer",
        "com.samsung.android.dialer", "com.google.android.dialer", "com.android.emergency",
        "com.google.android.gms", "com.google.android.gsf", "com.android.vending",
        "com.samsung.android.incallui", "com.android.incallui", "com.android.server.telecom",
        "com.samsung.android.app.clockpackage", "com.sec.android.app.clockpackage", "com.google.android.deskclock",
        "com.focusguard",
    )
}
