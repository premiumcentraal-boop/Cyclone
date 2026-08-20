# Cyclone Mobile build and update guide

This file applies to every change under `apps/mobile/**`. Read the root `AGENTS.md` and
`docs/agent-system/FAST_RELEASE_PLAYBOOK.md` first.

## Before changing Android

1. Run `python scripts/agent/cyclone-context.py --markdown` from the repository root.
2. Identify the owning V3 service in `docs/agent-system/infrastructure-v3/OWNERSHIP.md`.
3. Preserve one package (`com.cyclone.mobile`), one launcher (`.MainActivity`), one policy
   authority, one Module Supervisor and the canonical `PhoneToolExecutor`.
4. Do not add permissions, navigation surfaces, dynamic code, shell/root or a second executor
   unless the task explicitly changes that architecture and documents an ADR.

## What to change for an update

| Change | Version action | Build action |
|---|---|---|
| Docs, PC gateway or MCP only; no Android contract change | none | no APK |
| Android source/resource change for review | normally none | let `Cyclone Mobile CI` test/build the SHA |
| APK will be handed to a user/device | increment `versionCode` | use the CI artifact once |
| New named release/channel | increment `versionCode`; update `versionName` | use CI, then release verification |

`versionName` and `versionCode` each have one source in `app/build.gradle.kts`. UI must read
`BuildConfig.VERSION_NAME` via `CycloneRelease`; never hardcode a visible release label.

## Local gate

```bash
python scripts/ci/mobile_metadata.py
./apps/mobile/gradlew -p apps/mobile :app:testDebugUnitTest :app:assembleDebug --stacktrace
```

Use JDK 17. The wrapper pins Gradle 8.9; do not replace it with an unpinned system Gradle. If an
Android SDK is unavailable, run the focused contract tests you can and report the full Android gate
as not run—never as passed.

## CI and APK truth

- `mobile-ci.yml` is the only normal push/PR APK lane. Do not add a version-copied workflow.
- Tests and assembly run in one invocation; one artifact contains APK, SHA-256, source SHA and
  version metadata.
- `mobile-release.yml` reuses that exact artifact and never recompiles. Publication is disabled.
- An APK is not “ready” until the successful run ID, artifact name, source SHA and APK checksum are
  known. CI is not physical-device verification.
