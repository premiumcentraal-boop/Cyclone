# Cyclone Human Intervention Protocol

Owner: Agent 3 — Hermes AI Runtime

## Purpose

Human takeover is a first-class suspended task state, not an AI retry loop.

Use takeover when the agent reaches a state that should not be solved by unattended automation, including:

- login requiring user action
- CAPTCHA
- 2FA / OTP
- identity verification
- Android permission prompt requiring the user
- ambiguous consequential action
- unexpected high-risk state
- deterministic and AI recovery both exhausted

Cyclone must not design takeover as a way to bypass verification or security controls.

## State machine

```text
AGENT_RUNNING
    ↓
TAKEOVER_REQUIRED
    ↓
save checkpoint
    ↓
controller = HUMAN
    ↓
WAITING_FOR_HUMAN
    ↓ user presses Return to Cyclone
controller = AGENT (provisional)
    ↓
phone.observe
    ↓
verify resume condition
    ├─ fail → controller = HUMAN → WAITING_FOR_HUMAN
    └─ pass → TAKEOVER_COMPLETED → resume task
```

## Takeover checkpoint

A checkpoint contains:

```text
checkpoint_id
task_id
device_id
reason
current_app
user_instruction
resume_condition
created_at
```

`resume_condition` is structured. The Core coordinator currently verifies:

- exact `package`
- exact node `text`
- exact `resourceId`
- exact `contentDescription`

More predicates can be added through the verifier interface without changing the state machine.

## Zero-token waiting

`HumanInterventionCoordinator.wait_for_return()` awaits an `asyncio.Event`.

During the wait it does not:

- call Hermes repeatedly;
- request screenshots;
- poll Accessibility state;
- consume model tokens in an LLM loop.

The Hermes MCP `request_phone_takeover` tool uses the same coordinator and remains suspended on this event after it has switched the device to HUMAN. The user-facing return action releases the event only after observation/resume verification succeeds.

Transport-level MCP timeout behavior still needs an end-to-end Hermes integration test. If the Hermes gateway imposes a shorter tool timeout than a realistic human takeover, the same checkpoint/event state must be resumed by a new Hermes run rather than replaced with polling.

## Controller enforcement

Controller ownership is enforced twice:

1. Cyclone Core `MobileDeviceRegistry` blocks mutating commands while HUMAN owns the device.
2. Agent 1's phone toolbox independently rejects mutating commands while HUMAN owns the device.

This defense in depth prevents a queued or buggy Core command from bypassing the phone-side lock.

When ownership returns to AGENT, both layers require a fresh observation before mutations continue.

## API

### Request takeover

```text
POST /api/v1/mobile/devices/{deviceId}/takeover
X-Cyclone-Internal-Key: <internal integration key>
```

Example body:

```json
{
  "task_id": "task-123",
  "reason": "Google sign-in requires verification",
  "current_app": "com.google.android.gms",
  "user_instruction": "Complete verification, then return to Cyclone.",
  "resume_condition": {
    "package": "com.example.target"
  }
}
```

### Read pending takeover

```text
GET /api/v1/mobile/takeovers/{taskId}
```

This is intended for the local Cyclone UI/takeover card. Core remains loopback-published by default.

### Return to agent

```text
POST /api/v1/mobile/takeovers/{taskId}/return
```

Return does **not** immediately authorize new actions. Core first:

1. sets AGENT provisionally;
2. sends `phone.observe`;
3. checks the checkpoint resume condition;
4. restores HUMAN ownership on failure;
5. deletes the checkpoint and releases the waiting event only on success.

## User-facing card contract

Agent 2/UI can render a takeover card from the checkpoint fields:

```text
Agent needs you

Google sign-in requires verification.
Complete verification, then return to Cyclone.

[Take over]
```

After the user completes the requested action:

```text
[Return to agent]
```

The UI should show a resume-verification error if Core returns HTTP 409 and leave the device in HUMAN state.

The visual card itself is not implemented on Agent 3's branch because Agent 2 owns the Automation Studio/mobile UI.

## Consequential actions

Agent 2 already represents `confirmationRequired` and `WAITING_FOR_HUMAN`. Agent 3-generated workflows mark consequential steps as requiring confirmation before Agent 2 compiles them.

Interactive Hermes sessions should request takeover instead of guessing when an action is ambiguous or high-risk. Examples include sending an uncertain message, submitting an irreversible form, deleting data or making a purchase.

## Checkpoint persistence

`CheckpointStore` is an interface. Agent 3 currently wires an in-memory store so the Core protocol and zero-token state machine are functional and testable in one Core process.

Production restart persistence should bind this interface to Agent 2's durable checkpoint/run store or a shared Postgres representation after the three branches merge. Agent 3 intentionally does not create a competing workflow/checkpoint database.

Until that binding exists, a Core process restart can lose a Core-side takeover checkpoint. This is a known blocker and must not be described as restart-resilient.

## Audit events

The takeover path uses:

```text
TAKEOVER_REQUIRED
TAKEOVER_COMPLETED
```

with task ID, device ID, reason/checkpoint metadata and the fresh observation fingerprint where available. Phone actions remain auditable as `PHONE_ACTION`.

## Acceptance checklist

- [ ] request takeover while AGENT owns phone
- [ ] verify phone-side controller becomes HUMAN
- [ ] verify mutating Core command is rejected
- [ ] verify mutating Agent 1 tool is independently rejected
- [ ] verify observation remains available where intended
- [ ] verify no screenshot/model polling occurs while waiting
- [ ] press Return to agent
- [ ] verify fresh `phone.observe` is mandatory
- [ ] verify a mismatching resume condition returns device to HUMAN
- [ ] verify a matching resume condition releases the wait event
- [ ] verify no queued pre-takeover action fires afterward
- [ ] verify long human wait against actual Hermes MCP timeout
- [ ] verify restart behavior after durable checkpoint binding is added

No physical-device item above is marked complete on this branch yet.
