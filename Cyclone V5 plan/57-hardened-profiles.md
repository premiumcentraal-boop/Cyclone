# 57 · Hardened profiles: a switcher that always works, complete new profiles, and a debug file for every failure

**Owner's report (2026-10-09).** Creating a third profile ("Profile C") showed:

> **Profile B already exists**
> Cyclone found an existing Profile B instead of creating another one.
> Continue the saved Profile B setup.

**Owner's goals:**
1. A hardened profile switcher that always works.
2. New profiles that carry their cornerstone apps: root apps such as Magisk when present, and Cyclone itself with its
   full settings and profiles.
3. Cyclone Cloak's connection settings kept, and checked to be built correctly.
4. An error screen that downloads a full debug file, so every mistake can be fixed easily.
5. A multi-stage alpha plan.

This plan is:
- the deep dive (§1–§4);
- the design for making room on rooted phones (§5);
- the runs (§6);
- the next build step by step (§7);
- decisions and open questions (§8).

It is checked against the code at alpha.117 dev2.

## 1. Why "Profile B already exists" appeared for Profile C

### 1.1 The most likely cause: Android said "no more users", and Cyclone read it as "already exists"

`ProfileFailureClassifier.fromCommand` (`runtime/workspaces/ProfileProvisioningContract.kt`) reads the text Android
returns from `pm create-user Cyclone_<16 hex>`. For a create, the **first** test is:

```kotlin
if (lower.contains("already exists")) return failure(PROFILE_ALREADY_EXISTS, platform)
```

Android's own refusal when a user type has reached its limit, or is switched off, is (AOSP `UserManagerService`):

> `Cannot add more users of type android.os.usertype.full.SECONDARY. Maximum number of that type already exists.`

That sentence contains "already exists", so it is classified as "Profile B already exists" **before** any of the
limit checks run. The one limit check that knows this wording only matches `profile.managed`. Cyclone profiles have
been full secondary users since 4.2.7, so the check never fires.

**Then the recovery path makes it worse** (`ProfileSetupRuntime.create`):
1. On `PROFILE_ALREADY_EXISTS` it lists the users again and looks for one with the new profile's name.
2. The name is a fresh random `Cyclone_…`, so nothing matches, and the recovery answer is `Create`.
3. The code then throws the original misread failure: "Profile B already exists … Continue the saved Profile B setup".

A brand-new random name cannot really already exist, so this message is wrong by construction.

**The test suite misses it** because it uses an invented string (`"Error: user already exists"`), not Android's.

### 1.2 Why the phone could be at its limit with only "A, B and C"

1. **Recently deleted profiles still count.**
   - **Remove** only stops a profile (`ProfileLifecycle.remove` → `am stop-user`). It stays an Android user for 7
     days (`ProfileTrash.TRASH_DAYS`).
   - Android counts it. Cyclone's own pre-check counts it too, but the owner can't see it as "using a slot".
2. **Android counts more users than Cyclone's pre-check does.** `SecondaryUserProvisioningPolicy` counts only full,
   non-partial users against `pm get-max-users`. Android also counts:
   - work profiles;
   - Private Space (Android 15);
   - clone profiles;
   - partial users left by an interrupted create;
   - per-type limits that OEM or ROM settings can set.

   So Cyclone's pre-check can say "room left" while Android says no.
3. **Earlier failed attempts** can leave partial users behind. They are never cleaned up.

**What to confirm on the owner's phone** (until run P0 adds the debug file), with a PC and adb:

```
adb shell cmd user list --all --verbose
adb shell pm get-max-users
adb shell cmd user list --all --verbose | findstr /i "partial guest PRIVATE MANAGED"
```

The debug file (§4) will contain exactly this, plus Android's raw answer to the create.

### 1.2a Rooted phones can make room

The owner asked whether Magisk can make room for more profiles on rooted phones. It can, for Android's total user
limit. §5 is the full design.

### 1.3 Other defects found on the way

