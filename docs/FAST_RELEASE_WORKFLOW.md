# Cyclone fast release workflow

Cyclone mobile releases use a small set of stable rules so UI polish does not require rebuilding the release process by hand.

## Release identity

- Android's canonical user-facing version is `BuildConfig.VERSION_NAME`.
- `CycloneRelease` is the UI helper for that value.
- Product screens must reference `CycloneRelease` rather than embedding release numbers in strings.
- The Android package remains `com.cyclone.mobile` and there must be exactly one launcher activity (`.MainActivity`).

## UI compatibility guard

Release CI checks behavior/surfaces instead of freezing the whole Compose source file. The protected product surfaces are:

- Home
- Teach
- AI
- Automations
- Brain
- profile badge -> Cyclone Settings
- Full PC + Codex Gateway inside AI
- one Android launcher

This allows small UI fixes without losing the original Cyclone product structure.

## Fast patching

Large legacy Compose files should be changed with small deterministic/idempotent transforms or focused edits, not copied/reconstructed wholesale. `scripts/release/apply-v295-ui-polish.py` is an example: it makes a minimal verified transformation and fails if its expected source markers have drifted.

## Build optimization

- PC gateway tests and Android compilation run in parallel.
- Android unit tests and APK assembly share one Gradle invocation/cache.
- concurrency cancellation ensures only the newest release-branch push keeps building.
- the release asset is clobbered only after tests pass.
- `BUILD_VERIFIED.json` records the exact source SHA, workflow run, APK SHA-256 and UI guard.

A GitHub Actions bot push does not recursively trigger another Actions workflow with the default `GITHUB_TOKEN`; release-building commits therefore need to originate from the release workflow itself or a normal authorized repository write.
