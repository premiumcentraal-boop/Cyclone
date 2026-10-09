# Handoff: make Cyclone Cloak a compatible Cyclone connector (the Cyclone side)

**To the agent receiving this:** you are making **Cyclone Cloak** work as a "mod" on top of Cyclone's profiles:
- it shows a **rooted status pill** for each Cyclone profile;
- it lets the owner **load one of their Cloak phone profiles onto a Cyclone profile**.

This file explains:
- how Cyclone's root profiles work now (alpha.119) and how they will work (alpha.120–121);
- the exact contract Cloak has to speak;
- what Cyclone will add for Cloak, and when.

It is the **Cyclone side only**. How Cloak builds or applies a phone profile inside its own app is out of scope: Cyclone
never receives or needs those details.

- **Repository:** `premiumcentraal-boop/Cyclone` (Cyclone).
- **Baseline:** Cyclone `5.0.0-alpha.122.dev1` (version code 276). Written against alpha.119; §7 says what alpha.120 to
  alpha.122 built since.
- **Contract:** `cyclone.connector/1`, minor 3 since alpha.122 (SPEC §12 and §13). If this file and `tools/cyclone-connector-sdk/SPEC.md` disagree, SPEC.md
  wins for what exists today. This file wins for what is planned (§7).

**Don't edit Cyclone.** Cloak's work is in Cloak's own project. If Cloak needs something Cyclone doesn't offer, write it
under "Contract feedback" in your report (§9). The Cyclone agent builds it on the Cyclone side (`apps/mobile/app/**`).

---

## 0. Read first

1. `tools/cyclone-connector-sdk/SPEC.md`: the whole connector contract (manifest, scopes, calls, config §10,
   startup §11).
2. `apps/mobile/connector-client/` (the AAR): `CycloneConnector.connect(context)`, `getConfig`, `setConfig`,
   `configStatus`, `profiles`, `events`.
3. `apps/mobile/app/src/main/java/com/cyclone/mobile/connector/CycloneCloakProfileBinding.kt`: **exactly** how Cyclone
   reads Cloak's binding. Its test, `app/src/test/.../connector/CycloneCloakProfileBindingTest.kt`, holds valid and
   invalid envelopes.
