# 29 — The background engine in one tap (plan for alpha.45)

**Status:** plan, waiting for the owner's green light. It follows plan 28 (alpha.44, background that stays working).
Parallel sessions move to alpha.46 and Drive to alpha.47–48.

**The owner's ask:** "Deep dive into how we can make this way more user friendly and stable, like Google would design
it for billions. The install of the background engine should be one click, or even be installed from the get go."

---

## 1. Why setup is hard today

Background screens need a process that runs as Android's `shell` user: only that user may create a trusted private
display and move apps onto it. Today that process comes from **Shizuku**, a second app. Setting it up takes:

1. Download and install Shizuku (allow installs from Cyclone).
2. Turn on Developer options (tap Build number 7 times, enter your PIN).
3. Turn on Wireless debugging (on Wi-Fi) and allow the network.
4. In Shizuku, pair: switch between Settings and a notification to type a 6-digit code.
5. Tap Start in Shizuku, then allow Cyclone in Shizuku.
6. **After every restart:** open Shizuku and tap Start again (unless the PC's *Keep background work on* was used).

That is six screens in two apps, words like "debugging", and a step that silently undoes itself at every restart.
Almost nobody gets through it, and those who do lose it without noticing. Plan 26 made the failures visible; this
plan removes the setup itself.

## 2. How Google would design it

1. **No second app, no jargon.** The owner turns on one thing called *Background work*. The engine is part of Cyclone.
   The words "Shizuku", "ADB" and "debugging" never appear, except in an optional "How it works".
2. **Cyclone does the clicking.** Cyclone already holds Accessibility and already drives Settings pages in its own
   missions. The setup is a mission: Cyclone opens the right pages, finds the switches (with its maps, not hard-coded
   coordinates) and turns them on while the owner watches. The owner does only what Android reserves for them: type
   their PIN once and tap Allow once.
3. **Set up once, then silent forever.** After the first start, the engine grants Cyclone the one permission it needs
   to restart itself (`WRITE_SECURE_SETTINGS`). After a restart it starts by itself, with no taps and no notification,
   as long as the phone is on Wi-Fi. If it can't (no Wi-Fi yet), it waits for Wi-Fi and then starts.
4. **The smallest possible exposure.** Wireless debugging is on only for the seconds it takes to start the engine,
   then Cyclone turns it off again. Developer options can go off too, if the phone allows it (to be measured; see
   §5). This matters because some banking apps refuse to run while Developer options are on.
5. **Self-healing, not "it broke".** The engine watches the pieces Cyclone depends on and repairs what it can: it
   re-enables Cyclone's Accessibility service after Android turns it off, keeps Cyclone off battery restrictions, and
   restarts itself. The Background Check (plan 28) proves the result and names what it could not fix.
6. **Offered when it is useful.** First run offers it on one screen ("Let Cyclone work behind your screen"). If the
   owner skips it, the offer returns once, at the moment a task would have run in the background, with one tap.
7. **Tiers, so nothing depends on setup.** Tier 0 (no screen: intents, notification replies) and the owner's screen
   always work. The engine is an upgrade, never a requirement.
8. **Owner in control.** One switch turns it all off: the engine stops, and Wireless debugging and the extra
   permissions are reverted. Nothing runs that the owner can't see in Settings → Background work.

## 3. What "installed from the get go" can mean

| Option | What it takes | Verdict |
|---|---|---|
| **A. Built into Cyclone, one tap after install** | Cyclone ships its own engine starter (below). First run: *Turn on*, then PIN + Allow. | **Build this (alpha.45).** It works on every Android 11+ phone with Wi-Fi, today, with no PC and no second app. |
| B. One cable command from a PC | `adb install` plus 3 grants in one script; the engine starts from the PC once. | Keep as the power-user path (documented, no companion rebuild). |
| C. Preinstalled as a privileged app | An OEM or carrier ships Cyclone in `/system/priv-app` with the display and input permissions. The engine is then Cyclone itself, with zero setup. | The real "from the get go". It needs a manufacturer partner, so it is not reachable for a sideloaded app. The `PlanePort` design already allows swapping it in. |
| D. Root | If the phone is rooted, start the engine with `su`. | A free extra: detect it and use it. |
| E. Platform agent APIs | Android's own agent and app-functions work. | Watch it and adopt it when a third-party app is allowed to use it; not usable today. |

**Recommendation: A now, with B and D as extras. C is the long-term goal that needs a partner.**

## 4. The design (option A)

**Cyclone Engine: Cyclone's own shell process, replacing Shizuku.**

1. **Pair (first time only).**
   - Cyclone includes a wireless-ADB client (TLS and SPAKE2 pairing), as Shizuku and other open-source apps do.
   - It finds the phone's own pairing service on `localhost` through Android's network discovery.
   - It reads the 6-digit code from the Settings pairing dialog with its own Accessibility, in place, so the dialog
     never has to be left and nothing needs typing.
   - The code is used once and never stored. The resulting key is stored like other credentials: app-private,
     encrypted with the Android Keystore, and never in Brain, diagnostics or run records.
2. **Start.**
   - Cyclone connects to the phone's own ADB service with the stored key and runs one fixed command:
     `app_process` with Cyclone's own APK as the class path and `com.cyclone.mobile.engine.EngineMain`.
   - The engine runs as `shell` and hands its Binder to Cyclone. It accepts calls only from Cyclone's uid and
     signature, and checks the version on connect.
   - It hosts the existing `IWorkspaceService` unchanged, so everything alpha.40–44 built keeps working.
3. **Grant once.** The engine grants Cyclone:
   - `WRITE_SECURE_SETTINGS` (restart after reboot, and re-enable Accessibility);
   - the battery exemption (`deviceidle whitelist`);
   - on Android 13+ sideloads, the restricted-settings app-op that lets Accessibility be switched on.

   Each grant is a fixed, typed command; no generic shell ever reaches the model.
4. **Close the door.** Right after the start, Cyclone turns Wireless debugging off (`adb_wifi_enabled 0`). The engine
   keeps running.
5. **After a restart.** On boot, Cyclone waits for Wi-Fi, turns Wireless debugging on, connects with the stored key
   (no pairing), starts the engine and turns Wireless debugging off. This takes a few seconds, with no notification
   unless it fails. With no Wi-Fi for 10 minutes it says once: "Background work starts when you're on Wi-Fi."
6. **Heal.** Every 15 minutes, and on any engine death:
   - restart the engine the same way;
   - if Android turned Cyclone's Accessibility off, turn it back on through secure settings (only when the owner had
     it on; never newly);
   - rerun the Background Check after any repair.
