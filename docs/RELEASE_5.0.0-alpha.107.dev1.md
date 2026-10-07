# Cyclone V5 Alpha 107: Cyclone Fleet

Developer alpha for owner testing. It builds on Alpha 106 and includes it. It brings in Cyclone Fleet
(RTK23-dev/cyclone-fleet), fixes everything its review found, removes the phone limit and adds the phone overview.
Fleet docs: `docs/FLEET.md`, `docs/FLEET_OPS.md`, `docs/FLEET_ACCEPTANCE.md`.

Versions:
- **Mobile:** `5.0.0-alpha.107.dev1` (version code 252).
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.107.dev1.exe` (runtime `5.0.0-alpha.107.dev1`).
- **Glass:** `1.0.0-alpha.60`.

## What's new

**One sentence, many phones.** Glass → Command Center → **Multi-phone**. Type, for example, "check messages on Work
Phone, open the camera on Tablet":
- The PC splits the sentence by phone. It doesn't guess and doesn't call a model. If it can't tell which phone you
  mean, it asks.
- Each phone gets its own Command Center task, and its own Mind does the work with its own key, model and approvals.
- The tasks are grouped as a mission, which you can watch, stop, retry or export.
- "All phones", "all phones except Tablet" and "all phones in Sales" work too. They always ask you to confirm first.

**No phone limit.** The 16-phone and 32-phone caps are gone. A mission can name every phone you have:
- The list loads 100 phones at a time as you scroll.
- A page of missions costs one task query, however many phones each mission has.

**The phone overview.** Every phone gets one row:
- **Left:** the phone's colour with a status dot: ready, working, needs you, asleep, offline, needs attention,
  connecting or not paired.
- **Middle:** the name you gave it, and what it is doing.
- **Right:** **Rooted** or **Not rooted**, and the make and model, for example "Google Pixel 8 Pro".

Tap a row to:
- rename the phone;
- pick one of 12 colours;
- see Android version, connection, battery, network and last seen;
- mark it "don't target", so fleet commands never start it.

The summary tiles at the top double as filters, and there is a search box.

**Root detection.** The phone reports whether it is rooted:
- It looks for the files Magisk, KernelSU, APatch or an `su` binary leave behind.
- It never runs `su` for this, so no grant prompt appears.
- If you already ran Cyclone's own root check, its verified answer is used.
- A test-keys ROM is noted but doesn't count as rooted.
- A phone older than this alpha shows nothing (unknown).

**Smoother.** Rows update in place, so nothing flickers and nothing you're typing is lost. Live updates arrive
together, and only one refresh runs at a time. Polling slows down while live updates are connected.

## Fixed

- **Tasks never started on a real phone.** The Command Center has had this bug since alpha.51. The gateway's device
  list says `READY`, but the Command Center, its AI manager, vault delivery, Ports and Numbers only matched lowercase
  `ready`. All of them now accept both.
- **Every phone showed "Waiting".** The fleet view had the same uppercase/lowercase mismatch, so every phone showed
  "Waiting" and Ready was always 0.
- **Glass failed to build.** An unused variable in the fleet view broke the type check that runs before every build.
- **Canary rollout:**
  - "Continue" now waits until the canary has finished. A stopped or failed canary keeps the rest held back.
  - A rollout checks every phone before the canary starts: duplicates, do-not-target and pause.
- **Dispatch:**
  - One phone's unexpected error no longer stops the other phones.
  - A mission that can't be saved stops the tasks it created, so no hidden task keeps running.
- **Retry and cost:**
  - Retry no longer restarts a phone you stopped.
  - The spend cap and the export count the cost of every attempt, not only the latest.
- **"then" needed a confirm.** The fleet starts phones at the same time, so "then" now always asks you to confirm.
- **Smaller fixes:**
  - An unknown task state shows as "Checking…", not "Failed".
  - A live update without a mission id no longer changes another mission.
  - The battery line shows again.
  - The handoff reply is read correctly.
  - The phone now reports camera permission, which preflight already checked for.
  - A corrupt `fleet.db` is moved aside together with its journal files.

The fleet never answers an approval. A phone that needs you shows what it is asking and a button to the Approvals tab.

## Tests

- **New:**
  - Gateway `test_fleet_alpha107.py` (19):
    - a real `DeviceSession` that says READY gets its task;
    - a 60-phone mission;
    - one batched read per page;
    - canary gating and rollout validation;
    - crash and save-failure cleanup;
    - retry and the spend cap across attempts;
    - overview fields and presence;
    - "then" asks to confirm;
    - colours and corrupt-file handling;
    - root report filtering.
  - Phone: `FleetRootSignalsTest` (6).
  - Glass: `fleet-overview.test.mjs` (10):
    - parsing;
    - phone type;
    - rows;
    - name and colour in one save;
    - updates in place;
    - filters;
    - 250 phones;
    - live updates scoped to their mission.
- **Updated:** the fleet fixtures say `READY` like the real device list. The canary, overview-count, sleeping-hint
  and change-queue tests follow the new behaviour.
- **Results:** gateway suite, Glass (typecheck, build, 305 tests), phone gateway tests, the release and product guards
  (see the commits).

## Physical acceptance

UNVERIFIED. With two paired phones and Glass open:
1. Command Center → Multi-phone shows both phones with a green dot, "Ready", their make and model, and Rooted or
   Not rooted.
2. Tap a phone, name it "Work Phone", pick Teal and save. The row shows the new name and colour, and the colour
   survives a restart.
3. Type "open settings on Work Phone, open the camera on <other phone>". Both phones start. Both rows show "Working",
   and the mission's bar fills as they finish.
4. Lock one phone during a task. Its row shows "Asleep", and its mission row says why it's waiting.
5. Stop the mission. Both phones stop, and Retry doesn't offer the stopped phones.
6. On a rooted phone (Magisk), the row says Rooted. On a stock phone it says Not rooted. No root prompt appears.
