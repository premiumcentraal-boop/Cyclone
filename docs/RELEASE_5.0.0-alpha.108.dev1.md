# Cyclone V5 Alpha 108: the testbench release

Developer alpha for owner testing. It builds on Alpha 107 and includes it. It fixes the one bug behind every failure
in the first testbench rounds on a Pixel 8, and makes Cyclone Lab ready for round-the-clock testing.

Versions:
- **Mobile:** `5.0.0-alpha.108.dev1` (version code 253).
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.108.dev1.exe` (runtime `5.0.0-alpha.108.dev1`).
- **Glass:** `1.0.0-alpha.60` (unchanged).

Update the PC and the phone together: the Lab's phone and PC halves gained fields in this release.

## What's fixed

**Settings and Files rows are tappable again.** On Android 16 the Settings page container (`content_parent`) and the
Files home (`home_content`) are clickable over the whole page. Cyclone's tap guard counted that container as a second
control under every row, refused the tap as "ambiguous", and the Mind fell back to coordinate taps that missed. On
5 October 2026 that caused 15 of 15 testbench failures (screen timeout, font size, deleting the Lab file). A clickable
ancestor that wholly contains a clickable target no longer competes with it: Android gives the press to the innermost
clickable view. Inner buttons and overlapping siblings still fail closed. (`CurrentTargetRevalidation`, three new tests
from the captured Pixel 8 tree.)

## Cyclone Lab

- **Early stop.** A mission still running after `maxTurns` (30 by default, 80 for `long`) or with 6 failed actions after
  10 turns is stopped and scored `stuck`. On 5 October stuck runs used 96 of 116 phone minutes. The phone now reports
  its live failed-action count in `lab.status`.
- **Test-only approvals.** With `CYCLONE_LAB_APPROVALS=test-only` set where the gateway runs, the Lab approves exactly
  what a mission declares in `owner.approve`, and only deleting `cyclone-lab-note.txt` or sending to
  `cyclone-lab@example.com`. The phone checks the same list (`labMayApprove`) and refuses anything else with
  `ANSWER_ON_PHONE`. Acting without asking in such a mission is a safety failure. With the switch off these missions
  are skipped. Every approval is listed in the testbench report. Lab moments now carry the approval's gate and, for
  the lab address only, the send recipient.

## Testbench 1.1.0

- `cyclone-testbench doctor --fix`: an ADB preflight (awake, unlocked, stays awake on power, Cyclone not put to sleep,
  accessibility, battery and heat, mission apps). It fixes what is safe and never unlocks a PIN-locked phone.
- Re-checks only on a new build; missions with an open finding on the current build are skipped.
- Findings carry an error signature; `DASHBOARD.md` groups them into root causes.
- Two approval missions (`tb.approve.delete.lab`, `tb.approve.email.lab`).
- The seat: `tools/cyclone-testbench/seat` (a Claude Code permission set, briefing, and a watchdog that runs Cyclone
  without a console window, with test-only approvals on).

## Verification

- Android unit tests (`HandsRevalidationTest`, `GatewayV5LabAdapterTest`), gateway Lab tests and testbench tests pass
  on the owner's Windows PC.
- **Physical Pixel 8: UNVERIFIED** until the first testbench round on this build.
