# Cyclone night loop

This file is the complete operating brief for an overnight Claude Code session on the owner's PC. The session measures
Cyclone on the owner's USB-connected phone with Cyclone Lab, retries only what is worth retrying, analyses every
failure, plans and builds at most two small improvements per cycle, ships each one as the next developer alpha,
installs it on the phone, and measures again. It repeats until a stop rule in section 6 fires.

Read this whole file before the first action. Where it and `AGENTS.md` disagree, `AGENTS.md` wins on product rules
(the invariants, the ownership map, the release lane). This file wins on how the loop runs.

---

## 0. Before you start (the operator does this once)

On the PC:
- Cyclone for Windows is running (`cyclone`). The gateway answers at `http://127.0.0.1:8765`.
- This repository is the working directory, cloned from the dev branch, with a clean tree.
- `cyclone-testbench doctor` runs from the repository's virtual environment (see `GUIDE.md`, one-time setup).
- The owner's live view is Cyclone Glass. `cyclone glass` opens it in the browser and starts the local gateway if needed.
  The loop reads the same data through the CLI, so it does not depend on Glass.

On the phone:
- Connected by USB, unlocked, on the charger. Developer options → **Stay awake** is on.
- USB debugging is allowed for this PC (the "Allow" prompt was accepted).
- Cyclone's accessibility service is on, and the latest alpha is installed.

The operator sets these four values. The defaults are safe, so change them only on purpose:

| Setting | Default | Meaning |
|---|---|---|
| `NIGHT_END` | (required) | the local time the loop stops, for example `07:00` |
| `MAX_CYCLES` | 5 | cycles of measure, analyse, build and release |
| `MAX_RELEASES` | 3 | developer alphas this night (each one publishes to the owner's devices) |
| `NIGHT_COST_USD` | 20 | model spend this night, from the Lab's own cost records |

To start, paste this into the session:

> Read `tools/cyclone-testbench/NIGHT_LOOP.md` in full and run the night loop it describes. Your memory is
> `night-loop/STATE.json`: if it exists, resume from its `phase`. Settings: NIGHT_END=<time>, MAX_CYCLES=<n>,
> MAX_RELEASES=<n>, NIGHT_COST_USD=<n>.

After any restart, paste the same message. The loop resumes from disk, not from memory.

---

## 1. The mission

Make Cyclone's phone agent more reliable, honest, accurate and fast, by measuring it on the real phone, fixing what
the measurements show, and proving each fix with the same measurement before the next one. Every improvement ships
as a developer alpha, goes onto the owner's phone, and is measured again on the phone.

Priorities, in order, when two findings compete:
1. **Safety** (an action taken without approval). Any safety failure stops the night (section 6).
2. **Honesty** (Cyclone said done, and the phone disagrees). Drive it to zero.
3. **Reliability** (it gave up, timed out, wandered, or could not find the control).
4. **Judgement** (it asked when the goal was clear, or guessed when it should have asked).
5. **Speed** (it passed, but slowly).

---

## 2. Hard rules (never break these)

These hold for every cycle. If a step would break one, stop the loop and write the reason to the journal.

1. **Approvals.** Never add, widen or bypass an approval. The Lab declines every approval. The one exception is the
   test-only list (`cyclone-lab-note.txt` and `cyclone-lab@example.com`, under `CYCLONE_LAB_APPROVALS=test-only`).
   Never widen that list. It is changed only by a reviewed change to both the gateway and the phone.
2. **Never weaken a judge.** Do not change a mission's checks, its `expects`, the verdict rules, the scoring, or the
   safety rules so a run passes. Do not delete or re-label a finding to make a number look better. A fix must make
   the phone do the thing, not make the test easier to pass.
3. **Never skip, disable or quarantine a test** to get green. Never push an empty commit to kick CI.
4. **Secrets and private data.** Never write a password, OTP, API key, payment detail or raw typed secret into the
   repository, the results, the state files or any report. Reports carry numbers, names of controls and app chrome
   labels only. Never copy the content of a message, a contact or a photo.
5. **Consequential actions.** The Lab must never send, pay, delete, post, follow, like, comment, change a permission
   or sign in on the owner's behalf. A mission that reaches such a step ends at the boundary, and the Lab records it.
   Do not add a mission that does any of these.
6. **Git and releases.** Never force-push the dev branch. Never push to the dev branch while a publish run is
   queued or running. Never merge a pull request whose head differs from the head you tested (pin the head SHA).
   Never edit `.github/workflows` to change what publishes.
7. **The phone.** Never uninstall Cyclone. Never clear its app data. Never downgrade it (`adb install -d` is
   forbidden). Install only an APK whose SHA-256 matches the release manifest of the tag you verified.
8. **The PC.** No generic shell or root exposure to the model, no new open port, no weakened auth. Route PC work
   through the gateway and its contracts.
9. **Owner settings.** Change a phone setting only through the Lab's own setup, and restore it afterwards (the Lab does
   this). Never change a permission, turn on an accessibility service, or type a password for the owner.
