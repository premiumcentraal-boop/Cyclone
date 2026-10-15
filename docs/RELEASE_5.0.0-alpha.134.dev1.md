# Cyclone Alpha 134: a faster overlay

Developer alpha for owner testing. It is built on the published Alpha 133 and installs over it as a normal update.

## Versions

- **Mobile:** `5.0.0-alpha.134.dev1` (version code 288).
- **Cyclone for Windows, gateway and MCP:** unchanged, `5.0.0-alpha.133.dev1`.
- **Glass:** unchanged, `1.0.0-alpha.66`.

## What changes

The overlay does far less work while Cyclone acts. Its windows and the rules they keep are unchanged, so:
- the agent's view of the app stays clear;
- the Glass live view mirrors the overlay normally;
- the Secrets card stays hidden from capture.

The full reasoning is in plan 60, §9.

**Both modes (Full keeps its full look):**
- The task notification is rebuilt only when what it shows changes, and at most about once a second while only the
  words move. A new state (needs you, done) posts at once.
- An update that changes nothing, such as the model's "Thinking" tick about once a second, no longer re-lays out the
  overlay.
- Nothing asks for every frame any more just to sit still:
  - the Stop ring animates only while you hold it or after the first tap;
  - the screen-switch symbol moves only while the run switches screens;
  - the island ring turns only while working;
  - the progress light slides only when the progress is unknown.
- Each action makes fewer window calls. A window that is already out of the way pays nothing, and the overlay no
  longer waits for Android when it comes back after an action. About 33 ms less per action on the open card, and
  more when the card is folded.
- The keyboard check slows down when you're not typing, and checks at once when you tap the Ask field.

**Lite** (Settings → Visual quality → Lite, or Auto on the slower phones it already picks Lite for):
- no blur behind the glass, so the screen no longer has to be composed on the GPU for it;
- no halftone dots, tilt light or motion sensor;
- no full-screen Trace Field (the field's own setting is kept for Full);
- still spinners and rings;
- sizes snap instead of animating, so the overlay's window resizes once instead of on every frame of a transition;
- the card moves above the keyboard in one step.

## What did not change, on purpose

- Every overlay window is still an accessibility overlay. That is what the agent's reads and touch checks filter out,
  so the overlay never counts as covering the app.
- Only the Secrets card (and the small share pill) is capture-protected. A protected full-width window would black
  out the Glass mirror (the alpha.30 fix).
- A swipe Cyclone makes on its own glass still hides the overlay for that swipe, and only then.
- The one-window "stage" from plan 60 is deferred until it is tested on a phone.

## Validation

- **Android:** `:app:testDebugUnitTest` passed (2,688 tests, 0 failures), including the new `OverlaySpeedTest`.
- **Guards:** `scripts/ci/tests` passed (362), including the new `test_overlay_speed_guard.py`.
- **Release metadata:** `release_versions.py --check` is coherent.

## What is UNVERIFIED on a real phone

- How much smoother it feels in Full and in Lite.
- Stop held or double-tapped mid-action.
- The keyboard lift in Lite.
- Lite turning on with Visual quality, and Auto on a slower phone.
- That swipes near the card still hide it only for that swipe.
