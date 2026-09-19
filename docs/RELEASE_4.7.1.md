# Cyclone Mobile 4.7.1

Android versionCode 131; built directly on published Cyclone Mobile 4.7.0.

This patch corrects the overlay-state and quick-controls regressions found during physical-device review.

## Overlay interaction

- Expanded chat drawer collapses first into a **fully usable minimized Ask Cyclone composer**.
- The minimized composer remains focusable: tap the text field and type immediately.
- A second downward drag collapses to the tiny Cyclone launcher.
- The launcher keeps the existing deliberate **three-tap** activation contract.
- Triple tap restores the minimized composer first; an upward drag or handle tap restores the full drawer.
- Composer drafts and active-run ownership survive both presentation-only collapse steps.
- Transition motion uses the shared spring drawer instead of swapping directly to the oversized pill introduced in 4.7.0.

## Quick model controls

- Model and intelligence are visible directly inside the Ask Cyclone composer.
- The quick panel contains only **Model** and **Intelligence**.
- **Phone autonomy** remains exclusively in Settings → Phone autonomy.

## Extras / attachments

- **Photos** and **Files** are separate actions again.
- Photos launches an image-only picker; Files retains text + image document attachment behavior.
- Camera, Share screen, Cross-app share and Model & intelligence remain available.
- In-app Ask Cyclone uses the same separate Photos / Files semantics.

## Preserved reliability

The 4.6.9/4.7.0 execution baseline is unchanged: PhoneToolExecutor remains mutation authority; GATE,
capture security, lock-screen suppression, handoff re-grounding, stale-target quarantine, signup
completion evidence and destination-host verification are untouched.

Physical Pixel 8 acceptance remains UNVERIFIED until this exact build is exercised on-device.