4. `Cyclone V5 plan/57-hardened-profiles.md`: §2 (switcher), §3 (Cloak's connection reviewed: six known problems), §5
   (profile room), §6 (runs P1–P3).
5. `tools/cyclone-connector-sdk/schemas/vectors.json`: Cyclone's exact answers. Run your fakes against it.

## 1. How Cyclone's root profiles work (alpha.119)

### 1.1 What a profile is

**A Cyclone profile is a full secondary Android user:**
- Android user name `Cyclone_<16 lowercase hex>`;
- that string is also the profile id you see in `profiles`, for example `Cyclone_0123456789abcdef`;
- the label ("Profile B", "Work") is separate and can change; **the id never does**.

**The main profile** is the owner's own Android user (usually user 0). In the connector API it is the synthetic entry
`{"id": "owner", "label": "This phone", "kind": "owner"}`. It has no registry record.

**Every profile runs its own copy of Cyclone.** The same APK is installed into that Android user with
`pm install-existing`. Every profile also runs its own copy of every app in it, Cloak included. Android keeps them
apart:
- separate data and processes;
- uid = `userId * 100000 + appId`;
- separate grants.

**The profile list (the registry)** lives in each Cyclone. The **main profile's Cyclone is the authority**:
- On every switch, the list travels with the carry (alpha.119).
- From Main, it replaces the list.
- From another profile, only profiles that profile doesn't know yet are added.

### 1.2 Making a profile (root only)

- **Fixed commands only.** Cyclone creates the Android user with root, through a closed set of shape-checked commands
  (`ProfileSetupPlan`). Nothing a connector sends can reach those commands.
- **Room on the phone.** When Android's user limit is full, rooted phones can raise `fw.max_users`:
  - Magisk, KernelSU and APatch each use their own `resetprop`;
  - a small module, `/data/adb/modules/cyclone_profiles`, keeps the limit after restarts;
  - the owner confirms first, Cyclone reads the change back, and it can be undone (alpha.118).
- **Preparing the new profile.** Cyclone:
  - installs itself and the **support apps** into it: the root manager (a hidden Magisk app included), Shizuku and
    others on the allowlist;
  - copies Magisk's per-app grant and sets Magisk's multiuser mode;
  - **proves root from the new profile's own Cyclone**, which runs `su -c id` itself (alpha.119).

  On KernelSU and APatch the owner allows Cyclone in that profile once, by hand. Cyclone tells them the exact step.
- **Cloak in a new profile.** Cloak is **not yet** installed into new profiles automatically. It becomes a
  "cornerstone app" in alpha.120 (§7, CC0).

### 1.3 Switching (alpha.119)

A switch runs in stages, and each stage goes into the debug file:
1. **Preflight**, then **prepare**, then **carry** (memory, skills, the profile list).
2. **The way back.** The target's Cyclone is asked to say hello once its profile is in front. When it answers that it
   is listening, a fixed root-side timer is armed: it switches back if no hello arrives within 45 s.
3. **Switch.** Android's `am switch-user`; Cyclone waits for it to confirm.
4. **Hello.** The target's Cyclone writes its hello, which disarms the timer. It then **records `profile.switched` for
   its own connectors** and shows a quiet "Back to Main" notice.

**What this means for Cloak:**
- **Both copies hear about it.** The **target profile's** Cloak gets `profile.switched` from its own Cyclone, and the
  source profile's Cloak gets it from the source Cyclone. Before alpha.119 only the source heard about it.
- **`profiles.current`** answers "which profile is in front":
  - from the Cyclone in front, it is reliable;
  - from a background profile's Cyclone, it is the last switch that Cyclone made, or `null` when it can't tell.

  Treat it as a hint.
- **Connectors can't switch profiles.** Only the owner can, from Cyclone's own screens. For a way to *ask* the owner,
  see §7, CC7.

### 1.4 What is per profile (important for Cloak)

| Thing | Where it lives | Consequence for Cloak |
|---|---|---|
| Connector **approval** | each profile's Cyclone (`cyclone_connectors` prefs) | Cloak must be approved **in every profile** where it calls Cyclone. Approvals aren't carried yet (§7, CC1). |
| Binder door | the Cyclone **in the caller's own Android user** | Cloak in Profile C can only talk to Cyclone in Profile C (`ConnectorDiscovery.caller` refuses other users). |
| **Config store** (`config.*.v1`) | each Cyclone, namespace = connector id + **caller's** Android user | A binding written by Cloak in Main is seen by Main's Cyclone; one written in C, by C's Cyclone. |
| Profile list | each Cyclone; Main is the authority | Ids are the same everywhere. Labels sync from Main on every switch. |
| Startup provider | per user, never persisted | Re-register in every profile after either process restarts (SPEC §11). |

## 2. What "compatible" means: four features

| # | Feature | Status on the Cyclone side |
|---|---|---|
| A | Cloak is an approved connector with the right scopes | **Works today** |
| B | **Load a phone profile onto a Cyclone profile** (bind a Cloak profile to a Cyclone profile's apps) | **Works today** (`config.set.v1` with Cloak's envelope) |
| C | **Rooted pill:** Cyclone's Profiles page and Glass show Rooted/Native per profile; Cloak shows its own pill with live health | **Works since alpha.121:** the pill follows Cloak's `state` (CC5), and `root.status.v1` gives Cyclone's root facts (CC6) |
| D | Open another Cyclone profile from Cloak | **Works since alpha.122:** `profiles.open.request.v1` asks the owner on Cyclone's own screen (CC7, SPEC §13) |

## 3. Feature A: be an approved connector

**The manifest.** In Cloak's `AndroidManifest.xml`, declare the marker service exactly as SPEC §2 shows: permission
`com.cyclone.mobile.permission.CONNECTOR_HOST` and action `com.cyclone.connector.CONNECT`. Then add
`res/xml/cyclone_connector.xml`:

```xml
<cyclone-connector
    contract="cyclone.connector/1.1"
    id="cyclone-cloak"
    label="Cyclone Cloak"
    scopes="profiles.read profiles.apps.read profiles.config profiles.startup events.profiles selector.contribute"
    entryActivity=".CycloneEntryActivity"
    wakeReceiver=".CycloneWakeReceiver" />
```

**The rules:**
- **`id` must be exactly `cyclone-cloak`.** Cyclone looks the binding up under this id
  (`CycloneCloakProfileBinding.CONNECTOR_ID`). Any other id means no pill.
- **Scopes:**
  - `profiles.apps.read` is required to learn which packages a profile has. A binding is only accepted for a package in
    that profile's list.
  - `profiles.config` is required for feature B.
  - `events.profiles` with a `wakeReceiver` is how Cloak hears about switches. The receiver must be protected by
    `com.cyclone.mobile.permission.WAKE_CONNECTOR`.
  - `profiles.startup` is optional. Use it if Cloak wants a callback before Cyclone opens an app in a profile (SPEC
    §11, 250 ms total, never blocking).
  - `selector.contribute` is optional: Cloak's own entries in Cyclone's profile slider.
- **Signing:** sign every Cloak build with the same key, or rotate it with a lineage (APK Signature Scheme v3). Approval
  is by package **and** certificate lineage. A new unrelated key reads as "signer changed" and calls fail with
  `NOT_APPROVED`.
- **The owner approves Cloak** in Cyclone → Settings → Connectors, **in each profile** (until CC1).
- **Start every session with `hello`.** Read `approved`, `granted`, `pending` and `minor`. Never assume a scope; check
  `granted`.

## 4. Feature B: load a phone profile onto a Cyclone profile (the binding)

"Loading" a Cloak phone profile onto a Cyclone profile means Cloak tells Cyclone: "in Cyclone profile X (Android user
N), app P is bound to Cloak profile K". Cloak writes that through `config.set.v1`, once per (profile, user, package).

