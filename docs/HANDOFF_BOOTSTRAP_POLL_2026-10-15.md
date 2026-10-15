# Alpha 131 — bootstrap public-key poll regression

The receiving Cyclone process is force-stopped and cold-started just before preparation waits for `profile-bootstrap-public.txt`. That wait retained `poll`'s default 30 attempts at 200 ms (about six seconds plus reads); the other repaired waits already use longer budgets. A receiver starting in 7–10 seconds can miss this window before acknowledgement collection even begins.

The production fix is exactly one call changed to `poll(tries = 150)`, plus a cold-start comment. No acknowledgement, bootstrap sequence, wire contract or binding changes. The source guard failed before the fix and passed afterwards.

## Changed files

- `apps/mobile/app/src/main/java/com/cyclone/mobile/runtime/workspaces/ProfileBootstrapRuntime.kt`: explicit public-key budget.
- `scripts/ci/tests/test_profile_room_guard.py`: guard on the preparation call after force-stop/service start.
- `apps/mobile/app/build.gradle.kts`, `release/version.toml`: `5.0.0-alpha.131.dev1`, Android code 285. Other component versions unchanged.
- `docs/RELEASE_5.0.0-alpha.131.dev1.md` and this handoff.

## Local checks

```powershell
$env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot'
$env:ANDROID_HOME='C:/Users/Agent/android-sdk'
& "$env:TEMP/cloak-build/gradle-8.11.1/bin/gradle.bat" -p apps/mobile --offline --no-daemon :app:testDebugUnitTest
uv run -- python scripts/ci/release_versions.py --check
uv run -- python scripts/ci/mobile_product_guard.py
uv run --with pytest -- python -m pytest scripts/ci/tests/test_mobile_workflows.py scripts/ci/tests/test_release_versions.py scripts/ci/tests/test_mobile_product_guard.py scripts/ci/tests/test_profile_lifecycle_guard.py scripts/ci/tests/test_profile_room_guard.py scripts/ci/tests/test_connector_guard.py -q
git diff --check
```

**2,652 mobile tests passed**, no failures/errors/skips, including all **12 bootstrap acknowledgement tests**. **41 guards passed**, including the new poll guard. Metadata/product/whitespace checks passed. Unit build successful in 1m14s. Existing Gradle Kotlin `jvmTarget` deprecation warning remains; no warning in the changed runtime code. Log: `%TEMP%/cyclone-alpha131-tests.log`.

Release target: [v5.0.0-alpha.131.dev1](https://github.com/premiumcentraal-boop/Cyclone/releases/tag/v5.0.0-alpha.131.dev1). Push this release commit on `codex/profile-inventory-alpha128` through the existing Mobile CI and combined signed publisher; release publication requires those gates to pass.

No device work, reboot, data clearing or unrelated changes performed. Physical-device verification is **UNVERIFIED**. After normal update, retry the profile switch and confirm that the receiver completes verification. No platform verdict is claimed.
