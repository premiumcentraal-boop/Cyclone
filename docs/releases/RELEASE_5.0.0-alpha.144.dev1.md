# Cyclone Alpha 144: the phone is the truth

Developer alpha for owner testing. It is built on Alpha 143 and installs over it as a normal update.

## Versions

- **Mobile:** `5.0.0-alpha.144.dev1` (version code 298).
- **Cyclone for Windows, gateway and MCP:** `5.0.0-alpha.144.dev1`.
- **Glass:** unchanged, `1.0.0-alpha.68`.

## Why this release

Field test 143 ran Alpha 143 on the Pixel 8 for about three hours. It found that:

- Cyclone said "Tapped Profile" while Instagram's feed stayed.
- The same sentence went Instant one time and Flash the next, depending on a 1.5 s timer.
- "pull up google maps" was read as two apps.
- Two "Needs you" cards came in ten minutes, for a dialog and for a missed tab.
- Ask status stayed "running" after missions ended, and showed the last task's "failed" for a new request.
- Requests came back as a bare "rejected".
- Most of Instagram's Flash turns went to re-reading a screen that "changed during capture".
- Opening WhatsApp took 11 s.
- The brain store showed up in four freezes.

Each one was traced to its cause in the Alpha 143 source and fixed there. Plan 73 has the details.

## What changed

**Proof that a tap worked:**
- Cyclone now knows which screen it is on from what stays put: the app, the tab, the title, and the fixed controls.
  A feed moving by itself is not a change.
- A tap or drag counts only when the owner could see it work: another app, another screen or dialog, a switch or a
  field.
- This applies to Instant, Flash and OneMind. Per-app memory learns only from proven moves.
- Waiting for the screen to settle uses the same identity, so a busy feed or chat no longer keeps every tap and launch
  waiting up to 8 extra seconds.

**Routing:**
- The decision race waits up to 2.5 s instead of 1.5 s.
- When no model answers at all, the phone's own guess acts for an easy-to-undo action, and Instant proves it.
- "google maps", "google photos" and "youtube music" are each one app on every surface. Words count whole ("Meet" is
  not in "meeting").
- "pull up", "bring up", "open up" and "fire up" open apps at once.

**When a run is stuck:**
- Finishing a step resets the "same thing three times" count.
- Before asking you, Cyclone tries, once per milestone:
  1. close what is in the way ("Not now", "No thanks", "Close", the ✕);
  2. press Back;
  3. reopen the app.
- It never presses anything that confirms, allows, sends, pays or deletes, and never touches a permission, sign-in,
  install or share sheet.

**Ask status (Glass, MCP, Lab):**
- A handed-up request says why.
- It ends when its own mission ends.
- A new request reads "waiting" instead of the last task's result.
- A busy phone says which task is in the way and that you can try again. The PC passes that reason through, and
  `ASK_BUSY` is retryable.

**Reading the screen:**
- On a feed that never settles, the last of three reads is accepted when only the content moved. The window, scope
  and session must still hold.
- The PC's current-page read is refreshed when it is older than 3 s, and says how old it is.

**Smaller fixes:**
- The brain store looks one row up by its key, and its mirror is written off the main thread.
- Flash's tools explain themselves: `go_to` takes a map handle, and `open_link` is not for moving inside an app.
- The mid-run "Background check…" and "You are not using your phone…" lines no longer appear when you ask from inside
  the app the task works in.
- The testbench works on a Windows console without `PYTHONUTF8=1`.

## What did not change

- Approvals, the gates on sending, paying, deleting and permissions, and the secret rules.
- The Lab's test-only list, and the fleet's never-approve rule.
- Task Kit for every stop.
- The overlay and Glass.

## Not in this release

- A request queue: a busy phone still refuses, now with the reason.
- Screen diffs sent to the model, replayed recipes per screen, and cost per request.
- Changing how often Safe saves with root, the likely source of the "Superuser" toast. That is your call.

## Validation

- **Android:** `:app:testDebugUnitTest` passed, with new tests for the app names, the router, tap proof, screen
  identity, the recovery ladder and the ask status.
- **PC:** the gateway tests passed, with new tests for the busy reason, the bounded phone message and the
  current-page refresh. The testbench tests passed.
- **Guards:** `scripts/ci/tests` passed. `release_versions.py --check` is coherent.

## What is UNVERIFIED on a real phone

- "open my profile" from a playing Instagram feed: done only with the profile showing.
- "pull up google maps" from home: Instant.
- A dialog over an app during a Flash run: closed without a "Needs you" card.
- Opening WhatsApp and tapping in a feed: how much faster.
- Flash on Instagram: fewer "could not be read" turns.