### 4.1 The exact call

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

Then report its health:

```json
{"method": "config.status.v1", "args": {"profileId": "Cyclone_0123456789abcdef", "androidUserId": 11,
  "packageName": "com.example.app", "state": "ready"}}
```

With the client library: `cyclone.setConfig(profileId, userId, pkg, value)`, then
`cyclone.configStatus(profileId, userId, pkg, "ready")`. To unbind: `setConfig(..., null)`.

### 4.2 What Cyclone checks (or the call fails)

- **`androidUserId`** is a JSON **integer**, not a string, and must equal that profile's `androidUserId` from
  `profiles`.
- **The profile** must be `ready` and not `in_trash`. Otherwise `NO_SUCH_PROFILE`.
- **`packageName`** must be in that profile's `packages` (needs `profiles.apps.read`). Otherwise `BAD_REQUEST`.
- **`value`** is a JSON object of at most **4096 UTF-8 bytes**, nested at most 32 levels. The whole request is at most
  64 KB.
- **At most 256 tuples** per Cloak installation, per profile copy of Cyclone.
- **`state`** is `unknown` (the default), `ready`, `degraded` or `failed`.

### 4.3 What Cyclone reads from it

- **The binding:** `value.cloakProfileId` (a trimmed string, ≤ 160 characters, required), and only when the stored
  envelope's `profileId`, `androidUserId` and `packageName` match the tuple.
- **The identity summary,** only when `identityVersion == 1`:
  - `name`, `manufacturer` and `model` (≤ 80 characters each);
  - `androidRelease` (≤ 40);
  - `sdkInt` (1–1000).

  Any other `identityVersion` still counts as **bound**, but its fields are never read or shown. Add fields only with a
  new `identityVersion`, after the Cyclone side has added it (contract feedback).
- **Several apps in one profile:** the most common identity among its apps wins. A tie or a minority means Cyclone shows
  "bound" without the identity summary.

