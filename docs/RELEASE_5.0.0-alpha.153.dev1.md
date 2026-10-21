# Cyclone Alpha 153: Lab missions keep a local copy of the screens they see

Developer alpha for owner testing. It installs over Alpha 152 as a normal update. Nothing in it changes what the phone
does for you. It changes what a Lab test run leaves behind, so the owner's own Lab runs can be studied on a computer.

## Versions

- **Mobile:** `5.0.0-alpha.153.dev1` (version code 307).
- **Cyclone for Windows, gateway and MCP:** unchanged from `5.0.0-alpha.151.dev1` (no PC code changed).
- **Glass:** unchanged, `1.0.0-alpha.68`.

## Why this release

Alpha 152 was measured like for like against Alpha 151 on the same ten missions. The result was inconclusive: no clear
change in pass rate, and the screen-read errors that end runs still happen. The cause is not visible from the Lab's
results alone. The phone now keeps what it saw in Lab runs, so the next change can be checked against real screens
instead of guessed at.

## What changed

**Phone (`mind/one/ScreenTrace.kt`, `mind/one/AndroidCrew.kt`, `mind/PhoneMindToolbox.kt`, `mind/mission/MindMissions.kt`):**
- A Lab mission saves each screen the crew sees, one JSON line per screen, to the app's external folder
  (`lab-traces/<mission>.jsonl`). It is readable only by adb.
- Words on the screen are kept as they are, so a replay can check what the phone showed.
- Secrets are always scrubbed before saving: passwords, codes and the like (the same scrubbing the Lab reports use).
- A sensitive screen (a password, a code or a banking screen) is never saved. The crew still gets the screen.
- Owner missions get no sink. Nothing is saved for them.
- If saving fails, the run is unchanged. Saving is best effort and never changes a decision.

**Tests:** the six trace-recorder tests plus two new ones: a sensitive screen is never recorded, and a
trace file name keeps only letters, digits, dashes and underscores. All eight pass locally.

## What did not change

- Approvals, the gates on sending, paying, deleting and permissions, and the secret rules.
- The decision code. Recording reads the screen and writes it down; it never tells the crew what to do.
- Owner missions: no screens are saved for them at all.
- The Lab's verdicts, judges, scoring and missions.

## Privacy: what the owner's screens can and cannot leave the phone as

- A trace contains the words the phone showed during a Lab run. Those words can include message or mail text from apps
  the Lab opened. Traces stay on the phone until the owner pulls them with adb.
- A pulled trace belongs in the local, git-ignored results folder. It must not be committed, put in a pull request, or
  copied into a report. Reports keep counts, control names and app chrome labels only.

## Validation

- Local unit tests: `com.cyclone.mobile.mind.one.*` run on the build PC, 8 of 8 in the trace test class.
- Release metadata: `scripts/ci/release_versions.py --check`, coherent at 153 (307).
- Mobile CI on the pull request runs the full unit suite on Linux.

## Lab gate table

| Measure | Alpha 152 (before) | Alpha 153 (after) |
|---|---|---|
| Pass rate | 33% (12 of 36; 95% CI 20–50%) | NOT MEASURED |
| Safety failures | 0 | NOT MEASURED |
| Said done but wasn't | 0 | NOT MEASURED |
| Screen reads that failed (trials) | 10 | NOT MEASURED |
| Median time | 142 s | NOT MEASURED |

## What is UNVERIFIED on a real phone

- Everything in this release is UNVERIFIED on a real phone until the Lab runs with the trace on, and the traces are pulled
  and checked.
- The trace's size and the time it adds per screen are NOT MEASURED.
