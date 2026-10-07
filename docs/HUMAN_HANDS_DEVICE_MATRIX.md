# Human Hands device matrix (plan 52, run 7)

What has to be seen on a real Pixel before any Human Hands behaviour is called verified. Until a row has a dated
result from a physical phone, it is **UNVERIFIED** (as of 5.0.0-alpha.113, every row is).

## How to run it

1. **Harness.** Install the debug build, then run `tools/human-gesture-lab/android_v03_device_harness.sh [serial]`.
   It opens the debug-only gesture page (tap, long-press and scroll targets) and prints its own checklist.
2. **Testbench.** On the paired PC, run the `hands3` suite:
   `python -m cyclone_testbench run --suite hands3`.
   - It has ten missions in `tools/cyclone-testbench/missions/testbench-natural-hands.json`: keypad taps, a thumb
     scroll, tabs, keystrokes in Chrome and in Keep, zooming Maps and a web page, a photo double tap, drawing in Keep,
     and dragging a checklist item.
   - The suite is also in the round-the-clock rotation's `hands` slot.
3. **Device capture (optional).** Record the screen with "Show taps" and "Pointer location" on, for the speed-curve
   and placement rows.
4. Do it twice: once with Hands = Natural (the default) and once with Precise.

## The matrix

| # | What | How to see it | Pass when |
| --- | --- | --- | --- |
| 1 | Speed curves arrive as planned | Pointer location trail during a feed swipe | The trail's dots bunch at both ends of a glide, and are still spread at lift-off for a flick. If they are evenly spaced, chained pieces were dropped: `segmented=false` in the result. |
| 2 | Taps hit every target | Harness tap targets; `tb.hands3.calc.taps` | No missed or neighbouring key over 50 taps. Landing points cluster near each centre, a little low. |
| 3 | Touch-first presses | Any Mind run; results show `dispatchMode=touch_first` | The control reacts the same as with Precise. |
| 4 | Keystrokes in Chrome | `tb.hands3.chrome.keys` | The keyboard shows and the text appears key by key. The result says `method=keys`. |
| 5 | Keystrokes in a native / Compose form | `tb.hands3.keep.keys` | The same, and read-back is exact. |
| 6 | Keystrokes in a WebView form | Any in-app browser sign-in page (non-secret field) | `method=keys`, or an honest fallback to `set_text` with the reason. |
| 7 | Pacing | Time between Mind actions in the run diagnostics | Same-page actions are about 0.1–0.5 s apart. A new page gets about 0.3–0.9 s (Relaxed: up to 2.2 s). Precise gets none. |
| 8 | Double tap | `tb.hands3.photos.doubletap` | The photo zooms (Android saw a double tap, not two single taps). |
| 9 | Pinch | `tb.hands3.maps.zoom`, `tb.hands3.chrome.zoom` | Maps and the page zoom in, and `achievedScale` is reported. |
| 10 | Drag | `tb.hands3.keep.drag` | The item is picked up (it lifts), moves and lands in its new place. |
| 11 | Draw | `tb.hands3.keep.draw` | A circle appears on the canvas and no approval is asked. A signature-style shape asks the owner. |
| 12 | Background displays | Run 2, 4 and 10 in a background workspace | Same results. Pinch is refused with "not available on background screens yet". |
| 13 | Instant | Say "zoom in" on Maps; say "drag tb two to tb one" in Keep | Each happens in one move. "drag … to Trash" goes to the Mind, which asks first. |

## What to do with a failure

- **Pieces dropped (row 1).** If a phone drops chained pieces, Cyclone switches them off for the process and says
  `segmented=false`. Report the phone model; the per-phone default may need to change.
- **A missed tap (row 2).** Note the control's size. If it is under about 32 px, touch-first should not have been
  chosen. File it against `TouchFirst`.
- **Pinch on a background display.** Pinch stays off there until rows 9 and 12 both pass on a device. Then set
  `HandGesturesSupport.PINCH_ON_BACKGROUND`.
- **Pins.** Each `humanize=off` pin that still has to stay goes in the table below, with the reason.

## Pins that stay

| Where | Why |
| --- | --- |
| `GatewayV33ActionAdapter` desktop manual control (tap and swipe) | The owner chooses that exact pixel on the PC. It is their own hand, not an agent action. |
