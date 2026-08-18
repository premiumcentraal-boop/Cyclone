# Mobilerun Portal backend for Cyclone Mobile

Status: integration branch `feature/mobile-mobilerun-backend`

## Important licensing boundary

Current upstream `droidrun/mobilerun-portal` is licensed under **GNU AGPL-3.0-or-later**, not MIT.

Cyclone therefore does **not** copy Mobilerun Portal source into the Cyclone APK in this integration. Instead, Portal remains an independently installed Android companion and Cyclone communicates with its documented authenticated HTTP API through a clean compatibility adapter.

This keeps the product architecture modular and avoids silently imposing a new licensing model on Cyclone. If a future release wants to ship a Cyclone-branded Portal fork or merge Portal source into a single APK, that must be a deliberate licensing/distribution decision.

Pinned upstream reference used while building this integration:

- Repository: `droidrun/mobilerun-portal`
- Upstream commit inspected: `1b6431dfb90cb797d3cd4147dc4cceefb7dfc047`
- License: AGPL-3.0-or-later

## Architecture

```text
Cyclone Desktop / Automation Studio / Hermes
                    |
                    v
           Cyclone PhoneToolProtocol
                    |
                    v
           Cyclone Mobile Gateway
                    |
                    v
       MobilerunPortalClient adapter
                    |
           authenticated HTTP
                    |
                    v
          Mobilerun Portal on phone
                    |
                    v
 Android Accessibility / screenshot / input
```

Cyclone owns the stable `phone.*` contract. Mobilerun is one implementation backend. Higher layers must never rely directly on Mobilerun endpoint names or response shapes.

The existing Cyclone Android implementation remains available as a native backend. The long-term design is a capability router that can choose the strongest available backend per operation.

```text
PhoneToolProtocol
├── Cyclone native Android backend
└── Mobilerun Portal compatibility backend
```

## Why this approach

Portal already provides mature Android plumbing around accessibility state, screenshots, local HTTP/WebSocket control, reverse connections, files, app launching, input, trigger/event infrastructure and WebRTC streaming.

Cyclone adds the layers Portal does not define for our product:

- persistent Hermes workers
- one stable typed `phone.*` protocol
- semantic selectors and deterministic assertions
- controller ownership / human takeover
- command idempotency and duplicate suppression
- Cyclone Permission Broker integration
- Automation Studio / Skills
- agent memory and recovery
- event-driven zero-token waiting

## Configuration

Install Mobilerun Portal on the Android device and enable its local HTTP server. Obtain the Portal auth token from the Portal app.

In Cyclone's untracked `.env`:

```env
CYCLONE_MOBILERUN_PORTAL_URL=http://192.168.1.42:8080
CYCLONE_MOBILERUN_PORTAL_TOKEN=<portal-token>
```

Never commit the Portal token.

Start the optional gateway alongside the normal Cyclone stack:

```bash
docker compose --env-file .env \
  -f docker/docker-compose.yml \
  -f docker/docker-compose.mobile.yml \
  up -d mobile-gateway
```

The gateway binds only to host loopback at `127.0.0.1:8790` and requires Cyclone's internal API key for control calls.

## Cyclone gateway API

Health:

```text
GET /health
```

Authenticated backend status:

```text
GET /api/v1/mobile/status
X-Cyclone-Internal-Key: ...
```

Execute a stable Cyclone phone tool:

```text
POST /api/v1/mobile/tools/execute
X-Cyclone-Internal-Key: ...
Content-Type: application/json
```

Example:

```json
{
  "id": "cmd-42",
  "tool": "phone.click",
  "params": {
    "selector": {
      "resourceId": "com.example:id/claim",
      "text": "Claim shift",
      "clickable": true
    }
  }
}
```

Input ownership:

```text
POST /api/v1/mobile/ownership
```

```json
{"owner":"human"}
```

or:

```json
{"owner":"agent"}
```

When control returns to the agent, Cyclone requires a fresh `phone.observe` before any mutation is allowed.

