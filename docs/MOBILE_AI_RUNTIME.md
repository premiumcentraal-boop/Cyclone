# Agent 3 — Cyclone Mobile AI Runtime

Branch: `feature/mobile-ai-runtime`  
Owner: Agent 3 — Hermes AI Runtime / Super-App Intelligence

## Boundary

Agent 3 owns intelligence and coordination above the device and workflow layers:

- Hermes phone-tool exposure
- deterministic-first task routing
- compact phone session context
- AI workflow proposal validation
- interactive AI recovery
- human takeover/checkpoints
- mobile device sessions and command correlation
- privacy-conscious learned knowledge
- multi-device targeting

Agent 3 does **not** call `AccessibilityService` or `AccessibilityNodeInfo` and does not implement a second workflow runner.

The intended stack remains:

```text
Natural language
    ↓
Hermes / Agent 3
    ↓
Skills + AI planning
    ↓
Agent 2 automations / triggers
    ↓
Agent 1 phone.* toolbox
    ↓
Android OS / Accessibility
```

## Live Core mobile protocol

Cyclone Core now exposes an authenticated persistent WebSocket at:

```text
/api/v1/mobile/connect
```

The device bootstrap handshake uses:

```text
Authorization: Bearer <CYCLONE_MOBILE_DEVICE_TOKEN>
X-Cyclone-Device-Id: <stable persisted UUID>
X-Cyclone-Device-Name: <human readable model/name>
X-Cyclone-Device-Platform: android
```

Sessions without the configured token or stable device ID are rejected. Core remains loopback-published by Docker Compose; the phone endpoint must only be deliberately exposed through a trusted local/VPN/reverse-proxy arrangement that preserves authentication.

Core replies with:

```json
{
  "type": "mobile.registered",
  "deviceId": "...",
  "sessionId": "...",
  "heartbeatSeconds": 30
}
```

A command is correlated by ID:

```json
{
  "type": "mobile.command",
  "id": "cmd-...",
  "tool": "phone.click",
  "params": {
    "selector": { "text": "Battery" }
  }
}
```

Agent 1's current `phone-tool-v1` response is supported directly:

```json
{
  "type": "mobile.tool_result",
  "result": {
    "commandId": "cmd-...",
    "tool": "phone.click",
    "ok": true,
    "attempts": 1,
    "beforeFingerprint": "...",
    "afterFingerprint": "...",
    "payload": {},
    "error": null
  }
}
```

The old `mobile.result` shape is still accepted for takeover/backward compatibility during the three-branch merge.

Core normalizes Agent 1's capability array to a device capability map and explicitly tracks the target device for every action. Reconnecting the same device replaces the old live session and fails its pending commands rather than letting stale actions continue.

## Controller ownership

`MobileDeviceRegistry` enforces controller ownership above Agent 1's own phone-side enforcement:

- HUMAN ownership rejects mutating `phone.*` commands in Core.
- Observation-only tools remain available where appropriate.
- returning to AGENT sets `fresh_observation_required=true`;
- no mutation can be sent until a successful `phone.observe` clears the flag.

Ownership messages preserve Agent 1's current `takeover_start` / `takeover_return` compatibility action while also carrying the typed `mobile.control` envelope.

## Hermes integration

Cyclone already exposes an internal MCP server to Hermes. Agent 3 installs phone tools into that same MCP instance at Core startup:

- `list_phone_devices(agent_slug)`
- `phone_observe(agent_slug, device_id)`
- `phone_execute(agent_slug, device_id, tool, arguments, timeout_seconds)`
- `request_phone_takeover(...)`

`phone_execute` never knows Android internals. It routes to the live device registry, which routes a typed `phone.*` request to Agent 1.

The takeover MCP tool is event-driven. It switches control to HUMAN, stores a checkpoint and awaits an `asyncio.Event`. While the human is working, the tool does not screenshot-poll or run an LLM loop. Core's return endpoint performs the fresh observation and resume-condition verification, then releases the event so the Hermes tool call can continue.

