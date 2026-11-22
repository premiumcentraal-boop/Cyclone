# Cyclone V5 Alpha 118: profiles that say what's wrong, and room for more on rooted phones

Developer alpha for owner testing. It builds on alpha.117 dev2 (VMOS rent, power and backup) and includes it.

- **Mobile:** `5.0.0-alpha.118.dev1` (version code 272).
- **PC runtime:** gateway and MCP `5.0.0-alpha.118.dev1` (no PC changes; the version moves with the release).
- **Glass:** `1.0.0-alpha.64` (unchanged).

This alpha is plan 57 run P0 (`Cyclone V5 plan/57-hardened-profiles.md`).

## The bug the owner hit

Creating a third profile ("Profile C") showed **"Profile B already exists"**.

**Why:**
- Android refused because the phone had no room for another user of that kind. It says so with "… **Maximum number
  of that type already exists**".
- Cyclone checked for the words "already exists" before checking for limits, so it reported a duplicate Profile B.
- Every message also said "Profile B", whichever profile was being made.

**Now:**
- Limits are read first. Android's per-type refusal reads **"No room for Profile C"**; the phone's total limit reads
  **"User limit reached"**.
- A fresh profile name that Android doesn't list is never called "already exists".
- Every message names the profile you are making ("Creating Profile C…", "Profile C couldn't be added").

## See what uses the places on the phone

- **Counted as Android counts.** Every user except guests counts: profiles in Recently deleted, a work profile,
  Private Space and unfinished users included. Cyclone's pre-check now counts the same way, and stops early when
  the phone's system caps this kind of user (read from `dumpsys user`).
- **The error screen shows:**
  - what Cyclone checked (✓/✗);
  - the places in use, for example "Main · Profile B · Old test (Recently deleted, still uses a place) · Work
    profile";
  - Android's own words (tap to show);
  - the fixes that apply, in order (next section).

**The fixes:**
1. **Delete profiles in Recently deleted.** Each is backed up first, as before; no backup, no delete.
2. **Clean up unfinished profiles.** Only Android users Cyclone itself named and never finished; never the main
   profile, the one you're in, a ready profile or one in Recently deleted. Always after a confirm.
3. **Allow more profiles** (rooted phones, below).
4. **Open Android's users settings.**
5. **Try again.**

## Allow more profiles (rooted phones)

**What it does:**
- **Raises the limit.** Android's total user limit is the system property `fw.max_users`. With Magisk, KernelSU or
  APatch, Cyclone raises it with the root manager's own `resetprop`, at once, with no reboot.
- **Keeps it after restarts** with a small module, **Cyclone profiles** (`/data/adb/modules/cyclone_profiles`).
- **Asks you first**, with a choice of 6, 8 (default), 12 or 16. It never offers less than what is already in use, and
  warns when storage is low.
- **Reads every change back**, both `getprop` and Android's own `pm get-max-users`. If they don't match, it stops and
  says so.
- **Can be undone:** Profiles → **Profile room** → **Restore default** removes the module and puts the limit back as it
  was. Profiles you have stay.
- **Notices a lost limit:** if a restart lost it, the card offers **Apply again**.

**What it doesn't do:**
- **It can't lift the phone system's own cap on this kind of user.** That is not this property. If that cap is what
  stopped you, Cyclone says so instead of offering it.
- **It never touches anything else.** It uses one property and one module folder, through fixed commands; a CI guard
  checks this.

## Starting a new profile is always clean

- The **+** on Profiles always starts a new plan.
- If a setup was left unfinished, Cyclone asks: **Finish Profile X**, or **Discard and start new**.
- Typing a name can no longer rename another profile.
- The suggested name is the first free one ("Profile C", "Profile D", …).

## The debug file

**Where it is offered:**
- on the error screen;
- in Profiles;
- on the rescue screen.

**Three ways to take it:**
- **Save:** pick a folder, for example Downloads.
- **Share:** through its own narrow file sharer.
- **Copy summary.**

**What it holds:** a zip with `debug.json` and a readable `summary.txt`:
- the Cyclone and Android versions and the device;
- the root manager and the profile room state;
- every Android user and the places in use;
- the per-type limits;
- your profiles (names, Android user ids, stage), and which connectors keep data on each (their names only);
- the setup journal;
- the error with Android's own words;
- the last 200 privileged steps: command, exit code, time taken, Android's answer.

**Never in it:** keys, tokens, codes, passwords, the vault, chats, app data or a connector's own data. Every text is
redacted, and a test plants secrets and checks none survive. The file leaves the phone only when you save or share
it.

## Tests

- **`ProfileHardening57Test` (15 tests):**
  - Android's real refusal sentences;
  - every failure worded for the profile being made;
  - counting places the Android way;
  - per-type limits from `dumpsys user`;
  - the pre-check;
  - clean-up only touching unfinished Cyclone users;
  - root manager detection;
  - limit choices;
  - the exact module files;
  - Allow refused for a per-type limit;
  - every room command's exact shape;
  - the step journal ring and redaction;
  - the debug file holding the facts and none of the secrets;
  - the fixes offered per failure;
  - the name suggestion.
- **Existing tests:** the profile setup and structural tests pass unchanged. The provisioning UI contract test now
  checks the new error screen and the clean **+**.
- **CI guard `test_profile_room_guard.py`:**
  - only the Cyclone profiles module folder under `/data/adb`;
  - `resetprop` only for `fw.max_users` (4..16);
  - Allow, Restore and Clean up only from Cyclone's own screens, never from the Mind, gateway or MCP;
  - the debug file never copying connector data, and redacting every step;
  - no "Profile B" in setup messages;
  - its own narrow file sharer.
- **Runs:** the new and existing profile tests also compiled and ran locally (45 tests). The CI-script suite passes.

## Limits

- **Physical: UNVERIFIED.** No phone was available for this build.
- **The owner's first check:** create Profile C again.
  1. If the phone is full, the error screen shows what uses the places.
  2. **Allow more profiles** (or **Delete** / **Clean up**) makes room.
  3. The profile is created.
  4. **Save** puts a debug file in Downloads.
- **KernelSU and APatch** module loading from `/data/adb/modules` follows their documented layout but is unverified.
- **Not in this alpha** (plan 57 P1–P3, alpha.119–121):
  - the switcher's dead-man return and any-root-manager support;
  - the complete settings carry and cornerstone apps;
  - Cyclone Cloak's approval carry and binding fixes;
  - downloading the debug file from Glass.