| # | Defect | Where | Effect |
| --- | --- | --- | --- |
| D1 | Limit text read as "already exists" | `ProfileFailureClassifier` | Wrong error, wrong advice (§1.1) |
| D2 | Every message says "Profile B" | `failure()` texts, `boundary()`, the stage messages in `create()` | Creating Profile C or D reads as if B were broken |
| D3 | The **+** on the Profiles page opens setup without starting a clean plan | `CycloneProfilesPage` (`setup = true`), `ProfileSetup429` | Opens on the previous profile's leftover journal. A new name typed there can **rename the previous profile** (`persistFriendlyName` → `rename(currentId)`). |
| D4 | Pre-check counts users differently from Android | `SecondaryUserProvisioningPolicy` | "Room left" when there is none (§1.2) |
| D5 | Partial users from failed creates are never cleaned up | `create()` | Slots silently used up |
| D6 | The raw Android answer is kept but never shown or saved (`platformMessage`, `lastCommandResult`) | `ProfileSetupRuntime` | No way to see what Android really said |
| D7 | The switch waits only 3 s (20 × 150 ms) for Android to confirm | `openProfile` | Slow phones and first boots fail "Android hasn't completed switching" |
| D8 | Root in a new profile works only with Magisk 26+; KernelSU and APatch are refused | `ProfileBootstrapRuntime.prepareMagisk` | "The switch was cancelled" on those phones |
| D9 | A **hidden Magisk app** (renamed package) isn't found | `ProfileRequiredPackages.supportAllowlist` | The root manager is missing in the new profile |
| D10 | Few settings travel (§2.2) | `CarryRules.settings`, `ProfileBootstrapContract.aiKeys` | A new profile's Cyclone is not "the same Cyclone" |
| D11 | The registry copy in another profile is taken once, at prepare time, and goes stale | `prepareInternal` (`profiles`) | That profile's Cyclone doesn't know later profiles and can't switch to them |
| D12 | Cloak's approval is per profile and not carried | `cyclone_connectors` | Cloak is "not approved" in every new profile until approved there again (§3) |
| D13 | Cloak settings are deleted when an app leaves a profile's saved app list, even on a stale copy | `ProfileConfigStore.removed` | Cloak bindings can vanish silently (§3) |
| D14 | No escape hatch if the target profile's Cyclone never starts | `openProfile` | The owner can be left in a profile without a way back through Cyclone |

## 2. The switcher and new profiles, as built today

### 2.1 Switch (`ProfileSetupRuntime.openProfile`)

The steps today:
1. Check that no task is running.
2. Verify root.
3. List users and check the target is ready, owned and valid.
4. Prepare the target (`ProfileBootstrapRuntime.prepare`): copy Cyclone and the allowlisted root apps, start it,
   verify it is unlocked, apply Magisk policies, mirror permissions, accessibility and the listener, send AI settings
   plus the encrypted OpenRouter key, and prove root from the target's Cyclone UID.
5. Carry memory, skills and settings.
6. `am switch-user`.
7. Wait 3 s for the current user to change.

**Good:**
- Fixed commands only.
- Ownership checks.
- Proves root before leaving.
- Refuses during tasks.

**Missing:**
- an adaptive wait;
- a dead-man rollback;
- other root managers;
- registry sync;
- a step log.

### 2.2 What a new profile gets today

| Carried | Not carried (and should be, unless it is a secret) | Never carried, by design |
| --- | --- | --- |
| Cyclone app (`install-existing`), Shizuku / Magisk / KernelSU / KernelSU Next / APatch when installed under their normal package names | Hidden Magisk app; other root tools (LSPosed manager etc.); Cyclone Cloak | — |
| Runtime permissions (notifications, mic, calendar), overlay, exact alarms, battery exemption, assistant role, accessibility, notification listener | Cloak's connector approval (`cyclone_connectors`) | PC pairing and gateway trust (`cyclone_gateway_trust_v33`, `cyclone_pc_gateway_v293`, `cyclone_desktop_gateway_v1`) |
| `cyclone_ai`: model, reasoning effort, access profile, safe mode. OpenRouter key, sealed for the target's Keystore. | Hands (`cyclone_hands`), Modes (`cyclone_modes`), Fast Path (`cyclone_fast`), planes, setup cards, owner notes (`cyclone_user_md`), Automation Studio routines, app maps and manuals, Market installs, owner skills marked shareable, `cyclone_ui` beyond `visual_quality` | The vault (`cyclone_vault_secrets_v1`), codes, sealed leases, sessions, chat history, run logs |
| Carry on every switch: memory, Brain micro-skills and paths, notes, `cyclone_drive`, `cyclone_ui.visual_quality` | The profiles registry after prepare (D11) | — |

## 3. Cyclone Cloak's connection: is it built correctly?

**Correct:**
- **Approval is strong:** by package plus signing-certificate lineage (`ConnectorRuntime`).
- **The Binder door** checks the caller from the kernel (uid → package) and the scope.
- **Cloak's per-app binding** is read only from its own namespaced envelope, matching `profileId`,
  `androidUserId` and `packageName` (`CycloneCloakProfileBinding`).
- **Unknown schema versions** are shown as bound but never interpreted.
- **Public identity fields** are trimmed and length-capped.
- **Registry `ext`** is preserved through every save.

**Not correct, or fragile:**
1. **No approval in new profiles (D12).**
   - Each profile's Cyclone keeps its own `cyclone_connectors`, and the bootstrap doesn't carry it.
   - Cloak in Profile C gets `NOT_APPROVED` until the owner approves it in C's Settings.
   - **Fix:** carry the approval by package and certificate lineage, then re-verify against the certificate actually
     installed in the target. If it differs, don't carry it and say why.
2. **Settings deleted by app-list changes (D13).**
   - `ProfileConfigStore.removed` runs on every registry save. It deletes any Cloak config whose app isn't in that
     profile's `record.packages`.
   - `record.packages` is the last setup selection, not the apps really in the profile. Apps added later through the
     app manager, or a stale registry copy in another profile, make real bindings disappear.
   - **Fix:** delete only when Android says the app is gone from that user (`pm list packages --user`), and only on
     the main profile's Cyclone (the registry owner).
