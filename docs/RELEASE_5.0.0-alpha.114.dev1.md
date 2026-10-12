# Cyclone V5 Alpha 114: Human Hands, final

Developer alpha for owner testing. It builds on alpha.113 dev6 (native Instagram skills and the Skills library) and
includes it.

- **Mobile:** `5.0.0-alpha.114.dev1` (version code 267).
- **PC runtime:** gateway and MCP `5.0.0-alpha.114.dev1`. The gateway reports the new gestures truthfully. It also
  fixes a Port Hub race (below).
- **Glass:** `1.0.0-alpha.62` (unchanged).

This alpha finishes plan 52 (`Cyclone V5 plan/52-human-hands.md`).
- **Run 6:** drag, pinch, double tap and drawing.
- **Run 7:** Natural becomes the final default, the docs are brought up to date, and a device matrix is added.
- **Also:** tap placement, press length and the pauses between actions now behave much more like a person's.

## Hands that feel like a person's

**Where a finger lands.**
- With Natural hands (the default), a button is pressed near its middle, a little low and a little toward the hand
  holding the phone, as a thumb does.
- The spread grows with the control but stops growing on big ones, so a long list row is pressed near its middle and
  never at its far end.
- The point always stays inside the part of the control that was checked to be uncovered, so a tap never misses.
- A press lasts about 50–170 ms (typically around 90 ms) instead of a fixed 80 ms.

**The pace between actions.**
- On a new page, Cyclone reads first.
- When it acts again on the same page (a keypad, a form), it only glances, so repeated actions flow.
- A far or small target takes a little longer to reach than one next to the thumb (Fitts's law).
- Typing right after tapping a field is quick, and an approved irreversible step gets one more look.
- Time the model already spent thinking counts toward the pause, so a slow model adds no extra wait.
- Precise hands still never pause.

**Natural is the default, from the first moment.** The owner's Hands choice now loads when the app starts, not only
when Accessibility connects. Settings shows "Natural (default)".

## New gestures

| Gesture | What happens | Safety |
| --- | --- | --- |
| **Double tap** (like a post, zoom a photo) | Two quick presses 90–180 ms apart, the second a few pixels from the first, both inside the control | The same approval check as a tap |
| **Drag** (reorder, move into a folder, slide a handle) | Press and hold until the item is picked up, carry it, slow into the drop, rest, release. Drop onto another control, or drag by direction. | Dropping on Trash or Bin is a delete, so the owner approves it |
| **Zoom** (maps, photos, small text) | Two fingers on a slanted thumb–index axis. The thumb moves less and a beat later, and the axis turns a few degrees. | Main screen only for now |
| **Draw** (a check, a circle, a signature box) | A shape (circle, check, underline, zigzag, scribble, signature-style) or up to 4 short lines, with a pen's speed and a slight tremor | Only inside a real drawing or signature canvas. **Every signature asks the owner first.** |
| **Swipe style** | `arc`, `straight-ish` or `s-curve` when a path matters; otherwise it varies by itself | Unchanged |

None of these takes screen coordinates from a caller: the phone plans every point.

## For agents

- **The Mind has new tools:** `double_tap`, `drag`, `zoom` and `draw`, plus `swipe` with `speed` and `style`.
- **Its instructions gain a "Your hands" section:**
  - name the control and what you mean, never pixels or timings;
  - use the gesture a person would;
  - look, act, then look again;
  - don't add waits to look human (the pauses are built in).
- **Instant understands two new commands:** "zoom in" / "zoom out", and "drag X to Y". A drop on Trash, or on anything
  else that can't be undone, goes to the Mind, which asks first.
- **Capabilities are reported per display.** The PC gateway shows the new gestures only when the phone lists them.
  Pinch is reported as unavailable on background screens. The PC and MCP have no route to these gestures.

## Pins

- **Removed.** The Instagram stock skills no longer pin `humanize=off`: their scrolls are now intent swipes planned
  by the hand model.
- **Kept, on purpose.** The PC's desktop manual control keeps its `off` pin, because there the owner picks the exact
  pixel themselves.

## Fix: a code delivered by the PC is never missed

- **The problem.** When a Port plugin delivered a sign-in code, the hub marked the wait "delivered" a moment before it
  stored the code. A run that looked at exactly that moment was told a code had come, then found nothing to take.
- **The fix.** The hub now stores the code first and only then marks the wait delivered.
- **How it was found.** CI caught it in `test_ports_traffic`.
- **Test:** `test_a_delivered_value_is_held_before_the_wait_reads_as_delivered`. It fails on the old order and passes
  on the new one.

## Tests

- **Engine tests:**
  - `HandGesturesTest`: double-tap timing and spacing; drag pickup, slowing and rest; the pinch keeping its fingers
    apart and inside the area; drawing staying in the canvas; caller strokes bounded; replay.
  - `HandPlacementTest`: landing points, press length, Fitts's law, pacing, hand memory.
- **Tool tests:**
  - `HandGestureToolsTest`: raw points are refused; a drop on Trash is DELETE; any signature is GRANT; drawing is
    only on a canvas; stale targets are refused.
  - The safety contract: approval before every gesture.
  - Capabilities per display.
- **Mind and Instant:** Mind gesture tools (`PhoneMindToolboxTest`), and Instant zoom and drag, including the Trash
  promotion (`ModesTest`).
- **PC side:**
  - Gateway: the projection of the new gestures, including older phones that don't list them.
  - Testbench: new suite `hands3` with 10 missions.
  - CI guards updated.
- **Results:**
  - The pure gesture and tool code was also compiled and run locally (81 tests).
  - Gateway, MCP, testbench and CI-script suites pass.
  - Mobile CI (unit tests, lint, release build) passes.

## Limits

- **Physical: UNVERIFIED.** No phone was available for this build. The device matrix in
  `docs/HUMAN_HANDS_DEVICE_MATRIX.md` (and testbench suite `hands3`) lists exactly what must be seen on a Pixel. Until
  then, every gesture claim here is from tests, not from a device.
- **Drag needs chained strokes.** On a phone that refuses them, drag reports itself unavailable instead of
  pretending.
- **Pinch is main screen only** until a device proves it on background screens.
- **Owner templates** ("Teach Cyclone my hands", plan 52 run 5) are still not built.
- **Numbering.** This was planned as alpha.113. Alpha.113 shipped as the native Instagram skills (dev1–dev6), so
  this is alpha.114. Plan 55 reserved alpha.113 for Cyber R6, which moves on.