**Never put these in `value`:** Android ID, IMEI, serial numbers, account names, tokens, passwords or any other
secret.
- The config store is not a vault.
- Cyclone shows `cloakProfileId` on the phone (Profiles → info → "Cyclone Cloak").
- Glass on the PC gets only the version-1 summary fields above, never the raw `cloakProfileId` (alpha.116).

### 4.4 Which Cloak writes, so the pill shows everywhere

Bindings are stored per caller's Android user (§1.4), and each Cyclone shows only its own store. So:

- **Cloak in Main writes the binding for every profile** (its target `androidUserId` may be another user's; that is
  allowed). Main's Cyclone feeds the main Profiles page and Glass, so this is the one that matters most. **Treat Cloak
  in Main as the authority**, just as Cyclone's registry is.
- **Cloak in Profile C also writes the bindings for Profile C,** so Cyclone running in C shows the pill too.
- How the two copies of Cloak agree is Cloak's own business. They are separate installs in separate Android users.
  Cyclone carries nothing of Cloak's.

### 4.5 Known fragilities until alpha.121 (plan 57 §3), and what Cloak should do meanwhile

| Problem | Effect today | Do this in Cloak until it's fixed |
|---|---|---|
| D13: a binding is deleted when its package is not in the profile's `packages` list. That list is the last setup selection, not the apps really installed. | A binding can vanish after the owner changes a profile's apps, or when a stale list arrives. | On every `profile.updated` / `profile.switched` event and on start, **re-read with `config.get.v1` and re-write what is missing**. Writing the same value again is harmless. |
| Bindings are tied to `androidUserId`. | A profile restored with a new Android user id loses them. | Key your own records by **`profileId`**. When `profiles` shows a new `androidUserId` for the same id, write the bindings again under the new id. |
| Two lenient/strict parsers. | A malformed envelope can count as bound on one screen and not on another. | Always write the exact envelope in §4.1: integer `androidUserId`, string `cloakProfileId`, integer `identityVersion`. |
| No approval in new profiles (D12). | Cloak in a new profile gets `NOT_APPROVED`. | Show "Approve Cyclone Cloak in Cyclone → Settings → Connectors (this profile)". Retry on `hello`. |
| Main has no registry record. | Apps in Main can't be bound through Cyclone. | Don't try; `owner` is refused by `config.*`. Owner question 3 in plan 57. |

## 5. Feature C: the rooted pill

### 5.1 Today

**On Cyclone's Profiles page:**
- each ready profile shows **Rooted** when Cyclone has at least one valid Cloak binding for an app in it, and **Native**
  otherwise;
- Glass Home shows the same (`profiles.cloak` → `/v1/devices/{id}/profiles/cloak-identities`).

**What Rooted means today:** a Cloak binding is configured. It does **not** say that root works right now, or that
Cloak's module is active.

### 5.2 What Cloak should do now (works today, future-proof)

- **Report health per tuple** with `config.status.v1`:
  - `ready`: the binding is applied and Cloak's own checks pass;
  - `degraded`: bound, but something is off (for example, a root grant is missing in this profile);
  - `failed`: bound but not working;
  - `unknown`: not checked yet.

  Update it whenever Cloak re-checks. Cyclone stores it now; it reaches the pill with CC5.
- **Cloak's own pill** (in Cloak's UI), per Cyclone profile from `profiles`:
  - **Rooted ✓:** Cloak's binding is present and `ready`;
  - **Rooted !:** `degraded` or `failed`, with Cloak's own reason;
  - **Native:** no binding;
  - **Setting up / Recently deleted:** from `state`, `setting_up` or `in_trash`. Show no pill action.
- Cloak's own root check is Cloak's: it runs in its own process, with its own grant. **Never ask Cyclone to run
  anything.** Connectors can't, by design.

### 5.3 Coming from Cyclone (CC5, CC6)

- **CC5:** Cyclone's pill uses the `state`:
  - **Rooted** (`ready` or `unknown`);
  - **Rooted · check** (`degraded`);
  - **Rooted · not working** (`failed`);
  - **Native** (no binding).
