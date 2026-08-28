# Native target calendar verification

Version: `3.5.3.3` (`versionCode 6`)

Target: Pixel 8 `3B171FDJH0061G`.

## Result

**WORKING** — Teamwork Sniper opens directly to its native weekly target calendar. It uses Teamwork-inspired day and shift rows with Sniper identity, but does not attempt a pixel-for-pixel copy of Teamwork or present unsynced data as live Teamwork data.

- The calendar renders a week of M1 through S3 target rows.
- Tapping a row creates the exact date/code target consumed by the deterministic claim engine.
- Tapping again removes that exact target.
- A physical past-date M1 selection was created, persisted, removed, and then confirmed absent.
- The existing exact Sunday 2026-09-06 S2 rule remained intact.
- The legacy Teamwork overlay is not rendered on Teamwork by default (zero overlay windows observed).
- The legacy setting is available only under Settings and starts disabled.
- No screenshots, OCR, image analysis, or AI were used for schedule selection.

This verification intentionally excludes Scheduled/Open-to-take synchronization. The native calendar presently represents user targets only.
