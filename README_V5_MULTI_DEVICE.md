# Multi-device orchestration for Cyclone V5 (alpha.38)

Built against your uploaded V5 codebase specifically (not a port of the V4
patch) - checked the real observe/action contract, the input-ownership
handoff, and the lock-screen boundary in this codebase before writing
anything.

## New backend files (device-gateway)
- `desktop_runtime/task_runner.py` - one independent AI loop per device, run
  concurrently. Every action requests AI input ownership properly; if a
  phone is locked or a human is using it, the task **pauses** (status
  `BLOCKED`) - it never attempts to work around either. Includes a shared
  `MissionScratchpad` so one device's task can leave a note another
  device's task reads (e.g. passing a verification code across devices).
- `desktop_runtime/command_splitter.py` - the one-command-box piece: turns
  one sentence into a goal per device. If it can't confidently match part of
  the sentence to an actual paired device, it refuses to guess and returns a
  clarification question instead of dispatching anything.
- `desktop_runtime/scenes.py` - saved multi-device routines, referenced by
  device *nickname* (not raw id) so they survive re-pairing. Running a scene
  reports, rather than silently skips, any device that's no longer paired.

## Edited backend files (full replacement - overwrite yours)
- `desktop_runtime/workspace.py` - added persisted, unique, case-insensitive
  device nicknames ("Work Phone", "Tablet").
- `desktop_runtime/api.py` - wired all of the above in as new endpoints:
  - `POST /v1/fleet/devices/{id}/nickname`
  - `POST /v1/fleet/tasks`, `GET /v1/fleet/tasks[?missionId=]`,
    `GET /v1/fleet/tasks/{id}`, `POST /v1/fleet/tasks/{id}/cancel`
  - `POST /v1/fleet/command` (the one-command-box)
  - `GET/POST /v1/fleet/scenes...`, `POST /v1/fleet/scenes/{id}/run`

## Tests - 46/46 passing, run yourself with:
```
cd apps/device-gateway
python -m pip install -e ".[test]"
python -m pytest tests/test_device_nicknames.py tests/test_multi_device_task_runner.py tests/test_command_splitter.py tests/test_scenes.py -v
```
(All 46 were actually executed in the sandbox that built this, using
stdlib-only fakes - no network needed for that part. The FastAPI route layer
itself - `api.py`'s actual HTTP wiring - could not be executed in that
sandbox, since it had no internet to install fastapi. It byte-compiles clean
and follows the exact same patterns as the surrounding code, but please run
the full suite, including your pre-existing tests, on your machine to
confirm the wiring itself is correct.)

## New/edited frontend files (pc-companion)
- `ui/deviceTaskPanel.ts` (edited) - per-device Rename + Ask… + Stop, live
  status.
- `ui/missionControl.ts` (new) - the command box (with a mic button for
  voice, when the browser supports it), a unified status line across every
  running task, and a saved-routines ("scenes") manager.
- `ui/modelCredentials.ts` (new) - one shared, session-only prompt for
  model/providers/API key, reused by every entry point below so you're not
  asked four times. Never written to disk.
- `pages/fleetPage.ts`, `services/types.ts`, `services/httpDesktopService.ts`,
  `services/mockDesktopService.ts`, `styles.css` (all edited) - wired
  everything into the real fleet UI, and into mock mode so you can try it
  with zero setup.

Type-checks clean (`tsc --noEmit`) - every error left over is pre-existing
(missing `node_modules`, unrelated to this change).

## Try it with zero setup
```
cd apps/pc-companion
npm install
npm run dev
```
Open the printed URL with `?mock=2` on the end. Two mock devices are
pre-named "Work Phone" and "Tablet". Try:
- Typing into the command box: `check messages on Work Phone, take a photo on Tablet`
- Both should start and finish independently.
- Try "New routine…" to save that as a scene, then Run it later.
- Try Ask… on a single device's card directly.

## What's still not done
- **Unified approval surfacing** beyond the status line showing "N need
  you" - a dedicated single approval prompt (rather than the person having
  to click into each blocked device) is the next piece, not built yet.
- **Real hardware verification** - everything above is tested with fakes;
  nothing here has run against your actual paired phones yet. That's the
  natural next step once this is merged in.
- **Phone-as-hub** (a phone controlling other devices) is still not
  attempted - this is laptop/root-device-as-hub only, same as before.
