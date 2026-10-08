# 56 · VMOS fleet: cloud phones that arrive ready to work

**Owner's goal (2026-10-08):**
- Adding a few VMOS Cloud phones to the fleet is easy.
- Each one arrives with Cyclone and our skills, so it can get straight to work.
- Each one stays connected over ADB.
- Skills stay in sync across the fleet.

This plan picks up plan 44 (cloud fleet). Run 1 of plan 44 (alpha.90) is built. Plan 44's runs 2–6 are re-ordered
here around the owner's priorities:
1. ready in one click;
2. skills in sync;
3. spin up new phones;
4. run the fleet.

## 0. Rules that do not move

- **VMOS is only the way in.** The VMOS OpenAPI opens remote ADB, installs, and powers phones on and off. Every action
  inside a phone is Cyclone's: `PhoneToolExecutor`, GATE, fresh observation, one screen-changing action per turn. This
  is already enforced by `vmos/architecture.py`: no VMOS-native taps, and no VMOS or ADB shell for the model.
- **No shell for the model.** The gateway uses VMOS's command API (`asyncCmd`) and ADB only through a fixed,
  read-back provisioning catalogue in code. No field anywhere takes a command, argv or URL from a model, MCP or PC
  agent.
- **Money and wiping are the owner's.**
  - Buying or renewing a phone, "one-key new device" and reset are owner-only buttons in Glass, with a confirm.
  - They are never tools for an agent or MCP. Buying counts as GATE pay; a wipe counts as GATE delete.
- **Secrets stay in place.**
  - The VMOS keys stay in the DPAPI vault (built).
  - Skill packs never carry passwords, codes, tokens, pairing, the Vault, chats or people memory.
- **Android 13 or newer.** Cyclone's `minSdk` is 33, so VMOS phones must run an Android 13, 14 or 15 image.
- **The owner's own work.** The fleet runs the owner's own accounts and tasks. The plan 52 scope guard still holds: no
  bulk sign-ups, SMS farms, CAPTCHA solving, fingerprint spoofing or engagement manipulation.

## 1. Where we stand (checked in the code, 2026-10-08)

