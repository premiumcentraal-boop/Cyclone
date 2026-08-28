# Teamwork calendar overlay verification

Version: `3.5.3.2` (`versionCode 5`)

Target: Pixel 8 `3B171FDJH0061G`, 1080x2400 at 420 dpi.

## Result

**WORKING** on the live Teamwork calendar without screenshots, OCR, image analysis, or AI.

- The overlay appears only on the recognized `tech.picnic.workapp` calendar surface.
- Four live `No shift` day regions in Week 35 produced four independently aligned accessibility-overlay windows.
- Each day renders a transparent vertical stack of full-width shift bars with only a sniper sight icon, shift time/state, and code.
- There is no white panel, header, note, calendar icon, or plus button.
- Bars scroll vertically inside the real day region and do not cover assigned or open Teamwork rows.
- A past-day M1 row was selected and persisted as `overlay-target:2026-08-25:M1`, then unselected and removed.
- The existing exact S2 rule for 2026-09-06 remained unchanged.
- Pressing Home removed all overlay windows; returning to Teamwork restored them.

## Live semantic evidence

- Calendar identity: `agenda-list`; week selector text `Week 35`.
- Day anchors: semantic `No shift` nodes within full-width day groups.
- Existing shift rows: `shift-item`, `shift-text`, `shift-status`.
- Observed states: `Scheduled` and `Open to take`.
- Confirmed templates: M1 Friday 07:30–10:05; M1 weekend 08:00–10:35; S1 14:10–16:45; S2 16:55–19:30; S3 19:40–22:15.
- M2 remains provisional because no live M2 time was observed; the overlay does not invent or display a time for it.

This pass validates overlay placement, state persistence, safety, and lifecycle. The existing 3.5.3.1 physical record remains the evidence for the separately preserved two-step Claim/Confirm engine.
