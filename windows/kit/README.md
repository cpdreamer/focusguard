# FocusGuard for Windows

Blocker-only clone of the phone app: same domain lists, same categories, no task planner. Once
installed, **you are no longer administrator of your own laptop** until the unlock date; the only
administrator is a local account whose random password nobody knows and which is revealed by the
machine itself when the date is reached.

## What it blocks

Same lists as the phone (`lists.txt` is generated from the Android source) plus the 7 downloaded
blocklists (~1.1 M domains, refreshed daily):

- adult / sexual content, AI companions and uncensored generators, sexting and stranger-chat sites,
  voice/video chat-room apps (SUGO, Tigo, Hago, Yalla, Chamet, Litmatch…)
- social feeds (YouTube, TikTok, Instagram, X, Reddit, Snapchat web, Pinterest, Telegram web…),
  streaming and VOD (Netflix, Twitch, Forja, Shahid, TF1+, myCanal…), pirate streaming, anime,
  sports streams, torrents
- stock-photo / image banks / wallpapers (Getty, Shutterstock, Unsplash, Flickr…), Google/Bing/
  DuckDuckGo image tabs and search-result thumbnails, Windows-only extras: marketplace image CDNs
  (eBay/Amazon/AliExpress pictures), Etsy/Redbubble/Temu/Shein/Vinted…, PC game stores
- gambling, VPN / DoH / proxy services, and the programs: Firefox, Opera, Brave, Tor, Vivaldi, VPN
  clients, torrent clients, Steam/Epic/Riot/Battle.net/Roblox/Minecraft launchers, Android emulators

Stays: Chrome and Edge (with SafeSearch forced, no incognito, no Secure DNS, no proxy, no
extensions except Docs Offline/uBlock/Grammarly/Bitwarden), Wikipedia, school portals, Google
Docs/Drive/Gmail, ChatGPT/Gemini/Claude/Copilot, WhatsApp Web, Discord, Slack, banks, transport,
Microsoft/Office, GitHub, chess.com/lichess, VS Code and every program not in the blocked list.

## How the lock works

| Layer | What | Why you cannot undo it |
|---|---|---|
| Standard user | Your account is removed from Administrators; the built-in Administrator is disabled. | Every change below needs admin. UAC asks for `FocusGuardAdmin`'s password, which does not exist in readable form. |
| Sealed admin | `FocusGuardAdmin` gets a 24-char random password, written to a SYSTEM-only file that the service encrypts with DPAPI (SYSTEM scope) and deletes. | Only SYSTEM can decrypt it; the service and a SYSTEM scheduled task write it to `C:\Program Files\FocusGuard\UNLOCKED - admin password.txt` once the UTC unlock date is reached. Standard users cannot change the clock (time zone only, which is ignored). |
| Local DNS filter | Windows service `FocusGuardDns` answers on 127.0.0.1:53, blocks by list/keyword, rewrites Google/Bing/DDG to SafeSearch, forwards the rest to Cloudflare Families over DoH. | Service ACL: only SYSTEM/Administrators may stop it; auto-restart on crash. |
| DNS pinning | Every adapter is pointed at 127.0.0.1 / ::1 and re-pinned every minute (new adapters, USB tethering, new Wi-Fi included). | Adapter settings need admin. |
| Firewall | Outbound 53 (DNS), 853 (DoT/DoQ) and port 443 to the well-known public DoH resolvers (1.1.1.1, 8.8.8.8, 9.9.9.9, AdGuard, NextDNS, ControlD, OpenDNS, Mullvad, dns0…) are blocked. | Firewall needs admin. Result: the only DNS path on the box is the filter. |
| Browser policies | HKLM policies for Chrome, Edge, Chromium, Brave: SafeSearch, YouTube restricted, incognito/guest off, DoH off, proxy = direct, QUIC off, DevTools off, extension allow-list. System proxy is machine-wide and locked. | HKLM\Software\Policies is admin-only and honoured on Home too (no Group Policy needed). |
| Blocked programs | Image File Execution Options: launching `firefox.exe`, `opera.exe`, `steam.exe`, `nordvpn.exe`, `qbittorrent.exe`… opens a "blocked by FocusGuard" box instead. | Admin-only registry. |
| Recovery menu | WinRE disabled (`reagentc /disable`), F8/auto-repair menu off: no "Réinitialiser ce PC" from the login screen. Settings > Récupération asks for admin. | Admin-only. |
| Re-apply loop | The service re-checks all of the above every 60 s and logs what it had to fix. | — |

