# Cyclone Mobile 2.9.4 — Full Device Gateway Launch

Cyclone 2.9.4 is the first release branch that combines all three gateway tracks from the 2.9.3 page-awareness base.

## Included

### Android bridge

- explicit **Cyclone PC Gateway (USB debugging)** launcher/settings surface;
- Android `localabstract:cyclone_gateway` server, off by default;
- random per-phone session token with rotation/disconnect;
- frozen NDJSON protocol;
- full Accessibility/raw observation export plus PageDebug evidence;
- App Graph and Adaptive Brain retrieval;
- existing `PhoneToolExecutor` action execution;
- canonical Follow Me/Routine Teaching lifecycle;
- privacy redaction and high-risk action policy.

### Windows PC Device Gateway

- deterministic ADB device selection and exact `com.cyclone.mobile` package check;
- automatic ADB localabstract forwarding;
- loopback bearer-authenticated HTTP API on `127.0.0.1:8765`;
- durable SQLite observations/actions/transitions;
- content-addressed screenshots;
- independent UiAutomator observer;
- optional allowlisted root telemetry;
- semantic/raw/UIA retrieval with provenance;
- action before/stabilize/after recording;
- complete debug bundles.

### Codex MCP

- local STDIO MCP talking only to the PC gateway;
- compact observation and progressive UI search;
- element inspection and screenshot tool;
- typed action surface;
- teaching/debug tools;
- mock and harmless live acceptance route;
- PowerShell preflight.

## Integration blockers fixed for 2.9.4

1. PC retrieval now reads Android `semanticControls`, `elementId`, and raw Accessibility payloads.
2. MCP `include_screenshot` / `mode` observe requests are now an explicit PC HTTP contract.
3. Android bridge and PC HTTP tokens are separate required settings rather than an implicit fallback.
4. Android `PhoneToolResult.execution.ok` is authoritative; transport success no longer becomes a false action success.
5. Post-action page capture now waits for deterministic semantic stabilization.
6. Exact Cyclone package detection replaces substring matching.

## Verification gates

The release workflow runs:

- PC Device Gateway mocked/unit tests;
- Codex phone MCP tests and mock acceptance;
- Android gateway/unit tests;
- full Android debug APK assembly;
- wheel builds for PC gateway and MCP;
- packaged 2.9.4 APK and PC bundle artifacts.

The final physical gate is a real rooted Pixel 8 over USB:

`Home -> Settings -> Apps -> Home`, repeated once, while checking transition history and optional root telemetry.

## Release artifacts

GitHub Actions publishes:

- `Cyclone-Mobile-2.9.4-Full-Gateway.apk`
- `Cyclone-2.9.4-Full-Gateway-PC` package contents
- `Cyclone-2.9.4-Full-Gateway-Bundle.zip`

See `docs/CODEX_PHONE_FIRST_RUN.md` for setup.
