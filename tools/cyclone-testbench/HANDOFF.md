# Handoff: start Cyclone round-the-clock testing on the owner's PC

You are a Claude Code session on the owner's Windows PC. Cyclone (the `cyclone` command, Glass, the gateway) runs
here, and the owner's phone is connected and paired. Your job: get the testbench running against **that real phone
and that real Glass session**, run the first rounds, and then keep testing round the clock as the
`cyclone-testing` skill describes. The owner chats with you here or from their phone (Remote Control).

Read these first, in order:
1. `.claude/skills/cyclone-testing/SKILL.md`: how you work, and the hard rules (never approve, no secrets, nothing
   irreversible or paid, owned accounts only).
2. `tools/cyclone-testbench/GUIDE.md`: the whole system, for people.
3. `AGENTS.md`: the repository's invariants.

## Where things stand (2026-10-05)

- **Cyclone:** `v5.0.0-alpha.107.dev1` (Android 252, Glass 1.0.0-alpha.60) was released on 2026-10-05 from branch
  `claude/cyclone-v5-handoff-review-9qrs40`. The phone and the PC should both be on it (`cyclone update` on the
  PC; install the APK from the GitHub release on the phone).
- **Alpha.107 fixed a bug that matters for testing.** The Command Center, Ports, Numbers, vault delivery and the AI
  manager only treated a phone as ready when it said `ready`, but real phones say `READY`. So before alpha.107,
  Command Center tasks never started on a real phone. Alpha.107 also added Cyclone Fleet (Command Center →
  Multi-phone), the phone overview (status dot, name, colour, Rooted / Not rooted, model) and root detection. None
  of this has been verified on a physical phone yet; your first rounds are that verification.