| Piece | State | Where |
| --- | --- | --- |
| VMOS signing (HMAC-SHA256, `armcloud-paas`) | **Built** | `cloud_fleet/providers/vmos.py` |
| Account keys in the vault (DPAPI) | **Built** | `cloud_fleet/vault.py` |
| Phone list, 7-day ADB lease, SSH tunnel, `adb connect`, renew 1/5 before the end, repair | **Built** | `cloud_fleet/service.py`, `lease.py`, `tunnel.py` |
| Cyclone installed on a cloud phone that lacks it (verified build) | **Built** (through `adb install`) | `phone_care/` |
| Glass → Devices → Cloud phones (add account, keep connected, status line) | **Built** | `apps/glass/src/pages/cloudPhonesView.ts` |
| Starter skills (10 Instagram) | **Built**, shipped inside the APK, so a new phone has them at install | `market/InstagramSkills.kt` |
| Market installs to one phone from the PC | **Built** (`market.install` per device) | `market/api.py`, `MarketInstalls.kt` |
| Accessibility, permissions and battery set up without hands (provisioning) | **Not built** | — |
| Trust without a tap ("Connect this PC?" needs a tap in VMOS's viewer today) | **Not built** | `GatewayTrustPrompt.kt` |
| Skills shared across phones (owner skills are "never shared" today; app maps and routines stay per phone) | **Not built** | `OwnerSkills.kt`, `applearner/` |
| Buy or create a phone, restart, reset from Cyclone | **Not built** | — |
| Groups, staged updates, fleet health | **Not built** | — |
| Any run against a real VMOS account | **Never** (UNVERIFIED since alpha.90) | — |

## 2. What the VMOS OpenAPI offers (deep dive, 2026-10-08)

### 2.1 How this was checked

**Reading the pages.** The VMOS documentation hosts are blocked from the build environment's network:
`cloud.vmoscloud.com`, `www.vmoscloud.com`, `api.vmoscloud.com`, `cloud.vsphone.com` and `docs.armcloud.net` all
answer `403 CONNECT`. The pages were read through web search, which quotes them directly.

**Sources, most trusted first:**
1. **VMOS** (official): `cloud.vmoscloud.com/vmoscloud/doc/zh/server/OpenAPI.html`, its English pages, and the
   `www.vmoscloud.com/help/…` articles.
2. **vsPhone** (`cloud.vsphone.com/vsphone/doc/en/server/OpenAPI.html`): the same ArmCloud platform under another
   brand, with the same `padApi` names. Only the path prefix differs: `/vsphone/api/padApi/` instead of
   `/vcpcloud/api/padApi/`.
3. **ArmCloud** (`docs.armcloud.net`): the underlying PaaS.

**Status words used below:**
- **Confirmed (VMOS):** seen on a VMOS page.
- **Confirmed (vsPhone):** seen on the sibling page only.
- **Ours:** proven by our own client code.

Run 0 still checks every row on the owner's account, because a VMOS account can differ from its sibling's docs.

**Machine-readable spec.** VMOS's English docs list an **OpenAPI spec** ("for AI & tools") and an **LLMs.txt** quick
reference in the OpenAPI sidebar of `cloud.vmoscloud.com/vmoscloud/doc/en/`. Run 0 downloads both on the owner's PC.
With the spec, the endpoint table becomes data, not guesses.

### 2.2 Basics

| Item | Finding | Source |
| --- | --- | --- |
| Base | `https://api.vmoscloud.com` + `/vcpcloud/api/padApi/<name>`, POST with a JSON body. The VMOS H5 SDK calls `https://api.vmoscloud.com/vcpcloud/api/padApi/stsToken`. | Confirmed (VMOS); ours |
| Other host | The SDK examples also name an overseas ArmCloud host, `https://openapi-hk.armcloud.net`. Which host an account answers on is checked in run 0. | Confirmed (VMOS H5 page) |
| Keys | Access Key ID + Secret Access Key, from the key pair under user management | Confirmed (VMOS) |
| Signing | HMAC-SHA256. Headers `x-date` (`YYYYMMDD'T'HHMMSS'Z'`, UTC), `x-host`, `authorization`. The canonical string covers host, x-date, content type, signed headers and the body's SHA-256. The scope is `<date>/armcloud-paas/request`. The key is HMAC chained over date → `armcloud-paas` → `request`. | Confirmed (vsPhone; VMOS names the same scheme); ours matches |
| **Open: signing version** | The docs recommend a **"V2 simplified signature" for new customers**. ArmCloud's v1 page writes the credential as `Credential={AK}/{date}/armcloud-paas/request`; our client sends `Credential={AK}`. Run 0 tries ours first, then the other forms. | To check |
| Answer shape | `{code, msg, traceId, ts, data}`; `code` 200 is success | Confirmed (VMOS) |
| Limits | No rate limit or QPS rule was found in any doc | Not found |

### 2.3 Endpoints

| Need | Endpoint | What the docs say | Source |
| --- | --- | --- | --- |
| **Phone list** | `infos` | Paged: `page` and `rows` (required), optional `padType`, `padCodes`. Rows carry `padCode`, `padStatus`, `androidVersion`, `goodId`, `goodName` (e.g. `i18n_Android13-V08`). | Ours; fields Confirmed (vsPhone) |
| **Phone status codes** | `padStatus` | 10 running · 11 restarting · 12 resetting · 13 upgrading · 14 **abnormal** · 15 not ready · 17 restoring · 18 **shut down** · 19 shutting down · 20 booting · 23 deleting · 24 delete failed · 25 deleted · 26 cloning · −1 deleted | Confirmed (VMOS zh + vsPhone). **Our client had 14 = "stopped" and no 18; fixed 2026-10-08.** |
| Phone properties | `padProperties`, `updatePadProperties` (live), `updatePadAndroidProp` (needs a restart) | System and settings properties | Confirmed (VMOS zh) |
| Time zone, language | `updateTimeZone`, `updateLanguage` | Paths shown as `/vcpcloud/api/padApi/updateTimeZone` and `/updateLanguage` | Confirmed (VMOS zh) |
| Wi-Fi, GPS, smart IP | `setWifiList`, `gpsInjectInfo`, `smartIp` | `smartIp` changes the exit IP, SIM info and GPS together | `setWifiList`, `smartIp`: Confirmed (VMOS zh); `gpsInjectInfo`: Confirmed (vsPhone) |
| **Remote ADB** | `adb` | `padCode`, `enable` (required); `expireMinutes` 1–7 days, default 1440 (example 2880). Answer: `padCode`, `command` (SSH tunnel line), `key`, `expireTime` (e.g. `2025-01-16 14:32:00`), `enable`, `adb` (`adb connect localhost:8577`). If `key` or `adb` come back empty, call the endpoint again. | Confirmed (VMOS zh); ours |
| ADB batch | `batch/adb` (≤ 10 phones; `successList` / `failedList`, e.g. `PAD_NOT_RUNNING`); `openOnlineAdb` (on/off) | Batch is marked "pending launch" on vsPhone | Batch answer: Confirmed (VMOS zh); `openOnlineAdb`: Confirmed (vsPhone) |
| **ADB must be switched on** | — | "ADB permission must be opened by customer service" (online chat or `start@vmoscloud.com`). In the web or PC client, Local Debugging → ADB keeps a connection for **24 h**; the API allows up to 7 days. | Confirmed (VMOS help) |
| **ADB commands** | `asyncCmd` | `padCodes`, `scriptContent` (ADB commands separated by `;`, e.g. `cd /root;ls`). Answer `data[]`: `taskId`, `padCode`, `vmStatus` (0 offline, 1 online). | Confirmed (VMOS zh + vsPhone) |
| Task results | `padTaskDetail`, `getTaskStatus` | `taskStatus`: −1 all failed, −2 partly failed, −3 cancelled, −4 timeout, −5 abnormal, 1 pending, 2 running, 3 done (9 queued in `padTaskDetail`). Plus `taskResult`, `errorMsg`, `endTime`. | Confirmed (vsPhone) |
| **Install from a link** | `uploadFileV3` | `padCodes` (required); optional `url`, `md5`, `packageName`, `fileName`, `fileUniqueId`, `customizeFilePath`, `autoInstall` (1/0, APK only; needs `packageName`), `isAuthorization` (default: grant all permissions), `iconPath`. A file VMOS already has (by md5 or id) is reused; otherwise VMOS downloads the URL. Asynchronous. | Confirmed (vsPhone; listed on VMOS) |
| Installed apps | Installed-app query | `padCodes`, optional `appName`. Answer: `packageName`, `versionCode`, `versionName`, `appState` (0 installed, 1 installing, 2 downloading). | Confirmed (vsPhone) |
| Root | `switchRoot` | `padCodes`, `rootStatus` (required), `globalRoot`, `packageName` (comma list; needed when global root is off; error 110089 without it). VMOS advises per-app root, not global. | Confirmed (vsPhone); VMOS help: Toolbox → Root Management |
| **Restart, reset** | `restart`, `reset` | Reset wipes the phone. Answer: `taskId`, `padCode`, `vmStatus`, `taskStatus` (−1 already queued, 1 added). Results arrive by callback (1000 / 1001). | Confirmed (vsPhone) |
| One-key new device | `replacePad` (+ `country`) | `padCodes`; optional `countryCode` (default: a Singapore SIM), `realPhoneTemplateId`, `androidProp`, `wipeData` (default true), … **Erases all data**; "use with caution". | Confirmed (vsPhone + VMOS help) |
| **Create a phone** | `getCloudGoodList` (GET, SKUs), `createMoneyOrder` | `goodId` (from the SKU list), `autoRenew` (required). Pre-sale: `createMoneyProOrder`, `queryProOrderList`. | Confirmed (vsPhone); no renewal endpoint found |
| **Callbacks** | URL set in VMOS's web console | Types: 999 phone status (`padStatus`, `padConnectStatus` 1/0); 1000 restart; 1001 reset; 1002 ADB command (output + `taskStatus`); 1003 app install (per app `result`, `failMsg`, e.g. a blacklisted app); 1004 uninstall; 1005–1007 app stop / restart / start; 1009 file upload; 1012 image upgrade; 1124 one-key new device; 4001 user image upload | Confirmed (vsPhone; VMOS lists a "callback task business type codes" page) |
| Live view | `stsToken` | Server-side token for the H5 viewer. Errors `INVALID_TOKEN` / `TOKEN_EXPIRED` mean fetch a new one; API errors 100006–100008 mean missing or invalid token. Lifetime not documented. | Confirmed (VMOS) |
| Backup / restore, script info, restart-task info | — | Marked **"Pending Launch"** | vsPhone |
| Error codes | — | 110031 instance not ready (wait), 220029 instance not running, 110089 no package for single-app root | VMOS ErrorCode page + vsPhone |

### 2.4 VMOS features outside the OpenAPI

- **Pre-installation Management** (VMOS console):
  - Pick up to **6 apps**; they are "automatically installed when a cloud machine is reset or a new one is
    purchased" and marked "Pre-installed".
  - **This is the cleanest way to make every new VMOS phone arrive with Cyclone.** Run 0 checks whether
    "one-key new device" also triggers it.
  - Confirmed (VMOS help, "Pre-installed applications").
- **One-key new device is not the same as reset.** On a virtual phone it keeps the device id but changes the virtual
  machine's properties. Replacing a device wipes data (VMOS suggests Clone & Backup first). Confirmed (VMOS help).
- **VMOS's own control API** (the "Android Control API" on Edge images, `127.0.0.1:18185` inside the phone):
  - It offers taps, swipes, a root shell, installs and screenshots, and VMOS also offers an MCP.
  - **Cyclone does not use either**, by §0: it is the provider's own hands and shell.
  - It also means any app inside the phone could reach a root shell on that port. Run 0 checks whether VMOS Cloud
    images expose it. If they do, the doctor warns.

## 3. The design

### 3.1 "Add a VMOS phone": one button to Ready

Glass → Devices → Cloud phones → **Add a VMOS phone**. The owner picks an existing phone on the account; in run V3,
they can also buy a new one. Cyclone then does every step below, with one status line per phone. Each step is
idempotent, so a stop part-way resumes where it left off.

| Step | How | Done when |
| --- | --- | --- |
| 1. Running and Android 13+ | `padInfo`; if it is stopped, `restart` (owner setting). An older image is refused, with the fix. | The status says running |
| 2. Link | Built: `adb` 7 days, SSH tunnel, `adb connect` on a stable local port | `adb get-state` says `device` |
| 3. Cyclone installed | **New or reset phones:** Cyclone is one of VMOS's 6 pre-installed apps, so it is already there. **Existing phones:** `uploadFileV3` with the signed release APK's GitHub URL, its md5, `packageName` and `autoInstall=1`, so VMOS downloads it and the PC uploads nothing. **Fallback:** phone care's `adb install` (built). | Version and signing certificate are read back over ADB and match the release manifest. An older pre-installed Cyclone is updated the same way. |
| 4. Set up (provisioning) | A fixed catalogue over ADB, each item read back (below) | Every item reads back as set |
| 5. Trusted (enrollment) | A one-time enrollment grant, bound to this PC's key, sent by `adb shell am broadcast` to a receiver only the shell user may call. No tap needed. | The phone reports the PC as trusted; the bridge works |
| 6. Skills | Starter skills come with the APK. The fleet library syncs next (§3.3). | The library version on the phone matches the PC |
| 7. Ready check | Phone care health, then a read-only smoke mission (open Settings, read the Android version) | The card shows **Ready** |

**The provisioning catalogue** is code, never input. Each item is applied over ADB, then read back:
- **Accessibility:** turn on Cyclone's accessibility service (`settings put secure enabled_accessibility_services …` and
  `accessibility_enabled 1`).