3. **Bindings are tied to the Android user id.** A profile restored or re-created with a new user id loses its Cloak
   bindings.
   - **Fix:** key by the stable `Cyclone_…` id and migrate the user id with read-back.
4. **Two parsers disagree.** `readBindings` uses lenient `optInt` / `optString`; `identitySnapshot` uses strict
   checks. A malformed envelope can count as bound in one view and not the other.
   - **Fix:** one strict parser for both.
5. **The main profile has no record.** Cloak bindings can exist only for B, C and so on, never for apps in Main.
   - **Owner question 3** (§6): should Cloak bind Main's apps too?
6. **Switch events go only to connectors in the profile that did the switch.** Cloak in the target profile isn't
   told it became active.
   - **Fix:** after a verified switch, the target's Cyclone sends `SWITCHED` to its own connectors.

## 4. The debug file

**When it is offered:**
- on every profile error screen (and on the rescue screen);
- in Settings → Profiles → **Debug file**;
- later, from Glass through the gateway.

**What it is:** one file, `cyclone-profile-debug-<time>.json` (zipped with a readable `summary.txt`).

| Section | Content |
| --- | --- |
| `app` | Cyclone version, version code, build, the Android release and SDK, device model, security patch |
| `root` | Which root manager was found (Magisk, hidden Magisk package, KernelSU, KernelSU Next, APatch), its version, Magisk `multiuser_mode`, whether `su -c id` works in each profile Cyclone touched |
| `users` | `cmd user list --all --verbose` (all users, types, flags, partial, running), `pm get-max-users`, `canAddMoreUsers`, Android's count vs. Cyclone's |
| `registry` | Every Cyclone profile: id, label, Android user id, stage, ready, in trash, app count, `ext` connectors **present** (ids and schema versions only, never values) |
| `journal` | The setup journal's keys and stage |
| `steps` | The last 200 privileged steps: operation, the fixed command shape, exit code, time taken, **raw Android output** (8 KB cap, redacted), classification |
| `switch` | The last 20 switches: from, to, each stage with time, outcome, rollback used |
| `carry` | The last carry reports (counts only) |
| `connectors` | Approved connectors (package, certificate digest prefix, scopes) and Cloak binding health per profile (bound apps, conflicts, schema) |
| `error` | The failure shown: kind, headline, reason, Android's raw message |

**Never in it:**
- keys, tokens, OTPs, passwords;
- the vault, chats, memories' text;
- app data;
- Cloak's binding values.

Values that look like secrets are removed with the same check the Brain uses (`MindMemory.looksSecret`), plus fixed
patterns. A CI test plants secrets and checks none survive.

**How it is saved:**
- **Save to file:** `ACTION_CREATE_DOCUMENT`, so the owner picks Downloads.
- **Share:** a FileProvider `cache-path`, then the share sheet.
- Nothing is uploaded by itself.

## 5. Profile room on rooted phones (design)

### 5.1 Goal

On a rooted Cyclone phone, "the phone has no room for another profile" should not be the end. With the owner's yes,
Cyclone raises Android's limit for this phone, keeps it after reboots, and can put it back exactly as it was.

### 5.2 Android has two different limits, and Cyclone must tell them apart

| Limit | Where Android keeps it | Android's words when it is hit | Can root raise it? |
| --- | --- | --- | --- |
| **Total users** | `UserManager.getMaxSupportedUsers()` = `fw.max_users`, else `config_multiuserMaximumUsers`. Read on every call. Counts every alive user except guests, profiles included. | `Cannot add user. Maximum user limit is reached.` | **Yes.** `fw.max_users` is a normal system property: `resetprop` changes it at once, and a module keeps it at boot. |
| **Per user type** | The user type's `maxAllowed`, or the type switched off. Full secondary users are unlimited in stock Android; ROMs can cap them through `config_user_types` / `config_userTypeCustomizations`. | `Cannot add more users of type android.os.usertype.full.SECONDARY. Maximum number of that type already exists.` | **Not by a property.** Only a framework resource overlay could change it. Heavier and phone-specific; §5.8. |
| **Running users** (not a creation limit) | `config_multiuserMaxRunningUsers` | — (Android stops the least-recent background user) | Not needed. Cyclone starts the target user before every switch anyway. |

**How Cyclone tells which one fired:**
1. **Android's words** in the raw answer (P0's classifier).
2. **`dumpsys user`.** It prints each user type with its maximum allowed and whether it is enabled. Cyclone reads it
   read-only (new fixed op `DUMP_USERS`), parses only the type lines, and keeps the result in the debug file.
3. **The counts:** alive non-guest users vs. `pm get-max-users`, and full secondary users vs. that type's maximum.