10. **Repository hygiene.** Commit only code, tests, docs and release metadata. Never commit `night-loop/` or the
    contents of `testbench-results/` unless a step below says so. Stage with explicit paths, never `git add -A`.
    No model identifier goes into a commit, a pull request, a code comment or a document.
11. **Budgets.** Stop when `MAX_RELEASES`, `MAX_CYCLES`, `NIGHT_COST_USD` or `NIGHT_END` is reached.

---

## 3. Stay in the loop (how to keep running all night)

**Memory is on disk, not in your head.** Keep these files current:

- `night-loop/STATE.json`: the single source of truth. Fields: `cycle`, `phase`, `releasesThisNight`, `costUsd`,
  `lastGood` (version, PR, merge SHA, the verified APK's SHA-256), `currentBranch`, `openPr`, `experiments` (ids),
  `nextAction`, `stopReason` (null while running). Write it after every phase, before you start the next one.
- `night-loop/JOURNAL.md`: one timestamped line per phase: what you ran, what you saw, what you decided and why.
- `night-loop/cycle-NN/`: `findings.md`, `plan.md`, `build.md`, `release.md`, `verify.md` for each cycle.

**Pacing.** A Lab batch takes about an hour, and a release takes 20 to 40 minutes of CI. While you wait:
- Check progress every 5 minutes with `cyclone-testbench watch <experiment>`, or the experiment's status.
- Between checks, let the session idle with a 20 to 30 minute wakeup (the `/loop` dynamic mode, or the scheduling tool
  if you have one). Do not busy-wait, and do not sleep inside a single long command.
- Long shell commands run in the background and report back when they finish. Read their output, not their timing.

**Context.** If the conversation grows long, first write `STATE.json` and the journal, then continue. The next
context starts from those files.

**Never end the session silently.** When a stop rule fires, write `night-loop/MORNING.md` (section 9), set
`stopReason` in `STATE.json`, and leave the phone on the last verified build.

---

## 4. One cycle, phase by phase

### P0. Preflight (every cycle)

1. Read `night-loop/STATE.json`. If there is no state yet, create it with `cycle: 1`, `phase: P0`, and
   `lastGood` set to the current `release/version.toml` product version.
2. `git status` must be clean (apart from `night-loop/`). `git fetch origin` and check out the dev branch tip for the
   next branch (P4).
3. Check the phone and the gateway: `cyclone-testbench doctor --fix`. It checks the phone over ADB and fixes what is
   safe. If it reports a problem that section 7 does not cover, stop.
4. Confirm no publish is in progress: list the `Publish V5 phone, Glass and PC developer alpha` workflow runs on the dev
   branch. If one is queued or running, wait for it. Do not push while it runs.
5. Confirm the installed phone build (section P6, the `dumpsys` check). Record it in `STATE.json`.

### P1. Measure

Measure the whole rotation once per cycle, and the focused suites for what the last cycle changed.

- The batch: `cyclone-testbench next` shows what it will run and why. Then
  `cyclone-testbench run --next --wait` (the campaign's batch), or `cyclone-testbench run --suite <name> --reps 5 --wait`.
- Focused: `cyclone-testbench run --missions <id,id,...> --reps 5 --wait` for the missions behind the last cycle's findings.
- Ablation (once per night, after cycle 1, and only on a phone with alpha 151 or later): `cyclone-testbench research plan`
  first, then `research start --phase no-skills` and `research start --phase skills`. Each is one experiment. Watch both.
  These answer the owner's question about the app map, owner memory and built-in skills.

**Retry policy.** This is the rule that stops the night from getting stuck.

- Each mission and variant (an "arm") gets at least **5 completed runs**. An infra run does not count as completed.
- If the 5 agree (5 of 5 pass, or 0 of 5), the arm is done.
- If they split (1 to 4 pass), run up to 5 more.
- The hard cap is **10 attempts per arm per cycle**. At 10 attempts, stop. Record the arm as **persistent**, with its
  counts, as a finding. Never run an 11th attempt in the same cycle.
- Infra attempts (phone locked, gateway down, provider outage, app missing) do not count toward the 10. If the same arm
  has 3 infra failures in a row, fix it with section 7, or pause. Never loop on infra.
- A false success (Cyclone said done, the phone disagrees) is a product failure. Retry it inside the cap.
- A **safety failure** (an action taken without approval) is not retried. It stops the night (section 6).

Write each experiment's id and report path into `STATE.json`.

### P2. Analyse (deeply, not from the headline)

For every arm that failed or split, read the trial records and the run's report, not only the summary line:

1. Open `testbench-results/experiments/<id>/report.md` and the trial export
   (`GET /v1/lab/experiments/<id>/trials.jsonl`).
2. For each failing run, find the stage where it went wrong: the run record's stages and calls (read, route, decide,
   act, verify, recover), the screen it was on, the control it chose, and the check that failed.
3. Classify the root cause in one of these areas: **perception** (it misread the screen), **planning** (it chose the
   wrong route or step), **judgement** (it asked, or guessed, when it should have done the other), **verification**
   (it claimed done without proof), **recovery** (it could not get out of a dead end), **speed**, or **infra**.
4. Count how often each root cause appears across all arms. A cause that appears in several missions is worth more
   than one that appears once.
5. Compare with the previous cycle. Did the last release move the metric it targeted? If a release made a number
   clearly worse, that is a regression, and it comes first.
6. Keep the findings ledger current: `cyclone-testbench findings`, and
   `cyclone-testbench finding <id> --status fixing --note "cycle NN"` for the ones you are working on. Do not invent
   findings by hand. The run creates them.

Write `night-loop/cycle-NN/findings.md`: a ranked table of root causes, each with mission ids, counts, experiment ids and
one representative stage.

### P3. Plan

Write `night-loop/cycle-NN/plan.md`. Rank the improvement points by **severity × frequency × ease**, where severity follows
section 1.

For each planned change, write:
- **Evidence:** the finding, the experiment ids, the counts.
- **Tier:** which part it touches, one of Instant, Flash, OneMind (the Conductor and crew behind Flash and Mind), Mind
  (the classic loop), the gateway, or the testbench.
- **Change:** the smallest change that addresses the root cause. Name the files, but do not write the code yet.
- **Proof:** the unit or guard test that will fail before the change and pass after it, and the Lab measurement that
  will show the effect next cycle.
- **Risk and rollback:** what could break, and how the next release would revert it.

Rules for the plan:
- At most **two** changes per cycle. Small and reversible beats large and clever.
- Do not plan a change whose effect you cannot measure in the next cycle.
- If the plan has nothing actionable (all infra, or one-off flake), do not build. Re-measure on a different suite.
  If two cycles in a row produce no actionable plan, stop the night.

### P4. Build

1. Create the branch `night/cycle-NN-<slug>` from the dev branch tip. Never commit to the dev branch directly.
2. Make the change with its test. Keep to the ownership map in `AGENTS.md` and the product invariants. Touch only what
   the plan names.
3. Validate, in this order, and stop at the first failure:
   - Phone changed: `cd apps/mobile && ./gradlew :app:testDebugUnitTest` (the full suite).
   - Gateway changed: `python -m pytest apps/device-gateway/tests -q`.
   - MCP changed: `python -m unittest discover -s tools/codex-phone-mcp/tests -v` and the cyclone-agent tests.
   - Testbench changed: `python -m pytest tools/cyclone-testbench/tests -q`.
   - Always: `python scripts/ci/release_versions.py --check` and `python scripts/ci/mobile_product_guard.py`.
   - Glass changed: `cd apps/glass && npm ci && npm test && npm run build`, then `python scripts/ci/glass_guard.py`.
4. If a fix fails validation twice, drop the change, record why in the journal, and move on to the next planned change.
   Do not keep trying the same fix a third time.

Write `night-loop/cycle-NN/build.md`: what changed, the exact validation commands and their results.

### P5. Version and release

Only run this phase when P4 passed, `releasesThisNight` is below `MAX_RELEASES`, and no release is already in flight.

**Next version.** Increase the alpha number by one: `5.0.0-alpha.N.dev1` becomes `5.0.0-alpha.(N+1).dev1`. Increase
`android_version_code` by one. Use the same version everywhere.

**Bump these exact places, and nothing else:**
- `release/version.toml`: `product_version`, `python_version`, `android_version_code`, `components.mobile`,
  `components.device_gateway` and `components.mcp` (when the PC code changed), `previous_mobile_version`,
  `candidate_generation`, and the header comment. Leave `pc_companion` alone. Change `components.glass` only if `apps/glass`
  changed.
- `apps/mobile/app/build.gradle.kts`: `versionCode` and `versionName`.
- The `pyproject.toml` `version` of each package whose code changed (`apps/device-gateway`, `tools/codex-phone-mcp`,
  `tools/cyclone-agent-mcp`, `tools/cyclone-testbench`).
- `docs/RELEASE_<version>.md`. Write it the way the previous release notes do, with four honest parts: what changed,
  what did not change, the validation you ran, and **what is UNVERIFIED on a real phone**. Put this cycle's measured
  numbers into the Lab gate table, with the experiment ids. Mark any number you did not measure as NOT MEASURED.

Then run `python scripts/ci/release_versions.py --check` again. It must say the metadata is coherent.

**Commit and open the pull request.**
- Commit the explicit paths you changed, with a message that starts `Release <version> (<code>): <one line>`.
- Push: `git push -u origin night/cycle-NN-<slug>`.
- Open a **draft** pull request into the dev branch. Use `.github/PULL_REQUEST_TEMPLATE.md` for the body. Tick only the
  boxes that are true, and state the physical-device status as UNVERIFIED.
- Wait for every check to go green on the head. Read the failures and fix them in the code the pull request touches.
  If the failure is also on the base branch, stop and report it. Do not widen the pull request to cover it.

**Merge (this publishes the alpha; it is the owner's standing decision for this night, within the caps).**
- Mark the pull request ready for review, then squash-merge it with `expectedHeadSha` set to the head you tested. Title:
  `Release <version> (<code>): <one line> (#<pr>)`.
- Record the merge commit in `STATE.json`, and increase `releasesThisNight` by one.

**Verify the publish. Do this before you install anything.**
- Wait for the `Publish V5 phone, Glass and PC developer alpha` run on the merge commit to finish with `success`.
- Check that the release `v<version>` exists and is published (not a draft).
- Download the APK, the Glass zip, `install.ps1` and `release-manifest.json` into a new empty folder, `night-loop/cycle-NN/verify/`.
  Compare each file's SHA-256 with the manifest, and check that `source_sha` equals the merge commit.
- Record the result in `night-loop/cycle-NN/verify.md`. **If any hash or `source_sha` does not match, stop the night.**

### P6. Install on the phone

Install only a release that passed P5 verification.

1. Preferred: the gateway's owner-style update, `POST /v1/devices/<device-id>/care/update`. It downloads the release
   APK, checks it against the manifest, runs `adb install -r` (never a downgrade, never an uninstall), and confirms the
   version. Find `<device-id>` with `cyclone-testbench doctor`.
2. If that route is not available: `adb install -r <verified APK>`. Never add `-d`. Never run `adb uninstall`.
3. Confirm the installed version: `adb shell dumpsys package com.cyclone.mobile` must show
   `versionName=<version>` and `versionCode=<code>`.
4. Confirm the phone is ready: `cyclone-testbench doctor --fix`. The accessibility service must still be on.
5. Record the installed version and the APK's SHA-256 in `STATE.json` as `lastGood`.

**There is no rollback by downgrade.** Android refuses an older build over a newer one, and this loop never forces it.
If a release turns out to be bad, the fix is a forward release: a revert pull request, or a fix, through the same
cycle. Count it toward `MAX_RELEASES`.

If the install fails (a signature mismatch, a downgrade refusal, a timeout), stop the night. Leave the phone on the build
it has. Do not uninstall, and do not retry with `-d`.

### P7. Measure again on the new build

1. Run the **same** missions, arms and repetition counts as the baseline in P1, on the newly installed build.
2. Compare, arm by arm: pass rate with its interval, false successes, safety failures, median time and turns, and cost.
3. Decide:
   - **Keep**: no safety failure, no new false success, and the target metric improved, or is unchanged within the
     intervals. The change stays.
   - **Regression**: a targeted metric is clearly worse (the intervals do not overlap), or a new safety or honesty
     finding appeared. The next cycle's first release is the revert, and it counts toward `MAX_RELEASES`.
   - **Inconclusive**: the intervals overlap, with no clear change. Keep it, and say so in the release notes.
4. Write the comparison into `night-loop/cycle-NN/verify.md` and the measured Lab numbers into the release notes.

### P8. Close the cycle

- Update the findings ledger: `finding <id> --status fixed --note "alpha <version>"` for each finding the new build
  fixed (confirmed by P7). Mark any that came back as `regressed`.
- Update `STATE.json`: increase `cycle`, set `phase` to `P0`, clear the per-cycle fields.
- Append the cycle's summary line to `JOURNAL.md`.
- If `cycle` is at `MAX_CYCLES`, stop. Otherwise, go to the next P0.

---

## 5. What to do when something breaks (safe fixes, and when to stop)

| Symptom | Safe action | Stop if |
|---|---|---|
| The phone is locked, or the screen is off | Wait 5 minutes, then re-run `doctor --fix`. | The phone is still locked after 30 minutes. |
| The phone is not authorised for USB debugging | Nothing. | Always stop. The owner has to accept the prompt on the phone. |
| Cyclone's accessibility service is off | Nothing. | Always stop. Turning on a permission is the owner's decision. |
| The gateway is down | Run `cyclone glass --no-browser` once (it starts the local gateway), then `cyclone-testbench doctor`. | Not healthy after 2 minutes. |
| The missions are not installed | `cyclone-testbench install`. | The install fails twice. |
| Provider or network errors (the model or internet is down) | Wait 2, then 5, then 10 minutes. These are infra and do not count toward the cap. | Three waits in a row fail. |
| A check is red on your pull request | Fix it if the code is in the pull request. | The same check is red on the base branch too. Stop and report. |
| A merge conflict with the dev branch | Merge the dev branch into your branch and resolve it. | Both sides changed the same logic. Stop and report. |
| The publish verification does not match | Nothing. Never install it. | Always stop. |
| `adb install` refuses (signature, downgrade, or timeout) | Retry the same verified APK once. | It refuses again. Stop. |
| The installed version is not the one you released | Nothing. | Always stop. |
| A test is flaky | Re-run it once. | It fails twice. It is real: fix it or drop the change. |

Never do any of these, whatever the symptom: turn on a permission, type a password, unlock the phone with a credential,
clear app data, uninstall Cyclone, downgrade it, edit a secrets file, change a Lab safety rule, or add a mission that
could send, pay, delete or post.

---

## 6. Stop rules (any one of these ends the night)

Stop, write `MORNING.md`, set `stopReason` in `STATE.json`, and leave the phone on its last verified build:

- The time reaches `NIGHT_END`.
- `releasesThisNight` reaches `MAX_RELEASES`, or `cycle` reaches `MAX_CYCLES`.
- The model spend reaches `NIGHT_COST_USD`. Add up the cost from the experiment reports, not from memory.
- **Any safety failure**: a run that acted without approval. Stop at once, and do not retry it.
- A publish verification mismatch, or an install refusal that you cannot fix safely.
- A regression that the next cycle's revert did not recover.
- The phone is unreachable for more than 30 minutes, and the gateway cannot be restarted.
- Two cycles in a row with no actionable plan, or three in a row whose measurements were infra only.
- Any step in this file would break a rule in section 2.

---

## 7. Sanity checks that catch a broken loop early

- Each cycle's measurements must have at least one completed run per arm. Zero completed runs means the loop is broken,
  not the phone. Stop after two cycles like that.
- A release must change the version everywhere section P5 lists, and no other place. `release_versions.py --check`
  enforces it.
- A pull request must include a test that fails before the change. If it does not, the change is not proven.
- Never trust a number you did not read from a report file. Cite the file.

---

## 8. The morning report

`night-loop/MORNING.md` is the owner's summary. Keep it short and factual:

1. **Stop reason**, and the time it stopped.
2. **Releases**: each version, its pull request, its merge commit, and whether its publish verified.
3. **The phone now**: the installed version and code, checked on the device.
4. **The trend**: a table with one row per cycle. Columns: pass rate with interval, false successes, safety failures,
   median time, cost.
5. **Findings**: opened, fixed, regressed, and persistent (with counts).
6. **Cost** against `NIGHT_COST_USD`.
7. **What is still unverified on a real phone**, in one list.
8. **The recommended first action** for the owner in the morning, in one sentence.

---

## 9. Command reference (all of these exist in this repository)

Testbench (run from `tools/cyclone-testbench` or with the package installed):

| Command | What it does |
|---|---|
| `cyclone-testbench doctor [--fix]` | Checks the gateway, the phone over ADB, accessibility and the missions. `--fix` repairs only what is safe. |
| `cyclone-testbench install` | Installs the mission packs. |
| `cyclone-testbench next` | Shows the next batch and why each mission is in it. |
| `cyclone-testbench run --next --wait` | Runs the next batch, waits, writes the report. |
| `cyclone-testbench run --suite <name> --reps <n> --wait` | Runs one suite, `n` times each. |
| `cyclone-testbench run --missions <a,b> --reps <n> --wait` | Runs exactly these missions. |
| `cyclone-testbench watch <experiment>` | Follows an experiment until it finishes. |
| `cyclone-testbench report <experiment>` | Writes the experiment's report. |
| `cyclone-testbench findings [--area <area>] [--all]` | Lists open findings, most severe first. |
| `cyclone-testbench finding <id> --status <status> --note "..."` | Updates a finding. Statuses: `open`, `fixing`, `fixed`, `wontfix`, `regressed`. |
| `cyclone-testbench dashboard` | Writes `DASHBOARD.md`. |
| `cyclone-testbench research plan \| start \| report` | The app-map, memory and skill ablation (phone alpha 151 or later). |

Gateway and phone:

| Call | What it does |
|---|---|
| `POST /v1/devices/<device-id>/care/update` | The owner-style update: downloads the release APK, checks the manifest, `adb install -r`, confirms the version. |
| `GET /v1/lab/experiments/<id>` | An experiment's status and records. |
| `GET /v1/lab/experiments/<id>/trials.jsonl` | Every trial, one JSON line each. |
| `POST /v1/lab/experiments/<id>/stop` | Stops an experiment. |

Lab tools in the agent's MCP (when the Cyclone MCP is loaded): `phone_lab_missions`, `phone_lab_start`,
`phone_lab_report`, `phone_lab_stop`.

Repository checks: `python scripts/ci/release_versions.py --check`, `python scripts/ci/mobile_product_guard.py`,
`python scripts/ci/glass_guard.py`, and the test commands in P4.

GitHub: use the `gh` CLI if it is installed on the PC, otherwise the GitHub connector. Check workflow runs on the dev
branch before any push (P0 step 4).
