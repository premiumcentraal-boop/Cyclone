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
| VMOS signing: **V2** (SHA-256, three headers), with the older HMAC as a remembered fallback | **Built** (V2 since 2026-10-08) | `cloud_fleet/providers/vmos.py` |
| ADB switched on when needed (`openOnlineAdb`), padCode moves followed, keep-alive for Cyclone, owner names and paid-until dates | **Built** (2026-10-08) | `cloud_fleet/providers/vmos.py`, `service.py` |
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

## 2. The VMOS OpenAPI, from VMOS's own docs (2026-10-08)

### 2.1 Sources

The owner opened the network, so this section is read from VMOS's own pages, not from search quotes. It replaces the
earlier search-based reading.

| Page | What it settles |
| --- | --- |
| `cloud.vmoscloud.com/vmoscloud/doc/en/server/OpenAPI.html` | Every endpoint, its body and its answer |
| `…/server/example.html` (OpenAPI Getting Started) | **V2 signing**, with a worked example |
| `…/server/ErrorCode.html` | Error codes, including the sign-in ones |
| `…/server/callback.html` | Callback types and their JSON |
| The OpenAPI spec (`openapi.yaml`) and `llms.txt` | The same endpoints as data |

**Status words below:**
- **Built** is in the code now, with tests against VMOS's documented answers.
- **Run Vn** is planned for that run.
- Anything VMOS marks "Pending Launch" says so.
- Nothing here has met a real VMOS account yet: **UNVERIFIED** until run 0.

### 2.2 Basics

| Item | Finding |
| --- | --- |
| Base | `https://api.vmoscloud.com` + `/vcpcloud/api/padApi/<name>`. Mostly POST with a JSON body; a few lists are GET. |
| Keys | Access Key ID + Secret Access Key, from the console's Developer → API |
| **Signing (V2)** | Three headers: `X-Access-Key`, `X-Timestamp` (unix **seconds**, ±5 minutes) and `X-Sign` = lowerHex(SHA-256(SK + timestamp + path + bodyOrQuery)), plain concatenation. bodyOrQuery is the exact body sent, the raw query for a GET, and **empty** for `uploadFile`, `uploadFileV3`, `asyncCmd` and `syncCmd`. **Built:** checked against the guide's own example. |
| Old signing | Our alpha.90 client used the older HMAC scheme (`armcloud-paas` scope). VMOS's guide now documents only V2. **Built:** V2 first. If VMOS refuses the signature, the same call is tried once with HMAC, and the scheme that worked is remembered per account. |
| Sign-in errors | HTTP 401 with: 2019 signature mismatch · 2031 unknown key · 2032 missing header · 2033 timestamp expired · 1116 IP not on the allow list. **Built:** the last three each get their own fix in Cyclone's words ("this PC's clock is off", "add this PC's IP in the console"), and are not retried. |
| Answer shape | `{code, msg, ts, traceId, data}`; `code` 200 is success. Business refusals come back as HTTP 200 with another code. |
| Limits | No global rate limit is documented. Per-endpoint limits: reset at most once per 3 minutes (1219); auto-renew calls are throttled; some calls refuse a repeat within 2 s. Batches: `openOnlineAdb` takes 1–200 phones, `batch/adb` 10, `padDetail` up to 1000 rows a page. |
| Callbacks | A URL set in VMOS's web console; POST JSON. Codes 999 phone status, 1000 restart, 1001 reset, 1002 command, 1003 install, 1004 uninstall, 1005/1007 app stop/start, 1009 file upload, 1012 image upgrade, 1124 new device, 1403 backup size, 4001 image upload, plus the event `padCodeChange`. **They carry no signature**, so Cyclone treats a callback only as a hint to re-read the API, never as a fact. |
| `padStatus` | 10 running · 11 restarting · 12 resetting · 13 upgrading · 14 abnormal · 15 not ready · 16 backing up · 17 restoring · 18 shut down · 19 shutting down · 20 booting · 23 deleting · 24 delete failed · 25 deleted · 26 cloning · −1 deleted. **Built.** |

### 2.3 The connector set Cyclone uses

Picked for the owner's goal: phones that are easy to add, arrive with Cyclone and our skills, stay connected over ADB,
and sit at the owner's fingertips. Every connector runs in the PC gateway, inside the cloud-fleet keeper or behind an
owner button in Glass. **None of them is a model, MCP or agent tool.**

**A. Connect and stay connected**

