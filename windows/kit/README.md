# FocusGuard for Windows

Blocker-only clone of the phone app: same domain lists, same categories, no task planner. Once
installed, **you are no longer administrator of your own laptop** until the unlock date; the only
administrator is a local account whose random password nobody knows and which is revealed by the
machine itself when the date is reached.

## Two filter modes

**Allow-only (default).** A name resolves only if it is in `allowlist.txt` (or a subdomain of an
entry, or a name granted by `allow.cmd` after its waiting time) *and* no block rule matches it.
Everything else answers 0.0.0.0 — a site that does not exist yet, a web proxy nobody has listed,
a hosting platform (`*.vercel.app`, `*.github.io`, `*.pages.dev`…), a raw-IP session endpoint's
hostname. The same list is pushed into Chrome/Edge as `URLBlocklist = *` + `URLAllowlist`, so the
browser also refuses raw-IP URLs and anything the DNS layer never saw. The block rules below still
apply on top (they win over the allow list: `news.google.com` stays dead although `google.com` is
allowed). Adding a site is possible but slow on purpose: `allow.cmd name` → reachable **24 h later**,
refused outright if a block rule matches, at most 8 waiting at once. Nothing can shorten that.
`block.cmd name` removes a site immediately and for good.

**Blocklist** (`setup.ps1 -Mode blocklist`): the phone's behaviour, everything opens unless listed.
The mode is written once at setup; a later run may switch blocklist → allow-only, never back.

## What the block rules cover (both modes)

Same lists as the phone (`lists.txt` is generated from the Android source) plus the 7 downloaded
blocklists (~1.1 M domains, refreshed every 6 h) and one block-only list of our own (`extra-block.txt`,
see below):

- adult / sexual content, AI companions and uncensored generators, sexting and stranger-chat sites,
  voice/video chat-room apps (SUGO, Tigo, Hago, Yalla, Chamet, Litmatch…)
- social feeds (YouTube, TikTok, Instagram, X, Reddit, Snapchat web, Pinterest, Telegram web…),
  streaming and VOD (Netflix, Twitch, Forja, Shahid, TF1+, myCanal…), pirate streaming, anime,
  sports streams, torrents
- stock-photo / image banks / wallpapers (Getty, Shutterstock, Unsplash, Flickr…), Google/Bing/
  DuckDuckGo image tabs and search-result thumbnails, Windows-only extras: marketplace image CDNs
  (eBay/Amazon/AliExpress pictures), Etsy/Redbubble/Temu/Shein/Vinted…, PC game stores
- web proxies / unblockers (TrickyBird, CroxyProxy, Proxyium…), translate / cache / archive
  re-hosting, online "virtual PC" / remote browser / cloud gaming / remote desktop services, video
  and Instagram/TikTok downloaders, alternative frontends (Invidious, Piped, Nitter, SearXNG… ~280
  public instances)
- gambling, VPN / DoH / proxy services, and the programs: Firefox, Opera, Brave, Tor, Vivaldi, VPN
  clients, torrent clients, Steam/Epic/Riot/Battle.net/Roblox/Minecraft launchers, Android emulators

