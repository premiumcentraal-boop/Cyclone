# Cyclone 3.5.3 + Teamwork Sniper 3.5.3.1

Cyclone 3.5.3 combines the preserved Cyclone product, the Windows PC Companion stack and the standalone Teamwork Sniper in one aligned source revision.

## Release identity

| Component | Identity |
| --- | --- |
| Cyclone Mobile | `com.cyclone.mobile` · 3.5.3 · versionCode 39 |
| Teamwork Sniper | `com.cyclone.teamworksniper` · 3.5.3.1 · versionCode 4 |
| Cyclone PC Companion | 3.5.3 |
| Device Gateway | 3.5.3 |
| Codex phone MCP | 3.5.3 |
| Generic Cyclone Agent MCP | 3.5.3 |

Preserved pre-sprint baseline: `9957eea21016476e8b004121d553e80ad0f7c136`.

Integrated handoff heads:

- Agent 1 Teamwork Sniper: `1ecd6b1c4a246c3939b24add7476a283451571f9`
- Agent 2 Teamwork contract/probe: `86759940b66b5cccd38f74f143cf60d582ade821`
- Agent 3 3.5.2 release lane: `b0a25abfedb88a2becee462465cab101881402e7`

## Teamwork Sniper architecture

The standalone APK is intentionally phone-first. It does not depend on Cyclone PC or an AI provider.

The default decision chain is:

```text
Teamwork notification
  -> open Teamwork
  -> semantic UI-map navigation if needed
  -> accessibility hierarchy scan
  -> normalize Open to take shifts
  -> deterministic user-rule match
  -> Enabled + Armed safety gate
  -> optional OpenRouter priority advice
  -> fresh semantic target re-observation
  -> semantic action or live node-bounds gesture
  -> exact Claim confirmation
  -> post-action semantic verification
```

The optional OpenRouter stage is non-authoritative. It can only reorder already-safe `RuleMatch` candidates. API failure, timeout, missing key, disabled AI, a single candidate or an invalid model response all preserve deterministic local behavior.

No production Teamwork read/claim path may rely on screenshots, OCR, image analysis, MediaProjection, screencap or hardcoded coordinates.

## Semantic UI mapping

The Sniper may learn a successful Teamwork navigation resource ID and/or semantic label locally. On future triggers it tries that hint first, then safe semantic labels/resource IDs such as Calendar, Schedule, Shifts, Diensten, Rooster or Planning.

Every shift-row/claim path remains observation-scoped. The runtime re-observes before claiming and fails closed on ambiguity.

## Release artifacts expected from one source SHA

- `Cyclone-3.5.3.apk`
- `Teamwork-Sniper-3.5.3.1.apk`
- `Cyclone-PC-Companion-3.5.3-Setup.exe`
- checksums and provenance metadata for each build lane

Teamwork Sniper 3.5.3.1 was physically exercised on the Pixel 8 target. The exact authorized Sunday 2026-09-06 S2 16:55–19:30 shift was claimed through Teamwork's two-step Claim/Confirm flow. Post-action semantic evidence showed the stable shift ID no longer Open to take and the matching row Scheduled.

## Acceptance matrix

| Requirement | Current release gate |
| --- | --- |
| Cyclone Mobile compile/tests | CI required |
| Teamwork Sniper compile/tests | CI required |
| Screenshot/coordinate guard | CI required |
| PC Companion + Gateway + MCP tests | CI required |
| Windows installer | CI required |
| Pixel 8 install | PASS |
| Teamwork live hierarchy | PASS |
| Notification/manual trigger -> read -> compare | PASS |
| Real claim | PASS for the one explicitly authorized shift |

Pixel target: `3B171FDJH0061G`.

No other shift was claimed during acceptance. The production path remains local, deterministic, screenshot/OCR-free and fails closed on ambiguity or changed safety state.
