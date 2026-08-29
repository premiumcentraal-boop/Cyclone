# Teamwork Sniper V1 release status

Release branch: `release/teamwork-sniper-v1`

Product version: **V1**  
Android package: `com.cyclone.teamworksniper`

## Pre-publication CI evidence

Candidate commit:

`edf8bda053ffdec68327d1ca342791f3ae49ec55`

GitHub Actions run:

`33223081840`

Result: **SUCCESS**

Verified gates:

- Teamwork Sniper metadata: PASS
- semantic/safety guard: PASS
- JVM unit tests: PASS
- installable Android release assembly: PASS
- release artifact packaging: PASS

Candidate artifact:

`Teamwork-Sniper-V1.apk`

Candidate SHA-256:

`731f5b37730eddb377db49893b2032f553e1acb0933232fb9c93378cd9d9277f`

Artifact source SHA:

`edf8bda053ffdec68327d1ca342791f3ae49ec55`

## V1 visual implementation

V1 implements the approved orange Teamwork Sniper product direction:

- Welcome screen
- Quick Setup permissions flow
- Choose Shifts onboarding
- All Set confirmation
- polished weekly Schedule
- hollow orange Snipe state
- filled orange Sniping state
- evidence-backed Claimed state
- recent evidence-backed Open now badge
- Activity timeline
- simplified Settings
- Shift Templates page
- Overlay Preview page
- Diagnostics page
- Teamwork launch shortcut
- V1 target app icon

The visual layer reuses the existing RuleStore and deterministic claim runtime. It does not create a second claim engine.

## Verification boundary

The CI evidence above proves source/build/package gates only.

**Physical V1 visual acceptance is pending user testing.**

A successful live Teamwork claim must still be verified on a physical device against the real Teamwork post-action state. CI success is not live-claim proof.
