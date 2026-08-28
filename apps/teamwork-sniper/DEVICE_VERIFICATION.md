# Pixel 8 verification record

Target: `3B171FDJH0061G`, 1080x2400 @ 420dpi.

- separate APK install: PASS (`com.cyclone.teamworksniper`)
- Cyclone coexistence: PASS
- Teamwork remains installed: PASS (`tech.picnic.workapp`)
- notification access enabled: PASS
- accessibility access enabled: PASS (window retrieval + semantic gesture capability)
- Teamwork launch result: PASS
- accessibility hierarchy read: PASS, screenshot/OCR-free
- normalized open shifts: PASS, including native stable shift IDs
- exact rule evaluation: PASS
- real claim: PASS, explicitly authorized Sunday 2026-09-06 S2 16:55–19:30 only
- two-step Claim + Confirm flow: PASS
- post-claim evidence: exact stable ID absent from Open to take; matching row is Scheduled
- notification/manual launch → Teamwork root: 1523 ms in the acceptance run
- first comparison: 2036 ms
- full evaluation: 7170 ms
- claim interaction: 3639 ms

A real claim is permitted only when a genuinely open shift is present and an explicitly configured active rule targets it while both `enabled` and `armed` are true. Otherwise the correct result is `NOT EXECUTED`. The acceptance run initially reported `UNVERIFIED` because a fallback parser combined a Scheduled row with a neighboring Open row; the claim itself was independently proven by the stable Teamwork ID transition. Version 3.5.3.1 fixes that parser defect and adds a regression test.