## Implemented Portal-backed tools

- `phone.observe`
- `phone.screenshot`
- `phone.find`
- `phone.click`
- `phone.tap`
- `phone.type`
- `phone.replace_text`
- `phone.scroll`
- `phone.swipe`
- `phone.back`
- `phone.home`
- `phone.open_app`
- `phone.launch_intent`
- `phone.get_current_app`
- `phone.get_clipboard`
- `phone.set_clipboard`
- `phone.wait_for`
- `phone.assert`
- `phone.capabilities`

The adapter returns Cyclone-style typed results with:

- command ID
- tool name
- success/error
- timing
- retry count
- before/after screen fingerprints
- typed error code
- normalized payload

## Intentionally unsupported through Portal HTTP v1

The adapter currently returns a typed `CAPABILITY_UNAVAILABLE` instead of pretending these work:

- `phone.long_press`
- `phone.get_notifications`
- `phone.open_notification`
- `phone.share`

Cyclone's native Android node already provides notification/calendar integration. A later backend router should combine native and Portal capabilities instead of forcing every feature through one backend.

## UI normalization

Portal `state_full` is normalized into Cyclone's existing UI model:

```text
package
activity/class
screen dimensions
screen fingerprint
controller owner
nodes[]
```

Nodes include:

- stable Cyclone ID
- tree path
- parent/child IDs
- depth/window ID
- Android class
- inferred role
- text/content description/resource ID
- screen bounds
- clickable / long-clickable
- editable / scrollable
- enabled / selected
- checked / checkable
- focused / focusable
- visible-to-user

Selectors support resource IDs, exact/partial text, content descriptions, class, role, coordinate hit testing, ancestor/descendant text and lightweight fuzzy text.

## Reliability preserved above Portal

Cyclone does not simply proxy Portal commands. The compatibility layer adds:

- serialized phone execution
- command-ID idempotency
- rapid duplicate-action suppression
- semantic element re-resolution before each action
- bounded retries
- deterministic local `wait_for`
- deterministic local `assert`
- before/after screen fingerprints
- human/agent controller ownership
- fresh-observation requirement after takeover

This means Portal can be replaced later without changing Hermes/Automation Studio semantics.

## Next phases

### Phase 2 — reverse WebSocket and event bridge

Use Portal's outbound reverse WebSocket for:

- live device connectivity behind NAT/mobile networks
- app-entered and accessibility events
- notification/device events exposed by Portal
- reconnect/heartbeat handling
- low-latency request/response transport

Cyclone must normalize these into its own event bus rather than leaking Portal event names through the product.

### Phase 3 — live phone view

Use Portal's WebRTC streaming as an optional `Agent Computer` view for:

- live preview in Cyclone Desktop
- human takeover
- return-to-agent flow

Cyclone's controller lock remains authoritative even while video comes from Portal.

### Phase 4 — capability router

Implement routing such as:

```text
notifications/calendar  -> Cyclone native Android APIs
rich accessibility tree -> Portal when healthy
screenshots              -> healthiest available backend
input/actions            -> selected device backend
streaming                -> Portal WebRTC
```

### Phase 5 — distribution decision

Choose explicitly between:

1. external Portal companion + Cyclone gateway (current architecture),
2. a separately distributed Cyclone-branded AGPL Portal fork, or
3. merging AGPL-derived code into a single mobile codebase with the associated source-distribution obligations.

Do not make this decision implicitly through code copying.

## Verification status

Unit tests exercise normalization, selectors, semantic tapping, controller ownership, fresh-observation requirements, command idempotency and screenshot metadata through an HTTP mock transport.

Physical-device verification is still required before marking the backend production-ready:

- install/configure real Portal on Android 14+
- connect local HTTP API
- observe real app tree
- capture screenshot
- semantic click/type/scroll/swipe
- HUMAN takeover lock
- reconnect and network-failure behavior
- multi-OEM tests
- 24-hour soak/battery test