Whether a specific Hermes gateway deployment permits an indefinitely suspended MCP tool call without a transport timeout still requires integration verification.

## Deterministic-first task router

`TaskRouter` applies this order:

1. existing automation
2. existing skill
3. bounded interactive Hermes session if exploration is required
4. proposed reusable automation for repeatable tasks

This is intentionally a narrow `AutomationGateway` protocol so the Agent 2 integration can be bound without embedding Agent 2's runner in Core.

## Compact phone session context

`PhoneSessionContext` sends Hermes only bounded state:

- target device ID
- goal
- current package
- screen summary
- up to 24 important elements
- capability states
- controller state
- active automation
- last 12 actions
- up to 20 known skills
- up to 8 relevant memories

It deliberately excludes raw screenshots and complete action history. Screenshots are requested only on a fallback path.

## Interactive AI fallback

`InteractivePhoneExecutor` implements:

```text
phone.find(existing selector)
    ↓ fail
phone.observe()
    ↓
phone.find(existing selector)
    ↓ fail
phone.screenshot()
    ↓
vision/semantic resolver returns selector
    ↓
phone.find(new selector)
    ↓
perform typed action
    ↓
phone.assert(expected state)
```

If the first deterministic selector works, no screenshot or vision call occurs.

## Recovery ladder

`select_recovery_stage` defines the escalation order:

1. deterministic retry
2. fresh observation
3. known persisted recovery
4. Hermes AI recovery
5. human takeover

Only stage 4 is marked as consuming AI tokens.

## AI workflow builder

`AIWorkflowBuilder` accepts structured output from a model adapter and runs it through `WorkflowValidator` before returning a proposal. Model output is never executed as raw text.

The review-document shape is compatible with Agent 2's `AutomationProposalCompiler`, including:

- typed trigger
- `phone_tool`
- waits/conditions/branches/repeats
- variables/parsing/regex
- assertions
- skill invocation
- HTTP/Cyclone event integration boundaries
- `request_human_takeover`
- verification/recovery

Generated proposals are tagged `requiresReview=true` and are never auto-enabled by Agent 3. Agent 2's compiler independently leaves the resulting `AutomationDefinition.enabled=false`.

The validator also:

- rejects unsupported step types;
- rejects literal credential-like fields;
- requires confirmation on steps marked consequential;
- requires structured resume conditions for takeover steps;
- warns when screenshots are used where a UI-tree strategy may be sufficient.

## Learned Skills

`trace_to_skill_candidate` converts a successful interactive trace into a disabled human-review Skill candidate.

It refuses:

- failed trace steps;
- non-`phone.*` actions;
- coordinate-only taps that do not have a stable selector;
- selector-oriented actions without a structured selector.

Observation/screenshot exploration is not copied blindly into the deterministic Skill. Successful screen fingerprints can become verification evidence. Learned Skills are emitted disabled with deterministic recovery fallbacks.

## Mobile memory

`MobileMemoryService` uses Cyclone's existing `VaultMemoryService` for stable app knowledge such as:

- package identifiers
- screen terminology
- selectors
- recovery strategies
- skill hints

It rejects credential/OTP/PIN/password/token-like keys or nested values. Raw credentials are not written to plaintext Obsidian files.

## Audit event vocabulary

Agent 3 defines these task-level events:

- `AI_PLAN_CREATED`
- `SKILL_SELECTED`
- `AUTOMATION_STARTED`
- `PHONE_ACTION`
- `ASSERTION_FAILED`
- `AI_RECOVERY_STARTED`
- `TAKEOVER_REQUIRED`
- `TAKEOVER_COMPLETED`
- `TASK_COMPLETED`

Phone MCP actions and takeover transitions are also written through Core's existing audit repository where available.

## Human Intervention API

Core exposes:

```text
POST /api/v1/mobile/devices/{deviceId}/takeover
GET  /api/v1/mobile/takeovers/{taskId}
POST /api/v1/mobile/takeovers/{taskId}/return
```