7. **Turn off.** Stop the engine, turn Wireless debugging off, revoke what the engine granted, and forget the ADB key.
8. **Existing Shizuku users.** When the engine is available, Cyclone uses it; Shizuku stays a fallback until
   alpha.47, then the Shizuku path is removed.

**The owner's whole setup (first run, about 30 seconds):**
1. *Let Cyclone work behind your screen?* → **Turn on**.
2. Cyclone opens Settings and taps Build number (a progress line on the overlay says what it's doing).
3. Android asks for your PIN. **You type it.** Cyclone never reads or stores it: the PIN screen is a secure field and
   the Hands rules already stop Cyclone typing there.
4. Cyclone turns on Wireless debugging. Android asks *Allow on this network?* **You tap Allow** (a consent Android
   reserves for you).
5. Cyclone opens *Pair with code*, reads the code, pairs, starts the engine, closes the door, and returns to Cyclone:
   **"Background work is on."** The Background Check runs and shows ✓.

If a phone's Settings look different (Samsung, Xiaomi), the same mission uses Cyclone's app maps. If a step can't be
found, the overlay points at where to tap instead of failing.

## 5. What must be measured on the Pixel first (Phase 0)

These decide details, not direction, and none can be known from code:

| Question | Why it matters | Fallback |
|---|---|---|
| Does the engine keep running after Wireless debugging is turned off? | The "close the door" step | Leave Wireless debugging on, paired devices only (still no taps) |
| After Developer options are turned off? | Banking apps that refuse Developer options | Leave Developer options on and tell the owner which app complained |
| Is the pairing code readable by Accessibility? | Tap-free pairing | Cyclone shows its own field over the dialog and the owner types the 6 digits |
| Can `adb_wifi_enabled` be set by Cyclone after `WRITE_SECURE_SETTINGS`, and does adbd accept the stored key at boot without asking? | Zero-tap restarts | One tap: "Resume background work" |
| Does re-enabling Accessibility through secure settings bind the service on this Android version? | Self-healing | Tell the owner with one tap |

Phase 0 is a hidden Lab build step on the owner's phone, and each result is recorded in this plan.

## 6. Work items (alpha.45)

| # | Item | Where |
|---|---|---|
| E0 | Phase 0 measurements | Lab, the owner's Pixel |
| E1 | `engine/`: `EngineMain` (the `app_process` entry), Binder handoff through a Cyclone provider, uid and signature check, version handshake, `IWorkspaceService` hosted inside | `apps/mobile/.../engine/**` |
| E2 | Wireless ADB client: mDNS discovery, SPAKE2 pairing, TLS connect, key in the Keystore, the one fixed start command | `engine/adb/**` (reused open-source code, with licences recorded in `docs/OPEN_SOURCE_COMPONENTS.md`) |
| E3 | Setup mission: Developer options, Wireless debugging, pair, start, close the door, all driven by Accessibility with maps; PIN and Allow left to the owner | `engine/setup/**`, onboarding screen |
| E4 | Grants, boot start (Wi-Fi wait), heal loop, Turn off with a full revert | `engine/**`, `BackgroundWatch` |
| E5 | `WorkspaceRuntime` binds the engine first and Shizuku second; capability and Check steps renamed to *Background engine* | `runtime/background/**`, `runtime/plane/**` |
| E6 | Tests: a pure setup state machine, pure decision tables for boot, heal and revert, a CI guard (no generic shell; only the fixed commands; the key never in diagnostics) | tests, `scripts/ci/tests` |
| E7 | Docs, versions, release | plan, notes |

**Exit (Pixel):**
- From a fresh install to "Background work is on" in ≤ 60 s, with at most PIN + Allow.
- 5 restarts on Wi-Fi: the engine is ready within 60 s every time, with zero taps.
- The Background Check passes after each restart.
- Turning it off reverts everything.

## 7. Invariants kept

- **PhoneToolExecutor:** it stays the only way to change the phone. The engine offers only typed display, launch and
  input operations, as today.
- **No generic shell or ADB for the model.** The engine starts with one fixed command; grants are a fixed list.
- **Never read or stored:** the owner's PIN, and the pairing code (read once in place, never kept). The ADB key never
  leaves Keystore-encrypted app storage.
- **Owner's consents:** Android's own consents (PIN, Allow on this network) stay the owner's taps.
- **Approval boundaries:** pay, send and delete still ask the owner, on every tier.

## 8. Honest limits

- **Wi-Fi:** Android only offers Wireless debugging on Wi-Fi, so setup and restarts need Wi-Fi. On mobile data the
  engine starts when Wi-Fi returns.
- **Truly preinstalled** (option C) needs a manufacturer. Everything else gets as close as Android allows for a
  sideloaded app: one tap, a PIN, an Allow, once.
- **Android updates:** a major Android update may change these rules. The Background Check is how Cyclone notices,
  and Shizuku is kept as a fallback for the first releases.