- **Notification listener:** turn on Cyclone's listener.
- **Permissions:** `pm grant` for the runtime permissions Cyclone declares (notifications, microphone only if Drive is
  used, …), and `appops` for drawing over other apps.
- **Battery:** add Cyclone to the battery whitelist (`dumpsys deviceidle whitelist +com.cyclone.mobile`); stay awake
  while plugged in.
- **Hands:** Hands = Natural (the default already).
- **Time and language:** time zone and language set to the owner's, using VMOS's own property endpoints.

**Never in the catalogue:**
- disabling security features;
- global root (per-app root only, as an owner toggle, for a skill that needs it);
- anything that takes a value from a model.

**Enrollment (trust with no tap).**
- **The receiver.** `FleetEnrollReceiver` is guarded by a permission that only the shell user holds (`DUMP`). It
  accepts one grant:
  - signed by this PC's pairing key;
  - single use;
  - valid for 10 minutes.
- **What the phone shows.** A permanent notice: "Managed by <PC name> · Remove". The owner can revoke it on the phone
  or in Glass.
- **Who it is for.** It only works over ADB, which on a cloud phone only the owner's PC has. A personal phone keeps the
  "Connect this PC?" tap.

**Target:** about 3–5 minutes from click to Ready on an existing phone. This is an estimate, measured in run 0.

