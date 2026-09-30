# FocusGuard

One local Android app (Galaxy A33 / Android 12+) that combines:

- a **homework scheduler** — you enter tasks (deadline, estimate, importance, first two-minute action) and your fixed timetable (classes, meals, sleep, sport); it fills free slots and shows **one** task at each slot start, from the background, replanning after Done / Skip / Slipped / new tasks;
- an **unbypassable app blocker** — Device Owner mode suspends distraction apps (YouTube, TikTok, Instagram, Reddit, X, Twitch, Netflix, Telegram, Pinterest/Tumblr/Imgur/9GAG, dating, live-streaming, manga/fan-fiction readers, Samsung Free/TV Plus, other browsers, games, torrent clients and pirate media centres, gambling/betting, game launchers, shopping-feed and news-feed apps, VPN/proxy tools, AI companion / roleplay / "uncensored" generator apps (Character.AI, Replika, Chai…; ChatGPT, Gemini, Claude, Copilot, Perplexity, Le Chat stay allowed); anything installed after activation is suspended unless it is a known-safe type), disables safe mode, the Settings factory-reset entry, uninstall, user switching, unknown-source installs, other VPNs and Private DNS;
- a **DNS filtering VPN** — always-on, forwards to CleanBrowsing Family (proxy/anonymizer category blocked upstream; Cloudflare Families as fallback) plus built-in and downloaded blocklists (adult/soft-adult, anti-piracy streaming and torrents, gambling, DoH/VPN/proxy bypass — seven maintained sources, ~1.1M domains, refreshed every 6 h, plus our own block-only `lists/extra-block.txt`), web proxies / unblockers / translate-cache-archive re-hosting / remote browsers / cloud PCs / downloaders / alternative frontends (~500 hosts), built-in names for social, pirate movie/anime/sports streaming, torrent, gambling, browser-game portals, meme/gossip feeds, image boards, shopping feeds and AI companion / NSFW image-video generator / deepfake sites (Character.AI, Janitor AI, CrushOn, Candy.ai, Civitai, Perchance, nudify/undress tools…), and a domain-keyword heuristic for unknown mirrors; SafeSearch forced; YouTube media hosts blocked (so Discord Watch Together shows no video);
- a **managed Chrome policy** — incognito off, Secure DNS off, Google/Bing/DDG/Yandex image and video search pages blocked by URL pattern, Google Lens, translate/cache/archive proxies blocked;
- a **commitment of a chosen length** (3 / 7 / 14 / 30 days, picked at every commit and renewal — start short, then longer) — nothing can be relaxed from the phone until it ends; then the app offers Renew / adjust rules / release phone.

### v4: allow-only web, tighten any time, loosen only in the morning window

Same rules as the Windows kit (`windows/kit/README.md`), enforced on the phone:

| | when | how |
|---|---|---|
| `block X` | now, any hour | blocklist grows; never shrinks; beats every allow, even a later one |
| `unallow X` | now | X and its subdomains stop resolving (removed from the allowlist) |
| `blockapp com.pkg` | now | app suspended; no directive un-suspends it |
| `allow X` | first window start ≥ 8 h after the request | queued, lands automatically at 06:00; refused if any block rule matches X |
| `cancel X` | now | drops a waiting `allow` |

- **Allow-only web**: only `app/src/main/assets/allowlist.txt` (+ blocklist sources, + granted names) resolves; everything else, including sites that do not exist yet, is denied. Block rules (built-in, downloaded, keyword, user) are checked *before* the allowlist.
- **Apps frozen**: what is allowed on commitment day stays allowed until renewal; anything installed later is suspended; there is no `allowapp`, in or out of the window.
- **Window**: `06:00–08:00`, local offset frozen at commitment start (clock changes are blocked anyway). Its width is capped at 4 h and it cannot cross midnight. Nothing loosens outside it.
- **Channels**: the blocking screen accepts a bare host (`= block`) or a directive line; the phone also pulls `config/phone.txt` from this repo every 15 min and applies each new line once (the same tightening/loosening rules apply to remote lines — the channel cannot open anything the window would not).
- **End**: the length you picked (3 days first, then a week, …); after that, renew (rules carried over, blocklist and unallow state kept) or release. `release`, `unlock`, `extend`, `mode`, `window`, `unblock*` are not directives and are refused.
- **Not blockable by any app**: a factory reset from the recovery menu (phone off + buttons). That is the phone's equivalent of a USB reinstall.