- **CC6:** a read-only call, so Cloak can show Cyclone's **own** root facts next to its own:

```json
{"method": "root.status.v1", "args": {}}
→ {"version": 1,
   "rootManager": "magisk" | "kernelsu" | "apatch" | null,
   "profileRoom": {"limit": 8, "raisedByCyclone": true} | null,
   "profiles": [{"id": "Cyclone_0123456789abcdef", "rootProven": true | false | null, "checkedAt": 1760000000000 | null}]}
```

- Scope `device.root.read` ("See whether root works in your profiles"), approved separately.
- `rootProven` is the last root check the profile's own Cyclone made on a switch into it; `null` means never checked.
- No commands, paths, versions or package names. Ignore fields you don't know.
- Check `hello.minor >= 2` before calling it. An older Cyclone answers `UNKNOWN_METHOD`; keep working without it.

## 6. Feature D: opening another profile from Cloak (alpha.122)

**No connector switches a profile by itself** (SPEC §1): a switch changes the whole phone. Cloak **asks**, and the owner
answers on Cyclone's own screen. The owner signed this off on 2026-10-09.

```json
{"method": "profiles.open.request.v1", "args": {"profileId": "Cyclone_0123456789abcdef"}}
→ {"version": 1, "requested": true}
```

- **Before you call it:**
  - check `hello.minor >= 3`; an older Cyclone answers `UNKNOWN_METHOD`;
  - ask for the scope `profiles.open.request` ("Ask you to open a profile"), which is approved separately.
- **`profileId`:** `owner` (Main) or a ready Cyclone profile from `profiles`.
- **What happens:**
  - Cyclone shows **"Open Profile C?"** with **Open** and **Not now**. On a locked phone it only posts a notification;
    the screen never opens by itself over a lock screen.
  - **Only the owner's tap on Open** runs Cyclone's staged switch (§1.3), with its way back.
- **Learn the result only from `profile.switched`.** `requested: true` means the question was shown, never that the
  switch happened. "Not now" sends nothing.
- **Errors:**

  | Code | When |
  |---|---|
  | `NO_SUCH_PROFILE` | the profile isn't ready |
  | `ALREADY_OPEN` | it is already in front |
  | `BUSY` | a task is running, a review is waiting, another request is still waiting (2 minutes), or Cloak's profile isn't the one in front |
  | `RATE_LIMITED` | more than one request in 10 s |

  Back off; don't retry in a loop.
- **Call from the profile in front.** Cloak calls the Cyclone in its own profile, and that Cyclone can only ask the owner
  when its profile is on screen.
- **Selector entries are unchanged:** with `selector.contribute`, a tap on one of Cloak's entries in Cyclone's slider
  opens **Cloak's** `entryActivity`. Cyclone never switches for an entry; an entry can call the request above if it
  wants a switch.

## 7. Cyclone-side work for Cloak (the Cyclone agent builds these)

| Id | What | Run / alpha | Status |
|---|---|---|---|
| CC0 | Cloak becomes a **cornerstone app**: installed into new profiles with `install-existing`, enabled, verified; its root grant copied where the manager allows | P2 / alpha.120 | **built, alpha.120** |
| CC1 | **Carry Cloak's approval** to new profiles by package and certificate lineage, re-verified against the certificate installed in the target. If it differs, don't carry it and say why (D12). | P3 / alpha.121 | **built, alpha.121** |
| CC2 | Delete a binding only when Android says the app is gone from that user (`pm list packages --user`), and only on the main profile's Cyclone (D13) | P3 / alpha.121 | **built, alpha.121** |
| CC3 | Bindings keyed by the stable `Cyclone_…` id; Android user id migrated with read-back | P3 / alpha.121 | **built, alpha.121**: migration with read-back; the storage key still includes the user id |
| CC4 | One strict parser for bindings and identities | P3 / alpha.121 | **built, alpha.121** |
| CC5 | The pill shows Cloak's `state` (§5.3) | P3 / alpha.121 | **built, alpha.121** |
| CC6 | `root.status.v1` + scope `device.root.read`, contract minor 2 | P3 / alpha.121 | **built, alpha.121** (minor 2) |
| CC7 | `profiles.open.request.v1` + scope `profiles.open.request`, owner-confirmed sheet, minor 3 | alpha.122 | **built, alpha.122** (owner signed off 2026-10-09) |
| CC8 | Bindings for apps in Main | decided | **No** (owner, 2026-10-09): every profile except Main; `config.*` refuses `owner` |
| done | `profile.switched` also from the **target** profile's Cyclone (§1.3) | P1 / alpha.119 | **built** |