| Verdict | What the owner sees |
| --- | --- |
| `TOTAL_LIMIT` | The room screen with **Allow more profiles** |
| `TYPE_LIMIT` / `TYPE_DISABLED` | "This phone's system allows only N extra users of this kind." Then **Delete now** / **Clean up**, a **Save debug file** button, and no false promise. |
| `UNKNOWN` | The debug file, and the Android words shown as they are |

### 5.3 Which root managers, and how

| Manager | Detected by (fixed, read-only) | Sets the property with | Keeps it after reboot with |
| --- | --- | --- | --- |
| Magisk 24+ (hidden app too) | `magisk -V` | `magisk resetprop fw.max_users <n>` | Module `/data/adb/modules/cyclone_profiles/` with `system.prop` |
| KernelSU / KernelSU Next | `ksud -V` (`/data/adb/ksud`) | `/data/adb/ksu/bin/resetprop fw.max_users <n>` | The same module layout (KernelSU reads `system.prop`) |
| APatch | `apd -V` (`/data/adb/apd`) | `/data/adb/ap/bin/resetprop fw.max_users <n>` | The same module layout |
| None, or unknown | — | Not offered | Not offered |

**Rules for the commands:**
- Every command is a new **fixed** `ProfileSetupOperation` with an exact shape check, like every existing profile
  command:
  - `ROOM_DETECT`;
  - `ROOM_READ` (`getprop fw.max_users`);
  - `ROOM_SET` (`resetprop fw.max_users <n>`, with `n` in 4..16);
  - `ROOM_WRITE_MODULE`;
  - `ROOM_REMOVE_MODULE`;
  - `ROOM_RESET` (`resetprop --delete fw.max_users`, or the recorded original value).
- **No free shell, ever.**
- **The module's files are generated in code** from the number alone: `module.prop` (id `cyclone_profiles`, name
  "Cyclone profiles", the version, author Cyclone, a one-line description) and `system.prop` (`fw.max_users=<n>`).
- **Written atomically:** a temp folder, then `chmod 0644`, `restorecon -R`, then a rename into
  `/data/adb/modules/cyclone_profiles`.
- **Cyclone writes only that one folder.** The CI guard checks that no other `/data/adb` path appears.

### 5.4 The owner's flow

1. Creating a profile fails, or the pre-check finds no room, with verdict `TOTAL_LIMIT`.
2. **The room screen** (P0) lists what uses the slots:
   - "Main · Profile B · Work profile · 'Old test' (Recently deleted, still uses a slot) · 1 unfinished Cyclone
     profile";
   - the limit: "Android allows 4 on this phone";
   - free storage.
3. It offers three ways out, in this order:
   - **Delete now** for profiles in Recently deleted (each backed up first, as today);
   - **Clean up** unfinished Cyclone profiles;
   - **Allow more profiles** on rooted phones.
4. **Allow more profiles** opens a confirm sheet:
   - "Let this phone hold up to **8** profiles? Cyclone sets Android's user limit and keeps it after restarts with a
     small Magisk module. Each profile uses storage; you have 41 GB free. You can undo this in Settings → Profiles."
   - The owner can pick 6, 8, 12 or 16. Default 8, never below the number already in use.
   - Buttons: **Allow** / **Not now**.
5. **On Allow:**
   1. Record the original value, both the property and `pm get-max-users`.
   2. `ROOM_SET`, then read back `getprop` **and** `pm get-max-users`. They must both say `n`.
   3. `ROOM_WRITE_MODULE`, then read back both files byte for byte.
   4. Retry the create.

   Each step goes into the step journal.
6. **Settings → Profiles → Profile room card.** It shows "Up to 8 profiles · kept by the Cyclone profiles module",
   and two buttons:
   - **Change**;
   - **Restore default**, which removes the module, resets the property, reads back, and says "Profiles you already
     have stay".

### 5.5 What can go wrong, and what Cyclone does

