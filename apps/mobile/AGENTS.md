# Cyclone Mobile agent guide

This module is the Android product. Keep changes focused on the current runtime and its tests.

## Invariants

- Package: `com.cyclone.mobile`
- Launcher: `.MainActivity`
- Android 14+ (`minSdk 34`)
- `PhoneToolExecutor` is the canonical phone mutation path.
- Re-observe and verify after page-changing actions.
- Prefer semantic selectors and known routes before coordinates or vision.
- Keep approval boundaries for consequential actions.
- Never persist credentials, OTPs, payment data or raw typed secrets in Brain/run logs.
- Brain diagnostics may expose model-visible context, decisions, tool calls/results, verification and recovery events, but not hidden provider reasoning.

## Main product surfaces

`Home`, `Teach`, `Ask Cyclone`, `Routines`, `Brain`, `Settings`, plus the persistent Aurora activation overlay.

## Before committing

Run from `apps/mobile`:

```bash
./gradlew :app:testDebugUnitTest
```

For a release candidate also assemble the release artifact through the repository CI workflow. Physical-device behavior must be reported separately from unit/CI verification.
