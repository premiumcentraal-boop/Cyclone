# Handoff: wire Cyclone Cloak to Cyclone's profiles (the Cyclone side, as built in alpha.122)

**To the agent receiving this:** your job is to make **Cyclone Cloak** work with Cyclone's profiles. Everything on the
Cyclone side is built and released: Cyclone `5.0.0-alpha.122.dev1` (version code 276), connector contract
`cyclone.connector/1`, **minor 3**. What remains is Cloak's side. This file is the whole contract and the recommended
wiring.

**What Cloak should do when you are done:**

| # | Feature | Cyclone side |
|---|---|---|
| A | Be an **approved connector**; the approval follows the owner into every profile | built (alpha.121) |
| B | **Load a Cloak phone profile onto a Cyclone profile** (bind it to that profile's apps) | built |
| C | **The rooted pill**: Cyclone shows Rooted / Rooted · check / Rooted · not working / Native from Cloak's health; Cloak shows its own pill with Cyclone's root facts | built (alpha.121) |
| D | **Ask to open another profile**; the owner says yes on Cyclone's own screen | built (alpha.122) |

**Scope:**
- This file covers the **Cyclone side only**. How Cloak builds or applies a phone profile inside its own app is out of
  scope; Cyclone never receives or needs those details.
- **Don't edit Cyclone** (`premiumcentraal-boop/Cyclone`, `apps/mobile/app/**`). If Cloak needs something Cyclone
  doesn't offer, write it under "Contract feedback" in your report (§11); the Cyclone agent builds it there.
- **If this file and `tools/cyclone-connector-sdk/SPEC.md` disagree,** SPEC.md wins.

**Owner decisions (2026-10-09):**
- Cloak binds apps in **every Cyclone profile, never in Main**.
- Opening a profile from Cloak is allowed **only as a request the owner answers** on Cyclone's screen.

---

## 0. Get the kit and read first

**From the Cyclone release `v5.0.0-alpha.122.dev1`** (GitHub releases of `premiumcentraal-boop/Cyclone`):
- `Cyclone-Connector-Client-5.0.0-alpha.122.dev1.aar`: the client library. Add it to Cloak; it declares the
  `<queries>` entry for Cyclone.
- `Cyclone-Connector-Sample-5.0.0-alpha.122.dev1.apk`: a sample connector with a button per call. Read it; don't ship
  it.
- `Cyclone-Connector-Schemas-5.0.0-alpha.122.dev1.zip`: SPEC.md, the JSON Schemas and `vectors.json` (Cyclone's exact
  answers).

**Read, in this order:**
1. `tools/cyclone-connector-sdk/SPEC.md`: §2 manifest, §3 calls and errors, §5 profiles, §7 events, §10 config, §12 root
   status and Cloak, §13 open requests.
2. `apps/mobile/app/src/main/java/com/cyclone/mobile/connector/CycloneCloakProfileBinding.kt`: **exactly** how Cyclone
   reads Cloak's bindings. `CycloneCloakProfileBindingTest.kt` has valid and invalid envelopes.
3. `docs/PROFILES_DEVICE_MATRIX.md` §5: the Cloak checks the owner runs on the phone.

## 1. How Cyclone's profiles work (what Cloak must know)

### 1.1 Profiles are Android users

- **A Cyclone profile is a full secondary Android user.** Its Android user name and its profile id are both
  `Cyclone_<16 lowercase hex>`, for example `Cyclone_0123456789abcdef`.
  - **The id never changes.** The label ("Profile B", "Work") can.
  - The Android user number (`androidUserId`) can change, if a profile is restored.
- **The main profile** is the owner's own Android user. In the connector API it is the synthetic entry
  `{"id": "owner", "label": "This phone", "kind": "owner"}`. It has no registry record, and **Cloak never binds apps in
  it**.
- **Every profile runs its own Cyclone and its own Cloak.** They are separate installs with separate data, processes and
  grants. The uid is `userId * 100000 + appId`.
- **The profile list (registry)** lives in each Cyclone. The main profile's Cyclone is the authority: its list replaces
  the others' on every switch, and another profile only adds profiles that profile doesn't know yet.

### 1.2 Making a profile, and what it gets

- **Creation** is root only, through fixed commands. No connector can create, rename or remove a profile.
- **Profile room:** on full rooted phones the owner can allow more profiles (`fw.max_users` via `resetprop`, kept by a
  small module).
- **On every switch into a profile made on the phone,** Cyclone:
  - installs its **cornerstone apps** there with `pm install-existing`: Cyclone, the root manager, Shizuku, **Cyclone
    Cloak** (found by its connector id `cyclone-cloak`), and up to 12 apps the owner marked;
  - shares Magisk root grants where the app had one;
  - proves root from that profile's own Cyclone;
  - carries memory, skills, settings and the profile list;
  - carries **Cyclone Cloak's approval** (§2.2).
- **The owner sees it:** Profiles shows "Profile C has: … Cloak ✓ approved ✓", or why not.

### 1.3 Switching

- **On the phone** (Profiles, rescue screen, or the open request of §6), a switch is staged:
  1. prepare;
  2. carry;
  3. **arm the way back**: a root-side timer switches back after 45 s if the target's Cyclone never says hello. It is
     armed only once that Cyclone answers that it is listening;
  4. switch;
  5. hello, which disarms the timer.
- **Then both Cyclones record `profile.switched`:** the target's and the source's. So **Cloak in both profiles hears
  about it.**
- **From the PC** (Glass, the testbench), switches have the same way back and journal since alpha.122. They **carry
  nothing**: no memory, skills or approvals.
- **`profiles.current`:**
  - reliable from the Cyclone of the profile in front;
  - from a background profile's Cyclone it is that Cyclone's last switch, or `null`.

  Treat it as a hint.

### 1.4 What lives where (the most important table)

| Thing | Where | Consequence for Cloak |
|---|---|---|
| Binder door | the Cyclone **in the caller's own Android user** | Cloak in C talks only to Cyclone in C. |
| Approval | each Cyclone; Cloak's **is carried** on switches made on the phone, and re-verified there | Approve once (in Main, normally). A profile reached only by PC switches may still need approving there. |
| Config store (`config.*.v1`) | each Cyclone; namespace = connector id + **caller's** Android user | A binding written by Cloak in Main is in Main's Cyclone; one written by Cloak in C is in C's Cyclone. Nothing in it is carried. |
| Profile list | each Cyclone; Main is the authority | Ids are the same everywhere. |
| Events | each Cyclone's own journal | Each Cloak pulls from its own Cyclone. |
| Startup provider | per user, never persisted | Re-register after either process restarts (SPEC §11). |

## 2. Feature A: be an approved connector

### 2.1 The manifest

**The marker service.** In Cloak's `AndroidManifest.xml`, declare it exactly as SPEC §2 shows: an exported service,
protected by `com.cyclone.mobile.permission.CONNECTOR_HOST`, with the action `com.cyclone.connector.CONNECT` and
meta-data `com.cyclone.connector` → `@xml/cyclone_connector`. Then add `res/xml/cyclone_connector.xml`:

```xml
<cyclone-connector
    contract="cyclone.connector/1.3"
    id="cyclone-cloak"
    label="Cyclone Cloak"
    scopes="profiles.read profiles.apps.read profiles.config events.profiles device.root.read profiles.open.request selector.contribute"
    entryActivity=".CycloneEntryActivity"
    wakeReceiver=".CycloneWakeReceiver" />
```

**The rules:**
- **`id` must be exactly `cyclone-cloak`.** Cyclone looks bindings up, installs Cloak as a cornerstone, and carries
  its approval under this id. Any other id gets none of that.
- **The receiver:** `wakeReceiver` must be exported and protected by `com.cyclone.mobile.permission.WAKE_CONNECTOR`.
- **Scopes:**

  | Scope | Why Cloak needs it |
  |---|---|
  | `profiles.read` | the profile list, labels and ids, `current` |
  | `profiles.apps.read` | each profile's `packages` (a binding is only accepted for a listed package) |
  | `profiles.config` | feature B (bindings and their health) |
  | `events.profiles` | hear `profile.switched`, `profile.updated` and the rest; needs the wake receiver |
  | `device.root.read` | `root.status.v1` (feature C, minor 2) |
  | `profiles.open.request` | `profiles.open.request.v1` (feature D, minor 3) |
  | `selector.contribute` | optional: Cloak's own entries in Cyclone's profile slider |
  | `profiles.startup` | optional: a callback before Cyclone opens an app in a profile (SPEC §11, 250 ms, never blocking) |

  Each scope is approved by the owner. Never assume one; check `hello.granted`.
- **Signing:** sign every Cloak build with the same key, or rotate it with a lineage (APK Signature Scheme v3). Approval
  is by package **and** certificate lineage; an unrelated new key means `NOT_APPROVED` everywhere.

### 2.2 Approval, and how it follows the owner

1. **The owner approves Cloak once:** Cyclone → Settings → Connectors (normally in Main).
2. **On every switch made on the phone, the approval travels.** The receiving profile's Cyclone re-verifies it against
   the Cloak installed **there**:
   - the same package;
   - the same connector id;
   - the approved certificate in that install's lineage;
   - scopes limited to what that install's manifest asks for.
3. **If a check fails,** nothing is approved. The owner sees why in "Profile C has": "signed with a different key here;
   approve it again", "not installed in this profile", "its connector manifest doesn't match here", or "revoked in this
   profile".
4. **A revoke the owner made in a profile wins** over an older approval. Uninstalling is not a revoke.

**What Cloak does:** call `hello` at start and after every `profile.switched`.
- If `approved` is false, show one line: "Approve Cyclone Cloak in Cyclone → Settings → Connectors in this profile". Do
  nothing else.
- If `pending` lists scopes, the owner hasn't approved them yet; keep working with `granted`.

## 3. Feature B: load a Cloak phone profile onto a Cyclone profile (the binding)

"Loading" Cloak profile **K** onto Cyclone profile **X** means: for each app **P** in X that Cloak covers, tell Cyclone
"in X (Android user N), P is bound to K", with `config.set.v1`, then report its health with `config.status.v1`.

### 3.1 The exact calls

```json
{"method": "config.set.v1", "args": {
  "profileId": "Cyclone_0123456789abcdef",
  "androidUserId": 11,
  "packageName": "com.example.app",
  "value": {
    "cloakProfileId": "pixel-8-work",
    "identityVersion": 1,
    "name": "Work phone",
    "manufacturer": "Google",
    "model": "Pixel 8",
    "androidRelease": "15",
    "sdkInt": 35
  }
}}
```

```json
{"method": "config.status.v1", "args": {"profileId": "Cyclone_0123456789abcdef", "androidUserId": 11,
  "packageName": "com.example.app", "state": "ready"}}
```

- **Client library:** `setConfig(profileId, androidUserId, packageName, value)` and
  `configStatus(profileId, androidUserId, packageName, state)`.
- **Unbind:** `setConfig(..., null)`.
- **Read back:** `getConfig(...)`, which returns `{value, state, storageKey, …}`.

### 3.2 What Cyclone accepts (or refuses)

- **`profileId`** is a ready, non-trashed Cyclone profile. **Never `owner`**; Main is refused with `NO_SUCH_PROFILE`.
- **`androidUserId`** is a JSON **integer** (not a string), equal to that profile's `androidUserId` in `profiles`.
- **`packageName`** is in that profile's `packages` (needs `profiles.apps.read`). Otherwise `BAD_REQUEST`.
  - If an app really installed in the profile is refused, Cyclone's list doesn't name it yet. Tell the owner to add it
    to the profile in Cyclone, and report it under Contract feedback.
- **`value`** is a JSON object of at most **4,096 UTF-8 bytes**. A request is at most 64 KB and 32 levels deep.
- **At most 256 bindings** per Cloak install, in each Cyclone.
- **`state`** is `unknown` (the default), `ready`, `degraded` or `failed`.

### 3.3 What Cyclone reads (one strict reader since alpha.121)

- **A binding counts only** when the stored envelope's `profileId` (string), `androidUserId` (integer) and
  `packageName` (string) match the tuple exactly, and `value.cloakProfileId` is a non-empty string (≤ 160 characters).
- **The identity summary, only with `identityVersion: 1`:**
  - `name`, `manufacturer` and `model` (≤ 80 characters each);
  - `androidRelease` (≤ 40);
  - `sdkInt` (1–1000).

  Any other version still counts as **bound**, but none of its fields are shown. New fields need a new
  `identityVersion`, added on Cyclone's side first (Contract feedback).
- **Several apps in one profile:** the most common identity wins. A tie shows "bound" with no summary.
- **Where it shows:**
  - Cyclone Profiles → info → "Cyclone Cloak" (the `cloakProfileId` per app);
  - the pill (§4);
  - Glass Home, which shows only the version-1 summary fields, never `cloakProfileId`.

**Never put these in `value`:** Android ID, IMEI, serial numbers, account names, tokens, passwords or anything secret.
The config store is not a vault.

### 3.4 Which Cloak writes

Bindings live in the Cyclone of the Cloak that wrote them (§1.4), and each Cyclone shows only its own.

- **Cloak in Main writes the bindings for every profile.** It may name another profile's `androidUserId`; that is
  allowed. Main's Cyclone feeds the main Profiles page and Glass. **Treat Cloak in Main as the authority.**
- **Cloak in profile C also writes C's own bindings,** so Cyclone in C shows the pill there too.
- **How the two Cloaks agree** is Cloak's business; Cyclone carries nothing of Cloak's.

### 3.5 When bindings go, and what Cloak should still do

**When Cyclone deletes or moves a binding (alpha.121):**
- it is deleted **only** when its profile is permanently deleted, or when Android says the app is gone from that
  profile (checked by the main profile's Cyclone);
- it is **not** deleted when Cyclone's app list for the profile is out of date;
- a profile restored under a **new Android user number** keeps its bindings (they are moved, and read back).

**What Cloak should still do (cheap, and safe):**
- **Key your own records by `profileId`, never by user number.**
- **Reconcile on start and on `profile.updated` / `profile.switched` / `profile.restored`:**
  1. read `profiles`;
  2. for each binding Cloak wants, read it with `getConfig`;
  3. write it if it is missing or different. Writing the same value again is harmless.
- **On `profile.removed`,** forget that profile in Cloak; Cyclone has already dropped its bindings.

## 4. Feature C: the rooted pill

### 4.1 Cyclone's pill (Profiles page, profile details)

The pill follows **Cloak's own health report**, taking the worst state across that profile's bound apps:

| Pill | Cloak's `state` |
|---|---|
| **Rooted** | `ready` or `unknown` |
| **Rooted · check** | `degraded` |
| **Rooted · not working** | `failed` |
| **Native** | no binding |

So **Cloak must report health.** Call `config.status.v1` for each binding whenever Cloak re-checks it:
- `ready`: applied, and Cloak's own checks pass;
- `degraded`: bound, but something is off (for example, root isn't granted to Cloak in this profile);
- `failed`: bound but not working;
- `unknown`: not checked yet.

### 4.2 Cyclone's root facts for Cloak's own pill: `root.status.v1` (minor 2)

```json
{"method": "root.status.v1", "args": {}}
→ {"version": 1,
   "rootManager": "magisk" | "kernelsu" | "apatch" | null,
   "profileRoom": {"limit": 8, "raisedByCyclone": true} | null,
   "profiles": [{"id": "Cyclone_0123456789abcdef", "rootProven": true | false | null, "checkedAt": 1760000000000 | null}]}
```

- **Scope** `device.root.read`. Check `hello.minor >= 2`.
- **`rootProven`:** the last time that profile's own Cyclone checked root (`su -c id`) on a switch into it. `null`
  means never checked. It is a fact from the last switch, not a live probe.
- **Whose facts:** what the Cyclone you call saw on its own switches. Cloak in Main gets the full picture; Cloak in C
  sees only the switches C's Cyclone made.
- **What it never contains:** commands, paths, versions or package names.

**Cloak's own pill,** per Cyclone profile from `profiles`:

| Cloak shows | When |
|---|---|
| **Rooted ✓** | Cloak's binding is `ready` and `rootProven` is true |
| **Rooted !** | `degraded`, `failed`, or `rootProven` false, with Cloak's reason |
| **Native** | no binding |
| nothing to act on | `state` is `setting_up` or `in_trash` |

Cloak's own root check is Cloak's: in its own process, with its own grant. **Never ask Cyclone to run anything.**

## 5. Feature D: ask to open another profile (minor 3)

**No connector switches a profile.** Cloak **asks**, and the owner answers on Cyclone's own screen.

```json
{"method": "profiles.open.request.v1", "args": {"profileId": "Cyclone_0123456789abcdef"}}
→ {"version": 1, "requested": true}
```

- **Before you call it:** check `hello.minor >= 3`, and that `profiles.open.request` is granted. Client:
  `requestOpenProfile(profileId)`.
- **`profileId`:** `owner` (Main) or a ready Cyclone profile from `profiles`.
- **What the owner sees:** **"Open Profile C?"** with **Open** and **Not now**.
  - **Only Open switches,** using the staged switch of §1.3 (way back armed, approval carried).
  - **On a locked phone** there is only a notification; nothing opens over the lock screen.
- **The result:** `requested: true` means only that the question was shown. **Learn the outcome from
  `profile.switched`.** "Not now" sends nothing.
- **Errors:**

  | Code | When | Cloak does |
  |---|---|---|
  | `NO_SUCH_PROFILE` | not ready, in Recently deleted, or unknown | refresh `profiles` |
  | `ALREADY_OPEN` | it is already in front | nothing |
  | `BUSY` | a task is running, a review waits, another request is still waiting (up to 2 min), or Cloak's own profile isn't in front | say "Cyclone is busy; try again in a moment"; no retry loop |
  | `RATE_LIMITED` | more than one request in 10 s | wait |

- **Call it from the Cloak in front.** Cloak talks to the Cyclone in its own profile, which can only ask while that
  profile is on screen.
- **A good place for it:** Cloak's "Open in Cyclone" button next to each profile, and Cloak's entries in Cyclone's slider
  (`selector.contribute`). A tap on an entry opens **Cloak's** `entryActivity`, which can then make this request.

## 6. Recommended wiring in Cloak

### 6.1 A small `CycloneBridge`

Run all of this off the main thread.

1. **Connect:** `CycloneConnector.connect(context)` (it waits for the bind).
2. **`hello()`** gives `approved`, `granted`, `pending` and `minor`.
   - Not approved: show the approve line (§2.2) and stop.
   - Enable features by `granted` and `minor`: bindings (`profiles.config`), root status (minor ≥ 2 and
     `device.root.read`), open requests (minor ≥ 3 and `profiles.open.request`).
3. **`profiles()`** gives ids, labels, `androidUserId`, `state`, `packages` and `current`.
4. **Reconcile bindings** (§3.5, in Main for every profile; elsewhere for the own profile only). Then report the health of
   each binding (§4.1).
5. **`rootStatus()`** for Cloak's pill (if enabled).
6. **Register the wake receiver.** On `com.cyclone.connector.WAKE` (no data), pull `events(since)`.

### 6.2 Events

| Event | Cloak does |
|---|---|
| `profile.switched` | `hello` again (the approval may have just arrived); re-read `profiles`; reconcile; re-report health; refresh root status |
| `profile.updated`, `profile.restored` | re-read `profiles`; reconcile (the user number may have changed) |
| `profile.created` | re-read `profiles`; the profile becomes bindable once `ready` |
| `profile.trashed` | stop acting on it; keep its bindings (a restore brings them back) |
| `profile.removed` | forget it |
| `reset: true` | re-read everything |
| unknown types | ignore |

- **De-duplicate by `seq`**, and keep `next` between runs.
- **The wake is only a hint:** also pull `events` when Cloak starts.

### 6.3 Errors (every call)

| Code | Meaning | Cloak does |
|---|---|---|
| `NOT_APPROVED` | not approved here, or signed by an unapproved key | show the approve line |
| `SCOPE_NOT_GRANTED` | the owner hasn't approved that scope | hide that feature |
| `UNKNOWN_METHOD` | an older Cyclone | hide that feature |
| `NO_SUCH_PROFILE` | the profile isn't ready, or is Main for `config.*` | refresh `profiles` |
| `BAD_REQUEST` | wrong shape, or an app not in the profile's list | fix it, or tell the owner (§3.2) |
| `RATE_LIMITED` | over 20 calls a second, or a second open request in 10 s | back off |
| `BUSY`, `ALREADY_OPEN` | open requests only (§5) | as in §5 |
| `INTERNAL` | Cyclone couldn't answer | retry later, once |

Branch on `code`, never on `message`.

## 7. Hard rules

1. **No secrets or hardware identifiers** in anything sent to Cyclone. The config store is not a vault.
2. **No commands.** Cloak never asks Cyclone to run a command, a shell or root. Cyclone exposes none.
3. **No phone control.** Cloak never asks Cyclone to approve, pay, send, delete, grant permissions, or create, switch
   or remove profiles. The open request is a question the owner answers.
4. **Never bind apps in Main** (owner decision).
5. **The owner's own profiles and apps only.**
6. **Be gentle:**
   - at most 20 calls a second;
   - never block the UI thread on Binder;
   - de-duplicate events.
7. **Unknown is fine:** ignore unknown fields, events and states. Reject only a broken structure.

## 8. Cyclone-side status (for reference)

| Id | What | Status |
|---|---|---|
| CC0 | Cloak is a cornerstone app: installed into every profile on the switch in, its Magisk grant shared | built, alpha.120 |
| CC1 | Cloak's approval carried and re-verified; a revoke wins; the outcome is in "Profile C has" | built, alpha.121 |
| CC2 | Bindings deleted only with the profile, or when Android says the app is gone | built, alpha.121 |
| CC3 | Bindings follow a profile to a new Android user number (moved, read back) | built, alpha.121 |
| CC4 | One strict reader for bindings and identities | built, alpha.121 |
| CC5 | The pill follows Cloak's `state` | built, alpha.121 |
| CC6 | `root.status.v1` + `device.root.read` (minor 2) | built, alpha.121 |
| CC7 | `profiles.open.request.v1` + `profiles.open.request` (minor 3), owner-confirmed screen | built, alpha.122 |
| CC8 | Bindings in Main | **decided: no** |
| — | `profile.switched` from the target profile's Cyclone too | built, alpha.119 |
| — | The PC switch with the way back and the journal | built, alpha.122 |

**Physical: UNVERIFIED.** None of this has been run on the owner's phone yet.

## 9. Test it

### 9.1 Without a phone

- **Vectors:** run Cloak's bridge against fakes built from `vectors.json`. These must hold:
  - hello says minor 3;
  - `root.status.v1` and `profiles.open.request.v1` without their scopes give `SCOPE_NOT_GRANTED`.
- **Envelopes:** add your own vectors, matching `CycloneCloakProfileBindingTest`:
  - a valid version 1;
  - an unknown `identityVersion` (bound, no summary);
  - a mismatched tuple, and a string `androidUserId` (neither counts);
  - `owner` refused.
- **Bridge unit tests:**
  - gating on `minor` and `granted`;
  - reconciling after `profile.updated` with a new user number;
  - de-duplication by `seq`;
  - the error table of §6.3;
  - no retry loop on `BUSY`.

### 9.2 On the rooted phone

The setup: Cyclone alpha.122, with Main, B and C. These are rows 5.1–5.9 of `docs/PROFILES_DEVICE_MATRIX.md`:

1. **Approval travels.** Approve Cloak in Main only, then switch Main → B on the phone. B's "has" line reads "Cloak ✓
   approved ✓", and Cloak in B gets `approved: true`.
2. **A revoke stays.** Revoke Cloak in B, then switch Main → B again. It stays revoked.
3. **Health reaches the pill.** From Cloak in Main, bind an app in B and report `ready`, then `degraded`. The pill reads
   Rooted, then "Rooted · check".
4. **Bindings survive an app change.** Change B's apps in Cyclone. The binding survives; only uninstalling the app from
   B removes it.
5. **Root status matches.** `root.status.v1` agrees with the "has" lines.
6. **Open, from B for C.** Not now changes nothing. Open switches, and Cloak in both profiles gets `profile.switched`.
7. **Locked phone.** Lock the phone and ask: there is only a notification.
8. **Limits.** Ask twice within 10 s: `RATE_LIMITED`. Ask for B while in B: `ALREADY_OPEN`.
9. **Main stays unbindable.** `config.set.v1` for `owner` is refused.
10. **The debug file stays clean.** Save Cyclone's debug file. It contains none of Cloak's `value` fields.

## 10. Deliverables (in Cloak's own project)

- **The manifest** and `cyclone_connector.xml` (§2.1).
- **`CycloneBridge`** (§6):
  - connect;
  - `hello` and gating;
  - reconcile and health reports;
  - root status;
  - the open request;
  - the event pull with a wake receiver;
  - the approve prompt.
- **Cloak's UI:**
  - the pill per profile (§4.2);
  - "Open in Cyclone" (§5);
  - the approve line (§2.2).
- **Tests** (§9.1).
- **A README section:**
  - what Cloak sends to Cyclone (§3.1 fields only);
  - what it never sends (§7);
  - how to revoke it (Cyclone → Settings → Connectors → Revoke, which deletes Cloak's data in that profile's Cyclone).

## 11. Report back

- What passed: the vectors, the unit tests, and each phone row of §9.2.
- What is **UNVERIFIED**. Say it plainly for anything not run on a real phone.
- **Contract feedback:** anything Cyclone should add or change, for example an app refused by `config.set.v1` although
  it is installed in that profile.
