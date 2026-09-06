# Cyclone One v0.2.1 Beta

Windows PC line for Cyclone Mobile 3.9.11 live-context semantics. Cyclone One remains the Companion + loopback Device Gateway + CycloneAgentMCP path that lets desktop agents drive the same `PhoneToolExecutor` on a USB-READY phone.

This beta does **not** rebuild the Android APK as the published artifact. The tree's mobile identity stays `3.9.8` / versionCode `62`. Use a live **Cyclone Mobile 3.9.10/3.9.11** phone for the sampled screenshot live-context and read-only share-session contract. Magisk multi-AI tiles and concurrent H.264 workspaces are out of scope.

## Installer

CI artifact / GitHub prerelease name:

```text
Cyclone-One-0.2.1-Beta-1-Setup.exe
```

Tag: `cyclone-one-v0.2.1-beta.1` (do not recreate `cyclone-one-v0.2.0-beta.1`).

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

The NSIS output is copied to `dist/cyclone-one-beta/Cyclone-One-0.2.1-Beta-1-Setup.exe` by `.github/workflows/cyclone-one-windows-beta.yml`.

## Why 0.2.1 (Pixel 8 + One 0.2.0-beta.1)

- Operator live view died on an 8s H.264 handshake (`STREAM_INIT_TIMEOUT` → `RETRY_BACKOFF` / `FRAME_RENDER_ERROR`) while the device video mode was already JPEG/SCREENSHOT and scrcpy reported LIVE with `lastFrameAvailable:false`.
- Mouse tap/swipe was gated on stream `LIVE`, so input went silent whenever the overlay showed.
- MCP observe/locate worked; mutate failed with `HUMAN_HAS_CONTROL` while Companion owned input and there was no yield path.
- Unscoped `phone_status` reported a false bridge-down because isolated ADB forwards require a device serial.
- Overlay `onError` masked specific connection codes as `FRAME_RENDER_ERROR`.
- `CycloneAgentMCP.exe --help` could `UnicodeEncodeError` on Windows cp1252.

## Fixes

1. **JPEG / adb-screenshot first** for physical focus live view. The producer no longer sends a provisional `video/avc` init. Focus runs at ~2 fps JPEG, matching Mobile 3.9.11 sampled live-context. Bad JPEG frames are soft-dropped while LIVE; reconnect happens only after consecutive failures.
2. **Handshake / stale-frame timeouts** sized for real screencap latency (20s init / first frame, 15s stale). Overlay shows the specific code (`STREAM_INIT_TIMEOUT`, `FRAME_DECODE_FAILED`, `WEBSOCKET_ERROR`, …). `FRAME_RENDER_ERROR` is only used when the failure is truly unknown.
3. **Operator input while the preview is degraded.** Mouse tap/swipe stay available in CONNECTING / RECONNECTING / STREAM_ERROR when the phone is READY. UNAVAILABLE shows `Input paused — stream down` instead of a silent no-op.
4. **Human ↔ AI handoff.** Opening focused live control takes HUMAN ownership. **Give control to AI** yields so MCP can `phone.open_app` / `phone.click` after observe. MCP may also pass `request_ai_control=true`. A locked/asleep phone is not stolen (`PHONE_LOCKED`).
5. **Session awareness seed.** `phone_status` / Companion list `default-foreground` (executable) vs any Android-reported background/share sessions. No Magisk clones, no N concurrent H.264 tiles.
6. **MCP reliability.** Unscoped `phone_status` auto-picks a single USB-READY device (or requires `device_id` when several are READY) and isolated forwards inherit that serial. `phone.tap`→`phone.click` and `open_app` package aliases remain. `--help` is cp1252-safe.

### Handoff (operator)

1. Open the phone in Cyclone One (mouse control; JPEG preview).
2. Observe with MCP (`phone_status` → `phone_locate`).
3. Click **Give control to AI**, or retry `phone_act` with `request_ai_control=true`.
4. Mutate (`phone.open_app` / `phone.click`). Re-locate after every page change.
5. Click **Take control** to reclaim the mouse as exclusive Companion input.

## Component versions

| Lane | Version |
|---|---|
| Cyclone One Windows | `0.2.1` (`windows_product_version` `0.2.1-beta`) |
| Cyclone Mobile (this tree / APK not rebuilt) | `3.9.8` / versionCode `62` |
| Spoken live-context contract | Mobile **3.9.11** JPEG/screenshot focus; share-screen remains read-only/non-executable |
| Device Gateway | `3.9.11` |
| CycloneAgentMCP / cyclone-phone-mcp | `3.9.11` |

Gateway bind remains loopback-only.

## Validation

- Gateway + MCP contract tests, including JPEG-first producer, ownership/handoff, unscoped status serial, and `--help` cp1252.
- Companion unit tests for timeout sizing, specific error-code surfacing, and operator input while reconnecting.
- Offline execution-gateway smoke (`scripts/pc-companion/smoke-android-execution-gateway.py`).
- Android unit/lint/assemble gate via `_mobile-build.yml` (execution-gateway gate; APK is not the published Windows artifact).
- Physical Pixel 8 live view + MCP handoff: **UNVERIFIED** in this metadata. Run Companion against a USB-READY 3.9.11 phone and `python scripts/pc-companion/smoke-android-execution-gateway.py --live` to record evidence.

## Invariants kept

- Phone remains authority for pay/send/delete/permission/authentication-sensitive actions.
- No generic ADB/shell/root tools for the model.
- No Magisk multi-instance / multi-AI orchestration.
- No second phone-control engine.
