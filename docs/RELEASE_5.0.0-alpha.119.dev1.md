# Cyclone V5 Alpha 119: a profile switch that ends where you asked, or comes back by itself

Developer alpha for owner testing. It builds on alpha.118 dev1 (truthful profile errors, profile room, the debug file)
and includes it.

- **Mobile:** `5.0.0-alpha.119.dev1` (version code 273).
- **PC runtime:** gateway and MCP `5.0.0-alpha.119.dev1` (no PC changes; the version moves with the release).
- **Glass:** `1.0.0-alpha.64` (unchanged).

This alpha is plan 57 run P1 (`Cyclone V5 plan/57-hardened-profiles.md`).

## A switch in stages, each one written down

A profile switch now runs in six stages:

1. **Preflight:** no task running, root, the profile's identity. Nothing is changed.
2. **Prepare:** the profile's settings, permissions and root, each read back.
3. **Carry:** your memory, skills and the list of profiles.
4. **Arm the way back** (next section).
5. **Switch:** Cyclone waits for Android quickly at first, then patiently, for up to about 27 s.
6. **Confirm:** Cyclone in the new profile says hello.

Every switch, finished or not, is kept (the last 20). The debug file shows each stage, how long it took and why it
stopped.

## The way back is armed before the switch

- **Before switching,** Cyclone asks Cyclone in the target profile to say hello once that profile is in front.
- **Only after that Cyclone answers that it is listening** does Cyclone arm a small root-side timer.
- **If no hello arrives within 45 s** while the target is still in front, the timer switches back to where you were.
- **A hello disarms it.**
- **It can't get in your way:**
  - it is one fixed command built from numbers and a random code, and a CI guard pins it;
  - it never fires once you have moved on;
  - it is never armed for a profile whose Cyclone can't start yet (a locked profile, for example), so you can type
    your PIN.
- **If the hello is late,** the switch still counts as done ("confirm late"), and the way back stays armed until the
  hello arrives.

## Back to Main from anywhere

- **In every profile except Main,** a quiet notice reads **"You're in Profile C — tap to go back to Main"**. It opens
  Cyclone's rescue screen.
- **The profile list goes with every switch:**
  - from Main, it replaces the list (renames and removals included);
  - from another profile, only profiles it doesn't know yet are added.
  So any profile can switch to any other.
- **The safety net:** Profiles → **Profile room** shows whether Android's own user switcher (Quick Settings → users) is
  on. If it is off, a button turns it on. Cyclone never turns it off.

## Root in the new profile, whatever your root manager

- **Detection:** Cyclone reads which manager the phone uses (Magisk, KernelSU or APatch).
- **Magisk:**
  - Grants are still shared by command; Magisk 24 or newer is now enough (was 26).
  - A **hidden Magisk app** (renamed with "Hide the Magisk app") is found in Magisk's own database and comes along.
- **The proof:** root is proven from the new profile's own Cyclone, which runs `su -c id` itself. That is the very
  check it needs to switch back.
- **When the proof fails,** you get the one step to do by hand, named for the profile. For example: "Open KernelSU →
  Superuser and allow Cyclone in Profile C." It replaces the old silent "switch cancelled".

## Tests

- **`ProfileSwitch57Test` (7 tests):**
  - the wait schedule;
  - the exact return command, refusing anything but numbers and a hex code;
  - each root manager's guidance;
  - the profile-list merge rules;
  - the stage record and its round trip;
  - reading Android's user switcher;
  - the debug file carrying the switches with secrets redacted.
- **Existing tests:** the alpha.118 profile tests and the setup and structural tests pass unchanged. Locally, 52 profile
  tests compiled and ran.
- **CI guard `test_profile_room_guard.py`:**
  - the return command's shape and its only callers;
  - hello before the return is armed;
  - root proven from the target, never by nested su;
  - Android's user switcher only turned on, from an owner button;
  - no switch command in the gateway or MCP.

## Limits

- **Physical: UNVERIFIED.** No phone was available for this build. The plan's 50-switch run across Main, B and C is
  still owed.
- **The owner's first check:**
  1. Switch Main → Profile B → Profile C → Main.
  2. Save a debug file and look at "Last switches". Each switch should show `arm_return✓` and `confirm✓` and end
     `DONE`.
  3. The return firing for real is hard to provoke by hand (the hello lands within a second or two), so it is
     covered by the command test and still owes a device run.
- **KernelSU and APatch:** grants are not set by command; the owner allows Cyclone once per profile.
- **Not in this alpha** (plan 57 P2–P3, alpha.120–121):
  - the complete settings carry and cornerstone apps;
  - Cyclone Cloak's approval carry and binding fixes;
  - downloading the debug file from Glass.
