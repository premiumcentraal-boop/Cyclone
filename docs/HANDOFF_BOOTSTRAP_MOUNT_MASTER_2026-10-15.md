# Alpha 132 — bootstrap cross-user file visibility

Owner-supplied device evidence reports that the receiver writes its public key within approximately two seconds, while the owner's bootstrap shells cannot see the file through the inherited app mount namespace. The longer poll did not solve that visibility failure. This run makes exactly the requested production-line change: `ProcessBuilder("su", "--mount-master", "-c", command)` in `ProfileBootstrapRuntime.execute`.

Poll budgets, acknowledgement logic, wire contract, bootstrap sequence and all other runtime code remain unchanged. No device actions were performed; physical verification of this new artifact is **UNVERIFIED**.

## Changed files

- `apps/mobile/app/src/main/java/com/cyclone/mobile/runtime/workspaces/ProfileBootstrapRuntime.kt`: command prefix only.
- `scripts/ci/tests/test_profile_room_guard.py`: exact-prefix source guard, demonstrated failing before the change and passing after it.
- `apps/mobile/app/build.gradle.kts`, `release/version.toml`: `5.0.0-alpha.132.dev1`, code 286; other component versions unchanged.
- `docs/RELEASE_5.0.0-alpha.132.dev1.md` and this handoff.

## Tests and limitations

**2,652 mobile tests passed**, zero failures/errors/skips, including the existing **12 bootstrap acknowledgement tests**. **42 selected repository guards passed**, plus metadata/product and whitespace checks. Local Gradle test build succeeded in 1m16s. Existing `jvmTarget` deprecation warning remains; no warning in the changed runtime line.

The mobile harness uses JUnit and has neither Robolectric nor an injectable process-launch seam. No dependency or refactor was added. The source guard verifies the actual executor prefix; desktop tests do not validate Magisk mount namespace behavior. No platform verdict is claimed.

```powershell
$env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot'
$env:ANDROID_HOME='C:/Users/Agent/android-sdk'
& "$env:TEMP/cloak-build/gradle-8.11.1/bin/gradle.bat" -p apps/mobile --offline --no-daemon :app:testDebugUnitTest
uv run -- python scripts/ci/release_versions.py --check
uv run -- python scripts/ci/mobile_product_guard.py
uv run --with pytest -- python -m pytest scripts/ci/tests/test_mobile_workflows.py scripts/ci/tests/test_release_versions.py scripts/ci/tests/test_mobile_product_guard.py scripts/ci/tests/test_profile_lifecycle_guard.py scripts/ci/tests/test_profile_room_guard.py scripts/ci/tests/test_connector_guard.py -q
git diff --check
```

Log: `%TEMP%/cyclone-alpha132-tests.log`. Release target: [v5.0.0-alpha.132.dev1](https://github.com/premiumcentraal-boop/Cyclone/releases/tag/v5.0.0-alpha.132.dev1), using the existing signed combined publisher on `codex/profile-inventory-alpha128`. Publication requires Mobile CI and the standard packaging/signing gates. After normal update, retry profile switching and collect actual bootstrap evidence; no reboot or data reset was performed here.
