# Cyclone PC Device Gateway (2.9.4)

The PC Device Gateway is deterministic Windows-side infrastructure for a USB-connected Android device. It contains no LLM and no autonomous loop.

## Security boundary

- HTTP binds only to `127.0.0.1:8765`.
- `CYCLONE_DEVICE_GATEWAY_TOKEN` is mandatory for every `/v1/*` request.
- `CYCLONE_ANDROID_BRIDGE_TOKEN` is separately mandatory and must match the random session token shown by Cyclone on the phone.
- Optional `CYCLONE_DEVICE_SERIAL` pins one ADB serial. Multiple authorized devices without an explicit serial are rejected.
- The exact Android package checked is `com.cyclone.mobile`.
- No HTTP route exposes ADB shell, `su`, PowerShell, or an arbitrary command primitive.
- Root support is optional and restricted to allowlisted telemetry.
- `phone.type` values, passwords, auth tokens, OTPs, and API-key-shaped values are redacted from audit/state output.

## Windows first run

```powershell
cd apps\device-gateway
py -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -e ".[test,uiautomator2]"

$env:CYCLONE_ANDROID_BRIDGE_TOKEN = "<token shown by Cyclone PC Gateway on phone>"
$env:CYCLONE_DEVICE_GATEWAY_TOKEN = "<separate strong local token>"
$env:CYCLONE_DEVICE_SERIAL = "<adb serial>"

cyclone-device-gateway
```

The gateway forwards `tcp:8766` to `localabstract:cyclone_gateway` on the selected phone and speaks the frozen newline-delimited Android bridge protocol.

## HTTP API

- `GET /v1/device/status`
- `POST /v1/observe`
- `GET /v1/ui/search?q=...`
- `GET /v1/ui/element/{id}`
- `GET /v1/page/current?mode=compact|full&goal=...`
- `GET /v1/page/history`
- `POST /v1/action`
- `POST /v1/debug/bundle`
- `POST /v1/teach/start`
- `GET /v1/teach/status`
- `POST /v1/teach/stop`
- `GET /v1/debug/compare-sources?q=...`

`POST /v1/observe` accepts the MCP contract:

```json
{
  "include_screenshot": false,
  "uiautomator": true,
  "mode": "compact",
  "goal": "Open Apps"
}
```

`mode=compact` returns a ranked reasoning view. `mode=full` returns the locally stored observation including full Android semantic/raw payload references. Acquisition is not capped to model context.

## 2.9.4 integration behavior

Android 2.9.4 exports `semanticControls` and observation-scoped `elementId` values. The PC retrieval layer understands those names directly and also indexes sanitized raw Accessibility nodes. UiAutomator remains a separate witness and is never silently merged with Cyclone evidence.

For each action the gateway:

1. captures a before observation;
2. routes the typed tool through the authenticated Android bridge;
3. treats Android `execution.ok` as authoritative;
4. waits for two matching semantic page/fingerprint samples or a bounded timeout;
5. captures the final after observation;
6. records transition, verification, latency, backend, and Android error class.

A successful socket round-trip is therefore not enough to record a successful phone action.

## Rooted Pixel 8 telemetry

Root is optional for control. When `adb shell su -c id` returns uid 0, the strict RootProvider additionally supports only:

- input device inventory;
- local-only timestamped `getevent` trace start/stop;
- dumpsys window;
- dumpsys input;
- filtered logcat;
- process info.

There is no generic root shell API.

## Debug bundle

A debug bundle includes:

- Cyclone semantic/raw observation;
- PageDebug funnel/diagnosis;
- Android debug snapshot;
- independent UiAutomator hierarchy;
- content-addressed screenshot copy;
- package/activity/PageKey;
- allowlisted root telemetry when available;
- recent actions and page transitions;
- manifest with expected page/goal metadata.

## Known limitations

- A physical Pixel 8 USB smoke test is still required on the target PC after CI; repository CI cannot emulate your rooted handset or its exact OEM/app state.
- UiAutomator2 is optional and falls back to fixed `adb shell uiautomator dump`.
- Raw root `getevent` traces remain local evidence; automatic gesture compilation is not yet part of 2.9.4.
- Consequential/authentication controls remain blocked by Cyclone's existing safety policy; the PC gateway does not bypass human approval paths.