| Connector | What Cyclone does with it | State |
| --- | --- | --- |
| `infos` | The phone list and each phone's state | Built (alpha.90) |
| `userPadList` | The owner's own name for each phone, its Android version and when its paid time ends (`signExpirationTime`). `infos` has none of these. | **Built** |
| `adb` | The 1–7 day SSH link (`command`, `key`, `expireTime`). Cyclone asks for 7 days and renews early. | Built; the shortest lease is now 1 day, as documented |
| `openOnlineAdb` | Switches ADB on. VMOS's own instruction: when `adb` answers without `key` or `command`, switch ADB on first. Cyclone does that once and asks again. | **Built** |
| `queryPadIdChangeRecords` (+ `padCodeChange` callback) | VMOS can move a phone to a new padCode while keeping its data. Cyclone follows the move: the phone keeps its "keep connected", name and **local port**, so it comes back on the same serial as the same fleet phone. A phone that moved twice is followed to its newest code. | **Built** |
| `setKeepAliveApp` | Asks VMOS to keep Cyclone's service (`CycloneAccessibilityService`) running, on Android 13–15 images. Asked once per connection, after Cyclone is confirmed on the phone. | **Built** |
| `padDetail` | Online/offline, board status and compute use, with filters (only abnormal, only offline). Feeds the health board. | Run V4 |
| `restartApp`, `startApp` | A rung on the repair ladder: restart Cyclone without restarting the phone | Run V1 |
| `restart` | The next rung (an owner setting, off by default) | Run V1 |
| `batch/adb` | Renew 10 phones' links in one call | Run V4, once VMOS launches it ("Pending Launch") |

**B. Arrive ready**

| Connector | What Cyclone does with it | State |
| --- | --- | --- |
| `uploadFileV3` | Install the signed release APK straight from its GitHub URL, with `md5`, `packageName` and `autoInstall=1`. VMOS downloads it, so the PC uploads nothing. The body is not signed. | Run V1 |
| `listInstalledApp` | Cyclone's installed version on the phone, read live, and whether it is still downloading or installing. Confirms an install even before ADB is up. | **Built** (provider); wired in V1 |
| `fileTaskDetail`, `padTaskDetail` | Follow an install or command task to done or failed | Run V1 |
| `asyncCmd` | The fallback path for the fixed provisioning catalogue (§3.1) when ADB is down. Commands come only from code; the body is not signed. | Run V1 |
| `updateTimeZone`, `updateLanguage` | Set the owner's time zone and language | Run V1 |
| `selectFiles` (cloud space) | Reuse an APK already stored in the account's cloud space | Run V1, optional |
| Pre-installation Management (console, 6 slots) | Cyclone in one slot means every new or reset phone arrives with it | Owner question 2 |

**C. A golden phone, cloned (backup and clone are live now)**

| Connector | What Cyclone does with it | State |
| --- | --- | --- |
| `backupCalculate` → `queryBackupCalculateResult` (or callback 1403) → `addBackup` within 5 minutes → `queryBackupBatch` | Back up a phone when the owner presses **Back up** (built, alpha.117). Later: a freshly provisioned, Ready phone as the **golden phone** | **Built** (owner backup); golden: Run V3 |
| `listPadBackups`, `listPadBackupIds` | Pick the golden backup | Run V3 |
| `clonePadBackup` | **Built** (alpha.117) for restoring a phone from its own backup only. V3: clone the golden onto new phones in one batch. **Every clone then re-enrolls:** Cyclone notices it is a copy (a new padCode under an old install id), drops the copied pairing and keys, and gets its own grant (§3.1 step 5). A clone never inherits another phone's trust. | Run V3 |
| `imageVersionList` | Offer only Android 13+ images | Run V3 |
| `upgradeImage` | Image upgrades: owner-confirmed, staged | Run V4 |

**D. New phones and cost (owner-only buttons, GATE pay)**

| Connector | What Cyclone does with it | State |
| --- | --- | --- |
| `getCloudGoodList` | The SKUs: rental periods and pay-for-time rates | **Built** (alpha.117) |
| `createMoneyOrder` | Rent a phone by the period or renew one, owner-confirmed every time | **Built** (alpha.117) |
| `openAutoRenew`, `closeAutoRenew` | Auto-renew per phone; the card warns before the paid time ends | **Built** (alpha.117) |
| `selectPaidOrderList` | The bills, in Glass | Run V4 |
| Timing devices: `createByTimingOrder`, `timingPadOn`, `timingPadOff` (keeps the environment) | Pay-for-time phones: rent, power on and off by hand | **Built** (alpha.117); on a schedule for jobs: Run V4 |

**E. At the owner's fingertips (Glass)**

| Connector | What Cyclone does with it | State |
| --- | --- | --- |
| `getLongGenerateUrl` | A live thumbnail on every phone card, in batches, scaled and compressed. The gateway fetches it and never stores it. | Run V1 |
| `updatePadName` | Renaming a phone in Glass renames it at VMOS, so it has one name everywhere | Run V1 |
| `padGroupList`, `padGroupDevices` | Bring the owner's VMOS console groups in as Cyclone groups | Run V4 |
| `stsTokenByPadCode`, `clearStsToken` | The VMOS live viewer, view-only, and ending a share | Run V4 |
| `dissolveRoom` | End a stuck viewer stream | Run V4 |

