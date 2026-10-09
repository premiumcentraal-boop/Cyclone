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

This plan is the deep dive (§1–§4) and the runs (§5). It is checked against the code at alpha.117 dev2.

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

## 5. The runs

Each run is one alpha, built on the latest release, with tests and an honest device line.

**Numbering.** The VMOS fleet runs (plan 56 V1–V4) move after these, because a broken profile switcher blocks the
owner today.

### P0 (alpha.118): Truthful errors and the debug file

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

### P1 (alpha.119): A switcher that always works

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

### P2 (alpha.120): Complete new profiles (cornerstone apps and Cyclone's full settings)

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

## 6. Questions for the owner

1. **Right now:** what does Recently deleted hold, and what does `adb shell cmd user list --all --verbose` show?
   That confirms §1.2. P0's debug file will show it without adb.
2. **Cornerstone apps:** besides Cyclone, root apps, Shizuku and Cloak, which apps must every new profile have?
3. **Cloak and Main:** should Cloak bind apps in the main profile too, or only in B, C, …?
4. **Root manager:** Magisk (hidden or not), KernelSU or APatch on this phone?
5. **Deleting:** should **Delete now** be offered right inside the "phone is full" screen, always with its automatic
   backup first?
