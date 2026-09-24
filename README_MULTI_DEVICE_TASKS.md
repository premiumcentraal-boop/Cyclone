# Multi-device AI tasks — what changed

This adds the "root device tells device A to do X and device B to do Y, at
the same time" capability on top of the fleet plumbing that already existed
in `apps/device-gateway`.

## New file
- `apps/device-gateway/cyclone_device_gateway/desktop_runtime/task_runner.py`
  The actual engine: one observe→plan→act→verify loop per device, run
  concurrently. Calls OpenRouter directly to decide each next step.
- `apps/device-gateway/tests/test_multi_device_task_runner.py`
  11 tests, all passing, using fakes (no network, no real device needed).
  Run with: `cd apps/device-gateway && pip install -e '.[test]' && pytest tests/test_multi_device_task_runner.py -v`
- `apps/pc-companion/src/ui/deviceTaskPanel.ts`
  A small "Ask…" control that appears on each phone's card in the fleet
  view, with a live status badge and a Stop button.

## Edited files (full replacement — these are your original files with the
## change merged in, so just overwrite them)
- `apps/device-gateway/cyclone_device_gateway/desktop_runtime/api.py`
  Added `POST /v1/fleet/tasks`, `GET /v1/fleet/tasks`,
  `GET /v1/fleet/tasks/{id}`, `POST /v1/fleet/tasks/{id}/cancel`, and wired
  a `MultiDeviceTaskRunner` into `DesktopRuntime`.
- `apps/pc-companion/src/services/types.ts`
  Added `DeviceTask`, `DeviceTaskRequest`, and the new `DesktopService`
  methods (`startDeviceTasks`, `getDeviceTask`, `listDeviceTasks`,
  `cancelDeviceTask`).
- `apps/pc-companion/src/services/httpDesktopService.ts`
  Implemented those four methods against the new endpoints.
- `apps/pc-companion/src/pages/fleetPage.ts`
  Each paired device card now shows the new task panel.
- `apps/pc-companion/src/styles.css`
  A handful of `.device-task-*` rules for the new panel.

## What's verified vs. what still needs your environment
- `task_runner.py` logic: verified — 11/11 tests pass in this sandbox with
  plain `python3 -m unittest` (stdlib only, no install needed).
- `api.py`: byte-compiles cleanly, follows the exact same patterns as the
  existing fleet endpoints, but this sandbox has no network so I could not
  `pip install fastapi` to boot the real server or run the existing gateway
  test suite against it. Please run `pytest apps/device-gateway/tests` after
  merging.
- `pc-companion` TypeScript: type-checks cleanly with `tsc --noEmit` (no new
  errors introduced; the only errors shown are pre-existing missing
  `node_modules`, unrelated to this change). Please run `npm install && npm run build`
  (or your usual dev command) to confirm it actually renders.

## How to try it once merged
1. Pair two Android devices to the PC companion as usual (Fleet page).
2. On one device's card, click **Ask…** — enter a goal, an OpenRouter model
   id, allowed provider(s), and your OpenRouter API key (asked once per
   session, kept in memory only, never saved to disk).
3. Do the same on a second device's card with a *different* goal.
4. Both run independently — you'll see each card's status badge step through
   `Step n/40…` on its own, and either finish or need you (`Needs you`) on
   its own schedule.

## Known limits of this first version
- One task per device at a time (starting a second while one is running is
  rejected with "device busy" — cancel or wait for it to finish first).
- The planner is deliberately blocked from ever touching anything that
  smells like a payment, deletion, account removal, etc. — those goals come
  back `BLOCKED` and ask for a human, matching Cyclone's existing approval
  boundary. This is a first-pass keyword filter, not the full on-device GATE
  policy — treat it as a safety net, not a substitute for testing goals
  yourself before trusting them.
- This is the **laptop-as-hub** direction only. Phone-as-hub (a phone
  controlling other devices) is a separate, bigger piece of work not
  started here.