The takeover stores task/device/reason/instruction/resume condition, switches to HUMAN, and waits on an event. Return switches provisionally to AGENT, forces `phone.observe`, checks supported structured resume predicates (`package`, `text`, `resourceId`, `contentDescription`), and immediately restores HUMAN ownership if verification fails.

See `docs/HUMAN_INTERVENTION_PROTOCOL.md`.

## Agent 1 integration status

Agent 1 PR #2 now provides the final typed `PhoneToolRegistry` / `PhoneToolExecutor`, `phone-tool-v1` response envelope, capabilities and phone-side ownership enforcement. Agent 3 Core supports that result/capability protocol.

One handshake item remains for the merge: Agent 1's current `BridgeClient` does not yet send a stable device ID header. Agent 3 left an integration review note on PR #2 requesting the three device identity headers while preserving Bearer authentication. This should be resolved during merge rather than by reimplementing Agent 1's bridge on this branch.

## Agent 2 integration status

Agent 2 PR #3 provides `AutomationDefinition`, `SkillDefinition`, `AutomationStore`, `AutomationEventRouter`, `AutomationProposalCompiler`, checkpoints, run history and takeover states. Agent 3's proposal schema aligns with that compiler.

A remote correlated Automation control/query surface is still missing. Agent 3 left an integration review note on PR #3. The desired boundary should expose operations equivalent to:

```text
list automations
list skills
runManual(automationId)
compile + save disabled proposal
resume(runId)
stream run/result events
```

A future wire envelope can use command ID + operation + payload + typed result. Until Agent 2 exposes that boundary, Agent 3 must not pretend it can enumerate or explicitly start local deterministic automations from Core.

## Multi-device design

Every Core/MCP phone action requires an explicit `device_id`. The registry supports multiple simultaneous live devices and replaces only the reconnecting device's old session. Nothing assumes a singleton Android phone.

Durable device enrollment metadata is not yet persisted to Postgres; live session registration is implemented. Durable enrollment should be added after the three branches merge so the schema is shared rather than independently invented.

## Background efficiency

The runtime is designed around events rather than continuous AI polling:

- WebSocket device events
- notification events
- Agent 2 triggers/schedules
- phone-side `wait_for` / `assert`
- human return event

Screenshots and Hermes recovery are escalation paths, not background loops.

## Built vs verified

### Built on this branch

- authenticated Core mobile WebSocket endpoint
- stable device/session abstractions and explicit device targeting
- command IDs, result correlation and timeouts
- Agent 1 `mobile.tool_result` compatibility
- capability advertisement normalization
- controller ownership + fresh-observe gate
- Hermes MCP phone tools
- deterministic-first task router
- compact model context
- screenshot-last interactive fallback
- AI workflow proposal + validator boundary
- recovery escalation ladder
- event-driven human takeover coordinator
- structured takeover resume verification
- learned-Skill candidate pipeline
- privacy-conscious durable mobile memory adapter
- mobile task audit vocabulary
- Core REST takeover/tool/device surfaces
- branch-scoped Core test + Android assemble CI definition
- unit tests for the above pure/runtime contracts

### Verified by source inspection / deterministic tests once CI passes

The branch CI is configured to run all `tests/test_mobile_*.py` tests and assemble the Android debug APK. CI status is recorded in the Agent 3 PR/handoff once a pull request run completes.

### Not physically verified

- real Android 14+ device connection to Core
- phone reconnect over a real network
- multi-device simultaneous control
- real `phone.*` end-to-end action through Hermes MCP
- screenshot/vision recovery on a real app
- human takeover card/UI on-device
- MCP suspension duration during a long human takeover
- Agent 2 deterministic automation invocation from Core (remote surface pending)
- process-restart persistence of takeover checkpoint (production store binding pending)
- 24-hour background/battery reliability

Do not mark these as hardware verified without objective device evidence.