### 3.2 Staying connected over ADB

The owner chose ADB as the way in, so ADB stays the primary link. The keeper built in alpha.90 already:
- renews the 7-day lease when a fifth of it is left;
- restarts a dead tunnel;
- gets a new key after a refused one;
- makes a fresh link after three failed connects.

This plan adds:
- **Batch renewal:** one `adb` batch call for all phones on an account, so a fleet doesn't make one call per phone.
- **A repair ladder:**
  1. wake the bridge (60 s);
  2. restart the tunnel (3 min);
  3. VMOS `restart` (10 min, an owner setting, off by default);
  4. "needs you".
- **A stopped phone says so.** It is not shown as "reconnecting".
- **The bridge over ADB** stays exactly as for USB phones: `adb forward` to Cyclone's local gateway. A VMOS phone is
  just serial `127.0.0.1:<stable port>`.
- **An honest doctor line per phone:** link up / renewing / VMOS stopped / Cyclone not set up.

**Plan 44's own relay link** (runs 3–4: a WebSocket from the phone, end-to-end encrypted) stays as **V5, only if run 0
or real use shows the VMOS SSH ADB is not reliable enough.**

### 3.3 Skills in sync: the fleet library

**What a "skill" is on a phone today:**
- the **starter skills** inside the APK;
- **Market installs** (a listing plus its inputs);
- the owner's **saved skills** ("Save skill", stored locally and *never shared* today), with their **anchors** (where
  the skill lives on the app's map);