### 2.4 Not used, on purpose

| Endpoints | Why not |
| --- | --- |
| `simulateTouch`, `simulateClick`, `simulateSwipe`, `simulateLongPress`, `inputText`, the Edge control API on `127.0.0.1:18185`, VMOS's MCP | The provider's own hands. Cyclone acts only through `PhoneToolExecutor` (§0). |
| `syncCmd`, `asyncCmd` with any outside input, global `switchRoot` | A shell for someone other than Cyclone's fixed catalogue |
| `replacePad`, `padReplaceNew`, `updateSIM*`, `updatePadAndroidProp`, `updatePadProperties`, `resetGAID`, `setWifiList`, `gpsInjectInfo`, `smartIp`, `setProxy`, `replaceRealAdiTemplate`, `virtualRealSwitch` | Changing what device a phone claims to be. That is fingerprint spoofing, outside the scope guard. A wipe ("new device") stays an owner button in VMOS's own console. |
| `setHideAppList`, `setHideAccessibilityAppList` | Hiding automation from apps: defeating anti-automation checks, outside the scope guard |
| Cloud numbers, `simulateSendSms`, `enableSmsSendCallback`, `addPhoneRecord`, `updateContacts` | Number and SMS farms, outside the scope guard |
| Social accounts (`socialAccount*`) | Bulk third-party accounts, outside the scope guard |
| `injectAudioToMic`, `unmannedLive`, `injectPicture` | Faking the camera, microphone or a live stream |
| `authorizePad`, `confirmTransfer`, `replacement` | Handing a phone to another account: the owner does that in VMOS's console |

### 2.5 Still to see on the owner's account (run 0)

- V2 signing against the real account. It is tested against the guide's example only.
- Whether ADB still needs VMOS customer service, now that `openOnlineAdb` exists.
- Whether `setKeepAliveApp` accepts an accessibility service (the docs say "service").
- Whether "one-key new device" also re-installs the pre-installed apps.
- What a clone keeps of Cyclone's per-phone keys. The answer must be "nothing usable" after re-enrollment.
- Whether port 18185 is open inside the phone. If it is, the doctor warns.

## 3. The design

### 3.1 "Add a VMOS phone": one button to Ready

Glass → Devices → Cloud phones → **Add a VMOS phone**. The owner picks an existing phone on the account; in run V3,
they can also buy a new one. Cyclone then does every step below, with one status line per phone. Each step is
idempotent, so a stop part-way resumes where it left off.

| Step | How | Done when |
| --- | --- | --- |
| 1. Running and Android 13+ | `infos` + `userPadList`; if it is stopped, `restart` (owner setting). An older image is refused, with the fix. | The status says running |
| 2. Link | Built: `adb` 7 days (ADB switched on with `openOnlineAdb` if needed), SSH tunnel, `adb connect` on a stable local port | `adb get-state` says `device` |
| 3. Cyclone installed | **New or reset phones:** Cyclone is one of VMOS's 6 pre-installed apps, so it is already there. **Existing phones:** `uploadFileV3` with the signed release APK's GitHub URL, its md5, `packageName` and `autoInstall=1`, so VMOS downloads it and the PC uploads nothing. `listInstalledApp` follows it while VMOS downloads. **Fallback:** phone care's `adb install` (built). Then `setKeepAliveApp` (built). | Version and signing certificate are read back over ADB and match the release manifest. An older pre-installed Cyclone is updated the same way. |
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

Built on 2026-10-08:
- **ADB switched on by API** when VMOS hands back an incomplete link (`openOnlineAdb`).
- **A moved phone is followed.** When VMOS gives a phone a new padCode, it keeps its local port, so it is the same
  serial and the same fleet phone.
- **Keep-alive.** VMOS is asked to keep Cyclone's service running.
- **Sign-in problems in plain words**, for example this PC's clock being off or its IP missing from the allow list.

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
- a live thumbnail (`getLongGenerateUrl`);
- name (renaming here renames at VMOS) and Android version;
- paid until, with a warning before it ends;
- state: Ready, Working, Reconnecting, Stopped, Needs you;
- the skill-library version;
- the last task.

**Card buttons:**
- **Live view.** The VMOS H5 viewer via `stsToken`, view only, or Cyclone's own Live Phone.
- **Restart.**
- **Sync skills.**
- **Remove from fleet.** This removes the phone from the fleet but keeps the VMOS phone.
- **Reset / new device.** Owner only, with a confirm. It erases the phone.

