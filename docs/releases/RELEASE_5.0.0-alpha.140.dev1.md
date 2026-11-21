# Cyclone Alpha 140: as big as the work

Developer alpha for owner testing. It is built on Alpha 139 and installs over it as a normal update.

## Versions

- **Mobile:** `5.0.0-alpha.140.dev1` (version code 294).
- **Cyclone for Windows, gateway and MCP:** unchanged, `5.0.0-alpha.138.dev1`.
- **Glass:** unchanged, `1.0.0-alpha.68`.

## What changes

The overlay is now as big as the work. Small things happen almost invisibly, short tasks stay folded, and only real
work opens the card. The design is in plan 70 (`Cyclone V5 plan/70-as-big-as-the-work.md`).

**Instant** ("open camera", "swipe up", "volume up"):
- The Ask bar folds into a small capsule: Cy working in its ring, the app on its shoulder, and pause / hold-to-stop.
- No "Opening the camera", no card, no progress line. When it's done, Cy hops and the bar comes back.
- Words appear only when there is something to read:
  - an answer, for example the time;
  - what didn't happen, for example "Couldn't find Pokémon GO.";
  - a call's 2-second cancel window, which still shows "Calling … Tap Stop to cancel." on the island.

**Flash** (a few routine steps):
- It runs as the island, with one line: what it does now, then "2 of 3".
- If it hands over to the Mind, the island opens into the card once. It doesn't if you moved it yourself.

**OneMind** (fully planned runs):
- It keeps its card. Cy works beside the steps, and thinks while the mind is awake (planning, replanning, checking).
- Finished steps no longer show two checkmarks.
- The island no longer says "Task in Cyclone Mind"; it shows the progress.

**Plan diversion:**
- When Cyclone changes course on its own and there is nothing to unfold (every OneMind replan today), it is one quiet
  line, "Plan diverted", in the calm colour, with the new steps under it.
- With a reason, an earlier plan or dropped steps, or when you steered it, it is warm and opens on a tap, as before.
- At most three new steps show.

A run only grows on its own (capsule → island → card); only you fold it back.

## What did not change

- The runtime: modes, routing, steps, plans and approvals are as before. The overlay only reads what already exists.
- Windows, touch, focus and capture rules, the glass and Cy.
- Voice and Drive keep their own panel and the done sound.
- Needs you: the owner card, with the message as the hero.

## Validation

- **Android:** `:app:testDebugUnitTest` passed, including the new `RunPresentationTest`.
- **Guards:** `scripts/ci/tests` passed.
- **Release metadata:** `release_versions.py --check` is coherent.

## What is UNVERIFIED on a real phone

- How the capsule looks and feels for Instant: open camera, swipe up, volume, and the time as an answer.
- A call's cancel window on the island.
- A Flash run as the island, and its growing into the card.
- Cy thinking during a OneMind replan, and the quiet diversion line.
