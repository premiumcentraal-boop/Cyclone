# Cyclone Alpha 135: meet Cy

Developer alpha for owner testing. It is built on Alpha 134 and installs over it as a normal update.

## Versions

- **Mobile:** `5.0.0-alpha.135.dev1` (version code 289).
- **Cyclone for Windows, gateway and MCP:** unchanged, `5.0.0-alpha.133.dev1`.
- **Glass:** unchanged, `1.0.0-alpha.66`.

## What changes

**Cy**, Cyclone's character, is a first concept in the overlay. It is a frosted white orb with two dark eyes, and it
has small orb hands only when they mean something: busy while working, a wave when it needs you, and hands up when
it's done.

Cy appears only where the overlay already shows Cyclone:
- **Idle:** Cy floats where the glass bubble was, on its own, with a soft halo. Taps still make it jump, and the third
  tap opens Cyclone as before.
- **The island:** Cy works inside the turning ring, glancing where the work is. The app it works in sits as a small
  badge on its shoulder, so the island still says where. Paused, Cy closes its eyes.
- **The working card:** Cy sits beside the steps and works with its hands. On a finished task it is happy beside the
  outcome; when a task couldn't finish it looks puzzled.
- **Needs you:** Cy looks up at you and waves beside the "Needs you" chip. The message you approve stays the biggest
  thing on the card.

**What did not change:**
- The glass: the same smoke, the same dotted edges, the same shining rims, and the same sizes and spacing.
- Windows, touch, focus and capture rules: Cy is drawn inside surfaces that already exist. It adds no window, no
  panel, no touch area and no protected layer, so the agent's view and the Glass live view are as before.

**Motion follows the glass rules.** Cy is still unless something is happening:
- Only listening, thinking and working loop, and only while they last.
- Idle and waiting Cy move only in short gestures: a blink every few seconds, a wave, a hop. Between gestures it asks
  for no frames.
- Lite draws Cy still.

**Turn it off:** Settings → Appearance → Working indicator → **Cy**. Off brings back Cyclone's mark in every place.

## Validation

- **Android:** `:app:testDebugUnitTest` passed, including the new `CyTest`.
- **Guards:** `scripts/ci/tests` passed.
- **Release metadata:** `release_versions.py --check` is coherent.

## What is UNVERIFIED on a real phone

- How Cy looks and moves on the phone, in Full and in Lite.
- Cy over light and dark apps in the idle spot.
- The app badge in the island.
- Cy beside long step lists and a changed plan.
- The switch in Settings.