Always allowed: phone, SMS, contacts, WhatsApp, Discord, Slack, chess apps, mail, calendar, clock/alarm, calculator, camera, gallery, files, notes, Settings, Play Store, Chrome (filtered), music.

No root, no bootloader unlock, no custom ROM, no TLS interception. Runs fully offline except for DNS forwarding and blocklist downloads. It never reads or uploads messages or content.

---

## 1. Install (one-time, ~1 hour)

Device Owner can only be set on a phone with **no accounts**. That means one factory reset. Do not skip the backup checks.

### 1.1 Backup — verify each line before resetting

- [ ] **Contacts**: Contacts app → menu → Manage contacts → check they are on the Google account (not "Phone"). Open contacts.google.com on the laptop and confirm the count.
- [ ] **Photos / videos / documents**: copy `DCIM`, `Pictures`, `Download`, `Documents` to the laptop over USB (Smart Switch or file explorer). Open a few files on the laptop.
- [ ] **WhatsApp**: WhatsApp → Settings → Chats → Chat backup → Back up (to Google Drive). Note the "Last backup" time.
- [ ] **Everything else** (apps, settings, SMS, call log, home screen): Settings → Accounts and backup → Smart Switch → External storage / laptop backup, **and** Settings → Accounts and backup → Back up data (Google). Confirm both say completed.
- [ ] **2FA apps / bank tokens**: export or note recovery codes; these do not survive a reset.
- [ ] Note your Samsung and Google account passwords; you'll need them to restore.

If any line cannot be verified, do not reset.

### 1.2 Enable ADB on the phone

Settings → About phone → Software information → tap **Build number** 7× → back → Developer options → **USB debugging** on.

### 1.3 Factory reset

Settings → General management → Reset → Factory data reset. The phone reboots to the welcome screen.

### 1.4 Provision — do NOT add any account yet

