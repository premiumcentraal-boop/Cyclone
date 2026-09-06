# Cyclone 3.9.11 — Live Context + Composer Polish

Base: published v3.9.10, `553688ca8d1180adb4f8cbc3b19764923404612a`.
Work branch: `release/cyclone-mobile-v3.9.11`.

## Incremental delivery

1. Capture lifecycle, consent scope, frame sampling and invalidation.
2. In-window accessory deck, existing model preference, picker focus return, share status/Stop and overlay privacy.
3. Regression coverage, version 3.9.11 / code 65, exact-source Mobile CI and release evidence.

Checkpoint commits are pushed with CI skipped until the integrated candidate is ready. No changes to published 3.9.10.

Ordinary Android app-or-screen sharing must not be mislabeled as display-0 coordinate evidence. Only explicitly requested whole-display capture may feed foreground control. Background workspace capture remains independently session-scoped and never falls back to the main display.

Physical-device acceptance: NOT RUN. Required: Pixel keyboard and navigation modes, rotation, picker/camera cancel and return, capture consent/revocation/lock, repeated composer use, 15-minute capture memory/thermal test, and foreground/background observe-act-verify isolation.
