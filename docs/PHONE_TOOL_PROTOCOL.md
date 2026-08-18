# Cyclone Phone Tool Protocol v1

Branch: `feature/mobile-phone-toolbox-agent1`

This document defines the Android device-control contract consumed by future Cyclone Automation Studio and Hermes integrations. The Android Accessibility implementation is intentionally hidden behind typed `phone.*` tools.

## Design goals

- Structured UI data first; screenshots/vision are fallback.
- Every mutating action is blocked while the human owns the device.
- Returning control to the agent requires a fresh `phone.observe` before any queued action can run.
- Every command has an ID and returns timing, attempts, before/after screen fingerprints, payload, and typed error information.
- Local waits/assertions prevent LLM polling.
- Duplicate action suppression reduces accidental double taps/submissions.
- No arbitrary shell/root access exists in this layer.

## Request envelope

```json
{
  "id": "cmd-123",
  "tool": "phone.click",
  "params": {
    "selector": {
      "resourceId": "com.example:id/claim",
      "text": "Claim",
      "clickable": true
    },
    "retries": 1,
    "waitForChangeMs": 900,
    "expect": {
      "type": "text_contains",
      "text": "Claimed"
    }
  }
}
```

## Result envelope

```json
{
  "commandId": "cmd-123",
  "tool": "phone.click",
  "ok": true,
  "startedAtMs": 0,
  "finishedAtMs": 0,
  "durationMs": 0,
  "attempts": 1,
  "beforeFingerprint": "...",
  "afterFingerprint": "...",
  "payload": {
    "performed": true,
    "screenChanged": true
  },
  "error": null
}
```

Typed failures include `HUMAN_HAS_CONTROL`, `FRESH_OBSERVATION_REQUIRED`, `ELEMENT_NOT_FOUND`, `ACTION_FAILED`, `TIMEOUT`, `ASSERTION_FAILED`, `DUPLICATE_ACTION`, `APP_NOT_FOUND`, `NOTIFICATION_NOT_FOUND`, and `SECURITY_RESTRICTION`.

## Normalized observation

`phone.observe` returns a flat UI snapshot rather than raw `AccessibilityNodeInfo` objects.

Each node includes:

- stable node ID
- path
- parent ID / child IDs
- depth
- window ID
- class
- inferred role
- text
- content description
- Android resource ID
- bounds
- clickable / long-clickable
- editable
- scrollable
- enabled
- selected
- checked / checkable
- focused / focusable
- visible-to-user

The snapshot also includes package/class, screen size, window metadata, controller owner, timestamp, and a privacy-conscious screen fingerprint.

## Selectors

Selectors may combine:

```json
{
  "resourceId": "com.example:id/button",
  "text": "Exact text",
  "textContains": "partial text",
  "contentDescription": "Exact description",
  "contentDescriptionContains": "partial description",
  "class": "android.widget.Button",
  "role": "button",
  "ancestorText": "Shift details",
  "descendantText": "Claim",
  "x": 500,
  "y": 1200,
  "relativeToText": "Shift details",
  "relativeDirection": "below",
  "fuzzyText": "claim available shift",
  "minFuzzyScore": 0.72,
  "clickable": true,
  "editable": false,
  "scrollable": false
}
```

Resolution order should prefer stable deterministic properties such as resource IDs and exact text before structural/relative/fuzzy matching. Screenshot/vision is outside this v1 selector engine and should only be invoked after structured resolution fails.

## Tool registry

Current tools:

- `phone.observe`
- `phone.screenshot`
- `phone.find`
- `phone.click`
- `phone.long_press`
- `phone.tap`
- `phone.type`
- `phone.replace_text`
- `phone.scroll`
- `phone.swipe`
- `phone.back`
- `phone.home`
- `phone.open_app`
- `phone.open_notification`
- `phone.wait_for`
- `phone.assert`
- `phone.get_notifications`
- `phone.get_current_app`
- `phone.get_clipboard`
- `phone.set_clipboard`
- `phone.share`
- `phone.launch_intent`
- `phone.capabilities`

`PhoneToolRegistry` is the authoritative in-code registry. Higher layers should call `PhoneToolExecutor`; they should not reach into `CycloneAccessibilityService` directly.

## Local wait/assert conditions

Supported v1 conditions:

```json
{ "type": "selector_exists", "selector": { "text": "Claim" } }
{ "type": "selector_absent", "selector": { "text": "Loading" } }
{ "type": "package_equals", "package": "com.example" }
{ "type": "text_contains", "text": "Success" }
{ "type": "fingerprint_changed", "from": "old-fingerprint" }
```

`phone.wait_for` executes locally on the phone and does not require repeated Hermes/model calls.

## Capability matrix

| Capability | Non-root Android 14+ | Runtime status source | Notes |
|---|---:|---|---|
| Accessibility UI observation | Yes | Accessibility service connection | User must enable service |
| Semantic element actions | Yes | Accessibility service connection | Uses node actions first |
| Gesture tap/swipe/long press | Yes | Accessibility service connection | Accessibility gesture dispatch |
| Screenshot | Yes | Accessibility + API level | Accessibility screenshot path |
| Notification reading | Yes | Notification access | Explicit user grant |
| Open notification | Usually | Retained notification PendingIntent | Only if notification exposes content intent |
| Calendar read | Yes | READ_CALENDAR | Explicit permission |
| Clipboard | Yes with OS caveats | Android clipboard service | Android may restrict reads in some states |
| App launch | Yes | Package manager | App must expose launcher activity |
| Safe URI intent launch | Yes | Android intents | Allowlisted schemes only |
| MediaProjection | Not in v1 | Marked unsupported | Not needed for primary screenshot path |
| Battery optimization exemption | Optional | PowerManager | Reported as status, never assumed |
| Root/system private data | No | N/A | Intentionally outside Agent 1 scope |

## Human controller lock

State is either `AGENT` or `HUMAN`.

When the controller switches to `HUMAN`, all mutating tools are rejected. When it switches back to `AGENT`, `requireFreshObservation` becomes true. The first `phone.observe` clears the gate. This invalidates queued selectors/coordinates and prevents stale actions firing immediately after takeover.

## Reliability behavior

- selectors are resolved from a fresh snapshot before action
- live node path/class/resource/bounds are checked before click
- mutating actions can retry a bounded number of times
- command IDs are idempotent within the in-process result cache
- rapid duplicate action signatures are suppressed
- post-action fingerprint changes are observed when requested
- optional `expect` conditions verify postconditions
- every execution is added to a bounded local command audit record without raw screenshot contents or credentials

## Vision fallback contract

Agent 1 does not implement a visual model. A future visual fallback should use:

1. `phone.observe`
2. `phone.find`
3. deterministic/structural selector strategies
4. `phone.screenshot` only when structured UI is insufficient
5. vision returns a selector/region, then the normal typed tool performs the action

This keeps AI inference out of ordinary deterministic interactions.