Stays: Chrome and Edge (with SafeSearch forced, no incognito, no Secure DNS, no proxy, no
extensions except Docs Offline/uBlock/Grammarly/Bitwarden). In blocklist mode also Wikipedia,
school portals, Google Docs/Drive/Gmail, ChatGPT/Gemini/Claude/Copilot, WhatsApp Web, Discord,
Slack, banks, transport, Microsoft/Office, GitHub, chess.com/lichess, VS Code and every program not
in the blocked list. In allow-only mode only what `allowlist.txt` names: Windows/Microsoft/Google
sign-in and updates, Office/OneDrive, Drive/Docs/Classroom/Meet/Gmail, WhatsApp Web, Wikipedia,
ChatGPT/Claude, Anki, Overleaf, Stack Exchange, Wolfram, GeoGebra, Khan Academy, arXiv, programming
docs, package registries, Codeforces/AtCoder/…, the French/Moroccan concours and education sites,
certificate and CDN infrastructure. Discord, Slack, chess.com/lichess, Spotify are *not* in it (the
phone's exemptions for them do not carry over; `allow.cmd` if you really need one) — **edit the
file and add your school portal and mail before setup** (`FocusGuardDns.exe test host1 host2 …`
shows what a name would get).

## How the lock works

| Layer | What | Why you cannot undo it |
|---|---|---|
| Standard user | Your account is removed from Administrators; the built-in Administrator is disabled. | Every change below needs admin. UAC asks for `FocusGuardAdmin`'s password, which does not exist in readable form. |
| Sealed admin | `FocusGuardAdmin` gets a 24-char random password, written to a SYSTEM-only file that the service encrypts with DPAPI (SYSTEM scope) and deletes. | Only SYSTEM can decrypt it; the service and a SYSTEM scheduled task write it to `C:\Program Files\FocusGuard\UNLOCKED - admin password.txt` once the UTC unlock date is reached. Standard users cannot change the clock (time zone only, which is ignored). |
| Local DNS filter | Windows service `FocusGuardDns` answers on 127.0.0.1:53, blocks by list/keyword, denies every name not on the allow list (allow-only mode), rewrites Google/Bing/DDG to SafeSearch, forwards the rest to CleanBrowsing Family over DoH (Cloudflare Families is the fallback). | Service ACL: only SYSTEM/Administrators may stop it; auto-restart on crash. If `allowlist.txt` is missing or damaged the service does not fall back to blocklist mode: it denies everything except the list sources. |
| DNS pinning | Every adapter is pointed at 127.0.0.1 / ::1 and re-pinned every minute (new adapters, USB tethering, new Wi-Fi included). | Adapter settings need admin. |
| Firewall | Outbound 53 (DNS), 853 (DoT/DoQ) and port 443 to the well-known public DoH resolvers (1.1.1.1, 8.8.8.8, 9.9.9.9, AdGuard, NextDNS, ControlD, OpenDNS, Mullvad, dns0…) are blocked. | Firewall needs admin. Result: the only DNS path on the box is the filter. |
| Browser policies | HKLM policies for Chrome, Edge, Chromium, Brave: SafeSearch, YouTube restricted, incognito/guest off, DoH off, proxy = direct, QUIC off, DevTools off, extension allow-list; in allow-only mode `URLBlocklist` = `*` + the block rules and `URLAllowlist` = the allow list (so `http://1.2.3.4/` and `chrome://flags` are refused too). System proxy is machine-wide and locked. | HKLM\Software\Policies is admin-only and honoured on Home too (no Group Policy needed). Chrome reads at most 1000 allow entries; the service logs a warning past that. |
| Blocked programs | Image File Execution Options: launching `firefox.exe`, `opera.exe`, `steam.exe`, `nordvpn.exe`, `qbittorrent.exe`… opens a "blocked by FocusGuard" box instead. | Admin-only registry. |
| Recovery menu | WinRE disabled (`reagentc /disable`), F8/auto-repair menu off: no "Réinitialiser ce PC" from the login screen. Settings > Récupération asks for admin. | Admin-only. |
| Re-apply loop | The service re-checks all of the above every 60 s and logs what it had to fix. | — |

## Install (10 minutes)

Prerequisites: Windows 10 (1809+) or 11, Home or Pro; you are currently administrator; internet on.
Back up your files first (OneDrive / Drive / USB) — the only early exit is a full reinstall.

1. Extract the kit zip somewhere, e.g. `Téléchargements\FocusGuard-Windows`.
   Open `allowlist.txt` in Notepad and add your school portal, school mail and any site you need
   for work, one per line (subdomains are implied). Everything not in this file will be dead.
2. Double-click `setup.cmd`. Accept the administrator prompt.
   From a PowerShell in the folder instead (script execution is disabled by default on Windows,
   hence `-ExecutionPolicy Bypass`):
   `powershell -ExecutionPolicy Bypass -File .\setup.ps1 -Days 14`
   (or `-Until "2026-10-02 18:00"`, or `-Days 1` for a trial).
3. Read the summary, type `I COMMIT`.
4. The script checks everything (edition, port 53, filter self-test), installs the service, verifies
   that `wikipedia.org` resolves and `forja.ma` / `youtube.com` return 0.0.0.0 (allow-only: also
   that `example.com` and unknown names return 0.0.0.0 while Microsoft/Google sign-in resolve),
   applies the lock, downloads the blocklists, then removes your administrator rights. Every line
   ends in `ok`.
5. **Sign out and sign back in** (your session still carries the old admin token), restart Chrome/Edge.
6. Check: Wikipedia loads; youtube.com and forja.ma do not; Google Images shows nothing; Firefox
   shows "blocked by FocusGuard"; `Paramètres > Comptes` shows your account as *Utilisateur standard*.

Status any time (no admin needed): `"C:\Program Files\FocusGuard\FocusGuardDns.exe" status`,
log in `C:\Program Files\FocusGuard\state\focusguard.log`.

**Locking for longer** needs no password, any time before the date: double-click
`C:\Program Files\FocusGuard\extend.cmd` (or `extend.cmd -Until "2026-10-02 18:00"`). The
service accepts only a *later* date — nobody, admin or not, can shorten it.

**A site you need does not open** (allow-only mode): double-click
`C:\Program Files\FocusGuard\allow.cmd`, type the name. The service answers within seconds: either
*refused* (a block rule matches — final) or *allowed from <date>*, 24 hours later. No admin, no
way to make it sooner, at most 8 names waiting. `block.cmd` does the opposite, immediately and
permanently (type `I COMMIT`).

Updating the kit later (new lists, a new `allowlist.txt`) needs an administrator → only after the
date, by re-running `setup.ps1` from the new kit: it detects the installation and only replaces
the files.

## When the date comes

Within a minute, `C:\Program Files\FocusGuard\UNLOCKED - admin password.txt` appears (two
independent writers: the service, and a SYSTEM scheduled task that retries hourly). Then:

- **Keep it**: do nothing; everything stays blocked.
- **Recommit**: from any account, run `setup.cmd -Until "..."` (or `-Days N`) from the kit and
  enter `FocusGuardAdmin` + the revealed password in the UAC box: the old service is replaced
  in place with a new random password and the new date, files/lists kept, nothing to remove first.
  (Before the date, extending needs no password at all: `extend.cmd`.)
- **Remove**: sign out → sign in as `FocusGuardAdmin` → double-click
  `C:\Program Files\FocusGuard\remove.cmd` → restart. Your account is
  administrator again, Firefox/Steam/etc. work, browsers back to defaults.

## Honest limitations (the same kind as on the phone)

- **Reinstalling Windows from a USB stick wipes everything, including the lock** (~2 h + all your
  files if not backed up). Closing it needs a BIOS/UEFI supervisor password and disabling USB boot,
  which no script can set: do it by hand in the BIOS (F2/Del at boot) and give the password the same
  treatment (random, written down, sealed in an envelope somewhere inconvenient). BitLocker (Pro) or
  Device Encryption (Home, with a Microsoft account) makes the offline `C:` drive unreadable too.
- The DNS filter decides by **name**. A program that talks to a raw IP (a personal SSH/SOCKS tunnel,
  a VPN configured by IP with its client renamed) bypasses it. Known VPN/DoH/proxy domains and the
  public DoH resolver IPs are blocked, executables by name are blocked, but renaming a portable
  browser defeats the name block (the DNS filter still applies to it; only incognito/proxy freedom
  is regained).
- Programs installed **per-user** (portable apps, `%LOCALAPPDATA%`) need no admin. They are still
  behind the DNS filter; game/launcher binaries keep their names and stay blocked.
- Sites are blocked by domain, so a page on an allowed host (e.g. a listing on eBay, a Reddit
  mirror on an unknown domain, an image inside a Google Doc) is not caught. A **web proxy** (a site
  that fetches the blocked page for you and serves it under its own name), an **online virtual PC /
  remote browser** or a **translate / cache / archive** view is the same hole one level up: the
  filter only ever sees the proxy's own host. In blocklist mode three layers fight it — the
  built-in list (~500 such hosts), the upstream resolver's proxy category, the downloaded HaGeZi
  bypass list — but a brand-new proxy site slips through all three until it is listed. **Allow-only
  mode closes this class**: the proxy's own host is not on the allow list, so it never resolves,
  and the browser refuses its raw-IP session URL. What allow-only cannot close: a proxy *hosted on
  an allowed name* (a Google Apps Script, a Cloudflare Worker on an allowed zone, a Colab notebook,
  a page inside an allowed site that embeds outside content through the allowed site's own
  servers). The obvious ones (`script.google.com`, `*.workers.dev`, Colab, translate/cache) are
  block rules; the rest is a list you keep tightening with `block.cmd`.
- Reporting a hole: the service also downloads `extra-block.txt` (the `[optionalSources]` entry,
  every 6 h). Anything added there is blocked on the next refresh without touching the laptop, and
  is *ratcheted*: a name seen once stays blocked even if later removed from the file, so this
  channel can only tighten. If the file is unreachable the other lists are unaffected.
- If a site you genuinely need turns out blocked by a **block rule**, there is no fix before the
  date (no admin). In allow-only mode a site that is merely *not listed* can be added with
  `allow.cmd`, effective 24 h later — so the first days will be a stream of small waits (a school
  portal's CDN, a PDF host, a captcha provider); `allowlist.txt` ships with the common
  infrastructure, but test what you need before committing (`FocusGuardDns.exe test host1 host2 …`).
- Allow-only entries are parent domains: `google.com` allows every `*.google.com`, including
  surfaces that behave like a feed or a fetcher. The ones known to matter are block rules
  (`news.google.com`, `script.google.com`, Colab, Google Sites…); anything found later goes
  into `block.cmd` or `windows/extra_lists.txt`.
- A bug that kills the service leaves you without DNS (fail-closed). Windows restarts it (3 tries),
  and the release task still delivers the password at the date. Before the date the fallback is the
  reinstall exit.
- Windows in **S mode** cannot run the service (setup refuses). Windows Home and Pro both work.
- Time: standard users cannot change the clock; the unlock date is UTC so changing the time zone
  does nothing. A network that spoofs NTP could in theory move the clock — not a realistic exit.

## Files

| File | Role |
|---|---|
| `setup.ps1` / `setup.cmd` | one-shot installer / updater (admin); the .cmd only avoids the execution-policy error |
| `remove.ps1` | complete uninstaller (admin, i.e. `FocusGuardAdmin` before the date) |
| `release.ps1` | SYSTEM scheduled task: second writer of the password file at the date |
| `extend.ps1` / `extend.cmd` | push the date later as a standard user (drop box `extend\request.txt`, only later dates accepted) |
| `allow.ps1` / `allow.cmd` | standard user asks for a name (drop box `extend\allow-request.txt`); granted 24 h later unless a block rule matches; state in `state\allow-pending.txt` / `allow-granted.txt` |
| `block.ps1` / `block.cmd` | standard user blocks a name for good (drop box `extend\block-request.txt`); state in `state\user-block.txt`, applied within 15 s |
| `allowlist.txt` | allow-only mode: the names that resolve (parent domains, subdomains implied); read at service start, copied at setup, admin-only afterwards |
| `FocusGuardDns.cs` → `FocusGuardDns.exe` | service: resolver + enforcer + time lock + request drop boxes; also `test`, `selftest`, `status`, `update-lists`, `enforce-once`, `console` |
| `state\mode.txt` | `allowonly` or absent (blocklist); written by setup, never loosened |
| `FocusGuardBlocked.cs` → `blocked.exe` | the "blocked by FocusGuard" box launched instead of blocked programs |
| `lists.txt` | rules, generated by `windows/export_lists.py` from the phone's `BuiltinLists.kt` + `windows/extra_lists.txt` |

The kit ships **source only**: browsers/antivirus flag unsigned downloaded executables, and you
should not have to trust a binary that takes your admin rights. `setup.ps1` compiles the two
`.cs` files with the C# compiler that is part of the .NET Framework on every Windows 10/11
(`C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe`); nothing is downloaded. By hand:
`csc /out:FocusGuardDns.exe /r:System.ServiceProcess.dll /r:System.Security.dll FocusGuardDns.cs`
and `csc /target:winexe /out:blocked.exe /r:System.Windows.Forms.dll FocusGuardBlocked.cs`.
(`windows/build.sh` does the same with Mono against the .NET 4.8 reference assemblies, for testing.)
