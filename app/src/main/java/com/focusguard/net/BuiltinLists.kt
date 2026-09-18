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
        // search-result thumbnails are re-hosted by the engines themselves (a blocked x.com/tiktok
        // image still shows in the web tab otherwise); blocking these makes web search text-only
        "encrypted-tbn0.gstatic.com", "encrypted-tbn1.gstatic.com", "encrypted-tbn2.gstatic.com",
        "encrypted-tbn3.gstatic.com", "encrypted-tbn.gstatic.com", "encrypted-vtbn0.gstatic.com",
        "encrypted-vtbn1.gstatic.com", "encrypted-vtbn2.gstatic.com", "encrypted-vtbn3.gstatic.com",
        "th.bing.com", "tse1.mm.bing.net", "tse2.mm.bing.net", "tse3.mm.bing.net", "tse4.mm.bing.net",
        "tse1.explicit.bing.net", "tse2.explicit.bing.net", "tse3.explicit.bing.net", "tse4.explicit.bing.net",
        "external-content.duckduckgo.com", "proxy.duckduckgo.com", "avatars.yandex.net",
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
        // stock-photo / image-bank / wallpaper browsing (soft-adult side door; diagrams live in the cours PDF)
        "gettyimages.com", "gettyimages.fr", "gettyimages.co.uk", "gettyimages.ae", "gettyimages.ma",
        "gettyimages-cdn.com", "gettyimagesbank.com", "istockphoto.com", "istockimg.com", "istock.com",
        "shutterstock.com", "shutterstock.fr", "shutterstock-cdn.com",
        "alamy.com", "alamy.fr", "alamy.de", "alamy.it", "alamy.es", "alamyimages.fr",
        "adobe.stock.com", "stock.adobe.com", "ftcdn.net", "fotolia.com", "depositphotos.com",
        "depositphotos.fr", "dreamstime.com", "123rf.com", "bigstockphoto.com", "stocksy.com",
        "unsplash.com", "pexels.com", "pixabay.com", "pixabay.fr", "freepik.com", "freepik.es", "freepik.fr",
        "freeimages.com", "stockvault.net", "burst.shopify.com", "picjumbo.com", "kaboompics.com",
        "rawpixel.com", "vecteezy.com", "canstockphoto.com", "stocksnap.io", "reshot.com", "gratisography.com",
        "flickr.com", "staticflickr.com", "500px.com", "500px.org", "behance.net", "dribbble.com",
        "artstation.com", "smugmug.com", "photobucket.com", "imgbb.com", "postimg.cc", "postimages.org",
        "ibb.co", "imgbox.com", "imageshack.com", "tinypic.com", "lensdump.com", "pixhost.to",
        "imagevenue.com", "imagebam.com", "imgur.io", "wallhaven.cc", "wallpaperaccess.com",
        "wallpapercave.com", "wallpaperflare.com", "wallpapersden.com", "wallpapers.com", "hdwallpapers.in",
        "wallpaperbetter.com", "alphacoders.com", "wallpaperscraft.com", "peakpx.com", "zedge.net",
        "backiee.com", "wallpapersafari.com", "wallpaperset.com", "uhdpaper.com", "4kwallpapers.com",
        "wallpapers-clan.com", "wallpaperswide.com", "hdqwalls.com", "getwallpapers.com",
        "weheartit.com", "vsco.co", "eyeem.com", "fotocommunity.com", "fotocommunity.fr", "1x.com",
        "photo.net", "viewbug.com", "gurushots.com", "youpic.com", "picfair.com", "lookbook.nu",
        "pin.it", "pinterest.fr", "pinterest.co.uk", "pinterest.de", "pinterest.es", "pinterest.it",
        "pinterest.ca", "pinterest.ch", "pinterest.be", "pinterest.pt", "pinterest.com.mx", "pinterest.jp",
        "pinterest.co.kr", "pinterest.com.au", "pinterest.nz", "pinterest.ru", "pinterest.se", "pinterest.dk",
        "pinterest.nl", "pinterest.at", "pinterest.ie", "pinterest.cl", "pinterest.ph", "pinterest.info",
        // Moroccan / Arabic / French VOD and TV-replay platforms
        "forja.ma", "forja.tv", "forjaplus.ma", "snrt.ma", "snrtlive.ma", "2m.ma", "medi1tv.com",
        "medi1tv.ma", "chouftv.ma", "goud.ma",
        "shahid.net", "shahid.mbc.net", "mbc.net", "watchit.com", "osnplus.com", "osn.com", "starzplayarabia.com",
        "starzplay.com", "tod.tv", "todtv.com", "weyyak.com", "viu.com", "jawwy.tv",
        "rotana.net", "rotanaplus.net", "elcinema.com", "cima4u.tv", "cima4u.io", "cimanow.cc", "cimanow.tv",
        "egydead.com", "egydead.live", "tuktukcima.com", "tuktukcinema.com", "topcinema.cam", "wecima.click",
        "wecima.tube", "wecima.show", "mycima.io", "laroza.now", "laroza.tv", "arabseed.ink", "arblionz.com",
        "aflamco.com", "aflaam.com", "cimaclub.club", "cimaclub.cc", "movizland.online", "movizland.cyou",
        "fushaar.com", "fushaar.org", "hdd.watch", "hddwatch.tv", "3sk.tv", "3seq.com", "akoam.net", "akwam.cx",
        "akwam.us", "shoofvod.com", "shoofmedia.com", "shoof.tv", "mytv.ma", "tvhima.com", "yacine.tv",
        "yacinetv.com", "yacine-tv.com", "koora.live", "kooora.com", "kora-online.tv", "koora4live.io",
        "yalla-shoot.com", "yallashoot.io", "yallakora.com", "as-goal.com", "kooralive.net", "kora-star.com",
        "livekoora.online", "bein-live.com", "beinmatch.com", "beinsports.com", "bein.net", "beinconnect.com",
        "mytf1.fr", "tf1.fr", "tf1plus.fr", "6play.fr", "m6.fr", "france.tv", "canalplus.com",
        "mycanal.fr", "molotov.tv", "salto.fr", "rmcbfmplay.com", "ocs.fr", "paramountplus.com",
        "paramountplus.fr", "max.com", "hbomax.com", "play.hbomax.com", "crave.ca", "appletv.com", "tv.apple.com",
        "pluto.tv", "plex.tv", "tubitv.com", "rakuten.tv", "viki.com", "iqiyi.com", "iq.com", "wetv.vip",
        "bigo.tv", "likee.video", "omegle.com", "ome.tv", "chatroulette.com", "monkey.app",
        "tango.me", "kwai.com", "kuaishou.com", "quora.com",
        // voice/video chat rooms & "make friends" apps
        "sugoapp.com", "sugo.chat", "tigolive.com", "tigo.chat", "ihago.net", "hago.com", "yalla.live",
        "yallaludo.com", "yallagames.com", "chamet.com", "chametapp.com", "litmatch.net", "litatom.com",
        "yubo.live", "micoworld.net", "mico.us", "uplive.tv", "up.live", "liveme.com", "winkchat.app",
        "hoop.photo", "lamourapp.com", "skout.com", "waplog.com", "boo.world", "ablo.live", "wakie.com",
        "soulchill.com", "partying.chat", "welike.app", "imo.im", "justalk.com", "tantanapp.com",
        "hellotalk.com", "speaky.com", "getslowly.com", "meetme.com", "yubo.chat",
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
        // marketplace image CDNs: eBay/Amazon/AliExpress keep working, listings become text-only
        "i.ebayimg.com", "ir.ebaystatic.com", "thumbs.ebaystatic.com", "m.media-amazon.com",
        "images-na.ssl-images-amazon.com", "images-eu.ssl-images-amazon.com", "images-fe.ssl-images-amazon.com",
        "ae01.alicdn.com", "ae04.alicdn.com", "img.alicdn.com",
        // secondary marketplaces / print shops that are effectively image galleries
        "etsy.com", "etsystatic.com", "redbubble.com", "teepublic.com", "society6.com", "displate.com",
        "allposters.com", "allposters.fr", "posters.fr", "desenio.com", "desenio.fr", "juniqe.com", "juniqe.fr",
        "vinted.fr", "vinted.com", "leboncoin.fr", "wallapop.com", "ebay-kleinanzeigen.de", "kleinanzeigen.de",
        // PC / console game stores
        "xbox.com", "xboxlive.com", "microsoftgames.com", "store.epicgames.com", "gog.com", "humblebundle.com",
        "battle.net", "blizzard.com", "ea.com", "origin.com", "ubisoft.com", "ubi.com", "rockstargames.com",
        "minecraft.net",
        // VPN / proxy vendors
        "nordvpn.com", "expressvpn.com", "surfshark.com", "protonvpn.com", "windscribe.com", "hide.me",
        "privateinternetaccess.com", "cyberghostvpn.com", "mullvad.net", "tunnelbear.com", "hotspotshield.com",
        "urban-vpn.com", "1clickvpn.net", "zenmate.com", "hola.org", "psiphon.ca", "psiphon3.com", "torproject.org",
        "ultrasurf.us", "browsec.com", "setupvpn.com", "veepn.com", "touchvpn.net", "betternet.co", "ivacy.com",
        "purevpn.com", "atlasvpn.com", "itopvpn.com", "planetvpn.com", "freevpnplanet.com", "vpnunlimited.com",
        "keepsolid.com", "ipvanish.com", "strongvpn.com", "hidemyass.com", "hma.com", "speedify.com",
        "proxysite.com", "croxyproxy.com", "croxyproxy.rocks", "hidester.com", "kproxy.com", "4everproxy.com",
        // free-game portals
        "poki.com", "crazygames.com", "y8.com", "miniclip.com", "kongregate.com", "armorgames.com",
        "addictinggames.com", "agame.com", "friv.com", "coolmathgames.com", "gamepix.com", "lagged.com",
        "silvergames.com", "kizi.com", "itch.io", "newgrounds.com", "1v1.lol", "krunker.io", "shellshock.io",
        "slither.io", "agar.io", "diep.io", "paper-io.com", "gartic.io", "skribbl.io", "jklm.fun",
        "geoguessr.com", "roblox.com", "rbxcdn.com", "epicgames.com", "store.steampowered.com", "steamcommunity.com",
        "minecraft.net", "ea.com", "supercell.com", "clashofclans.com", "brawlstars.com", "hoyoverse.com",
        "genshin.hoyoverse.com", "leagueoflegends.com", "valorant.com", "playvalorant.com", "riotgames.com",
        // AI companions / roleplay chat and "uncensored" AI image/video generators
        // (study assistants ChatGPT, Gemini, Claude, Copilot, Perplexity, Mistral stay allowed)
        "character.ai", "c.ai", "janitorai.com", "janitorai.chat", "crushon.ai", "spicychat.ai", "candy.ai",
        "kupid.ai", "dreamgf.ai", "muah.ai", "nomi.ai", "replika.com", "replika.ai", "kindroid.ai", "chai-research.com",
        "myanima.ai", "romanticai.com", "eva.ai", "girlfriendgpt.com", "soulgen.net", "soulgen.ai", "pephop.ai",
        "sakura.fm", "chub.ai", "figgs.ai", "yodayo.com", "moescape.ai", "seaart.ai", "pixai.art", "tensor.art",
        "civitai.com", "civitai.green", "unstability.ai", "promptchan.ai", "pornpen.ai", "pornx.ai", "pornjourney.ai",
        "nudify.online", "undress.app", "deepswap.ai", "mrdeepfakes.com", "perchance.org", "nastia.ai", "joyland.ai",
        "talkie-ai.com", "polybuzz.ai", "dopple.ai", "linky.ai", "ourdream.ai", "herahaven.ai", "aidungeon.com",
        "novelai.net", "botify.ai", "chatfai.com", "anime.gf", "fantasy.ai", "secretdesires.ai", "lustix.ai",
        "venus.chub.ai", "yodayo.ai", "aiuncensored.info", "nsfwlover.com", "dittin.ai", "charstar.ai",
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
        // sexting / adult chat / random-stranger cam chat
        "justsext.com", "justsext.net", "sextfriend.com", "sextpanther.com", "arousr.com", "snapsext.com",
        "sextingforum.net", "sextlocal.com", "sextme.com", "phonesexts.com", "dirtyroulette.com", "flingster.com",
        "chatspin.com", "camsurf.com", "shagle.com", "emeraldchat.com", "chatrandom.com", "chathub.cam",
        "coomeet.com", "bazoocam.org", "chatib.us", "321sexchat.com", "sexchat.com", "lewdchat.com", "isexychat.com",
        "chat-avenue.com", "chatavenue.com", "freechatnow.com", "chatzy.com", "wireclub.com", "talkwithstranger.com",
        "joingy.com", "strangercam.com", "chatroulette.co", "omegle.tv", "omegle.io", "omegleapp.com", "ometv.chat",
        "uhmegle.com", "omeglealternative.com", "chatki.com", "luckycrush.live", "livetolives.com", "tinychat.com",
        "paltalk.com", "camfrog.com", "kikfriender.com", "kikusernames.com", "snapchatnudes.com", "sexmessenger.com",
        "adultfriendfinder.com", "ashleymadison.com", "seeking.com", "benaughty.com", "flirt.com", "iamnaughty.com",
        "onenightfriend.com", "uberhorny.com", "instabang.com", "fling.com", "passion.com", "alt.com", "xmatch.com",
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
        "sexcam", "sextape", "sexvid", "sexychat", "sexchat", "sext", "omegle", "chatroulette", "dirtyroulette",
        "strangercam", "horny", "naughty", "sugoapp", "tigolive", "yallaludo", "chamet", "litmatch", "litatom",
        "uplive", "liveme", "soulchill", "talktostrangers", "strangerchat", "randomchat", "videochat", "pussy", "dildo", "vibrator", "stripper", "striptease",
        "invidious", "piped.", "yewtu", "shorts",
        // stock-photo / wallpaper families (all Getty ccTLDs, mirrors)
        "gettyimages", "istockphoto", "shutterstock", "depositphotos", "dreamstime", "wallpaper", "wallhaven",
        "alphacoders", "freepik", "unsplash", "pexels", "pixabay", "flickr", "pinterest", "pinimg",
        // Moroccan / Arabic VOD & pirate-sport families
        "forja", "shahid.", "weyyak", "starzplay", "wecima", "cimanow", "egydead", "tuktuk", "movizland", "fushaar",
        "yacine", "koora", "kooora", "yalla-shoot", "yallashoot", "yallakora", "beinmatch", "bein-live",
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
        // AI companion / uncensored-generator families
        "janitorai", "crushon", "spicychat", "candy.ai", "dreamgf", "muah.ai", "kindroid", "girlfriendgpt",
        "aigirlfriend", "ai-girlfriend", "soulgen", "pephop", "chub.ai", "promptchan", "pornpen", "nudify",
        "undress", "deepnude", "deepfake", "deepswap", "uncensored", "nsfwlover", "polybuzz", "joyland.ai",
        // gambling brands
        "1xbet", "bet365", "betclic", "winamax", "unibet", "pokerstars", "roobet", "csgoroll", "rollbit", "duelbits", "gamdom",
    )

    /**
     * Innocent words that contain a [strongKeywords] entry; a host containing one of these is not
     * hit by that keyword ("sextant.example" is not sexting).
     */
    val strongKeywordExceptions: List<String> = listOf(
        "sextant", "sextet", "sexton", "sextu", "sextil", "thorny",
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
    }
}
