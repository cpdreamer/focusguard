# FocusGuard

One local Android app (Galaxy A33 / Android 12+) that combines:

- a **homework scheduler** — you enter tasks (deadline, estimate, importance, first two-minute action) and your fixed timetable (classes, meals, sleep, sport); it fills free slots and shows **one** task at each slot start, from the background, replanning after Done / Skip / Slipped / new tasks;
- an **unbypassable app blocker** — Device Owner mode suspends distraction apps (YouTube, TikTok, Instagram, Reddit, X, Twitch, Netflix, other browsers, games), disables safe mode, uninstall, user switching, unknown-source installs, other VPNs and Private DNS;
- a **DNS filtering VPN** — always-on, forwards to family-filtering resolvers (Cloudflare Families, CleanBrowsing) plus built-in and downloaded adult/soft-adult/social blocklists and a domain-keyword heuristic; SafeSearch forced; YouTube media hosts blocked (so Discord Watch Together shows no video);
- a **managed Chrome policy** — incognito off, Secure DNS off, Google/Bing/DDG/Yandex image and video search pages blocked by URL pattern, Google Lens, translate/cache/archive proxies blocked;
- a **14-day commitment** — nothing can be relaxed from the phone until it ends; then the app offers Renew 14 days / adjust rules / release phone (renewal repeatable).

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
2. Open **FocusGuard**. The setup screen shows "Device owner: active". Set bedtime / wake / evening planning time, review the app list (what will be blocked), accept the VPN prompt, then confirm **Commit for 14 days**.
3. Enter your timetable (Menu → Timetable) and your homework (+). Close the app; it works from the background.

Optional: reinstall FocusGuard later without losing anything (`adb install -r`); Device Owner survives updates.

---

## 2. Daily use

- A full-screen card appears at the start of each free slot: task, first action, **Start / Done / Skip slot / Slipped**. Nothing repeats every 10 minutes; it re-evaluates at slot boundaries and when you act.
- **Slipped**: one optional line, then a 10-minute reset; the next block counts.
- Evening planning prompt at the configured time; Sunday evening weekly review.
- Main screen: current task, commitment status, streak / minutes this week, tasks, tomorrow.

---

## 3. When the 14 days end

A notification says "14 days done". **Blocking stays on** until you decide. Menu → **Renew 14 days** (repeatable), **Settings** (adjust rules), or **Release phone**. During an active commitment those menu entries are hidden.

---

## 4. Recovery

### 4.1 ADB release (intended exit, phone stays intact)

Needs a computer, USB cable, the phone unlocked and USB debugging enabled:

```bash
adb shell am broadcast -a com.focusguard.RELEASE -n com.focusguard/.core.AdbReleaseReceiver
```

Within seconds: Device Owner is dropped, all apps unsuspended, restrictions and VPN removed. The app becomes an ordinary app you can uninstall. This is protected by the `DUMP` permission, so no app on the phone can trigger it.

### 4.2 Factory reset (last resort)

Settings → General management → Reset → Factory data reset also removes Device Owner. You lose local data again (hence the backup discipline above).

### 4.3 Things that always work during a commitment

Emergency dialer, phone, SMS, WhatsApp, Discord, Slack, alarm/clock, camera, Settings (except the restricted items), Play Store (installs are allowed but new apps are suspended unless allow-listed).

---

## 5. Known limitations (honest list)

- **Content classification** is by domain, not by page content. Soft-adult material *inside* an allowed service (a Discord server, a WhatsApp group, Slack) is not filtered. Sites that host it wholesale (Reddit, X, Instagram, Tumblr, Pinterest, Imgur, Twitch, …) are blocked outright instead.
- **Google**: text search works with SafeSearch forced; Images / Videos / Short videos / Lens tabs are blocked by Chrome URL policy. Google's result page may still show small thumbnails in text results.
- **DNS-only VPN**: HTTPS traffic is not inspected (deliberately — no TLS interception). If the VPN process is restarted by the system there may be a few seconds of unfiltered DNS before Android brings it back; lockdown mode is not used because it would cut all traffic for a DNS-only tunnel.
- **Blocklists** are downloaded periodically; a brand-new mirror domain may take up to a day to be listed (the keyword heuristic catches most obvious names).
- Apps with their own hard-coded DNS-over-HTTPS may resolve around the filter; the major ones (Chrome, Android) are forced off via policy.
- The 14-day lock is against *impulse*, not against a determined person with a laptop and ten minutes (ADB release). That is by design.
- Tested on an Android 14 emulator provisioned as Device Owner; Samsung One UI may show additional confirmation dialogs at provisioning.

---

## 6. Development

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
