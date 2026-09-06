# Cyclone One v0.2 Beta

Windows PC line for Cyclone Mobile 3.9.8. Cyclone One is the Companion + loopback Device Gateway + CycloneAgentMCP path that lets desktop agents drive the same `PhoneToolExecutor` on a USB-READY phone.

This beta **includes the Android execution-gateway gate**. v0.1 Beta 1 deferred that gate; v0.2 does not.

## Installer

CI artifact / GitHub prerelease name:

```text
Cyclone-One-0.2.0-Beta-1-Setup.exe
```

The installer bundles `CyclonePCRuntime.exe` (loopback Device Gateway) and `CycloneAgentMCP.exe`. Build locally with:

```powershell
python -m pip install -e 'apps/device-gateway[test]' -e 'tools/cyclone-agent-mcp[test]' -e tools/codex-phone-mcp
python scripts/pc-companion/smoke-android-execution-gateway.py
./scripts/pc-companion/build-sidecars.ps1
cd apps/pc-companion
npm ci --no-audit --no-fund
npm test
npm run tauri build -- --bundles nsis
```

The NSIS output is copied to `dist/cyclone-one-beta/Cyclone-One-0.2.0-Beta-1-Setup.exe` by `.github/workflows/cyclone-one-windows-beta.yml`.

## Phone-control fixes (2026-09-06 Pixel 8 session)

- `phone.tap` is an alias of `phone.click`. Unsupported names list the real allowlist.
- `phone.open_app` takes `params.package` (example `com.android.vending`). `packageName` is accepted as an alias.
- `phone.type` requires a current observation-scoped `elementId`. Sequence: `phone_locate` → click to focus → type. `user_authorized=true` is MCP intent only.
- **Soft-success:** if `pageChanged` or expected `afterPackage` matches, `ok` follows the UI effect. `PROTOCOL_MISMATCH` is a warning/diagnostic, not a stop.
- Play Store listings: `phone.launch_intent` with `uri=market://details?id=<package>` (or the Play Store https details URL). Then `phone.wait_for` `package_equals` instead of a blind sleep.
- `phone_locate` ranks controls; it does not navigate.
- MCP uses the **Cyclone One Companion loopback gateway** (USB READY). Do not point agents at a standalone classic `:8765` process. Keep Cyclone One open.

`cyclone-agent-mcp --help`, `verify`, and `copy-config` each print one example for click/open_app/type. Generated MCP configs still contain no gateway tokens.

## Component versions

| Lane | Version |
|---|---|
| Cyclone One Windows | `0.2.0` (`windows_product_version` `0.2.0-beta`) |
| Cyclone Mobile | `3.9.8` (existing APK; not rebuilt as the primary deliverable) |
| Device Gateway | `3.9.8` |
| CycloneAgentMCP / cyclone-phone-mcp | `3.9.8` |

Gateway bind remains loopback-only.

## Validation

- Gateway + MCP contract tests
- Offline execution-gateway smoke (`scripts/pc-companion/smoke-android-execution-gateway.py`)
- Android unit/lint/assemble gate via `_mobile-build.yml` (execution-gateway gate; APK is not the published Windows artifact)
- Physical Pixel 8 live drive: **UNVERIFIED** in this metadata. Run `python scripts/pc-companion/smoke-android-execution-gateway.py --live` against a USB-READY Companion phone to record evidence.

## Invariants kept

- Phone remains authority for pay/send/delete/permission/authentication-sensitive actions.
- No generic ADB/shell/root tools for the model.
- No second phone-control engine.