**Contract rules for Cloak:**
- Minor versions only **add** methods, fields and scopes; both sides ignore what they don't know (SPEC §8).
- Gate every new call on `hello.minor`, and treat `UNKNOWN_METHOD` as "not yet".

## 8. Hard rules

1. **No secrets or hardware identifiers** in `value`, `ext`, entries or anything else sent to Cyclone. Cyclone refuses
   values that look like secrets in `ext`, and the config store is not a vault.
2. **No commands.** Cloak never asks Cyclone to run a command, a shell or root. Cyclone never exposes one to a
   connector or to the model.
3. **No phone control.** Cloak never asks Cyclone to approve, pay, send, delete, grant permissions, create, switch or
   remove profiles. CC7 is a request the owner answers in Cyclone.
4. **The owner only.** Bindings are for the owner's own profiles and apps.
5. **Be gentle:**
   - 20 calls a second at most;
   - de-duplicate events by `seq`;
   - on `reset: true`, read `profiles` again;
   - never block on Binder from the UI thread.
6. **Unknown is fine:** ignore unknown fields, event types and states. Reject only a broken structure.

## 9. Test it

**Without a phone:**
- Run Cloak's Cyclone client code against fakes built from `tools/cyclone-connector-sdk/schemas/vectors.json`.
- Add your own vectors for the §4.1 envelope. Feed them to the same parser rules as `CycloneCloakProfileBindingTest`:
  - valid version 1;
  - unknown `identityVersion`;
  - an envelope that doesn't match its tuple;
  - a string `androidUserId` (must not count).

**On a rooted phone** with Cyclone alpha.119 or newer, Main plus Profiles B and C:
1. Approve Cloak in Main (and in B and C) in Cyclone → Settings → Connectors. `hello` shows `approved: true`.
2. From Cloak in Main, bind one app in B to a Cloak profile and report `ready`. Cyclone's Profiles page shows **Rooted**
   on B; B's info page shows the Cloak profile id.
3. Switch Main → B in Cyclone. Cloak in B is woken and reads `profile.switched` for B. Cloak in Main reads it too.
4. In Cyclone, change B's apps. Confirm the binding survives, or that Cloak re-writes it (§4.5).
5. Save Cyclone's debug file (Profiles → debug buttons). It must contain none of Cloak's `value` fields. Connector
   names appear only for `ext` notes, by name.

**Report back:**
- what passed;
- what is **UNVERIFIED** (say it plainly for anything not run on a real phone);
- the "Contract feedback" list for the Cyclone agent.

## 10. Deliverables (in Cloak's own project)

- The manifest and `cyclone_connector.xml` (§3).
- A small `CycloneBridge` layer on the client AAR:
  - `hello` and its gating;
  - binding writes with re-assertion (§4);
  - status reports (§5.2);
  - the event pull with a wake receiver;
  - the per-profile approval prompt.
- Cloak's pill UI (§5.2) and the "Open in Cyclone" hint (§6).
- Tests: envelope vectors, gating on `hello.minor`, re-assertion after `profile.updated`, de-duplication by `seq`.
- A README section:
  - what Cloak sends to Cyclone (§4.1 fields only);
  - what it never sends (§8);
  - how to revoke it (Cyclone → Settings → Connectors → revoke deletes all of Cloak's data in that profile's Cyclone).
