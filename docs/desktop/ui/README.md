# Cyclone Desktop V1 UI shell

## Scope

`apps/pc-companion` owns presentation and frontend interaction state only. Backend/device authority stays outside this package.

The UI is organized around four routes:

- **Fleet / Phones** — primary monitoring wall for all detected devices.
- **Focused phone** — one-phone remote-control workspace.
- **Connections** — user-facing Codex, DeepSeek/MCP harness, and Generic MCP connection cards.
- **Settings & diagnostics** — calm user-facing companion health and privacy status.

## Visual direction

The shell uses a near-black monitoring environment, raised charcoal surfaces, restrained Cyclone purple accents, minimal controls, rounded corners, and short transitions. Phone displays occupy most of the fleet and focused workspaces. Developer diagnostics, tokens, raw ADB details, and console-style tables are kept out of primary views.

The sprint brief references four files under `visual-references/`, but that directory is absent from exact base `eb84f0578570cdac84aea8dd3612031aa0e8158f` and from the repository default branch at implementation time. The implementation therefore follows the explicitly stated visual characteristics from the sprint brief rather than claiming pixel-level comparison with unavailable images.

## Backend adapter boundary

All network/business calls are centralized behind `DesktopService`. UI components do not build endpoint URLs or duplicate HTTP behavior.

The real adapter currently assumes the Desktop V1 backend will provide:

```text
GET  /v1/devices
POST /v1/devices/{id}/pair/begin
POST /v1/devices/{id}/pair/confirm
POST /v1/devices/{id}/control
WS   /v1/devices/{id}/video?profile=thumbnail|focus
GET  /v1/devices/{id}/video/fallback?profile=thumbnail|focus
GET  /v1/connectors
POST /v1/connectors/{id}/action
GET  /v1/diagnostics/status
```

The exact base repository's existing Device Gateway still exposes the older single-device `/v1/device/status`, `/v1/action`, `/v1/observe`, and capability endpoints. Agent 3/backend integration should implement or adapt the frozen Desktop V1 contract rather than moving backend logic into this UI package.

### H.264 framing assumption

`WebCodecsH264Renderer` expects the websocket to send:

1. optional JSON config messages such as `{ "type": "config", "codec": "avc1...", "description_base64": "..." }`;
2. JSON frame metadata such as `{ "type": "frame", "key": true, "timestamp_us": 123 }`;
3. the encoded H.264 access unit as the following binary websocket message.

This framing knowledge is isolated in one decoder module so it can be aligned to the final backend contract without changing `LivePhoneView` or page code.

## Privacy invariants

- Pairing IDs stay internal and are never rendered.
- The four-letter code exists only while the pairing sheet is open.
- Keyboard capture retains only the selected device ID, never key history.
- Typed characters are sent directly as ephemeral control requests and are not added to application state.
- Clipboard contents are not rendered or retained after dispatch.
- One device receives keyboard input at a time; Escape immediately releases capture.

## Coordinate mapping

Phone pointer input is normalized after calculating the actual `object-fit: contain` content rectangle. Clicks in letterbox padding return `null` and are not sent. Coordinates are unrotated for 0/90/180/270-degree device rotation before reaching the backend.
