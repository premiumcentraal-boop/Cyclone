# Cyclone Safe integration — Alpha 135

Version 5.0.0-alpha.135.dev1 / Android code 289; Glass 1.0.0-alpha.67.
Owner authorized publication on 2026-10-16: “Continue until released”.
Physical uninstall/reinstall verification remains **UNVERIFIED**; no phone actions taken.

## Outcome

Safe setup, per-user encrypted capture, root/SAF/PC ciphertext copies, startup
restore, fresh-key resealing, scheduled saves, Glass recovery, and guarded owner
deletion are connected. Local uninstall simulations cover recovery routes.
This preserves the last authenticated snapshot; it does not prevent uninstall.
Existing signer, package, SDK, permissions and agent approval boundaries remain.

## Changes

- Android runtime/safe: snapshot/session/key/store/recovery/manifest helpers,
  startup barrier, profile workers, scheduling, private bridge and Safe Activity.
- Settings: Safe entry and native-credential-gated Delete all Cyclone data.
- Gateway: private Safe commands, bounded PC ciphertext relay and archive store.
- Glass: Safe status, explicit returning-phone confirmation and HPKE/recovery flow.
- CI: inventory and owner-delete guards; release metadata and documentation.

Public internal entry points include SafeRuntime, SafeSession.save/recover,
SafeRecovery, SafeStartupGate and the four private safe bridge operations.
No generic model shell or model delete route is introduced.

## Local verification

- Android: **2,772 passed**, zero skipped/failures; **78 Safe tests** included.
- Gateway: **1,208 passed, 18 skipped** (45 Safe PC tests included).
- Glass: **360 passed**; TypeScript/Vite production build passed.
- Agent MCP: **93 passed**; phone MCP: **191 passed**.
- Ports: **92 passed, 2 skipped**; CI unittest suite: **276 passed**.
- Focused Safe/portable-settings/PC guards: **22 passed**.

Commands run from repository root unless noted:

```powershell
$env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot'
& "$env:TEMP/cloak-build/gradle-8.11.1/bin/gradle.bat" -p apps/mobile :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease --console=plain
uv run --with-editable 'apps/device-gateway[test]' --with-editable tools/codex-phone-mcp --with-editable tools/cyclone-ports-sdk --with-editable tools/cyclone-testbench -- python -X utf8 -m pytest apps/device-gateway/tests -q -o addopts=''
uv run --with-editable 'apps/device-gateway[test]' --with-editable tools/cyclone-agent-mcp --with-editable tools/cyclone-ports-sdk -- python -X utf8 -m pytest tools/cyclone-agent-mcp/tests -q
uv run --with-editable 'apps/device-gateway[test]' --with-editable tools/codex-phone-mcp --with-editable tools/cyclone-ports-sdk -- python -X utf8 -m unittest discover -s tools/codex-phone-mcp/tests -v
uv run --with-editable tools/cyclone-ports-sdk --with pytest -- python -X utf8 -m pytest tools/cyclone-ports-sdk/tests -q
uv run --with pytest -- python -X utf8 -m unittest discover -s scripts/ci/tests -v
uv run --with pytest -- python -X utf8 -m pytest scripts/ci/tests/test_cyclone_safe_guard.py scripts/ci/tests/test_portable_settings_guard.py scripts/ci/tests/test_pc_web_only.py -q
# apps/glass:
npm ci
npm test
npm run build
# repository root:
uv run -- python -X utf8 scripts/ci/release_versions.py --check
uv run -- python -X utf8 scripts/ci/mobile_product_guard.py
uv run -- python -X utf8 scripts/ci/repository_security_guard.py
uv run -- python -X utf8 scripts/ci/glass_guard.py
git diff --check
```

Local APKs (release signing belongs to protected CI):

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| apps/mobile/app/build/outputs/apk/debug/app-debug.apk | 92350632 | dab421845e116a02f2997b9c479986958523d0096851ceeb0b45435a2ec0bef7 |
| apps/mobile/app/build/outputs/apk/release/app-release-unsigned.apk | 65839457 | 3bf3e8f269339cdb2f45b5c01f688093901ba7c61fb69e22309517a664ec9840 |

## Limits and warnings

Physical update/uninstall/reinstall, per-user root access, SAF provider behavior,
scheduled delivery, native credential confirmation and real returning-phone
recovery are UNVERIFIED. Do not uninstall an older build before a verified Safe.
The pre-existing 132→134 installation failure is not diagnosed here.
Large snapshots buffer entry bodies (PC relay buffers ciphertext); heap scaling
is unverified. Missing/locked users stay pending. Folder-only mode cannot capture
other users without root. Root previews include counts; PC/folder routes do not
yet have equivalent pre-apply count previews. Legacy unconfigured full deletion
fails closed without a Safe inventory. See SAFE-IMPLEMENTATION.md for boundaries.

Existing Android deprecation/condition warnings, gateway test warnings and Vite's
over-500-kB chunk warning remain. No new Safe-source compiler warnings observed.
The normal release lane must pass Mobile CI, Windows install/smoke packaging,
Glass tests/build, signer/lineage/version checks before publishing a prerelease.