**Groups:** tag phones ("instagram", "test"), or bring in the VMOS console's groups, then run a skill or a mission on a phone or a group. The MCP asks which
phone; approvals name the phone.

## 4. The runs

| Run | Alpha (next free) | What ships | Done when |
| --- | --- | --- | --- |
| **Rent, power, backup** (built 2026-10-09, **alpha.117**) | — | Owner asked for both rentals by the period (a week, a month) and pay-for-time, plus backups the owner triggers. Glass: Rent phones (price confirmed, 1–5 per order), new phones kept by themselves; Power on/off; Renew and auto-renew; Back up; Restore a phone's own backup with a confirm | Gateway, Glass and guard tests pass (done) |
| **Connectors** (built 2026-10-08, **alpha.117**) | — | V2 signing with HMAC fallback; named sign-in errors; `openOnlineAdb`; padCode following; `setKeepAliveApp`; `userPadList` names and paid-until; `listInstalledApp`; status 16 | Gateway tests pass (done) |
| **0 Probe** (with the owner's account, not a release) | — | A small probe in `scripts/pc/cloud_probe.py` (it exists; extend it) calls each §2.3 connector once on one test phone and records the answers with keys removed, as test fixtures. It checks the list in §2.5, plus 7-day `expireMinutes`, `uploadFileV3` from a GitHub URL, `asyncCmd` + `padTaskDetail`, `getCloudGoodList`, Pre-installation Management and callbacks. | Fixtures are committed. Every row in §2.3 is marked confirmed or changed. |
| **V1 Ready in one click** | alpha.118 | The provisioning catalogue with read-back; `FleetEnrollReceiver` and the gateway's grant; install via `uploadFileV3` with `adb install` as fallback; the ready check; Glass card states, thumbnails and rename; the `restartApp` repair rung; the doctor lines | An existing VMOS phone goes from "Add" to Ready with no tap, and runs a starter skill |
| **V2 Skills in sync** | alpha.119 | `cyclone.skillpack/1`; phone ops `skills.export` / `skills.import`; "Share with my phones"; the PC library; sync on join, on change and on reconnect; Glass Skills → Fleet | A skill saved on phone A runs on VMOS phones B and C after one Share |
| **V3 Spin up new** | alpha.120 | SKU and image list (Android 13+ only); owner-confirmed purchase and auto-renew; wait until running; chains into V1; **the golden phone**: back up a Ready phone, clone it onto new ones, each clone re-enrolls; restart and reset buttons; the callback receiver as a hint (else polling) | "New VMOS phone" in Glass gives a Ready phone with skills, the purchase confirmed by the owner; three clones of a golden are Ready, each with its own trust |
| **V4 Run the fleet** | alpha.121 | Groups (with the VMOS console's); run on a group; `padDetail` health; timing devices on a schedule; bills; staged Cyclone updates (canary → 10 % → all, stop if health drops); batch ADB renewal; the repair ladder; a health board | Five VMOS phones updated, synced and kept connected for a week without the owner |
| **V5 (only if needed)** | later | Plan 44's relay link (runs 3–4) if the SSH ADB proves flaky | — |

Each run ships with:
- tests (provider fixtures from run 0, provisioning read-back fakes, enrollment security tests, the skill-pack
  privacy scan);
- a Glass test;
- the CI guards: no provider-native mutation, no shell for the model, no secret in a pack.

**Physical results stay UNVERIFIED until they are seen on the owner's VMOS account.**

## 5. Questions for the owner

1. **Account and ADB.** Cyclone can now switch ADB on by API (`openOnlineAdb`). VMOS's help still says customer
   service opens ADB permission for an account (online chat or `start@vmoscloud.com`); if run 0 is refused, that is
   the fix. Does the account use an API IP allow list? If so, this PC's IP must be on it.
2. **Pre-install.** Should Cyclone be put into VMOS's Pre-installation Management (one of the 6 slots), so every new or
   reset phone has it?
3. **Images.** Android 13, 14 or 15? Is there a preferred SKU?
4. **Sharing.** Should the owner's own saved skills be shareable to the fleet (one tap per skill), or only Market and
   starter skills?
5. **Buying.** May Glass buy new phones (owner-confirmed every time), or should Cyclone only adopt phones bought in
   the VMOS console?
6. **Size.** How many phones? The fleet's limit is 32 today; raising it is part of V4 if needed.
7. **Timing devices.** Answered 2026-10-09: yes, both pay-for-time phones and rentals by the period (a week, a month).
   Built in alpha.117. Still open: should Cyclone power pay-for-time phones on for scheduled jobs by itself (V4)?
8. **Backup.** Answered 2026-10-09: yes, triggered by the owner. Built in alpha.117. Golden-phone cloning (V3) is
   still to confirm.
