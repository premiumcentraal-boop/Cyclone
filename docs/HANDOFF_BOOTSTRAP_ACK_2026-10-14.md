# Companion provisioning and bootstrap acknowledgement collection

Prepared for `v5.0.0-alpha.130.dev1`, Android code 284, on
`codex/profile-inventory-alpha128`. Provisioning lookup commit: `8992e0b7`.

## Source-confirmed cause and repair

`ProfileBootstrapService` wrote failure as only `{"ok":false}` and wrote replies
directly to the result file. `ProfileBootstrapRuntime` retried only 30 times with
200 ms sleeps and accepted only a matching nonce/user/true result. It swallowed
negative replies, missing files, partial JSON and read errors into the same
generic verification timeout. A receiver taking longer than the original six
seconds of sleep budget could also be falsely treated as unavailable.

This collector bug is independent of package installation and launcher
resolution. The receiving service already derives its Android user from its
process UID; it does not confuse the foreground Main user with the receiving
user. No change to that identity calculation was needed.

The receiver now writes the complete acknowledgement through a synced temporary
file and atomic rename. Existing nonce/user/ok fields and file locations remain;
optional stage/reasonCode describe only fixed local diagnostics. Negative replies
retain the receiving user and the attempt nonce once read from the transfer.
The collector accepts only that exact target/nonce, retries missing, transient,
partial and stale responses within a 30-second/150-attempt budget, and rejects
late successes. A matching negative reply fails immediately with its stage and
fixed reason. Key preparation receives a longer bounded polling window and can
surface a pre-transfer failure. The normal user retry starts the same preparation
sequence with a new nonce; no automatic restart, reboot, reset or clear-data
action is added.

Collection journal entries include only `ok`, `user`, `stage`, `reasonCode`, and
a fixed collection status. No nonce, transfer content, key, token or preferences
are included in the collection summary. Original successful three-field replies
remain accepted. A missing/failed/mismatched acknowledgement never proves ready.

The install/enable/start/unlock sequence, connector wire protocol, bindings and
identity/profile storage are unchanged. The only workflow changes add this
user-selected branch to the existing Mobile CI and V5 publish triggers; exact
commit verification, artifact checks, signer/lineage and environment gates are
unchanged. No dependencies or permissions were added.

## Files

- `apps/mobile/app/src/main/java/com/cyclone/mobile/runtime/workspaces/ProfileCornerstones.kt`
- `apps/mobile/app/src/test/java/com/cyclone/mobile/runtime/workspaces/ProfileCompanionProvisioningTest.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/runtime/workspaces/ProfileBootstrapAcknowledgement.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/runtime/workspaces/ProfileBootstrapRuntime.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/runtime/workspaces/ProfileBootstrapService.kt`
- `apps/mobile/app/src/test/java/com/cyclone/mobile/runtime/workspaces/ProfileBootstrapAcknowledgementTest.kt`
- `apps/mobile/app/build.gradle.kts`, `release/version.toml`
- `.github/workflows/mobile-ci.yml`, `.github/workflows/v5-publish.yml`
- `docs/RELEASE_5.0.0-alpha.130.dev1.md`, this handoff

## Local checks

From the Cyclone worktree, PowerShell:

```powershell
$env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot'
$env:ANDROID_HOME='C:/Users/Agent/android-sdk'
& "$env:TEMP/cloak-build/gradle-8.11.1/bin/gradle.bat" -p apps/mobile --no-daemon :app:testDebugUnitTest
& "$env:TEMP/cloak-build/gradle-8.11.1/bin/gradle.bat" -p apps/mobile --offline --no-daemon :app:testDebugUnitTest
uv run -- python scripts/ci/release_versions.py --check
uv run -- python scripts/ci/mobile_product_guard.py
uv run --with pytest -- python -m pytest scripts/ci/tests/test_mobile_workflows.py scripts/ci/tests/test_release_versions.py scripts/ci/tests/test_mobile_product_guard.py scripts/ci/tests/test_profile_lifecycle_guard.py scripts/ci/tests/test_profile_room_guard.py scripts/ci/tests/test_connector_guard.py -q
git diff --check
```

The existing provisioning suite passed **2,640 tests** before its commit. Final
Android tests passed **2,652**, zero failures/errors/skips, including four
provisioning tests and twelve acknowledgement tests. Guards passed **40 tests**;
metadata/product guards and whitespace checks passed. An initial test draft used
Robolectric, which this repository does not include; it was replaced by pure-JVM
file-cycle tests and Java's atomic move without adding a dependency. Final build
log: `$env:TEMP/cyclone-alpha130-final.log`. Existing unrelated Cyclone warnings
remain; no warning was reported in the changed acknowledgement/provisioning code.
Release CI still must build/lint/sign/check the exact final commit before publish.

## Device and release verification

`C:/Users/Agent/android-sdk/platform-tools/adb.exe devices` returned no devices.
No device was rebooted, installed, switched, unlocked or reset during preparation.
The exact earlier phone-side failure stage is **UNVERIFIED**; the source defects
above and slow-receiver retry regression are demonstrated by tests, not a new
device result. Do not claim the prior phone symptom has been physically resolved.

After both normal signed updates, use Cyclone's profile switch to open/unlock a
profile. Confirm the receiving-profile verification succeeds. In that profile's
Cloak, Health → Reconnect → Check companion copies verifies the own row's private
directory write access. Export its redacted diagnostics bundle and attach that
actual evidence to the release notes. No diagnostics bundle is fabricated in
the absence of a device. This is local bootstrap evidence, not a platform verdict.
