package com.focusguard.policy

/**
 * URL patterns for Chrome's URLBlocklist / URLAllowlist policies.
 * Pattern grammar: [scheme://][.]host[:port][/path][?query]
 *   - a leading "." matches only that exact host; otherwise subdomains match too;
 *   - "?key=value" matches when that pair is present anywhere in the query string
 *     (Chrome's docs write the separator as "@" in the grammar line but every example,
 *     and Chrome itself, use "?" — verified on Chrome 12x: "@" patterns never match).
 *
 * Goal: no web search. The google.com family is closed as a whole (search lives on the hosts the
 * account needs at DNS level) and [urlAllowlist] reopens its services one by one; the image/video
 * patterns stay as a second layer; pages that only exist to bypass filters are removed.
 */
object ChromePolicy {

    /** Google web hosts by country TLD (search works on any of them). Declared first: used by [urlBlocklist]. */
    val googleHosts: List<String> = listOf(
        "google.com", "google.fr", "google.co.uk", "google.de", "google.es", "google.it", "google.be",
        "google.ch", "google.ca", "google.nl", "google.pt", "google.co.ma", "google.dz", "google.tn",
        "google.ru", "google.com.br", "google.com.au", "google.co.in", "google.co.jp", "google.pl",
        "google.se", "google.at", "google.ie", "google.lu", "google.com.tr", "google.com.eg", "google.ae",
        "google.sn", "google.ci", "google.cm", "google.mg", "google.co.za", "google.com.mx", "google.com.ar",
    )

    val urlBlocklist: List<String> = buildList {
        add("google.com")
        // --- Google image / video / lens search, all ccTLDs handled by DNS-level host list below
        for (host in googleHosts) {
            add("$host/search?tbm=isch")
            add("$host/search?tbm=vid")
            add("$host/search?udm=2")       // "Images" tab
            add("$host/search?udm=7")       // "Videos" tab (numeric form)
            add("$host/search?udm=vid*")    // "Videos" tab (udm=vids, seen on Chrome mobile 2026)
            add("$host/search?udm=39")      // "Short videos" tab
            add("$host/search?udm=50")      // "AI Mode" tab (Gemini answers; the only AI allowed is Devin)
            add("$host/imgres")
            add("$host/images")
            add("$host/imghp")
            add("$host/videohp")
        }
        add("images.google.com")
        add("lens.google.com")
        // --- Bing
        add("bing.com/images")
        add("bing.com/chat")
        add("bing.com/copilotsearch")
        add("duckduckgo.com/?ia=chat")
        add("bing.com/videos")
        add("bing.com/visualsearch")
        // --- DuckDuckGo
        add("duckduckgo.com/?ia=images")
        add("duckduckgo.com/?iax=images")
        add("duckduckgo.com/?ia=videos")
        add("duckduckgo.com/?iax=videos")
        // --- Yandex / Yahoo / others
        add("yandex.com/images")
        add("yandex.ru/images")
        add("yandex.com/video")
        add("images.search.yahoo.com")
        add("video.search.yahoo.com")
        add("search.brave.com/images")
        add("search.brave.com/videos")
        add("ecosia.org/images")
        add("ecosia.org/videos")
        add("qwant.com/?t=images")
        add("qwant.com/?t=videos")
        add("startpage.com/sp/search?cat=images")
        add("startpage.com/sp/search?cat=video")
        add("swisscows.com/image")
        add("swisscows.com/video")
        add("presearch.com/search?type=images")
        // --- Chrome internals that could relax filtering
        add("chrome://flags")
        add("chrome://net-internals")
        add("chrome://settings/security")
        // --- web proxies / translation-as-proxy
        add("translate.google.com/translate")
        add("translate.goog")
        add("cache.googleusercontent.com")
        add("webcache.googleusercontent.com")
        add("web.archive.org/web")
        add("croxyproxy.com")
        add("hide.me/en/proxy")
        add("proxysite.com")
        add("kproxy.com")
        add("hidester.com")
        add("4everproxy.com")
        add("blockaway.net")
        add("plainproxies.com")
    }

    /** The Google services reopened inside the closed google.com family (longer host beats the bare block). */
    val urlAllowlist: List<String> = listOf(
        "accounts.google.com",
        "myaccount.google.com",
        "mail.google.com",
        "contacts.google.com",
        "calendar.google.com",
        "meet.google.com",
        "docs.google.com",
        "drive.google.com",
        "classroom.google.com",
        "translate.google.com/",
        "www.google.com/recaptcha",
    )
}