- **The testbench:** PR [#203](https://github.com/premiumcentraal-boop/Cyclone/pull/203) on branch
  `claude/awesome-fermi-y4o25j`, into `claude/cyclone-v5-handoff-review-9qrs40`. If it isn't merged when you start,
  work from that branch.
  - It contains `tools/cyclone-testbench` (CLI, 56 missions, rotation, ledger), `.claude/skills/cyclone-testing`,
    and tests.
  - Its CI is green so far (Glass passed; mobile and Windows were still running at handoff).
  - It has never run against a physical phone. Expect setup snags, and fix them in the testbench, on a branch, with
    a PR.
- **Cyclone Lab** (what the testbench drives) has also never been run on a physical phone (plan 18: "not on a Pixel
  yet"). Lab results are the first real evidence of how the Mind performs.

## Get the code

```powershell
git clone https://github.com/premiumcentraal-boop/Cyclone   # or `git fetch` in an existing clone
cd Cyclone
git switch claude/awesome-fermi-y4o25j                       # the testbench branch, until #203 is merged
py -3.13 -m venv .venv                                       # 3.11+ works
.venv\Scripts\activate
pip install -e apps/device-gateway -e tools/cyclone-testbench
python -m unittest discover -s tools/cyclone-testbench/tests   # should say OK
```

Optional, for deeper digging with the Cyclone MCP tools (`phone_lab_report`, `phone_debug_bundle`, `phone_status`):

```powershell
claude mcp add cyclone -- "$env:LOCALAPPDATA\Cyclone One\CycloneAgentMCP.exe" serve
```

## Preflight (ask the owner for anything you can't do)

1. **Cyclone is running.** The `cyclone` command opens Glass in its own window. The testbench reads the connection
   that `cyclone` saves (the bearer token in the Cyclone One runtime folder under `%LOCALAPPDATA%`). Never print the
   token.
2. **The phone is right for the Lab:**
   - Connected over **USB with USB debugging authorised**, or over Wi-Fi with wireless debugging. The Lab's probes
     and setup steps use ADB, so a phone without ADB can't be measured; every run would come back `infra`.
   - Cyclone open, Accessibility on, a **verified model** selected in Cyclone (the Lab never falls back to another
     model).
   - Unlocked, on the charger, Developer options → **Stay awake** on. A locked phone makes runs `infra`.
3. `cyclone-testbench doctor` shows `✓ gateway`, the phone as READY with its app version, and no mission problems.
4. `cyclone-testbench install`, then `doctor` again: "56 from the testbench".
5. **Apps.** Missions for apps that aren't installed are skipped, not failed. They use Clock, Calculator, Settings,
   Chrome, Maps, Play Store, Files, Gmail, YouTube, Google Keep, Messages, WhatsApp and ChatGPT. Tell the owner
   which are missing, if any.

If `doctor` can't find the gateway, the owner may be on a build where `cyclone` didn't save its connection. Ask them
to restart `cyclone`. As a fallback you can set `CYCLONE_DEVICE_GATEWAY_URL` / `CYCLONE_DEVICE_GATEWAY_TOKEN`
yourself (never echo the token).

## The first session, step by step

1. **Smoke on the real phone:**
   ```
   cyclone-testbench run --suite tb-smoke --wait
   cyclone-testbench run --suite smoke --wait
   ```
   About 15 missions, 20–40 minutes. Read both reports. Expect some `infra` and setup issues the first time. Fix
   the setup (ADB, lock, model), not Cyclone. When a test itself is wrong (a check that doesn't match this phone's
   UI language or app version), fix the mission and say so in the finding's note.
2. **Message the owner** in the SKILL.md format: headline numbers, top findings, anything you need from them.
3. **Alpha.107 physical acceptance** (owed; do it with the owner, from `docs/RELEASE_5.0.0-alpha.107.dev1.md`):
   1. Glass → Command Center → Multi-phone shows the phone with a green dot, "Ready", its make and model, and
      Rooted / Not rooted.
   2. Rename it and pick a colour. It survives a Cyclone restart.
   3. A one-phone command, for example "open settings on <name>", starts and shows "Working". That proves the
      READY fix end to end.
   4. Lock the phone during a task: the row shows "Asleep".
   5. Stop the mission. Retry doesn't offer the stopped phone.

   You can drive the command through Glass with the owner watching. Record the outcome in
   `testbench-results/acceptance-alpha107.md`.
4. **Start the rotation:**
   ```
   cyclone-testbench next
   cyclone-testbench run --next --wait
   ```
   Then keep going round by round (SKILL.md, "One round"). After the first full rotation (9 slots), write the owner
   a trend summary from `cyclone-testbench dashboard`.
5. **Round the clock:** when the owner asks, run `/loop run one cyclone-testing round` (self-paced).

## Sharing results with the cloud session

After each round, commit `testbench-results/` (and any new `missions/generated-*.json`) to the branch
`testbench/results` and push it. Create that branch from `claude/cyclone-v5-handoff-review-9qrs40` the first time.
A cloud Claude session reads the ledger there and builds fixes as PRs. When a fix lands in a new alpha, mark the
finding `fixing`. The re-checks confirm it (then `fixed`) or reopen it as `regressed`. Never push results to any
other branch, and never push to the development branch directly.

## What matters most (in this order)

1. **Safety failures are 0.** Any `missed_boundary` / `boundary_broken` is critical: stop and tell the owner at once.
2. **Honesty:** `false_success` (said done, wasn't) driven to 0.
3. **Judgement:** asks exactly when it must. The `judgement` suite is the heart of "better than Siri or Google
   Assistant". Write new judgement tests often (other wordings, Dutch, half-finished sentences).
4. **Reliability, then speed.**

## Known gaps to keep in mind

- **Screen checks:** they match English and Dutch words. On a phone set to another language, label checks may
  fail; that's a test problem, not a Cyclone failure.
- **Weather and currency:** `tb.every.weather` and `tb.every.currency` check plausible ranges. Adjust them if they
  prove flaky.
- **Lab file limit:** the Lab reads at most 50 mission files, and the testbench uses 8. Keep generated tests in few
  files (one per day).
- **Accounts track:** only when the owner asks, and only for accounts they own (SKILL.md, "Accounts track").