- **app maps** and the **App Manual**;
- **routines**.

**The fleet library** sits on the PC (`fleet_skills/`) and holds versioned packs in a new `cyclone.skillpack/1`
format.

- **A pack holds:**
  - the listings;
  - the saved skills the owner chose to share, with their anchors;
  - for the apps those skills touch, the app map and manual entries;
  - routines.
- **Every item is tagged** with the app version it was learned on.
- **Packs are checked on both ends.** Each pack is checked against its schema, kept to a size cap, and scanned for
  secret-shaped text (the existing `MarketRules.SECRET_SHAPE`). It holds no typed values, no screenshots and no people
  memory.

**How skills move:**
1. **Share.** On any phone or in Glass, the owner marks a skill **Share with my phones**, so nothing is shared by
   default.
2. **Collect.** The PC takes the skill with a new bridge op `skills.export`.
3. **Send.** The PC sends the pack with `skills.import`, using the pairing it already has:
   - to every fleet phone, or to a group;
   - to a phone joining the fleet, in step 6 of §3.1;
   - to a phone coming back online, which catches up.
4. **Install.** The phone stores the pack's items as source **Fleet**, kept apart from its own skills.

**Sync rules:**
- **Newer wins.** A newer version from the library replaces the phone's Fleet copy. A phone's own re-grounded copy is
  kept, and the library is offered the newer one.
- **A different app version** marks a skill **re-check**: the phone re-grounds it on its first run.
- **Proven:** a skill that succeeded on three phones is marked **proven** (plan 44 run 5).
- **Glass → Skills → Fleet** shows each skill × phone with a tick, an out-of-date mark or "re-check", and a **Sync
  now** button.

