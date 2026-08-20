# Cyclone 2.9.3 Android Device Gateway Provider

This document describes the **Android provider only** for Cyclone's PC Device Gateway. The PC gateway and Codex/MCP layers are separate components owned by other agents.

## Security and transport

Cyclone does **not** open an HTTP server or a LAN port. When the user explicitly enables **Cyclone PC Gateway (USB debugging)**, the app listens only on Android's local abstract Unix socket:

```text
cyclone_gateway
```

A trusted USB-connected PC exposes that socket locally with ADB:

```powershell
adb forward tcp:8766 localabstract:cyclone_gateway
```

The PC then connects to `127.0.0.1:8766`. ADB performs the forwarding; the APK itself never binds TCP port 8766.

The gateway is **OFF by default**. Enabling it creates a random 256-bit session token. Rotating the token immediately disconnects current clients. Disabling the gateway closes the listener and removes the token. The token is shown only in the explicit user settings surface and is never included in gateway status, debug snapshots, command audit logs, or action logs.

The launcher contains a second Cyclone entry named **Cyclone PC Gateway** so the developer surface is easy to find without changing the production 2.9.3 Compose navigation. It is still an Activity in the same `com.cyclone.mobile` APK and uses the same runtimes/stores.

## Wire protocol

Transport is newline-delimited UTF-8 JSON. One request produces one response.

Request:

```json
{"id":"uuid","op":"observe.semantic","args":{},"auth":"session-token"}
```

Response:

```json
{"id":"uuid","ok":true,"result":{},"error":null}
```

Errors preserve the request ID where possible and use:

```json
{"id":"uuid","ok":false,"result":null,"error":{"code":"AUTH_REJECTED","message":"...","details":null}}
```

Input lines are bounded to 1 MiB to prevent a forwarded client from growing the phone process without limit.

## Frozen operations

The Android provider implements exactly these protocol operations:

| Operation | Android source of truth |
| --- | --- |
| `bridge.status` | `DeviceState`, `FollowMeLearnerRuntime`, gateway runtime |
| `observe.semantic` | `CycloneAccessibilityService.observe` + `PageAwarenessRuntime` |
| `observe.page_debug` | `PageDebugSandboxV293` + deterministic `PageDebugDiagnosisV293` evidence |
| `ui.search` | deterministic local search over the latest complete semantic/raw observation |
| `ui.element` | latest observation-local element map |
| `app_graph.get` | `AppLearnerRuntime.graph/retrieval` |
| `brain.recall` | `AdaptiveBrainRuntime.recall` |
| `action.execute` | `PhoneToolExecutor` |
| `teach.start` | `FollowMeLearnerRuntime.start` |
| `teach.status` | Follow Me + `RoutineTeachingRuntime` + gesture evidence |
| `teach.stop` | canonical Follow Me/Routine Teaching finish path |
| `debug.snapshot` | sanitized gateway, observation, PageDebug, teaching and command-audit metadata |

Unknown operations are rejected. Every operation, including `bridge.status`, requires the current session token.

## Semantic observation

`observe.semantic` runs the production Accessibility observation path and then the production Page Awareness path. It exports:

- observation ID and explicit observation-local element-ID scope;
- timestamp, foreground package/activity, display size and orientation;
- Accessibility fingerprint and semantic PageKey/fingerprint;
- full `PageContext` controls retained by Page Awareness, up to the existing **80-control store limit**;
- enriched semantic controls with Android actions, resource IDs, content descriptions, bounds and state flags when a matching raw node is available;
- relevant Accessibility windows;
- a separately named, sanitized raw Accessibility snapshot for PC-side acquisition.

The gateway does **not** reapply the Page Agent's 36-control cap. It also does not change Page Awareness's existing 450-node scan or 80-control store behavior.

Element IDs have forms similar to:

```text
semantic:<observation-id>:<control-key>
raw:<observation-id>:<raw-node-id>
```

They are intentionally observation-scoped. `ui.element` rejects an ID from an older observation with `STALE_ELEMENT`.

## PageDebug export

`observe.page_debug` captures through the existing 2.9.3 sandbox and exports deterministic evidence for the current funnel:

```text
raw Accessibility collection: 2500 max
              ↓
semantic node scan:             450 max
              ↓
semantic control store:          80 max
              ↓
production Page Agent payload:   36 max
```

The response includes raw/visible/interactive/unlabelled counts, semantic and production-agent control counts, deterministic diagnosis, sanitized raw Accessibility, full semantic page, production agent payload and full-controls comparison.

The gateway intentionally does **not** export hidden chain-of-thought or provider-private reasoning. It also omits the sandbox's system-prompt field and screenshot filesystem path; only harmless screenshot metadata is exposed.

Diagnosis stages remain the existing 2.9.3 values:

- `ACCESSIBILITY_PERCEPTION`
- `SEMANTICIZATION_LOSS`
- `AGENT_CONTEXT_TRUNCATION`
- `AGENT_REASONING_OR_MEMORY`

## UI search

`ui.search` does not call an LLM. It searches the latest complete semantic/raw observation using deterministic normalized matching. Exact matches rank above substring matches, token overlap ranks below those, and semantic candidates receive a small deterministic tie-break advantage.

Example:

```json
{"id":"2","op":"ui.search","args":{"query":"Continue","limit":30},"auth":"..."}
```

Candidates expose element ID, label, semantic name, role, resource ID, content description, bounds, Android actions, source and a deterministic relevance score.

