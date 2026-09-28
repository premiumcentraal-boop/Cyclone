# Cyclone V5 Alpha 61: Driver mode no longer crashes Cyclone

Developer alpha for owner testing, a hotfix. It builds on Alpha 60 and includes it.
- **Mobile:** `5.0.0-alpha.61.dev1` (version code 206).
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.61.dev1.exe` (runtime `5.0.0-alpha.60.dev1`, unchanged).
- **Glass:** `1.0.0-alpha.36` (unchanged).

## What was wrong

Switching **Driver mode** on crashed Cyclone entirely. This was reported by the owner, and it has been true since
Drive first shipped in Alpha 49.

The Drive button is a Compose view inside a small touch frame, drawn in its own overlay window. Compose looks for its
lifecycle on the window's root view, which is the touch frame. Only the inner view had it, so the button's first
frame threw an exception, and because the overlay runs in Cyclone's own process, the whole app went down.

The unit tests and CI builds of Alpha 49–53 could not catch this: it happens only when the window is really drawn on
a phone. Everything built on Drive (announcements, the car microphone, readbacks) was therefore never reachable on
a phone until now.

## What changed

- **The fix:** the touch frame now carries the lifecycle, view-model and saved-state owners, as Cyclone's other
  overlay windows already did. Driver mode switches on and the button draws.
- **A CI guard** (`test_drive_overlay.py`): every Compose view hosted inside another view in Drive's overlay must give
  its host the owners. The guard fails on the Alpha 53 code and passes now.
- **The microphone service** is now started only when the microphone permission is granted. Before, a first tap
  without the permission could make Android refuse the service after it had started, which also crashes an app.

## Validation and limits

- **Passes:** all CI guards and the Mobile CI build and unit tests.
- **Physical status: UNVERIFIED.** The project has no on-device UI tests, so the fix is proven by the code path and the
  guard, not yet by a phone.
- **Please test in this order:**
  1. switch Driver mode on;
  2. tap the orb and say "set a timer for one minute";
  3. only then try announcements and the car microphone.
  If anything still closes Cyclone, say what you tapped last.

Install the APK as an update; do not uninstall it. It is signed with the rotated Cyclone release key.