| Case | Behaviour |
| --- | --- |
| `resetprop` read-back doesn't match | Stop. Undo nothing (nothing changed). "Root didn't apply the change"; debug file. |
| Module written but the property read-back fails | Remove the module again, then report |
| The module exists but the owner disabled it in Magisk | The Profile room card says "Off in Magisk: on the next restart the limit goes back to N" |
| After a reboot the limit is back (the module wasn't loaded) | At start, Cyclone compares `getprop` with the module and offers **Apply again** |
| More profiles than the restored default allows | Allowed. Android keeps existing users; only creating new ones is refused. Restore default says so before it runs. |
| Low storage (under 3 GB free) | The confirm sheet warns; it doesn't forbid |
| A per-type limit fired | Never offered (§5.2) |
| Not rooted, or root denied | Not offered. The room screen shows only Delete now and Clean up. |

### 5.6 Safety

- **Owner-only.** The owner's confirm is required, as for a permission grant. It is never automatic and never a
  Mind, Instant, MCP or PC tool.
- **One property and one module folder, nothing else.**
- **Every change is read back and recorded** in the debug file: before, after, the manager and its version.
- **Fully reversible** with Restore default.
- **The scope guard holds.** This adds Android users for the owner's own profiles. It does not hide root, change the
  device's identity, or touch other apps.

### 5.7 Tests

- **Pure:**
  - the verdict classifier (Android's real sentences, `dumpsys user` type blocks from AOSP 13/14/15 shapes);
  - slot counting (guests excluded; work, private and clone profiles counted; partial users counted);
  - the choice of `n` (never below in use, 4..16).
- **Command shapes:** each `ROOM_*` op accepts only its exact form.
- **The generated module files**, byte for byte.
- **Read-back logic:** success, a mismatch, and a module written but the property not set.
- **CI guard:** the only `/data/adb` path in the app is `/data/adb/modules/cyclone_profiles`, and `resetprop` only
  ever sets `fw.max_users`.

### 5.8 Later, only if a debug file shows a per-type limit

A framework overlay module (RRO) raising that user type's `maxAllowed` would be phone- and ROM-specific. It would need
a reboot and its own device proof, so it is a research item: built only when a real debug file shows
`TYPE_LIMIT` on an owner's phone.

## 6. The runs

Each run is one alpha, built on the latest release, with tests and an honest device line.

**Numbering.** The VMOS fleet runs (plan 56 V1–V4) move after these, because a broken profile switcher blocks the
owner today.

### P0 (alpha.118): Truthful errors and the debug file (**built, alpha.118.dev1**)

**Fixes:**
- **D1, the classifier:**
  - limit and "type disabled" sentences are checked **before** "already exists";
  - "Maximum number of that type already exists" becomes `MAX_USERS_REACHED`, naming the type;
  - `PROFILE_ALREADY_EXISTS` only when the re-list really finds the name;
  - tests use Android's **real** strings (AOSP `UserManagerService` and `pm` texts).
- **D2:** every message uses the profile's own label ("Profile C couldn't be added").
- **D4, the capacity check:**
  - Count users as Android does (all alive non-guest users, profiles included).
  - Before creating, show a **room line**: "This phone allows 4 users: Main, Profile B, Work profile, 'Old test'
    (Recently deleted, still uses a slot)."
  - Then offer buttons: **Delete now** for trashed profiles (with their automatic backup) and **Open Android users
    settings**.
- **D5:** list partial users that Cyclone made (`Cyclone_…` name) and offer **Clean up**. Cyclone never touches users
  it didn't make.
- **Make room on rooted phones (§5).** When the phone is full and root is Magisk, KernelSU or APatch, the full screen
  offers **Allow up to 8 profiles**.
  - The owner confirms it.
  - Cyclone runs `resetprop`, writes the Cyclone profiles module, reads it back, then retries the create.
  - Settings → Profiles shows the current limit and **Restore default**.
  - If the limit that fired is a per-type one, the screen says so instead of pretending.
- **D3:**
  - The Profiles page **+** always starts a clean plan.
  - A paused, unfinished setup shows "Finish setting up Profile X" or "Discard it".
  - Typing a name never renames another profile.
- **D6 and §4:**
  - the step journal (a ring buffer, redacted);
  - the debug file;
  - the new **error screen**: what Cyclone checked (✓/✗), Android's own words, the fix buttons, **Save debug file**,
    **Share**, **Copy summary**.

**Tests:**
- classifier vectors from real Android text;
- the counting rules;
- the label in the copy;
- the redaction test (planted secrets);
- the debug-file schema;
- the + flow starting a clean journal.

**Done when:** creating a profile on a full phone says the phone is full, shows what is using the slots, offers the
fix, and saves a debug file.

### P1 (alpha.119): A switcher that always works (**built, alpha.119.dev1**)

**A switch state machine with a journal.** Every stage is logged into the debug file:
1. **Preflight.** Nothing is changed in it. Check:
   - source root and target identity;
   - the target is unlocked (a lock screen prompts the owner, never a bypass);
   - Cyclone installed and enabled in the target;
   - root works for the target's Cyclone;
   - no running task.
2. **Prepare and carry**, as today, each step read back.
3. **Arm a dead-man return.** Before switching, arm a fixed, root-side return:
   - "if the target's Cyclone hasn't reported in within 45 s, switch back to the source";
   - it is a typed command in `ProfileSetupPlan`, never free shell;
   - the target's Cyclone disarms it once it is up.
4. **Switch.** Wait adaptively for Android to confirm, up to 30 s (D7).
5. **Confirm.** The target's Cyclone reports in, then sends `SWITCHED` to its connectors (§3.6).

**Any profile to any profile:** B to C directly, and **Back to Main** from anywhere:
- every profile's Cyclone gets the full, current registry on every switch (D11);
- a persistent **Back to Main** notice in every Cyclone profile.

**Root managers (D8, D9).** One adapter per manager, each with read-back:
- Magisk 24+, including the hidden app (`magisk --sqlite "SELECT value FROM strings WHERE key='requester'"`);
- KernelSU and KernelSU Next (`ksud`);
- APatch (`apd`).

When a manager can't be set by command, the owner gets a guided step ("Open KernelSU → Superuser → allow Cyclone in
Profile C"). It is no longer a silent "switch cancelled".

**Android's own user switcher** stays reachable as the last way back. Cyclone checks that the quick-settings user
switcher is on, and offers to turn it on.

**Tests:**
- state machine transitions;
- rollback;
- each adapter's commands and parsers;
- the registry sync.

**Done when:** 50 switches in a row across Main, B and C on a Pixel (`testbench` suite `profiles`) end where asked,
or come back by themselves with a debug file.

**As built (alpha.119.dev1):**
- **`ProfileSwitch`** is the state machine: `PREFLIGHT`, `PREPARE`, `CARRY`, `ARM_RETURN`, `SWITCH`, `CONFIRM`.
  - Each stage records its result and time.
  - The last 20 switches are kept in `profile-debug/switches.json` and shown in the debug file.
- **The dead-man return** is one fixed command built by `ProfileSwitch.returnCommand`. It takes numbers and a 32-hex
  nonce only, and a CI guard pins its shape.
  - **A deviation from the plan:** it is not a `ProfileSetupPlan` operation. It is a backgrounded `setsid sh -c`
    timer, which the token-only plan can't express.
  - It is armed only after the target's Cyclone writes `switch-wait-<nonce>`. A locked profile, or one whose Cyclone
    can't start, never gets a return armed, so the owner can type a PIN.
  - It fires only while the target is still in front and its hello is missing.
- **The hello:**
  - The target's Cyclone waits until its profile is in front, then writes `switch-hello-<nonce>`, which disarms the
    return.
  - It then tells its connectors and shows a minimum-priority **Back to Main** notice (outside the main profile).
  - The source waits up to 18 s for it. A late hello is `CONFIRM_LATE`, not a failure.
- **Root managers:**
  - The manager is detected from `/data/adb`. Magisk grants are still shared by command (24+ now, the hidden app
    included).
  - Root is proven from the target's own Cyclone (`ROOT_CHECK`, `su -c id`) for every manager.
  - KernelSU and APatch grants are not set by command. When the check fails, the owner gets the one step to do by hand.
- **The registry:** it travels with every carry. From Main it replaces the list; from another profile, only unknown
  profiles are added.
- **The safety net:** Profile room shows whether Android's user switcher is on, and offers to turn it on (never off).
- **Tests:**
  - `ProfileSwitch57Test` (7);
  - guard checks in `test_profile_room_guard.py` for the return, the root proof and the user switcher.
- **Physical: UNVERIFIED.** The 50-switch run is still owed.

### P2 (alpha.120): Complete new profiles (cornerstone apps and Cyclone's full settings) (**built, alpha.120.dev1**)

**Cornerstone apps:**
- **The set:** Cyclone, the detected root manager (hidden Magisk included), Shizuku, Cyclone Cloak, and any app the
  owner marks **Cornerstone** in Settings → Profiles. Root tools such as an LSPosed manager can be marked the same way.
- **On create and on every switch into a profile:**
  - each is installed with `install-existing`, enabled and verified;
  - its root grant is copied through the manager adapter where it had one.

**Cyclone's full settings (D10).** A portable-settings table in code, one line per settings file:
- **Carried:** Hands, Modes, Fast Path, planes, UI, Drive, setup-cards state, owner notes, Automation Studio
  routines, app maps and manuals, Market installs, and owner skills marked shareable.
- **Per profile:** PC pairing and gateway trust, vault, codes, sealed leases, sessions.
- **The secret filter stays on every value.** The OpenRouter key keeps its sealed path.
- A CI guard fails the build when a new settings file isn't classified.

**Profiles, in full:** the registry (labels, looks, `ext`) syncs both ways on every switch, so every profile's
Cyclone knows every profile.

**A "Profile C has" report** after create and in Profiles, for example: "Cyclone 5.0.0-alpha.120 ✓ · Magisk ✓ root ✓ ·
Shizuku ✓ · Cloak ✓ approved ✓ · 47 settings ✓ · 312 skills ✓".

**Tests:**
- the classification guard;
- carry round-trips per file;
- cornerstone verification;
- the hidden-Magisk detection parser.

**As built (alpha.120.dev1):**
- **`PortableSettings`** classifies all 34 settings files: 11 CARRY, 16 PER_PROFILE, 7 NEVER.
  - `test_portable_settings_guard.py` resolves every `getSharedPreferences` name (literal or constant) and fails on an
    unclassified one.
  - **Newly carried:** Hands, Modes, Fast Path, planes, setup cards, the working indicator, the owner-notes switch,
    Automation Studio routines and skills (merged by id; runs and checkpoints stay), and the cornerstone marks.
  - **Kept per profile,** a change from the plan: `cyclone_ai` is not carried on every switch; it still goes once at
    setup, with the key sealed.
- **`PortableFiles`:**
  - Market installs: the one used last wins; secret inputs never leave.
  - Owner skills and their anchors: only added.
  - App manuals: added when missing; at most 200 manuals, 4 MB in all.

  A carry deletes nothing. App maps (`atlas`) and Brain app notes are not carried yet.
- **`ProfileCornerstones`:**
  - **Required,** as before: Cyclone, the root manager (a hidden Magisk app included) and Shizuku.
  - **Best effort, reported:** Cyclone Cloak (found by its connector id) and up to 12 apps the owner marks in Profiles →
    Cornerstone apps.
  - Installed with `install-existing` on every switch into a profile. Magisk grants are shared where the app had one.
  - KernelSU and APatch grants for these apps are not set by command.
  - "Owner skills marked shareable" became "all owner skills, through the secret filter": no shareable flag exists.
- **`ProfileInventory`:**
  - **The line:** "Profile C has: Cyclone … ✓ · Magisk ✓ root ✓ · Shizuku ✓ · Cloak ✓ · N of your apps · N settings ✓ ·
    N skills ✓", with a line for each missing app.
  - **Where:** Profiles → "What each profile has", and the debug file (`inventories`).
  - **When:** it is filled on each switch into a profile, not at create (creation doesn't prepare the profile yet).
  - **Not shown yet:** "Cloak approved", which needs the approval carry (P3, CC1).
- **Tests:**
  - `ProfileComplete57Test` (9);
  - the existing `CarryRulesTest` passes unchanged;
  - guards: `test_portable_settings_guard.py` (4), plus updated carry and room guards.
- **Physical: UNVERIFIED.**

### P3 (alpha.121): Cyclone Cloak connectivity done right, and device proof

**Cloak fixes (§3):**
- the approval carried with certificate re-verification;
- bindings deleted only when Android says the app is gone, and only by the registry owner;
- bindings keyed by the stable profile id, with migration;
- one strict parser;
- `SWITCHED` delivered in the target profile;
- the main profile's apps bindable if the owner says yes (owner question 3).

**Glass:**
- Profiles health per phone;
- **Download debug file** through the gateway (`profiles.debug`, read-only, redacted, owner route only).

**Testbench:**
- suite `profiles`: create B, C and D on a full and an empty phone; switch matrix; rollback; Cloak bound before and
  after a switch;
- `docs/PROFILES_DEVICE_MATRIX.md`, **UNVERIFIED** until seen on the owner's Pixel.

## 7. The next build: alpha.118 (P0), step by step

**Base:** the latest release, `5.0.0-alpha.117.dev2` (version code 271, `6936667d`).

**Ships as:** `5.0.0-alpha.118.dev1` with version code **272**:
- gateway and MCP `5.0.0-alpha.118.dev1`;
- Glass unchanged (`1.0.0-alpha.64`) unless W10 lands.

**Order.** Work packages go in this order, so that everything after W1 is logged from the start.

| # | Work package | Files | Tests |
| --- | --- | --- | --- |
| W1 | **Step journal.** Every privileged step (`ProfileSetupRuntime.executeRoot`, `ProfileBootstrapRuntime.execute`, `ProfileLifecycle`): time, operation, fixed command shape, exit code, ms, raw output (8 KB, redacted), verdict. A ring of 200 in `noBackupFilesDir/profile-debug/steps.json`, written atomically. | new `runtime/workspaces/ProfileStepJournal.kt`; hooks in the three runtimes | `ProfileStepJournalTest`: ring size, atomic write, redaction of planted secrets |
| W2 | **Truthful classifier and labels (D1, D2).** Limit and type sentences checked before "already exists"; new kinds `MAX_USERS_REACHED` (total) and `USER_TYPE_LIMIT` / `USER_TYPE_DISABLED`; "already exists" only after a re-list finds the name. Every message takes the profile's label; no "Profile B" text left in the setup path. | `ProfileProvisioningContract.kt`, `ProfileSetupRuntime.kt`, `ProfileSetup429.kt` | `ProfileFailureClassifierTest` with AOSP's real sentences; a guard that no "Profile B" literal remains in setup copy |
| W3 | **Capacity (D4).** Pure `ProfileCapacity`: parses `cmd user list --all --verbose`, `pm get-max-users` and the type lines of `dumpsys user` (new fixed op `DUMP_USERS`). Counts slots as Android does, gives a verdict (§5.2), lists what uses each slot. Replaces the count in `SecondaryUserProvisioningPolicy`. | new `ProfileCapacity.kt`; `ProfileSetupPlan.kt` (`DUMP_USERS`) | `ProfileCapacityTest`: Pixel-like 4-user phone with a trashed profile, work profile and Private Space; partial users; type-limit text |
| W4 | **Clean up unfinished Cyclone users (D5).** Only users named `Cyclone_<16 hex>`, marked partial or never journaled as ready, not the main or current user, and not in the registry as ready. `pm remove-user` through the existing fixed op, after an owner confirm. | `ProfileLifecycle.kt` (new `cleanUpUnfinished`), `ProfileTrash.kt` (refusal rules) | `ProfileCleanupRulesTest`: never main, current, ready, foreign-named or non-Cyclone users |
| W5 | **Profile room (§5).** Root-manager detection; `ROOM_*` fixed ops; generated module files; set, read back, retry; the Profile room card with Change / Restore default. | new `ProfileRoom.kt`; `ProfileSetupPlan.kt`; Settings → Profiles card | `ProfileRoomTest`: detection parsing, `n` rules, module bytes, read-back cases; command-shape tests |
| W6 | **The room screen.** What uses the slots, the limit, free storage; Delete now / Clean up / Allow more profiles; the type-limit wording. | `ProfileSetup429.kt` (or new `ProfileRoomScreen.kt`) | Compose contract test like `CycloneProfile429ContractTest` |
| W7 | **Clean start (D3).** The Profiles page **+** always starts a new plan (`beginAnotherProfile`, or **Finish setting up X / Discard it** for an unfinished one). Typing a name never renames another profile. | `CycloneProfilesPage.kt`, `ProfileSetup429.kt`, `ProfileSetupRuntime.kt` (`discardUnfinished`) | Journal tests: + clears a ready journal, offers resume for an unfinished one; the rename bug is covered |
| W8 | **The debug file (§4).** Pure assembler plus redaction (`MindMemory.looksSecret` and fixed patterns; never `ext` values, keys, vault or app data). Zip with `debug.json` and `summary.txt`. **Save** (`ACTION_CREATE_DOCUMENT`) and **Share** (FileProvider `cache-path` `profile-debug/`). | new `ProfileDebugReport.kt`; `res/xml/setup_helper_paths.xml` (add path); manifest unchanged | `ProfileDebugReportTest`: schema, planted secrets removed, size cap |
| W9 | **The error screen.** Used by setup, the Profiles page issue card and the rescue screen. Shows the headline with the label, what was checked (✓/✗), Android's own words (expandable), the fix buttons from W4–W6, and **Save debug file**, **Share**, **Copy summary**. | new `ui/ProfileErrorScreen.kt`; `ProfileSetup429.kt`, `CycloneProfilesPage.kt`, `ProfileRescueActivity.kt` | Contract test: every failure kind has a screen with its buttons |
| W10 | *(Optional in 118, else P3)* The gateway `profiles.debug` op so Glass can download the file | gateway and Glass | — |

**Guards (CI):**
- `scripts/ci`: the only `/data/adb` path in the app is the Cyclone profiles module folder;
- `resetprop` only ever with `fw.max_users`;
- every new `ProfileSetupOperation` has an exact shape check;
- no "Profile B" literal in setup copy.

**Validation before the release push:**
- `./gradlew :app:testDebugUnitTest` (in CI), plus the pure profile classes compiled and run locally with `kotlinc` as in
  alpha.114;
- gateway, MCP and `scripts/ci` suites;
- `python scripts/ci/release_versions.py --check`;
- `python scripts/ci/mobile_product_guard.py`.

**Release:**
1. Bump `release/version.toml`, `build.gradle.kts` (272), the three `pyproject.toml` files, and add
   `docs/RELEASE_5.0.0-alpha.118.dev1.md`.
2. Push to `claude/cyclone-v5-handoff-review-9qrs40`.
3. Wait for Mobile CI and publish.
4. Verify the manifest `source_sha`, every checksum and the APK signer.

**Device line:** **UNVERIFIED.** The owner's first check is to create Profile C again:
- if the phone is full, the room screen appears;
- **Allow more profiles** then succeeds (or names a type limit);
- a debug file saves to Downloads.

**After 118:**

| Alpha | Run |
| --- | --- |
| 119 | P1, the switcher |
| 120 | P2, complete profiles |
| 121 | P3, Cloak and device proof |
| 122 | Plan 56 V1 (VMOS "Ready in one click"), then V2–V4 |

## 8. Decisions and questions for the owner

**Decided defaults** (the owner can override any of them):
- **Room ceiling:** offered default **8** profiles; choices 6, 8, 12, 16; never below the number in use.
- **Delete now:** offered inside the room screen, always with its automatic backup first (unchanged rule: no backup,
  no delete).
- **Clean up** of unfinished users: only users Cyclone itself named, always after a confirm.
- **Order of builds:** profiles P0–P3 (alpha.118–121) before VMOS V1 (alpha.122).

**Still open:**

1. **Right now:** what does Recently deleted hold, and what does `adb shell cmd user list --all --verbose` show?
   That confirms §1.2. P0's debug file will show it without adb.
2. **Cornerstone apps:** besides Cyclone, root apps, Shizuku and Cloak, which apps must every new profile have?
3. **Cloak and Main:** should Cloak bind apps in the main profile too, or only in B, C, …?
4. **Root manager:** Magisk (hidden or not), KernelSU or APatch on this phone?
5. **Deleting:** should **Delete now** be offered right inside the "phone is full" screen, always with its automatic
   backup first?
6. **Per-type limit:** if the debug file shows `TYPE_LIMIT` on this phone, should Cyclone research the overlay module
   (§5.8)?