On the welcome screen: pick language, skip Wi-Fi if you can (or connect, but **skip** every "sign in" screen). Get to the home screen with zero accounts. Re-enable USB debugging (1.2). Plug in USB, accept the "Allow USB debugging" prompt, then on the laptop (with [platform-tools](https://developer.android.com/tools/releases/platform-tools) unzipped):

```bash
adb devices                                  # phone must be listed as "device"
adb install app-debug.apk
adb shell dpm set-device-owner com.focusguard/.policy.GuardAdminReceiver
```

Expected output: `Success: Device owner set to package com.focusguard/...`.

If you get `Not allowed to set the device owner because there are already some accounts on the device`, remove the account (Settings → Accounts) and retry.

### 1.5 Restore and set up

1. Add your Google and Samsung accounts, restore from Smart Switch / Google backup, reinstall WhatsApp and restore the chat backup.
2. Open **FocusGuard**. The setup screen shows "Device owner: active". Set bedtime / wake / evening planning time, review the app list (what will be blocked), accept the VPN prompt, then pick the length (3 days first, a week next…) and confirm **Commit**.
3. Enter your timetable (Menu → Timetable) and your homework (+). Close the app; it works from the background.

Optional: reinstall FocusGuard later without losing anything (`adb install -r`); Device Owner survives updates.

---

## 2. Daily use

- A full-screen card appears at the start of each free slot: task, first action, **Start / Done / Skip slot / Slipped**. Nothing repeats every 10 minutes; it re-evaluates at slot boundaries and when you act.
- **Slipped**: one optional line, then a 10-minute reset; the next block counts.
- Evening planning prompt at the configured time; Sunday evening weekly review.
- Main screen: current task, commitment status, streak / minutes this week, tasks, tomorrow.

---

## 3. When the commitment ends

A notification says "Commitment done". **Blocking stays on** until you decide. Menu → **Renew** (pick the length; repeatable), **Settings** (adjust rules), or **Release phone**. During an active commitment those menu entries are hidden.

---

## 4. Updating during a commitment

A new APK is installed in place, without releasing:

```bash
adb install -r FocusGuard.apk
```

Device Owner, restrictions, suspended apps, the commitment end date, tasks and timetable are all kept (verified on the emulator: same state before and after, service restarts by itself). Needs the same physical access as a release, so it adds no new exit.

---

## 5. Recovery

### 5.1 ADB release (intended exit, phone stays intact)

Needs a computer, USB cable, the phone unlocked and USB debugging enabled:

```bash
adb shell am broadcast -a com.focusguard.RELEASE -n com.focusguard/.core.AdbReleaseReceiver
```

Within seconds: Device Owner is dropped, all apps unsuspended, restrictions and VPN removed. The app becomes an ordinary app you can uninstall. This is protected by the `DUMP` permission, so no app on the phone can trigger it.

### 5.2 Factory reset (last resort)

The Settings reset entry is disabled during a commitment; a reset from the recovery menu (phone off → hold Volume Up + Power → *Wipe data/factory reset*) still works and also removes Device Owner. You lose local data again (hence the backup discipline above).

### 5.3 Things that always work during a commitment

Emergency dialer, phone, SMS, WhatsApp, Discord, Slack, alarm/clock, camera, Settings (except the restricted items), Play Store (installs are allowed but new apps are suspended unless allow-listed).

---

## 6. Known limitations (honest list)

- **Content classification** is by domain, not by page content. Soft-adult material *inside* an allowed service (a Discord server, a WhatsApp group, Slack) is not filtered. Sites that host it wholesale (Reddit, X, Instagram, Tumblr, Pinterest, Imgur, Twitch, …) are blocked outright instead.
- **Google**: text search works with SafeSearch forced; Images / Videos / Short videos / Lens tabs are blocked by Chrome URL policy. Google's result page may still show small thumbnails in text results.
- **DNS-only VPN**: HTTPS traffic is not inspected (deliberately — no TLS interception). If the VPN process is restarted by the system there may be a few seconds of unfiltered DNS before Android brings it back; lockdown mode is not used because it would cut all traffic for a DNS-only tunnel.
- **Blocklists** (~1.1M domains, seven sources) are downloaded on first start and refreshed every 6 h; a brand-new mirror domain may take a while to be listed (the keyword heuristic catches most obvious names). `lists/extra-block.txt` in this repo is fetched too: a hole found during a commitment is closed by adding the host there, no reinstall. Entries are ratcheted on the device (once seen, kept even if removed from the file), and a failed fetch leaves the other lists untouched.
- **Web proxies / remote browsers** (TrickyBird-style sites, online virtual PCs, translate/cache/archive views) fetch the blocked page server-side, so a DNS filter only sees the proxy's own host. Covered by the built-in list, the upstream proxy category and the HaGeZi bypass list — a brand-new one can still slip through until listed. Only a default-deny allowlist closes this class structurally.
- Apps with their own hard-coded DNS-over-HTTPS may resolve around the filter; the major ones (Chrome, Android) are forced off via policy.
- The 7-day lock is against *impulse*, not against a determined person with a laptop and ten minutes (ADB release). That is by design.
- Tested on an Android 14 emulator provisioned as Device Owner; Samsung One UI may show additional confirmation dialogs at provisioning.

---

## 7. Development

```bash
ANDROID_HOME=$HOME/android-sdk gradle test lint assembleDebug
```

Kotlin, minSdk 31, targetSdk 34, Room, Material 3, no third-party runtime dependencies beyond AndroidX/Kotlin.

- `core/` — `Coordinator` (planner + enforcement brain), `Reminders`, `GuardService`, receivers, `AdbReleaseReceiver`
- `planner/` — slot computation and task allocation (pure, unit-tested)
- `policy/` — `PolicyController` (DevicePolicyManager), `AppPolicy` (package categorisation), `ChromePolicy`
- `net/` — `DnsFilterVpnService`, `DnsResolver`, `DomainFilter`, `BuiltinLists`, `BlocklistUpdater`
- `data/` — Room entities/DAOs, `Settings`
- `ui/` — Activities

Emulator validation checklist (all verified on API 34 `google_apis` image): Device Owner provisioning, restrictions, safe-boot off, package suspension + essentials untouched, Chrome managed policy, Google Images/Videos/Shorts blocked, YouTube/TikTok/Instagram/Reddit/adult domains blocked via DNS, Wikipedia/Codeforces/Discord/Spotify/Lichess allowed, always-on VPN, survival of reboot and process kill, task entry → full-screen reminder → Done crediting minutes/streak, Slipped reset, commitment expiry prompt, Renew, ADB release.