## App Graph and Adaptive Brain

`app_graph.get` uses `AppLearnerRuntime` rather than creating a second graph. If a PageKey is supplied, the adapter maps it to a learned screen using the screen's semantic fingerprint and passes that screen into the existing retrieval path. The response includes query/relevance metadata and graph counts, but not a dump of the lifelong graph database.

`brain.recall` uses `AdaptiveBrainRuntime.recall` with goal/package/PageKey and the latest Accessibility fingerprint when available. The result is passed through the gateway privacy boundary before export.

## Action execution

`action.execute` accepts only the first-run phone tools:

```text
phone.observe
phone.find
phone.click
phone.long_press
phone.swipe
phone.scroll
phone.type
phone.back
phone.home
phone.open_app
phone.wait_for
```

The adapter calls the existing `PhoneToolExecutor`; it does not implement ADB taps, a second Accessibility engine, or direct root execution.

The source label must be `PC_CODEX`. This label does not bypass `DeviceState` controller ownership, fresh-observation requirements, selector resolution, semantic Android actions or verification in the existing executor.

As an additional boundary, the USB gateway refuses high-risk/authentication/unknown click/long-press targets instead of inventing a remote approval bypass. Sensitive `phone.type` selectors (password, OTP, PIN, token, etc.) are rejected. Successful/meaningful attempted actions are recorded through the existing `AdaptiveBrainRuntime.recordToolOutcome(..., source="PC_CODEX")`; the existing Brain sanitizer deliberately omits typed values.

A human-demonstrated long press still benefits from the production Accessibility service's optimization: `ACTION_LONG_CLICK` is preferred when Android exposes it, with a timed hold only as fallback.

## Teaching

Remote teaching uses the existing Follow Me and Routine Teaching history. No second teaching database exists.

`teach.start` starts `FollowMeLearnerRuntime` if it is not already active. `teach.status` returns:

- session ID;
- active/paused state;
- current package and most recent PageKey;
- page, action and gesture counts;
- apps and reusable paths seen;
- canonical session status.

`teach.stop` completes through the canonical Follow Me/Routine Teaching finish path and returns the stopped session/report identifier and summary metadata.

Typed text is still ignored by Follow Me, matching existing Cyclone privacy behavior.

## Privacy boundary

The Android provider never exports or logs:

- the PC gateway session token;
- password/passcode/PIN field contents;
- OTP / verification-code contents;
- API/provider keys or token fields;
- the value supplied to `phone.type`.

For editable Accessibility nodes, text is redacted before PC export while structural metadata remains available. Sensitive node descriptions are also redacted. `debug.snapshot` contains command IDs/tools/outcomes only, not action parameters.

## Rooted Pixel 8 scope

Root is not required by the Android provider. `bridge.status` may report a heuristic `rootAppearsAvailable` flag, but the protocol deliberately has no `su.execute`, `root.shell`, `runCommand`, or equivalent arbitrary shell operation.

Root-only telemetry such as `getevent`, `dumpsys` and `logcat` belongs on the PC/Agent 1 side over ADB.

## Lifecycle

`GatewayInitProvider` is an unexported process initializer. It starts nothing while the gateway is disabled. If the user enabled the gateway previously and Android later recreates the Cyclone process for MainActivity, Accessibility or another component, it recreates the localabstract listener.

USB/ADB disconnects are treated as normal socket disconnects. The listener remains available for a later `adb forward`/reconnect. Disable closes clients and listener; rotate/disconnect closes clients without opening any network socket.

## Agent 1 integration

1. Ensure the Pixel 8 has Cyclone 2.9.3 installed and Cyclone Accessibility enabled.
2. On the phone open **Cyclone PC Gateway** and explicitly enable **PC Gateway (USB debugging)**.
3. Read the session token from the phone UI.
4. On Windows verify the phone is visible with `adb devices`.
5. Run `adb forward tcp:8766 localabstract:cyclone_gateway`.
6. Connect Agent 1's PC gateway client to `127.0.0.1:8766`.
7. Send `bridge.status` with the token before acquisition.
8. Use `observe.semantic` as the full acquisition interface. Store the full returned state on the PC; do not assume the 36-control Page Agent payload is complete.
9. Use `observe.page_debug` when investigating a lost target.
10. Send only the typed `action.execute` phone tools listed above. Root telemetry remains a separate PC-side ADB concern.

For the harmless acceptance route, Agent 1 should drive:

```text
Pixel Launcher -> Settings -> Apps -> Home
```

and repeat it. The second pass should be able to query the App Graph/Brain evidence created by Cyclone's existing execution/learning paths.

## Agent 3 / MCP integration

Agent 3 should talk only to Agent 1's PC Device Gateway abstraction. It should not connect directly to the APK socket or implement another phone controller.

The MCP layer can surface the frozen operations/typed tools from Agent 1, preserving:

- `PC_CODEX` source labeling;
- observation-local element-ID scope;
- PageDebug's deterministic diagnosis rather than chain-of-thought;
- no arbitrary root shell;
- no raw gateway token in model-visible logs.

## Unit-test coverage

`GatewayBridgeV293Test` covers protocol parsing, unknown-op validation, auth comparison, Accessibility sanitization, `phone.type` redaction, deterministic UI search/stale IDs, action-tool mapping and risk policy, teaching mapping, PageDebug funnel export, App Graph PageKey matching, Brain credential privacy, and bounded socket-line lifecycle behavior without requiring a physical phone.