### 3.4 At the owner's fingertips

Glass → Devices → **Cloud phones** gets one card per phone:
- name and Android version;
- state: Ready, Working, Reconnecting, Stopped, Needs you;
- the skill-library version;
- the last task.

**Card buttons:**
- **Live view.** The VMOS H5 viewer via `stsToken`, view only, or Cyclone's own Live Phone.
- **Restart.**
- **Sync skills.**
- **Remove from fleet.** This removes the phone from the fleet but keeps the VMOS phone.
- **Reset / new device.** Owner only, with a confirm. It erases the phone.

**Groups:** tag phones ("instagram", "test"), then run a skill or a mission on a phone or a group. The MCP asks which
phone; approvals name the phone.

## 4. The runs

| Run | Alpha (next free) | What ships | Done when |
| --- | --- | --- | --- |
| **0 Probe** (with the owner's account, not a release) | — | A small probe in `scripts/pc/cloud_probe.py` (it exists; extend it) calls each _confirm_ endpoint once on one test phone and records the answers with keys removed, as test fixtures. It also downloads VMOS's OpenAPI spec and LLMs.txt and checks: the signing form (§2.2); whether ADB is enabled for the account; 7-day `expireMinutes`; `uploadFileV3` from a GitHub URL; `asyncCmd` + `padTaskDetail`; `getCloudGoodList`; Pre-installation Management; callbacks; and whether port 18185 is open inside the phone. | Fixtures are committed. Every table row in §2 is marked confirmed or changed. |
| **V1 Ready in one click** | alpha.115 | The provisioning catalogue with read-back; `FleetEnrollReceiver` and the gateway's grant; install via `uploadFileV3` with `adb install` as fallback; the ready check; Glass card states; the doctor lines | An existing VMOS phone goes from "Add" to Ready with no tap, and runs a starter skill |
| **V2 Skills in sync** | alpha.116 | `cyclone.skillpack/1`; phone ops `skills.export` / `skills.import`; "Share with my phones"; the PC library; sync on join, on change and on reconnect; Glass Skills → Fleet | A skill saved on phone A runs on VMOS phones B and C after one Share |
| **V3 Spin up new** | alpha.117 | SKU and image list (Android 13+ only); owner-confirmed purchase; wait until running; chains into V1; restart and reset buttons; the callback receiver (else polling) | "New VMOS phone" in Glass gives a Ready phone with skills, the purchase confirmed by the owner |
| **V4 Run the fleet** | alpha.118 | Groups; run on a group; staged Cyclone updates (canary → 10 % → all, stop if health drops); batch ADB renewal; the repair ladder; a health board | Five VMOS phones updated, synced and kept connected for a week without the owner |
| **V5 (only if needed)** | later | Plan 44's relay link (runs 3–4) if the SSH ADB proves flaky; golden-phone cloning once VMOS backup leaves "Pending Launch" | — |

Each run ships with:
- tests (provider fixtures from run 0, provisioning read-back fakes, enrollment security tests, the skill-pack
  privacy scan);
- a Glass test;
- the CI guards: no provider-native mutation, no shell for the model, no secret in a pack.

**Physical results stay UNVERIFIED until they are seen on the owner's VMOS account.**

## 5. Questions for the owner

1. **Account and ADB.** VMOS confirms that customer service must open ADB permission for the account (online chat or
   `start@vmoscloud.com`). Has that been done? Which host does the account answer on: `api.vmoscloud.com`, or the
   ArmCloud overseas host?
2. **Pre-install.** Should Cyclone be put into VMOS's Pre-installation Management (one of the 6 slots), so every new or
   reset phone has it?
3. **Images.** Android 13, 14 or 15? Is there a preferred SKU?
4. **Sharing.** Should the owner's own saved skills be shareable to the fleet (one tap per skill), or only Market and
   starter skills?
5. **Buying.** May Glass buy new phones (owner-confirmed every time), or should Cyclone only adopt phones bought in
   the VMOS console?
6. **Size.** How many phones? The fleet's limit is 32 today; raising it is part of V4 if needed.
