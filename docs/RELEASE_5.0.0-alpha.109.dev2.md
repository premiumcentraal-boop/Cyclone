# Cyclone V5 Alpha 109: screen reads that hold

Developer alpha for owner testing. It builds on Alpha 108 and includes it. It removes the bug that hid behind the tap
guard in every testbench run so far: Cyclone threw away its own screen reads whenever the status bar or the closed
notification area changed, which on a Pixel 8 is about once a second.

Versions:
- **Mobile:** `5.0.0-alpha.109.dev2` (version code 255).
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.109.dev2.exe` (runtime `5.0.0-alpha.109.dev2`).
- **Glass:** `1.0.0-alpha.60` (unchanged).

Update the PC and the phone together.

## What's fixed

**Screen reads hold.** Before using a read, Cyclone checks nothing changed during it. Its change counter also counted
events from the status bar and the closed notification shade, so on 5 October 2026 "the screen could not be read" hit
every run (4 to 24 times per run; 18 of 35 failed actions in the alpha 108 dry run). Now only windows that can change
what Cyclone reads count: app windows, the keyboard, and system windows that are active, focused or cover at least half
the display (an opened shade, a full-screen system surface). A window appearing, moving, resizing or taking focus still
voids a read through the window signature. (`TaskSurfaceWindows.changesTaskRead`, `recordObservationEvent`.)

**A changed read is retried in place.** Up to 3 reads within 600 ms, after the screen settles, before the Mind hears of
it; every race used to cost the Mind a turn. (`SemanticCaptureBoundary.captureRetrying`.)

**The approval review judges only what a tap does on the page it lands on.** For a tap by position the review now
looks at one thing: the control that receives the touch, in the window that takes it (the one on top at that point,
so a dialog over an app is the page), plus that control's own words and its own children. Words from anything behind
it or beside it no longer count. Before, the deepest node under the point spoke for the tap (in Gmail an e-mail row
under the floating Compose button made Compose look like a send), windows were ordered by id instead of by layer, and
a button could borrow a neighbour's words ("0/1" matched "0/10"). If the window on top cannot be told, every node
under the finger is still checked, so the review asks more, never less. Background-workspace taps follow the same
rule. This is review-side only: what the model sees is unchanged (the full tree, every window, every label), so it
still sees where an action will take it. (`ClickGateIntercept.labelsAtPoint`, `PhoneToolExecutor`.)

**A declined approval no longer ends the task.** The Mind is told that a decline blocks that one action: never retry it
or reach the same result another way, but finish what is still its to do (keep a draft) and say what was not done.

**A missing app manual is not a failed action.** It counted toward the Lab's early stop.

## Cyclone Lab and testbench 1.2.0

- Every approval request is recorded with its gate and the phone's redacted text; an approval check may require the
  gate (`{"check": "approval", "requested": true, "gate": "send"}`).
- `cyclone-testbench soak --reads 50`: read the screen 50 times; gate 1 of a run is 0 failed reads.
- `doctor --fix` restarts a stale ADB server once and warns when the phone charges over USB only.
- Reports lead with failed screen reads and taps refused as ambiguous.
- 10 new missions (`alpha109` suite) aimed at these fixes, and the two approval missions now require the right gate.

## Verification

- Android unit tests, gateway Lab tests and testbench tests pass on the owner's Windows PC.
- **Physical Pixel 8: UNVERIFIED** until the soak and the `alpha109` suite run on this build.
