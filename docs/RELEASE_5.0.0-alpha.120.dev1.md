# Cyclone V5 Alpha 120: every profile complete — its apps, Cyclone's settings and your saved work

Developer alpha for owner testing. It builds on alpha.119 dev1 (the staged switch with its way back) and includes it.

- **Mobile:** `5.0.0-alpha.120.dev1` (version code 274).
- **PC runtime:** gateway and MCP `5.0.0-alpha.120.dev1` (no PC changes; the version moves with the release).
- **Glass:** `1.0.0-alpha.64` (unchanged).

This alpha is plan 57 run P2 (`Cyclone V5 plan/57-hardened-profiles.md`).

## Cornerstone apps

Every switch into a profile checks that the apps it is built on are there. Each one is installed with Android's
`install-existing` (the same app, nothing downloaded), enabled and verified.

| App | When it is missing |
|---|---|
| Cyclone, your root manager (a hidden, renamed Magisk app included), Shizuku | The switch stops and says so, as before |
| **Cyclone Cloak** (new), found by its connector id | Added; reported if it fails, never blocks |
| **Up to 12 apps you mark** (new), in Profiles → **Cornerstone apps** → **Choose cornerstone apps**. Root tools such as an LSPosed manager belong here | Added; reported if it fails, never blocks |

- On Magisk, each of these apps gets its root grant in the profile when it has one in the profile you come from.
- On KernelSU and APatch, you allow them once per profile.
- Your marks travel with the switch, so every profile's Cyclone knows them.

## Cyclone's settings travel

**What is new on every switch, both ways.** The profile you come from wins:
- Hands (style, handedness, typos);
- Modes (speed, listening, silent success);
- Fast Path;
- planes;
- the setup cards you've seen;
- the working indicator;
- the owner-notes switch;
- **Automation Studio routines and their skills.**

**What already travelled:** Drive and the look. Drive now also brings its announce lists.

**Routines merge by id:**
- the same routine is updated;
- a new one is added;
- one that exists only in this profile stays.

A carry never deletes a routine. Runs and checkpoints stay with the profile that ran them.

**Every settings file is classified now** (34 files): carried, kept per profile, or never.
- **Never:** the vault, codes, sealed leases, PC pairing and sessions, and the OpenRouter key. The key is still sealed
  once at setup.
- **Kept per profile:** the AI settings, which go once at setup. Also connector approvals, account progress such as
  stock-skill checkpoints, run history and debug state.
- **A CI guard fails the build** when someone adds a settings file without deciding where it belongs.
- **Every carried value still passes the secret filter.** A routine that mentions a password, a code or a key stays
  where it is.

## Your saved work travels

| What | How it merges |
|---|---|
| **Market installs** | The one you used last wins. An install whose inputs look like a secret never leaves. |
| **Your own skills**, and where each works on an app's map | Only added. |
| **App manuals** (what Cyclone learned about each app's screens) | Added when this profile has none for that app. At most 200 manuals, 4 MB in all. |

A carry deletes nothing. App maps and the Brain's app notes don't travel yet.

## "Profile C has"

**Where:** Profiles → **What each profile has** shows, for each profile, what it had at its last switch:

> Profile C has: Cyclone 5.0.0-alpha.120.dev1 ✓ · Magisk ✓ root ✓ · Shizuku ✓ · Cloak ✓ · 2 of your apps ✓ · 47 settings ✓
> · 312 skills ✓

**What it shows:**
- Anything missing gets its own line ("LSPosed: Android didn't install it").
- **root** is proven from that profile's own Cyclone.
- **settings** are the carried settings that now match.
- **skills** are the skills and paths that profile's Brain holds.
- The same lines are in the debug file (`inventories`).

**When:** the line appears after the first switch into a profile; creating a profile doesn't prepare it yet.

**Not shown yet:** whether Cloak is *approved* in that profile. Carrying the approval comes in alpha.121.

## Tests

- **`ProfileComplete57Test` (9 tests):**
  - every file classified once, and secrets never travel;
  - the new settings travelling through the secret filter;
  - routines merging by id without deletes or secrets;
  - Market installs (the one used last wins, secret inputs stay);
  - owner skills, anchors and manuals only added;
  - cornerstone order, roles and limits;
  - the "has" line and its round trip;
  - old and new carry reports;
  - the debug file.
- **Existing tests:** `CarryRulesTest` and the alpha.118–119 profile tests pass unchanged. 61 profile tests and the
  carry rules ran locally.
- **CI guards:**
  - `test_portable_settings_guard.py`: every settings file classified, secrets never carried, fixed file paths merged
    without deleting, Cloak found by connector id, marks only from Profiles;
  - the carry and room guards were updated.

## Limits

- **Physical: UNVERIFIED.** No phone was available for this build.
- **The owner's first check:**
  1. In Main, open Profiles → Cornerstone apps and mark one app.
  2. Switch to Profile B. That app and Cloak are there.
  3. Back in Main, "What each profile has" shows B's line.
  4. Change a Hands setting in B, switch to Main: Main has it.
- **KernelSU and APatch:** grants for cornerstone apps are not set by command.
- **Not in this alpha** (plan 57 P3, alpha.121):
  - Cyclone Cloak's approval carry and binding fixes (`docs/handoff/CLOAK_CONNECTOR_COMPAT.md` CC1–CC7);
  - downloading the debug file from Glass;
  - the 50-switch device run.