## Install (10 minutes)

Prerequisites: Windows 10 (1809+) or 11, Home or Pro; you are currently administrator; internet on.
Back up your files first (OneDrive / Drive / USB) — the only early exit is a full reinstall.

1. Extract the kit zip somewhere, e.g. `Téléchargements\FocusGuard-Windows`.
2. Double-click `setup.cmd`. Accept the administrator prompt.
   From a PowerShell in the folder instead (script execution is disabled by default on Windows,
   hence `-ExecutionPolicy Bypass`):
   `powershell -ExecutionPolicy Bypass -File .\setup.ps1 -Days 14`
   (or `-Until "2026-10-02 18:00"`, or `-Days 1` for a trial).
3. Read the summary, type `I COMMIT`.
4. The script checks everything (edition, port 53, filter self-test), installs the service, verifies
   that `wikipedia.org` resolves and `forja.ma` / `youtube.com` return 0.0.0.0, applies the lock,
   downloads the blocklists, then removes your administrator rights. Every line ends in `ok`.
5. **Sign out and sign back in** (your session still carries the old admin token), restart Chrome/Edge.
6. Check: Wikipedia loads; youtube.com and forja.ma do not; Google Images shows nothing; Firefox
   shows "blocked by FocusGuard"; `Paramètres > Comptes` shows your account as *Utilisateur standard*.

Status any time (no admin needed): `"C:\Program Files\FocusGuard\FocusGuardDns.exe" status`,
log in `C:\Program Files\FocusGuard\state\focusguard.log`.

**Locking for longer** needs no password, any time before the date: double-click
`C:\Program Files\FocusGuard\extend.cmd` (or `extend.cmd -Until "2026-10-02 18:00"`). The
service accepts only a *later* date — nobody, admin or not, can shorten it.

Updating the kit later (new lists) needs an administrator → only after the date, by re-running
`setup.ps1` from the new kit: it detects the installation and only replaces the files.

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
  mirror on an unknown domain, an image inside a Google Doc) is not caught; report the exact host
  and it goes into the next list update — on the laptop that is only possible after the date.
- If a site you genuinely need turns out blocked, **there is no fix before the date** (no admin).
  The allow list covers school, bank, transport, study, communication and Microsoft/Google/Apple
  infrastructure; test what you need before committing (`FocusGuardDns.exe test host1 host2 …`).
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
| `FocusGuardDns.cs` → `FocusGuardDns.exe` | service: resolver + enforcer + time lock; also `test`, `selftest`, `status`, `update-lists`, `enforce-once`, `console` |
| `FocusGuardBlocked.cs` → `blocked.exe` | the "blocked by FocusGuard" box launched instead of blocked programs |
| `lists.txt` | rules, generated by `windows/export_lists.py` from the phone's `BuiltinLists.kt` + `windows/extra_lists.txt` |

The kit ships **source only**: browsers/antivirus flag unsigned downloaded executables, and you
should not have to trust a binary that takes your admin rights. `setup.ps1` compiles the two
`.cs` files with the C# compiler that is part of the .NET Framework on every Windows 10/11
(`C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe`); nothing is downloaded. By hand:
`csc /out:FocusGuardDns.exe /r:System.ServiceProcess.dll /r:System.Security.dll FocusGuardDns.cs`
and `csc /target:winexe /out:blocked.exe /r:System.Windows.Forms.dll FocusGuardBlocked.cs`.
(`windows/build.sh` does the same with Mono against the .NET 4.8 reference assemblies, for testing.)
