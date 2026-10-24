# Cyclone night loop: starting block

This is the short launcher for the overnight loop. It holds what the operator sets, the message that starts the agent,
the links to the GitHub files the agent needs, and the rules in brief. The full operating brief is
[`tools/cyclone-testbench/NIGHT_LOOP.md`](https://github.com/premiumcentraal-boop/Cyclone/blob/claude/vigilant-goodall-dy6z4y/tools/cyclone-testbench/NIGHT_LOOP.md).
Read that file in full before the first action. Once PR #234 is merged, the same file is on the dev branch
([`claude/cyclone-v5-handoff-review-9qrs40`](https://github.com/premiumcentraal-boop/Cyclone/blob/claude/cyclone-v5-handoff-review-9qrs40/tools/cyclone-testbench/NIGHT_LOOP.md)).

---

## 1. Set these before you start

| Setting | Default | What it means |
|---|---|---|
| `NIGHT_END` | (required, no default) | the local time the loop stops, for example `07:00` |
| `MAX_CYCLES` | 5 | cycles of measure, analyse, build and release |
| `MAX_RELEASES` | 3 | developer alphas this night (each one publishes to the owner's devices) |
| `NIGHT_COST_USD` | 20 | model spend this night, from the Lab's own cost records |

## 2. Check the PC and the phone

On the PC:
- Cyclone for Windows is running (`cyclone`). Open Glass with `cyclone glass`. It is the owner's live view.
- This repository is open, on a clean tree, with the dev branch fetched.
- `cyclone-testbench doctor` runs from the repository's virtual environment.

On the phone:
- On USB, unlocked, on the charger, with **Stay awake** on.
- USB debugging is allowed for this PC.
- Cyclone's accessibility service is on, and the latest alpha is installed.

## 3. Start the agent

Paste this into a Claude Code session opened in the repository:

> Read `tools/cyclone-testbench/NIGHT_LOOP_START.md`, then read `tools/cyclone-testbench/NIGHT_LOOP.md` in full, and run
> the night loop it describes. Your memory is `night-loop/STATE.json`: if it exists, resume from its `phase`. Settings:
> NIGHT_END=`<time>`, MAX_CYCLES=`<n>`, MAX_RELEASES=`<n>`, NIGHT_COST_USD=`<n>`.

After any restart, paste the same message. The loop resumes from disk, not from memory.

## 4. The first ten minutes

1. Read `night-loop/STATE.json`. If it does not exist, create it at cycle 1, phase P0.
2. `cyclone-testbench doctor --fix`. If the missions are missing, run `cyclone-testbench install`.
3. Confirm that no `Publish V5 phone, Glass and PC developer alpha` run is queued or running on the dev branch.
4. Record the installed phone build (`adb shell dumpsys package com.cyclone.mobile`) in `STATE.json`.
5. Start cycle 1 at P1 (measure). Section 4 of the full brief describes each phase.

## 5. The rules in brief

These are the hard rules. The full list is section 2 of `NIGHT_LOOP.md`.

- **Approvals:** the Lab declines every approval. Never widen the test-only list.
- **Never weaken a judge:** do not change checks, `expects`, verdicts or safety rules to make a run pass.
- **Never skip a test** or push an empty commit to get green.
- **Secrets and private data:** nothing secret, and no message, contact or photo content, goes into the repository,
  the results or a report.
- **Consequential actions:** the Lab never sends, pays, deletes, posts, follows, comments or signs in. Do not add a
  mission that does.
- **Git:** never force-push the dev branch. Never push to dev while a publish is running. Pin each merge to the tested
  head SHA.
- **The phone:** never uninstall, clear app data, downgrade (`adb install -d`), or install an APK whose SHA-256 is not in
  the release manifest.
- **Owner settings and permissions:** never change a permission, turn on an accessibility service, or type a password.
- **Budgets:** stop at `MAX_RELEASES`, `MAX_CYCLES`, `NIGHT_COST_USD` or `NIGHT_END`.
- **Any safety failure** (an action taken without approval) stops the night at once.

## 6. The GitHub files the agent needs

Everything here is on the branch
[`claude/vigilant-goodall-dy6z4y`](https://github.com/premiumcentraal-boop/Cyclone/tree/claude/vigilant-goodall-dy6z4y)
until PR #234 is merged. Then use the dev branch
[`claude/cyclone-v5-handoff-review-9qrs40`](https://github.com/premiumcentraal-boop/Cyclone/tree/claude/cyclone-v5-handoff-review-9qrs40).

**Product rules and architecture (read first):**
- [`AGENTS.md`](https://github.com/premiumcentraal-boop/Cyclone/blob/claude/vigilant-goodall-dy6z4y/AGENTS.md): the invariants, the ownership map, the release lane.
- [`README.md`](https://github.com/premiumcentraal-boop/Cyclone/blob/claude/vigilant-goodall-dy6z4y/README.md)
- [`docs/ARCHITECTURE.md`](https://github.com/premiumcentraal-boop/Cyclone/blob/claude/vigilant-goodall-dy6z4y/docs/ARCHITECTURE.md)

**The Lab and the testbench:**
- [`tools/cyclone-testbench/NIGHT_LOOP.md`](https://github.com/premiumcentraal-boop/Cyclone/blob/claude/vigilant-goodall-dy6z4y/tools/cyclone-testbench/NIGHT_LOOP.md): the full brief.
- [`tools/cyclone-testbench/GUIDE.md`](https://github.com/premiumcentraal-boop/Cyclone/blob/claude/vigilant-goodall-dy6z4y/tools/cyclone-testbench/GUIDE.md): the one-time setup and the reading guide.
- [`tools/cyclone-testbench/README.md`](https://github.com/premiumcentraal-boop/Cyclone/blob/claude/vigilant-goodall-dy6z4y/tools/cyclone-testbench/README.md)
- [`tools/cyclone-testbench/campaigns/round-the-clock.json`](https://github.com/premiumcentraal-boop/Cyclone/blob/claude/vigilant-goodall-dy6z4y/tools/cyclone-testbench/campaigns/round-the-clock.json): the rotation.
- [`apps/device-gateway/cyclone_device_gateway/lab/runner.py`](https://github.com/premiumcentraal-boop/Cyclone/blob/claude/vigilant-goodall-dy6z4y/apps/device-gateway/cyclone_device_gateway/lab/runner.py): how the Lab runs, judges and restores.

**The release lane (every alpha goes through it):**
- [`release/version.toml`](https://github.com/premiumcentraal-boop/Cyclone/blob/claude/vigilant-goodall-dy6z4y/release/version.toml): the release metadata.
- [`apps/mobile/app/build.gradle.kts`](https://github.com/premiumcentraal-boop/Cyclone/blob/claude/vigilant-goodall-dy6z4y/apps/mobile/app/build.gradle.kts): the Android version code and name.
- [`docs/RELEASE_5.0.0-alpha.151.dev1.md`](https://github.com/premiumcentraal-boop/Cyclone/blob/claude/vigilant-goodall-dy6z4y/docs/RELEASE_5.0.0-alpha.151.dev1.md): an example of honest release notes.
- [`.github/PULL_REQUEST_TEMPLATE.md`](https://github.com/premiumcentraal-boop/Cyclone/blob/claude/vigilant-goodall-dy6z4y/.github/PULL_REQUEST_TEMPLATE.md): the pull request body.
- [`.github/workflows/v5-publish.yml`](https://github.com/premiumcentraal-boop/Cyclone/blob/claude/vigilant-goodall-dy6z4y/.github/workflows/v5-publish.yml): what a merge into dev publishes.
- [`scripts/ci/release_versions.py`](https://github.com/premiumcentraal-boop/Cyclone/blob/claude/vigilant-goodall-dy6z4y/scripts/ci/release_versions.py): the version coherence check.
- [`scripts/ci/mobile_product_guard.py`](https://github.com/premiumcentraal-boop/Cyclone/blob/claude/vigilant-goodall-dy6z4y/scripts/ci/mobile_product_guard.py): the product invariants check.

**Installing on the phone:**
- [`apps/device-gateway/cyclone_device_gateway/phone_care/updater.py`](https://github.com/premiumcentraal-boop/Cyclone/blob/claude/vigilant-goodall-dy6z4y/apps/device-gateway/cyclone_device_gateway/phone_care/updater.py):
  the owner-style update. It never downgrades and never uninstalls.
- [`apps/device-gateway/cyclone_device_gateway/phone_care/apk.py`](https://github.com/premiumcentraal-boop/Cyclone/blob/claude/vigilant-goodall-dy6z4y/apps/device-gateway/cyclone_device_gateway/phone_care/apk.py):
  the APK download, checked against the release manifest.

## 7. What is not verified yet

- The loop has not run on a phone. Every number it produces is new, and none is measured yet.
- A bad release is fixed by a forward release. Android refuses an older build over a newer one, so there is no rollback
  by downgrade.
- Every merge into the dev branch publishes an alpha to the owner's phone and the Windows package. The caps in section 1
  are the only brake the loop has.

## 8. Where the loop keeps its state

- `night-loop/STATE.json`: the memory. The loop writes it after every phase.
- `night-loop/JOURNAL.md`: one line per phase.
- `night-loop/cycle-NN/`: the findings, plan, build notes, release notes and verification for each cycle.
- `night-loop/MORNING.md`: the summary written when the loop stops.

The `night-loop/` folder is ignored by git, so it stays on the PC.
