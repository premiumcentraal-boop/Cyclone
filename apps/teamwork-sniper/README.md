# Teamwork Sniper V1

Teamwork Sniper V1 is the first product-style release of the standalone companion for `tech.picnic.workapp`.

- package: `com.cyclone.teamworksniper`
- versionName: `V1`
- versionCode: `7`
- PC connection: not required
- AI model: optional
- primary input: Teamwork notifications + Android accessibility hierarchy
- primary actions: bounded semantic Teamwork actions
- visual direction: bright-orange, schedule-first, lightweight native Android UI

## V1 product experience

V1 replaces the previous technical-looking planner with the approved Teamwork Sniper visual system:

1. Welcome
2. Quick Setup
3. Choose shifts
4. All Set
5. Main weekly schedule
6. Activity timeline
7. Settings
8. Shift Templates
9. Overlay Preview
10. Diagnostics

The primary schedule uses the product state language from the approved design:

- hollow orange = available to select
- filled orange = selected for sniping
- green claimed state = a real claim was post-action verified
- green `Open now` badge = a recent real semantic Teamwork observation reported the shift open

A selected shift is not the same as an armed claim and is not the same as a verified successful claim.

## Schedule behavior

Tapping a shift creates or removes the exact date/code target already consumed by the deterministic claim engine.

The UI does not fabricate Teamwork data:

- live-confirmed template times may be displayed as expected Teamwork times;
- provisional/unknown times remain `Time to be confirmed`;
- `Claimed` is shown only when activity evidence contains `TARGET_NO_LONGER_OPEN`;
- recent open badges come from recent semantic Teamwork observations.

## Overlay

The old accessibility overlay remains available as an experimental option under:

`Settings → Overlay mode`

The native Teamwork Sniper schedule is the default and reliable product surface. The Overlay Preview page shows the intended UI language without pretending preview data is live Teamwork state.

## Deterministic-first runtime

Teamwork Sniper still works without a PC and without AI.

The runtime continues to:

1. observe the current semantic Teamwork hierarchy;
2. normalize Teamwork shift candidates;
3. compare only against persisted user targets;
4. require Sniper enabled + Armed mode before claim actions;
5. re-observe and resolve the fresh semantic target;
6. act;
7. verify the Teamwork postcondition.

The UI overhaul does not create a second claim engine.

## Optional OpenRouter

OpenRouter remains optional and is not an action authority. It cannot arm Sniper, create targets, expand user rules, resolve ambiguous Teamwork UI, or click Teamwork directly.

## Build and validation

From the repository root:

```bash
python scripts/ci/teamwork_sniper_metadata.py --require-app
python scripts/ci/teamwork_sniper_guard.py --require-app
./apps/mobile/gradlew -p apps/teamwork-sniper :app:testDebugUnitTest :app:assembleRelease --stacktrace
```

The V1 GitHub workflow builds and publishes the installable APK from branch:

`release/teamwork-sniper-v1`

Physical-device Teamwork behavior remains a separate acceptance gate. CI compilation is not proof of successful live Teamwork claiming.
