# Cyclone PC Device Gateway (2.9.3)

This service is deterministic PC-side infrastructure for a locally attached Android phone. It deliberately contains no LLM or autonomous loop.

## Security boundary

- HTTP binds only to `127.0.0.1:8765`.
- `CYCLONE_DEVICE_GATEWAY_TOKEN` is mandatory and every `/v1/*` route requires a bearer token.
- Optional `CYCLONE_DEVICE_SERIAL` pins one ADB serial. With multiple authorized devices and no configured serial the gateway refuses to choose.
- No HTTP route exposes ADB shell, `su`, PowerShell, or an arbitrary command primitive.
- Root support is optional and limited to fixed telemetry operations in `RootProvider`.
- Action audit is JSONL. `phone.type` values, passwords, auth tokens, and API-key-shaped fields are redacted.

## First run (Windows PowerShell)

```powershell
cd apps/device-gateway
py -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -e ".[test,uiautomator2]"
$env:CYCLONE_DEVICE_GATEWAY_TOKEN = "replace-with-a-long-random-token"
$env:CYCLONE_DEVICE_SERIAL = "<adb-serial>"
cyclone-device-gateway
```

The server listens on `http://127.0.0.1:8765`. It forwards `tcp:8766` to `localabstract:cyclone_gateway` and speaks newline-delimited UTF-8 JSON using the frozen Android bridge operations.

If Agent 2 uses a distinct Android session token, also set `CYCLONE_ANDROID_BRIDGE_TOKEN`. Otherwise the gateway token is used as the bridge session token.

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

`POST /v1/action` accepts only `phone.observe`, `phone.find`, `phone.click`, `phone.long_press`, `phone.swipe`, `phone.scroll`, `phone.type`, `phone.back`, `phone.home`, `phone.open_app`, and `phone.wait_for`.

## Local acquisition and retrieval

Every observation stores the complete Android bridge semantic payload plus PageDebug and `debug.snapshot`, independent UiAutomator XML/nodes, and a content-addressed screenshot reference in SQLite under `.runtime/device-gateway/`. Compact retrieval ranks controls later; raw local state is not truncated to model context.

Evidence remains provenance-separated. Cyclone accessibility and UiAutomator observations are never silently merged; `/v1/debug/compare-sources` reports them side-by-side.

Every routed action records a before observation, typed action, bridge result, after observation, before/after PageKey, latency, backend, verification signal, and error class. Raw root `getevent` traces are stored as local files and the stop operation returns metadata/hash only.

## Agent 2 integration requirement

Agent 2 must provide the Android-local socket at `localabstract:cyclone_gateway` and implement the frozen operations exactly. In particular `action.execute` must accept `{tool, params, goal, source}` and should return only after its Android-side stabilization/verification point is reached. The PC gateway then captures the after-state.

## Agent 3 integration requirement

Agent 3's MCP surface should call only this HTTP API, pass the bearer token, prefer compact page context/search for reasoning, and request full context/debug bundles only when necessary. It should never bypass the gateway with direct ADB or root commands.

## Known limitations

- UiAutomator2 is preferred when installed; otherwise the gateway falls back to the fixed ADB `uiautomator dump` path.
- Raw root input traces are retained locally but are not yet compiled into gesture-level teaching evidence.
- Cyclone package detection is name-based (`cyclone` substring among third-party packages) until Agent 2 freezes the exact application id.
- The debug bundle stores root telemetry in one JSON file rather than splitting each dumpsys/logcat stream into separate files.
